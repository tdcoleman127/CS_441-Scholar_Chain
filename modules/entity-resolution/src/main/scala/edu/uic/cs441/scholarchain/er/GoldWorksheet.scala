package edu.uic.cs441.scholarchain.er

import edu.uic.cs441.scholarchain.core.ids.CanonicalHash
import edu.uic.cs441.scholarchain.core.model.{IdNamespace, Provenance, SourceType}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

/**
 * Minimal RFC 4180 CSV reader and writer for the gold-set worksheet: comma separator, `"` quoting
 * with `""` escapes, quoted fields may hold commas and newlines, LF or CRLF row ends, and a leading
 * UTF-8 byte-order mark (spreadsheets add one) is ignored.
 */
object Csv:

  /** Rows with the 1-based physical line on which each row starts. Blank rows are dropped. */
  def parse(text: String): Vector[(Int, Vector[String])] =
    val s                = text.stripPrefix("﻿")
    val rows             = Vector.newBuilder[(Int, Vector[String])]
    var row              = Vector.newBuilder[String]
    val field            = new StringBuilder
    var quoted, any      = false
    var line, start      = 1
    var i                = 0
    def endField(): Unit = { row += field.toString; field.clear(); any = true }
    def endRow(): Unit   =
      endField()
      val r = row.result()
      if !(r.size == 1 && r.head.isEmpty) then rows += start -> r
      row = Vector.newBuilder[String]; any = false
    while i < s.length do
      val c = s(i)
      if quoted then
        if c == '"' && i + 1 < s.length && s(i + 1) == '"' then { field += '"'; i += 1 }
        else if c == '"' then quoted = false
        else { if c == '\n' then line += 1; field += c }
      else
        c match
          case '"'  => quoted = true
          case ','  => endField()
          case '\r' => ()
          case '\n' => endRow(); line += 1; start = line
          case _    => field += c
      i += 1
    if field.nonEmpty || any then endRow()
    rows.result()

  def quote(v: String): String =
    if v.exists(c => c == ',' || c == '"' || c == '\n' || c == '\r') then
      "\"" + v.replace("\"", "\"\"") + "\""
    else v

  def render(rows: Seq[Seq[String]]): String =
    rows.map(_.map(quote).mkString(",")).mkString("", "\n", "\n")

/**
 * One researcher mention from the inventory (`gold/v1/mentions-v1.csv`), traceable to its source.
 */
final case class GoldMention(
    mentionId: String,
    sourceDataset: String,
    sourceRecordId: String,
    position: String,
    role: String,
    name: String,
    title: String,
    year: String,
    venue: String,
    coauthors: String,
    researcherIds: String,
    workIds: String,
    context: String
):
  /** Two author or editor mentions on one source record are positions on one work. */
  def workKey: String =
    if role == "author" || role == "editor" then s"$sourceDataset:$sourceRecordId" else mentionId

/**
 * Gold-set labeling worksheet: deterministic pair selection from a mention inventory, the worksheet
 * the human fills, and the loader that turns a filled worksheet into evaluator input.
 *
 * The worksheet never carries a score, decision, path, or suggested label: the loader recomputes
 * the decision of the policy under evaluation from the mention attributes at load time.
 */
