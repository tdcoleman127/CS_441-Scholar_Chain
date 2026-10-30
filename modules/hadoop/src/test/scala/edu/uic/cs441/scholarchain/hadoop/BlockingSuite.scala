package edu.uic.cs441.scholarchain.hadoop

import edu.uic.cs441.scholarchain.core.SchemaVersion
import edu.uic.cs441.scholarchain.core.ids.StableIds
import edu.uic.cs441.scholarchain.core.model.*
import org.apache.hadoop.io.Text
import upickle.default.read

/** Jobs 3, 4, 5, 7: exact-ID keys, bounded blocking, raw features, partition keys. */
class BlockingSuite extends munit.FunSuite:
  private def prov(src: SourceType, id: String) = Provenance(src, id, Fixtures.runId)

  final private class Recorder extends Blocking.Sink:
    val counts = scala.collection.mutable.Map.empty[ScCounter, Long]
    val out    = scala.collection.mutable.ArrayBuffer.empty[(String, String)]
    def count(c: ScCounter, n: Long): Unit       = counts(c) = counts.getOrElse(c, 0L) + n
    def emit(output: String, json: String): Unit = out += output -> json
    def lines(o: String): Vector[String]         = out.filter(_._1 == o).map(_._2).toVector

  private def member(i: Int, label: String = "ada sample"): BlockMember =
    BlockMember(
      "mention",
      f"mention:v1:$i%064x",
      s"work:dblp:x/$i",
      Some(0),
      label,
      None,
      Some(2000 + i),
      None,
      Nil,
      Nil,
      prov(SourceType.Dblp, s"x/$i")
    )

  test("mention and work block keys") {
    assertEquals(Blocking.mentionKey("ada sample"), Some("m|sample|a"))
    assertEquals(Blocking.mentionKey("collab member 07"), Some("m|member|c"))
    assertEquals(Blocking.mentionKey("sample"), Some("m|sample|"))
    assertEquals(Blocking.mentionKey("12 34"), None)
    assertEquals(
      Blocking.workKey("Disambiguating Homonymous Authors at Scale: H<sub>2</sub> x"),
      Blocking.workKey("Disambiguating Homonymous Authors at Scale: H2 Heuristics")
    )
    assertEquals(Blocking.workKey("   "), None)
  }

  test("exact keys: own source ID plus external IDs; sc1 IDs are not keys") {
    assertEquals(Blocking.ownKey("work:openalex:W1"), Some("openalex:W1"))
    assertEquals(Blocking.ownKey("work:sc1:" + "a" * 64), None)
    val w = WorkRecord(
      SchemaVersion.Current,
      "work:openalex:W1",
      "T",
      None,
      None,
      List(ExternalId(IdNamespace.Doi, "10.1/x")),
      Nil,
      prov(SourceType.OpenAlex, "W1")
    )
    val env = SourceEnvelope(
      "work",
      Some(NormalizedWork(
        WorkEnvelope("article", w, Nil),
        WorkExtra(w.workId, "article", None, None, Nil, w.provenance),
        Nil,
        Nil,
        Nil
      )),
      None
    )
    assertEquals(ExactIds.keys(env).map(_._1), List("work|doi:10.1/x", "work|openalex:W1"))
    assert(!ExactIds.missingKey(env))
    val bare = env.copy(work =
      env.work.map(nw =>
        nw.copy(envelope = nw.envelope.copy(work = w.copy(externalIds = Nil)))
      )
    )
    assert(ExactIds.missingKey(bare))
    // F3: a Crossref work is keyed by its DOI, a cross-source identifier, so it is not missing,
    // even with no other external ID; a sc1 work with no keys at all is.
    val cw = w.copy(
      workId = "work:doi:10.1/x",
      externalIds = List(ExternalId(IdNamespace.Doi, "10.1/x")),
      provenance = prov(SourceType.Crossref, "10.1/x")
    )
    val crossref = env.copy(work =
      env.work.map(nw =>
        nw.copy(envelope = nw.envelope.copy(work = cw))
      )
    )
    assert(!ExactIds.missingKey(crossref))
    val sc1 = env.copy(work =
      env.work.map(nw =>
        nw.copy(envelope =
          nw.envelope.copy(work =
            w.copy(workId = "work:sc1:" + "a" * 64, externalIds = Nil)
          )
        )
      )
    )
    assert(ExactIds.missingKey(sc1))
  }

  test("block stats carry the pipeline run ID and the member source datasets (F5)") {
    val rec = new Recorder
    val ms  = Iterator(
      member(1),
      member(2).copy(provenance = prov(SourceType.Crossref, "10.1/x")),
      member(3)
    )
    val stat = Blocking.processBlock("m|sample|a", ms, 10, "run-v1-abc", rec)
    assertEquals(stat.pipelineRunId, "run-v1-abc")
    assertEquals(stat.sourceDatasets, List(SourceType.Crossref, SourceType.Dblp))
    val json = upickle.default.writeJs(read[BlockStat](rec.lines("blockstats").head)).obj
    assertEquals(json("pipelineRunId").str, "run-v1-abc")
  }

  test("Job 7 composite key: partition and group on entity + partition key, sort by line (F1)") {
    val a = new Text(ExportKeys.composite("pairs", "w1", """{"b":2}"""))
    val b = new Text(ExportKeys.composite("pairs", "w1", """{"a":1}"""))
    val c = new Text(ExportKeys.composite("pairs", "w10", """{"a":0}"""))
    val g = new ExportGroupingComparator
    val p = new ExportPartitioner
    assertEquals(g.compare(a, b), 0)
    assert(g.compare(a, c) != 0)
    for n <- 1 to 7 do
      assertEquals(p.getPartition(a, a, n), p.getPartition(b, b, n))
      assertEquals(p.getPartition(a, a, n), WorkIdPartitioner.partition("w1", n))
    // The shuffle's byte order puts a group contiguous and its lines in order.
    val sorted = Vector(c, a, b).sortWith((x, y) => x.compareTo(y) < 0)
    assertEquals(
      sorted.map(t => ExportKeys.line(t.toString)),
      Vector("""{"a":1}""", """{"b":2}""", """{"a":0}""")
    )
    assertEquals(ExportKeys.partitionKey(a.toString), "w1")
    assertEquals(ExportKeys.composite("e", "x\ty", "l"), "e\tx y\tl") // no tab inside the group
  }

  test("Job 7 reduce streams: each line is emitted before the next value is read (F1)") {
    // Models Hadoop's reduce iterator, which advances the shared key object on each next().
    val lines = Vector("""{"a":1}""", """{"b":2}""", """{"c":3}""")
    val key   = new Text(ExportKeys.composite("pairs", "w1", lines.head))
    val log   = scala.collection.mutable.ArrayBuffer.empty[String]
    val it    = new java.util.Iterator[Text]:
      private var i        = 0
      def hasNext: Boolean = i < lines.size
      def next(): Text     =
        log += s"read $i"; key.set(ExportKeys.composite("pairs", "w1", lines(i))); i += 1
        new Text("")
    Export.streamGroup(key, it, (e, l) => log += s"emit $e $l")
    assertEquals(
      log.toVector,
      lines.zipWithIndex.flatMap((l, i) => Vector(s"read $i", s"emit pairs $l"))
    )
  }

  test("exact-ID groups: duplicates collapse, conflicts flag one source on two works") {
    val a  = IdMember("work:doi:10.1/x", "work:doi:10.1/x", prov(SourceType.Crossref, "10.1/x"))
    val b  = IdMember("work:openalex:W1", "work:openalex:W1", prov(SourceType.OpenAlex, "W1"))
    val b2 = b.copy(provenance = prov(SourceType.OpenAlex, "W0"))
    val c  = IdMember("work:openalex:W2", "work:openalex:W2", prov(SourceType.OpenAlex, "W2"))
    val g  = ExactIds.group("work|doi:10.1/x", Seq(b, a, b2))
    assertEquals(g.key, "doi:10.1/x")
    assertEquals(g.members.map(_.memberId), List("work:doi:10.1/x", "work:openalex:W1"))
    assertEquals(g.members(1).provenance.sourceRecordId, "W0") // smallest record ID kept
    assertEquals(g.attestations, 3)
    assert(!g.conflict)
    assert(ExactIds.group("work|doi:10.1/x", Seq(a, b, c)).conflict)
    // Many mentions sharing an ORCID are expected, never a conflict.
    val m1 = IdMember("mention:v1:1", "work:doi:10.1/x", prov(SourceType.Crossref, "10.1/x"))
    val m2 = IdMember("mention:v1:2", "work:doi:10.1/y", prov(SourceType.Crossref, "10.1/y"))
    assert(!ExactIds.group("mention|orcid:0000-0002-1825-0097", Seq(m1, m2)).conflict)
  }

  test("a block within the cap yields all n(n-1)/2 candidates with features, no decisions") {
    val rec  = new Recorder
    val stat =
      Blocking.processBlock("m|sample|a", (1 to 4).iterator.map(member(_)), 10, Fixtures.runId, rec)
    assertEquals(stat.size, 4L); assert(!stat.oversize); assertEquals(stat.chunks, 1)
    assertEquals(stat.candidatePairs, 6L); assertEquals(stat.crossChunkPairsSkipped, 0L)
    assertEquals(rec.counts.get(ScCounter.OVERSIZE_BLOCK), None)
    assertEquals(rec.counts(ScCounter.CANDIDATES), 6L)
    val cands = rec.lines("candidates").map(read[MatchCandidateRecord](_))
    assertEquals(cands.size, 6)
    assert(cands.forall(c => c.memberLow.compareTo(c.memberHigh) < 0))
    val f = cands.head.features.map(x => x.name -> x.value).toMap
    assertEquals(f("labelExact"), Some(1.0))
    assertEquals(f("yearGap"), Some(1.0))
    assertEquals(f("orcidEqual"), None)
    assertEquals(cands.head.provenanceLow.pipelineRunId, Fixtures.runId)
    // The candidate record has no decision, score, or threshold field.
    val names = upickle.default.writeJs(cands.head).obj.keySet
    assert(!names.exists(n => Seq("decision", "outcome", "score", "threshold").exists(n.contains)))
  }

  test("an oversize block counts OVERSIZE_BLOCK and is cut into bounded chunks") {
    val rec  = new Recorder
    val stat = Blocking.processBlock(
      "m|member|c",
      (1 to 21).iterator.map(member(_)),
      10,
      Fixtures.runId,
      rec
    )
    assertEquals(stat.size, 21L); assert(stat.oversize); assertEquals(stat.chunks, 3)
    assertEquals(stat.candidatePairs, 45L + 45L + 0L)
    assertEquals(stat.crossChunkPairsSkipped, 210L - 90L)
    assertEquals(rec.counts(ScCounter.OVERSIZE_BLOCK), 1L)
    assertEquals(rec.counts(ScCounter.BLOCKS), 1L)
    assertEquals(rec.lines("candidates").size, 90)
    assertEquals(rec.lines("blockstats").map(read[BlockStat](_)), Vector(stat))
    assert(rec.lines("candidates").map(read[MatchCandidateRecord](_)).forall(_.chunk < 3))
  }

  test("a repeated member ID counts DUPLICATE and is kept once") {
    val rec  = new Recorder
    val stat =
      Blocking.processBlock("k", Iterator(member(1), member(1), member(2)), 10, Fixtures.runId, rec)
    assertEquals(stat.size, 2L); assertEquals(rec.counts(ScCounter.DUPLICATE), 1L)
  }

  test("members of one work: a work key and one mention key per author, self excluded") {
    val wid = "work:dblp:x/1"
    val p   = prov(SourceType.Dblp, "x/1")
    val ms  = List("Ada Sample", "Bo Li").zipWithIndex.map((n, i) =>
      AuthorMention(
        SchemaVersion.Current,
        StableIds.mentionId(wid, i, SourceType.Dblp, "x/1"),
        wid,
        i,
        n,
        DblpRecordParser.normalizeName(n),
        None,
        None,
        p
      )
    )
    val env = WorkEnvelope(
      "article",
      WorkRecord(SchemaVersion.Current, wid, "A Title Here Now", Some(2020), None, Nil, Nil, p),
      ms
    )
    val got = Blocking.members(env)
    assertEquals(
      got.map(_._1),
      List(
        Some("w|a title here now"),
        Some("m|sample|a"),
        Some("m|li|b")
      )
    )
    assertEquals(got(1)._2.context, List("bo li"))
    assertEquals(got.head._2.context, List("li", "sample"))
  }

  test("block partitioner and grouping comparator use only the block key") {
    val a = new Text("m|sample|a\tmention:v1:1"); val b = new Text("m|sample|a\tmention:v1:2")
    val c = new Text("m|sample|ab\tmention:v1:0")
    assertEquals(new BlockGroupingComparator().compare(a, b), 0)
    assert(new BlockGroupingComparator().compare(a, c) != 0)
    val p = new BlockPartitioner
    for n <- 1 to 7 do assertEquals(p.getPartition(a, a, n), p.getPartition(b, b, n))
  }

  test("export partition key: workId, citingWorkId, workLow, then key") {
    assertEquals(Export.partitionKey("""{"workId":"w1","x":1}"""), "w1")
    assertEquals(Export.partitionKey("""{"oci":"1-2","citingWorkId":"w2"}"""), "w2")
    assertEquals(Export.partitionKey("""{"workLow":"w3"}"""), "w3")
    assertEquals(Export.partitionKey("""{"key":"doi:10.1/x"}"""), "doi:10.1/x")
    assertEquals(Export.partitionKey("""{"topicId":"topic:openalex:T1"}"""), "topic:openalex:T1")
    assertEquals(
      Export.partitionKey("""{"institutionId":"institution:openalex:I1"}"""),
      "institution:openalex:I1"
    )
    assert(Export.Entities.contains("topics") && Export.Entities.contains("institutions"))
    val p = new ExportPartitioner
    assertEquals(
      p.getPartition(new Text("works\tw1"), new Text(""), 4),
      p.getPartition(new Text("teams\tw1"), new Text(""), 4)
    )
  }
