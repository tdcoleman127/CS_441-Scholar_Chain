package edu.uic.cs441.scholarchain.er

import edu.uic.cs441.scholarchain.core.model.{IdNamespace, Provenance}
import edu.uic.cs441.scholarchain.er.Identifiers.IdComparison
import upickle.default.{read, ReadWriter}

import scala.util.Try

/**
 * Reader for the Phase 04 Hadoop Job 5 candidate output, by wire format only (no dependency on
 * hadoop classes, per the CLAUDE.md module table). Mirrors, field for field:
 *   - `MatchCandidateRecord`:
 *     modules/hadoop/src/main/scala/edu/uic/cs441/scholarchain/hadoop/Blocking.scala:53-64
 *   - `RawFeature`: Blocking.scala:47
 *   - raw feature names: Blocking.scala:168-182 (`Blocking.features`)
 *   - one JSON object per line, emitted to the `candidates` named output at Blocking.scala:232.
 */
final case class HadoopRawFeature(name: String, value: Option[Double]) derives ReadWriter

final case class HadoopCandidate(
    scope: String,
    blockKey: String,
    chunk: Int,
    memberLow: String,
    memberHigh: String,
    workLow: String,
    workHigh: String,
    features: List[HadoopRawFeature],
    provenanceLow: Provenance,
    provenanceHigh: Provenance
) derives ReadWriter

object HadoopCandidates:

  /** Tag added to reason codes: these features are Hadoop raw-feature proxies, not ER-computed. */
  val ProxyReason: String = "features:hadoop-raw-proxy-v1"

  def parseLine(line: String): Either[String, HadoopCandidate] =
    Try(read[HadoopCandidate](line)).toEither.left.map(e => s"malformed candidate: ${e.getMessage}")

  /**
   * Maps a Hadoop candidate onto the ER pair input. The record carries raw features, not the
   * attributes, so the six ER features are filled from raw-feature proxies where one exists:
   *   - mention scope: nameSimilarity <- labelTokenJaccard; coauthorOverlap <- contextJaccard;
   *     idAgreement and the ID comparison <- orcidEqual (1 = ORCID agrees, 0 = ORCID conflict).
   *   - work scope: titleSimilarity <- labelTokenJaccard; coauthorOverlap <- contextJaccard (author
   *     surnames).
   *   - both: yearDistance <- yearGap; sameWork <- sameWork.
   *   - initialsCompatibility: not derivable (None). Under er-policy-v2 the deterministic mention
   *     accept needs initials == 1, so a proxy pair never takes it: labelTokenJaccard is a
   *     token-set proxy, not name equality (Phase 05 repair-01 F2). This mapping, and so the
   *     [[ProxyReason]] tag, is unchanged.
   * Work-scope `sharedExactKeys` does not say which namespace matched or whether another conflicts,
   * so it is recorded as a reason code only and never takes the exact-ID path.
   *
   * Path parity (er-features-v3, Phase 11 repair-01 M1): `contextJaccard` is 0.0 for two empty
   * context sets, and since er-features-v3 [[Features.coauthorOverlap]] uses the same convention,
   * so this path and [[PairInput.fromViews]] give the same coauthorOverlap, nameSimilarity (for
   * equal normalized names), idAgreement, yearDistance, and sameWork for the same pair. The record
   * has no name string and no mention title, so initialsCompatibility and the mention
   * titleSimilarity stay None here; under er-policy-v3 a missing feature never raises the score and
   * never corroborates. Under er-policy-v4 the missing initials also block every non-ID accept
   * (`accept-blocked:initials-unknown`, [[Resolver.acceptBlocks]]), so a mention pair from this
   * path is accepted only on ORCID agreement. Coauthor identity (er-features-v4) keeps the DBLP
   * homonym number, as `contextJaccard` does, so the homonym case has the same coauthorOverlap too.
   */
  def toPairInput(c: HadoopCandidate): PairInput =
    val raw            = c.features.map(f => f.name -> f.value).toMap
    def get(n: String) = raw.get(n).flatten
    val mention        = c.scope == "mention"
    val orcid          = if mention then get("orcidEqual") else None
    val ids            = orcid match
      case Some(v) if v >= 1.0 => IdComparison(List(IdNamespace.Orcid), Nil)
      case Some(_)             => IdComparison(Nil, List(IdNamespace.Orcid))
      case None                => IdComparison.Empty
    val shared = get("sharedExactKeys").filter(_ > 0 && !mention)
    PairInput(
      scope = c.scope,
      blockKey = c.blockKey,
      memberLow = c.memberLow,
      memberHigh = c.memberHigh,
      sameWork = get("sameWork").map(_ >= 1.0).orElse(Some(c.workLow == c.workHigh)),
      features = FeatureVector(
        nameSimilarity = if mention then get("labelTokenJaccard") else None,
        initialsCompatibility = None,
        coauthorOverlap = get("contextJaccard"),
        idAgreement = Features.idAgreement(ids),
        titleSimilarity = if mention then None else get("labelTokenJaccard"),
        yearDistance = get("yearGap")
      ),
      ids = ids,
      extraReasons = ProxyReason ::
        shared.map(n => s"shared-exact-keys-unverified:${n.toInt}").toList,
      provenanceLow = c.provenanceLow,
      provenanceHigh = c.provenanceHigh
    )
