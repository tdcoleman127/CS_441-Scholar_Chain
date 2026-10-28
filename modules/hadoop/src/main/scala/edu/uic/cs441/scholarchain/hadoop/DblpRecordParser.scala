package edu.uic.cs441.scholarchain.hadoop

import edu.uic.cs441.scholarchain.core.SchemaVersion
import edu.uic.cs441.scholarchain.core.ids.{IdFormat, IdKind, StableIds}
import edu.uic.cs441.scholarchain.core.model.*
import edu.uic.cs441.scholarchain.core.validation.Validation
import edu.uic.cs441.scholarchain.core.validation.Validators.given
import java.io.StringReader
import java.text.Normalizer
import javax.xml.stream.{XMLInputFactory, XMLStreamConstants}
import scala.collection.mutable.ListBuffer
import scala.util.control.NonFatal

/** Why a DBLP record was rejected. `reason` is a stable, greppable code. */
final case class Rejection(reason: String, sourceRecordId: Option[String], detail: String)

/** One parsed and validated DBLP publication: the work plus its ordered author mentions. */
final case class ParsedWork(work: WorkRecord, mentions: List[AuthorMention], recordType: String)

/**
 * Entity table for DBLP XML. DBLP records use DTD-declared named entities (`&uuml;`); the table is
 * read from `dblp.dtd` (`<!ENTITY name "&#NNN;">`) so the parser needs no network DTD fetch.
 */
object DblpEntities:
  private val Decl = """<!ENTITY\s+([A-Za-z0-9]+)\s+"&#(\d+);"\s*>""".r

  def fromDtd(dtdText: String): Map[String, String] =
    Decl
      .findAllMatchIn(dtdText)
      .map(m => m.group(1) -> new String(Character.toChars(m.group(2).toInt)))
      .toMap

/**
 * Pure DBLP record parser (no Hadoop types; unit-tested directly).
 *
 * Input: the complete text of one DBLP record element as cut by [[DblpXmlInputFormat]]. Output:
 * either a [[ParsedWork]] whose WorkRecord and AuthorMentions pass the core Validators, or a
 * [[Rejection]]. Reason codes: `undeclared-entity`, `xml-not-well-formed`, `truncated-record`,
 * `unsupported-record-type`, `missing-key`, `missing-title`, `schema-failure`.
 */
