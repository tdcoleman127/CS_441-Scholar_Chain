package edu.uic.cs441.scholarchain.rag.runtime

import edu.uic.cs441.scholarchain.core.SchemaVersion
import edu.uic.cs441.scholarchain.core.ids.{CanonicalHash, IdFormat, IdKind, StableIds}
import edu.uic.cs441.scholarchain.core.model.*
import edu.uic.cs441.scholarchain.core.validation.Validation
import edu.uic.cs441.scholarchain.core.validation.Validators.given
import edu.uic.cs441.scholarchain.rag.semantic.{MetadataFormat, SemanticContext, TextRules}

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
 * The bounded evidence bundle (rag-evidence-bundle-v2). Graph evidence and semantic context are
 * kept in separate fields. `core` is the core [[EvidenceBundle]] and passes core validation, so
 * storage can persist it. Semantic item IDs are listed in `semanticEvidenceIds` so the verifier can
 * refuse them as support for graph claims.
 *
 * `core` is a deterministic function of its evidence items, which is exactly what the core bundle
 * ID covers (D4): items are ordered graph items first, then semantic text-chunk items, each group
 * sorted by evidence ID, and the bundle provenance is `(scholarchain-runtime, <bundleId>, <release
 * run>)`. Query-specific fields (the plan ID, the question) are never in `core`; the audit record
 * links a query to its bundle (`plan` and `bundleId`).
 *
 * The guarantee is bounded. Two wordings of one question that retrieve the same evidence persist
 * byte-identical bundles, so the second write is an idempotent replay instead of a
 * conditional-write conflict, only while no bound cuts anything: at most
 * [[EvidenceBuilder.MaxGraphItems]] unique graph facts within [[EvidenceBuilder.MaxGraphTextChars]]
 * excerpt characters, and at most [[EvidenceBuilder.MaxSemanticItems]] semantic items.
 * [[EvidenceBuilder.build]] applies these bounds in retrieval order, before the canonical sort.
 * Beyond them, the same retrieved set in a different order can keep a different subset of items, so
 * the two bundles differ, get different bundle IDs, and are persisted as two separate bundles (no
 * conflict, but no deduplication either). Each cut graph fact is listed in `truncated`, which is
 * not part of `core`.
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
   * ID, their text hash is well formed, and their provenance passes core validation (M3: an item
   * with invalid provenance is left out, so it can never make the persisted bundle invalid) and the
   * [[MetadataFormat]] token rule (RR5, rag-evidence-bundle-v3: a source record or run ID holding
   * control, whitespace, or non-ASCII characters never reaches the persisted bundle).
   *
   * No query-specific input is taken: the persisted core bundle depends only on the evidence items
   * and the release run (D4).
   */
  def build(
      facts: Seq[GraphFact],
      semantic: SemanticContext,
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
      if s != null && s.vectorKey != null && s.textHash != null && s.excerpt != null &&
        IdFormat.isValid(IdKind.Chunk, s.vectorKey) &&
        IdFormat.isValid(IdKind.ContentHash, s.textHash) &&
        Validation.validate(s.provenance).isRight &&
        MetadataFormat.provenanceOk(s.provenance)
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
    // Canonical order: graph group, then semantic group, each sorted by evidence ID. Retrieval
    // order (which can depend on the question's wording) never reaches the persisted payload's
    // ordering. It can still decide which items survive the bounds above, because the item, text,
    // and semantic cuts run in retrieval order before this sort.
    val items = kept.map(_.item).toList.sortBy(_.evidenceId) ++ semanticItems.sortBy(_.evidenceId)
    val bundleId = StableIds.evidenceBundleId(items.map(_.evidenceId))
    val core     = EvidenceBundle(
      SchemaVersion.Current,
      bundleId,
      items,
      Provenance(SourceType.ScholarChainRuntime, bundleId, releaseRunId)
    )
    RagEvidenceBundle(
      RagVersions.EvidenceBundle,
      kept.toList,
      semantic,
      semanticItems.map(_.evidenceId),
      core,
      cut.toList
    )
