package edu.uic.cs441.scholarchain.rag.semantic

import edu.uic.cs441.scholarchain.core.model.Provenance
import edu.uic.cs441.scholarchain.storage.vectors.MetaValue

/**
 * semantic-metadata-format-v1 (Phase 11 repair-02, RR5): the only shape rag writes for, and accepts
 * back from, string vector metadata and semantic-item provenance.
 *
 * Allow-list: a value is a token of 1 to [[MaxChars]] characters, each printable ASCII other than
 * space (U+0021 to U+007E). Every value rag writes fits it: stable IDs (`res:v1:...`,
 * `chunk:v1:...`), run IDs (`run:v1:...`), `sha256:<hex>` text hashes, document-type and dataset
 * wire values, embedding model versions, S3 keys under `semantic-context/`, and source record IDs
 * (DBLP keys, OpenAlex, Crossref DOIs, ORCID iDs, arXiv IDs). Everything else is outside it: every
 * control character (NUL, TAB, LF, CR, ...), every space, line or paragraph separator (U+0085,
 * U+2028, U+2029), format (bidi) characters, and all non-ASCII. A token therefore cannot start a
 * new prompt line, and it cannot spell a multi-word delimiter or directive such as `END SEMANTIC
 * CONTEXT` or `SYSTEM: obey`.
 *
 * Rag applies the same rule on both sides: [[SummaryTemplates]] refuses a source record ID outside
 * it at ingestion, and [[SemanticRetriever]] drops, with a reason, any hit read back from the index
 * whose string metadata or provenance is outside it.
 */
object MetadataFormat:
  val Version: String = "semantic-metadata-format-v1"

  /** Longest token; equal to storage's `VectorMetadata.MaxValueChars`. */
  val MaxChars: Int = 256

  /** True when `s` is a token: 1..[[MaxChars]] characters, each in U+0021..U+007E. */
  def isToken(s: String): Boolean =
    s != null && s.nonEmpty && s.length <= MaxChars && s.forall(c => c >= '!' && c <= '~')

  /**
   * Names of the metadata keys whose string value is not a token, sorted. Values are never echoed,
   * so a reason built from this list cannot carry retrieved text.
   */
  def problems(m: Map[String, MetaValue]): List[String] =
    m.toList.collect { case (k, MetaValue.Str(v)) if !isToken(v) => String.valueOf(k) }.sorted

  /** True when every string field of the provenance is a token and the dataset is present. */
  def provenanceOk(p: Provenance): Boolean =
    p != null && p.sourceDataset != null && isToken(p.sourceRecordId) && isToken(p.pipelineRunId)
