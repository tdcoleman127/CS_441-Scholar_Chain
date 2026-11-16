package edu.uic.cs441.scholarchain.tests.adversarial

import edu.uic.cs441.scholarchain.core.json.Wire
import edu.uic.cs441.scholarchain.core.model.*
import edu.uic.cs441.scholarchain.er.*
import edu.uic.cs441.scholarchain.spark.*
import org.apache.spark.sql.{DataFrame, Row, SparkSession}
import org.apache.spark.sql.types.{StringType, StructField, StructType}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/**
 * Graph analytics against independent reference implementations on seeded random graphs, plus
 * empty, disconnected, self-loop, duplicate-edge, and dangling-endpoint inputs; and the
 * entity-resolution to Spark identity hand-off on a common-name block (over-merge).
 */
class GraphAnalyticsSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(900, "s")

  private lazy val spark: SparkSession = SparkSession
    .builder()
    .master("local[2]")
    .appName("scholarchain-phase11-graph")
    .config("spark.ui.enabled", "false")
    .config("spark.driver.host", "127.0.0.1")
    .config("spark.sql.shuffle.partitions", "4")
    .getOrCreate()

  override def afterAll(): Unit = spark.stop()

  private val schemaV = StructType(Seq(StructField("id", StringType)))
  private val schemaE =
    StructType(Seq(StructField("src", StringType), StructField("dst", StringType)))
  private def vdf(vs: Seq[String]): DataFrame =
    spark.createDataFrame(vs.map(Row(_)).asJava, schemaV)
  private def edf(es: Seq[(String, String)]): DataFrame =
    spark.createDataFrame(es.map((a, b) => Row(a, b)).asJava, schemaE)

  // ---- Reference implementations (plain Scala, written from the definitions) ------------------

  private def adjacency(vs: Seq[String], es: Seq[(String, String)]): Map[String, Set[String]] =
    val in = vs.toSet
    val ps = es.filter((a, b) => a != b && in(a) && in(b))
    vs.map(v => v -> ps.collect { case (a, b) if a == v => b; case (a, b) if b == v => a }.toSet)
      .toMap

  private def refComponents(vs: Seq[String], es: Seq[(String, String)]): Map[String, String] =
    val adj  = adjacency(vs, es)
    val seen = mutable.Map.empty[String, String]
    vs.sorted.foreach { v =>
      if !seen.contains(v) then
        val comp  = mutable.Set(v)
        val queue = mutable.Queue(v)
        while queue.nonEmpty do
          adj(queue.dequeue()).foreach(n => if comp.add(n) then queue.enqueue(n))
        val label = comp.min
        comp.foreach(seen(_) = label)
    }
    seen.toMap

  private def refCoreness(vs: Seq[String], es: Seq[(String, String)]): Map[String, Int] =
    val adj   = adjacency(vs, es)
    val alive = mutable.Set(vs*)
    val core  = mutable.Map.empty[String, Int]
    var k     = 0
    while alive.nonEmpty do
      val low = alive.filter(v => adj(v).count(alive) <= k).toList
      if low.isEmpty then k += 1
      else low.foreach { v => core(v) = k; alive -= v }
    core.toMap

  private def refDistance(es: Seq[(String, String)], s: String, t: String): Option[Int] =
    val adj  = es.filter(_ != _).flatMap((a, b) => Seq(a -> b, b -> a)).groupMap(_._1)(_._2)
    val dist = mutable.Map(s -> 0)
    val q    = mutable.Queue(s)
    while q.nonEmpty do
      val u = q.dequeue()
      adj.getOrElse(u, Nil).foreach(n =>
        if !dist.contains(n) then { dist(n) = dist(u) + 1; q.enqueue(n) }
      )
    dist.get(t)

  private def randomGraph(seed: Int, n: Int, p: Double): (Seq[String], Seq[(String, String)]) =
    val rnd = scala.util.Random(seed)
    val vs  = (0 until n).map(i => f"v$i%02d")
    val es  = for i <- vs; j <- vs if i < j && rnd.nextDouble() < p yield i -> j
    (vs, es)

  /** Adversarial noise: reversed duplicates and self loops must not change any result. */
  private def noisy(vs: Seq[String], es: Seq[(String, String)]): Seq[(String, String)] =
    es ++ es.take(3).map(_.swap) ++ es.take(2) ++ vs.take(2).map(v => v -> v)

  private val seeds = List(1, 7, 42, 1009)

  test("connected components equal the reference on random, noisy, and disconnected graphs") {
    seeds.foreach { seed =>
      val (vs, es) = randomGraph(seed, 14, 0.12)
      val got      = ConnectedComponents.run(vdf(vs), edf(noisy(vs, es)), 30).labels.collect()
        .map(r => r.getString(0) -> r.getString(1)).toMap
      assertEquals(got, refComponents(vs, es), s"seed $seed")
    }
    assertEquals(ConnectedComponents.run(vdf(Nil), edf(Nil), 1).labels.count(), 0L)
    // Dangling endpoints (documented: ignored) never appear and never join two components.
    val got = ConnectedComponents.run(vdf(Seq("a", "b", "c")), edf(Seq("a" -> "z", "z" -> "c")), 10)
      .labels.collect().map(r => r.getString(0) -> r.getString(1)).toMap
    assertEquals(got, Map("a" -> "a", "b" -> "b", "c" -> "c"))
  }

  test("k-core coreness equals the reference; duplicates and self loops do not inflate degree") {
    seeds.foreach { seed =>
      val (vs, es) = randomGraph(seed, 14, 0.3)
      val got      = KCore.run(vdf(vs), edf(noisy(vs, es)), 100).coreness.collect()
        .map(r => r.getString(0) -> r.getInt(1)).toMap
      assertEquals(got, refCoreness(vs, es), s"seed $seed")
    }
    // A 5-clique plus a pendant path: coreness 4 for the clique, 1 for the path.
    val k5 =
      for a <- Seq("a", "b", "c", "d", "e"); b <- Seq("a", "b", "c", "d", "e") if a < b yield a -> b
    val tail = Seq("e" -> "f", "f" -> "g")
    val got  = KCore.run(vdf(Seq("a", "b", "c", "d", "e", "f", "g", "h")), edf(k5 ++ tail), 50)
      .coreness.collect().map(r => r.getString(0) -> r.getInt(1)).toMap
    assertEquals(
      got,
      Map("a" -> 4, "b" -> 4, "c" -> 4, "d" -> 4, "e" -> 4, "f" -> 1, "g" -> 1, "h" -> 0)
    )
  }

  test("bounded BFS depth equals the reference shortest distance; hops form that path") {
    val ps = StructType(Seq("pairId", "sourceId", "targetId").map(StructField(_, StringType)))
    seeds.foreach { seed =>
      val (vs, es) = randomGraph(seed, 14, 0.15)
      val pairs    = vs.drop(1).take(8).map(t => (s"p-$t", vs.head, t))
      val r        = BoundedBfs.run(
        spark.createDataFrame(pairs.map((i, s, t) => Row(i, s, t)).asJava, ps),
        edf(noisy(vs, es)),
        3
      )
      val depth = r.paths.collect().map(x => x.getString(0) -> x.getInt(1)).toMap
      val hops  =
        r.hops.collect().map(x => (x.getString(0), x.getInt(1), x.getString(2), x.getString(3)))
          .groupBy(_._1).view.mapValues(_.sortBy(_._2).map(h => (h._3, h._4)).toList).toMap
      val edgeSet = es.flatMap(e => Seq(e, e.swap)).toSet
      pairs.foreach { (id, s, t) =>
        val expected = refDistance(es, s, t).filter(_ <= 3)
        assertEquals(depth.get(id), expected, s"seed $seed $s->$t")
        expected.foreach { d =>
          val path = hops(id)
          assertEquals(path.size, d, s"seed $seed $s->$t hops")
          assertEquals(path.head._1, s)
          assertEquals(path.last._2, t)
          path.zip(path.drop(1)).foreach((x, y) => assertEquals(x._2, y._1))
          path.foreach(h => assert(edgeSet(h), s"hop $h is not an edge"))
        }
      }
    }
  }

  test("bridge measure equals the reference for any community assignment") {
    seeds.foreach { seed =>
      val (vs, es) = randomGraph(seed, 12, 0.25)
      val rnd      = scala.util.Random(seed * 31)
      val comm     = vs.map(v => v -> s"C${rnd.nextInt(3)}").toMap
      val ms  = StructType(Seq(StructField("id", StringType), StructField("community", StringType)))
      val got = Bridges
        .compute(spark.createDataFrame(comm.toSeq.map((a, b) => Row(a, b)).asJava, ms), edf(es))
        .collect().map(x => x.getString(0) -> (x.getLong(1), x.getLong(2), x.getInt(3))).toMap
      val adj      = adjacency(vs, es)
      val expected = vs.map { v =>
        val nb = adj(v)
        v -> (
          nb.size.toLong,
          nb.count(n => comm(n) != comm(v)).toLong,
          (nb.map(comm) + comm(v)).size
        )
      }.toMap
      assertEquals(got, expected, s"seed $seed")
    }
  }

  test("label propagation is deterministic and never merges disconnected components") {
    seeds.foreach { seed =>
      val (vs, es) = randomGraph(seed, 14, 0.15)
      def run      = LabelPropagation.run(vdf(vs), edf(es), 20).labels.collect()
        .map(r => r.getString(0) -> r.getString(1)).toMap
      val a = run
      assertEquals(run, a, s"seed $seed")
      val cc = refComponents(vs, es)
      a.toList.groupBy(_._2).values.foreach { members =>
        assertEquals(members.map((v, _) => cc(v)).distinct.size, 1, s"seed $seed $members")
      }
    }
  }

  // ---- Entity resolution to Spark identity: common-name over-merge (D1) -----------------------

  private val hadoopRun = "run:v1:" + "a" * 64
  private val erRun     = "run:v1:" + "b" * 64

  private def writeLines(p: Path, lines: Seq[String]): Unit =
    Files.createDirectories(p.getParent)
    Files.write(p, lines.mkString("", "\n", "\n").getBytes(UTF_8))

  private def uri(p: Path): String =
    val s = p.toAbsolutePath.toString.replace(java.io.File.separatorChar, '/')
    if s.startsWith("/") then "file://" + s else "file:///" + s

  test("D1: a common-name bridge never merges two mentions that ER explicitly rejected") {
    // Three "Smith" mentions with the same coauthor and title: "John Smith" (w1), "J. Smith" (w2),
    // "Jane Smith" (w3). ER (real Resolver, bundled er-policy-v2) accepts J.-John and J.-Jane and
    // rejects John-Jane (initials-incompatible). PartA closes accepted links transitively and reads
    // only accepted.jsonl and conflict.jsonl (spark PartA.scala:138-139, 192-204); rejected.jsonl is
    // never read, so the closure puts John and Jane, whom ER called different people, into one
    // researcher. Expected: John and Jane never share a researcher ID.
    val policy = ScoringPolicy.loadDefault().fold(e => fail(e), identity)
    val title  = "Graph partitioning at scale"
    def view(m: String, w: String, name: String) =
      EntityView(
        m,
        s"work:dblp:t/$w",
        name,
        title,
        Some(2020),
        List("Alan Turing"),
        Map.empty,
        Provenance(SourceType.Dblp, s"t/$w", hadoopRun)
      )
    val john                                 = view("m-a-john", "w1", "John Smith")
    val j                                    = view("m-b-j", "w2", "J. Smith")
    val jane                                 = view("m-c-jane", "w3", "Jane Smith")
    def decide(a: EntityView, b: EntityView) =
      Resolver.decide(policy, PairInput.fromViews("mention", "smith|j", a, b), erRun)
    val decisions = List(decide(john, j), decide(j, jane), decide(john, jane))
    assertEquals(
      decisions.map(d => (d.memberLow, d.memberHigh, d.status)),
      List(
        ("m-a-john", "m-b-j", ResolutionStatus.Accepted),
        ("m-b-j", "m-c-jane", ResolutionStatus.Accepted),
        ("m-a-john", "m-c-jane", ResolutionStatus.Rejected)
      ),
      "precondition: ER decisions on the fixture"
    )
    val root = Files.createTempDirectory("sc-phase11-overmerge")
    Outputs.writeAll(root.resolve("er"), decisions, 10)
    val mentions = List(
      ("m-a-john", "w1", 0, "john smith"),
      ("m-a-turing", "w1", 1, "alan turing"),
      ("m-b-j", "w2", 0, "j smith"),
      ("m-b-turing", "w2", 1, "alan turing"),
      ("m-c-jane", "w3", 0, "jane smith"),
      ("m-c-turing", "w3", 1, "alan turing")
    )
    def prov(w: String) =
      s"""{"sourceDataset":"dblp","sourceRecordId":"t/$w","pipelineRunId":"$hadoopRun"}"""
    writeLines(
      root.resolve("ap/part-r-00000"),
      mentions.map((m, w, p, n) =>
        s"""{"mentionId":"$m","workId":"work:dblp:t/$w","authorPosition":$p,""" +
          s""""normalizedName":"$n","year":2020,"provenance":${prov(w)}}"""
      )
    )
    writeLines(
      root.resolve("pairs/part-r-00000"),
      List(
        "w1" -> ("m-a-john", "m-a-turing"),
        "w2" -> ("m-b-j", "m-b-turing"),
        "w3" -> ("m-c-jane", "m-c-turing")
      ).map { case (w, (lo, hi)) =>
        s"""{"mentionLow":"$lo","mentionHigh":"$hi","positionLow":0,"positionHigh":1,""" +
          s""""workId":"work:dblp:t/$w","year":2020,"provenance":${prov(w)}}"""
      }
    )
    writeLines(
      root.resolve("works/part-r-00000"),
      List("w1", "w2", "w3").map(w =>
        Wire.encode(WorkRecord(
          1,
          s"work:dblp:t/$w",
          title,
          Some(2020),
          None,
          Nil,
          Nil,
          Provenance(SourceType.Dblp, s"t/$w", hadoopRun)
        ))
      )
    )
    val in = PartAInputs(
      uri(root.resolve("ap")),
      uri(root.resolve("pairs")),
      uri(root.resolve("works")),
      uri(root.resolve("er"))
    )
    val r = PartA.run(
      spark,
      in,
      PartAParams(YearWindow(2015, 2025), "graph-policy-test-v1", 20, 4),
      "sha256:phase11",
      "phase11",
      1,
      uri(root.resolve("out"))
    )
    val researcherOf = spark.read.parquet(s"${r.outputBase}/mention-researcher-map").collect()
      .map(row => row.getAs[String]("mentionId") -> row.getAs[String]("researcherId")).toMap
    assertEquals(researcherOf.size, 6)
    assertNotEquals(
      researcherOf("m-a-john"),
      researcherOf("m-c-jane"),
      "John Smith and Jane Smith (ER: rejected, initials-incompatible) were merged into one " +
        "researcher through the J. Smith mention"
    )
  }
