package edu.uic.cs441.scholarchain.core.ids

import edu.uic.cs441.scholarchain.core.SchemaVersion
import edu.uic.cs441.scholarchain.core.ids.CanonicalHash.{hashFields, list}
import edu.uic.cs441.scholarchain.core.model.*
import edu.uic.cs441.scholarchain.core.validation.ValidationError

/**
 * Stable, deterministic, timestamp-independent ID formulas (schema version 1).
 *
 * `H(f1, ..., fn)` below is [[CanonicalHash.hashFields]]: SHA-256 over the length-prefixed NFC
 * UTF-8 encoding of the fields, as 64 lowercase hex characters. `list(xs)` is the element count
 * followed by the elements. An absent optional is the empty string. Enum fields use their wire
 * value. Changing any formula below is a schema-version change.
 *
 * {{{
 * work         work:<ns>:<normalized source ID>   ns in doi|dblp|openalex|arxiv|s2|pwc|omid|pmid
 *              work:sc1:H("scholarchain:work:sc1", sourceDataset, sourceRecordId)   (fallback)
 * venue        venue:<ns>:<id> (dblp|openalex|issn) or venue:sc1:H("scholarchain:venue:sc1", ...)
 * institution  institution:<ns>:<id> (ror|openalex) or institution:sc1:H(...)
 * topic        topic:<ns>:<id> (openalex|pwc) or topic:sc1:H(...)
 * researcher   researcher:orcid:<ORCID>                 when the ORCID passes its checksum
 *              researcher:sc<N>:H("scholarchain:researcher:sc<N>", anchorDataset, anchorRecordId)
 * mention      mention:v1:H("scholarchain:mention:v1", workId, authorPosition,
 *                           sourceDataset, sourceRecordId)
 * coedge       coedge:v1:H("scholarchain:coauthor-edge:v1", low, high,
 *                          startYear, endYear, policyVersion)
 * chunk        chunk:v1:H("scholarchain:chunk:v1", entityId, sourceVersion, documentType,
 *                         ordinal or "", textHash)
 * gedge        gedge:v1:H("scholarchain:graph-edge:v1", edgeType, from, to, policyVersion,
 *                         startYear or "", endYear or "")   undirected endpoints canonical;
 *                         evidence is an attribute and does not determine identity
 * evidence     evidence:v1:H("scholarchain:evidence:v1", evidenceType, subjectId, contentHash,
 *                            sourceDataset, sourceRecordId)
 * match        match:v1:H("scholarchain:match-decision:v1", mentionId,
 *                         candidateResearcherId or "", policyVersion)
 * bundle       bundle:v1:H("scholarchain:evidence-bundle:v1", list(sorted distinct evidenceIds))
 * plan         plan:v1:H("scholarchain:query-plan:v1", trimmed question, list(researcherIds),
 *                        startYear or "", endYear or "", list(steps)) where each step is
 *                        operation, list(name1, value1, name2, value2, ...)
 * claim        claim:v1:H("scholarchain:claim:v1", trimmed text, list(sorted distinct evidenceIds))
 * answer       answer:v1:H("scholarchain:answer:v1", planId, bundleId, list(claimIds in order))
 * run          run:v1:H("scholarchain:run:v1", pipelineName, codeVersion, configFingerprint,
 *                       list(inputs sorted by (dataset, snapshotId, checksum), 3 fields each),
 *                       attempt)
 * }}}
 */
