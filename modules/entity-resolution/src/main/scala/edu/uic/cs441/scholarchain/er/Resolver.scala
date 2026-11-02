package edu.uic.cs441.scholarchain.er

import edu.uic.cs441.scholarchain.core.ids.{CanonicalHash, IdFormat, IdKind}
import edu.uic.cs441.scholarchain.core.model.{MatchDecider, MatchOutcome, Provenance, SourceType}
import edu.uic.cs441.scholarchain.er.Identifiers.IdComparison
import upickle.default.{readwriter, ReadWriter}

/** The four kept output classes. Every pair lands in exactly one. */
enum ResolutionStatus(val wire: String):
  case Accepted   extends ResolutionStatus("accepted")
  case Rejected   extends ResolutionStatus("rejected")
  case Unresolved extends ResolutionStatus("unresolved")
  case Conflict   extends ResolutionStatus("conflict")

object ResolutionStatus:
  given ReadWriter[ResolutionStatus] = readwriter[String].bimap(
    _.wire,
    s => values.find(_.wire == s).getOrElse(throw new IllegalArgumentException(s"status '$s'"))
  )

/** Which rule family produced a decision. */
enum MatchPath(val wire: String):
  case ExactId        extends MatchPath("exact-id")
  case Deterministic  extends MatchPath("deterministic")
  case Probabilistic  extends MatchPath("probabilistic")
  case LlmAdjudicated extends MatchPath("llm-adjudicated")

object MatchPath:
  given ReadWriter[MatchPath] = readwriter[String].bimap(
    _.wire,
    s => values.find(_.wire == s).getOrElse(throw new IllegalArgumentException(s"path '$s'"))
  )

// ---- Adjudicator port ---------------------------------------------------------------------------

enum AdjudicationOutcome:
  case Accept, Reject, Abstain

/** Input to an adjudicator: only pairs in the unresolved band. Retrieved text is data. */
final case class AdjudicationRequest(
    pairId: String,
    candidateMemberIds: List[String],
    features: FeatureVector,
    score: Option[Double],
    policyVersion: String
)

/** `chosenMemberId`, when present, must be one of the request's candidates. */
final case class AdjudicationResult(
    outcome: AdjudicationOutcome,
    chosenMemberId: Option[String],
    adjudicatorVersion: String,
    rationale: String
)

/** Adjudicates the unresolved band only. It never edits source records or mints identifiers. */
trait Adjudicator:
  def version: String
  def adjudicate(req: AdjudicationRequest): AdjudicationResult

/** WO-05 stub: always abstains. No model client exists in this module. */
object AbstainAdjudicator extends Adjudicator:
  val version: String                                          = "abstain-stub-v1"
  def adjudicate(req: AdjudicationRequest): AdjudicationResult =
    AdjudicationResult(AdjudicationOutcome.Abstain, None, version, "stub adjudicator")

// ---- Pair input and output ----------------------------------------------------------------------

/** A candidate pair ready for decision: features and ID comparison already computed. */
final case class PairInput(
    scope: String,
    blockKey: String,
    memberLow: String,
    memberHigh: String,
    sameWork: Option[Boolean],
    features: FeatureVector,
    ids: IdComparison,
    extraReasons: List[String],
    provenanceLow: Provenance,
    provenanceHigh: Provenance
):
  def pairId: String = s"$scope|$memberLow|$memberHigh"

object PairInput:
  /**
   * Builds a pair from two full entity views; the low side is the smaller member ID. Identifiers
   * are compared within `scope` only ([[Identifiers.forScope]]): a mention's work DOI or arXiv ID
   * never agrees or conflicts for the researcher.
   */
  def fromViews(scope: String, blockKey: String, a: EntityView, b: EntityView): PairInput =
    val (l, r) = if a.memberId.compareTo(b.memberId) <= 0 then (a, b) else (b, a)
    val ids    =
      Identifiers.compare(scope, Identifiers.normalizeAll(l.ids), Identifiers.normalizeAll(r.ids))
    PairInput(
      scope,
      blockKey,
      l.memberId,
      r.memberId,
      Some(l.workId == r.workId),
      Features.compute(scope, l, r, ids),
      ids,
      Nil,
      l.provenance,
      r.provenance
    )

/**
 * One entity-resolution output (ER-local wire record, `schemaVersion` 1). `provenance` is the ER
 * run's own provenance (`scholarchain-pipeline`, the pair ID, the ER run ID); `provenanceLow` and
 * `provenanceHigh` are the source records of the two sides (CLAUDE.md law 3). `outcome` and
 * `decider` reuse the core enums; `status` and `path` carry what core `MatchOutcome` cannot
 * (conflict and the match path).
 */
