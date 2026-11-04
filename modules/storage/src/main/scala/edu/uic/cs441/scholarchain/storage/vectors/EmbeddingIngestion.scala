package edu.uic.cs441.scholarchain.storage.vectors

import edu.uic.cs441.scholarchain.core.ids.{CanonicalHash, StableIds}
import edu.uic.cs441.scholarchain.core.model.{DocumentType, Provenance, YearWindow}
import edu.uic.cs441.scholarchain.storage.kv.{Keys, RejectRecord, RunLedgerTable, RunStep}
import edu.uic.cs441.scholarchain.storage.objectstore.{ObjectKey, ObjectStore, S3Prefix}
import edu.uic.cs441.scholarchain.storage.{Retry, RetryPolicy, Sleeper, StorageError}
import java.nio.charset.StandardCharsets
import upickle.default.{write, ReadWriter}

/**
 * One semantic-summary record to embed (storage-local input contract; core has no summary record
 * yet). `textHash` must equal `StableIds.textHash(text)`. `entityId` is the stable ID of the
 * researcher (or other entity) the summary describes. Producers that emit several windows of the
 * same entity and document type must make `sourceVersion` window specific, because the vector key
 * is the core chunk ID, which does not include the window.
 */
final case class SemanticSummary(
    entityId: String,
    docType: DocumentType,
    window: Option[YearWindow],
    sourceVersion: String,
    text: String,
    textHash: String,
    provenance: Provenance
)

/**
 * Caller-supplied embedding function. Storage holds no model client: the RAG layer passes its
 * embedder as a plain function, with the model version it stamps on every vector. `embed` gets the
 * texts of one batch and returns one vector per text, in order.
 */
final case class EmbeddingSupplier(
    modelVersion: String,
    dimension: Int,
    embed: Seq[String] => Either[String, Seq[Vector[Float]]]
)

/** Long text stored in S3 under `semantic-context/`, with its provenance. */
final case class SemanticContextDoc(
    vectorKey: String,
    entityId: String,
    docType: String,
    windowStart: Option[Int],
    windowEnd: Option[Int],
    sourceVersion: String,
    textHash: String,
    text: String,
    provenance: Provenance,
    ingestRunId: String
) derives ReadWriter

/** Outcome of one ingestion. `failed` items are retried by replaying the same input. */
final case class IngestionReport(
    input: Int,
    collapsedDuplicates: Int,
    rejected: List[(String, String)],
    skippedUnchanged: Int,
    written: Int,
    failed: List[(String, String)],
    notAttempted: Int,
    batches: Int,
    putAttempts: Int,
    aborted: Option[String]
)

object EmbeddingIngestion:
  /** Artifact schema version of the `semantic-context/<docType>/v1/...` objects. */
  val ContextSchemaVersion: Int = 1

  /**
   * Routing table from document-type wire value to vector index. Routing is on the wire value, not
   * on the enum case, so a document type that core adds later never makes this a partial match: a
   * wire value listed here routes as soon as core defines it, and any other value is a typed
   * rejection ([[routeWire]] returns Left), never a MatchError.
   */
  val Routes: Map[String, VectorIndex] = Map(
    "researcher-profile"    -> VectorIndex.ResearcherProfile,
    "collaboration-summary" -> VectorIndex.CollaborationCommunity,
    "work-abstract"         -> VectorIndex.PaperAbstract,
    "work-metadata"         -> VectorIndex.PaperAbstract,
    "evidence-path"         -> VectorIndex.CollaborationPath,
    "topic-summary"         -> VectorIndex.TopicInstitutionSummary,
    "institution-summary"   -> VectorIndex.TopicInstitutionSummary
  )

  /** Index for a document-type wire value; Left (the rejection reason) when none is routed. */
  def routeWire(wire: String): Either[String, VectorIndex] =
    Routes.get(wire).toRight(s"no vector index is routed for docType '$wire'")

  /** Index for a core document type. Total: an unrouted type is Left, never a MatchError. */
  def indexFor(docType: DocumentType): Either[String, VectorIndex] = routeWire(docType.wire)

  /** Reject scope of summaries whose document type routes to no index. */
  val UnroutedScope: String = "vector-ingest:unrouted"

  /** Vector key: the core chunk ID (no ordinal: chunking is deferred). */
  def vectorKey(s: SemanticSummary): String =
    StableIds.chunkId(s.entityId, s.sourceVersion, s.docType, None, s.textHash)

  def rejectScope(index: VectorIndex): String  = s"vector-ingest:${index.wire}"
  def failureScope(index: VectorIndex): String = s"vector-ingest-failure:${index.wire}"

