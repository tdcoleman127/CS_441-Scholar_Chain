package edu.uic.cs441.scholarchain.tests.hadoop

import edu.uic.cs441.scholarchain.core.model.*
import edu.uic.cs441.scholarchain.hadoop.*
import edu.uic.cs441.scholarchain.tests.hadoop.AdvFixtures.*
import java.nio.file.Files
import upickle.default.{read, write}

/**
 * Phase 04 adversarial tests, priorities 1 and 2: malformed and truncated DBLP XML, JSONL, CSV, and
 * arXiv XML become Rejections (and RejectRecord v2), never exceptions; Unicode, confusable, and
 * diacritic names; XML entities.
 */
class HadoopMalformedInputSuite extends munit.FunSuite:

  private val DblpReasons = Set(
    "undeclared-entity",
    "xml-not-well-formed",
    "truncated-record",
    "unsupported-record-type",
    "missing-key",
    "missing-title",
    "schema-failure"
  )
  private val SourceReasons = Set(
    "malformed-json",
    "xml-not-well-formed",
    "missing-key",
    "missing-title",
    "unsupported-record-type",
    "deleted-record",
    "unsupported-source",
    "schema-failure"
  )

  // ---- 1. malformed and truncated input ------------------------------------------------------

  test("DBLP: every proper prefix of a valid record is rejected with a documented reason") {
    val rec = dblpRecord(
      "journals/tst/P1",
      Seq("J&uuml;rgen M&ouml;ller", "Ada Lovelace"),
      ees = Seq("https://doi.org/10.1000/xyz")
    )
    parseOk(rec) // the whole record is valid
    for n <- 0 until rec.length do
      val r = DblpRecordParser.parse(rec.take(n), entities, runId)
      r match
        case Left(rej) =>
          assert(DblpReasons(rej.reason), s"prefix $n: undocumented reason ${rej.reason}")
        case Right(p) => fail(s"prefix of length $n was accepted: ${p.work.workId}")
  }

  test("DBLP: the scanner's truncation marker, bad char refs, and binary garbage reject") {
    val cases = Seq(
      DblpXmlInputFormat.TruncatedMarker + """<article key="journals/tst/T"><title>x""" ->
        "truncated-record",
      dblpRecord("journals/tst/C0", Seq("A&#0;B"))                         -> "xml-not-well-formed",
      dblpRecord("journals/tst/E1", Seq("A &nosuch; B"))                   -> "undeclared-entity",
      "\u0000\u0001\u00ff<<<>>>&&&"                                        -> "xml-not-well-formed",
      """<article key="journals/tst/X"><title>x</title></inproceedings>""" ->
        "xml-not-well-formed"
    )
    for (xml, reason) <- cases do
      val r = DblpRecordParser.parse(xml, entities, runId)
      assertEquals(r.left.map(_.reason), Left(reason), s"input: $xml")
  }

  test(
    "DBLP scanner: a record left open mid-file must not swallow the well-formed records after it"
  ) {
    // Record A is cut (no close tag) or closed with the wrong tag. B and C are well formed.
    // Expected: one record per start tag (A rejected; B and C accepted). The scanner reads A until
    // the next `</article>`, so B and C are merged into A's text and lost without their own
    // rejects or accepts.
    val xml =
      """<dblp>
        |<article key="journals/tst/A"><author>A B</author><title>Broken
        |<inproceedings key="conf/tst/B"><author>C D</author><title>Good B</title><year>2020</year></inproceedings>
        |<article key="journals/tst/C"><author>E F</author><title>Good C</title><year>2020</year></article>
        |</dblp>""".stripMargin
    val recs     = scanAll(xml)
    val accepted =
      recs.flatMap((_, t) => DblpRecordParser.parse(t, entities, runId).toOption)
        .map(_.work.provenance.sourceRecordId).toSet
    assertEquals(recs.size, 3, s"records cut: ${recs.map(_._1)}")
    assertEquals(accepted, Set("conf/tst/B", "journals/tst/C"))
  }

  test("JSONL: every proper prefix of an OpenAlex and a Crossref line rejects malformed-json") {
    for (src, line) <- Seq(SourceType.OpenAlex -> openAlexLine, SourceType.Crossref -> crossrefLine)
    do
      normalizeOk(src, line)
      for n <- 0 until line.length do
        val r = SourceNormalizers.normalize(src, line.take(n), runId)
        assertEquals(r.left.map(_.reason), Left("malformed-json"), s"$src prefix $n")
  }

  test(
    "JSONL: non-object JSON, wrong field types, and hostile values reject or accept, never throw"
  ) {
    val inputs = Seq(
      "",
      "   ",
      "[]",
      "null",
      "42",
      "\"str\"",
      "{}",
      """{"id":"https://openalex.org/W1","title":42}""",
      """{"id":"https://openalex.org/W1","title":"T","publication_year":"2020"}""",
      """{"id":"https://openalex.org/W1","title":"T","publication_year":1e30}""",
      """{"id":"https://openalex.org/W1","title":"T","authorships":"x"}""",
      """{"id":"https://openalex.org/W1","title":"T","authorships":[1,null,"x",{"author":7}]}""",
      """{"id":"https://openalex.org/W1","title":"T","abstract_inverted_index":{"a":[1e12,-5,"x"]}}""",
      """{"id":"not-an-id","title":"T"}""",
      """{"DOI":"10.1/x","type":"journal-article","title":[null,7]}""",
      """{"DOI":"10.1/x","type":"journal-article","title":["T"],"issued":{"date-parts":"x"}}""",
      """{"DOI":"10.1/x","type":"dataset","title":["T"]}"""
    )
    for src <- Seq(SourceType.OpenAlex, SourceType.Crossref); in <- inputs do
      val r =
        try SourceNormalizers.normalize(src, in, runId)
        catch case e: Throwable => fail(s"$src threw ${e.getClass.getName} on: $in")
      r.left.foreach(rej => assert(SourceReasons(rej.reason), s"$src undocumented ${rej.reason}"))
  }

  test("JSONL: a deeply nested value in an unused field does not crash the mapper") {
    // StackOverflowError is fatal (not NonFatal), so it would escape normalize and kill the task.
    val depth = 200000
    val line  = """{"id":"https://openalex.org/W1","title":"T","junk":""" +
      ("[" * depth) + ("]" * depth) + "}"
    val r =
      try SourceNormalizers.normalize(SourceType.OpenAlex, line, runId)
      catch case e: Throwable => fail(s"threw ${e.getClass.getName}")
    assert(r.isRight || r.left.exists(l => SourceReasons(l.reason)))
  }

  test("CSV: truncated, short, long, empty, and garbage OpenCitations rows reject, never throw") {
    normalizeOk(SourceType.OpenCitations, citationLine)
    // Proper prefixes that still have fewer than 7 columns (a cut inside the last two flag columns
    // keeps 7 columns and is a separate question, see the report).
    val prefixes = (0 until citationLine.length).map(citationLine.take).filter(_.count(_ == ',') <
      6)
    val inputs = prefixes ++ Seq(
      citationLine + ",extra",
      ",,,,,,",
      "oci:1-2,,,,,,",
      "oci:1-2,omid:br/1,doi:junk,,,,",
      "oci:1-2,omid:ra/1,omid:br/2,,,,",
      "\u0000,\u0001",
      "oci,citing,cited,creation,timespan,journal_sc,author_sc"
    )
    for in <- inputs do
      val r =
        try SourceNormalizers.normalize(SourceType.OpenCitations, in, runId)
        catch case e: Throwable => fail(s"threw ${e.getClass.getName} on: $in")
      assert(r.isLeft, s"accepted: $in")
      assert(SourceReasons(r.left.toOption.get.reason), s"undocumented reason on: $in")
  }

  test("arXiv: every proper prefix of a record rejects; deleted and id-less records reject") {
    normalizeOk(SourceType.Arxiv, arxivRecord)
    for n <- 0 until arxivRecord.length do
      val r = SourceNormalizers.normalize(SourceType.Arxiv, arxivRecord.take(n), runId)
      assert(r.isLeft, s"prefix $n accepted")
      assert(SourceReasons(r.left.toOption.get.reason), s"prefix $n: ${r.left.toOption.get}")
    val deleted = arxivRecord.replace("<header>", """<header status="deleted">""")
    assertEquals(
      SourceNormalizers.normalize(SourceType.Arxiv, deleted, runId).left.map(_.reason),
      Left("deleted-record")
    )
    val noId = arxivRecord.replace("<id>2001.00001</id>", "")
      .replace("oai:arXiv.org:2001.00001", "")
    assertEquals(
      SourceNormalizers.normalize(SourceType.Arxiv, noId, runId).left.map(_.reason),
      Left("missing-key")
    )
  }

  test("arXiv and DBLP: an external entity (XXE) never reads a local file") {
    val dir    = Files.createTempDirectory("sc-xxe")
    val secret = dir.resolve("secret.txt")
    // CO-3 (phase 00-09 backlog): the marker file and its folder are removed after the test.
    try
      Files.writeString(secret, "XXE-MARKER-7f3a")
      val uri = secret.toUri.toString
      val arx =
        s"""<!DOCTYPE record [<!ENTITY xxe SYSTEM "$uri">]>""" +
          arxivRecord.replace("<title>Graph Things</title>", "<title>&xxe;</title>")
      val ra = SourceNormalizers.normalize(SourceType.Arxiv, arx, runId)
      assert(!write(ra.toOption).contains("XXE-MARKER"), "arXiv leaked a local file")
      assert(ra.isLeft, s"arXiv accepted an undeclared external entity: $ra")
      val dblp =
        s"""<!DOCTYPE article [<!ENTITY xxe SYSTEM "$uri">]>""" +
          dblpRecord("journals/tst/X1", Seq("A B"), title = "&xxe;")
      val rd = DblpRecordParser.parse(dblp, entities, runId)
      assertEquals(rd.left.map(_.reason), Left("undeclared-entity"))
    finally
      Files.deleteIfExists(secret)
      Files.deleteIfExists(dir)
      ()
  }

  test("RejectRecord v2: schema 2, full provenance, bounded detail, Unicode round trip") {
    val rej = Rejection(
      "xml-not-well-formed",
      Some("journals/tst/\u00dc1"),
      "\u00fcn\u00efc\u00f6d\u00e9 " + ("x" * 5000)
    )
    val rr = DblpExtractMapper.rejectRecord(rej, "dblp.xml.gz", "snap-1", runId, 123L)
    assertEquals(rr.rejectSchemaVersion, 2)
    assertEquals(rr.sourceDataset, "dblp")
    assertEquals(rr.sourceRecordId, Some("journals/tst/\u00dc1"))
    assertEquals(rr.pipelineRunId, runId)
    assertEquals(rr.snapshotId, "snap-1")
    assertEquals(rr.inputFile, "dblp.xml.gz")
    assertEquals(rr.inputOffset, 123L)
    assert(rr.detail.length <= 500, s"detail length ${rr.detail.length}")
    assert(rr.detail.startsWith("\u00fcn\u00efc\u00f6d\u00e9"))
    assertEquals(read[RejectRecord](write(rr)), rr)
    assert(DblpExtractMapper.RejectBase.startsWith("rejects/v2/"))
  }

  test("Job 2 reject counters: every documented reason maps to exactly one split counter") {
    val expected = Map(
      "missing-key"             -> ScCounter.MISSING_KEY,
      "unsupported-record-type" -> ScCounter.UNSUPPORTED_TYPE,
      "deleted-record"          -> ScCounter.UNSUPPORTED_TYPE,
      "unsupported-source"      -> ScCounter.UNSUPPORTED_TYPE,
      "malformed-json"          -> ScCounter.SCHEMA_FAILURE,
      "xml-not-well-formed"     -> ScCounter.SCHEMA_FAILURE,
      "missing-title"           -> ScCounter.SCHEMA_FAILURE,
      "schema-failure"          -> ScCounter.SCHEMA_FAILURE
    )
    assertEquals(expected.keySet, SourceReasons)
    for (r, c) <- expected do assertEquals(SourceNormalizeMapper.counterFor(r), c, r)
  }

  test("a source the job does not normalize rejects unsupported-source, never throws") {
    for s <- Seq(SourceType.Dblp, SourceType.Orcid, SourceType.SemanticScholar) do
      assertEquals(
        SourceNormalizers.normalize(s, openAlexLine, runId).left.map(_.reason),
        Left("unsupported-source")
      )
  }

  // ---- 2. Unicode, confusables, diacritics, entities -----------------------------------------

  test("DBLP entities: named, decimal, hex, astral; raw name kept, normalized name folded") {
    val p = parseOk(
      dblpRecord(
        "journals/tst/U1",
        Seq(
          "J&uuml;rgen M&#246;ller",
          "Ren&#xE9;e &#x1F600;Smith",
          "\u674e \u5c0f\u9f8d",
          "A &amp; B"
        )
      )
    )
    val raws  = p.mentions.map(_.rawName)
    val norms = p.mentions.map(_.normalizedName)
    assertEquals(raws(0), "J\u00fcrgen M\u00f6ller")
    assertEquals(norms(0), "jurgen moller")
    assertEquals(raws(1), "Ren\u00e9e \ud83d\ude00Smith")
    assertEquals(norms(1), "renee smith")
    assertEquals(norms(2), "\u674e \u5c0f\u9f8d")
    assertEquals(raws(3), "A & B")
    assertEquals(p.mentions.map(_.authorPosition), List(0, 1, 2, 3))
  }

  test("normalization folds NFC/NFD, fullwidth, ligatures, case, and dotted capital I") {
    val n = DblpRecordParser.normalizeName
    assertEquals(n("M\u00fcller"), n("Mu\u0308ller"))
    assertEquals(n("\uff2d\u00fcller"), "muller") // fullwidth M
    assertEquals(n("\ufb01sher"), "fisher")       // fi ligature
    assertEquals(n("\u0130lker"), "ilker")        // Turkish dotted capital I
    assertEquals(n("  ADA\t\nLOVELACE  "), "ada lovelace")
    assertEquals(n("O'Brien-Smith"), "o brien smith")
  }

  test("confusables: a Cyrillic look-alike name never shares a block key with the Latin name") {
    // Folding scripts together would over-merge distinct people; keys must stay apart.
    val latin    = DblpRecordParser.normalizeName("Ivan Popov")
    val cyrillic =
      DblpRecordParser.normalizeName("Ivan P\u043e\u0440\u043ev") // \u043e \u0440 \u043e Cyrillic
    assertNotEquals(cyrillic, latin)
    assertNotEquals(Blocking.mentionKey(cyrillic), Blocking.mentionKey(latin))
  }

  test("invisible format characters (soft hyphen, zero-width space) do not split a name") {
    // U+00AD and U+200B are invisible (category Cf). Treating them as separators turns
    // "M\u00fcller" into "mul ler" and moves the mention to block m|ler|m, away from "M\u00fcller".
    val n = DblpRecordParser.normalizeName
    assertEquals(n("M\u00fcl\u00adler"), "muller")
    assertEquals(n("M\u00fcl\u200bler"), "muller")
    assertEquals(
      Blocking.mentionKey(n("Hans M\u00fcl\u00adler")),
      Blocking.mentionKey(n("Hans M\u00fcller"))
    )
  }

  test("an author whose name normalizes to nothing rejects the work with its key, not a crash") {
    val xml = dblpRecord("journals/tst/B1", Seq("Ada Lovelace", "&#8230;"))
    val r   = DblpRecordParser.parse(xml, entities, runId)
    r match
      case Left(rej) =>
        assertEquals(rej.sourceRecordId, Some("journals/tst/B1"))
        assert(DblpReasons(rej.reason))
      case Right(p) =>
        assert(p.mentions.forall(_.normalizedName.trim.nonEmpty), "blank normalized name emitted")
  }

  test("JSONL Unicode: escaped and raw non-ASCII names survive OpenAlex and Crossref") {
    val oa = openAlexLine.replace(
      "\"raw_author_name\":\"Ada Lovelace\"",
      "\"raw_author_name\":\"J\\u00fcrgen M\u00f6ller\""
    )
    val e = normalizeOk(SourceType.OpenAlex, oa).work.get.envelope.mentions.head
    assertEquals(e.rawName, "J\u00fcrgen M\u00f6ller")
    assertEquals(e.normalizedName, "jurgen moller")
    val cr = crossrefLine.replace("\"given\":\"Ada\"", "\"given\":\"Ren\u00e9e\"")
    val c  = normalizeOk(SourceType.Crossref, cr).work.get.envelope.mentions.head
    assertEquals(c.normalizedName, "renee lovelace")
  }

  test("arXiv LaTeX accents and raw UTF-8 in one record normalize to the same key") {
    val latex =
      arxivRecord.replace("<keyname>Lovelace</keyname>", "<keyname>M\\\"{u}ller</keyname>")
    val utf = arxivRecord.replace("<keyname>Lovelace</keyname>", "<keyname>M\u00fcller</keyname>")
    val a   = normalizeOk(SourceType.Arxiv, latex).work.get.envelope.mentions.head
    val b   = normalizeOk(SourceType.Arxiv, utf).work.get.envelope.mentions.head
    assertEquals(a.normalizedName, "ada muller")
    assertEquals(a.normalizedName, b.normalizedName)
    assertEquals(b.rawName, "Ada M\u00fcller")
  }
