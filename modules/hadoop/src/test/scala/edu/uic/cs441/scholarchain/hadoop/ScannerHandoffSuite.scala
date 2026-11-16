package edu.uic.cs441.scholarchain.hadoop

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{Path => HPath}
import org.apache.hadoop.mapreduce.TaskAttemptID
import org.apache.hadoop.mapreduce.lib.input.FileSplit
import org.apache.hadoop.mapreduce.task.TaskAttemptContextImpl

/**
 * Phase 11 repair-03 F1 (review-02): the resync rule must never make a record vanish. Before the
 * fix, a split that started at s > 0 followed within SectionLimit by well-formed records and then a
 * stray `]]>` (malformed XML) skipped to that `]]>`; the previous reader stopped at s, so every
 * record start tag in between was emitted by no reader, with no reject and no counter. The hand-off
 * rule (DblpRecordScanner class comment) gives those start tags to the previous reader.
 */
class ScannerHandoffSuite extends munit.FunSuite:
  import Fixtures.*

  private def scan(b: Array[Byte], start: Int, end: Int): Vector[(Long, String)] =
    val in = new ByteArrayInputStream(b, start, b.length - start)
    val s  = new DblpRecordScanner(in, start.toLong, end.toLong, DblpRecordParser.AllRecordTypes)
    try Iterator.continually(s.next()).takeWhile(_.isDefined).flatten.toVector
    finally s.close()

  private def bytesOf(xml: String) = xml.getBytes(StandardCharsets.ISO_8859_1)

  private def bySize(b: Array[Byte], size: Int): Vector[(Long, String)] =
    (0 until b.length by size).toVector.flatMap(a => scan(b, a, (a + size).min(b.length)))

  /** Accepted work keys and (reason, offset) rejects, as the Job 1 mapper would split them. */
  private def outcomes(recs: Vector[(Long, String)]): (Vector[String], Vector[(String, Long)]) =
    val parsed = recs.map((off, t) => off -> DblpRecordParser.parse(t, entities, runId))
    (
      parsed.flatMap(_._2.toOption.map(_.work.workId)).sorted,
      parsed.flatMap((off, r) => r.left.toOption.map(_.reason -> off)).sorted
    )

  private def rec(kind: String, key: String, title: String): String =
    val venue = if kind == "article" then "<journal>J</journal>" else "<booktitle>B</booktitle>"
    s"""<$kind key="$key" mdate="2020-01-01"><author>A $key</author><title>$title</title>""" +
      s"""<year>2020</year>$venue</$kind>"""

  private val good   = (1 to 4).map(i => rec("article", s"journals/tst/G$i", s"Good $i")).toVector
  private val stray  = rec("inproceedings", "conf/tst/Stray", "Literal ]]> in text")
  private val after  = rec("article", "journals/tst/After", "After")
  private val before = rec("article", "journals/tst/Before", "Before")

  /** The review-02 F1 scenario: well-formed records, then a record with a stray `]]>`. */
  private val strayDoc: String =
    "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n<dblp>\n" + before + "\n" +
      good.mkString("\n") + "\n" + stray + "\n" + after + "\n</dblp>\n"

  test("F1 precondition: one reader emits every record; only the stray-]]> record is rejected") {
    val b = bytesOf(strayDoc)
    val w = scan(b, 0, b.length)
    assertEquals(w.map(_._2), (before +: good) ++ Vector(stray, after))
    val (acc, rej) = outcomes(w)
    assertEquals(acc.size, 6, acc)
    assertEquals(rej.map(_._2), Vector(strayDoc.indexOf(stray).toLong), rej)
  }

  test("F1: a stray ']]>' after a split start loses no record, at every split point") {
    val b = bytesOf(strayDoc)
    val w = scan(b, 0, b.length)
    (0 to b.length).foreach { s =>
      assertEquals(scan(b, 0, s) ++ scan(b, s, b.length), w, s"two readers at $s")
      assertEquals(scan(b, 0, s) ++ scan(b, s, s) ++ scan(b, s, b.length), w, s"empty split at $s")
    }
  }

  test("F1: every pair of split points (three readers) yields each record exactly once") {
    val b = bytesOf(strayDoc)
    val w = scan(b, 0, b.length)
    for
      x <- 0 to b.length by 7
      y <- x to b.length by 11
    do assertEquals(scan(b, 0, x) ++ scan(b, x, y) ++ scan(b, y, b.length), w, s"at $x, $y")
  }

  test("F1 property: emitted, accepted, and rejected records are the same for every split size") {
    val b        = bytesOf(strayDoc)
    val w        = scan(b, 0, b.length)
    val expected = outcomes(w)
    (1 to b.length).foreach { size =>
      val got = bySize(b, size)
      assertEquals(got, w, s"split size $size: emitted records")
      assertEquals(outcomes(got), expected, s"split size $size: accepted and rejected")
    }
  }

  test("F1: records spread over almost SectionLimit before the stray ']]>' are all kept") {
    val pad     = "p" * 2000
    val many    = (1 to 30).map(i => rec("article", s"journals/tst/M$i", s"Many $i $pad"))
    val xml     = "<dblp>\n" + many.mkString("\n") + "\n" + stray + "\n" + after + "\n</dblp>\n"
    val b       = bytesOf(xml)
    val w       = scan(b, 0, b.length)
    val strayAt = xml.indexOf("]]>")
    assert(strayAt - xml.indexOf(many.head) < DblpRecordScanner.SectionLimit, "precondition")
    assertEquals(w.size, 32)
    for s <- (0 to b.length by 1009) ++ many.map(xml.indexOf(_)) ++ Seq(strayAt, strayAt + 2) do
      assertEquals(scan(b, 0, s) ++ scan(b, s, b.length), w, s"at $s")
    Seq(97, 4096, 20011).foreach(size => assertEquals(bySize(b, size), w, s"split size $size"))
  }

  test("F1-adjacent: a split inside a comment that holds '<![CDATA[' hides no later record") {
    // Well-formed XML. The reader that starts inside the comment cannot see the "<!--", so it
    // reads "<![CDATA[" as a section that runs to the "]]>" in C3. Before the fix C1..C3 vanished.
    val a = """<article key="journals/tst/CA" mdate="2020-01-01"><author>A B</author>""" +
      """<!-- old: <![CDATA[ --><title>T</title><year>2020</year><journal>J</journal></article>"""
    val c1  = rec("article", "journals/tst/C1", "One")
    val c2  = rec("inproceedings", "conf/tst/C2", "Two")
    val c3  = rec("article", "journals/tst/C3", "<![CDATA[x]]>")
    val xml = "<dblp>\n" + a + "\n" + c1 + "\n" + c2 + "\n" + c3 + "\n" + after + "\n</dblp>\n"
    val b   = bytesOf(xml)
    val w   = scan(b, 0, b.length)
    assertEquals(w.map(_._2), Vector(a, c1, c2, c3, after))
    assertEquals(outcomes(w)._1.size, 5)
    (0 to b.length).foreach(s => assertEquals(scan(b, 0, s) ++ scan(b, s, b.length), w, s"at $s"))
    // Documented limit: when a third boundary falls inside the hand-off window, a record can be
    // emitted twice (Job 1 deduplicates and counts DUPLICATE). It is never lost, never a phantom.
    var dupSizes = 0
    (1 to b.length).foreach { size =>
      val got = bySize(b, size)
      assertEquals(got.distinct, w, s"split size $size: no record lost or invented")
      if got.size != w.size then dupSizes += 1
    }
    assert(dupSizes < b.length / 4, s"duplicates at $dupSizes of ${b.length} split sizes")
    // A split longer than the window (comment to C3's start tag) holds no third boundary in it.
    (xml.indexOf(c3) + 1 to b.length).foreach(size =>
      assertEquals(bySize(b, size), w, s"split size $size")
    )
  }

  test("a stray ']]>' farther than SectionLimit after the split start does not skip") {
    val pad = "q" * (DblpRecordScanner.SectionLimit + 50)
    val big = rec("article", "journals/tst/Big", pad)
    val xml = "<dblp>\n" + good(0) + "\n" + big + "\n" + stray + "\n</dblp>\n"
    val b   = bytesOf(xml)
    val w   = scan(b, 0, b.length)
    assertEquals(w.map(_._2), Vector(good(0), big, stray))
    for s <- Seq(1, xml.indexOf(good(0)), xml.indexOf(big), xml.indexOf(big) + 1, b.length / 2) do
      assertEquals(scan(b, 0, s) ++ scan(b, s, b.length), w, s"at $s")
  }

  test("F1 with the real DblpXmlRecordReader over FileSplits: same records at any split size") {
    val dir  = Files.createTempDirectory("sc-f1-handoff")
    val file = dir.resolve("dblp-stray.xml")
    val b    = bytesOf(strayDoc)
    Files.write(file, b)
    val w    = scan(b, 0, b.length)
    val conf = new Configuration()
    val path = new HPath(file.toUri)
    for size <- Seq(1, 13, 64, 200, 331, b.length / 2 + 1, b.length) do
      val got = (0 until b.length by size).toVector.flatMap { start =>
        val rr = new DblpXmlRecordReader
        rr.initialize(
          new FileSplit(path, start.toLong, ((start + size).min(b.length) - start).toLong, null),
          new TaskAttemptContextImpl(conf, new TaskAttemptID())
        )
        try
          Iterator.continually(rr.nextKeyValue()).takeWhile(identity).map { _ =>
            val v = rr.getCurrentValue
            rr.getCurrentKey.get -> new String(v.getBytes, 0, v.getLength, StandardCharsets.UTF_8)
          }.toVector
        finally rr.close()
      }
      assertEquals(got, w, s"split size $size")
    Files.delete(file); Files.delete(dir)
  }
