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
 * One entity-resolution output (ER-local wire record, `schemaVersion` 2, see
 * [[Resolver.RecordSchemaVersion]]). `provenance` is the ER run's own provenance
 * (`scholarchain-pipeline`, the pair ID, the ER run ID); `provenanceLow` and `provenanceHigh` are
 * the source records of the two sides (CLAUDE.md law 3). `outcome` and `decider` reuse the core
 * enums; `status` and `path` carry what core `MatchOutcome` cannot (conflict and the match path).
 *
 * Schema version 2 (er-policy-v3) adds two trailing fields, always written:
 *   - `scoreUpper`: the upper score bound (missing features counted as 1); `score` is the lower
 *     bound (missing features counted as 0). Both null when no score was computed.
 *   - `corroborated`: whether a name-independent feature corroborates the pair (policy
 *     `corroboration`); null when the decision did not reach the probabilistic path.
 * Version 1 lines (without the two fields) still read: both default to null.
 *
 * Merge veto (human decision STRICT, 2026-11-14, rule c): every record whose status is not
 * `accepted` ([[blocksMerge]]) means the two mentions must not be joined by any identity closure.
 */
@upickle.implicits.serializeDefaults(true)
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
    provenanceHigh: Provenance,
    scoreUpper: Option[Double] = None,
    corroborated: Option[Boolean] = None
) derives ReadWriter:
  /** True for rejected, unresolved, and conflict: the two members must never be joined. */
  def blocksMerge: Boolean = status != ResolutionStatus.Accepted

/**
 * Bounds of the probabilistic score (er-policy-v3). With W the sum of the scope's positive weights
 * and v the component of each computable feature: `lower` = sum(w * v) / W (a missing feature
 * counts 0), `upper` = (sum(w * v) + sum of missing weights) / W (a missing feature counts 1). So
 * filling in a missing feature with any value never lowers `lower` and never raises `upper`.
 * `independentWeight` is the weight of the computable name-independent features (coauthorOverlap,
 * titleSimilarity, yearDistance), compared with the policy's min-evidence-weight.
 */
final case class ScoreBounds(lower: Double, upper: Double, independentWeight: Double)

