package edu.uic.cs441.scholarchain.hadoop

import edu.uic.cs441.scholarchain.core.model.{AuthorMention, Provenance, WorkRecord}
import upickle.default.ReadWriter

/**
 * Versioned reject record, reject schema 2 (written to the `rejects` named output under
 * `rejects/v2/`). v2 adds `inputFile`; v1 had no file and its offsets were ambiguous with several
 * input files.
 *
 * @param inputFile
 *   base name of the input file the record came from (`FileSplit.getPath.getName`, for example
 *   `dblp.xml.gz`), not the full URI, so the bytes do not depend on where the input is stored.
 * @param inputOffset
 *   byte offset of the record's opening tag within `inputFile`. Offsets are per file and restart at
 *   0 in each file. For compressed input (one split per file) it is the offset in the decompressed
 *   stream, not in the compressed file.
 */
final case class RejectRecord(
    rejectSchemaVersion: Int,
    reason: String,
    sourceDataset: String,
    sourceRecordId: Option[String],
    snapshotId: String,
    pipelineRunId: String,
    inputFile: String,
    inputOffset: Long,
    detail: String
) derives ReadWriter

/** Job 1 -> Job 6 hand-off: one validated work with its ordered mentions. */
final case class WorkEnvelope(recordType: String, work: WorkRecord, mentions: List[AuthorMention])
    derives ReadWriter

/** One retained work's team: ordered mention IDs and names (pre-entity-resolution). */
final case class TeamRecord(
    workId: String,
    year: Option[Int],
    teamSize: Int,
    oversize: Boolean,
    mentionIds: List[String],
    normalizedNames: List[String],
    provenance: Provenance
) derives ReadWriter

/** Author-paper record keyed by (mentionId, workId). */
final case class AuthorPaperRecord(
    mentionId: String,
    workId: String,
    authorPosition: Int,
    normalizedName: String,
    year: Option[Int],
    provenance: Provenance
) derives ReadWriter

/**
 * Unordered candidate coauthor pair at mention level. Canonical order uses the frozen
 * ResearcherPair rule (`String.compareTo`, `low < high`) applied to mention IDs, because before
 * entity resolution there are no researcher IDs to give `ResearcherPair.of` (it rejects anything
 * that is not a `researcher:` ID). Positions are those of the low and high mentions.
 */
final case class MentionPairCandidate(
    mentionLow: String,
    mentionHigh: String,
    positionLow: Int,
    positionHigh: Int,
    nameLow: String,
    nameHigh: String,
    workId: String,
    year: Option[Int],
    provenance: Provenance
) derives ReadWriter

object Teams:

  /** n * (n - 1) / 2 as a Long, so large teams cannot overflow. */
  def pairCount(n: Int): Long = if n < 2 then 0L else n.toLong * (n - 1) / 2

  /** All unordered pairs of `mentions` (any order in, author order out), canonicalized. */
  def pairs(mentions: List[AuthorMention]): Iterator[MentionPairCandidate] =
    val ordered = mentions.sortBy(_.authorPosition).toVector
    for
      i <- ordered.indices.iterator
      j <- (i + 1 until ordered.size).iterator
    yield
      val (a, b) =
        val x = ordered(i); val y = ordered(j)
        if x.mentionId.compareTo(y.mentionId) <= 0 then (x, y) else (y, x)
      MentionPairCandidate(
        a.mentionId,
        b.mentionId,
        a.authorPosition,
        b.authorPosition,
        a.normalizedName,
        b.normalizedName,
        a.workId,
        None,
        a.provenance
      )

  def team(env: WorkEnvelope, oversizeThreshold: Int): TeamRecord =
    val ms = env.mentions.sortBy(_.authorPosition)
    TeamRecord(
      env.work.workId,
      env.work.year,
      ms.size,
      ms.size > oversizeThreshold,
      ms.map(_.mentionId),
      ms.map(_.normalizedName),
      env.work.provenance
    )

  def authorPapers(env: WorkEnvelope): List[AuthorPaperRecord] =
    env.mentions
      .sortBy(_.authorPosition)
      .map(m =>
        AuthorPaperRecord(
          m.mentionId,
          m.workId,
          m.authorPosition,
          m.normalizedName,
          env.work.year,
          m.provenance
        )
      )

  def pairsFor(env: WorkEnvelope): Iterator[MentionPairCandidate] =
    pairs(env.mentions).map(_.copy(year = env.work.year))
