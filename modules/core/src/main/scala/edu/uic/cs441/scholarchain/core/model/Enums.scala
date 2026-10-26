package edu.uic.cs441.scholarchain.core.model

import edu.uic.cs441.scholarchain.core.ids.IdKind
import upickle.default.ReadWriter

// Every enum below serializes as its explicit `wire` string. Wire strings are frozen by
// EnumWireSuite and the golden fixtures; changing one is a schema-version change.

/** The dataset (or ScholarChain-internal origin) a record came from. */
enum SourceType(val wire: String):
  case Dblp            extends SourceType("dblp")
  case OpenAlex        extends SourceType("openalex")
  case OpenCitations   extends SourceType("opencitations")
  case Crossref        extends SourceType("crossref")
  case Arxiv           extends SourceType("arxiv")
  case Orcid           extends SourceType("orcid")
  case SemanticScholar extends SourceType("semantic-scholar")
  case PapersWithCode  extends SourceType("papers-with-code")

  /** Produced by a ScholarChain batch pipeline (entity resolution, graph build, metrics). */
  case ScholarChainPipeline extends SourceType("scholarchain-pipeline")

  /** Produced at query time by the ScholarChain runtime (query plans, claims, answers). */
  case ScholarChainRuntime extends SourceType("scholarchain-runtime")

  /** Produced by a human: gold-set labels and adjudications. */
  case HumanCuration extends SourceType("human-curation")

object SourceType:
  given ReadWriter[SourceType] = WireEnum.readWriter("SourceType", values.toSeq, _.wire)
  def fromWire(s: String): Option[SourceType] = values.find(_.wire == s)

/** Outcome of an entity-resolution decision. Match policy itself belongs to entity-resolution. */
enum MatchOutcome(val wire: String):
  case Match       extends MatchOutcome("match")
  case NonMatch    extends MatchOutcome("non-match")
  case NeedsReview extends MatchOutcome("needs-review")
  case Merge       extends MatchOutcome("merge")
  case Split       extends MatchOutcome("split")

object MatchOutcome:
  given ReadWriter[MatchOutcome] = WireEnum.readWriter("MatchOutcome", values.toSeq, _.wire)

/** Who or what made a match decision. */
enum MatchDecider(val wire: String):
  case Rule            extends MatchDecider("rule")
  case Model           extends MatchDecider("model")
  case LlmAdjudication extends MatchDecider("llm-adjudication")
  case Human           extends MatchDecider("human")

object MatchDecider:
  given ReadWriter[MatchDecider] = WireEnum.readWriter("MatchDecider", values.toSeq, _.wire)

/** What an evidence item points at. */
enum EvidenceType(val wire: String):
  case GraphNode    extends EvidenceType("graph-node")
  case GraphEdge    extends EvidenceType("graph-edge")
  case GraphPath    extends EvidenceType("graph-path")
  case SourceRecord extends EvidenceType("source-record")
  case TextChunk    extends EvidenceType("text-chunk")
  case Metric       extends EvidenceType("metric")

object EvidenceType:
  given ReadWriter[EvidenceType] = WireEnum.readWriter("EvidenceType", values.toSeq, _.wire)

/** Per-researcher collaboration metrics computed over a graph window. */
enum CollaborationMetric(val wire: String):
  case Degree                  extends CollaborationMetric("degree")
  case WeightedDegree          extends CollaborationMetric("weighted-degree")
  case CoauthoredWorkCount     extends CollaborationMetric("coauthored-work-count")
  case NewCoauthorCount        extends CollaborationMetric("new-coauthor-count")
  case RepeatCollaborationRate extends CollaborationMetric("repeat-collaboration-rate")
  case ComponentSize           extends CollaborationMetric("component-size")
  case CoreNumber              extends CollaborationMetric("core-number")
  case ClusteringCoefficient   extends CollaborationMetric("clustering-coefficient")
  case BetweennessCentrality   extends CollaborationMetric("betweenness-centrality")
  case BridgeEdgeCount         extends CollaborationMetric("bridge-edge-count")
  case PageRank                extends CollaborationMetric("pagerank")

object CollaborationMetric:
  given ReadWriter[CollaborationMetric] =
    WireEnum.readWriter("CollaborationMetric", values.toSeq, _.wire)

/** One step of a runtime query plan. */
enum QueryOperation(val wire: String):
  case ResolveResearcher     extends QueryOperation("resolve-researcher")
  case FindCoauthors         extends QueryOperation("find-coauthors")
  case CollaborationTimeline extends QueryOperation("collaboration-timeline")
  case CommonCoauthors       extends QueryOperation("common-coauthors")
  case ShortestPath          extends QueryOperation("shortest-path")
  case ResearcherMetrics     extends QueryOperation("researcher-metrics")
  case FindBridges           extends QueryOperation("find-bridges")
  case CommunityMembers      extends QueryOperation("community-members")
  case VectorSearch          extends QueryOperation("vector-search")
  case FetchEvidence         extends QueryOperation("fetch-evidence")

