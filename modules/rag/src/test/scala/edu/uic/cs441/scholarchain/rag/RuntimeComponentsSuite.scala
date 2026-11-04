package edu.uic.cs441.scholarchain.rag

import edu.uic.cs441.scholarchain.core.model.*
import edu.uic.cs441.scholarchain.core.validation.Validation
import edu.uic.cs441.scholarchain.core.validation.Validators.given
import edu.uic.cs441.scholarchain.rag.runtime.*
import edu.uic.cs441.scholarchain.rag.semantic.SemanticContext
import edu.uic.cs441.scholarchain.storage.fakes.InMemoryObjectStore
import edu.uic.cs441.scholarchain.storage.graph.GraphQueries
import java.nio.charset.StandardCharsets
import upickle.default.write

class RuntimeComponentsSuite extends munit.FunSuite:
  import RuntimeFixtures.*
  import PlanOperation.*
  import PlannerResult.*

  private val planner =
    QueryPlanner(PlannerConfig(W, defaultCoauthorLimit = 500, defaultPathDepth = 9))

  private def op(q: String): PlanOperation[String] = planner.plan(q) match
    case Planned(p) => p.operation
    case Refused(r) => fail(s"refused: $r")

  // ---- planner ---------------------------------------------------------------------------------

  test("planner: exact typed operations, limits clamped to storage bounds") {
    assertEquals(
      op("Who are the coauthors of Alice Adams?"),
      DirectCoauthors("alice adams", GraphQueries.MaxCoauthorLimit)
    )
    assertEquals(
      op("Show the path from Alice Adams to Bob Brown"),
      ShortestPath("alice adams", "bob brown", GraphQueries.MaxPathDepth, 5)
    )
    assertEquals(
      op("What is Bob Brown's core number?"),
      MetricLookup("bob brown", CollaborationMetric.CoreNumber)
    )
    assertEquals(op("Top 999 researchers by degree"), MetricRanking(CollaborationMetric.Degree, 20))
    assertEquals(op("Which component is Frank Fox in?"), ComponentOf("frank fox"))
    assertEquals(op("Who are the bridge researchers?"), Bridges(5))
  }

  test("planner: windows are parsed and validated; unknown intents and metrics are refused") {
    planner.plan("Did Alice Adams collaborate with Dave Diaz from 2010 to 2014?") match
      case Planned(p) =>
        assertEquals(
          (p.window, p.windowExplicit, p.rule),
          (YearWindow(2010, 2014), true, "did-collaborate")
        )
      case other => fail(other.toString)
    planner.plan("Who are the coauthors of Alice Adams?") match
      case Planned(p) => assertEquals((p.window, p.windowExplicit), (W, false))
      case other      => fail(other.toString)
    assertEquals(
      planner.plan("Did A B collaborate with C D between 2020 and 2010?"),
      Refused(RefusalReason.InvalidWindow)
    )
    assertEquals(
      planner.plan("Did A B collaborate with C D in 1066?"),
      Refused(RefusalReason.InvalidWindow)
    )
    assertEquals(
      planner.plan("What is the h-index of Alice Adams?"),
      Refused(RefusalReason.UnknownMetric)
    )
    assertEquals(planner.plan("Write me a poem"), Refused(RefusalReason.UnsupportedIntent))
    assertEquals(planner.plan("   "), Refused(RefusalReason.EmptyQuestion))
    assertEquals(planner.plan("x" * 501), Refused(RefusalReason.QuestionTooLong))
    assertEquals(
      planner.plan("Path between Alice Adams and Alice Adams"),
      Refused(RefusalReason.SameResearcher)
    )
    assertEquals(
      planner.plan("Who are the coauthors of Robert'); DROP TABLE researchers;--"),
      Refused(RefusalReason.InvalidName)
    )
  }

  // ---- resolver --------------------------------------------------------------------------------

  private val resolver = ResearcherResolver(researchers)

  test("resolver: normalization (case, punctuation, whitespace, diacritics) and initials") {
    assertEquals(NameNormalizer.normalize("  ÁLICE   Adams. "), "alice adams")
    assertEquals(NameNormalizer.normalize("Jean-Luc O'Brien"), "jean luc o brien")
    assertEquals(
      resolver.resolve("alice  ADAMS"),
      Resolution.Resolved("alice  ADAMS", Candidate(alice, "Alice Adams", "exact-name"))
    )
    assertEquals(resolver.resolve("Àlice Adams").isInstanceOf[Resolution.Resolved], true)
    assertEquals(
      resolver.resolve("B. Brown"),
      Resolution.Resolved("B. Brown", Candidate(bob, "Bob Brown", "exact-name"))
    )
    assertEquals(
      resolver.resolve("C Chen"),
      Resolution.Resolved("C Chen", Candidate(carol, "Carol Chen", "initials"))
    )
  }

  test("resolver: two plausible candidates are all returned; none abstains; never a guess") {
    resolver.resolve("wei zhang") match
      case Resolution.Ambiguous(_, cs, n) =>
        assertEquals(n, 2)
        assertEquals(cs.map(_.researcherId), List(zhang1, zhang2).sorted)
      case other => fail(other.toString)
    resolver.resolve("W. Zhang") match
      case Resolution.Ambiguous(_, cs, _) =>
        assertEquals(cs.map(_.matchRule).distinct, List("initials"))
      case other => fail(other.toString)
    assertEquals(resolver.resolve("Zed Zulu"), Resolution.Unknown("Zed Zulu"))
    assertEquals(resolver.resolve("Adams"), Resolution.Unknown("Adams"))
  }

  // ---- export reader ---------------------------------------------------------------------------

  test("export reader: rows from another run or with malformed IDs fail the load") {
    val os  = InMemoryObjectStore()
    val bad = graphMetrics.head.copy(provenance =
      graphMetrics.head.provenance.copy(pipelineRunId =
        edu.uic.cs441.scholarchain.core.ids.StableIds.runId(
          "other",
          "c",
          "sha256:" + "1" * 64,
          Nil,
          1
        )
      )
    )
    os.putIfAbsent(
      GraphExportReader.key(GraphExportReader.GraphMetrics, RagFixtures.runId).toOption.get,
      write(bad).getBytes(StandardCharsets.UTF_8),
      "application/x-ndjson"
    )
    assert(GraphExportReader.load(os, RagFixtures.runId).left.exists(_.contains("release run")))
    assert(GraphExportReader.load(InMemoryObjectStore(), "not-a-run").isLeft)
    assertEquals(
      GraphExportReader.load(InMemoryObjectStore(), RagFixtures.runId).map(_.researchers),
      Right(Nil)
    )
  }

  // ---- verifier --------------------------------------------------------------------------------

  private val prov                                                      = pipelineProv("x")
  private def edgeFact(a: String, b: String, n: Int, w: YearWindow = W) =
    val (lo, hi) = if a < b then (a, b) else (b, a)
    GraphFact.Edge(edgeId(a, b, w), lo, hi, w, policy, n, w.startYear, w.endYear, "ptr", prov)
  private def bundle(facts: GraphFact*) =
    EvidenceBuilder.build(facts, SemanticStage.empty, "plan:v1:" + "a" * 64, RagFixtures.runId)
  private def ids(b: RagEvidenceBundle) = b.graph.map(_.evidenceId)

  test(
    "verifier: path hops; all hops verified, some partially supported, broken chain unsupported"
  ) {
    val ab   = edgeFact(alice, bob, 3)
    val bc   = edgeFact(bob, carol, 2)
    val full = bundle(ab, bc)
    val path = Assertion.PathExists(List(alice, bob, carol), W)
    assertEquals(
      ClaimVerifier.verify(DraftClaim(path, ids(full)), full, W).verdict,
      Verdict.Verified
    )
    val partial = bundle(ab, GraphFact.Identity(carol, "Carol Chen", prov))
    assertEquals(
      ClaimVerifier.verify(DraftClaim(path, ids(partial).take(1)), partial, W).verdict,
      Verdict.PartiallySupported
    )
    val loop = Assertion.PathExists(List(alice, bob, alice), W)
    assertEquals(
      ClaimVerifier.verify(DraftClaim(loop, ids(full)), full, W).verdict,
      Verdict.Unsupported
    )
  }

  test("verifier: numbers match exactly; disagreeing facts are CONFLICTING") {
    val m1  = GraphFact.Metric(bob, CollaborationMetric.Degree, 2.0, W, policy, prov)
    val m2  = GraphFact.Metric(bob, CollaborationMetric.Degree, 3.0, W, policy, pipelineProv("y"))
    val one = bundle(m1)
    def claim(v: Double, b: RagEvidenceBundle) =
      ClaimVerifier.verify(
        DraftClaim(Assertion.MetricValueIs(bob, CollaborationMetric.Degree, v, W), ids(b)),
        b,
        W
      )
    assertEquals(claim(2.0, one).verdict, Verdict.Verified)
    assertEquals(claim(2.000001, one).verdict, Verdict.Unsupported)
    val both = bundle(m1, m2)
    assertEquals(claim(2.0, both).verdict, Verdict.Conflicting)
    val c1 = GraphFact.Community(carol, commA, 3, alg, W, prov)
    val c2 = GraphFact.Community(carol, commB, 3, alg, W, pipelineProv("z"))
    val cb = bundle(c1, c2)
    assertEquals(
      ClaimVerifier.verify(
        DraftClaim(Assertion.MemberOfCommunity(carol, commA, W), ids(cb)),
        cb,
        W
      ).verdict,
      Verdict.Conflicting
    )
  }

  test("verifier: windows, identities, and semantic-only citations") {
    val ab = bundle(edgeFact(alice, bob, 3))
    def v(a: Assertion, cite: List[String] = ids(ab), plan: YearWindow = W) =
      ClaimVerifier.verify(DraftClaim(a, cite), ab, plan)
    assertEquals(v(Assertion.CoauthoredWith(alice, bob, W, 3)).verdict, Verdict.Verified)
    assertEquals(
      v(Assertion.CoauthoredWith(alice, bob, Early, 3)).verdict,
      Verdict.Unsupported
    ) // edge window outside
    assertEquals(
      v(Assertion.CoauthoredWith(alice, bob, W, 3), plan = Early).reasons.head.take(2),
      "V3"
    )
    assertEquals(v(Assertion.CoauthoredWith(alice, gina, W, 3)).reasons.head.take(2), "V2")
    assertEquals(
      v(Assertion.CoauthoredWith("researcher:bogus", bob, W, 3)).reasons.head.take(2),
      "V2"
    )
    assertEquals(
      v(Assertion.CoauthoredWith(alice, bob, W, 3), cite = Nil).reasons,
      List("V1: no evidence cited")
    )
    val semanticOnly = ab.copy(semanticEvidenceIds = List("evidence:v1:" + "b" * 64))
    val check        = ClaimVerifier.verify(
      DraftClaim(Assertion.CoauthoredWith(alice, bob, W, 3), semanticOnly.semanticEvidenceIds),
      semanticOnly,
      W
    )
    assertEquals(check.reasons, List("V1: only semantic context cited; it is not graph evidence"))
  }

  test("verifier: overall status follows core rules") {
    import Verdict.*
    def o(vs: Verdict*) = ClaimVerifier.overall(vs.toList.map(ClaimCheck(
      DraftClaim(Assertion.PathExists(Nil, W), Nil),
      _,
      Nil
    )))
    assertEquals(o(), VerificationStatus.Unsupported)
    assertEquals(o(Verified, Verified), VerificationStatus.Supported)
    assertEquals(o(Verified, Unsupported), VerificationStatus.PartiallySupported)
    assertEquals(o(Conflicting, Unsupported), VerificationStatus.Contradicted)
    assertEquals(o(Unsupported), VerificationStatus.Unsupported)
  }

  // ---- structured output -----------------------------------------------------------------------

  test("structured output: malformed JSON, wrong schema, and unknown types are rejected") {
    assertEquals(DraftAnswer.decode("{not json"), Left("malformed answer JSON"))
    assertEquals(
      DraftAnswer.decode("""{"schema":"v0","claims":[]}"""),
      Left("unexpected schema 'v0'")
    )
    assertEquals(
      DraftAnswer.decode(
        s"""{"schema":"${RagVersions.AnswerClaimSchema}","claims":[{"assertion":{"$$type":"DropTables"},"evidenceIds":[]}]}"""
      ).isLeft,
      true
    )
    val many = DraftAnswer(
      RagVersions.AnswerClaimSchema,
      List.fill(DraftAnswer.MaxClaims + 1)(DraftClaim(Assertion.PathExists(Nil, W), Nil))
    )
    assert(DraftAnswer.decode(write(many)).left.exists(_.contains("more than")))
  }

  test(
    "structured output: a well-formed claim with a fabricated ID decodes but fails verification"
  ) {
    val b     = bundle(edgeFact(alice, bob, 3))
    val draft = DraftAnswer(
      RagVersions.AnswerClaimSchema,
      List(DraftClaim(Assertion.CoauthoredWith(alice, bob, W, 3), List("evidence:v1:" + "f" * 64)))
    )
    // Wire tag pinned by prompts/answer-synthesis/v1/answer-claims.schema.json.
    assert(write(draft).contains("\"$type\":\"CoauthoredWith\""), write(draft))
    val decoded = DraftAnswer.decode(write(draft))
    assertEquals(decoded, Right(draft))
    val check = ClaimVerifier.verify(decoded.toOption.get.claims.head, b, W)
    assertEquals(check.verdict, Verdict.Unsupported)
    assert(check.reasons.head.startsWith("V1: cited evidence not in bundle"))
  }

  // ---- evidence bundle -------------------------------------------------------------------------

  test("evidence bundle: bounded, deduplicated, core-valid, and graph kept apart from semantic") {
    val many = (1 to EvidenceBuilder.MaxGraphItems + 5).map(i =>
      GraphFact.Metric(
        alice,
        CollaborationMetric.Degree,
        i.toDouble,
        W,
        policy,
        pipelineProv(s"m$i")
      )
    )
    val b = EvidenceBuilder.build(
      many ++ many.take(3),
      SemanticStage.empty,
      "plan:v1:" + "a" * 64,
      RagFixtures.runId
    )
    assertEquals(b.graph.size, EvidenceBuilder.MaxGraphItems)
    assertEquals(b.truncated.size, 5)
    assertEquals(b.version, RagVersions.EvidenceBundle)
    assertEquals(b.semantic.kind, SemanticContext.Kind)
    assertEquals(Validation.validate(b.core).isRight, true)
    assert(b.graph.flatMap(_.item.excerpt).forall(_.length <= EvidenceBuilder.MaxExcerptChars))
    // Evidence IDs are content-derived and stable.
    assertEquals(
      EvidenceBuilder.build(
        many,
        SemanticStage.empty,
        "plan:v1:" + "a" * 64,
        RagFixtures.runId
      ).core.bundleId,
      b.core.bundleId
    )
  }
