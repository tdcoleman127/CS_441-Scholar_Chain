package edu.uic.cs441.scholarchain.hadoop

import edu.uic.cs441.scholarchain.core.model.*
import edu.uic.cs441.scholarchain.core.validation.Validation
import edu.uic.cs441.scholarchain.core.validation.Validators.given
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.jdk.CollectionConverters.*

/** Job 2 normalizers over the committed external samples (datasets/samples). */
class SourceNormalizersSuite extends munit.FunSuite:
  private val samples            = Fixtures.root.resolve("datasets/samples")
  private def lines(rel: String) =
    Files.readAllLines(samples.resolve(rel), StandardCharsets.UTF_8).asScala.toVector
      .filter(_.nonEmpty)
  private def run                                                      = Fixtures.runId
  private def ok(e: Either[Rejection, SourceEnvelope]): NormalizedWork =
    e match
      case Right(env) => env.work.getOrElse(fail("expected a work"))
      case Left(r)    => fail(s"rejected: $r")
  private def valid(nw: NormalizedWork): Unit =
    assert(Validation.validate(nw.envelope.work).isRight)
    nw.envelope.mentions.foreach(m => assert(Validation.validate(m).isRight, m))

  test("source is chosen by file-name prefix, then parent directory") {
    assertEquals(
      SourceNormalizers.sourceFor("openalex-works-sample.jsonl", "x"),
      Some(SourceType.OpenAlex)
    )
    assertEquals(
      SourceNormalizers.sourceFor("part-0001.jsonl", "crossref"),
      Some(SourceType.Crossref)
    )
    assertEquals(
      SourceNormalizers.sourceFor("opencitations-index.csv", ""),
      Some(SourceType.OpenCitations)
    )
    assertEquals(SourceNormalizers.sourceFor("dblp.xml", "dblp"), None)
  }

  test("OpenAlex: IDs, ORCID, affiliations, institutions, author IDs; bad lines reject") {
    val ls = lines("openalex/openalex-works-sample.jsonl")
    val w1 = ok(SourceNormalizers.openAlex(ls(0), run))
    valid(w1)
    val work = w1.envelope.work
    assertEquals(work.workId, "work:openalex:W9990000001")
    assertEquals(work.year, Some(2024))
    assertEquals(work.provenance, Provenance(SourceType.OpenAlex, "W9990000001", run))
    assert(work.externalIds.contains(ExternalId(IdNamespace.Doi, "10.5555/sc441.0001")))
    assert(work.externalIds.contains(ExternalId(IdNamespace.OpenAlex, "W9990000001")))
    val ms = w1.envelope.mentions
    assertEquals(ms.map(_.authorPosition), List(0, 1, 2))
    assertEquals(ms.head.orcid, Some("0000-0002-1825-0097"))
    assertEquals(ms.head.affiliationText, Some("Example University"))
    assertEquals(ms(1).normalizedName, "jurgen muller")
    assertEquals(w1.mentionExtras.head.sourceAuthorId, Some("openalex:A9990000001"))
    assertEquals(w1.mentionExtras.head.institutionIds, List("institution:openalex:I9990000001"))
    assertEquals(w1.extra.referencedWorkIds, List("work:openalex:W9990000002"))
    // Markup is stripped from titles.
    assertEquals(
      ok(SourceNormalizers.openAlex(ls(1), run)).envelope.work.title,
      "Disambiguating Homonymous Authors at Scale: H2 Heuristics"
    )
    assertEquals(SourceNormalizers.openAlex(ls(2), run).left.map(_.reason), Left("missing-title"))
    assertEquals(SourceNormalizers.openAlex(ls(3), run).left.map(_.reason), Left("malformed-json"))
    assertEquals(
      SourceNormalizers.openAlex(
        """{"id":"https://openalex.org/W1","title":"T",
        |"publication_year":"twenty"}""".stripMargin.replace("\n", ""),
        run
      ).left.map(_.reason),
      Left("schema-failure")
    )
    assertEquals(
      SourceNormalizers.openAlex("""{"title":"T"}""", run).left.map(_.reason),
      Left("missing-key")
    )
  }

  test("OpenAlex abstract_inverted_index and topics are kept") {
    val j = """{"id":"W5","title":"T","abstract_inverted_index":{"world":[1],"hello":[0]},""" +
      """"topics":[{"id":"https://openalex.org/T77","display_name":"Graphs"}]}"""
    val nw = ok(SourceNormalizers.openAlex(j, run))
    assertEquals(nw.extra.abstractText, Some("hello world"))
    assertEquals(nw.envelope.work.topicIds, List("topic:openalex:T77"))
    assertEquals(nw.topics.map(t => t.topicId -> t.name), List("topic:openalex:T77" -> "Graphs"))
  }

  test("topics keep each ID paired with its label; entries missing an id or a label (F2)") {
    // Reviewer failure input: source order B, A; IDs sort T1, T2. Plus a label without an id, an
    // id without a label, and an empty entry.
    val j = """{"id":"W6","title":"T","topics":[""" +
      """{"id":"https://openalex.org/T2","display_name":"B"},""" +
      """{"id":"https://openalex.org/T1","display_name":"A"},""" +
      """{"display_name":"No Id"},{"id":"https://openalex.org/T3"},{}]}"""
    val nw      = ok(SourceNormalizers.openAlex(j, run))
    val noIdTid = SourceNormalizers.labelTopicId(SourceType.OpenAlex, "No Id")
    assertEquals(
      nw.topics.map(t => t.topicId -> t.name).toMap,
      Map("topic:openalex:T1" -> "A", "topic:openalex:T2" -> "B", noIdTid -> "No Id")
    )
    assertEquals(nw.topics.map(_.topicId), nw.topics.map(_.topicId).sorted)
    // Every topic ID stays on the work, including T3 (no label, so no named Topic record).
    assertEquals(
      nw.envelope.work.topicIds,
      List("topic:openalex:T1", "topic:openalex:T2", "topic:openalex:T3", noIdTid).sorted
    )
    nw.topics.foreach(t => assert(Validation.validate(t).isRight, t))
    assert(nw.topics.forall(_.provenance == nw.envelope.work.provenance))
  }

  test("OpenAlex institutions keep their display names as core Institution records (F2)") {
    val nw = ok(SourceNormalizers.openAlex(lines("openalex/openalex-works-sample.jsonl")(0), run))
    // Authors 0 and 2 share I9990000001: one record per work.
    assertEquals(
      nw.institutions.map(i => i.institutionId -> i.name),
      List("institution:openalex:I9990000001" -> "Example University")
    )
    assertEquals(nw.institutions.head.provenance, nw.envelope.work.provenance)
    nw.institutions.foreach(i => assert(Validation.validate(i).isRight, i))
    val j = """{"id":"W7","title":"T","authorships":[{"raw_author_name":"A B","institutions":[""" +
      """{"id":"https://openalex.org/I5"},{"display_name":"Unresolved U"}]}]}"""
    val nw2 = ok(SourceNormalizers.openAlex(j, run))
    // ID without a name: kept on the mention, no named record. Name without an ID: no record.
    assertEquals(nw2.mentionExtras.head.institutionIds, List("institution:openalex:I5"))
    assertEquals(nw2.institutions, Nil)
  }

  test(
    "Crossref: DOI lowercased, authors, ORCID, affiliation; dataset type and truncation reject"
  ) {
    val ls = lines("crossref/crossref-works-sample.jsonl")
    val w2 = ok(SourceNormalizers.crossref(ls(1), run))
    valid(w2)
    assertEquals(w2.envelope.work.workId, "work:doi:10.5555/sc441.0002")
    assertEquals(w2.envelope.work.year, Some(2023))
    assertEquals(w2.envelope.mentions(2).orcid, None) // 0000-0002-1825-0098 fails the checksum
    assertEquals(w2.mentionExtras(2).invalidOrcid, Some("https://orcid.org/0000-0002-1825-0098"))
    assertEquals(w2.extra.venueName, Some("Journal of Sample Data"))
    val w3 = ok(SourceNormalizers.crossref(ls(2), run))
    assertEquals(w3.envelope.mentions.size, 24)
    assertEquals(
      SourceNormalizers.crossref(ls(3), run).left.map(_.reason),
      Left("unsupported-record-type")
    )
    assertEquals(SourceNormalizers.crossref(ls(4), run).left.map(_.reason), Left("malformed-json"))
  }

  test("arXiv: whole records, LaTeX names, DOI, categories, abstract; deleted header rejects") {
    val xml = new String(
      Files.readAllBytes(samples.resolve("arxiv/arxiv-oai-sample.xml")),
      StandardCharsets.UTF_8
    )
    val records = "(?s)<record>.*?</record>".r.findAllIn(xml).toVector
    assertEquals(records.size, 3)
    val a1 = ok(SourceNormalizers.arxiv(records(0), run))
    valid(a1)
    assertEquals(a1.envelope.work.workId, "work:arxiv:2401.99901")
    assertEquals(
      a1.envelope.work.title,
      "Provenance-Preserving Coauthorship Graphs for Scholarly Linking"
    )
    assertEquals(a1.envelope.work.year, Some(2024))
    assert(a1.envelope.work.externalIds.contains(ExternalId(IdNamespace.Doi, "10.5555/sc441.0001")))
    assertEquals(
      a1.envelope.mentions.map(_.rawName),
      List("Josiah Carberry", "Jurgen Muller", "Ada Sample")
    )
    assertEquals(a1.envelope.mentions.head.affiliationText, Some("Example University"))
    assertEquals(a1.topics.map(_.name).sorted, List("cs.DB", "cs.DL"))
    assertEquals(a1.topics.map(_.topicId), a1.envelope.work.topicIds)
    assertEquals(
      a1.topics.find(_.name == "cs.DL").map(_.topicId),
      Some(SourceNormalizers.labelTopicId(SourceType.Arxiv, "cs.DL"))
    )
    assertEquals(a1.envelope.work.topicIds.size, 2)
    assert(a1.extra.abstractText.exists(_.startsWith("Synthetic abstract")))
    val a2 = ok(SourceNormalizers.arxiv(records(1), run))
    assertEquals(a2.envelope.work.workId, "work:arxiv:cs/9912999")
    assertEquals(a2.envelope.mentions.map(_.rawName), List("A. Sample Jr"))
    assertEquals(
      SourceNormalizers.arxiv(records(2), run).left.map(_.reason),
      Left("deleted-record")
    )
    assertEquals(
      SourceNormalizers.arxiv("<record><oops></record>", run).left.map(_.reason),
      Left("xml-not-well-formed")
    )
  }

  test("OpenCitations: OCI, OMID/DOI/PMID endpoints; header detected; short rows reject") {
    val ls = lines("opencitations/opencitations-index-sample.csv")
    assert(SourceNormalizers.isHeader(SourceType.OpenCitations, ls(0)))
    val c = SourceNormalizers.openCitations(ls(2), run).toOption.flatMap(_.citation).get
    assertEquals(c.oci, "0699900000003-0699900000001")
    assertEquals(c.citingWorkId, "work:omid:br/0699900000003")
    assertEquals(c.citedWorkId, "work:omid:br/0699900000001")
    assert(c.citedIds.contains(ExternalId(IdNamespace.Pmid, "99900001")))
    assertEquals(c.journalSelfCitation, Some(true))
    assertEquals(c.provenance, Provenance(SourceType.OpenCitations, c.oci, run))
    assertEquals(
      SourceNormalizers.openCitations(ls(5), run).left.map(_.reason),
      Left("schema-failure")
    )
    assertEquals(
      SourceNormalizers.openCitations("x,y", run).left.map(_.reason),
      Left("missing-key")
    )
  }

  test("normalization is deterministic") {
    val l = lines("openalex/openalex-works-sample.jsonl").head
    assertEquals(SourceNormalizers.openAlex(l, run), SourceNormalizers.openAlex(l, run))
  }
