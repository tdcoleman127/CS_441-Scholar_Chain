package edu.uic.cs441.scholarchain.rag

import edu.uic.cs441.scholarchain.core.model.*
import edu.uic.cs441.scholarchain.rag.runtime.*
import edu.uic.cs441.scholarchain.storage.StorageError
import edu.uic.cs441.scholarchain.storage.fakes.InMemoryGraphStore
import edu.uic.cs441.scholarchain.storage.graph.*

/**
 * F1 repair: the temporal-window pair check must not depend on a researcher's degree, and a capped
 * direct-coauthor list must never produce a "no edge" negative.
 */
class PairConfirmationSuite extends munit.FunSuite:
  import RuntimeFixtures.*

  private def highDegreeEnv() =
    env(extraEdges = highDegreeEdges, extraResearchers = highDegreeNames)

  private val allowed: Set[String] =
    GraphQueries.BoundedPathTemplates.toSet + GraphQueries.DirectCoauthorsTemplate

  private def boundedAndTemplated(e: Env): Unit =
    e.graph.queries.foreach { q =>
      assert(allowed.contains(q.text), q.text)
      assert(q.timeoutMillis <= GraphQueries.MaxTimeoutMillis)
      q.parameters.foreach {
        case ("limit", Param.Int32(l)) =>
          assert(l >= 1 && l <= GraphQueries.MaxCoauthorLimit, s"limit $l")
        case (_, Param.Str(v)) => assert(v.startsWith("researcher:"), s"non-ID parameter $v")
        case _                 => ()
      }
    }

  test("fixture precondition: Solo and Max fall outside Hub's capped top-100 coauthor list") {
    val g    = InMemoryGraphStore(edges ++ highDegreeEdges, "graph/coauthor-edges/v1")
    val rows = GraphQueries
      .directCoauthors(hub, W, GraphQueries.MaxCoauthorLimit)
      .flatMap(g.directCoauthors)
      .toOption
      .get
    assertEquals(rows.size, GraphQueries.MaxCoauthorLimit)
    assert(rows.forall(_.jointPublicationCount == 2))
    assert(!rows.exists(r => r.coauthorId == solo || r.coauthorId == maxR))
  }

  test("high-degree pair: the edge outside A's top 100 is confirmed with its exact count") {
    val e = highDegreeEnv()
    e.pipeline.answer("Did Hub Hughes collaborate with Solo Sato between 2015 and 2020?") match
      case RagOutcome.Answered(resp, bundle, checks, audit) =>
        assertEquals(audit.operation, Some("temporal-window"))
        assertEquals(audit.resolvedIds, List(hub, solo))
        assertEquals(checks.map(_.claim.assertion), List(Assertion.CoauthoredWith(hub, solo, W, 1)))
        assertEquals(checks.map(_.verdict), List(Verdict.Verified))
        assertEquals(resp.claims.map(_.verificationStatus), List(VerificationStatus.Supported))
        assertEquals(resp.overallStatus, VerificationStatus.Supported)
        assertEquals(audit.reason, None)
        val edgeFacts = bundle.facts.collect { case f: GraphFact.Edge => f }
        assertEquals(edgeFacts.map(_.edgeId), List(edgeId(hub, solo)))
        assertEquals(edgeFacts.map(_.coauthoredWorkCount), List(1))
        assert(edgeFacts.forall(_.provenance.pipelineRunId == RagFixtures.runId))
        assert(edgeFacts.forall(_.policyVersion == policy))
        assert(resp.answerText.contains("(1 coauthored works)"), resp.answerText)
      case other => fail(s"expected a verified answer, got ${other.productPrefix} ${other.audit}")
    // Depth-1 path confirms the edge; Hub's list is capped, so Solo's list supplies the count.
    assertEquals(
      e.graph.queries.map(_.text).toList,
      List(
        GraphQueries.BoundedPathTemplates(0),
        GraphQueries.DirectCoauthorsTemplate,
        GraphQueries.DirectCoauthorsTemplate
      )
    )
    boundedAndTemplated(e)
  }

  test("truncation: an edge outside both capped lists yields a truncation gap, never no-edge") {
    val e = highDegreeEnv()
    e.pipeline.answer("Did Hub Hughes collaborate with Max Moore between 2015 and 2020?") match
      case RagOutcome.Abstained(Abstention.Gap(gap), audit) =>
        assertEquals(
          gap,
          RetrievalGap.CoauthorListTruncated(
            List(edgeId(hub, maxR)),
            List(hub, maxR),
            GraphQueries.MaxCoauthorLimit
          )
        )
        assertEquals(audit.reason, Some("insufficient-evidence:coauthor-list-truncated"))
        assertNotEquals(audit.reason, Some("insufficient-evidence:no-evidence"))
        assertEquals(audit.claimVerdicts, Nil)
        assertEquals(audit.answerHash, None)
      case other => fail(s"expected a truncation gap, got ${other.productPrefix} ${other.audit}")
    boundedAndTemplated(e)
  }

  test("a real absence is reported only from the untruncated depth-1 path read") {
    val e = highDegreeEnv()
    e.pipeline.answer("Did Hub Hughes collaborate with Lone Lee between 2015 and 2020?") match
      case RagOutcome.Abstained(Abstention.Gap(RetrievalGap.NoEvidence(d)), audit) =>
        assertEquals(d, "no edge between the pair in window")
        assertEquals(audit.reason, Some("insufficient-evidence:no-evidence"))
      case other => fail(s"expected no-evidence, got ${other.productPrefix} ${other.audit}")
    // The negative rests on the pair-filtered path template alone; no capped list was consulted.
    assertEquals(e.graph.queries.map(_.text).toList, List(GraphQueries.BoundedPathTemplates(0)))
  }

  test("templates disagree: a confirmed edge missing from complete lists is a typed gap") {
    val eid = edgeId(alice, bob)
    object Disagreeing extends GraphStore:
      def directCoauthors(q: DirectCoauthorsQuery): Either[StorageError, List[CoauthorRow]] =
        Right(Nil)
      def boundedPaths(q: BoundedPathQuery): Either[StorageError, List[PathRow]] =
        Right(List(PathRow(List(q.fromId, q.toId), List(eid))))
    val facts = env().facts
    val r     = GraphRetriever(Disagreeing, facts, RetrievalConfig(RagFixtures.runId, policy))
    assertEquals(
      r.collaborationInWindow(alice, bob, W),
      Right(Retrieved(Nil, Some(RetrievalGap.EdgeAttributesUnavailable(List(eid)))))
    )
  }

  test("high-degree pair answers are deterministic") {
    def run() = highDegreeEnv().pipeline.answer(
      "Did Hub Hughes collaborate with Solo Sato between 2015 and 2020?"
    )
    (run(), run()) match
      case (RagOutcome.Answered(a, _, _, x), RagOutcome.Answered(b, _, _, y)) =>
        assertEquals(a.responseId, b.responseId)
        assertEquals(x.answerHash, y.answerHash)
      case other => fail(s"expected answers, got $other")
  }
