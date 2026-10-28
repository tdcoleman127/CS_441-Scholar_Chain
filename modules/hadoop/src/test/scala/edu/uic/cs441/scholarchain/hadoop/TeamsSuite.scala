package edu.uic.cs441.scholarchain.hadoop

import edu.uic.cs441.scholarchain.core.SchemaVersion
import edu.uic.cs441.scholarchain.core.ids.StableIds
import edu.uic.cs441.scholarchain.core.model.*

class TeamsSuite extends munit.FunSuite:
  private val key  = "journals/x/T1"
  private val work = "work:dblp:journals/x/T1"
  private val prov = Provenance(SourceType.Dblp, key, Fixtures.runId)

  private def env(n: Int): WorkEnvelope =
    val ms = (0 until n).toList.map(i =>
      AuthorMention(
        SchemaVersion.Current,
        StableIds.mentionId(work, i, SourceType.Dblp, key),
        work,
        i,
        s"Author $i",
        s"author $i",
        None,
        None,
        prov
      )
    )
    WorkEnvelope(
      "article",
      WorkRecord(SchemaVersion.Current, work, "T.", Some(2020), None, Nil, Nil, prov),
      ms.reverse
    )

  test("pair count is n(n-1)/2 for n = 0, 1, 2, 3, and a large team") {
    for n <- Seq(0, 1, 2, 3, 120) do
      val ps = Teams.pairsFor(env(n)).toList
      assertEquals(ps.size.toLong, Teams.pairCount(n), s"n=$n")
      assertEquals(ps.map(p => (p.mentionLow, p.mentionHigh)).distinct.size, ps.size)
    assertEquals(Teams.pairCount(100000), 4999950000L)
  }

  test("pairs are canonical and carry work ID, year, positions, and provenance") {
    val ps = Teams.pairsFor(env(3)).toList
    ps.foreach { p =>
      assert(p.mentionLow.compareTo(p.mentionHigh) < 0)
      assertEquals(p.workId, work); assertEquals(p.year, Some(2020));
      assertEquals(p.provenance, prov)
      assertNotEquals(p.positionLow, p.positionHigh)
    }
    assertEquals(ps.flatMap(p => Seq(p.positionLow, p.positionHigh)).toSet, Set(0, 1, 2))
    assertEquals(Teams.pairsFor(env(3)).toList, ps) // deterministic
  }

  test("team record is ordered by author position and flags oversize teams") {
    val t = Teams.team(env(4), oversizeThreshold = 3)
    assertEquals(t.normalizedNames, List("author 0", "author 1", "author 2", "author 3"))
    assert(t.oversize)
    assert(!Teams.team(env(3), oversizeThreshold = 3).oversize)
    assertEquals(Teams.authorPapers(env(4)).map(_.authorPosition), List(0, 1, 2, 3))
  }
