package edu.uic.cs441.scholarchain.spark

import edu.uic.cs441.scholarchain.core.ids.{CanonicalHash, ResearcherPair, StableIds}
import edu.uic.cs441.scholarchain.core.model.{SourceType, YearWindow}
import org.apache.hadoop.fs.{FileStatus, Path}
import org.apache.spark.scheduler.{SparkListener, SparkListenerStageCompleted, SparkListenerTaskEnd}
import edu.uic.cs441.scholarchain.er.{DecisionsManifest, Outputs, ResolutionStatus}
import org.apache.spark.sql.{functions as F, Column, SparkSession}
import org.apache.spark.sql.api.java.UDF2
import org.apache.spark.sql.expressions.UserDefinedFunction
import org.apache.spark.sql.types.*

import java.io.InputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.atomic.AtomicLong
import scala.util.{Try, Using}

/**
 * Input schemas, read by wire format only (CLAUDE.md: spark must not depend on hadoop job classes).
 * Each mirrors, by field name, a record written by an upstream module:
 *   - `authorPaper`: hadoop `AuthorPaperRecord` (modules/hadoop/.../Teams.scala), Job 6 named
 *     output `team-extract/authorpapers/part-*`.
 *   - `mentionPair`: hadoop `MentionPairCandidate` (Teams.scala), Job 6 named output
 *     `team-extract/pairs/part-*`.
 *   - `work`: core `WorkRecord`, Job 1 named output `dblp-extract/works/part-*`.
 *   - `resolution`: entity-resolution `ResolutionRecord`, files `accepted.jsonl` plus the
 *     merge-veto files `rejected.jsonl`, `unresolved.jsonl`, and `conflict.jsonl`
 *     (`er.Outputs.MergeVetoFiles`) written by `er.Outputs.writeRun`. Part A reads them only after
 *     [[ErManifestGate.verify]], which admits schemaVersion 2 only; the schema itself (and
 *     [[ErRecordCheck]]) still reads version-1 lines, whose absent fields read as null. Only the
 *     fields used here are declared.
 */
object InputSchemas:

  val provenance: StructType = StructType(
    Seq(
      StructField("sourceDataset", StringType),
      StructField("sourceRecordId", StringType),
      StructField("pipelineRunId", StringType)
    )
  )

  val authorPaper: StructType = StructType(
    Seq(
      StructField("mentionId", StringType),
      StructField("workId", StringType),
      StructField("authorPosition", IntegerType),
      StructField("normalizedName", StringType),
      StructField("year", IntegerType),
      StructField("provenance", provenance)
    )
  )

  val mentionPair: StructType = StructType(
    Seq(
      StructField("mentionLow", StringType),
      StructField("mentionHigh", StringType),
      StructField("positionLow", IntegerType),
      StructField("positionHigh", IntegerType),
      StructField("workId", StringType),
      StructField("year", IntegerType),
      StructField("provenance", provenance)
    )
  )

  val work: StructType = StructType(
    Seq(
      StructField("workId", StringType),
      StructField("title", StringType),
      StructField("year", IntegerType),
      StructField("venueId", StringType),
      StructField("provenance", provenance)
    )
  )

  val resolution: StructType = StructType(
    Seq(
      StructField("decisionId", StringType),
      StructField("pairId", StringType),
      StructField("scope", StringType),
      StructField("memberLow", StringType),
      StructField("memberHigh", StringType),
      StructField("status", StringType),
      StructField("confidence", DoubleType),
      StructField("policyVersion", StringType),
      StructField("reasonCodes", ArrayType(StringType)),
      StructField("provenance", provenance),
      StructField("provenanceLow", provenance),
      StructField("provenanceHigh", provenance)
    )
  )

/**
 * Soundness rule for ER decision records (Phase 11 repair-02 RR1: a corrupt veto line must never be
 * read as "no veto"). It applies to every line of all four ER files (`accepted.jsonl` and the three
 * merge-veto files), whatever the record's scope:
 *   - `unparseable`: the line is not a JSON object of the expected shape (for example a
 *     half-written line). Spark's PERMISSIVE reader puts such a line in [[CorruptColumn]].
 *   - `incomplete`: the line parses, but a field in [[RequiredFields]] is null, missing, or blank,
 *     or `scope` is not one of [[Scopes]], or `status` is not an ER status wire value.
 *
 * Any problem record fails the Part A run before any output is written (fail closed), with the
 * count per file and problem in the error; a sound run records those counts as 0 in counts.json.
 * Every required field is present in both schemaVersion 1 and 2 records, so version-1 lines stay
 * readable: their absent version-2 fields (`scoreUpper`, `corroborated`) are not read here, and
 * `confidence` is optional (absent reads as 0.0 in the link order).
 */
