package edu.uic.cs441.scholarchain.rag.runtime

import edu.uic.cs441.scholarchain.core.ids.{IdFormat, IdKind}
import edu.uic.cs441.scholarchain.core.model.{VerificationStatus, YearWindow}

/** Claim verdicts (claim-verifier-rules-v1) and the core status each one is recorded as. */
enum Verdict(val wire: String, val core: VerificationStatus):
  case Verified extends Verdict("VERIFIED", VerificationStatus.Supported)
  case PartiallySupported
      extends Verdict("PARTIALLY_SUPPORTED", VerificationStatus.PartiallySupported)
  case Unsupported extends Verdict("UNSUPPORTED", VerificationStatus.Unsupported)
  case Conflicting extends Verdict("CONFLICTING", VerificationStatus.Contradicted)

final case class ClaimCheck(claim: DraftClaim, verdict: Verdict, reasons: List[String])

/**
 * Deterministic claim verifier (claim-verifier-rules-v1). Rules, applied in order; the first
 * failing rule decides:
 *
 *   - V1 evidence: the claim cites at least one ID, and every cited ID is in the bundle. A cited ID
 *     outside the bundle (fabricated) makes the claim UNSUPPORTED. Semantic-context IDs are in the
 *     bundle but never count as support.
 *   - V2 identity: every researcher ID in the assertion is a well-formed researcher ID that some
 *     graph fact in the bundle mentions.
 *   - V3 window: the assertion's window lies inside the plan window.
 *   - V4 conflict: two graph facts in the bundle with the same key (pair and window; researcher,
 *     metric, and window; researcher, algorithm, and window) but different values make the claim
 *     CONFLICTING.
 *   - V5 value: a cited graph fact matches the assertion exactly: endpoints, numbers by exact
 *     equality, community and component IDs, and a fact window inside the assertion window.
 *   - V6 path: the node list is a simple chain of at least one hop and every hop is a cited edge in
 *     the window. All hops: VERIFIED; some: PARTIALLY_SUPPORTED; none: UNSUPPORTED.
 */
