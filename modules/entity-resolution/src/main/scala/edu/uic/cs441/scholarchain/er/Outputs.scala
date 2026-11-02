package edu.uic.cs441.scholarchain.er

import edu.uic.cs441.scholarchain.core.model.Provenance
import upickle.default.{write, ReadWriter}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

/**
 * One review-queue line (JSON Lines, `review-queue.jsonl`). The human labeler fills `label`
 * (`match`, `non-match`, or `unsure`), `labeler` (who reviewed it), and optionally `labelNote`, and
 * keeps every other field unchanged so the label stays traceable to the source records through
 * `provenanceLow` / `provenanceHigh`. The same format is the evaluator's input.
 */
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
    labelNote: Option[String]
) derives ReadWriter

object Outputs:

  val ReviewQueueFile: String = "review-queue.jsonl"

  def fileFor(s: ResolutionStatus): String = s"${s.wire}.jsonl"

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
      None
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

  private def writeLines(path: Path, lines: Seq[String]): Unit =
    Files.createDirectories(path.getParent)
    Files.write(path, lines.mkString("", "\n", if lines.isEmpty then "" else "\n").getBytes(UTF_8))

  /**
   * Writes `accepted.jsonl`, `rejected.jsonl`, `unresolved.jsonl`, `conflict.jsonl` (all four, even
   * when empty) and the review queue.
   */
  def writeAll(outDir: Path, records: Seq[ResolutionRecord], reviewLimit: Int): Map[String, Int] =
    val byStatus = records.groupBy(_.status)
    val counts   = ResolutionStatus.values.toList.map { s =>
      val rs = byStatus.getOrElse(s, Nil).sortBy(_.pairId)
      writeLines(outDir.resolve(fileFor(s)), rs.map(write(_)))
      s.wire -> rs.size
    }
    val queue = reviewQueue(records, reviewLimit)
    writeLines(outDir.resolve(ReviewQueueFile), queue.map(write(_)))
    (counts :+ ("review-queue" -> queue.size)).toMap
