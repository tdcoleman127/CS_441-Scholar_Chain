package edu.uic.cs441.scholarchain.tests.adversarial

import edu.uic.cs441.scholarchain.core.model.*
import edu.uic.cs441.scholarchain.rag.embedding.HashedBagOfTokensEmbedder
import edu.uic.cs441.scholarchain.rag.runtime.*
import edu.uic.cs441.scholarchain.services.LocalFixture
import edu.uic.cs441.scholarchain.services.LocalFixture.{alice, bob, carol, dave}
import edu.uic.cs441.scholarchain.storage.{RetryPolicy, StorageError}
import edu.uic.cs441.scholarchain.storage.fakes.{InMemoryKeyValueStore, InMemoryVectorStore}
import edu.uic.cs441.scholarchain.storage.kv.{RejectRecord, RunLedgerTable, Table}
import edu.uic.cs441.scholarchain.storage.vectors.*
import edu.uic.cs441.scholarchain.tests.support.RagKit.*
import scala.collection.mutable

/**
 * S3 Vectors throttling and transient failures, duplicate vector keys, stale model versions, and
 * replayed ingestion, through the storage vector port, the in-memory fakes, and rag's embedder and
 * retriever. No live S3 Vectors call is made.
 */
class VectorIngestionSuite extends munit.FunSuite:

  /** Injects errors into PutVectors only (lookups and queries pass through). */
  final class FlakyPuts(inner: VectorStore) extends VectorStore:
    private val queue                         = mutable.Queue.empty[StorageError]
    var putCalls: Int                         = 0
    def failPuts(errors: StorageError*): Unit = synchronized(queue.enqueueAll(errors))
    def putBatch(index: VectorIndex, vs: Seq[VectorRecord]): Either[StorageError, Unit] =
      synchronized {
        putCalls += 1; if queue.nonEmpty then Left(queue.dequeue()) else inner.putBatch(index, vs)
      }
    def query(index: VectorIndex, v: Vector[Float], k: Int, f: Map[String, MetaValue]) =
      inner.query(index, v, k, f)
    def getMetadata(index: VectorIndex, keys: Seq[String]) = inner.getMetadata(index, keys)

  private val throttled = StorageError.Throttled("s3vectors:PutVectors", "TooManyRequests")
  private val summaries = List(alice, bob, carol, dave).map(id => summary(id, s"Profile of $id."))

  private def rejects(kv: InMemoryKeyValueStore): List[RejectRecord] =
    RunLedgerTable(kv, RetryPolicy.NoRetry).rejects(runId, 100).toOption.get

  test("throttled PutVectors inside the retry budget: everything written, attempts counted") {
    val ix    = semanticIndex()
    val flaky = FlakyPuts(ix.vectors)
    flaky.failPuts(throttled, StorageError.Unavailable("s3vectors", "503"))
    val ing = EmbeddingIngestion(
      flaky,
      ix.objects,
      ix.ledger,
      policy = RetryPolicy(3, 0, 0),
      sleeper = noSleep
    )
    val rep =
      ing.ingest(runId, summaries, supplier(ix.embedder)).fold(e => fail(e.message), identity)
    assertEquals((rep.written, rep.failed, rep.putAttempts), (4, Nil, 3))
    assertEquals(ix.vectors.count(VectorIndex.ResearcherProfile), 4)
  }

  test("throttling past the budget loses nothing: failures are ledgered and a replay completes") {
    val ix    = semanticIndex()
    val flaky = FlakyPuts(ix.vectors)
    flaky.failPuts(List.fill(3)(throttled)*)
    def ing = EmbeddingIngestion(
      flaky,
      ix.objects,
      ix.ledger,
      policy = RetryPolicy(3, 0, 0),
      sleeper = noSleep
    )
    val r1 =
      ing.ingest(runId, summaries, supplier(ix.embedder)).fold(e => fail(e.message), identity)
    assertEquals(r1.written, 0)
    assertEquals(r1.failed.map(_._1).toSet.size, 4)
    assert(r1.failed.forall(_._2.startsWith("PutVectors failed after 3 attempts")), r1.failed)
    assertEquals(ix.vectors.count(VectorIndex.ResearcherProfile), 0)
    val failures = rejects(ix.ledgerStore).filter(_.scope.startsWith("vector-ingest-failure:"))
    assertEquals(failures.map(_.key).toSet, r1.failed.map(_._1).toSet)
    assert(failures.forall(_.provenance.pipelineRunId == runId))
    val r2 =
      ing.ingest(runId, summaries, supplier(ix.embedder)).fold(e => fail(e.message), identity)
    assertEquals((r2.written, r2.failed, r2.skippedUnchanged), (4, Nil, 0))
    val r3 =
      ing.ingest(runId, summaries, supplier(ix.embedder)).fold(e => fail(e.message), identity)
    assertEquals((r3.written, r3.skippedUnchanged, r3.batches), (0, 4, 0))
  }

  test("consecutive failed batches abort the run and count the rest as not attempted") {
    val ix    = semanticIndex()
    val flaky = FlakyPuts(ix.vectors)
    flaky.failPuts(List.fill(20)(throttled)*)
    val ing = EmbeddingIngestion(
      flaky,
      ix.objects,
      ix.ledger,
      batchSize = 1,
      policy = RetryPolicy(2, 0, 0),
      sleeper = noSleep,
      maxConsecutiveFailedBatches = 2
    )
    val r = ing.ingest(runId, summaries, supplier(ix.embedder)).fold(e => fail(e.message), identity)
    assert(r.aborted.isDefined, r)
    assertEquals((r.batches, r.notAttempted, r.written), (2, 2, 0))
    assertEquals(flaky.putCalls, 4)
  }

  test("a lookup that stays unavailable makes the ingestion fail closed (Left), writing nothing") {
    val ix = semanticIndex()
    ix.vectors.failNext(List.fill(3)(StorageError.Unavailable("s3vectors:GetVectors", "503"))*)
    val r = ix.ingestion().ingest(runId, summaries, supplier(ix.embedder))
    assert(r.isLeft, r)
    assertEquals(ix.vectors.count(VectorIndex.ResearcherProfile), 0)
    assertEquals(ix.objects.keys, Nil)
  }

  test("S3 Vectors throttled at query time: semantic context is dropped, the graph answer stands") {
    val ix = semanticIndex()
    ix.ingestion().ingest(runId, summaries, supplier(ix.embedder))
    val plain = answered(pipe().pipeline.answer("Who are the coauthors of Alice Adams?"))
    ix.vectors.failNext(StorageError.Throttled("s3vectors:QueryVectors", "TooManyRequests"))
    val p = pipe(semantic = Some(ix.stage()))
    val a = answered(p.pipeline.answer("Who are the coauthors of Alice Adams?"))
    assertEquals(a.response.claims.map(_.claimId), plain.response.claims.map(_.claimId))
    assertEquals(a.bundle.semanticEvidenceIds, Nil)
    assert(
      a.bundle.semantic.dropped.exists(_.startsWith(VectorIndex.ResearcherProfile.wire)),
      a.bundle.semantic
    )
  }

  test("duplicate vector keys: identical records collapse, conflicting records are all rejected") {
    val ix   = semanticIndex()
    val s    = summaries.head
    val twin = s.copy(provenance = s.provenance.copy(sourceRecordId = "phase11/other-record"))
    val rep  = ix.ingestion().ingest(
      runId,
      List(s, s, twin, summaries(1), summaries(1)),
      supplier(ix.embedder)
    )
      .fold(e => fail(e.message), identity)
    assertEquals(rep.collapsedDuplicates, 1)
    assertEquals(rep.rejected.map(_._2).distinct, List("conflicting duplicate key in input"))
    assertEquals(rep.rejected.size, 1, rep.rejected) // one key, listed once
    assertEquals(rep.written, 1)
    assertEquals(ix.vectors.count(VectorIndex.ResearcherProfile), 1)
    val ledgered = rejects(ix.ledgerStore).filter(_.reason == "conflicting duplicate key in input")
    assertEquals(
      ledgered.map(_.provenance.sourceRecordId).toSet,
      Set("phase11/record", "phase11/other-record")
    )
    // The port itself refuses a batch with a repeated key.
    val rec = ix.vectors.stored(VectorIndex.ResearcherProfile, rep.rejected.head._1)
    assertEquals(rec, None)
    val any = VectorRecord("chunk:v1:" + "a" * 64, Vector.fill(8)(0.1f), Map.empty)
    assert(InMemoryVectorStore().putBatch(VectorIndex.ResearcherProfile, List(any, any)).isLeft)
  }

  test(
    "vector loader: conflicting duplicates rejected in any input order, never last-writer-wins"
  ) {
    val dim  = 8
    val meta = VectorMetadata(
      alice,
      DocumentType.ResearcherProfile,
      Some((2015, 2020)),
      Provenance(SourceType.ScholarChainPipeline, "rec", runId),
      "model-v1",
      "sha256:" + "3" * 64,
      edu.uic.cs441.scholarchain.storage.objectstore.ObjectKey
        .versioned(
          edu.uic.cs441.scholarchain.storage.objectstore.S3Prefix.SemanticContext,
          "researcher-profile",
          1,
          runId,
          "x.json"
        )
        .toOption.get
    ).toMap
    val key = "chunk:v1:" + "b" * 64
    val a   = VectorRecord(key, Vector.fill(dim)(0.1f), meta)
    val b   = VectorRecord(key, Vector.fill(dim)(0.2f), meta)
    val ok  = VectorRecord("chunk:v1:" + "c" * 64, Vector.fill(dim)(0.3f), meta)
    def load(rs: Seq[VectorRecord]) =
      val store = InMemoryVectorStore()
      val kv    = InMemoryKeyValueStore()
      val rep = VectorLoader(store, RunLedgerTable(kv, RetryPolicy.NoRetry), dim, sleeper = noSleep)
        .load(runId, VectorIndex.ResearcherProfile, rs).fold(e => fail(e.message), identity)
      (rep, store, kv)
    val (r1, s1, kv1) = load(List(a, ok, b, a))
    val (r2, _, kv2)  = load(List(b, a, ok))
    assertEquals(r1.written, 1)
    assertEquals(s1.stored(VectorIndex.ResearcherProfile, key), None)
    assertEquals(r1.rejected.map(_._1).distinct, List(key))
    assertEquals(r1.rejected, r2.rejected)
    assertEquals(rejects(kv1).toSet, rejects(kv2).toSet)
    // a and b carry the same provenance, so they are one ledgered reject (RejectRecord.digest).
    assertEquals(kv1.size(Table.RunLedger), 1)
  }

  test(
    "stale model version: a new model re-embeds the same keys; old vectors are never retrieved"
  ) {
    val ix = semanticIndex(HashedBagOfTokensEmbedder(modelVersion = "phase11-model-v1"))
    val v2 = HashedBagOfTokensEmbedder(modelVersion = "phase11-model-v2")
    val r1 = ix.ingestion().ingest(runId, summaries, supplier(ix.embedder)).toOption.get
    assertEquals(r1.written, 4)
    // An old-model vector under another key coexists with new-model vectors.
    val oldOnly = summary(
      alice,
      "Legacy profile text only the old model embedded.",
      sourceVersion = "legacy-v0"
    )
    ix.ingestion().ingest(runId, List(oldOnly), supplier(ix.embedder))
    val r2 = ix.ingestion().ingest(runId, summaries, supplier(v2)).toOption.get
    assertEquals((r2.written, r2.skippedUnchanged), (4, 0), "a model change did not re-embed")
    val r3 = ix.ingestion().ingest(runId, summaries, supplier(v2)).toOption.get
    assertEquals((r3.written, r3.skippedUnchanged), (0, 4))
    val ctxV2 = edu.uic.cs441.scholarchain.rag.semantic
      .SemanticRetriever(ix.vectors, ix.objects, v2)
      .search(VectorIndex.ResearcherProfile, "Profile legacy text", 10).toOption.get
    assertEquals(ctxV2.items.size, 4)
    assert(ctxV2.items.forall(_.modelVersion == "phase11-model-v2"), ctxV2.items)
    val ctxV1 =
      ix.retriever.search(VectorIndex.ResearcherProfile, "Profile legacy text", 10).toOption.get
    assertEquals(ctxV1.items.map(_.modelVersion).distinct, List("phase11-model-v1"))
    assertEquals(ctxV1.items.size, 1, "old-model retriever saw vectors rewritten by the new model")
  }

  test("an embedder that returns the wrong dimension or too few vectors writes nothing") {
    val ix    = semanticIndex()
    val wrong =
      EmbeddingSupplier("phase11-bad", 256, ts => Right(ts.map(_ => Vector.fill(17)(0.5f))))
    val r = ix.ingestion().ingest(runId, summaries, wrong).toOption.get
    assertEquals((r.written, r.failed.size), (0, 4))
    val short = EmbeddingSupplier(
      "phase11-short",
      256,
      ts => Right(ts.drop(1).map(_ => Vector.fill(256)(0.5f)))
    )
    val r2 = ix.ingestion().ingest(runId, summaries, short).toOption.get
    assertEquals((r2.written, r2.failed.size), (0, 4))
    val nan =
      EmbeddingSupplier("phase11-nan", 256, ts => Right(ts.map(_ => Vector.fill(256)(Float.NaN))))
    assertEquals(ix.ingestion().ingest(runId, summaries, nan).toOption.get.written, 0)
    assertEquals(ix.vectors.count(VectorIndex.ResearcherProfile), 0)
  }

  test("a tampered text hash or missing text object drops the hit with a reason") {
    val ix = semanticIndex()
    ix.ingestion().ingest(runId, summaries.take(1), supplier(ix.embedder))
    val key = ix.retriever.search(
      VectorIndex.ResearcherProfile,
      "Profile",
      1
    ).toOption.get.items.head.vectorKey
    val stored = ix.vectors.stored(VectorIndex.ResearcherProfile, key).get
    ix.vectors.putBatch(
      VectorIndex.ResearcherProfile,
      List(stored.copy(metadata =
        stored.metadata + ("textHash" -> MetaValue.Str("sha256:" + "9" * 64))
      ))
    )
    val ctx = ix.retriever.search(VectorIndex.ResearcherProfile, "Profile", 1).toOption.get
    assertEquals(ctx.items, Nil)
    assert(ctx.dropped.exists(_.contains("does not match its hash")), ctx.dropped)
    val missing = stored.copy(metadata =
      stored.metadata +
        ("textRef" -> MetaValue.Str("semantic-context/researcher-profile/v1/none.json"))
    )
    ix.vectors.putBatch(VectorIndex.ResearcherProfile, List(missing))
    val ctx2 = ix.retriever.search(VectorIndex.ResearcherProfile, "Profile", 1).toOption.get
    assertEquals(ctx2.items, Nil)
    assert(ctx2.dropped.nonEmpty, ctx2)
  }

  test("an empty topic and institution index yields empty context, never an error (R-08x-1)") {
    val ix  = semanticIndex()
    val ctx = ix.retriever.search(VectorIndex.TopicInstitutionSummary, "graph partitioning", 5)
    assertEquals(ctx.map(_.items), Right(Nil))
    val a =
      answered(pipe(semantic = Some(ix.stage(List(VectorIndex.TopicInstitutionSummary)))).pipeline
        .answer("Who are the coauthors of Alice Adams?"))
    assertEquals(a.bundle.semanticEvidenceIds, Nil)
    assertEquals(a.response.overallStatus, VerificationStatus.Supported)
  }