object DblpRecordParser:

  val PublicationTypes: Set[String] =
    Set(
      "article",
      "inproceedings",
      "proceedings",
      "book",
      "incollection",
      "phdthesis",
      "mastersthesis"
    )

  /**
   * Every record type the DTD declares; `www` (person pages) and `data` are recognized but
   * unsupported.
   */
  val AllRecordTypes: Set[String] = PublicationTypes ++ Set("www", "data")

  private val Builtin   = Set("amp", "lt", "gt", "quot", "apos")
  private val EntityRef = """&([A-Za-z][A-Za-z0-9]*);""".r

  /** Replaces DTD entities by their characters; fails on an entity the DTD does not declare. */
  def resolveEntities(xml: String, entities: Map[String, String]): Either[String, String] =
    val undeclared = EntityRef
      .findAllMatchIn(xml)
      .map(_.group(1))
      .filterNot(n => Builtin(n) || entities.contains(n))
      .toList
    if undeclared.nonEmpty then Left(undeclared.distinct.mkString(","))
    else
      Right(EntityRef.replaceAllIn(
        xml,
        m =>
          val n = m.group(1)
          java.util.regex.Matcher.quoteReplacement(
            if Builtin(n) then m.matched else entities(n)
          )
      ))

  /** Name normalization for blocking: NFKD, strip marks, lowercase, collapse spaces. */
  def normalizeName(raw: String): String =
    Normalizer
      .normalize(raw, Normalizer.Form.NFKD)
      .replaceAll("\\p{M}+", "")
      .toLowerCase(java.util.Locale.ROOT)
      .replaceAll("[^\\p{L}\\p{N} ]+", " ")
      .trim
      .replaceAll("\\s+", " ")

  final private case class Raw(
      recordType: String,
      key: Option[String],
      authors: List[(String, Option[String])],
      title: Option[String],
      year: Option[String],
      venueText: Option[String],
      crossref: Option[String],
      ees: List[String],
      urls: List[String]
  )

  private def readRaw(xml: String): Raw =
    val factory = XMLInputFactory.newFactory()
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false)
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
    val r                            = factory.createXMLStreamReader(new StringReader(xml))
    var depth                        = 0
    var recType                      = ""
    var key                          = Option.empty[String]
    val authors                      = ListBuffer.empty[(String, Option[String])]
    val ees                          = ListBuffer.empty[String]
    val urls                         = ListBuffer.empty[String]
    var title, year, venue, crossref = Option.empty[String]
    var field                        = ""
    var orcid                        = Option.empty[String]
    val text                         = new StringBuilder
    while r.hasNext do
      r.next() match
        case XMLStreamConstants.START_ELEMENT =>
          depth += 1
          if depth == 1 then
            recType = r.getLocalName
            key = Option(r.getAttributeValue(null, "key")).map(_.trim).filter(_.nonEmpty)
          else if depth == 2 then
            field = r.getLocalName
            orcid = Option(r.getAttributeValue(null, "orcid")).map(_.trim).filter(_.nonEmpty)
            text.clear()
        case XMLStreamConstants.CHARACTERS | XMLStreamConstants.CDATA |
            XMLStreamConstants.SPACE =>
          if depth >= 2 then text.append(r.getText)
        case XMLStreamConstants.END_ELEMENT =>
          if depth == 2 then
            val v = text.toString.trim.replaceAll("\\s+", " ")
            field match
              case "author"                => if v.nonEmpty then authors += (v -> orcid)
              case "title"                 => title = Some(v)
              case "year"                  => year = Some(v)
              case "journal" | "booktitle" => if venue.isEmpty then venue = Some(v)
              case "crossref"              => crossref = Some(v)
              case "ee"                    => ees += v
              case "url"                   => urls += v
              case _                       => ()
          depth -= 1
        case _ => ()
    Raw(
      recType,
      key,
      authors.toList,
      title.filter(_.nonEmpty),
      year,
      venue,
      crossref,
      ees.toList,
      urls.toList
    )

  private def externalIds(ees: List[String]): List[ExternalId] =
    ees.flatMap { ee =>
      val lower = ee.toLowerCase(java.util.Locale.ROOT)
      if lower.contains("doi.org/") then
        IdFormat.normalizeSourceId(IdNamespace.Doi, ee).toOption.map(ExternalId(IdNamespace.Doi, _))
      else if lower.startsWith("https://arxiv.org/abs/") then
        IdFormat
          .normalizeSourceId(IdNamespace.Arxiv, ee.drop("https://arxiv.org/abs/".length))
          .toOption
          .map(ExternalId(IdNamespace.Arxiv, _))
      else None
    }.distinct

  /** DBLP venue stream key: `conf/x` or `journals/x` from the record key. */
  def venueKey(dblpKey: String): Option[String] =
    dblpKey.split('/').toList match
      case kind :: stream :: _ :: Nil if kind == "conf" || kind == "journals" =>
        Some(s"$kind/$stream")
      case _ => None

  def parse(
      recordXml: String,
      entities: Map[String, String],
      runId: String
  ): Either[Rejection, ParsedWork] =
    val keyGuess = """key="([^"]*)"""".r.findFirstMatchIn(recordXml).map(_.group(1))
    if recordXml.startsWith(DblpXmlInputFormat.TruncatedMarker) then
      Left(Rejection("truncated-record", keyGuess, "record has no closing tag before end of input"))
    else
      resolveEntities(recordXml, entities) match
        case Left(names) => Left(Rejection("undeclared-entity", keyGuess, names))
        case Right(xml)  =>
          val raw =
            try Right(readRaw(xml))
            catch case NonFatal(e) => Left(Rejection("xml-not-well-formed", keyGuess, e.getMessage))
          raw.flatMap(build(_, runId))

  private def build(raw: Raw, runId: String): Either[Rejection, ParsedWork] =
    if !PublicationTypes(raw.recordType) then
      Left(Rejection("unsupported-record-type", raw.key, raw.recordType))
    else
      raw.key match
        case None      => Left(Rejection("missing-key", None, raw.recordType))
        case Some(key) =>
          raw.title match
            case None        => Left(Rejection("missing-title", Some(key), raw.recordType))
            case Some(title) =>
              StableIds.workId(IdNamespace.Dblp, key) match
                case Left(e)       => Left(Rejection("schema-failure", Some(key), e.toString))
                case Right(workId) =>
                  val prov  = Provenance(SourceType.Dblp, key, runId)
                  val venue = venueKey(key).flatMap(v =>
                    StableIds.namespacedId(IdKind.Venue, IdNamespace.Dblp, v).toOption
                  )
                  val work = WorkRecord(
                    SchemaVersion.Current,
                    workId,
                    title,
                    raw.year.flatMap(_.toIntOption),
                    venue,
                    externalIds(raw.ees),
                    Nil,
                    prov
                  )
                  val mentions = raw.authors.zipWithIndex.map { case ((name, orcid), pos) =>
                    AuthorMention(
                      SchemaVersion.Current,
                      StableIds.mentionId(workId, pos, SourceType.Dblp, key),
                      workId,
                      pos,
                      name,
                      normalizeName(name),
                      orcid.flatMap(o => IdFormat.normalizeOrcid(o).toOption),
                      None,
                      prov
                    )
                  }
                  val errors =
                    Validation.validate(work).left.toOption.toList.flatten ++
                      mentions.flatMap(m => Validation.validate(m).left.toOption.toList.flatten)
                  if errors.nonEmpty then
                    Left(Rejection("schema-failure", Some(key), errors.mkString("; ")))
                  else Right(ParsedWork(work, mentions, raw.recordType))