object StableIds:

  private def hashed(kind: IdKind, tag: String, fields: Seq[String]): String =
    s"${kind.prefix}:${IdFormat.HashedFormulaVersion}:${hashFields(tag +: fields)}"

  private def sortedDistinct(xs: Seq[String]): Seq[String] = xs.distinct.sorted

  // ---- Namespaced entity IDs ----------------------------------------------------------------

  /** `<kind>:<ns>:<normalized source ID>`; fails for a namespace the kind does not allow. */
  def namespacedId(
      kind: IdKind,
      namespace: IdNamespace,
      sourceId: String
  ): Either[ValidationError, String] =
    if !kind.isNamespaced || !kind.namespaces.contains(namespace) ||
      namespace == IdNamespace.ScholarChainV1
    then
      Left(
        ValidationError.InvalidField(
          "namespace",
          s"${namespace.wire} is not allowed for ${kind.prefix}"
        )
      )
    else
      IdFormat
        .normalizeSourceId(namespace, sourceId)
        .left
        .map(reason => ValidationError.MalformedId("sourceId", namespace.wire, reason))
        .map(normalized => s"${kind.prefix}:${namespace.wire}:$normalized")

  /** Hashed fallback when no authoritative source ID exists. */
  def fallbackNamespacedId(
      kind: IdKind,
      sourceDataset: SourceType,
      sourceRecordId: String
  ): String =
    require(kind.isNamespaced, s"${kind.prefix} is not a namespaced kind")
    val tag = s"scholarchain:${kind.prefix}:${IdNamespace.ScholarChainV1.wire}"
    s"${kind.prefix}:${IdNamespace.ScholarChainV1.wire}:" +
      hashFields(Seq(tag, sourceDataset.wire, sourceRecordId))

  def workId(namespace: IdNamespace, sourceId: String): Either[ValidationError, String] =
    namespacedId(IdKind.Work, namespace, sourceId)

  def fallbackWorkId(sourceDataset: SourceType, sourceRecordId: String): String =
    fallbackNamespacedId(IdKind.Work, sourceDataset, sourceRecordId)

  // ---- Researchers ---------------------------------------------------------------------------

  /** `researcher:orcid:<ORCID>` for a checksum-valid ORCID iD. */
  def researcherIdFromOrcid(orcid: String): Either[ValidationError, String] =
    IdFormat
      .normalizeOrcid(orcid)
      .left
      .map(reason => ValidationError.MalformedId("orcid", "orcid", reason))
      .map(o => s"researcher:orcid:$o")

  /**
   * Versioned ScholarChain identity ID, used when no validated external identifier exists. The
   * anchor is the source key that entity resolution chose to anchor the identity cluster (the
   * choice is match policy and belongs to entity-resolution); core only hashes it.
   */
  def scholarChainResearcherId(
      identityVersion: Int,
      anchorDataset: SourceType,
      anchorRecordId: String
  ): Either[ValidationError, String] =
    if !SchemaVersion.ResearcherIdentitySupported.contains(identityVersion) then
      Left(
        ValidationError.UnsupportedSchemaVersion(
          "identityVersion",
          identityVersion,
          SchemaVersion.ResearcherIdentitySupported.toList.sorted
        )
      )
    else if anchorRecordId.trim.isEmpty then
      Left(ValidationError.InvalidField("anchorRecordId", "must not be blank"))
    else
      val sc = s"sc$identityVersion"
      Right(s"researcher:$sc:" +
        hashFields(Seq(s"scholarchain:researcher:$sc", anchorDataset.wire, anchorRecordId)))

  // ---- Hashed IDs ----------------------------------------------------------------------------

  def mentionId(
      workId: String,
      authorPosition: Int,
      sourceDataset: SourceType,
      sourceRecordId: String
  ): String =
    hashed(
      IdKind.Mention,
      "scholarchain:mention:v1",
      Seq(workId, authorPosition.toString, sourceDataset.wire, sourceRecordId)
    )

  def coauthorEdgeId(pair: ResearcherPair, window: YearWindow, policyVersion: String): String =
    hashed(
      IdKind.CoauthorEdge,
      "scholarchain:coauthor-edge:v1",
      Seq(pair.low, pair.high, window.startYear.toString, window.endYear.toString, policyVersion)
    )

  /** Chunk or profile ID. `textHash` is [[CanonicalHash.contentHash]] of the chunk text. */
  def chunkId(
      entityId: String,
      sourceVersion: String,
      documentType: DocumentType,
      ordinal: Option[Int],
      textHash: String
  ): String =
    hashed(
      IdKind.Chunk,
      "scholarchain:chunk:v1",
      Seq(entityId, sourceVersion, documentType.wire, ordinal.fold("")(_.toString), textHash)
    )

  def textHash(text: String): String = CanonicalHash.contentHash(text)

  /**
   * Graph edge ID. Identity is the edge type, its endpoints (put in canonical order for undirected
   * types, so `from`/`to` argument order does not matter for them), the policy version, and the
   * graph window (an absent window hashes as two empty fields). Evidence IDs are deliberately not
   * part of the identity: an edge keeps its ID as evidence accumulates, so graph-store upserts
   * keyed by `edgeId` are idempotent and only the edge's `evidenceIds` attribute changes.
   */
  def graphEdgeId(
      edgeType: GraphEdgeType,
      fromId: String,
      toId: String,
      policyVersion: String,
      window: Option[YearWindow]
  ): String =
    val (a, b) =
      if edgeType.undirected && fromId.compareTo(toId) > 0 then (toId, fromId) else (fromId, toId)
    hashed(
      IdKind.GraphEdge,
      "scholarchain:graph-edge:v1",
      Seq(
        edgeType.wire,
        a,
        b,
        policyVersion,
        window.fold("")(_.startYear.toString),
        window.fold("")(_.endYear.toString)
      )
    )

  /** The ID `edge` must carry, computed from its identity fields (never from its evidence). */
  def graphEdgeId(edge: GraphEdge): String =
    graphEdgeId(edge.edgeType, edge.fromId, edge.toId, edge.policyVersion, edge.window)

  def evidenceId(
      evidenceType: EvidenceType,
      subjectId: String,
      contentHash: String,
      sourceDataset: SourceType,
      sourceRecordId: String
  ): String =
    hashed(
      IdKind.Evidence,
      "scholarchain:evidence:v1",
      Seq(evidenceType.wire, subjectId, contentHash, sourceDataset.wire, sourceRecordId)
    )

  def matchDecisionId(
      mentionId: String,
      candidateResearcherId: Option[String],
      policyVersion: String
  ): String =
    hashed(
      IdKind.MatchDecision,
      "scholarchain:match-decision:v1",
      Seq(mentionId, candidateResearcherId.getOrElse(""), policyVersion)
    )

  def evidenceBundleId(evidenceIds: Seq[String]): String =
    hashed(
      IdKind.EvidenceBundle,
      "scholarchain:evidence-bundle:v1",
      list(sortedDistinct(evidenceIds))
    )

  def queryPlanId(
      question: String,
      researcherIds: Seq[String],
      window: Option[YearWindow],
      steps: Seq[QueryStep]
  ): String =
    val stepFields = steps.flatMap { s =>
      s.operation.wire +: list(s.parameters.flatMap(p => Seq(p.name, p.value)))
    }
    hashed(
      IdKind.QueryPlan,
      "scholarchain:query-plan:v1",
      Seq(question.trim) ++ list(researcherIds) ++
        Seq(window.fold("")(_.startYear.toString), window.fold("")(_.endYear.toString)) ++
        (steps.size.toString +: stepFields)
    )

  def claimId(text: String, evidenceIds: Seq[String]): String =
    hashed(IdKind.Claim, "scholarchain:claim:v1", text.trim +: list(sortedDistinct(evidenceIds)))

  def answerId(planId: String, bundleId: String, claimIds: Seq[String]): String =
    hashed(IdKind.Answer, "scholarchain:answer:v1", Seq(planId, bundleId) ++ list(claimIds))

  def runId(
      pipelineName: String,
      codeVersion: String,
      configFingerprint: String,
      inputs: Seq[RunInput],
      attempt: Int
  ): String =
    val sortedInputs = inputs
      .map(i => (i.sourceDataset.wire, i.snapshotId, i.checksum))
      .distinct
      .sorted
    hashed(
      IdKind.Run,
      "scholarchain:run:v1",
      Seq(pipelineName, codeVersion, configFingerprint, sortedInputs.size.toString) ++
        sortedInputs.flatMap((d, s, c) => Seq(d, s, c)) ++ Seq(attempt.toString)
    )

  def runId(manifest: RunManifest): String =
    runId(
      manifest.pipelineName,
      manifest.codeVersion,
      manifest.configFingerprint,
      manifest.inputs,
      manifest.attempt
    )
