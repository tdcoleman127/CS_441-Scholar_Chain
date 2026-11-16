package edu.uic.cs441.scholarchain.hadoop

import java.io.InputStream
import java.nio.charset.StandardCharsets
import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.{LongWritable, Text}
import org.apache.hadoop.mapreduce.lib.input.{FileInputFormat, FileSplit}
import org.apache.hadoop.mapreduce.{InputSplit, RecordReader, TaskAttemptContext}

/**
 * Splittable input format that yields whole DBLP record elements.
 *
 * Record boundary rule (iron law 1): a record belongs to the split in which its start tag
 * (`<article `, `<inproceedings `, ...) begins. The reader of that split reads past the split end
 * until the matching close tag, and every other reader skips it. So no record is split across
 * mappers and none is read twice. A record still open at end of file is emitted prefixed with
 * [[DblpXmlInputFormat.TruncatedMarker]] so the mapper rejects it instead of dropping it. A start
 * tag inside a CDATA section or an XML comment is not a record start (CO-4); see
 * [[DblpRecordScanner]] for the rule, including splits that begin inside such a section, where the
 * reader of the previous split keeps the start tags just after its split end (the hand-off rule).
 *
 * Key: byte offset of the record start tag. Value: the record text (DBLP dumps are ASCII bytes with
 * an ISO-8859-1 header, decoded as ISO-8859-1).
 */
class DblpXmlInputFormat extends FileInputFormat[LongWritable, Text]:
  override def createRecordReader(
      split: InputSplit,
      context: TaskAttemptContext
  ): RecordReader[LongWritable, Text] = new DblpXmlRecordReader

  /** Compressed input (for example dblp.xml.gz) is never split: one mapper decompresses it all. */
  override def isSplitable(context: org.apache.hadoop.mapreduce.JobContext, file: Path): Boolean =
    DblpXmlInputFormat.codecFor(context.getConfiguration, file) == null

object DblpXmlInputFormat:
  val TruncatedMarker = "#TRUNCATED#"

  /**
   * Codec chosen by file suffix, case-insensitively (`dblp.xml.GZ` is gzip too). Only the file name
   * is lower-cased for the lookup; the path that is opened is unchanged.
   */
  def codecFor(
      conf: org.apache.hadoop.conf.Configuration,
      file: Path
  ): org.apache.hadoop.io.compress.CompressionCodec =
    val factory = new org.apache.hadoop.io.compress.CompressionCodecFactory(conf)
    val exact   = factory.getCodec(file)
    if exact != null then exact
    else factory.getCodec(new Path(file.getName.toLowerCase(java.util.Locale.ROOT)))

