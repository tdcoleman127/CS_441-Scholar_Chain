package edu.uic.cs441.scholarchain.hadoop

import java.nio.file.{Files, Path as JPath}
import org.apache.hadoop.conf.Configuration
import edu.uic.cs441.scholarchain.core.model.{Institution, Topic}
import org.apache.hadoop.fs.Path
import scala.jdk.CollectionConverters.*
import upickle.default.read

/**
 * Local-mode (LocalJobRunner) integration test of part B: Job 2 on the external samples, Job 3 and
 * Jobs 4+5 over the Job 1 and Job 2 outputs, Job 6, then Job 7 export. Fails (never skips) on
 * Windows without HADOOP_HOME, like LocalPipelineSuite.
 */
class SourcePipelineSuite extends munit.FunSuite:
  // Pinned sample counters that changed in part B repair 1 (see the assertions for why).
  private val MissingKeyPinned = 4L
  private val ExportedPinned   = 759L

  override val munitTimeout = scala.concurrent.duration.Duration(600, "s")

  private val samples = Fixtures.root.resolve("datasets/samples")

  private def hpath(p: JPath): String =
    val s = p.toAbsolutePath.toString.replace(java.io.File.separatorChar, '/')
    if s.startsWith("/") then "file://" + s else "file:///" + s

  private def conf(): Configuration =
    val c = new Configuration()
    c.set("mapreduce.framework.name", "local"); c.set("fs.defaultFS", "file:///")
    c

  private def lines(out: Path, sub: String): Vector[String] =
    val d = JPath.of(out.toUri).resolve(sub)
    if !Files.exists(d) then Vector.empty
    else
      Files.walk(d).iterator.asScala.toVector
        .filter(p => Files.isRegularFile(p) && p.getFileName.toString.startsWith("part"))
        .sortBy(_.toString).flatMap(p => Files.readAllLines(p).asScala)

  private def partFiles(out: Path, sub: String): Vector[String] =
    val d = JPath.of(out.toUri).resolve(sub)
    if !Files.exists(d) then Vector.empty
    else
      Files.list(d).iterator.asScala.toVector.map(_.getFileName.toString)
        .filter(_.startsWith("part")).sorted

  private val sources = Seq(
    "openalex/openalex-works-sample.jsonl",
    "crossref/crossref-works-sample.jsonl",
    "arxiv/arxiv-oai-sample.xml",
    "opencitations/opencitations-index-sample.csv"
  )

  private def runAll(base: JPath, maxBlock: Int) =
    val b   = hpath(base); val rid = Fixtures.runId; val snap = "sample"
    val dtd = hpath(Fixtures.dblpDir.resolve("dblp.dtd"))
    val j1  = Pipeline.runExtract(
      conf(),
      hpath(Fixtures.dblpDir) + "/dblp-*.xml",
      dtd,
      b,
      rid,
      snap,
      2
    )
    val j2 = SourcePipeline.runNormalize(
      conf(),
      sources.map(s => hpath(samples.resolve(s))),
      b,
      rid,
      snap,
      2
    )
    val env = Seq(j1.output, j2.output)
    val j3  = SourcePipeline.runExactIds(conf(), env, b, rid, snap, 2)
    val j4  = SourcePipeline.runBlocking(conf(), env, b, rid, snap, 2, maxBlock)
    val j6  = Pipeline.runTeams(conf(), j1.output, b, rid, snap, 2, 50)
    val j7  = SourcePipeline.runExport(
      conf(),
      Seq(
        j1.output,
        j2.output,
        j3.output,
        j4.output,
        j6.output
      ),
      b,
      rid,
      snap,
      3
    )
    Vector(j1, j2, j3, j4, j6, j7)

  test("Job 2 reducer counts DUPLICATE (identical copy) and CONFLICT (differing copy) (F4)") {
    LocalPipelineSuite.windowsPrerequisiteProblem(
      System.getProperty("os.name"),
      sys.env.get("HADOOP_HOME")
    ).foreach(msg => fail(msg))
    val base = Files.createTempDirectory("sc-partb-dup")
    try
      val in   = Files.createDirectories(base.resolve("in"))
      val line = """{"id":"https://openalex.org/W1","title":"Alpha","publication_year":2020}"""
      val diff = """{"id":"https://openalex.org/W1","title":"Beta","publication_year":2020}"""
      Files.write(
        in.resolve("openalex-dups.jsonl"),
        java.util.List.of(line, line, diff),
        java.nio.charset.StandardCharsets.UTF_8
      )
      val r = SourcePipeline.runNormalize(
        conf(),
        Seq(hpath(in.resolve("openalex-dups.jsonl"))),
        hpath(base.resolve("out")),
        Fixtures.runId,
        "dups",
        2
      )
      assert(r.ok)
      assertEquals(r.counters("INPUT"), 3L)
      assertEquals(r.counters("ACCEPTED"), 3L)
      assertEquals(r.counters("DUPLICATE"), 1L)
      assertEquals(r.counters("CONFLICT"), 1L)
      val works = lines(r.output, "works")
      assertEquals(works.size, 1)
      assert(works.head.contains("\"Alpha\""), works) // smallest envelope kept, deterministic
    finally
      Files.walk(base).iterator.asScala.toVector.sortBy(-_.getNameCount)
        .foreach(Files.deleteIfExists)
  }

  test("part B jobs run locally over the committed samples, bounded, deterministic, idempotent") {
    LocalPipelineSuite.windowsPrerequisiteProblem(
      System.getProperty("os.name"),
      sys.env.get("HADOOP_HOME")
    ).foreach(msg => fail(msg))
    val base = Files.createTempDirectory("sc-partb-it")
    val alt  = Files.createTempDirectory("sc-partb-it2")
    try
      val rs = runAll(base, 10)
      val j2 = rs(1); val j3 = rs(2); val j4 = rs(3); val j6 = rs(4); val j7 = rs(5)
      assert(rs.forall(_.ok), rs.map(r => r.job -> r.ok))
      // Job 2, counted by hand: OpenAlex 4 lines (2 ok, missing title, truncated JSON); Crossref 5
      // (3 ok, dataset type, truncated JSON); arXiv 3 records (2 ok, deleted); OpenCitations
      // header + 5 rows (4 ok, 2-column row).
      val c2 = j2.counters
      assertEquals(c2("HEADER_SKIPPED"), 1L)
      assertEquals(c2("INPUT"), 17L)
      assertEquals(c2("ACCEPTED"), 11L)
      assertEquals(c2("REJECTED"), 6L)
      assertEquals(c2("MISSING_KEY"), 0L)
      assertEquals(c2("UNSUPPORTED_TYPE"), 2L)
      assertEquals(c2("SCHEMA_FAILURE"), 4L)
      assertEquals(c2("CITATIONS"), 4L)
      assertEquals(c2("DUPLICATE") + c2("CONFLICT"), 0L)
      val rejects = lines(j2.output, "rejects/v2").map(read[RejectRecord](_))
      assertEquals(rejects.size, 6)
      assertEquals(
        rejects.map(r => (r.sourceDataset, r.inputFile, r.reason)).sorted,
        Vector(
          ("arxiv", "arxiv-oai-sample.xml", "deleted-record"),
          ("crossref", "crossref-works-sample.jsonl", "malformed-json"),
          ("crossref", "crossref-works-sample.jsonl", "unsupported-record-type"),
          ("openalex", "openalex-works-sample.jsonl", "malformed-json"),
          ("openalex", "openalex-works-sample.jsonl", "missing-title"),
          ("opencitations", "opencitations-index-sample.csv", "schema-failure")
        )
      )
      assertEquals(lines(j2.output, "works").size, 7)
      assertEquals(lines(j2.output, "citations").size, 4)
      assertEquals(lines(j2.output, "mentions").size, lines(j2.output, "mentionextras").size)
      // F2: arXiv categories (cs.DL, cs.DB; cs.DL) and OpenAlex W9990000001's institution.
      val topics = lines(j2.output, "topics").map(l => upickle.default.read[Topic](l))
      assertEquals(topics.map(_.name).sorted, Vector("cs.DB", "cs.DL", "cs.DL"))
      val insts = lines(j2.output, "institutions").map(l => upickle.default.read[Institution](l))
      assertEquals(
        insts.map(i => (i.institutionId, i.name)),
        Vector(("institution:openalex:I9990000001", "Example University"))
      )
      // Job 3: the DOI 10.5555/sc441.0001 links DBLP, OpenAlex, Crossref, arXiv, OpenCitations.
      val groups = lines(j3.output, "exactids").map(read[ExactIdGroup](_))
      assertEquals(groups.size.toLong, j3.counters("EXACT_ID_KEY"))
      val doi1 = groups.find(_.key == "doi:10.5555/sc441.0001").get
      assertEquals(
        doi1.members.map(_.provenance.sourceDataset.wire).distinct.sorted,
        List("arxiv", "crossref", "dblp", "openalex", "opencitations")
      )
      assertEquals(j3.counters("CONFLICT"), 0L)
      // Observed 2026-10-29 and pinned: 18 envelopes (7 DBLP, 7 works, 4 citations).
      assertEquals(j3.counters("INPUT"), 18L)
      assertEquals(j3.counters("EXACT_ID_KEY"), 20L)
      assertEquals(j3.counters("LINKED_KEY"), 5L)
      assertEquals(j3.counters("DUPLICATE"), 8L)
      // F3: was 7 when every Crossref work (keyed by its DOI) counted; the 3 Crossref works now
      // do not, since a DOI is a cross-source identifier.
      assertEquals(j3.counters("MISSING_KEY"), MissingKeyPinned)
      assert(j3.counters("LINKED_KEY") >= 2L)
      // Jobs 4+5: 21 "Collab Member NN" mentions in DBLP (SampleP25) and 21 in Crossref 0003
      // share m|member|c: 42 > cap 10, so 5 chunks (10, 10, 10, 10, 2).
      val c4    = j4.counters
      val stats = lines(j4.output, "blockstats").map(read[BlockStat](_))
      assertEquals(stats.size.toLong, c4("BLOCKS"))
      assertEquals(c4("OVERSIZE_BLOCK"), 2L)
      assertEquals(c4("BLOCKS"), 17L)
      assertEquals(c4("CANDIDATES"), 268L)
      assertEquals(c4("MISSING_KEY"), 0L)
      assertEquals(stats.count(_.oversize).toLong, c4("OVERSIZE_BLOCK"))
      assert(stats.forall(s => s.size <= 10 || s.chunks == ((s.size + 9) / 10).toInt))
      val largest = SourcePipeline.largestBlock(conf(), j4.output).get
      assertEquals(largest.blockKey, "m|member|c")
      assertEquals(largest.size, 42L)
      assertEquals(largest.chunks, 5)
      assertEquals(largest.candidatePairs, 4L * 45 + 1)
      assertEquals(largest.crossChunkPairsSkipped, 42L * 41 / 2 - 181)
      val cands = lines(j4.output, "candidates").map(read[MatchCandidateRecord](_))
      assertEquals(cands.size.toLong, c4("CANDIDATES"))
      assertEquals(cands.size.toLong, stats.map(_.candidatePairs).sum)
      assert(cands.exists(c => c.scope == "work" && c.blockKey.startsWith("w|provenance")))
      // Job 7: every exported entity is partitioned into the 3 reducer partitions' files.
      val c7 = j7.counters
      assertEquals(c7("EXPORTED"), c7("INPUT"))
      // 755 before F2, plus 3 topic and 1 institution record.
      assertEquals(c7("EXPORTED"), ExportedPinned)
      assertEquals(lines(j7.output, "topics").size, 3)
      assertEquals(lines(j7.output, "institutions").size, 1)
      // F1: the streamed export holds exactly the input records of each entity.
      for e <- Seq("pairs", "works", "citations", "topics") do
        val src = rs.take(5).flatMap(r => lines(r.output, e)).sorted
        assertEquals(lines(j7.output, e).sorted, src, e)
      assertEquals(lines(j7.output, "works").size, 7 + 7)
      assertEquals(lines(j7.output, "pairs").size, lines(j6.output, "pairs").size)
      assertEquals(lines(j7.output, "candidates").size, cands.size)
      assert(partFiles(j7.output, "works").forall(_.matches("part-r-0000[0-2]")))
      // Closeout, round-2 MINOR 1: Job 7 must use ExportPartitioner. Without
      // `job.setPartitionerClass(classOf[ExportPartitioner])` the job falls back to
      // WorkIdPartitioner over the whole composite key (`<entity>\t<key>\t<line>`), so one work's
      // records scatter across part numbers. Every exported record must sit in the part file
      // numbered WorkIdPartitioner.partition(partitionKey, 3), and each work ID's works, mentions,
      // and workextras records must share one part number.
      val placed =
        for
          e <- Export.Entities; f <- partFiles(j7.output, e)
          l <- lines(j7.output, s"$e/$f")
        yield (e, Export.partitionKey(l), f.stripPrefix("part-r-").toInt)
      assertEquals(placed.size.toLong, c7("EXPORTED"))
      val misplaced = placed.filter((_, k, p) => p != WorkIdPartitioner.partition(k, 3))
      assert(misplaced.isEmpty, misplaced.take(5))
      val byWork = placed.filter((e, _, _) => Set("works", "mentions", "workextras")(e))
        .groupBy(_._2).view.mapValues(_.map(_._3).distinct).toMap
      assert(byWork.size >= 7, byWork.keys)
      assert(byWork.forall(_._2.size == 1), byWork.filter(_._2.size > 1))
      assert(placed.map(_._3).distinct.size > 1, "all records in one partition proves nothing")
      assert(j7.output.toString.contains("run=run-v1-") &&
        j7.output.toString.contains("snapshot=sample/graph-export"))
      rs.foreach(r => println(s"PARTB ${DblpPipelineMain.report(r)}"))
      println(s"PARTB largest-block $largest")
      println(s"PARTB oversize-blocks ${stats.filter(_.oversize).sortBy(_.blockKey)}")
      println(s"PARTB block-sizes ${stats.map(_.size).sorted.reverse}")
      println(s"PARTB export-files " + Export.Entities.map(e => e -> partFiles(j7.output, e))
        .mkString(" "))
      // Rerun: completed outputs are reused. A fresh base yields identical records.
      assert(runAll(base, 10).forall(_.skipped))
      val again = runAll(alt, 10)
      for
        (r, s) <- rs.zip(again);
        sub    <- Seq("candidates", "exactids", "works", "rejects/v2") ++ Export.Entities
      do
        assertEquals(lines(s.output, sub), lines(r.output, sub), s"${r.job}/$sub")
      // Job 7 is byte-deterministic per partition file, not only per entity.
      for e <- Export.Entities; f <- partFiles(rs(5).output, e) do
        assertEquals(lines(again(5).output, s"$e/$f"), lines(rs(5).output, s"$e/$f"), s"$e/$f")
    finally
      Seq(base, alt).foreach(d =>
        if Files.exists(d) then
          Files.walk(d).iterator.asScala.toVector.sortBy(-_.getNameCount)
            .foreach(Files.deleteIfExists)
      )
  }
