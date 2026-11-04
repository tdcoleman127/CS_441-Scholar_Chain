package edu.uic.cs441.scholarchain.spark

import edu.uic.cs441.scholarchain.core.ids.StableIds
import edu.uic.cs441.scholarchain.core.json.Wire
import edu.uic.cs441.scholarchain.core.validation.Validators.given
import edu.uic.cs441.scholarchain.core.model.*
import org.apache.spark.sql.{functions as F, DataFrame, Row, SparkSession}
import org.apache.spark.sql.types.{StringType, StructField, StructType}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 * Part B (WO-06b) on hand-checkable graphs and on the Part A fixture (PartASuite, window
 * 2020-2022). This suite's SparkContext has a checkpoint directory, so iterations use reliable
 * checkpoints (PartASuite exercises the localCheckpoint fallback).
 *
 * Letter graphs (IDs compare as strings; LP = label propagation with self vote, ties to the
 * smallest label):
 *   - Two triangles {a,b,c}, {d,e,f} joined by bridge c-d. LP iteration 1: a,b,c -> a; d -> c (min
 *     of c,d,e,f); e,f -> d. Iteration 2: d sees labels a,c,d,d -> d (count 2); e,f -> d. Fixed
 *     point: {a,b,c} label a, {d,e,f} label d, cross edge c-d. Bridges: c and d have
 *     crossCommunityDegree 1 and touch 2 communities; the rest 0 and 1. Coreness: all 2.
 *   - Path a-b-c-d-e. LP after 2 iterations: a,a,a,b,c (not converged); converges to all a by
 *     iteration 5. Coreness all 1. BFS a->e: depth 4 via a,b,c,d,e; with depth limit 3: not found.
 *   - Star h-{x,y,z}: LP all h; coreness all 1. K4 {p,q,r,s}: LP all p; coreness 3; pendant t on p:
 *     coreness 1. Isolated vertex: own label, coreness 0. Pair a-b: both a (no oscillation).
 *   - Square a-b, a-c, b-d, c-d: BFS a->d picks predecessor min(b,c) = b, so path a,b,d.
 *
 * Fixture graph: vertices A,B,C,D3,E,F,G,X8,X9,X10; edges A-B, A-C, B-C, C-D3, E-F, B-X8.
 *   - Coreness: A,B,C 2 (triangle); D3, X8, E, F 1; G, X9, X10 0. Distribution {0:3, 1:4, 2:3}.
 *   - Paths (max depth 2): A->D3 found, depth 2, A,C,D3; X8->C found, depth 2, X8,B,C; D3->X8 needs
 *     3 hops: beyond-depth-limit; E->A disconnected; G->G invalid-pair; U->A endpoint-missing (U
 *     has no metrics in the window).
 *   - Communities depend on the hash order of researcher IDs, so the expected partition comes from
 *     a direct Scala transcription of the same rule (`refLp`); order-free facts are asserted too:
 *     {E,F} together, G, X9, X10 singletons.
 *   - Researcher A profile input: coauthor edges A-B, A-C; names {B: bob brown, C: carol chen};
 *     representative works w02 (2021), w01 (2020); metrics degree 2, weighted-degree 3,
 *     component-size 5, core-number 2.
 */
class PartBSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(900, "s")

  private lazy val spark: SparkSession =
    val s = SparkSession
      .builder()
      .master("local[2]")
      .appName("scholarchain-spark-part-b-test")
      .config("spark.ui.enabled", "false")
      .config("spark.driver.host", "127.0.0.1")
      .config("spark.sql.shuffle.partitions", "4")
      .getOrCreate()
    s.sparkContext.setCheckpointDir(Files.createTempDirectory("sc-spark-ckpt").toUri.toString)
    s

  override def afterAll(): Unit = spark.stop()

  private val schemaV = StructType(Seq(StructField("id", StringType)))
  private val schemaE =
    StructType(Seq(StructField("src", StringType), StructField("dst", StringType)))
  private def vdf(vs: Seq[String]): DataFrame =
    spark.createDataFrame(vs.map(Row(_)).asJava, schemaV)
  private def edf(es: Seq[(String, String)]): DataFrame =
    spark.createDataFrame(es.map((a, b) => Row(a, b)).asJava, schemaE)
  private def pairs2(df: DataFrame): Map[String, String] =
    df.collect().map(r => r.getString(0) -> r.getString(1)).toMap

  private val twoTriangles =
    Seq("a" -> "b", "b" -> "c", "a" -> "c", "d" -> "e", "e" -> "f", "d" -> "f", "c" -> "d")
  private val path5 = Seq("a" -> "b", "b" -> "c", "c" -> "d", "d" -> "e")
  private val k4    =
    for a <- Seq("p", "q", "r", "s"); b <- Seq("p", "q", "r", "s") if a < b yield a -> b

  test("checkpointing is reliable when a checkpoint directory is set") {
    assertEquals(Checkpointing.mode(spark), "reliable")
  }

  test("label propagation on known graphs") {
    def lp(vs: Seq[String], es: Seq[(String, String)], it: Int) =
      val r = LabelPropagation.run(vdf(vs), edf(es), it)
      (pairs2(r.labels), r.converged)
    assertEquals(lp(Nil, Nil, 10), (Map.empty[String, String], true))
    assertEquals(
      lp(Seq("a", "b", "c", "d", "e", "f"), twoTriangles, 10),
      (Map("a" -> "a", "b" -> "a", "c" -> "a", "d" -> "d", "e" -> "d", "f" -> "d"), true)
    )
    assertEquals(
      lp(Seq("a", "b", "c", "d", "e"), path5, 2),
      (Map("a" -> "a", "b" -> "a", "c" -> "a", "d" -> "b", "e" -> "c"), false)
    )
    assertEquals(lp(Seq("a", "b", "c", "d", "e"), path5, 10)._1.values.toSet, Set("a"))
    assertEquals(
      lp(Seq("h", "x", "y", "z"), Seq("h" -> "x", "h" -> "y", "z" -> "h"), 10)._1.values.toSet,
      Set("h")
    )
    assertEquals(lp(Seq("p", "q", "r", "s"), k4, 10)._1.values.toSet, Set("p"))
    assertEquals(lp(Seq("a", "b"), Seq("a" -> "b"), 10), (Map("a" -> "a", "b" -> "a"), true))
    assertEquals(
      lp(Seq("a", "b", "c", "d", "e", "f"), Seq("a" -> "b", "c" -> "d", "d" -> "e"), 10)._1,
      Map("a" -> "a", "b" -> "a", "c" -> "c", "d" -> "c", "e" -> "c", "f" -> "f")
    )
    assertEquals(
      LabelPropagation.algorithmVersion(10),
      "label-propagation-v1-it10-selfvote-minlabel"
    )
  }

  test("k-core coreness on known graphs") {
    def kc(vs: Seq[String], es: Seq[(String, String)]) =
      KCore.run(vdf(vs), edf(es), 50).coreness.collect().map(r =>
        r.getString(0) -> r.getInt(1)
      ).toMap
    assertEquals(kc(Nil, Nil), Map.empty[String, Int])
    assertEquals(
      kc(Seq("a", "b", "c", "d", "e", "f", "g"), twoTriangles),
      Map("a" -> 2, "b" -> 2, "c" -> 2, "d" -> 2, "e" -> 2, "f" -> 2, "g" -> 0)
    )
    assertEquals(kc(Seq("a", "b", "c", "d", "e"), path5).values.toSet, Set(1))
    assertEquals(
      kc(Seq("h", "x", "y", "z"), Seq("h" -> "x", "h" -> "y", "h" -> "z")).values.toSet,
      Set(1)
    )
    assertEquals(
      kc(Seq("p", "q", "r", "s", "t"), k4 :+ ("p" -> "t")),
      Map("p" -> 3, "q" -> 3, "r" -> 3, "s" -> 3, "t" -> 1)
    )
    intercept[IllegalStateException](KCore.run(vdf(Seq("p", "q", "r", "s")), edf(k4), 1))
  }

  test("bounded BFS: exact paths, depth limit, unreachable pairs, deterministic tie-break") {
    val ps = StructType(Seq("pairId", "sourceId", "targetId").map(StructField(_, StringType)))
    def bfs(prs: Seq[(String, String, String)], es: Seq[(String, String)], depth: Int) =
      val r = BoundedBfs.run(
        spark.createDataFrame(prs.map((i, s, t) => Row(i, s, t)).asJava, ps),
        edf(es),
        depth
      )
      val d = r.paths.collect().map(x => x.getString(0) -> x.getInt(1)).toMap
      val h =
        r.hops.collect().map(x => (x.getString(0), x.getInt(1), x.getString(2), x.getString(3)))
          .groupBy(_._1).view.mapValues(_.sortBy(_._2).map(x => (x._3, x._4)).toList).toMap
      (d, h)
    val all = Seq("p1" -> ("a", "e"), "p2" -> ("b", "d"), "p3" -> ("a", "z")).map((i, st) =>
      (i, st._1, st._2)
    )
    val (d4, h4) = bfs(all, path5 :+ ("y" -> "z"), 4)
    assertEquals(d4, Map("p1" -> 4, "p2" -> 2))
    assertEquals(h4("p1"), List("a" -> "b", "b" -> "c", "c" -> "d", "d" -> "e"))
    assertEquals(h4("p2"), List("b" -> "c", "c" -> "d"))
    assert(!h4.contains("p3"))
    val (d3, h3) = bfs(all, path5, 3)
    assertEquals(d3, Map("p2" -> 2))
    assertEquals(h3.keySet, Set("p2"))
    val (ds, hs) = bfs(Seq(("q", "a", "d")), Seq("a" -> "c", "a" -> "b", "c" -> "d", "b" -> "d"), 5)
    assertEquals(ds, Map("q" -> 2))
    assertEquals(hs("q"), List("a" -> "b", "b" -> "d"))
  }

  test("bridge measure on two bridged triangles plus an isolated member") {
    val ms = StructType(Seq(StructField("id", StringType), StructField("community", StringType)))
    val m  = Seq("a" -> "A", "b" -> "A", "c" -> "A", "d" -> "D", "e" -> "D", "f" -> "D", "g" -> "G")
    val r  = Bridges
      .compute(spark.createDataFrame(m.map((a, b) => Row(a, b)).asJava, ms), edf(twoTriangles))
      .collect()
      .map(x => x.getString(0) -> (x.getLong(1), x.getLong(2), x.getInt(3)))
      .toMap
    assertEquals(
      r,
      Map(
        "a" -> (2L, 0L, 1),
        "b" -> (2L, 0L, 1),
        "c" -> (3L, 1L, 2),
        "d" -> (3L, 1L, 2),
        "e" -> (2L, 0L, 1),
        "f" -> (2L, 0L, 1),
        "g" -> (0L, 0L, 1)
      )
    )
  }

  /** Direct transcription of label-propagation-v1 (closed-neighbourhood vote, min-label tie). */
  private def refLp(vs: Seq[String], es: Seq[(String, String)], it: Int): Map[String, String] =
    val nb =
      vs.map(v => v -> es.collect { case (a, b) if a == v => b; case (a, b) if b == v => a }).toMap
    (1 to it).foldLeft(vs.map(v => v -> v).toMap) { (lab, _) =>
      vs.map { v =>
        val votes = (v +: nb(v)).map(lab).groupBy(identity).toList.map((l, xs) => (l, xs.size))
        v -> votes.minBy((l, n) => (-n, l))._1
      }.toMap
    }

  private def jsonRows(df: DataFrame): List[String] =
    df.select(F.to_json(
      F.struct(df.columns.map(F.col).toSeq*),
      Map("ignoreNullFields" -> "false").asJava
    ))
      .collect()
      .map(_.getString(0))
      .toList

  test("Part B jobs 5-11 on the Part A fixture: paths, communities, bridges, coreness, export") {
    val fx     = new PartASuite
    val rid    = fx.rid
    val nameOf = rid.map(_.swap)
    val root   = Files.createTempDirectory("sc-spark-part-b")
    val window = YearWindow(2020, 2022)
    val policy = "graph-policy-test-v1"
    val a      = PartA.run(
      spark,
      fx.fixture(root),
      PartAParams(window, policy, ccMaxIterations = 20, shufflePartitions = 4),
      "sha256:test-core-config",
      "test",
      1,
      fx.uri(root.resolve("out-a"))
    )
    assertEquals(a.failures, Nil)
    val pairs = List("A" -> "D3", "X8" -> "C", "D3" -> "X8", "E" -> "A", "G" -> "G", "U" -> "A")
    val p     = PartBParams(window, policy, 10, 2, 50, 4, pairs.map((s, t) => rid(s) -> rid(t)))
    val outB  = fx.uri(root.resolve("out-b"))
    val r     = PartB.run(spark, a.outputBase, p, "sha256:test-core-config", "test", 1, outB)
    assertEquals(r.failures, Nil, s"invariant failures: ${r.failures}")
    assertEquals(r.partARunId, a.runId)
    assertEquals(r.invariantStatus, "pass")
    val inv = r.invariantFailures.map(_._1).toSet
    List(
      "community-member-missing",
      "community-size-sum",
      "community-edge-accounting",
      "cross-edge-not-edge",
      "bridge-inconsistent",
      "coreness-exceeds-degree",
      "path-hop-not-edge",
      "path-chain-broken"
    ).foreach(n => assert(inv(n), n))

    val ws                         = "graphWindow=2020-2022"
    def tbl(t: String)             = spark.read.parquet(s"${r.outputBase}/export/$t/$ws")
    def edge(x: String, y: String) =
      val (l, h) = if rid(x) < rid(y) then (rid(x), rid(y)) else (rid(y), rid(x))
      IdUdfs.coauthorEdgeId(l, h, window, policy)

    // Job 8: coreness.
    val core = tbl("researcher-graph-metrics").collect()
      .map(x => nameOf(x.getAs[String]("researcherId")) -> x.getAs[Int]("coreness")).toMap
    assertEquals(
      core,
      Map(
        "A"   -> 2,
        "B"   -> 2,
        "C"   -> 2,
        "D3"  -> 1,
        "X8"  -> 1,
        "E"   -> 1,
        "F"   -> 1,
        "G"   -> 0,
        "X9"  -> 0,
        "X10" -> 0
      )
    )
    assertEquals(r.corenessDistribution, List(0 -> 3L, 1 -> 4L, 2 -> 3L))

    // Job 6: communities equal the reference transcription; order-free facts hold.
    val vs   = List("A", "B", "C", "D3", "E", "F", "G", "X8", "X9", "X10")
    val es   = List("A" -> "B", "A" -> "C", "B" -> "C", "C" -> "D3", "E" -> "F", "B" -> "X8")
    val ref  = refLp(vs.map(rid), es.map((x, y) => rid(x) -> rid(y)), 10)
    val refG = ref.groupBy(_._2).values.map(_.keySet).toSet
    val memb = tbl("community-membership").collect()
      .map(x => x.getAs[String]("researcherId") -> x.getAs[String]("communityId")).toMap
    assertEquals(memb.groupBy(_._2).values.map(_.keySet).toSet, refG)
    assertEquals(memb(rid("E")), memb(rid("F")))
    List("G", "X9", "X10").foreach(n => assertEquals(memb.values.count(_ == memb(rid(n))), 1, n))
    val alg   = p.algorithmVersion
    val comms = tbl("communities").collect().map(x =>
      x.getAs[String]("communityId") ->
        (
          x.getList[String](x.fieldIndex("memberIds")).asScala.toList,
          x.getAs[Long]("communitySize"),
          x.getAs[Long]("internalEdgeCount"),
          x.getAs[Long]("crossCommunityEdgeCount")
        )
    ).toMap
    assertEquals(comms.size.toLong, r.count("communities"))
    assertEquals(comms.size, refG.size)
    def crossRef(x: String, y: String) = ref(rid(x)) != ref(rid(y))
    comms.foreach { case (id, (ms, size, internal, cross)) =>
      assertEquals(Right(id), StableIds.communityId(alg, window, ms))
      assertEquals(ms, ms.sorted)
      assertEquals(size, ms.size.toLong)
      val inC = es.filter((x, y) => ms.contains(rid(x)) && ms.contains(rid(y)))
      assertEquals(internal, inC.size.toLong, id)
      assertEquals(cross, es.count((x, y) => ms.contains(rid(x)) != ms.contains(rid(y))).toLong, id)
    }
    val crossT = tbl("cross-community-edges").collect().map(_.getAs[String]("edgeId")).toSet
    assertEquals(crossT, es.filter(crossRef).map(edge).toSet)

    // Job 7: bridges consistent with the reference membership.
    val br = tbl("researcher-graph-metrics").collect().map(x =>
      nameOf(x.getAs[String]("researcherId")) ->
        (x.getAs[Long]("crossCommunityDegree"), x.getAs[Int]("distinctCommunitiesTouched"))
    ).toMap
    vs.foreach { n =>
      val nbs = es.collect { case (x, y) if x == n => y; case (x, y) if y == n => x }
      assertEquals(br(n)._1, nbs.count(m => crossRef(n, m)).toLong, n)
      assertEquals(br(n)._2, (nbs.map(m => ref(rid(m))) :+ ref(rid(n))).distinct.size, n)
    }

    // Job 5: paths and path evidence.
    val pathRows = tbl("paths").collect().map(x =>
      (nameOf(x.getAs[String]("sourceId")), nameOf(x.getAs[String]("targetId"))) ->
        (
          x.getAs[String]("status"),
          Option(x.getAs[Integer]("depth")).map(_.toInt),
          x.getList[String](x.fieldIndex("nodeIds")).asScala.toList.map(nameOf),
          x.getList[String](x.fieldIndex("edgeIds")).asScala.toList
        )
    ).toMap
    assertEquals(
      pathRows,
      Map(
        ("A", "D3") ->
          ("found", Some(2), List("A", "C", "D3"), List(edge("A", "C"), edge("C", "D3"))),
        ("X8", "C") ->
          ("found", Some(2), List("X8", "B", "C"), List(edge("B", "X8"), edge("B", "C"))),
        ("D3", "X8") -> ("beyond-depth-limit", None, Nil, Nil),
        ("E", "A")   -> ("disconnected", None, Nil, Nil),
        ("G", "G")   -> ("invalid-pair", None, Nil, Nil),
        ("U", "A")   -> ("endpoint-missing", None, Nil, Nil)
      )
    )
    val hops = tbl("path-evidence").collect().map(x =>
      (
        nameOf(x.getAs[String]("sourceId")),
        x.getAs[Int]("hopIndex"),
        nameOf(x.getAs[String]("fromId")),
        nameOf(x.getAs[String]("toId")),
        x.getAs[Int]("jointPublicationCount")
      )
    ).toSet
    assertEquals(
      hops,
      Set(
        ("A", 0, "A", "C", 1),
        ("A", 1, "C", "D3", 1),
        ("X8", 0, "X8", "B", 1),
        ("X8", 1, "B", "C", 1)
      )
    )

    // Job 11: core-shaped rows decode through Wire with validation.
    jsonRows(tbl("coauthor-edges")).foreach(j => assert(Wire.decode[CoauthorEdge](j).isRight, j))
    jsonRows(tbl("researcher-metrics")).foreach(j =>
      assert(Wire.decode[ResearcherMetrics](j).isRight, j)
    )
    jsonRows(spark.read.parquet(s"${r.outputBase}/export/researchers")).foreach(j =>
      assert(Wire.decode[CanonicalResearcher](j).isRight, j)
    )
    jsonRows(spark.read.parquet(s"${r.outputBase}/export/works")).foreach(j =>
      assert(Wire.decode[WorkRecord](j).isRight, j)
    )
    val ab = jsonRows(tbl("coauthor-edges").where(F.col("edgeId") === edge("A", "B"))).map(j =>
      Wire.decode[CoauthorEdge](j).toOption.get
    )
    assertEquals(
      ab.map(e => (e.coauthoredWorkCount, e.firstYear, e.lastYear, e.provenance.pipelineRunId)),
      List((2, 2020, 2021, r.runId))
    )

    // Job 11: manifests with counts and invariant status.
    def manifest(t: String) =
      ujson.read(JobFiles.readText(spark, s"${r.outputBase}/export/manifests/$t.json"))
    val expectRows = Map(
      "coauthor-edges"            -> 6,
      "researcher-metrics"        -> 10,
      "researchers"               -> 14,
      "works"                     -> 10,
      "paths"                     -> 6,
      "path-evidence"             -> 4,
      "path-inputs"               -> 2,
      "community-profile-inputs"  -> comms.size,
      "researcher-profile-inputs" -> 10,
      "edge-supporting-works"     -> 7
    )
    List("neptune", "dynamodb", "s3-vectors", "s3").foreach { t =>
      val m = manifest(t)
      assertEquals(m("invariantStatus").str, "pass", t)
      assertEquals(m("runId").str, r.runId, t)
      assertEquals(m("partARunId").str, a.runId, t)
      assertEquals(m("policyVersion").str, policy, t)
      assertEquals(m("algorithmVersion").str, alg, t)
      m("tables").arr.foreach { tb =>
        expectRows.get(tb("name").str).foreach(n =>
          assertEquals(tb("rowCount").num.toInt, n, tb("name").str)
        )
      }
    }
    assertEquals(
      manifest("s3-vectors")("tables").arr.map(_("name").str).toSet,
      Set("researcher-profile-inputs", "community-profile-inputs", "path-inputs")
    )
    assertEquals(manifest("s3")("tables").arr.size, 15)

    // Job 10: summary inputs for one researcher, one community, one path.
    val prof =
      tbl("researcher-profile-inputs").where(F.col("researcherId") === rid("A")).collect().head
    assertEquals(
      prof.getList[String](prof.fieldIndex("coauthorEdgeIds")).asScala.toList,
      List(edge("A", "B"), edge("A", "C")).sorted
    )
    assertEquals(
      prof.getList[String](prof.fieldIndex("representativeWorkIds")).asScala.toList,
      List("work:dblp:t/w02", "work:dblp:t/w01")
    )
    assertEquals(
      prof.getMap[String, String](prof.fieldIndex("names")).toMap,
      Map(rid("B") -> "bob brown", rid("C") -> "carol chen")
    )
    val mets = prof.getList[Row](prof.fieldIndex("metrics")).asScala.map(x =>
      x.getString(0) -> x.getDouble(1)
    ).toMap
    assertEquals(
      mets,
      Map("degree" -> 2.0, "weighted-degree" -> 3.0, "component-size" -> 5.0, "core-number" -> 2.0)
    )
    assertEquals(prof.getAs[Row]("provenance").getString(2), r.runId)
    assertEquals(prof.getAs[Row]("window"), Row(2020, 2022))
    val ef = List(rid("E"), rid("F")).sorted
    val ci =
      tbl("community-profile-inputs").where(F.col("communityId") === memb(rid("E"))).collect().head
    assertEquals(ci.getList[String](ci.fieldIndex("memberIds")).asScala.toList, ef)
    assertEquals(ci.getAs[Int]("internalEdgeCount"), 1)
    assertEquals(
      ci.getList[String](ci.fieldIndex("representativeWorkIds")).asScala.toList,
      List("work:dblp:t/w05")
    )
    assertEquals(
      ci.getMap[String, String](ci.fieldIndex("names")).toMap,
      Map(rid("E") -> "eve evans", rid("F") -> "fay fox")
    )
    assertEquals(Right(ci.getAs[String]("communityId")), StableIds.communityId(alg, window, ef))
    val pi = tbl("path-inputs").where(F.col("sourceId") === rid("A")).collect().head
    assertEquals(pi.getAs[String]("targetId"), rid("D3"))
    assertEquals(
      pi.getList[String](pi.fieldIndex("hopEdgeIds")).asScala.toList,
      List(edge("A", "C"), edge("C", "D3"))
    )
    assertEquals(
      pi.getMap[String, String](pi.fieldIndex("names")).toMap,
      Map(rid("A") -> "alice adams", rid("C") -> "carol chen", rid("D3") -> "dan diaz")
    )

    val params = ujson.read(JobFiles.readText(spark, s"${r.outputBase}/params.json"))
    assertEquals(params("checkpointMode").str, "reliable")
    assertEquals(params("lpIterations").num.toInt, 10)
    assertEquals(params("maxPathDepth").num.toInt, 2)

    // Never overwrite.
    intercept[IllegalStateException](PartB.run(
      spark,
      a.outputBase,
      p,
      "sha256:test-core-config",
      "test",
      1,
      outB
    ))

    // R-06a-14: a failing Part A invariant reaches every manifest as invariantStatus fail.
    val aInv = Path.of(java.net.URI.create(s"${a.outputBase}/invariants.json"))
    Files.write(aInv, """{"edge-self-loop": 1}""".getBytes(UTF_8))
    Files.deleteIfExists(aInv.resolveSibling(".invariants.json.crc")) // local FS checksum sidecar
    val bad = PartB.run(spark, a.outputBase, p, "sha256:test-core-config", "test", 1, outB)
    assertEquals(bad.invariantStatus, "fail")
    assertEquals(bad.failures, List("part-a:edge-self-loop" -> 1L))
    val badM =
      ujson.read(JobFiles.readText(spark, s"${bad.outputBase}/export/manifests/neptune.json"))
    assertEquals(badM("invariantStatus").str, "fail")
    assertEquals(badM("invariantFailures")("part-a:edge-self-loop").num.toLong, 1L)
  }