/**
 * Pure scanner over a stream positioned at `start`; shared by the DBLP reader, the arXiv reader
 * ([[ArxivRecordReader]]), and their unit tests.
 *
 * A record ends at its matching close tag. If a top-level record start tag (one of `types`, for
 * example `<inproceedings `) appears before that close tag, the open record is broken (unclosed or
 * closed with the wrong tag): it ends just before that start tag and is emitted as is, so the
 * parser rejects it as `xml-not-well-formed` under its own key, and the next record is read
 * normally. This keeps one record per start tag, so a broken record never swallows the well-formed
 * records after it, and no record is read by two splits (the reader that owns the broken record
 * stops where the next record begins). A record still open at end of input is emitted with
 * [[DblpXmlInputFormat.TruncatedMarker]].
 *
 * CDATA sections and comments (CO-4). Markup is read in one of three lexical states: normal, inside
 * a CDATA section (`<![CDATA[` ... `]]>`), or inside a comment (`<!--` ... `-->`). Record start
 * tags and the record's close tag count only in the normal state, both inside a record and between
 * records, so `<article key="x">` inside a CDATA section or a comment neither starts nor ends a
 * record. Bounded sections: an opener counts only if its closer begins within
 * [[DblpRecordScanner.SectionLimit]] bytes after the opener; otherwise the opener is plain text
 * (the record is not well formed and the parser rejects it). So an unterminated `<![CDATA[` or
 * `<!--` in a broken record cannot swallow the records after it beyond that bound. Within the bound
 * it does, as XML requires: an opener whose closer begins within `SectionLimit` bytes is a real
 * section, so the record start tags between opener and closer are text of that section, not
 * records; they end up inside the record that holds the opener (usually rejected as not well
 * formed). The lookahead needed to decide is bounded (at most `2 * SectionLimit + 64` bytes ahead
 * of the current position; the lookahead buffer never exceeds twice that, about 256 KiB). Whether
 * an opener counts depends only on the bytes after it, so every reader that sees it decides the
 * same way.
 *
 * Split starts inside a section (Phase 11 repair-03 F1). A reader whose split starts at `start = 0`
 * knows it is in the normal state. A reader with `start > 0` cannot see the bytes before `start`,
 * so its starting point g(start) is a pure function of the bytes after it (resync rule):
 *   - Let `f` be the first `]]>` that begins at or after `b` and at most `SectionLimit` bytes after
 *     it. If there is one, and no counted section opener (`<![CDATA[`, or `<!--` whose `-->` is
 *     within the bound) lies in `[b, f)`, then g(b) = f + 3: `b` probably lay inside a CDATA
 *     section that ends at `f` (XML forbids `]]>` in character data). Otherwise g(b) = b.
 *   - The reader starts in the normal state at g(start) and owns the record start tags it finds
 *     from there on, by its own lexer.
 * Hand-off rule: the reader whose split ends at `end` (its own lexer has read every byte from its
 * start, so it knows its state at `end`) also owns every start tag its lexer finds in `[end,
 * g(end))`, which the next reader skips, and keeps reading while its lexer and the next reader's
 * lexer (simulated from g(end) in the normal state; deterministic, since it depends only on the
 * bytes) disagree, owning every start tag its own lexer finds before they agree. They agree at the
 * first common token boundary in the same state, and from there they read every byte identically,
 * so the next reader owns the rest. Before that point both lexers can never be in the normal state
 * at the same token boundary, so no start tag is owned twice. g is monotone in `b` (a later `b`
 * sees a subset of the openers before the same `f`), so the ownership intervals of consecutive
 * readers tile the file. Consequences, for every split placement:
 *   - No record start tag that the whole-file reader (start = 0) finds is skipped by every reader:
 *     a stray `]]>` after a split start (input that is not well-formed XML) no longer hides the
 *     well-formed records before it; the previous reader emits them, and the record holding the
 *     `]]>` is emitted and rejected by the parser as at a single split.
 *   - A split that starts inside a comment that holds `<![CDATA[` no longer hides the records after
 *     the comment: the previous reader emits them while the next reader's lexer is misled.
 *   - The previous reader reads past its split end by at most the disagreement window: at most
 *     about `SectionLimit` bytes after g(end) for any section, more only in input that chains
 *     sections that the two lexers read differently.
 * Limits (documented, deterministic): a comment cannot be recognized from the bytes after the split
 * start (`-->` is legal character data), so the next reader reads the rest of that comment as
 * normal markup and emits a record start tag in it as a record (a phantom, which the parser may
 * accept under the key written in the comment; the real records are still emitted, so nothing is
 * lost). A CDATA section whose text holds `<![CDATA[` or `<!--` can mislead the next reader the
 * same way (phantoms only). If a third split boundary falls inside a disagreement window (splits
 * shorter than about `2 * SectionLimit` in such input), a start tag can be owned by two readers;
 * Job 1 deduplicates by work key and counts DUPLICATE, so the result is a counted duplicate, never
 * a loss. DBLP and arXiv OAI dumps contain no CDATA sections and no comments inside or between
 * records, so none of these arise there, and there the hand-off ends at the split end itself.
 */
