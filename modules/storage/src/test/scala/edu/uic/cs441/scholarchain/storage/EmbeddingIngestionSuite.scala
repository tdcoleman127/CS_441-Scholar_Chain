package edu.uic.cs441.scholarchain.storage

import edu.uic.cs441.scholarchain.core.ids.StableIds
import edu.uic.cs441.scholarchain.core.model.{DocumentType, YearWindow}
import edu.uic.cs441.scholarchain.storage.fakes.{
  InMemoryKeyValueStore,
  InMemoryObjectStore,
  InMemoryVectorStore
}
import edu.uic.cs441.scholarchain.storage.kv.RunLedgerTable
import edu.uic.cs441.scholarchain.storage.objectstore.ObjectKey
import edu.uic.cs441.scholarchain.storage.vectors.*
import upickle.default.read

class EmbeddingIngestionSuite extends munit.FunSuite:
  import Fixtures.*

  private val dim  = 4
  private val run2 = runIdFor(2)

  private def summary(
      i: Int,
      text: String = "",
      docType: DocumentType = DocumentType.ResearcherProfile
  ): SemanticSummary =
    val t = if text.isEmpty then s"profile text $i" else text
    SemanticSummary(
      researcherId(s"homepages/p$i"),
      docType,
      Some(YearWindow(2000, 2020)),
      "summary-v1",
      t,
      StableIds.textHash(t),
      prov(s"r/$i")
    )

  /** Deterministic fake embedder that counts the texts it embeds. */
  final private class CountingEmbedder(model: String = "embed-v1"):
    var texts: List[String]         = Nil
    var failNext: Boolean           = false
    val supplier: EmbeddingSupplier = EmbeddingSupplier(
      model,
      dim,
      ts =>
        if failNext then
          failNext = false
          Left("model unavailable")
        else
          texts = texts ++ ts
          Right(ts.map(t => Vector(t.length.toFloat, 1f, 0f, (t.hashCode % 7).toFloat)))
    )

  final private case class Env(
      vs: InMemoryVectorStore,
      os: InMemoryObjectStore,
      kv: InMemoryKeyValueStore,
      led: RunLedgerTable,
      ing: EmbeddingIngestion
  )

  private def env(batch: Int = 100, policy: RetryPolicy = RetryPolicy.NoRetry): Env =
    val vs  = InMemoryVectorStore()
    val os  = InMemoryObjectStore()
    val kv  = InMemoryKeyValueStore()
    val led = RunLedgerTable(kv, RetryPolicy.NoRetry)
    Env(vs, os, kv, led, EmbeddingIngestion(vs, os, led, batch, policy, (_: Long) => ()))

  test("first ingestion writes vectors, metadata schema, and S3 text with provenance") {
    val e   = env()
    val emb = CountingEmbedder()
    val in  = (1 to 3).map(summary(_))
    val rep = e.ing.ingest(run2, in, emb.supplier).toOption.get
    assertEquals((rep.written, rep.skippedUnchanged, rep.failed, rep.rejected), (3, 0, Nil, Nil))
    assertEquals(rep.batches, 1)
    val key    = EmbeddingIngestion.vectorKey(in.head)
    val stored = e.vs.stored(VectorIndex.ResearcherProfile, key).get
    assertEquals(
      stored.metadata.keySet,
      Set(
        "entityId",
        "docType",
        "windowStart",
        "windowEnd",
        "sourceDataset",
        "sourceRecordId",
        "pipelineRunId",
        "modelVersion",
        "textHash",
        "textRef"
      )
    )
    assertEquals(stored.metadata("modelVersion"), MetaValue.Str("embed-v1"))
    assertEquals(stored.metadata("textHash"), MetaValue.Str(in.head.textHash))
    assertEquals(stored.metadata("pipelineRunId"), MetaValue.Str(runId))
    val MetaValue.Str(ref) = stored.metadata("textRef"): @unchecked
    assert(ref.startsWith("semantic-context/researcher-profile/v1/run="), ref)
    assert(ref.contains(run2), ref)
    val doc =
      read[SemanticContextDoc](e.os.get(ObjectKey.parse(ref).toOption.get).toOption.get.get.text)
    assertEquals(doc.text, in.head.text)
    assertEquals(doc.provenance, prov("r/1"))
    assertEquals(doc.ingestRunId, run2)
    assertEquals(doc.vectorKey, key)
  }

  test("skip-unchanged: replay embeds nothing and writes nothing") {
    val e   = env()
    val emb = CountingEmbedder()
    val in  = (1 to 5).map(summary(_))
    e.ing.ingest(run2, in, emb.supplier)
    val putsAfterFirst = e.vs.putCalls
    val stepsAfter     = e.led.steps(run2, 100).toOption.get
    emb.texts = Nil
    val replay = e.ing.ingest(run2, in, emb.supplier).toOption.get
    assertEquals((replay.skippedUnchanged, replay.written, replay.batches), (5, 0, 0))
    assertEquals(emb.texts, Nil)
    assertEquals(e.vs.putCalls, putsAfterFirst)
    assertEquals(e.led.steps(run2, 100), Right(stepsAfter))
  }

  test("changed model version re-embeds and rewrites; changed text writes a new key") {
    val e  = env()
    val in = (1 to 3).map(summary(_))
    e.ing.ingest(run2, in, CountingEmbedder().supplier)

    val v2  = CountingEmbedder("embed-v2")
    val rep = e.ing.ingest(run2, in, v2.supplier).toOption.get
    assertEquals((rep.written, rep.skippedUnchanged), (3, 0))
    assertEquals(v2.texts.size, 3)
    in.foreach(s =>
      assertEquals(
        e.vs.stored(VectorIndex.ResearcherProfile, EmbeddingIngestion.vectorKey(s)).map(
          _.metadata("modelVersion")
        ),
        Some(MetaValue.Str("embed-v2"))
      )
    )

    val edited = summary(2, text = "profile text 2, revised")
    val v2b    = CountingEmbedder("embed-v2")
    val rep2   = e.ing.ingest(run2, Seq(in(0), edited, in(2)), v2b.supplier).toOption.get
    assertEquals((rep2.written, rep2.skippedUnchanged), (1, 2))
    assertEquals(v2b.texts, List(edited.text))
    assertNotEquals(EmbeddingIngestion.vectorKey(edited), EmbeddingIngestion.vectorKey(in(1)))
    assertEquals(e.vs.count(VectorIndex.ResearcherProfile), 4, "old vector kept: no delete path")
  }

  test("duplicate keys: identical collapse; conflicting are rejected, recorded, never embedded") {
    val e     = env()
    val emb   = CountingEmbedder()
    val same  = summary(1)
    val clash = summary(2)
    val other = clash.copy(provenance = prov("r/other"))
    val rep   = e.ing.ingest(run2, Seq(same, same, clash, other), emb.supplier).toOption.get
    assertEquals((rep.written, rep.collapsedDuplicates), (1, 1))
    val clashKey = EmbeddingIngestion.vectorKey(clash)
    assertEquals(rep.rejected.map(_._1).distinct, List(clashKey))
    assertEquals(emb.texts, List(same.text))
    assertEquals(e.vs.stored(VectorIndex.ResearcherProfile, clashKey), None)
    val recorded = e.led.rejects(run2, 100).toOption.get
    assertEquals(recorded.map(_.scope).distinct, List("vector-ingest:researcher-profile-v1"))
    assertEquals(recorded.map(_.provenance).toSet, Set(prov("r/2"), prov("r/other")))
    // Replay in reverse order: same ledger state, no conflict.
    val again = e.ing.ingest(run2, Seq(other, clash, same), emb.supplier)
    assert(again.isRight, s"$again")
    assertEquals(e.led.rejects(run2, 100), Right(recorded))
  }

  test("invalid input is rejected: text hash mismatch, bad entity ID, inverted window") {
    val e   = env()
    val bad = Seq(
      summary(1).copy(textHash = StableIds.textHash("something else")),
      summary(2).copy(entityId = "not-an-id"),
      summary(3).copy(window = Some(YearWindow(2020, 2000)))
    )
    val rep = e.ing.ingest(run2, bad :+ summary(4), CountingEmbedder().supplier).toOption.get
    assertEquals(rep.written, 1)
    val reasons = rep.rejected.map(_._2)
    assert(reasons.exists(_.contains("textHash does not match text")), reasons)
    assert(reasons.exists(_.contains("entityId is not a stable ID")), reasons)
    assert(reasons.exists(_.contains("window start exceeds end")), reasons)
    assertEquals(e.led.rejects(run2, 100).map(_.size), Right(3))
  }

  test("failure recording: retry counts in STEP names and REJECT reasons; replay recovers") {
    val fresh = env(batch = 2, policy = RetryPolicy(3, 0, 0))
    val emb   = CountingEmbedder()
    val in    = (1 to 4).map(summary(_))
    val vs    = fresh.vs
    val ing   = fresh.ing
    // PutVectors only: batch 1 is throttled once, then written (2 attempts); batch 2 times out on
    // every attempt (3 attempts). The GetVectors lookup is not scripted.
    val scripted = new ScriptedVectorStore(
      vs,
      putErrors = List(
        Some(StorageError.Throttled("s3vectors:PutVectors", "429")),
        None,
        Some(StorageError.Timeout("s3vectors:PutVectors", "t")),
        Some(StorageError.Timeout("s3vectors:PutVectors", "t")),
        Some(StorageError.Timeout("s3vectors:PutVectors", "t"))
      )
    )
    val ing2 =
      EmbeddingIngestion(scripted, fresh.os, fresh.led, 2, RetryPolicy(3, 0, 0), (_: Long) => ())
    val rep = ing2.ingest(run2, in, emb.supplier).toOption.get
    assertEquals(rep.written, 2)
    assertEquals(rep.failed.size, 2)
    assertEquals(rep.putAttempts, 5)
    assert(rep.failed.forall(_._2.contains("PutVectors failed after 3 attempts")), rep.failed)
    val steps = fresh.led.steps(run2, 100).toOption.get
    assertEquals(steps.map(_.status).sorted, List("failed", "written"))
    assert(steps.exists(s => s.status == "written" && s.step.step.endsWith(":w2:f0:a2")), steps)
    assert(steps.exists(s => s.status == "failed" && s.step.step.endsWith(":w0:f2:a3")), steps)
    val failures = fresh.led.rejects(run2, 100).toOption.get
    assertEquals(
      failures.map(_.scope).distinct,
      List("vector-ingest-failure:researcher-profile-v1")
    )
    assertEquals(failures.map(_.key).toSet, rep.failed.map(_._1).toSet)
    assert(failures.forall(_.provenance.pipelineRunId == runId))

    // Replay after recovery: the two written keys are skipped, only the failed two are written.
    emb.texts = Nil
    val replay = ing.ingest(run2, in, emb.supplier).toOption.get
    assertEquals((replay.skippedUnchanged, replay.written, replay.failed), (2, 2, Nil))
    assertEquals(emb.texts.size, 2)
    assertEquals(vs.count(VectorIndex.ResearcherProfile), 4)
  }

  test("embedding failure is recorded per record; consecutive failed batches abort the run") {
    val vs      = InMemoryVectorStore()
    val os      = InMemoryObjectStore()
    val led     = RunLedgerTable(InMemoryKeyValueStore(), RetryPolicy.NoRetry)
    val ing     = EmbeddingIngestion(vs, os, led, 1, RetryPolicy.NoRetry, (_: Long) => (), 2)
    val failing = EmbeddingSupplier("embed-v1", dim, _ => Left("model unavailable"))
    val rep     = ing.ingest(run2, (1 to 4).map(summary(_)), failing).toOption.get
    assertEquals(rep.written, 0)
    assertEquals(rep.failed.size, 2)
    assertEquals(rep.notAttempted, 2)
    assert(rep.aborted.isDefined)
    assert(rep.failed.forall(_._2 == "embedding failed: model unavailable"))
    assertEquals(os.keys, Nil, "no text written when embedding fails")
    assertEquals(led.rejects(run2, 100).map(_.size), Right(2))
  }

  test("wrong embedding dimension fails that record only; text written before the vector") {
    val e   = env()
    val odd = EmbeddingSupplier(
      "embed-v1",
      dim,
      ts => Right(ts.map(t => if t.endsWith("2") then Vector(1f) else Vector(1f, 0f, 0f, 0f)))
    )
    val rep = e.ing.ingest(run2, (1 to 3).map(summary(_)), odd).toOption.get
    assertEquals(rep.written, 2)
    assertEquals(rep.failed.map(_._1), List(EmbeddingIngestion.vectorKey(summary(2))))
    assertEquals(e.os.keys.size, 2)
  }

  test("S3 text conflict is a typed failure, never an overwrite") {
    val e  = env()
    val s1 = summary(1)
    e.ing.ingest(run2, Seq(s1), CountingEmbedder().supplier)
    // Same key and run, different provenance: the stored JSON differs, so the create conflicts.
    val e2  = EmbeddingIngestion(InMemoryVectorStore(), e.os, e.led, 10, RetryPolicy.NoRetry)
    val rep = e2.ingest(run2, Seq(s1.copy(provenance = prov("r/x"))), CountingEmbedder().supplier)
    val r   = rep.toOption.get
    assertEquals(r.written, 0)
    assert(r.failed.head._2.contains("semantic-context write failed after 1 attempts"), r.failed)
  }

  test("routing: total on wire values; topic and institution summaries share one index") {
    // Wire routing for the two summary types core is adding (R-08-2). No DocumentType case needed.
    assertEquals(
      EmbeddingIngestion.routeWire("topic-summary"),
      Right(VectorIndex.TopicInstitutionSummary)
    )
    assertEquals(
      EmbeddingIngestion.routeWire("institution-summary"),
      Right(VectorIndex.TopicInstitutionSummary)
    )
    // Every DocumentType core defines, now or later, routes without throwing.
    DocumentType.values.foreach(d =>
      assert(EmbeddingIngestion.indexFor(d).isRight, s"${d.wire} is not routed")
    )
    // Fixed routes of the current types.
    val expected = Map(
      "researcher-profile"    -> VectorIndex.ResearcherProfile,
      "collaboration-summary" -> VectorIndex.CollaborationCommunity,
      "work-abstract"         -> VectorIndex.PaperAbstract,
      "work-metadata"         -> VectorIndex.PaperAbstract,
      "evidence-path"         -> VectorIndex.CollaborationPath,
      "topic-summary"         -> VectorIndex.TopicInstitutionSummary,
      "institution-summary"   -> VectorIndex.TopicInstitutionSummary
    )
    DocumentType.values.filter(d => expected.contains(d.wire)).foreach(d =>
      assertEquals(EmbeddingIngestion.indexFor(d), Right(expected(d.wire)), d.wire)
    )
    assertEquals(EmbeddingIngestion.Routes, expected)
    assertEquals(EmbeddingIngestion.Routes.values.toSet, VectorIndex.values.toSet)
    // An unknown wire value is a typed rejection, not an exception.
    val unknown = EmbeddingIngestion.routeWire("grant-summary")
    assert(unknown.left.exists(_.contains("'grant-summary'")), s"$unknown")
    assert(EmbeddingIngestion.routeWire("").isLeft)
    assert(EmbeddingIngestion.routeWire("Topic-Summary").isLeft, "routing is exact, not fuzzy")
    val e   = env()
    val rep = e.ing.ingest(
      run2,
      Seq(summary(1), summary(2, docType = DocumentType.CollaborationSummary)),
      CountingEmbedder().supplier
    ).toOption.get
    assertEquals(rep.written, 2)
    assertEquals(e.vs.count(VectorIndex.CollaborationCommunity), 1)
  }

  test("unrouted document type: REJECT with provenance, never embedded, replay-stable") {
    val vs  = InMemoryVectorStore()
    val os  = InMemoryObjectStore()
    val led = RunLedgerTable(InMemoryKeyValueStore(), RetryPolicy.NoRetry)
    // Simulates a document type core defines but storage does not route.
    val router: String => Either[String, VectorIndex] = w =>
      if w == "collaboration-summary" then Left(s"no vector index is routed for docType '$w'")
      else EmbeddingIngestion.routeWire(w)
    val ing = EmbeddingIngestion(vs, os, led, 100, RetryPolicy.NoRetry, (_: Long) => (), 3, router)
    val emb = CountingEmbedder()
    val routed  = summary(1)
    val stray   = summary(2, docType = DocumentType.CollaborationSummary)
    val rep     = ing.ingest(run2, Seq(routed, stray), emb.supplier).toOption.get
    val strayKy = EmbeddingIngestion.vectorKey(stray)
    assertEquals(rep.written, 1)
    assertEquals(
      rep.rejected,
      List(strayKy -> "no vector index is routed for docType 'collaboration-summary'")
    )
    assertEquals(emb.texts, List(routed.text))
    assertEquals(VectorIndex.values.map(vs.count).sum, 1)
    assertEquals(os.keys.size, 1, "no semantic-context text for the unrouted summary")
    val recorded = led.rejects(run2, 100).toOption.get
    assertEquals(
      recorded.map(r => (r.scope, r.key)),
      List((EmbeddingIngestion.UnroutedScope, strayKy))
    )
    assertEquals(recorded.map(_.provenance), List(prov("r/2")))
    assertEquals(recorded.map(_.runId), List(run2))
    val again = ing.ingest(run2, Seq(stray, routed), emb.supplier)
    assert(again.isRight, s"$again")
    assertEquals(led.rejects(run2, 100), Right(recorded))
  }

  test("a core community ID (cocomm:v1) is a valid entityId; rag-local community:v1 is not") {
    val cocomm = "cocomm:v1:" + "ab" * 32
    val text   = "community summary"
    val ref    = ObjectKey.parse("semantic-context/collaboration-summary/v1/c.json").toOption.get
    def meta(id: String) = VectorMetadata(
      id,
      DocumentType.CollaborationSummary,
      Some((2000, 2020)),
      prov("r/c"),
      "embed-v1",
      StableIds.textHash(text),
      ref
    ).toMap
    assertEquals(VectorMetadata.validate(meta(cocomm)), Nil)
    assertEquals(
      VectorMetadata.validate(meta("community:v1:" + "ab" * 32)),
      List("entityId is not a stable ID")
    )
    assertEquals(VectorMetadata.validate(meta("cocomm:v1:" + "AB" * 32)).size, 1)
    // End to end: a community summary keyed by a cocomm ID is ingested, not rejected.
    val e = env()
    val s = summary(1, text = text, docType = DocumentType.CollaborationSummary)
      .copy(entityId = cocomm)
    val rep = e.ing.ingest(run2, Seq(s), CountingEmbedder().supplier).toOption.get
    assertEquals((rep.written, rep.rejected), (1, Nil))
    assertEquals(
      e.vs.stored(VectorIndex.CollaborationCommunity, EmbeddingIngestion.vectorKey(s)).map(
        _.metadata("entityId")
      ),
      Some(MetaValue.Str(cocomm))
    )
  }

  test("GetVectors lookup is bounded and a failed lookup surfaces a typed error") {
    val vs = InMemoryVectorStore()
    assert(vs.getMetadata(VectorIndex.PaperAbstract, (1 to 101).map(i => s"k$i")).isLeft)
    assert(vs.getMetadata(VectorIndex.PaperAbstract, Nil).isLeft)
    val e = env()
    e.vs.failNext(StorageError.Failed("s3vectors:GetVectors", "AccessDenied"))
    val out = e.ing.ingest(run2, Seq(summary(1)), CountingEmbedder().supplier)
    assert(out.left.exists(_.isInstanceOf[StorageError.Failed]), s"$out")
    assertEquals(e.vs.putCalls, 0)
  }

  test("Retry.runCounted reports attempts and keeps run's behavior") {
    var n   = 0
    val out = Retry.runCounted(RetryPolicy(4, 0, 0), (_: Long) => ()) {
      n += 1
      if n < 3 then Left(StorageError.Throttled("op", "x")) else Right(n)
    }
    assertEquals(out, Attempted(Right(3), 3))
    val failed = Retry.runCounted(RetryPolicy(4, 0, 0), (_: Long) => ())(
      Left(StorageError.InvalidInput("f", "r")): Either[StorageError, Int]
    )
    assertEquals(failed.attempts, 1)
  }

/** Delegates to a fake but returns scripted PutVectors errors, one per call, in order. */
final private class ScriptedVectorStore(
    underlying: InMemoryVectorStore,
    putErrors: List[Option[StorageError]]
) extends VectorStore:
  private var script = putErrors

  def putBatch(index: VectorIndex, vectors: Seq[VectorRecord]): Either[StorageError, Unit] =
    script match
      case Some(err) :: rest =>
        script = rest
        Left(err)
      case None :: rest =>
        script = rest
        underlying.putBatch(index, vectors)
      case Nil => underlying.putBatch(index, vectors)

  def query(
      index: VectorIndex,
      vector: Vector[Float],
      topK: Int,
      filter: Map[String, MetaValue]
  ): Either[StorageError, List[VectorHit]] = underlying.query(index, vector, topK, filter)

  def getMetadata(
      index: VectorIndex,
      keys: Seq[String]
  ): Either[StorageError, Map[String, Map[String, MetaValue]]] =
    underlying.getMetadata(index, keys)
