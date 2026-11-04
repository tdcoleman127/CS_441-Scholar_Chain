package edu.uic.cs441.scholarchain.rag.runtime

import edu.uic.cs441.scholarchain.rag.semantic.SemanticContext

/**
 * Versions of every runtime rule set and rag-local schema. Each answer and audit record carries the
 * full map ([[RagVersions.all]]), so a stored answer can be tied to the exact rules that produced
 * it. Changing a rule's behavior or a schema's shape means bumping its version string here.
 */
object RagVersions:
  val PlannerRules: String      = "query-planner-rules-v1"
  val ResolverRules: String     = "researcher-resolver-rules-v1"
  val EvidenceBundle: String    = "rag-evidence-bundle-v1"
  val AnswerClaimSchema: String = "rag-answer-claim-schema-v1"
  val VerifierRules: String     = "claim-verifier-rules-v1"
  val AuditRecordSchema: String = "rag-audit-record-schema-v1"
  val TemplateSynthesis: String = "template-synthesizer-v1"
  val ExportReader: String      = "graph-export-reader-v1"
  val QueryPlanSchema: String   = "rag-query-plan-schema-v1"

  /** Prompt for a future model synthesizer (prompts/answer-synthesis/v1). Unused in this phase. */
  val FuturePrompt: String = "answer-synthesis-prompt-v1"

  def all(synthesizer: String, embeddingModel: Option[String]): Map[String, String] =
    Map(
      "plannerRules"      -> PlannerRules,
      "resolverRules"     -> ResolverRules,
      "queryPlanSchema"   -> QueryPlanSchema,
      "evidenceBundle"    -> EvidenceBundle,
      "answerClaimSchema" -> AnswerClaimSchema,
      "verifierRules"     -> VerifierRules,
      "auditRecordSchema" -> AuditRecordSchema,
      "synthesizer"       -> synthesizer,
      "exportReader"      -> ExportReader,
      "semanticFormat"    -> SemanticContext.FormatVersion,
      "embeddingModel"    -> embeddingModel.getOrElse("none")
    )
