package edu.uic.cs441.scholarchain.core.model

import upickle.default.ReadWriter

// Shared ScholarChain domain records, wire schema version 1 (SchemaVersion.Current).
//
// Conventions frozen by the golden fixtures (modules/core/src/test/resources/wire/):
// - JSON field names are the Scala field names (camelCase), written in declaration order.
// - No field has a default value, so every field is always written.
// - `Option[T]` is nullable: `None` is written as JSON `null`, never omitted.
// - Lists are always present; an empty list is `[]`.
// - IDs are strings in the formats documented in ids.StableIds and checked by ids.IdFormat.
// - Years are calendar years (Int). Confidences are Doubles in [0, 1].
// - Every record except the embedded value types carries `schemaVersion` and `provenance`.

// ---- Embedded value types -------------------------------------------------------------------

/**
 * Where a record came from (CLAUDE.md law 3). `sourceDataset` is the origin dataset,
 * `sourceRecordId` is the record's key in that dataset (for example a DBLP key or OpenAlex ID), and
 * `pipelineRunId` is the `run:v1:<hex>` ID of the run that produced this record. Runtime records
 * (query plans, claims, answers) use the run ID of the data release they were answered from.
 */
final case class Provenance(
    sourceDataset: SourceType,
    sourceRecordId: String,
    pipelineRunId: String
) derives ReadWriter

/** Inclusive calendar-year window. Valid only when `startYear <= endYear`. */
final case class YearWindow(startYear: Int, endYear: Int) derives ReadWriter

/** An external identifier attached to a record, for example a DOI or an arXiv ID. */
final case class ExternalId(namespace: IdNamespace, value: String) derives ReadWriter

/** A string-valued property. Names are unique within their list. */
final case class Property(name: String, value: String) derives ReadWriter

/** One collaboration metric value. Metrics are unique within their list. */
final case class MetricValue(metric: CollaborationMetric, value: Double) derives ReadWriter

/** One step of a query plan with its string parameters. */
final case class QueryStep(operation: QueryOperation, parameters: List[Property])
    derives ReadWriter

// ---- Bibliographic records ------------------------------------------------------------------

final case class WorkRecord(
    schemaVersion: Int,
    workId: String,
    title: String,
    year: Option[Int],
    venueId: Option[String],
    externalIds: List[ExternalId],
    topicIds: List[String],
    provenance: Provenance
) derives ReadWriter

/**
 * One author occurrence on one work, before entity resolution. `authorPosition` is 0-based.
 *
 * `affiliationText` is the raw, source-supplied affiliation string. It is batch-only input to
 * entity resolution and institution linking: it must never be copied into a runtime store (Neptune,
 * DynamoDB, S3 Vectors), a graph node property, an evidence excerpt, or an answer. Only resolved
 * institution links (ROR or OpenAlex institution IDs, with years) reach runtime, as
 * `AFFILIATED_WITH` graph edges. `AuthorMention` is the only shared record with a raw-affiliation
 * field, and `AffiliationPolicySuite` keeps it that way.
 */
final case class AuthorMention(
    schemaVersion: Int,
    mentionId: String,
    workId: String,
    authorPosition: Int,
    rawName: String,
    normalizedName: String,
    orcid: Option[String],
    affiliationText: Option[String],
    provenance: Provenance
) derives ReadWriter

/**
 * A resolved researcher identity. `institutionIds` holds only resolved institution IDs
 * (`institution:ror:` or `institution:openalex:`), never raw affiliation text or a hashed `sc1`
 * fallback. The list carries no years, so it is a batch-side summary: runtime affiliation facts
 * come from `AFFILIATED_WITH` graph edges, which carry a window.
 */
final case class CanonicalResearcher(
    schemaVersion: Int,
    researcherId: String,
    displayName: String,
    orcid: Option[String],
    nameVariants: List[String],
    institutionIds: List[String],
    provenance: Provenance
) derives ReadWriter

/** A resolved authorship: mention `mentionId` on `workId` is researcher `researcherId`. */
final case class AuthorshipRecord(
    schemaVersion: Int,
    workId: String,
    researcherId: String,
    mentionId: String,
    authorPosition: Int,
    year: Option[Int],
    provenance: Provenance
) derives ReadWriter

/** The distinct resolved researchers of one work, in author order. */
final case class PublicationTeam(
    schemaVersion: Int,
    workId: String,
    year: Option[Int],
    researcherIds: List[String],
    provenance: Provenance
) derives ReadWriter

/** One undirected coauthor pair from one work. `researcherLow < researcherHigh`. */
final case class CoauthorPairCandidate(
    schemaVersion: Int,
    researcherLow: String,
    researcherHigh: String,
    workId: String,
    year: Option[Int],
    provenance: Provenance
) derives ReadWriter

/**
 * Aggregated `COAUTHORED_WITH` edge over a graph window. `researcherLow < researcherHigh`;
 * `supportingWorkIds` is sorted and distinct and its size equals `coauthoredWorkCount`;
 * `window.startYear <= firstYear <= lastYear <= window.endYear`.
 */
final case class CoauthorEdge(
    schemaVersion: Int,
    edgeId: String,
    researcherLow: String,
    researcherHigh: String,
    window: YearWindow,
    policyVersion: String,
    coauthoredWorkCount: Int,
    firstYear: Int,
    lastYear: Int,
    supportingWorkIds: List[String],
    confidence: Double,
    provenance: Provenance
) derives ReadWriter

