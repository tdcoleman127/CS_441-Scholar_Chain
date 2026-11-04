package edu.uic.cs441.scholarchain.rag.runtime

import edu.uic.cs441.scholarchain.core.SchemaVersion
import edu.uic.cs441.scholarchain.core.ids.{CanonicalHash, IdFormat, IdKind, StableIds}
import edu.uic.cs441.scholarchain.core.model.*
import edu.uic.cs441.scholarchain.rag.semantic.{SemanticContext, TextRules}

/**
 * One typed graph fact retrieved from a store or export. Every fact carries its window, its policy
 * or algorithm version, and provenance whose `pipelineRunId` is the data release's run ID. Facts
 * are the only things the verifier accepts as support for a claim; semantic context never is.
 */
enum GraphFact:
  /**
   * A COAUTHORED_WITH edge returned by the bounded graph template. `window` is the retrieval
   * window: the template guarantees the edge's own window lies inside it.
   */
  case Edge(
      edgeId: String,
      low: String,
      high: String,
      window: YearWindow,
      policyVersion: String,
      coauthoredWorkCount: Int,
      firstYear: Int,
      lastYear: Int,
      evidencePointer: String,
      provenance: Provenance
  )
  case Metric(
      researcherId: String,
      metric: CollaborationMetric,
      value: Double,
      window: YearWindow,
      policyVersion: String,
      provenance: Provenance
  )
  case Community(
      researcherId: String,
      communityId: String,
      communitySize: Long,
      algorithmVersion: String,
      window: YearWindow,
      provenance: Provenance
  )
  case Bridge(
      researcherId: String,
      crossCommunityDegree: Long,
      distinctCommunitiesTouched: Long,
      algorithmVersion: String,
      window: YearWindow,
      provenance: Provenance
  )
  case Component(
      researcherId: String,
      componentId: String,
      componentSize: Long,
      window: YearWindow,
      provenance: Provenance
  )
  case Identity(researcherId: String, displayName: String, provenance: Provenance)

  def provenance: Provenance

  def evidenceType: EvidenceType = this match
    case _: Edge      => EvidenceType.GraphEdge
    case _: Metric    => EvidenceType.Metric
    case _: Community => EvidenceType.GraphNode
    case _: Bridge    => EvidenceType.Metric
    case _: Component => EvidenceType.Metric
    case _: Identity  => EvidenceType.GraphNode

  def subjectId: String = this match
    case e: Edge      => e.edgeId
    case m: Metric    => m.researcherId
    case c: Community => c.communityId
    case b: Bridge    => b.researcherId
    case c: Component => c.researcherId
    case i: Identity  => i.researcherId

  private def w(x: YearWindow) = s"${x.startYear}-${x.endYear}"

  /** Deterministic canonical text: the evidence excerpt and the input to the content hash. */
  def canonical: String = this match
    case e: Edge =>
      s"edge COAUTHORED_WITH id=${e.edgeId} low=${e.low} high=${e.high} window=${w(e.window)} " +
        s"policy=${e.policyVersion} coauthoredWorkCount=${e.coauthoredWorkCount} " +
        s"firstYear=${e.firstYear} lastYear=${e.lastYear} run=${e.provenance.pipelineRunId}"
    case m: Metric =>
      s"metric ${m.metric.wire}=${TextRules.num(m.value)} researcher=${m.researcherId} " +
        s"window=${w(m.window)} policy=${m.policyVersion} run=${m.provenance.pipelineRunId}"
    case c: Community =>
      s"community member researcher=${c.researcherId} community=${c.communityId} " +
        s"size=${c.communitySize} algorithm=${c.algorithmVersion} window=${w(c.window)} " +
        s"run=${c.provenance.pipelineRunId}"
    case b: Bridge =>
      s"bridge researcher=${b.researcherId} crossCommunityDegree=${b.crossCommunityDegree} " +
        s"distinctCommunitiesTouched=${b.distinctCommunitiesTouched} " +
        s"algorithm=${b.algorithmVersion} window=${w(b.window)} run=${b.provenance.pipelineRunId}"
    case c: Component =>
      s"component researcher=${c.researcherId} component=${TextRules.quote(c.componentId, 120)} " +
        s"size=${c.componentSize} window=${w(c.window)} run=${c.provenance.pipelineRunId}"
    case i: Identity =>
      s"identity researcher=${i.researcherId} displayName=${TextRules.quote(i.displayName, 120)} " +
        s"run=${i.provenance.pipelineRunId}"

  def contentHash: String = CanonicalHash.contentHash(canonical)

  def evidenceItem: EvidenceItem =
    EvidenceItem(
      SchemaVersion.Current,
      StableIds.evidenceId(
        evidenceType,
        subjectId,
        contentHash,
        provenance.sourceDataset,
        provenance.sourceRecordId
      ),
      evidenceType,
      subjectId,
      contentHash,
      Some(TextRules.clean(canonical, EvidenceBuilder.MaxExcerptChars)),
      provenance
    )

  def evidenceId: String = evidenceItem.evidenceId

