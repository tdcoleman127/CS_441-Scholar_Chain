package edu.uic.cs441.scholarchain.hadoop

/**
 * DblpPipelineMain argument parsing. Phase 04 local run: the Windows java launcher expanded the
 * unquoted glob `dblp-*.xml` into two file names, every later positional argument shifted by one,
 * and the old driver died on `Integer.parseInt(runId)`. With a different file count the shift would
 * have used a file as the DTD and the DTD path as the output base. Any shifted vector must now be
 * rejected before a job is built.
 */
class DriverArgsSuite extends munit.FunSuite:
  private val run  = Fixtures.runId
  private val good =
    Seq("in/dblp-{sample,malformed}.xml", "in/dblp.dtd", "file:///out", "snap", run)

  test("a valid vector parses, with defaults and with both optional integers") {
    assertEquals(
      DblpPipelineMain.parseArgs(good),
      Right(DblpPipelineMain.Args(good(0), good(1), good(2), "snap", run, 2, Conf.DefaultOversize))
    )
    assertEquals(
      DblpPipelineMain.parseArgs(good ++ Seq("3", "40")).map(a => (a.reducers, a.oversize)),
      Right((3, 40))
    )
  }

  test("the observed glob-expanded vector (8 arguments) is rejected, not shifted") {
    val expanded = Seq(
      "in/dblp-malformed.xml",
      "in/dblp-sample.xml",
      "in/dblp.dtd",
      "file:///out",
      "snap",
      run,
      "2",
      "50"
    )
    val res = DblpPipelineMain.parseArgs(expanded)
    assert(res.isLeft, res)
    assert(res.left.exists(_.contains("expected 5 to 7 arguments, got 8")), res)
  }

  test("a shift that keeps the count in range is caught by the snapshot or run ID check") {
    // Glob expanded to two files with no optional arguments: 6 arguments, output base lands in
    // the snapshot slot.
    val six = Seq("in/a.xml", "in/b.xml", "in/dblp.dtd", "file:///out", "snap", run)
    assert(DblpPipelineMain.parseArgs(six).left.exists(_.contains("snapshotId")))
    // Snapshot valid but the run ID slot holds something else.
    assert(DblpPipelineMain.parseArgs(good.updated(4, "snap2")).left.exists(_.contains("runId")))
  }

  test("a blank (empty or whitespace-only) positional argument is rejected (review round 4, F3)") {
    // A quoted but unset "$OUT" gives an empty output base; the job would then write
    // /run=.../snapshot=... at the file-system root instead of failing with exit 2.
    val full = good ++ Seq("2", "50")
    for i <- full.indices; b <- Seq("", "   ", "\t") do
      val res = DblpPipelineMain.parseArgs(full.updated(i, b))
      assert(res.left.exists(_.contains(s"argument ${i + 1} is blank")), s"i=$i b='$b' $res")
    assert(DblpPipelineMain.parseArgs(good.updated(2, "")).left.exists(_.contains("argument 3")))
  }

  test("too few arguments and non-positive or non-numeric integers are rejected") {
    assert(DblpPipelineMain.parseArgs(good.take(4)).isLeft)
    assert(DblpPipelineMain.parseArgs(good :+ "0").left.exists(_.contains("reducers")))
    assert(DblpPipelineMain.parseArgs(good :+ "x").left.exists(_.contains("reducers")))
    assert(DblpPipelineMain.parseArgs(good ++ Seq("2", "-1")).left.exists(_.contains("oversize")))
  }
