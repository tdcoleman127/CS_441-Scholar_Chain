package edu.uic.cs441.scholarchain.core

import edu.uic.cs441.scholarchain.core.ids.{CanonicalHash, ResearcherPair, StableIds}
import edu.uic.cs441.scholarchain.core.json.Wire
import edu.uic.cs441.scholarchain.core.model.*

/**
 * Representative, valid records used by the contract tests and frozen in src/test/resources/wire/.
 * All names, keys, and DOIs are fictional sample values.
 */
object Fixtures:

  private def right[A](e: Either[?, A]): A = e.fold(err => sys.error(s"fixture: $err"), identity)

  val manifest: RunManifest =
    val draft = RunManifest(
      schemaVersion = 1,
      runId = "",
      pipelineName = "hadoop-dblp-parse",
      codeVersion = "git-0000000000000000000000000000000000000000",
      configFingerprint = CanonicalHash.contentHash("sample configuration"),
      inputs = List(
        RunInput(SourceType.Dblp, "dblp-sample-2026-10", CanonicalHash.contentHash("dblp sample")),
        RunInput(
          SourceType.OpenAlex,
          "openalex-sample-2026-10",
          CanonicalHash.contentHash("oa sample")
        )
      ),
      attempt = 1
    )
    draft.copy(runId = StableIds.runId(draft))

  val runId: String = manifest.runId

  def prov(dataset: SourceType, record: String): Provenance = Provenance(dataset, record, runId)

  val dblpKey = "conf/kdd/CarberrySmith24"

  val workId: String  = right(StableIds.workId(IdNamespace.Dblp, dblpKey))
  val workId2: String =
    right(StableIds.workId(IdNamespace.Doi, "https://doi.org/10.5555/SAMPLE.0002"))
  val venueId       = "venue:dblp:conf/kdd"
  val topicId       = "topic:openalex:T10001"
  val institutionId = "institution:ror:0sample00"

  val orcidA              = "0000-0002-1825-0097"
  val researcherA: String = right(StableIds.researcherIdFromOrcid(orcidA))
  val researcherB: String =
    right(StableIds.scholarChainResearcherId(1, SourceType.Dblp, "homepages/s/JaneSmith"))
  val pair: ResearcherPair = right(ResearcherPair.of(researcherB, researcherA))
  val window: YearWindow   = YearWindow(2020, 2024)
  val graphPolicy          = "graph-policy-v1"
  val erPolicy             = "er-policy-v1"

  val work: WorkRecord = WorkRecord(
    schemaVersion = 1,
    workId = workId,
    title = "A Sample Study of Collaboration Graphs",
    year = Some(2024),
    venueId = Some(venueId),
    externalIds = List(ExternalId(IdNamespace.Doi, "10.5555/sample.0001")),
    topicIds = List(topicId),
    provenance = prov(SourceType.Dblp, dblpKey)
  )

  val mention: AuthorMention = AuthorMention(
    schemaVersion = 1,
    mentionId = StableIds.mentionId(workId, 0, SourceType.Dblp, dblpKey),
    workId = workId,
    authorPosition = 0,
    rawName = "Josiah Carberry",
    normalizedName = "josiah carberry",
    orcid = Some(orcidA),
    affiliationText = None,
    provenance = prov(SourceType.Dblp, dblpKey)
  )

  val mentionB: AuthorMention =
    mention.copy(
      mentionId = StableIds.mentionId(workId, 1, SourceType.Dblp, dblpKey),
      authorPosition = 1,
      rawName = "Jane Smith 0001",
      normalizedName = "jane smith",
      orcid = None
    )

  val researcher: CanonicalResearcher = CanonicalResearcher(
    schemaVersion = 1,
    researcherId = researcherA,
    displayName = "Josiah Carberry",
    orcid = Some(orcidA),
    nameVariants = List("J. Carberry", "Josiah Carberry"),
    institutionIds = List(institutionId),
    provenance = prov(SourceType.ScholarChainPipeline, "er-cluster-0001")
  )

  val authorship: AuthorshipRecord = AuthorshipRecord(
    schemaVersion = 1,
    workId = workId,
    researcherId = researcherA,
    mentionId = mention.mentionId,
    authorPosition = 0,
    year = Some(2024),
    provenance = prov(SourceType.Dblp, dblpKey)
  )

  val team: PublicationTeam = PublicationTeam(
    schemaVersion = 1,
    workId = workId,
    year = Some(2024),
    researcherIds = List(researcherA, researcherB),
    provenance = prov(SourceType.Dblp, dblpKey)
  )

  val pairCandidate: CoauthorPairCandidate = CoauthorPairCandidate(
    schemaVersion = 1,
    researcherLow = pair.low,
    researcherHigh = pair.high,
    workId = workId,
    year = Some(2024),
    provenance = prov(SourceType.Dblp, dblpKey)
  )

  val coauthorEdge: CoauthorEdge = CoauthorEdge(
    schemaVersion = 1,
    edgeId = StableIds.coauthorEdgeId(pair, window, graphPolicy),
    researcherLow = pair.low,
    researcherHigh = pair.high,
    window = window,
    policyVersion = graphPolicy,
    coauthoredWorkCount = 2,
    firstYear = 2022,
    lastYear = 2024,
    supportingWorkIds = List(workId, workId2).sorted,
    confidence = 0.95,
    provenance = prov(SourceType.ScholarChainPipeline, "coauthor-aggregation")
  )

  val metrics: ResearcherMetrics = ResearcherMetrics(
    schemaVersion = 1,
    researcherId = researcherA,
    window = window,
    policyVersion = graphPolicy,
    metrics = List(
      MetricValue(CollaborationMetric.Degree, 1.0),
      MetricValue(CollaborationMetric.CoauthoredWorkCount, 2.0),
      MetricValue(CollaborationMetric.ClusteringCoefficient, 0.25)
    ),
    provenance = prov(SourceType.ScholarChainPipeline, "graph-metrics")
  )

  val venue: Venue = Venue(
    1,
    venueId,
    "Sample Conference on Knowledge Discovery",
    VenueKind.Conference,
    prov(SourceType.Dblp, "conf/kdd")
  )

  val institution: Institution =
    Institution(
      1,
      institutionId,
      "Sample University",
      Some("US"),
      prov(SourceType.OpenAlex, "I0000001")
    )

  val topic: Topic = Topic(1, topicId, "Graph Analytics", prov(SourceType.OpenAlex, "T10001"))

  val matchDecision: MatchDecision = MatchDecision(
    schemaVersion = 1,
    decisionId = StableIds.matchDecisionId(mention.mentionId, Some(researcherA), erPolicy),
    mentionId = mention.mentionId,
    candidateResearcherId = Some(researcherA),
    outcome = MatchOutcome.Match,
    decider = MatchDecider.Rule,
    confidence = 0.99,
    policyVersion = erPolicy,
    reasonCodes = List("orcid-exact"),
    provenance = prov(SourceType.ScholarChainPipeline, "er-decision-0001")
  )

  val graphNode: GraphNode = GraphNode(
    schemaVersion = 1,
    nodeId = researcherA,
    nodeType = GraphNodeType.Researcher,
    label = "Josiah Carberry",
    properties = List(Property("orcid", orcidA)),
    provenance = prov(SourceType.ScholarChainPipeline, "graph-nodes")
  )

  val sourceEvidence: EvidenceItem =
    val hash = CanonicalHash.contentHash(s"$dblpKey|Josiah Carberry|Jane Smith 0001")
    EvidenceItem(
      schemaVersion = 1,
      evidenceId =
        StableIds.evidenceId(EvidenceType.SourceRecord, workId, hash, SourceType.Dblp, dblpKey),
      evidenceType = EvidenceType.SourceRecord,
      subjectId = workId,
      contentHash = hash,
      excerpt =
        Some("Josiah Carberry, Jane Smith 0001: A Sample Study of Collaboration Graphs. KDD 2024"),
      provenance = prov(SourceType.Dblp, dblpKey)
    )

  val graphEdge: GraphEdge = GraphEdge(
    schemaVersion = 1,
    edgeId = StableIds.graphEdgeId(
      GraphEdgeType.CoauthoredWith,
      pair.low,
      pair.high,
      graphPolicy,
      Some(window)
    ),
    edgeType = GraphEdgeType.CoauthoredWith,
    fromId = pair.low,
    toId = pair.high,
    policyVersion = graphPolicy,
    window = Some(window),
    weight = Some(2.0),
    confidence = 0.95,
    evidenceIds = List(sourceEvidence.evidenceId),
    provenance = prov(SourceType.ScholarChainPipeline, "graph-edges")
  )

  val edgeEvidence: EvidenceItem =
    val hash = CanonicalHash.contentHash(Wire.encode(graphEdge))
    EvidenceItem(
      schemaVersion = 1,
      evidenceId = StableIds.evidenceId(
        EvidenceType.GraphEdge,
        graphEdge.edgeId,
        hash,
        SourceType.ScholarChainPipeline,
        "graph-edges"
      ),
      evidenceType = EvidenceType.GraphEdge,
      subjectId = graphEdge.edgeId,
      contentHash = hash,
      excerpt = None,
      provenance = prov(SourceType.ScholarChainPipeline, "graph-edges")
    )

  val bundle: EvidenceBundle =
    val items = List(sourceEvidence, edgeEvidence)
    EvidenceBundle(
      schemaVersion = 1,
      bundleId = StableIds.evidenceBundleId(items.map(_.evidenceId)),
      items = items,
      provenance = prov(SourceType.ScholarChainRuntime, "request-0001")
    )

  val queryPlan: QueryPlan =
    val steps = List(
      QueryStep(QueryOperation.ResolveResearcher, List(Property("name", "Josiah Carberry"))),
      QueryStep(QueryOperation.FindCoauthors, List(Property("limit", "10"))),
      QueryStep(QueryOperation.FetchEvidence, Nil)
    )
    val question = "Who has Josiah Carberry collaborated with since 2020?"
    QueryPlan(
      schemaVersion = 1,
      planId = StableIds.queryPlanId(question, List(researcherA), Some(window), steps),
      question = question,
      researcherIds = List(researcherA),
      window = Some(window),
      steps = steps,
      provenance = prov(SourceType.ScholarChainRuntime, "request-0001")
    )

  val claim: AnswerClaim =
    val text      = "Josiah Carberry coauthored two works with Jane Smith between 2022 and 2024."
    val evidences = List(edgeEvidence.evidenceId, sourceEvidence.evidenceId)
    AnswerClaim(
      schemaVersion = 1,
      claimId = StableIds.claimId(text, evidences),
      text = text,
      evidenceIds = evidences,
      verificationStatus = VerificationStatus.Supported,
      confidence = 0.9,
      provenance = prov(SourceType.ScholarChainRuntime, "request-0001")
    )

  val answer: AnswerResponse = AnswerResponse(
    schemaVersion = 1,
    responseId = StableIds.answerId(queryPlan.planId, bundle.bundleId, List(claim.claimId)),
    planId = queryPlan.planId,
    answerText = "Since 2020, Josiah Carberry has collaborated with Jane Smith on two works.",
    claims = List(claim),
    evidence = bundle,
    overallStatus = VerificationStatus.Supported,
    provenance = prov(SourceType.ScholarChainRuntime, "request-0001")
  )