/** A graph fact with its core evidence item. */
final case class GraphEvidence(fact: GraphFact, item: EvidenceItem):
  def evidenceId: String = item.evidenceId

/**
 * The bounded evidence bundle (rag-evidence-bundle-v1). Graph evidence and semantic context are
 * kept in separate fields. `core` is the core [[EvidenceBundle]] (graph items first, then semantic
 * text-chunk items) and passes core validation, so storage can persist it. Semantic item IDs are
 * listed in `semanticEvidenceIds` so the verifier can refuse them as support for graph claims.
 */
final case class RagEvidenceBundle(
    version: String,
    graph: List[GraphEvidence],
    semantic: SemanticContext,
    semanticEvidenceIds: List[String],
    core: EvidenceBundle,
    truncated: List[String]
):
  def graphById: Map[String, GraphFact] = graph.map(g => g.evidenceId -> g.fact).toMap
  def facts: List[GraphFact]            = graph.map(_.fact)

object EvidenceBuilder:
  val MaxGraphItems: Int     = 60
  val MaxExcerptChars: Int   = 600
  val MaxGraphTextChars: Int = 24000
  val MaxSemanticItems: Int  = SemanticContext.MaxItems

  /**
   * Builds the bundle: graph facts deduplicated by evidence ID in retrieval order, cut at
   * [[MaxGraphItems]] items and [[MaxGraphTextChars]] excerpt characters (each cut is recorded in
   * `truncated`); semantic items become text-chunk evidence only when their key is a valid chunk
   * ID.
   */
  def build(
      facts: Seq[GraphFact],
      semantic: SemanticContext,
      planId: String,
      releaseRunId: String
  ): RagEvidenceBundle =
    val unique         = facts.map(f => GraphEvidence(f, f.evidenceItem)).distinctBy(_.evidenceId)
    val (kept, _, cut) =
      unique.foldLeft((Vector.empty[GraphEvidence], 0, Vector.empty[String])) {
        case ((acc, used, cut), g) =>
          val cost = g.item.excerpt.fold(0)(_.length)
          if acc.size >= MaxGraphItems then (acc, used, cut :+ s"${g.evidenceId}: over item bound")
          else if used + cost > MaxGraphTextChars then
            (acc, used, cut :+ s"${g.evidenceId}: over text bound")
          else (acc :+ g, used + cost, cut)
      }
    val semanticItems = semantic.items.take(MaxSemanticItems).flatMap { s =>
      if IdFormat.isValid(IdKind.Chunk, s.vectorKey) &&
        IdFormat.isValid(IdKind.ContentHash, s.textHash)
      then
        Some(
          EvidenceItem(
            SchemaVersion.Current,
            StableIds.evidenceId(
              EvidenceType.TextChunk,
              s.vectorKey,
              s.textHash,
              s.provenance.sourceDataset,
              s.provenance.sourceRecordId
            ),
            EvidenceType.TextChunk,
            s.vectorKey,
            s.textHash,
            Some(TextRules.clean(s.excerpt, MaxExcerptChars)),
            s.provenance
          )
        )
      else None
    }.distinctBy(_.evidenceId).filterNot(i => kept.exists(_.evidenceId == i.evidenceId))
    val items = kept.map(_.item).toList ++ semanticItems
    val core  = EvidenceBundle(
      SchemaVersion.Current,
      StableIds.evidenceBundleId(items.map(_.evidenceId)),
      items,
      Provenance(SourceType.ScholarChainRuntime, planId, releaseRunId)
    )
    RagEvidenceBundle(
      RagVersions.EvidenceBundle,
      kept.toList,
      semantic,
      semanticItems.map(_.evidenceId),
      core,
      cut.toList
    )