object GoldWorksheet:

  val SelectionVersion: String = "er-gold-selection-v1"
  val Seed: String             = "scholarchain-er-gold-v1"

  val MentionFields: List[String] = List(
    "mention_id",
    "source_dataset",
    "source_record_id",
    "position",
    "role",
    "name",
    "title",
    "year",
    "venue",
    "coauthors",
    "researcher_ids",
    "work_ids",
    "context"
  )

  /** Per-side worksheet fields (the inventory fields without `position`). */
  val SideFields: List[String] = MentionFields.filterNot(_ == "position")

  val LabelValues: List[String] = List("same", "different", "unsure")

  val Header: List[String] =
    List("pair_id", "stratum") ++ SideFields.map("a_" + _) ++ SideFields.map("b_" + _) ++
      List("label", "labeler", "notes")

  /**
   * Strata, in rule order (a pair takes the first that applies), with the number of pairs taken
   * from each. Names describe an observable property of the pair, not an expected answer.
   */
  val Strata: List[(String, Int)] = List(
    "shared-researcher-id"        -> 7,
    "same-record-other-position"  -> 5,
    "identical-name-other-record" -> 9,
    "homonym-number-suffix"       -> 7,
    "spelling-variant"            -> 6,
    "name-order"                  -> 3,
    "initials-or-middle-name"     -> 6,
    "shared-name-token"           -> 3,
    "different-name"              -> 4
  )

  private def splitList(s: String): List[String] =
    s.split(';').toList.map(_.trim).filter(_.nonEmpty)

  /** `ns:value; ns:value` to raw IDs by namespace. Unknown namespaces are an error. */
  def parseIds(s: String): Either[String, Map[IdNamespace, List[String]]] =
    val parsed = splitList(s).map { tok =>
      tok.indexOf(':') match
        case i if i > 0 =>
          IdNamespace.values.find(_.wire == tok.take(i).trim.toLowerCase)
            .toRight(s"unknown identifier namespace in '$tok'")
            .map(_ -> tok.drop(i + 1).trim)
        case _ => Left(s"identifier '$tok' is not ns:value")
    }
    parsed.collectFirst { case Left(e) => e } match
      case Some(e) => Left(e)
      case None    =>
        Right(parsed.collect { case Right(p) => p }.groupMap(_._1)(_._2))

  private def researcherIds(m: GoldMention): Map[IdNamespace, Set[String]] =
    Identifiers.normalizeAll(parseIds(m.researcherIds).getOrElse(Map.empty))

  /** The stratum of an unordered mention pair (rule order of [[Strata]]). */
  def stratum(a: GoldMention, b: GoldMention): String =
    val pa                   = Normalize.personName(a.name)
    val pb                   = Normalize.personName(b.name)
    val ta                   = Normalize.tokens(a.name)
    val tb                   = Normalize.tokens(b.name)
    val canonEq              = pa.canonical == pb.canonical
    val squashEq             = pa.canonical.replace(" ", "") == pb.canonical.replace(" ", "")
    val ids                  = Identifiers.compare("mention", researcherIds(a), researcherIds(b))
    def long(t: Seq[String]) = t.filter(x => x.length > 1 && !x.forall(_.isDigit)).toSet
    if ids.hasAgreement then "shared-researcher-id"
    else if a.workKey == b.workKey then "same-record-other-position"
    else if a.name.trim == b.name.trim then "identical-name-other-record"
    else if canonEq && ta != tb && (ta.lastOption ++ tb.lastOption).exists(_.forall(_.isDigit)) then
      "homonym-number-suffix"
    else if canonEq || squashEq then "spelling-variant"
    else if ta.sorted == tb.sorted then "name-order"
    else if Features.initialsCompatibility(a.name, b.name).contains(1.0) then
      "initials-or-middle-name"
    else if (long(ta) & long(tb)).nonEmpty then "shared-name-token"
    else "different-name"

  private def pairKey(a: GoldMention, b: GoldMention): String = s"${a.mentionId}|${b.mentionId}"

  private def rank(key: String): String = CanonicalHash.hashFields(Seq(Seed, key))

  final case class Selection(
      pairs: List[(String, GoldMention, GoldMention)],
      available: Map[String, Int]
  )

  /**
   * Every unordered pair of distinct mentions, sides ordered by mention ID, is put in its stratum.
   * Within a stratum, pairs whose canonical names are equal come first, then pairs are ordered by
   * SHA-256 of (seed, pair key); the first `quota` are taken. The output rows are ordered by the
   * same hash, so strata are interleaved.
   */
  def select(mentions: Seq[GoldMention]): Selection =
    val sorted = mentions.sortBy(_.mentionId).toVector
    val all    =
      for
        i <- sorted.indices
        j <- (i + 1) until sorted.size
      yield (stratum(sorted(i), sorted(j)), sorted(i), sorted(j))
    val byStratum = all.groupBy(_._1)
    val chosen    = Strata.flatMap { (s, quota) =>
      byStratum.getOrElse(s, Vector.empty).sortBy { (_, a, b) =>
        val eq = Normalize.personName(a.name).canonical == Normalize.personName(b.name).canonical
        (if eq then 0 else 1, rank(pairKey(a, b)))
      }.take(quota)
    }
    Selection(
      chosen.sortBy((_, a, b) => rank(pairKey(a, b))).toList,
      Strata.map((s, _) => s -> byStratum.get(s).fold(0)(_.size)).toMap
    )

  /**
   * SHA-256 of the inventory text with CRLF folded to LF, so a Windows checkout hashes the same.
   */
  def inventorySha(bytes: Array[Byte]): String =
    CanonicalHash.sha256Hex(new String(bytes, UTF_8).replace("\r\n", "\n").getBytes(UTF_8))

  def readInventory(text: String): Either[String, List[GoldMention]] =
    val rows = Csv.parse(text)
    rows.headOption match
      case Some((_, h)) if h == MentionFields.toVector =>
        val bad = rows.tail.find(_._2.size != MentionFields.size)
        bad match
          case Some((ln, r)) => Left(s"inventory line $ln: ${r.size} fields")
          case None          =>
            Right(rows.tail.map { (_, r) =>
              GoldMention(
                r(0),
                r(1),
                r(2),
                r(3),
                r(4),
                r(5),
                r(6),
                r(7),
                r(8),
                r(9),
                r(10),
                r(11),
                r(12)
              )
            }.toList)
      case _ => Left(s"inventory header must be ${MentionFields.mkString(",")}")

  private def side(m: GoldMention): List[String] =
    List(
      m.mentionId,
      m.sourceDataset,
      m.sourceRecordId,
      m.role,
      m.name,
      m.title,
      m.year,
      m.venue,
      m.coauthors,
      m.researcherIds,
      m.workIds,
      m.context
    )

  /** Worksheet rows: header, then one row per pair with empty label, labeler, and notes. */
  def worksheet(sel: Selection): List[List[String]] =
    Header :: sel.pairs.zipWithIndex.map { case ((s, a, b), i) =>
      List(f"P${i + 1}%03d", s) ++ side(a) ++ side(b) ++ List("", "", "")
    }

  def manifest(inventorySha: String, inventoryCount: Int, sel: Selection): String =
    val counts = sel.pairs.groupMapReduce(_._1)(_ => 1)(_ + _)
    val lines  = List(
      s"selection-version: $SelectionVersion",
      s"seed: $Seed",
      s"inventory-sha256: $inventorySha",
      s"inventory-mentions: $inventoryCount",
      s"candidate-pairs: ${sel.available.values.sum}",
      s"selected-pairs: ${sel.pairs.size}",
      "rule: every unordered pair of distinct inventory mentions; stratum = first matching rule in",
      "  the order below; within a stratum, equal canonical names first, then SHA-256(seed, pair",
      "  key) ascending; take the quota; rows ordered by the same hash.",
      "strata (name: selected / quota / available):"
    ) ++ Strata.map((s, q) => s"  $s: ${counts.getOrElse(s, 0)} / $q / ${sel.available(s)}")
    lines.mkString("", "\n", "\n")

  // ---- Loader for a filled worksheet -------------------------------------------------------------

  /**
   * A loaded worksheet: evaluator input plus the stratum of each labeled pair and the file hash.
   */
  final case class Loaded(load: LoadResult, strata: Map[String, String], sha256: String)

  private def view(get: String => String, pipelineRunId: String): Either[String, EntityView] =
    for
      ds <- SourceType.values.find(_.wire == get("source_dataset"))
        .toRight(s"unknown source_dataset '${get("source_dataset")}'")
      year <- get("year") match
        case "" => Right(None)
        case y  => y.toIntOption.map(Some(_)).toRight(s"year '$y' is not an integer")
      rIds <- parseIds(get("researcher_ids"))
      wIds <- parseIds(get("work_ids"))
      mid = get("mention_id")
      rec = get("source_record_id")
      _ <- Either.cond(mid.nonEmpty && rec.nonEmpty, (), "mention_id and source_record_id required")
    yield
      val m = GoldMention(
        mid,
        ds.wire,
        rec,
        "",
        get("role"),
        get("name"),
        get("title"),
        "",
        "",
        "",
        "",
        "",
        ""
      )
      EntityView(
        memberId = mid,
        workId = m.workKey,
        name = get("name"),
        title = get("title"),
        year = year,
        coauthors = splitList(get("coauthors")),
        ids = (rIds.keySet ++ wIds.keySet)
          .map(ns => ns -> (rIds.getOrElse(ns, Nil) ++ wIds.getOrElse(ns, Nil))).toMap,
        provenance = Provenance(ds, rec, pipelineRunId)
      )

  /**
   * Reads a filled worksheet and decides every row with `policy` (the decision is recomputed here;
   * the worksheet has none). A row counts when `label` is `same` or `different` (any case) and a
   * labeler is known: the row's `labeler` cell, else `defaultLabeler`. `unsure` and blank labels
   * are counted and skipped; a label without any labeler is counted as untraced and skipped;
   * malformed rows and unknown labels are reported, never silently dropped.
   */
  def load(path: Path, policy: ScoringPolicy, defaultLabeler: Option[String]): Loaded =
    val bytes = Files.readAllBytes(path)
    val sha   = CanonicalHash.sha256Hex(bytes)
    val runId = s"run:v1:$sha"
    val rows  = Csv.parse(new String(bytes, UTF_8))
    val empty = LoadResult(Nil, 0, 0, Nil)
    rows.headOption match
      case None => Loaded(empty.copy(rejectedLines = List("worksheet is empty")), Map.empty, sha)
      case Some((_, header)) =>
        val idx     = header.map(_.trim).zipWithIndex.toMap
        val missing = Header.filterNot(idx.contains)
        if missing.nonEmpty then
          Loaded(
            empty.copy(rejectedLines = List(s"missing columns: ${missing.mkString(", ")}")),
            Map.empty,
            sha
          )
        else
          rows.tail.foldLeft(Loaded(empty, Map.empty, sha)) { case (acc, (ln, r)) =>
            def cell(c: String)     = r.lift(idx(c)).fold("")(_.trim)
            val l                   = acc.load
            def reject(msg: String) =
              acc.copy(load = l.copy(rejectedLines = l.rejectedLines :+ s"line $ln: $msg"))
            val labeler = Option(cell("labeler")).filter(_.nonEmpty)
              .orElse(defaultLabeler.map(_.trim).filter(_.nonEmpty))
            cell("label").toLowerCase match
              case ""       => acc.copy(load = l.copy(skippedUnlabeled = l.skippedUnlabeled + 1))
              case "unsure" => acc.copy(load = l.copy(skippedUnsure = l.skippedUnsure + 1))
              case lab @ ("same" | "different") =>
                if labeler.isEmpty then
                  acc.copy(load = l.copy(skippedUntraced = l.skippedUntraced + 1))
                else
                  val both =
                    for
                      a <- view(f => cell("a_" + f), runId).left.map("side a: " + _)
                      b <- view(f => cell("b_" + f), runId).left.map("side b: " + _)
                    yield (a, b)
                  both match
                    case Left(e)       => reject(e)
                    case Right((a, b)) =>
                      val in   = PairInput.fromViews("mention", s"gold:${cell("pair_id")}", a, b)
                      val rec  = Resolver.decide(policy, in, runId)
                      val item = Outputs.toReviewItem(rec).copy(
                        label = Some(if lab == "same" then "match" else "non-match"),
                        labeler = labeler,
                        labelNote = Option(cell("notes")).filter(_.nonEmpty)
                      )
                      acc.copy(
                        load = l.copy(labeled = l.labeled :+ LabeledPair(item, lab == "same")),
                        strata = acc.strata + (item.pairId -> cell("stratum"))
                      )
              case other =>
                reject(s"unknown label '$other' (allowed: ${LabelValues.mkString(", ")})")
          }

  /** Confusion per selection stratum, for the strata present among labeled pairs. */
  def byStratum(loaded: Loaded): List[(String, Confusion)] =
    loaded.load.labeled
      .groupMapReduce(lp => loaded.strata.getOrElse(lp.item.pairId, ""))(lp =>
        Confusion.of(lp.item.status, lp.isMatch)
      )(_ + _)
      .toList
      .sortBy(_._1)

  def report(policy: ScoringPolicy, loaded: Loaded): String =
    val e  = Evaluator.evaluate(policy, loaded.load)
    val sb = new StringBuilder
    sb.append(s"worksheet sha256=${loaded.sha256}\n")
    sb.append(Evaluator.report(e))
    sb.append("Confusion by selection stratum:\n")
    byStratum(loaded).foreach((s, c) =>
      sb.append(Evaluator.row(if s.isEmpty then "(none)" else s, c)).append('\n')
    )
    sb.toString