object Resolver:

  /** Wire version of [[ResolutionRecord]]; 2 since er-policy-v3 (adds scoreUpper, corroborated). */
  val RecordSchemaVersion: Int = 2

  def decisionId(pairId: String, policyVersion: String): String =
    "erdecision:v1:" +
      CanonicalHash.hashFields(Seq("scholarchain:er-decision:v1", pairId, policyVersion))

  /**
   * Score bounds over every positively weighted feature of the scope (er-policy-v3, D3). Unlike
   * er-policy-v2 the weights are NOT renormalized over the computable features, so a missing
   * feature can never raise the score. idAgreement is excluded (it routes to the exact-ID or
   * conflict path). None when the scope has no positive weight.
   */
  def score(p: ScoringPolicy, scope: String, f: FeatureVector): Option[ScoreBounds] =
    val w     = p.weights(scope)
    val parts = List(
      (f.nameSimilarity, w.nameSimilarity, false),
      (f.initialsCompatibility, w.initialsCompatibility, false),
      (f.coauthorOverlap, w.coauthorOverlap, true),
      (f.titleSimilarity, w.titleSimilarity, true),
      (f.yearDistance.map(d => math.max(0.0, 1.0 - d / p.yearScale)), w.yearDistance, true)
    ).filter(_._2 > 0.0)
    val total = parts.map(_._2).sum
    Option.when(total > 0.0) {
      val observed = parts.collect { case (Some(v), wt, _) => v * wt }.sum
      val missing  = parts.collect { case (None, wt, _) => wt }.sum
      val indep    = parts.collect { case (Some(_), wt, true) => wt }.sum
      ScoreBounds(observed / total, (observed + missing) / total, indep)
    }

  /**
   * Corroboration (er-policy-v3, D3-floor): positive evidence from a feature that does not come
   * from the name. yearDistance never corroborates; nameSimilarity and initialsCompatibility never
   * do, so name plus initials alone can never reach an accept.
   */
  def corroborated(p: ScoringPolicy, f: FeatureVector): Boolean =
    f.coauthorOverlap.exists(_ >= p.corroboration.coauthorOverlapMin) ||
      f.titleSimilarity.exists(_ >= p.corroboration.titleSimilarityMin)

  /** Band of a probabilistic score: accept at or above `accept`, reject strictly below `reject`. */
  def band(s: Double, accept: Double, reject: Double): ResolutionStatus =
    band(s, s, corroborated = true, accept, reject)

  /**
   * Band of score bounds: accepted when `lower >= accept` and corroborated; rejected when
   * `upper < reject`; otherwise unresolved. A feature that is MISSING (None) can therefore cause
   * neither an accept nor a reject: it counts 0 on `lower` and 1 on `upper`.
   *
   * Exception (M1, er-features-v3): an empty coauthor list is not missing. When both sides have no
   * coauthors, coauthorOverlap is an OBSERVED 0.0 (as the Hadoop contextJaccard), so `upper` loses
   * the coauthor weight share that a missing value would add, and it can, together with other low
   * observed features, push a pair below `reject` (for example a mononym single-author pair with
   * disjoint titles and a 20-year gap is rejected only because of it). It never helps an accept:
   * 0.0 never corroborates and adds nothing to `lower`. Such a reject is a merge veto, so it is
   * precision-safe.
   */
  def band(
      lower: Double,
      upper: Double,
      corroborated: Boolean,
      accept: Double,
      reject: Double
  ): ResolutionStatus =
    if lower >= accept && corroborated then ResolutionStatus.Accepted
    else if upper < reject then ResolutionStatus.Rejected
    else ResolutionStatus.Unresolved

  /** Reason-code prefix of a decision whose accept a missing value blocked (er-policy-v4). */
  val AcceptBlocked: String   = "accept-blocked:"
  val SameWorkUnknown: String = "same-work-unknown"
  val InitialsUnknown: String = "initials-unknown"

  /**
   * Reason code (er-policy-v4) of an unresolved pair with `lower < reject <= upper`: some
   * completion of its missing features would reject it, so it is not sent to the adjudicator.
   * Otherwise removing a feature could raise `upper` from below reject into the band and let an
   * adjudicator accept a pair that its observed evidence rejected.
   */
  val BoundsStraddleReject: String = "bounds-straddle-reject"

  /**
   * Missing values that block every non-ID accept (er-policy-v4, Phase 11 repair-02; human decision
   * STRICT). A value whose observed form can veto the pair must not, when missing, let the pair be
   * accepted, or the absence would be worth more than the evidence:
   *   - `same-work-unknown`: a mention pair whose sameWork is unknown, when reject-same-work is on
   *     (sameWork = true rejects). Both production paths always set sameWork.
   *   - `initials-unknown`: a mention pair whose initialsCompatibility is not computable (a mononym
   *     or name without given names on the entity-view path; every Hadoop proxy pair), when
   *     reject-incompatible-initials is on (initials = 0 rejects).
   * A blocked pair is still rejected when its score bounds say so, and is never adjudicated.
   * Identifier absence is not in this list: see [[decide]].
   */
  def acceptBlocks(p: ScoringPolicy, in: PairInput): List[String] =
    val mention = in.scope == "mention"
    val d       = p.deterministic
    List(
      Option.when(mention && d.rejectSameWork && in.sameWork.isEmpty)(SameWorkUnknown),
      Option.when(
        mention && d.rejectIncompatibleInitials && in.features.initialsCompatibility.isEmpty
      )(InitialsUnknown)
    ).flatten

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
   *
   * Policy er-policy-v3 (Phase 11 repair-01, human decision STRICT 2026-11-14), probabilistic path:
   *   - D3: the score is the pair of bounds of [[score]]; missing evidence never raises it.
   *     Accepted needs `lower >= accept`, rejected needs `upper < reject` ([[band]]).
   *   - D3-floor: accepted also needs [[corroborated]]; a pair below min-evidence-weight of
   *     name-independent evidence is unresolved (`insufficient-evidence`); an uncorroborated pair
   *     is unresolved (`uncorroborated`) and never reaches the adjudicator, so neither the score
   *     nor an adjudicator can accept on name plus initials alone.
   *
   * Policy er-policy-v4 (Phase 11 repair-02, human decisions STRICT and RR3 2026-11-15): no missing
   * value moves a decision to Accepted. An accept condition needs an observed value; a veto fires
   * only on an observed value, and its missing value blocks every non-ID accept instead.
   *   - RR3: the deterministic mention accept needs an observed yearDistance <=
   *     mention-max-year-gap. A missing year falls through to the scored path, where it counts 0 on
   *     the lower bound and never corroborates.
   *   - [[acceptBlocks]]: unknown sameWork (mention) or unknown initials (mention) block the
   *     deterministic, probabilistic, and adjudicated accepts (`accept-blocked:<why>`, unresolved,
   *     never adjudicated). An ID-agreeing mention pair with unknown sameWork is unresolved, not
   *     accepted, because sameWork = true would make it a conflict.
   *   - Adjudicator band: a pair is adjudicated only when `lower >= reject` (and corroborated and
   *     not blocked). A pair with `lower < reject <= upper` is unresolved
   *     ([[BoundsStraddleReject]]): only its missing features keep it out of the reject band.
   *   - Not changed: the absence of an identifier. An ID conflict is a contradiction between two
   *     present authorities (conflict output); a missing identifier is the normal case for DBLP, so
   *     treating it as a blocked veto would leave only the exact-ID path. An identifier that fails
   *     validation is not an identifier ([[Identifiers.normalize]]).
   *
   * Policy er-policy-v5 (Phase 11 repair-03, review-02 F3, human decision STRICT): the
   * deterministic mention accept (`name-exact+coauthor-overlap`) and work accept
   * (`title-exact+year`) also need [[corroborated]]. [[ScoringPolicy.validated]] additionally keeps
   * their minimums at or above the corroboration minimums, so this check is defense in depth: a
   * policy built with `copy` (bypassing validation) with `mention-coauthor-min = 0` still cannot
   * accept two single-author mentions whose empty coauthor lists observe 0.0 (M1). A pair that
   * fails it falls through to the scored path, which also needs corroboration.
   */
  def decide(
      p: ScoringPolicy,
      in: PairInput,
      runId: String,
      adjudicator: Adjudicator = AbstainAdjudicator
  ): ResolutionRecord =
    require(IdFormat.isValid(IdKind.Run, runId), s"runId must be a run:v1 ID, got '$runId'")
    val f      = in.features
    val d      = p.deterministic
    val bounds = score(p, in.scope, f)
    val corr   = corroborated(p, f)
    def out(
        st: ResolutionStatus,
        path: MatchPath,
        decider: MatchDecider,
        conf: Double,
        reasons: List[String],
        adj: Option[String] = None
    ) =
      val probabilistic = path == MatchPath.Probabilistic || path == MatchPath.LlmAdjudicated
      val outcome       = st match
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
        bounds.map(_.lower),
        conf,
        p.policyVersion,
        Features.SpecVersion,
        adj,
        f.named,
        reasons ++ in.extraReasons,
        Provenance(SourceType.ScholarChainPipeline, in.pairId, runId),
        in.provenanceLow,
        in.provenanceHigh,
        bounds.map(_.upper),
        Option.when(probabilistic)(corr)
      )
    val agreeCodes   = in.ids.agreements.map(ns => s"id-agree:${ns.wire}")
    val mention      = in.scope == "mention"
    val mentionOnOne = mention && in.sameWork.contains(true)
    val workUnknown  = mention && in.sameWork.isEmpty
    // er-policy-v4: a missing value that would block an accept when observed blocks it too.
    val blocks = acceptBlocks(p, in)
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
    else if in.ids.hasAgreement && workUnknown then
      out(
        ResolutionStatus.Unresolved,
        MatchPath.ExactId,
        MatchDecider.Rule,
        0.0,
        s"$AcceptBlocked$SameWorkUnknown" :: agreeCodes
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
    else if mention && blocks.isEmpty && corr && f.nameSimilarity.exists(_ >= d.mentionNameMin) &&
      f.initialsCompatibility.contains(1.0) &&
      f.coauthorOverlap.exists(_ >= d.mentionCoauthorMin) &&
      f.yearDistance.exists(_ <= d.mentionMaxYearGap)
    then
      out(
        ResolutionStatus.Accepted,
        MatchPath.Deterministic,
        MatchDecider.Rule,
        1.0,
        List("name-exact+coauthor-overlap")
      )
    else if in.scope == "work" && corr && f.titleSimilarity.exists(_ >= d.workTitleMin) &&
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
      bounds match
        case None =>
          out(
            ResolutionStatus.Unresolved,
            MatchPath.Probabilistic,
            MatchDecider.Model,
            0.0,
            List("no-features")
          )
        case Some(b) if b.independentWeight < p.minEvidenceWeight =>
          out(
            ResolutionStatus.Unresolved,
            MatchPath.Probabilistic,
            MatchDecider.Model,
            0.0,
            List("insufficient-evidence")
          )
        case Some(b) =>
          band(b.lower, b.upper, corr, p.accept, p.reject) match
            case ResolutionStatus.Unresolved if !corr =>
              out(
                ResolutionStatus.Unresolved,
                MatchPath.Probabilistic,
                MatchDecider.Model,
                0.0,
                List("score-band:unresolved", "uncorroborated")
              )
            case st if st != ResolutionStatus.Rejected && blocks.nonEmpty =>
              // Accepted or the adjudicator band: never accepted, never adjudicated.
              out(
                ResolutionStatus.Unresolved,
                MatchPath.Probabilistic,
                MatchDecider.Model,
                0.0,
                "score-band:unresolved" :: blocks.map(AcceptBlocked + _)
              )
            case ResolutionStatus.Unresolved if b.lower < p.reject =>
              // Only missing features keep this pair out of the reject band: not adjudicated.
              out(
                ResolutionStatus.Unresolved,
                MatchPath.Probabilistic,
                MatchDecider.Model,
                0.0,
                List("score-band:unresolved", BoundsStraddleReject)
              )
            case ResolutionStatus.Unresolved => adjudicate(p, in, b.lower, adjudicator, out)
            case st                          =>
              val conf = if st == ResolutionStatus.Accepted then b.lower else 1.0 - b.upper
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
