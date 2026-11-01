package edu.uic.cs441.scholarchain.hadoop

import java.nio.file.Files
import org.apache.hadoop.conf.Configuration
import scala.jdk.CollectionConverters.*

/**
 * SourcePipelineMain argument parsing and upstream validation, in process (no job runs). Same rules
 * as DblpPipelineMain (DriverArgsSuite): blank arguments, invalid snapshot or run ID, and
 * non-positive integers are rejected before any job is built.
 */
class SourceDriverArgsSuite extends munit.FunSuite:
  private val run   = Fixtures.runId
  private val fixed = Seq("file:///out", "snap", run, "2", "3", "500")
  private val ins  = Seq("in/openalex/openalex-works-sample.jsonl", "in/arxiv/arxiv-oai-sample.xml")
  private val good = fixed ++ ins

  test("a valid vector parses into every field, inputs in order") {
    assertEquals(
      SourcePipelineMain.parseArgs(good),
      Right(SourcePipelineMain.Args("file:///out", "snap", run, 2, 3, 500, ins.toVector))
    )
    assertEquals(
      SourcePipelineMain.parseArgs(fixed :+ ins.head).map(_.inputs),
      Right(Vector(ins.head))
    )
  }

  test("a vector with no source input, or too few fixed arguments, is rejected") {
    val res = SourcePipelineMain.parseArgs(fixed)
    assert(
      res.left.exists(m => m.startsWith("expected at least 7 arguments") && m.contains("got 6;")),
      res
    )
    assert(SourcePipelineMain.parseArgs(Seq.empty).isLeft)
    assert(SourcePipelineMain.parseArgs(good.take(3)).isLeft)
  }

  test("a blank (empty or whitespace-only) argument is rejected at any position") {
    for i <- good.indices; b <- Seq("", "   ", "\t") do
      val res = SourcePipelineMain.parseArgs(good.updated(i, b))
      assert(res.left.exists(_.contains(s"argument ${i + 1} is blank")), s"i=$i b='$b' $res")
  }

  test("invalid snapshot and run IDs are rejected, including path escapes") {
    for s <- Seq("..", "a/b", "../x", "-x", "a b", "a:b") do
      val res = SourcePipelineMain.parseArgs(good.updated(1, s))
      assert(res.left.exists(_.contains("snapshotId (argument 2)")), s"$s $res")
    for r <- Seq("snap", "run:v1:xyz", run + "0", run.toUpperCase) do
      val res = SourcePipelineMain.parseArgs(good.updated(2, r))
      assert(res.left.exists(_.contains("runId (argument 3)")), s"$r $res")
  }

  test("an argument vector in DblpPipelineMain order (shifted) is rejected") {
    // DblpPipelineMain order puts the output base third: snapshot slot holds a path.
    val shifted = Seq("in/a.jsonl", "file:///out", "snap", run, "2", "3", "500")
    assert(SourcePipelineMain.parseArgs(shifted).left.exists(_.contains("snapshotId")))
    // Snapshot and run ID swapped.
    assert(
      SourcePipelineMain.parseArgs(good.updated(1, run).updated(2, "snap"))
        .left.exists(_.contains("snapshotId"))
    )
  }

  test("non-positive or non-numeric reducers, exportPartitions, and maxBlock are rejected") {
    for
      (i, name) <- Seq(3 -> "reducers", 4 -> "exportPartitions", 5 -> "maxBlock");
      v         <- Seq("0", "-1", "x", "2.5", "99999999999")
    do
      val res = SourcePipelineMain.parseArgs(good.updated(i, v))
      assert(res.left.exists(_.contains(s"$name (argument ${i + 1})")), s"$name=$v $res")
  }

  test("a source input given twice is rejected (it would be counted as DUPLICATE records)") {
    val res = SourcePipelineMain.parseArgs(good :+ ins.head)
    assert(res.left.exists(_.contains("more than once")), res)
  }

  test("upstream Job 1 and Job 6 paths come from Pipeline.outputPath of the same run") {
    val a        = SourcePipelineMain.parseArgs(good).toOption.get
    val (j1, j6) = SourcePipelineMain.upstream(a)
    assertEquals(j1, Pipeline.outputPath("file:///out", run, "snap", "dblp-extract"))
    assertEquals(j6, Pipeline.outputPath("file:///out", run, "snap", "team-extract"))
  }

  test("checkUpstream fails until both upstream outputs have _SUCCESS") {
    val base = Files.createTempDirectory("sc-srcmain")
    try
      val uri      = base.toUri.toString.stripSuffix("/")
      val a        = SourcePipelineMain.parseArgs(uri +: good.tail).toOption.get
      val conf     = new Configuration(); conf.set("fs.defaultFS", "file:///")
      val (j1, j6) = SourcePipelineMain.upstream(a)
      def mark(p: org.apache.hadoop.fs.Path): Unit =
        val d = java.nio.file.Paths.get(p.toUri); Files.createDirectories(d)
        Files.createFile(d.resolve("_SUCCESS"))
      val none = SourcePipelineMain.checkUpstream(conf, a)
      assert(none.left.exists(m => m.contains("dblp-extract") && m.contains("team-extract")), none)
      mark(j1)
      val one = SourcePipelineMain.checkUpstream(conf, a)
      assert(one.left.exists(m => !m.contains("dblp-extract") && m.contains("team-extract")), one)
      mark(j6)
      assertEquals(SourcePipelineMain.checkUpstream(conf, a), Right((j1, j6)))
    finally
      Files.walk(base).iterator.asScala.toVector.sortBy(-_.getNameCount)
        .foreach(Files.deleteIfExists)
  }
