package edu.uic.cs441.scholarchain.hadoop

import edu.uic.cs441.scholarchain.core.ids.{IdFormat, IdKind}
import edu.uic.cs441.scholarchain.core.json.Wire
import java.nio.charset.StandardCharsets
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.compress.DefaultCodec
import org.apache.hadoop.io.{LongWritable, NullWritable, Text}
import org.apache.hadoop.mapreduce.lib.input.{FileInputFormat, FileSplit, TextInputFormat}
import org.apache.hadoop.mapreduce.lib.output.{
  FileOutputFormat,
  LazyOutputFormat,
  MultipleOutputs,
  TextOutputFormat
}
import org.apache.hadoop.mapreduce.{Job, Mapper, Partitioner, Reducer}
import scala.jdk.CollectionConverters.*
import upickle.default.{read, write}

/** Counter group `ScholarChain` (names are stable; reports grep them). */
enum ScCounter extends java.lang.Enum[ScCounter]:
  case INPUT, ACCEPTED, REJECTED, DUPLICATE, CONFLICT, MISSING_KEY, SCHEMA_FAILURE,
    UNSUPPORTED_TYPE, OVERSIZE_TEAM, EMITTED_PAIR, TEAMS, AUTHOR_PAPERS,
    // Part B (Jobs 2, 3, 4+5, 7). Appended so part A names and order are unchanged.
    HEADER_SKIPPED, CITATIONS, EXACT_ID_KEY, LINKED_KEY, BLOCKS, OVERSIZE_BLOCK, CANDIDATES,
    EXPORTED

object Conf:
  val RunId             = "scholarchain.run.id"
  val Snapshot          = "scholarchain.snapshot.id"
  val DtdPath           = "scholarchain.dblp.dtd"
  val OversizeThreshold = "scholarchain.team.oversize.threshold"
  val DefaultOversize   = 50

/**
 * Explicit partitioner: Text key (a work ID) -> partition by the non-negative `String.hashCode` of
 * the key, which is specified by the JDK and therefore identical on every node and run.
 */
class WorkIdPartitioner extends Partitioner[Text, Text]:
  override def getPartition(key: Text, value: Text, n: Int): Int =
    WorkIdPartitioner.partition(key.toString, n)

object WorkIdPartitioner:
  def partition(workId: String, n: Int): Int = (workId.hashCode & Int.MaxValue) % n