object ErRecordCheck:

  /** Spark corrupt-record column used while reading the ER files (dropped afterwards). */
  val CorruptColumn: String = "_erCorruptRecord"

  val Unparseable: String = "unparseable"
  val Incomplete: String  = "incomplete"

  /** Problem classes, in report order. */
  val Problems: List[String] = List(Unparseable, Incomplete)

  /** Fields every decision record must carry, non-blank (nested fields as `parent.child`). */
  val RequiredFields: List[String] = List(
    "decisionId",
    "scope",
    "memberLow",
    "memberHigh",
    "status",
    "provenance.sourceDataset",
    "provenance.sourceRecordId",
    "provenance.pipelineRunId"
  )

  /** ER scopes (entity-resolution `Identifiers`: `mention` or `work`). */
  val Scopes: List[String] = List("mention", "work")

  /** ER status wire values. */
  val Statuses: List[String] = ResolutionStatus.values.toList.map(_.wire)

  val Rule: String =
    "every line of accepted.jsonl and the merge-veto files must parse and carry non-blank " +
      RequiredFields.mkString(", ") + s", scope in {${Scopes.mkString(", ")}} and status in " +
      s"{${Statuses.mkString(", ")}}; any unparseable or incomplete record fails the run before " +
      "any output is written (never read as no veto)"

  /** counts.json key, for example `erDecisionRecordsUnparseable:rejected.jsonl`. */
  def countKey(problem: String, file: String): String =
    s"erDecisionRecords${problem.capitalize}:$file"

  private def present(name: String): Column =
    F.col(name).isNotNull && F.length(F.trim(F.col(name))) > 0

  private val complete: Column = F.coalesce(
    RequiredFields.map(present).reduce(_ && _) &&
      F.col("scope").isin(Scopes*) && F.col("status").isin(Statuses*),
    F.lit(false)
  )

  /** The record's problem class, or null for a sound record. Needs [[CorruptColumn]] in scope. */
  def problem: Column =
    F.when(F.col(CorruptColumn).isNotNull, F.lit(Unparseable))
      .when(!complete, F.lit(Incomplete))

  /** Deterministic error text: every (file, problem) count, sorted. */
  def failureMessage(erDir: String, problems: Map[(String, String), Long]): String =
    val detail = problems.toList.sorted.map { case ((f, p), n) => s"$f $p=$n" }.mkString(", ")
    s"ER decision files under $erDir hold unreadable records ($detail); failing closed: a " +
      "corrupt or incomplete ER decision is never read as no veto or silently dropped"

/**
 * ER completion-manifest gate (Phase 11 repair-03, review-02 F2, consumer side). Part A trusts an
 * ER decision directory only after two independent checks, both before any output is written:
 *
 *   1. [[verify]] (driver, before any decision record is read): the directory's
 *      `_DECISIONS_MANIFEST.json` (ER `Outputs.ManifestFileName`) must exist, be at most
 *      [[MaxManifestBytes]], and pass ER `Outputs.verifyWith` over a Hadoop FileSystem `open`, so
 *      the same rule holds on local, HDFS, and S3 paths: header rules H1-H5, then per decision file
 *      V1 exists, V2 SHA-256 equal, V3 record count equal, V4 every line parses as a schema-2
 *      record of the file's status and of the manifest's run, policy, and features versions. A
 *      missing manifest fails the run; there is no fallback to unverified reading (ER outputs
 *      written before er-outputs-v2 must be regenerated). A 0-byte veto file is "no vetoes" only
 *      when the manifest says so.
 *   2. [[runProblems]] (defense in depth, on what Spark actually read): every decision record
 *      carries exactly one distinct `provenance.pipelineRunId`, equal to the manifest's, and the
 *      number of records Spark read per file equals the manifest's `recordCount`.
 *
 * Verification streams each decision file once through the driver (bounded memory, nothing is
 * collected); its cost is one sequential read of the ER output.
 */
