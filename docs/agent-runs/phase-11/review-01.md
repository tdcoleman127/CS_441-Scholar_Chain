# Phase 11 review 01: code-reviewer (full repository)

Assembled by the orchestrator from the code-reviewer final report, 2026-11-13. Spec review work order: docs/project-description.md:1705-1727. HEAD 8bb4cbc plus test-engineer's tests-only diff.

VERDICT: REQUEST_CHANGES (on the repository; the tests-only diff is in test-engineer's territory and is kept). No BLOCKER.

Reviewer evidence: `sbt -batch contractTests/test` printed "Failed: Total 155, Failed 9, Errors 0, Passed 146" (124 s), with exactly the 9 known red tests. `bash -n scripts/run-bounded-pipeline.sh` is clean. infra/terraform holds only `.gitkeep`. The reviewer did not run `sbt check`.

## A. The 9 red tests

| ID | Verdict | Note |
|---|---|---|
| D1 | CONFIRMED | ER writes rejected.jsonl (er/Outputs.scala:81-87); PartA never reads it (spark/PartA.scala:138-139, 186-188); transitive closure at :192-204. The closure also merges across unresolved pairs, so the repair must decide whether rejected or unresolved pairs veto the closure. |
| D2 | CONFIRMED | Presenter.scala:59-61 builds the map with `.toMap` (last duplicate wins); copied to the view at :66. |
| D3 | CONFIRMED | Resolver.scala:150-160 renormalizes over the computable features: 0.70/0.75 = 0.9333 ≥ 0.85, so Accepted. See M1. |
| D4 | CONFIRMED | StableIds.scala:268-273 (ID from evidence IDs only); Evidence.scala:201-206 (payload carries planId); Tables.scala:236-242 (`putIfAbsent` compares payloads). |
| D5 | CONFIRMED | SemanticContext.scala:46-50 prints metadata unquoted; :131 takes it unvalidated. |
| D6 | CONFIRMED | Synthesis.scala:60-67: the checks run outside the `Try`. |
| R-09-11 | CONFIRMED (location wrong) | GraphRagPipeline.scala:249; the logic is at ClaimVerifier.scala:52-71 (V1 gate :62), not :231-247 (the file has 187 lines). |
| N2 | CONFIRMED | Records.scala:192 vs the doc at :142-144. |
| CO-4 | CONFIRMED | DblpXmlInputFormat.scala:112-115. Stays LOW (DBLP does not normally use CDATA). |

## B. New findings

| ID | Severity | Area | Location | Summary | Owner |
|---|---|---|---|---|---|
| M1 | MINOR | 2, 6 | er GoldWorksheet.scala:373, Features.scala:75-76 vs hadoop/Blocking.scala:158-160, 178, HadoopCandidates.scala:75 | The evaluation path (`PairInput.fromViews`) gives empty coauthor sets coauthorOverlap=None. The Hadoop production path gives 0.0. The same J. Smith pair scores 0.933 Accepted on the view path and 0.6875 Unresolved on the Hadoop path. D3 is reachable today through gold evaluation and EntityView drivers, not ResolveMain, and the gold pilot F1 0.514 measures a path production does not run. | entity-resolution-engineer |
| D3-floor | MINOR (fold into D3) | 6 | er-policy-v1.conf:30 | min-evidence-weight 0.5 is below the name plus initials weight of 0.55, so name and initials alone pass the floor (J. Smith vs J. Smith, no year or coauthors, disjoint titles: 0.917 Accepted). Raising `accept` alone cannot fix this. | entity-resolution-engineer |
| M2 | MINOR (fold into D2) | 8, 9 | Presenter.scala:76-79 (also the :71, :73 pattern) | `collectFirst` shows a CONFLICTING Bridge fact in `researchers[].bridgeMetric` (duplicate graph-metrics rows; "Who are the bridge researchers?"). Fix every `collectFirst` presentation. | service-engineer |
| M3 | MINOR (fold into D5) | 4, 8 | SemanticContext.scala:49; storage VectorStore.scala:127-133; Evidence.scala:195 | `pipelineRunId` is unquoted and unvalidated, a second injection vector. It also makes the semantic evidence fail `encodeValidated`, so bundleStore reports "failed". | rag-engineer |
| M4 | MINOR (D6 class) | 1, 8 | GraphExports.scala:105-108, 115, 131-137, 144-150; Validators.scala:189 | A `null` export line, `"provenance": null`, or `"memberIds": null` throws NPE instead of returning Left. Startup fails closed via Main.scala:110, but `stores.handle.close()` (Main.scala:67) is skipped, and Wire.decodeValue's gate order is bypassed. | rag-engineer |
| D6-nested | MINOR (fold into D6) | 8 | ClaimVerifier.scala:55 | Nested nulls (`"evidenceIds": null`) decode as Right and later throw. The decoder must validate deeply. | rag-engineer |
| M5 | MINOR | 10 | tests ApiAdversarialSuite.scala:340-369 | The N3 test does not tell the orderings apart: swapping QueryService.scala:192/193 still passes, but the report claims "N3 ordering now protected". Needs a store decorator that holds the timeout write until the worker finishes. (Reasoned from the code; the reviewer could not run the mutation.) | test-engineer |
| NIT-1 | NIT | 10 | tests EntityResolutionAdversarialSuite.scala:116 | The confusable test checks only reasonCodes; assert `status != Accepted`. | test-engineer |
| NIT-2 | NIT | 9, 10 | services Router.scala:97; tests ApiAdversarialSuite.scala:171-189 | The Content-Type prefix match accepts `application/jsonp`; the test never probes a prefix. | service-engineer (impl), test-engineer (test) |

Areas 1, 4, 5, and 7: nothing new beyond the red tests and the known backlog. Area 3: no ownership violations; phase-11/prompt.md is empty; docs/project-description.md ownership is a human question. Area 11: no project doc contradicts the code; services docs are missing (DOC-10-1). Area 12: no Terraform; run-bounded-pipeline.sh is bounded (MAX_STEPS=2, timeout ≤ 3600 s).

## C. Severity changes

R-05-3 agreed as MAJOR via D3 (qualified by M1). D1 is a new precision failure of the identity closure (R-06a-11 is unchanged). Nothing else changed.

## Routing (reviewer proposal)

- spark-engineer: D1 (with entity-resolution-engineer on negative links)
- entity-resolution-engineer: D3 plus the floor note, M1
- service-engineer: D2 plus M2, N2, NIT-2 (impl)
- rag-engineer: D4, D5 plus M3, D6 plus nested nulls, R-09-11, M4
- hadoop-engineer: CO-4
- test-engineer: M5, NIT-1, NIT-2 (test); correct the R-09-11 line reference

Then a test-engineer rerun in a fresh context and code-reviewer review-02.

Manual action: the human decides the ownership of docs/project-description.md.
