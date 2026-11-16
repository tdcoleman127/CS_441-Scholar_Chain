package edu.uic.cs441.scholarchain.tests.support

import edu.uic.cs441.scholarchain.core.ids.StableIds
import edu.uic.cs441.scholarchain.core.json.Wire
import edu.uic.cs441.scholarchain.core.model.*
import edu.uic.cs441.scholarchain.rag.embedding.{Embedder, HashedBagOfTokensEmbedder}
import edu.uic.cs441.scholarchain.rag.runtime.*
import edu.uic.cs441.scholarchain.rag.semantic.{SemanticContext, SemanticRetriever}
import edu.uic.cs441.scholarchain.services.LocalFixture
import edu.uic.cs441.scholarchain.storage.{RetryPolicy, Sleeper}
import edu.uic.cs441.scholarchain.storage.fakes.*
import edu.uic.cs441.scholarchain.storage.kv.RunLedgerTable
import edu.uic.cs441.scholarchain.storage.vectors.*
import java.nio.charset.StandardCharsets
import upickle.default.write

/** RAG-side harness: the LocalFixture release, pipelines over it, and a semantic index. */
object RagKit:
  val window: YearWindow = LocalFixture.Window
  val runId: String      = LocalFixture.RunId
  val noSleep: Sleeper   = (_: Long) => ()

  final case class Pipe(pipeline: GraphRagPipeline, graph: InMemoryGraphStore, facts: GraphFacts)

  def pipe(
      synthesizer: AnswerSynthesizer = TemplateSynthesizer,
      semantic: Option[SemanticStage] = None,
      facts: GraphFacts = LocalFixture.build().dependencies.facts
  ): Pipe =
    val graph = InMemoryGraphStore(LocalFixture.edges, "graph/coauthor-edges/v1")
    val r = GraphRetriever(graph, facts, RetrievalConfig(facts.releaseRunId, LocalFixture.Policy))
    Pipe(
      GraphRagPipeline(
        QueryPlanner(PlannerConfig(window)),
        facts,
        r,
        semantic,
        synthesizer,
        () => 0L
      ),
      graph,
      facts
    )

  /** A synthesizer that appends `extra(in)` to the template synthesizer's claims. */
  final class Appending(extra: SynthesisInput => List[DraftClaim]) extends AnswerSynthesizer:
    val version: String                                             = "phase11-rogue-synthesizer-v1"
    def synthesize(in: SynthesisInput): Either[String, DraftAnswer] =
      TemplateSynthesizer.synthesize(in).map(d => d.copy(claims = d.claims ++ extra(in)))

  /** A synthesizer that returns exactly `claims(in)` (or the given error). */
  final class Fixed(claims: SynthesisInput => Either[String, List[DraftClaim]])
      extends AnswerSynthesizer:
    val version: String                                             = "phase11-fixed-synthesizer-v1"
    def synthesize(in: SynthesisInput): Either[String, DraftAnswer] =
      claims(in).map(DraftAnswer(RagVersions.AnswerClaimSchema, _))

  /** Models a model client: whatever text the "model" returns goes through the strict decoder. */
  final class Decoding(modelOutput: String) extends AnswerSynthesizer:
    val version: String = "phase11-decoding-synthesizer-v1"
    def synthesize(in: SynthesisInput): Either[String, DraftAnswer] =
      DraftAnswer.decode(modelOutput)

  def edgeEvidence(in: SynthesisInput, a: String, b: String): String =
    in.bundle.graph.collectFirst {
      case GraphEvidence(e: GraphFact.Edge, item) if Set(e.low, e.high) == Set(a, b) =>
        item.evidenceId
    }.getOrElse(sys.error(s"no edge evidence for $a-$b"))

  def answered(o: RagOutcome): RagOutcome.Answered = o match
    case a: RagOutcome.Answered => a
    case other                  => throw AssertionError(s"expected an answer, got $other")

  // ---- Semantic index over in-memory fakes ---------------------------------------------------

  final case class SemanticIndex(
      vectors: InMemoryVectorStore,
      objects: InMemoryObjectStore,
      ledgerStore: InMemoryKeyValueStore,
      embedder: HashedBagOfTokensEmbedder
  ):
    val ledger: RunLedgerTable = RunLedgerTable(ledgerStore, RetryPolicy.NoRetry)
    def ingestion(policy: RetryPolicy = RetryPolicy(3, 0, 0)): EmbeddingIngestion =
      EmbeddingIngestion(vectors, objects, ledger, policy = policy, sleeper = noSleep)
    def retriever: SemanticRetriever = SemanticRetriever(vectors, objects, embedder)
    def stage(indexes: List[VectorIndex] = List(VectorIndex.ResearcherProfile), k: Int = 5) =
      SemanticStage(retriever, indexes, k)

  def semanticIndex(embedder: HashedBagOfTokensEmbedder = HashedBagOfTokensEmbedder()) =
    SemanticIndex(InMemoryVectorStore(), InMemoryObjectStore(), InMemoryKeyValueStore(), embedder)

  def summary(
      entityId: String,
      text: String,
      docType: DocumentType = DocumentType.ResearcherProfile,
      sourceVersion: String = "phase11-template-v1/2015-2020",
      w: Option[YearWindow] = Some(window),
      record: String = "phase11/record"
  ): SemanticSummary =
    SemanticSummary(
      entityId,
      docType,
      w,
      sourceVersion,
      text,
      StableIds.textHash(text),
      Provenance(SourceType.ScholarChainPipeline, record, runId)
    )

  def supplier(e: Embedder): EmbeddingSupplier = Embedder.toSupplier(e)

  /** Render-structure invariant of the semantic-context prompt block. */
  def renderProblems(ctx: SemanticContext): List[String] =
    val lines = ctx.render.split("\n", -1).toList
    val body  = lines.drop(1).dropRight(1)
    List(
      Option.when(lines.headOption != Some(SemanticContext.Begin))("first line is not Begin"),
      Option.when(lines.lastOption != Some(SemanticContext.End))("last line is not End"),
      Option.when(lines.size != 2 * ctx.items.size + 2)(
        s"${lines.size} lines for ${ctx.items.size} items"
      ),
      body.zipWithIndex.collectFirst {
        case (l, i) if i % 2 == 0 && !l.startsWith(s"item ${i / 2 + 1}: key=") =>
          s"line ${i + 1} is not an item header: $l"
        case (l, i) if i % 2 == 1 && !l.startsWith("excerpt: \"") =>
          s"line ${i + 1} is not a quoted excerpt: $l"
      },
      body.find(l => l == SemanticContext.End || l.startsWith("BEGIN SEMANTIC CONTEXT")).map(l =>
        s"a body line imitates a delimiter: $l"
      )
    ).flatten

  // ---- Release exports through the real reader -----------------------------------------------

  /** Writes LocalFixture's exports (optionally edited) and loads them through GraphExportReader. */
  def loadRelease(
      researchers: List[CanonicalResearcher] = LocalFixture.researchers,
      metrics: List[ResearcherMetrics] = LocalFixture.metrics,
      graphMetrics: Option[List[GraphMetricsRow]] = Some(LocalFixture.graphMetrics),
      communities: Option[List[CommunityRow]] = Some(LocalFixture.communities)
  ): Either[String, GraphFacts] =
    val objects                                          = InMemoryObjectStore()
    def put(artifact: String, lines: List[String]): Unit =
      val key = GraphExportReader.key(artifact, runId).toOption.get
      objects.putIfAbsent(key, lines.mkString("\n").getBytes(StandardCharsets.UTF_8), "x")
      ()
    put(GraphExportReader.Researchers, researchers.map(Wire.encode(_)))
    put(GraphExportReader.Metrics, metrics.map(Wire.encode(_)))
    graphMetrics.foreach(g => put(GraphExportReader.GraphMetrics, g.map(write(_))))
    communities.foreach(c => put(GraphExportReader.Communities, c.map(write(_))))
    GraphExportReader.load(objects, runId)
