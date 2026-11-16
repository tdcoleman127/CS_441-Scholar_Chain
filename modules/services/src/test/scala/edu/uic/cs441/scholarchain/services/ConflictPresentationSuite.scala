package edu.uic.cs441.scholarchain.services

import edu.uic.cs441.scholarchain.core.model.{CollaborationMetric, MetricValue}
import edu.uic.cs441.scholarchain.rag.runtime.*
import edu.uic.cs441.scholarchain.services.LocalFixture.*
import edu.uic.cs441.scholarchain.services.TestKit.*
import edu.uic.cs441.scholarchain.services.api.{OutputOptions, ResearcherView}
import edu.uic.cs441.scholarchain.services.app.Presenter

/**
 * Phase 11 repair-01, D2 and M2: researcher views present an attribute only when every bundle fact
 * for it agrees, and never an attribute the verifier marked CONFLICTING. Duplicate export rows are
 * injected into the release (the reader stays permissive, follow-up P11-F1).
 */
class ConflictPresentationSuite extends munit.FunSuite:

  private val base = LocalFixture.build().dependencies.facts

  private def researcherView(r: ujson.Value, id: String): ujson.Value =
    r("answer")("researchers").arr.find(_("researcherId").str == id).getOrElse(
      fail(s"no researcher view for $id in $r")
    )

  private def answer(facts: GraphFacts, question: String): ujson.Value =
    val r = env(deps = _.copy(facts = facts)).post(ujson.write(ujson.Obj("question" -> question)))
    assertEquals(r.status, 200, r.body)
    val j = json(r)
    assertEquals(j("status").str, "ANSWERED", r.body)
    j

  test("D2: disagreeing duplicate metric rows are never presented; agreeing duplicates are") {
    val row  = base.metrics.find(_.researcherId == alice).get
    val bad  = row.copy(metrics = List(MetricValue(CollaborationMetric.Degree, 7.0)))
    val same = row.copy(metrics = row.metrics.filter(_.metric == CollaborationMetric.Degree))
    val q    = "Who are the top 20 researchers by degree?"

    val conflicted = answer(base.copy(metrics = base.metrics :+ bad), q)
    val a          = researcherView(conflicted, alice)
    assertEquals(a("distinctCoauthorCount"), ujson.Null, a)
    assert(!a("metrics").obj.contains("degree"), a)
    val aliceClaims = conflicted("claims").arr.filter(_("text").str.contains(alice))
    assertEquals(aliceClaims.map(_("verificationStatus").str).toSet, Set("CONFLICTING"))
    // Researchers without duplicates keep their values.
    assertEquals(researcherView(conflicted, bob)("distinctCoauthorCount").num, 2.0)

    val agreeing = researcherView(answer(base.copy(metrics = base.metrics :+ same), q), alice)
    assertEquals(agreeing("distinctCoauthorCount").num, 1.0, agreeing)
    assertEquals(agreeing("metrics")("degree").num, 1.0)
  }

  test("M2: a bridge measure with disagreeing duplicate rows is not presented") {
    val row   = base.graphMetrics.find(_.researcherId == carol).get
    val facts = base.copy(graphMetrics = base.graphMetrics :+ row.copy(crossCommunityDegree = 5))
    val r     = answer(facts, "Who are the bridge researchers?")
    assertEquals(researcherView(r, carol)("bridgeMetric"), ujson.Null, r)
    val d = researcherView(r, dave)("bridgeMetric")
    assertEquals((d("name").str, d("value").num), ("crossCommunityDegree", 1.0))
  }

  test("M2: two community IDs for one researcher (two algorithm versions) present neither") {
    val row   = base.graphMetrics.find(_.researcherId == carol).get
    val other = base.graphMetrics.find(_.researcherId == dave).get.communityId
    val dup   = row.copy(communityId = other, algorithmVersion = "label-propagation-v2-synthetic")
    val r     = answer(
      base.copy(graphMetrics = base.graphMetrics :+ dup),
      "Which community is Carol Chen in?"
    )
    // The verifier groups community facts by algorithm version, so both claims are VERIFIED, but
    // the view has one communityId and no algorithm field: showing either would be arbitrary.
    assertEquals(r("claims").arr.map(_("verificationStatus").str).toSet, Set("VERIFIED"))
    assertEquals(researcherView(r, carol)("communityId"), ujson.Null, r)
    val single = answer(base, "Which community is Carol Chen in?")
    assertEquals(researcherView(single, carol)("communityId").str, row.communityId)
  }

  // ---- Presenter directly: component, display name, and the CONFLICTING guard -----------------

  private val out = OutputOptions(true, true, true, true, true, true)

  private def componentOutcome(): RagOutcome.Answered =
    val fx = LocalFixture.build()
    val p  = GraphRagPipeline(
      QueryPlanner(PlannerConfig(Window)),
      base,
      GraphRetriever(fx.graph, base, RetrievalConfig(base.releaseRunId, Policy)),
      None,
      TemplateSynthesizer,
      () => 0L
    )
    p.answer("Which component is Alice Adams in?") match
      case a: RagOutcome.Answered => a
      case other                  => fail(s"expected an answer, got $other")

  private def aliceView(o: RagOutcome.Answered): ResearcherView =
    Presenter.queryResponse("qry-test", "synchronous", o, out).answer.get.researchers
      .find(_.researcherId == alice).get

  private def withFact(o: RagOutcome.Answered, f: GraphFact): RagOutcome.Answered =
    o.copy(bundle = o.bundle.copy(graph = o.bundle.graph :+ GraphEvidence(f, f.evidenceItem)))

  test("control: a single component fact and identity are presented") {
    val v = aliceView(componentOutcome())
    assertEquals(
      v.componentId,
      Some(base.graphMetrics.find(_.researcherId == alice).get.componentId)
    )
    assertEquals(v.displayName, Some("Alice Adams"))
  }

  test("M2: disagreeing component facts and display names are not presented") {
    val o    = componentOutcome()
    val comp = o.bundle.facts.collectFirst { case c: GraphFact.Component => c }.get
    val v1   = aliceView(withFact(o, comp.copy(componentId = s"${comp.componentId}-dup")))
    assertEquals(v1.componentId, None)
    val v2 = aliceView(withFact(o, comp.copy(componentSize = comp.componentSize + 1)))
    assertEquals(v2.componentId, None, "same ID but disagreeing size is still a conflict")
    val id = o.bundle.facts.collectFirst { case i: GraphFact.Identity => i }.get
    val v3 = aliceView(withFact(o, id.copy(displayName = "A. Adams")))
    assertEquals(v3.displayName, None)
    // An identical duplicate collapses to its value.
    assertEquals(aliceView(withFact(o, comp)).componentId, Some(comp.componentId))
  }

  test("a value the verifier marked CONFLICTING is never presented, even if the facts agree") {
    val o       = componentOutcome()
    val flipped = o.copy(checks = o.checks.map(_.copy(verdict = Verdict.Conflicting)))
    assertEquals(aliceView(flipped).componentId, None)
    assertEquals(Presenter.agreed(List(1, 1, 1)), Some(1))
    assertEquals(Presenter.agreed(List(1, 2)), None)
    assertEquals(Presenter.agreed(List.empty[Int]), None)
  }
