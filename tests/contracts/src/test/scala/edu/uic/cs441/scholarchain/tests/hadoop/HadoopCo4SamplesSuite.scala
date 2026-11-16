package edu.uic.cs441.scholarchain.tests.hadoop

import edu.uic.cs441.scholarchain.hadoop.*
import edu.uic.cs441.scholarchain.tests.hadoop.AdvFixtures.runId
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets.ISO_8859_1
import java.nio.file.{Files, Path, Paths}
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path as HPath
import org.apache.hadoop.io.{LongWritable, Text}
import org.apache.hadoop.mapreduce.{RecordReader, TaskAttemptID}
import org.apache.hadoop.mapreduce.lib.input.FileSplit
import org.apache.hadoop.mapreduce.task.TaskAttemptContextImpl

/**
 * Phase 11 repair-01 rerun, target 6 (CO-4) on the committed dataset samples.
 *
 * For datasets/samples/dblp/dblp-sample.xml, dblp-malformed.xml, arxiv/arxiv-oai-sample.xml, and
 * DBLP variants with CDATA sections and comments injected inside and between records (start tags
 * and close tags inside them), every split point (two readers) and many split sizes (N readers)
 * must yield exactly the records a single reader yields: none lost, none duplicated, identical
 * text. A subset of split sizes runs through the real Hadoop record readers on FileSplits (seek
 * path). Unterminated openers must not swallow the records after them.
 */