object ClaimVerifier:
  import GraphFact.*
  import Assertion.*

  private def within(inner: YearWindow, outer: YearWindow): Boolean =
    inner.startYear >= outer.startYear && inner.endYear <= outer.endYear

  private def mentions(f: GraphFact, id: String): Boolean = f match
    case e: Edge      => e.low == id || e.high == id
    case m: Metric    => m.researcherId == id
    case c: Community => c.researcherId == id
    case b: Bridge    => b.researcherId == id
    case c: Component => c.researcherId == id
    case i: Identity  => i.researcherId == id

  private def unsupported(c: DraftClaim, why: String) =
    ClaimCheck(c, Verdict.Unsupported, List(why))

  def verify(claim: DraftClaim, bundle: RagEvidenceBundle, planWindow: YearWindow): ClaimCheck =
    val graphById  = bundle.graphById
    val semantic   = bundle.semanticEvidenceIds.toSet
    val cited      = claim.evidenceIds.distinct
    val missing    = cited.filterNot(id => graphById.contains(id) || semantic.contains(id))
    val citedFacts = cited.flatMap(graphById.get)
    val a          = claim.assertion
    if cited.isEmpty then unsupported(claim, "V1: no evidence cited")
    else if missing.nonEmpty then
      unsupported(claim, s"V1: cited evidence not in bundle: ${missing.sorted.mkString(",")}")
    else if citedFacts.isEmpty then
      unsupported(claim, "V1: only semantic context cited; it is not graph evidence")
    else
      a.researcherIds.find(id =>
        !IdFormat.isValid(IdKind.Researcher, id) || !bundle.facts.exists(mentions(_, id))
      ) match
        case Some(id) => unsupported(claim, s"V2: identity $id not in graph evidence")
        case None if !within(a.window, planWindow) =>
          unsupported(claim, "V3: claim window outside the plan window")
        case None => checkValue(claim, a, citedFacts, bundle.facts)

  private def decide(
      claim: DraftClaim,
      conflicting: Boolean,
      matched: Boolean,
      what: String
  ): ClaimCheck =
    if conflicting then
      ClaimCheck(claim, Verdict.Conflicting, List(s"V4: bundle facts disagree on $what"))
    else if matched then ClaimCheck(claim, Verdict.Verified, Nil)
    else unsupported(claim, s"V5: no cited fact matches the $what")

  private def checkValue(
      claim: DraftClaim,
      a: Assertion,
      cited: List[GraphFact],
      all: List[GraphFact]
  ): ClaimCheck = a match
    case CoauthoredWith(x, y, win, count) =>
      val pair = Set(x, y)
      val same = all.collect { case e: Edge if Set(e.low, e.high) == pair && e.window == win => e }
      decide(
        claim,
        same.map(_.coauthoredWorkCount).distinct.size > 1,
        x != y && cited.exists {
          case e: Edge => Set(e.low, e.high) == pair && within(e.window, win) &&
            e.coauthoredWorkCount == count
          case _ => false
        },
        "coauthor edge"
      )
    case MetricValueIs(r, m, v, win) =>
      val same = all.collect {
        case f: Metric if f.researcherId == r && f.metric == m && f.window == win => f
      }
      decide(
        claim,
        same.map(_.value).distinct.size > 1,
        cited.exists {
          case f: Metric => f.researcherId == r && f.metric == m && within(f.window, win) &&
            f.value == v
          case _ => false
        },
        "metric value"
      )
    case MemberOfCommunity(r, c, win) =>
      val same = all.collect { case f: Community if f.researcherId == r && f.window == win => f }
      decide(
        claim,
        same.groupBy(_.algorithmVersion).values.exists(_.map(_.communityId).distinct.size > 1),
        cited.exists {
          case f: Community => f.researcherId == r && f.communityId == c && within(f.window, win)
          case _            => false
        },
        "community membership"
      )
    case BridgeScore(r, cross, distinct, win) =>
      val same = all.collect { case f: Bridge if f.researcherId == r && f.window == win => f }
      decide(
        claim,
        same.map(f => (f.crossCommunityDegree, f.distinctCommunitiesTouched)).distinct.size > 1,
        cited.exists {
          case f: Bridge =>
            f.researcherId == r && f.crossCommunityDegree == cross &&
            f.distinctCommunitiesTouched == distinct && within(f.window, win)
          case _ => false
        },
        "bridge measure"
      )
    case InComponent(r, c, size, win) =>
      val same = all.collect { case f: Component if f.researcherId == r && f.window == win => f }
      decide(
        claim,
        same.map(f => (f.componentId, f.componentSize)).distinct.size > 1,
        cited.exists {
          case f: Component =>
            f.researcherId == r && f.componentId == c && f.componentSize == size &&
            within(f.window, win)
          case _ => false
        },
        "component"
      )
    case PathExists(nodes, win) =>
      if nodes.size < 2 || nodes.distinct.size != nodes.size then
        unsupported(claim, "V6: path is not a simple chain")
      else
        val hops = nodes.zip(nodes.tail)
        val ok   = hops.count { (u, v) =>
          cited.exists {
            case e: Edge => Set(e.low, e.high) == Set(u, v) && within(e.window, win)
            case _       => false
          }
        }
        if ok == hops.size then ClaimCheck(claim, Verdict.Verified, Nil)
        else if ok > 0 then
          ClaimCheck(
            claim,
            Verdict.PartiallySupported,
            List(s"V6: $ok of ${hops.size} hops have edge evidence")
          )
        else unsupported(claim, "V6: no hop has edge evidence")

  /**
   * Overall status under core's rules: no claims or none supported is `unsupported`; all verified
   * is `supported`; any verified or partially supported is `partially-supported`; otherwise any
   * conflicting claim is `contradicted`.
   */
  def overall(checks: List[ClaimCheck]): VerificationStatus =
    val vs = checks.map(_.verdict)
    if vs.isEmpty then VerificationStatus.Unsupported
    else if vs.forall(_ == Verdict.Verified) then VerificationStatus.Supported
    else if vs.exists(v => v == Verdict.Verified || v == Verdict.PartiallySupported) then
      VerificationStatus.PartiallySupported
    else if vs.contains(Verdict.Conflicting) then VerificationStatus.Contradicted
    else VerificationStatus.Unsupported
