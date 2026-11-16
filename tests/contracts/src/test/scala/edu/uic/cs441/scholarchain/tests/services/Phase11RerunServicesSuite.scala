package edu.uic.cs441.scholarchain.tests.services

import edu.uic.cs441.scholarchain.core.model.{CollaborationMetric, MetricValue}
import edu.uic.cs441.scholarchain.services.LocalFixture
import edu.uic.cs441.scholarchain.services.LocalFixture.{alice, bob, carol, dave}
import edu.uic.cs441.scholarchain.services.api.{NormalizedQuery, OutputOptions, WorkflowOptions}
import edu.uic.cs441.scholarchain.services.app.*
import edu.uic.cs441.scholarchain.tests.support.ApiKit
import edu.uic.cs441.scholarchain.tests.support.ApiKit.*
import edu.uic.cs441.scholarchain.tests.support.RagKit
import java.time.Instant
import scala.collection.mutable

/**
 * Phase 11 repair-01 rerun, targets 3 and 4.
 *
 * Target 3 (D2, M2): a release with duplicate export rows, loaded through rag's real
 * GraphExportReader and served by the real services composition. Disagreeing duplicate metric,
 * component, community, and bridge rows are never presented; agreeing duplicates are presented
 * once; CONFLICTING claims suppress the attribute; and the asynchronous result (GET
 * /v1/jobs/{jobId}/result) follows the same rule as the synchronous answer.
 *
 * Target 4 (N2): the in-memory record store at its global cap, driven by seeded random workloads
 * and checked step by step against the documented eviction contract.
 */
