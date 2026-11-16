package edu.uic.cs441.scholarchain.services.app

import edu.uic.cs441.scholarchain.core.ids.{IdFormat, IdKind}
import edu.uic.cs441.scholarchain.core.json.Wire
import edu.uic.cs441.scholarchain.core.model.{AnswerClaim, VerificationStatus}
import edu.uic.cs441.scholarchain.rag.runtime.*
import edu.uic.cs441.scholarchain.services.api.*

/**
 * Presentation only: re-shapes rag's typed outcome into REST views. It computes no metric, ranks
 * nothing, and verifies nothing; every value shown comes from a graph fact, claim, or audit field
 * that rag produced. Researcher attributes follow the duplicate and conflict rule at [[agreed]].
 */
object Presenter:

  def queryLinks(queryId: String): QueryLinks =
    QueryLinks(s"/v1/queries/$queryId/evidence", s"/v1/explain/$queryId")

  def evidenceHref(evidenceId: String): String = s"/v1/evidence/$evidenceId"

  def statusWire(s: VerificationStatus): String = s match
    case VerificationStatus.Supported          => "VERIFIED"
    case VerificationStatus.PartiallySupported => "PARTIALLY_SUPPORTED"
    case VerificationStatus.Unsupported        => "UNSUPPORTED"
    case VerificationStatus.Contradicted       => "CONFLICTING"
    case VerificationStatus.NotVerified        => "NOT_VERIFIED"

  /** Audit records carry the core wire value (`supported`, ...); the API uses `VERIFIED`, ... */
  def auditStatusWire(s: Option[String]): Option[String] =
    s.flatMap(w => VerificationStatus.values.find(_.wire == w)).map(statusWire)

  private def uncertainty(claim: AnswerClaim, check: Option[ClaimCheck]): String =
    val base = "Depends on the researcher-identity resolution policy and data release " +
      s"${claim.provenance.pipelineRunId}."
    check.map(_.reasons).filter(_.nonEmpty) match
      case Some(rs) => s"$base Verifier: ${rs.mkString("; ")}"
      case None     => base

  private def summaries(bundle: RagEvidenceBundle): List[EvidenceSummaryView] =
    bundle.core.items.map(i =>
      EvidenceSummaryView(
        i.evidenceId,
        i.evidenceType.wire,
        i.subjectId,
        i.contentHash,
        evidenceHref(i.evidenceId)
      )
    )

  /**
   * Duplicate and conflict rule for researcher views (phase 11 repair-01, D2 and M2). A researcher
   * attribute (one metric, the component, the community, the bridge measure, the display name) is
   * presented only when every graph fact in the bundle for that researcher and attribute carries
   * the same value. Identical duplicates collapse to that value. When the facts disagree (in any
   * window, policy, or algorithm version, since the view carries none of those), the attribute is
   * presented as absent (JSON null, or left out of `metrics`): the API never picks one duplicate.
   * As a second, independent guard, an attribute is also absent when the verifier marked a claim
   * about it CONFLICTING. The claims themselves are listed unchanged with their verification
   * status.
   */
  def agreed[A](values: List[A]): Option[A] = values.distinct match
    case List(v) => Some(v)
    case _       => None

  /** The view attribute a claim's assertion is about, for the CONFLICTING guard. */
  private def attribute(a: Assertion): Option[(String, String)] = a match
    case Assertion.MetricValueIs(r, m, _, _)  => Some(r -> s"metric:${m.wire}")
    case Assertion.MemberOfCommunity(r, _, _) => Some(r -> "community")
    case Assertion.BridgeScore(r, _, _, _)    => Some(r -> "bridge")
    case Assertion.InComponent(r, _, _, _)    => Some(r -> "component")
    case _                                    => None

  private def researchers(
      bundle: RagEvidenceBundle,
      claims: List[(AnswerClaim, Option[ClaimCheck])],
      out: OutputOptions
  ): List[ResearcherView] =
    import GraphFact.*
    val facts      = bundle.facts
    val conflicted = claims.collect {
      case (_, Some(c)) if c.verdict == Verdict.Conflicting => attribute(c.claim.assertion)
    }.flatten.toSet
    facts.collect { case i: Identity => i.researcherId }.distinct.map { id =>
      def shown[A](attr: String, values: List[A]): Option[A] =
        if conflicted.contains(id -> attr) then None else agreed(values)
      val metrics = facts
        .collect { case m: Metric if m.researcherId == id => m }
        .groupBy(_.metric.wire)
        .toList
        .flatMap((wire, ms) => shown(s"metric:$wire", ms.map(_.value)).map(wire -> _))
        .toMap
      ResearcherView(
        id,
        agreed(facts.collect { case i: Identity if i.researcherId == id => i.displayName }),
        None,
        metrics.get("degree"),
        metrics.get("weighted-degree"),
        None,
        None,
        Nil,
        shown(
          "component",
          facts.collect {
            case c: Component if c.researcherId == id => (c.componentId, c.componentSize)
          }
        ).map(_._1),
        if !out.includeCommunities then None
        else
          shown(
            "community",
            facts.collect { case c: Community if c.researcherId == id => c.communityId }
          )
        ,
        if !out.includeBridgeMetrics then None
        else
          shown(
            "bridge",
            facts.collect {
              case b: Bridge if b.researcherId == id =>
                (b.crossCommunityDegree, b.distinctCommunitiesTouched)
            }
          ).map((cross, _) => BridgeMetricView("crossCommunityDegree", cross.toDouble))
        ,
        metrics,
        claims.collect {
          case (ac, Some(c)) if c.claim.assertion.researcherIds.contains(id) => ac.claimId
        }
      )
    }

  private def abstention(a: Abstention): AbstentionView = a match
    case Abstention.UnknownResearcher(m) =>
      AbstentionView(a.wire, Some(s"no researcher matches '${ApiError.bounded(m)}'"), Nil, None)
    case Abstention.AmbiguousResearcher(m, cs, n) =>
      AbstentionView(
        a.wire,
        Some(s"'${ApiError.bounded(m)}' matches several researchers"),
        cs.map(c => CandidateView(c.researcherId, c.displayName, c.matchRule)),
        Some(n)
      )
    case Abstention.Gap(g) => AbstentionView(a.wire, Some(g.wire), Nil, None)
    case other             => AbstentionView(other.wire, None, Nil, None)

  /** The answer body for an answered or abstained outcome. */
  def queryResponse(
      queryId: String,
      mode: String,
      outcome: RagOutcome,
      out: OutputOptions
  ): QueryResponse =
    outcome match
      case RagOutcome.Answered(response, bundle, checks, _) =>
        val paired =
          response.claims.zipWithIndex.map((c, i) => (c, checks.lift(i)))
        QueryResponse(
          queryId,
          mode,
          "ANSWERED",
          Some(AnswerView(response.answerText, researchers(bundle, paired, out))),
          None,
          paired.map((c, chk) =>
            ClaimView(
              c.claimId,
              c.text,
              c.evidenceIds,
              statusWire(c.verificationStatus),
              Option.when(out.includeScores)(c.confidence),
              Option.when(out.includeUncertainty)(uncertainty(c, chk))
            )
          ),
          Some(statusWire(response.overallStatus)),
          Option.when(out.includeEvidence)(summaries(bundle)),
          queryLinks(queryId)
        )
      case RagOutcome.Abstained(reason, audit) =>
        QueryResponse(
          queryId,
          mode,
          "ABSTAINED",
          None,
          Some(abstention(reason)),
          Nil,
          auditStatusWire(audit.overallStatus),
          Option.when(out.includeEvidence)(Nil),
          queryLinks(queryId)
        )
      case RagOutcome.Refused(reason, _) =>
        QueryResponse(
          queryId,
          mode,
          "REFUSED",
          None,
          Some(AbstentionView(reason.wire, None, Nil, None)),
          Nil,
          None,
          None,
          queryLinks(queryId)
        )

  /** Evidence records (with pointers) for every item of an answered outcome's bundle. */
  def evidenceRecords(outcome: RagOutcome): List[EvidenceRecord] = outcome match
    case RagOutcome.Answered(_, bundle, _, _) =>
      val graph    = bundle.graphById
      val bundleId = bundle.core.bundleId
      val semantic = bundle.semanticEvidenceIds.toSet
      bundle.core.items.map { item =>
        graph.get(item.evidenceId) match
          case Some(e: GraphFact.Edge) =>
            EvidenceRecord(item, bundleId, Some(e.evidencePointer), List(e.low, e.high), false)
          case _ => EvidenceRecord(item, bundleId, None, Nil, semantic.contains(item.evidenceId))
      }
    case _ => Nil

  def evidenceView(r: EvidenceRecord): EvidenceView =
    val i = r.item
    EvidenceView(
      i.evidenceId,
      i.evidenceType.wire,
      i.subjectId,
      Option.when(IdFormat.isValid(IdKind.Work, i.subjectId))(i.subjectId),
      i.provenance.sourceDataset.wire,
      i.provenance.sourceRecordId,
      None,
      i.excerpt,
      None,
      r.sourceUri,
      r.graphPath,
      r.semantic,
      r.bundleId,
      None,
      EvidenceProvenanceView(
        i.provenance.pipelineRunId,
        i.contentHash,
        s"evidence-v${i.schemaVersion}",
        i.provenance.sourceDataset.wire,
        i.provenance.sourceRecordId
      )
    )

  def jobView(r: QueryRecord): JobView =
    JobView(
      r.jobId,
      r.queryId,
      r.state.wire,
      r.stage,
      r.createdAt.toString,
      r.updatedAt.toString,
      Option.when(r.state == JobState.Succeeded)(s"/v1/jobs/${r.jobId}/result"),
      r.error.map(e => JobErrorView(e.code, e.message, e.retryable))
    )

  private val ModelKeys = Set("synthesizer", "embeddingModel")

  private def stages(a: AuditRecord, semanticRan: Boolean): List[String] =
    List(
      Some("plan"),
      Option.when(a.plan.isDefined || a.resolvedIds.nonEmpty || a.candidateIds.nonEmpty)(
        "resolve-researchers"
      ),
      Option.when(a.bundleId.isDefined)("graph-retrieval"),
      Option.when(a.bundleId.isDefined && semanticRan)("semantic-context"),
      Option.when(a.bundleId.isDefined)("evidence-bundle"),
      Option.when(a.claimVerdicts.nonEmpty)("synthesis"),
      Option.when(a.claimVerdicts.nonEmpty)("claim-verification")
    ).flatten

  /** Bundle-store results are reduced to a status word; storage error text is never echoed. */
  private def storeStatus(s: Option[String]): Option[String] =
    s.map(v => if v.startsWith("failed") then "failed" else v.toLowerCase(java.util.Locale.ROOT))

  /** Retrieval modes and indexes are those this query actually ran (from its workflow result). */
  def explainView(r: QueryRecord): ExplainView =
    val result = r.result
    val audit  = result.map(_.outcome.audit)
    // The semantic stage runs after graph retrieval succeeds, so it ran only if a bundle exists.
    val searched =
      if audit.exists(_.bundleId.isDefined) then result.fold(Nil)(_.semanticIndexes) else Nil
    val claims = result.map(_.outcome) match
      case Some(RagOutcome.Answered(resp, _, _, _)) => resp.claims
      case _                                        => Nil
    val versions = audit.fold(Map.empty[String, String])(_.versions)
    ExplainView(
      r.queryId,
      r.state.wire,
      audit.map(_.outcome),
      audit.flatMap(_.reason),
      audit.flatMap(_.plan).map(p => Wire.toJson(p)),
      audit.flatMap(_.operation),
      audit.flatMap(_.plannerRule),
      audit.flatMap(_.window).map(w => WindowView(w.startYear, w.endYear)),
      if audit.exists(_.bundleId.isDefined) then
        "graph" :: (if searched.nonEmpty then List("semantic-context") else Nil)
      else Nil,
      searched,
      audit.fold(Nil)(stages(_, searched.nonEmpty)),
      audit.fold(Nil)(_.resolvedIds),
      audit.fold(Nil)(_.candidateIds),
      versions.filter((k, _) => k.toLowerCase(java.util.Locale.ROOT).contains("prompt")),
      versions.filter((k, _) => ModelKeys.contains(k)),
      versions.filter((k, _) =>
        !ModelKeys.contains(k) && !k.toLowerCase(java.util.Locale.ROOT).contains("prompt")
      ),
      audit.fold(Nil)(_.graphEvidenceIds),
      audit.fold(Nil)(_.semanticEvidenceIds),
      audit.flatMap(_.bundleId),
      storeStatus(audit.flatMap(_.bundleStore)),
      audit.fold(Nil)(_.truncated),
      claims.map(c =>
        ScoreComponentView(c.claimId, statusWire(c.verificationStatus), c.confidence)
      ),
      auditStatusWire(audit.flatMap(_.overallStatus)),
      audit.fold(Nil)(_.claimVerdicts.map(v => ClaimVerdictView(v.claimId, v.verdict, v.reasons))),
      result.map(w =>
        CountersView(
          w.outcome.audit.latencyMillis,
          w.workflowMillis,
          w.trace.calls,
          w.trace.retries,
          w.trace.faults,
          w.outcome.audit.graphEvidenceIds.size,
          w.outcome.audit.semanticEvidenceIds.size,
          w.outcome.audit.truncated.size,
          if w.pipelineCacheHit then 1 else 0
        )
      )
    )
