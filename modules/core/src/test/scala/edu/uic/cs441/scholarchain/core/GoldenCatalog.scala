package edu.uic.cs441.scholarchain.core

import edu.uic.cs441.scholarchain.core.validation.Validator
import edu.uic.cs441.scholarchain.core.validation.Validators.given
import upickle.default.ReadWriter

/** A frozen wire example: `src/test/resources/wire/<name>.json` must equal `value` encoded. */
final case class Golden[A](name: String, value: A)(using
    val rw: ReadWriter[A],
    val validator: Validator[A]
)

object GoldenCatalog:

  val all: List[Golden[?]] = List(
    Golden("run-manifest", Fixtures.manifest),
    Golden("work-record", Fixtures.work),
    Golden("author-mention", Fixtures.mention),
    Golden("canonical-researcher", Fixtures.researcher),
    Golden("authorship-record", Fixtures.authorship),
    Golden("publication-team", Fixtures.team),
    Golden("coauthor-pair-candidate", Fixtures.pairCandidate),
    Golden("coauthor-edge", Fixtures.coauthorEdge),
    Golden("researcher-metrics", Fixtures.metrics),
    Golden("venue", Fixtures.venue),
    Golden("institution", Fixtures.institution),
    Golden("topic", Fixtures.topic),
    Golden("match-decision", Fixtures.matchDecision),
    Golden("graph-node", Fixtures.graphNode),
    Golden("graph-edge", Fixtures.graphEdge),
    Golden("evidence-item", Fixtures.sourceEvidence),
    Golden("evidence-bundle", Fixtures.bundle),
    Golden("query-plan", Fixtures.queryPlan),
    Golden("answer-claim", Fixtures.claim),
    Golden("answer-response", Fixtures.answer)
  )
