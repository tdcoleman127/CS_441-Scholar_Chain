package edu.uic.cs441.scholarchain.rag.runtime

import edu.uic.cs441.scholarchain.core.SchemaVersion
import edu.uic.cs441.scholarchain.core.ids.{CanonicalHash, StableIds}
import edu.uic.cs441.scholarchain.core.json.Wire
import edu.uic.cs441.scholarchain.core.model.*
import edu.uic.cs441.scholarchain.core.validation.Validation
import edu.uic.cs441.scholarchain.core.validation.Validators.given
import edu.uic.cs441.scholarchain.storage.kv.EvidenceBundleTable
import upickle.default.ReadWriter

/** Why the runtime did not answer. Abstaining is a correct outcome (never a guess). */
enum Abstention(val wire: String):
  case UnknownResearcher(mention: String) extends Abstention("unknown-researcher")
  case AmbiguousResearcher(mention: String, candidates: List[Candidate], totalMatches: Int)
      extends Abstention("ambiguous-researcher")
  case Gap(gap: RetrievalGap)          extends Abstention("insufficient-evidence")
  case NoSupportedClaims               extends Abstention("no-supported-claims")
  case RetrievalFailed(detail: String) extends Abstention("retrieval-failed")
  case SynthesisFailed(detail: String) extends Abstention("synthesis-failed")

final case class ClaimVerdictRecord(claimId: String, verdict: String, reasons: List[String])
    derives ReadWriter

/**
 * Audit record (rag-audit-record-schema-v1): the plan, resolved IDs, evidence IDs, versions,
 * latency, verification result, and answer hash. The raw question is kept only inside the core plan
 * record; it is data, never instructions.
 */
final case class AuditRecord(
    schema: String,
    outcome: String,
    reason: Option[String],
    plan: Option[QueryPlan],
    operation: Option[String],
    plannerRule: Option[String],
    window: Option[YearWindow],
    resolvedIds: List[String],
    candidateIds: List[String],
    graphEvidenceIds: List[String],
    semanticEvidenceIds: List[String],
    bundleId: Option[String],
    bundleStore: Option[String],
    truncated: List[String],
    claimVerdicts: List[ClaimVerdictRecord],
    overallStatus: Option[String],
    answerHash: Option[String],
    versions: Map[String, String],
    latencyMillis: Long
) derives ReadWriter

enum RagOutcome:
  case Answered(
      response: AnswerResponse,
      bundle: RagEvidenceBundle,
      checks: List[ClaimCheck],
      audit: AuditRecord
  )
  case Abstained(reason: Abstention, audit: AuditRecord)
  case Refused(reason: RefusalReason, audit: AuditRecord)

  def audit: AuditRecord

/**
 * The runtime workflow: plan, resolve, retrieve (graph, then semantic), build the bounded bundle,
 * synthesize typed claims, verify each one deterministically, and assemble a core-valid
 * [[AnswerResponse]]. Unsupported claims stay in the claim list marked `unsupported` (with only
 * in-bundle evidence IDs) and are left out of the answer text. If no claim is verified or partially
 * supported, the runtime abstains.
 */