class HadoopCo4SamplesSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(600, "s")

  private val root: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(p => Files.exists(p.resolve("datasets/samples/dblp/dblp-sample.xml")))
      .getOrElse(fail("datasets/samples not found above the working directory"))

  private val dblpDir   = root.resolve("datasets/samples/dblp")
  private val sample    = Files.readAllBytes(dblpDir.resolve("dblp-sample.xml"))
  private val malformed = Files.readAllBytes(dblpDir.resolve("dblp-malformed.xml"))
  private val arxiv     =
    Files.readAllBytes(root.resolve("datasets/samples/arxiv/arxiv-oai-sample.xml"))
  private val entities =
    DblpEntities.fromDtd(Files.readString(dblpDir.resolve("dblp.dtd"), ISO_8859_1))
  private val dblpTypes  = DblpRecordParser.AllRecordTypes
  private val arxivTypes = Set("record")

  private def scan(
      bytes: Array[Byte],
      types: Set[String],
      start: Int,
      end: Int
  ): Vector[(Long, String)] =
    val in = new ByteArrayInputStream(bytes, start, bytes.length - start)
    val s  = new DblpRecordScanner(in, start.toLong, end.toLong, types)
    try Iterator.continually(s.next()).takeWhile(_.isDefined).flatten.toVector
    finally s.close()

  private def whole(bytes: Array[Byte], types: Set[String]) = scan(bytes, types, 0, bytes.length)

  private val splitSizes: List[Int] = (1 to 48).toList ++
    List(61, 97, 128, 251, 509, 1024, 1499, 2048, 3001)

  /** Every split point (two readers) and every listed split size (N readers). */
  private def checkSplits(
      name: String,
      bytes: Array[Byte],
      types: Set[String]
  ): Vector[(Long, String)] =
    val all = whole(bytes, types)
    (0 to bytes.length).foreach { s =>
      val got = scan(bytes, types, 0, s) ++ scan(bytes, types, s, bytes.length)
      if got != all then
        fail(s"$name: split at byte $s: ${got.map(_._1)} vs ${all.map(_._1)}")
    }
    splitSizes.filter(_ <= bytes.length).foreach { size =>
      val got = (0 until bytes.length by size).flatMap(a =>
        scan(bytes, types, a, (a + size).min(bytes.length))
      )
      if got != all then fail(s"$name: split size $size: ${got.map(_._1)} vs ${all.map(_._1)}")
    }
    all

  /** The key attribute of the record's own start tag only (one sample record has no key). */
  private def keyOf(text: String): Option[String] =
    val t        = text.stripPrefix(DblpXmlInputFormat.TruncatedMarker)
    val startTag = t.take(t.indexOf('>') + 1)
    """\bkey="([^"]*)"""".r.findFirstMatchIn(startTag).map(_.group(1))

  /** Record identity for comparisons: the offset-free start tag (key may be absent). */
  private def startTagOf(text: String): String =
    val t = text.stripPrefix(DblpXmlInputFormat.TruncatedMarker)
    t.take(t.indexOf('>') + 1)

  /** (key, accepted) per record, through the real DBLP parser. */
  private def outcomes(recs: Vector[(Long, String)]): Vector[(Option[String], Boolean)] =
    recs.map((_, t) => keyOf(t) -> DblpRecordParser.parse(t, entities, runId).isRight)

  private def insertAfterEach(s: String, marker: String, insert: String): String =
    s.split(java.util.regex.Pattern.quote(marker), -1).mkString(marker + insert)

  private def insertAfterNth(s: String, marker: String, n: Int, insert: String): String =
    var at = -1
    (1 to n).foreach(_ => at = s.indexOf(marker, at + 1))
    assert(at >= 0, s"no occurrence $n of $marker")
    s.substring(0, at + marker.length) + insert + s.substring(at + marker.length)

  private lazy val baseline = outcomes(whole(sample, dblpTypes))

  // ---- Tests -----------------------------------------------------------------------------------

  test("committed samples: every split point and many split sizes yield each record exactly once") {
    val d = checkSplits("dblp-sample.xml", sample, dblpTypes)
    val m = checkSplits("dblp-malformed.xml", malformed, dblpTypes)
    val a = checkSplits("arxiv-oai-sample.xml", arxiv, arxivTypes)
    assert(d.size >= 5 && m.size == 3 && a.nonEmpty, (d.size, m.size, a.size))
    val tags = whole(sample, dblpTypes).map(r => startTagOf(r._2))
    assertEquals(tags.distinct.size, tags.size, s"a sample record start tag repeats: $tags")
    assertEquals(baseline.size, 10, baseline)
  }

  test("CO-4 on the DBLP sample: CDATA and comments in and between records, at every split") {
    val text = new String(sample, ISO_8859_1)
    val v1   =
      insertAfterEach(text, "<title>", """<![CDATA[<article key="fake-in-title"> </article> ]]>""")
    val v2 = insertAfterEach(v1, "</title>", "<!-- remark ]]> </inproceedings> </article> -->")
    val v3 = List("</article>", "</inproceedings>", "</proceedings>", "</www>").foldLeft(v2)(
      (s, c) =>
        insertAfterEach(
          s,
          c + "\n",
          """<![CDATA[ <article key="ghost"> </article> ]]>""" + "\n<!-- between -->\n"
        )
    )
    val bytes = v3.getBytes(ISO_8859_1)
    val recs  = checkSplits("dblp-sample+sections", bytes, dblpTypes)
    // Same records as the original sample: no phantom (fake-in-title, ghost) and none cut.
    assertEquals(recs.map(r => keyOf(r._2)), baseline.map(_._1))
    assertEquals(
      recs.map(r => startTagOf(r._2)),
      whole(sample, dblpTypes).map(r => startTagOf(r._2))
    )
    assert(!recs.exists(r => keyOf(r._2).exists(k => k == "fake-in-title" || k == "ghost")), recs)
    // Comments are ignored by XML; the parse outcome of every record is unchanged.
    assertEquals(outcomes(recs).map(_._2), baseline.map(_._2), outcomes(recs))
  }

  test("CO-4: an unterminated CDATA or comment opener does not swallow the records after it") {
    val text = new String(sample, ISO_8859_1)
    assert(!text.contains("]]>"), "precondition: no CDATA closer in the sample")
    List(
      "unterminated CDATA"   -> insertAfterNth(text, "<title>", 2, "<![CDATA[ never closed "),
      "unterminated comment" -> insertAfterNth(text, "<title>", 2, "<!-- never closed ")
    ).foreach { (name, v) =>
      val idx = v.indexOf("never closed")
      assert(v.indexOf("-->", idx) < 0, s"$name: precondition: no comment closer after the opener")
      val recs = checkSplits(name, v.getBytes(ISO_8859_1), dblpTypes)
      assertEquals(recs.map(r => keyOf(r._2)), baseline.map(_._1), name)
      assertEquals(
        recs.map(r => startTagOf(r._2)),
        whole(sample, dblpTypes).map(r => startTagOf(r._2)),
        name
      )
      val got = outcomes(recs)
      // The damaged second record is rejected; every other record keeps its outcome.
      assertEquals(got(1)._2, false, s"$name: ${recs(1)._2}")
      assertEquals(got.patch(1, Nil, 1), baseline.patch(1, Nil, 1), name)
    }
  }

  // ---- The real Hadoop record readers over FileSplits -------------------------------------------

  private def readSplits(file: Path, size: Long, arxivReader: Boolean): Vector[(Long, String)] =
    val conf = new Configuration()
    val len  = Files.size(file)
    val path = new HPath(file.toUri)
    (0L until len by size).toVector.flatMap { start =>
      val rr: RecordReader[LongWritable, Text] =
        if arxivReader then new ArxivRecordReader else new DblpXmlRecordReader
      rr.initialize(
        new FileSplit(path, start, (start + size).min(len) - start, Array.empty[String]),
        new TaskAttemptContextImpl(conf, new TaskAttemptID())
      )
      try
        Iterator.continually(rr.nextKeyValue()).takeWhile(identity).map { _ =>
          val bytes =
            java.util.Arrays.copyOf(rr.getCurrentValue.getBytes, rr.getCurrentValue.getLength)
          rr.getCurrentKey.get -> new String(
            bytes,
            if arxivReader then ISO_8859_1 else java.nio.charset.StandardCharsets.UTF_8
          )
        }.toVector
      finally rr.close()
    }

  test("real DblpXmlRecordReader and ArxivRecordReader on FileSplits: same records at any size") {
    val tmp  = Files.createTempDirectory("sc-rerun-co4")
    val text = new String(sample, ISO_8859_1)
    val sect = insertAfterEach(
      insertAfterEach(text, "<title>", """<![CDATA[<article key="fake-in-title"> ]]>"""),
      "</title>",
      "<!-- remark ]]> </article> -->"
    )
    val files = List(
      ("dblp-sample.xml", sample, false),
      ("dblp-malformed.xml", malformed, false),
      ("dblp-sample-sections.xml", sect.getBytes(ISO_8859_1), false),
      ("arxiv-oai-sample.xml", arxiv, true)
    )
    files.foreach { (name, bytes, isArxiv) =>
      val f = tmp.resolve(name)
      Files.write(f, bytes)
      val expected = whole(bytes, if isArxiv then arxivTypes else dblpTypes)
      List(7L, 97L, 512L, 1499L, bytes.length / 3L + 1, bytes.length.toLong).foreach { size =>
        val got = readSplits(f, size, isArxiv)
        assertEquals(got.map(_._1), expected.map(_._1), s"$name split size $size: offsets")
        assertEquals(got.map(_._2), expected.map(_._2), s"$name split size $size: record text")
      }
    }
  }
