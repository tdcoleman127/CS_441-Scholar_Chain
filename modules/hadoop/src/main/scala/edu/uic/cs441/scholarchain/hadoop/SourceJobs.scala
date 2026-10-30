package edu.uic.cs441.scholarchain.hadoop

import edu.uic.cs441.scholarchain.core.json.Wire
import edu.uic.cs441.scholarchain.core.model.SourceType
import java.nio.charset.StandardCharsets
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.{
  LongWritable,
  NullWritable,
  Text,
  WritableComparable,
  WritableComparator
}
import org.apache.hadoop.mapreduce.lib.input.{
  FileInputFormat,
  FileSplit,
  LineRecordReader,
  TextInputFormat
}
import org.apache.hadoop.mapreduce.lib.output.{
  FileOutputFormat,
  LazyOutputFormat,
  MultipleOutputs,
  TextOutputFormat
}
import org.apache.hadoop.mapreduce.{
  InputSplit,
  Job,
  JobContext,
  Mapper,
  Partitioner,
  RecordReader,
  Reducer,
  TaskAttemptContext
}
import scala.jdk.CollectionConverters.*
import upickle.default.{read, write}

object SourceConf:
  val MaxBlock        = "scholarchain.block.max.size"
  val DefaultMaxBlock = 500

/**
 * Job 2 input format. `*.xml` (arXiv OAI-PMH) yields whole `<record>` elements through the same
 * boundary-safe scanner as DBLP (a record belongs to the split where its start tag begins, so none
 * is split across mappers); every other file (JSONL, CSV) yields lines. Key: byte offset in the
 * file. Value: the record text (UTF-8). Compressed files are not split.
 */
class SourceInputFormat extends FileInputFormat[LongWritable, Text]:
  override def createRecordReader(split: InputSplit, ctx: TaskAttemptContext)
      : RecordReader[LongWritable, Text] =
    val name = split.asInstanceOf[FileSplit].getPath.getName.toLowerCase(java.util.Locale.ROOT)
    if name.endsWith(".xml") then new ArxivRecordReader else new LineRecordReader
  override def isSplitable(context: JobContext, file: Path): Boolean =
    DblpXmlInputFormat.codecFor(context.getConfiguration, file) == null

class ArxivRecordReader extends RecordReader[LongWritable, Text]:
  private var scanner: DblpRecordScanner = scala.compiletime.uninitialized
  private var start, end                 = 0L
  private val key                        = new LongWritable
  private val value                      = new Text
  override def initialize(split: InputSplit, context: TaskAttemptContext): Unit =
    val fs = split.asInstanceOf[FileSplit]
    start = fs.getStart; end = start + fs.getLength
    val in = fs.getPath.getFileSystem(context.getConfiguration).open(fs.getPath)
    in.seek(start)
    scanner = new DblpRecordScanner(in, start, end, Set("record"))
  override def nextKeyValue(): Boolean = scanner.next() match
    case Some((off, text)) =>
      // The scanner decodes bytes as ISO-8859-1; re-encoding restores the original UTF-8 bytes.
      key.set(off); value.set(text.getBytes(StandardCharsets.ISO_8859_1)); true
    case None => false
  override def getCurrentKey: LongWritable = key
  override def getCurrentValue: Text       = value
  override def getProgress: Float          =
    if end == start then 1f else math.min(1f, (scanner.position - start).toFloat / (end - start))
  override def close(): Unit = if scanner != null then scanner.close()

/** Shared parsing of Job 1 (WorkEnvelope) and Job 2 (SourceEnvelope) main-output lines. */
object Envelopes:
  def parse(line: String): SourceEnvelope =
    val v = ujson.read(line)
    if v.obj.contains("kind") then read[SourceEnvelope](v)
    else SourceEnvelope("work", Some(dblpOnly(read[WorkEnvelope](v))), None)

  private def dblpOnly(env: WorkEnvelope): NormalizedWork =
    NormalizedWork(
      env,
      WorkExtra(
        env.work.workId,
        env.recordType,
        None,
        None,
        Nil,
        env.work.provenance
      ),
      Nil,
      Nil,
      Nil
    )

