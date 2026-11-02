package edu.uic.cs441.scholarchain.er

import upickle.default.read

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * Confusion counts over human-labeled pairs. Accepted = predicted match, Rejected = predicted
 * non-match; unresolved and conflict pairs are undecided and kept in their own cells.
 */
final case class Confusion(
    tp: Int,
    fp: Int,
    tn: Int,
    fn: Int,
    undecidedPos: Int,
    undecidedNeg: Int
):
  def total: Int     = tp + fp + tn + fn + undecidedPos + undecidedNeg
  def decided: Int   = tp + fp + tn + fn
  def positives: Int = tp + fn + undecidedPos

  /** TP / (TP + FP), over accepted pairs. */
  def precision: Option[Double] = Evaluator.ratio(tp, tp + fp)

  /** TP / all labeled matches, so undecided matches count against recall. */
  def recall: Option[Double] = Evaluator.ratio(tp, positives)

  def f1: Option[Double] =
    for p <- precision; r <- recall if p + r > 0 yield 2 * p * r / (p + r)

  def coverage: Option[Double]       = Evaluator.ratio(decided, total)
  def unresolvedRate: Option[Double] = Evaluator.ratio(undecidedPos + undecidedNeg, total)

  def +(o: Confusion): Confusion =
    Confusion(
      tp + o.tp,
      fp + o.fp,
      tn + o.tn,
      fn + o.fn,
      undecidedPos + o.undecidedPos,
      undecidedNeg + o.undecidedNeg
    )

object Confusion:
  val Zero: Confusion = Confusion(0, 0, 0, 0, 0, 0)

  def of(status: ResolutionStatus, isMatch: Boolean): Confusion = (status, isMatch) match
    case (ResolutionStatus.Accepted, true)  => Zero.copy(tp = 1)
    case (ResolutionStatus.Accepted, false) => Zero.copy(fp = 1)
    case (ResolutionStatus.Rejected, false) => Zero.copy(tn = 1)
    case (ResolutionStatus.Rejected, true)  => Zero.copy(fn = 1)
    case (_, true)                          => Zero.copy(undecidedPos = 1)
    case (_, false)                         => Zero.copy(undecidedNeg = 1)

/** One labeled pair the evaluator uses: the system's decision plus a traceable human label. */
final case class LabeledPair(item: ReviewItem, isMatch: Boolean)

final case class LoadResult(
    labeled: List[LabeledPair],
    skippedUnlabeled: Int,
    skippedUnsure: Int,
    rejectedLines: List[String]
)

final case class SweepRow(accept: Double, reject: Double, overall: Confusion)

final case class Evaluation(
    policyVersion: String,
    byPath: List[(MatchPath, Confusion)],
    overall: Confusion,
    sweepAccept: List[SweepRow],
    sweepReject: List[SweepRow],
    load: LoadResult
)