/**
 * Manifest-driven embedding ingestion (no queue). For each index, in order:
 *
 *   1. Route every summary to its index by document-type wire value (`router`, default
 *      [[EmbeddingIngestion.routeWire]]), validate it, and build its metadata. Unrouted and invalid
 *      summaries are rejected and recorded with their provenance (unrouted ones under
 *      `vector-ingest:unrouted`); none is embedded.
 *   2. Duplicate vector keys: identical summaries collapse; differing ones are all rejected.
 *   3. Look up stored metadata of the keys (GetVectors, at most 100 keys per call). A key whose
 *      stored `textHash` and `modelVersion` both equal the current ones is skipped and is never
 *      embedded. A changed text yields a new key; a changed model version re-embeds and rewrites
 *      the same key.
 *   4. In batches of `batchSize`: embed (caller function), write each text to S3 (create if absent,
 *      so a vector never points at missing text), then PutVectors under bounded retry.
 *   5. Record rejects and failures (with attempt counts) as RunLedger REJECT items, and one STEP
 *      item per batch whose name carries the batch digest, written and failed counts, and
 *      PutVectors attempts (so a replay never changes an existing STEP item; see R-07-7).
 *
 * A failed batch does not stop the run; the run aborts after `maxConsecutiveFailedBatches` batches
 * in a row write nothing. Replaying the same input is the retry path: written keys are skipped,
 * failed ones are attempted again. Nothing is ever deleted.
 */