class Phase11RerunServicesSuite extends munit.FunSuite:

  // ---- Target 3 --------------------------------------------------------------------------------

  private val fx = LocalFixture

  /**
   * Alice: disagreeing degree. Bob: identical metric duplicate. Carol: every graph attribute
   * disagrees. Dave: identical graph-metrics duplicate.
   */
  private lazy val dupFacts =
    val aliceRow       = fx.metrics.find(_.researcherId == alice).get
    val bobRow         = fx.metrics.find(_.researcherId == bob).get
    val carolG         = fx.graphMetrics.find(_.researcherId == carol).get
    val daveG          = fx.graphMetrics.find(_.researcherId == dave).get
    val otherCommunity = fx.graphMetrics.find(_.researcherId == dave).get.communityId
    RagKit.loadRelease(
      metrics = fx.metrics ++ List(
        aliceRow.copy(metrics = List(MetricValue(CollaborationMetric.Degree, 7.0))),
        bobRow
      ),
      graphMetrics = Some(
        fx.graphMetrics ++ List(
          carolG.copy(
            componentId = fx.graphMetrics.find(_.researcherId == fx.frank).get.componentId,
            communityId = otherCommunity,
            crossCommunityDegree = 5L
          ),
          daveG
        )
      )
    ).fold(e => fail(s"the real reader refused the duplicate release: $e"), identity)

  private val questions = List(
    "Who are the top 20 researchers by degree?",
    "Who are the bridge researchers?",
    "Which community is Carol Chen in?",
    "Which component is Carol Chen in?",
    "Which component is Alice Adams in?",
    "Which community is Dave Diaz in?",
    "What is the degree of Alice Adams?",
    "What is the degree of Bob Brown?"
  )

  private def async(question: String): String =
    ujson.write(ujson.Obj("question" -> question, "mode" -> "asynchronous"))

  private def sync(question: String): String = ujson.write(ujson.Obj("question" -> question))

  /** Every presentation rule that must hold on one answer body (sync or async). */
  private def checkBody(q: String, body: ujson.Value): Unit =
    val claims = body("claims").arr.toList
    if body("status").str == "ANSWERED" then
      val views = body("answer")("researchers").arr.toList
      val ids   = views.map(_("researcherId").str)
      assertEquals(ids.distinct, ids, s"$q: a researcher is presented twice")
      def opt(v: ujson.Value, k: String) = v.obj.get(k).filterNot(_ == ujson.Null)
      views.foreach { v =>
        v("researcherId").str match
          case `alice` =>
            assertEquals(opt(v, "distinctCoauthorCount"), None, s"$q: alice degree $v")
            assert(!v("metrics").obj.contains("degree"), s"$q: alice degree in metrics $v")
          case `carol` =>
            List("componentId", "communityId", "bridgeMetric").foreach(k =>
              assertEquals(opt(v, k), None, s"$q: carol $k presented although rows disagree: $v")
            )
          case `bob` =>
            opt(v, "distinctCoauthorCount").foreach(d => assertEquals(d.num, 2.0, s"$q: bob $v"))
            v("metrics").obj.get("degree").foreach(d => assertEquals(d.num, 2.0, s"$q: bob $v"))
          case `dave` =>
            val g = fx.graphMetrics.find(_.researcherId == dave).get
            opt(v, "componentId").foreach(c => assertEquals(c.str, g.componentId, s"$q: dave $v"))
            opt(v, "communityId").foreach(c => assertEquals(c.str, g.communityId, s"$q: dave $v"))
            opt(v, "bridgeMetric").foreach(b => assertEquals(b("value").num, 1.0, s"$q: dave $v"))
          case _ => ()
      }
      // Evidence listed once per ID, even when two export rows are identical.
      body.obj.get("evidence").filterNot(_ == ujson.Null).foreach { ev =>
        val eids = ev.arr.toList.map(_("evidenceId").str)
        assertEquals(eids.distinct, eids, s"$q: an evidence item is listed twice")
      }
    // Claims about a disagreeing attribute are CONFLICTING, never VERIFIED.
    claims.foreach { c =>
      val t        = c("text").str
      val disputed = List(
        s"($alice) has degree ",
        s"($carol) is a member of community ",
        s"($carol) is in component ",
        s"($carol) has cross-community degree "
      )
      if disputed.exists(t.contains) then
        assertEquals(c("verificationStatus").str, "CONFLICTING", s"$q: $t")
    }

  test("D2/M2 end to end: duplicate export rows through the real reader, sync and job result") {
    withEnv(env(deps = _.copy(facts = dupFacts))) { e =>
      var answered = 0
      questions.foreach { q =>
        val s = e.post(sync(q))
        assertEquals(s.status == 200 || s.status == 422, true, s"$q: ${s.status} ${s.body}")
        if s.status == 200 then
          val sb = json(s)
          checkBody(q, sb)
          if sb("status").str == "ANSWERED" then answered += 1
          val a   = json(e.post(async(q)))
          val jid = a("jobId").str
          assertEquals(e.awaitJob(jid)("state").str, "SUCCEEDED", q)
          val ab = json(e.get(s"/v1/jobs/$jid/result"))
          checkBody(q, ab)
          // The job result presents exactly what the synchronous answer presents.
          assertEquals(ab("status"), sb("status"), q)
          assertEquals(ab.obj.get("answer"), sb.obj.get("answer"), q)
          assertEquals(
            ab("claims").arr.map(c => (c("text").str, c("verificationStatus").str)),
            sb("claims").arr.map(c => (c("text").str, c("verificationStatus").str)),
            q
          )
      }
      assert(answered >= 4, s"only $answered questions were answered; the rules were not exercised")
    }
  }

  test("CONFLICTING claims suppress the attribute, and agreeing duplicates are shown once") {
    withEnv(env(deps = _.copy(facts = dupFacts))) { e =>
      val r = json(e.post(sync("Who are the top 20 researchers by degree?")))
      assertEquals(r("status").str, "ANSWERED", r)
      val aliceClaims = r("claims").arr.filter(_("text").str.contains(alice))
      assert(aliceClaims.nonEmpty, r)
      assertEquals(aliceClaims.map(_("verificationStatus").str).toSet, Set("CONFLICTING"))
      val views = r("answer")("researchers").arr
      views.find(_("researcherId").str == alice).foreach(v =>
        assertEquals(v.obj.get("distinctCoauthorCount").filterNot(_ == ujson.Null), None, v)
      )
      // Bob's identical duplicate row: one view, value shown, and one VERIFIED claim per value.
      val bobView = views.filter(_("researcherId").str == bob)
      assertEquals(bobView.size, 1, views)
      assertEquals(bobView.head("distinctCoauthorCount").num, 2.0)
      val bobClaims = r("claims").arr.filter(_("text").str.contains(bob))
      assertEquals(bobClaims.map(_("verificationStatus").str).distinct.toList, List("VERIFIED"))
      // Carol: bridge question; every carol bridge claim is CONFLICTING and nothing is shown.
      val b = json(e.post(sync("Who are the bridge researchers?")))
      if b("status").str == "ANSWERED" then
        val carolBridge = b("claims").arr.filter(_("text").str.contains(carol))
        assert(carolBridge.forall(_("verificationStatus").str == "CONFLICTING"), carolBridge)
        b("answer")("researchers").arr.find(_("researcherId").str == carol).foreach(v =>
          assertEquals(v.obj.get("bridgeMetric").filterNot(_ == ujson.Null), None, v)
        )
    }
  }

  // ---- Target 4 --------------------------------------------------------------------------------

  private val T0 = Instant.parse("2026-11-15T00:00:00Z")

  private def record(principal: String): QueryRecord =
    QueryRecord(
      RestIds.newQueryId(),
      RestIds.newJobId(),
      principal,
      ExecutionMode.Asynchronous,
      true,
      NormalizedQuery(
        "q",
        ExecutionMode.Asynchronous,
        WorkflowOptions(None, None, None, None),
        OutputOptions(true, true, true, true, true, true)
      ),
      JobState.Queued,
      "QUEUED",
      T0,
      T0,
      None,
      None
    )

  /**
   * One randomized workload against the documented contract (Records.scala:139-147, 199-210). The
   * test keeps its own view of what the store holds (insertion order, principal, terminal) and,
   * after every claim, compares the store with it and checks each rule.
   */
  private def workload(
      seed: Int,
      maxRecords: Int,
      perPrincipal: Int,
      steps: Int
  ): Map[String, Int] =
    val rnd   = scala.util.Random(seed)
    val store = InMemoryQueryRecordStore(
      StoreLimits(maxRecords, perPrincipal, 3600, 1000, 86400, 1000, 1000),
      () => T0
    )
    // Insertion-ordered model of the store: id -> (principal, terminal).
    val model      = mutable.LinkedHashMap.empty[String, (String, Boolean)]
    val principals = Vector("heavy", "heavy", "heavy", "mid", "mid", "light", "solo")
    val outcomes   = mutable.Map.empty[String, Int].withDefaultValue(0)
    (1 to steps).foreach { step =>
      // Finish some running records.
      model.filter(!_._2._2).keys.toList.foreach { id =>
        if rnd.nextDouble() < 0.35 then
          store.advance(id)(_.copy(state = JobState.Succeeded, stage = "COMPLETE"))
          model(id) = model(id).copy(_2 = true)
      }
      val p      = principals(rnd.nextInt(principals.size))
      val before = model.toList
      val counts = before.groupMapReduce(_._2._1)(_ => 1)(_ + _)
      val own    = counts.getOrElse(p, 0)
      val rec    = record(p)
      val res    = store.claim(p, None, "h", rec, T0)
      val after  = before.map(_._1).filter(id => store.get(id).isDefined)
      val gone   = before.map(_._1).filterNot(after.contains)
      def ctx = s"seed $seed step $step principal $p own $own counts $counts: $res, evicted $gone"
      assert(gone.size <= 1, ctx)
      gone.foreach(id => assert(before.toMap.apply(id)._2, s"non-terminal record evicted; $ctx"))
      res match
        case ClaimResult.Created =>
          assert(store.get(rec.queryId).isDefined, ctx)
          if own >= perPrincipal then
            outcomes("own-eviction") += 1
            val oldestOwn = before.collectFirst { case (id, (`p`, true)) => id }
            assertEquals(gone.headOption, oldestOwn, s"per-principal cap: own oldest; $ctx")
          else if before.size >= maxRecords then
            outcomes("global-eviction") += 1
            val top    = counts.values.max
            val heavy  = counts.filter(_._2 == top).keySet
            val victim = before.collectFirst { case (id, (q, true)) if heavy(q) => id }
            assertEquals(
              gone.headOption,
              victim,
              s"global cap: oldest terminal of the heaviest; $ctx"
            )
            // A principal holding fewer records than the heaviest is never the victim.
            gone.foreach(id => assert(heavy(before.toMap.apply(id)._1), s"lighter victim; $ctx"))
          else
            outcomes("no-eviction") += 1
            assertEquals(gone, Nil, ctx)
          model(rec.queryId) = (p, false)
        case ClaimResult.Rejected(err) =>
          assertEquals(gone, Nil, s"a refused claim evicted a record; $ctx")
          assert(store.get(rec.queryId).isEmpty, ctx)
          if own >= perPrincipal then
            outcomes("429") += 1
            assertEquals(err.status, 429, ctx)
            assert(
              !before.exists(x => x._2 == (p, true)),
              s"429 although $p had a terminal record; $ctx"
            )
          else
            outcomes("503") += 1
            assertEquals((err.status, err.code), (503, "OVERLOADED"), ctx)
            assert(before.size >= maxRecords, ctx)
            val top   = counts.values.max
            val heavy = counts.filter(_._2 == top).keySet
            assert(
              !before.exists { case (_, (q, done)) => done && heavy(q) },
              s"503 although a heaviest principal held a terminal record; $ctx"
            )
        case other => fail(s"unexpected claim result $other; $ctx")
      gone.foreach(model.remove)
      assertEquals(model.keys.toList.filter(id => store.get(id).isDefined), model.keys.toList, ctx)
    }
    outcomes.toMap

  test("N2: randomized workloads obey the global-cap eviction contract at every step") {
    val total = mutable.Map.empty[String, Int].withDefaultValue(0)
    for seed <- 1 to 25 do
      workload(seed, maxRecords = 8, perPrincipal = 5, steps = 120).foreach((k, v) => total(k) += v)
    // Every branch of the contract was exercised.
    List("global-eviction", "own-eviction", "503", "no-eviction").foreach(k =>
      assert(total(k) > 0, s"branch $k never ran: $total")
    )
  }

  test("N2: a light principal keeps its records under sustained heavy traffic at the global cap") {
    val store = InMemoryQueryRecordStore(StoreLimits(6, 6, 3600, 1000, 86400, 1000, 1000), () => T0)
    val light = List(record("light"), record("light"))
    light.foreach(r => assertEquals(store.claim("light", None, "h", r, T0), ClaimResult.Created))
    light.foreach(r => store.advance(r.queryId)(_.copy(state = JobState.Succeeded)))
    // Heavy floods: each record finishes at once, so heavy always has a terminal record to give.
    (1 to 50).foreach { i =>
      val r = record("heavy")
      store.claim("heavy", None, "h", r, T0) match
        case ClaimResult.Created =>
          store.advance(r.queryId)(_.copy(state = JobState.Succeeded))
        case other => fail(s"heavy claim $i: $other")
    }
    assert(
      light.forall(r => store.get(r.queryId).isDefined),
      "a light principal's record was evicted"
    )
    // When heavy's records all run, the next claim from anyone is refused, not served by evicting light.
    val running = (1 to 4).map(_ => record("heavy2")).toList
    val s2 = InMemoryQueryRecordStore(StoreLimits(6, 6, 3600, 1000, 86400, 1000, 1000), () => T0)
    light.foreach(r => s2.claim("light", None, "h", r, T0))
    light.foreach(r => s2.advance(r.queryId)(_.copy(state = JobState.Succeeded)))
    running.foreach(r => assertEquals(s2.claim("heavy2", None, "h", r, T0), ClaimResult.Created))
    s2.claim("third", None, "h", record("third"), T0) match
      case ClaimResult.Rejected(err) => assertEquals((err.status, err.code), (503, "OVERLOADED"))
      case other                     => fail(s"expected 503, got $other")
    assert((light ++ running).forall(r => s2.get(r.queryId).isDefined), "a record was evicted")
  }
