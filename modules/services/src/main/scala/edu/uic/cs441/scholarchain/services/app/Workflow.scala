package edu.uic.cs441.scholarchain.services.app

import edu.uic.cs441.scholarchain.core.model.YearWindow
import edu.uic.cs441.scholarchain.rag.runtime.*
import edu.uic.cs441.scholarchain.rag.semantic.SemanticRetriever
import edu.uic.cs441.scholarchain.services.api.WorkflowOptions
import edu.uic.cs441.scholarchain.storage.graph.GraphStore
import edu.uic.cs441.scholarchain.storage.kv.EvidenceBundleTable
import edu.uic.cs441.scholarchain.storage.vectors.VectorIndex
import scala.collection.mutable

/**
 * A workflow answer, whether a cached pipeline served it, and the vector indexes its semantic stage
 * searched for this query (empty when the stage was disabled or not configured).
 */
final case class WorkflowAnswer(
    outcome: RagOutcome,
    pipelineCacheHit: Boolean,
    semanticIndexes: List[VectorIndex]
)

/** Port to the runtime Graph-RAG workflow (rag). The services module implements no RAG logic. */
trait GraphRagWorkflow:
  def answer(question: String, options: WorkflowOptions): WorkflowAnswer

/** Optional semantic-context wiring: a retriever and the indexes it searches. */
final case class SemanticWiring(
    retriever: SemanticRetriever,
    indexes: List[VectorIndex],
    defaultTopK: Int
)

/**
 * The rag-backed workflow. It composes [[GraphRagPipeline]] from rag's planner, retriever,
 * synthesizer, and verifier over storage ports, and caches one pipeline per distinct option set
 * (window and limits) in a bounded LRU, because each pipeline indexes the researcher export.
 */
final class RagWorkflow(
    facts: GraphFacts,
    graph: GraphStore,
    retrieval: RetrievalConfig,
    defaultWindow: YearWindow,
    synthesizer: AnswerSynthesizer,
    semantic: Option[SemanticWiring],
    embeddingModel: Option[String],
    bundleTable: Option[EvidenceBundleTable],
    clockMillis: () => Long,
    maxCachedPipelines: Int = 8
) extends GraphRagWorkflow:

  private val retriever = GraphRetriever(graph, facts, retrieval)
  private val cache     =
    mutable.LinkedHashMap.empty[WorkflowOptions, (GraphRagPipeline, List[VectorIndex])]

  private def build(o: WorkflowOptions): (GraphRagPipeline, List[VectorIndex]) =
    val base   = PlannerConfig(o.window.getOrElse(defaultWindow))
    val config = base.copy(
      defaultCoauthorLimit = o.graphLimit.getOrElse(base.defaultCoauthorLimit),
      defaultPathDepth = o.maxPathDepth.getOrElse(base.defaultPathDepth)
    )
    val stage = semantic.flatMap { s =>
      val k = o.vectorLimit.getOrElse(s.defaultTopK)
      Option.when(k > 0)(SemanticStage(s.retriever, s.indexes, k))
    }
    val pipeline = GraphRagPipeline(
      QueryPlanner(config),
      facts,
      retriever,
      stage,
      synthesizer,
      clockMillis,
      embeddingModel,
      bundleTable
    )
    (pipeline, if stage.isDefined then semantic.fold(Nil)(_.indexes) else Nil)

  private def pipeline(o: WorkflowOptions): ((GraphRagPipeline, List[VectorIndex]), Boolean) =
    synchronized {
      cache.remove(o) match
        case Some(p) =>
          cache(o) = p
          (p, true)
        case None =>
          val p = build(o)
          cache(o) = p
          if cache.size > maxCachedPipelines then cache.remove(cache.head._1)
          (p, false)
    }

  def answer(question: String, options: WorkflowOptions): WorkflowAnswer =
    val ((p, indexes), hit) = pipeline(options)
    WorkflowAnswer(p.answer(question), hit, indexes)