final class DblpRecordScanner(in: InputStream, start: Long, end: Long, types: Set[String]):
  import DblpRecordScanner.*

  private val look          = new Lookahead(in, MaxPeek)
  private var pos           = start // absolute offset of look.peek(0)
  private val maxName       = types.map(_.length).max
  private var lex           = Normal
  private var synced        = start == 0L
  private val cdataCloser   = new CloserCache(CdataClose)
  private val commentCloser = new CloserCache(CommentClose)

  // Hand-off at `end` (see the class comment). `ownEnd` is the next reader's start point g(end);
  // `shadowPos`/`shadowLex` is the next reader's lexer, simulated from g(end) until it agrees with
  // this reader's lexer (`tracking` false). Both are pure functions of the bytes after `end`.
  private var endResolved = false
  private var ownEnd      = end
  private var tracking    = false
  private var shadowPos   = 0L
  private var shadowLex   = Normal

  private def consume(n: Int, out: java.io.ByteArrayOutputStream): Unit =
    var k = 0
    while k < n do
      if pos == end && !endResolved then resolveEnd()
      if tracking && pos == shadowPos then advanceShadow()
      val b = look.peek(0)
      if out != null then out.write(b)
      look.skip1(); pos += 1; k += 1

  /** The record type whose start tag begins at offset `i` (a '<' there), if any. */
  private def startTagAt(i: Int): Option[String] =
    val sb = new StringBuilder
    var c  = look.peek(i + 1)
    while c >= 0 && sb.length <= maxName && Character.isLetter(c) do
      sb.append(c.toChar); c = look.peek(i + 1 + sb.length)
    val name = sb.toString
    if types(name) && (c == ' ' || c == '>' || c == '\t' || c == '\n' || c == '\r') then Some(name)
    else None

  /**
   * The section a counted opener at offset `i` (a '<' there) opens: InCdata, InComment, or Normal.
   */
  private def sectionAt(i: Int): Int =
    if look.peek(i + 1) != '!' then Normal
    else if look.startsWith(i, CdataOpen) && cdataCloser.within(pos + i + CdataOpen.length) then
      InCdata
    else if look.startsWith(i, CommentOpen) &&
      commentCloser.within(pos + i + CommentOpen.length)
    then InComment
    else Normal

  /**
   * Lexical step at offset `i` in state `st`, packed as `nextState * 16 + bytesCovered` (no
   * allocation per byte). Shared by the consuming scan and the non-consuming resync lookahead, so
   * both lex identically.
   */
  private def stepAt(i: Int, st: Int): Int = st match
    case Normal =>
      if look.peek(i) != '<' then Normal * 16 + 1
      else
        sectionAt(i) match
          case InCdata   => InCdata * 16 + CdataOpen.length
          case InComment => InComment * 16 + CommentOpen.length
          case _         => Normal * 16 + 1
    case InCdata =>
      if look.startsWith(i, CdataClose) then Normal * 16 + CdataClose.length else st * 16 + 1
    case _ =>
      if look.startsWith(i, CommentClose) then Normal * 16 + CommentClose.length else st * 16 + 1

  private def step(out: java.io.ByteArrayOutputStream): Unit =
    val s = stepAt(0, lex)
    consume(s & 15, out); lex = s >> 4

  /**
   * Skip length g(b) - b for a split boundary b at the current position (resync rule, class
   * comment): the bytes up to and including the first `]]>` within SectionLimit, if no counted
   * section opener lies before it; else 0. Pure in the bytes after b, and monotone in b, so the
   * reader that starts at b and the reader that ends at b compute the same value.
   */
  private def skipTarget(): Int =
    val f = look.indexOf(CdataClose, 0, SectionLimit)
    if f < 0 then 0
    else
      var i     = 0
      var clean = true
      // No opener or closer can overlap a first "]]>", so an opener before f ends before f.
      while clean && i < f do
        if look.peek(i) == '<' && sectionAt(i) != Normal then clean = false
        i += 1
      if clean then f + CdataClose.length else 0

  /** Resync rule for `start > 0`: skip to g(start), where the previous reader's hand-off ends. */
  private def resync(): Unit = consume(skipTarget(), null)

  /**
   * At `pos == end`: compute g(end) and start simulating the next reader's lexer there. g(0) = 0,
   * because a reader that starts at offset 0 never resyncs.
   */
  private def resolveEnd(): Unit =
    endResolved = true
    ownEnd = if end == 0L then 0L else end + skipTarget()
    tracking = true; shadowPos = ownEnd; shadowLex = Normal

  /** Advances the simulated next-reader lexer by one token (only when `pos == shadowPos`). */
  private def advanceShadow(): Unit =
    val s = stepAt(0, shadowLex)
    shadowLex = s >> 4; shadowPos += s & 15

  /**
   * Called at every token boundary of this reader's lexer. Once both lexers are at the same token
   * boundary in the same state they agree on every later byte, so the hand-off is complete.
   */
  private def checkpoint(): Unit =
    if pos == end && !endResolved then resolveEnd()
    if tracking && pos == shadowPos && shadowLex == lex then tracking = false

  /** Whether a record start tag found at `pos` (by this reader's lexer) belongs to this reader. */
  private def owns: Boolean = pos < end || (endResolved && (pos < ownEnd || tracking))

  /** Next (offset, recordText) whose start tag this reader owns (class comment), or None. */
  def next(): Option[(Long, String)] =
    if !synced then
      synced = true
      resync() // also for start == end: the start tags before g(start) are the previous reader's
    var found: Option[(Long, String)] = None
    while found.isEmpty && { checkpoint(); owns } && look.peek(0) >= 0 do
      val tag = if lex == Normal && look.peek(0) == '<' then startTagAt(0) else None
      tag match
        case Some(name) =>
          val off = pos
          found = Some(off -> readRecord(name))
        case None => step(null)
    found

  private def readRecord(name: String): String =
    val close = s"</$name>".getBytes(StandardCharsets.ISO_8859_1)
    val buf   = new java.io.ByteArrayOutputStream()
    consume(1, buf) // the start tag's '<'
    var closed = false
    var broken = false
    while !closed && !broken && { checkpoint(); look.peek(0) >= 0 } do
      if lex == Normal && look.peek(0) == '<' then
        if look.startsWith(0, close) then
          consume(close.length, buf); closed = true
        else if startTagAt(0).isDefined then
          // Next top-level record begins: end this broken record before its '<'.
          broken = true
        else step(buf)
      else step(buf)
    val text = new String(buf.toByteArray, StandardCharsets.ISO_8859_1)
    if closed || broken then text else DblpXmlInputFormat.TruncatedMarker + text

  /**
   * Memo of "does `tok` begin within SectionLimit bytes at or after absolute offset x". Pure in the
   * bytes; the memo keeps the total lookahead work linear even for input full of unclosed openers.
   * Invariant: no occurrence of `tok` begins in [lo, hi); if hit >= 0, `tok` begins at hit = hi.
   */
  final private class CloserCache(tok: Array[Byte]):
    private var lo, hi = -1L
    private var hit    = -1L

    def within(x: Long): Boolean =
      if hit >= 0 && lo <= x && x <= hit then hit - x <= SectionLimit
      else
        if !(hit < 0 && lo <= x && x <= hi) then
          lo = x; hi = x; hit = -1L
        val limit = x + SectionLimit
        var p     = hi
        var eof   = false
        while hit < 0 && !eof && p <= limit do
          val i = (p - pos).toInt
          if look.peek(i) < 0 then eof = true
          else if look.startsWith(i, tok) then hit = p
          else p += 1
        hi = p
        hit >= 0

  def position: Long = pos
  def close(): Unit  = in.close()

