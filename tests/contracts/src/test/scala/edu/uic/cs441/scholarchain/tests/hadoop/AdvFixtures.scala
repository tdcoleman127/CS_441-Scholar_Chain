package edu.uic.cs441.scholarchain.tests.hadoop

import edu.uic.cs441.scholarchain.core.ids.StableIds
import edu.uic.cs441.scholarchain.core.model.*
import edu.uic.cs441.scholarchain.hadoop.*
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

/** Small synthetic fixtures for the phase 04 Hadoop adversarial suites. No files, no network. */
object AdvFixtures:
  val runId: String =
    StableIds.runId("adv-test", "test", "cfg", Seq(RunInput(SourceType.Dblp, "adv", "x")), 1)

  /** A tiny DTD in DBLP's `<!ENTITY name "&#NNN;">` form. */
  val entities: Map[String, String] = DblpEntities.fromDtd(
    """<!ENTITY uuml "&#252;">
      |<!ENTITY eacute "&#233;">
      |<!ENTITY ouml "&#246;">""".stripMargin
  )

  def dblpRecord(
      key: String,
      authors: Seq[String],
      title: String = "A Synthetic Title",
      year: String = "2020",
      ees: Seq[String] = Nil,
      tpe: String = "article"
  ): String =
    val a = authors.map(n => s"<author>$n</author>").mkString
    val e = ees.map(x => s"<ee>$x</ee>").mkString
    s"""<$tpe key="$key" mdate="2020-01-01">$a<title>$title</title><year>$year</year><journal>J</journal>$e</$tpe>"""

  def parseOk(xml: String): ParsedWork =
    DblpRecordParser.parse(xml, entities, runId) match
      case Right(p) => p
      case Left(r)  => throw new AssertionError(s"expected accept, got $r")

  def envelope(p: ParsedWork): WorkEnvelope = WorkEnvelope(p.recordType, p.work, p.mentions)

  val openAlexLine: String =
    """{"id":"https://openalex.org/W100","title":"Graph Things","publication_year":2020,""" +
      """"doi":"https://doi.org/10.1000/Abc","type":"article",""" +
      """"authorships":[""" +
      """{"author":{"id":"https://openalex.org/A1","display_name":"Ada Lovelace","orcid":"https://orcid.org/0000-0002-1825-0097"},"raw_author_name":"Ada Lovelace","institutions":[]},""" +
      """{"author":{"id":"https://openalex.org/A2","display_name":"Charles Babbage","orcid":null},"raw_author_name":"Charles Babbage","institutions":[]},""" +
      """{"author":{"id":"https://openalex.org/A3","display_name":"Mary Somerville","orcid":null},"raw_author_name":"Mary Somerville","institutions":[]}""" +
      """]}"""

  val crossrefLine: String =
    """{"DOI":"10.1000/ABC","type":"journal-article","title":["Graph Things"],""" +
      """"author":[{"given":"Ada","family":"Lovelace","ORCID":"http://orcid.org/0000-0002-1825-0097"}],""" +
      """"issued":{"date-parts":[[2020]]}}"""

  val arxivRecord: String =
    """<record><header><identifier>oai:arXiv.org:2001.00001</identifier></header><metadata>""" +
      """<arXiv><id>2001.00001</id><created>2020-01-01</created><authors>""" +
      """<author><keyname>Lovelace</keyname><forenames>Ada</forenames></author>""" +
      """</authors><title>Graph Things</title><categories>cs.DL</categories>""" +
      """<abstract>An abstract.</abstract><doi>10.1000/abc</doi></arXiv></metadata></record>"""

  val citationLine: String =
    "oci:061-062,omid:br/061 doi:10.1000/abc,omid:br/062,2020-01,P1Y,no,no"

  def normalizeOk(source: SourceType, text: String): SourceEnvelope =
    SourceNormalizers.normalize(source, text, runId) match
      case Right(e) => e
      case Left(r)  => throw new AssertionError(s"expected accept for $source, got $r")

  def scanAll(xml: String): Vector[(Long, String)] =
    val bytes = xml.getBytes(StandardCharsets.ISO_8859_1)
    val s     = new DblpRecordScanner(
      new ByteArrayInputStream(bytes),
      0L,
      bytes.length.toLong,
      DblpRecordParser.AllRecordTypes
    )
    try Iterator.continually(s.next()).takeWhile(_.isDefined).flatten.toVector
    finally s.close()

  /** Recording sink usable for both TeamReducer and Blocking. */
  final class Recorder extends TeamReducer.Sink with Blocking.Sink:
    val counts = scala.collection.mutable.Map.empty[ScCounter, Long]
    val out    = scala.collection.mutable.ArrayBuffer.empty[(String, String)]
    def count(c: ScCounter, n: Long): Unit       = counts(c) = counts.getOrElse(c, 0L) + n
    def emit(output: String, json: String): Unit = out += output -> json
    def lines(o: String): Vector[String]         = out.filter(_._1 == o).map(_._2).toVector
    def get(c: ScCounter): Long                  = counts.getOrElse(c, 0L)
