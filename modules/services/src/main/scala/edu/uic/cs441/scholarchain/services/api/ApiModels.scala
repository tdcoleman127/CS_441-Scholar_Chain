package edu.uic.cs441.scholarchain.services.api

import upickle.default.ReadWriter

// REST response models (REST API contract). Field names are the JSON names. Option fields are
// written as JSON null (the project-wide uPickle convention): null means "not available in the
// evidence", never a guessed value.

final case class BridgeMetricView(name: String, value: Double) derives ReadWriter

/** One researcher in an answer. Values come only from graph facts in the evidence bundle. */
final case class ResearcherView(
    researcherId: String,
    displayName: Option[String],
    publicationCount: Option[Long],
    distinctCoauthorCount: Option[Double],
    weightedDegree: Option[Double],
    averageCoauthorsPerPaper: Option[Double],
    maximumCoauthorsOnOnePaper: Option[Int],
    maximumTeamWorkIds: List[String],
    componentId: Option[String],
    communityId: Option[String],
    bridgeMetric: Option[BridgeMetricView],
    metrics: Map[String, Double],
    claimIds: List[String]
) derives ReadWriter

final case class AnswerView(summary: String, researchers: List[ResearcherView]) derives ReadWriter

final case class ClaimView(
    claimId: String,
    text: String,
    evidenceIds: List[String],
    verificationStatus: String,
    confidence: Option[Double],
    uncertainty: Option[String]
) derives ReadWriter

final case class CandidateView(researcherId: String, displayName: String, matchRule: String)
    derives ReadWriter

/** Why the runtime did not answer. Abstaining is a correct, evidence-respecting outcome. */
final case class AbstentionView(
    reason: String,
    detail: Option[String],
    candidates: List[CandidateView],
    totalCandidates: Option[Int]
) derives ReadWriter

final case class EvidenceSummaryView(
    evidenceId: String,
    evidenceType: String,
    subjectId: String,
    contentHash: String,
    href: String
) derives ReadWriter

final case class QueryLinks(evidence: String, explain: String) derives ReadWriter

/** Answer body: the synchronous `POST /v1/query` response and `GET /v1/jobs/{id}/result`. */
final case class QueryResponse(
    queryId: String,
    mode: String,
    status: String,
    answer: Option[AnswerView],
    abstention: Option[AbstentionView],
    claims: List[ClaimView],
    overallStatus: Option[String],
    evidence: Option[List[EvidenceSummaryView]],
    links: QueryLinks
) derives ReadWriter

/** Job reference: the asynchronous `POST /v1/query` response (HTTP 202). */
final case class AsyncQueryResponse(
    queryId: String,
    mode: String,
    jobId: String,
    status: String,
    statusUrl: String
) derives ReadWriter

final case class JobErrorView(error: String, message: String, retryable: Boolean)
    derives ReadWriter

final case class JobView(
    jobId: String,
    queryId: String,
    state: String,
    stage: String,
    createdAt: String,
    updatedAt: String,
    resultUrl: Option[String],
    error: Option[JobErrorView]
) derives ReadWriter

final case class EvidenceProvenanceView(
    runId: String,
    textHash: String,
    schemaVersion: String,
    sourceDataset: String,
    sourceRecordId: String
) derives ReadWriter

/** `GET /v1/evidence/{evidenceId}`: provenance, source record, pointer, and a bounded excerpt. */
final case class EvidenceView(
    evidenceId: String,
    evidenceType: String,
    subjectId: String,
    workId: Option[String],
    sourceDataset: String,
    sourceRecordId: String,
    snapshot: Option[String],
    text: Option[String],
    textS3Uri: Option[String],
    sourceUri: Option[String],
    graphPath: List[String],
    contextOnly: Boolean,
    bundleId: String,
    score: Option[Double],
    provenance: EvidenceProvenanceView
) derives ReadWriter