object DblpRecordScanner:
  /** Longest CDATA section or comment (opener end to closer start) the scanner recognizes. */
  val SectionLimit: Int = 1 << 16
  private val MaxPeek   = 2 * SectionLimit + 64

  private inline val Normal    = 0
  private inline val InCdata   = 1
  private inline val InComment = 2

  private val CdataOpen    = "<![CDATA[".getBytes(StandardCharsets.ISO_8859_1)
  private val CdataClose   = "]]>".getBytes(StandardCharsets.ISO_8859_1)
  private val CommentOpen  = "<!--".getBytes(StandardCharsets.ISO_8859_1)
  private val CommentClose = "-->".getBytes(StandardCharsets.ISO_8859_1)

  /**
   * Forward-only byte buffer with bounded lookahead: `peek(i)` for `0 <= i < maxPeek` without
   * consuming. Starts small and grows to at most `2 * maxPeek` bytes.
   */
  final private class Lookahead(in: InputStream, maxPeek: Int):
    private var buf  = new Array[Byte](8192)
    private var head = 0 // index of peek(0)
    private var tail = 0 // buf(head until tail) is valid
    private var eof  = false

    def peek(i: Int): Int =
      if i < 0 || i >= maxPeek then throw new IllegalArgumentException(s"lookahead $i")
      while tail - head <= i && !eof do fill(i + 1)
      if tail - head > i then buf(head + i) & 0xff else -1

    private def fill(need: Int): Unit =
      if head + need > buf.length then
        val avail  = tail - head
        val target =
          if 2 * need > buf.length then math.max(2 * buf.length, 2 * need) else buf.length
        val next = if target == buf.length then buf else new Array[Byte](target)
        System.arraycopy(buf, head, next, 0, avail)
        buf = next; head = 0; tail = avail
      val n = in.read(buf, tail, buf.length - tail)
      if n < 0 then eof = true else tail += n

    def skip1(): Unit = head += 1

    def startsWith(i: Int, tok: Array[Byte]): Boolean =
      var k = 0
      while k < tok.length && peek(i + k) == (tok(k) & 0xff) do k += 1
      k == tok.length

    /** First offset in [from, to] where `tok` begins, or -1. */
    def indexOf(tok: Array[Byte], from: Int, to: Int): Int =
      var p     = from
      var found = -1
      while found < 0 && p <= to && peek(p) >= 0 do
        if startsWith(p, tok) then found = p else p += 1
      found

