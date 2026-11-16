package edu.uic.cs441.scholarchain.spark

import edu.uic.cs441.scholarchain.core.SchemaVersion
import edu.uic.cs441.scholarchain.core.config.ConfigLoader
import edu.uic.cs441.scholarchain.core.ids.{CanonicalHash, StableIds}
import edu.uic.cs441.scholarchain.core.json.Wire
import edu.uic.cs441.scholarchain.core.model.{RunInput, RunManifest, SourceType, YearWindow}
import edu.uic.cs441.scholarchain.er.{DecisionsManifest, Outputs, ResolutionStatus}
import org.apache.spark.sql.{functions as F, Column, DataFrame, SparkSession}
import org.apache.spark.sql.expressions.Window
import org.apache.spark.storage.StorageLevel

import java.nio.file.Paths

/** Upstream output directories (Hadoop Job 1 and Job 6 named outputs; the ER output directory). */
final case class PartAInputs(authorPapers: String, pairs: String, works: String, erDir: String)

/**
 * Run parameters. `window` and `policyVersion` come from the core configuration (`graph.*`);
 * `ccMaxIterations` and `shufflePartitions` are required job arguments. All are recorded in
 * `params.json` and folded into the run's config fingerprint.
 */
final case class PartAParams(
    window: YearWindow,
    policyVersion: String,
    ccMaxIterations: Int,
    shufflePartitions: Int
)

final case class PartAResult(
    runId: String,
    outputBase: String,
    counts: List[(String, Long)],
    invariantFailures: List[(String, Long)],
    largestComponentFraction: Double,
    ccIterationsIdentity: Int,
    ccIterationsGraph: Int
):
  def count(k: String): Long = counts.find(_._1 == k).map(_._2).getOrElse(sys.error(s"no $k"))
  def failures: List[(String, Long)] = invariantFailures.filter(_._2 != 0L)

/**
 * Spark Part A (WO-06a): canonical researchers and authorship (job 1), the temporal weighted
 * coauthorship graph (job 2), per-researcher metrics (job 3), and connected components (job 4).
 *
 * Output layout under `<out>/run=<run-v1-hex>/`: `researchers`, `mention-researcher-map`,
 * `identity-refused-links`, `works`, `authored` (all years), and under `window=<start>-<end>/`:
 * `coauthor-edges`, `excluded-pairs`, `researcher-metrics`, `components` (Parquet), plus
 * `manifest.json`, `params.json`, `counts.json`, and `invariants.json`. Nothing is overwritten: an
 * existing run directory fails the run (rerun with a new `attempt`).
 *
 * Nothing but single-row aggregates and small grouped counts reaches the driver, and no broadcast
 * hint is applied to a graph-scale table (joins are planner-chosen).
 *
 * Identity (`identity-closure-v2`, Phase 11 repair-01 D1, human decision STRICT rule c): ER's
 * accepted mention-scope links are closed transitively, but never across a merge veto. A veto is
 * any mention-scope decision in `rejected.jsonl`, `unresolved.jsonl`, or `conflict.jsonl` (ER
 * `Outputs.MergeVetoFiles`), or any record whose status is not `accepted`. Steps:
 *   1. Distributed connected components of the accepted links (the unconstrained closure).
 *   2. A closure component that contains both members of a conflict pair is split, so each of its
 *      mentions becomes a singleton researcher with identityStatus `conflicted-split` (F2).
 *   3. A closure component that contains both members of another veto pair is re-solved by
 *      [[ConstrainedClosure]] (constrained union-find in a canonical link order) inside one task;
 *      its researchers carry `vetoConstrainedClosureAnchorMentionId`.
 *   4. Every other component keeps its closure cluster, so its researcher ID is unchanged.
 * Every accepted link not honoured is written to `identity-refused-links` with its vetoing ER
 * decision and provenance. Pairs that were never ER candidates have no decision and carry no veto.
 *
 * ER input gate (Phase 11 repair-03, review-02 F2): before any decision record is read, the ER
 * directory must pass [[ErManifestGate.verify]] against its `_DECISIONS_MANIFEST.json` (no
 * manifest, no run); after reading, every record must carry the manifest's single ER run ID and
 * each file's record count must equal the manifest's ([[ErManifestGate.runProblems]]). Either
 * failure stops the run before any output is written. The verified ER run ID, policy and features
 * versions, per-file counts and hashes, and the manifest SHA-256 are recorded in `params.json`; the
 * manifest SHA-256 is also a run-manifest input, so it is part of the run ID.
 */
