package edu.uic.cs441.scholarchain.storage

import edu.uic.cs441.scholarchain.core.ids.StableIds
import edu.uic.cs441.scholarchain.core.model.DocumentType
import edu.uic.cs441.scholarchain.storage.fakes.{InMemoryKeyValueStore, InMemoryVectorStore}
import edu.uic.cs441.scholarchain.storage.kv.RunLedgerTable
import edu.uic.cs441.scholarchain.storage.objectstore.ObjectKey
import edu.uic.cs441.scholarchain.storage.vectors.*

class VectorLoaderSuite extends munit.FunSuite:
  import Fixtures.*

  private val dim = 4

  private def record(i: Int, data: Vector[Float] = Vector(1f, 0f, 0f, 0f)): VectorRecord =
    val text     = s"profile text $i"
    val textHash = StableIds.textHash(text)
    val entity   = researcherId(s"homepages/p$i")
    val key      = StableIds.chunkId(entity, "v1", DocumentType.ResearcherProfile, None, textHash)
    val meta     = VectorMetadata(
      entity,
      DocumentType.ResearcherProfile,
      Some((2000, 2020)),
      prov(s"r/$i"),
      "embed-v1",
      textHash,
      ObjectKey.parse(s"semantic-context/researcher-profile/v1/p$i.txt").toOption.get
    )
    VectorRecord(key, data, meta.toMap)

  private def setup(batch: Int = 100, policy: RetryPolicy = RetryPolicy.NoRetry) =
    val vs  = InMemoryVectorStore()
    val kv  = InMemoryKeyValueStore()
    val led = RunLedgerTable(kv, RetryPolicy.NoRetry)
    (vs, led, VectorLoader(vs, led, dim, batch, policy, (_: Long) => ()))

  test("metadata: compact filterable fields plus non-filterable textRef") {
    val m = record(1).metadata
    assertEquals(VectorMetadata.validate(m), Nil)
    assertEquals(m.keySet -- VectorMetadata.Filterable, Set("textRef"))
    assert(m.keySet.contains("pipelineRunId") && m.keySet.contains("sourceRecordId"))
  }

  test("load and idempotent reload produce the same index state") {
    val (vs, led, loader) = setup()
    val recs              = (1 to 5).map(record(_))
    val first             = loader.load(runId, VectorIndex.ResearcherProfile, recs)
    assertEquals(first.map(_.written), Right(5))
    val snapshot = recs.map(r => vs.stored(VectorIndex.ResearcherProfile, r.key))
    val second   = loader.load(runId, VectorIndex.ResearcherProfile, recs)
    assertEquals(second, first)
    assertEquals(vs.count(VectorIndex.ResearcherProfile), 5)
    assertEquals(recs.map(r => vs.stored(VectorIndex.ResearcherProfile, r.key)), snapshot)
    assertEquals(led.rejects(runId, 100), Right(Nil))
  }

  test(
    "duplicate vector keys: identical copies collapse; conflicting copies are rejected and recorded"
  ) {
    val (vs, led, loader) = setup()
    val same              = record(1)
    val clash             = record(2)
    val clashOther        = clash.copy(data = Vector(0f, 1f, 0f, 0f))
    val out = loader.load(runId, VectorIndex.ResearcherProfile, Seq(same, same, clash, clashOther))
    val report = out.toOption.get
    assertEquals(report.written, 1)
    assertEquals(report.collapsedDuplicates, 1)
    assertEquals(report.rejected.map(_._1), List(clash.key))
    assertEquals(vs.stored(VectorIndex.ResearcherProfile, clash.key), None)
    val recorded = led.rejects(runId, 100).toOption.get
    assertEquals(
      recorded.map(r => (r.scope, r.key)),
      List(("vector:researcher-profile-v1", clash.key))
    )
    assertEquals(recorded.head.provenance, prov("r/2"))
  }

  test(
    "conflicting duplicates with different provenance: each recorded; reverse-order replay is idempotent"
  ) {
    val (vs, led, loader) = setup()
    val a                 = record(7)
    val b                 = a.copy(
      data = Vector(0f, 1f, 0f, 0f),
      metadata = a.metadata + ("sourceRecordId" -> MetaValue.Str("r/8"))
    )
    assertEquals(VectorMetadata.provenanceOf(a.metadata).map(_.sourceRecordId), Some("r/7"))
    assertEquals(VectorMetadata.provenanceOf(b.metadata).map(_.sourceRecordId), Some("r/8"))

    val first = loader.load(runId, VectorIndex.ResearcherProfile, Seq(a, b))
    assert(first.isRight, s"$first")
    val ledgerAfterFirst = led.rejects(runId, 100).toOption.get
    assertEquals(ledgerAfterFirst.map(_.key).distinct, List(a.key))
    assertEquals(ledgerAfterFirst.map(_.provenance).toSet, Set(prov("r/7"), prov("r/8")))

    val replay = loader.load(runId, VectorIndex.ResearcherProfile, Seq(b, a))
    assert(replay.isRight, s"reverse-order replay must not conflict: $replay")
    assertEquals(replay, first, "report is independent of input order")
    assertEquals(led.rejects(runId, 100), Right(ledgerAfterFirst), "same ledger state")
    assertEquals(vs.stored(VectorIndex.ResearcherProfile, a.key), None)
  }

  test("malformed metadata: unknown keys, long text, missing keys, bad refs are rejected") {
    val (vs, led, loader) = setup()
    val base              = record(1)
    val unknown = record(2).copy(metadata = record(2).metadata + ("abstract" -> MetaValue.Str("x")))
    val longText = record(3).copy(metadata =
      record(3).metadata + ("entityId" -> MetaValue.Str("researcher:sc1:" + "a" * 300))
    )
    val missing = record(4).copy(metadata = record(4).metadata - "pipelineRunId")
    val badRef  =
      record(5).copy(metadata = record(5).metadata + ("textRef" -> MetaValue.Str("raw/x")))
    val badType =
      record(6).copy(metadata = record(6).metadata + ("windowStart" -> MetaValue.Str("2000")))
    val out = loader.load(
      runId,
      VectorIndex.ResearcherProfile,
      Seq(base, unknown, longText, missing, badRef, badType)
    ).toOption.get
    assertEquals(out.written, 1)
    val reasons = out.rejected.toMap
    assert(reasons(unknown.key).contains("unknown key abstract"))
    assert(reasons(longText.key).contains("exceeds 256 characters"))
    assert(reasons(missing.key).contains("missing key pipelineRunId"))
    assert(reasons(badRef.key).contains("semantic-context/"))
    assert(reasons(badType.key).contains("windowStart must be an integer"))
    assertEquals(vs.count(VectorIndex.ResearcherProfile), 1)
    assertEquals(led.rejects(runId, 100).map(_.size), Right(5))
  }

  test("wrong dimension, non-finite data, and non-chunk keys are rejected") {
    val (_, _, loader) = setup()
    val out            = loader.load(
      runId,
      VectorIndex.PaperAbstract,
      Seq(
        record(1, Vector(1f, 2f)),
        record(2, Vector(Float.NaN, 0f, 0f, 0f)),
        record(3).copy(key = "researcher:sc1:" + "b" * 64)
      )
    ).toOption.get
    assertEquals(out.written, 0)
    assertEquals(out.rejected.size, 3)
  }

  test("batched writes: 250 vectors in batches of 100 make 3 PutVectors calls") {
    val (vs, _, loader) = setup(batch = 100)
    val out = loader.load(runId, VectorIndex.CollaborationPath, (1 to 250).map(record(_)))
    assertEquals(out.map(_.batches), Right(3))
    assertEquals(vs.putCalls, 3)
    assertEquals(vs.count(VectorIndex.CollaborationPath), 250)
  }

  test("bounded retries: throttled batch is retried, persistent failure stops with typed error") {
    val (vs, _, loader) = setup(policy = RetryPolicy(3, 0, 0))
    vs.failNext(StorageError.Throttled("s3vectors:PutVectors", "429"))
    assertEquals(
      loader.load(runId, VectorIndex.ResearcherProfile, Seq(record(1))).map(_.written),
      Right(1)
    )
    vs.failNext((1 to 3).map(_ => StorageError.Timeout("s3vectors:PutVectors", "t"))*)
    assert(loader.load(runId, VectorIndex.ResearcherProfile, Seq(record(2))).left.exists(
      _.isInstanceOf[StorageError.Timeout]
    ))
  }

  test("query: bounded topK, filterable keys only, and metadata filter applied") {
    val (vs, _, loader) = setup()
    loader.load(runId, VectorIndex.ResearcherProfile, (1 to 3).map(record(_)))
    val entity = researcherId("homepages/p2")
    val hits   = vs.query(
      VectorIndex.ResearcherProfile,
      Vector(1f, 0f, 0f, 0f),
      5,
      Map("entityId" -> MetaValue.Str(entity))
    )
    assertEquals(hits.map(_.map(_.metadata("entityId"))), Right(List(MetaValue.Str(entity))))
    assert(vs.query(VectorIndex.ResearcherProfile, Vector(1f, 0f, 0f, 0f), 31, Map.empty).isLeft)
    assert(vs.query(
      VectorIndex.ResearcherProfile,
      Vector(1f, 0f, 0f, 0f),
      5,
      Map("textRef" -> MetaValue.Str("x"))
    ).isLeft)
  }

  test("index names are stable and versioned") {
    assertEquals(
      VectorIndex.values.map(_.wire).toList,
      List(
        "researcher-profile-v1",
        "collaboration-community-v1",
        "paper-abstract-v1",
        "collaboration-path-v1",
        "topic-institution-summary-v1"
      )
    )
    assertEquals(VectorIndex.PaperAbstract.physicalName("sc-dev"), "sc-dev-paper-abstract-v1")
  }
