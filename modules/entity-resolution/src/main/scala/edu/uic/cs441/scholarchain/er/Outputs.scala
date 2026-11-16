package edu.uic.cs441.scholarchain.er

import edu.uic.cs441.scholarchain.core.ids.{IdFormat, IdKind}
import edu.uic.cs441.scholarchain.core.model.Provenance
import upickle.default.{read, write, ReadWriter}

import java.io.{BufferedReader, InputStream, InputStreamReader, IOException}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, LinkOption, Path, StandardCopyOption}
import java.security.{DigestInputStream, MessageDigest}
import java.util.UUID
import scala.jdk.CollectionConverters.*
import scala.util.{Try, Using}

/**
 * One review-queue line (JSON Lines, `review-queue.jsonl`). The human labeler fills `label`
 * (`match`, `non-match`, or `unsure`), `labeler` (who reviewed it), and optionally `labelNote`, and
 * keeps every other field unchanged so the label stays traceable to the source records through
 * `provenanceLow` / `provenanceHigh`. The same format is the evaluator's input. `scoreUpper` and
 * `corroborated` (er-policy-v3) are copied from the [[ResolutionRecord]]; lines written before v3
 * lack them and read as null, which the evaluator treats as `scoreUpper = score` and, since
 * er-policy-v4, as corroborated only when the item was already accepted ([[Evaluator.restatus]]).
 */
@upickle.implicits.serializeDefaults(true)
final case class ReviewItem(
    pairId: String,
    decisionId: String,
    scope: String,
    memberLow: String,
    memberHigh: String,
    status: ResolutionStatus,
    path: MatchPath,
    score: Option[Double],
    policyVersion: String,
    features: List[NamedFeature],
    reasonCodes: List[String],
    provenanceLow: Provenance,
    provenanceHigh: Provenance,
    label: Option[String],
    labeler: Option[String],
    labelNote: Option[String],
    scoreUpper: Option[Double] = None,
    corroborated: Option[Boolean] = None
) derives ReadWriter

/**
 * Decision outputs of one ER run (directory written by [[Outputs.writeRun]] /
 * [[Outputs.writeAll]]).
 *
 * Files: `accepted.jsonl`, `rejected.jsonl`, `unresolved.jsonl`, `conflict.jsonl` (all four always
 * written, possibly empty; one [[ResolutionRecord]] JSON object per line, sorted by `pairId`; every
 * pair in exactly one file) and `review-queue.jsonl` ([[ReviewItem]]). Since Phase 11 repair-03
 * (output layout er-outputs-v2, review-02 F2) the directory also holds `_DECISIONS_MANIFEST.json`
 * ([[DecisionsManifest]]), written last, and is published atomically; every record in the four
 * files carries the manifest's `pipelineRunId`. A consumer must call [[Outputs.verify]] (or
 * [[Outputs.verifyWith]]) before it trusts the four files: an absent manifest, a 0-byte file the
 * manifest does not describe, or a file from another run fails verification.
 *
 * Merge vetoes for the identity closure (human decision STRICT, 2026-11-14, rule c): every record
 * in [[MergeVetoFiles]] (`rejected.jsonl`, `unresolved.jsonl`, `conflict.jsonl`, that is every
 * status except accepted) means its two members must never end up in one identity. A consumer
 * identifies the pair by `scope` (apply `mention` records to researcher identity), `memberLow` and
 * `memberHigh` (the two member IDs, `memberLow < memberHigh` by String.compareTo), and keeps
 * `decisionId`, `status`, `reasonCodes`, `policyVersion`, `provenance` (the ER run) and
 * `provenanceLow` / `provenanceHigh` (the two source records) as the veto's evidence.
 */