object PartA:

  val PipelineName: String = "scholarchain-spark-part-a"

  /** Run-manifest input snapshot ID whose checksum is the ER manifest's SHA-256. */
  val ErDecisionsManifestSnapshot: String = "er-decisions-manifest"

  private val AcceptedFile: String = Outputs.fileFor(ResolutionStatus.Accepted)
  private val ConflictFile: String = Outputs.fileFor(ResolutionStatus.Conflict)

  /** `refusalReason` values of `identity-refused-links`. */
  val RefusedByVeto: String  = "veto-would-join"
  val RefusedBySplit: String = "conflicted-cluster-split"

  private def c(name: String): Column = F.col(name)

  /** ER pair key of a mention pair, `mention|<low>|<high>` (ER `PairInput.pairId`). */
  private def pairKey(low: Column, high: Column): Column =
    F.concat_ws("|", F.lit("mention"), low, high)

  private def readJson(spark: SparkSession, schema: org.apache.spark.sql.types.StructType)(
      path: String
  ): DataFrame =
    spark.read.schema(schema).option("mode", "PERMISSIVE").json(path)

  /**
   * Reads the ER decision files `files` under `erDir` into one frame with an `erFile` column and an
   * `erRecordProblem` column ([[ErRecordCheck]]; null for a sound record). The raw line of an
   * unparseable record is captured in a corrupt-record column, so a broken line is classified,
   * never turned into a silent all-null row.
   */
  private[spark] def readErDecisions(spark: SparkSession, erDir: String, files: List[String])
      : DataFrame =
    files
      .map(f =>
        spark.read
          .schema(InputSchemas.resolution.add(ErRecordCheck.CorruptColumn, "string"))
          .option("mode", "PERMISSIVE")
          .option("columnNameOfCorruptRecord", ErRecordCheck.CorruptColumn)
          .json(s"$erDir/$f")
          .withColumn("erFile", F.lit(f))
      )
      .reduce(_.unionByName(_))
      .withColumn("erRecordProblem", ErRecordCheck.problem)
      .drop(ErRecordCheck.CorruptColumn)

  /**
   * Defense-in-depth check of the decision frame from [[readErDecisions]] against the verified
   * manifest ([[ErManifestGate.runProblems]]). Only the per-file counts (at most four rows) and at
   * most [[ErManifestGate.MaxReportedRunIds]] distinct run IDs reach the driver. Returns the
   * problems and the number of distinct run IDs fetched.
   */
  private[spark] def erRunProblems(decisions: DataFrame, m: DecisionsManifest)
      : (List[String], Long) =
    val perFile = decisions
      .groupBy("erFile")
      .count()
      .collect() // at most four rows
      .map(r => r.getString(0) -> r.getLong(1))
      .toMap
    val runIds = decisions
      .select(c("provenance.pipelineRunId"))
      .distinct()
      .limit(ErManifestGate.MaxReportedRunIds)
      .collect()
      .map(r => Option(r.getString(0)).getOrElse("<null>"))
      .toList
    (ErManifestGate.runProblems(m, perFile, runIds), runIds.size.toLong)

  /** Config fingerprint of this run: the core config fingerprint plus the Spark parameters. */
  def configFingerprint(coreFingerprint: String, p: PartAParams): String =
    "sha256:" + CanonicalHash.hashFields(
      Seq(
        "scholarchain:spark-part-a-config:v1",
        coreFingerprint,
        p.window.startYear.toString,
        p.window.endYear.toString,
        p.policyVersion,
        p.ccMaxIterations.toString,
        p.shufflePartitions.toString
      )
    )

  /**
   * Attaches each mention's researcher (from the identity-cluster table) to the mention rows. The
   * cluster table has about one row per mention, so this is a planner-chosen join with no broadcast
   * hint: an explicit hint ignores `spark.sql.autoBroadcastJoinThreshold` and would collect the
   * build side on the driver (WO-06a repair-01 F1).
   */
  private[spark] def attachResearchers(clustered: DataFrame, clusters: DataFrame): DataFrame =
    clustered
      .select(
        "mentionId",
        "anchorMentionId",
        "workId",
        "authorPosition",
        "normalizedName",
        "year",
        "provenance",
        "vetoConstrainedClosureAnchorMentionId"
      )
      .join(
        clusters.select(
          "anchorMentionId",
          "researcherId",
          "identityStatus",
          "clusterSize",
          "conflictedClusterAnchorMentionId"
        ),
        Seq("anchorMentionId")
      )

  def run(
      spark: SparkSession,
      in: PartAInputs,
      p: PartAParams,
      coreFingerprint: String,
      codeVersion: String,
      attempt: Int,
      outRoot: String
  ): PartAResult =
    require(p.window.startYear <= p.window.endYear, s"invalid window ${p.window}")
    spark.conf.set("spark.sql.shuffle.partitions", p.shufflePartitions.toLong)
    val metrics = new StageMetrics
    spark.sparkContext.addSparkListener(metrics)
    try runInner(spark, in, p, coreFingerprint, codeVersion, attempt, outRoot, metrics)
    finally spark.sparkContext.removeSparkListener(metrics)

  private def runInner(
      spark: SparkSession,
      in: PartAInputs,
      p: PartAParams,
      coreFingerprint: String,
      codeVersion: String,
      attempt: Int,
      outRoot: String,
      stageMetrics: StageMetrics
  ): PartAResult =
    // ER decision files: accepted.jsonl plus the three merge-veto files. ER always writes all four
    // (possibly empty) and, last, its completion manifest.
    val erFiles           = AcceptedFile :: Outputs.MergeVetoFiles
    def erPath(f: String) = s"${in.erDir}/$f"

    // ---- ER manifest gate, step 1 (Phase 11 repair-03 F2): verify before reading any record ----
    // A missing, stale, partial, or mixed ER directory fails here, before the run ID is derived
    // and before any output exists (ErManifestGate.verify).
    val erVerified = ErManifestGate.verify(spark, in.erDir) match
      case Right(v)       => v
      case Left(problems) =>
        throw new IllegalStateException(ErManifestGate.verifyFailureMessage(in.erDir, problems))
    val erManifest = erVerified.manifest

    // ---- Run manifest first: the run ID stamps every output record -----------------------------
    // The ER manifest's SHA-256 is a run input (snapshot `er-decisions-manifest`), so the run ID is
    // content-addressed on the ER decisions: it covers every decision file's SHA-256 and the ER
    // run ID, which the listing fingerprints (path and byte length only) do not.
    val inputs =
      (List(
        "hadoop-authorpapers" -> in.authorPapers,
        "hadoop-pairs"        -> in.pairs,
        "hadoop-works"        -> in.works
      ) ++ erFiles.map(f => s"er-${f.stripSuffix(".jsonl")}" -> erPath(f))).map((snap, path) =>
        RunInput(SourceType.ScholarChainPipeline, snap, JobFiles.listingFingerprint(spark, path))
      ) :+ RunInput(
        SourceType.ScholarChainPipeline,
        ErDecisionsManifestSnapshot,
        "sha256:" + erVerified.manifestSha256
      )
    val fingerprint = configFingerprint(coreFingerprint, p)
    val runId       = StableIds.runId(PipelineName, codeVersion, fingerprint, inputs, attempt)
    val manifest    = RunManifest(
      SchemaVersion.Current,
      runId,
      PipelineName,
      codeVersion,
      fingerprint,
      inputs,
      attempt
    )
    val base = s"$outRoot/run=${runId.replace(':', '-')}"
    if JobFiles.exists(spark, base) then
      throw new IllegalStateException(s"output $base exists; rerun with a new attempt")
    val win     = p.window
    val winBase = s"$base/window=${win.startYear}-${win.endYear}"
    val runIdC  = F.lit(runId)

    val counts     = List.newBuilder[(String, Long)]
    val invariants = List.newBuilder[(String, Long)]

    // ==== Job 1: canonical researchers, works, AUTHORED ==========================================
    val apRaw = readJson(spark, InputSchemas.authorPaper)(in.authorPapers)
    val apOk  = apRaw.where(
      c("mentionId").isNotNull && c("workId").isNotNull && c("provenance").isNotNull
    )
    val mentions = apOk.dropDuplicates("mentionId").persist(StorageLevel.MEMORY_AND_DISK)
    val apRows   = apRaw.count()
    val apOkRows = apOk.count()
    val mCount   = mentions.count()
    counts += "inputAuthorPaperRows"             -> apRows
    counts += "inputAuthorPaperMalformed"        -> (apRows - apOkRows)
    counts += "inputAuthorPaperDuplicateMention" -> (apOkRows - mCount)
    counts += "mentions"                         -> mCount

    // ER decision records fail closed (Phase 11 repair-02 RR1, ErRecordCheck): an unparseable line
    // or an incomplete record in any of the four files fails the run here, before any output is
    // written. A broken veto line is never read as "no veto", and a broken accepted line never
    // disappears unnoticed. Since repair-03 a corrupt line already fails ErManifestGate.verify
    // (sha256, count, parse); this rule still catches records the verifier admits but Spark cannot
    // use (for example a blank member, a blank decisionId, or a scope other than mention or work).
    val decisions  = readErDecisions(spark, in.erDir, erFiles).persist(StorageLevel.MEMORY_AND_DISK)
    val erProblems = decisions
      .where(c("erRecordProblem").isNotNull)
      .groupBy("erFile", "erRecordProblem")
      .count()
      .collect() // at most eight rows (four files, two problems)
      .map(r => (r.getString(0), r.getString(1)) -> r.getLong(2))
      .toMap
    for f <- erFiles; pr <- ErRecordCheck.Problems do
      counts += ErRecordCheck.countKey(pr, f) -> erProblems.getOrElse((f, pr), 0L)
    if erProblems.nonEmpty then
      decisions.unpersist()
      mentions.unpersist()
      throw new IllegalStateException(ErRecordCheck.failureMessage(in.erDir, erProblems))
    // ER manifest gate, step 2 (defense in depth): what Spark read is exactly one ER run, the
    // verified one, with the manifest's record count per file.
    val (runProblems, erRunIdsDistinct) = erRunProblems(decisions, erManifest)
    counts += "erDecisionDistinctRunIds" -> erRunIdsDistinct
    if runProblems.nonEmpty then
      decisions.unpersist()
      mentions.unpersist()
      throw new IllegalStateException(ErManifestGate.runFailureMessage(in.erDir, runProblems))
    val acc = decisions.where(c("erFile") === AcceptedFile)
    val con = decisions.where(c("erFile") === ConflictFile)
    Outputs.MergeVetoFiles.foreach(f =>
      counts += s"erVetoRecords:$f" -> decisions.where(c("erFile") === f).count()
    )
    counts += "erAcceptedRecords"             -> acc.count()
    counts += "erAcceptedWorkLinksNotApplied" -> acc.where(c("scope") === "work").count()
    val vIds  = mentions.select(c("mentionId").as("id"))
    val links = acc
      .where(c("scope") === "mention" && c("status") === "accepted")
      .select(c("memberLow").as("src"), c("memberHigh").as("dst"))
    val linkOk = links
      .join(vIds.withColumnRenamed("id", "src"), Seq("src"), "left_semi")
      .join(vIds.withColumnRenamed("id", "dst"), Seq("dst"), "left_semi")
    val linkN   = links.count()
    val linkOkN = linkOk.count()
    counts += "erAcceptedMentionLinks"         -> linkN
    counts += "erAcceptedMentionLinksApplied"  -> linkOkN
    counts += "erAcceptedMentionLinksDangling" -> (linkN - linkOkN)

    // Distinct applied link pairs in canonical order (low < high; self links dropped), one
    // representative ER record per pair: highest confidence, then smallest decisionId.
    val conf     = F.when(F.isnan(c("confidence")), F.lit(0.0)).otherwise(c("confidence"))
    val swapped  = c("memberLow") > c("memberHigh")
    val linkRecs = acc
      .where(c("scope") === "mention" && c("status") === "accepted")
      .where(c("memberLow").isNotNull && c("memberHigh").isNotNull)
      .withColumn("low", F.least(c("memberLow"), c("memberHigh")))
      .withColumn("high", F.greatest(c("memberLow"), c("memberHigh")))
      .where(c("low") =!= c("high"))
      .join(vIds.withColumnRenamed("id", "low"), Seq("low"), "left_semi")
      .join(vIds.withColumnRenamed("id", "high"), Seq("high"), "left_semi")
      .withColumn("linkKey", pairKey(c("low"), c("high")))
      .withColumn("linkConfidence", F.coalesce(conf, F.lit(0.0)))
      .withColumn("pLow", F.when(swapped, c("provenanceHigh")).otherwise(c("provenanceLow")))
      .withColumn("pHigh", F.when(swapped, c("provenanceLow")).otherwise(c("provenanceHigh")))
      .withColumn(
        "rk",
        F.row_number().over(
          Window
            .partitionBy("linkKey")
            .orderBy(c("linkConfidence").desc, c("decisionId").asc_nulls_last)
        )
      )
      .where(c("rk") === 1)
      .drop("rk")
      .transform(Checkpointing.cut)
    val linkPairN = linkRecs.count()
    counts += "erAcceptedMentionLinkPairs" -> linkPairN

    // Merge vetoes (human decision STRICT, rule c): every mention-scope record of a veto file, and
    // any mention-scope record whose status is not `accepted` (ResolutionRecord.blocksMerge). One
    // representative per pair: a conflict record if any, then smallest decisionId. A pair that was
    // never an ER candidate has no record, so it carries no veto.
    val isVeto = c("erFile") =!= AcceptedFile || c("status").isNull || c("status") =!= "accepted"
    val vetoes = decisions
      .where(c("scope") === "mention" && isVeto)
      .where(c("memberLow").isNotNull && c("memberHigh").isNotNull)
      .withColumn("a", F.least(c("memberLow"), c("memberHigh")))
      .withColumn("b", F.greatest(c("memberLow"), c("memberHigh")))
      .where(c("a") =!= c("b"))
      .withColumn("vetoKey", pairKey(c("a"), c("b")))
      .withColumn(
        "isConflict",
        F.coalesce(c("erFile") === ConflictFile || c("status") === "conflict", F.lit(false))
      )
      .withColumn(
        "rk",
        F.row_number().over(
          Window
            .partitionBy("vetoKey")
            .orderBy(c("isConflict").desc, c("decisionId").asc_nulls_last, c("erFile").asc)
        )
      )
      .where(c("rk") === 1)
      .drop("rk")
      .transform(Checkpointing.cut)
    counts += "erVetoMentionPairs" -> vetoes.count()

    // Unconstrained closure (distributed): connected components of the accepted links. A veto can
    // only matter inside one closure component, because accepted links never cross components.
    val idCc    = ConnectedComponents.run(vIds, linkOk, p.ccMaxIterations)
    val lab     = idCc.labels.select(c("id"), c("component"))
    val closure = mentions
      .join(lab.withColumnRenamed("id", "mentionId"), Seq("mentionId"))
      .withColumnRenamed("component", "closureAnchorMentionId")
    val activeVetoes = vetoes
      .join(lab.select(c("id").as("a"), c("component").as("cA")), Seq("a"))
      .join(lab.select(c("id").as("b"), c("component").as("cB")), Seq("b"))
      .where(c("cA") === c("cB"))
      .select(c("cA").as("component"), c("a"), c("b"), c("vetoKey"), c("isConflict"))
      .transform(Checkpointing.cut)
    counts += "erVetoMentionPairsWithinClosure" -> activeVetoes.count()

    // Conflict policy (WO-06a repair-01 F2, kept): ER's rule is "a pair with any conflict never
    // merges". A closure component that contains both members of a mention-scope conflict pair is
    // not merged at all: every member mention becomes its own singleton researcher (anchor = the
    // mention itself), with identityStatus `conflicted-split` and the pre-split closure anchor kept
    // in `conflictedClusterAnchorMentionId`. This is stricter than the constrained closure below, so
    // a conflict veto is honoured either way; every accepted link of such a component is recorded as
    // refused (`conflicted-cluster-split`, vetoing decision = the smallest conflict pair key).
    val conflictedClusters = activeVetoes
      .where(c("isConflict"))
      .groupBy(c("component").as("closureAnchorMentionId"))
      .agg(F.min("vetoKey").as("splitVetoKey"))
      .withColumn("conflictedCluster", F.lit(true))
      .transform(Checkpointing.cut)

    // Veto-constrained closure (D1, ConstrainedClosure): only the closure components that hold a
    // non-conflict veto between two of their own mentions are gathered, one group per component,
    // and solved inside one task each (constrained union-find is sequential). Every other component
    // keeps its distributed closure label unchanged.
    val constrainedComps = activeVetoes
      .select("component")
      .distinct()
      .join(
        conflictedClusters.select(c("closureAnchorMentionId").as("component")),
        Seq("component"),
        "left_anti"
      )
    val linkComp = linkRecs
      .join(lab.select(c("id").as("low"), c("component")), Seq("low"))
    val compLinks = linkComp
      .join(constrainedComps, Seq("component"), "left_semi")
      .groupBy("component")
      .agg(
        F.collect_list(
          F.struct(
            c("low"),
            c("high"),
            c("linkConfidence").as("confidence"),
            c("linkKey").as("key")
          )
        ).as("links"),
        F.count(F.lit(1)).as("componentLinks")
      )
    val compVetoes = activeVetoes
      .join(constrainedComps, Seq("component"), "left_semi")
      .groupBy("component")
      .agg(F.collect_list(F.struct(c("a"), c("b"), c("vetoKey").as("key"))).as("vetoes"))
    val solved = compLinks
      .join(compVetoes, Seq("component"))
      .select(
        c("component"),
        c("componentLinks"),
        ConstrainedClosure.solveUdf(c("links"), c("vetoes")).as("r")
      )
      .transform(Checkpointing.cut)
    val constrainedAssign = solved
      .select(c("component"), F.explode(c("r.assign")).as("x"))
      .select(
        c("x.mentionId").as("mentionId"),
        c("x.anchorMentionId").as("constrainedAnchorMentionId"),
        c("component").as("vetoConstrainedClosureAnchorMentionId")
      )
    val refused = solved
      .select(c("component"), F.explode(c("r.refused")).as("x"))
      .select(
        c("x.linkKey").as("linkKey"),
        c("x.vetoKey").as("vetoKey"),
        c("component").as("closureAnchorMentionId"),
        F.lit(RefusedByVeto).as("refusalReason")
      )
      .unionByName(
        linkComp
          .join(
            conflictedClusters.select(
              c("closureAnchorMentionId").as("component"),
              c("splitVetoKey")
            ),
            Seq("component")
          )
          .select(
            c("linkKey"),
            c("splitVetoKey").as("vetoKey"),
            c("component").as("closureAnchorMentionId"),
            F.lit(RefusedBySplit).as("refusalReason")
          )
      )
      .transform(Checkpointing.cut)

    val clustered = closure
      .join(
        conflictedClusters.select("closureAnchorMentionId", "conflictedCluster"),
        Seq("closureAnchorMentionId"),
        "left"
      )
      .join(constrainedAssign, Seq("mentionId"), "left")
      .withColumn("split", c("conflictedCluster").isNotNull)
      .withColumn(
        "anchorMentionId",
        F.when(c("split"), c("mentionId"))
          .when(c("constrainedAnchorMentionId").isNotNull, c("constrainedAnchorMentionId"))
          .otherwise(c("closureAnchorMentionId"))
      )
      .withColumn(
        "conflictedClusterAnchorMentionId",
        F.when(c("split"), c("closureAnchorMentionId"))
      )
      .drop("conflictedCluster", "closureAnchorMentionId", "constrainedAnchorMentionId")
      .transform(Checkpointing.cut)
    counts += "conflictedClusters"             -> conflictedClusters.count()
    counts += "conflictedClusterMentionsSplit" -> clustered.where(c("split")).count()
    val solvedStats = solved
      .agg(
        F.count(F.lit(1)),
        F.coalesce(F.max(F.size(c("r.assign"))).cast("long"), F.lit(0L)),
        F.coalesce(F.max(c("componentLinks")), F.lit(0L))
      )
      .first()
    counts += "identityConstrainedComponents"   -> solvedStats.getLong(0)
    counts += "maxConstrainedComponentMentions" -> solvedStats.getLong(1)
    counts += "maxConstrainedComponentLinks"    -> solvedStats.getLong(2)
    counts += "identityConstrainedMentions"     ->
      clustered.where(c("vetoConstrainedClosureAnchorMentionId").isNotNull).count()
    val refusedByReason = refused
      .groupBy("refusalReason")
      .count()
      .collect() // at most two rows
      .map(r => r.getString(0) -> r.getLong(1))
      .toMap
    List(RefusedByVeto, RefusedBySplit).foreach(r =>
      counts += s"identityLinksRefused:$r" -> refusedByReason.getOrElse(r, 0L)
    )
    val refusedN = refusedByReason.values.sum
    val anchors  = mentions.select(
      c("mentionId").as("anchorMentionId"),
      c("provenance.sourceDataset").as("anchorDataset"),
      c("provenance.sourceRecordId").as("anchorRecordId"),
      c("provenance.pipelineRunId").as("anchorRunId")
    )
    val sizes = clustered
      .groupBy("anchorMentionId")
      .agg(
        F.count(F.lit(1)).as("clusterSize"),
        F.max(c("split")).as("split"),
        F.max(c("conflictedClusterAnchorMentionId")).as("conflictedClusterAnchorMentionId"),
        F.max(c("vetoConstrainedClosureAnchorMentionId"))
          .as("vetoConstrainedClosureAnchorMentionId")
      )
    val clusters = anchors
      .join(sizes, Seq("anchorMentionId"))
      .withColumn("researcherId", IdUdfs.researcherIdUdf(c("anchorDataset"), c("anchorMentionId")))
      .withColumn(
        "identityStatus",
        F.when(c("split"), "conflicted-split")
          .when(c("clusterSize") > 1, "merged")
          .otherwise("singleton")
      )
      .drop("split")
    val mentionMap = attachResearchers(clustered, clusters).persist(StorageLevel.MEMORY_AND_DISK)
    mentionMap
      .select(
        c("mentionId"),
        c("researcherId"),
        c("anchorMentionId"),
        c("identityStatus"),
        c("clusterSize"),
        c("conflictedClusterAnchorMentionId"),
        c("vetoConstrainedClosureAnchorMentionId"),
        c("provenance.sourceDataset").as("sourceDataset"),
        c("provenance.sourceRecordId").as("sourceRecordId"),
        c("provenance.pipelineRunId").as("sourcePipelineRunId"),
        runIdC.as("pipelineRunId")
      )
      .write
      .parquet(s"$base/mention-researcher-map")

    // Refused accepted links (D1 evidence): the link, the vetoing ER decision, and both sources.
    val linkDetail = linkRecs.select(
      c("linkKey"),
      c("low").as("memberLow"),
      c("high").as("memberHigh"),
      c("decisionId").as("linkDecisionId"),
      c("linkConfidence"),
      c("policyVersion").as("linkPolicyVersion"),
      c("provenance.sourceDataset").as("sourceDataset"),
      c("provenance.sourceRecordId").as("sourceRecordId"),
      c("provenance.pipelineRunId").as("sourcePipelineRunId"),
      c("pLow.sourceDataset").as("memberLowSourceDataset"),
      c("pLow.sourceRecordId").as("memberLowSourceRecordId"),
      c("pLow.pipelineRunId").as("memberLowSourcePipelineRunId"),
      c("pHigh.sourceDataset").as("memberHighSourceDataset"),
      c("pHigh.sourceRecordId").as("memberHighSourceRecordId"),
      c("pHigh.pipelineRunId").as("memberHighSourcePipelineRunId")
    )
    val vetoDetail = vetoes.select(
      c("vetoKey"),
      c("a").as("vetoMemberLow"),
      c("b").as("vetoMemberHigh"),
      c("decisionId").as("vetoDecisionId"),
      c("status").as("vetoStatus"),
      c("erFile").as("vetoErFile"),
      c("reasonCodes").as("vetoReasonCodes"),
      c("policyVersion").as("vetoPolicyVersion"),
      c("provenance.sourceDataset").as("vetoSourceDataset"),
      c("provenance.sourceRecordId").as("vetoSourceRecordId"),
      c("provenance.pipelineRunId").as("vetoSourcePipelineRunId")
    )
    val refusedOut = refused
      .join(linkDetail, Seq("linkKey"), "left")
      .join(vetoDetail, Seq("vetoKey"), "left")
      .select(
        c("linkKey").as("pairId"),
        c("memberLow"),
        c("memberHigh"),
        c("linkDecisionId"),
        c("linkConfidence"),
        c("linkPolicyVersion"),
        c("refusalReason"),
        c("closureAnchorMentionId"),
        c("vetoKey").as("vetoPairId"),
        c("vetoMemberLow"),
        c("vetoMemberHigh"),
        c("vetoDecisionId"),
        c("vetoStatus"),
        c("vetoErFile"),
        c("vetoReasonCodes"),
        c("vetoPolicyVersion"),
        c("vetoSourceDataset"),
        c("vetoSourceRecordId"),
        c("vetoSourcePipelineRunId"),
        c("sourceDataset"),
        c("sourceRecordId"),
        c("sourcePipelineRunId"),
        c("memberLowSourceDataset"),
        c("memberLowSourceRecordId"),
        c("memberLowSourcePipelineRunId"),
        c("memberHighSourceDataset"),
        c("memberHighSourceRecordId"),
        c("memberHighSourcePipelineRunId"),
        F.lit(ConstrainedClosure.Version).as("identityClosureVersion"),
        runIdC.as("pipelineRunId")
      )
      .transform(Checkpointing.cut)
    refusedOut.write.parquet(s"$base/identity-refused-links")

    val byName = mentionMap
      .groupBy("researcherId", "normalizedName")
      .agg(F.count(F.lit(1)).as("n"))
      .withColumn(
        "rk",
        F.row_number().over(
          Window.partitionBy("researcherId").orderBy(c("n").desc, c("normalizedName").asc)
        )
      )
      .where(c("rk") === 1)
      .select(c("researcherId"), c("normalizedName").as("displayName"))
    val researchers = mentionMap
      .groupBy("researcherId")
      .agg(
        F.sort_array(F.collect_set("normalizedName")).as("nameVariants"),
        F.count(F.lit(1)).as("mentionCount")
      )
      .join(byName, Seq("researcherId"))
      .join(
        clusters.select(
          c("researcherId"),
          c("identityStatus"),
          c("anchorMentionId"),
          c("conflictedClusterAnchorMentionId"),
          c("vetoConstrainedClosureAnchorMentionId"),
          c("anchorDataset").as("sourceDataset"),
          c("anchorRecordId").as("sourceRecordId"),
          c("anchorRunId").as("sourcePipelineRunId")
        ),
        Seq("researcherId")
      )
      .withColumn("pipelineRunId", runIdC)
      .persist(StorageLevel.MEMORY_AND_DISK)
    researchers.write.parquet(s"$base/researchers")
    counts += "researchers"       -> researchers.count()
    counts += "researchersMerged" -> researchers.where(c("identityStatus") === "merged").count()
    counts += "researchersConflictedSplit" ->
      researchers.where(c("identityStatus") === "conflicted-split").count()
    counts += "researchersVetoConstrained" ->
      researchers.where(c("vetoConstrainedClosureAnchorMentionId").isNotNull).count()
    counts += "identityCcIterations"   -> idCc.iterations.toLong
    counts += "maxIdentityClusterSize" ->
      researchers.agg(F.coalesce(F.max("mentionCount"), F.lit(0L))).first().getLong(0)
    // A conflicted-split researcher is exactly one mention.
    invariants += "conflicted-split-not-singleton" ->
      (mentionMap
        .where(c("identityStatus") === "conflicted-split" && c("clusterSize") =!= 1)
        .count() +
        researchers
          .where(c("identityStatus") === "conflicted-split" && c("mentionCount") =!= 1)
          .count())
    // ER's rule, checked on the output: the two members of a conflict pair never share a researcher.
    val mmR = mentionMap.select("mentionId", "researcherId")
    invariants += "conflict-pair-same-researcher" ->
      con
        .where(c("scope") === "mention")
        .join(
          mmR.select(c("mentionId").as("memberLow"), c("researcherId").as("rL")),
          Seq("memberLow")
        )
        .join(
          mmR.select(c("mentionId").as("memberHigh"), c("researcherId").as("rH")),
          Seq("memberHigh")
        )
        .where(c("rL") === c("rH"))
        .count()
    // STRICT rule c, checked on the output: no veto pair (rejected, unresolved, conflict) shares a
    // researcher.
    invariants += "veto-pair-same-researcher" ->
      vetoes
        .join(mmR.select(c("mentionId").as("a"), c("researcherId").as("rA")), Seq("a"))
        .join(mmR.select(c("mentionId").as("b"), c("researcherId").as("rB")), Seq("b"))
        .where(c("rA") === c("rB"))
        .count()
    // A refused link's two mentions never share a researcher, and every refused row resolves to
    // its link and its vetoing decision.
    invariants += "refused-link-same-researcher" ->
      refusedOut
        .join(
          mmR.select(c("mentionId").as("memberLow"), c("researcherId").as("rL")),
          Seq("memberLow")
        )
        .join(
          mmR.select(c("mentionId").as("memberHigh"), c("researcherId").as("rH")),
          Seq("memberHigh")
        )
        .where(c("rL") === c("rH"))
        .count()
    invariants += "refused-link-missing-evidence" ->
      refusedOut
        .where(
          c("memberLow").isNull || c("vetoDecisionId").isNull || c("vetoSourceDataset").isNull ||
            c("vetoSourceRecordId").isNull || c("vetoSourcePipelineRunId").isNull
        )
        .count()
    invariants += "refused-link-duplicate" ->
      (refusedN - refusedOut.select("pairId").distinct().count())
    // Every distinct applied link is either refused or has both mentions in one researcher.
    val linkJoined = linkRecs
      .join(mmR.select(c("mentionId").as("low"), c("researcherId").as("rL")), Seq("low"))
      .join(mmR.select(c("mentionId").as("high"), c("researcherId").as("rH")), Seq("high"))
      .where(c("rL") === c("rH"))
      .count()
    invariants += "accepted-link-accounting" -> math.abs(linkPairN - refusedN - linkJoined)
    // Every mention of a constrained component received a constrained anchor.
    invariants += "constrained-mention-unassigned" ->
      closure
        .join(
          constrainedComps.select(c("component").as("closureAnchorMentionId")),
          Seq("closureAnchorMentionId"),
          "left_semi"
        )
        .join(constrainedAssign, Seq("mentionId"), "left_anti")
        .count()

    val worksRaw = readJson(spark, InputSchemas.work)(in.works)
    val works    = worksRaw
      .where(c("workId").isNotNull && c("provenance").isNotNull)
      .dropDuplicates("workId")
      .select(
        c("workId"),
        c("title"),
        c("year"),
        c("venueId"),
        c("provenance.sourceDataset").as("sourceDataset"),
        c("provenance.sourceRecordId").as("sourceRecordId"),
        c("provenance.pipelineRunId").as("sourcePipelineRunId"),
        runIdC.as("pipelineRunId")
      )
      .persist(StorageLevel.MEMORY_AND_DISK)
    works.write.parquet(s"$base/works")
    val workRows = worksRaw.count()
    val workN    = works.count()
    counts += "inputWorkRows"                 -> workRows
    counts += "inputWorkMalformedOrDuplicate" -> (workRows - workN)
    counts += "works"                         -> workN

    val missingWorkMentions =
      mentionMap.join(works.select("workId"), Seq("workId"), "left_anti").count()
    counts += "authoredMentionsMissingWork" -> missingWorkMentions
    val authored = mentionMap
      .join(works.select("workId", "year").withColumnRenamed("year", "workYear"), Seq("workId"))
      .groupBy("researcherId", "workId")
      .agg(
        F.sort_array(F.collect_list("mentionId")).as("mentionIds"),
        F.min("authorPosition").as("authorPosition"),
        F.first("workYear").as("year"),
        F.min(
          F.struct(
            c("mentionId"),
            c("provenance.sourceDataset").as("sourceDataset"),
            c("provenance.sourceRecordId").as("sourceRecordId"),
            c("provenance.pipelineRunId").as("sourcePipelineRunId")
          )
        ).as("src")
      )
      .select(
        c("researcherId"),
        c("workId"),
        c("mentionIds"),
        c("authorPosition"),
        c("year"),
        c("src.sourceDataset").as("sourceDataset"),
        c("src.sourceRecordId").as("sourceRecordId"),
        c("src.sourcePipelineRunId").as("sourcePipelineRunId"),
        runIdC.as("pipelineRunId")
      )
      .persist(StorageLevel.MEMORY_AND_DISK)
    authored.write.parquet(s"$base/authored")
    val authoredN = authored.count()
    counts += "authored"                  -> authoredN
    counts += "authoredCollapsedMentions" ->
      (mCount - missingWorkMentions - authoredN)
    invariants += "authored-dangling-researcher" ->
      authored.join(researchers.select("researcherId"), Seq("researcherId"), "left_anti").count()
    invariants += "authored-dangling-work" ->
      authored.join(works.select("workId"), Seq("workId"), "left_anti").count()

    // ==== Job 2: temporal weighted coauthorship graph ============================================
    val pairsRaw = readJson(spark, InputSchemas.mentionPair)(in.pairs)
    val pairs    = pairsRaw.where(
      c("mentionLow").isNotNull && c("mentionHigh").isNotNull && c("workId").isNotNull
    )
    val pairRows = pairsRaw.count()
    val pairN    = pairs.count()
    counts += "inputPairRows"      -> pairRows
    counts += "inputPairMalformed" -> (pairRows - pairN)
    // Pairs whose endpoint is a conflicted-split mention are handled like any singleton's pairs:
    // the endpoint is one mention, nothing is merged, so the pair is attributed to that mention's
    // own researcher and enters the graph (WO-06a repair-01 F2).
    val mm         = mentionMap.select("mentionId", "researcherId")
    val l          = mm.select(c("mentionId").as("mentionLow"), c("researcherId").as("rL"))
    val h          = mm.select(c("mentionId").as("mentionHigh"), c("researcherId").as("rH"))
    val wk         = works.select(c("workId"), F.lit(true).as("workExists"))
    val y          = c("year")
    val classified = pairs
      .join(l, Seq("mentionLow"), "left")
      .join(h, Seq("mentionHigh"), "left")
      .join(wk, Seq("workId"), "left")
      .withColumn(
        "exclusionReason",
        F.when(c("rL").isNull || c("rH").isNull, "unresolved-endpoint")
          .when(c("workExists").isNull, "missing-work")
          .when(y.isNull, "missing-year")
          .when(y < win.startYear || y > win.endYear, "out-of-window")
          .when(c("rL") === c("rH"), "self-pair")
      )
      .withColumn("researcherLow", F.least(c("rL"), c("rH")))
      .withColumn("researcherHigh", F.greatest(c("rL"), c("rH")))
      .persist(StorageLevel.MEMORY_AND_DISK)
    val excluded = classified.where(c("exclusionReason").isNotNull)
    excluded
      .select(
        c("mentionLow"),
        c("mentionHigh"),
        c("workId"),
        c("year"),
        c("researcherLow"),
        c("researcherHigh"),
        c("exclusionReason"),
        c("provenance.sourceDataset").as("sourceDataset"),
        c("provenance.sourceRecordId").as("sourceRecordId"),
        c("provenance.pipelineRunId").as("sourcePipelineRunId"),
        runIdC.as("pipelineRunId")
      )
      .write
      .parquet(s"$winBase/excluded-pairs")
    // `conflicted-endpoint` is no longer assigned (conflicted clusters are split into singletons,
    // F2). It stays in the reported list, as 0, so counts.json and the metric column keep their
    // schema; any future reintroduction flows through to excludedConflictPairOccurrences.
    val reasons = List(
      "unresolved-endpoint",
      "missing-work",
      "missing-year",
      "out-of-window",
      "self-pair",
      "conflicted-endpoint"
    )
    val byReason = excluded
      .groupBy("exclusionReason")
      .count()
      .collect() // at most six rows
      .map(r => r.getString(0) -> r.getLong(1))
      .toMap
    reasons.foreach(r => counts += s"pairsExcluded:$r" -> byReason.getOrElse(r, 0L))
    val kept      = classified.where(c("exclusionReason").isNull)
    val keptN     = kept.count()
    val keptDist  = kept.dropDuplicates("researcherLow", "researcherHigh", "workId")
    val keptDistN = keptDist.count()
    counts += "pairsKept"                       -> keptN
    counts += "pairsCollapsedSameWorkDuplicate" -> (keptN - keptDistN)
    invariants += "pair-accounting"             -> (pairN - keptN - byReason.values.sum)

    val edges = keptDist
      .groupBy("researcherLow", "researcherHigh")
      .agg(
        F.countDistinct("workId").cast("int").as("jointPublicationCount"),
        F.min("year").as("firstCollaborationYear"),
        F.max("year").as("lastCollaborationYear"),
        F.sort_array(F.collect_set("workId")).as("supportingWorkIds"),
        F.sort_array(F.collect_set("provenance.sourceDataset")).as("sourceDatasets"),
        F.sort_array(F.collect_set("provenance.pipelineRunId")).as("sourcePipelineRunIds")
      )
      .withColumn(
        "edgeId",
        IdUdfs.coauthorEdgeIdUdf(win, p.policyVersion)(c("researcherLow"), c("researcherHigh"))
      )
      .withColumn("windowStartYear", F.lit(win.startYear))
      .withColumn("windowEndYear", F.lit(win.endYear))
      .withColumn("policyVersion", F.lit(p.policyVersion))
      .withColumn("sourceDataset", F.lit(SourceType.ScholarChainPipeline.wire))
      .withColumn("sourceRecordId", c("edgeId"))
      .withColumn("pipelineRunId", runIdC)
      .persist(StorageLevel.MEMORY_AND_DISK)
    edges.write.parquet(s"$winBase/coauthor-edges")
    val edgeN = edges.count()
    counts += "coauthorEdges" -> edgeN
    val partStats = edges
      .groupBy(F.spark_partition_id().as("pid"))
      .count()
      .agg(
        F.count(F.lit(1)),
        F.coalesce(F.max("count"), F.lit(0L)),
        F.coalesce(F.min("count"), F.lit(0L))
      )
      .first()
    counts += "coauthorEdgePartitionsNonEmpty"  -> partStats.getLong(0)
    counts += "coauthorEdgeMaxRowsPerPartition" -> partStats.getLong(1)
    counts += "coauthorEdgeMinRowsPerPartition" -> partStats.getLong(2)
    counts += "maxJointPublicationCount"        ->
      edges.agg(F.coalesce(
        F.max("jointPublicationCount"),
        F.lit(0)
      ).cast("long")).first().getLong(0)

    val rIds = researchers.select("researcherId")
    invariants +=
      "edge-self-loop" -> edges.where(c("researcherLow") === c("researcherHigh")).count()
    invariants += "edge-non-canonical-order" ->
      edges.where(c("researcherLow") >= c("researcherHigh")).count()
    invariants += "edge-duplicate-pair" ->
      (edgeN - edges.select("researcherLow", "researcherHigh").distinct().count())
    invariants += "edge-duplicate-id"   -> (edgeN - edges.select("edgeId").distinct().count())
    invariants += "edge-count-mismatch" ->
      edges.where(F.size(c("supportingWorkIds")) =!= c("jointPublicationCount")).count()
    invariants += "edge-year-bounds" ->
      edges
        .where(
          !(c("firstCollaborationYear") >= win.startYear &&
            c("firstCollaborationYear") <= c("lastCollaborationYear") &&
            c("lastCollaborationYear") <= win.endYear)
        )
        .count()
    val endpoints = edges
      .select(F.explode(F.array(c("researcherLow"), c("researcherHigh"))).as("researcherId"))
      .distinct()
    invariants += "edge-dangling-endpoint" ->
      endpoints.join(rIds, Seq("researcherId"), "left_anti").count()
    invariants += "edge-support-missing-work" ->
      edges
        .select(F.explode(c("supportingWorkIds")).as("workId"))
        .join(works.select("workId"), Seq("workId"), "left_anti")
        .count()

    // ==== Job 3: per-researcher metrics for the window ===========================================
    val aw = authored
      .where(c("year").between(win.startYear, win.endYear))
      .select("researcherId", "workId")
    val team = aw.groupBy("workId").agg(F.count(F.lit(1)).as("teamSize"))
    val rw   = aw.join(team, Seq("workId")).withColumn("k", c("teamSize") - 1)
    val agg  = rw
      .groupBy("researcherId")
      .agg(
        F.count(F.lit(1)).as("publicationCount"),
        F.sum("k").as("totalCoauthorOccurrences"),
        F.max("k").as("maximumCoauthorsOnOnePaper")
      )
    val mWorks = rw
      .join(agg.select("researcherId", "maximumCoauthorsOnOnePaper"), Seq("researcherId"))
      .where(c("k") === c("maximumCoauthorsOnOnePaper"))
      .groupBy("researcherId")
      .agg(F.sort_array(F.collect_list("workId")).as("maximumCoauthorWorkIds"))
    val degree = edges
      .select(
        F.explode(
          F.array(
            F.struct(c("researcherLow").as("r"), c("jointPublicationCount").as("w")),
            F.struct(c("researcherHigh").as("r"), c("jointPublicationCount").as("w"))
          )
        ).as("e")
      )
      .groupBy(c("e.r").as("researcherId"))
      .agg(
        F.count(F.lit(1)).as("distinctCoauthorCount"),
        F.sum("e.w").as("weightedDegree")
      )
    val conflictExcl = excluded
      .where(c("exclusionReason") === "conflicted-endpoint")
      .select("researcherLow", "researcherHigh", "workId")
      .distinct()
      .select(F.explode(F.array(c("researcherLow"), c("researcherHigh"))).as("researcherId"))
      .groupBy("researcherId")
      .agg(F.count(F.lit(1)).as("excludedConflictPairOccurrences"))
    val metricsDf = agg
      .join(mWorks, Seq("researcherId"))
      .join(degree, Seq("researcherId"), "left")
      .join(conflictExcl, Seq("researcherId"), "left")
      .na
      .fill(0L, Seq("distinctCoauthorCount", "weightedDegree", "excludedConflictPairOccurrences"))
      .withColumn(
        "averageCoauthorsPerPaper",
        c("totalCoauthorOccurrences").cast("double") / c("publicationCount").cast("double")
      )
      .join(researchers.select("researcherId", "identityStatus"), Seq("researcherId"), "left")
      .withColumn("windowStartYear", F.lit(win.startYear))
      .withColumn("windowEndYear", F.lit(win.endYear))
      .withColumn("policyVersion", F.lit(p.policyVersion))
      .withColumn("sourceDataset", F.lit(SourceType.ScholarChainPipeline.wire))
      .withColumn("sourceRecordId", c("researcherId"))
      .withColumn("pipelineRunId", runIdC)
      .select(
        "researcherId",
        "identityStatus",
        "publicationCount",
        "distinctCoauthorCount",
        "totalCoauthorOccurrences",
        "averageCoauthorsPerPaper",
        "maximumCoauthorsOnOnePaper",
        "weightedDegree",
        "maximumCoauthorWorkIds",
        "excludedConflictPairOccurrences",
        "windowStartYear",
        "windowEndYear",
        "policyVersion",
        "sourceDataset",
        "sourceRecordId",
        "pipelineRunId"
      )
      .persist(StorageLevel.MEMORY_AND_DISK)
    metricsDf.write.parquet(s"$winBase/researcher-metrics")
    val metricN = metricsDf.count()
    counts += "researchersWithMetrics" -> metricN
    counts += "maxTeamSizeInWindow"    ->
      team.agg(F.coalesce(F.max("teamSize"), F.lit(0L))).first().getLong(0)
    counts += "maxDistinctCoauthorCount" ->
      metricsDf.agg(F.coalesce(F.max("distinctCoauthorCount"), F.lit(0L))).first().getLong(0)
    invariants += "metrics-weighted-degree-identity" ->
      metricsDf
        .where(
          c("weightedDegree") + c("excludedConflictPairOccurrences") =!=
            c("totalCoauthorOccurrences")
        )
        .count()
    invariants += "metrics-bounds" ->
      metricsDf
        .where(
          c("publicationCount") < 1 ||
            c("distinctCoauthorCount") > c("totalCoauthorOccurrences") ||
            c("maximumCoauthorsOnOnePaper") > c("totalCoauthorOccurrences") ||
            F.size(c("maximumCoauthorWorkIds")) < 1
        )
        .count()
    invariants += "edge-endpoint-without-metrics" ->
      endpoints.join(metricsDf.select("researcherId"), Seq("researcherId"), "left_anti").count()

    // ==== Job 4: connected components of the windowed coauthorship graph =========================
    val gCc = ConnectedComponents.run(
      metricsDf.select(c("researcherId").as("id")),
      edges.select(c("researcherLow").as("src"), c("researcherHigh").as("dst")),
      p.ccMaxIterations
    )
    val labelled = gCc.labels.select(c("id").as("researcherId"), c("component").as("componentId"))
    val compSize = labelled.groupBy("componentId").agg(F.count(F.lit(1)).as("componentSize"))
    val comps    = labelled
      .join(compSize, Seq("componentId"))
      .withColumn("windowStartYear", F.lit(win.startYear))
      .withColumn("windowEndYear", F.lit(win.endYear))
      .withColumn("policyVersion", F.lit(p.policyVersion))
      .withColumn("sourceDataset", F.lit(SourceType.ScholarChainPipeline.wire))
      .withColumn("sourceRecordId", c("researcherId"))
      .withColumn("pipelineRunId", runIdC)
      .persist(StorageLevel.MEMORY_AND_DISK)
    comps.write.parquet(s"$winBase/components")
    val summary = compSize
      .agg(
        F.count(F.lit(1)),
        F.coalesce(F.max("componentSize"), F.lit(0L)),
        F.coalesce(F.sum(F.when(c("componentSize") === 1, 1L).otherwise(0L)), F.lit(0L)),
        F.coalesce(F.sum("componentSize"), F.lit(0L))
      )
      .first()
    val compCount   = summary.getLong(0)
    val largest     = summary.getLong(1)
    val singletons  = summary.getLong(2)
    val vertexTotal = summary.getLong(3)
    val fraction    = if metricN == 0 then 0.0 else largest.toDouble / metricN.toDouble
    counts += "components"               -> compCount
    counts += "largestComponentSize"     -> largest
    counts += "singletonResearchers"     -> singletons
    counts += "graphCcIterations"        -> gCc.iterations.toLong
    invariants += "component-size-sum"   -> math.abs(vertexTotal - metricN)
    invariants += "component-edge-split" ->
      edges
        .join(
          labelled.select(c("researcherId").as("researcherLow"), c("componentId").as("cL")),
          Seq("researcherLow")
        )
        .join(
          labelled.select(c("researcherId").as("researcherHigh"), c("componentId").as("cH")),
          Seq("researcherHigh")
        )
        .where(c("cL") =!= c("cH"))
        .count()

    // ---- Reports ---------------------------------------------------------------------------------
    val stage    = stageMetrics.snapshot
    val countL   = counts.result() ++ stage.map((k, v) => s"stage:$k" -> v)
    val invL     = invariants.result()
    val countObj = ujson.Obj.from(countL.map((k, v) => k -> ujson.Num(v.toDouble)))
    countObj("largestComponentFraction") = ujson.Num(fraction)
    JobFiles.writeText(spark, s"$base/manifest.json", Wire.encodePretty(manifest) + "\n")
    JobFiles.writeText(
      spark,
      s"$base/params.json",
      ujson.write(
        ujson.Obj(
          "pipelineName"              -> PipelineName,
          "runId"                     -> runId,
          "windowStartYear"           -> win.startYear,
          "windowEndYear"             -> win.endYear,
          "policyVersion"             -> p.policyVersion,
          "ccMaxIterations"           -> p.ccMaxIterations,
          "shufflePartitions"         -> p.shufflePartitions,
          "coreConfigFingerprint"     -> coreFingerprint,
          "researcherIdentityVersion" -> IdUdfs.IdentityVersion,
          "researcherAnchorRule"      ->
            "smallest mention ID in the veto-constrained accepted-link cluster",
          "conflictPolicy" ->
            "a cluster joining a conflict pair is split: each member mention is its own singleton researcher (conflicted-split)",
          "identityClosure"  -> ConstrainedClosure.Version,
          "identityVetoRule" ->
            "STRICT rule c: a mention pair with any non-accepted ER decision (rejected, unresolved, conflict) is never joined; pairs with no ER decision carry no veto",
          "identityVetoFiles"    -> ujson.Arr.from(Outputs.MergeVetoFiles),
          "erDecisionRecordRule" -> ErRecordCheck.Rule,
          "erManifestRule"       -> ErManifestGate.Rule,
          "erDecisions"          -> ujson.Obj(
            "manifestFile"          -> Outputs.ManifestFileName,
            "manifestSha256"        -> ("sha256:" + erVerified.manifestSha256),
            "manifestSchemaVersion" -> erManifest.manifestSchemaVersion,
            "pipelineRunId"         -> erManifest.pipelineRunId,
            "policyVersion"         -> erManifest.policyVersion,
            "featuresVersion"       -> erManifest.featuresVersion,
            "schemaVersion"         -> erManifest.schemaVersion,
            "files"                 -> ujson.Arr.from(erManifest.files.map(e =>
              ujson.Obj(
                "name"        -> e.name,
                "recordCount" -> ujson.Num(e.recordCount.toDouble),
                "sha256"      -> e.sha256
              )
            )),
            "runIdInput" ->
              s"the manifest SHA-256 is run input '$ErDecisionsManifestSnapshot', so the run ID changes whenever any ER decision file or the ER run changes"
          ),
          "identityLinkOrder" ->
            "ER confidence descending (NaN or null = 0.0), then link key mention|low|high ascending",
          "inputs" -> ujson.Obj(
            "authorPapers" -> in.authorPapers,
            "pairs"        -> in.pairs,
            "works"        -> in.works,
            "erDir"        -> in.erDir
          )
        ),
        indent = 2
      ) + "\n"
    )
    JobFiles.writeText(spark, s"$base/counts.json", ujson.write(countObj, indent = 2) + "\n")
    JobFiles.writeText(
      spark,
      s"$base/invariants.json",
      ujson.write(ujson.Obj.from(invL.map((k, v) => k -> ujson.Num(v.toDouble))), indent = 2) + "\n"
    )
    List(
      mentions,
      decisions,
      linkRecs,
      vetoes,
      activeVetoes,
      conflictedClusters,
      solved,
      refused,
      refusedOut,
      clustered,
      mentionMap,
      researchers,
      works,
      authored,
      classified,
      edges,
      metricsDf,
      comps
    ).foreach(_.unpersist())
    PartAResult(runId, base, countL, invL, fraction, idCc.iterations, gCc.iterations)

