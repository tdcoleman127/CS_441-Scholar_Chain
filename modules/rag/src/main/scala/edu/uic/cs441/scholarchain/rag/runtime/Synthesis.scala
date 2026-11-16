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

/**
 * Synthesizer output. `schema` must equal [[RagVersions.AnswerClaimSchema]]. Construct from model
 * text only through [[DraftAnswer.decode]].
 */
final case class DraftAnswer(schema: String, claims: List[DraftClaim]) derives ReadWriter

object DraftAnswer:
  val MaxClaims: Int = 25

  /**
   * Strict, total decoder for a model's JSON output: unreadable JSON, a JSON `null` anywhere (the
   * whole answer, `claims`, a claim, an assertion, a field, or a list element), a non-finite
   * number, a wrong schema version, an unknown assertion type, or too many claims are rejected
   * (Left). It never throws. Decoding never verifies: every decoded claim still goes through
   * [[ClaimVerifier]].
   */
  def decode(json: String): Either[String, DraftAnswer] =
    Try {
      Option(json)
        .toRight("malformed answer JSON")
        .flatMap(j => Try(read[DraftAnswer](j)).toEither.left.map(_ => "malformed answer JSON"))
        .flatMap(validate)
    }.toEither.left.map(_ => "malformed answer JSON").flatten

  /**
   * Deep structural check of a decoded or synthesized draft (rag-answer-claim-schema-v1): no null
   * at any depth, finite numbers, the expected schema, and at most [[MaxClaims]] claims. Total: it
   * returns Left for every malformed value and never throws. The pipeline applies it to every
   * synthesizer's output, so a model client that skips [[decode]] is checked too.
   */
  def validate(d: DraftAnswer): Either[String, DraftAnswer] =
    Try {
      if d == null then Left("answer is null")
      else if d.schema == null then Left("schema is null")
      else if d.schema != RagVersions.AnswerClaimSchema then
        Left(s"unexpected schema '${TextRules.clean(d.schema, 80)}'")
      else if d.claims == null then Left("claims is null")
      else if d.claims.size > MaxClaims then Left(s"more than $MaxClaims claims")
      else
        d.claims.zipWithIndex.iterator
          .flatMap((c, i) => claimProblem(c).map(p => s"claims[$i]: $p"))
          .nextOption()
          .toLeft(d)
    }.toEither.left.map(_ => "malformed answer").flatten

  private def claimProblem(c: DraftClaim): Option[String] =
    if c == null then Some("claim is null")
    else if c.evidenceIds == null then Some("evidenceIds is null")
    else if c.evidenceIds.contains(null) then Some("evidenceIds contains null")
    else if c.assertion == null then Some("assertion is null")
    else assertionProblem(c.assertion)

  private def assertionProblem(a: Assertion): Option[String] =
    def str(name: String, v: String)   = Option.when(v == null)(s"$name is null")
    def window(w: YearWindow)          = Option.when(w == null)("window is null")
    def metric(m: CollaborationMetric) = Option.when(m == null)("metric is null")
    val problems: List[Option[String]] = a match
      case Assertion.CoauthoredWith(x, y, w, _) =>
        List(str("first", x), str("second", y), window(w))
      case Assertion.PathExists(ns, w) =>
        List(
          Option.when(ns == null)("nodeIds is null"),
          Option.when(ns != null && ns.contains(null))("nodeIds contains null"),
          window(w)
        )
      case Assertion.MetricValueIs(r, m, v, w) =>
        List(
          str("researcherId", r),
          metric(m),
          Option.when(v.isNaN || v.isInfinite)("value is not finite"),
          window(w)
        )
      case Assertion.MemberOfCommunity(r, c, w) =>
        List(str("researcherId", r), str("communityId", c), window(w))
      case Assertion.BridgeScore(r, _, _, w) => List(str("researcherId", r), window(w))
      case Assertion.InComponent(r, c, _, w) =>
        List(str("researcherId", r), str("componentId", c), window(w))
    problems.flatten.headOption

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