object QueryOperation:
  given ReadWriter[QueryOperation] = WireEnum.readWriter("QueryOperation", values.toSeq, _.wire)

/** Claim verification result. A claim with no evidence must be `Unsupported` (CLAUDE.md law 3). */
enum VerificationStatus(val wire: String):
  case Supported          extends VerificationStatus("supported")
  case PartiallySupported extends VerificationStatus("partially-supported")
  case Unsupported        extends VerificationStatus("unsupported")
  case Contradicted       extends VerificationStatus("contradicted")
  case NotVerified        extends VerificationStatus("not-verified")

object VerificationStatus:
  given ReadWriter[VerificationStatus] =
    WireEnum.readWriter("VerificationStatus", values.toSeq, _.wire)

/** Graph node label. Each node type fixes the stable-ID kind of its node ID. */
enum GraphNodeType(val wire: String, val idKind: IdKind):
  case Researcher  extends GraphNodeType("Researcher", IdKind.Researcher)
  case Work        extends GraphNodeType("Work", IdKind.Work)
  case Venue       extends GraphNodeType("Venue", IdKind.Venue)
  case Institution extends GraphNodeType("Institution", IdKind.Institution)
  case Topic       extends GraphNodeType("Topic", IdKind.Topic)

object GraphNodeType:
  given ReadWriter[GraphNodeType] = WireEnum.readWriter("GraphNodeType", values.toSeq, _.wire)

/**
 * Graph edge label with its endpoint ID kinds. Undirected edge types store their endpoints in
 * canonical order (see [[edu.uic.cs441.scholarchain.core.ids.ResearcherPair]]).
 */
enum GraphEdgeType(
    val wire: String,
    val fromKind: IdKind,
    val toKind: IdKind,
    val undirected: Boolean
):
  case CoauthoredWith
      extends GraphEdgeType("COAUTHORED_WITH", IdKind.Researcher, IdKind.Researcher, true)
  case Authored    extends GraphEdgeType("AUTHORED", IdKind.Researcher, IdKind.Work, false)
  case PublishedIn extends GraphEdgeType("PUBLISHED_IN", IdKind.Work, IdKind.Venue, false)
  case AffiliatedWith
      extends GraphEdgeType("AFFILIATED_WITH", IdKind.Researcher, IdKind.Institution, false)
  case HasTopic extends GraphEdgeType("HAS_TOPIC", IdKind.Work, IdKind.Topic, false)
  case Cites    extends GraphEdgeType("CITES", IdKind.Work, IdKind.Work, false)

object GraphEdgeType:
  given ReadWriter[GraphEdgeType] = WireEnum.readWriter("GraphEdgeType", values.toSeq, _.wire)

/** Kind of publication venue. */
enum VenueKind(val wire: String):
  case Conference extends VenueKind("conference")
  case Journal    extends VenueKind("journal")
  case Workshop   extends VenueKind("workshop")
  case Preprint   extends VenueKind("preprint")
  case Other      extends VenueKind("other")

object VenueKind:
  given ReadWriter[VenueKind] = WireEnum.readWriter("VenueKind", values.toSeq, _.wire)

/** Document type of an embedded chunk or profile (input to the chunk ID). */
enum DocumentType(val wire: String):
  case ResearcherProfile    extends DocumentType("researcher-profile")
  case WorkAbstract         extends DocumentType("work-abstract")
  case WorkMetadata         extends DocumentType("work-metadata")
  case CollaborationSummary extends DocumentType("collaboration-summary")
  case EvidencePath         extends DocumentType("evidence-path")

object DocumentType:
  given ReadWriter[DocumentType] = WireEnum.readWriter("DocumentType", values.toSeq, _.wire)

/**
 * Namespace of an externally sourced identifier inside a namespaced stable ID
 * (`work:doi:10.1145/...`). `ScholarChainV1` (`sc1`) is the hashed fallback used when no
 * authoritative source ID is available.
 */
enum IdNamespace(val wire: String):
  case Doi             extends IdNamespace("doi")
  case Dblp            extends IdNamespace("dblp")
  case OpenAlex        extends IdNamespace("openalex")
  case Arxiv           extends IdNamespace("arxiv")
  case SemanticScholar extends IdNamespace("s2")
  case PapersWithCode  extends IdNamespace("pwc")
  case Issn            extends IdNamespace("issn")
  case Ror             extends IdNamespace("ror")
  case Orcid           extends IdNamespace("orcid")
  case ScholarChainV1  extends IdNamespace("sc1")

  /** OpenCitations Meta identifier of a bibliographic resource (`omid:br/<digits>`). */
  case Omid extends IdNamespace("omid")

  /** PubMed identifier (a positive integer). */
  case Pmid extends IdNamespace("pmid")

object IdNamespace:
  given ReadWriter[IdNamespace] = WireEnum.readWriter("IdNamespace", values.toSeq, _.wire)
  def fromWire(s: String): Option[IdNamespace] = values.find(_.wire == s)