class DblpXmlRecordReader extends RecordReader[LongWritable, Text]:
  private var scanner: DblpRecordScanner = scala.compiletime.uninitialized
  private var start, end                 = 0L
  private val key                        = new LongWritable
  private val value                      = new Text

  override def initialize(split: InputSplit, context: TaskAttemptContext): Unit =
    val fs   = split.asInstanceOf[FileSplit]
    val path = fs.getPath
    start = fs.getStart
    end = start + fs.getLength
    val conf  = context.getConfiguration
    val raw   = path.getFileSystem(conf).open(path)
    val codec = DblpXmlInputFormat.codecFor(conf, path)
    if codec != null then
      // Whole-file split (isSplitable is false): scan the decompressed stream to its end.
      require(start == 0L, "compressed input must not be split: " + path)
      end = Long.MaxValue
      scanner = new DblpRecordScanner(
        codec.createInputStream(raw),
        0L,
        Long.MaxValue,
        DblpRecordParser.AllRecordTypes
      )
    else
      raw.seek(start)
      scanner = new DblpRecordScanner(raw, start, end, DblpRecordParser.AllRecordTypes)

  override def nextKeyValue(): Boolean =
    scanner.next() match
      case Some((off, text)) =>
        key.set(off); value.set(text.getBytes(StandardCharsets.UTF_8)); true
      case None => false

  override def getCurrentKey: LongWritable = key
  override def getCurrentValue: Text       = value
  override def getProgress: Float          =
    if end == Long.MaxValue then 0f
    else if end == start then 1f
    else math.min(1f, (scanner.position - start).toFloat / (end - start))
  override def close(): Unit = if scanner != null then scanner.close()
