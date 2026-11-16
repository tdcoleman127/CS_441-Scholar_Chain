package edu.uic.cs441.scholarchain.spark

import edu.uic.cs441.scholarchain.core.json.Wire
import edu.uic.cs441.scholarchain.core.model.*
import edu.uic.cs441.scholarchain.er.{
  Features,
  MatchPath,
  Outputs,
  ResolutionRecord,
  ResolutionStatus,
  Resolver
}
import org.apache.spark.sql.{Row, SparkSession}
import org.apache.spark.sql.types.{StringType, StructField, StructType}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 * Hand-checkable fixture for Part A jobs 1 to 4 (window 2020-2022).
 *
 * Works (year): w01 2020 {a1, b1, c1}; w02 2021 {a2, a2b, b2}; w03 2022 {c3, d3}; w04 2019 {a4,
 * e4}; w05 2021 {e5, f5}; w06 2020 {g6}; w07 no year {a7, d7}; w08 2021 {b8, x8}; w09 2022 {x9};
 * w10 2022 {x10}; w11 2021 {u11, v11} is missing from the works table.
 *
 * Accepted mention links: a1-a2, a1-a2b, a2-a4, b1-b2, b2-b8, c1-c3, e4-e5, x8-x9, x9-x10, and
 * a1-zz (dangling). One accepted work-scope link (not applied). Conflict x8-x10 lies inside the
 * closure cluster {x8,x9,x10}, so that cluster is split (F2): x8, x9, x10 become the singleton
 * researchers X8, X9, X10 with identityStatus conflicted-split, and both of its links are written
 * to identity-refused-links (conflicted-cluster-split). Unresolved a1-a7 keeps a7 unmerged
 * (researcher A7). Rejected a4-e4 joins two different closure components, so it changes nothing.
 *
 * Researchers: A{a1,a2,a2b,a4} B{b1,b2,b8} C{c1,c3} E{e4,e5} merged; X8, X9, X10 conflicted-split;
 * singletons A7, D3, D7, F, G, U, V: 14.
 *
 * Window edges: A-B (w01, w02: 2, 2020-2021), A-C (w01), B-C (w01), C-D3 (w03), E-F (w05), B-X8
 * (w08, 2021): 6. Excluded pairs: a1-q unresolved endpoint, u11-v11 missing work, a7-d7 missing
 * year, a4-e4 out of window, a2-a2b self-pair; a2b-b2 collapses onto a2-b2 (same work). b8-x8 is
 * kept: x8 is a singleton researcher like any other.
 *
 * Metrics (P, C, T, A, M, W, M-works): A 2,2,3,1.5,2,3,[w01]; B 3,3,4,4/3,2,4,[w01]; C
 * 2,3,3,1.5,2,3,[w01]; D3 1,1,1,1,1,1,[w03]; E 1,1,1,1,1,1,[w05]; F same,[w05]; G
 * 1,0,0,0,0,0,[w06]; X8 1,1,1,1,1,1,[w08]; X9 1,0,0,0,0,0,[w09]; X10 1,0,0,0,0,0,[w10].
 *
 * Components: {A,B,C,D3,X8} 5, {E,F} 2, {G} 1, {X9} 1, {X10} 1 -> 5 components, largest 5/10 = 0.5,
 * 3 singletons.
 */
class PartASuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(600, "s")

  private val hadoopRun = "run:v1:" + "a" * 64
  private val erRun     = "run:v1:" + "b" * 64
  private val erPolicy  = "er-policy-test"
  private val window    = YearWindow(2020, 2022)
  private val policy    = "graph-policy-test-v1"

  private def wid(n: String) = s"work:dblp:t/$n"

  private val years: Map[String, Option[Int]] = Map(
    "w01" -> Some(2020),
    "w02" -> Some(2021),
    "w03" -> Some(2022),
    "w04" -> Some(2019),
    "w05" -> Some(2021),
    "w06" -> Some(2020),
    "w07" -> None,
    "w08" -> Some(2021),
    "w09" -> Some(2022),
    "w10" -> Some(2022),
    "w11" -> Some(2021)
  )

  // (mention suffix, work, position, name)
  private val mentions = List(
    ("a1", "w01", 0, "alice adams"),
    ("b1", "w01", 1, "bob brown"),
    ("c1", "w01", 2, "carol chen"),
    ("a2", "w02", 0, "alice adams"),
    ("a2b", "w02", 1, "a adams"),
    ("b2", "w02", 2, "bob brown"),
    ("c3", "w03", 0, "carol chen"),
    ("d3", "w03", 1, "dan diaz"),
    ("a4", "w04", 0, "alice adams"),
    ("e4", "w04", 1, "eve evans"),
    ("e5", "w05", 0, "eve evans"),
    ("f5", "w05", 1, "fay fox"),
    ("g6", "w06", 0, "gus gray"),
    ("a7", "w07", 0, "alice adams"),
    ("d7", "w07", 1, "dan diaz"),
    ("b8", "w08", 0, "bob brown"),
    ("x8", "w08", 1, "xi xu"),
    ("x9", "w09", 0, "xi xu"),
    ("x10", "w10", 0, "xi xu"),
    ("u11", "w11", 0, "uma ulm"),
    ("v11", "w11", 1, "vic vo")
  )

  private def mid(s: String)                              = s"m-$s"
  private def prov(work: String, run: String = hadoopRun) =
    Provenance(SourceType.Dblp, s"t/$work", run)
  private def provJson(work: String) =
    s"""{"sourceDataset":"dblp","sourceRecordId":"t/$work","pipelineRunId":"$hadoopRun"}"""
  private def yearJson(w: String) = years(w).fold("null")(_.toString)

  private def authorPaperLine(m: (String, String, Int, String)): String =
    val (s, w, pos, name) = m
    s"""{"mentionId":"${mid(s)}","workId":"${wid(w)}","authorPosition":$pos,""" +
      s""""normalizedName":"$name","year":${yearJson(w)},"provenance":${provJson(w)}}"""

  private def pairLine(x: String, y: String, w: String): String =
    val pos      = mentions.map(m => m._1 -> m._3).toMap
    val (lo, hi) = if mid(x).compareTo(mid(y)) <= 0 then (x, y) else (y, x)
    val (pl, ph) = (pos.getOrElse(lo, 9), pos.getOrElse(hi, 9))
    s"""{"mentionLow":"${mid(lo)}","mentionHigh":"${mid(hi)}","positionLow":$pl,""" +
      s""""positionHigh":$ph,"nameLow":"n","nameHigh":"n","workId":"${wid(w)}",""" +
      s""""year":${yearJson(w)},"provenance":${provJson(w)}}"""

  private def resolution(scope: String, a: String, b: String, st: ResolutionStatus) =
    val (lo, hi) = if a.compareTo(b) <= 0 then (a, b) else (b, a)
    val pairId   = s"$scope|$lo|$hi"
    val outcome  = st match
      case ResolutionStatus.Accepted => MatchOutcome.Merge
      case ResolutionStatus.Rejected => MatchOutcome.NonMatch
      case _                         => MatchOutcome.NeedsReview
    ResolutionRecord(
      Resolver.RecordSchemaVersion,
      s"erdecision:v1:$pairId",
      pairId,
      scope,
      "bk",
      lo,
      hi,
      st,
      MatchPath.Probabilistic,
      outcome,
      MatchDecider.Model,
      Some(0.9),
      0.9,
      erPolicy,
      Features.SpecVersion,
      None,
      Nil,
      Nil,
      Provenance(SourceType.ScholarChainPipeline, pairId, erRun),
      prov("w01"),
      prov("w01"),
      Some(0.9),
      Some(true)
    )

  private def writeLines(p: Path, lines: Seq[String]): Unit =
    Files.createDirectories(p.getParent)
    Files.write(p, lines.mkString("", "\n", "\n").getBytes(UTF_8))

  private[spark] def uri(p: Path): String =
    val s = p.toAbsolutePath.toString.replace(java.io.File.separatorChar, '/')
    if s.startsWith("/") then "file://" + s else "file:///" + s

  private[spark] def fixture(root: Path): PartAInputs =
    val ap = root.resolve("team-extract/authorpapers/part-r-00000")
    writeLines(ap, mentions.map(authorPaperLine) :+ "this line is not json")
    val pairs = List(
      pairLine("a1", "b1", "w01"),
      pairLine("a1", "c1", "w01"),
      pairLine("b1", "c1", "w01"),
      pairLine("a1", "q", "w01"),
      pairLine("a2", "a2b", "w02"),
      pairLine("a2", "b2", "w02"),
      pairLine("a2b", "b2", "w02"),
      pairLine("c3", "d3", "w03"),
      pairLine("a4", "e4", "w04"),
      pairLine("e5", "f5", "w05"),
      pairLine("a7", "d7", "w07"),
      pairLine("b8", "x8", "w08"),
      pairLine("u11", "v11", "w11")
    )
    writeLines(root.resolve("team-extract/pairs/part-r-00000"), pairs)
    val works = years.keys.toList.sorted.filterNot(_ == "w11").map { w =>
      Wire.encode(WorkRecord(1, wid(w), s"title $w", years(w), None, Nil, Nil, prov(w)))
    }
    writeLines(root.resolve("dblp-extract/works/part-r-00000"), works)
    val acc = List(
      "a1" -> "a2",
      "a1" -> "a2b",
      "a2" -> "a4",
      "b1" -> "b2",
      "b2" -> "b8",
      "c1" -> "c3",
      "e4" -> "e5",
      "x8" -> "x9",
      "x9" -> "x10",
      "a1" -> "zz"
    ).map((a, b) => resolution("mention", mid(a), mid(b), ResolutionStatus.Accepted)) :+
      resolution("work", wid("w01"), wid("w02"), ResolutionStatus.Accepted)
    // The ER directory is written by the real ER writer, so it carries a verified
    // _DECISIONS_MANIFEST.json (Phase 11 repair-03 F2): one file per status.
    val er = root.resolve("er")
    Outputs.writeRun(
      er,
      acc ++ List(
        resolution("mention", mid("x8"), mid("x10"), ResolutionStatus.Conflict),
        resolution("mention", mid("a1"), mid("a7"), ResolutionStatus.Unresolved),
        // A veto between two different closure components (A and E) changes nothing.
        resolution("mention", mid("a4"), mid("e4"), ResolutionStatus.Rejected)
      ),
      10,
      erRun,
      erPolicy
    )
    PartAInputs(
      uri(root.resolve("team-extract/authorpapers")),
      uri(root.resolve("team-extract/pairs")),
      uri(root.resolve("dblp-extract/works")),
      uri(er)
    )

  private lazy val spark: SparkSession = SparkSession
    .builder()
    .master("local[2]")
    .appName("scholarchain-spark-part-a-test")
    .config("spark.ui.enabled", "false")
    .config("spark.driver.host", "127.0.0.1")
    .config("spark.sql.shuffle.partitions", "4")
    .getOrCreate()

  override def afterAll(): Unit = spark.stop()

  // Researcher IDs by cluster name: anchor = smallest mention ID of the cluster. The conflicted
  // closure {x8,x9,x10} is split, so each X mention anchors itself.
  private val anchorOf = Map(
    "A"   -> "a1",
    "A7"  -> "a7",
    "B"   -> "b1",
    "C"   -> "c1",
    "D3"  -> "d3",
    "D7"  -> "d7",
    "E"   -> "e4",
    "F"   -> "f5",
    "G"   -> "g6",
    "X8"  -> "x8",
    "X9"  -> "x9",
    "X10" -> "x10",
    "U"   -> "u11",
    "V"   -> "v11"
  )
  private[spark] val rid = anchorOf.view.mapValues(a => IdUdfs.researcherId("dblp", mid(a))).toMap
  private val nameOf     = rid.map(_.swap)

  test("CC on known graphs: empty, path, star, clique, disconnected with isolated vertices") {
    val schemaV = StructType(Seq(StructField("id", StringType)))
    val schemaE = StructType(Seq(StructField("src", StringType), StructField("dst", StringType)))
    def cc(vs: Seq[String], es: Seq[(String, String)]): Map[String, String] =
      val v = spark.createDataFrame(vs.map(Row(_)).asJava, schemaV)
      val e = spark.createDataFrame(es.map((a, b) => Row(a, b)).asJava, schemaE)
      ConnectedComponents
        .run(v, e, 20)
        .labels
        .collect()
        .map(r => r.getString(0) -> r.getString(1))
        .toMap
    assertEquals(cc(Nil, Nil), Map.empty[String, String])
    assertEquals(
      cc(
        Seq("e", "d", "c", "b", "a"),
        Seq(
          "e" -> "d",
          "d" -> "c",
          "c" -> "b",
          "b" -> "a"
        )
      ).values.toSet,
      Set("a")
    )
    assertEquals(
      cc(Seq("h", "x", "y", "z"), Seq("h" -> "x", "h" -> "y", "z" -> "h")).values.toSet,
      Set("h")
    )
    val k4 = Seq("p", "q", "r", "s")
    assertEquals(cc(k4, for a <- k4; b <- k4 if a < b yield a -> b).values.toSet, Set("p"))
    assertEquals(
      cc(Seq("a", "b", "c", "d", "e", "f"), Seq("a" -> "b", "c" -> "d", "d" -> "e", "e" -> "e")),
      Map("a" -> "a", "b" -> "a", "c" -> "c", "d" -> "c", "e" -> "c", "f" -> "f")
    )
    // A path of diameter 2 cannot converge in one iteration: fail closed, never a partial result.
    intercept[IllegalStateException](
      ConnectedComponents.run(
        spark.createDataFrame(Seq(Row("c"), Row("b"), Row("a")).asJava, schemaV),
        spark.createDataFrame(Seq(Row("c", "b"), Row("b", "a")).asJava, schemaE),
        1
      )
    )
  }

  test("F1: the mention-to-researcher join carries no broadcast hint") {
    import org.apache.spark.sql.functions.{col, lit, struct}
    val key  = "spark.sql.autoBroadcastJoinThreshold"
    val prev = spark.conf.getOption(key)
    // With auto-broadcast disabled, only an explicit hint could still plan a broadcast.
    spark.conf.set(key, "-1")
    try
      val clustered = spark.range(10).select(
        col("id").cast("string").as("mentionId"),
        col("id").cast("string").as("anchorMentionId"),
        lit("w").as("workId"),
        lit(0).as("authorPosition"),
        lit("n").as("normalizedName"),
        lit(2020).as("year"),
        struct(lit("dblp").as("sourceDataset")).as("provenance"),
        lit(null).cast("string").as("vetoConstrainedClosureAnchorMentionId")
      )
      val clusters = spark.range(10).select(
        col("id").cast("string").as("anchorMentionId"),
        lit("r").as("researcherId"),
        lit("singleton").as("identityStatus"),
        lit(1L).as("clusterSize"),
        lit(null).cast("string").as("conflictedClusterAnchorMentionId")
      )
      val qe = PartA.attachResearchers(clustered, clusters).queryExecution
      assert(!qe.optimizedPlan.toString.toLowerCase.contains("broadcast"), qe.optimizedPlan)
      assert(!qe.executedPlan.toString.toLowerCase.contains("broadcast"), qe.executedPlan)
    finally prev.fold(spark.conf.unset(key))(v => spark.conf.set(key, v))
  }

  test("Part A jobs 1-4 produce the hand-checked graph, metrics, components, and reports") {
    val root = Files.createTempDirectory("sc-spark-part-a")
    val in   = fixture(root)
    val p    = PartAParams(window, policy, ccMaxIterations = 20, shufflePartitions = 4)
    val r = PartA.run(spark, in, p, "sha256:test-core-config", "test", 1, uri(root.resolve("out")))

    assertEquals(r.failures, Nil, s"invariant failures: ${r.failures}")
    val expectCounts = List(
      "inputAuthorPaperRows"                          -> 22L,
      "inputAuthorPaperMalformed"                     -> 1L,
      "mentions"                                      -> 21L,
      "erAcceptedRecords"                             -> 11L,
      "erAcceptedWorkLinksNotApplied"                 -> 1L,
      "erAcceptedMentionLinks"                        -> 10L,
      "erAcceptedMentionLinksApplied"                 -> 9L,
      "erAcceptedMentionLinksDangling"                -> 1L,
      "conflictedClusters"                            -> 1L,
      "conflictedClusterMentionsSplit"                -> 3L,
      "erAcceptedMentionLinkPairs"                    -> 9L,
      "erVetoMentionPairs"                            -> 3L,
      "erVetoMentionPairsWithinClosure"               -> 1L,
      "identityConstrainedComponents"                 -> 0L,
      "identityConstrainedMentions"                   -> 0L,
      "researchersVetoConstrained"                    -> 0L,
      "identityLinksRefused:veto-would-join"          -> 0L,
      "identityLinksRefused:conflicted-cluster-split" -> 2L,
      "researchers"                                   -> 14L,
      "researchersMerged"                             -> 4L,
      "researchersConflictedSplit"                    -> 3L,
      "maxIdentityClusterSize"                        -> 4L,
      "works"                                         -> 10L,
      "authoredMentionsMissingWork"                   -> 2L,
      "authored"                                      -> 18L,
      "authoredCollapsedMentions"                     -> 1L,
      "inputPairRows"                                 -> 13L,
      "pairsExcluded:unresolved-endpoint"             -> 1L,
      "pairsExcluded:missing-work"                    -> 1L,
      "pairsExcluded:missing-year"                    -> 1L,
      "pairsExcluded:out-of-window"                   -> 1L,
      "pairsExcluded:self-pair"                       -> 1L,
      "pairsExcluded:conflicted-endpoint"             -> 0L,
      "pairsKept"                                     -> 8L,
      "pairsCollapsedSameWorkDuplicate"               -> 1L,
      "coauthorEdges"                                 -> 6L,
      "maxJointPublicationCount"                      -> 2L,
      "researchersWithMetrics"                        -> 10L,
      "maxTeamSizeInWindow"                           -> 3L,
      "maxDistinctCoauthorCount"                      -> 3L,
      "components"                                    -> 5L,
      "largestComponentSize"                          -> 5L,
      "singletonResearchers"                          -> 3L
    )
    val invNames = r.invariantFailures.map(_._1).toSet
    assert(invNames("conflicted-split-not-singleton"), invNames)
    assert(invNames("conflict-pair-same-researcher"), invNames)
    List(
      "veto-pair-same-researcher",
      "refused-link-same-researcher",
      "refused-link-missing-evidence",
      "refused-link-duplicate",
      "accepted-link-accounting",
      "constrained-mention-unassigned"
    ).foreach(n => assert(invNames(n), n))
    expectCounts.foreach((k, v) => assertEquals(r.count(k), v, k))
    assertEqualsDouble(r.largestComponentFraction, 0.5, 1e-12)

    val win = s"${r.outputBase}/window=2020-2022"

    // Job 1: researchers and identity status.
    val researchers = spark.read.parquet(s"${r.outputBase}/researchers").collect()
      .map(row =>
        nameOf(row.getAs[String]("researcherId")) ->
          (
            row.getAs[String]("identityStatus"),
            row.getAs[String]("displayName"),
            row.getAs[String]("pipelineRunId")
          )
      )
      .toMap
    assertEquals(researchers.keySet, rid.keySet)
    assertEquals(researchers("A")._1, "merged")
    assertEquals(researchers("A")._2, "alice adams")
    assertEquals(researchers("A7")._1, "singleton")
    List("X8", "X9", "X10").foreach(n => assertEquals(researchers(n)._1, "conflicted-split", n))
    assert(researchers.values.forall(_._3 == r.runId))

    // F2: a conflicted closure is never merged. Each of x8, x9, x10 is its own researcher (ID from
    // the mention itself), cluster size 1, the pre-split anchor m-x10 kept for visibility, and no
    // AUTHORED row of an X researcher combines works. Under the old merge policy all three mapped to
    // one researcher with three AUTHORED rows.
    val xMap = spark.read.parquet(s"${r.outputBase}/mention-researcher-map")
      .where(org.apache.spark.sql.functions.col("mentionId").isin(mid("x8"), mid("x9"), mid("x10")))
      .collect()
      .map(row =>
        row.getAs[String]("mentionId") ->
          (
            row.getAs[String]("researcherId"),
            row.getAs[String]("identityStatus"),
            row.getAs[Long]("clusterSize"),
            row.getAs[String]("conflictedClusterAnchorMentionId")
          )
      )
      .toMap
    assertEquals(
      xMap,
      List("x8", "x9", "x10")
        .map(s =>
          mid(s) -> (IdUdfs.researcherId("dblp", mid(s)), "conflicted-split", 1L, mid("x10"))
        )
        .toMap
    )
    val xAuthored = spark.read.parquet(s"${r.outputBase}/authored").collect()
      .filter(row => Set("X8", "X9", "X10").contains(nameOf(row.getAs[String]("researcherId"))))
      .map(row =>
        nameOf(row.getAs[String]("researcherId")) ->
          (
            row.getAs[String]("workId"),
            row.getList[String](row.fieldIndex("mentionIds")).asScala.toList
          )
      )
      .toList
      .sorted
    assertEquals(
      xAuthored,
      List(
        "X10" -> (wid("w10"), List(mid("x10"))),
        "X8"  -> (wid("w08"), List(mid("x8"))),
        "X9"  -> (wid("w09"), List(mid("x9")))
      )
    )
    // A non-conflicted mention carries no conflicted-cluster anchor.
    assertEquals(
      spark.read.parquet(s"${r.outputBase}/mention-researcher-map")
        .where(org.apache.spark.sql.functions.col("conflictedClusterAnchorMentionId").isNotNull)
        .count(),
      3L
    )

    // Job 2: edges, exactly.
    def key(a: String, b: String) =
      if rid(a) < rid(b) then (rid(a), rid(b)) else (rid(b), rid(a))
    val edges = spark.read.parquet(s"$win/coauthor-edges").collect().map { row =>
      (row.getAs[String]("researcherLow"), row.getAs[String]("researcherHigh")) ->
        (
          row.getAs[Int]("jointPublicationCount"),
          row.getAs[Int]("firstCollaborationYear"),
          row.getAs[Int]("lastCollaborationYear"),
          row.getList[String](row.fieldIndex("supportingWorkIds")).asScala.toList
        )
    }.toMap
    assertEquals(
      edges,
      Map(
        key("A", "B")  -> (2, 2020, 2021, List(wid("w01"), wid("w02"))),
        key("A", "C")  -> (1, 2020, 2020, List(wid("w01"))),
        key("B", "C")  -> (1, 2020, 2020, List(wid("w01"))),
        key("C", "D3") -> (1, 2022, 2022, List(wid("w03"))),
        key("E", "F")  -> (1, 2021, 2021, List(wid("w05"))),
        key("B", "X8") -> (1, 2021, 2021, List(wid("w08")))
      )
    )

    // Job 3: metrics, exactly.
    val metrics = spark.read.parquet(s"$win/researcher-metrics").collect().map { row =>
      nameOf(row.getAs[String]("researcherId")) -> (
        row.getAs[Long]("publicationCount"),
        row.getAs[Long]("distinctCoauthorCount"),
        row.getAs[Long]("totalCoauthorOccurrences"),
        row.getAs[Double]("averageCoauthorsPerPaper"),
        row.getAs[Long]("maximumCoauthorsOnOnePaper"),
        row.getAs[Long]("weightedDegree"),
        row.getList[String](row.fieldIndex("maximumCoauthorWorkIds")).asScala.toList
      )
    }.toMap
    val expected = Map(
      "A"   -> (2L, 2L, 3L, 1.5, 2L, 3L, List(wid("w01"))),
      "B"   -> (3L, 3L, 4L, 4.0 / 3.0, 2L, 4L, List(wid("w01"))),
      "C"   -> (2L, 3L, 3L, 1.5, 2L, 3L, List(wid("w01"))),
      "D3"  -> (1L, 1L, 1L, 1.0, 1L, 1L, List(wid("w03"))),
      "E"   -> (1L, 1L, 1L, 1.0, 1L, 1L, List(wid("w05"))),
      "F"   -> (1L, 1L, 1L, 1.0, 1L, 1L, List(wid("w05"))),
      "G"   -> (1L, 0L, 0L, 0.0, 0L, 0L, List(wid("w06"))),
      "X8"  -> (1L, 1L, 1L, 1.0, 1L, 1L, List(wid("w08"))),
      "X9"  -> (1L, 0L, 0L, 0.0, 0L, 0L, List(wid("w09"))),
      "X10" -> (1L, 0L, 0L, 0.0, 0L, 0L, List(wid("w10")))
    )
    val excludedConflict = spark.read.parquet(s"$win/researcher-metrics").collect()
      .map(_.getAs[Long]("excludedConflictPairOccurrences"))
    assert(excludedConflict.forall(_ == 0L), excludedConflict.toList)
    assertEquals(metrics.keySet, expected.keySet)
    expected.foreach { (n, e) =>
      val m = metrics(n)
      assertEquals((m._1, m._2, m._3, m._5, m._6, m._7), (e._1, e._2, e._3, e._5, e._6, e._7), n)
      assertEqualsDouble(m._4, e._4, 1e-12, n)
    }

    // Job 4: components, exactly (component ID = smallest member researcher ID).
    val comps = spark.read.parquet(s"$win/components").collect()
      .map(row =>
        nameOf(row.getAs[String]("researcherId")) ->
          (row.getAs[String]("componentId"), row.getAs[Long]("componentSize"))
      )
      .toMap
    val groups =
      List(Set("A", "B", "C", "D3", "X8"), Set("E", "F"), Set("G"), Set("X9"), Set("X10"))
    groups.foreach(g =>
      g.foreach(n => assertEquals(comps(n), (g.map(rid).min, g.size.toLong), n))
    )
    assertEquals(comps.keySet, groups.flatten.toSet)

    // Reports and manifest.
    List("manifest.json", "params.json", "counts.json", "invariants.json").foreach { f =>
      assert(JobFiles.exists(spark, s"${r.outputBase}/$f"), f)
    }
    val manifestText = spark.read.text(s"${r.outputBase}/manifest.json").collect()
      .map(_.getString(0)).mkString("\n")
    assertEquals(read[RunManifest](manifestText).runId, r.runId)

    // F2 (repair-03): the verified ER run is recorded, and the ER manifest hash is a run input.
    val erManifestSha =
      "sha256:" +
        Outputs.sha256Hex(Files.readAllBytes(root.resolve(s"er/${Outputs.ManifestFileName}")))
    val erp = ujson.read(JobFiles.readText(spark, s"${r.outputBase}/params.json"))("erDecisions")
    assertEquals(erp("pipelineRunId").str, erRun)
    assertEquals(erp("policyVersion").str, erPolicy)
    assertEquals(erp("featuresVersion").str, Features.SpecVersion)
    assertEquals(erp("schemaVersion").num, Resolver.RecordSchemaVersion.toDouble)
    assertEquals(erp("manifestSha256").str, erManifestSha)
    assertEquals(
      erp("files").arr.map(f => f("name").str -> f("recordCount").num.toLong).toList,
      List(
        "accepted.jsonl"   -> 11L,
        "rejected.jsonl"   -> 1L,
        "unresolved.jsonl" -> 1L,
        "conflict.jsonl"   -> 1L
      )
    )
    assert(
      read[RunManifest](manifestText).inputs.contains(
        RunInput(SourceType.ScholarChainPipeline, PartA.ErDecisionsManifestSnapshot, erManifestSha)
      ),
      manifestText
    )
    assertEquals(r.count("erDecisionDistinctRunIds"), 1L)

    // A rerun with the same attempt must refuse to overwrite.
    intercept[IllegalStateException](
      PartA.run(spark, in, p, "sha256:test-core-config", "test", 1, uri(root.resolve("out")))
    )
  }

  private def read[A: upickle.default.Reader](s: String): A = upickle.default.read[A](s)