object Outputs:

  val ReviewQueueFile: String = "review-queue.jsonl"

  def fileFor(s: ResolutionStatus): String = s"${s.wire}.jsonl"

  /** Statuses whose pairs must never be joined (rule c): every status except accepted. */
  val MergeVetoStatuses: List[ResolutionStatus] =
    ResolutionStatus.values.toList.filter(_ != ResolutionStatus.Accepted)

  /** `rejected.jsonl`, `unresolved.jsonl`, `conflict.jsonl`. */
  val MergeVetoFiles: List[String] = MergeVetoStatuses.map(fileFor)

  /** Reads one decision file (any schema version); malformed lines are returned, never dropped. */
  def readRecords(path: Path): (List[ResolutionRecord], List[String]) =
    val lines = Files.readAllLines(path, UTF_8).asScala.toList.map(_.trim).zipWithIndex
      .filter(_._1.nonEmpty)
    val parsed = lines.map((l, i) =>
      Try(read[ResolutionRecord](l)).toEither.left.map(e => s"line ${i + 1}: ${e.getMessage}")
    )
    (parsed.collect { case Right(r) => r }, parsed.collect { case Left(e) => e })

  def toReviewItem(r: ResolutionRecord): ReviewItem =
    ReviewItem(
      r.pairId,
      r.decisionId,
      r.scope,
      r.memberLow,
      r.memberHigh,
      r.status,
      r.path,
      r.score,
      r.policyVersion,
      r.features,
      r.reasonCodes,
      r.provenanceLow,
      r.provenanceHigh,
      None,
      None,
      None,
      r.scoreUpper,
      r.corroborated
    )

  private def priority(s: ResolutionStatus): Int = s match
    case ResolutionStatus.Conflict   => 0
    case ResolutionStatus.Unresolved => 1
    case ResolutionStatus.Accepted   => 2
    case ResolutionStatus.Rejected   => 3

  /**
   * Review queue ordering: conflicts, then unresolved, then accepted, then rejected; within a class
   * by decision ID (a hash, so a deterministic pseudo-random sample). `limit` caps the export.
   */
  def reviewQueue(records: Seq[ResolutionRecord], limit: Int): List[ReviewItem] =
    records.sortBy(r => (priority(r.status), r.decisionId)).take(limit).map(toReviewItem).toList

  // ---- Completion manifest (Phase 11 repair-03, review-02 F2) -----------------------------------

  /** The completion manifest, written LAST into the output directory. */
  val ManifestFileName: String = "_DECISIONS_MANIFEST.json"

  /** Version of the [[DecisionsManifest]] format (output layout er-outputs-v2: with manifest). */
  val ManifestSchemaVersion: Int = 1

  /** The four decision files, in manifest order: accepted, rejected, unresolved, conflict. */
  val DecisionFiles: List[String] = ResolutionStatus.values.toList.map(fileFor)

  /** Every name [[writeAll]] writes; only a directory holding nothing else may be replaced. */
  val OwnedFiles: Set[String] = (ReviewQueueFile :: ManifestFileName :: DecisionFiles).toSet

  private val Sha256Hex = "^[0-9a-f]{64}$".r

  /** Lower-case hex SHA-256 of `bytes`. */
  def sha256Hex(bytes: Array[Byte]): String =
    hex(MessageDigest.getInstance("SHA-256").digest(bytes))

  private def hex(d: Array[Byte]): String = d.map(b => f"${b & 0xff}%02x").mkString

  private def encodeLines(lines: Seq[String]): Array[Byte] =
    lines.mkString("", "\n", if lines.isEmpty then "" else "\n").getBytes(UTF_8)

  /**
   * Writes one ER run, deriving the run from the records: `provenance.pipelineRunId` and
   * `policyVersion` of the first record (every record must carry the same ones, see [[writeRun]]).
   * `records` must not be empty; an empty run uses [[writeRun]] with an explicit run ID.
   */
  def writeAll(outDir: Path, records: Seq[ResolutionRecord], reviewLimit: Int): Map[String, Int] =
    require(
      records.nonEmpty,
      "Outputs.writeAll derives the run from the records; use Outputs.writeRun for an empty run"
    )
    writeRun(
      outDir,
      records,
      reviewLimit,
      records.head.provenance.pipelineRunId,
      records.head.policyVersion
    )

  /**
   * Writes the decision outputs of one ER run so that `outDir` never holds a mix of two runs (Phase
   * 11 repair-03, F2):
   *
   *   1. Checks every record BEFORE writing anything: `provenance.pipelineRunId == runId` (a valid
   *      `run:v1` ID), `policyVersion == policyVersion`, `featureSpecVersion ==`
   *      [[Features.SpecVersion]], `schemaVersion ==` [[Resolver.RecordSchemaVersion]]. A mismatch
   *      throws IllegalArgumentException and leaves `outDir` untouched.
   *   2. Checks that `outDir`, if it exists, is a real directory (not a link) holding only
   *      [[OwnedFiles]]; anything else throws java.io.IOException and nothing is moved or deleted.
   *   3. Writes the four decision files (all four, even when empty), the review queue, and LAST the
   *      [[ManifestFileName]] into a fresh hidden sibling `.<name>.staging-<uuid>`.
   *   4. Publishes it with same-directory atomic renames (ATOMIC_MOVE; never a copy fallback): when
   *      `outDir` does not exist, staging -> outDir; otherwise outDir -> `.<name>.replaced-<uuid>`,
   *      staging -> outDir, then the replaced directory's owned files and the directory itself are
   *      deleted. If the second rename fails, the first is undone.
   *
   * Local file system semantics (the only target: `outDir` is a java.nio Path, and ResolveMain
   * passes `Path.of(args(1))`): at every instant the path `outDir` is either the complete previous
   * run, the complete new run, or (only between the two renames of a replacement, or after a kill
   * there) absent. A reader that opens the files one by one across a replacement can still read
   * files of two runs; [[verify]] detects that (hashes and run IDs), so readers verify. A kill
   * before step 4 leaves `outDir` unchanged and a hidden staging directory behind; a kill between
   * the two renames leaves `outDir` absent (readers fail closed) and the previous run in the hidden
   * `.replaced-` directory. HDFS and S3: not written by this method. Whoever copies the directory
   * there must copy all files; on S3 (no atomic rename) a partial or mixed copy fails [[verify]]
   * because every file's SHA-256 and record count, and every record's run ID, must match the
   * manifest.
   *
   * Returns the record count per status wire name plus `review-queue`.
   */
  def writeRun(
      outDir: Path,
      records: Seq[ResolutionRecord],
      reviewLimit: Int,
      runId: String,
      policyVersion: String
  ): Map[String, Int] = writeRunWith(outDir, records, reviewLimit, runId, policyVersion, _ => ())

  /** [[writeRun]] with a hook called after each staged file is written (tests simulate a crash). */
  private[er] def writeRunWith(
      outDir: Path,
      records: Seq[ResolutionRecord],
      reviewLimit: Int,
      runId: String,
      policyVersion: String,
      afterStagedFile: String => Unit
  ): Map[String, Int] =
    require(IdFormat.isValid(IdKind.Run, runId), s"runId must be a run:v1 ID, got '$runId'")
    require(policyVersion.trim.nonEmpty, "policyVersion must not be blank")
    records.find(r =>
      r.provenance.pipelineRunId != runId || r.policyVersion != policyVersion ||
        r.featureSpecVersion != Features.SpecVersion ||
        r.schemaVersion != Resolver.RecordSchemaVersion
    ).foreach { r =>
      throw new IllegalArgumentException(
        s"record ${r.pairId} is not from run $runId / $policyVersion / ${Features.SpecVersion} / " +
          s"schema ${Resolver.RecordSchemaVersion} (it has ${r.provenance.pipelineRunId} / " +
          s"${r.policyVersion} / ${r.featureSpecVersion} / schema ${r.schemaVersion}); one output " +
          "directory holds exactly one ER run"
      )
    }
    val target = outDir.toAbsolutePath.normalize
    val parent = Option(target.getParent).getOrElse(
      throw new IllegalArgumentException(s"outDir $target has no parent directory")
    )
    Files.createDirectories(parent)
    checkReplaceable(target)
    val staging = Files.createDirectory(
      parent.resolve(s".${target.getFileName}.staging-${UUID.randomUUID}")
    )
    try
      val byStatus = records.groupBy(_.status)
      val entries  = ResolutionStatus.values.toList.map { s =>
        val rs    = byStatus.getOrElse(s, Nil).sortBy(_.pairId)
        val bytes = encodeLines(rs.map(write(_)))
        Files.write(staging.resolve(fileFor(s)), bytes)
        afterStagedFile(fileFor(s))
        ManifestEntry(fileFor(s), rs.size.toLong, sha256Hex(bytes))
      }
      val queue = reviewQueue(records, reviewLimit)
      Files.write(staging.resolve(ReviewQueueFile), encodeLines(queue.map(write(_))))
      afterStagedFile(ReviewQueueFile)
      val manifest = DecisionsManifest(
        ManifestSchemaVersion,
        runId,
        policyVersion,
        Features.SpecVersion,
        Resolver.RecordSchemaVersion,
        entries
      )
      Files.write(
        staging.resolve(ManifestFileName),
        (write(manifest, indent = 2) + "\n").getBytes(UTF_8)
      )
      afterStagedFile(ManifestFileName)
      publish(staging, target)
      (entries.map(e => e.name.stripSuffix(".jsonl") -> e.recordCount.toInt) :+
        ("review-queue" -> queue.size)).toMap
    catch
      case e: Throwable =>
        Try(deleteOwned(staging))
        throw e

  private def checkReplaceable(target: Path): Unit =
    if Files.exists(target, LinkOption.NOFOLLOW_LINKS) then
      if !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS) then
        throw new IOException(
          s"outDir $target exists and is not a directory; refusing to replace it"
        )
      val foreign = Using.resource(Files.list(target))(_.iterator.asScala.toList).filterNot(p =>
        OwnedFiles.contains(p.getFileName.toString) &&
          Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)
      )
      if foreign.nonEmpty then
        throw new IOException(
          s"outDir $target holds ${foreign.map(_.getFileName).mkString(", ")}, which the ER writer " +
            "does not own; refusing to replace it (choose an empty or new output directory)"
        )

  private def publish(staging: Path, target: Path): Unit =
    if !Files.exists(target, LinkOption.NOFOLLOW_LINKS) then
      Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
    else
      val replaced = target.resolveSibling(s".${target.getFileName}.replaced-${UUID.randomUUID}")
      Files.move(target, replaced, StandardCopyOption.ATOMIC_MOVE)
      try Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
      catch
        case e: Throwable =>
          Try(Files.move(replaced, target, StandardCopyOption.ATOMIC_MOVE))
          throw e
      deleteOwned(replaced)

  /** Deletes only [[OwnedFiles]] in `dir`, then `dir` if it is empty. Never recursive. */
  private def deleteOwned(dir: Path): Unit =
    OwnedFiles.foreach(n => Files.deleteIfExists(dir.resolve(n)))
    Try(Files.deleteIfExists(dir))
    ()

  // ---- Reader and verifier -----------------------------------------------------------------------

  /** Reads and checks the manifest of `dir` (header rules H1-H5 of [[verify]] only). */
  def readManifest(dir: Path): Either[String, DecisionsManifest] =
    val p = dir.resolve(ManifestFileName)
    if !Files.isRegularFile(p) then
      Left(
        s"$ManifestFileName missing in $dir: the ER output is incomplete or was not written by " +
          "Outputs.writeRun"
      )
    else Try(Files.readAllBytes(p)).toEither.left.map(_.toString).flatMap(parseManifest)

  /** Parses manifest bytes and checks the header rules H1-H5 of [[verify]]. */
  def parseManifest(bytes: Array[Byte]): Either[String, DecisionsManifest] =
    Try(read[DecisionsManifest](new String(bytes, UTF_8))).toEither.left
      .map(e => s"$ManifestFileName unreadable: ${e.getMessage}")
      .flatMap { m =>
        val names = m.files.map(_.name)
        val bad   = List(
          Option.when(m.manifestSchemaVersion != ManifestSchemaVersion)(
            s"manifestSchemaVersion ${m.manifestSchemaVersion} is not $ManifestSchemaVersion"
          ),
          Option.when(!IdFormat.isValid(IdKind.Run, m.pipelineRunId))(
            s"pipelineRunId '${m.pipelineRunId}' is not a run:v1 ID"
          ),
          Option.when(m.policyVersion.trim.isEmpty || m.featuresVersion.trim.isEmpty)(
            "policyVersion and featuresVersion must not be blank"
          ),
          Option.when(m.schemaVersion != Resolver.RecordSchemaVersion)(
            s"schemaVersion ${m.schemaVersion} is not ${Resolver.RecordSchemaVersion}"
          ),
          Option.when(names.sorted != DecisionFiles.sorted)(
            s"files must be exactly ${DecisionFiles.mkString(", ")} once each, got ${names.mkString(", ")}"
          ),
          m.files.collectFirst {
            case f if f.recordCount < 0 || Sha256Hex.findFirstIn(f.sha256).isEmpty =>
              s"${f.name}: recordCount must be >= 0 and sha256 64 lower-case hex digits"
          }
        ).flatten
        if bad.isEmpty then Right(m) else Left(s"$ManifestFileName invalid: ${bad.mkString("; ")}")
      }

  /**
   * Verifies a decision directory against its manifest; Right(manifest) only when every rule holds.
   *
   * Header (from [[parseManifest]]): H1 `manifestSchemaVersion == ManifestSchemaVersion` (1); H2
   * `pipelineRunId` is a valid `run:v1` ID; H3 `policyVersion` and `featuresVersion` non-blank; H4
   * `schemaVersion == Resolver.RecordSchemaVersion` (2); H5 `files` names exactly the four
   * [[DecisionFiles]], each once, with `recordCount >= 0` and a 64-digit lower-case hex `sha256`.
   *
   * Each file: V1 it exists; V2 the SHA-256 of its exact bytes equals `sha256` (so a 0-byte file is
   * "no records" only when the manifest says so); V3 its number of non-blank lines equals
   * `recordCount`; V4 every non-blank line parses as a [[ResolutionRecord]] whose `status` is the
   * file's status, and whose `provenance.pipelineRunId`, `policyVersion`, `featureSpecVersion`, and
   * `schemaVersion` equal the manifest's `pipelineRunId`, `policyVersion`, `featuresVersion`, and
   * `schemaVersion`.
   *
   * Left lists the problems (at most 50). The review queue is not covered (the identity closure
   * does not read it).
   */
  def verify(dir: Path): Either[List[String], DecisionsManifest] =
    readManifest(dir).left.map(List(_)).flatMap(m =>
      verifyAgainst(
        m,
        name =>
          val p = dir.resolve(name)
          Option.when(Files.isRegularFile(p))(Files.newInputStream(p))
      )
    )

  /**
   * [[verify]] for any storage: `manifestBytes` are the bytes of [[ManifestFileName]] and `open`
   * opens a decision file by name (None when absent). The streams are read once and closed. Spark
   * can pass a Hadoop FileSystem `open` (an InputStream) for HDFS or S3 without this module
   * depending on Hadoop. Files are streamed, never held in memory.
   */
  def verifyWith(
      manifestBytes: Array[Byte],
      open: String => Option[InputStream]
  ): Either[List[String], DecisionsManifest] =
    parseManifest(manifestBytes).left.map(List(_)).flatMap(verifyAgainst(_, open))

  private val MaxProblems = 50

  private def verifyAgainst(
      m: DecisionsManifest,
      open: String => Option[InputStream]
  ): Either[List[String], DecisionsManifest] =
    val problems                 = List.newBuilder[String]
    var count                    = 0
    def problem(s: String): Unit =
      if count < MaxProblems then problems += s
      count += 1
    m.files.foreach { e =>
      val status = ResolutionStatus.values.find(s => fileFor(s) == e.name).get // checked by H5
      Try(open(e.name)).toEither match
        case Left(ex)        => problem(s"${e.name}: cannot open: $ex")
        case Right(None)     => problem(s"${e.name}: missing")
        case Right(Some(in)) =>
          Try(Using.resource(in)(verifyFile(m, e, status, _, problem))).failed
            .foreach(ex => problem(s"${e.name}: read failed: $ex"))
    }
    if count == 0 then Right(m)
    else
      Left(problems.result() ++
        Option.when(count > MaxProblems)(s"... ${count - MaxProblems} more"))

  private def verifyFile(
      m: DecisionsManifest,
      e: ManifestEntry,
      status: ResolutionStatus,
      in: InputStream,
      problem: String => Unit
  ): Unit =
    val md     = MessageDigest.getInstance("SHA-256")
    val reader = new BufferedReader(new InputStreamReader(new DigestInputStream(in, md), UTF_8))
    var n      = 0L
    var lineNo = 0
    var line   = reader.readLine()
    while line != null do
      lineNo += 1
      if line.trim.nonEmpty then
        n += 1
        Try(read[ResolutionRecord](line)).toEither match
          case Left(ex) => problem(s"${e.name} line $lineNo: unparseable: ${ex.getMessage}")
          case Right(r) =>
            val diffs = List(
              Option.when(r.status != status)(s"status ${r.status.wire}"),
              Option.when(r.provenance.pipelineRunId != m.pipelineRunId)(
                s"pipelineRunId ${r.provenance.pipelineRunId}"
              ),
              Option.when(r.policyVersion != m.policyVersion)(s"policyVersion ${r.policyVersion}"),
              Option.when(r.featureSpecVersion != m.featuresVersion)(
                s"featureSpecVersion ${r.featureSpecVersion}"
              ),
              Option.when(r.schemaVersion != m.schemaVersion)(s"schemaVersion ${r.schemaVersion}")
            ).flatten
            if diffs.nonEmpty then
              problem(
                s"${e.name} line $lineNo (${r.pairId}): not this run: ${diffs.mkString(", ")}"
              )
      line = reader.readLine()
    val sha = hex(md.digest())
    if sha != e.sha256 then problem(s"${e.name}: sha256 $sha, manifest ${e.sha256}")
    if n != e.recordCount then problem(s"${e.name}: $n records, manifest ${e.recordCount}")

/** One decision file in the [[DecisionsManifest]]. */
final case class ManifestEntry(name: String, recordCount: Long, sha256: String) derives ReadWriter

/**
 * Completion manifest of one ER run (`_DECISIONS_MANIFEST.json`, [[Outputs.ManifestFileName]]),
 * written last by [[Outputs.writeRun]]. Verification rules: [[Outputs.verify]].
 */
final case class DecisionsManifest(
    manifestSchemaVersion: Int,
    pipelineRunId: String,
    policyVersion: String,
    featuresVersion: String,
    schemaVersion: Int,
    files: List[ManifestEntry]
) derives ReadWriter
