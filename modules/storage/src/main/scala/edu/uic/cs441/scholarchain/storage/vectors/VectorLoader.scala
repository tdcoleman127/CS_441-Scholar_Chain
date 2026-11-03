package edu.uic.cs441.scholarchain.storage.vectors

import edu.uic.cs441.scholarchain.core.ids.{IdFormat, IdKind}
import edu.uic.cs441.scholarchain.core.model.{Provenance, SourceType}
import edu.uic.cs441.scholarchain.storage.kv.{Keys, RejectRecord, RunLedgerTable}
import edu.uic.cs441.scholarchain.storage.{Retry, RetryPolicy, Sleeper, StorageError}

/** Outcome of one vector load. `rejected` is also written to the RunLedger as REJECT items. */
final case class VectorLoadReport(
    written: Int,
    collapsedDuplicates: Int,
    rejected: List[(String, String)],
    batches: Int
)

/**
 * Loads vectors into one index.
 *
 *   1. Validate each record: `chunk:v1` key, non-empty finite data of `dimension` floats, and
 *      metadata per [[VectorMetadata.validate]].
 *   2. Duplicate keys: identical records collapse to one; records that share a key but differ are
 *      all rejected (the producer must decide), never last-writer-wins.
 *   3. Write in batches of `batchSize` (1..500), each batch under the bounded retry policy.
 *   4. Record every reject, each with its own provenance, as a RunLedger
 *      `REJECT#vector:<index>#<key>#<digest>` item; replays in any input order are
 *      `AlreadyPresent`.
 *
 * PutVectors is an upsert keyed by the stable vector key, so replaying the whole load yields the
 * same index state. A batch that fails after retries stops the load and returns the error; the
 * batches already written are safe to replay.
 */
final class VectorLoader(
    store: VectorStore,
    ledger: RunLedgerTable,
    dimension: Int,
    batchSize: Int = 100,
    policy: RetryPolicy = RetryPolicy.Default,
    sleeper: Sleeper = Sleeper.Real
):
  require(batchSize >= 1 && batchSize <= VectorStore.MaxBatchSize, "batchSize must be in 1..500")
  require(dimension >= 1, "dimension must be positive")

  private def problems(r: VectorRecord): List[String] =
    val keyErr =
      if IdFormat.isValid(IdKind.Chunk, r.key) then Nil else List("key is not a chunk:v1 ID")
    val dataErr =
      if r.data.size != dimension then List(s"dimension ${r.data.size} != $dimension")
      else if r.data.exists(f => f.isNaN || f.isInfinite) then List("data has non-finite values")
      else Nil
    keyErr ++ dataErr ++ VectorMetadata.validate(r.metadata)

  def load(
      runId: String,
      index: VectorIndex,
      records: Seq[VectorRecord]
  ): Either[StorageError, VectorLoadReport] =
    val scope                 = s"vector:${index.wire}"
    val (valid, invalidRecs)  = records.partition(problems(_).isEmpty)
    val invalidRejects        = invalidRecs.map(r => (r, problems(r).mkString("; ")))
    val grouped               = valid.groupBy(_.key).toList.sortBy(_._1)
    val (unique, conflicting) = grouped.partition((_, rs) => rs.distinct.size == 1)
    val conflictRejects       = conflicting.flatMap((_, rs) =>
      rs.distinct.map(r => (r, "conflicting duplicate key in load"))
    )
    val toWrite   = unique.map((_, rs) => rs.head)
    val collapsed = unique.map((_, rs) => rs.size - 1).sum

    def provenance(r: VectorRecord): Provenance =
      VectorMetadata
        .provenanceOf(r.metadata)
        .getOrElse(Provenance(SourceType.ScholarChainPipeline, Keys.safeOrHash(r.key), runId))

    // One RejectRecord per distinct (key, reason, provenance); each has its own ledger item (see
    // RejectRecord.digest), and sorting by (key, digest) makes the order independent of input order.
    val rejects = (invalidRejects ++ conflictRejects)
      .map((r, reason) => RejectRecord(runId, scope, r.key, reason, provenance(r)))
      .distinct
      .sortBy(r => (r.key, RejectRecord.digest(r)))
      .toList

    for
      _ <- rejects.foldLeft[Either[StorageError, Unit]](Right(())) { (acc, r) =>
        acc.flatMap(_ => ledger.recordReject(r).map(_ => ()))
      }
      batches = toWrite.grouped(batchSize).toList
      _ <- batches.foldLeft[Either[StorageError, Unit]](Right(())) { (acc, batch) =>
        acc.flatMap(_ => Retry.run(policy, sleeper)(store.putBatch(index, batch)))
      }
    yield VectorLoadReport(
      toWrite.size,
      collapsed,
      rejects.map(r => (r.key, r.reason)),
      batches.size
    )
