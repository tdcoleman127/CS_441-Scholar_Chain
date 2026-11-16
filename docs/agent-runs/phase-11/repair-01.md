# Phase 11 repair-01: rag-engineer

Assembled by the orchestrator from the rag-engineer report (received 2026-11-13, before the session ended). Items: D4, D5 + M3, D6 + nested nulls, R-09-11, M4 (review-01.md).

Orchestrator spot check (2026-11-14, observed): `git diff --stat HEAD` shows only modules/rag main files (Evidence, GraphExports, GraphRagPipeline, RagVersions, Synthesis, SemanticContext) and RuntimeComponentsSuite, plus test-engineer's earlier HadoopMalformedInputSuite change. New: modules/rag/src/test/.../Phase11RepairSuite.scala. No tests/** file was edited by rag-engineer.

STATUS: COMPLETE for rag scope.

| Item | Fix | Feature test (Phase11RepairSuite) | Red test |
|---|---|---|---|
| D4 | The stored bundle depends only on what its ID covers: items in canonical order (graph, then semantic, each sorted by ID); provenance `(scholarchain-runtime, bundleId, release run)`; planId removed from the bundle (the audit links query to bundle). rag-evidence-bundle-v2. No core ID change. | "two wordings ... store one bundle; replay is idempotent"; "core bundle independent of retrieval order" | StorageFaultSuite D4 PASS |
| D5 + M3 | Every hit read back from the index is re-validated (chunk key, `VectorMetadata.validate`, core provenance, embedder modelVersion, window); failures are dropped with a reason. Every header field is quoted and escaped on one line, with delimiters neutralized. Invalid semantic items stay out of the persisted bundle. semantic-context-format-v2. | "forged metadata ... dropped" (6 fields); "every header field quoted and escaped"; "M3: invalid provenance never enters the bundle" | PromptInjectionSuite D5 PASS |
| D6 + nested | `DraftAnswer.decode` is total (one outer Try, null rejected) followed by a deep `validate` (no nulls at any depth, finite metrics, schema, at most 25 claims). The pipeline wraps the synthesizer in Try and validates its output; failures give `Abstained(SynthesisFailed)`. | "nulls at any depth ... typed Lefts" (11 inputs); "nested nulls or exceptions abstain" (8 cases) | RagGroundingSuite D6 PASS |
| R-09-11 | Answer assembly: claims with graph-based verdicts list only graph evidence IDs; UNSUPPORTED claims keep what they cited. New `answerAssembly -> answer-assembly-rules-v1`; verifier rules unchanged (v1). | "verified claims list only graph evidence ..." | RagGroundingSuite R-09-11 PASS |
| M4 | `GraphExportReader.load` is total (decode and validate per row inside Try; null rows and fields give Left). Researcher and metric rows must carry the release run ID. graph-export-reader-v2. No duplicate-metric uniqueness check (see below). | "null lines and null fields ... typed Lefts" (13 cases); "rows from another run fail the load" | none in tests/** |

Mutation check (implementer-observed): with 3 fixes reverted, Phase11RepairSuite gave "Failed: Total 10, Failed 3" (exactly D4-order, D5-forged, and R-09-11); the fixes were then restored. D6 and M4 threw before the fix (confirmed by the red test and review-01).

Results (implementer-observed): rag/test 68/68 (was 58). contractTests "Failed: Total 155, Failed 5, Errors 0, Passed 150"; the remaining failures are D1, D2, N2, D3, and CO-4 (other owners). `sbt -batch check` exit 1: core 160, ER 26, rag 68, storage 73 (71 passed, 2 skipped), services 61, spark 9, hadoop 66, all green; whole build 638 total, 631 passed, 5 failed, 2 skipped. GraphRagBenchmarkSuite passes in full.

Versions: rag-evidence-bundle-v2, semantic-context-format-v2, graph-export-reader-v2, answer-assembly-rules-v1 (new). Others unchanged.

Risks:
- An empty or semantic-only bundle has the same ID across releases while its provenance carries the run, so a cross-release write would conflict. A full fix needs the core `evidenceBundleId` to include the run (domain-engineer proposal; non-blocking).
- API evidence lists are now in canonical ID order.
- S3 Vectors read-back must return exactly the ingested metadata keys, or every hit is dropped. Unverified live.

Open decision raised by rag-engineer: should GraphExportReader reject duplicate metric rows per researcher and window? Orchestrator decision (2026-11-14): not now. The reader stays permissive, so the D2 red test keeps its setup and service-engineer fixes the presentation side (defense in depth). A load-time uniqueness check is logged as follow-up P11-F1 for rag-engineer, with test-engineer adjusting D2's setup at that time.

## Human decisions recorded in chat (2026-11-14)

- D1 and D3 entity-resolution policy: STRICT. A "different people" (rejected) or unresolved decision blocks any merge that would join the two mentions. Missing evidence never raises a score, and name plus initials alone cannot reach Accepted.
- docs/project-description.md is the human's copy of the course specification (human-provided reference material, committed by the human; no agent edits it).
# Phase 11 repair-01: entity-resolution-engineer (STRICT policy)

Assembled by the orchestrator from the entity-resolution-engineer report, 2026-11-14. Items: D3, D3-floor, M1, D1 support, policy versioning (review-01.md), under the human's STRICT decision (repair-01-rag.md).

Orchestrator spot check (observed): `git status` shows ER changes only under modules/entity-resolution/src (Evaluator, Features, HadoopCandidates, Outputs, Policy, Resolver; FeaturesSuite, ResolverSuite; new StrictPolicySuite). There is a staged `git mv` rename er-policy-v1.conf → er-policy-v3.conf, which is staged in the index (status `RM`) with further working-tree edits. No other module or tests/** file was changed by this agent.

STATUS: BLOCKED-ON, for the gold-pilot re-run only. The guard blocks `sbt runMain` for every agent. Items 1-4 and the policy bump are done and tested.

| Item | Fix | Feature tests (StrictPolicySuite unless noted) | Red test |
|---|---|---|---|
| D3 | No renormalization. `score` returns bounds (lower: missing counted as 0; upper: missing counted as 1). Accepted needs lower ≥ accept; Rejected needs upper < reject; the reported score is lower. Resolver.scala:178-193, :213-222, :355-389. | D3 pair now Unresolved at 0.70 (v2: 0.9333 Accepted); "(a) over the full combination space" (more than 10,000 checks); bound thresholds | EntityResolutionAdversarialSuite D3 PASS (10/10) |
| D3-floor | Corroboration gate: a probabilistic or adjudicated accept needs coauthorOverlap ≥ 0.05 or titleSimilarity ≥ 0.5 (name, initials, and year never corroborate); otherwise Unresolved `uncorroborated`, never adjudicated. min-evidence-weight now counts only name-independent features (coauthor, title, year). | "(b) over the full combination space and any thresholds" (4 policies, always-accept adjudicator: never Accepted, 0 adjudicator calls); boundaries; inclusive minimums; adjudicator gating | folded into D3 |
| M1 | er-features-v3: `coauthorOverlap(empty, empty)` = 0.0, matching Hadoop `contextJaccard`. Fixed at the ER boundary; hadoop untouched. | view vs Hadoop parity (D3 pair; shared-coauthor pair); FeaturesSuite | none |
| Sweep | `Evaluator.restatus` uses the v3 bounds and corroboration. | evaluator sweep never re-bands an uncorroborated pair to accepted | n/a |
| D1 support | Decision outputs documented as merge vetoes: `ResolutionRecord.blocksMerge`, `Outputs.MergeVetoStatuses` / `MergeVetoFiles`, `Outputs.readRecords`. Record schema v2 adds `scoreUpper` and `corroborated`; v1 lines still read. No core change. | every rejected, unresolved, and conflict pair is a readable veto; v1 line reads | D1 still red (spark) |

Mutation check (implementer-observed, then restored): reverting D3 gave 4 of 40 failures; the corroboration gate 7 of 40; the floor 2 of 40; M1 4 of 40; restatus 1 of 40. "(a) decisions" never failed alone (a safety net; the discriminating test is "(a) over the full combination space").

## Decision outputs for spark-engineer (D1, rule c)

- ER outDir from `Outputs.writeAll` (ResolveMain argument 2). Veto files: `rejected.jsonl`, `unresolved.jsonl`, `conflict.jsonl` (`Outputs.MergeVetoFiles`). Always written (possibly empty), one JSON object per line, sorted by pairId; every pair appears in exactly one of the 4 status files.
- `ResolutionRecord` schemaVersion 2. Spark needs: `scope` (apply only `"mention"` to researcher identity), `memberLow` / `memberHigh` (mention IDs, memberLow < memberHigh), `status`, plus `decisionId`, `reasonCodes`, `policyVersion` (er-policy-v3), `provenance`, `provenanceLow`, `provenanceHigh`.
- spark Support.scala:70-80 `InputSchemas.resolution` already declares decisionId, scope, memberLow, memberHigh, status, policyVersion, and provenance. Spark may also depend on ER directly (allowed by the module table).
- Not covered: pairs that were never candidates have no decision. Whether the closure may join them is Spark's decision.

## Results (implementer-observed)

- entityResolution/test: 40/40 (was 26).
- contractTests: "Failed: Total 155, Failed 4, Errors 0, Passed 151". Remaining: D1, D2, N2, CO-4. D3 passes; nothing regressed. The D1 precondition still holds under v3 (John–J. ≈ 0.913, corroborated); D1 fails only at Spark's assertion, GraphAnalyticsSuite.scala:312.
- `sbt -batch check` exit 1: core 160, ER 40, storage 73 (71 passed, 2 skipped), rag 68, services 61, hadoop 66, spark 9, all green; contractTests 155/151/4. Whole build 632 total, 626 passed, 4 failed, 2 skipped. Format check passed.

## Gold pilot

- Before (er-policy-v2, recorded in phase-00-09-evidence/gold-eval-v1.txt, worksheet sha256 449c3b82…, re-hashed and matching): n=48, P 0.750, R 0.391, F1 0.514, coverage 0.563.
- After (er-policy-v3): NOT RUN (guard blocks runMain). No after-metrics are claimed. Expected direction (unverified): recall cannot rise, and the 2 probabilistic FPs may become unresolved.
- No tuning. accept 0.85, reject 0.55, and weights unchanged. min-evidence-weight 0.15 (name-independent). Corroboration minimums 0.05 and 0.5 are new priors. All UNCALIBRATED.

Versions: er-policy-v3, er-features-v3, ResolutionRecord schema 2 (decisionId changes because it hashes policyVersion). Adjudicator still abstain-stub-v1.

## Risks

- UNCALIBRATED; recall and coverage will likely fall.
- `ScoringPolicy.withCoreSettings` can still override policyVersion and thresholds from core config (no production caller found).
- R-05-6 is half closed (ReviewItem still lacks the ER run ID).
- The `git mv` rename is staged in the index.

## Manual action (human)

Run the v3 pilot and record it next to gold-eval-v1.txt:
`sbt -batch "entityResolution/runMain edu.uic.cs441.scholarchain.er.WorksheetEvalMain modules/entity-resolution/gold/v1/labeling-worksheet-v1.csv --labeler TC"`
If the accepted-merge delta against v2 exceeds the error margin, human approval with the confusion-matrix delta is required.
# Phase 11 repair-01: spark-engineer (D1, STRICT policy)

Assembled by the orchestrator from the spark-engineer report, 2026-11-14. Item: D1 (review-01.md), under the human's STRICT decision (repair-01-rag.md). An earlier spark-engineer session wrote the logic and was interrupted before reporting. A second session (this report) reviewed that diff, made no logic changes, formatted the module, ran the mutation checks, and reported.

## Orchestrator observations (2026-11-14)

- Before the second session, at 22:30-22:34: `sbt -batch sparkAnalytics/test` gave "Passed: Total 25, Failed 0". `sbt -batch contractTests/test` gave "Failed: Total 155, Failed 3, Errors 0, Passed 152" (D2, N2, CO-4; D1 passed). `sbt -batch check` exited 1 at sparkAnalytics Compile and Test scalafmtCheck (5 unformatted spark files).
- After the second session, at 23:11: `sbt -batch sparkAnalytics/scalafmtCheckAll` gave "[success]". `git status` shows no change outside modules/spark compared with the 22:30 snapshot.

STATUS: COMPLETE for spark scope.

## Fix: identity-closure-v2

- Inputs: ER accepted.jsonl plus rejected.jsonl, unresolved.jsonl, and conflict.jsonl. All 4 are in the run manifest, and a missing file fails the run.
- Scope: only `scope == "mention"` records count. Work-scope accepts are counted (erAcceptedWorkLinksNotApplied) but not applied.
- Links and vetoes are put in canonical order and de-duplicated. For links: highest confidence, then smallest decisionId. For vetoes: a conflict record if there is one, then smallest decisionId. The veto rule matches ResolutionRecord.blocksMerge.
- Conflict veto inside a closure component: the existing F2 rule splits the component into singletons.
- Rejected or unresolved veto inside a component: the component is re-solved by ConstrainedClosure (union-find, links taken by confidence descending, then link key). A union is refused at union time if it would join a veto pair, and the refusal is recorded (`veto-would-join`). Anchor = smallest mention ID in the final cluster.
- Components with no internal veto keep their closure labels.
- Never-candidate rule (spark-engineer decision): pairs ER never compared carry no veto and may be joined transitively through accepted links.

## Outputs and versions

- New output `run=<id>/identity-refused-links` (Parquet), carrying link, veto, and member provenance.
- New nullable column vetoConstrainedClosureAnchorMentionId in `researchers` and `mention-researcher-map`.
- params.json: identityClosure = identity-closure-v2, identityVetoRule, identityVetoFiles, identityLinkOrder.
- The run manifest lists 4 ER inputs, so run IDs change for the same inputs.
- researcherIdentityVersion is unchanged (only cluster membership changes).
- 6 new output invariants, each required to be 0: veto-pair-same-researcher, refused-link-same-researcher, refused-link-missing-evidence, refused-link-duplicate, accepted-link-accounting, constrained-mention-unassigned.

## Mutation checks (implementer-observed, restored byte-identical)

- M1, solver veto disabled: "Failed: Total 25, Failed 11" (ConstrainedClosureSuite 9, IdentityVetoSuite 2, including the job invariant veto-pair-same-researcher = 3).
- M2, veto set reduced to conflict only (pre-repair behavior): "Failed: Total 25, Failed 2" (John and Jane merged; PartASuite erVetoMentionPairs 1 vs 3).

## Results (implementer-observed)

- sparkAnalytics/test: 25/25 (was 9).
- contractTests: "Failed: Total 155, Failed 3, Errors 0, Passed 152". GraphAnalyticsSuite 6/6, so D1 passes. Remaining: D2 (RagGroundingSuite.scala:145), N2 (ApiAdversarialSuite.scala:421), CO-4 (HadoopSplitBoundarySuite.scala:77).
- `sbt -batch check` exit 1: every format check passed; module tests ER 40, rag 68, services 61, core 160, storage 73 (71 passed, 2 skipped), spark 25, hadoop 66, all green; contractTests 155/152/3. markTestRun did not run.
- Logs: the session scratchpad (m1.log, m2.log, spark-test.log, contract.log, check.log).

## Risks

- A giant closure component with an internal veto is solved in one task. This is visible in counts.json (identityConstrainedComponents, maxConstrainedComponentMentions, maxConstrainedComponentLinks) but not measured on real data.
- Run IDs change, because the manifest now lists 4 ER inputs.
- No shuffle metrics or dataset-sample run, only test fixtures.

## Routing note

The report routes D2 to rag-engineer. review-01 places D2 in services Presenter.scala:59-61, so the orchestrator keeps D2 with service-engineer. N2 is in services app/Records.scala:192 (observed), so service-engineer.
# Phase 11 repair-01: service-engineer

Assembled by the orchestrator from the service-engineer report, 2026-11-14. Items: D2 + M2, N2, NIT-2 (implementation side) (review-01.md).

Orchestrator spot check (observed): `git status` shows changes in modules/services only: Router.scala, Presenter.scala, Records.scala, LifecycleBoundsSuite.scala, QueryApiSuite.scala, and new ConflictPresentationSuite.scala. The tests/** entries match the earlier snapshot (test-engineer's work from before this repair).

STATUS: COMPLETE.

| Item | Fix | Feature tests | Red test |
|---|---|---|---|
| D2 + M2 | `Presenter.agreed`: a researcher attribute (each metric, componentId, communityId, bridgeMetric, displayName) is shown only if every graph fact for it in the bundle has the same value (component compares (componentId, componentSize); bridge compares (crossCommunityDegree, distinctCommunitiesTouched), as in V4). Disagreement in any window, policy, or algorithm version makes the attribute absent (null or key left out). Second guard: absent if any claim about it is CONFLICTING. Claims are still listed with their status. Replaces `.toMap`, `collectFirst`, and `distinctBy`. | ConflictPresentationSuite (6) | RagGroundingSuite D2 PASS (16/16) |
| N2 | `Records.globalVictim`: at maxRecords, principals are ranked by all records held (running included). The victim is the oldest terminal record among the heaviest; ties go to the oldest record, not name order. If those principals hold only running records, the claim gets 503 OVERLOADED. Non-terminal records are never evicted. The doc comment now matches. | LifecycleBoundsSuite (3 new) | ApiAdversarialSuite N2 PASS (17/17) |
| NIT-2 (impl) | `Router.isJson`: the media type before the first `;`, OWS trimmed, must equal `application/json` (case-insensitive); parameters are allowed. `application/jsonp`, `json-seq`, `json+x`, lists, and empty values get 415. | QueryApiSuite (cases added) | test side still open (test-engineer) |

Mutation check (implementer-observed, restored with `cmp` identical): with all three fixes reverted, `services/testOnly` on the 3 suites gave "Failed: Total 31, Failed 9, Errors 0, Passed 22". 5 D2/M2, 3 N2, and 1 NIT-2 test failed. Only the control test passed, as intended.

Results (implementer-observed):
- services/test 70/70 (was 61).
- contractTests "Failed: Total 155, Failed 1, Errors 0, Passed 154", only CO-4 (HadoopSplitBoundarySuite.scala:77).
- `sbt -batch check` exit 1 (364 s): every format check passed; module suites 68, 40, 160, 73 (71 passed, 2 skipped), 70, 25, 66, all green; contractTests 155/154/1.

API-visible behavior changes:
- (a) `researchers[]` fields can now be null or absent when bundle facts disagree.
- (b) The same researcher under two community algorithm versions gets communityId null.
- (c) At the global record cap, 503 OVERLOADED can now occur where a lighter principal's record used to be evicted.
- (d) Content-Type values that only start with `application/json` now get 415.

Routes: none added or removed. POST /v1/query and GET /v1/jobs/{jobId}/result carry the presentation rule. Limits and timeouts are unchanged.

Follow-ups: rag-engineer P11-F1 (duplicate rows still accepted by GraphExportReader); documentation-engineer DOC-10-1 (document these three rules); test-engineer NIT-2 test side (ApiAdversarialSuite never probes `application/jsonp`).
# Phase 11 repair-01: hadoop-engineer (CO-4)

Assembled by the orchestrator from the hadoop-engineer report, 2026-11-15. Item: CO-4 (review-01.md, CONFIRMED, LOW).

## Orchestrator observations (2026-11-15)

- `git status -- modules/hadoop`: DblpXmlInputFormat.scala modified, ScannerSectionSuite.scala new. Nothing else changed outside the earlier snapshot.
- `sbt -batch check` run by the orchestrator, exit 0, 338 s (log: session scratchpad orch-check.log):
  - every format check "[success]";
  - core 160/160, ER 40/40, rag 68/68, storage 73 (71 passed, 2 skipped), services 70/70, spark 25/25, contractTests 155/155, hadoop 75/75;
  - "markTestRun: updated .claude/.last-test-run".

STATUS: COMPLETE.

## Fix

`DblpRecordScanner` (shared by DBLP Job 1 and arXiv Job 2) tracks three lexical states: normal, CDATA (`<![CDATA[` to `]]>`), and comment (`<!--` to `-->`). Record start tags and the record close tag count only in the normal state, inside and between records.

- Bounded sections: an opener counts only if its closer begins within `SectionLimit` = 65,536 bytes. Otherwise it is plain text, so an unclosed opener cannot swallow later records, and the record rejects as xml-not-well-formed as before.
- Ownership is unchanged: a record belongs to the split in which its start tag begins, and its owner reads through any section that straddles the split end.
- Resync at a split start s > 0: if the first `]]>` within 64 KiB of s is reached in the normal state, s was inside CDATA (XML forbids `]]>` in text), and the reader starts after it. Otherwise the reader starts in the normal state at s. The decision depends only on the bytes after s, so it is deterministic.
- Counters, RejectRecord v2, the truncated marker, the key-value contract, and partitioning are unchanged. The lookahead buffer is capped at about 256 KiB per reader.

## Tests

- ScannerSectionSuite, 9 tests. They cover every single split point (two readers) and every pair of split points (three readers) over CDATA and comments inside and between records, `]]>` inside a comment, `-->` inside CDATA, unterminated openers, an opener beyond SectionLimit, the documented comment limit, and arXiv.
- LocalPipelineSuite (real Job 1 and Job 6 on datasets/samples) passed with its pinned counters unchanged: INPUT 13, ACCEPTED 7, REJECTED 6, EMITTED_PAIR 285.

## Mutation checks (implementer-observed, restored with `cmp` identical)

- M1, the HEAD scanner:
  - HadoopSplitBoundarySuite "Failed: Total 4, Failed 1" (CO-4 at :77);
  - ScannerSectionSuite (plus only the SectionLimit constant) "Failed: Total 9, Failed 4, Passed 5".
- M2 + M3 (resync without the lexing check; openers counted without a closer check): ScannerSectionSuite "Failed: Total 9, Failed 6, Passed 3".

## Results (implementer-observed)

hadoop 75/75 (was 66); contractTests 155/155; `sbt -batch check` exit 0 (337 s).

## Risks (documented in the class comment)

1. A split that starts inside a comment containing a record start tag emits one extra record. Nothing is lost, and a test pins the behavior. A comment cannot be detected from the bytes after the split start, because `-->` is legal text.
2. In malformed input, a stray `]]>` within 64 KiB after a split start makes that reader skip records in the skipped bytes, and no counter records it.
3. CDATA text that contains `<![CDATA[` or `<!--` can mislead the resync rule.
4. Processing instructions are not treated as sections.
5. Already present before this repair, not a regression: the record buffer has no size cap, a gap against "bounded record size". Proposed follow-up P11-F2 for hadoop-engineer.

The DBLP and arXiv OAI dumps contain no CDATA or comments in or between records, so risks 1-4 do not arise on real input.
# Phase 11 repair-01: test-engineer (test side)

Assembled by the orchestrator from the test-engineer report, 2026-11-15. Items: M5, NIT-1, NIT-2 (test side), and the R-09-11 line reference (review-01.md).

## Orchestrator spot check (observed)

- `git status` shows no change to modules/services/.../app/QueryService.scala (the M5 swap was reverted).
- `.claude/.last-test-run` was written by markTestRun at 2026-11-15 00:51:11. No Scala source under modules/ or tests/ is newer than it.

STATUS: COMPLETE. No new red tests. Test counts are unchanged, because existing tests were strengthened and none were added.

## Items

| Item | Test (file:line) | Change |
|---|---|---|
| M5 | ApiAdversarialSuite "N3: a deadline records the timeout before interrupting, so a late success cannot win" (:445) | Deterministic interleaving control with no sleeps. `ParkUntilInterrupted` (ApiKit.scala:166) parks until interrupted, then completes normally. `DeadlineOrderingStore` (ApiAdversarialSuite.scala:379) holds the WORKFLOW_TIMEOUT write until the worker's terminal write if the interrupt came first. Covers the async job deadline and the sync deadline (504). Replaces `InterruptSwallowingWorkflow` and `Thread.sleep(50)`. |
| NIT-1 | EntityResolutionAdversarialSuite "Unicode ... confusables are not" (:106) | Adds `assertNotEquals(r.status, Accepted)` at :119 (passes under er-policy-v3). |
| NIT-2 | ApiAdversarialSuite "content type: JSON media types only; parameters and case are tolerated" (:174) | 5 accepted forms (parameters, case, OWS, tab). 14 refused forms with 415 UNSUPPORTED_MEDIA_TYPE, including `application/jsonp`, `json-seq`, `jsonp; charset=utf-8`, `json5`, `json+ld`, lists, `text/json`, an empty value, and a missing header. |
| R-09-11 | RagGroundingSuite "R-09-11 ..." (:279) | The comment now cites ClaimVerifier.scala:52-71 (V1 gate :59-63) and GraphRagPipeline.scala:241 (semantic-ID filter :257-262). The stale :231-247 and :249 references are removed. |

## M5 mutation check (implementer-observed)

QueryService.scala :192 (`fail(... WorkflowTimeout ...)`) and :193 (`future.cancel(true)`) were swapped. The N3 test failed: "a late success overwrote the timeout", expected `Failed()`, obtained `Succeeded()`; "Failed: Total 1, Failed 1". The file was restored, and `cmp` printed "identical". sha256 of all 192 files under modules/ matched before and after.

## Results (implementer-observed)

- contractTests 155/155.
- `sbt -batch check`: the first run exited 1 on the format check of 2 edited test files. After `contractTests/Test/scalafmt`, the second run exited 0 (346 s): ER 40, core 160, rag 68, storage 73 (71 passed, 2 skipped), services 70, spark 25, hadoop 75, contractTests 155.

## Risks

- The synchronous-path ordering (QueryService.scala:207-208) is covered by the same control, but it was not mutation-checked.
- The 5 s hang guards could expire under extreme load. The test would then fail, not pass falsely.
- The N3 test builds QueryService directly, bypassing ServicesApp.build. The HTTP-level tests still cover the composed app.
- The test was not looped to measure flakiness.
