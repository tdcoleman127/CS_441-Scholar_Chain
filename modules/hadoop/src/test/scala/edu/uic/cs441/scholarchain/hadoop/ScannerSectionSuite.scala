package edu.uic.cs441.scholarchain.hadoop

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

/**
 * Phase 11 repair-01, CO-4: a record start tag inside a CDATA section or an XML comment is not a
 * record start, inside a record or between records, and the split-boundary guarantee (each record
 * read exactly once, by the reader whose split contains its start tag) still holds when a section
 * straddles a split boundary or a split starts inside a section. See DblpRecordScanner for the rule
 * and its documented limits.
 */
class ScannerSectionSuite extends munit.FunSuite:
  import Fixtures.*

  private def scan(bytes: Array[Byte], start: Int, end: Int): Vector[(Long, String)] =
    val in = new ByteArrayInputStream(bytes, start, bytes.length - start)
    val s  = new DblpRecordScanner(in, start.toLong, end.toLong, DblpRecordParser.AllRecordTypes)
    try Iterator.continually(s.next()).takeWhile(_.isDefined).flatten.toVector
    finally s.close()

  private def bytesOf(xml: String) = xml.getBytes(StandardCharsets.ISO_8859_1)
  private def whole(xml: String)   = { val b = bytesOf(xml); scan(b, 0, b.length) }
  private def keyOf(text: String)  = """key="([^"]*)"""".r.findFirstMatchIn(text).map(_.group(1))

  private val k1 =
    """<article key="journals/tst/K1" mdate="2020-01-01"><author>A B</author>""" +
      """<title><![CDATA[On <article key="x"> and <inproceedings key="z"> --> tags]]></title>""" +
      """<year>2020</year><journal>J</journal></article>"""
  private val k2 =
    """<inproceedings key="conf/tst/K2" mdate="2020-01-01"><author>C D</author>""" +
      """<title>Plain</title><year>2021</year><booktitle>B</booktitle></inproceedings>"""
  private val k3 =
    """<article key="journals/tst/K3" mdate="2020-01-01"><author>E F</author>""" +
      """<!-- an ordinary comment ]]> --><title>T3</title><year>2022</year><journal>J</journal>""" +
      """</article>"""
  private val k4 = // broken: never closed; the next start tag ends it
    """<article key="journals/tst/K4" mdate="2020-01-01"><author>G H</author>""" +
      """<title><![CDATA[x]]></title>"""
  private val k5 =
    """<article key="journals/tst/K5" mdate="2020-01-01"><author>J K</author>""" +
      """<title>Last</title><year>2023</year><journal>J</journal></article>"""

  /** CDATA with start tags inside and between records, a comment holding "]]>", a broken record. */
  private val doc: String =
    "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n<!-- header comment -->\n<dblp>\n" +
      k1 + "\n<![CDATA[ between records: <article key=\"w\"> ]]>\n<!-- note ]]> -->\n" +
      k2 + "\n" + k3 + "\n" + k4 + "\n" + k5 + "\n</dblp>\n"

  test("CO-4: start tags inside CDATA or comments, in or between records, are not records") {
    val recs = whole(doc)
    assertEquals(recs.map(r => keyOf(r._2)).flatten, Vector(k1, k2, k3, k4, k5).flatMap(keyOf))
    // A broken record ends just before the next start tag, so k4 keeps the newline after it.
    assertEquals(recs.map(_._2), Vector(k1, k2, k3, k4 + "\n", k5))
    for (off, text) <- recs do assert(doc.substring(off.toInt).startsWith(text), s"offset $off")
    val parsed = recs.map(r => DblpRecordParser.parse(r._2, entities, runId))
    assertEquals(parsed.map(_.isRight), Vector(true, true, true, false, true))
    assertEquals(parsed(3).left.map(_.reason), Left("xml-not-well-formed"))
    assertEquals(
      parsed(0).toOption.get.work.title,
      """On <article key="x"> and <inproceedings key="z"> --> tags"""
    )
  }

  test("CO-4: every split point (two readers) yields each record exactly once") {
    val b = bytesOf(doc)
    val w = scan(b, 0, b.length)
    (0 to b.length).foreach(s => assertEquals(scan(b, 0, s) ++ scan(b, s, b.length), w, s"at $s"))
  }

  test("CO-4: every pair of split points (three readers) yields each record exactly once") {
    val b = bytesOf(doc)
    val w = scan(b, 0, b.length)
    for
      x <- 0 to b.length by 3
      y <- x to b.length by 5
    do assertEquals(scan(b, 0, x) ++ scan(b, x, y) ++ scan(b, y, b.length), w, s"at $x, $y")
  }

  test("CO-4: a split that starts inside a CDATA section skips to its end (resync rule)") {
    val b       = bytesOf(doc)
    val inside  = doc.indexOf("""<article key="x">""") // inside k1's CDATA, after its opener
    val recs    = scan(b, inside, b.length)
    assertEquals(recs.map(_._2), Vector(k2, k3, k4 + "\n", k5)) // no phantom "x"/"z"; k1 not ours
    val between = doc.indexOf("""<article key="w">""")
    assertEquals(scan(b, between, b.length).map(_._2), Vector(k2, k3, k4 + "\n", k5))
  }

  test("CO-4: a ']]>' inside a comment after the split start does not trigger a skip") {
    // The first "]]>" after the split start is inside k3's comment, so the reader stays in normal
    // markup and still reads k2 and k3, which begin before that "]]>".
    val b = bytesOf(doc)
    val s = doc.indexOf(k2) - 1
    assertEquals(scan(b, s, b.length).map(_._2), Vector(k2, k3, k4 + "\n", k5))
  }

  test("CO-4 documented limit: a split inside a comment that holds a start tag (no loss)") {
    val c2 =
      """<article key="journals/tst/C2" mdate="2020-01-01"><author>A B</author>""" +
        """<!-- <article key="y"> --><title>T</title><year>2020</year><journal>J</journal></article>"""
    val xml   = "<dblp>\n" + c2 + "\n" + k5 + "\n</dblp>\n"
    val b     = bytesOf(xml)
    val w     = scan(b, 0, b.length)
    val open  = xml.indexOf("<!--")
    val inner = xml.indexOf("""<article key="y">""")
    assertEquals(w.map(_._2), Vector(c2, k5))
    (0 to b.length).foreach { s =>
      val joined = scan(b, 0, s) ++ scan(b, s, b.length)
      if s <= open || s > inner then assertEquals(joined, w, s"at $s")
      else
        // The reader cannot tell it starts inside a comment: one phantom "y", nothing lost.
        assertEquals(joined.diff(w).map(r => keyOf(r._2)), Vector(Some("y")), s"at $s")
        assertEquals(w.diff(joined), Vector.empty, s"at $s")
    }
  }

  test("unterminated CDATA or comment openers do not swallow the records after them") {
    val a   = """<article key="journals/tst/A"><author>A B</author><title><![CDATA[never closed"""
    val c   = """<article key="journals/tst/C"><author>E F</author><title>C</title></article>"""
    val xml = "<dblp>\n" + a + "\n" + k2 + "\n" + c + "\n<!-- unterminated\n" + k5 + "\n</dblp>\n"
    val b   = bytesOf(xml)
    val w   = scan(b, 0, b.length)
    assertEquals(
      w.map(r => keyOf(r._2)).flatten,
      Vector("journals/tst/A", "conf/tst/K2", "journals/tst/C", "journals/tst/K5")
    )
    assertEquals(
      DblpRecordParser.parse(w.head._2, entities, runId).left.map(_.reason),
      Left("xml-not-well-formed")
    )
    (0 to b.length).foreach(s => assertEquals(scan(b, 0, s) ++ scan(b, s, b.length), w, s"at $s"))
  }

  test("an opener whose closer is beyond SectionLimit is plain text (bounded section)") {
    val a   = """<article key="journals/tst/A"><author>A B</author><title><![CDATA[far"""
    val pad = "y" * (DblpRecordScanner.SectionLimit + 100)
    val big = s"""<www key="homepages/x/Big"><author>Z</author><title>$pad</title></www>"""
    val xml = "<dblp>\n" + a + "\n" + k2 + "\n" + big + "\n<!-- ]]> -->\n" + k5 + "\n</dblp>\n"
    val b   = bytesOf(xml)
    val w   = scan(b, 0, b.length)
    assertEquals(
      w.map(r => keyOf(r._2)).flatten,
      Vector("journals/tst/A", "conf/tst/K2", "homepages/x/Big", "journals/tst/K5")
    )
    for s <- (0 to b.length by 4099) ++ Seq(
        xml.indexOf("far"),
        xml.indexOf("<!-- ]]>") - 1,
        xml.indexOf("<!-- ]]>") + 4,
        xml.indexOf("<!-- ]]>") + 6
      )
    do
      assertEquals(scan(b, 0, s) ++ scan(b, s, b.length), w, s"at $s")
  }

  test("the arXiv scanner (types = record) ignores a record tag inside CDATA too") {
    val xml =
      """<ListRecords><record><header><identifier>oai:arXiv.org:1</identifier></header>""" +
        """<metadata><![CDATA[<record> not a record]]></metadata></record>""" +
        """<record><header><identifier>oai:arXiv.org:2</identifier></header></record></ListRecords>"""
    val b                    = bytesOf(xml)
    def sc(st: Int, en: Int) =
      val s = new DblpRecordScanner(
        new ByteArrayInputStream(b, st, b.length - st),
        st.toLong,
        en.toLong,
        Set("record")
      )
      Iterator.continually(s.next()).takeWhile(_.isDefined).flatten.toVector
    val w = sc(0, b.length)
    assertEquals(w.size, 2, w.map(_._2))
    assert(w.head._2.endsWith("</metadata></record>"), w.head._2)
    (0 to b.length).foreach(s => assertEquals(sc(0, s) ++ sc(s, b.length), w, s"at $s"))
  }