object ErManifestGate:

  /** Upper bound on the manifest size read into the driver (the real manifest is about 1 KiB). */
  val MaxManifestBytes: Long = 1L << 20

  /** At most this many distinct run IDs are fetched to the driver for the error message. */
  val MaxReportedRunIds: Int = 5

  val Rule: String =
    s"the ER directory must hold ${Outputs.ManifestFileName} (manifestSchemaVersion " +
      s"${Outputs.ManifestSchemaVersion}) and pass er.Outputs.verifyWith (header H1-H5; per file " +
      "exists, sha256, recordCount, and every line a schema-2 record of the manifest's run and " +
      "versions) before any decision record is read; then every record Spark reads must carry " +
      "exactly the manifest's provenance.pipelineRunId and each file's record count must equal the " +
      "manifest's recordCount; a missing manifest or any failure stops the run before any output " +
      "(no unverified fallback)"

  /** A verified ER directory: its manifest and the lower-case hex SHA-256 of the manifest bytes. */
  final case class Verified(manifest: DecisionsManifest, manifestSha256: String)

  /**
   * Verifies the ER directory `erDir` against its manifest. Left lists the problems in the
   * verifier's order (manifest file order, then line number, then sha256 and count), which is a
   * pure function of the directory contents.
   */
  def verify(spark: SparkSession, erDir: String): Either[List[String], Verified] =
    val dir = new Path(erDir)
    val fs  = dir.getFileSystem(spark.sparkContext.hadoopConfiguration)
    def regularFile(name: String): Option[FileStatus] =
      Try(fs.getFileStatus(new Path(dir, name))).toOption.filter(_.isFile)
    regularFile(Outputs.ManifestFileName) match
      case None =>
        Left(List(
          s"${Outputs.ManifestFileName} missing in $erDir: the ER output is incomplete, was " +
            "written before er-outputs-v2, or was not written by er.Outputs.writeRun; regenerate it"
        ))
      case Some(st) if st.getLen > MaxManifestBytes =>
        Left(List(
          s"${Outputs.ManifestFileName} in $erDir is ${st.getLen} bytes, over the " +
            s"$MaxManifestBytes-byte bound; not an ER manifest"
        ))
      case Some(st) =>
        Try(Using.resource(fs.open(st.getPath))(_.readAllBytes())).toEither match
          case Left(e)      => Left(List(s"${Outputs.ManifestFileName} unreadable: $e"))
          case Right(bytes) =>
            Outputs
              .verifyWith(
                bytes,
                name => regularFile(name).map(s => fs.open(s.getPath): InputStream)
              )
              .map(m => Verified(m, Outputs.sha256Hex(bytes)))

  /** Deterministic failure text for [[verify]] problems. */
  def verifyFailureMessage(erDir: String, problems: List[String]): String =
    s"ER decision directory $erDir failed verification against ${Outputs.ManifestFileName} " +
      s"(${problems.size} problem(s)): ${problems.mkString("; ")}; failing closed before any ER " +
      "decision is read: a partial, stale, mixed, or unverified ER output is never read as no veto"

  /**
   * Defense-in-depth problems on what Spark read: `perFile` is the number of records read per
   * decision file and `runIds` the distinct `provenance.pipelineRunId` values (null as `<null>`).
   * Deterministic: files in `Outputs.DecisionFiles` order, run IDs sorted.
   */
  def runProblems(m: DecisionsManifest, perFile: Map[String, Long], runIds: Seq[String])
      : List[String] =
    val expected = m.files.map(e => e.name -> e.recordCount).toMap
    val counts   = Outputs.DecisionFiles.flatMap { f =>
      val got  = perFile.getOrElse(f, 0L)
      val want = expected.get(f)
      Option.when(!want.contains(got))(
        s"$f: Spark read $got records, manifest recordCount ${want.fold("absent")(_.toString)}"
      )
    }
    val ids  = runIds.distinct.sorted
    val runs = Option.when(ids.exists(_ != m.pipelineRunId))(
      s"decision records carry ${ids.size}${if ids.size >= MaxReportedRunIds then "+" else ""} " +
        s"distinct provenance.pipelineRunId value(s) (${ids.mkString(", ")}); expected exactly " +
        s"the manifest's ${m.pipelineRunId}"
    )
    counts ++ runs.toList

  def runFailureMessage(erDir: String, problems: List[String]): String =
    s"ER decision records read from $erDir do not match the verified manifest " +
      s"(${problems.size} problem(s)): ${problems.mkString("; ")}; failing closed: one Part A run " +
      "reads exactly one ER run"

/** Stable-ID UDFs. All hashing goes through core `StableIds`, so IDs match every other module. */
object IdUdfs:

  /** Researcher identity version passed to `StableIds.scholarChainResearcherId`. */
  val IdentityVersion: Int = 1

  /**
   * `researcher:sc1:<hex>` for an identity cluster anchored at mention `anchorMentionId`, whose
   * source dataset is `dataset` (the anchor mention's `provenance.sourceDataset` wire value).
   * Anchor rule (Part A): the smallest mention ID of the cluster (String.compareTo). Fails on an
   * unknown dataset wire value rather than inventing one.
   */
  def researcherId(dataset: String, anchorMentionId: String): String =
    val st = SourceType.values
      .find(_.wire == dataset)
      .getOrElse(throw new IllegalArgumentException(s"unknown sourceDataset '$dataset'"))
    StableIds
      .scholarChainResearcherId(IdentityVersion, st, anchorMentionId)
      .fold(e => throw new IllegalArgumentException(e.toString), identity)

  def coauthorEdgeId(low: String, high: String, window: YearWindow, policy: String): String =
    ResearcherPair
      .fromOrdered(low, high)
      .fold(
        es => throw new IllegalArgumentException(es.mkString("; ")),
        p => StableIds.coauthorEdgeId(p, window, policy)
      )

  val researcherIdUdf: UserDefinedFunction =
    F.udf(
      new UDF2[String, String, String]:
        def call(dataset: String, anchor: String): String = researcherId(dataset, anchor)
      ,
      StringType
    )

  def coauthorEdgeIdUdf(window: YearWindow, policy: String): UserDefinedFunction =
    F.udf(
      new UDF2[String, String, String]:
        def call(low: String, high: String): String = coauthorEdgeId(low, high, window, policy)
      ,
      StringType
    )

