package edu.uic.cs441.scholarchain.rag.runtime

import edu.uic.cs441.scholarchain.core.model.*
import edu.uic.cs441.scholarchain.rag.semantic.TextRules
import upickle.default.{read, ReadWriter}
import scala.util.Try

/**
 * A typed, checkable assertion (rag-answer-claim-schema-v1). Claim text is always rendered from the
 * assertion by [[ClaimText]], never taken from a model or from retrieved text, so the verifier
 * checks exactly what the reader sees.
 */
enum Assertion derives ReadWriter:
  case CoauthoredWith(first: String, second: String, window: YearWindow, coauthoredWorkCount: Int)
  case PathExists(nodeIds: List[String], window: YearWindow)
  case MetricValueIs(
      researcherId: String,
      metric: CollaborationMetric,
      value: Double,
      window: YearWindow
  )
  case MemberOfCommunity(researcherId: String, communityId: String, window: YearWindow)
  case BridgeScore(
      researcherId: String,
      crossCommunityDegree: Long,
      distinctCommunitiesTouched: Long,
      window: YearWindow
  )
  case InComponent(
      researcherId: String,
      componentId: String,
      componentSize: Long,
      window: YearWindow
  )

  def window: YearWindow

  def researcherIds: List[String] = this match
    case CoauthoredWith(a, b, _, _) => List(a, b)
    case PathExists(ns, _)          => ns
    case MetricValueIs(r, _, _, _)  => List(r)
    case MemberOfCommunity(r, _, _) => List(r)
    case BridgeScore(r, _, _, _)    => List(r)
    case InComponent(r, _, _, _)    => List(r)

/** A drafted claim: an assertion plus the evidence IDs it cites. */
final case class DraftClaim(assertion: Assertion, evidenceIds: List[String]) derives ReadWriter

/** Synthesizer output. `schema` must equal [[RagVersions.AnswerClaimSchema]]. */
final case class DraftAnswer(schema: String, claims: List[DraftClaim]) derives ReadWriter

object DraftAnswer:
  val MaxClaims: Int = 25

  /**
   * Strict decoder for a future model's JSON output: unreadable JSON, a wrong schema version, an
   * unknown assertion type, or too many claims are rejected (Left). Decoding never verifies: every
   * decoded claim still goes through [[ClaimVerifier]].
   */
  def decode(json: String): Either[String, DraftAnswer] =
    Try(read[DraftAnswer](json)).toEither.left
      .map(_ => "malformed answer JSON")
      .flatMap { d =>
        if d.schema != RagVersions.AnswerClaimSchema then Left(s"unexpected schema '${d.schema}'")
        else if d.claims.size > MaxClaims then Left(s"more than $MaxClaims claims")
        else Right(d)
      }

/** Deterministic claim text. Names are untrusted data: always quoted and bounded. */
object ClaimText:
  private def w(x: YearWindow) = s"${x.startYear}-${x.endYear}"

  def render(a: Assertion, names: Map[String, String]): String =
    def n(id: String) = names.get(id).fold(id)(nm => s"${TextRules.quote(nm, 120)} ($id)")
    a match
      case Assertion.CoauthoredWith(x, y, win, c) =>
        s"${n(x)} coauthored with ${n(y)} in window ${w(win)} ($c coauthored works)."
      case Assertion.PathExists(ns, win) =>
        s"Collaboration path in window ${w(win)} with ${ns.size - 1} hops: ${ns.map(n).mkString(" -> ")}."
      case Assertion.MetricValueIs(r, m, v, win) =>
        s"${n(r)} has ${m.wire} ${TextRules.num(v)} in window ${w(win)}."
      case Assertion.MemberOfCommunity(r, c, win) =>
        s"${n(r)} is a member of community $c in window ${w(win)}."
      case Assertion.BridgeScore(r, cross, distinct, win) =>
        s"${n(r)} has cross-community degree $cross across $distinct communities in window ${w(win)}."
      case Assertion.InComponent(r, c, size, win) =>
        s"${n(r)} is in component ${TextRules.quote(c, 120)} of size $size in window ${w(win)}."

