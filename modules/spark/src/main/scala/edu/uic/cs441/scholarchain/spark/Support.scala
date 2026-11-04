package edu.uic.cs441.scholarchain.spark

import edu.uic.cs441.scholarchain.core.ids.{CanonicalHash, ResearcherPair, StableIds}
import edu.uic.cs441.scholarchain.core.model.{SourceType, YearWindow}
import org.apache.hadoop.fs.Path
import org.apache.spark.scheduler.{SparkListener, SparkListenerStageCompleted, SparkListenerTaskEnd}
import org.apache.spark.sql.{functions as F, SparkSession}
import org.apache.spark.sql.api.java.UDF2
import org.apache.spark.sql.expressions.UserDefinedFunction
import org.apache.spark.sql.types.*

import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.atomic.AtomicLong

/**
 * Input schemas, read by wire format only (CLAUDE.md: spark must not depend on hadoop job classes).
 * Each mirrors, by field name, a record written by an upstream module:
 *   - `authorPaper`: hadoop `AuthorPaperRecord` (modules/hadoop/.../Teams.scala), Job 6 named
 *     output `team-extract/authorpapers/part-*`.
 *   - `mentionPair`: hadoop `MentionPairCandidate` (Teams.scala), Job 6 named output
 *     `team-extract/pairs/part-*`.
 *   - `work`: core `WorkRecord`, Job 1 named output `dblp-extract/works/part-*`.
 *   - `resolution`: entity-resolution `ResolutionRecord` (erdecision:v1), files `accepted.jsonl`
 *     and `conflict.jsonl` written by `er.Outputs.writeAll`. Only the fields used here are
 *     declared.
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
      StructField("scope", StringType),
      StructField("memberLow", StringType),
      StructField("memberHigh", StringType),
      StructField("status", StringType),
      StructField("policyVersion", StringType),
      StructField("provenance", provenance)
    )
  )

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