/**
 * `GoldWorksheetMain <mentions.csv> <worksheet.csv> <manifest.txt>`: builds the labeling worksheet
 * and its selection manifest from the mention inventory. Refuses to overwrite a worksheet that
 * already holds a label.
 */
object GoldWorksheetMain:
  def main(args: Array[String]): Unit =
    if args.length != 3 then
      System.err.println("usage: GoldWorksheetMain <mentions.csv> <worksheet.csv> <manifest.txt>")
      sys.exit(2)
    val inv   = Path.of(args(0)); val out = Path.of(args(1)); val man = Path.of(args(2))
    val bytes = Files.readAllBytes(inv)
    val ms    = GoldWorksheet.readInventory(new String(bytes, UTF_8))
      .fold(e => { System.err.println(e); sys.exit(2) }, identity)
    if Files.exists(out) then
      val rows = Csv.parse(Files.readString(out, UTF_8))
      val li   = rows.headOption.map(_._2.indexOf("label")).getOrElse(-1)
      if li >= 0 && rows.tail.exists(_._2.lift(li).exists(_.trim.nonEmpty)) then
        System.err.println(s"refusing to overwrite $out: it already contains labels"); sys.exit(2)
    val sel = GoldWorksheet.select(ms)
    Files.writeString(out, Csv.render(GoldWorksheet.worksheet(sel)), UTF_8)
    Files.writeString(
      man,
      GoldWorksheet.manifest(GoldWorksheet.inventorySha(bytes), ms.size, sel),
      UTF_8
    )
    println(s"mentions=${ms.size} pairs=${sel.pairs.size} worksheet=$out manifest=$man")

/**
 * `WorksheetEvalMain <worksheet.csv> [--labeler NAME] [--policy policy.conf]`: evaluates the
 * current (or given) policy against a filled labeling worksheet and prints the report.
 */
object WorksheetEvalMain:
  def main(args: Array[String]): Unit =
    val usage = "usage: WorksheetEvalMain <worksheet.csv> [--labeler NAME] [--policy policy.conf]"
    def opt(n: String) = args.indexOf(n) match
      case -1                       => None
      case i if i + 1 < args.length => Some(args(i + 1))
      case _                        => System.err.println(usage); sys.exit(2)
    if args.isEmpty || args(0).startsWith("--") then { System.err.println(usage); sys.exit(2) }
    val policy = opt("--policy").fold(ScoringPolicy.loadDefault())(p =>
      ScoringPolicy.loadFile(Path.of(p))
    ).fold(e => { System.err.println(e); sys.exit(2) }, identity)
    val loaded = GoldWorksheet.load(Path.of(args(0)), policy, opt("--labeler"))
    print(GoldWorksheet.report(policy, loaded))
