package edu.uic.cs441.scholarchain.hadoop

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path as JPath}
import java.util.zip.GZIPOutputStream
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat
import org.apache.hadoop.mapreduce.task.TaskAttemptContextImpl
import org.apache.hadoop.mapreduce.{Job, TaskAttemptID}
import scala.jdk.CollectionConverters.*

class RepairSuite extends munit.FunSuite:

  final private class Probe extends DblpXmlInputFormat:
    def splitable(p: Path): Boolean = isSplitable(Job.getInstance(new Configuration()), p)

  /** Fresh temp directory per test, deleted afterwards. */
  private val tmp = FunFixture[JPath](
    setup = _ => Files.createTempDirectory("sc-hadoop-gz"),
    teardown = d =>
      Files.walk(d).iterator.asScala.toVector.sortBy(-_.getNameCount).foreach(Files.deleteIfExists)
  )

  private def conf(): Configuration =
    val c = new Configuration()
    c.set("fs.defaultFS", "file:///")
    // Small max split so the plain (raw-seek) branch is exercised with several splits.
    c.setLong(FileInputFormat.SPLIT_MAXSIZE, 512L)
    c

  private def gzip(bytes: Array[Byte], to: JPath): JPath =
    val gz = new GZIPOutputStream(Files.newOutputStream(to))
    try gz.write(bytes)
    finally gz.close()
    to

  /**
   * Drives the production input format: getSplits, createRecordReader, initialize, nextKeyValue.
   */
  private def readAll(file: JPath): (Int, Vector[(Long, String)]) =
    val c   = conf()
    val job = Job.getInstance(c)
    FileInputFormat.addInputPath(job, new Path(file.toUri))
    val fmt    = new DblpXmlInputFormat
    val splits = fmt.getSplits(job).asScala.toVector
    val recs   = splits.flatMap { split =>
      val ctx    = new TaskAttemptContextImpl(job.getConfiguration, new TaskAttemptID())
      val reader = fmt.createRecordReader(split, ctx)
      try
        reader.initialize(split, ctx)
        Iterator
          .continually(reader.nextKeyValue())
          .takeWhile(identity)
          .map(_ => reader.getCurrentKey.get -> reader.getCurrentValue.toString)
          .toVector
      finally reader.close()
    }
    (splits.size, recs)

  private val sampleBytes =
    Fixtures.read("dblp-sample.xml").getBytes(StandardCharsets.ISO_8859_1)

  test("F1: gzip input is not splittable (any suffix case), plain XML is") {
    val fmt = new Probe
    assert(!fmt.splitable(new Path("file:///x/dblp.xml.gz")))
    assert(!fmt.splitable(new Path("file:///x/dblp.xml.GZ")))
    assert(fmt.splitable(new Path("file:///x/dblp.xml")))
  }

  tmp.test("F1: real reader on a .gz file gives 1 split and the same records as the plain file") {
    dir =>
      val plainFile = dir.resolve("dblp-sample.xml")
      Files.write(plainFile, sampleBytes)
      val (plainSplits, plain) = readAll(plainFile)
      assert(plainSplits > 1, s"plain file should be split, got $plainSplits")
      assertEquals(plain.size, 10)
      for name <- Seq("dblp-sample.xml.gz", "dblp-sample-upper.xml.GZ") do
        val gzFile = gzip(sampleBytes, dir.resolve(name))
        assert(Files.size(gzFile) < sampleBytes.length.toLong)
        val (splits, recs) = readAll(gzFile)
        assertEquals(splits, 1, name)
        assertEquals(recs, plain, name)
  }

  tmp.test("F1: truncated tail in a .gz file comes back as the truncated marker, not dropped") {
    dir =>
      val (_, plain) = readAll {
        val f = dir.resolve("plain.xml"); Files.write(f, sampleBytes); f
      }
      val (lastOff, lastText) = plain.last
      val cut                 = (lastOff + lastText.length / 2).toInt
      val truncated           = java.util.Arrays.copyOf(sampleBytes, cut)
      val (splits, recs)      = readAll(gzip(truncated, dir.resolve("dblp-truncated.xml.gz")))
      assertEquals(splits, 1)
      assertEquals(recs.size, plain.size)
      assertEquals(recs.init, plain.init)
      val (off, text) = recs.last
      assertEquals(off, lastOff)
      assert(text.startsWith(DblpXmlInputFormat.TruncatedMarker), text)
      assertEquals(
        text.stripPrefix(DblpXmlInputFormat.TruncatedMarker),
        new String(truncated.drop(lastOff.toInt), StandardCharsets.ISO_8859_1)
      )
  }

  test("F2: snapshotId is validated before building the output path") {
    for bad <- Seq("../../other", "a/b", "..", "", ".hidden", "a..b", "x y", "a\\b") do
      intercept[IllegalArgumentException](Pipeline.outputPath("/out", Fixtures.runId, bad, "j"))
    val ok  = Pipeline.outputPath("/out", Fixtures.runId, "dblp-2026-10_01.v1", "j")
    val seg = Fixtures.runId.replace(':', '-')
    assertEquals(ok.toString, s"/out/run=$seg/snapshot=dblp-2026-10_01.v1/j")
  }

  test("F2: runId is validated in outputPath, so runTeams cannot escape the run partition") {
    for bad <- Seq("x/../../y", "run:v1:ab", "", "run:v1:../../y", null) do
      intercept[IllegalArgumentException](Pipeline.outputPath("/out", bad, "snap", "j"))
    // runTeams builds its path through outputPath before touching any file system or job.
    intercept[IllegalArgumentException](
      Pipeline.runTeams(new Configuration(), new Path("/in"), "/out", "x/../../y", "snap", 2, 50)
    )
  }
