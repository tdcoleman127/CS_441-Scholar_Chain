package edu.uic.cs441.scholarchain.storage

import edu.uic.cs441.scholarchain.core.SchemaVersion
import edu.uic.cs441.scholarchain.core.ids.{CanonicalHash, StableIds}
import edu.uic.cs441.scholarchain.core.model.*
import edu.uic.cs441.scholarchain.storage.fakes.InMemoryKeyValueStore
import edu.uic.cs441.scholarchain.storage.kv.*

class KeyValueTablesSuite extends munit.FunSuite:
  import Fixtures.*

  private val noRetry = RetryPolicy.NoRetry

  private val mentionId = StableIds.mentionId(workId("conf/a/1"), 0, SourceType.Dblp, "conf/a/1")

  private val decision = MatchDecision(
    SchemaVersion.Current,
    StableIds.matchDecisionId(mentionId, Some(alice), "er-v1"),
    mentionId,
    Some(alice),
    MatchOutcome.Match,
    MatchDecider.Rule,
    0.97,
    "er-v1",
    List("exact-orcid"),
    prov("conf/a/1")
  )

  private val evidenceItem =
    val hash = CanonicalHash.contentHash("Alice and Bob wrote conf/a/1")
    EvidenceItem(
      SchemaVersion.Current,
      StableIds.evidenceId(
        EvidenceType.SourceRecord,
        workId("conf/a/1"),
        hash,
        SourceType.Dblp,
        "conf/a/1"
      ),
      EvidenceType.SourceRecord,
      workId("conf/a/1"),
      hash,
      Some("Alice and Bob wrote conf/a/1"),
      prov("conf/a/1")
    )

  private val bundle = EvidenceBundle(
    SchemaVersion.Current,
    StableIds.evidenceBundleId(List(evidenceItem.evidenceId)),
    List(evidenceItem),
    prov("conf/a/1")
  )

  test("table names and key schema are explicit") {
    assertEquals(Table.RunLedger.physicalName("sc-dev"), "sc-dev-run-ledger")
    assertEquals(Keys.run(runId), Right(s"RUN#$runId"))
    assertEquals(Keys.turn(7), Right("TURN#000007"))
    assertEquals(
      Keys.artifact("graph", "neptune-bulk-load"),
      Right("ARTIFACT#graph#neptune-bulk-load")
    )
    assert(Keys.artifact("a#b", "x").isLeft, "a '#' in a key component must be rejected")
    assertEquals(
      Keys.reject("vector:x", "evil#key", "0123456789abcdef"),
      Right(s"REJECT#vector:x#${CanonicalHash.contentHash("evil#key")}#0123456789abcdef")
    )
  }

  test("RunLedger: manifest is written once; replay is AlreadyPresent; provenance kept") {
    val store  = InMemoryKeyValueStore()
    val ledger = RunLedgerTable(store, noRetry)
    assertEquals(ledger.putManifest(manifest), Right(WriteOutcome.Written))
    assertEquals(ledger.putManifest(manifest), Right(WriteOutcome.AlreadyPresent))
    val item = store.get(Table.RunLedger, s"RUN#$runId", "MANIFEST").toOption.flatten.get
    assertEquals(item.provenance.pipelineRunId, runId)
    assertEquals(item.status, Some("started"))
    assertEquals(store.size(Table.RunLedger), 1)
  }

  test("RunLedger: status transitions use optimistic locking on version") {
    val store  = InMemoryKeyValueStore()
    val ledger = RunLedgerTable(store, noRetry)
    ledger.putManifest(manifest)
    assertEquals(ledger.transition(runId, "started", "loaded"), Right(()))
    val item = store.get(Table.RunLedger, s"RUN#$runId", "MANIFEST").toOption.flatten.get
    assertEquals((item.status, item.version), (Some("loaded"), 2L))
    assert(ledger.transition(runId, "started", "loaded").left.exists(
      _.isInstanceOf[StorageError.VersionConflict]
    ))
    val stale = item.copy(version = 2L, status = Some("x"))
    assertEquals(
      store.updateIfVersion(Table.RunLedger, stale, 1L),
      Left(StorageError.VersionConflict(item.key, 1L))
    )
  }

  test("conditional write: same key with different content is a typed conflict, not overwrite") {
    val store = InMemoryKeyValueStore()
    val a     = TableItem.create(s"RUN#$runId", "STEP#x", """{"a":1}""", prov("x"))
    val b     = TableItem.create(s"RUN#$runId", "STEP#x", """{"a":2}""", prov("x"))
    assertEquals(store.putIfAbsent(Table.RunLedger, a), Right(WriteOutcome.Written))
    assertEquals(store.putIfAbsent(Table.RunLedger, a), Right(WriteOutcome.AlreadyPresent))
    assertEquals(
      store.putIfAbsent(Table.RunLedger, b),
      Left(StorageError.ConditionalConflict(a.key, Some(a.contentHash), b.contentHash))
    )
    assertEquals(
      store.get(Table.RunLedger, a.pk, a.sk).map(_.map(_.payload)),
      Right(Some(a.payload))
    )
  }

  test("RunLedger rejects: several rejects under one key each get an item; replay is idempotent") {
    val store  = InMemoryKeyValueStore()
    val ledger = RunLedgerTable(store, noRetry)
    val r1     = RejectRecord(runId, "vector:x", "k1", "reason one", prov("k1/a"))
    val r2     = r1.copy(reason = "reason two")
    val r3     = r1.copy(provenance = prov("k1/b"))
    assertNotEquals(RejectRecord.digest(r1), RejectRecord.digest(r2))
    assertNotEquals(RejectRecord.digest(r1), RejectRecord.digest(r3))
    List(r1, r2, r3).foreach(r => assertEquals(ledger.recordReject(r), Right(WriteOutcome.Written)))
    List(r3, r2, r1).foreach(r =>
      assertEquals(ledger.recordReject(r), Right(WriteOutcome.AlreadyPresent))
    )
    assertEquals(ledger.rejects(runId, 10).map(_.toSet), Right(Set(r1, r2, r3)))
    val item = store.queryPartition(Table.RunLedger, s"RUN#$runId", 10).toOption.get
    assertEquals(item.map(_.provenance).toSet, Set(prov("k1/a"), prov("k1/b")))
  }

  test("RunLedger steps: identical replay is AlreadyPresent; a different status is not") {
    val store  = InMemoryKeyValueStore()
    val ledger = RunLedgerTable(store, noRetry)
    val step   = RunStep(runId, "parse", "d")
    assertEquals(ledger.putStep(step, "running"), Right(WriteOutcome.Written))
    assertEquals(ledger.putStep(step, "running"), Right(WriteOutcome.AlreadyPresent))
    val changed = ledger.putStep(step, "failed")
    assertNotEquals(changed, Right(WriteOutcome.AlreadyPresent))
    assert(changed.left.exists(_.isInstanceOf[StorageError.ConditionalConflict]), s"$changed")
    val stored = store.get(Table.RunLedger, s"RUN#$runId", "STEP#parse").toOption.flatten.get
    assertEquals(stored.status, Some("running"), "never overwritten")
    assertEquals(stored.provenance.pipelineRunId, runId)
  }

  test("EntityLinkCache: match decision round trip and idempotent reload") {
    val store = InMemoryKeyValueStore()
    val cache = EntityLinkCacheTable(store, noRetry)
    assertEquals(cache.put(decision), Right(WriteOutcome.Written))
    assertEquals(cache.put(decision), Right(WriteOutcome.AlreadyPresent))
    assertEquals(cache.get(mentionId, "er-v1"), Right(Some(decision)))
    assertEquals(cache.get(mentionId, "er-v2"), Right(None))
    val item = store.get(Table.EntityLinkCache, s"MENTION#$mentionId", "POLICY#er-v1")
    assertEquals(item.map(_.map(_.provenance)), Right(Some(decision.provenance)))
  }

  test("EntityLinkCache: an invalid record never reaches the table") {
    val store = InMemoryKeyValueStore()
    assert(EntityLinkCacheTable(
      store,
      noRetry
    ).put(decision.copy(decisionId = "match:v1:x")).isLeft)
    assertEquals(store.size(Table.EntityLinkCache), 0)
  }

  test("EvidenceBundle: round trip and idempotent reload") {
    val store = InMemoryKeyValueStore()
    val table = EvidenceBundleTable(store, noRetry)
    assertEquals(table.put(bundle), Right(WriteOutcome.Written))
    assertEquals(table.put(bundle), Right(WriteOutcome.AlreadyPresent))
    assertEquals(table.get(bundle.bundleId), Right(Some(bundle)))
  }

  test("QuerySession: turns are keyed by zero-padded turn number and carry ttl") {
    val store   = InMemoryKeyValueStore()
    val table   = QuerySessionTable(store, noRetry)
    val planId  = StableIds.queryPlanId("who?", List(alice), None, Nil)
    val runtime = Provenance(SourceType.ScholarChainRuntime, "session-1", runId)
    val t0      = SessionTurn("session-1", 0, planId, None, 1800000000L, runtime)
    val t1      = t0.copy(turn = 1)
    assertEquals(table.put(t1), Right(WriteOutcome.Written))
    assertEquals(table.put(t0), Right(WriteOutcome.Written))
    assertEquals(table.put(t0), Right(WriteOutcome.AlreadyPresent))
    assertEquals(table.turns("session-1", 10), Right(List(t0, t1)))
    assertEquals(
      store.get(
        Table.QuerySession,
        "SESSION#session-1",
        "TURN#000000"
      ).map(_.flatMap(_.ttlEpochSeconds)),
      Right(Some(1800000000L))
    )
    assert(table.turns("session-1", 1000).isLeft, "query limit is bounded")
  }

  test("ArtifactRegistry: registration is idempotent and requires matching run provenance") {
    val store = InMemoryKeyValueStore()
    val table = ArtifactRegistryTable(store, noRetry)
    val a     = ArtifactRecord(
      "graph",
      "neptune-bulk-load",
      runId,
      "graph/x",
      "sha256:" + "a" * 64,
      Provenance(SourceType.ScholarChainPipeline, "graph/x", runId)
    )
    assertEquals(table.register(a), Right(WriteOutcome.Written))
    assertEquals(table.register(a), Right(WriteOutcome.AlreadyPresent))
    assert(table.register(a.copy(runId = runIdFor(2))).isLeft)
  }

  test("failure injection: throttled create is retried; the replay resolves by content hash") {
    val store  = InMemoryKeyValueStore()
    val ledger = RunLedgerTable(store, RetryPolicy(3, 0, 0))
    store.failNext(
      StorageError.Throttled("dynamodb:PutItem", "throughput"),
      StorageError.Unavailable("dynamodb:PutItem", "500")
    )
    assertEquals(ledger.putManifest(manifest), Right(WriteOutcome.Written))
    assertEquals(store.calls, 3)
  }

  test("failure injection: access-denied style failures surface immediately") {
    val store = InMemoryKeyValueStore()
    store.failNext(StorageError.Failed("dynamodb:PutItem", "AccessDenied"))
    assertEquals(
      RunLedgerTable(store, RetryPolicy.Default).putManifest(manifest),
      Left(StorageError.Failed("dynamodb:PutItem", "AccessDenied"))
    )
    assertEquals(store.calls, 1)
  }