/**
 * `PartAMain --config <file> --authorpapers <dir> --pairs <dir> --works <dir> --er <dir> --out
 * <dir> --cc-max-iterations <n> --shuffle-partitions <n> [--code-version <v>] [--attempt <n>]`.
 *
 * The year window and policy version come from the core configuration (`graph.window-*`,
 * `graph.policy-version`), loaded by `ConfigLoader` from `--config` plus the environment. Exits 2
 * on bad arguments or configuration and 3 when any output invariant fails.
 */
object PartAMain:
  def main(args: Array[String]): Unit =
    val kv =
      if args.length % 2 != 0 then Map.empty[String, String]
      else args.grouped(2).map(a => a(0).stripPrefix("--") -> a(1)).toMap
    val required = List(
      "config",
      "authorpapers",
      "pairs",
      "works",
      "er",
      "out",
      "cc-max-iterations",
      "shuffle-partitions"
    )
    val missing = required.filterNot(k => kv.get(k).exists(_.trim.nonEmpty))
    if missing.nonEmpty then
      System.err.println(s"missing arguments: ${missing.mkString(", ")}")
      sys.exit(2)
    val cfg = ConfigLoader.load(Some(Paths.get(kv("config"))), sys.env) match
      case Right(v) => v
      case Left(es) =>
        System.err.println(es.mkString("configuration errors:\n  ", "\n  ", ""))
        sys.exit(2)
    val params = PartAParams(
      cfg.graph.window,
      cfg.graph.policyVersion,
      kv("cc-max-iterations").toInt,
      kv("shuffle-partitions").toInt
    )
    val spark = SparkSession.builder().appName(PartA.PipelineName).getOrCreate()
    try
      val r = PartA.run(
        spark,
        PartAInputs(kv("authorpapers"), kv("pairs"), kv("works"), kv("er")),
        params,
        cfg.fingerprint,
        kv.getOrElse("code-version", "dev"),
        kv.getOrElse("attempt", "1").toInt,
        kv("out")
      )
      println(s"PART-A run=${r.runId} output=${r.outputBase}")
      r.counts.foreach((k, v) => println(s"PART-A count $k=$v"))
      r.invariantFailures.foreach((k, v) => println(s"PART-A invariant $k=$v"))
      if r.failures.nonEmpty then sys.exit(3)
    finally spark.stop()
