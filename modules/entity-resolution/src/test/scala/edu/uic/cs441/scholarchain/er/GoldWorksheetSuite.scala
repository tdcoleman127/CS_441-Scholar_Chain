package edu.uic.cs441.scholarchain.er

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

/**
 * Gold-set worksheet tooling. The loader tests use a tiny SYNTHETIC fixture
 * (src/test/resources/er/worksheet-fixture.csv) whose labels are test inputs, not gold labels. The
 * committed worksheet is only checked for reproducibility and blindness, never evaluated here.
 */
class GoldWorksheetSuite extends munit.FunSuite:

  val policy  = ScoringPolicy.loadDefault().fold(e => fail(e), identity)
  val fixture = Path.of(getClass.getClassLoader.getResource("er/worksheet-fixture.csv").toURI)

  /** The repository root, found by walking up from the working directory. */
  lazy val repoRoot: Path =
    Iterator.iterate(Path.of("").toAbsolutePath)(_.getParent).takeWhile(_ != null)
      .find(p => Files.isDirectory(p.resolve("modules/entity-resolution/gold/v1")))
      .getOrElse(fail("cannot locate modules/entity-resolution/gold/v1 from the working directory"))

  def goldFile(name: String): Path = repoRoot.resolve(s"modules/entity-resolution/gold/v1/$name")

  test("csv: quotes, doubled quotes, embedded comma and newline, CRLF, BOM, blank rows") {
    val text = "﻿a,b,c\r\n\"x,1\",\"say \"\"hi\"\"\",\"two\nlines\"\r\n\r\n,,\n"
    val rows = Csv.parse(text)
    assertEquals(
      rows.map(_._2),
      Vector(
        Vector("a", "b", "c"),
        Vector("x,1", "say \"hi\"", "two\nlines"),
        Vector("", "", "")
      )
    )
    assertEquals(rows.map(_._1), Vector(1, 2, 5))
    val back = Csv.parse(Csv.render(rows.map(_._2)))
    assertEquals(back.map(_._2), rows.map(_._2))
  }

  test("worksheet loader: same/different count, unsure/blank/untraced skipped, bad rows reported") {
    val loaded = Evaluator.loadWorksheet(fixture, policy)
    val l      = loaded.load
    assertEquals(
      (l.labeled.size, l.skippedUnlabeled, l.skippedUnsure, l.skippedUntraced),
      (5, 1, 1, 1)
    )
    assertEquals(l.rejectedLines.size, 2)
    assert(l.rejectedLines.exists(m =>
      m.contains("line 10") && m.contains("unknown label 'maybe'")
    ))
    assert(l.rejectedLines.exists(m => m.contains("line 11") && m.contains("20x4")))
    // Labels map to the evaluator's vocabulary and stay traceable to the source records.
    val f01 = l.labeled.find(_.item.provenanceLow.sourceRecordId == "fixture/a1").get
    assertEquals(
      (f01.isMatch, f01.item.label, f01.item.labeler),
      (
        true,
        Some("match"),
        Some("fixture-labeler")
      )
    )
    assertEquals(f01.item.provenanceHigh.sourceRecordId, "fixture/b1")
    assertEquals(f01.item.policyVersion, policy.policyVersion)
    val f03 = l.labeled.find(_.item.provenanceLow.sourceRecordId == "fixture/a3").get
    assertEquals(f03.item.labelNote, Some("note, with comma"))

    val e    = Evaluator.evaluate(policy, l)
    val path = e.byPath.toMap
    assertEquals(path(MatchPath.ExactId), Confusion(1, 0, 0, 0, 0, 0))       // F01
    assertEquals(path(MatchPath.Deterministic), Confusion(1, 1, 1, 0, 0, 0)) // F03, F04, F02
    assertEquals(path(MatchPath.Probabilistic), Confusion(0, 0, 0, 0, 1, 0)) // F05 unresolved
    assertEquals(e.overall, Confusion(2, 1, 1, 0, 1, 0))
    assertEqualsDouble(e.overall.precision.get, 2.0 / 3, 1e-9)
    assertEqualsDouble(e.overall.recall.get, 2.0 / 3, 1e-9)
    assertEqualsDouble(e.overall.f1.get, 2.0 / 3, 1e-9)
    assertEquals(GoldWorksheet.byStratum(loaded).toMap.apply("s-name"), Confusion(1, 1, 0, 0, 0, 0))

    val report = GoldWorksheet.report(policy, loaded)
    assert(report.contains(s"worksheet sha256=${loaded.sha256}"))
    assert(report.contains("labeled=5 unlabeled=1 unsure=1 untraced=1 malformed=2"))
    assert(report.contains("Confusion by selection stratum"))
  }

  test("worksheet loader: a default labeler makes rows with a blank labeler cell count") {
    val l = Evaluator.loadWorksheet(fixture, policy, Some("human-architect")).load
    assertEquals((l.labeled.size, l.skippedUntraced), (6, 0))
    val f08 = l.labeled.find(_.item.provenanceLow.sourceRecordId == "fixture/a8").get
    assertEquals(f08.item.labeler, Some("human-architect"))
    assertEquals(f08.item.status, ResolutionStatus.Accepted)
  }

  test("worksheet loader: missing columns are reported, not guessed") {
    val tmp = Files.createTempFile("ws", ".csv")
    Files.writeString(tmp, "pair_id,label\nP001,same\n", UTF_8)
    val l = GoldWorksheet.load(tmp, policy, None).load
    assertEquals(l.labeled, Nil)
    assert(l.rejectedLines.head.startsWith("missing columns:"))
    Files.delete(tmp)
  }

  test("selection: deterministic, blind (no score, decision, or label), provenance on every side") {
    val inv = GoldWorksheet.readInventory(Files.readString(goldFile("mentions-v1.csv"), UTF_8))
      .fold(e => fail(e), identity)
    val s1 = GoldWorksheet.select(inv)
    val s2 = GoldWorksheet.select(inv.reverse)
    assertEquals(GoldWorksheet.worksheet(s1), GoldWorksheet.worksheet(s2))
    val header = GoldWorksheet.Header.map(_.toLowerCase)
    assert(!header.exists(h =>
      List("score", "status", "decision", "path", "suggest").exists(h.contains)
    ))
    val rows = GoldWorksheet.worksheet(s1).tail
    assert(rows.forall(r => r.takeRight(3) == List("", "", "")))
    val col = GoldWorksheet.Header.zipWithIndex.toMap
    for r <- rows; side <- List("a_", "b_"); f <- List("source_dataset", "source_record_id") do
      assert(r(col(side + f)).nonEmpty, s"${r.head}: $side$f is empty")
    assertEquals(rows.map(_.head).distinct.size, rows.size)
    val counts = s1.pairs.groupMapReduce(_._1)(_ => 1)(_ + _)
    GoldWorksheet.Strata.foreach((s, q) =>
      assertEquals(counts.getOrElse(s, 0), math.min(q, s1.available(s)), s)
    )
    assert(rows.size >= 45 && rows.size <= 55, s"${rows.size} pairs")
  }

  test("committed worksheet and manifest equal a regeneration from the committed inventory") {
    val invBytes = Files.readAllBytes(goldFile("mentions-v1.csv"))
    val inv = GoldWorksheet.readInventory(new String(invBytes, UTF_8)).fold(e => fail(e), identity)
    val sel = GoldWorksheet.select(inv)
    val expected = GoldWorksheet.worksheet(sel)
    val manifest = GoldWorksheet.manifest(
      GoldWorksheet.inventorySha(invBytes),
      inv.size,
      sel
    )
    val outDir               = repoRoot.resolve("modules/entity-resolution/target/gold")
    def actual(name: String) = Option.when(Files.exists(goldFile(name)))(
      Files.readString(goldFile(name), UTF_8).replace("\r\n", "\n")
    )
    // Compare every column except the human's label, labeler, and notes.
    def blind(rows: Seq[Seq[String]]) = rows.map(_.dropRight(3))
    val committed = actual("labeling-worksheet-v1.csv").map(t => Csv.parse(t).map(_._2))
    if !committed.exists(c => blind(c) == blind(expected)) ||
      !actual("selection-manifest-v1.txt").contains(manifest)
    then
      Files.createDirectories(outDir)
      Files.writeString(outDir.resolve("labeling-worksheet-v1.csv"), Csv.render(expected), UTF_8)
      Files.writeString(outDir.resolve("selection-manifest-v1.txt"), manifest, UTF_8)
      fail(
        s"committed gold worksheet or manifest differs from regeneration; expected files in $outDir"
      )
  }

  test("inventory: every mention's source record ID occurs in the committed sample file") {
    val inv = GoldWorksheet.readInventory(Files.readString(goldFile("mentions-v1.csv"), UTF_8))
      .fold(e => fail(e), identity)
    val files = Map(
      "dblp"     -> "datasets/samples/dblp/dblp-sample.xml",
      "openalex" -> "datasets/samples/openalex/openalex-works-sample.jsonl",
      "crossref" -> "datasets/samples/crossref/crossref-works-sample.jsonl",
      "arxiv"    -> "datasets/samples/arxiv/arxiv-oai-sample.xml"
    ).view.mapValues(f => Files.readString(repoRoot.resolve(f), UTF_8)).toMap
    inv.foreach { m =>
      val text   = files(m.sourceDataset)
      val needle = m.sourceDataset match
        case "dblp"     => s"key=\"${m.sourceRecordId}\""
        case "crossref" => s"\"DOI\":\"${m.sourceRecordId}\""
        case "arxiv"    => s"<identifier>${m.sourceRecordId}</identifier>"
        case _          => s"\"id\":\"${m.sourceRecordId}\""
      assert(text.contains(needle), s"${m.mentionId}: $needle not in ${m.sourceDataset} sample")
    }
    assertEquals(inv.map(_.mentionId).distinct.size, inv.size)
  }
