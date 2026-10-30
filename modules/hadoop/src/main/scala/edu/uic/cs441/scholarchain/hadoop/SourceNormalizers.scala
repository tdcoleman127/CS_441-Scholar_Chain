package edu.uic.cs441.scholarchain.hadoop

import edu.uic.cs441.scholarchain.core.SchemaVersion
import edu.uic.cs441.scholarchain.core.ids.{IdFormat, IdKind, StableIds}
import edu.uic.cs441.scholarchain.core.model.*
import edu.uic.cs441.scholarchain.core.validation.Validation
import edu.uic.cs441.scholarchain.core.validation.Validators.given
import java.io.StringReader
import javax.xml.stream.{XMLInputFactory, XMLStreamConstants}
import scala.collection.mutable.ListBuffer
import scala.util.control.NonFatal
import upickle.default.ReadWriter

// Hadoop-local records for Job 2 (external source normalization). Core has no field for these
// source-specific values, so they stay in the hadoop module (as human decision 3 did for
// TeamRecord); they are listed under RISKS for the human and domain-engineer.

/**
 * Work-level source values with no core field: abstract, venue name, references. Topic labels are
 * not kept here: they travel paired with their IDs as core [[Topic]] records in
 * [[NormalizedWork.topics]].
 */
final case class WorkExtra(
    workId: String,
    recordType: String,
    abstractText: Option[String],
    venueName: Option[String],
    referencedWorkIds: List[String],
    provenance: Provenance
) derives ReadWriter

/**
 * Mention-level source values with no core field. `sourceAuthorId` is the source's own author ID
 * (`openalex:A123`); `institutionIds` are resolved institution IDs; `invalidOrcid` keeps a raw
 * ORCID that failed `IdFormat.normalizeOrcid`, so it is not silently dropped.
 */
final case class MentionExtra(
    mentionId: String,
    workId: String,
    authorPosition: Int,
    sourceAuthorId: Option[String],
    institutionIds: List[String],
    invalidOrcid: Option[String],
    provenance: Provenance
) derives ReadWriter

/**
 * One normalized work: the shared contracts plus the source-specific extras. `topics` and
 * `institutions` are core [[Topic]] and [[Institution]] records (ID paired with its label, the
 * work's provenance), one per distinct labelled ID in this work, sorted by ID.
 */
final case class NormalizedWork(
    envelope: WorkEnvelope,
    extra: WorkExtra,
    mentionExtras: List[MentionExtra],
    topics: List[Topic],
    institutions: List[Institution]
) derives ReadWriter

/**
 * One OpenCitations citation. `oci` is `IdFormat.normalizeOci` of the source OCI and is also the
 * provenance `sourceRecordId`. Endpoint work IDs use the OMID when present, else the DOI.
 */
final case class CitationRecord(
    oci: String,
    citingWorkId: String,
    citedWorkId: String,
    citingIds: List[ExternalId],
    citedIds: List[ExternalId],
    creation: Option[String],
    timespan: Option[String],
    journalSelfCitation: Option[Boolean],
    authorSelfCitation: Option[Boolean],
    provenance: Provenance
) derives ReadWriter

/** Job 2 map value: exactly one of `work` and `citation` is set (`kind` = work | citation). */
final case class SourceEnvelope(
    kind: String,
    work: Option[NormalizedWork],
    citation: Option[CitationRecord]
) derives ReadWriter:
  def key: String = work.fold(citation.fold("")(c => s"oci:${c.oci}"))(_.envelope.work.workId)

/**
 * Pure normalizers, one per committed external sample (no Hadoop types; unit-tested directly).
 *
 * Input: one source record as text (a JSONL line, a CSV line, or one whole OAI-PMH `<record>`).
 * Output: a [[SourceEnvelope]] whose WorkRecord and AuthorMentions pass the core Validators, or a
 * [[Rejection]]. Reason codes: `malformed-json`, `xml-not-well-formed`, `missing-key`,
 * `missing-title`, `unsupported-record-type`, `deleted-record`, `unsupported-source`,
 * `schema-failure`. Identifiers go through `IdFormat` / `StableIds` only.
 */
