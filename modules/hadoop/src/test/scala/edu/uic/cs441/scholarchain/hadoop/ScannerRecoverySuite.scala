package edu.uic.cs441.scholarchain.hadoop

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

/**
 * Phase 04 closeout repairs: a broken record (unclosed or closed with the wrong tag) ends at the
 * next top-level record start tag instead of swallowing the records after it; and invisible format
 * characters (Unicode Cf) are removed from names rather than splitting them.
 */
class ScannerRecoverySuite extends munit.FunSuite:
  import Fixtures.*

  private def doc(brokenA: String): String =
    s"""<dblp>
       |$brokenA
       |<inproceedings key="conf/tst/B"><author>C D</author><title>Good B</title><year>2020</year></inproceedings>
       |<article key="journals/tst/C"><author>E F</author><title>Good C</title><year>2020</year></article>
       |</dblp>""".stripMargin

  private val unclosed  = """<article key="journals/tst/A"><author>A B</author><title>Broken"""
  private val wrongTail =
    """<article key="journals/tst/A"><author>A B</author><title>T</title></inproceedings>"""

  for (label, a) <- Seq("unclosed" -> unclosed, "wrong close tag" -> wrongTail) do
    test(s"a $label record mid-file is its own reject; the records after it are accepted") {
      val xml  = doc(a)
      val recs = scanAll(xml, Int.MaxValue / 2)
      assertEquals(recs.size, 3, recs.map(_._2).mkString("\n---\n"))
      // Offsets point at each record's start tag.
      for (off, text) <- recs do assert(xml.substring(off.toInt).startsWith(text), s"offset $off")
      val results = recs.map((_, t) => DblpRecordParser.parse(t, entities, runId))
      results.head match
        case Left(rej) =>
          assertEquals(rej.reason, "xml-not-well-formed")
          assertEquals(rej.sourceRecordId, Some("journals/tst/A"))
        case Right(p) => fail(s"broken record accepted: ${p.work.workId}")
      assertEquals(
        results.tail.flatMap(_.toOption).map(_.work.provenance.sourceRecordId),
        Vector("conf/tst/B", "journals/tst/C")
      )
      assert(!recs.head._2.startsWith(DblpXmlInputFormat.TruncatedMarker))
    }

    test(s"a $label record keeps identical boundaries for every split size (no dup, no split)") {
      val xml   = doc(a)
      val whole = scanAll(xml, Int.MaxValue / 2)
      for size <- 1 to xml.length + 1 do assertEquals(scanAll(xml, size), whole, s"split=$size")
    }

  test("a record still open at end of input keeps the truncated marker") {
    val xml  = "<dblp>\n" + """<article key="journals/tst/Z"><title>cut"""
    val recs = scanAll(xml, Int.MaxValue / 2)
    assertEquals(recs.size, 1)
    assert(recs.head._2.startsWith(DblpXmlInputFormat.TruncatedMarker))
    assertEquals(
      DblpRecordParser.parse(recs.head._2, entities, runId).left.map(_.reason),
      Left("truncated-record")
    )
  }

  test("arXiv scanner (types = record) gets the same recovery for an unclosed record") {
    val xml =
      """<ListRecords>
        |<record><header><identifier>oai:arXiv.org:1</identifier></header><metadata>broken
        |<record><header><identifier>oai:arXiv.org:2</identifier></header></record>
        |<record><header><identifier>oai:arXiv.org:3</identifier></header></record>
        |</ListRecords>""".stripMargin
    val bytes            = xml.getBytes(StandardCharsets.ISO_8859_1)
    def scan(split: Int) = (0 until bytes.length by split).toVector.flatMap { start =>
      val in = new ByteArrayInputStream(bytes); in.skip(start.toLong)
      val s  =
        new DblpRecordScanner(
          in,
          start.toLong,
          math.min(bytes.length, start + split).toLong,
          Set("record")
        )
      Iterator.continually(s.next()).takeWhile(_.isDefined).flatten.toVector
    }
    val whole = scan(Int.MaxValue / 2)
    assertEquals(whole.size, 3)
    assert(whole(1)._2.contains("oai:arXiv.org:2") && whole(1)._2.endsWith("</record>"))
    assert(whole(2)._2.contains("oai:arXiv.org:3") && whole(2)._2.endsWith("</record>"))
    assert(!whole(0)._2.contains("oai:arXiv.org:2"))
    for size <- Seq(1, 5, 17, 64) do assertEquals(scan(size), whole, s"split=$size")
  }

  test("normalizeName removes invisible format characters instead of splitting the name") {
    val n = DblpRecordParser.normalizeName
    assertEquals(n("Mül­ler"), "muller")  // soft hyphen
    assertEquals(n("Mül​ler"), "muller")  // zero-width space
    assertEquals(n("﻿Mül⁠ler"), "muller") // BOM, word joiner
    assertEquals(n("Hans​ Müller"), "hans muller")
    assertEquals(
      Blocking.mentionKey(n("Hans Mül­ler")),
      Blocking.mentionKey(n("Hans Müller"))
    )
  }
