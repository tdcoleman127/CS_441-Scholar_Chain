package edu.uic.cs441.scholarchain.hadoop

import scala.collection.mutable
import upickle.default.{read as decode, write}

/**
 * Job 6 reduce logic (`TeamReducer.process`) without a cluster, so it runs on every platform,
 * including Windows without HADOOP_HOME. The input is what Job 1's mapper would emit for the DBLP
 * samples: one WorkEnvelope JSON per accepted record, grouped by work ID. Review round 4, F2:
 * losing pair emission in the reducer must fail here, not only in LocalPipelineSuite.
 */
class TeamReducerSuite extends munit.FunSuite:
  import Fixtures.*

  final private class Recorder extends TeamReducer.Sink:
    val counters = mutable.Map.empty[ScCounter, Long].withDefaultValue(0L)
    val outputs  = mutable.Map.empty[String, Vector[String]].withDefaultValue(Vector.empty)
    def count(c: ScCounter, n: Long): Unit       = counters(c) += n
    def emit(output: String, json: String): Unit = outputs(output) :+= json

  /** Job 1 map side on both samples: accepted records as (workId, WorkEnvelope JSON). */
  private lazy val sampleEnvelopes: Vector[(String, String)] =
    Vector("dblp-sample.xml", "dblp-malformed.xml")
      .flatMap(f => scanAll(read(f), 1 << 20).map(_._2))
      .flatMap(r => DblpRecordParser.parse(r, entities, runId).toOption)
      .map(pw => pw.work.workId -> write(WorkEnvelope(pw.recordType, pw.work, pw.mentions)))

  private def reduceAll(groups: Map[String, Seq[String]], threshold: Int): Recorder =
    val rec = new Recorder
    groups.toVector.sortBy(_._1).foreach((_, vs) => TeamReducer.process(vs, threshold, rec))
    rec

  test("reducing the sample works emits 285 pairs, 35 author-papers, 7 teams, 4 oversize at 2") {
    val groups = sampleEnvelopes.groupMap(_._1)(_._2)
    assertEquals(groups.size, 7)
    val rec = reduceAll(groups, threshold = 2)
    assertEquals(rec.counters(ScCounter.ACCEPTED), 7L)
    assertEquals(rec.counters(ScCounter.TEAMS), 7L)
    assertEquals(rec.counters(ScCounter.DUPLICATE), 0L)
    assertEquals(rec.counters(ScCounter.OVERSIZE_TEAM), 4L)
    assertEquals(rec.counters(ScCounter.AUTHOR_PAPERS), 35L)
    assertEquals(rec.counters(ScCounter.EMITTED_PAIR), 285L)
    assertEquals(rec.outputs("teams").size, 7)
    assertEquals(rec.outputs("authorpapers").size, 35)
    assertEquals(rec.outputs("pairs").size, 285)
    assertEquals(rec.outputs.keySet.toSet, Set("teams", "authorpapers", "pairs"))

    // Every pair keeps work ID, year, both positions, and provenance; n(n-1)/2 per work.
    val teams = rec.outputs("teams").map(decode[TeamRecord](_)).map(t => t.workId -> t).toMap
    val pairs = rec.outputs("pairs").map(decode[MentionPairCandidate](_))
    assertEquals(pairs.map(p => (p.mentionLow, p.mentionHigh)).distinct.size, 285)
    pairs.foreach { p =>
      val t = teams(p.workId)
      assertEquals(p.year, t.year)
      assertEquals(p.provenance, t.provenance)
      assert(p.mentionLow.compareTo(p.mentionHigh) < 0, p)
      assertNotEquals(p.positionLow, p.positionHigh)
    }
    val perWork = pairs.groupBy(_.workId).view.mapValues(_.size.toLong).toMap
    teams.values.foreach(t =>
      assertEquals(perWork.getOrElse(t.workId, 0L), Teams.pairCount(t.teamSize), t.workId)
    )
    assertEquals(teams.values.map(_.teamSize).max, 24)
    assertEquals(perWork.values.max, 276L)
  }

  test("default threshold: no oversize team on the samples, pairs unchanged") {
    val rec = reduceAll(sampleEnvelopes.groupMap(_._1)(_._2), Conf.DefaultOversize)
    assertEquals(rec.counters(ScCounter.OVERSIZE_TEAM), 0L)
    assertEquals(rec.counters(ScCounter.EMITTED_PAIR), 285L)
  }

  test("duplicate envelopes for one work count DUPLICATE and do not double the pairs") {
    val (workId, json) = sampleEnvelopes.head
    val once           = reduceAll(Map(workId -> Seq(json)), 50)
    val twice          = reduceAll(Map(workId -> Seq(json, json)), 50)
    assertEquals(twice.counters(ScCounter.DUPLICATE), 1L)
    assertEquals(twice.counters(ScCounter.TEAMS), 1L)
    assertEquals(twice.outputs("pairs"), once.outputs("pairs"))
    assert(once.outputs("pairs").nonEmpty)
  }
