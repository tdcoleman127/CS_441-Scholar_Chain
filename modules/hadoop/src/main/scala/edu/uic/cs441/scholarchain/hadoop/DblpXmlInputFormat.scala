package edu.uic.cs441.scholarchain.hadoop

import java.io.{BufferedInputStream, InputStream}
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
 * [[DblpXmlInputFormat.TruncatedMarker]] so the mapper rejects it instead of dropping it.
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

/** Pure scanner over a stream positioned at `start`; shared by the reader and its unit tests. */
final class DblpRecordScanner(in: InputStream, start: Long, end: Long, types: Set[String]):
  private val stream  = new BufferedInputStream(in, 1 << 16)
  private var pos     = start
  private val maxName = types.map(_.length).max

  private def read(): Int =
    val b = stream.read()
    if b >= 0 then pos += 1
    b

  /** Next (offset, recordText) whose start tag begins before `end`, or None. */
  def next(): Option[(Long, String)] =
    var found: Option[(Long, String)] = None
    var done                          = false
    while !done && found.isEmpty do
      if pos >= end then done = true
      else
        val b = read()
        if b < 0 then done = true
        else if b == '<' then
          val tagStart = pos - 1
          stream.mark(maxName + 2)
          val sb = new StringBuilder
          var c  = stream.read()
          while c >= 0 && sb.length <= maxName && Character.isLetter(c) do
            sb.append(c.toChar); c = stream.read()
          stream.reset()
          val name = sb.toString
          if types(name) && (c == ' ' || c == '>' || c == '\t' || c == '\n' || c == '\r') then
            found = Some(tagStart -> readRecord(name))
    found

  private def readRecord(name: String): String =
    val close = s"</$name>".getBytes(StandardCharsets.ISO_8859_1)
    val buf   = new java.io.ByteArrayOutputStream()
    buf.write('<')
    var matched = 0
    var b       = 0
    while matched < close.length && { b = read(); b >= 0 } do
      buf.write(b)
      matched =
        if b == close(matched) then matched + 1 else if b == close(0) then 1 else 0
    val text = new String(buf.toByteArray, StandardCharsets.ISO_8859_1)
    if matched == close.length then text else DblpXmlInputFormat.TruncatedMarker + text

  def position: Long = pos
  def close(): Unit  = stream.close()

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
