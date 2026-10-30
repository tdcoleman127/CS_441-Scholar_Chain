package edu.uic.cs441.scholarchain.hadoop

import edu.uic.cs441.scholarchain.core.model.{ExternalId, Provenance, SourceType}
import upickle.default.ReadWriter

// Hadoop-local records for Jobs 3, 4, and 5. Raw candidate generation only: no scores, no
// thresholds, no match/merge decisions (match policy belongs to entity-resolution-engineer).

/** One attestation of an exact identifier key. `memberId` is a work ID or a mention ID. */
final case class IdMember(memberId: String, workId: String, provenance: Provenance)
    derives ReadWriter

/**
 * Job 3 output: every distinct record carrying one exact identifier key (`doi:10.x/y`,
 * `orcid:0000-...`). `scope` is `work` or `mention`. `conflict` is true when one source dataset
 * attaches the key to two or more distinct work IDs (work scope only). `attestations` counts input
 * attestations including duplicates.
 */
final case class ExactIdGroup(
    key: String,
    scope: String,
    members: List[IdMember],
    attestations: Int,
    conflict: Boolean
) derives ReadWriter

/**
 * Job 4 blocking member. `label` is the normalized name (mention) or normalized title (work);
 * `context` is the other authors' normalized names (mention) or the authors' surnames (work);
 * `exactKeys` are the work's exact identifier keys.
 */
final case class BlockMember(
    scope: String,
    memberId: String,
    workId: String,
    position: Option[Int],
    label: String,
    orcid: Option[String],
    year: Option[Int],
    venueId: Option[String],
    context: List[String],
    exactKeys: List[String],
    provenance: Provenance
) derives ReadWriter

/** A raw, deterministic feature. `None` means not computable (a side lacks the input). */
final case class RawFeature(name: String, value: Option[Double]) derives ReadWriter

/**
 * Job 5 output: one candidate pair inside one bounded block chunk, with raw features and both
 * sides' provenance. `memberLow < memberHigh` (String.compareTo). Not a decision.
 */
final case class MatchCandidateRecord(
    scope: String,
    blockKey: String,
    chunk: Int,
    memberLow: String,
    memberHigh: String,
    workLow: String,
    workHigh: String,
    features: List[RawFeature],
    provenanceLow: Provenance,
    provenanceHigh: Provenance
) derives ReadWriter

/**
 * Job 4 block report. Blocks larger than `maxBlockSize` are split, in member-ID order, into chunks
 * of at most `maxBlockSize`; candidates are formed only within a chunk, and
 * `crossChunkPairsSkipped` reports the pairs that splitting removed. Provenance: `pipelineRunId` is
 * the run that built the block (job configuration); `sourceDatasets` are the distinct source
 * datasets of the block's members, sorted by wire name.
 */
final case class BlockStat(
    blockKey: String,
    scope: String,
    size: Long,
    maxBlockSize: Int,
    oversize: Boolean,
    chunks: Int,
    candidatePairs: Long,
    crossChunkPairsSkipped: Long,
    pipelineRunId: String,
    sourceDatasets: List[SourceType]
) derives ReadWriter

