package edu.uic.cs441.scholarchain.core.validation

import edu.uic.cs441.scholarchain.core.SchemaVersion
import edu.uic.cs441.scholarchain.core.ids.{IdFormat, IdKind, ResearcherPair, StableIds}
import edu.uic.cs441.scholarchain.core.model.*
import edu.uic.cs441.scholarchain.core.validation.ValidationError.*

/** Contract validation for a record type. Returns every violation found, not just the first. */
trait Validator[A]:
  def validate(a: A): List[ValidationError]

  /** Whether the wire form must carry a top-level `provenance` object. */
  def requiresProvenance: Boolean = true

object Validation:

  def validate[A](a: A)(using v: Validator[A]): Either[List[ValidationError], A] =
    v.validate(a) match
      case Nil    => Right(a)
      case errors => Left(errors)

  def isValid[A](a: A)(using v: Validator[A]): Boolean = v.validate(a).isEmpty

/** Reusable checks. Each returns the violations it finds (possibly none). */
object Checks:

  private def at(prefix: String, field: String): String =
    if prefix.isEmpty then field else s"$prefix.$field"

  def schemaVersion(prefix: String, version: Int): List[ValidationError] =
    if SchemaVersion.isSupported(version) then Nil
    else
      List(
        UnsupportedSchemaVersion(
          at(prefix, "schemaVersion"),
          version,
          SchemaVersion.Supported.toList.sorted
        )
      )

  def nonBlank(path: String, s: String): List[ValidationError] =
    if s == null || s.trim.isEmpty then List(InvalidField(path, "must not be blank")) else Nil

  def provenance(prefix: String, p: Provenance): List[ValidationError] =
    val path = at(prefix, "provenance")
    if p == null then List(MissingProvenance(path, "provenance is absent"))
    else
      val dataset =
        if p.sourceDataset == null then List(MissingProvenance(s"$path.sourceDataset", "absent"))
        else Nil
      val record =
        if p.sourceRecordId == null || p.sourceRecordId.trim.isEmpty then
          List(MissingProvenance(s"$path.sourceRecordId", "blank source record ID"))
        else Nil
      val run =
        if p.pipelineRunId == null || p.pipelineRunId.trim.isEmpty then
          List(MissingProvenance(s"$path.pipelineRunId", "blank pipeline run ID"))
        else if !IdFormat.isValid(IdKind.Run, p.pipelineRunId) then
          List(MalformedId(s"$path.pipelineRunId", IdKind.Run.prefix, p.pipelineRunId))
        else Nil
      dataset ++ record ++ run

  def confidence(path: String, c: Double): List[ValidationError] =
    if c.isNaN || c < 0.0 || c > 1.0 then List(ConfidenceOutOfRange(path, c)) else Nil

  def id(path: String, kind: IdKind, value: String): List[ValidationError] =
    if value != null && IdFormat.isValid(kind, value) then Nil
    else List(MalformedId(path, kind.prefix, String.valueOf(value)))

  def optId(path: String, kind: IdKind, value: Option[String]): List[ValidationError] =
    value.toList.flatMap(id(path, kind, _))

  def ids(path: String, kind: IdKind, values: List[String]): List[ValidationError] =
    values.zipWithIndex.flatMap((v, i) => id(s"$path[$i]", kind, v))

  def window(path: String, w: YearWindow): List[ValidationError] =
    if w.startYear > w.endYear then List(InvalidYearWindow(path, w.startYear, w.endYear)) else Nil

  def formula(path: String, found: String, expected: String): List[ValidationError] =
    if found == expected then Nil else List(IdMismatch(path, found, expected))

  def distinct(path: String, values: List[String]): List[ValidationError] =
    if values.distinct.size == values.size then Nil
    else List(InvalidField(path, "must not contain duplicates"))

  def sortedDistinct(path: String, values: List[String]): List[ValidationError] =
    if values == values.distinct.sorted then Nil
    else List(InvalidField(path, "must be sorted ascending with no duplicates"))

  def nonEmpty(path: String, values: List[?]): List[ValidationError] =
    if values.isEmpty then List(InvalidField(path, "must not be empty")) else Nil

  def finite(path: String, d: Double): List[ValidationError] =
    if d.isNaN || d.isInfinite then List(InvalidField(path, "must be a finite number")) else Nil

  def pair(prefix: String, low: String, high: String): List[ValidationError] =
    ResearcherPair
      .fromOrdered(low, high, at(prefix, "researcherLow"), at(prefix, "researcherHigh"))
      .left
      .getOrElse(Nil)

  /** Rejects an institution ID that is not in a resolved namespace (ROR or OpenAlex). */
  def resolvedInstitution(path: String, value: String): List[ValidationError] =
    if IdFormat.isResolvedInstitutionId(value) then Nil
    else
      List(
        InvalidField(
          path,
          s"'$value' is not a resolved institution ID (institution:ror: or institution:openalex:)"
        )
      )

  /** Property names containing `affiliation` (any case) are reserved; see GraphNode. */
  def noAffiliationProperties(path: String, props: List[Property]): List[ValidationError] =
    props.zipWithIndex.collect {
      case (p, i)
          if p.name != null && p.name.toLowerCase(java.util.Locale.ROOT).contains("affiliation") =>
        InvalidField(
          s"$path[$i].name",
          "affiliation properties are not allowed; use a resolved AFFILIATED_WITH edge"
        )
    }

  def uniqueNames(path: String, props: List[Property]): List[ValidationError] =
    props.zipWithIndex.flatMap((p, i) => nonBlank(s"$path[$i].name", p.name)) ++
      distinct(path, props.map(_.name))