final class EmbeddingIngestion(
    vectors: VectorStore,
    objects: ObjectStore,
    ledger: RunLedgerTable,
    batchSize: Int = 100,
    policy: RetryPolicy = RetryPolicy.Default,
    sleeper: Sleeper = Sleeper.Real,
    maxConsecutiveFailedBatches: Int = 3,
    router: String => Either[String, VectorIndex] = EmbeddingIngestion.routeWire
):
  import EmbeddingIngestion.*

  /** Reject scope of a summary: its index scope, or [[UnroutedScope]] when it routes nowhere. */
  private def scopeOf(s: SemanticSummary): String =
    router(s.docType.wire).fold(_ => UnroutedScope, rejectScope)

  require(batchSize >= 1 && batchSize <= VectorStore.MaxBatchSize, "batchSize must be in 1..500")
  require(maxConsecutiveFailedBatches >= 1, "maxConsecutiveFailedBatches must be positive")

  final private case class Planned(
      summary: SemanticSummary,
      key: String,
      index: VectorIndex,
      textKey: ObjectKey,
      metadata: Map[String, MetaValue]
  )

  private def plan(
      runId: String,
      modelVersion: String,
      s: SemanticSummary
  ): Either[String, Planned] =
    router(s.docType.wire).flatMap(index => planRouted(runId, modelVersion, s, index))

  private def planRouted(
      runId: String,
      modelVersion: String,
      s: SemanticSummary,
      index: VectorIndex
  ): Either[String, Planned] =
    val key = vectorKey(s)
    val pre = List(
      Option.when(s.text.trim.isEmpty)("text is blank"),
      Option.when(StableIds.textHash(s.text) != s.textHash)("textHash does not match text"),
      Option.when(s.sourceVersion.trim.isEmpty)("sourceVersion is blank"),
      s.window.filter(w => w.startYear > w.endYear).map(_ => "window start exceeds end")
    ).flatten
    if pre.nonEmpty then Left(pre.mkString("; "))
    else
      ObjectKey
        .versioned(
          S3Prefix.SemanticContext,
          s.docType.wire,
          ContextSchemaVersion,
          runId,
          s"$key.json"
        )
        .left
        .map(_.message)
        .flatMap { textKey =>
          val meta = VectorMetadata(
            s.entityId,
            s.docType,
            s.window.map(w => (w.startYear, w.endYear)),
            s.provenance,
            modelVersion,
            s.textHash,
            textKey
          ).toMap
          VectorMetadata.validate(meta) match
            case Nil      => Right(Planned(s, key, index, textKey, meta))
            case problems => Left(problems.mkString("; "))
        }

  private def unchanged(stored: Map[String, MetaValue], p: Planned, modelVersion: String) =
    stored.get("textHash").contains(MetaValue.Str(p.summary.textHash)) &&
      stored.get("modelVersion").contains(MetaValue.Str(modelVersion))

  private def contextBytes(runId: String, p: Planned): Array[Byte] =
    val s = p.summary
    write(SemanticContextDoc(
      p.key,
      s.entityId,
      s.docType.wire,
      s.window.map(_.startYear),
      s.window.map(_.endYear),
      s.sourceVersion,
      s.textHash,
      s.text,
      s.provenance,
      runId
    )).getBytes(StandardCharsets.UTF_8)

  private def record(
      runId: String,
      scope: String,
      items: Seq[(SemanticSummary, String, String)]
  ): Either[StorageError, Unit] =
    items
      .map((s, key, reason) => RejectRecord(runId, scope, key, reason, s.provenance))
      .distinct
      .sortBy(r => (r.key, RejectRecord.digest(r)))
      .foldLeft[Either[StorageError, Unit]](Right(()))((acc, r) =>
        acc.flatMap(_ => ledger.recordReject(r).map(_ => ()))
      )

  private def checkSupplier(e: EmbeddingSupplier): Either[StorageError, Unit] =
    if e.modelVersion.trim.isEmpty then
      Left(StorageError.InvalidInput("modelVersion", "must not be blank"))
    else if e.dimension < 1 then Left(StorageError.InvalidInput("dimension", "must be positive"))
    else Right(())

  /** Ingests `summaries` for run `runId`. Left only when the ledger or a lookup is unusable. */
  def ingest(
      runId: String,
      summaries: Seq[SemanticSummary],
      embedding: EmbeddingSupplier
  ): Either[StorageError, IngestionReport] =
    val mv = embedding.modelVersion
    for
      _ <- Keys.run(runId)
      _ <- checkSupplier(embedding)
      planned = summaries.map(s => s -> plan(runId, mv, s))
      invalid = planned.collect { case (s, Left(reason)) => (s, vectorKey(s), reason) }
      grouped = planned.collect { case (_, Right(p)) => p }.groupBy(_.key).toList.sortBy(_._1)
      (unique, conflicting) = grouped.partition((_, ps) => ps.distinct.size == 1)
      conflicts             = conflicting.flatMap((_, ps) =>
        ps.distinct.map(p => (p.summary, p.key, "conflicting duplicate key in input"))
      )
      collapsed = unique.map((_, ps) => ps.size - 1).sum
      _ <- invalid.groupBy(t => scopeOf(t._1)).toList.sortBy(_._1).foldLeft[
        Either[StorageError, Unit]
      ](Right(()))((acc, kv) => acc.flatMap(_ => record(runId, kv._1, kv._2)))
      _ <- conflicts.groupBy(t => scopeOf(t._1)).toList.sortBy(_._1).foldLeft[
        Either[StorageError, Unit]
      ](Right(()))((acc, kv) => acc.flatMap(_ => record(runId, kv._1, kv._2)))
      byIndex = unique.map(_._2.head).groupBy(_.index).toList.sortBy(_._1.ordinal)
      todo <- byIndex.foldLeft[Either[StorageError, List[(VectorIndex, List[Planned], Int)]]](
        Right(Nil)
      ) { case (acc, (index, ps)) =>
        acc.flatMap(done => pending(index, ps.sortBy(_.key), mv).map(t => done :+ t))
      }
      report <- writeAll(runId, todo, embedding)
    yield report.copy(
      input = summaries.size,
      collapsedDuplicates = collapsed,
      rejected = (invalid ++ conflicts).map((_, k, r) => (k, r)).distinct.sortBy(identity).toList
    )

  /** Keys of one index that still need embedding, plus the count skipped as unchanged. */
  private def pending(
      index: VectorIndex,
      ps: List[Planned],
      modelVersion: String
  ): Either[StorageError, (VectorIndex, List[Planned], Int)] =
    ps.grouped(VectorStore.MaxGetKeys)
      .foldLeft[Either[StorageError, Map[String, Map[String, MetaValue]]]](Right(Map.empty)) {
        (acc, chunk) =>
          acc.flatMap(found =>
            Retry.run(policy, sleeper)(vectors.getMetadata(index, chunk.map(_.key))).map(found ++ _)
          )
      }
      .map { stored =>
        val (skip, keep) =
          ps.partition(p => stored.get(p.key).exists(unchanged(_, p, modelVersion)))
        (index, keep, skip.size)
      }

  final private case class BatchOutcome(
      written: Int,
      failed: List[(Planned, String)],
      attempts: Int
  )

  private def runBatch(
      runId: String,
      index: VectorIndex,
      batch: List[Planned],
      embedding: EmbeddingSupplier
  ): BatchOutcome =
    embedding.embed(batch.map(_.summary.text)) match
      case Left(msg) => BatchOutcome(0, batch.map(p => p -> s"embedding failed: $msg"), 0)
      case Right(vs) if vs.size != batch.size =>
        BatchOutcome(
          0,
          batch.map(p => p -> s"embedding returned ${vs.size} vectors for ${batch.size} texts"),
          0
        )
      case Right(vs) =>
        val paired           = batch.zip(vs.toList)
        val (good, badShape) = paired.partition((_, v) =>
          v.size == embedding.dimension && !v.exists(f => f.isNaN || f.isInfinite)
        )
        val shapeFailures = badShape.map((p, v) =>
          p ->
            s"embedding has ${v.size} dimensions or non-finite values (want ${embedding.dimension})"
        )
        val texts = good.map { (p, v) =>
          val out = Retry.runCounted(policy, sleeper)(
            objects.putIfAbsent(p.textKey, contextBytes(runId, p), "application/json")
          )
          (p, v, out)
        }
        val textFailures = texts.collect {
          case (p, _, out) if out.result.isLeft =>
            p ->
              s"semantic-context write failed after ${out.attempts} attempts: ${out.result.left.toOption.get.message}"
        }
        val ready = texts.collect {
          case (p, v, out) if out.result.isRight =>
            VectorRecord(p.key, v, p.metadata) -> p
        }
        if ready.isEmpty then BatchOutcome(0, shapeFailures ++ textFailures, 0)
        else
          val put = Retry.runCounted(policy, sleeper)(vectors.putBatch(index, ready.map(_._1)))
          put.result match
            case Right(_)  => BatchOutcome(ready.size, shapeFailures ++ textFailures, put.attempts)
            case Left(err) =>
              val putFailures = ready.map((_, p) =>
                p -> s"PutVectors failed after ${put.attempts} attempts: ${err.message}"
              )
              BatchOutcome(0, shapeFailures ++ textFailures ++ putFailures, put.attempts)

  private def writeAll(
      runId: String,
      todo: List[(VectorIndex, List[Planned], Int)],
      embedding: EmbeddingSupplier
  ): Either[StorageError, IngestionReport] =
    val empty = IngestionReport(0, 0, Nil, todo.map(_._3).sum, 0, Nil, 0, 0, 0, None)
    val work  = todo.flatMap((index, ps, _) => ps.grouped(batchSize).map(b => (index, b)))
    work
      .foldLeft[Either[StorageError, (IngestionReport, Int)]](Right((empty, 0))) {
        case (Left(e), _)                                                => Left(e)
        case (Right((rep, streak)), (_, batch)) if rep.aborted.isDefined =>
          Right((rep.copy(notAttempted = rep.notAttempted + batch.size), streak))
        case (Right((rep, streak)), (index, batch)) =>
          val out    = runBatch(runId, index, batch, embedding)
          val digest = CanonicalHash.hashFields(batch.map(_.key).sorted).take(16)
          val f      = out.failed.size
          val status =
            if f == 0 then "written" else if out.written == 0 then "failed" else "partial"
          val step = RunStep(
            runId,
            s"vector-ingest:${index.wire}:batch:$digest:w${out.written}:f$f:a${out.attempts}",
            s"keys=${batch.size}"
          )
          val newStreak = if out.written == 0 && f > 0 then streak + 1 else 0
          for
            _ <- record(
              runId,
              failureScope(index),
              out.failed.map((p, reason) => (p.summary, p.key, reason))
            )
            _ <- ledger.putStep(step, status)
          yield
            val next = rep.copy(
              written = rep.written + out.written,
              failed = rep.failed ++ out.failed.map((p, r) => (p.key, r)),
              batches = rep.batches + 1,
              putAttempts = rep.putAttempts + out.attempts
            )
            val aborted =
              if newStreak >= maxConsecutiveFailedBatches then
                Some(s"$newStreak consecutive batches wrote nothing; remaining batches skipped")
              else None
            (next.copy(aborted = aborted), newStreak)
      }
      .map(_._1)
