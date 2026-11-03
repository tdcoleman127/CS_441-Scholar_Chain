package edu.uic.cs441.scholarchain.storage

import edu.uic.cs441.scholarchain.core.model.YearWindow
import edu.uic.cs441.scholarchain.storage.fakes.{
  InMemoryGraphStore,
  InMemoryKeyValueStore,
  InMemoryObjectStore
}
import edu.uic.cs441.scholarchain.storage.graph.*
import edu.uic.cs441.scholarchain.storage.kv.{RejectRecord, RunLedgerTable, Table}

class NeptuneGraphSuite extends munit.FunSuite:
  import Fixtures.*

  private val ab = coauthorEdge(alice, bob, List("conf/a/1", "conf/a/2"), 2001, 2005)
  private val bc = coauthorEdge(bob, carol, List("conf/b/1"), 2010, 2010)
  private val cd = coauthorEdge(carol, dave, List("conf/c/1"), 2012, 2012)

  private def input(edges: Seq[edu.uic.cs441.scholarchain.core.model.CoauthorEdge] = Seq(ab, bc)) =
    BulkLoadInput(
      runId,
      policy,
      Seq(researcher(alice, "Alice"), researcher(bob, "Bob"), researcher(carol, "Carol")),
      Seq(work("conf/a/1", "Graphs, \"quoted\"\nand more", 2001), work("conf/a/2", "Second", 2005)),
      Seq(authorship(alice, "conf/a/1", 0, 2001), authorship(bob, "conf/a/1", 1, 2001)),
      edges
    )

  test("bulk load: openCypher CSV headers carry required properties and provenance") {
    val plan   = NeptuneBulkLoad.plan(input()).toOption.get
    val co     = plan.csvFiles.find(_.key.value.endsWith("rel-coauthored-with.csv")).get
    val header = co.content.linesIterator.next()
    List(
      ":START_ID",
      ":END_ID",
      ":TYPE",
      "jointPublicationCount:Int",
      "firstYear:Int",
      "lastYear:Int",
      "windowStart:Int",
      "windowEnd:Int",
      "evidencePointer:String",
      "sourceDataset:String",
      "sourceRecordId:String",
      "pipelineRunId:String"
    ).foreach(h =>
      assert(header.contains(h), h)
    )
    assertEquals(co.rows, 2)
    assert(co.content.contains(s"\"${plan.evidence.key.value}#${ab.edgeId}\""))
    assert(
      co.content.contains(s"\"${ab.edgeId}\",\"${ab.researcherLow}\",\"${ab.researcherHigh}\"")
    )
    assert(plan.csvFiles.forall(_.key.value.startsWith(
      s"graph/neptune-bulk-load/v1/run=$runId/csv/"
    )))
    assert(!plan.manifest.key.value.contains("/csv/") && !plan.evidence.key.value.contains("/csv/"))
  }

  test("bulk load: CSV escaping keeps commas, quotes, and newlines inside one field") {
    val plan  = NeptuneBulkLoad.plan(input()).toOption.get
    val works = plan.csvFiles.find(_.key.value.endsWith("nodes-work.csv")).get
    assertEquals(works.content.linesIterator.size, 3) // header + 2 rows; newline flattened
    assert(works.content.contains("\"Graphs, \"\"quoted\"\" and more\""))
  }

  test("bulk load: coauthor-edge endpoint validation rejects edges to unknown researchers") {
    val plan = NeptuneBulkLoad.plan(input(Seq(ab, bc, cd))).toOption.get
    val co   = plan.csvFiles.find(_.key.value.endsWith("rel-coauthored-with.csv")).get
    assertEquals(co.rows, 2)
    assertEquals(
      plan.rejects.map(r => (r.scope, r.key, r.reason)),
      List(("graph:COAUTHORED_WITH", cd.edgeId, "endpoint not in load"))
    )
  }

  test(
    "bulk load: invalid edges (bad edgeId, inverted pair) and conflicting duplicates are rejected"
  ) {
    val badId    = ab.copy(edgeId = bc.edgeId)
    val inverted = bc.copy(researcherLow = bc.researcherHigh, researcherHigh = bc.researcherLow)
    val conflict = ab.copy(confidence = 0.1)
    val plan     = NeptuneBulkLoad.plan(input(Seq(ab, conflict, badId, inverted))).toOption.get
    val co       = plan.csvFiles.find(_.key.value.endsWith("rel-coauthored-with.csv")).get
    assertEquals(co.rows, 0)
    assertEquals(plan.rejects.size, 4)
    assert(plan.rejects.exists(_.reason == "conflicting duplicate ID"))
  }

  test(
    "bulk load writer: several rejects under one ID are all recorded; load completes; replay is idempotent"
  ) {
    val badId    = ab.copy(edgeId = bc.edgeId)
    val inverted = bc.copy(researcherLow = bc.researcherHigh, researcherHigh = bc.researcherLow)
    val conflict = ab.copy(confidence = 0.1)
    val carolDup = researcher(carol, "Caroline") // same researcherId, other name and provenance
    val in       = input(Seq(ab, conflict, badId, inverted))
    val plan = NeptuneBulkLoad.plan(in.copy(researchers = in.researchers :+ carolDup)).toOption.get
    val sharedEdge = plan.rejects.filter(_.key == bc.edgeId)
    assertEquals(sharedEdge.map(_.provenance).distinct.size, 2, "badId and inverted share an ID")
    val sharedNode = plan.rejects.filter(_.key == carol)
    assertEquals(sharedNode.map(_.provenance).toSet, Set(prov("r/Carol"), prov("r/Caroline")))

    val store  = InMemoryObjectStore()
    val kv     = InMemoryKeyValueStore()
    val ledger = RunLedgerTable(kv, RetryPolicy.NoRetry)
    val writer = NeptuneBulkLoadWriter(store, ledger, RetryPolicy.NoRetry)
    val first  = writer.write(plan)
    assert(first.isRight, s"writer must not abort on its own rejects: $first")
    assert(first.toOption.get.forall(_._2 == WriteOutcome.Written))
    assertEquals(first.toOption.get.last._1, plan.manifest.key, "manifest is written last")
    assertEquals(store.keys.size, 6)

    def asTuple(r: RejectRecord) =
      (r.scope, r.key, r.reason, r.provenance)
    val stored = ledger.rejects(runId, 100).toOption.get
    assertEquals(
      stored.map(asTuple).toSet,
      plan.rejects.map(r => (r.scope, r.key, r.reason, r.provenance)).toSet,
      "every distinct reject is stored with its own provenance"
    )
    assertEquals(stored.size, stored.map(asTuple).distinct.size)
    assertEquals(stored.filter(_.key == bc.edgeId).map(_.provenance).distinct.size, 2)
    assertEquals(stored.filter(_.key == carol).map(_.provenance).distinct.size, 2)

    val ledgerBefore = kv.size(Table.RunLedger)
    val replay       = writer.write(plan)
    assert(replay.toOption.get.forall(_._2 == WriteOutcome.AlreadyPresent), s"$replay")
    assertEquals(kv.size(Table.RunLedger), ledgerBefore)
    assertEquals(
      ledger.rejects(runId, 100).map(_.map(asTuple).toSet),
      Right(stored.map(asTuple).toSet)
    )
    plan.rejects.foreach { r =>
      assertEquals(
        ledger.recordReject(RejectRecord(runId, r.scope, r.key, r.reason, r.provenance)),
        Right(WriteOutcome.AlreadyPresent)
      )
    }
  }

  test("bulk load: deterministic output and idempotent replay through the writer") {
    val p1 = NeptuneBulkLoad.plan(input()).toOption.get
    val p2 = NeptuneBulkLoad.plan(input().copy(coauthorEdges = Seq(bc, ab))).toOption.get
    assertEquals(p1.csvFiles.map(_.contentHash), p2.csvFiles.map(_.contentHash))
    val store  = InMemoryObjectStore()
    val writer = NeptuneBulkLoadWriter(store, RunLedgerTable(InMemoryKeyValueStore()))
    val first  = writer.write(p1).toOption.get
    assert(first.forall(_._2 == WriteOutcome.Written))
    assertEquals(first.last._1, p1.manifest.key, "manifest is written last")
    val second = writer.write(p2).toOption.get
    assert(second.forall(_._2 == WriteOutcome.AlreadyPresent))
    assertEquals(store.keys.size, 6)
  }

  test("bulk load: AUTHORED edges use gedge:v1 IDs and keep mention provenance") {
    val plan = NeptuneBulkLoad.plan(input()).toOption.get
    val au   = plan.csvFiles.find(_.key.value.endsWith("rel-authored.csv")).get
    assertEquals(au.rows, 2)
    assert(au.content.linesIterator.drop(1).forall(_.startsWith("\"gedge:v1:")))
    val manifest = ujson.read(plan.manifest.content)
    assertEquals(manifest("format").str, "opencypher")
    assertEquals(manifest("files").arr.size, 4)
  }

  test("queries: templates are constants and every input is a parameter") {
    val hostile = "researcher:sc1:x' OR 1=1 //"
    assert(GraphQueries.directCoauthors(hostile, window, 10).isLeft)
    val q = GraphQueries.directCoauthors(alice, window, 10).toOption.get
    assertEquals(q.cypher.text, GraphQueries.DirectCoauthorsTemplate)
    assert(!q.cypher.text.contains(alice))
    assertEquals(q.cypher.parameters("researcherId"), Param.Str(alice))
    assertEquals(q.cypher.timeoutMillis, 5000)
    val p = GraphQueries.boundedPaths(alice, carol, window, 3, 5).toOption.get
    assert(GraphQueries.BoundedPathTemplates(2) eq p.cypher.text)
    assert(p.cypher.text.contains("*1..3") && !p.cypher.text.contains(alice))
  }

  test("queries: limit, depth, timeout, and window are bounded") {
    assert(GraphQueries.directCoauthors(alice, window, 0).isLeft)
    assert(GraphQueries.directCoauthors(alice, window, 101).isLeft)
    assert(GraphQueries.directCoauthors(alice, window, 10, timeoutMillis = 10001).isLeft)
    assert(GraphQueries.directCoauthors(alice, YearWindow(2020, 2000), 10).isLeft)
    assert(GraphQueries.boundedPaths(alice, carol, window, 5, 5).isLeft)
    assert(GraphQueries.boundedPaths(alice, carol, window, 2, 21).isLeft)
    assert(GraphQueries.boundedPaths(alice, alice, window, 2, 5).isLeft)
  }

  test("fake graph: direct coauthors and bounded paths honor limits and depth") {
    val g   = InMemoryGraphStore(Seq(ab, bc, cd), "graph/ev.jsonl")
    val q   = GraphQueries.directCoauthors(bob, window, 1).toOption.get
    val top = g.directCoauthors(q).toOption.get
    assertEquals(top.map(_.coauthorId), List(alice)) // 2 joint works beats 1; limit 1
    assertEquals(top.head.evidencePointer, s"graph/ev.jsonl#${ab.edgeId}")
    val short = GraphQueries.boundedPaths(alice, dave, window, 2, 5).toOption.get
    assertEquals(g.boundedPaths(short), Right(Nil))
    val deep = GraphQueries.boundedPaths(alice, dave, window, 3, 5).toOption.get
    assertEquals(
      g.boundedPaths(deep).map(_.map(_.nodeIds)),
      Right(List(List(alice, bob, carol, dave)))
    )
  }

  test("fake graph: injected timeout surfaces as a typed error") {
    val g = InMemoryGraphStore(Seq(ab), "graph/ev.jsonl")
    g.failNext(StorageError.Timeout("neptune:openCypher", "5000 ms"))
    val q = GraphQueries.directCoauthors(alice, window, 5).toOption.get
    assertEquals(g.directCoauthors(q), Left(StorageError.Timeout("neptune:openCypher", "5000 ms")))
  }
