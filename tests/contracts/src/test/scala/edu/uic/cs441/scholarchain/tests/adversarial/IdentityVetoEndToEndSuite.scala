package edu.uic.cs441.scholarchain.tests.adversarial

import edu.uic.cs441.scholarchain.core.json.Wire
import edu.uic.cs441.scholarchain.core.model.*
import edu.uic.cs441.scholarchain.er.*
import edu.uic.cs441.scholarchain.spark.*
import org.apache.spark.sql.SparkSession
import upickle.default.write

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardCopyOption}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.Using

/**
 * Phase 11 repair-01 rerun, target 1: STRICT-policy entity-resolution output fed end to end into
 * Spark Part A (identity-closure-v2). Updated in Phase 11 repair-03 for the ER decision manifest
 * (er-outputs-v2, `_DECISIONS_MANIFEST.json`, review-02 F2).
 *
 * Decisions come from the real ER Resolver (bundled policy) and are published by the real ER writer
 * (Outputs.writeRun), so every directory Spark reads is a verified ER run: one run ID, one policy
 * version, one features version, schema 2. Adversarial records are built as schema-2 records of the
 * same run (their versions and provenance copied from a real decision) and go through the same
 * writer: work-scope records naming mention IDs, reversed member order, a pair that is both
 * accepted and rejected, and three seeded random groups with many vetoes. Every identity check is a
 * property of the output computed by an independent Scala reference (closure, veto set), not a
 * re-run of the production algorithm.
 *
 * Fail-closed cases (each must stop Part A before any output): a schema-v1 line, a missing decision
 * file, a truncated veto line (resealed or not), stale vetoes beside a fresh accepted.jsonl, a
 * 0-byte veto file, and a mixed-run or mixed-version append under a recomputed manifest.
 */
class IdentityVetoEndToEndSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(1200, "s")

  private lazy val spark: SparkSession = SparkSession
    .builder()
    .master("local[2]")
    .appName("scholarchain-phase11-rerun-identity")
    .config("spark.ui.enabled", "false")
    .config("spark.driver.host", "127.0.0.1")
    .config("spark.sql.shuffle.partitions", "4")
    .getOrCreate()

  override def afterAll(): Unit = spark.stop()

  private val hadoopRun = "run:v1:" + "a" * 64
  private val erRun     = "run:v1:" + "e" * 64
  private val erRunOld  = "run:v1:" + "d" * 64
  private val policy    = ScoringPolicy.loadDefault().fold(e => fail(e), identity)
  private val Accepted  = "accepted.jsonl"
  private val Rejected  = "rejected.jsonl"
  private val Title     = "Graph partitioning at scale"

  /** Prefix of every ErManifestGate verification failure (spark Support.scala). */
  private val VerifyFailed = s"failed verification against ${Outputs.ManifestFileName}"

  private def mid(s: String)  = s"m-$s"
  private def wid(s: String)  = s"work:dblp:t/w-$s"
  private def prov(s: String) = Provenance(SourceType.Dblp, s"t/w-$s", hadoopRun)

  private def view(
      m: String,
      name: String,
      co: List[String],
      title: String = Title,
      ids: Map[IdNamespace, List[String]] = Map.empty
  ) = EntityView(mid(m), wid(m), name, title, Some(2020), co, ids, prov(m))

  private def decideIn(run: String)(a: EntityView, b: EntityView): ResolutionRecord =
    Resolver.decide(policy, PairInput.fromViews("mention", "bk", a, b), run)

  private def decide(a: EntityView, b: EntityView): ResolutionRecord = decideIn(erRun)(a, b)

  // ---- Real-resolver groups --------------------------------------------------------------------

  // S: several rejected vetoes in one component (a star around "J. Smith").
  private val sJ     = view("s0-j", "J. Smith", List("Alan Turing"))
  private val sJohn  = view("s1-john", "John Smith", List("Alan Turing"))
  private val sJane  = view("s2-jane", "Jane Smith", List("Alan Turing"))
  private val sJames = view("s3-james", "James Smith", List("Alan Turing"))
  // W: a conflict veto (ORCIDs differ) and a rejected veto in the same closure component.
  private val orcidX = Map(IdNamespace.Orcid -> List("0000-0002-1825-0097"))
  private val orcidY = Map(IdNamespace.Orcid -> List("0000-0001-5109-3700"))
  private val wA     = view("w0-a", "Wei Wang", List("Ada Byron"), ids = orcidX)
  private val wB     = view("w1-b", "W. Wang", List("Ada Byron"))
  private val wC     = view("w2-c", "Wei Wang", List("Ada Byron"), ids = orcidY)
  private val wD     = view("w3-d", "Wen Wang", List("Ada Byron"))
  // U: an unresolved veto (no shared coauthor, disjoint titles) closing a chain of accepts.
  private val uX = view("u0-x", "Li Chen", List("Bo Wu"), "Graph mining at scale")
  private val uY = view("u1-y", "Li Chen", List("Bo Wu", "Cy Ho"), "Stream joins in practice")
  private val uZ = view("u2-z", "Li Chen", List("Cy Ho"), "Quantum error correcting codes")
  // N: never-candidate rule; p and r are never compared by ER.
  private val nP = view("n0-p", "Ana Lee", List("Bo Chen"))
  private val nQ = view("n1-q", "Ana Lee", List("Bo Chen", "Di Fox"))
  private val nR = view("n2-r", "Ana Lee", List("Di Fox"))
  // G: two singletons that an adversarial work-scope accept names.
  private val gNames = List("g0-kai", "g1-mei")

  private lazy val realDecisions: List[ResolutionRecord] = List(
    decide(sJ, sJohn),
    decide(sJ, sJane),
    decide(sJ, sJames),
    decide(sJohn, sJane),
    decide(sJohn, sJames),
    decide(sJane, sJames),
    decide(wA, wB),
    decide(wB, wC),
    decide(wA, wC),
    decide(wB, wD),
    decide(wA, wD),
    decide(wC, wD),
    decide(uX, uY),
    decide(uY, uZ),
    decide(uX, uZ),
    decide(nP, nQ),
    decide(nQ, nR)
  )

  /** A real work-scope decision on two work views (title-exact + year): never a researcher link. */
  private lazy val realWorkDecision: ResolutionRecord =
    def wv(w: String) = EntityView(
      s"work:dblp:t/wk-$w",
      s"work:dblp:t/wk-$w",
      "",
      "Scalable entity resolution",
      Some(2019),
      Nil,
      Map.empty,
      Provenance(SourceType.Dblp, s"t/wk-$w", hadoopRun)
    )
    Resolver.decide(policy, PairInput.fromViews("work", "w|bk", wv("a"), wv("b")), erRun)

  // ---- Seeded random groups (hand-built records of the same ER run, all three veto files) -------

  /** Real decision whose run provenance and versions every hand-built record copies. */
  private lazy val template: ResolutionRecord = realDecisions.head

  private def record(a: String, b: String, st: ResolutionStatus, conf: Double): ResolutionRecord =
    val (lo, hi) = if mid(a).compareTo(mid(b)) <= 0 then (a, b) else (b, a)
    val pairId   = s"mention|${mid(lo)}|${mid(hi)}"
    val outcome  = st match
      case ResolutionStatus.Accepted => MatchOutcome.Merge
      case ResolutionStatus.Rejected => MatchOutcome.NonMatch
      case _                         => MatchOutcome.NeedsReview
    template.copy(
      schemaVersion = template.schemaVersion,
      decisionId = Resolver.decisionId(pairId, template.policyVersion),
      pairId = pairId,
      scope = "mention",
      blockKey = "bk",
      memberLow = mid(lo),
      memberHigh = mid(hi),
      status = st,
      path = MatchPath.Probabilistic,
      outcome = outcome,
      decider = MatchDecider.Model,
      score = Some(conf),
      confidence = conf,
      adjudicatorVersion = None,
      features = Nil,
      reasonCodes = List(s"reason:${st.wire}"),
      provenance = template.provenance.copy(sourceRecordId = pairId),
      provenanceLow = prov(lo),
      provenanceHigh = prov(hi),
      scoreUpper = Some(conf),
      corroborated = Some(true)
    )

  /** The same decision with its sides reversed (members and their provenance together). */
  private def swapMembers(r: ResolutionRecord): ResolutionRecord =
    r.copy(
      memberLow = r.memberHigh,
      memberHigh = r.memberLow,
      provenanceLow = r.provenanceHigh,
      provenanceHigh = r.provenanceLow
    )

  /** Copy of a decision rewritten to another scope and members (adversarial work scope). */
  private def rescope(r: ResolutionRecord, scope: String, a: String, b: String): ResolutionRecord =
    val pairId = s"$scope|${mid(a)}|${mid(b)}"
    r.copy(
      scope = scope,
      memberLow = mid(a),
      memberHigh = mid(b),
      pairId = pairId,
      decisionId = Resolver.decisionId(pairId, r.policyVersion),
      provenance = r.provenance.copy(sourceRecordId = pairId)
    )

  /** Groups r0 (10 mentions), r1 (10), r2 (10, conflicts allowed); pairs only within a group. */
  private lazy val randomGroups: (List[String], List[ResolutionRecord]) =
    val rnd   = scala.util.Random(20261115)
    val names = for g <- 0 until 3; i <- 0 until 10 yield f"r$g-$i%02d"
    val recs  = mutable.ListBuffer.empty[ResolutionRecord]
    def add(r: ResolutionRecord, rev: Boolean): Unit = recs += (if rev then swapMembers(r) else r)
    for
      g <- 0 until 3
      i <- 0 until 10
      j <- (i + 1) until 10
    do
      val a   = f"r$g-$i%02d"
      val b   = f"r$g-$j%02d"
      val rev = rnd.nextDouble() < 0.3
      val x   = rnd.nextDouble()
      val c   = math.round((0.86 + rnd.nextDouble() * 0.13) * 100) / 100.0
      if x < 0.20 then add(record(a, b, ResolutionStatus.Accepted, c), rev)
      else if x < 0.30 then
        val st =
          if g == 2 && rnd.nextDouble() < 0.15 then ResolutionStatus.Conflict
          else if rnd.nextBoolean() then ResolutionStatus.Rejected
          else ResolutionStatus.Unresolved
        add(record(a, b, st, 1.0), rev)
      else if x < 0.32 then
        // ER contradiction: the same pair accepted and rejected; the veto must win.
        add(record(a, b, ResolutionStatus.Accepted, c), rev)
        add(record(a, b, ResolutionStatus.Rejected, 1.0), !rev)
    (names.toList, recs.toList)

  /** Every record of the base ER run, as handed to the real ER writer. */
  private lazy val erRecords: List[ResolutionRecord] =
    val anyAccepted = realDecisions.find(_.status == ResolutionStatus.Accepted).get
    val anyRejected = realDecisions.find(_.status == ResolutionStatus.Rejected).get
    val adversarial = List(
      // A work-scope accept naming two unrelated mentions: must never merge them.
      rescope(anyAccepted, "work", gNames(0), gNames(1)),
      // A work-scope rejection naming two accepted-linked mentions: must never split them.
      rescope(anyRejected, "work", "n0-p", "n1-q")
    )
    (realDecisions :+ realWorkDecision) ++ randomGroups._2 ++ adversarial

  // ---- Fixture writing ---------------------------------------------------------------------------

  private def writeLines(p: Path, lines: Seq[String]): Unit =
    Files.createDirectories(p.getParent)
    Files.write(p, lines.mkString("", "\n", if lines.isEmpty then "" else "\n").getBytes(UTF_8))

  private def readLines(p: Path): List[String] =
    Files.readAllLines(p, UTF_8).asScala.toList.filter(_.trim.nonEmpty)

  private def uri(p: Path): String =
    val s = p.toAbsolutePath.toString.replace(java.io.File.separatorChar, '/')
    if s.startsWith("/") then "file://" + s else "file:///" + s

  /** Publishes one ER run with the real writer (atomic, manifest last). */
  private def writeEr(er: Path, recs: Seq[ResolutionRecord], run: String = erRun): Unit =
    Outputs.writeRun(er, recs, 10, run, policy.policyVersion)
    ()

  private def manifestOf(er: Path): DecisionsManifest =
    Outputs.readManifest(er).fold(e => fail(e), identity)

  /**
   * Recomputes every manifest entry (sha256, non-blank line count) from the files as they now are,
   * keeping the manifest header: what a careless copier or an attacker who regenerates the manifest
   * would produce. Only the line-level rule V4 can then catch a foreign record.
   */
  private def reseal(er: Path): Unit =
    val m     = manifestOf(er)
    val files = m.files.map { e =>
      val bytes = Files.readAllBytes(er.resolve(e.name))
      val n     = new String(bytes, UTF_8).split("\n").count(_.trim.nonEmpty)
      ManifestEntry(e.name, n.toLong, Outputs.sha256Hex(bytes))
    }
    Files.write(
      er.resolve(Outputs.ManifestFileName),
      (write(m.copy(files = files), indent = 2) + "\n").getBytes(UTF_8)
    )
    ()

  /** Copies the four decision files and the manifest of `from` into a new directory `to`. */
  private def copyEr(from: Path, to: Path): Path =
    Files.createDirectories(to)
    (Outputs.ManifestFileName :: Outputs.DecisionFiles).foreach(f =>
      Files.copy(from.resolve(f), to.resolve(f), StandardCopyOption.REPLACE_EXISTING)
    )
    to

  private def writeHadoop(root: Path, names: Seq[String], order: Seq[String] => Seq[String])
      : Unit =
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

  private def inputs(root: Path, er: Path): PartAInputs =
    PartAInputs(
      uri(root.resolve("ap")),
      uri(root.resolve("pairs")),
      uri(root.resolve("works")),
      uri(er)
    )

  private def allMentionNames: List[String] =
    List(sJ, sJohn, sJane, sJames, wA, wB, wC, wD, uX, uY, uZ, nP, nQ, nR)
      .map(_.memberId.stripPrefix("m-")) ++ gNames ++ randomGroups._1

  /**
   * Writes the Hadoop inputs and a verified ER directory `root/er`. `order` reorders the Hadoop
   * lines and the lines inside every ER decision file; a reordered ER file is resealed (manifest
   * entries recomputed), so the directory stays a verified run. `skip` deletes decision files
   * afterwards.
   */
  private def fixture(root: Path, order: Seq[String] => Seq[String], skip: Set[String] = Set.empty)
      : PartAInputs =
    writeHadoop(root, allMentionNames, order)
    val er = root.resolve("er")
    writeEr(er, erRecords)
    val reordered = Outputs.DecisionFiles.map { f =>
      val ls = readLines(er.resolve(f))
      val o  = order(ls)
      if o != ls then writeLines(er.resolve(f), o)
      o != ls
    }
    if reordered.contains(true) then reseal(er)
    skip.foreach(f => Files.delete(er.resolve(f)))
    inputs(root, er)

  private def partA(root: Path, in: PartAInputs, partitions: Int, out: String = "out")
      : PartAResult =
    PartA.run(
      spark,
      in,
      PartAParams(YearWindow(2015, 2025), "graph-policy-test-v1", 60, partitions),
      "sha256:phase11-rerun",
      "phase11-rerun",
      1,
      uri(root.resolve(out))
    )

  /** Runs Part A, requires an IllegalStateException and no output; returns the message. */
  private def failsBeforeOutput(root: Path, in: PartAInputs, what: String, out: String = "out")
      : String =
    val err = intercept[IllegalStateException](partA(root, in, 2, out))
    assert(!Files.exists(root.resolve(out)), s"$what: output written although the run failed")
    String.valueOf(err.getMessage)

  private def runWith(order: Seq[String] => Seq[String], partitions: Int): (PartAResult, Path) =
    val root = Files.createTempDirectory("sc-rerun-identity")
    (partA(root, fixture(root, order), partitions), root)

  private lazy val baseRun: (PartAResult, Path) = runWith(identity, 4)
  private def base: PartAResult                 = baseRun._1
  private def baseEr: Path                      = baseRun._2.resolve("er")

  /** The ER decision files of the base run as Spark reads them, file name -> lines. */
  private lazy val erFiles: Map[String, List[String]] =
    Outputs.DecisionFiles.map(f => f -> readLines(baseEr.resolve(f))).toMap

  // ---- Reference model over the exact lines Spark reads ------------------------------------------

  final private case class Dec(
      file: String,
      scope: String,
      a: String,
      b: String,
      status: String,
      conf: Double
  )

  private lazy val decs: List[Dec] = erFiles.toList.flatMap { (f, ls) =>
    ls.map { l =>
      val j = ujson.read(l)
      val x = j("memberLow").str
      val y = j("memberHigh").str
      Dec(
        f,
        j("scope").str,
        if x < y then x else y,
        if x < y then y else x,
        j("status").str,
        j("confidence").num
      )
    }
  }

  private lazy val mentionIds: Set[String] = allMentionNames.map(mid).toSet

  private def known(d: Dec) = mentionIds(d.a) && mentionIds(d.b) && d.a != d.b

  private lazy val links: Set[(String, String)] = decs
    .filter(d => d.file == Accepted && d.scope == "mention" && d.status == "accepted" && known(d))
    .map(d => d.a -> d.b).toSet

  private lazy val vetoes: List[Dec] = decs
    .filter(d => d.scope == "mention" && (d.file != Accepted || d.status != "accepted") && known(d))

  /** Plain transitive closure of the accepted mention links: mention -> smallest member. */
  private lazy val closure: Map[String, String] =
    val adj  = links.toList.flatMap((a, b) => Seq(a -> b, b -> a)).groupMap(_._1)(_._2)
    val comp = mutable.Map.empty[String, String]
    mentionIds.toList.sorted.foreach { v =>
      if !comp.contains(v) then
        val seen = mutable.Set(v)
        val q    = mutable.Queue(v)
        while q.nonEmpty do
          adj.getOrElse(q.dequeue(), Nil).foreach(n => if seen.add(n) then q.enqueue(n))
        val anchor = seen.min
        seen.foreach(comp(_) = anchor)
    }
    comp.toMap

  /** mentionId -> (researcherId, identityStatus, vetoConstrainedClosureAnchorMentionId). */
  private def mentionMap(r: PartAResult): Map[String, (String, String, String)] =
    spark.read.parquet(s"${r.outputBase}/mention-researcher-map").collect()
      .map(row =>
        row.getAs[String]("mentionId") -> (
          row.getAs[String]("researcherId"),
          row.getAs[String]("identityStatus"),
          row.getAs[String]("vetoConstrainedClosureAnchorMentionId")
        )
      )
      .toMap

  private def refused(r: PartAResult): Set[(String, String, String, String, String)] =
    spark.read.parquet(s"${r.outputBase}/identity-refused-links").collect()
      .map(row =>
        (
          row.getAs[String]("memberLow"),
          row.getAs[String]("memberHigh"),
          row.getAs[String]("refusalReason"),
          row.getAs[String]("vetoMemberLow"),
          row.getAs[String]("vetoMemberHigh")
        )
      )
      .toSet

  private def status(a: EntityView, b: EntityView): ResolutionStatus =
    realDecisions.find(d =>
      Set(d.memberLow, d.memberHigh) == Set(a.memberId, b.memberId)
    ).get.status

  // ---- Tests -----------------------------------------------------------------------------------

  test("preconditions: the real resolver produces the intended STRICT decisions") {
    val (acc, rej, unr, con) = (
      ResolutionStatus.Accepted,
      ResolutionStatus.Rejected,
      ResolutionStatus.Unresolved,
      ResolutionStatus.Conflict
    )
    val expected = List(
      (sJ, sJohn, acc),
      (sJ, sJane, acc),
      (sJ, sJames, acc),
      (sJohn, sJane, rej),
      (sJohn, sJames, rej),
      (sJane, sJames, rej),
      (wA, wB, acc),
      (wB, wC, acc),
      (wA, wC, con),
      (wB, wD, acc),
      (wA, wD, rej),
      (uX, uY, acc),
      (uY, uZ, acc),
      (uX, uZ, unr),
      (nP, nQ, acc),
      (nQ, nR, acc)
    )
    val got = expected.map((a, b, _) => (a.memberId, b.memberId, status(a, b)))
    assertEquals(got, expected.map((a, b, s) => (a.memberId, b.memberId, s)))
    assertEquals(realWorkDecision.scope, "work")
    assertEquals(realWorkDecision.status, acc)
  }

  test("preconditions: Spark reads one verified ER run that still holds the adversarial records") {
    // The directory passes the ER verifier, and its manifest names the one run and versions.
    val m = Outputs.verify(baseEr).fold(ps => fail(ps.mkString("; ")), identity)
    assertEquals(
      (m.pipelineRunId, m.policyVersion, m.featuresVersion, m.schemaVersion),
      (erRun, policy.policyVersion, Features.SpecVersion, Resolver.RecordSchemaVersion)
    )
    assertEquals(
      m.files.map(e => e.name -> e.recordCount).toMap,
      erFiles.view.mapValues(_.size.toLong).toMap
    )
    val all = erFiles.values.flatten.map(ujson.read(_)).toList
    assertEquals(all.size.toLong, erRecords.size.toLong)
    // Every line is schema 2 of the one ER run, policy, and features version (no mixing).
    assertEquals(all.map(_("schemaVersion").num).toSet, Set(2.0))
    assertEquals(all.map(_("provenance")("pipelineRunId").str).toSet, Set(erRun))
    assertEquals(all.map(_("policyVersion").str).toSet, Set(policy.policyVersion))
    assertEquals(all.map(_("featureSpecVersion").str).toSet, Set(Features.SpecVersion))
    // The adversarial content survived the writer.
    val reversed = Outputs.MergeVetoFiles.flatMap(erFiles).map(ujson.read(_))
      .filter(j => j("memberLow").str > j("memberHigh").str)
    assert(reversed.nonEmpty, "no veto line with reversed member order")
    assert(
      erFiles(Accepted).exists(_.contains("\"pairId\":\"work|m-g0-kai|m-g1-mei\"")),
      "the adversarial work-scope accept is missing"
    )
    assert(
      erFiles(Rejected).exists(_.contains("\"pairId\":\"work|m-n0-p|m-n1-q\"")),
      "the adversarial work-scope rejection is missing"
    )
    val pid      = (l: String) => ujson.read(l)("pairId").str
    val accPairs = erFiles(Accepted).map(pid).toSet
    assert(erFiles(Rejected).map(pid).exists(accPairs), "no accepted-and-rejected contradiction")
    assertEquals(
      erFiles("conflict.jsonl").size,
      erRecords.count(_.status == ResolutionStatus.Conflict),
      "conflict vetoes lost by the writer"
    )
    assert(erFiles("conflict.jsonl").nonEmpty, "no conflict veto")
  }

  test(
    "STRICT: no veto pair shares a researcher; every cluster is held together by accepted links"
  ) {
    val r = base
    assertEquals(r.failures, Nil, s"invariant failures: ${r.failures}")
    val mm  = mentionMap(r)
    val rid = mm.view.mapValues(_._1).toMap
    assertEquals(mm.keySet, mentionIds)
    // (1) No veto pair (rejected, unresolved, conflict; any member order).
    val joined = vetoes.filter(v => rid(v.a) == rid(v.b))
    assertEquals(joined, Nil, "veto pairs that share a researcher")
    // (2) Each researcher's mentions are connected through accepted links inside the researcher.
    mm.groupBy(_._2._1).foreach { (researcher, ms) =>
      val members = ms.keySet
      val inner   = links.filter((a, b) => members(a) && members(b))
      val adj     = inner.toList.flatMap((a, b) => Seq(a -> b, b -> a)).groupMap(_._1)(_._2)
      val seen    = mutable.Set(members.min)
      val q       = mutable.Queue(members.min)
      while q.nonEmpty do
        adj.getOrElse(q.dequeue(), Nil).foreach(n => if seen.add(n) then q.enqueue(n))
      assertEquals(seen.toSet, members, s"researcher $researcher is not linked by accepted links")
    }
    // (3) A researcher never spans two components of the unconstrained closure.
    mm.groupBy(_._2._1).foreach((res, ms) =>
      assertEquals(ms.keys.map(closure).toSet.size, 1, s"researcher $res spans closure components")
    )
    // (4) A closure component holding a conflict pair is split into singletons (F2).
    val conflictComps = vetoes
      .filter(v => v.file == "conflict.jsonl" || v.status == "conflict")
      .filter(v => closure(v.a) == closure(v.b))
      .map(v => closure(v.a)).toSet
    mentionIds.filter(m => conflictComps(closure(m))).foreach(m =>
      assertEquals(mm(m)._2, "conflicted-split", m)
    )
    // (5) Every accepted link is either inside one researcher or refused, never both or neither.
    val ref      = refused(r)
    val refPairs = ref.map(x => (x._1, x._2))
    links.foreach { (a, b) =>
      val inside = rid(a) == rid(b)
      assert(inside ^ refPairs((a, b)), s"link $a-$b inside=$inside refused=${refPairs((a, b))}")
    }
    // (6) A refusal is justified on the output: its veto separates the two final clusters.
    ref.filter(_._3 == PartA.RefusedByVeto).foreach { (a, b, _, va, vb) =>
      val sides = Set(rid(a), rid(b))
      assert(
        rid(va) != rid(vb) && sides == Set(rid(va), rid(vb)),
        s"refusal of $a-$b cites veto $va-$vb that does not separate the two clusters"
      )
      assert(vetoes.exists(v => v.a == va && v.b == vb), s"cited veto $va-$vb is not an ER veto")
    }
  }

  test("several vetoes in one component: the hub joins exactly the best-ranked accepted partner") {
    val rid      = mentionMap(base).view.mapValues(_._1).toMap
    val partners = List(sJohn, sJane, sJames).map(_.memberId)
    val withHub  = partners.filter(p => rid(p) == rid(sJ.memberId))
    // Link order (documented in params.json): confidence descending, then link key ascending.
    val best = realDecisions
      .filter(d => d.memberLow == sJ.memberId || d.memberHigh == sJ.memberId)
      .filter(_.status == ResolutionStatus.Accepted)
      .sortBy(d => (-d.confidence, d.pairId))
      .head
    val expected = List(best.memberLow, best.memberHigh).filterNot(_ == sJ.memberId)
    assertEquals(withHub, expected)
    assertEquals(partners.map(rid).distinct.size, 3, "two rejected Smiths share a researcher")
  }

  test(
    "a conflict veto and a rejected veto in one component: every mention is its own researcher"
  ) {
    val mm = mentionMap(base)
    val ws = List(wA, wB, wC, wD).map(_.memberId)
    assertEquals(ws.map(mm(_)._2), List.fill(4)("conflicted-split"))
    assertEquals(ws.map(mm(_)._1).distinct.size, 4)
    val ref = refused(base).filter(x => ws.contains(x._1))
    assertEquals(ref.map(_._3), Set(PartA.RefusedBySplit))
    assertEquals(ref.size, 3, ref) // a-b, b-c, b-d
  }

  test("unresolved veto (schema-2 record) and the never-candidate rule, as Spark documents it") {
    // The u0-x/u2-z veto is a schema-2 line of unresolved.jsonl in the verified directory.
    val uKey  = s"mention|${uX.memberId}|${uZ.memberId}"
    val uLine = erFiles("unresolved.jsonl").map(ujson.read(_)).find(_("pairId").str == uKey)
    assertEquals(uLine.map(_("schemaVersion").num), Some(2.0), "the unresolved veto line")
    val mm  = mentionMap(base)
    val rid = mm.view.mapValues(_._1).toMap
    assertNotEquals(rid(uX.memberId), rid(uZ.memberId))
    assertEquals(List(uX, uZ).count(v => rid(v.memberId) == rid(uY.memberId)), 1)
    // Never-candidate: p and r have no ER decision, so they may (and do) join through q.
    assertEquals(List(nP, nQ, nR).map(v => rid(v.memberId)).distinct.size, 1)
    assertEquals(List(nP, nQ, nR).map(v => mm(v.memberId)._2).distinct, List("merged"))
    assertEquals(List(nP, nQ, nR).map(v => mm(v.memberId)._3).distinct, List(null))
    val params = ujson.read(JobFiles.readText(spark, s"${base.outputBase}/params.json"))
    assert(
      params("identityVetoRule").str.contains("pairs with no ER decision carry no veto"),
      params("identityVetoRule").str
    )
    assertEquals(params("identityVetoFiles").arr.map(_.str).toList, Outputs.MergeVetoFiles)
    assertEquals(params("erDecisions")("pipelineRunId").str, erRun)
  }

  test("a schema-v1 line in any decision file fails the run before output (resealed or not)") {
    def toV1(line: String): String =
      val j = ujson.read(line)
      j.obj.remove("scoreUpper"); j.obj.remove("corroborated"); j("schemaVersion") = 1
      j.render()
    for f <- Outputs.DecisionFiles; resealed <- List(false, true) do
      val what = s"$f resealed=$resealed"
      val root = Files.createTempDirectory("sc-rerun-v1")
      val in   = fixture(root, identity)
      val er   = root.resolve("er")
      val ls   = readLines(er.resolve(f))
      assert(ls.nonEmpty, s"$what: precondition, the file has records")
      val key = ujson.read(ls.head)("pairId").str
      writeLines(er.resolve(f), toV1(ls.head) :: ls.tail)
      if resealed then reseal(er)
      val m = failsBeforeOutput(root, in, what)
      assert(m.contains(VerifyFailed), s"$what: $m")
      assert(m.contains(s"$f line 1 ($key): not this run: schemaVersion 1"), s"$what: $m")
      assertEquals(m.contains(s"$f: sha256 "), !resealed, s"$what: $m")
  }

  test("work-scope records are ignored: neither a merge nor a veto for researcher identity") {
    val mm  = mentionMap(base)
    val rid = mm.view.mapValues(_._1).toMap
    assertNotEquals(rid(mid(gNames(0))), rid(mid(gNames(1))), "work-scope accept merged mentions")
    gNames.foreach(g => assertEquals(mm(mid(g))._2, "singleton", g))
    assertEquals(rid(nP.memberId), rid(nQ.memberId), "work-scope rejection split two mentions")
    assertEquals(base.count("erAcceptedWorkLinksNotApplied"), 2L)
  }

  test("determinism: reordered input lines and other partition counts give identical identity") {
    val expectedMap = mentionMap(base)
    val expectedRef = refused(base)
    val baseFiles   = manifestOf(baseEr).files
    List[(Seq[String] => Seq[String], Int)](
      ((s: Seq[String]) => s.reverse, 1),
      ((s: Seq[String]) => scala.util.Random(7).shuffle(s), 7)
    ).foreach { (order, parts) =>
      val (r, root) = runWith(order, parts)
      val er        = root.resolve("er")
      // The ER files really were reordered and resealed into a verified run of the same records.
      assert(Outputs.verify(er).isRight, s"partitions $parts: ${Outputs.verify(er)}")
      val files = manifestOf(er).files
      assertEquals(files.map(_.recordCount), baseFiles.map(_.recordCount), s"partitions $parts")
      assertNotEquals(files.map(_.sha256), baseFiles.map(_.sha256), s"partitions $parts")
      assertEquals(r.failures, Nil, s"partitions $parts")
      // Identity content, not run IDs (the run ID covers the ER manifest SHA-256).
      assertEquals(mentionMap(r), expectedMap, s"partitions $parts")
      assertEquals(refused(r), expectedRef, s"partitions $parts")
    }
  }

  test("identity-refused-links provenance: link, both members, and the ER run on every row") {
    val rows = spark.read.parquet(s"${base.outputBase}/identity-refused-links").collect().toList
    assert(rows.nonEmpty)
    rows.foreach { row =>
      def s(c: String) = row.getAs[String](c)
      val (lo, hi)     = (s("memberLow"), s("memberHigh"))
      assertEquals(s("pairId"), s"mention|$lo|$hi")
      // The accepted link decision: ER run provenance.
      assertEquals(
        (s("sourceDataset"), s("sourceRecordId"), s("sourcePipelineRunId")),
        ("scholarchain-pipeline", s"mention|$lo|$hi", erRun),
        row
      )
      // Both member mentions: their Hadoop source records.
      assertEquals(
        (
          s("memberLowSourceDataset"),
          s("memberLowSourceRecordId"),
          s("memberLowSourcePipelineRunId")
        ),
        ("dblp", s"t/w-${lo.stripPrefix("m-")}", hadoopRun),
        row
      )
      assertEquals(
        (
          s("memberHighSourceDataset"),
          s("memberHighSourceRecordId"),
          s("memberHighSourcePipelineRunId")
        ),
        ("dblp", s"t/w-${hi.stripPrefix("m-")}", hadoopRun),
        row
      )
      // The vetoing decision: record ID and run ID.
      val vk = s("vetoPairId")
      assertEquals((s("vetoSourceRecordId"), s("vetoSourcePipelineRunId")), (vk, erRun), row)
      assert(s("vetoDecisionId") != null && s("linkDecisionId") != null, row)
      assertEquals(s("pipelineRunId"), base.runId)
    }
  }

  test("Law 3: the vetoing decision on a refused row carries its source dataset too") {
    // CLAUDE.md law 3: every transformation keeps source dataset, source record ID, and pipeline
    // run ID. identity-refused-links must keep the veto's source dataset (vetoSourceDataset) next
    // to its record ID (vetoSourceRecordId) and run ID (vetoSourcePipelineRunId).
    val df = spark.read.parquet(s"${base.outputBase}/identity-refused-links")
    assert(
      df.columns.contains("vetoSourceDataset"),
      s"no veto source dataset column; columns: ${df.columns.mkString(", ")}"
    )
    df.collect().foreach(row =>
      assertEquals(row.getAs[String]("vetoSourceDataset"), "scholarchain-pipeline")
    )
  }

  test("a missing ER decision file fails the run before any output, naming the file") {
    Outputs.DecisionFiles.foreach { f =>
      val root = Files.createTempDirectory("sc-rerun-missing")
      val in   = fixture(root, identity, skip = Set(f))
      assert(Files.exists(root.resolve(s"er/${Outputs.ManifestFileName}")), s"$f: precondition")
      val m = failsBeforeOutput(root, in, f)
      assert(m.contains(VerifyFailed), s"$f: $m")
      assert(m.contains(s"$f: missing"), s"$f: $m")
    }
  }

  // ---- D1 trio: truncation and F2 (stale, 0-byte, mixed) through Resolver, Outputs, and PartA ------

  private val trio    = List(sJ, sJohn, sJane)
  private val vetoKey = s"mention|${sJohn.memberId}|${sJane.memberId}"

  /** D1 trio decisions of ER run `run`: J-John and J-Jane accepted, plus the John-Jane veto. */
  private def trioDecisions(run: String, withVeto: Boolean): List[ResolutionRecord] =
    val d = decideIn(run)
    List(d(sJ, sJohn), d(sJ, sJane)) ++ Option.when(withVeto)(d(sJohn, sJane)).toList

  private def trioRoot(prefix: String): Path =
    val root = Files.createTempDirectory(prefix)
    writeHadoop(root, trio.map(_.memberId.stripPrefix("m-")), identity)
    root

  private def trioRids(r: PartAResult): Map[String, String] =
    mentionMap(r).view.mapValues(_._1).toMap

  test(
    "a truncated veto line is never read as 'no veto': it fails before output, resealed or not"
  ) {
    // The John-Jane rejection line in rejected.jsonl is cut in half (a partial write). Unresealed,
    // the hash fails and the line does not parse; resealed (manifest regenerated over the broken
    // file), only the parse rule can catch it. Either way: no run, no output, never a silent merge.
    val ds = trioDecisions(erRun, withVeto = true)
    assertEquals(ds.map(_.status.wire), List("accepted", "accepted", "rejected"))
    List(false, true).foreach { resealed =>
      val what = s"resealed=$resealed"
      val root = trioRoot("sc-rerun-truncated")
      val er   = root.resolve("er")
      writeEr(er, ds)
      val line = readLines(er.resolve(Rejected)).head
      assert(line.contains(vetoKey), s"$what: precondition")
      writeLines(er.resolve(Rejected), List(line.take(line.length / 2)))
      if resealed then reseal(er)
      val m = failsBeforeOutput(root, inputs(root, er), what)
      assert(m.contains(VerifyFailed), s"$what: $m")
      assert(m.contains(s"$Rejected line 1: unparseable"), s"$what: $m")
      assertEquals(m.contains(s"$Rejected: sha256 "), !resealed, s"$what: $m")
    }
  }

  test(
    "F2: stale R1 vetoes beside R2's accepted.jsonl fail before output; a full republish works"
  ) {
    // R1 (old run) never compared John and Jane, so it has no veto: read alone, Spark joins them
    // through J (the never-candidate rule). R2 (new run) rejects John-Jane. A rerun of R2 into R1's
    // directory killed after accepted.jsonl (or a partial copy) leaves R2's links with R1's empty
    // veto files and R1's manifest; reading that would be the same over-merge despite R2's veto.
    val root = trioRoot("sc-rerun-f2-stale")
    val d    = root.resolve("er")
    writeEr(d, trioDecisions(erRunOld, withVeto = false), erRunOld)
    val r1 = partA(root, inputs(root, d), 2, "out-r1")
    assertEquals(r1.failures, Nil)
    val rid1 = trioRids(r1)
    assertEquals(trio.map(v => rid1(v.memberId)).distinct.size, 1, "control: R1 alone merges all")
    assertEquals(Files.size(d.resolve(Rejected)), 0L, "control: R1's genuinely empty veto file")

    val d2 = root.resolve("er-r2")
    writeEr(d2, trioDecisions(erRun, withVeto = true))
    assert(readLines(d2.resolve(Rejected)).exists(_.contains(vetoKey)), "precondition: R2 veto")
    // (a) R2's accepted.jsonl over R1's directory; (b) the same with a regenerated manifest.
    List(false, true).foreach { resealed =>
      val what  = s"stale resealed=$resealed"
      val stale = copyEr(d, root.resolve(s"er-stale-$resealed"))
      Files.copy(d2.resolve(Accepted), stale.resolve(Accepted), StandardCopyOption.REPLACE_EXISTING)
      if resealed then reseal(stale)
      val m = failsBeforeOutput(root, inputs(root, stale), what, s"out-stale-$resealed")
      assert(m.contains(VerifyFailed), s"$what: $m")
      assert(m.contains(s"$Accepted line 1 ("), s"$what: $m")
      assert(m.contains(s"not this run: pipelineRunId $erRun"), s"$what: $m")
      assertEquals(m.contains(s"$Accepted: sha256 "), !resealed, s"$what: $m")
    }

    // Positive: the real writer republishes R2 over R1's directory as a whole; Spark reads only R2
    // and honours its veto. No staging or replaced directory is left behind.
    writeEr(d, trioDecisions(erRun, withVeto = true))
    assertEquals(Outputs.verify(d).map(_.pipelineRunId), Right(erRun))
    val r2 = partA(root, inputs(root, d), 2, "out-r2")
    assertEquals(r2.failures, Nil)
    val rid2 = trioRids(r2)
    assertNotEquals(rid2(sJohn.memberId), rid2(sJane.memberId), "R2's veto was not honoured")
    assertEquals(
      List(sJohn, sJane).count(v => rid2(v.memberId) == rid2(sJ.memberId)),
      1,
      "J joins exactly one of John and Jane"
    )
    val params = ujson.read(JobFiles.readText(spark, s"${r2.outputBase}/params.json"))
    assertEquals(params("erDecisions")("pipelineRunId").str, erRun)
    val leftovers = Using.resource(Files.list(root))(_.iterator.asScala.toList)
      .map(_.getFileName.toString).filter(n =>
        n.startsWith(".er.staging-") || n.startsWith(".er.replaced-")
      )
    assertEquals(leftovers, Nil)
  }

  test("F2: a 0-byte veto file is never 'no vetoes' unless the manifest says so") {
    val root = trioRoot("sc-rerun-f2-zero")
    val er   = root.resolve("er")
    writeEr(er, trioDecisions(erRun, withVeto = true))
    Files.write(er.resolve(Rejected), Array.emptyByteArray)
    assertEquals(Files.size(er.resolve(Rejected)), 0L)
    val m = failsBeforeOutput(root, inputs(root, er), "0-byte rejected.jsonl")
    assert(m.contains(VerifyFailed), m)
    assert(m.contains(s"$Rejected: sha256 "), m)
    assert(m.contains(s"$Rejected: 0 records, manifest 1"), m)
  }

  test(
    "F2: a mixed-run or mixed-version append fails before output even with a resealed manifest"
  ) {
    val r2      = trioDecisions(erRun, withVeto = true)
    val v2      = r2.find(_.pairId == vetoKey).get
    val a2      = r2.find(_.status == ResolutionStatus.Accepted).get
    val appends = List[(String, String, ResolutionRecord, String)](
      // The same pair decided by the stale run R1 (the duplicate a mixed copy leaves).
      ("veto from run R1", Rejected, decideIn(erRunOld)(sJohn, sJane), s"pipelineRunId $erRunOld"),
      ("accept from run R1", Accepted, decideIn(erRunOld)(sJ, sJohn), s"pipelineRunId $erRunOld"),
      // Same run ID, an older policy or features version (a pre-bump output left in place).
      (
        "veto under an older policy version",
        Rejected,
        v2.copy(
          policyVersion = "er-policy-v4",
          decisionId = Resolver.decisionId(v2.pairId, "er-policy-v4")
        ),
        "policyVersion er-policy-v4"
      ),
      (
        "accept under an older features version",
        Accepted,
        a2.copy(featureSpecVersion = "er-features-v3"),
        "featureSpecVersion er-features-v3"
      )
    )
    appends.foreach { (what, f, rec, diff) =>
      val root = trioRoot("sc-rerun-f2-mixed")
      val er   = root.resolve("er")
      writeEr(er, r2)
      val ls = readLines(er.resolve(f))
      writeLines(er.resolve(f), ls :+ write(rec))
      reseal(er)
      assertEquals(manifestOf(er).files.find(_.name == f).map(_.recordCount), Some(ls.size + 1L))
      val m = failsBeforeOutput(root, inputs(root, er), what)
      assert(m.contains(VerifyFailed), s"$what: $m")
      assert(
        m.contains(s"$f line ${ls.size + 1} (${rec.pairId}): not this run: $diff"),
        s"$what: $m"
      )
      assert(!m.contains("sha256 "), s"$what: the resealed hashes match, only V4 fails: $m")
    }
  }
