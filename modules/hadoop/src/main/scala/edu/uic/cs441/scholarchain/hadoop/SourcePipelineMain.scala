package edu.uic.cs441.scholarchain.hadoop

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import scala.util.control.NonFatal

/**
 * Part B driver. Runs Job 2 (source normalize), Job 3 (exact IDs), Jobs 4+5 (blocking and
 * candidates), then Job 7 (graph export), in that order.
 *
 * Argument contract (positional, all required except that more than one source input may follow):
 * {{{
 * SourcePipelineMain <outputBase> <snapshotId> <runId> <reducers> <exportPartitions> <maxBlock>
 *                    <sourceInput> [<sourceInput> ...]
 * }}}
 *   - `outputBase`: the same base given to DblpPipelineMain for this run.
 *   - `snapshotId`, `runId`: the same values given to DblpPipelineMain. Validated by
 *     `Pipeline.validateSnapshot`, `Pipeline.validateRunId`, and `Pipeline.outputPath`.
 *   - `reducers`: positive integer, reducer count of Jobs 2, 3, and 4+5.
 *   - `exportPartitions`: positive integer, reducer (= partition file) count of Job 7.
 *   - `maxBlock`: positive integer, the block cap of Jobs 4+5 (default in code:
 *     `SourceConf.DefaultMaxBlock`; here it is explicit).
 *   - `sourceInput`: one or more Job 2 input files or Hadoop globs (OpenAlex, Crossref, arXiv,
 *     OpenCitations; the source comes from each file's name or folder). Duplicates are rejected,
 *     because a file read twice would be counted as DUPLICATE records instead of failing.
 *
 * The fixed arguments come first and the variable-length input list last, so a `*` expanded by the
 * shell or the Windows java launcher only adds inputs; it cannot shift a fixed argument. Every
 * argument must be non-blank (a quoted unset variable would otherwise root the output at `/`).
 *
 * Upstream: Job 1 (`dblp-extract`) and Job 6 (`team-extract`) are read from
 * `Pipeline.outputPath(outputBase, runId, snapshotId, ...)`, so DblpPipelineMain must have
 * completed the same run and snapshot first (both directories need `_SUCCESS`). Outputs:
 * `<outputBase>/run=<run>/snapshot=<snapshot>/{source-normalize,exact-ids,blocking-candidates,
 * graph-export}`. Completed outputs are reused (Pipeline.guard), so a rerun is idempotent.
 *
 * Exit status: 0 when every job succeeded; 2 for an argument rejection (reason and usage on
 * stderr); 1 when an upstream output is missing, a job fails, or a job throws (for example a
 * partial output without `_SUCCESS`). One `JOB ...` line with all counters is printed per job, then
 * `LARGEST-BLOCK ...`.
 */
object SourcePipelineMain:
  final case class Args(
      outputBase: String,
      snapshot: String,
      runId: String,
      reducers: Int,
      exportPartitions: Int,
      maxBlock: Int,
      inputs: Vector[String]
  )

  val Usage =
    "usage: <outputBase> <snapshotId> <runId> <reducers> <exportPartitions> <maxBlock> " +
      "<sourceInput> [<sourceInput> ...]"

  private val Fixed = 6

  def parseArgs(args: Seq[String]): Either[String, Args] =
    def posInt(name: String, i: Int): Either[String, Int] =
      val s = args(i)
      s.toIntOption.filter(_ > 0)
        .toRight(s"$name (argument ${i + 1}) must be a positive integer, got: $s; $Usage")
    val blank  = args.indexWhere(_.isBlank)
    val inputs = args.drop(Fixed).toVector
    val dup    = inputs.diff(inputs.distinct).headOption
    if args.length < Fixed + 1 then
      Left(
        s"expected at least ${Fixed + 1} arguments (6 fixed, then 1 or more source inputs), " +
          s"got ${args.length}; $Usage"
      )
    else if blank >= 0 then
      Left(
        s"argument ${blank + 1} is blank (empty or whitespace only; was a quoted variable unset?); " +
          Usage
      )
    else if scala.util.Try(Pipeline.validateSnapshot(args(1))).isFailure then
      Left(s"snapshotId (argument 2) is invalid: ${args(1)}; $Usage")
    else if scala.util.Try(Pipeline.validateRunId(args(2))).isFailure then
      Left(s"runId (argument 3) is not a run ID: ${args(2)}; $Usage")
    else if scala.util.Try(Pipeline.outputPath(args(0), args(2), args(1), "graph-export"))
        .isFailure
    then Left(s"outputBase (argument 1) does not form a valid output path: ${args(0)}; $Usage")
    else if dup.nonEmpty then Left(s"source input given more than once: ${dup.get}; $Usage")
    else
      for
        r <- posInt("reducers", 3)
        p <- posInt("exportPartitions", 4)
        m <- posInt("maxBlock", 5)
      yield Args(args(0), args(1), args(2), r, p, m, inputs)

  /** Job 1 and Job 6 outputs of the same run and snapshot (written by DblpPipelineMain). */
  def upstream(a: Args): (Path, Path) =
    (
      Pipeline.outputPath(a.outputBase, a.runId, a.snapshot, "dblp-extract"),
      Pipeline.outputPath(a.outputBase, a.runId, a.snapshot, "team-extract")
    )

  /** Left(reason) unless both upstream outputs exist with `_SUCCESS`. Runs no job. */
  def checkUpstream(conf: Configuration, a: Args): Either[String, (Path, Path)] =
    val (j1, j6) = upstream(a)
    val missing  = Seq(j1, j6).filterNot { p =>
      p.getFileSystem(conf).exists(new Path(p, "_SUCCESS"))
    }
    if missing.isEmpty then Right((j1, j6))
    else
      Left(
        s"upstream output without _SUCCESS (run DblpPipelineMain for the same outputBase, " +
          s"snapshotId, and runId first): ${missing.mkString(", ")}"
      )

  def main(argv: Array[String]): Unit =
    val a = parseArgs(argv.toSeq) match
      case Right(a)  => a
      case Left(msg) =>
        System.err.println(msg)
        sys.exit(2)
    val conf     = new Configuration()
    val (j1, j6) = checkUpstream(conf, a) match
      case Right(ps) => ps
      case Left(msg) =>
        System.err.println(msg)
        sys.exit(1)
    def step(r: => Pipeline.Result): Pipeline.Result =
      val res =
        try r
        catch
          case NonFatal(e) =>
            System.err.println(s"job failed: $e")
            sys.exit(1)
      println(DblpPipelineMain.report(res))
      if !res.ok then sys.exit(1)
      res
    val b  = a.outputBase
    val j2 =
      step(SourcePipeline.runNormalize(conf, a.inputs, b, a.runId, a.snapshot, a.reducers))
    val env = Seq(j1, j2.output)
    val j3  = step(SourcePipeline.runExactIds(conf, env, b, a.runId, a.snapshot, a.reducers))
    val j4  = step(
      SourcePipeline.runBlocking(conf, env, b, a.runId, a.snapshot, a.reducers, a.maxBlock)
    )
    step(
      SourcePipeline.runExport(
        conf,
        Seq(j1, j2.output, j3.output, j4.output, j6),
        b,
        a.runId,
        a.snapshot,
        a.exportPartitions
      )
    )
    println(s"LARGEST-BLOCK ${SourcePipeline.largestBlock(conf, j4.output)}")
