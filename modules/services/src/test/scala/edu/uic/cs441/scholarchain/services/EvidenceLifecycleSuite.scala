package edu.uic.cs441.scholarchain.services

import edu.uic.cs441.scholarchain.core.ids.CanonicalHash
import edu.uic.cs441.scholarchain.services.TestKit.*
import edu.uic.cs441.scholarchain.storage.StorageError
import edu.uic.cs441.scholarchain.storage.kv.Table

class EvidenceLifecycleSuite extends munit.FunSuite:

  private val MissingEvidence = "evidence:v1:" + "0" * 64

  test("full query-to-evidence lifecycle over the fakes") {
    val e = env()
    // 1. Ask: a synchronous, evidence-backed answer.
    val answer = json(e.post(CoauthorsOfAlice))
    val qid    = answer("queryId").str
    val claim  = answer("claims").arr.head
    val evId   = claim("evidenceIds").arr.head.str
    assert(
      answer("evidence").arr.exists(_("evidenceId").str == evId),
      "claim cites in-bundle evidence"
    )

    // 2. The bundle was persisted through the storage-owned evidence-bundle table.
    assertEquals(e.fixture.keyValue.size(Table.EvidenceBundle), 1)

    // 3. Evidence: provenance, source record, pointer, graph path, bounded excerpt.
    val r = e.get(s"/v1/evidence/$evId")
    assertEquals(r.status, 200, r.body)
    val ev = json(r)
    assertEquals(ev("evidenceId").str, evId)
    assertEquals(ev("evidenceType").str, "graph-edge")
    assertEquals(ev("provenance")("runId").str, LocalFixture.RunId)
    assertEquals(ev("sourceDataset").str, "scholarchain-pipeline")
    assert(ev("sourceUri").str.startsWith("graph/coauthor-edges/v1#coedge:v1:"))
    assertEquals(ev("graphPath").arr.map(_.str).toSet, Set(LocalFixture.alice, LocalFixture.bob))
    val text = ev("text").str
    assert(text.length <= 600 && text.contains("COAUTHORED_WITH"))
    assertEquals(ev("provenance")("textHash").str, CanonicalHash.contentHash(text))
    assertEquals(ev("contextOnly").bool, false)

    // 4. The query's evidence list and the explain record agree with the answer.
    val list = json(e.get(s"/v1/queries/$qid/evidence"))
    assert(list("items").arr.exists(_("evidenceId").str == evId))
    val ex = json(e.get(s"/v1/explain/$qid"))
    assertEquals(ex("state").str, "SUCCEEDED")
    assertEquals(ex("outcome").str, "answered")
    assertEquals(ex("operation").str, "direct-coauthors")
    assert(ex("queryPlan")("planId").str.startsWith("plan:v1:"))
    assertEquals(ex("resolvedResearcherIds").arr.map(_.str).toList, List(LocalFixture.alice))
    assert(ex("evidenceIds").arr.exists(_.str == evId))
    assertEquals(ex("verification").str, "VERIFIED")
    assertEquals(ex("modelVersions")("synthesizer").str, "template-synthesizer-v1")
    assertEquals(ex("ruleVersions")("verifierRules").str, "claim-verifier-rules-v1")
    assert(ex("stagesExecuted").arr.map(_.str).contains("claim-verification"))
    assertEquals(ex("bundleStore").str, "written")
    assertEquals(ex("scoreComponents").arr.head("confidence").num, 1.0)
    assert(ex("counters")("downstreamCalls").num >= 1)
    // No hidden reasoning or prompt text is exposed.
    List("chainOfThought", "reasoning", "thoughts", "prompt\"").foreach(k =>
      assert(!ex.render().contains(k), k)
    )
  }

  test("missing evidence is 404; a malformed evidence ID is 400") {
    val e       = env()
    val missing = e.get(s"/v1/evidence/$MissingEvidence")
    assertEquals(missing.status, 404)
    assertEquals(json(missing)("error").str, "EVIDENCE_NOT_FOUND")
    assertEquals(json(missing)("traceId").str, missing.header("X-Trace-Id").get)
    val bad = e.get("/v1/evidence/not-an-evidence-id")
    assertEquals(bad.status, 400)
    assertEquals(json(bad)("error").str, "INVALID_PARAMETER")
  }

  test("an evidence-store failure during lookup is 502") {
    val e    = env()
    val evId = json(e.post(CoauthorsOfAlice))("claims").arr.head("evidenceIds").arr.head.str
    e.fixture.keyValue.failNext(List.fill(3)(StorageError.Unavailable("dynamodb", "500"))*)
    val r = e.get(s"/v1/evidence/$evId")
    assertEquals(r.status, 502, r.body)
    assertEquals(json(r)("error").str, "DOWNSTREAM_FAILURE")
    assertEquals(e.get(s"/v1/evidence/$evId").status, 200, "recovers once the store recovers")
  }

  test("a path answer exposes each hop's edge evidence") {
    val e = env()
    val r =
      e.post("""{"question": "What is the shortest path between Alice Adams and Carol Chen?"}""")
    assertEquals(r.status, 200, r.body)
    val j = json(r)
    assertEquals(j("status").str, "ANSWERED")
    val ev = j("claims").arr.head("evidenceIds").arr.map(_.str)
    assertEquals(ev.size, 2, "two hops, two edge evidence items")
    ev.foreach(id => assertEquals(e.get(s"/v1/evidence/$id").status, 200))
    val off = e.post(
      """{"question": "What is the shortest path between Alice Adams and Carol Chen?", "analysis": {"includePaths": false}}"""
    )
    assertEquals(json(off)("error").str, "CONSTRAINT_CONFLICT")
  }