// ============================================================================================
// Job 2: external source normalization (OpenAlex, Crossref, arXiv, OpenCitations).
//   Input       SourceInputFormat: (LongWritable byte offset, Text JSONL/CSV line or arXiv record)
//               The source is chosen per file by SourceNormalizers.sourceFor (file-name prefix,
//               then parent directory). The OpenCitations CSV header counts HEADER_SKIPPED only.
//   Map out     (Text key, Text SourceEnvelope JSON); key = workId (works) or `oci:<oci>`
//               named output `rejects`: (NullWritable, Text RejectRecord v2 JSON) under
//               `rejects/v2/` with input file base name and byte offset.
//               Counters: INPUT = ACCEPTED + REJECTED; MISSING_KEY, UNSUPPORTED_TYPE (including
//               deleted and unsupported-source), SCHEMA_FAILURE split REJECTED.
//   Partition   WorkIdPartitioner on the key
//   Reduce in   (Text key, Iterable[Text SourceEnvelope JSON])
//   Reduce out  main: (NullWritable, Text SourceEnvelope JSON), one per key (sorted, smallest
//               kept; identical extras DUPLICATE, differing extras CONFLICT); named `works`
//               (WorkRecord, core Wire), `mentions` (AuthorMention), `workextras` (WorkExtra),
//               `mentionextras` (MentionExtra), `citations` (CitationRecord, CITATIONS),
//               `topics` (core Topic: ID, label, provenance), `institutions` (core Institution:
//               ID, display name, provenance); one per distinct labelled ID per work.
// ============================================================================================
class SourceNormalizeMapper extends Mapper[LongWritable, Text, Text, Text]:
  private var runId, snapshot, inputFile       = ""
  private var source: Option[SourceType]       = None
  private var mos: MultipleOutputs[Text, Text] = scala.compiletime.uninitialized
  private val outKey, outValue                 = new Text

  override def setup(ctx: Mapper[LongWritable, Text, Text, Text]#Context): Unit =
    runId = ctx.getConfiguration.get(Conf.RunId); snapshot = ctx.getConfiguration.get(Conf.Snapshot)
    val path = ctx.getInputSplit.asInstanceOf[FileSplit].getPath
    inputFile = DblpExtractMapper.inputFileName(ctx.getInputSplit)
    source = SourceNormalizers.sourceFor(path.getName, Option(path.getParent).fold("")(_.getName))
    mos = new MultipleOutputs(ctx)

  override def map(
      offset: LongWritable,
      rec: Text,
      ctx: Mapper[LongWritable, Text, Text, Text]#Context
  ): Unit =
    val text = rec.toString
    if source.exists(SourceNormalizers.isHeader(_, text)) && offset.get == 0L then
      ctx.getCounter(ScCounter.HEADER_SKIPPED).increment(1)
    else
      ctx.getCounter(ScCounter.INPUT).increment(1)
      val result = source match
        case Some(s) => SourceNormalizers.normalize(s, text, runId)
        case None    => Left(Rejection("unsupported-source", None, inputFile))
      result match
        case Right(env) =>
          ctx.getCounter(ScCounter.ACCEPTED).increment(1)
          outKey.set(env.key); outValue.set(write(env)); ctx.write(outKey, outValue)
        case Left(rej) =>
          ctx.getCounter(ScCounter.REJECTED).increment(1)
          ctx.getCounter(SourceNormalizeMapper.counterFor(rej.reason)).increment(1)
          val rr = DblpExtractMapper.rejectRecord(rej, inputFile, snapshot, runId, offset.get)
            .copy(sourceDataset = source.fold("unknown")(_.wire))
          mos.write("rejects", NullWritable.get, new Text(write(rr)), DblpExtractMapper.RejectBase)

  override def cleanup(ctx: Mapper[LongWritable, Text, Text, Text]#Context): Unit = mos.close()

object SourceNormalizeMapper:
  def counterFor(reason: String): ScCounter = reason match
    case "missing-key" => ScCounter.MISSING_KEY
    case "unsupported-record-type" | "deleted-record" | "unsupported-source" =>
      ScCounter.UNSUPPORTED_TYPE
    case _ => ScCounter.SCHEMA_FAILURE

class SourceNormalizeReducer extends Reducer[Text, Text, NullWritable, Text]:
  private var mos: MultipleOutputs[NullWritable, Text] = scala.compiletime.uninitialized
  override def setup(ctx: Reducer[Text, Text, NullWritable, Text]#Context): Unit =
    mos = new MultipleOutputs(ctx)
  private def emit(name: String, json: String): Unit =
    mos.write(name, NullWritable.get, new Text(json), s"$name/part")
  override def reduce(
      key: Text,
      values: java.lang.Iterable[Text],
      ctx: Reducer[Text, Text, NullWritable, Text]#Context
  ): Unit =
    val all  = values.asScala.map(_.toString).toVector.sorted
    val kept = all.head
    ctx.getCounter(ScCounter.DUPLICATE).increment(all.tail.count(_ == kept).toLong)
    ctx.getCounter(ScCounter.CONFLICT).increment(all.tail.count(_ != kept).toLong)
    ctx.write(NullWritable.get, new Text(kept))
    val env = read[SourceEnvelope](kept)
    env.work.foreach { nw =>
      emit("works", Wire.encode(nw.envelope.work))
      nw.envelope.mentions.foreach(m => emit("mentions", Wire.encode(m)))
      emit("workextras", write(nw.extra))
      nw.mentionExtras.foreach(x => emit("mentionextras", write(x)))
      nw.topics.foreach(t => emit("topics", Wire.encode(t)))
      nw.institutions.foreach(i => emit("institutions", Wire.encode(i)))
    }
    env.citation.foreach { c =>
      ctx.getCounter(ScCounter.CITATIONS).increment(1); emit("citations", write(c))
    }
  override def cleanup(ctx: Reducer[Text, Text, NullWritable, Text]#Context): Unit = mos.close()

// ============================================================================================
// Job 3: exact identifier extraction and join preparation.
//   Input       TextInputFormat over Job 1 and Job 2 main outputs (WorkEnvelope or
//               SourceEnvelope JSON lines)
//   Map out     (Text `<scope>|<ns>:<value>`, Text IdMember JSON). Work scope: the work's own
//               source ID and every ExternalId (normalized by core IdFormat in Jobs 1 and 2);
//               citations contribute their citing and cited IDs. Mention scope: `orcid:<iD>`.
//               MISSING_KEY counts works with no identifier outside their own source's namespace
//               (ExactIds.missingKey): a Crossref `work:doi:` work is keyed by a DOI and so is
//               not missing; an OpenAlex work with only `openalex:W...` is.
//   Partition   WorkIdPartitioner on the key
//   Reduce in   (Text key, Iterable[Text IdMember JSON])
//   Reduce out  named `exactids`: ExactIdGroup JSON (ExactIds.group). EXACT_ID_KEY per key;
//               LINKED_KEY when 2+ distinct work IDs share it; DUPLICATE per repeated
//               (memberId, source) attestation; CONFLICT when one source puts the key on 2+
//               distinct works.
// ============================================================================================
class ExactIdMapper extends Mapper[LongWritable, Text, Text, Text]:
  private val k, v = new Text
  override def map(
      o: LongWritable,
      line: Text,
      ctx: Mapper[LongWritable, Text, Text, Text]#Context
  ): Unit =
    ctx.getCounter(ScCounter.INPUT).increment(1)
    val env = Envelopes.parse(line.toString)
    ExactIds.keys(env).foreach { (key, m) =>
      k.set(key); v.set(write(m)); ctx.write(k, v)
    }
    if ExactIds.missingKey(env) then
      ctx.getCounter(ScCounter.MISSING_KEY).increment(1)

class ExactIdReducer extends Reducer[Text, Text, NullWritable, Text]:
  private var mos: MultipleOutputs[NullWritable, Text] = scala.compiletime.uninitialized
  override def setup(ctx: Reducer[Text, Text, NullWritable, Text]#Context): Unit =
    mos = new MultipleOutputs(ctx)
  override def reduce(
      key: Text,
      values: java.lang.Iterable[Text],
      ctx: Reducer[Text, Text, NullWritable, Text]#Context
  ): Unit =
    val g =
      ExactIds.group(key.toString, values.asScala.map(t => read[IdMember](t.toString)).toVector)
    ctx.getCounter(ScCounter.EXACT_ID_KEY).increment(1)
    ctx.getCounter(ScCounter.DUPLICATE).increment((g.attestations - g.members.size).toLong)
    if g.members.map(_.workId).distinct.size > 1 then
      ctx.getCounter(ScCounter.LINKED_KEY).increment(1)
    if g.conflict then ctx.getCounter(ScCounter.CONFLICT).increment(1)
    mos.write("exactids", NullWritable.get, new Text(write(g)), "exactids/part")
  override def cleanup(ctx: Reducer[Text, Text, NullWritable, Text]#Context): Unit = mos.close()

object ExactIds:
  /** Every (key, member) attestation of one envelope. */
  def keys(env: SourceEnvelope): List[(String, IdMember)] =
    env.work.toList.flatMap { nw =>
      val w = nw.envelope.work
      Blocking.workKeys(nw.envelope).map(k =>
        s"work|$k" -> IdMember(
          w.workId,
          w.workId,
          w.provenance
        )
      ) ++
        nw.envelope.mentions.flatMap(m =>
          m.orcid.map(o => s"mention|orcid:$o" -> IdMember(m.mentionId, m.workId, m.provenance))
        )
    } ++ env.citation.toList.flatMap { c =>
      c.citingIds.map(e =>
        s"work|${Blocking.idKey(e)}" -> IdMember(
          c.citingWorkId,
          c.citingWorkId,
          c.provenance
        )
      ) ++
        c.citedIds.map(e =>
          s"work|${Blocking.idKey(e)}" -> IdMember(
            c.citedWorkId,
            c.citedWorkId,
            c.provenance
          )
        )
    }

  /**
   * True for a work with no exact key outside its own source's namespace (`openalex:` for OpenAlex,
   * `dblp:` for DBLP, `arxiv:` for arXiv): nothing another source could join on. A work whose own
   * key is a cross-source identifier (Crossref `work:doi:` -> `doi:`) is not missing.
   */
  def missingKey(env: SourceEnvelope): Boolean =
    env.work.exists { nw =>
      val own = nw.envelope.work.provenance.sourceDataset.wire
      Blocking.workKeys(nw.envelope).forall(_.takeWhile(_ != ':') == own)
    }

  /**
   * One key's group: members distinct by (memberId, sourceDataset), keeping the smallest provenance
   * record ID, sorted by memberId then source, so the output is deterministic.
   */
  def group(key: String, attestations: Seq[IdMember]): ExactIdGroup =
    val scope   = key.takeWhile(_ != '|')
    val members = attestations
      .groupBy(m => (m.memberId, m.provenance.sourceDataset.wire))
      .toVector
      .sortBy(_._1)
      .map(_._2.minBy(_.provenance.sourceRecordId))
      .toList
    val conflict = scope == "work" &&
      members.groupBy(_.provenance.sourceDataset).exists(_._2.map(_.workId).distinct.size > 1)
    ExactIdGroup(key.drop(scope.length + 1), scope, members, attestations.size, conflict)

// ============================================================================================
// Jobs 4 + 5: bounded researcher and work blocking with raw candidate features.
//   Input       TextInputFormat over Job 1 and Job 2 main outputs
//   Map out     (Text `<blockKey>\t<memberId>`, Text BlockMember JSON). Block keys:
//               `m|<surname>|<initial>` per mention, `w|<first 4 title tokens>` per work
//               (Blocking.mentionKey / workKey). A member with no key counts MISSING_KEY.
//   Partition   BlockPartitioner: WorkIdPartitioner.partition on the block key (before `\t`)
//   Sort/group  sort by the full composite key (so members arrive in member-ID order);
//               BlockGroupingComparator groups by block key only (secondary sort)
//   Reduce in   (Text blockKey\tfirstMember, Iterable[Text BlockMember JSON] in member order)
//   Reduce out  Blocking.processBlock: holds at most `scholarchain.block.max.size` members;
//               larger blocks count OVERSIZE_BLOCK and are cut into chunks of that size.
//               Named `candidates`: MatchCandidateRecord JSON (raw features, both provenances,
//               no decisions; CANDIDATES); named `blockstats`: BlockStat JSON, one per block,
//               with the pipeline run ID (job configuration) and the source datasets present.
// ============================================================================================
object BlockKeys:
  def prefix(composite: String): String =
    val i = composite.indexOf('\t'); if i < 0 then composite else composite.substring(0, i)

class BlockPartitioner extends Partitioner[Text, Text]:
  override def getPartition(key: Text, value: Text, n: Int): Int =
    WorkIdPartitioner.partition(BlockKeys.prefix(key.toString), n)

class BlockGroupingComparator extends WritableComparator(classOf[Text], true):
  override def compare(a: WritableComparable[?], b: WritableComparable[?]): Int =
    BlockKeys.prefix(a.toString).compareTo(BlockKeys.prefix(b.toString))

class BlockMapper extends Mapper[LongWritable, Text, Text, Text]:
  private val k, v = new Text
  override def map(
      o: LongWritable,
      line: Text,
      ctx: Mapper[LongWritable, Text, Text, Text]#Context
  ): Unit =
    ctx.getCounter(ScCounter.INPUT).increment(1)
    Envelopes.parse(line.toString).work.foreach { nw =>
      Blocking.members(nw.envelope).foreach {
        case (Some(key), m) => k.set(s"$key\t${m.memberId}"); v.set(write(m)); ctx.write(k, v)
        case (None, _)      => ctx.getCounter(ScCounter.MISSING_KEY).increment(1)
      }
    }

class BlockReducer extends Reducer[Text, Text, NullWritable, Text]:
  private var mos: MultipleOutputs[NullWritable, Text] = scala.compiletime.uninitialized
  private var maxBlock                                 = SourceConf.DefaultMaxBlock
  private var runId                                    = ""
  override def setup(ctx: Reducer[Text, Text, NullWritable, Text]#Context): Unit =
    mos = new MultipleOutputs(ctx)
    maxBlock = ctx.getConfiguration.getInt(SourceConf.MaxBlock, SourceConf.DefaultMaxBlock)
    runId = ctx.getConfiguration.get(Conf.RunId)
  override def reduce(
      key: Text,
      values: java.lang.Iterable[Text],
      ctx: Reducer[Text, Text, NullWritable, Text]#Context
  ): Unit =
    val blockKey = BlockKeys.prefix(key.toString)
    val sink     = new Blocking.Sink:
      def count(c: ScCounter, n: Long): Unit       = ctx.getCounter(c).increment(n)
      def emit(output: String, json: String): Unit =
        mos.write(output, NullWritable.get, new Text(json), s"$output/part")
    Blocking.processBlock(
      blockKey,
      values.iterator.asScala.map(t => read[BlockMember](t.toString)),
      maxBlock,
      runId,
      sink
    )
  override def cleanup(ctx: Reducer[Text, Text, NullWritable, Text]#Context): Unit = mos.close()

// ============================================================================================
// Job 7: graph export preparation for Spark.
//   Input       TextInputFormat over `<jobOutput>/<entity>/part-*` of Jobs 1, 2, 3, 4+5, and 6
//               (entities: Export.Entities). The entity is the parent directory.
//   Map out     (Text `<entity>\t<partitionKey>\t<record JSON>`, Text empty). partitionKey =
//               workId, else citingWorkId (citations), workLow (candidates), key (exactids),
//               topicId (topics), institutionId (institutions); control characters become spaces.
//               The record line is in the key so the shuffle sort orders it (secondary sort).
//   Partition   ExportPartitioner: WorkIdPartitioner.partition on the partition key, so every
//               entity of one work lands in the same partition number across directories.
//   Sort/group  sort by the full composite key (bytes); ExportGroupingComparator groups by
//               `<entity>\t<partitionKey>` only.
//   Reduce in   (Text composite key, Iterable[Text empty]); Hadoop advances the key with each
//               value, so each value step exposes the next record line in sorted order.
//   Reduce out  named `<entity>`: the record lines, streamed (nothing buffered), in byte order
//               within a group, under `<entity>/part-r-<partition>`; EXPORTED per record.
//               Partition count = the reducer count.
// ============================================================================================
object ExportKeys:
  private def secondTab(s: String): Int =
    val i = s.indexOf('\t'); if i < 0 then -1 else s.indexOf('\t', i + 1)

  /** `<entity>\t<partitionKey>` of a composite key (the group). */
  def group(composite: String): String =
    val j = secondTab(composite); if j < 0 then composite else composite.substring(0, j)

  /** The partition key of a composite key. */
  def partitionKey(composite: String): String =
    val g = group(composite); g.substring(g.indexOf('\t') + 1)

  /** The record line of a composite key. */
  def line(composite: String): String =
    val j = secondTab(composite); if j < 0 then "" else composite.substring(j + 1)

  def composite(entity: String, partitionKey: String, line: String): String =
    s"$entity\t${partitionKey.map(c => if c < ' ' then ' ' else c)}\t$line"

  /** Byte length of the `<entity>\t<partitionKey>` prefix of a serialized composite key. */
  def groupLength(bytes: Array[Byte], length: Int): Int =
    var tabs = 0; var i = 0
    while i < length && tabs < 2 do
      if bytes(i) == '\t' then tabs += 1
      i += 1
    if tabs == 2 then i - 1 else length

class ExportPartitioner extends Partitioner[Text, Text]:
  override def getPartition(key: Text, value: Text, n: Int): Int =
    WorkIdPartitioner.partition(ExportKeys.partitionKey(key.toString), n)

/** Groups composite keys by `<entity>\t<partitionKey>` (byte prefix, consistent with the sort). */
class ExportGroupingComparator extends WritableComparator(classOf[Text], true):
  override def compare(a: WritableComparable[?], b: WritableComparable[?]): Int =
    val x = a.asInstanceOf[Text]; val y = b.asInstanceOf[Text]
    WritableComparator.compareBytes(
      x.getBytes,
      0,
      ExportKeys.groupLength(x.getBytes, x.getLength),
      y.getBytes,
      0,
      ExportKeys.groupLength(y.getBytes, y.getLength)
    )

class ExportMapper extends Mapper[LongWritable, Text, Text, Text]:
  private var entity                                                            = ""
  private val k, empty                                                          = new Text
  override def setup(ctx: Mapper[LongWritable, Text, Text, Text]#Context): Unit =
    entity = ctx.getInputSplit.asInstanceOf[FileSplit].getPath.getParent.getName
  override def map(
      o: LongWritable,
      line: Text,
      ctx: Mapper[LongWritable, Text, Text, Text]#Context
  ): Unit =
    ctx.getCounter(ScCounter.INPUT).increment(1)
    val s = line.toString
    k.set(ExportKeys.composite(entity, Export.partitionKey(s), s)); ctx.write(k, empty)

class ExportReducer extends Reducer[Text, Text, NullWritable, Text]:
  private var mos: MultipleOutputs[NullWritable, Text] = scala.compiletime.uninitialized
  override def setup(ctx: Reducer[Text, Text, NullWritable, Text]#Context): Unit =
    mos = new MultipleOutputs(ctx)
  override def reduce(
      key: Text,
      values: java.lang.Iterable[Text],
      ctx: Reducer[Text, Text, NullWritable, Text]#Context
  ): Unit =
    Export.streamGroup(
      key,
      values.iterator,
      (entity, line) =>
        ctx.getCounter(ScCounter.EXPORTED).increment(1)
        mos.write(entity, NullWritable.get, new Text(line), s"$entity/part")
    )
  override def cleanup(ctx: Reducer[Text, Text, NullWritable, Text]#Context): Unit = mos.close()

object Export:
  val Entities: Seq[String] = Seq(
    "works",
    "mentions",
    "workextras",
    "mentionextras",
    "citations",
    "topics",
    "institutions",
    "teams",
    "authorpapers",
    "pairs",
    "exactids",
    "candidates"
  )

  def partitionKey(json: String): String =
    val o = ujson.read(json).obj
    Seq("workId", "citingWorkId", "workLow", "key", "topicId", "institutionId").flatMap(o.get)
      .flatMap(_.strOpt).headOption.getOrElse("")

  /**
   * Job 7 reduce step for one (entity, partition key) group. Streams: each `values` step makes
   * Hadoop advance `key` to the next composite key of the group, whose record line is emitted at
   * once. Holds one line at a time.
   */
  def streamGroup(key: Text, values: java.util.Iterator[Text], emit: (String, String) => Unit)
      : Unit =
    while values.hasNext do
      values.next()
      val k = key.toString
      emit(BlockKeys.prefix(k), ExportKeys.line(k))

/** Part B wiring. Output paths go through Pipeline.outputPath (run ID and snapshot validated). */
object SourcePipeline:

  private def namedOutputs(job: Job, names: Seq[String]): Unit =
    LazyOutputFormat.setOutputFormatClass(job, classOf[TextOutputFormat[?, ?]])
    names.foreach(n =>
      MultipleOutputs.addNamedOutput(
        job,
        n,
        classOf[TextOutputFormat[?, ?]],
        classOf[NullWritable],
        classOf[Text]
      )
    )

  private def run(
      conf0: Configuration,
      base: String,
      runId: String,
      snapshot: String,
      name: String,
      reducers: Int
  )(wire: Job => Unit): Pipeline.Result =
    val conf = new Configuration(conf0)
    conf.set(Conf.RunId, runId); conf.set(Conf.Snapshot, snapshot)
    val out = Pipeline.outputPath(base, runId, snapshot, name)
    Pipeline.guard(conf, out) match
      case Some(_) => Pipeline.Result(name, true, true, Map.empty, out)
      case None    =>
        val job = Pipeline.common(conf, s"scholarchain-$name-$snapshot", reducers)
        wire(job)
        FileOutputFormat.setOutputPath(job, out)
        val ok = job.waitForCompletion(true)
        Pipeline.Result(
          name,
          ok,
          false,
          Pipeline.counters(job),
          out,
          Pipeline.frameworkCounters(job)
        )

  /** Job 2. `inputs`: source files or globs (each file's source comes from its name or folder). */
  def runNormalize(
      conf: Configuration,
      inputs: Seq[String],
      base: String,
      runId: String,
      snapshot: String,
      reducers: Int
  ): Pipeline.Result =
    run(conf, base, runId, snapshot, "source-normalize", reducers) { job =>
      job.setInputFormatClass(classOf[SourceInputFormat])
      job.setMapperClass(classOf[SourceNormalizeMapper])
      job.setReducerClass(classOf[SourceNormalizeReducer])
      inputs.foreach(i => FileInputFormat.addInputPath(job, new Path(i)))
      job.setOutputFormatClass(classOf[TextOutputFormat[?, ?]])
      Seq(
        "rejects",
        "works",
        "mentions",
        "workextras",
        "mentionextras",
        "citations",
        "topics",
        "institutions"
      ).foreach(n =>
        MultipleOutputs.addNamedOutput(
          job,
          n,
          classOf[TextOutputFormat[?, ?]],
          classOf[NullWritable],
          classOf[Text]
        )
      )
    }

  private def mainParts(job: Job, outs: Seq[Path]): Unit =
    job.setInputFormatClass(classOf[TextInputFormat])
    outs.foreach(o => FileInputFormat.addInputPath(job, new Path(o, "part-r-*")))

  /** Job 3 over the Job 1 and Job 2 main outputs. */
  def runExactIds(
      conf: Configuration,
      envelopeOuts: Seq[Path],
      base: String,
      runId: String,
      snapshot: String,
      reducers: Int
  ): Pipeline.Result =
    run(conf, base, runId, snapshot, "exact-ids", reducers) { job =>
      mainParts(job, envelopeOuts)
      job.setMapperClass(classOf[ExactIdMapper]); job.setReducerClass(classOf[ExactIdReducer])
      namedOutputs(job, Seq("exactids"))
    }

  /** Jobs 4 + 5 over the Job 1 and Job 2 main outputs, with block cap `maxBlock`. */
  def runBlocking(
      conf0: Configuration,
      envelopeOuts: Seq[Path],
      base: String,
      runId: String,
      snapshot: String,
      reducers: Int,
      maxBlock: Int
  ): Pipeline.Result =
    val conf = new Configuration(conf0); conf.setInt(SourceConf.MaxBlock, maxBlock)
    run(conf, base, runId, snapshot, "blocking-candidates", reducers) { job =>
      mainParts(job, envelopeOuts)
      job.setMapperClass(classOf[BlockMapper]); job.setReducerClass(classOf[BlockReducer])
      job.setPartitionerClass(classOf[BlockPartitioner])
      job.setGroupingComparatorClass(classOf[BlockGroupingComparator])
      namedOutputs(job, Seq("candidates", "blockstats"))
    }

  /** Job 7 over the entity directories that exist under `jobOuts`. */
  def runExport(
      conf: Configuration,
      jobOuts: Seq[Path],
      base: String,
      runId: String,
      snapshot: String,
      partitions: Int
  ): Pipeline.Result =
    run(conf, base, runId, snapshot, "graph-export", partitions) { job =>
      job.setInputFormatClass(classOf[TextInputFormat])
      for
        o <- jobOuts; e <- Export.Entities
        glob = new Path(o, s"$e/part-*")
        st   = glob.getFileSystem(job.getConfiguration).globStatus(glob)
        if st != null && st.nonEmpty
      do FileInputFormat.addInputPath(job, glob)
      job.setMapperClass(classOf[ExportMapper]); job.setReducerClass(classOf[ExportReducer])
      job.setPartitionerClass(classOf[ExportPartitioner])
      job.setGroupingComparatorClass(classOf[ExportGroupingComparator])
      namedOutputs(job, Export.Entities)
    }

  /** Largest block from a blocking output's `blockstats` (the report's largest block). */
  def largestBlock(conf: Configuration, blockingOut: Path): Option[BlockStat] =
    val fs    = blockingOut.getFileSystem(conf)
    val st    = fs.globStatus(new Path(blockingOut, "blockstats/part-*"))
    val stats = Option(st).toSeq.flatten.flatMap { s =>
      val in = fs.open(s.getPath)
      try scala.io.Source.fromInputStream(in, "UTF-8").getLines().toVector.map(read[BlockStat](_))
      finally in.close()
    }
    stats.sortBy(s => (-s.size, s.blockKey)).headOption
