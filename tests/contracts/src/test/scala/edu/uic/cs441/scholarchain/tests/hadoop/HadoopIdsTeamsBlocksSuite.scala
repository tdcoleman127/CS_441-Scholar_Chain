package edu.uic.cs441.scholarchain.tests.hadoop

import edu.uic.cs441.scholarchain.core.json.Wire
import edu.uic.cs441.scholarchain.core.model.*
import edu.uic.cs441.scholarchain.hadoop.*
import edu.uic.cs441.scholarchain.tests.hadoop.AdvFixtures.*
import org.apache.hadoop.io.Text
import upickle.default.{read, write}

/**
 * Phase 04 adversarial tests, priorities 3 to 7: exact IDs with duplicate and conflicting DOIs, a
 * giant team, oversize blocks, provenance on every record type, and Job 7 co-partitioning.
 */
class HadoopIdsTeamsBlocksSuite extends munit.FunSuite:

  private def dblpEnv(key: String, ees: Seq[String], authors: Seq[String] = Seq("Ada Lovelace")) =
    Envelopes.parse(write(envelope(parseOk(dblpRecord(key, authors, ees = ees)))))

  private def groupsOf(envs: Seq[SourceEnvelope]): Map[String, ExactIdGroup] =
    envs.flatMap(ExactIds.keys).groupMap(_._1)(_._2).map((k, ms) => k -> ExactIds.group(k, ms))

  // ---- 3. exact IDs: duplicate DOIs and conflicting identifiers ------------------------------

  test("one DOI spelled four ways across DBLP, OpenAlex, Crossref, arXiv joins under one key") {
    val envs = Seq(
      dblpEnv("journals/tst/D1", Seq("https://doi.org/10.1000/ABC")),
      normalizeOk(SourceType.OpenAlex, openAlexLine), // https://doi.org/10.1000/Abc
      normalizeOk(SourceType.Crossref, crossrefLine), // 10.1000/ABC
      normalizeOk(SourceType.Arxiv, arxivRecord)      // 10.1000/abc
    )
    val g = groupsOf(envs)("work|doi:10.1000/abc")
    assertEquals(g.scope, "work")
    assertEquals(
      g.members.map(_.provenance.sourceDataset).toSet,
      Set(SourceType.Dblp, SourceType.OpenAlex, SourceType.Crossref, SourceType.Arxiv)
    )
    assertEquals(g.members.map(_.workId).distinct.size, 4)
    assert(!g.conflict, "different sources linking one DOI is a link, not a conflict")
    envs.foreach(e => assert(!ExactIds.missingKey(e), s"has a DOI: ${e.key}"))
  }

  test("DBLP ee with the legacy http://dx.doi.org/ resolver keeps its DOI (cross-source join)") {
    val p =
      parseOk(dblpRecord("journals/tst/D2", Seq("A B"), ees = Seq("http://dx.doi.org/10.1000/ABC")))
    assertEquals(p.work.externalIds, List(ExternalId(IdNamespace.Doi, "10.1000/abc")))
  }

  test("one source putting one DOI on two works is a CONFLICT; a repeated attestation is not") {
    val a = dblpEnv("journals/tst/C1", Seq("https://doi.org/10.1000/dup"))
    val b = dblpEnv("journals/tst/C2", Seq("https://doi.org/10.1000/DUP"))
    val g = groupsOf(Seq(a, b, a))("work|doi:10.1000/dup")
    assert(g.conflict)
    assertEquals(
      g.members.map(_.memberId),
      List("work:dblp:journals/tst/C1", "work:dblp:journals/tst/C2")
    )
    assertEquals(g.attestations, 3)
    val only = groupsOf(Seq(a, a, a))("work|doi:10.1000/dup")
    assert(!only.conflict)
    assertEquals(only.members.size, 1)
    assertEquals(only.attestations, 3)
  }

  test("two OpenAlex works with one DOI conflict; group output is permutation-invariant") {
    val w2   = openAlexLine.replace("W100", "W200")
    val envs =
      Seq(normalizeOk(SourceType.OpenAlex, openAlexLine), normalizeOk(SourceType.OpenAlex, w2))
    val ms = envs.flatMap(ExactIds.keys).filter(_._1 == "work|doi:10.1000/abc").map(_._2)
    val g1 = ExactIds.group("work|doi:10.1000/abc", ms)
    val g2 = ExactIds.group("work|doi:10.1000/abc", ms.reverse)
    assert(g1.conflict)
    assertEquals(g1, g2)
    assertEquals(write(g1), write(g2))
  }

  test("citation endpoints join the DOI key without conflicting with Crossref") {
    val g = groupsOf(Seq(
      normalizeOk(SourceType.Crossref, crossrefLine),
      normalizeOk(SourceType.OpenCitations, citationLine)
    ))("work|doi:10.1000/abc")
    assertEquals(
      g.members.map(m => m.provenance.sourceDataset -> m.workId).toSet,
      Set(
        SourceType.Crossref      -> "work:doi:10.1000/abc",
        SourceType.OpenCitations -> "work:omid:br/061"
      )
    )
    assert(!g.conflict)
  }

  test("ORCID keys: case and URL forms collapse; a bad checksum never becomes a key") {
    val oa  = normalizeOk(SourceType.OpenAlex, openAlexLine)
    val cr  = normalizeOk(SourceType.Crossref, crossrefLine)
    val key = "mention|orcid:0000-0002-1825-0097"
    val g   = groupsOf(Seq(oa, cr))(key)
    assertEquals(g.members.size, 2)
    assertEquals(g.scope, "mention")
    assert(!g.conflict, "mention scope never flags conflict")
    val bad = normalizeOk(
      SourceType.OpenAlex,
      openAlexLine.replace("0000-0002-1825-0097", "0000-0002-1825-0098")
    )
    assert(!ExactIds.keys(bad).exists(_._1.startsWith("mention|orcid:")))
    assertEquals(
      bad.work.get.mentionExtras.head.invalidOrcid,
      Some("https://orcid.org/0000-0002-1825-0098")
    )
  }

  test("missing key: DBLP without a DOI is missing; with one it is not") {
    assert(ExactIds.missingKey(dblpEnv("journals/tst/M1", Nil)))
    assert(!ExactIds.missingKey(dblpEnv("journals/tst/M2", Seq("https://doi.org/10.1/x"))))
  }

  // ---- 4. giant team ---------------------------------------------------------------------------

  private val giant: WorkEnvelope =
    envelope(parseOk(dblpRecord(
      "journals/tst/G300",
      (0 until 300).map(i => f"Author$i%03d Person$i%03d")
    )))

  test("a 300-author team yields 44850 canonical, distinct pairs and counts OVERSIZE_TEAM once") {
    val rec = new Recorder
    TeamReducer.process(Seq(write(giant)), 50, rec)
    val pairs = rec.lines("pairs").map(read[MentionPairCandidate](_))
    assertEquals(Teams.pairCount(300), 44850L)
    assertEquals(pairs.size, 44850)
    assertEquals(rec.get(ScCounter.EMITTED_PAIR), 44850L)
    assertEquals(rec.get(ScCounter.OVERSIZE_TEAM), 1L)
    assertEquals(rec.get(ScCounter.TEAMS), 1L)
    assertEquals(rec.get(ScCounter.AUTHOR_PAPERS), 300L)
    assertEquals(rec.lines("authorpapers").size, 300)
    assertEquals(pairs.map(p => (p.mentionLow, p.mentionHigh)).distinct.size, 44850)
    assert(pairs.forall(p => p.mentionLow.compareTo(p.mentionHigh) < 0), "low < high violated")
    val pos = giant.mentions.map(m => m.mentionId -> m.authorPosition).toMap
    assert(pairs.forall(p =>
      pos(p.mentionLow) == p.positionLow && pos(p.mentionHigh) == p.positionHigh
    ))
    assert(pairs.forall(p => p.workId == giant.work.workId && p.year == Some(2020)))
    val team = read[TeamRecord](rec.lines("teams").head)
    assert(team.oversize)
    assertEquals(team.teamSize, 300)
    assertEquals(team.mentionIds, giant.mentions.sortBy(_.authorPosition).map(_.mentionId))
  }

  test("oversize threshold boundary: 300 authors at threshold 300 is not oversize, at 299 it is") {
    for (t, flag) <- Seq(300 -> false, 299 -> true) do
      val rec = new Recorder
      TeamReducer.process(Seq(write(giant)), t, rec)
      assertEquals(rec.get(ScCounter.OVERSIZE_TEAM), if flag then 1L else 0L, s"threshold $t")
      assertEquals(rec.get(ScCounter.EMITTED_PAIR), 44850L, "oversize teams still emit pairs")
  }

  test("pair counts do not overflow for huge teams") {
    assertEquals(Teams.pairCount(100000), 4999950000L)
    assertEquals(Teams.pairCount(Int.MaxValue), Int.MaxValue.toLong * (Int.MaxValue - 1) / 2)
    assertEquals(Teams.pairCount(-3), 0L)
  }

  // ---- 5. oversize blocks ----------------------------------------------------------------------

  private def bm(i: Int): BlockMember =
    BlockMember(
      "mention",
      f"mention:v1:$i%064x",
      s"work:dblp:x/$i",
      Some(0),
      "ada sample",
      None,
      Some(2000),
      None,
      Nil,
      Nil,
      Provenance(SourceType.Dblp, s"x/$i", runId)
    )

  test(
    "a 1234-member block at cap 100: no chunk exceeds the cap, chunks are disjoint, counts add up"
  ) {
    val n = 1234; val cap = 100
    // Consecutive duplicates are mixed in, as a sorted reducer input would deliver them.
    val in = (0 until n).iterator.flatMap(i =>
      if i % 97 == 0 then Iterator(bm(i), bm(i)) else Iterator(bm(i))
    )
    val rec     = new Recorder
    val st      = Blocking.processBlock("m|sample|a", in, cap, runId, rec)
    val cs      = rec.lines("candidates").map(read[MatchCandidateRecord](_))
    val byChunk = cs.groupBy(_.chunk).view.mapValues(v =>
      v.flatMap(c => Seq(c.memberLow, c.memberHigh)).toSet
    ).toMap
    assert(byChunk.values.forall(_.size <= cap), s"chunk sizes ${byChunk.values.map(_.size)}")
    val all = byChunk.values.toSeq
    for i <- all.indices; j <- all.indices if i < j do
      assert((all(i) & all(j)).isEmpty, "chunks overlap")
    val expectedPairs = 12L * (cap * (cap - 1) / 2) + 34L * 33 / 2
    assertEquals(st.size, n.toLong)
    assertEquals(st.chunks, 13)
    assert(st.oversize)
    assertEquals(st.candidatePairs, expectedPairs)
    assertEquals(cs.size.toLong, expectedPairs)
    assertEquals(rec.get(ScCounter.CANDIDATES), expectedPairs)
    assertEquals(st.crossChunkPairsSkipped, n.toLong * (n - 1) / 2 - expectedPairs)
    assertEquals(rec.get(ScCounter.OVERSIZE_BLOCK), 1L)
    assertEquals(rec.get(ScCounter.DUPLICATE), (0 until n).count(_ % 97 == 0).toLong)
    assertEquals(rec.get(ScCounter.BLOCKS), 1L)
    assert(cs.forall(c => c.memberLow.compareTo(c.memberHigh) < 0))
    assertEquals(st.pipelineRunId, runId)
    assertEquals(st.sourceDatasets, List(SourceType.Dblp))
  }

  test(
    "block cap boundaries: exactly the cap is one chunk; cap 2 pairs neighbours only; cap 1 fails"
  ) {
    val r1 = new Recorder
    val s1 = Blocking.processBlock("k", (0 until 10).iterator.map(bm), 10, runId, r1)
    assertEquals(
      (s1.chunks, s1.oversize, s1.candidatePairs, s1.crossChunkPairsSkipped),
      (1, false, 45L, 0L)
    )
    val r2 = new Recorder
    val s2 = Blocking.processBlock("k", (0 until 5).iterator.map(bm), 2, runId, r2)
    assertEquals((s2.chunks, s2.oversize, s2.candidatePairs), (3, true, 2L))
    intercept[IllegalArgumentException](Blocking.processBlock(
      "k",
      Iterator(bm(0)),
      1,
      runId,
      new Recorder
    ))
  }

  // ---- 6. provenance on every emitted record type ---------------------------------------------

  private def checkProv(what: String, p: Provenance, src: SourceType, id: String): Unit =
    assertEquals(p.sourceDataset, src, what)
    assertEquals(p.sourceRecordId, id, what)
    assertEquals(p.pipelineRunId, runId, what)

  test(
    "provenance: every DBLP-derived record (work, mention, team, author-paper, pair, block member)"
  ) {
    val key = "journals/tst/P1"
    val env = envelope(parseOk(dblpRecord(
      key,
      Seq("Ada Lovelace", "Charles Babbage"),
      ees = Seq("https://doi.org/10.1/p")
    )))
    checkProv("work", env.work.provenance, SourceType.Dblp, key)
    env.mentions.foreach(m => checkProv("mention", m.provenance, SourceType.Dblp, key))
    val rec = new Recorder
    TeamReducer.process(Seq(write(env)), 50, rec)
    rec.lines("teams").map(read[TeamRecord](_)).foreach(t =>
      checkProv("team", t.provenance, SourceType.Dblp, key)
    )
    rec.lines("authorpapers").map(read[AuthorPaperRecord](_)).foreach(a =>
      checkProv("authorpaper", a.provenance, SourceType.Dblp, key)
    )
    val ps = rec.lines("pairs").map(read[MentionPairCandidate](_))
    assertEquals(ps.size, 1)
    ps.foreach(p => checkProv("pair", p.provenance, SourceType.Dblp, key))
    Blocking.members(env).foreach((_, m) =>
      checkProv(s"block member ${m.scope}", m.provenance, SourceType.Dblp, key)
    )
    ExactIds.keys(Envelopes.parse(write(env))).foreach((k, m) =>
      checkProv(s"exact id $k", m.provenance, SourceType.Dblp, key)
    )
  }

  test("provenance: every Job 2 record type (work, mention, extras, topics, citation, candidate)") {
    val oaLine = openAlexLine.replace(
      "\"type\":\"article\"",
      "\"type\":\"article\",\"topics\":[{\"id\":\"https://openalex.org/T1\",\"display_name\":\"Graphs\"}]"
    )
    val cases = Seq(
      (SourceType.OpenAlex, oaLine, "W100"),
      (SourceType.Crossref, crossrefLine, "10.1000/abc"),
      (SourceType.Arxiv, arxivRecord, "2001.00001")
    )
    for (src, text, id) <- cases do
      val nw = normalizeOk(src, text).work.get
      checkProv(s"$src work", nw.envelope.work.provenance, src, id)
      nw.envelope.mentions.foreach(m => checkProv(s"$src mention", m.provenance, src, id))
      checkProv(s"$src workextra", nw.extra.provenance, src, id)
      nw.mentionExtras.foreach(x => checkProv(s"$src mentionextra", x.provenance, src, id))
      nw.topics.foreach(t => checkProv(s"$src topic", t.provenance, src, id))
      nw.institutions.foreach(i => checkProv(s"$src institution", i.provenance, src, id))
      assert(nw.topics.nonEmpty || src == SourceType.Crossref, s"$src topics expected")
    val c = normalizeOk(SourceType.OpenCitations, citationLine).citation.get
    checkProv("citation", c.provenance, SourceType.OpenCitations, "061-062")
    val a =
      Blocking.members(normalizeOk(SourceType.Crossref, crossrefLine).work.get.envelope).last._2
    val b  = Blocking.members(dblpEnv("journals/tst/P9", Nil).work.get.envelope).last._2
    val mc = Blocking.candidate("m|lovelace|a", 0, a, b)
    Seq(mc.provenanceLow, mc.provenanceHigh).foreach(p => assertEquals(p.pipelineRunId, runId))
    assertEquals(
      Set(mc.provenanceLow.sourceDataset, mc.provenanceHigh.sourceDataset),
      Set(SourceType.Crossref, SourceType.Dblp)
    )
  }

  // ---- 7. Job 7 co-partitioning ----------------------------------------------------------------

  test("Job 7: one work's works, mentions, extras, teams, author-papers, pairs share a partition") {
    val oa   = normalizeOk(SourceType.OpenAlex, openAlexLine).work.get
    val dblp = envelope(parseOk(dblpRecord("journals/tst/J7", Seq("A B", "C D", "E F"))))
    val rec  = new Recorder
    TeamReducer.process(Seq(write(dblp)), 50, rec)
    val byWork: Seq[(String, Seq[(String, String)])] = Seq(
      oa.envelope.work.workId ->
        (Seq("works" -> Wire.encode(oa.envelope.work), "workextras" -> write(oa.extra)) ++
          oa.envelope.mentions.map(m => "mentions" -> Wire.encode(m)) ++
          oa.mentionExtras.map(x => "mentionextras" -> write(x))),
      dblp.work.workId ->
        (Seq("works" -> Wire.encode(dblp.work)) ++
          dblp.mentions.map(m => "mentions" -> Wire.encode(m)) ++
          rec.out.toSeq)
    )
    val part = new ExportPartitioner
    for (workId, lines) <- byWork do
      assertEquals(
        lines.map(_._1).toSet.size >= 4,
        true,
        s"entities for $workId: ${lines.map(_._1).toSet}"
      )
      for (entity, line) <- lines do
        assertEquals(
          Export.partitionKey(line),
          workId,
          s"$entity partition key (empty key would hide skew)"
        )
      for n <- Seq(1, 2, 3, 7, 16, 64, 1000) do
        val ps = lines.map((e, l) =>
          part.getPartition(
            new Text(ExportKeys.composite(e, Export.partitionKey(l), l)),
            new Text(""),
            n
          )
        )
        assertEquals(ps.distinct.size, 1, s"$workId split across partitions with n=$n: $ps")
        assertEquals(ps.head, WorkIdPartitioner.partition(workId, n))
  }

  test(
    "Job 7 composite key: Unicode and escaped control characters round-trip; grouping is per entity"
  ) {
    val w    = normalizeOk(SourceType.OpenAlex, openAlexLine).work.get.envelope.work
    val line = Wire.encode(w.copy(title = "Gr\taph \u00fc\n\u0001 end"))
    assert(!line.contains('\t'), "JSON line must not contain a raw tab")
    val k = ExportKeys.composite("workextras", "work:openalex:W100", line)
    assertEquals(ExportKeys.line(k), line)
    assertEquals(ExportKeys.partitionKey(k), "work:openalex:W100")
    assertEquals(ExportKeys.group(k), "workextras\twork:openalex:W100")
    val bytes = new Text(k)
    assertEquals(
      ExportKeys.groupLength(bytes.getBytes, bytes.getLength),
      "workextras\twork:openalex:W100".getBytes("UTF-8").length
    )
    val cmp = new ExportGroupingComparator
    val k2  = new Text(ExportKeys.composite("workextras", "work:openalex:W100", line + " "))
    val k3  = new Text(ExportKeys.composite("works", "work:openalex:W100", line))
    assertEquals(cmp.compare(bytes, k2), 0)
    assertNotEquals(cmp.compare(bytes, k3), 0)
  }