final case class QueryEvidenceView(queryId: String, items: List[EvidenceSummaryView])
    derives ReadWriter

final case class WindowView(startYear: Int, endYear: Int) derives ReadWriter

final case class MembershipView(
    window: WindowView,
    communityId: String,
    communitySize: Long,
    componentId: String,
    componentSize: Long,
    crossCommunityDegree: Long,
    distinctCommunitiesTouched: Long,
    algorithmVersion: String
) derives ReadWriter

final case class MetricsView(window: WindowView, policyVersion: String, values: Map[String, Double])
    derives ReadWriter

final case class NodeProvenanceView(sourceDataset: String, sourceRecordId: String, runId: String)
    derives ReadWriter

/** `GET /v1/graph/nodes/{nodeId}`. */
final case class NodeView(
    nodeId: String,
    nodeType: String,
    label: String,
    orcid: Option[String],
    nameVariants: List[String],
    institutionIds: List[String],
    memberships: List[MembershipView],
    metrics: List[MetricsView],
    provenance: NodeProvenanceView,
    neighborsUrl: String
) derives ReadWriter

final case class EdgeView(
    edgeId: String,
    edgeType: String,
    fromId: String,
    toId: String,
    coauthoredWorkCount: Int,
    firstYear: Int,
    lastYear: Int
) derives ReadWriter

final case class NeighborView(
    nodeId: String,
    nodeType: String,
    label: Option[String],
    depth: Int,
    via: EdgeView
) derives ReadWriter

final case class PageView(limit: Int, returned: Int, total: Int, nextCursor: Option[String])
    derives ReadWriter

/** `GET /v1/graph/nodes/{nodeId}/neighbors`. */
final case class NeighborsView(
    nodeId: String,
    direction: String,
    depth: Int,
    edgeTypes: List[String],
    window: WindowView,
    items: List[NeighborView],
    page: PageView,
    truncated: List[String]
) derives ReadWriter

final case class ClaimVerdictView(claimId: String, verdict: String, reasons: List[String])
    derives ReadWriter

final case class ScoreComponentView(
    claimId: String,
    verificationStatus: String,
    confidence: Double
) derives ReadWriter

final case class CountersView(
    ragLatencyMillis: Long,
    workflowMillis: Long,
    downstreamCalls: Int,
    downstreamRetries: Int,
    downstreamFaults: List[String],
    graphEvidenceCount: Int,
    semanticEvidenceCount: Int,
    truncatedCount: Int,
    pipelineCacheHits: Int
) derives ReadWriter

/**
 * `GET /v1/explain/{queryId}`: the recorded plan and audit. It holds no hidden reasoning: the
 * runtime's planner, retrieval, and verifier are rule-based and record only typed decisions.
 */
final case class ExplainView(
    queryId: String,
    state: String,
    outcome: Option[String],
    reason: Option[String],
    queryPlan: Option[ujson.Value],
    operation: Option[String],
    plannerRule: Option[String],
    window: Option[WindowView],
    retrievalModes: List[String],
    indexesQueried: List[String],
    stagesExecuted: List[String],
    resolvedResearcherIds: List[String],
    candidateResearcherIds: List[String],
    promptVersions: Map[String, String],
    modelVersions: Map[String, String],
    ruleVersions: Map[String, String],
    evidenceIds: List[String],
    semanticEvidenceIds: List[String],
    bundleId: Option[String],
    bundleStore: Option[String],
    truncated: List[String],
    scoreComponents: List[ScoreComponentView],
    verification: Option[String],
    claimVerdicts: List[ClaimVerdictView],
    counters: Option[CountersView]
) derives ReadWriter

/** One readiness check: its name and `pass` or `fail` only (details go to the log). */
final case class CheckView(name: String, status: String) derives ReadWriter

final case class HealthView(status: String, service: String) derives ReadWriter

final case class ReadinessView(status: String, checks: List[CheckView], traceId: String)
    derives ReadWriter
