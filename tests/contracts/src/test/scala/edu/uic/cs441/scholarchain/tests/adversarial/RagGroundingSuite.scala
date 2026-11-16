package edu.uic.cs441.scholarchain.tests.adversarial

import edu.uic.cs441.scholarchain.core.ids.StableIds
import edu.uic.cs441.scholarchain.core.model.*
import edu.uic.cs441.scholarchain.rag.runtime.*
import edu.uic.cs441.scholarchain.services.LocalFixture
import edu.uic.cs441.scholarchain.services.LocalFixture.{alice, bob, carol, dave, erin}
import edu.uic.cs441.scholarchain.tests.support.{ApiKit, RagKit}
import edu.uic.cs441.scholarchain.tests.support.RagKit.*
import upickle.default.write

/**
 * Grounding attacks on the runtime Graph-RAG workflow: fabricated identifiers, unsupported and
 * conflicting claims, malformed model JSON, and conflicting release data. The template synthesizer
 * is replaced by rogue synthesizers that model a future LLM; the deterministic verifier must catch
 * every unsupported claim and the answer must never assert one.
 */
class RagGroundingSuite extends munit.FunSuite:

  private val Q        = "Who are the coauthors of Alice Adams?"
  private val fakeEvid = "evidence:v1:" + "e" * 64
  private val mallory  =
    StableIds.scholarChainResearcherId(1, SourceType.Dblp, "synthetic/mallory").toOption.get
  private def verdicts(a: RagOutcome.Answered) = a.checks.map(_.verdict)

  test("a fabricated evidence ID makes the claim UNSUPPORTED and keeps it out of the answer") {
    val rogue = Appending(_ =>
      List(DraftClaim(Assertion.CoauthoredWith(alice, carol, window, 7), List(fakeEvid)))
    )
    val a = answered(pipe(rogue).pipeline.answer(Q))
    assertEquals(verdicts(a), List(Verdict.Verified, Verdict.Unsupported))
    assert(a.checks(1).reasons.head.startsWith("V1: cited evidence not in bundle"), a.checks(1))
    val fab = a.response.claims(1)
    assertEquals(fab.verificationStatus, VerificationStatus.Unsupported)
    assertEquals(fab.evidenceIds, Nil, "a fabricated evidence ID was kept on the claim")
    assertEquals(fab.confidence, 0.0)
    assert(!a.response.answerText.contains(carol), a.response.answerText)
    assert(a.response.answerText.contains("1 claim(s) failed verification and were withheld"))
    assertEquals(a.response.overallStatus, VerificationStatus.PartiallySupported)
  }

  test("a fabricated researcher ID, a wrong count, a self-pair, and a foreign window are refused") {
    val rogue = Appending { in =>
      val ab = edgeEvidence(in, alice, bob)
      List(
        DraftClaim(Assertion.CoauthoredWith(alice, mallory, window, 3), List(ab)),
        DraftClaim(Assertion.CoauthoredWith(alice, bob, window, 99), List(ab)),
        DraftClaim(Assertion.CoauthoredWith(alice, alice, window, 3), List(ab)),
        DraftClaim(Assertion.CoauthoredWith(alice, bob, YearWindow(1990, 2030), 3), List(ab)),
        DraftClaim(Assertion.CoauthoredWith(alice, bob, window, 3), Nil)
      )
    }
    val a = answered(pipe(rogue).pipeline.answer(Q))
    assertEquals(verdicts(a).head, Verdict.Verified)
    val reasons = a.checks.drop(1).map(c => (c.verdict, c.reasons.head.take(2)))
    assertEquals(
      reasons,
      List(
        Verdict.Unsupported -> "V2",
        Verdict.Unsupported -> "V5",
        Verdict.Unsupported -> "V5",
        Verdict.Unsupported -> "V3",
        Verdict.Unsupported -> "V1"
      )
    )
    val shown = a.response.answerText.split("\n").toList
    assertEquals(shown.size, 2, a.response.answerText) // the verified claim plus the footer
    assert(!a.response.answerText.contains("99 coauthored"), a.response.answerText)
    assert(!a.response.answerText.contains(mallory), a.response.answerText)
  }

  test("when every claim is unsupported the runtime abstains instead of answering") {
    val rogue = Fixed(in =>
      Right(List(
        DraftClaim(
          Assertion.CoauthoredWith(alice, bob, window, 4),
          List(edgeEvidence(in, alice, bob))
        ),
        DraftClaim(
          Assertion.MetricValueIs(alice, CollaborationMetric.Degree, 9, window),
          List(fakeEvid)
        )
      ))
    )
    pipe(rogue).pipeline.answer(Q) match
      case RagOutcome.Abstained(Abstention.NoSupportedClaims, audit) =>
        assertEquals(audit.overallStatus, Some("unsupported"))
        assertEquals(audit.claimVerdicts.map(_.verdict), List("UNSUPPORTED", "UNSUPPORTED"))
        assertEquals(audit.answerHash, None)
      case other => fail(s"expected abstention, got $other")
    assert(pipe(Fixed(_ => Right(Nil))).pipeline.answer(Q).isInstanceOf[RagOutcome.Abstained])
    pipe(Fixed(_ => Left("model refused"))).pipeline.answer(Q) match
      case RagOutcome.Abstained(Abstention.SynthesisFailed(_), _) => ()
      case other => fail(s"expected synthesis failure, got $other")
  }

  test("a path claim with an invented hop is never VERIFIED and is marked when shown") {
    val q     = "What is the shortest path between Alice Adams and Erin Evans?"
    val rogue = Appending { in =>
      List(DraftClaim(
        Assertion.PathExists(List(alice, bob, carol, erin), window),
        List(edgeEvidence(in, alice, bob), edgeEvidence(in, bob, carol))
      ))
    }
    val a = answered(pipe(rogue).pipeline.answer(q))
    assertEquals(verdicts(a).head, Verdict.Verified, a.checks.head) // the real 4-hop path
    assertNotEquals(verdicts(a)(1), Verdict.Verified)
    val invented = a.response.claims(1).text
    a.response.answerText.split("\n").find(_.contains(invented)).foreach { line =>
      assert(line.startsWith("[partially supported]"), line)
    }
  }

  test("conflicting duplicate metric rows in a release: both claims CONFLICTING, never asserted") {
    val dup   = LocalFixture.metrics.find(_.researcherId == alice).get
    val bad   = dup.copy(metrics = List(MetricValue(CollaborationMetric.Degree, 7.0)))
    val facts = loadRelease(metrics = LocalFixture.metrics :+ bad).fold(e => fail(e), identity)
    // The reader accepts the duplicate (no uniqueness check); the verifier must catch it.
    pipe(facts = facts).pipeline.answer("What is the degree of Alice Adams?") match
      case RagOutcome.Abstained(Abstention.NoSupportedClaims, audit) =>
        assertEquals(audit.claimVerdicts.map(_.verdict).distinct, List("CONFLICTING"))
      case other => fail(s"expected abstention on conflicting facts, got $other")
  }

  test("D2: the API never presents a metric value the verifier marked CONFLICTING") {
    // Release with two degree rows for Alice (1 and 7, same window and policy). A ranking answer
    // withholds both of Alice's claims as CONFLICTING, but Presenter.researchers
    // (services app/Presenter.scala:57-60, 65) copies every Metric fact in the bundle into the
    // researcher view, so one of the contradicted values is shown as her distinctCoauthorCount.
    val dup   = LocalFixture.metrics.find(_.researcherId == alice).get
    val bad   = dup.copy(metrics = List(MetricValue(CollaborationMetric.Degree, 7.0)))
    val facts = loadRelease(metrics = LocalFixture.metrics :+ bad).fold(e => fail(e), identity)
    ApiKit.withEnv(ApiKit.env(deps = _.copy(facts = facts))) { e =>
      val r = ApiKit.json(e.post("""{"question": "Who are the top 20 researchers by degree?"}"""))
      assertEquals(r("status").str, "ANSWERED", r)
      val aliceClaims = r("claims").arr.filter(_("text").str.contains(alice))
      assertEquals(aliceClaims.map(_("verificationStatus").str).toSet, Set("CONFLICTING"))
      assert(!r("answer")("summary").str.contains(alice), r("answer")("summary").str)
      val view = r("answer")("researchers").arr.find(_("researcherId").str == alice)
      view.foreach { v =>
        assertEquals(
          v("distinctCoauthorCount"),
          ujson.Null,
          s"conflicted degree shown as a fact: ${v("distinctCoauthorCount")}"
        )
        assert(
          !v("metrics").obj.contains("degree"),
          s"conflicted degree in metrics: ${v("metrics")}"
        )
      }
    }
  }

  test(
    "malformed model JSON: every prefix, wrong schema, unknown type, and excess claims refused"
  ) {
    val good = write(DraftAnswer(
      RagVersions.AnswerClaimSchema,
      List(DraftClaim(Assertion.CoauthoredWith(alice, bob, window, 3), List(fakeEvid)))
    ))
    assert(DraftAnswer.decode(good).isRight, good)
    (0 until good.length).foreach { n =>
      assert(DraftAnswer.decode(good.take(n)).isLeft, s"prefix $n accepted: ${good.take(n)}")
    }
    val many = write(DraftAnswer(
      RagVersions.AnswerClaimSchema,
      List.fill(DraftAnswer.MaxClaims + 1)(
        DraftClaim(Assertion.CoauthoredWith(alice, bob, window, 3), Nil)
      )
    ))
    val refused = List(
      "",
      "[]",
      "{}",
      "not json at all",
      "```json\n" + good + "\n```",
      good.replace(RagVersions.AnswerClaimSchema, "rag-answer-claim-schema-v0"),
      good.replace("CoauthoredWith", "CoauthoredWithEveryone"),
      good.replace("\"claims\"", "\"claimz\""),
      many
    )
    refused.foreach(t => assert(DraftAnswer.decode(t).isLeft, s"accepted: ${t.take(120)}"))
  }

  test("D6: JSON nulls in model output are refused by the decoder, never thrown") {
    // DraftAnswer.decode (rag runtime/Synthesis.scala:60-67) promises Left for unreadable output,
    // but uPickle reads a JSON null as a null DraftAnswer (or null claims), and the schema check
    // then dereferences it: decode("null") throws NullPointerException instead of returning Left.
    val schema = RagVersions.AnswerClaimSchema
    val inputs = List(
      "null",
      s"""{"schema": "$schema", "claims": null}""",
      s"""{"schema": "$schema", "claims": [null]}"""
    )
    val bad = inputs.flatMap { in =>
      scala.util.Try(DraftAnswer.decode(in)) match
        case scala.util.Success(Left(_))                                                  => None
        case scala.util.Success(Right(d)) if d.claims != null && !d.claims.contains(null) => None
        case other => Some(s"$in -> $other")
    }
    assertEquals(bad, Nil)
    // Downstream: the workflow abstains (synthesis failed); the API answers 502, not 500.
    val outcome = scala.util.Try(pipe(Decoding("null")).pipeline.answer(Q))
    assert(
      outcome.toOption.exists {
        case RagOutcome.Abstained(Abstention.SynthesisFailed(_), _) => true
        case _                                                      => false
      },
      s"pipeline outcome for model output 'null': $outcome"
    )
  }

  test("decoded model output with fabricated IDs is caught by the verifier; bad JSON abstains") {
    val fabricated = write(DraftAnswer(
      RagVersions.AnswerClaimSchema,
      List(
        DraftClaim(Assertion.CoauthoredWith(alice, mallory, window, 3), List(fakeEvid)),
        DraftClaim(Assertion.InComponent(alice, "cc-invented", 99, window), List(fakeEvid))
      )
    ))
    pipe(Decoding(fabricated)).pipeline.answer(Q) match
      case RagOutcome.Abstained(Abstention.NoSupportedClaims, audit) =>
        assert(audit.claimVerdicts.forall(_.verdict == "UNSUPPORTED"), audit.claimVerdicts)
      case other => fail(s"expected abstention, got $other")
    pipe(Decoding("{\"schema\": ")).pipeline.answer(Q) match
      case RagOutcome.Abstained(Abstention.SynthesisFailed(d), _) =>
        assertEquals(d, "malformed answer JSON")
      case other => fail(s"expected synthesis failure, got $other")
    // Through the API, malformed model output is a typed 502, not a 200 and not a 500.
    ApiKit.withEnv(ApiKit.env(deps = _.copy(synthesizer = Decoding("{\"schema\": ")))) { e =>
      val r = e.post(ApiKit.Coauthors)
      assertEquals((r.status, ApiKit.json(r)("error").str), (502, "DOWNSTREAM_FAILURE"))
      assert(ApiKit.json(r)("message").str.contains("synthesis"), r.body)
    }
  }

  test("unsupported claims through the API: shown in claims as UNSUPPORTED, absent from summary") {
    val rogue = Appending { in =>
      List(DraftClaim(
        Assertion.CoauthoredWith(alice, bob, window, 42),
        List(edgeEvidence(in, alice, bob))
      ))
    }
    ApiKit.withEnv(ApiKit.env(deps = _.copy(synthesizer = rogue))) { e =>
      val r = ApiKit.json(e.post(ApiKit.Coauthors))
      assertEquals(r("status").str, "ANSWERED")
      val statuses = r("claims").arr.map(c => c("verificationStatus").str).toList
      assertEquals(statuses, List("VERIFIED", "UNSUPPORTED"))
      val bad = r("claims").arr(1)
      assertEquals(bad("confidence").num, 0.0)
      assert(bad("uncertainty").str.contains("V5"), bad("uncertainty").str)
      assert(!r("answer")("summary").str.contains("42 coauthored"), r("answer")("summary").str)
      assertEquals(r("overallStatus").str, "PARTIALLY_SUPPORTED")
      // The unsupported claim keeps only in-bundle evidence, so a reader can inspect it.
      val bundle = r("evidence").arr.map(_("evidenceId").str).toSet
      assert(bad("evidenceIds").arr.map(_.str).forall(bundle), bad)
    }
  }

  test("semantic context cites as nothing: a claim resting only on a text chunk is UNSUPPORTED") {
    val ix = semanticIndex()
    ix.ingestion().ingest(
      runId,
      List(summary(alice, "Alice Adams profile: frequent collaborator of Gina Green, 99 works.")),
      supplier(ix.embedder)
    )
    val rogue = Appending { in =>
      val chunk = in.bundle.semanticEvidenceIds.head
      List(DraftClaim(Assertion.CoauthoredWith(alice, bob, window, 3), List(chunk)))
    }
    val a = answered(pipe(rogue, semantic = Some(ix.stage())).pipeline.answer(Q))
    assertEquals(verdicts(a), List(Verdict.Verified, Verdict.Unsupported))
    assertEquals(
      a.checks(1).reasons,
      List("V1: only semantic context cited; it is not graph evidence")
    )
  }

  test("R-09-11: a VERIFIED graph claim never lists a semantic text chunk as its evidence") {
    // Phase 09 backlog R-09-11: "A non-template synthesizer can keep a semantic ID on a verified
    // claim." ClaimVerifier.verify (rag runtime/ClaimVerifier.scala:52-71) lets the claim past the
    // V1 evidence gate (:59-63; the semantic-only check is :62-63) once any graph fact is cited, so
    // the answer-assembly filter in GraphRagPipeline.assemble (runtime/GraphRagPipeline.scala:241,
    // filter :257-262) must drop the semantic ID; otherwise the answer presents a
    // vector-similarity chunk as evidence for a verified coauthorship claim.
    val ix = semanticIndex()
    ix.ingestion().ingest(
      runId,
      List(summary(alice, "Alice Adams profile: coauthor network summary.")),
      supplier(ix.embedder)
    )
    val rogue = Fixed { in =>
      Right(List(DraftClaim(
        Assertion.CoauthoredWith(alice, bob, window, 3),
        List(edgeEvidence(in, alice, bob), in.bundle.semanticEvidenceIds.head)
      )))
    }
    val a        = answered(pipe(rogue, semantic = Some(ix.stage())).pipeline.answer(Q))
    val semantic = a.bundle.semanticEvidenceIds.toSet
    val claim    = a.response.claims.head
    assertEquals(claim.verificationStatus, VerificationStatus.Supported)
    assert(
      !claim.evidenceIds.exists(semantic),
      s"VERIFIED claim cites semantic context ${claim.evidenceIds.filter(semantic)}"
    )
  }

  test("common names at runtime: two exact matches abstain with every candidate listed") {
    val wang1 =
      StableIds.scholarChainResearcherId(1, SourceType.Dblp, "synthetic/wang1").toOption.get
    val wang2 =
      StableIds.scholarChainResearcherId(1, SourceType.Dblp, "synthetic/wang2").toOption.get
    val prov  = LocalFixture.researchers.head.provenance
    val extra = List(
      CanonicalResearcher(1, wang1, "Wei Wang", None, Nil, Nil, prov),
      CanonicalResearcher(1, wang2, "Wéi Wang", None, List("W. Wang"), Nil, prov)
    )
    val facts =
      loadRelease(researchers = LocalFixture.researchers ++ extra).fold(e => fail(e), identity)
    val p = pipe(facts = facts).pipeline
    List("Wei Wang", "WEI WANG", "wei-wang", "Wéi Wang").foreach { name =>
      p.answer(s"Who are the coauthors of $name?") match
        case RagOutcome.Abstained(Abstention.AmbiguousResearcher(_, cs, n), audit) =>
          assertEquals(cs.map(_.researcherId).toSet, Set(wang1, wang2), name)
          assertEquals(n, 2)
          assertEquals(audit.candidateIds.toSet, Set(wang1, wang2))
        case other => fail(s"$name: expected an ambiguous abstention, got $other")
    }
    // Reordered surname-first input never resolves to someone else.
    p.answer("Who are the coauthors of Wang Wei?") match
      case RagOutcome.Abstained(Abstention.UnknownResearcher(_), _) => ()
      case other => fail(s"expected unknown researcher, got $other")
  }

  test("missing metadata: absent metrics, graph metrics, and communities abstain, never guess") {
    val noGina = LocalFixture.metrics.filterNot(_.researcherId == LocalFixture.gina)
    val f1     = loadRelease(metrics = noGina).fold(e => fail(e), identity)
    pipe(facts = f1).pipeline.answer("What is the degree of Gina Green?") match
      case RagOutcome.Abstained(Abstention.Gap(RetrievalGap.NoEvidence(_)), _) => ()
      case other => fail(s"expected a no-evidence gap, got $other")
    val f2 = loadRelease(graphMetrics = None, communities = None).fold(e => fail(e), identity)
    assertEquals(f2.graphMetrics, Nil)
    val p2 = pipe(facts = f2).pipeline
    List(
      "Which community is Alice Adams in?",
      "Which component is Alice Adams in?",
      "Who are the bridge researchers?"
    ).foreach { q =>
      p2.answer(q) match
        case RagOutcome.Abstained(Abstention.Gap(RetrievalGap.NoEvidence(_)), _) => ()
        case other => fail(s"$q: expected a no-evidence gap, got $other")
    }
    // A metric nobody has in the window is unavailable, not zero.
    pipe(facts = f1).pipeline.answer("What is the pagerank of Alice Adams?") match
      case RagOutcome.Abstained(Abstention.Gap(RetrievalGap.MetricUnavailable(_)), _) => ()
      case other => fail(s"expected metric-unavailable, got $other")
    // Through the API, unknown values are JSON null, never invented zeros.
    ApiKit.withEnv(ApiKit.env(deps = _.copy(facts = f2))) { e =>
      val r = ApiKit.json(e.post(ApiKit.Coauthors))
      r("answer")("researchers").arr.foreach { v =>
        List("publicationCount", "componentId", "communityId", "bridgeMetric").foreach { k =>
          assertEquals(v(k), ujson.Null, s"$k of ${v("researcherId")}")
        }
      }
      val node = ApiKit.json(e.get(s"/v1/graph/nodes/$dave"))
      assertEquals(node("memberships").arr.size, 0)
      assertEquals(node("orcid"), ujson.Null)
    }
  }

  test("disconnected and empty graphs: no path or coauthor is ever invented") {
    pipe().pipeline.answer("What is the shortest path between Alice Adams and Frank Fox?") match
      case RagOutcome.Abstained(Abstention.Gap(RetrievalGap.Disconnected), audit) =>
        assert(audit.graphEvidenceIds.nonEmpty, "the disconnection is not evidenced")
        assertEquals(audit.claimVerdicts, Nil)
      case other => fail(s"expected a disconnected gap, got $other")
    val facts = LocalFixture.build().dependencies.facts
    val empty =
      edu.uic.cs441.scholarchain.storage.fakes.InMemoryGraphStore(Nil, "graph/coauthor-edges/v1")
    val p = GraphRagPipeline(
      QueryPlanner(PlannerConfig(window)),
      facts,
      GraphRetriever(empty, facts, RetrievalConfig(facts.releaseRunId, LocalFixture.Policy)),
      None,
      TemplateSynthesizer,
      () => 0L
    )
    List(
      "Who are the coauthors of Alice Adams?",
      "Did Alice Adams collaborate with Bob Brown?",
      "What is the shortest path between Alice Adams and Erin Evans?"
    ).foreach { q =>
      p.answer(q) match
        case RagOutcome.Abstained(Abstention.Gap(_), audit) =>
          assertEquals(audit.claimVerdicts, Nil, q)
        case other => fail(s"$q on an empty graph: $other")
    }
    ApiKit.withEnv(ApiKit.env()) { e =>
      val n = ApiKit.json(e.get(s"/v1/graph/nodes/${LocalFixture.frank}/neighbors?depth=3"))
      assertEquals(n("items").arr.map(_("nodeId").str).toList, List(LocalFixture.gina))
    }
  }

  test("graph store results that are not simple chains are never used as a path") {
    import edu.uic.cs441.scholarchain.storage.graph.*
    // A store that returns a cyclic row and a row that does not reach the target (openCypher can
    // revisit nodes; R-07-12): the retriever re-validates and abstains rather than answer.
    final class Corrupt(inner: GraphStore) extends GraphStore:
      def directCoauthors(q: DirectCoauthorsQuery) = inner.directCoauthors(q)
      def boundedPaths(q: BoundedPathQuery)        =
        Right(List(
          PathRow(List(alice, bob, alice, bob), List("e1", "e2", "e3")),
          PathRow(List(alice, bob), List("e1")),
          PathRow(List(alice), Nil)
        ))
    val fx = LocalFixture.build()
    val r  = GraphRetriever(
      Corrupt(fx.graph),
      fx.dependencies.facts,
      RetrievalConfig(fx.dependencies.facts.releaseRunId, LocalFixture.Policy)
    )
    val got = r.shortestPath(alice, erin, window, 4, 5).fold(e => fail(e), identity)
    assert(got.gap.isDefined, got)
    assertEquals(got.pathNodeIds, Nil)
  }