object Evaluator:

  def ratio(n: Int, d: Int): Option[Double] = Option.when(d > 0)(n.toDouble / d)

  /** Wilson score interval (95%) for n successes out of d trials. */
  def wilson(n: Int, d: Int, z: Double = 1.959964): Option[(Double, Double)] =
    Option.when(d > 0) {
      val p      = n.toDouble / d
      val denom  = 1 + z * z / d
      val centre = (p + z * z / (2 * d)) / denom
      val half   = z * math.sqrt(p * (1 - p) / d + z * z / (4.0 * d * d)) / denom
      (math.max(0.0, centre - half), math.min(1.0, centre + half))
    }

  /**
   * Reads a labeled review-queue file. A line counts only when `label` is `match` or `non-match`
   * AND `labeler` is non-blank (traceable human review). `unsure` and unlabeled lines are counted
   * and skipped; malformed lines are reported, never silently dropped.
   */
  def load(path: Path): LoadResult =
    val lines = Files.readAllLines(path, UTF_8).asScala.toList.map(_.trim).filter(_.nonEmpty)
    lines.zipWithIndex.foldLeft(LoadResult(Nil, 0, 0, Nil)) { case (acc, (line, i)) =>
      Try(read[ReviewItem](line)).toEither match
        case Left(e) =>
          acc.copy(rejectedLines = acc.rejectedLines :+ s"line ${i + 1}: ${e.getMessage}")
        case Right(item) =>
          val traced = item.labeler.exists(_.trim.nonEmpty)
          item.label.map(_.trim) match
            case Some("match") if traced =>
              acc.copy(labeled = acc.labeled :+ LabeledPair(item, true))
            case Some("non-match") if traced =>
              acc.copy(labeled = acc.labeled :+ LabeledPair(item, false))
            case Some("unsure")        => acc.copy(skippedUnsure = acc.skippedUnsure + 1)
            case Some(other) if traced =>
              acc.copy(rejectedLines =
                acc.rejectedLines :+ s"line ${i + 1}: unknown label '$other'"
              )
            case _ => acc.copy(skippedUnlabeled = acc.skippedUnlabeled + 1)
    }

  /** Status under alternative thresholds: only probabilistic-path pairs with a score move. */
  def restatus(item: ReviewItem, accept: Double, reject: Double): ResolutionStatus =
    val probabilistic = item.path == MatchPath.Probabilistic ||
      item.path == MatchPath.LlmAdjudicated
    val scoreDecided = item.reasonCodes.exists(_.startsWith("score-band:")) ||
      item.reasonCodes.exists(_.startsWith("adjudicator-"))
    (probabilistic && scoreDecided, item.score) match
      case (true, Some(s)) => Resolver.band(s, accept, reject)
      case _               => item.status

  def evaluate(policy: ScoringPolicy, load: LoadResult): Evaluation =
    val cells  = load.labeled.map(lp => lp.item.path -> Confusion.of(lp.item.status, lp.isMatch))
    val byPath = MatchPath.values.toList.map(p =>
      p -> cells.filter(_._1 == p).map(_._2).foldLeft(Confusion.Zero)(_ + _)
    )
    val overall                     = byPath.map(_._2).foldLeft(Confusion.Zero)(_ + _)
    def sweep(a: Double, r: Double) = SweepRow(
      a,
      r,
      load.labeled.map(lp => Confusion.of(restatus(lp.item, a, r), lp.isMatch)).foldLeft(
        Confusion.Zero
      )(_ + _)
    )
    Evaluation(
      policy.policyVersion,
      byPath,
      overall,
      policy.sweepAccept.filter(_ >= policy.reject).map(a => sweep(a, policy.reject)),
      policy.sweepReject.filter(_ <= policy.accept).map(r => sweep(policy.accept, r)),
      load
    )

  private def fmt(o: Option[Double]): String          = o.fold("n/a")(v => f"$v%.3f")
  private def ci(o: Option[(Double, Double)]): String = o.fold("n/a")((a, b) => f"[$a%.3f, $b%.3f]")

  private def row(name: String, c: Confusion): String =
    f"$name%-16s n=${c.total}%4d TP=${c.tp}%3d FP=${c.fp}%3d TN=${c.tn}%3d FN=${c.fn}%3d " +
      f"U+=${c.undecidedPos}%3d U-=${c.undecidedNeg}%3d P=${fmt(c.precision)} ${ci(wilson(c.tp, c.tp + c.fp))} " +
      f"R=${fmt(c.recall)} ${ci(wilson(c.tp, c.positives))} F1=${fmt(c.f1)} " +
      f"coverage=${fmt(c.coverage)} unresolved=${fmt(c.unresolvedRate)}"

  def report(e: Evaluation): String =
    val sb = new StringBuilder
    sb.append(s"ER evaluation, policy ${e.policyVersion}, features ${Features.SpecVersion}\n")
    sb.append(s"labeled=${e.load.labeled.size} unlabeled=${e.load.skippedUnlabeled} " +
      s"unsure=${e.load.skippedUnsure} malformed=${e.load.rejectedLines.size}\n")
    e.load.rejectedLines.foreach(l => sb.append(s"  malformed $l\n"))
    sb.append(
      "Confusion by match path (U+/U- = unresolved or conflict, labeled match / non-match; " +
        "P and R with Wilson 95% intervals):\n"
    )
    e.byPath.foreach((p, c) => sb.append(row(p.wire, c)).append('\n'))
    sb.append(row("overall", e.overall)).append('\n')
    sb.append("Threshold sweep, accept varied (reject fixed):\n")
    e.sweepAccept.foreach(s =>
      sb.append(row(f"a=${s.accept}%.2f r=${s.reject}%.2f", s.overall)).append('\n')
    )
    sb.append("Threshold sweep, reject varied (accept fixed):\n")
    e.sweepReject.foreach(s =>
      sb.append(row(f"a=${s.accept}%.2f r=${s.reject}%.2f", s.overall)).append('\n')
    )
    sb.toString

/** `EvaluatorMain <labeled.jsonl> [policy.conf]`: prints the evaluation report. */
object EvaluatorMain:
  def main(args: Array[String]): Unit =
    if args.isEmpty then
      System.err.println("usage: EvaluatorMain <labeled.jsonl> [policy.conf]"); sys.exit(2)
    val policy =
      (if args.length > 1 then ScoringPolicy.loadFile(Path.of(args(1)))
       else ScoringPolicy.loadDefault()).fold(e => { System.err.println(e); sys.exit(2) }, identity)
    print(Evaluator.report(Evaluator.evaluate(policy, Evaluator.load(Path.of(args(0))))))

/**
 * `ResolveMain <candidates.jsonl> <outDir> <runId> [reviewLimit]`: decides Hadoop Job 5 candidates.
 */
object ResolveMain:
  def main(args: Array[String]): Unit =
    if args.length < 3 then
      System.err.println("usage: ResolveMain <candidates.jsonl> <outDir> <runId> [reviewLimit]");
      sys.exit(2)
    val policy =
      ScoringPolicy.loadDefault().fold(e => { System.err.println(e); sys.exit(2) }, identity)
    val limit  = if args.length > 3 then args(3).toInt else 500
    val lines  = Files.readAllLines(Path.of(args(0)), UTF_8).asScala.filter(_.trim.nonEmpty).toList
    val parsed = lines.map(HadoopCandidates.parseLine)
    val bad    = parsed.collect { case Left(e) => e }
    val recs   = parsed.collect { case Right(c) =>
      Resolver.decide(policy, HadoopCandidates.toPairInput(c), args(2))
    }
    val counts = Outputs.writeAll(Path.of(args(1)), recs, limit)
    println(s"policy=${policy.policyVersion} candidates=${lines.size} malformed=${bad.size} " +
      counts.toList.sorted.map((k, v) => s"$k=$v").mkString(" "))
    bad.take(10).foreach(e => System.err.println(e))
