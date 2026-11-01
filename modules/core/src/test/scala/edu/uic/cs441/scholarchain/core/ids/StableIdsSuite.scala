package edu.uic.cs441.scholarchain.core.ids

import edu.uic.cs441.scholarchain.core.Fixtures
import edu.uic.cs441.scholarchain.core.model.*
import edu.uic.cs441.scholarchain.core.validation.ValidationError
import java.nio.charset.StandardCharsets

class CanonicalHashSuite extends munit.FunSuite:

  private def ascii(b: Array[Byte]) = String(b, StandardCharsets.UTF_8)

  test("fields are length-prefixed, so different splits of the same characters differ") {
    assertEquals(ascii(CanonicalHash.encode(Seq("ab", "c"))), "2:ab1:c")
    assertEquals(ascii(CanonicalHash.encode(Seq("a", "bc"))), "1:a2:bc")
    assertNotEquals(
      CanonicalHash.hashFields(Seq("ab", "c")),
      CanonicalHash.hashFields(Seq("a", "bc"))
    )
  }

  test("an absent field (empty string) is distinct from a present one") {
    assertEquals(ascii(CanonicalHash.encode(Seq("", "0"))), "0:1:0")
    assertNotEquals(CanonicalHash.hashFields(Seq("x", "")), CanonicalHash.hashFields(Seq("x", "0")))
  }

  test("lengths count UTF-8 bytes, not characters") {
    assertEquals(ascii(CanonicalHash.encode(Seq("é"))), "2:é")
  }

  test("strings are NFC-normalized before hashing") {
    val composed   = "José"
    val decomposed = "José"
    assertEquals(CanonicalHash.hashFields(Seq(decomposed)), CanonicalHash.hashFields(Seq(composed)))
    assertEquals(CanonicalHash.contentHash(decomposed), CanonicalHash.contentHash(composed))
  }

  test("sha256Hex matches the FIPS 180-2 test vector") {
    assertEquals(
      CanonicalHash.sha256Hex("abc".getBytes(StandardCharsets.US_ASCII)),
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    )
  }

  test("contentHash is sha256:<hex> of the raw text") {
    assertEquals(
      CanonicalHash.contentHash("abc"),
      "sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    )
  }

class StableIdsSuite extends munit.FunSuite:
  import Fixtures.*

  private def right[A](e: Either[?, A]): A = e.fold(err => fail(s"unexpected: $err"), identity)

  // ---- Frozen values (independently recomputed with sha256sum over the documented encoding) --

  test("frozen: ScholarChain researcher ID formula") {
    assertEquals(
      researcherB,
      "researcher:sc1:2288d5bc36f4eebcf91c1e7399f902b26ee76295b5936b58f6203e1321341600"
    )
  }

  test("frozen: coauthor edge ID formula") {
    assertEquals(
      StableIds.coauthorEdgeId(pair, YearWindow(2020, 2024), "graph-policy-v1"),
      "coedge:v1:4bfb121e18887a2417239d000ce0f94096c78b12b130841f5b9176557f215a16"
    )
  }

  test("frozen: other hashed ID formulas") {
    assertEquals(runId, "run:v1:40bb9cfda64123cbe54be17ae30f7340ee0958ff3d7c1a4056926fbb08069875")
    assertEquals(
      mention.mentionId,
      "mention:v1:6b7f517df8cd9fa35a83284f23aab20ccfc256d34cff069b741416b9af94779b"
    )
    assertEquals(
      graphEdge.edgeId,
      "gedge:v1:641b42a8d957263c5038b43e5909fc277a10cd827c8542d53dcd79938a50360d"
    )
    assertEquals(
      sourceEvidence.evidenceId,
      "evidence:v1:76b5ffce76941206c355ce3b2ce217875bafa014c8479ef6073e5fb623ba0fcc"
    )
  }

  test("frozen: graph edge ID with an absent window hashes the window as two empty fields") {
    assertEquals(
      StableIds.graphEdgeId(
        GraphEdgeType.CoauthoredWith,
        researcherB,
        researcherA,
        "graph-policy-v1",
        None
      ),
      "gedge:v1:e70e1b95addee47cec0dc9a550f62b7a9aaa19c36a3c347f1ca093702ed2af22"
    )
  }

  // ---- Work and other namespaced IDs ------------------------------------------------------

  test("work ID is the namespace plus the normalized authoritative source ID") {
    assertEquals(workId, "work:dblp:conf/kdd/CarberrySmith24")
    assertEquals(
      right(StableIds.workId(IdNamespace.Doi, " https://doi.org/10.5555/ABC.1 ")),
      "work:doi:10.5555/abc.1"
    )
    assertEquals(
      right(StableIds.workId(IdNamespace.Doi, "doi:10.5555/ABC.1")),
      "work:doi:10.5555/abc.1"
    )
    assertEquals(
      right(StableIds.workId(IdNamespace.Arxiv, "arXiv:2101.00001v2")),
      "work:arxiv:2101.00001v2"
    )
    assertEquals(
      right(StableIds.workId(IdNamespace.OpenAlex, "https://openalex.org/W123")),
      "work:openalex:W123"
    )
  }

  test("every DOI resolver prefix variant normalizes to the same DOI (any scheme/host case)") {
    val variants = Seq(
      "https://doi.org/10.1000/ABC",
      "http://doi.org/10.1000/ABC",
      "https://dx.doi.org/10.1000/ABC",
      "http://dx.doi.org/10.1000/ABC",
      "HTTP://DX.DOI.ORG/10.1000/ABC",
      "Https://Dx.Doi.Org/10.1000/ABC",
      "HTTPS://DOI.ORG/10.1000/ABC",
      "doi:10.1000/ABC",
      "DOI:10.1000/ABC",
      " http://dx.doi.org/10.1000/ABC ",
      "10.1000/ABC"
    )
    variants.foreach { raw =>
      assertEquals(IdFormat.normalizeSourceId(IdNamespace.Doi, raw), Right("10.1000/abc"), raw)
      assertEquals(right(StableIds.workId(IdNamespace.Doi, raw)), "work:doi:10.1000/abc", raw)
    }
  }

  test("DOI prefix stripping does not loosen validation of the DOI body") {
    val bad = Seq(
      "http://dx.doi.org/",
      "http://dx.doi.org/11.1000/abc",
      "http://dx.doi.org/10.1000",
      "http://dx.doi.org/10.1000/a b",
      "http://dx.doi.org/http://doi.org/10.1000/abc",
      "doi:doi:10.1000/abc",
      "http://www.dx.doi.org/10.1000/abc",
      "ftp://dx.doi.org/10.1000/abc",
      "http:/dx.doi.org/10.1000/abc"
    )
    bad.foreach(raw => assert(IdFormat.normalizeSourceId(IdNamespace.Doi, raw).isLeft, raw))
  }

  test("work ID rejects unusable source IDs and namespaces the kind does not allow") {
    assert(StableIds.workId(IdNamespace.Doi, "not-a-doi").isLeft)
    assert(StableIds.workId(IdNamespace.Dblp, "has space").isLeft)
    assert(StableIds.workId(IdNamespace.Dblp, "   ").isLeft)
    assert(StableIds.workId(IdNamespace.Ror, "0sample00").isLeft)
    assert(StableIds.workId(IdNamespace.ScholarChainV1, "abc").isLeft)
  }

  test("omid: OpenCitations bibliographic-resource OMIDs normalize to br/<digits>") {
    val expected = "work:omid:br/0699900000001"
    assertEquals(right(StableIds.workId(IdNamespace.Omid, "omid:br/0699900000001")), expected)
    assertEquals(right(StableIds.workId(IdNamespace.Omid, " OMID:BR/0699900000001 ")), expected)
    assertEquals(
      right(StableIds.workId(IdNamespace.Omid, "https://w3id.org/oc/meta/br/0699900000001")),
      expected
    )
    assertEquals(right(StableIds.workId(IdNamespace.Omid, "br/0699900000001")), expected)
    assert(IdFormat.isValid(IdKind.Work, expected))
    List("omid:ra/0699900000001", "omid:br/", "omid:br/06x1", "06101801781", "omid:br/1 2")
      .foreach(bad => assert(StableIds.workId(IdNamespace.Omid, bad).isLeft, bad))
    assert(!IdFormat.isValid(IdKind.Work, "work:omid:BR/0699900000001"))
    assert(!IdFormat.isValid(IdKind.Work, "work:omid:omid:br/0699900000001"))
    assert(StableIds.namespacedId(IdKind.Venue, IdNamespace.Omid, "br/1").isLeft)
  }

  test("pmid: PubMed IDs normalize to a positive integer without leading zeros") {
    val expected = "work:pmid:33817056"
    assertEquals(right(StableIds.workId(IdNamespace.Pmid, "pmid:33817056")), expected)
    assertEquals(right(StableIds.workId(IdNamespace.Pmid, "PMID:33817056")), expected)
    assertEquals(right(StableIds.workId(IdNamespace.Pmid, "33817056")), expected)
    assertEquals(right(StableIds.workId(IdNamespace.Pmid, "0033817056")), expected)
    assertEquals(
      right(StableIds.workId(IdNamespace.Pmid, "https://pubmed.ncbi.nlm.nih.gov/33817056/")),
      expected
    )
    assertEquals(
      right(StableIds.workId(IdNamespace.Pmid, "http://pubmed.ncbi.nlm.nih.gov/33817056")),
      expected
    )
    assert(IdFormat.isValid(IdKind.Work, expected))
    List("pmid:", "0", "pmid:PMC123", "33817056a", "-1", "3381 7056")
      .foreach(bad => assert(StableIds.workId(IdNamespace.Pmid, bad).isLeft, bad))
    assert(!IdFormat.isValid(IdKind.Work, "work:pmid:033817056"))
    assert(StableIds.namespacedId(IdKind.Institution, IdNamespace.Pmid, "1").isLeft)
  }

  test("oci: an Open Citation Identifier normalizes to <digits>-<digits>, without its prefix") {
    assertEquals(
      IdFormat.normalizeOci("oci:06101801781-06180334099"),
      Right("06101801781-06180334099")
    )
    assertEquals(
      IdFormat.normalizeOci(" OCI:0699900000001-0699900000002 "),
      Right("0699900000001-0699900000002")
    )
    assertEquals(
      IdFormat.normalizeOci("0699900000001-0699900000002"),
      Right("0699900000001-0699900000002")
    )
    List("", "oci:", "0699900000001", "06999-", "a-1", "1-2-3", "1 - 2")
      .foreach(bad => assert(IdFormat.normalizeOci(bad).isLeft, bad))
    assert(IdNamespace.fromWire("oci").isEmpty, "an OCI is a record key, not an ID namespace")
  }

  test("fallback work ID is deterministic, hashed, and well-formed") {
    val a = StableIds.fallbackWorkId(SourceType.Crossref, "record-1")
    assertEquals(a, StableIds.fallbackWorkId(SourceType.Crossref, "record-1"))
    assertNotEquals(a, StableIds.fallbackWorkId(SourceType.OpenAlex, "record-1"))
    assert(a.startsWith("work:sc1:"))
    assert(IdFormat.isValid(IdKind.Work, a))
  }

  // ---- Researcher IDs ------------------------------------------------------------------------

  test("researcher ID uses a validated ORCID") {
    assertEquals(
      right(StableIds.researcherIdFromOrcid("https://orcid.org/0000-0002-1694-233x")),
      "researcher:orcid:0000-0002-1694-233X"
    )
    assertEquals(researcherA, "researcher:orcid:0000-0002-1825-0097")
  }

  test("researcher ID rejects an ORCID with a bad checksum or shape") {
    assert(StableIds.researcherIdFromOrcid("0000-0002-1825-0098").isLeft)
    assert(StableIds.researcherIdFromOrcid("0000000218250097").isLeft)
    assert(!IdFormat.isValid(IdKind.Researcher, "researcher:orcid:0000-0002-1825-0098"))
  }

  test("ScholarChain identity ID is versioned and rejects unsupported versions and blank anchors") {
    assert(researcherB.startsWith("researcher:sc1:"))
    assert(clue(StableIds.scholarChainResearcherId(2, SourceType.Dblp, "x")).isLeft)
    assert(StableIds.scholarChainResearcherId(1, SourceType.Dblp, " ").isLeft)
    assert(!IdFormat.isValid(IdKind.Researcher, researcherB.replace(":sc1:", ":sc2:")))
  }

  // ---- Hashed IDs ----------------------------------------------------------------------------

  test("coauthor edge ID depends on the window and the policy version") {
    val base = StableIds.coauthorEdgeId(pair, window, graphPolicy)
    assertNotEquals(base, StableIds.coauthorEdgeId(pair, YearWindow(2020, 2025), graphPolicy))
    assertNotEquals(base, StableIds.coauthorEdgeId(pair, window, "graph-policy-v2"))
  }

  test("coauthor edge ID is independent of argument order (pair canonicalization)") {
    val ab = right(ResearcherPair.of(researcherA, researcherB))
    val ba = right(ResearcherPair.of(researcherB, researcherA))
    assertEquals(
      StableIds.coauthorEdgeId(ab, window, graphPolicy),
      StableIds.coauthorEdgeId(ba, window, graphPolicy)
    )
  }

  test("graph edge ID: undirected endpoints are canonicalized; direction matters otherwise") {
    val t = GraphEdgeType.CoauthoredWith
    val w = Some(window)
    assertEquals(
      StableIds.graphEdgeId(t, researcherA, researcherB, graphPolicy, w),
      StableIds.graphEdgeId(t, researcherB, researcherA, graphPolicy, w)
    )
    assertNotEquals(
      StableIds.graphEdgeId(GraphEdgeType.Cites, workId, workId2, graphPolicy, w),
      StableIds.graphEdgeId(GraphEdgeType.Cites, workId2, workId, graphPolicy, w)
    )
  }

  test("graph edge ID: two edges that differ only in evidenceIds get the same edgeId") {
    val accumulated = graphEdge.copy(
      evidenceIds = List(sourceEvidence.evidenceId, edgeEvidence.evidenceId).sorted
    )
    assertNotEquals(accumulated.evidenceIds, graphEdge.evidenceIds)
    assertEquals(StableIds.graphEdgeId(accumulated), StableIds.graphEdgeId(graphEdge))
    assertEquals(StableIds.graphEdgeId(accumulated), graphEdge.edgeId)
  }

  test("graph edge ID depends on type, endpoints, policy version, and window") {
    val t    = GraphEdgeType.CoauthoredWith
    val base = StableIds.graphEdgeId(t, researcherA, researcherB, graphPolicy, Some(window))
    assertNotEquals(
      base,
      StableIds.graphEdgeId(t, researcherA, researcherB, "graph-policy-v2", Some(window))
    )
    assertNotEquals(
      base,
      StableIds.graphEdgeId(t, researcherA, researcherB, graphPolicy, Some(YearWindow(2020, 2025)))
    )
    assertNotEquals(
      base,
      StableIds.graphEdgeId(t, researcherA, researcherB, graphPolicy, Some(YearWindow(2021, 2024)))
    )
    assertNotEquals(base, StableIds.graphEdgeId(t, researcherA, researcherB, graphPolicy, None))
    assertNotEquals(
      StableIds.graphEdgeId(GraphEdgeType.Cites, workId, workId2, graphPolicy, None),
      StableIds.graphEdgeId(GraphEdgeType.HasTopic, workId, workId2, graphPolicy, None)
    )
    assert(IdFormat.isValid(IdKind.GraphEdge, base))
  }

  test("chunk ID: absent ordinal differs from ordinal 0; text hash and document type matter") {
    val h  = StableIds.textHash("profile text")
    val c0 = StableIds.chunkId(researcherA, "v1", DocumentType.ResearcherProfile, None, h)
    assertNotEquals(
      c0,
      StableIds.chunkId(researcherA, "v1", DocumentType.ResearcherProfile, Some(0), h)
    )
    assertNotEquals(c0, StableIds.chunkId(researcherA, "v1", DocumentType.WorkAbstract, None, h))
    assertNotEquals(
      c0,
      StableIds.chunkId(
        researcherA,
        "v1",
        DocumentType.ResearcherProfile,
        None,
        StableIds.textHash("other")
      )
    )
    assert(IdFormat.isValid(IdKind.Chunk, c0))
  }

  test("run ID is deterministic, ignores input order, and changes with attempt") {
    val m = manifest
    assertEquals(StableIds.runId(m.copy(inputs = m.inputs.reverse)), m.runId)
    assertNotEquals(StableIds.runId(m.copy(attempt = 2)), m.runId)
    assertNotEquals(StableIds.runId(m.copy(codeVersion = "git-1")), m.runId)
    assert(IdFormat.isValid(IdKind.Run, m.runId))
  }

  test("claim and bundle IDs ignore evidence order") {
    val ids = List(sourceEvidence.evidenceId, edgeEvidence.evidenceId)
    assertEquals(StableIds.claimId("t", ids), StableIds.claimId(" t ", ids.reverse))
    assertEquals(StableIds.evidenceBundleId(ids), StableIds.evidenceBundleId(ids.reverse))
  }

  test("evidence ID does not depend on the pipeline run, so reruns keep evidence stable") {
    val p = sourceEvidence.provenance
    assertEquals(
      sourceEvidence.evidenceId,
      StableIds.evidenceId(
        sourceEvidence.evidenceType,
        sourceEvidence.subjectId,
        sourceEvidence.contentHash,
        p.sourceDataset,
        p.sourceRecordId
      )
    )
  }

class IdFormatSuite extends munit.FunSuite:
  import Fixtures.*

  test("every fixture ID is well-formed for its kind") {
    assert(IdFormat.isValid(IdKind.Work, workId))
    assert(IdFormat.isValid(IdKind.Work, workId2))
    assert(IdFormat.isValid(IdKind.Venue, venueId))
    assert(IdFormat.isValid(IdKind.Topic, topicId))
    assert(IdFormat.isValid(IdKind.Institution, institutionId))
    assert(IdFormat.isValid(IdKind.Researcher, researcherA))
    assert(IdFormat.isValid(IdKind.Researcher, researcherB))
    assert(IdFormat.isValid(IdKind.CoauthorEdge, coauthorEdge.edgeId))
    assert(IdFormat.isValid(IdKind.ContentHash, sourceEvidence.contentHash))
  }

  test("malformed IDs are rejected") {
    val hex = "a" * 64
    val bad = List(
      IdKind.Run          -> s"run:v2:$hex",
      IdKind.Run          -> s"run:v1:${"A" * 64}",
      IdKind.Run          -> s"run:v1:${"a" * 63}",
      IdKind.Run          -> s"evidence:v1:$hex",
      IdKind.Run          -> "",
      IdKind.CoauthorEdge -> s"coedge:v1:$hex:extra",
      IdKind.Work         -> "work:doi:10.5555/UPPER",
      IdKind.Work         -> "work:ror:0sample00",
      IdKind.Work         -> "work:dblp:",
      IdKind.Work         -> "work:dblp:has space",
      IdKind.Work         -> "work:sc1:nothex",
      IdKind.Work         -> "dblp:conf/kdd/x",
      IdKind.Venue        -> "venue:issn:1234-567",
      IdKind.Institution  -> "institution:ror:https://ror.org/0sample00",
      IdKind.Researcher   -> "researcher:orcid:0000-0002-1825-0098",
      IdKind.Researcher   -> s"researcher:sc0:$hex",
      IdKind.Researcher   -> "researcher:dblp:homepages/s/JaneSmith",
      IdKind.ContentHash  -> s"md5:$hex"
    )
    bad.foreach((kind, v) => assert(!IdFormat.isValid(kind, v), s"accepted $kind '$v'"))
  }

  test("isAnyKnown accepts any kind and rejects free text") {
    assert(IdFormat.isAnyKnown(workId))
    assert(IdFormat.isAnyKnown(graphEdge.edgeId))
    assert(!IdFormat.isAnyKnown("Josiah Carberry"))
  }

class ResearcherPairSuite extends munit.FunSuite:
  import Fixtures.*

  test("canonical order is lexicographic and independent of argument order") {
    val ab = ResearcherPair.of(researcherA, researcherB)
    assertEquals(ab, ResearcherPair.of(researcherB, researcherA))
    assert(researcherA < researcherB)
    assertEquals(ab.map(p => (p.low, p.high)), Right((researcherA, researcherB)))
  }

  test("self-pairs are rejected") {
    assertEquals(
      ResearcherPair.of(researcherA, researcherA),
      Left(ValidationError.SelfPair("", researcherA))
    )
    assert(ResearcherPair.fromOrdered(researcherA, researcherA).isLeft)
  }

  test("malformed researcher IDs are rejected") {
    assert(ResearcherPair.of("Josiah Carberry", researcherB).isLeft)
  }

  test("an inverted stored pair is rejected, a canonical one accepted") {
    assertEquals(
      ResearcherPair.fromOrdered(researcherB, researcherA),
      Left(List(ValidationError.InvalidPairOrdering("researcherLow", researcherB, researcherA)))
    )
    assertEquals(
      ResearcherPair.fromOrdered(researcherA, researcherB).toOption,
      ResearcherPair.of(researcherB, researcherA).toOption
    )
  }