/** Small driver-side file helpers (metadata and reports only, never datasets). */
object JobFiles:

  def exists(spark: SparkSession, path: String): Boolean =
    val p = new Path(path)
    p.getFileSystem(spark.sparkContext.hadoopConfiguration).exists(p)

  /** Reads a small report file (params, invariants, manifests), never a dataset. */
  def readText(spark: SparkSession, path: String): String =
    val p  = new Path(path)
    val in = p.getFileSystem(spark.sparkContext.hadoopConfiguration).open(p)
    try new String(in.readAllBytes(), UTF_8)
    finally in.close()

  def writeText(spark: SparkSession, path: String, text: String): Unit =
    val p   = new Path(path)
    val out = p.getFileSystem(spark.sparkContext.hadoopConfiguration).create(p, false)
    try out.write(text.getBytes(UTF_8))
    finally out.close()

  /**
   * `sha256:<hex>` over the sorted (relative path, byte length) listing of the data files under
   * `path` (names starting with `_` or `.` are skipped, as Spark skips them). This is a listing
   * fingerprint, not a content hash.
   */
  def listingFingerprint(spark: SparkSession, path: String): String =
    val root  = new Path(path)
    val fs    = root.getFileSystem(spark.sparkContext.hadoopConfiguration)
    val base  = fs.getFileStatus(root).getPath.toString
    val it    = fs.listFiles(root, true)
    val files = Iterator
      .continually(it)
      .takeWhile(_.hasNext)
      .map(_.next())
      .filter { s =>
        val n = s.getPath.getName; !n.startsWith("_") && !n.startsWith(".")
      }
      .map(s => (s.getPath.toString.stripPrefix(base), s.getLen))
      .toVector
      .sorted
    "sha256:" + CanonicalHash.hashFields(
      "scholarchain:input-listing:v1" +: files.flatMap((n, l) => Seq(n, l.toString))
    )

/**
 * Stage and task metrics collected through a SparkListener. Listener delivery is asynchronous, so
 * totals read at the end of a run can miss the last few events; they are reported as observed.
 */
final class StageMetrics extends SparkListener:
  val stages: AtomicLong                                              = new AtomicLong
  val tasks: AtomicLong                                               = new AtomicLong
  val shuffleReadBytes: AtomicLong                                    = new AtomicLong
  val shuffleWriteBytes: AtomicLong                                   = new AtomicLong
  val maxTaskShuffleRead: AtomicLong                                  = new AtomicLong
  val maxTaskRecordsRead: AtomicLong                                  = new AtomicLong
  val spilledDiskBytes: AtomicLong                                    = new AtomicLong
  override def onStageCompleted(e: SparkListenerStageCompleted): Unit =
    stages.incrementAndGet()
    val m = e.stageInfo.taskMetrics
    if m != null then
      shuffleReadBytes.addAndGet(m.shuffleReadMetrics.totalBytesRead)
      shuffleWriteBytes.addAndGet(m.shuffleWriteMetrics.bytesWritten)
      spilledDiskBytes.addAndGet(m.diskBytesSpilled)
  override def onTaskEnd(e: SparkListenerTaskEnd): Unit =
    tasks.incrementAndGet()
    val m = e.taskMetrics
    if m != null then
      maxTaskShuffleRead.accumulateAndGet(m.shuffleReadMetrics.totalBytesRead, math.max)
      maxTaskRecordsRead.accumulateAndGet(m.inputMetrics.recordsRead, math.max)

  def snapshot: List[(String, Long)] = List(
    "stagesCompleted"         -> stages.get,
    "tasksEnded"              -> tasks.get,
    "shuffleReadBytes"        -> shuffleReadBytes.get,
    "shuffleWriteBytes"       -> shuffleWriteBytes.get,
    "maxTaskShuffleReadBytes" -> maxTaskShuffleRead.get,
    "maxTaskInputRecordsRead" -> maxTaskRecordsRead.get,
    "diskBytesSpilled"        -> spilledDiskBytes.get
  )