/** Input to synthesis: the resolved plan, the bounded bundle, and the retrieved path (if any). */
final case class SynthesisInput(
    operation: PlanOperation[String],
    window: YearWindow,
    bundle: RagEvidenceBundle,
    pathNodeIds: List[String]
)

/** Model port for answer synthesis. Only [[TemplateSynthesizer]] is implemented in this phase. */
trait AnswerSynthesizer:
  def version: String
  def synthesize(in: SynthesisInput): Either[String, DraftAnswer]

/**
 * Template synthesizer: emits one typed claim per graph fact that answers the operation, each
 * citing that fact's evidence ID. It reads only graph evidence, never semantic context, so
 * retrieved text cannot create a claim.
 */
object TemplateSynthesizer extends AnswerSynthesizer:
  import GraphFact.*
  import PlanOperation.*

  val version: String = RagVersions.TemplateSynthesis

  def synthesize(in: SynthesisInput): Either[String, DraftAnswer] =
    val g                                               = in.bundle.graph
    def edgeClaims(keep: Edge => Boolean, self: String) = g.collect {
      case GraphEvidence(e: Edge, item) if keep(e) =>
        val other = if e.low == self then e.high else e.low
        DraftClaim(
          Assertion.CoauthoredWith(self, other, in.window, e.coauthoredWorkCount),
          List(item.evidenceId)
        )
    }
    val claims = in.operation match
      case DirectCoauthors(r, _)       => edgeClaims(e => e.low == r || e.high == r, r)
      case CollaborationInWindow(a, b) =>
        edgeClaims(e => Set(e.low, e.high) == Set(a, b), a)
      case ShortestPath(_, _, _, _) if in.pathNodeIds.size >= 2 =>
        val hops  = in.pathNodeIds.zip(in.pathNodeIds.tail).map(Set(_, _))
        val cited = g.collect {
          case GraphEvidence(e: Edge, item) if hops.contains(Set(e.low, e.high)) => item.evidenceId
        }
        if cited.isEmpty then Nil
        else List(DraftClaim(Assertion.PathExists(in.pathNodeIds, in.window), cited))
      case ShortestPath(_, _, _, _) => Nil
      case MetricLookup(r, m)       =>
        g.collect {
          case GraphEvidence(f: Metric, item) if f.researcherId == r && f.metric == m =>
            DraftClaim(Assertion.MetricValueIs(r, m, f.value, f.window), List(item.evidenceId))
        }
      case MetricRanking(m, _) =>
        g.collect {
          case GraphEvidence(f: Metric, item) if f.metric == m =>
            DraftClaim(
              Assertion.MetricValueIs(f.researcherId, m, f.value, f.window),
              List(item.evidenceId)
            )
        }
      case CommunityOf(r) =>
        g.collect {
          case GraphEvidence(f: Community, item) if f.researcherId == r =>
            DraftClaim(
              Assertion.MemberOfCommunity(r, f.communityId, f.window),
              List(item.evidenceId)
            )
        }
      case ComponentOf(r) =>
        g.collect {
          case GraphEvidence(f: Component, item) if f.researcherId == r =>
            DraftClaim(
              Assertion.InComponent(r, f.componentId, f.componentSize, f.window),
              List(item.evidenceId)
            )
        }
      case Bridges(_) =>
        g.collect { case GraphEvidence(f: Bridge, item) =>
          DraftClaim(
            Assertion.BridgeScore(
              f.researcherId,
              f.crossCommunityDegree,
              f.distinctCommunitiesTouched,
              f.window
            ),
            List(item.evidenceId)
          )
        }
    Right(DraftAnswer(RagVersions.AnswerClaimSchema, claims.take(DraftAnswer.MaxClaims)))