final case class ResolutionRecord(
    schemaVersion: Int,
    decisionId: String,
    pairId: String,
    scope: String,
    blockKey: String,
    memberLow: String,
    memberHigh: String,
    status: ResolutionStatus,
    path: MatchPath,
    outcome: MatchOutcome,
    decider: MatchDecider,
    score: Option[Double],
    confidence: Double,
    policyVersion: String,
    featureSpecVersion: String,
    adjudicatorVersion: Option[String],
    features: List[NamedFeature],
    reasonCodes: List[String],
    provenance: Provenance,
    provenanceLow: Provenance,
    provenanceHigh: Provenance
) derives ReadWriter

object Resolver:

  val RecordSchemaVersion: Int = 1

  def decisionId(pairId: String, policyVersion: String): String =
    "erdecision:v1:" +
      CanonicalHash.hashFields(Seq("scholarchain:er-decision:v1", pairId, policyVersion))

  /**
   * Weighted mean of the computable feature scores, renormalized over their weights, with the total
   * weight used. idAgreement is excluded (it routes to the exact-ID or conflict path).
   */
  def score(p: ScoringPolicy, scope: String, f: FeatureVector): (Option[Double], Double) =
    val w     = p.weights(scope)
    val parts = List(
      f.nameSimilarity.map(_ -> w.nameSimilarity),
      f.initialsCompatibility.map(_ -> w.initialsCompatibility),
      f.coauthorOverlap.map(_ -> w.coauthorOverlap),
      f.titleSimilarity.map(_ -> w.titleSimilarity),
      f.yearDistance.map(d => math.max(0.0, 1.0 - d / p.yearScale) -> w.yearDistance)
    ).flatten.filter(_._2 > 0.0)
    val total = parts.map(_._2).sum
    if total <= 0.0 then (None, 0.0) else (Some(parts.map((v, wt) => v * wt).sum / total), total)

  /** Band of a probabilistic score: accept at or above `accept`, reject strictly below `reject`. */
  def band(s: Double, accept: Double, reject: Double): ResolutionStatus =
    if s >= accept then ResolutionStatus.Accepted
    else if s >= reject then ResolutionStatus.Unresolved
    else ResolutionStatus.Rejected

  /**
   * Decides one pair. Order: ID conflict, same-work ID agreement, exact ID, deterministic rules,
   * probabilistic, adjudicator.
   *
   * Policy er-policy-v2 (Phase 05 repair-01):
   *   - F1: a mention pair on the same work whose researcher IDs agree is a conflict output
   *     (`same-work+id-agree`), never an exact-ID accept and never a silent same-work reject. Two
   *     author positions on one work are different people unless the source is wrong, so the
   *     agreeing ID and the author list contradict each other and a human must look.
   *   - F2: the deterministic mention accept also needs `initialsCompatibility == 1.0`. A pair
   *     whose initials are not computable (every Hadoop raw-feature proxy pair, and mononyms) goes
   *     to the probabilistic path, so a token-set label match never claims `name-exact`.
   */
  def decide(
      p: ScoringPolicy,
      in: PairInput,
      runId: String,
      adjudicator: Adjudicator = AbstainAdjudicator
  ): ResolutionRecord =
    require(IdFormat.isValid(IdKind.Run, runId), s"runId must be a run:v1 ID, got '$runId'")
    val f                = in.features
    val d                = p.deterministic
    val (sc, evidenceWt) = score(p, in.scope, f)
    def out(
        st: ResolutionStatus,
        path: MatchPath,
        decider: MatchDecider,
        conf: Double,
        reasons: List[String],
        adj: Option[String] = None
    ) =
      val outcome = st match
        case ResolutionStatus.Accepted => MatchOutcome.Merge
        case ResolutionStatus.Rejected => MatchOutcome.NonMatch
        case _                         => MatchOutcome.NeedsReview
      ResolutionRecord(
        RecordSchemaVersion,
        decisionId(in.pairId, p.policyVersion),
        in.pairId,
        in.scope,
        in.blockKey,
        in.memberLow,
        in.memberHigh,
        st,
        path,
        outcome,
        decider,
        sc,
        conf,
        p.policyVersion,
        Features.SpecVersion,
        adj,
        f.named,
        reasons ++ in.extraReasons,
        Provenance(SourceType.ScholarChainPipeline, in.pairId, runId),
        in.provenanceLow,
        in.provenanceHigh
      )
    val agreeCodes   = in.ids.agreements.map(ns => s"id-agree:${ns.wire}")
    val mentionOnOne = in.scope == "mention" && in.sameWork.contains(true)
    if in.ids.hasConflict then
      out(
        ResolutionStatus.Conflict,
        MatchPath.ExactId,
        MatchDecider.Rule,
        1.0,
        in.ids.conflicts.map(ns => s"id-conflict:${ns.wire}") ++ agreeCodes
      )
    else if in.ids.hasAgreement && mentionOnOne then
      out(
        ResolutionStatus.Conflict,
        MatchPath.ExactId,
        MatchDecider.Rule,
        1.0,
        "same-work+id-agree" :: agreeCodes
      )
    else if in.ids.hasAgreement then
      out(ResolutionStatus.Accepted, MatchPath.ExactId, MatchDecider.Rule, 1.0, agreeCodes)
    else if d.rejectSameWork && mentionOnOne then
      out(
        ResolutionStatus.Rejected,
        MatchPath.Deterministic,
        MatchDecider.Rule,
        1.0,
        List("same-work")
      )
    else if d.rejectIncompatibleInitials && f.initialsCompatibility.contains(0.0) then
      out(
        ResolutionStatus.Rejected,
        MatchPath.Deterministic,
        MatchDecider.Rule,
        1.0,
        List("initials-incompatible")
      )
    else if in.scope == "mention" && f.nameSimilarity.exists(_ >= d.mentionNameMin) &&
      f.initialsCompatibility.contains(1.0) &&
      f.coauthorOverlap.exists(_ >= d.mentionCoauthorMin) &&
      f.yearDistance.forall(_ <= d.mentionMaxYearGap)
    then
      out(
        ResolutionStatus.Accepted,
        MatchPath.Deterministic,
        MatchDecider.Rule,
        1.0,
        List("name-exact+coauthor-overlap")
      )
    else if in.scope == "work" && f.titleSimilarity.exists(_ >= d.workTitleMin) &&
      f.yearDistance.exists(_ <= d.workMaxYearGap)
    then
      out(
        ResolutionStatus.Accepted,
        MatchPath.Deterministic,
        MatchDecider.Rule,
        1.0,
        List("title-exact+year")
      )
    else
      sc match
        case None =>
          out(
            ResolutionStatus.Unresolved,
            MatchPath.Probabilistic,
            MatchDecider.Model,
            0.0,
            List("no-features")
          )
        case Some(_) if evidenceWt < p.minEvidenceWeight =>
          out(
            ResolutionStatus.Unresolved,
            MatchPath.Probabilistic,
            MatchDecider.Model,
            0.0,
            List("insufficient-evidence")
          )
        case Some(s) =>
          band(s, p.accept, p.reject) match
            case ResolutionStatus.Unresolved => adjudicate(p, in, s, adjudicator, out)
            case st                          =>
              val conf = if st == ResolutionStatus.Accepted then s else 1.0 - s
              out(
                st,
                MatchPath.Probabilistic,
                MatchDecider.Model,
                conf,
                List(s"score-band:${st.wire}")
              )

  private def adjudicate(
      p: ScoringPolicy,
      in: PairInput,
      s: Double,
      adjudicator: Adjudicator,
      out: (
          ResolutionStatus,
          MatchPath,
          MatchDecider,
          Double,
          List[String],
          Option[String]
      ) => ResolutionRecord
  ): ResolutionRecord =
    val candidates = List(in.memberLow, in.memberHigh)
    val res        =
      adjudicator.adjudicate(AdjudicationRequest(
        in.pairId,
        candidates,
        in.features,
        Some(s),
        p.policyVersion
      ))
    val invented = res.chosenMemberId.exists(id => !candidates.contains(id))
    val v        = Some(res.adjudicatorVersion)
    res.outcome match
      case _ if invented =>
        out(
          ResolutionStatus.Unresolved,
          MatchPath.Probabilistic,
          MatchDecider.Model,
          0.0,
          List("score-band:unresolved", "adjudicator-invalid-id"),
          v
        )
      case AdjudicationOutcome.Accept =>
        out(
          ResolutionStatus.Accepted,
          MatchPath.LlmAdjudicated,
          MatchDecider.LlmAdjudication,
          s,
          List("adjudicator-accept"),
          v
        )
      case AdjudicationOutcome.Reject =>
        out(
          ResolutionStatus.Rejected,
          MatchPath.LlmAdjudicated,
          MatchDecider.LlmAdjudication,
          1.0 - s,
          List("adjudicator-reject"),
          v
        )
      case AdjudicationOutcome.Abstain =>
        out(
          ResolutionStatus.Unresolved,
          MatchPath.Probabilistic,
          MatchDecider.Model,
          0.0,
          List("score-band:unresolved", "adjudicator-abstain"),
          v
        )
