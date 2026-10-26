package edu.uic.cs441.scholarchain.core.model

import upickle.default.{read, write, ReadWriter}

/** Freezes every enum wire value. Changing one is a schema-version change. */
class EnumWireSuite extends munit.FunSuite:

  private def frozen[E: ReadWriter](
      name: String,
      values: Seq[E],
      wire: E => String,
      expected: List[String]
  ): Unit =
    test(s"$name wire values are frozen and round-trip") {
      assertEquals(values.map(wire).toList, expected)
      values.foreach { v =>
        assertEquals(write(v), "\"" + wire(v) + "\"")
        assertEquals(read[E](write(v)), v)
      }
      intercept[Exception](read[E]("\"no-such-value\""))
    }

  frozen(
    "SourceType",
    SourceType.values.toSeq,
    _.wire,
    List(
      "dblp",
      "openalex",
      "opencitations",
      "crossref",
      "arxiv",
      "orcid",
      "semantic-scholar",
      "papers-with-code",
      "scholarchain-pipeline",
      "scholarchain-runtime",
      "human-curation"
    )
  )
  frozen(
    "MatchOutcome",
    MatchOutcome.values.toSeq,
    _.wire,
    List("match", "non-match", "needs-review", "merge", "split")
  )
  frozen(
    "MatchDecider",
    MatchDecider.values.toSeq,
    _.wire,
    List("rule", "model", "llm-adjudication", "human")
  )
  frozen(
    "EvidenceType",
    EvidenceType.values.toSeq,
    _.wire,
    List("graph-node", "graph-edge", "graph-path", "source-record", "text-chunk", "metric")
  )
  frozen(
    "CollaborationMetric",
    CollaborationMetric.values.toSeq,
    _.wire,
    List(
      "degree",
      "weighted-degree",
      "coauthored-work-count",
      "new-coauthor-count",
      "repeat-collaboration-rate",
      "component-size",
      "core-number",
      "clustering-coefficient",
      "betweenness-centrality",
      "bridge-edge-count",
      "pagerank"
    )
  )
  frozen(
    "QueryOperation",
    QueryOperation.values.toSeq,
    _.wire,
    List(
      "resolve-researcher",
      "find-coauthors",
      "collaboration-timeline",
      "common-coauthors",
      "shortest-path",
      "researcher-metrics",
      "find-bridges",
      "community-members",
      "vector-search",
      "fetch-evidence"
    )
  )
  frozen(
    "VerificationStatus",
    VerificationStatus.values.toSeq,
    _.wire,
    List("supported", "partially-supported", "unsupported", "contradicted", "not-verified")
  )
  frozen(
    "GraphNodeType",
    GraphNodeType.values.toSeq,
    _.wire,
    List("Researcher", "Work", "Venue", "Institution", "Topic")
  )
  frozen(
    "GraphEdgeType",
    GraphEdgeType.values.toSeq,
    _.wire,
    List("COAUTHORED_WITH", "AUTHORED", "PUBLISHED_IN", "AFFILIATED_WITH", "HAS_TOPIC", "CITES")
  )
  frozen(
    "VenueKind",
    VenueKind.values.toSeq,
    _.wire,
    List("conference", "journal", "workshop", "preprint", "other")
  )
  frozen(
    "DocumentType",
    DocumentType.values.toSeq,
    _.wire,
    List(
      "researcher-profile",
      "work-abstract",
      "work-metadata",
      "collaboration-summary",
      "evidence-path"
    )
  )
  frozen(
    "IdNamespace",
    IdNamespace.values.toSeq,
    _.wire,
    List(
      "doi",
      "dblp",
      "openalex",
      "arxiv",
      "s2",
      "pwc",
      "issn",
      "ror",
      "orcid",
      "sc1",
      "omid",
      "pmid"
    )
  )

  test("only COAUTHORED_WITH is undirected") {
    assertEquals(
      GraphEdgeType.values.filter(_.undirected).toList,
      List(GraphEdgeType.CoauthoredWith)
    )
  }
