package edu.uic.cs441.scholarchain.er

import edu.uic.cs441.scholarchain.core.model.{IdNamespace, Provenance, SourceType}
import upickle.default.write

import java.io.{ByteArrayInputStream, IOException}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import scala.jdk.CollectionConverters.*
import scala.util.Using

/**
 * Phase 11 repair-03 (code-reviewer review-02 F2, producer side): the ER decision directory is
 * self-certifying. It is published atomically (a reader never sees a mix of two runs at the path)
 * and carries a completion manifest, written last, that a consumer verifies: per-file record count
 * and SHA-256, and one pipelineRunId for every record. All inputs are synthetic.
 */
class DecisionOutputsSuite extends munit.FunSuite:

  val runA   = "run:v1:" + "a" * 64
  val runB   = "run:v1:" + "e" * 64
  val policy = ScoringPolicy.loadDefault().fold(e => fail(e), identity)

  val EmptySha = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

  def view(id: String, name: String, coauthors: List[String], ids: Map[IdNamespace, List[String]]) =
    EntityView(
      id,
      s"work:dblp:t/$id",
      name,
      "Graph partitioning",
      Some(2020),
      coauthors,
      ids,
      Provenance(SourceType.Dblp, s"src/$id", runA)
    )

  /** One accepted, one rejected, one unresolved, one conflict decision of run `run`. */
  def decisions(run: String): List[ResolutionRecord] =
    val john = view("m-a", "John Smith", List("Alan Turing"), Map.empty)
    val j    = view("m-b", "J. Smith", List("Alan Turing"), Map.empty)
    val jane = view("m-c", "Jane Smith", List("Alan Turing"), Map.empty)
    val solo = view("m-d", "J. Smith", Nil, Map.empty).copy(title = "Distributed hash tables")
    val o1   = view("m-e", "Wei Wang", Nil, Map(IdNamespace.Orcid -> List("0000-0002-1825-0097")))
    val o2   = view("m-f", "Wei Wang", Nil, Map(IdNamespace.Orcid -> List("0000-0001-5109-3700")))
    def d(x: EntityView, y: EntityView) =
      Resolver.decide(policy, PairInput.fromViews("mention", "k", x, y), run)
    val recs = List(d(john, j), d(john, jane), d(j, solo), d(o1, o2))
    assertEquals(
      recs.map(_.status),
      List(
        ResolutionStatus.Accepted,
        ResolutionStatus.Rejected,
        ResolutionStatus.Unresolved,
        ResolutionStatus.Conflict
      ),
      "fixture precondition"
    )
    recs

  def fresh(): Path = Files.createTempDirectory("er-f2").resolve("er")

  def bytes(dir: Path): Map[String, Seq[Byte]] =
    Using.resource(Files.list(dir))(_.iterator.asScala.toList)
      .map(p => p.getFileName.toString -> Files.readAllBytes(p).toSeq).toMap

  def siblings(dir: Path): List[String] =
    Using.resource(Files.list(dir.getParent))(_.iterator.asScala.toList)
      .map(_.getFileName.toString).filter(_ != dir.getFileName.toString).sorted

  def sha(b: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(b).map(x => f"${x & 0xff}%02x").mkString

  def problems(dir: Path): List[String] =
    Outputs.verify(dir).swap.getOrElse(fail(s"$dir verified, expected problems"))

  final class Crash(at: String) extends RuntimeException(s"simulated kill after $at")

  test(
    "writeAll publishes four files, the review queue, and a manifest written last that verifies"
  ) {
    val dir    = fresh()
    val counts = Outputs.writeAll(dir, decisions(runA), 10)
    assertEquals(
      counts,
      Map("accepted" -> 1, "rejected" -> 1, "unresolved" -> 1, "conflict" -> 1, "review-queue" -> 4)
    )
    assertEquals(
      bytes(dir).keySet,
      Set(
        "accepted.jsonl",
        "rejected.jsonl",
        "unresolved.jsonl",
        "conflict.jsonl",
        "review-queue.jsonl",
        "_DECISIONS_MANIFEST.json"
      )
    )
    val js = ujson.read(Files.readAllBytes(dir.resolve(Outputs.ManifestFileName)))
    assertEquals(
      js.obj.keys.toList,
      List(
        "manifestSchemaVersion",
        "pipelineRunId",
        "policyVersion",
        "featuresVersion",
        "schemaVersion",
        "files"
      )
    )
    assertEquals(js("manifestSchemaVersion").num, 1.0)
    assertEquals(js("pipelineRunId").str, runA)
    assertEquals(js("policyVersion").str, "er-policy-v5")
    assertEquals(js("featuresVersion").str, Features.SpecVersion)
    assertEquals(js("schemaVersion").num, 2.0)
    assertEquals(js("files").arr.map(_("name").str).toList, Outputs.DecisionFiles)
    js("files").arr.foreach { f =>
      val b = Files.readAllBytes(dir.resolve(f("name").str))
      assertEquals(f.obj.keys.toList, List("name", "recordCount", "sha256"))
      assertEquals(f("sha256").str, sha(b))               // independent SHA-256 of the exact bytes
      assert(f("recordCount").isInstanceOf[ujson.Num], f) // a JSON number, not a string
      assertEquals(f("recordCount").num, 1.0)
    }
    val m = Outputs.verify(dir).fold(p => fail(p.mkString("\n")), identity)
    assertEquals((m.pipelineRunId, m.files.map(_.recordCount)), (runA, List(1L, 1L, 1L, 1L)))
    assertEquals(Outputs.readManifest(dir), Right(m))
    assertEquals(siblings(dir), Nil, "no staging or replaced directory is left behind")
  }

  test("a kill after any staged file leaves the previous run intact and verifying (no mix)") {
    // review-02 F2: the old writer overwrote accepted.jsonl first, in place. A kill then left run
    // B's links next to run A's vetoes. Now nothing at the path changes before the publish.
    val names = Outputs.DecisionFiles :+ Outputs.ReviewQueueFile :+ Outputs.ManifestFileName
    names.foreach { at =>
      val dir = fresh()
      Outputs.writeAll(dir, decisions(runA), 10)
      val before = bytes(dir)
      intercept[Crash](
        Outputs.writeRunWith(
          dir,
          decisions(runB).take(2),
          10,
          runB,
          "er-policy-v5",
          n => if n == at then throw Crash(at)
        )
      )
      assertEquals(bytes(dir), before, s"kill after $at changed the published directory")
      assertEquals(Outputs.verify(dir).map(_.pipelineRunId), Right(runA), at)
      assertEquals(siblings(dir), Nil, s"kill after $at left a staging directory")
    }
  }

  test("a mix of two runs (the old in-place failure) fails verification") {
    val a = fresh()
    val b = fresh()
    Outputs.writeAll(a, decisions(runA), 10)
    Outputs.writeAll(b, decisions(runB), 10)
    // New accepted.jsonl next to the previous run's vetoes and manifest.
    Files.copy(b.resolve("accepted.jsonl"), a.resolve("accepted.jsonl"), REPLACE)
    val p1 = problems(a)
    assert(p1.exists(_.startsWith("accepted.jsonl: sha256")), p1)
    assert(p1.exists(_.contains(s"pipelineRunId $runB")), p1)
    // The new manifest and accepted.jsonl next to the previous run's vetoes.
    Files.copy(b.resolve(Outputs.ManifestFileName), a.resolve(Outputs.ManifestFileName), REPLACE)
    val p2 = problems(a)
    Outputs.MergeVetoFiles.foreach(f => assert(p2.exists(_.startsWith(s"$f: sha256")), (f, p2)))
    assert(p2.exists(_.contains(s"pipelineRunId $runA")), p2)
  }

  val REPLACE = java.nio.file.StandardCopyOption.REPLACE_EXISTING

  test("a 0-byte veto file means 'no vetoes' only when the manifest says so") {
    val dir = fresh()
    Outputs.writeAll(dir, decisions(runA), 10)
    val written = sha(Files.readAllBytes(dir.resolve("rejected.jsonl")))
    Files.write(dir.resolve("rejected.jsonl"), Array.emptyByteArray) // truncated to 0 bytes
    assertEquals(
      problems(dir),
      List(
        s"rejected.jsonl: sha256 $EmptySha, manifest $written",
        "rejected.jsonl: 0 records, manifest 1"
      )
    )
    // A run with no rejections: the empty file is described by the manifest and verifies.
    val none = fresh()
    Outputs.writeAll(none, decisions(runA).filter(_.status != ResolutionStatus.Rejected), 10)
    val m   = Outputs.verify(none).fold(p => fail(p.mkString("\n")), identity)
    val rej = m.files.find(_.name == "rejected.jsonl").get
    assertEquals((rej.recordCount, rej.sha256), (0L, EmptySha))
    assertEquals(Files.size(none.resolve("rejected.jsonl")), 0L)
  }

  test("an absent, unreadable, or malformed manifest fails closed") {
    val dir = fresh()
    Outputs.writeAll(dir, decisions(runA), 10)
    val good = Files.readAllBytes(dir.resolve(Outputs.ManifestFileName))
    def withManifest(edit: ujson.Value => Unit): List[String] =
      val js = ujson.read(good); edit(js)
      Files.write(dir.resolve(Outputs.ManifestFileName), js.render().getBytes(UTF_8))
      problems(dir)
    Files.delete(dir.resolve(Outputs.ManifestFileName))
    assert(problems(dir).head.contains("_DECISIONS_MANIFEST.json missing"), problems(dir))
    assert(Outputs.readManifest(dir).isLeft)
    Files.write(dir.resolve(Outputs.ManifestFileName), "{not json".getBytes(UTF_8))
    assert(problems(dir).head.contains("unreadable"), problems(dir))
    assert(withManifest(_("manifestSchemaVersion") = 2).head.contains("manifestSchemaVersion 2"))
    assert(withManifest(_("schemaVersion") = 1).head.contains("schemaVersion 1"))
    assert(withManifest(_("pipelineRunId") = "run-1").head.contains("not a run:v1 ID"))
    assert(withManifest(_("policyVersion") = " ").head.contains("must not be blank"))
    assert(withManifest(_.obj.remove("pipelineRunId")).head.contains("unreadable"))
    assert(withManifest(_("files").arr.remove(3)).head.contains("exactly"))
    assert(withManifest(js => js("files").arr(3) = js("files").arr(0)).head.contains("exactly"))
    assert(withManifest(_("files")(1)("sha256") = "ABC").head.contains("sha256 64"))
    assert(withManifest(_("files")(1)("recordCount") = -1).head.contains("recordCount"))
    assert(withManifest(_("files")(1)("recordCount") = 2).contains(
      "rejected.jsonl: 1 records, manifest 2"
    ))
    // A missing decision file fails even when the manifest is intact.
    Files.write(dir.resolve(Outputs.ManifestFileName), good)
    assert(Outputs.verify(dir).isRight)
    Files.delete(dir.resolve("conflict.jsonl"))
    assertEquals(problems(dir), List("conflict.jsonl: missing"))
  }

  test("a forged, self-consistent manifest still fails when a record is from another run") {
    val dir = fresh()
    Outputs.writeAll(dir, decisions(runA), 10)
    val foreign = write(decisions(runB).find(_.status == ResolutionStatus.Rejected).get) + "\n"
    val fb      = (new String(Files.readAllBytes(dir.resolve("rejected.jsonl")), UTF_8) + foreign)
      .getBytes(UTF_8)
    Files.write(dir.resolve("rejected.jsonl"), fb)
    val js = ujson.read(Files.readAllBytes(dir.resolve(Outputs.ManifestFileName)))
    js("files")(1)("sha256") = sha(fb); js("files")(1)("recordCount") = 2
    Files.write(dir.resolve(Outputs.ManifestFileName), js.render().getBytes(UTF_8))
    val p = problems(dir)
    assertEquals(p.size, 1, p)
    assert(p.head.startsWith("rejected.jsonl line 2") && p.head.contains(s"pipelineRunId $runB"), p)
    // A record filed under the wrong status, and an unparseable line, are problems too.
    val acc = Files.readAllBytes(dir.resolve("accepted.jsonl"))
    val bad = (new String(acc, UTF_8) + "{truncated\n").getBytes(UTF_8)
    Files.write(dir.resolve("conflict.jsonl"), bad)
    js("files")(3)("sha256") = sha(bad); js("files")(3)("recordCount") = 2
    Files.write(dir.resolve(Outputs.ManifestFileName), js.render().getBytes(UTF_8))
    val q = problems(dir)
    assert(q.exists(l => l.startsWith("conflict.jsonl line 1") && l.contains("status accepted")), q)
    assert(q.exists(_.startsWith("conflict.jsonl line 2: unparseable")), q)
  }

  test("the writer refuses records of more than one run and leaves outDir untouched") {
    val dir = fresh()
    Outputs.writeAll(dir, decisions(runA), 10)
    val before = bytes(dir)
    val mixed  = decisions(runA).take(3) ++ decisions(runB).drop(3)
    val e      = intercept[IllegalArgumentException](Outputs.writeAll(dir, mixed, 10))
    assert(e.getMessage.contains("exactly one ER run"), e.getMessage)
    val otherPolicy = decisions(runA).map(r =>
      if r.status == ResolutionStatus.Conflict then r.copy(policyVersion = "er-policy-v4") else r
    )
    intercept[IllegalArgumentException](Outputs.writeAll(dir, otherPolicy, 10))
    intercept[IllegalArgumentException](
      Outputs.writeRun(dir, decisions(runA), 10, runB, "er-policy-v5")
    )
    intercept[IllegalArgumentException](Outputs.writeRun(dir, Nil, 10, "run-x", "er-policy-v5"))
    assertEquals(bytes(dir), before)
    assertEquals(siblings(dir), Nil)
  }

  test("a rerun replaces the previous run completely; foreign content is never replaced") {
    val dir = fresh()
    Outputs.writeAll(dir, decisions(runA), 10)
    Outputs.writeAll(dir, decisions(runB).filter(_.status != ResolutionStatus.Conflict), 10)
    val m = Outputs.verify(dir).fold(p => fail(p.mkString("\n")), identity)
    assertEquals(m.pipelineRunId, runB)
    assertEquals(Files.size(dir.resolve("conflict.jsonl")), 0L, "run A's conflict was kept")
    assertEquals(siblings(dir), Nil, "the replaced directory was not removed")
    // A directory written before the manifest existed (no manifest) may be replaced.
    val old = fresh()
    Files.createDirectories(old)
    (Outputs.DecisionFiles :+ Outputs.ReviewQueueFile).foreach(f =>
      Files.write(old.resolve(f), Array.emptyByteArray)
    )
    Outputs.writeAll(old, decisions(runA), 10)
    assert(Outputs.verify(old).isRight)
    // An existing empty directory (for example Files.createTempDirectory) is fine too.
    val empty = Files.createTempDirectory("er-f2-empty")
    Outputs.writeAll(empty, decisions(runA), 10)
    assert(Outputs.verify(empty).isRight)
    // Anything the writer does not own: refused, nothing moved, nothing deleted.
    val mine = fresh()
    Outputs.writeAll(mine, decisions(runA), 10)
    Files.write(mine.resolve("notes.txt"), "keep me".getBytes(UTF_8))
    val before = bytes(mine)
    val e      = intercept[IOException](Outputs.writeAll(mine, decisions(runB), 10))
    assert(e.getMessage.contains("notes.txt"), e.getMessage)
    assertEquals(bytes(mine), before)
    assertEquals(siblings(mine), Nil)
    val sub = fresh()
    Files.createDirectories(sub.resolve("nested"))
    intercept[IOException](Outputs.writeAll(sub, decisions(runA), 10))
    assert(Files.isDirectory(sub.resolve("nested")))
  }

  test("an empty run gets a manifest through writeRun; writeAll needs records to name the run") {
    val dir    = fresh()
    val counts = Outputs.writeRun(dir, Nil, 10, runA, policy.policyVersion)
    assertEquals(counts.values.toSet, Set(0))
    val m = Outputs.verify(dir).fold(p => fail(p.mkString("\n")), identity)
    assertEquals((m.pipelineRunId, m.files.map(_.sha256).distinct), (runA, List(EmptySha)))
    intercept[IllegalArgumentException](Outputs.writeAll(fresh(), Nil, 10))
  }

  test("verifyWith: the same rules over any byte source (for Spark on HDFS or S3)") {
    val dir = fresh()
    Outputs.writeAll(dir, decisions(runA), 10)
    val files                              = bytes(dir)
    val man                                = files(Outputs.ManifestFileName).toArray
    def open(drop: Set[String])(n: String) =
      Option.when(!drop(n))(new ByteArrayInputStream(files(n).toArray))
    assertEquals(Outputs.verifyWith(man, open(Set.empty)), Outputs.verify(dir))
    assertEquals(
      Outputs.verifyWith(man, open(Set("unresolved.jsonl"))),
      Left(List("unresolved.jsonl: missing"))
    )
    val boom = Outputs.verifyWith(man, _ => throw new IOException("denied"))
    assertEquals(boom.swap.map(_.size), Right(4))
  }