object SourceNormalizers:

  /** Source of an input file: the file-name prefix first, then the parent directory name. */
  def sourceFor(fileName: String, parentDir: String): Option[SourceType] =
    val supported = Seq(
      SourceType.OpenCitations,
      SourceType.OpenAlex,
      SourceType.Crossref,
      SourceType.Arxiv
    )
    val f = fileName.toLowerCase(java.util.Locale.ROOT)
    val d = Option(parentDir).getOrElse("").toLowerCase(java.util.Locale.ROOT)
    supported.find(s => f.startsWith(s.wire)).orElse(supported.find(_.wire == d))

  /** True for the OpenCitations CSV header line, which is skipped and not counted as input. */
  def isHeader(source: SourceType, line: String): Boolean =
    source == SourceType.OpenCitations && line.startsWith("oci,")

  def normalize(
      source: SourceType,
      text: String,
      runId: String
  ): Either[Rejection, SourceEnvelope] =
    try
      source match
        case SourceType.OpenAlex      => openAlex(text, runId)
        case SourceType.Crossref      => crossref(text, runId)
        case SourceType.Arxiv         => arxiv(text, runId)
        case SourceType.OpenCitations => openCitations(text, runId)
        case other                    => Left(Rejection("unsupported-source", None, other.wire))
    catch case NonFatal(e) => Left(Rejection("schema-failure", None, String.valueOf(e.getMessage)))

  // ---- shared helpers ------------------------------------------------------------------------

  private def str(v: ujson.Value): Option[String] = v match
    case ujson.Str(s) if s.trim.nonEmpty => Some(s.trim)
    case _                               => None

  private def field(o: ujson.Value, name: String): ujson.Value =
    o.objOpt.flatMap(_.get(name)).getOrElse(ujson.Null)

  private def arr(v: ujson.Value): Seq[ujson.Value] = v.arrOpt.map(_.toSeq).getOrElse(Nil)

  private def parseJson(text: String): Either[Rejection, ujson.Value] =
    try
      val v = ujson.read(text)
      if v.objOpt.isEmpty then Left(Rejection("malformed-json", None, "not a JSON object"))
      else Right(v)
    catch case NonFatal(e) => Left(Rejection("malformed-json", None, String.valueOf(e.getMessage)))

  /** Drops markup tags (`<sub>`, JATS `<jats:p>`) and collapses whitespace. */
  def stripTags(s: String): String = s.replaceAll("<[^>]+>", "").trim.replaceAll("\\s+", " ")

  /** `\"u` -> `u`, `{\'e}` -> `e`: drops LaTeX accent commands used in arXiv names. */
  def deLatex(s: String): String =
    s.replaceAll("""\\[\"'`^~=.Hcuvk]\s*\{?([A-Za-z])\}?""", "$1").replaceAll("[{}]", "")

  private def extId(ns: IdNamespace, raw: String): Option[ExternalId] =
    IdFormat.normalizeSourceId(ns, raw).toOption.map(ExternalId(ns, _))

  private def nsId(kind: IdKind, ns: IdNamespace, raw: String): Option[String] =
    StableIds.namespacedId(kind, ns, raw).toOption

  final private case class RawAuthor(
      name: String,
      orcidRaw: Option[String],
      affiliation: Option[String],
      sourceAuthorId: Option[String],
      institutions: List[(String, Option[String])]
  )

  /**
   * Topic or institution entries, kept as (ID, label) pairs per source entry so a label never
   * detaches from its ID. Missing pieces:
   *   - ID and label: paired as given.
   *   - label but no (valid) ID: topics get a hashed `topic:sc1:` ID from the label
   *     (`StableIds.fallbackNamespacedId`, as for arXiv categories). Institutions get none (core
   *     admits only resolved ROR/OpenAlex institution IDs); the name stays in `affiliationText`.
   *   - ID but no label: the ID stays on the work (`topicIds`) or mention
   *     (`MentionExtra.institutionIds`); no core Topic/Institution record, which requires a name.
   *   - an unparseable topic ID with no label: hashed `topic:sc1:` ID from `id:<raw>`.
   */
  def labelTopicId(source: SourceType, label: String): String =
    StableIds.fallbackNamespacedId(IdKind.Topic, source, label)

  /** (ID, label) pairs -> one entry per distinct labelled ID (smallest label wins), by ID. */
  private def labelled(pairs: List[(String, Option[String])]): List[(String, String)] =
    pairs.collect { case (id, Some(l)) => id -> l }.groupMapReduce(_._1)(_._2)((a, b) =>
      if a.compareTo(b) <= 0 then a else b
    ).toList.sorted

  /** Builds and validates the shared records for one work. */
  private def build(
      source: SourceType,
      recordId: String,
      workId: String,
      recordType: String,
      title: String,
      year: Option[Int],
      venueId: Option[String],
      externalIds: List[ExternalId],
      topics: List[(String, Option[String])],
      authors: List[RawAuthor],
      abstractText: Option[String],
      venueName: Option[String],
      references: List[String],
      runId: String
  ): Either[Rejection, SourceEnvelope] =
    val prov = Provenance(source, recordId, runId)
    val work = WorkRecord(
      SchemaVersion.Current,
      workId,
      title,
      year,
      venueId,
      externalIds.distinct,
      topics.map(_._1).distinct.sorted,
      prov
    )
    val topicRecs = labelled(topics).map((id, l) => Topic(SchemaVersion.Current, id, l, prov))
    val instRecs  = labelled(authors.flatMap(_.institutions))
      .map((id, l) => Institution(SchemaVersion.Current, id, l, None, prov))
    val withIds = authors.zipWithIndex.map { case (a, pos) =>
      val mid   = StableIds.mentionId(workId, pos, source, recordId)
      val orcid = a.orcidRaw.flatMap(o => IdFormat.normalizeOrcid(o).toOption)
      val m     = AuthorMention(
        SchemaVersion.Current,
        mid,
        workId,
        pos,
        a.name,
        DblpRecordParser.normalizeName(a.name),
        orcid,
        a.affiliation,
        prov
      )
      val x = MentionExtra(
        mid,
        workId,
        pos,
        a.sourceAuthorId,
        a.institutions.map(_._1).distinct.sorted,
        a.orcidRaw.filter(_ => orcid.isEmpty),
        prov
      )
      (m, x)
    }
    val mentions = withIds.map(_._1)
    val errors   = Validation.validate(work).left.toOption.toList.flatten ++
      mentions.flatMap(m => Validation.validate(m).left.toOption.toList.flatten) ++
      topicRecs.flatMap(t => Validation.validate(t).left.toOption.toList.flatten) ++
      instRecs.flatMap(i => Validation.validate(i).left.toOption.toList.flatten)
    if errors.nonEmpty then Left(Rejection("schema-failure", Some(recordId), errors.mkString("; ")))
    else
      val extra = WorkExtra(
        workId,
        recordType,
        abstractText,
        venueName,
        references.distinct.sorted,
        prov
      )
      Right(SourceEnvelope(
        "work",
        Some(NormalizedWork(
          WorkEnvelope(recordType, work, mentions),
          extra,
          withIds.map(_._2),
          topicRecs,
          instRecs
        )),
        None
      ))

  // ---- OpenAlex works (JSONL) ----------------------------------------------------------------

  /** OpenAlex `abstract_inverted_index` -> text, words ordered by position. */
  def invertedAbstract(v: ujson.Value): Option[String] =
    v.objOpt.flatMap { o =>
      val words =
        for
          (w, ps) <- o.toSeq
          p       <- arr(ps).flatMap(_.numOpt)
        yield p.toInt -> w
      if words.isEmpty then None else Some(words.sortBy(_._1).map(_._2).mkString(" "))
    }

  /** One OpenAlex `topics`/`concepts` entry -> (topic ID, label), paired (see labelTopicId). */
  def openAlexTopic(tp: ujson.Value): Option[(String, Option[String])] =
    val rawId = str(field(tp, "id")); val label = str(field(tp, "display_name"))
    rawId.flatMap(nsId(IdKind.Topic, IdNamespace.OpenAlex, _))
      .orElse(label.map(labelTopicId(SourceType.OpenAlex, _)))
      .orElse(rawId.map(r => labelTopicId(SourceType.OpenAlex, s"id:$r")))
      .map(_ -> label)

  def openAlex(text: String, runId: String): Either[Rejection, SourceEnvelope] =
    parseJson(text).flatMap { j =>
      str(field(j, "id")).flatMap(id =>
        IdFormat.normalizeSourceId(IdNamespace.OpenAlex, id)
          .toOption
      ) match
        case None      => Left(Rejection("missing-key", None, "openalex work without a valid id"))
        case Some(oid) =>
          val title = str(field(j, "title")).orElse(str(field(j, "display_name"))).map(stripTags)
          val yearV = field(j, "publication_year")
          title match
            case None => Left(Rejection("missing-title", Some(oid), "title and display_name null"))
            case Some(_) if !yearV.isNull && yearV.numOpt.isEmpty =>
              Left(Rejection("schema-failure", Some(oid), "publication_year is not a number"))
            case Some(t) =>
              val ids    = field(j, "ids")
              val extIds =
                List(Some(ExternalId(IdNamespace.OpenAlex, oid))) ++
                  List(str(field(j, "doi")).orElse(str(field(ids, "doi"))).flatMap(
                    extId(IdNamespace.Doi, _)
                  )) ++
                  List(str(field(ids, "pmid")).flatMap(extId(IdNamespace.Pmid, _)))
              val source = field(field(j, "primary_location"), "source")
              val venue  = str(field(source, "id")).flatMap(nsId(
                IdKind.Venue,
                IdNamespace.OpenAlex,
                _
              ))
              val topics  = arr(field(j, "topics")) ++ arr(field(j, "concepts"))
              val authors = arr(field(j, "authorships")).toList.flatMap { a =>
                val au   = field(a, "author")
                val name = str(field(a, "raw_author_name")).orElse(str(field(au, "display_name")))
                name.map(n =>
                  RawAuthor(
                    n,
                    str(field(au, "orcid")),
                    Some(arr(field(a, "raw_affiliation_strings")).flatMap(str).mkString("; "))
                      .filter(_.nonEmpty),
                    str(field(au, "id")).flatMap(IdFormat.normalizeSourceId(
                      IdNamespace.OpenAlex,
                      _
                    ).toOption).map(s => s"openalex:$s"),
                    arr(field(a, "institutions")).toList.flatMap(i =>
                      str(field(i, "ror")).flatMap(nsId(IdKind.Institution, IdNamespace.Ror, _))
                        .orElse(str(field(i, "id")).flatMap(nsId(
                          IdKind.Institution,
                          IdNamespace.OpenAlex,
                          _
                        )))
                        .map(_ -> str(field(i, "display_name")))
                    )
                  )
                )
              }
              build(
                SourceType.OpenAlex,
                oid,
                s"work:openalex:$oid",
                str(field(j, "type")).getOrElse("unknown"),
                t,
                yearV.numOpt.map(_.toInt),
                venue,
                extIds.flatten,
                topics.toList.flatMap(openAlexTopic),
                authors,
                invertedAbstract(field(j, "abstract_inverted_index")),
                str(field(source, "display_name")),
                arr(field(j, "referenced_works")).toList.flatMap(str).flatMap(
                  StableIds.workId(IdNamespace.OpenAlex, _).toOption
                ),
                runId
              )
    }

  // ---- Crossref works (JSONL) ----------------------------------------------------------------

  /** Crossref work types normalized as works; anything else (dataset, component, ...) rejects. */
  val CrossrefTypes: Set[String] = Set(
    "journal-article",
    "proceedings-article",
    "book-chapter",
    "book",
    "monograph",
    "edited-book",
    "posted-content",
    "report",
    "dissertation",
    "reference-entry"
  )

  def crossref(text: String, runId: String): Either[Rejection, SourceEnvelope] =
    parseJson(text).flatMap { j =>
      str(field(j, "DOI")).flatMap(d =>
        IdFormat.normalizeSourceId(IdNamespace.Doi, d).toOption
      ) match
        case None      => Left(Rejection("missing-key", None, "crossref work without a valid DOI"))
        case Some(doi) =>
          val tpe   = str(field(j, "type")).getOrElse("unknown")
          val title = arr(field(j, "title")).flatMap(str).headOption.map(stripTags)
          if !CrossrefTypes(tpe) then Left(Rejection("unsupported-record-type", Some(doi), tpe))
          else
            title match
              case None    => Left(Rejection("missing-title", Some(doi), tpe))
              case Some(t) =>
                val year = arr(field(field(j, "issued"), "date-parts")).headOption
                  .flatMap(p => arr(p).headOption).flatMap(_.numOpt).map(_.toInt)
                val issn    = arr(field(j, "ISSN")).flatMap(str).headOption
                val authors = arr(field(j, "author")).toList.flatMap { a =>
                  val name = (str(field(a, "given")).toList ++ str(field(a, "family")).toList)
                    .mkString(" ")
                  Some(name).filter(_.nonEmpty).orElse(str(field(a, "name"))).map(n =>
                    RawAuthor(
                      n,
                      str(field(a, "ORCID")),
                      Some(arr(field(a, "affiliation")).flatMap(af => str(field(af, "name")))
                        .mkString("; ")).filter(_.nonEmpty),
                      None,
                      Nil
                    )
                  )
                }
                build(
                  SourceType.Crossref,
                  doi,
                  s"work:doi:$doi",
                  tpe,
                  t,
                  year,
                  issn.flatMap(nsId(IdKind.Venue, IdNamespace.Issn, _)),
                  List(ExternalId(IdNamespace.Doi, doi)),
                  // Crossref subjects are labels without IDs: hashed sc1 topic IDs.
                  arr(field(j, "subject")).toList.flatMap(str)
                    .map(s => labelTopicId(SourceType.Crossref, s) -> Some(s)),
                  authors,
                  str(field(j, "abstract")).map(stripTags),
                  arr(field(j, "container-title")).flatMap(str).headOption,
                  arr(field(j, "reference")).toList.flatMap(r => str(field(r, "DOI")))
                    .flatMap(StableIds.workId(IdNamespace.Doi, _).toOption),
                  runId
                )
    }

  // ---- arXiv OAI-PMH records (metadataPrefix=arXiv) ------------------------------------------

  final private case class ArxivRaw(
      deleted: Boolean,
      headerId: Option[String],
      fields: Map[String, String],
      authors: List[RawAuthor]
  )

  private def readArxiv(xml: String): ArxivRaw =
    val factory = XMLInputFactory.newFactory()
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false)
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
    // A record cut out of the OAI-PMH response loses the root's `xsi` declaration, so prefixed
    // attributes (`xsi:schemaLocation`) would be undeclared. Element names here are unprefixed.
    factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, false)
    val r       = factory.createXMLStreamReader(new StringReader(xml))
    var deleted = false
    val path    = ListBuffer.empty[String]
    val fields  = scala.collection.mutable.LinkedHashMap.empty[String, String]
    val authors = ListBuffer.empty[RawAuthor]
    var cur     = Map.empty[String, String]
    var affs    = List.empty[String]
    val text    = new StringBuilder
    while r.hasNext do
      r.next() match
        case XMLStreamConstants.START_ELEMENT =>
          val name = r.getLocalName
          path += name
          if name == "header" && r.getAttributeValue(null, "status") == "deleted" then
            deleted = true
          if name == "author" then { cur = Map.empty; affs = Nil }
          text.clear()
        case XMLStreamConstants.CHARACTERS | XMLStreamConstants.CDATA =>
          text.append(r.getText)
        case XMLStreamConstants.END_ELEMENT =>
          val name = path.last
          val v    = text.toString.trim.replaceAll("\\s+", " ")
          val in   = path.mkString("/")
          if in.endsWith("header/identifier") then fields("headerId") = v
          else if in.contains("authors/author/") then
            if name == "affiliation" then affs = affs :+ v else cur += name -> v
          else if name == "author" then
            val n = deLatex(
              (cur.get("forenames").toList ++ cur.get("keyname").toList ++ cur.get("suffix").toList)
                .mkString(" ")
            ).trim
            if n.nonEmpty then
              authors += RawAuthor(
                n,
                None,
                Some(affs.mkString("; ")).filter(_.nonEmpty),
                None,
                Nil
              )
          else if in.contains("metadata/arXiv/") && path.size >= 4 then fields(name) = v
          path.remove(path.size - 1)
          text.clear()
        case _ => ()
    ArxivRaw(deleted, fields.get("headerId"), fields.toMap, authors.toList)

  def arxiv(text: String, runId: String): Either[Rejection, SourceEnvelope] =
    val raw =
      try Right(readArxiv(text))
      catch case NonFatal(e) => Left(Rejection("xml-not-well-formed", None, e.getMessage))
    raw.flatMap { a =>
      val id = a.fields.get("id").filter(_.nonEmpty)
        .orElse(a.headerId.map(_.stripPrefix("oai:arXiv.org:")).filter(_.nonEmpty))
        .flatMap(i => IdFormat.normalizeSourceId(IdNamespace.Arxiv, i).toOption)
      (id, a.deleted) match
        case (None, _)       => Left(Rejection("missing-key", None, "arXiv record without an id"))
        case (Some(i), true) => Left(Rejection("deleted-record", Some(i), "OAI header deleted"))
        case (Some(i), _)    =>
          a.fields.get("title").filter(_.nonEmpty) match
            case None    => Left(Rejection("missing-title", Some(i), "arXiv"))
            case Some(t) =>
              val cats = a.fields.get("categories").toList.flatMap(_.split(' ')).filter(_.nonEmpty)
              build(
                SourceType.Arxiv,
                i,
                s"work:arxiv:$i",
                "preprint",
                t,
                a.fields.get("created").flatMap(_.take(4).toIntOption),
                None,
                List(
                  Some(ExternalId(IdNamespace.Arxiv, i)),
                  a.fields.get("doi").flatMap(extId(IdNamespace.Doi, _))
                ).flatten,
                // arXiv categories have no core topic namespace: hashed sc1 topic IDs.
                cats.map(c => labelTopicId(SourceType.Arxiv, c) -> Some(c)),
                a.authors,
                a.fields.get("abstract").filter(_.nonEmpty),
                None,
                Nil,
                runId
              )
    }

  // ---- OpenCitations index (CSV) -------------------------------------------------------------

  /** `omid:br/1 doi:10.x/y pmid:3` -> normalized ExternalIds (unknown prefixes are skipped). */
  def citationIds(field: String): List[ExternalId] =
    field.split(' ').toList.filter(_.nonEmpty).flatMap { tok =>
      tok.split(":", 2) match
        case Array("omid", v) => extId(IdNamespace.Omid, v)
        case Array("doi", v)  => extId(IdNamespace.Doi, v)
        case Array("pmid", v) => extId(IdNamespace.Pmid, v)
        case _                => None
    }

  private def endpoint(ids: List[ExternalId]): Option[String] =
    ids.find(_.namespace == IdNamespace.Omid).orElse(ids.find(_.namespace == IdNamespace.Doi))
      .flatMap(e => StableIds.workId(e.namespace, e.value).toOption)

  def openCitations(line: String, runId: String): Either[Rejection, SourceEnvelope] =
    val cols = line.split(",", -1).toVector
    val oci  = cols.headOption.flatMap(c => IdFormat.normalizeOci(c).toOption)
    oci match
      case None => Left(Rejection("missing-key", None, "line without a valid OCI"))
      case Some(o) if cols.size != 7 =>
        Left(Rejection("schema-failure", Some(o), s"expected 7 columns, got ${cols.size}"))
      case Some(o) =>
        val citing = citationIds(cols(1)); val cited = citationIds(cols(2))
        (endpoint(citing), endpoint(cited)) match
          case (Some(a), Some(b)) =>
            def opt(s: String)  = Some(s.trim).filter(_.nonEmpty)
            def flag(s: String) = s.trim match
              case "yes" => Some(true)
              case "no"  => Some(false)
              case _     => None
            Right(SourceEnvelope(
              "citation",
              None,
              Some(CitationRecord(
                o,
                a,
                b,
                citing,
                cited,
                opt(cols(3)),
                opt(cols(4)),
                flag(cols(5)),
                flag(cols(6)),
                Provenance(SourceType.OpenCitations, o, runId)
              ))
            ))
          case _ => Left(Rejection("missing-key", Some(o), "citing or cited has no OMID or DOI"))