/** Validators for every record in the shared contract. */
object Validators:
  import Checks.*

  private def common(version: Int, prov: Provenance): List[ValidationError] =
    schemaVersion("", version) ++ provenance("", prov)

  given Validator[YearWindow] with
    def validate(w: YearWindow): List[ValidationError] = window("", w)
    override def requiresProvenance: Boolean           = false

  given Validator[Provenance] with
    def validate(p: Provenance): List[ValidationError] = provenance("", p)
    override def requiresProvenance: Boolean           = false

  given Validator[WorkRecord] with
    def validate(r: WorkRecord): List[ValidationError] =
      common(r.schemaVersion, r.provenance) ++
        id("workId", IdKind.Work, r.workId) ++
        nonBlank("title", r.title) ++
        optId("venueId", IdKind.Venue, r.venueId) ++
        ids("topicIds", IdKind.Topic, r.topicIds) ++
        distinct("topicIds", r.topicIds) ++
        r.externalIds.zipWithIndex.flatMap((e, i) =>
          IdFormat
            .normalizeSourceId(e.namespace, e.value)
            .fold(
              reason => List(MalformedId(s"externalIds[$i].value", e.namespace.wire, reason)),
              n =>
                if n == e.value then Nil
                else List(InvalidField(s"externalIds[$i].value", s"not normalized (expected '$n')"))
            )
        )

  given Validator[AuthorMention] with
    def validate(r: AuthorMention): List[ValidationError] =
      common(r.schemaVersion, r.provenance) ++
        id("workId", IdKind.Work, r.workId) ++
        (if r.authorPosition < 0 then List(InvalidField("authorPosition", "must be >= 0"))
         else Nil) ++
        nonBlank("rawName", r.rawName) ++
        nonBlank("normalizedName", r.normalizedName) ++
        r.orcid.toList.flatMap(o =>
          if IdFormat.normalizeOrcid(o) == Right(o) then Nil
          else List(MalformedId("orcid", "orcid", o))
        ) ++
        (if r.provenance == null then Nil
         else
           formula(
             "mentionId",
             r.mentionId,
             StableIds.mentionId(
               r.workId,
               r.authorPosition,
               r.provenance.sourceDataset,
               r.provenance.sourceRecordId
             )
           ))

  given Validator[CanonicalResearcher] with
    def validate(r: CanonicalResearcher): List[ValidationError] =
      common(r.schemaVersion, r.provenance) ++
        id("researcherId", IdKind.Researcher, r.researcherId) ++
        nonBlank("displayName", r.displayName) ++
        r.orcid.toList.flatMap(o =>
          if IdFormat.normalizeOrcid(o) != Right(o) then List(MalformedId("orcid", "orcid", o))
          else if r.researcherId.startsWith("researcher:orcid:") &&
            r.researcherId != s"researcher:orcid:$o"
          then List(InvalidField("orcid", "disagrees with the ORCID in researcherId"))
          else Nil
        ) ++
        r.institutionIds.zipWithIndex.flatMap((inst, i) =>
          resolvedInstitution(s"institutionIds[$i]", inst)
        ) ++
        distinct("institutionIds", r.institutionIds)

  given Validator[AuthorshipRecord] with
    def validate(r: AuthorshipRecord): List[ValidationError] =
      common(r.schemaVersion, r.provenance) ++
        id("workId", IdKind.Work, r.workId) ++
        id("researcherId", IdKind.Researcher, r.researcherId) ++
        id("mentionId", IdKind.Mention, r.mentionId) ++
        (if r.authorPosition < 0 then List(InvalidField("authorPosition", "must be >= 0")) else Nil)

  given Validator[PublicationTeam] with
    def validate(r: PublicationTeam): List[ValidationError] =
      common(r.schemaVersion, r.provenance) ++
        id("workId", IdKind.Work, r.workId) ++
        nonEmpty("researcherIds", r.researcherIds) ++
        ids("researcherIds", IdKind.Researcher, r.researcherIds) ++
        distinct("researcherIds", r.researcherIds)

  given Validator[CoauthorPairCandidate] with
    def validate(r: CoauthorPairCandidate): List[ValidationError] =
      common(r.schemaVersion, r.provenance) ++
        pair("", r.researcherLow, r.researcherHigh) ++
        id("workId", IdKind.Work, r.workId)

  given Validator[CoauthorEdge] with
    def validate(r: CoauthorEdge): List[ValidationError] =
      val pairErrors = pair("", r.researcherLow, r.researcherHigh)
      val years      =
        if r.firstYear > r.lastYear then
          List(InvalidYearWindow("firstYear", r.firstYear, r.lastYear))
        else if r.firstYear < r.window.startYear || r.lastYear > r.window.endYear then
          List(InvalidField("firstYear", "firstYear..lastYear must lie inside window"))
        else Nil
      val idCheck =
        ResearcherPair
          .fromOrdered(r.researcherLow, r.researcherHigh)
          .toOption
          .toList
          .flatMap(p =>
            formula("edgeId", r.edgeId, StableIds.coauthorEdgeId(p, r.window, r.policyVersion))
          )
      common(r.schemaVersion, r.provenance) ++
        pairErrors ++
        window("window", r.window) ++
        years ++
        nonBlank("policyVersion", r.policyVersion) ++
        nonEmpty("supportingWorkIds", r.supportingWorkIds) ++
        ids("supportingWorkIds", IdKind.Work, r.supportingWorkIds) ++
        sortedDistinct("supportingWorkIds", r.supportingWorkIds) ++
        (if r.coauthoredWorkCount != r.supportingWorkIds.size then
           List(InvalidField("coauthoredWorkCount", "must equal the number of supportingWorkIds"))
         else Nil) ++
        confidence("confidence", r.confidence) ++
        (if pairErrors.isEmpty then idCheck else id("edgeId", IdKind.CoauthorEdge, r.edgeId))

  given Validator[ResearcherMetrics] with
    def validate(r: ResearcherMetrics): List[ValidationError] =
      common(r.schemaVersion, r.provenance) ++
        id("researcherId", IdKind.Researcher, r.researcherId) ++
        window("window", r.window) ++
        nonBlank("policyVersion", r.policyVersion) ++
        r.metrics.zipWithIndex.flatMap((m, i) => finite(s"metrics[$i].value", m.value)) ++
        distinct("metrics", r.metrics.map(_.metric.wire))

  given Validator[Venue] with
    def validate(r: Venue): List[ValidationError] =
      common(r.schemaVersion, r.provenance) ++ id("venueId", IdKind.Venue, r.venueId) ++
        nonBlank("name", r.name)

  given Validator[Institution] with
    def validate(r: Institution): List[ValidationError] =
      common(r.schemaVersion, r.provenance) ++
        id("institutionId", IdKind.Institution, r.institutionId) ++
        nonBlank("name", r.name) ++
        r.countryCode.toList.flatMap(c =>
          if c.matches("^[A-Z]{2}$") then Nil
          else List(InvalidField("countryCode", "must be ISO 3166-1 alpha-2"))
        )

  given Validator[Topic] with
    def validate(r: Topic): List[ValidationError] =
      common(r.schemaVersion, r.provenance) ++ id("topicId", IdKind.Topic, r.topicId) ++
        nonBlank("name", r.name)

  given Validator[MatchDecision] with
    def validate(r: MatchDecision): List[ValidationError] =
      common(r.schemaVersion, r.provenance) ++
        id("mentionId", IdKind.Mention, r.mentionId) ++
        optId("candidateResearcherId", IdKind.Researcher, r.candidateResearcherId) ++
        (if r.outcome == MatchOutcome.Match && r.candidateResearcherId.isEmpty then
           List(InvalidField("candidateResearcherId", "required when outcome is match"))
         else Nil) ++
        confidence("confidence", r.confidence) ++
        nonBlank("policyVersion", r.policyVersion) ++
        formula(
          "decisionId",
          r.decisionId,
          StableIds.matchDecisionId(r.mentionId, r.candidateResearcherId, r.policyVersion)
        )

  given Validator[GraphNode] with
    def validate(r: GraphNode): List[ValidationError] =
      common(r.schemaVersion, r.provenance) ++
        id("nodeId", r.nodeType.idKind, r.nodeId) ++
        (if r.nodeType == GraphNodeType.Institution then resolvedInstitution("nodeId", r.nodeId)
         else Nil) ++
        nonBlank("label", r.label) ++
        uniqueNames("properties", r.properties) ++
        noAffiliationProperties("properties", r.properties)

  given Validator[GraphEdge] with
    def validate(r: GraphEdge): List[ValidationError] =
      val t         = r.edgeType
      val endpoints =
        id("fromId", t.fromKind, r.fromId) ++ id("toId", t.toKind, r.toId) ++
          (if r.fromId == r.toId then List(SelfPair("fromId", r.fromId))
           else if t.undirected && r.fromId.compareTo(r.toId) > 0 then
             List(InvalidPairOrdering("fromId", r.fromId, r.toId))
           else Nil)
      val affiliation =
        if t != GraphEdgeType.AffiliatedWith then Nil
        else
          resolvedInstitution("toId", r.toId) ++
            (if r.window.isEmpty then
               List(InvalidField("window", "an AFFILIATED_WITH edge must carry its years"))
             else Nil)
      common(r.schemaVersion, r.provenance) ++
        endpoints ++
        affiliation ++
        nonBlank("policyVersion", r.policyVersion) ++
        r.window.toList.flatMap(window("window", _)) ++
        r.weight.toList.flatMap(w =>
          finite("weight", w) ++
            (if w < 0 then List(InvalidField("weight", "must be >= 0")) else Nil)
        ) ++
        confidence("confidence", r.confidence) ++
        nonEmpty("evidenceIds", r.evidenceIds) ++
        ids("evidenceIds", IdKind.Evidence, r.evidenceIds) ++
        sortedDistinct("evidenceIds", r.evidenceIds) ++
        formula("edgeId", r.edgeId, StableIds.graphEdgeId(r))

  def evidenceItemErrors(prefix: String, r: EvidenceItem): List[ValidationError] =
    def p(f: String) = if prefix.isEmpty then f else s"$prefix.$f"
    schemaVersion(prefix, r.schemaVersion) ++
      provenance(prefix, r.provenance) ++
      (if IdFormat.isAnyKnown(r.subjectId) then Nil
       else List(MalformedId(p("subjectId"), "ScholarChain", r.subjectId))) ++
      id(p("contentHash"), IdKind.ContentHash, r.contentHash) ++
      (if r.provenance == null then Nil
       else
         formula(
           p("evidenceId"),
           r.evidenceId,
           StableIds.evidenceId(
             r.evidenceType,
             r.subjectId,
             r.contentHash,
             r.provenance.sourceDataset,
             r.provenance.sourceRecordId
           )
         ))

  given Validator[EvidenceItem] with
    def validate(r: EvidenceItem): List[ValidationError] = evidenceItemErrors("", r)

  def evidenceBundleErrors(prefix: String, r: EvidenceBundle): List[ValidationError] =
    def p(f: String) = if prefix.isEmpty then f else s"$prefix.$f"
    val itemIds      = r.items.map(_.evidenceId)
    schemaVersion(prefix, r.schemaVersion) ++
      provenance(prefix, r.provenance) ++
      r.items.zipWithIndex.flatMap((it, i) => evidenceItemErrors(p(s"items[$i]"), it)) ++
      distinct(p("items"), itemIds) ++
      formula(p("bundleId"), r.bundleId, StableIds.evidenceBundleId(itemIds))

  given Validator[EvidenceBundle] with
    def validate(r: EvidenceBundle): List[ValidationError] = evidenceBundleErrors("", r)

  given Validator[QueryPlan] with
    def validate(r: QueryPlan): List[ValidationError] =
      common(r.schemaVersion, r.provenance) ++
        nonBlank("question", r.question) ++
        ids("researcherIds", IdKind.Researcher, r.researcherIds) ++
        distinct("researcherIds", r.researcherIds) ++
        r.window.toList.flatMap(window("window", _)) ++
        nonEmpty("steps", r.steps) ++
        r.steps.zipWithIndex.flatMap((s, i) =>
          uniqueNames(s"steps[$i].parameters", s.parameters)
        ) ++
        formula(
          "planId",
          r.planId,
          StableIds.queryPlanId(r.question, r.researcherIds, r.window, r.steps)
        )

  def answerClaimErrors(prefix: String, r: AnswerClaim): List[ValidationError] =
    def p(f: String) = if prefix.isEmpty then f else s"$prefix.$f"
    schemaVersion(prefix, r.schemaVersion) ++
      provenance(prefix, r.provenance) ++
      nonBlank(p("text"), r.text) ++
      ids(p("evidenceIds"), IdKind.Evidence, r.evidenceIds) ++
      distinct(p("evidenceIds"), r.evidenceIds) ++
      (if r.evidenceIds.isEmpty && r.verificationStatus != VerificationStatus.Unsupported then
         List(InvalidField(p("verificationStatus"), "a claim without evidence must be unsupported"))
       else Nil) ++
      confidence(p("confidence"), r.confidence) ++
      formula(p("claimId"), r.claimId, StableIds.claimId(r.text, r.evidenceIds))

  given Validator[AnswerClaim] with
    def validate(r: AnswerClaim): List[ValidationError] = answerClaimErrors("", r)

  /**
   * Overall-status rules for an answer (CLAUDE.md law 3). Every overall status other than
   * `unsupported` and `not-verified` asserts a verification outcome, so it needs at least one claim
   * that witnesses it; a `forall` over an empty claim list must never grant one.
   *   - no claims: only `unsupported` or `not-verified`.
   *   - `supported`: non-empty claims, every claim `supported`.
   *   - `partially-supported`: at least one claim `supported` or `partially-supported`.
   *   - `contradicted`: at least one claim `contradicted`.
   * A witnessing claim has evidence, because a claim without evidence must be `unsupported`.
   */
  def overallStatusErrors(
      overall: VerificationStatus,
      claimStatuses: List[VerificationStatus]
  ): List[ValidationError] =
    import VerificationStatus.*
    def err(reason: String) = List(InvalidField("overallStatus", reason))
    overall match
      case Unsupported | NotVerified  => Nil
      case _ if claimStatuses.isEmpty =>
        err(s"an answer with no claims must be unsupported or not-verified, not ${overall.wire}")
      case Supported if !claimStatuses.forall(_ == Supported) =>
        err("supported requires every claim to be supported")
      case PartiallySupported
          if !claimStatuses.exists(s => s == Supported || s == PartiallySupported) =>
        err("partially-supported requires a supported or partially-supported claim")
      case Contradicted if !claimStatuses.contains(Contradicted) =>
        err("contradicted requires a contradicted claim")
      case _ => Nil

  given Validator[AnswerResponse] with
    def validate(r: AnswerResponse): List[ValidationError] =
      val available = r.evidence.items.map(_.evidenceId).toSet
      val citations = r.claims.zipWithIndex.flatMap((c, i) =>
        c.evidenceIds.filterNot(available).map(e =>
          InvalidField(s"claims[$i].evidenceIds", s"'$e' is not in the evidence bundle")
        )
      )
      val overall = overallStatusErrors(r.overallStatus, r.claims.map(_.verificationStatus))
      common(r.schemaVersion, r.provenance) ++
        id("planId", IdKind.QueryPlan, r.planId) ++
        nonBlank("answerText", r.answerText) ++
        r.claims.zipWithIndex.flatMap((c, i) => answerClaimErrors(s"claims[$i]", c)) ++
        evidenceBundleErrors("evidence", r.evidence) ++
        citations ++
        overall ++
        formula(
          "responseId",
          r.responseId,
          StableIds.answerId(r.planId, r.evidence.bundleId, r.claims.map(_.claimId))
        )

  given Validator[RunManifest] with
    def validate(r: RunManifest): List[ValidationError] =
      schemaVersion("", r.schemaVersion) ++
        nonBlank("pipelineName", r.pipelineName) ++
        nonBlank("codeVersion", r.codeVersion) ++
        id("configFingerprint", IdKind.ContentHash, r.configFingerprint) ++
        nonEmpty("inputs", r.inputs) ++
        r.inputs.zipWithIndex.flatMap((in, i) =>
          nonBlank(s"inputs[$i].snapshotId", in.snapshotId) ++
            nonBlank(s"inputs[$i].checksum", in.checksum)
        ) ++
        (if r.attempt < 1 then List(InvalidField("attempt", "must be >= 1")) else Nil) ++
        formula("runId", r.runId, StableIds.runId(r))
    override def requiresProvenance: Boolean = false