object Blocking:

  /** `doi:10.x/y` style key of an external identifier. */
  def idKey(e: ExternalId): String = s"${e.namespace.wire}:${e.value}"

  /** The work's own source ID as an exact key (`work:openalex:W1` -> `openalex:W1`), unless sc1. */
  def ownKey(workId: String): Option[String] =
    workId.split(":", 3) match
      case Array("work", ns, rest) if ns != "sc1" => Some(s"$ns:$rest")
      case _                                      => None

  def workKeys(env: WorkEnvelope): List[String] =
    (ownKey(env.work.workId).toList ++ env.work.externalIds.map(idKey)).distinct.sorted

  private def tokens(s: String): Vector[String] = s.split(' ').toVector.filter(_.nonEmpty)

  /** Normalized title for blocking: markup stripped, then name normalization. */
  def normTitle(title: String): String =
    DblpRecordParser.normalizeName(SourceNormalizers.stripTags(title))

  /** Surname = last token containing a letter; initial = first letter of the first token. */
  def surname(normName: String): Option[String] = tokens(normName).findLast(_.exists(_.isLetter))

  /**
   * Mention block key `m|<surname>|<first initial>`; None when the name has no letters (counted
   * MISSING_KEY, never emitted).
   */
  def mentionKey(normName: String): Option[String] =
    surname(normName).map { s =>
      val first = tokens(normName).headOption.filter(_ != s).flatMap(_.find(_.isLetter))
      s"m|$s|${first.fold("")(_.toString)}"
    }

  /** Work block key `w|<first 4 title tokens>`; None for a title with no tokens. */
  def workKey(title: String): Option[String] =
    val t = tokens(normTitle(title))
    if t.isEmpty then None else Some("w|" + t.take(4).mkString(" "))

  /** Every blocking member and key of one work: the work itself and each of its mentions. */
  def members(env: WorkEnvelope): List[(Option[String], BlockMember)] =
    val w     = env.work
    val keys  = workKeys(env)
    val names = env.mentions.sortBy(_.authorPosition).map(_.normalizedName)
    val work  = workKey(w.title) -> BlockMember(
      "work",
      w.workId,
      w.workId,
      None,
      normTitle(w.title),
      None,
      w.year,
      w.venueId,
      names.flatMap(surname).distinct.sorted,
      keys,
      w.provenance
    )
    work :: env.mentions.sortBy(_.authorPosition).map { m =>
      mentionKey(m.normalizedName) -> BlockMember(
        "mention",
        m.mentionId,
        m.workId,
        Some(m.authorPosition),
        m.normalizedName,
        m.orcid,
        w.year,
        w.venueId,
        names.patch(m.authorPosition.min(names.size), Nil, 1).distinct.sorted,
        keys,
        m.provenance
      )
    }

  def jaccard(a: Iterable[String], b: Iterable[String]): Double =
    val x = a.toSet; val y = b.toSet
    if x.isEmpty && y.isEmpty then 0.0 else (x & y).size.toDouble / (x | y).size

  private def b(v: Boolean): Option[Double] = Some(if v then 1.0 else 0.0)

  private def both[A](x: Option[A], y: Option[A])(f: (A, A) => Double): Option[Double] =
    for a <- x; c <- y yield f(a, c)

  /** Raw features of one pair, in a fixed order. Deterministic; no weights or thresholds. */
  def features(x: BlockMember, y: BlockMember): List[RawFeature] =
    val common = List(
      RawFeature("labelExact", b(x.label == y.label)),
      RawFeature("labelTokenJaccard", Some(jaccard(tokens(x.label), tokens(y.label)))),
      RawFeature("sameWork", b(x.workId == y.workId)),
      RawFeature("sameSource", b(x.provenance.sourceDataset == y.provenance.sourceDataset)),
      RawFeature("yearGap", both(x.year, y.year)((a, c) => math.abs(a - c).toDouble)),
      RawFeature("sameVenue", both(x.venueId, y.venueId)((a, c) => if a == c then 1.0 else 0.0)),
      RawFeature("sharedExactKeys", Some((x.exactKeys.toSet & y.exactKeys.toSet).size.toDouble)),
      RawFeature("sharedContext", Some((x.context.toSet & y.context.toSet).size.toDouble)),
      RawFeature("contextJaccard", Some(jaccard(x.context, y.context)))
    )
    if x.scope == "mention" then
      common :+ RawFeature("orcidEqual", both(x.orcid, y.orcid)((a, c) => if a == c then 1 else 0))
    else common

  /** The candidate for an unordered pair, canonicalized by member ID. */
  def candidate(key: String, chunk: Int, x: BlockMember, y: BlockMember): MatchCandidateRecord =
    val (lo, hi) = if x.memberId.compareTo(y.memberId) <= 0 then (x, y) else (y, x)
    MatchCandidateRecord(
      lo.scope,
      key,
      chunk,
      lo.memberId,
      hi.memberId,
      lo.workId,
      hi.workId,
      features(lo, hi),
      lo.provenance,
      hi.provenance
    )

  /** Where one block's reduce call sends counters and named-output records. */
  trait Sink:
    def count(c: ScCounter, n: Long): Unit
    def emit(output: String, json: String): Unit

  /**
   * The whole Job 4+5 reduce step for one block, cluster-free. `members` must arrive in member-ID
   * order (the job's secondary sort guarantees it). At most `maxBlock` members are held at once:
   * the block is cut into consecutive chunks of `maxBlock`, and pairs are formed within a chunk.
   * Counters: BLOCKS += 1; OVERSIZE_BLOCK += 1 when size > maxBlock; CANDIDATES per pair; DUPLICATE
   * per repeated member ID (kept once). Emits `candidates` and one `blockstats` record.
   */
  def processBlock(
      key: String,
      members: Iterator[BlockMember],
      maxBlock: Int,
      runId: String,
      sink: Sink
  ): BlockStat =
    require(maxBlock >= 2, "maxBlock must be at least 2")
    val buf           = scala.collection.mutable.ArrayBuffer.empty[BlockMember]
    var size, pairs   = 0L
    var chunks        = 0
    var scope         = ""
    var last          = ""
    val sources       = scala.collection.mutable.Set.empty[SourceType] // bounded by the enum
    def flush(): Unit =
      if buf.nonEmpty then
        for
          i <- buf.indices
          j <- (i + 1) until buf.size
        do
          sink.emit("candidates", upickle.default.write(candidate(key, chunks, buf(i), buf(j))))
          pairs += 1
        sink.count(ScCounter.CANDIDATES, buf.size.toLong * (buf.size - 1) / 2)
        chunks += 1
        buf.clear()
    members.foreach { m =>
      if m.memberId == last then sink.count(ScCounter.DUPLICATE, 1)
      else
        last = m.memberId; scope = m.scope; sources += m.provenance.sourceDataset
        size += 1
        buf += m
        if buf.size == maxBlock then flush()
    }
    flush()
    val oversize = size > maxBlock
    sink.count(ScCounter.BLOCKS, 1)
    if oversize then sink.count(ScCounter.OVERSIZE_BLOCK, 1)
    val stat = BlockStat(
      key,
      scope,
      size,
      maxBlock,
      oversize,
      chunks,
      pairs,
      (if size < 2 then 0L else size * (size - 1) / 2) - pairs,
      runId,
      sources.toList.sortBy(_.wire)
    )
    sink.emit("blockstats", upickle.default.write(stat))
    stat