final case class ResearcherMetrics(
    schemaVersion: Int,
    researcherId: String,
    window: YearWindow,
    policyVersion: String,
    metrics: List[MetricValue],
    provenance: Provenance
) derives ReadWriter

final case class Venue(
    schemaVersion: Int,
    venueId: String,
    name: String,
    venueKind: VenueKind,
    provenance: Provenance
) derives ReadWriter

/** `countryCode` is an ISO 3166-1 alpha-2 code when known. */
final case class Institution(
    schemaVersion: Int,
    institutionId: String,
    name: String,
    countryCode: Option[String],
    provenance: Provenance
) derives ReadWriter

final case class Topic(
    schemaVersion: Int,
    topicId: String,
    name: String,
    provenance: Provenance
) derives ReadWriter

/**
 * Shared envelope of an entity-resolution decision. Features, scores, and thresholds stay in the
 * entity-resolution module; `reasonCodes` are opaque to core. `Match` requires a candidate.
 */
final case class MatchDecision(
    schemaVersion: Int,
    decisionId: String,
    mentionId: String,
    candidateResearcherId: Option[String],
    outcome: MatchOutcome,
    decider: MatchDecider,
    confidence: Double,
    policyVersion: String,
    reasonCodes: List[String],
    provenance: Provenance
) derives ReadWriter

// ---- Graph records --------------------------------------------------------------------------

/**
 * `nodeId` is the stable entity ID whose kind `nodeType` fixes. An `Institution` node must be a
 * resolved institution (`institution:ror:` or `institution:openalex:`). Property names containing
 * `affiliation` (any case) are reserved and rejected: affiliations reach the graph only as
 * `AFFILIATED_WITH` edges.
 */
final case class GraphNode(
    schemaVersion: Int,
    nodeId: String,
    nodeType: GraphNodeType,
    label: String,
    properties: List[Property],
    provenance: Provenance
) derives ReadWriter

/**
 * A typed graph edge. For undirected types `fromId < toId`.
 *
 * Identity: `edgeId` is [[edu.uic.cs441.scholarchain.core.ids.StableIds.graphEdgeId]] of
 * `edgeType`, the canonical endpoints, `policyVersion`, and `window`. `evidenceIds` (non-empty,
 * sorted, distinct) is an attribute, not part of the identity, so the edge keeps its ID as evidence
 * accumulates and graph-store upserts keyed by `edgeId` are idempotent.
 *
 * Affiliation policy: an `AFFILIATED_WITH` edge must target a resolved institution
 * (`institution:ror:` or `institution:openalex:`) and must carry a `window` (the years the
 * affiliation is attested). Raw affiliation text never appears on an edge.
 */
final case class GraphEdge(
    schemaVersion: Int,
    edgeId: String,
    edgeType: GraphEdgeType,
    fromId: String,
    toId: String,
    policyVersion: String,
    window: Option[YearWindow],
    weight: Option[Double],
    confidence: Double,
    evidenceIds: List[String],
    provenance: Provenance
) derives ReadWriter

// ---- Evidence and answers -------------------------------------------------------------------

/**
 * One piece of evidence. `subjectId` is the stable ID of the graph element, record, or chunk it
 * cites; `contentHash` is `sha256:<hex>` of the cited content. `excerpt` is runtime-facing: it must
 * not quote raw affiliation text (see [[AuthorMention]]); cite the resolved institution or the
 * `AFFILIATED_WITH` edge instead.
 */
final case class EvidenceItem(
    schemaVersion: Int,
    evidenceId: String,
    evidenceType: EvidenceType,
    subjectId: String,
    contentHash: String,
    excerpt: Option[String],
    provenance: Provenance
) derives ReadWriter

final case class EvidenceBundle(
    schemaVersion: Int,
    bundleId: String,
    items: List[EvidenceItem],
    provenance: Provenance
) derives ReadWriter

final case class QueryPlan(
    schemaVersion: Int,
    planId: String,
    question: String,
    researcherIds: List[String],
    window: Option[YearWindow],
    steps: List[QueryStep],
    provenance: Provenance
) derives ReadWriter

/** A claim with no evidence must have `verificationStatus = unsupported`. */
final case class AnswerClaim(
    schemaVersion: Int,
    claimId: String,
    text: String,
    evidenceIds: List[String],
    verificationStatus: VerificationStatus,
    confidence: Double,
    provenance: Provenance
) derives ReadWriter

/**
 * A verified answer. Every evidence ID cited by a claim must be present in `evidence`. An answer
 * with no claims may only be `unsupported` or `not-verified`. `supported` requires a non-empty
 * claim list with every claim `supported`; `partially-supported` requires a `supported` or
 * `partially-supported` claim; `contradicted` requires a `contradicted` claim (see
 * `Validators.overallStatusErrors`).
 */
final case class AnswerResponse(
    schemaVersion: Int,
    responseId: String,
    planId: String,
    answerText: String,
    claims: List[AnswerClaim],
    evidence: EvidenceBundle,
    overallStatus: VerificationStatus,
    provenance: Provenance
) derives ReadWriter
