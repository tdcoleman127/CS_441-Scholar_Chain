package edu.uic.cs441.scholarchain.hadoop

import java.nio.file.{Files, Path as JPath}
import org.apache.hadoop.conf.Configuration
import scala.jdk.CollectionConverters.*

/** Local-mode (LocalJobRunner) integration test: Job 1 then Job 6 on the DBLP samples. */
class LocalPipelineSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(300, "s")

  private def lines(dir: JPath, sub: String): Vector[String] =
    val d = dir.resolve(sub)
    if !Files.exists(d) then Vector.empty
    else
      Files.walk(d).iterator.asScala.toVector
        .filter(p => Files.isRegularFile(p) && p.getFileName.toString.startsWith("part"))
        .sortBy(_.toString)
        .flatMap(p => Files.readAllLines(p).asScala)

  private def hpath(p: JPath): String =
    val s = p.toAbsolutePath.toString.replace(java.io.File.separatorChar, '/')
    if s.startsWith("/") then "file://" + s else "file:///" + s

  private def conf(): Configuration =
    val c = new Configuration()
    c.set("mapreduce.framework.name", "local")
    c.set("fs.defaultFS", "file:///")
    c

  private def run(input: String, base: JPath) =
    val dtd = hpath(Fixtures.dblpDir.resolve("dblp.dtd"))
    val r1  = Pipeline.runExtract(conf(), input, dtd, hpath(base), Fixtures.runId, "sample", 2)
    val r2  = Pipeline.runTeams(conf(), r1.output, hpath(base), Fixtures.runId, "sample", 2, 2)
    (r1, r2)

  test("both jobs run locally, emit valid outputs, reject bad input, and are idempotent") {
    // LocalJobRunner on Windows needs winutils.exe and hadoop.dll (HADOOP_HOME); without them
    // Hadoop fails in NativeIO. Human decision 2026-10-27: fail there with a clear message, never
    // skip, so `sbt check` cannot go green without this test running.
    LocalPipelineSuite.windowsPrerequisiteProblem(
      System.getProperty("os.name"),
      sys.env.get("HADOOP_HOME")
    ).foreach(msg => fail(msg))
    val base  = Files.createTempDirectory("sc-hadoop-it")
    val base2 = Files.createTempDirectory("sc-hadoop-it2")
    try
      val input    = hpath(Fixtures.dblpDir) + "/dblp-*.xml"
      val (r1, r2) = run(input, base)
      assert(r1.ok && r2.ok)
      val c1 = r1.counters; val c2 = r2.counters
      // Values observed in phase 04 and counted by hand from the samples (10 + 3 records; rejects:
      // keyless article, 3 www, undeclared entity, truncated record).
      assertEquals(c1("INPUT"), 13L)
      assertEquals(c1("ACCEPTED"), 7L)
      assertEquals(c1("REJECTED"), 6L)
      assertEquals(c1("INPUT"), c1("ACCEPTED") + c1("REJECTED"))
      assertEquals(c1("MISSING_KEY"), 1L)
      assertEquals(c1("UNSUPPORTED_TYPE"), 3L)
      assertEquals(c1("SCHEMA_FAILURE"), 2L)
      // Job 6, threshold 2: teams of 3, 3, 3, 24, 1, 1, 0 authors. Pinned so that losing pair
      // emission in the reducer cannot pass with 0 lines == 0 counted (review round 4, F2).
      assertEquals(c2("TEAMS"), 7L)
      assertEquals(c2("AUTHOR_PAPERS"), 35L)
      assertEquals(c2("OVERSIZE_TEAM"), 4L)
      assertEquals(c2("EMITTED_PAIR"), 285L) // 3 + 3 + 3 + 24 * 23 / 2
      // Framework counters agree with the ScholarChain counters (Job 1: 13 records in, 7 works
      // shuffled, 7 groups; Job 6: 7 envelopes in, 7 groups, nothing on the lazy main output).
      val task = "org.apache.hadoop.mapreduce.TaskCounter:"
      val f1   = r1.framework; val f2 = r2.framework
      assertEquals(f1(task + "MAP_INPUT_RECORDS"), 13L)
      assertEquals(f1(task + "MAP_OUTPUT_RECORDS"), 7L)
      assertEquals(f1(task + "REDUCE_INPUT_GROUPS"), 7L)
      assertEquals(f1(task + "REDUCE_OUTPUT_RECORDS"), 7L)
      assertEquals(f2(task + "MAP_INPUT_RECORDS"), 7L)
      assertEquals(f2(task + "REDUCE_INPUT_GROUPS"), 7L)
      assertEquals(f2(task + "REDUCE_OUTPUT_RECORDS"), 0L)
      val out1 = JPath.of(r1.output.toUri)
      assertEquals(lines(out1, "rejects").size, 6)
      // RejectRecord v2 under rejects/v2: each reject names its input file (base name).
      assert(!Files.exists(out1.resolve("rejects/v1")))
      val rejects = lines(out1, "rejects/v2").map(upickle.default.read[RejectRecord](_))
      assertEquals(rejects.size, 6)
      assert(rejects.forall(_.rejectSchemaVersion == 2))
      assertEquals(
        rejects.map(r => (r.inputFile, r.inputOffset, r.reason)).sorted,
        Vector(
          ("dblp-malformed.xml", 643L, "undeclared-entity"),
          ("dblp-malformed.xml", 812L, "truncated-record"),
          ("dblp-sample.xml", 3859L, "missing-key"),
          ("dblp-sample.xml", 4031L, "unsupported-record-type"),
          ("dblp-sample.xml", 4285L, "unsupported-record-type"),
          ("dblp-sample.xml", 4454L, "unsupported-record-type")
        )
      )
      assertEquals(lines(out1, "works").size, 7)
      val out2     = JPath.of(r2.output.toUri)
      val mentions = lines(out1, "mentions").size
      assertEquals(lines(out2, "teams").size, 7)
      assertEquals(lines(out2, "authorpapers").size, mentions)
      assertEquals(lines(out2, "pairs").size, 285)
      assertEquals(lines(out2, "pairs").size.toLong, c2("EMITTED_PAIR"))
      // Rerun with the same run ID and snapshot reuses the completed output.
      val (s1, s2) = run(input, base)
      assert(s1.skipped && s2.skipped)
      assert(s1.framework.isEmpty && s2.framework.isEmpty)
      // A fresh run produces identical output (determinism).
      // A different output base must not change any record, including the rejects.
      val (t1, t2) = run(input, base2)
      assertEquals(lines(JPath.of(t2.output.toUri), "pairs").sorted, lines(out2, "pairs").sorted)
      assertEquals(
        lines(JPath.of(t1.output.toUri), "rejects").sorted,
        lines(out1, "rejects").sorted
      )
      println(s"IT counters job1=$c1 job6=$c2 out1=$out1 out2=$out2")
      println(s"IT framework job1=$f1 job6=$f2")
    finally Seq(base, base2).foreach(delete)
  }

  private def delete(d: JPath): Unit =
    if Files.exists(d) then
      Files.walk(d).iterator.asScala.toVector.sortBy(-_.getNameCount).foreach(Files.deleteIfExists)

  test("the Windows prerequisite check fails without HADOOP_HOME and never applies elsewhere") {
    val msg = LocalPipelineSuite.windowsPrerequisiteProblem("Windows 11", None)
    assert(
      msg.exists(m => Seq("HADOOP_HOME", "winutils.exe", "hadoop.dll").forall(m.contains)),
      msg
    )
    assertEquals(LocalPipelineSuite.windowsPrerequisiteProblem("Linux", None), None)
    assertEquals(LocalPipelineSuite.windowsPrerequisiteProblem("Mac OS X", None), None)
  }

object LocalPipelineSuite:

  /**
   * None when LocalJobRunner can run here. On Windows it needs HADOOP_HOME pointing at a Hadoop
   * install whose bin holds winutils.exe and hadoop.dll; otherwise the failure message says so.
   */
  def windowsPrerequisiteProblem(osName: String, hadoopHome: Option[String]): Option[String] =
    val needs =
      "LocalPipelineSuite runs Hadoop's LocalJobRunner, which on Windows needs HADOOP_HOME set to " +
        "a Hadoop install whose bin directory contains winutils.exe and hadoop.dll (and that bin " +
        "on PATH). This integration test fails instead of skipping (phase 04 human decision)."
    if !osName.toLowerCase.contains("win") then None
    else
      hadoopHome.filter(_.trim.nonEmpty) match
        case None    => Some(s"HADOOP_HOME is not set. $needs")
        case Some(h) =>
          val bin     = java.nio.file.Paths.get(h, "bin")
          val missing =
            Seq("winutils.exe", "hadoop.dll").filterNot(f => Files.exists(bin.resolve(f)))
          if missing.isEmpty then None
          else Some(s"HADOOP_HOME=$h but $bin lacks ${missing.mkString(" and ")}. $needs")
