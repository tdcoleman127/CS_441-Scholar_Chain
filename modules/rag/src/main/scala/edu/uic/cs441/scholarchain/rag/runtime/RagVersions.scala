package edu.uic.cs441.scholarchain.rag.runtime

import edu.uic.cs441.scholarchain.rag.semantic.{MetadataFormat, SemanticContext}

/**
 * Versions of every runtime rule set and rag-local schema. Each answer and audit record carries the
 * full map ([[RagVersions.all]]), so a stored answer can be tied to the exact rules that produced
 * it. Changing a rule's behavior or a schema's shape means bumping its version string here.
 */
object RagVersions:
  val PlannerRules: String  = "query-planner-rules-v1"
  val ResolverRules: String = "researcher-resolver-rules-v1"
  // v2 (Phase 11 repair-01, D4): canonical item order and query-independent provenance.
  // v3 (Phase 11 repair-02, RR5): semantic items also need semantic-metadata-format-v1 provenance.
  val EvidenceBundle: String    = "rag-evidence-bundle-v3"
  val AnswerClaimSchema: String = "rag-answer-claim-schema-v1"
  val VerifierRules: String     = "claim-verifier-rules-v1"
  val AuditRecordSchema: String = "rag-audit-record-schema-v1"
  val TemplateSynthesis: String = "template-synthesizer-v1"
  // v2 (Phase 11 repair-01, M4): total decoding (nulls are Left) and run-ID checks on every row.
  val ExportReader: String    = "graph-export-reader-v2"
  val QueryPlanSchema: String = "rag-query-plan-schema-v1"

  /**
   * Answer assembly (Phase 11 repair-01, R-09-11): a claim whose verdict rests on graph facts
   * (VERIFIED, PARTIALLY_SUPPORTED, CONFLICTING) lists only graph evidence IDs; semantic context is
   * never presented as support. An UNSUPPORTED claim keeps every in-bundle ID it cited, for
   * inspection. Every synthesizer's draft passes [[DraftAnswer.validate]] first (D6).
   */
  val AnswerAssembly: String = "answer-assembly-rules-v1"

  /** Allow-list for string vector metadata and semantic provenance (RR5). */
  val SemanticMetadata: String = MetadataFormat.Version

  /**
   * Pipeline guards on pluggable components (Phase 11 repair-02, RR6): a synthesizer, graph store,
   * semantic stage, bundle table, or clock that throws or returns null (a null Either, a null Left
   * message, a null result) gives a typed outcome: synthesis failures and graph retrieval failures
   * abstain, a semantic failure is a dropped reason, a bundle store failure is recorded in the
   * audit. The pipeline never throws for them.
   */
  val ComponentGuards: String = "pipeline-component-guards-v1"

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
      "answerAssembly"    -> AnswerAssembly,
      "auditRecordSchema" -> AuditRecordSchema,
      "synthesizer"       -> synthesizer,
      "exportReader"      -> ExportReader,
      "semanticFormat"    -> SemanticContext.FormatVersion,
      "semanticMetadata"  -> SemanticMetadata,
      "componentGuards"   -> ComponentGuards,
      "embeddingModel"    -> embeddingModel.getOrElse("none")
    )
