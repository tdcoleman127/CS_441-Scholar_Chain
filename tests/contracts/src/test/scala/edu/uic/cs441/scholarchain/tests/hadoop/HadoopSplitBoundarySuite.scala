package edu.uic.cs441.scholarchain.tests.hadoop

import edu.uic.cs441.scholarchain.hadoop.*
import edu.uic.cs441.scholarchain.tests.hadoop.AdvFixtures.*
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

/**
 * Phase 11: DBLP XML split boundaries. A record belongs to the split where its start tag begins
 * (iron law 1), so for every possible split point the two readers together must yield each record
 * exactly once, including truncated and broken records, with no record lost or duplicated.
 */
class HadoopSplitBoundarySuite extends munit.FunSuite:

  private val doc: String =
    """<?xml version="1.0" encoding="ISO-8859-1"?>
      |<!DOCTYPE dblp SYSTEM "dblp.dtd">
      |<dblp>
      |""".stripMargin +
      dblpRecord("journals/tst/S1", Seq("M&uuml;ller A", "B C")) + "\n" +
      """<inproceedings key="conf/tst/S2" mdate="2020-01-01"><author>D E</author>""" +
      """<title>Broken, never closed</title><year>2020</year>""" + "\n" +
      dblpRecord("journals/tst/S3", Seq("F G"), tpe = "article") + "\n" +
      """<www key="homepages/x/S4" mdate="2020-01-01"><author>H I</author><title>Home</title></www>""" +
      "\n" + dblpRecord("journals/tst/S5", Seq("J K"), title = "Line\nbreak title") + "\n</dblp>\n"

  private val bytes = doc.getBytes(StandardCharsets.ISO_8859_1)

  private def scan(start: Int, end: Int): Vector[(Long, String)] =
    val in = new ByteArrayInputStream(bytes, start, bytes.length - start)
    val s  = new DblpRecordScanner(in, start.toLong, end.toLong, DblpRecordParser.AllRecordTypes)
    try Iterator.continually(s.next()).takeWhile(_.isDefined).flatten.toVector
    finally s.close()

  test("every split point yields each record exactly once, with identical text") {
    val whole = scan(0, bytes.length)
    assertEquals(whole.size, 5, whole.map(_._2.take(40)))
    (0 to bytes.length).foreach { s =>
      val joined = scan(0, s) ++ scan(s, bytes.length)
      assertEquals(joined, whole, s"split at byte $s")
    }
  }

  test("every pair of split points (three readers) yields each record exactly once") {
    val whole = scan(0, bytes.length)
    val step  = 7
    for
      a <- 0 to bytes.length by step
      b <- a to bytes.length by step
    do assertEquals(scan(0, a) ++ scan(a, b) ++ scan(b, bytes.length), whole, s"splits $a, $b")
  }

  test("the broken record is emitted under its own key and the next record still parses") {
    val recs   = scan(0, bytes.length).map(_._2)
    val parsed = recs.map(r => DblpRecordParser.parse(r, entities, runId))
    assertEquals(parsed.count(_.isRight), 3, parsed.map(_.left.map(_.reason)))
    val broken = parsed(1).left.toOption.get
    assertEquals(broken.reason, "xml-not-well-formed")
    assert(recs(1).contains("conf/tst/S2"), recs(1))
    assert(parsed(2).isRight, parsed(2))
  }

  test("CO-4: a start tag inside CDATA or a comment does not cut a well-formed record") {
    // Phase 00-09 backlog CO-4 (hadoop-engineer): DblpRecordScanner.readRecord
    // (hadoop DblpXmlInputFormat.scala:112-115) treats any '<article ' it meets as the next record,
    // even inside CDATA or a comment, so this well-formed record is cut in two: the real record is
    // rejected and a phantom record keyed "x" appears. Expected: one record, accepted.
    val cdata =
      """<article key="journals/tst/C1" mdate="2020-01-01"><author>A B</author>""" +
        """<title><![CDATA[On <article key="x"> elements]]></title><year>2020</year>""" +
        """<journal>J</journal></article>"""
    val comment =
      """<article key="journals/tst/C2" mdate="2020-01-01"><author>A B</author>""" +
        """<!-- <article key="y"> --><title>T</title><year>2020</year><journal>J</journal></article>"""
    List(cdata, comment).foreach { xml =>
      val recs = scanAll(xml)
      assertEquals(recs.size, 1, s"records: ${recs.map(_._2)}")
      assert(DblpRecordParser.parse(recs.head._2, entities, runId).isRight, recs.head._2)
    }
  }
