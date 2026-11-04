package edu.uic.cs441.scholarchain.core.ids

import edu.uic.cs441.scholarchain.core.SchemaVersion
import edu.uic.cs441.scholarchain.core.model.IdNamespace

/**
 * Every stable-ID kind and its textual shape.
 *
 * Namespaced kinds: `<prefix>:<namespace>:<sourceId>`, where the namespace is one of the kind's
 * `namespaces` and `sourceId` is the already-normalized authoritative source ID (see
 * [[IdFormat.normalizeSourceId]]), or `<prefix>:sc1:<64 hex>` for the hashed fallback.
 *
 * Researcher: `researcher:orcid:<ORCID with valid checksum>` or `researcher:sc<N>:<64 hex>`, where
 * N is a supported ScholarChain identity version.
 *
 * Hashed kinds (including `cocomm`, the coauthorship community): `<prefix>:v1:<64 lowercase hex>`.
 * Content hashes: `sha256:<64 lowercase hex>`.
 */
enum IdKind(val prefix: String, val namespaces: Set[IdNamespace]):
  case Work
      extends IdKind(
        "work",
        Set(
          IdNamespace.Doi,
          IdNamespace.Dblp,
          IdNamespace.OpenAlex,
          IdNamespace.Arxiv,
          IdNamespace.SemanticScholar,
          IdNamespace.PapersWithCode,
          IdNamespace.Omid,
          IdNamespace.Pmid,
          IdNamespace.ScholarChainV1
        )
      )
  case Venue
      extends IdKind(
        "venue",
        Set(IdNamespace.Dblp, IdNamespace.OpenAlex, IdNamespace.Issn, IdNamespace.ScholarChainV1)
      )
  case Institution
      extends IdKind(
        "institution",
        Set(IdNamespace.Ror, IdNamespace.OpenAlex, IdNamespace.ScholarChainV1)
      )
  case Topic
      extends IdKind(
        "topic",
        Set(IdNamespace.OpenAlex, IdNamespace.PapersWithCode, IdNamespace.ScholarChainV1)
      )
  case Researcher     extends IdKind("researcher", Set.empty)
  case Mention        extends IdKind("mention", Set.empty)
  case CoauthorEdge   extends IdKind("coedge", Set.empty)
  case Chunk          extends IdKind("chunk", Set.empty)
  case GraphEdge      extends IdKind("gedge", Set.empty)
  case Evidence       extends IdKind("evidence", Set.empty)
  case MatchDecision  extends IdKind("match", Set.empty)
  case EvidenceBundle extends IdKind("bundle", Set.empty)
  case QueryPlan      extends IdKind("plan", Set.empty)
  case Claim          extends IdKind("claim", Set.empty)
  case Answer         extends IdKind("answer", Set.empty)
  case Run            extends IdKind("run", Set.empty)
  case ContentHash    extends IdKind("sha256", Set.empty)

  /**
   * A community of the temporal coauthorship graph: `cocomm:v1:<64 hex>` (see
   * [[StableIds.communityId]]). The prefix parallels `coedge`. It is deliberately not `community`:
   * the Phase 08 rag-local `community:v1:<hex>` IDs are not core IDs and stay invalid.
   */
  case Community extends IdKind("cocomm", Set.empty)

  def isNamespaced: Boolean = namespaces.nonEmpty

