package edu.uic.cs441.scholarchain.spark

import edu.uic.cs441.scholarchain.core.json.Wire
import edu.uic.cs441.scholarchain.core.model.*
import edu.uic.cs441.scholarchain.er.{
  DecisionsManifest,
  Features,
  ManifestEntry,
  MatchPath,
  Outputs,
  ResolutionRecord,
  ResolutionStatus,
  Resolver
}
import org.apache.spark.sql.SparkSession
import upickle.default.write

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/**
 * Part A identity under ER merge vetoes (Phase 11 repair-01 D1, human decision STRICT rule c).
 *
 * Fixture: every mention is author 0 of its own work (year 2020); ER decisions of one ER run are
 * written by the real ER writer (`Outputs.writeRun`), so the ER directory carries a verified
 * `_DECISIONS_MANIFEST.json` (Phase 11 repair-03 F2).
 *
 *   - D1: d1-1john, d1-2j, d1-3jane. Accepted john-j and j-jane (0.91), rejected john-jane.
 *     Expected {john, j} (anchor john), {jane}; j-jane refused by john-jane.
 *   - L: l1..l6 chain, links 0.95 except l3-l4 at 0.86; rejected l2-l5 in the middle. Expected
 *     {l1,l2,l3} (anchor l1), {l4,l5,l6} (anchor l4); l3-l4 refused by l2-l5.
 *   - U: u1-u2, u2-u3 accepted (0.9); u1-u3 unresolved. Expected {u1,u2}, {u3}; u2-u3 refused by
 *     u1-u3.
 *   - C: c1-c2, c2-c3 accepted; c1-c3 conflict. Expected three conflicted-split singletons (F2);
 *     both links refused with reason conflicted-cluster-split, veto c1-c3.
 *   - N: n1-n2-n3-n4 accepted, no veto inside (n1 and n4 were never ER candidates: no decision, no
 *     veto); rejected n1-z1 crosses into the singleton z1. Expected one merged researcher anchored
 *     at n1, exactly as the plain closure gives (regression).
 */
class IdentityVetoSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(900, "s")

  private val hadoopRun = "run:v1:" + "a" * 64
  private val erRun     = "run:v1:" + "c" * 64
  private val erRunOld  = "run:v1:" + "d" * 64
  private val erPolicy  = "er-policy-test"
  private val window    = YearWindow(2015, 2025)
  private val policy    = "graph-policy-test-v1"

  private def mid(s: String)  = s"m-$s"
  private def wid(s: String)  = s"work:dblp:t/w-$s"
  private def prov(s: String) = Provenance(SourceType.Dblp, s"t/w-$s", hadoopRun)

  private val names = List(
    "d1-1john",
    "d1-2j",
    "d1-3jane",
    "l1",
    "l2",
    "l3",
    "l4",
    "l5",
    "l6",
    "u1",
    "u2",
    "u3",
    "c1",
    "c2",
    "c3",
    "n1",
    "n2",
    "n3",
    "n4",
    "z1"
  )

  private def rec(
      a: String,
      b: String,
      st: ResolutionStatus,
      conf: Double,
      run: String = erRun
  ): ResolutionRecord =
    val (lo, hi) = if mid(a).compareTo(mid(b)) <= 0 then (a, b) else (b, a)
    val pairId   = s"mention|${mid(lo)}|${mid(hi)}"
    val outcome  = st match
      case ResolutionStatus.Accepted => MatchOutcome.Merge
      case ResolutionStatus.Rejected => MatchOutcome.NonMatch
      case _                         => MatchOutcome.NeedsReview
    ResolutionRecord(
      Resolver.RecordSchemaVersion,
      s"erdecision:v1:$pairId",
      pairId,
      "mention",
      "bk",
      mid(lo),
      mid(hi),
      st,
      MatchPath.Probabilistic,
      outcome,
      MatchDecider.Model,
      Some(conf),
      conf,
      erPolicy,
      Features.SpecVersion,
      None,
      Nil,
      List(s"reason:${st.wire}"),
      Provenance(SourceType.ScholarChainPipeline, pairId, run),
      prov(lo),
      prov(hi),
      Some(conf),
      Some(true)
    )

  private def record(a: String, b: String, st: ResolutionStatus, conf: Double): String =
    write(rec(a, b, st, conf))

  /** A schemaVersion 1 line (no scoreUpper, no corroborated), as written before er-policy-v3. */
  private def v1Line(a: String, b: String, st: String): String =
    val pairId = s"mention|${mid(a)}|${mid(b)}"
    s"""{"schemaVersion":1,"decisionId":"erdecision:v1:$pairId","pairId":"$pairId",""" +
      s""""scope":"mention","blockKey":"bk","memberLow":"${mid(a)}","memberHigh":"${mid(b)}",""" +
      s""""status":"$st","path":"probabilistic","outcome":"needs-review","decider":"model",""" +
      s""""score":0.7,"confidence":0.0,"policyVersion":"er-policy-v2",""" +
      s""""featureSpecVersion":"er-features-v2","adjudicatorVersion":null,"features":[],""" +
      s""""reasonCodes":["reason:$st"],"provenance":${Wire.encode(
          Provenance(SourceType.ScholarChainPipeline, pairId, erRun)
        )},"provenanceLow":${Wire.encode(prov(a))},"provenanceHigh":${Wire.encode(prov(b))}}"""

  private val accepted = List(
    ("d1-1john", "d1-2j", 0.91),
    ("d1-2j", "d1-3jane", 0.91),
    ("l1", "l2", 0.95),
    ("l2", "l3", 0.95),
    ("l3", "l4", 0.86),
    ("l4", "l5", 0.95),
    ("l5", "l6", 0.95),
    ("u1", "u2", 0.9),
    ("u2", "u3", 0.9),
    ("c1", "c2", 0.9),
    ("c2", "c3", 0.9),
    ("n1", "n2", 0.9),
    ("n2", "n3", 0.9),
    ("n3", "n4", 0.9)
  )

  private def writeLines(p: Path, lines: Seq[String]): Unit =
    Files.createDirectories(p.getParent)
    Files.write(p, lines.mkString("", "\n", if lines.isEmpty then "" else "\n").getBytes(UTF_8))

  private def uri(p: Path): String =
    val s = p.toAbsolutePath.toString.replace(java.io.File.separatorChar, '/')
    if s.startsWith("/") then "file://" + s else "file:///" + s

  /** The ER decisions of the fixture (one ER run, `run`). */
  private def erRecords(run: String = erRun): List[ResolutionRecord] =
    accepted.map((a, b, c) => rec(a, b, ResolutionStatus.Accepted, c, run)) ++ List(
      rec("d1-1john", "d1-3jane", ResolutionStatus.Rejected, 1.0, run),
      rec("l2", "l5", ResolutionStatus.Rejected, 0.8, run),
      rec("n1", "z1", ResolutionStatus.Rejected, 1.0, run),
      rec("u1", "u3", ResolutionStatus.Unresolved, 1.0, run),
      rec("c1", "c3", ResolutionStatus.Conflict, 1.0, run)
    )

  private def lines(p: Path): List[String] =
    Files.readAllLines(p, UTF_8).asScala.toList.filter(_.trim.nonEmpty)

  private def readManifest(er: Path): DecisionsManifest =
    upickle.default.read[DecisionsManifest](Files.readString(er.resolve(Outputs.ManifestFileName)))

  private def writeManifest(er: Path, m: DecisionsManifest): Unit =
    Files.write(
      er.resolve(Outputs.ManifestFileName),
      (write(m, indent = 2) + "\n").getBytes(UTF_8)
    )

  /**
   * Regenerates the manifest's per-file record counts and SHA-256 from the files as they now are,
   * keeping (or rewriting with `header`) the header: a manifest "regenerated over" edited files.
   */
  private def reseal(er: Path, header: DecisionsManifest => DecisionsManifest = identity): Unit =
    val files = Outputs.DecisionFiles.map { f =>
      val bytes = Files.readAllBytes(er.resolve(f))
      ManifestEntry(f, lines(er.resolve(f)).size.toLong, Outputs.sha256Hex(bytes))
    }
    writeManifest(er, header(readManifest(er).copy(files = files)))

  /**
   * Writes the fixture. `order` permutes the lines of every input file (ER files too: the permuted
   * ER directory is resealed, so it still verifies). `erEdit` changes the ER records before the
   * real ER writer (`Outputs.writeRun`) writes them with their manifest; `tamper` then changes the
   * written ER directory (corrupt, stale, or forged directories).
   */
  private def fixture(
      root: Path,
      order: Seq[String] => Seq[String],
      erEdit: List[ResolutionRecord] => List[ResolutionRecord] = identity,
      tamper: Path => Unit = _ => ()
  ): PartAInputs =
    writeLines(
      root.resolve("ap/part-r-00000"),
      order(names.map { n =>
        s"""{"mentionId":"${mid(n)}","workId":"${wid(n)}","authorPosition":0,""" +
          s""""normalizedName":"$n","year":2020,"provenance":${Wire.encode(prov(n))}}"""
      })
    )
    writeLines(root.resolve("pairs/part-r-00000"), Nil)
    writeLines(
      root.resolve("works/part-r-00000"),
      order(names.map(n =>
        Wire.encode(WorkRecord(1, wid(n), s"t $n", Some(2020), None, Nil, Nil, prov(n)))
      ))
    )
    val er = root.resolve("er")
    Outputs.writeRun(er, erEdit(erRecords()), 10, erRun, erPolicy)
    val permuted = Outputs.DecisionFiles.filter { f =>
      val ls = lines(er.resolve(f))
      val ps = order(ls)
      if ps != ls then writeLines(er.resolve(f), ps)
      ps != ls
    }
    if permuted.nonEmpty then reseal(er)
    tamper(er)
    PartAInputs(
      uri(root.resolve("ap")),
      uri(root.resolve("pairs")),
      uri(root.resolve("works")),
      uri(er)
    )

  private lazy val spark: SparkSession = SparkSession
    .builder()
    .master("local[2]")
    .appName("scholarchain-spark-identity-veto-test")
    .config("spark.ui.enabled", "false")
    .config("spark.driver.host", "127.0.0.1")
    .config("spark.sql.shuffle.partitions", "4")
    .getOrCreate()

  override def afterAll(): Unit = spark.stop()

  private def runWith(order: Seq[String] => Seq[String], partitions: Int): PartAResult =
    val root = Files.createTempDirectory("sc-spark-identity-veto")
    PartA.run(
      spark,
      fixture(root, order),
      PartAParams(window, policy, ccMaxIterations = 20, shufflePartitions = partitions),
      "sha256:test-core-config",
      "test",
      1,
      uri(root.resolve("out"))
    )

  private lazy val base: PartAResult = runWith(identity, 4)

  /** mentionId -> (researcherId, anchorMentionId, identityStatus, vetoConstrainedClosureAnchor). */
  private def mentionMap(r: PartAResult): Map[String, (String, String, String, String)] =
    spark.read.parquet(s"${r.outputBase}/mention-researcher-map").collect()
      .map(row =>
        row.getAs[String]("mentionId") -> (
          row.getAs[String]("researcherId"),
          row.getAs[String]("anchorMentionId"),
          row.getAs[String]("identityStatus"),
          row.getAs[String]("vetoConstrainedClosureAnchorMentionId")
        )
      )
      .toMap

  private def refusedRows(r: PartAResult): Set[(String, String, String, String, String)] =
    spark.read.parquet(s"${r.outputBase}/identity-refused-links").collect()
      .map(row =>
        (
          row.getAs[String]("pairId"),
          row.getAs[String]("refusalReason"),
          row.getAs[String]("vetoPairId"),
          row.getAs[String]("vetoDecisionId"),
          row.getAs[String]("vetoStatus")
        )
      )
      .toSet

  private def anchorOf(r: PartAResult): Map[String, String] =
    mentionMap(r).map((m, v) => m.stripPrefix("m-") -> v._2.stripPrefix("m-"))

  private def pk(a: String, b: String) = s"mention|${mid(a)}|${mid(b)}"

  test("D1 chain, long chain, unresolved veto, conflict veto: no veto pair shares a researcher") {
    val r = base
    assertEquals(r.failures, Nil, s"invariant failures: ${r.failures}")
    val a = anchorOf(r)
    // D1: John and Jane (ER rejected) never share a researcher through J. Smith.
    assertNotEquals(a("d1-1john"), a("d1-3jane"))
    assertEquals(
      List("d1-1john", "d1-2j", "d1-3jane").map(a),
      List("d1-1john", "d1-1john", "d1-3jane")
    )
    // Long chain: the 0.86 link l3-l4 under the l2-l5 veto is the one refused.
    assertEquals(
      List("l1", "l2", "l3", "l4", "l5", "l6").map(a),
      List("l1", "l1", "l1", "l4", "l4", "l4")
    )
    // Unresolved veto blocks the join like a rejection.
    assertEquals(List("u1", "u2", "u3").map(a), List("u1", "u1", "u3"))
    // Conflict veto: the whole closure component is split (F2).
    assertEquals(List("c1", "c2", "c3").map(a), List("c1", "c2", "c3"))
    val mm = mentionMap(r)
    List("c1", "c2", "c3").foreach(n => assertEquals(mm(mid(n))._3, "conflicted-split", n))
    // Researcher IDs follow the anchors.
    mm.foreach((m, v) => assertEquals(v._1, IdUdfs.researcherId("dblp", v._2), m))
    // Constrained components carry their pre-constraint closure anchor; others carry none.
    val constrained = Map(
      "d1-1john" -> "d1-1john",
      "d1-2j"    -> "d1-1john",
      "d1-3jane" -> "d1-1john",
      "l1"       -> "l1",
      "l2"       -> "l1",
      "l3"       -> "l1",
      "l4"       -> "l1",
      "l5"       -> "l1",
      "l6"       -> "l1",
      "u1"       -> "u1",
      "u2"       -> "u1",
      "u3"       -> "u1"
    )
    names.foreach(n =>
      assertEquals(Option(mm(mid(n))._4).map(_.stripPrefix("m-")), constrained.get(n), n)
    )
    assertEquals(mm(mid("d1-3jane"))._3, "singleton")
    assertEquals(mm(mid("d1-2j"))._3, "merged")

    // Every refused link, with its vetoing decision.
    def did(k: String) = s"erdecision:v1:$k"
    assertEquals(
      refusedRows(r),
      Set(
        (
          pk("d1-2j", "d1-3jane"),
          "veto-would-join",
          pk("d1-1john", "d1-3jane"),
          did(pk("d1-1john", "d1-3jane")),
          "rejected"
        ),
        (pk("l3", "l4"), "veto-would-join", pk("l2", "l5"), did(pk("l2", "l5")), "rejected"),
        (pk("u2", "u3"), "veto-would-join", pk("u1", "u3"), did(pk("u1", "u3")), "unresolved"),
        (
          pk("c1", "c2"),
          "conflicted-cluster-split",
          pk("c1", "c3"),
          did(pk("c1", "c3")),
          "conflict"
        ),
        (
          pk("c2", "c3"),
          "conflicted-cluster-split",
          pk("c1", "c3"),
          did(pk("c1", "c3")),
          "conflict"
        )
      )
    )
    // Provenance of a refused row: the accepted ER decision, both source records, both runs.
    val row = spark.read.parquet(s"${r.outputBase}/identity-refused-links")
      .where(org.apache.spark.sql.functions.col("pairId") === pk("l3", "l4"))
      .collect()
      .head
    assertEquals(row.getAs[String]("memberLow"), mid("l3"))
    assertEquals(row.getAs[String]("memberHigh"), mid("l4"))
    assertEquals(row.getAs[String]("linkDecisionId"), did(pk("l3", "l4")))
    assertEqualsDouble(row.getAs[Double]("linkConfidence"), 0.86, 1e-12)
    assertEquals(row.getAs[String]("sourceDataset"), "scholarchain-pipeline")
    assertEquals(row.getAs[String]("sourceRecordId"), pk("l3", "l4"))
    assertEquals(row.getAs[String]("sourcePipelineRunId"), erRun)
    assertEquals(row.getAs[String]("memberLowSourceRecordId"), "t/w-l3")
    assertEquals(row.getAs[String]("memberHighSourceRecordId"), "t/w-l4")
    assertEquals(row.getAs[String]("memberLowSourcePipelineRunId"), hadoopRun)
    assertEquals(row.getAs[String]("vetoErFile"), "rejected.jsonl")
    assertEquals(
      row.getList[String](row.fieldIndex("vetoReasonCodes")).asScala.toList,
      List("reason:rejected")
    )
    assertEquals(row.getAs[String]("vetoSourcePipelineRunId"), erRun)
    assertEquals(row.getAs[String]("closureAnchorMentionId"), mid("l1"))
    assertEquals(row.getAs[String]("identityClosureVersion"), ConstrainedClosure.Version)
    assertEquals(row.getAs[String]("pipelineRunId"), r.runId)

    val expectCounts = List(
      "mentions"                                      -> 20L,
      "erAcceptedRecords"                             -> 14L,
      "erVetoRecords:rejected.jsonl"                  -> 3L,
      "erVetoRecords:unresolved.jsonl"                -> 1L,
      "erVetoRecords:conflict.jsonl"                  -> 1L,
      "erAcceptedMentionLinkPairs"                    -> 14L,
      "erVetoMentionPairs"                            -> 5L,
      "erVetoMentionPairsWithinClosure"               -> 4L,
      "conflictedClusters"                            -> 1L,
      "conflictedClusterMentionsSplit"                -> 3L,
      "identityConstrainedComponents"                 -> 3L,
      "identityConstrainedMentions"                   -> 12L,
      "maxConstrainedComponentMentions"               -> 6L,
      "maxConstrainedComponentLinks"                  -> 5L,
      "identityLinksRefused:veto-would-join"          -> 3L,
      "identityLinksRefused:conflicted-cluster-split" -> 2L,
      // d1: 2, l: 2, u: 2, c: 3, n: 1, z1: 1
      "researchers"                -> 11L,
      "researchersVetoConstrained" -> 6L,
      "researchersConflictedSplit" -> 3L,
      "erDecisionDistinctRunIds"   -> 1L
    )
    expectCounts.foreach((k, v) => assertEquals(r.count(k), v, k))
    val params = ujson.read(JobFiles.readText(spark, s"${r.outputBase}/params.json"))
    assertEquals(params("identityClosure").str, ConstrainedClosure.Version)
    assertEquals(params("identityVetoFiles").arr.map(_.str).toList, Outputs.MergeVetoFiles)
  }

  test("a component with no veto inside is unchanged from the plain closure (regression)") {
    val r  = base
    val a  = anchorOf(r)
    val mm = mentionMap(r)
    // Reference closure over the accepted links, anchor = smallest mention.
    val adj  = accepted.flatMap((x, y, _) => Seq(x -> y, y -> x)).groupMap(_._1)(_._2)
    val comp = mutable.Map.empty[String, String]
    names.sorted.foreach { v =>
      if !comp.contains(v) then
        val seen = mutable.Set(v)
        val q    = mutable.Queue(v)
        while q.nonEmpty do
          adj.getOrElse(q.dequeue(), Nil).foreach(n => if seen.add(n) then q.enqueue(n))
        seen.foreach(comp(_) = mid(seen.min).stripPrefix("m-"))
    }
    // N (never-candidate pairs n1-n4 carry no veto) and the singleton z1 behind a cross veto.
    List("n1", "n2", "n3", "n4", "z1").foreach { n =>
      assertEquals(a(n), comp(n), n)
      assertEquals(mm(mid(n))._1, IdUdfs.researcherId("dblp", mid(comp(n))), n)
    }
    List("n1", "n2", "n3", "n4").foreach(n => assertEquals(mm(mid(n))._3, "merged", n))
    assertEquals(mm(mid("z1"))._3, "singleton")
  }

  test("input-order and partition invariance: same researcher IDs and refusals") {
    val expectedMap     = mentionMap(base).view.mapValues(v => (v._1, v._3, v._4)).toMap
    val expectedRefused = refusedRows(base)
    val shuffles        = List[(Seq[String] => Seq[String], Int)](
      ((s: Seq[String]) => s.reverse, 1),
      ((s: Seq[String]) => scala.util.Random(17).shuffle(s), 7)
    )
    shuffles.foreach { (order, parts) =>
      val r = runWith(order, parts)
      assertEquals(r.failures, Nil, s"partitions $parts: ${r.failures}")
      assertEquals(
        mentionMap(r).view.mapValues(v => (v._1, v._3, v._4)).toMap,
        expectedMap,
        s"partitions $parts"
      )
      assertEquals(refusedRows(r), expectedRefused, s"partitions $parts")
    }
  }

  // ---- Phase 11 repair-02 RR1: corrupt or incomplete ER records fail closed -----------------------

  /** The D1 rejection line (John-Jane) in rejected.jsonl. */
  private val d1VetoKey = pk("d1-1john", "d1-3jane")

  private def editJson(line: String)(f: ujson.Obj => Unit): String =
    val j = ujson.read(line) match
      case o: ujson.Obj => o
      case other        => fail(s"not a JSON object: $other")
    f(j)
    j.render()

  /**
   * Runs Part A on `in` expecting it to fail; returns the error and asserts that no output exists.
   */
  private def runFailing(root: Path, in: PartAInputs): Throwable =
    val out = root.resolve("out")
    val err = intercept[IllegalStateException] {
      PartA.run(
        spark,
        in,
        PartAParams(window, policy, ccMaxIterations = 20, shufflePartitions = 2),
        "sha256:test-core-config",
        "test",
        1,
        uri(out)
      )
    }
    assert(!Files.exists(out), s"output written although the run failed: ${err.getMessage}")
    err

  /**
   * Rewrites the lines of ER file `file` in a written ER directory (the manifest is not updated).
   */
  private def editFile(er: Path, file: String)(f: List[String] => List[String]): Unit =
    writeLines(er.resolve(file), f(lines(er.resolve(file))))

  /** Rewrites the D1 veto line of `file` after the ER writer wrote it (the manifest goes stale). */
  private def tamperD1(file: String, edit: String => String)(er: Path): Unit =
    editFile(er, file)(_.map(l => if l.contains(s"\"$d1VetoKey\"") then edit(l) else l))

  /** Runs Part A with one ER file's D1 veto line rewritten after writing; returns the error. */
  private def runEdited(file: String, edit: String => String): Throwable =
    val root = Files.createTempDirectory("sc-spark-er-corrupt")
    runFailing(root, fixture(root, identity, tamper = tamperD1(file, edit)))

  private val VerifyFailed = "failed verification against _DECISIONS_MANIFEST.json"

  test(
    "RR1: ER record classification: v1 and v2 lines sound; corrupt or incomplete lines flagged"
  ) {
    val good = record("l2", "l5", ResolutionStatus.Rejected, 0.8)
    def labelled(label: String)(f: ujson.Obj => Unit): String =
      editJson(good) { j => j("pairId") = label; f(j) }
    val lines = List(
      labelled("ok-v2")(_ => ()),
      editJson(v1Line("u1", "u3", "unresolved"))(j => j("pairId") = "ok-v1"),
      labelled("no-scope")(j => j.value.remove("scope")),
      labelled("no-memberLow")(j => j.value.remove("memberLow")),
      labelled("no-memberHigh")(j => j.value.remove("memberHigh")),
      labelled("no-status")(j => j.value.remove("status")),
      labelled("no-decisionId")(j => j.value.remove("decisionId")),
      labelled("null-status")(j => j("status") = ujson.Null),
      labelled("blank-memberHigh")(j => j("memberHigh") = "  "),
      labelled("bad-scope")(j => j("scope") = "Mention"),
      labelled("bad-status")(j => j("status") = "maybe"),
      labelled("null-provenance")(j => j("provenance") = ujson.Null),
      labelled("no-provenance-dataset")(j => j("provenance").obj.remove("sourceDataset")),
      good.take(good.length / 2),
      "this line is not json"
    )
    val dir = Files.createTempDirectory("sc-spark-er-classify")
    writeLines(dir.resolve("rejected.jsonl"), lines)
    val rows = PartA
      .readErDecisions(spark, uri(dir), List("rejected.jsonl"))
      .select("pairId", "erRecordProblem")
      .collect()
      .toList
    assertEquals(rows.size, lines.size)
    assertEquals(rows.count(_.getString(1) == ErRecordCheck.Unparseable), 2, rows)
    val parsed = rows
      .filter(_.getString(1) != ErRecordCheck.Unparseable)
      .map(r => r.getString(0) -> Option(r.getString(1)))
      .toMap
    val incomplete = Some(ErRecordCheck.Incomplete)
    assertEquals(
      parsed,
      Map(
        "ok-v2"                 -> None,
        "ok-v1"                 -> None,
        "no-scope"              -> incomplete,
        "no-memberLow"          -> incomplete,
        "no-memberHigh"         -> incomplete,
        "no-status"             -> incomplete,
        "no-decisionId"         -> incomplete,
        "null-status"           -> incomplete,
        "blank-memberHigh"      -> incomplete,
        "bad-scope"             -> incomplete,
        "bad-status"            -> incomplete,
        "null-provenance"       -> incomplete,
        "no-provenance-dataset" -> incomplete
      )
    )
  }

  // Since repair-03 a line corrupted after the ER writer ran fails ErManifestGate.verify first
  // (sha256, record count, parse), before Spark reads a record: still fail closed.

  test("RR1: a truncated veto line fails the run before any output (never read as 'no veto')") {
    val m = runEdited("rejected.jsonl", l => l.take(l.length / 2)).getMessage
    assert(m.contains(VerifyFailed), m)
    assert(m.contains("rejected.jsonl line") && m.contains("unparseable"), m)
    assert(m.contains("rejected.jsonl: sha256"), m)
  }

  test("RR1: a veto record missing scope, memberLow, memberHigh, or status fails the run") {
    List("scope", "memberLow", "memberHigh", "status").foreach { field =>
      val m = runEdited("rejected.jsonl", l => editJson(l)(j => j.value.remove(field))).getMessage
      assert(m.contains(VerifyFailed), s"$field: $m")
      assert(m.contains("rejected.jsonl: sha256"), s"$field: $m")
    }
  }

  test("RR1: a corrupt accepted line fails the run too (never dropped unnoticed)") {
    val root = Files.createTempDirectory("sc-spark-er-corrupt-acc")
    val in   = fixture(
      root,
      identity,
      tamper = er => editFile(er, "accepted.jsonl")(_.map(l => l.take(l.length - 7)))
    )
    val m = runFailing(root, in).getMessage
    assert(m.contains(VerifyFailed), m)
    assert(m.contains("accepted.jsonl: sha256"), m)
  }

  test("RR1: a manifest regenerated over a truncated line still fails verification (parse)") {
    val root = Files.createTempDirectory("sc-spark-er-resealed")
    val in   = fixture(
      root,
      identity,
      tamper = { er =>
        tamperD1("rejected.jsonl", l => l.take(l.length / 2))(er)
        reseal(er)
      }
    )
    val m = runFailing(root, in).getMessage
    assert(m.contains(VerifyFailed), m)
    assert(m.contains("rejected.jsonl line") && m.contains("unparseable"), m)
    assert(!m.contains("sha256 "), s"the resealed hashes match, only the parse rule fails: $m")
  }

  test("RR1: ErRecordCheck still guards records that the ER verifier admits") {
    // A record the ER writer and verifier accept (it parses as a schema-2 record of the run) but
    // that Spark cannot use as a veto: a blank member, a blank decision ID, or a scope that is not
    // mention or work. Without ErRecordCheck such a veto would be ignored silently.
    val edits = List[(String, ResolutionRecord => ResolutionRecord)](
      "scope"      -> (_.copy(scope = "Mention")),
      "memberHigh" -> (_.copy(memberHigh = "  ")),
      "decisionId" -> (_.copy(decisionId = ""))
    )
    edits.foreach { (field, edit) =>
      val root = Files.createTempDirectory("sc-spark-er-incomplete")
      val in   = fixture(
        root,
        identity,
        erEdit = _.map(r => if r.pairId == d1VetoKey then edit(r) else r)
      )
      assert(
        Outputs.verify(root.resolve("er")).isRight,
        s"$field: precondition, the ER verifier admits it"
      )
      val m = runFailing(root, in).getMessage
      assert(m.contains("rejected.jsonl incomplete=1"), s"$field: $m")
    }
  }

  test("RR1: a sound run reports zero unreadable ER records per file and records the rule") {
    val r = base
    for f <- "accepted.jsonl" :: Outputs.MergeVetoFiles; p <- ErRecordCheck.Problems do
      assertEquals(r.count(ErRecordCheck.countKey(p, f)), 0L, s"$p $f")
    val params = ujson.read(JobFiles.readText(spark, s"${r.outputBase}/params.json"))
    assertEquals(params("erDecisionRecordRule").str, ErRecordCheck.Rule)
  }

  // ---- Phase 11 repair-03 F2 (consumer side): verified, single-run ER input ----------------------

  test("F2: a fresh accepted.jsonl next to stale veto files (killed rerun) fails before output") {
    // Run R1 (old) had no John-Jane veto; run R2 (new) vetoes it. A rerun of R2 into R1's
    // directory, killed after accepted.jsonl, leaves R2's accepted links with R1's vetoes and R1's
    // manifest. Reading it would join John and Jane through J.
    val root = Files.createTempDirectory("sc-spark-er-stale")
    val in   = fixture(root, identity) // R2 at root/er
    val r1   = root.resolve("er-r1")
    Outputs.writeRun(
      r1,
      erRecords(erRunOld).filterNot(_.pairId == d1VetoKey),
      10,
      erRunOld,
      erPolicy
    )
    Files.copy(
      root.resolve("er/accepted.jsonl"),
      r1.resolve("accepted.jsonl"),
      java.nio.file.StandardCopyOption.REPLACE_EXISTING
    )
    assert(!lines(r1.resolve("rejected.jsonl")).exists(_.contains(d1VetoKey)), "precondition")
    val m = runFailing(root, in.copy(erDir = uri(r1))).getMessage
    assert(m.contains(VerifyFailed), m)
    assert(m.contains("accepted.jsonl: sha256"), m)
    assert(m.contains(s"not this run: pipelineRunId $erRun"), m)
  }

  test("F2: a 0-byte veto file that the manifest does not describe is never 'no vetoes'") {
    val root = Files.createTempDirectory("sc-spark-er-zero")
    val in   = fixture(
      root,
      identity,
      tamper = er => Files.write(er.resolve("rejected.jsonl"), Array.emptyByteArray)
    )
    assertEquals(Files.size(root.resolve("er/rejected.jsonl")), 0L)
    val m = runFailing(root, in).getMessage
    assert(m.contains(VerifyFailed), m)
    assert(m.contains("rejected.jsonl: sha256"), m)
    assert(m.contains("rejected.jsonl: 0 records, manifest 3"), m)
  }

  test("F2: a missing manifest fails the run (no fallback to unverified reading)") {
    val root = Files.createTempDirectory("sc-spark-er-nomanifest")
    val in   =
      fixture(root, identity, tamper = er => Files.delete(er.resolve(Outputs.ManifestFileName)))
    val m = runFailing(root, in).getMessage
    assert(m.contains(VerifyFailed), m)
    assert(m.contains(s"${Outputs.ManifestFileName} missing in"), m)
  }

  test("F2: a forged manifest fails the run") {
    val emptySha                   = Outputs.sha256Hex(Array.emptyByteArray)
    def hideVetoes(er: Path): Unit =
      val m = readManifest(er)
      writeManifest(
        er,
        m.copy(files =
          m.files.map(e =>
            if e.name == "rejected.jsonl" then ManifestEntry(e.name, 0L, emptySha) else e
          )
        )
      )
    def otherRun(er: Path): Unit =
      writeManifest(er, readManifest(er).copy(pipelineRunId = erRunOld))
    def dropConflict(er: Path): Unit =
      val m = readManifest(er)
      writeManifest(er, m.copy(files = m.files.filterNot(_.name == "conflict.jsonl")))
    def garbage(er: Path): Unit =
      Files.write(er.resolve(Outputs.ManifestFileName), "{".getBytes(UTF_8))
      ()
    val forgeries = List[(String, Path => Unit, String)](
      (
        "hides the vetoes (rejected.jsonl declared empty)",
        hideVetoes,
        "rejected.jsonl: 3 records, manifest 0"
      ),
      ("claims another ER run", otherRun, s"not this run: pipelineRunId $erRun"),
      ("omits conflict.jsonl", dropConflict, "files must be exactly"),
      ("is not a manifest", garbage, "unreadable")
    )
    forgeries.foreach { (what, forge, expected) =>
      val root = Files.createTempDirectory("sc-spark-er-forged")
      val m    = runFailing(root, fixture(root, identity, tamper = forge)).getMessage
      assert(m.contains(VerifyFailed), s"$what: $m")
      assert(m.contains(expected), s"$what: $m")
    }
  }

  test("F2: a decision of another ER run in a resealed directory fails the run (mixed run ID)") {
    val root = Files.createTempDirectory("sc-spark-er-mixed")
    val in   = fixture(
      root,
      identity,
      tamper = { er =>
        editFile(er, "rejected.jsonl")(
          _ :+ write(rec("d1-2j", "d1-3jane", ResolutionStatus.Rejected, 1.0, erRunOld))
        )
        reseal(er)
      }
    )
    val m = runFailing(root, in).getMessage
    assert(m.contains(erRunOld), m)
    assert(m.contains("not this run"), m)
  }

  test("F2 defense in depth: Spark-side run-ID and record-count check on what it read") {
    // Independent of the ER verifier: a frame read from a directory holding two ER runs and a
    // short file is refused against the manifest it claims.
    val dir = Files.createTempDirectory("sc-spark-er-runcheck")
    writeLines(
      dir.resolve("accepted.jsonl"),
      List(record("l1", "l2", ResolutionStatus.Accepted, 0.9))
    )
    writeLines(
      dir.resolve("rejected.jsonl"),
      List(write(rec("l2", "l5", ResolutionStatus.Rejected, 0.8, erRunOld)))
    )
    writeLines(dir.resolve("unresolved.jsonl"), Nil)
    writeLines(dir.resolve("conflict.jsonl"), Nil)
    val sha      = "0" * 64
    val manifest = DecisionsManifest(
      Outputs.ManifestSchemaVersion,
      erRun,
      erPolicy,
      Features.SpecVersion,
      Resolver.RecordSchemaVersion,
      List(
        ManifestEntry("accepted.jsonl", 1L, sha),
        ManifestEntry("rejected.jsonl", 1L, sha),
        ManifestEntry("unresolved.jsonl", 0L, sha),
        ManifestEntry("conflict.jsonl", 1L, sha)
      )
    )
    val frame = PartA.readErDecisions(spark, uri(dir), "accepted.jsonl" :: Outputs.MergeVetoFiles)
    val (problems, distinct) = PartA.erRunProblems(frame, manifest)
    assertEquals(distinct, 2L)
    assertEquals(
      problems,
      List(
        "conflict.jsonl: Spark read 0 records, manifest recordCount 1",
        s"decision records carry 2 distinct provenance.pipelineRunId value(s) ($erRun, $erRunOld); " +
          s"expected exactly the manifest's $erRun"
      )
    )
    // A single-run frame whose counts match the manifest passes.
    val single = PartA.readErDecisions(spark, uri(dir), List("accepted.jsonl"))
    val counts = Map("accepted.jsonl" -> 1L).withDefaultValue(0L)
    val clean  = manifest.copy(files =
      manifest.files.map(e => e.copy(recordCount = counts(e.name)))
    )
    assertEquals(PartA.erRunProblems(single, clean), (Nil, 1L))
  }

  test("F2: params.json and the run manifest record the verified ER run") {
    val r      = base
    val params = ujson.read(JobFiles.readText(spark, s"${r.outputBase}/params.json"))
    val erp    = params("erDecisions")
    assertEquals(erp("pipelineRunId").str, erRun)
    assertEquals(erp("policyVersion").str, erPolicy)
    assertEquals(erp("featuresVersion").str, Features.SpecVersion)
    assertEquals(params("erManifestRule").str, ErManifestGate.Rule)
    val runManifest =
      upickle.default.read[RunManifest](JobFiles.readText(spark, s"${r.outputBase}/manifest.json"))
    val input = runManifest.inputs.filter(_.snapshotId == PartA.ErDecisionsManifestSnapshot)
    assertEquals(input.map(_.checksum), List(erp("manifestSha256").str))
  }

  test("RR2 (Law 3): every refused row carries the vetoing decision's source dataset") {
    val df = spark.read.parquet(s"${base.outputBase}/identity-refused-links")
    assert(df.columns.contains("vetoSourceDataset"), df.columns.mkString(", "))
    val rows = df.collect().toList
    assertEquals(rows.size, 5)
    rows.foreach { row =>
      assertEquals(row.getAs[String]("vetoSourceDataset"), "scholarchain-pipeline", row)
      assertEquals(row.getAs[String]("vetoSourceRecordId"), row.getAs[String]("vetoPairId"), row)
      assertEquals(row.getAs[String]("vetoSourcePipelineRunId"), erRun, row)
    }
  }