// ============================================================================================
// Job 1: DBLP record extraction.
//   Input       DblpXmlInputFormat: (LongWritable byte offset, Text one whole record element)
//   Map out     (Text workId, Text WorkEnvelope JSON)     accepted records
//               named output `rejects`: (NullWritable, Text RejectRecord v2 JSON) under
//               `rejects/v2/`; carries the input file base name and the record's byte offset
//               in that file (see RejectRecord); offsets restart at 0 in every file.
//   Partition   WorkIdPartitioner on workId
//   Reduce in   (Text workId, Iterable[Text WorkEnvelope JSON])
//   Reduce out  main output: (NullWritable, Text WorkEnvelope JSON), one per distinct workId;
//               named `works`: WorkRecord JSON (core Wire); named `mentions`: AuthorMention JSON.
//               Values are sorted and the smallest kept, so the choice is deterministic; extra
//               identical copies count DUPLICATE, differing copies count CONFLICT.
// ============================================================================================
class DblpExtractMapper extends Mapper[LongWritable, Text, Text, Text]:
  private var entities: Map[String, String]    = Map.empty
  private var runId, snapshot, inputFile       = ""
  private var mos: MultipleOutputs[Text, Text] = scala.compiletime.uninitialized
  private val outKey, outValue                 = new Text

  override def setup(ctx: Mapper[LongWritable, Text, Text, Text]#Context): Unit =
    val conf = ctx.getConfiguration
    runId = conf.get(Conf.RunId); snapshot = conf.get(Conf.Snapshot)
    inputFile = DblpExtractMapper.inputFileName(ctx.getInputSplit)
    val dtd = new Path(conf.get(Conf.DtdPath))
    val in  = dtd.getFileSystem(conf).open(dtd)
    try entities = DblpEntities.fromDtd(new String(in.readAllBytes(), StandardCharsets.ISO_8859_1))
    finally in.close()
    mos = new MultipleOutputs(ctx)

  override def map(
      offset: LongWritable,
      record: Text,
      ctx: Mapper[LongWritable, Text, Text, Text]#Context
  ): Unit =
    ctx.getCounter(ScCounter.INPUT).increment(1)
    DblpRecordParser.parse(record.toString, entities, runId) match
      case Right(pw) =>
        ctx.getCounter(ScCounter.ACCEPTED).increment(1)
        outKey.set(pw.work.workId)
        outValue.set(write(WorkEnvelope(pw.recordType, pw.work, pw.mentions)))
        ctx.write(outKey, outValue)
      case Left(rej) =>
        ctx.getCounter(ScCounter.REJECTED).increment(1)
        rej.reason match
          case "missing-key"             => ctx.getCounter(ScCounter.MISSING_KEY).increment(1)
          case "unsupported-record-type" => ctx.getCounter(ScCounter.UNSUPPORTED_TYPE).increment(1)
          case _                         => ctx.getCounter(ScCounter.SCHEMA_FAILURE).increment(1)
        val rr = DblpExtractMapper.rejectRecord(rej, inputFile, snapshot, runId, offset.get)
        mos.write("rejects", NullWritable.get, new Text(write(rr)), DblpExtractMapper.RejectBase)

  override def cleanup(ctx: Mapper[LongWritable, Text, Text, Text]#Context): Unit = mos.close()

object DblpExtractMapper:
  /** Reject schema written by Job 1; the directory carries the same version. */
  val RejectSchemaVersion = 2
  val RejectBase          = s"rejects/v$RejectSchemaVersion/part"

  /**
   * The reject record's `inputFile`: the base name of the split's file (`dblp.xml.gz`,
   * `dblp-sample.xml`), never the full URI, so reject output does not depend on where the input or
   * the output lives. DblpXmlInputFormat only produces FileSplits; anything else is a wiring error
   * and fails the task rather than writing rejects without file provenance.
   */
  def inputFileName(split: org.apache.hadoop.mapreduce.InputSplit): String = split match
    case f: FileSplit => f.getPath.getName
    case other        =>
      throw new IllegalStateException(s"Job 1 expects a FileSplit, got ${other.getClass.getName}")

  def rejectRecord(
      rej: Rejection,
      inputFile: String,
      snapshot: String,
      runId: String,
      offset: Long
  ): RejectRecord =
    RejectRecord(
      RejectSchemaVersion,
      rej.reason,
      "dblp",
      rej.sourceRecordId,
      snapshot,
      runId,
      inputFile,
      offset,
      rej.detail.take(500)
    )

class DblpExtractReducer extends Reducer[Text, Text, NullWritable, Text]:
  private var mos: MultipleOutputs[NullWritable, Text] = scala.compiletime.uninitialized

  override def setup(ctx: Reducer[Text, Text, NullWritable, Text]#Context): Unit =
    mos = new MultipleOutputs(ctx)

  override def reduce(
      key: Text,
      values: java.lang.Iterable[Text],
      ctx: Reducer[Text, Text, NullWritable, Text]#Context
  ): Unit =
    val all  = values.asScala.map(_.toString).toVector.sorted
    val kept = all.head
    val dups = all.tail
    ctx.getCounter(ScCounter.DUPLICATE).increment(dups.count(_ == kept).toLong)
    ctx.getCounter(ScCounter.CONFLICT).increment(dups.count(_ != kept).toLong)
    val env = read[WorkEnvelope](kept)
    ctx.write(NullWritable.get, new Text(kept))
    mos.write("works", NullWritable.get, new Text(Wire.encode(env.work)), "works/part")
    env.mentions.foreach(m =>
      mos.write("mentions", NullWritable.get, new Text(Wire.encode(m)), "mentions/part")
    )

  override def cleanup(ctx: Reducer[Text, Text, NullWritable, Text]#Context): Unit = mos.close()

// ============================================================================================
// Job 6: publication team extraction.
//   Input       TextInputFormat over Job 1 main output: (LongWritable, Text WorkEnvelope JSON)
//   Map out     (Text workId, Text WorkEnvelope JSON)
//   Partition   WorkIdPartitioner on workId
//   Reduce in   (Text workId, Iterable[Text WorkEnvelope JSON]) (one value after Job 1 dedup)
//   Reduce out  named `teams`: TeamRecord JSON, one per work, mentions in author order;
//               named `authorpapers`: AuthorPaperRecord JSON, one per mention;
//               named `pairs`: MentionPairCandidate JSON, n*(n-1)/2 per work, with workId,
//               year, both author positions, and provenance. Teams with more than
//               `scholarchain.team.oversize.threshold` authors count OVERSIZE_TEAM and are
//               flagged `oversize = true`; their pairs are still emitted and counted.
//               The reduce body is `TeamReducer.process` (cluster-free, unit-tested).
// ============================================================================================
class TeamMapper extends Mapper[LongWritable, Text, Text, Text]:
  private val outKey = new Text
  override def map(
      offset: LongWritable,
      line: Text,
      ctx: Mapper[LongWritable, Text, Text, Text]#Context
  ): Unit =
    ctx.getCounter(ScCounter.INPUT).increment(1)
    val env = read[WorkEnvelope](line.toString)
    outKey.set(env.work.workId)
    ctx.write(outKey, line)

class TeamReducer extends Reducer[Text, Text, NullWritable, Text]:
  private var mos: MultipleOutputs[NullWritable, Text] = scala.compiletime.uninitialized
  private var threshold                                = Conf.DefaultOversize

  override def setup(ctx: Reducer[Text, Text, NullWritable, Text]#Context): Unit =
    mos = new MultipleOutputs(ctx)
    threshold = ctx.getConfiguration.getInt(Conf.OversizeThreshold, Conf.DefaultOversize)

  override def reduce(
      key: Text,
      values: java.lang.Iterable[Text],
      ctx: Reducer[Text, Text, NullWritable, Text]#Context
  ): Unit =
    val sink = new TeamReducer.Sink:
      def count(c: ScCounter, n: Long): Unit       = ctx.getCounter(c).increment(n)
      def emit(output: String, json: String): Unit =
        mos.write(output, NullWritable.get, new Text(json), s"$output/part")
    TeamReducer.process(values.asScala.map(_.toString).toVector, threshold, sink)

  override def cleanup(ctx: Reducer[Text, Text, NullWritable, Text]#Context): Unit = mos.close()

object TeamReducer:

  /**
   * Where one reduce call sends its counters and named-output records. The reducer binds it to the
   * task context and MultipleOutputs (base path `<output>/part`); tests bind it to a recorder, so
   * the reduce logic, including pair emission, is tested without a cluster or HADOOP_HOME.
   */
  trait Sink:
    def count(c: ScCounter, n: Long): Unit
    def emit(output: String, json: String): Unit

  /**
   * The whole Job 6 reduce step for one work ID. In: every WorkEnvelope JSON value of the key. Out
   * (through `sink`): DUPLICATE += values - 1; ACCEPTED, TEAMS += 1; OVERSIZE_TEAM += 1 when the
   * team exceeds `threshold`; one `teams` TeamRecord; one `authorpapers` AuthorPaperRecord per
   * mention (AUTHOR_PAPERS); one `pairs` MentionPairCandidate per unordered mention pair, n*(n-1)/2
   * (EMITTED_PAIR). The smallest value in sorted order is kept, so the choice is deterministic.
   */
  def process(values: Seq[String], threshold: Int, sink: Sink): Unit =
    val all = values.toVector.sorted
    sink.count(ScCounter.DUPLICATE, (all.size - 1).toLong)
    val env  = read[WorkEnvelope](all.head)
    val team = Teams.team(env, threshold)
    sink.count(ScCounter.ACCEPTED, 1)
    sink.count(ScCounter.TEAMS, 1)
    if team.oversize then sink.count(ScCounter.OVERSIZE_TEAM, 1)
    sink.emit("teams", write(team))
    Teams.authorPapers(env).foreach { ap =>
      sink.count(ScCounter.AUTHOR_PAPERS, 1)
      sink.emit("authorpapers", write(ap))
    }
    Teams.pairsFor(env).foreach { p =>
      sink.count(ScCounter.EMITTED_PAIR, 1)
      sink.emit("pairs", write(p))
    }

/** Output layout and job wiring. */
object Pipeline:

  /** `run:v1:<hex>` -> `run-v1-<hex>` (Hadoop paths may not contain `:`). */
  def runSegment(runId: String): String = runId.replace(':', '-')

  private val SnapshotPattern = "^[A-Za-z0-9][A-Za-z0-9._-]*$".r

  /** Rejects snapshot IDs that could escape the run partition (`..`, `/`, empty, other chars). */
  def validateSnapshot(snapshot: String): String =
    if snapshot == null || !SnapshotPattern.matches(snapshot) || snapshot.contains("..") then
      throw new IllegalArgumentException("invalid snapshotId: " + snapshot)
    snapshot

  /** Rejects anything that is not a core run ID (`run:v1:<64 hex>`), so it cannot escape a path. */
  def validateRunId(runId: String): String =
    if runId == null || !IdFormat.isValid(IdKind.Run, runId) then
      throw new IllegalArgumentException("invalid runId: " + runId)
    runId

  /**
   * `<base>/run=<run>/snapshot=<snapshot>/<job>`: deterministic per run and snapshot. Every path
   * builder goes through here, so both the run ID and the snapshot ID are validated here.
   */
  def outputPath(base: String, runId: String, snapshot: String, job: String): Path =
    validateRunId(runId)
    validateSnapshot(snapshot)
    new Path(s"$base/run=${runSegment(runId)}/snapshot=$snapshot/$job")

  private[hadoop] def common(conf: Configuration, name: String, reducers: Int): Job =
    val job = Job.getInstance(conf, name)
    job.setJarByClass(classOf[WorkIdPartitioner])
    job.getConfiguration.setBoolean("mapreduce.map.output.compress", true)
    job.getConfiguration.setClass(
      "mapreduce.map.output.compress.codec",
      classOf[DefaultCodec],
      classOf[org.apache.hadoop.io.compress.CompressionCodec]
    )
    job.setMapOutputKeyClass(classOf[Text]); job.setMapOutputValueClass(classOf[Text])
    job.setOutputKeyClass(classOf[NullWritable]); job.setOutputValueClass(classOf[Text])
    job.setPartitionerClass(classOf[WorkIdPartitioner])
    job.setNumReduceTasks(reducers)
    job

  /**
   * `counters`: the `ScholarChain` group by counter name. `framework`: every other counter group
   * (Hadoop task, file system, and job counters), keyed `<group name>:<counter name>`, sorted. Both
   * are empty when the job was skipped because its output already existed.
   */
  final case class Result(
      job: String,
      ok: Boolean,
      skipped: Boolean,
      counters: Map[String, Long],
      output: Path,
      framework: scala.collection.immutable.SortedMap[String, Long] =
        scala.collection.immutable.SortedMap.empty
  )

  private[hadoop] def counters(job: Job): Map[String, Long] =
    val cs = job.getCounters
    ScCounter.values.map(c => c.toString -> cs.findCounter(c).getValue).toMap

  private[hadoop] def frameworkCounters(job: Job)
      : scala.collection.immutable.SortedMap[String, Long] =
    val own = classOf[ScCounter].getName
    val all =
      for
        g <- job.getCounters.asScala.toSeq if g.getName != own
        c <- g.asScala.toSeq
      yield s"${g.getName}:${c.getName}" -> c.getValue
    scala.collection.immutable.SortedMap.from(all)

  /**
   * Idempotency: an output directory with `_SUCCESS` is a completed run of the same run ID and
   * snapshot, so it is reused, not recomputed. A directory without `_SUCCESS` is a failed attempt;
   * overwriting it needs a human decision (docs/agents.md section 8), so the job fails instead.
   */
  private[hadoop] def guard(conf: Configuration, out: Path): Option[Boolean] =
    val fs = out.getFileSystem(conf)
    if fs.exists(new Path(out, "_SUCCESS")) then Some(true)
    else if fs.exists(out) then
      throw new IllegalStateException(s"partial output exists without _SUCCESS: $out")
    else None

  def runExtract(
      conf0: Configuration,
      input: String,
      dtd: String,
      base: String,
      runId: String,
      snapshot: String,
      reducers: Int
  ): Result =
    require(IdFormat.isValid(IdKind.Run, runId), s"not a run ID: $runId")
    val conf = new Configuration(conf0)
    conf.set(Conf.RunId, runId); conf.set(Conf.Snapshot, snapshot); conf.set(Conf.DtdPath, dtd)
    val out = outputPath(base, runId, snapshot, "dblp-extract")
    guard(conf, out) match
      case Some(_) => Result("dblp-extract", true, true, Map.empty, out)
      case None    =>
        val job = common(conf, s"scholarchain-dblp-extract-$snapshot", reducers)
        job.setInputFormatClass(classOf[DblpXmlInputFormat])
        job.setMapperClass(classOf[DblpExtractMapper])
        job.setReducerClass(classOf[DblpExtractReducer])
        FileInputFormat.addInputPath(job, new Path(input))
        FileOutputFormat.setOutputPath(job, out)
        Seq("rejects", "works", "mentions").foreach(n =>
          MultipleOutputs.addNamedOutput(
            job,
            n,
            classOf[TextOutputFormat[?, ?]],
            classOf[NullWritable],
            classOf[Text]
          )
        )
        val ok = job.waitForCompletion(true)
        Result("dblp-extract", ok, false, counters(job), out, frameworkCounters(job))

  def runTeams(
      conf0: Configuration,
      extractOut: Path,
      base: String,
      runId: String,
      snapshot: String,
      reducers: Int,
      oversize: Int
  ): Result =
    val conf = new Configuration(conf0)
    conf.set(Conf.RunId, runId); conf.set(Conf.Snapshot, snapshot)
    conf.setInt(Conf.OversizeThreshold, oversize)
    val out = outputPath(base, runId, snapshot, "team-extract")
    guard(conf, out) match
      case Some(_) => Result("team-extract", true, true, Map.empty, out)
      case None    =>
        val job = common(conf, s"scholarchain-team-extract-$snapshot", reducers)
        job.setInputFormatClass(classOf[TextInputFormat])
        job.setMapperClass(classOf[TeamMapper])
        job.setReducerClass(classOf[TeamReducer])
        FileInputFormat.addInputPath(job, new Path(extractOut, "part-r-*"))
        FileOutputFormat.setOutputPath(job, out)
        LazyOutputFormat.setOutputFormatClass(job, classOf[TextOutputFormat[?, ?]])
        Seq("teams", "authorpapers", "pairs").foreach(n =>
          MultipleOutputs.addNamedOutput(
            job,
            n,
            classOf[TextOutputFormat[?, ?]],
            classOf[NullWritable],
            classOf[Text]
          )
        )
        val ok = job.waitForCompletion(true)
        Result("team-extract", ok, false, counters(job), out, frameworkCounters(job))

/**
 * Driver: `DblpPipelineMain <dblp.xml> <dblp.dtd> <outputBase> <snapshotId> <runId> [reducers=2]
 * [oversizeThreshold=50]`. Runs Job 1 then Job 6.
 *
 * The input may be a Hadoop glob. On Windows the `java.exe` launcher itself expands an unquoted `*`
 * argument into matching file names (observed in phase 04: `dblp-*.xml` became two arguments and
 * every later positional argument shifted). Pass `{a,b}` globs or a single file there.
 * [[parseArgs]] rejects any argument vector that does not have exactly 5 to 7 arguments with a
 * valid snapshot ID, run ID, and positive integers, so a shifted vector fails before any job runs.
 * It also rejects any blank (empty or whitespace-only) argument: a quoted but unset `"$OUT"` would
 * otherwise make the output base empty and put `/run=...` at the file-system root. Every rejection
 * prints the reason and usage and exits with status 2.
 */
object DblpPipelineMain:
  final case class Args(
      input: String,
      dtd: String,
      outputBase: String,
      snapshot: String,
      runId: String,
      reducers: Int,
      oversize: Int
  )

  val Usage = "usage: <dblp.xml> <dblp.dtd> <outputBase> <snapshotId> <runId> [reducers] [oversize]"

  def parseArgs(args: Seq[String]): Either[String, Args] =
    def posInt(name: String, i: Int, default: Int): Either[String, Int] =
      args.lift(i) match
        case None    => Right(default)
        case Some(s) =>
          s.toIntOption.filter(_ > 0).toRight(s"$name must be a positive integer, got: $s; $Usage")
    val blank = args.indexWhere(_.isBlank)
    if args.length < 5 || args.length > 7 then
      Left(
        s"expected 5 to 7 arguments, got ${args.length} (was a '*' glob expanded by the shell or " +
          s"the Windows java launcher?); $Usage"
      )
    else if blank >= 0 then
      Left(
        s"argument ${blank + 1} is blank (empty or whitespace only; was a quoted variable unset?); " +
          Usage
      )
    else if scala.util.Try(Pipeline.validateSnapshot(args(3))).isFailure then
      Left(s"snapshotId (argument 4) is invalid: ${args(3)}; $Usage")
    else if scala.util.Try(Pipeline.validateRunId(args(4))).isFailure then
      Left(s"runId (argument 5) is not a run ID: ${args(4)}; $Usage")
    else
      for
        r <- posInt("reducers", 5, 2)
        o <- posInt("oversize", 6, Conf.DefaultOversize)
      yield Args(args(0), args(1), args(2), args(3), args(4), r, o)

  /** One line per job: status, output, ScholarChain counters, then all framework counters. */
  def report(r: Pipeline.Result): String =
    val own = scala.collection.immutable.SortedMap.from(r.counters)
    s"JOB ${r.job} ok=${r.ok} skipped=${r.skipped} output=${r.output} counters=$own " +
      s"framework=${r.framework}"

  def main(argv: Array[String]): Unit =
    val a = parseArgs(argv.toSeq) match
      case Right(a)  => a
      case Left(msg) =>
        System.err.println(msg)
        sys.exit(2)
    val conf = new Configuration()
    val r1   =
      Pipeline.runExtract(conf, a.input, a.dtd, a.outputBase, a.runId, a.snapshot, a.reducers)
    println(report(r1))
    if !r1.ok then sys.exit(1)
    val r2 = Pipeline.runTeams(
      conf,
      r1.output,
      a.outputBase,
      a.runId,
      a.snapshot,
      a.reducers,
      a.oversize
    )
    println(report(r2))
    if !r2.ok then sys.exit(1)