object IdFormat:

  /** Version tag of every hashed ID formula. */
  val HashedFormulaVersion: String = "v1"

  private val Hex64        = "[0-9a-f]{64}"
  private val HexPattern   = s"^$Hex64$$".r
  private val SourceIdChar = """^[^\s\p{Cntrl}]+$""".r
  private val OrcidPattern = """^\d{4}-\d{4}-\d{4}-\d{3}[\dX]$""".r
  private val IssnPattern  = """^\d{4}-\d{3}[\dX]$""".r
  private val RorPattern   = """^0[a-z0-9]{6}\d{2}$""".r
  private val OpenAlexId   = """^[A-Z]\d+$""".r
  private val ScVersion    = """^sc(\d{1,4})$""".r
  private val OmidPattern  = """^br/\d+$""".r
  private val PmidPattern  = """^[1-9]\d*$""".r
  private val OciPattern   = """^\d+-\d+$""".r

  /**
   * DOI resolver prefixes stripped by [[normalizeSourceId]], compared after lowercasing: every
   * combination of `http`/`https` and `doi.org`/`dx.doi.org`, plus `doi:`. At most one is stripped.
   */
  val DoiResolverPrefixes: Seq[String] = Seq(
    "https://doi.org/",
    "http://doi.org/",
    "https://dx.doi.org/",
    "http://dx.doi.org/",
    "doi:"
  )

  /** Institution-ID namespaces that count as resolved (the only ones allowed at runtime). */
  val ResolvedInstitutionNamespaces: Set[IdNamespace] = Set(IdNamespace.Ror, IdNamespace.OpenAlex)

  /**
   * True for a well-formed institution ID in a resolved namespace (`institution:ror:<id>` or
   * `institution:openalex:<id>`); false for the hashed `institution:sc1:` fallback and for any
   * malformed value.
   */
  def isResolvedInstitutionId(value: String): Boolean =
    value != null && isValid(IdKind.Institution, value) &&
      ResolvedInstitutionNamespaces.exists(ns => value.startsWith(s"institution:${ns.wire}:"))

  /** Strips the first matching prefix, compared case-insensitively. */
  private def stripPrefixIgnoreCase(s: String, prefixes: Seq[String]): String =
    prefixes
      .find(p => s.regionMatches(true, 0, p, 0, p.length))
      .fold(s)(p => s.drop(p.length))

  def isValid(kind: IdKind, value: String): Boolean =
    if kind == IdKind.ContentHash then value.startsWith("sha256:") && isHex64(value.drop(7))
    else if kind == IdKind.Researcher then isResearcherId(value)
    else if kind.isNamespaced then isNamespacedId(kind, value)
    else isHashedId(kind, value)

  /** True when `value` is a well-formed ID of any kind. */
  def isAnyKnown(value: String): Boolean = IdKind.values.exists(isValid(_, value))

  def isHex64(s: String): Boolean = HexPattern.matches(s)

  private def isHashedId(kind: IdKind, value: String): Boolean =
    value.split(":", -1) match
      case Array(p, v, hex) => p == kind.prefix && v == HashedFormulaVersion && isHex64(hex)
      case _                => false

  private def isNamespacedId(kind: IdKind, value: String): Boolean =
    value.split(":", 3) match
      case Array(p, ns, rest) if p == kind.prefix =>
        IdNamespace.fromWire(ns) match
          case Some(IdNamespace.ScholarChainV1)                       => isHex64(rest)
          case Some(namespace) if kind.namespaces.contains(namespace) =>
            normalizeSourceId(namespace, rest) == Right(rest)
          case _ => false
      case _ => false

  private def isResearcherId(value: String): Boolean =
    value.split(":", -1) match
      case Array("researcher", "orcid", orcid)    => normalizeOrcid(orcid) == Right(orcid)
      case Array("researcher", ScVersion(v), hex) =>
        SchemaVersion.ResearcherIdentitySupported.contains(v.toInt) && isHex64(hex)
      case _ => false

  /**
   * Normalizes an authoritative source ID for its namespace, or explains why it is unusable.
   *   - doi: lowercase, then strip one resolver prefix from [[DoiResolverPrefixes]] (`http` or
   *     `https`, `doi.org` or `dx.doi.org`, or `doi:`; scheme and host match case-insensitively);
   *     the remaining body must start with `10.` and contain `/`.
   *   - arxiv: strip a leading `arXiv:` (any case). Version suffixes are kept.
   *   - openalex: strip `https://openalex.org/`; must be a letter followed by digits (`W123`).
   *   - issn: uppercase; must be `NNNN-NNNC`.
   *   - ror: strip `https://ror.org/`; lowercase; must be a 9-character ROR ID.
   *   - orcid: see [[normalizeOrcid]].
   *   - omid: strip `https://w3id.org/oc/meta/` or `omid:` (any case); lowercase; must be
   *     `br/<digits>` (an OpenCitations Meta bibliographic resource, for example
   *     `omid:br/06101801781` becomes `br/06101801781`). Digits, including leading zeros, are kept.
   *     Other OMID entity types (`ra`, `id`, `ar`, `re`) are rejected: no ScholarChain ID kind uses
   *     them.
   *   - pmid: strip `https://pubmed.ncbi.nlm.nih.gov/`, `http://pubmed.ncbi.nlm.nih.gov/`, or
   *     `pmid:` (any case) and one trailing `/`; strip leading zeros; must be a positive base-10
   *     integer (`pmid:33817056` and `https://pubmed.ncbi.nlm.nih.gov/33817056` become `33817056`).
   *   - dblp, s2, pwc: trimmed and otherwise kept verbatim.
   * In every namespace the result must be non-empty with no whitespace or control characters.
   */
  def normalizeSourceId(namespace: IdNamespace, raw: String): Either[String, String] =
    val trimmed    = raw.trim
    val normalized = namespace match
      case IdNamespace.Doi =>
        val lower    = trimmed.toLowerCase(java.util.Locale.ROOT)
        val stripped =
          DoiResolverPrefixes.find(lower.startsWith).fold(lower)(p => lower.drop(p.length))
        if stripped.startsWith("10.") && stripped.contains("/") then Right(stripped)
        else Left(s"'$raw' is not a DOI")
      case IdNamespace.Arxiv =>
        Right(
          if trimmed.toLowerCase(java.util.Locale.ROOT).startsWith("arxiv:") then trimmed.drop(6)
          else trimmed
        )
      case IdNamespace.OpenAlex =>
        val s = trimmed.stripPrefix("https://openalex.org/")
        if OpenAlexId.matches(s) then Right(s) else Left(s"'$raw' is not an OpenAlex ID")
      case IdNamespace.Issn =>
        val s = trimmed.toUpperCase(java.util.Locale.ROOT)
        if IssnPattern.matches(s) then Right(s) else Left(s"'$raw' is not an ISSN")
      case IdNamespace.Ror =>
        val s = trimmed.stripPrefix("https://ror.org/").toLowerCase(java.util.Locale.ROOT)
        if RorPattern.matches(s) then Right(s) else Left(s"'$raw' is not a ROR ID")
      case IdNamespace.Omid =>
        val s = stripPrefixIgnoreCase(trimmed, Seq("https://w3id.org/oc/meta/", "omid:"))
          .toLowerCase(java.util.Locale.ROOT)
        if OmidPattern.matches(s) then Right(s)
        else Left(s"'$raw' is not an OpenCitations bibliographic-resource OMID (br/<digits>)")
      case IdNamespace.Pmid =>
        val s = stripPrefixIgnoreCase(
          trimmed,
          Seq("https://pubmed.ncbi.nlm.nih.gov/", "http://pubmed.ncbi.nlm.nih.gov/", "pmid:")
        ).stripSuffix("/").dropWhile(_ == '0')
        if PmidPattern.matches(s) then Right(s) else Left(s"'$raw' is not a PubMed ID")
      case IdNamespace.Orcid          => normalizeOrcid(trimmed)
      case IdNamespace.ScholarChainV1 => Left("sc1 IDs are hashed, not normalized from a source ID")
      case _                          => Right(trimmed)
    normalized.flatMap(s =>
      if SourceIdChar.matches(s) then Right(s)
      else Left(s"'$raw' is empty or contains whitespace or control characters")
    )

  /**
   * Normalizes an ORCID iD: strips `https://orcid.org/` or `http://orcid.org/`, uppercases the
   * check character, requires the hyphenated 16-character form, and verifies the ISO 7064 MOD 11-2
   * check digit.
   */
  def normalizeOrcid(raw: String): Either[String, String] =
    val s = raw.trim
      .stripPrefix("https://orcid.org/")
      .stripPrefix("http://orcid.org/")
      .toUpperCase(java.util.Locale.ROOT)
    if !OrcidPattern.matches(s) then Left(s"'$raw' is not an ORCID iD")
    else if !orcidChecksumValid(s.filter(_ != '-')) then Left(s"'$raw' fails the ORCID checksum")
    else Right(s)

  /**
   * Normalizes an Open Citation Identifier to the canonical `sourceRecordId` of an OpenCitations
   * citation record: strips `oci:` (any case) and requires `<digits>-<digits>`, for example
   * `oci:06101801781-06180334099` becomes `06101801781-06180334099`. An OCI names a citation, not
   * an entity, so it is a provenance record key rather than an [[IdNamespace]]: a citation's graph
   * identity is its `CITES` edge ID, and the OCI is the `sourceRecordId` of that edge's evidence.
   */
  def normalizeOci(raw: String): Either[String, String] =
    val s = stripPrefixIgnoreCase(raw.trim, Seq("oci:"))
    if OciPattern.matches(s) then Right(s) else Left(s"'$raw' is not an Open Citation Identifier")

  private def orcidChecksumValid(digits: String): Boolean =
    val total    = digits.take(15).foldLeft(0)((acc, c) => (acc + (c - '0')) * 2)
    val result   = (12 - total % 11) % 11
    val expected = if result == 10 then 'X' else ('0' + result).toChar
    digits(15) == expected