final class GraphRagPipeline(
    planner: QueryPlanner,
    facts: GraphFacts,
    retriever: GraphRetriever,
    semantic: Option[SemanticStage],
    synthesizer: AnswerSynthesizer,
    clockMillis: () => Long,
    embeddingModel: Option[String] = None,
    bundleTable: Option[EvidenceBundleTable] = None
):
  import PlanOperation.*

  private val resolver = ResearcherResolver(facts.researchers)
  private val versions = RagVersions.all(synthesizer.version, embeddingModel)

  private def emptyAudit = AuditRecord(
    RagVersions.AuditRecordSchema,
    "",
    None,
    None,
    None,
    None,
    None,
    Nil,
    Nil,
    Nil,
    Nil,
    None,
    None,
    Nil,
    Nil,
    None,
    None,
    versions,
    0L
  )

  /** The core plan record: resolve steps, the operation step, then fetch-evidence. */
  def corePlan(parsed: ParsedPlan, op: PlanOperation[String]): QueryPlan =
    val ids     = op.subjects.distinct
    val resolve =
      ids.map(id => QueryStep(QueryOperation.ResolveResearcher, List(Property("researcherId", id))))
    val main = QueryStep(
      op.coreOperation,
      Property("ragOperation", op.wire) :: Property("plannerRule", parsed.rule) :: op.parameters
    )
    val steps = resolve :+ main :+ QueryStep(QueryOperation.FetchEvidence, Nil)
    val prov  =
      Provenance(SourceType.ScholarChainRuntime, RagVersions.PlannerRules, facts.releaseRunId)
    QueryPlan(
      SchemaVersion.Current,
      StableIds.queryPlanId(parsed.question, ids, Some(parsed.window), steps),
      parsed.question,
      ids,
      Some(parsed.window),
      steps,
      prov
    )

  def answer(question: String): RagOutcome =
    val start                = clockMillis()
    def done(a: AuditRecord) = a.copy(latencyMillis = (clockMillis() - start).max(0L))
    val base                 = emptyAudit
    planner.plan(question) match
      case PlannerResult.Refused(r) =>
        RagOutcome.Refused(r, done(base.copy(outcome = "refused", reason = Some(r.wire))))
      case PlannerResult.Planned(parsed) =>
        val withPlan = base.copy(
          operation = Some(parsed.operation.wire),
          plannerRule = Some(parsed.rule),
          window = Some(parsed.window)
        )
        val resolutions = parsed.operation.subjects.map(m => m -> resolver.resolve(m))
        val candidates  = resolutions.flatMap {
          case (_, Resolution.Ambiguous(_, cs, _)) => cs.map(_.researcherId)
          case (_, Resolution.Resolved(_, c))      => List(c.researcherId)
          case _                                   => Nil
        }
        val failed = resolutions.collectFirst {
          case (_, Resolution.Unknown(m))          => Abstention.UnknownResearcher(m)
          case (_, Resolution.Ambiguous(m, cs, n)) => Abstention.AmbiguousResearcher(m, cs, n)
        }
        failed match
          case Some(abs) =>
            RagOutcome.Abstained(
              abs,
              done(withPlan.copy(
                outcome = "abstained",
                reason = Some(abs.wire),
                candidateIds = candidates
              ))
            )
          case None =>
            val idOf = resolutions.collect { case (m, Resolution.Resolved(_, c)) =>
              m -> c.researcherId
            }.toMap
            val op = parsed.operation.map(idOf)
            if op.subjects.distinct.size != op.subjects.size then
              RagOutcome.Refused(
                RefusalReason.SameResearcher,
                done(withPlan.copy(
                  outcome = "refused",
                  reason = Some(RefusalReason.SameResearcher.wire)
                ))
              )
            else run(parsed, op, withPlan.copy(resolvedIds = op.subjects), done)

  private def retrieve(op: PlanOperation[String], w: YearWindow): Either[String, Retrieved] =
    op match
      case DirectCoauthors(r, l)       => retriever.coauthors(r, w, l)
      case CollaborationInWindow(a, b) => retriever.collaborationInWindow(a, b, w)
      case ShortestPath(a, b, d, l)    => retriever.shortestPath(a, b, w, d, l)
      case MetricLookup(r, m)          => Right(retriever.metricLookup(r, m, w))
      case MetricRanking(m, l)         => Right(retriever.ranking(m, w, l))
      case CommunityOf(r)              => Right(retriever.community(r, w))
      case ComponentOf(r)              => Right(retriever.component(r, w))
      case Bridges(l)                  => Right(retriever.bridges(w, l))

  private def run(
      parsed: ParsedPlan,
      op: PlanOperation[String],
      audit0: AuditRecord,
      done: AuditRecord => AuditRecord
  ): RagOutcome =
    val plan  = corePlan(parsed, op)
    val audit = audit0.copy(plan = Some(plan))
    retrieve(op, parsed.window) match
      case Left(err) =>
        val abs = Abstention.RetrievalFailed(err)
        RagOutcome.Abstained(abs, done(audit.copy(outcome = "abstained", reason = Some(abs.wire))))
      case Right(got) =>
        val ctx    = semantic.fold(SemanticStage.empty)(_.search(parsed.question))
        val bundle = EvidenceBuilder.build(got.facts, ctx, plan.planId, facts.releaseRunId)
        val stored =
          bundleTable.map(_.put(bundle.core).fold(e => s"failed: ${e.message}", _.toString))
        val withBundle = audit.copy(
          graphEvidenceIds = bundle.graph.map(_.evidenceId),
          semanticEvidenceIds = bundle.semanticEvidenceIds,
          bundleId = Some(bundle.core.bundleId),
          bundleStore = stored,
          truncated = bundle.truncated
        )
        got.gap match
          case Some(gap) =>
            val abs = Abstention.Gap(gap)
            RagOutcome.Abstained(
              abs,
              done(withBundle.copy(
                outcome = "abstained",
                reason = Some(s"${abs.wire}:${gap.wire}")
              ))
            )
          case None =>
            val input = SynthesisInput(op, parsed.window, bundle, got.pathNodeIds)
            synthesizer.synthesize(input) match
              case Left(err) =>
                val abs = Abstention.SynthesisFailed(err)
                RagOutcome.Abstained(
                  abs,
                  done(withBundle.copy(outcome = "abstained", reason = Some(abs.wire)))
                )
              case Right(draft) => assemble(plan, parsed.window, bundle, draft, withBundle, done)

  private def assemble(
      plan: QueryPlan,
      window: YearWindow,
      bundle: RagEvidenceBundle,
      draft: DraftAnswer,
      audit: AuditRecord,
      done: AuditRecord => AuditRecord
  ): RagOutcome =
    val checks = draft.claims.map(ClaimVerifier.verify(_, bundle, window))
    val names  = bundle.facts.collect { case i: GraphFact.Identity =>
      i.researcherId -> i.displayName
    }.toMap
    val inBundle = bundle.core.items.map(_.evidenceId).toSet
    val claims   = checks.map { c =>
      val text   = ClaimText.render(c.claim.assertion, names)
      val ev     = c.claim.evidenceIds.filter(inBundle).distinct.sorted
      val status =
        if ev.isEmpty then VerificationStatus.Unsupported else c.verdict.core
      val confidence = c.verdict match
        case Verdict.Verified           => 1.0
        case Verdict.PartiallySupported => 0.5
        case _                          => 0.0
      AnswerClaim(
        SchemaVersion.Current,
        StableIds.claimId(text, ev),
        text,
        ev,
        status,
        confidence,
        Provenance(SourceType.ScholarChainRuntime, plan.planId, facts.releaseRunId)
      )
    }
    val verdicts =
      claims.zip(checks).map((ac, c) => ClaimVerdictRecord(ac.claimId, c.verdict.wire, c.reasons))
    val overall = ClaimVerifier.overall(checks)
    val shown   = claims.zip(checks).filter((_, c) =>
      c.verdict == Verdict.Verified || c.verdict == Verdict.PartiallySupported
    )
    if shown.isEmpty then
      RagOutcome.Abstained(
        Abstention.NoSupportedClaims,
        done(audit.copy(
          outcome = "abstained",
          reason = Some(Abstention.NoSupportedClaims.wire),
          claimVerdicts = verdicts,
          overallStatus = Some(overall.wire)
        ))
      )
    else
      val withheld = claims.size - shown.size
      val lines    = shown.map { (ac, c) =>
        if c.verdict == Verdict.Verified then ac.text else s"[partially supported] ${ac.text}"
      }
      val footer =
        if withheld == 0 then Nil
        else List(s"$withheld claim(s) failed verification and were withheld.")
      val text     = (lines ++ footer).mkString("\n")
      val response = AnswerResponse(
        SchemaVersion.Current,
        StableIds.answerId(plan.planId, bundle.core.bundleId, claims.map(_.claimId)),
        plan.planId,
        text,
        claims,
        bundle.core,
        overall,
        Provenance(SourceType.ScholarChainRuntime, plan.planId, facts.releaseRunId)
      )
      Validation.validate(response) match
        case Left(errs) =>
          val abs = Abstention.SynthesisFailed(errs.map(_.message).mkString("; "))
          RagOutcome.Abstained(
            abs,
            done(audit.copy(outcome = "abstained", reason = Some(abs.wire)))
          )
        case Right(valid) =>
          RagOutcome.Answered(
            valid,
            bundle,
            checks,
            done(audit.copy(
              outcome = "answered",
              claimVerdicts = verdicts,
              overallStatus = Some(overall.wire),
              answerHash = Some(CanonicalHash.contentHash(Wire.encode(valid)))
            ))
          )
