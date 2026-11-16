# Phase 11 repair-02: spark-engineer (RR1, RR2)

Assembled by the orchestrator from the spark-engineer report, 2026-11-15. Items: RR1 and RR2 (rerun-01.md).

Orchestrator spot check (observed): files newer than rerun-01.md under modules/, tests/, and .claude/ are only PartA.scala, Support.scala, and IdentityVetoSuite.scala (all in modules/spark).

STATUS: COMPLETE.

| Item | Fix | Feature tests (IdentityVetoSuite) | Red test |
|---|---|---|---|
| RR1 | `ErRecordCheck` (Support.scala), applied by `PartA.readErDecisions`. Every line of all 4 ER files is classified. **Unparseable**: Spark's corrupt-record column is non-null. **Incomplete**: decisionId, scope, memberLow, memberHigh, status, or provenance.sourceDataset, sourceRecordId, or pipelineRunId is null or blank; or scope is not mention or work; or status is not one of accepted, rejected, unresolved, conflict. Any problem record throws IllegalStateException before any output is written, with sorted per-file counts (fail closed, like a missing file). The accepted file follows the same rule. v1 lines still read. | classification (15 lines); a truncated veto fails before output; missing scope, memberLow, memberHigh, or status fails (4 runs); a corrupt accepted line fails; a sound run reports 8 zero counters | IdentityVetoEndToEndSuite:601 PASS |
| RR2 | identity-refused-links gains `vetoSourceDataset`. Invariant refused-link-missing-evidence is widened to cover the veto's dataset, record ID, and run ID. | RR2 (Law 3) | IdentityVetoEndToEndSuite:567 PASS |

Output changes:
- the new identity-refused-links column above;
- 8 counts.json keys `erDecisionRecordsUnparseable:<file>` and `erDecisionRecordsIncomplete:<file>`;
- the params.json key `erDecisionRecordRule`.
Run IDs are unchanged.

Mutation checks (implementer-observed, restored with sha256 matching):
- M1, `ErRecordCheck.problem` always null: "Failed: Total 9, Failed 4" (the RR1 tests; the truncated veto was silently accepted).
- M2, full RR2 revert: "Failed: Total 9, Failed 1".

Results (implementer-observed):
- sparkAnalytics 31/31 (was 25); format check [success].
- contractTests "Failed: Total 186, Failed 4, Errors 0, Passed 182". Remaining: RR3, RR4 (ER) and RR5, RR6 (rag).
- Full `sbt check` was not run (allowed by the work order).

Risks:
- One bad ER line now stops the whole Part A run (intended). The fix on real data is to repair or regenerate the ER output.
- If ER adds a new scope or status, Part A refuses to run until the rule is updated.
- A line cut so that it is still valid, complete JSON would not be detected.
# Phase 11 repair-02: entity-resolution-engineer (RR3, RR4)

Assembled by the orchestrator from the entity-resolution-engineer report, 2026-11-15. Items: RR3 and RR4 (rerun-01.md), under STRICT (2026-11-14) and the RR3 decision (2026-11-15), with G1 deferred.

Orchestrator spot check (observed): files newer than repair-02-spark.md under modules/, tests/, and .claude/ are only in modules/entity-resolution (er-policy-v4.conf; Evaluator, Features, HadoopCandidates, Normalize (ER-local), Outputs, Policy, Resolver; EvaluatorSuite, FeaturesSuite, ResolverSuite, StrictPolicySuite). The index now shows `RM er-policy-v1.conf -> er-policy-v4.conf`.

STATUS: COMPLETE. The gold pilot was not run (the guard blocks runMain).

## Fixes

- **RR3.** The deterministic mention accept needs an observed year gap of 15 or less (Resolver.scala:398, `forall` changed to `exists`).
- **RR4 (er-features-v4).** Coauthor key = `personName(raw).canonical` plus the DBLP homonym number as written. "Wei Wang 0001" and "Wei Wang 0002" now differ, matching Hadoop `Blocking.jaccard`. Name order stays folded. The suffix-dropping code was ER's own Normalize.scala:54, not core, so no domain-engineer change was needed.
- **Missing-value audit** of 13 places in the Resolver, Policy, and their inputs. Rule: an accept needs an observed value; a veto fires only on an observed value; if a veto's value is missing, the pair cannot be accepted except by an exact ID. Additional fixes:
  - `accept-blocked:initials-unknown`: missing initials skipped the incompatible-initials veto, so "Li"/"Li" could be accepted at 0.85.
  - `accept-blocked:same-work-unknown`: two places; production paths always set sameWork.
  - Adjudicator band: needs lower ≥ reject. Pairs that straddle the reject threshold are unresolved (`bounds-straddle-reject`). No status change with the stub.
  - `Evaluator.restatus`: a missing `corroborated` no longer defaults to true.
  - ID absence and conflict: deliberately unchanged (flagged for review).
- **StrictPolicySuite.** New property "(a) decisions against EVERY value, the blocking ones included: removal never accepts": 2 policies × 2 adjudicators × 2 scopes, more than 20,000 non-accepted checks and more than 2,000 accepted cases.

## Versions

- er-policy-v4 (rules only; no threshold or weight changed).
- er-features-v4.
- ResolutionRecord stays schema 2. decisionIds change because they hash the policyVersion.

## Mutation checks (implementer-observed, restored with hashes matching)

| Reverted | Result |
|---|---|
| RR3 | "Failed: Total 22, Failed 3" |
| RR4 | "Failed: Total 27, Failed 3" |
| initials-unknown block | "Failed: Total 22, Failed 2" |
| same-work-unknown | "Failed: Total 22, Failed 1" |
| straddle-reject band | "Failed: Total 22, Failed 2" |
| Evaluator sweep | "Failed: Total 23, Failed 2" |

## Results (implementer-observed)

- entityResolution 50/50 (was 40).
- sparkAnalytics 31/31.
- contractTests "Failed: Total 186, Failed 2, Errors 0, Passed 184". Only RR5 and RR6 (rag) remain; ErStrictCrossPathSuite 6/6.
- Format check passed.
- Full `sbt check` not run.

## Decision raised for the human

The initials-unknown block means that whenever initials cannot be computed, only an exact-ID accept is possible. On the Hadoop production path initials are never computable, so ResolveMain can accept only on ORCID agreement at ANY thresholds. Output at today's thresholds is unchanged: the best lower bound is 0.80 < 0.85, and the adjudicator abstains. But G1 can no longer be fixed by lowering thresholds. It would need Hadoop to carry initials or name strings (hadoop-engineer), or a human decision to drop this rule.

Human decision (2026-11-15): KEEP the initials-unknown block. G1 / P11-F3 becomes a hadoop-engineer task (carry initials or name strings through the candidate path), to be decided after the v4 gold pilot.

## Risks

- Cross-source coauthors: DBLP "Ada Sample 0001" no longer matches OpenAlex "Ada Sample" (recall loss; errs toward precision).
- The view path folds "Turing, Alan" into "Alan Turing" but Hadoop does not (a remaining parity gap; the folding is pinned by a contract test).
- A future adjudicator must treat missing evidence as missing.
- Still UNCALIBRATED. `withCoreSettings` can still override. R-05-6 is half closed.

## Expected v4 gold-pilot direction (UNVERIFIED)

Precision the same or higher; recall and coverage the same or lower; unresolved rate the same or higher. Last recorded run (er-policy-v2): n=48, P 0.750, R 0.391, F1 0.514, coverage 0.563. Neither v3 nor v4 has been run.

## Manual action (human)

- Confirm or overrule the initials-unknown block.
- Run the v4 pilot: `sbt -batch "entityResolution/runMain edu.uic.cs441.scholarchain.er.WorksheetEvalMain modules/entity-resolution/gold/v1/labeling-worksheet-v1.csv --labeler TC"`.
- Commit the staged rename together with the edits.
# Phase 11 repair-02: rag-engineer (RR5, RR6)

Assembled by the orchestrator from the rag-engineer report, 2026-11-15. Items: RR5 and RR6 (rerun-01.md).

## Orchestrator spot check (observed)

- Files changed during this repair are only in modules/rag:
  - new: MetadataFormat.scala, Phase11Repair02Suite.scala;
  - modified: GraphRagPipeline, Evidence, RagVersions, SemanticContext, SummaryTemplates.
- No change to storage, core, tests/**, or prompts/**.
- No Scala source under modules/ or tests/ is newer than `.claude/.last-test-run`, which markTestRun wrote during rag-engineer's `sbt check`.

STATUS: COMPLETE.

## Fixes

| Item | Fix | Red test |
|---|---|---|
| RR5 | `MetadataFormat.isToken` (semantic-metadata-format-v1): an allow-list of 1-256 printable non-space ASCII characters (U+0021-U+007E). Applied in 3 places: (1) read-back, where every string metadata value and the provenance must pass, a failing hit is dropped with the key names only (values are never echoed), and an invalid vector key is shown as `<invalid vector key>`; (2) EvidenceBuilder, before a semantic item can be persisted; (3) ingestion (SummaryTemplates), which refuses out-of-format sourceRecordIds. Every value rag writes fits the rule (stable IDs, hashes, wire values, model versions, textRefs, DBLP, OpenAlex, DOI, ORCID, and arXiv IDs). The S3 Vectors docs show no character restriction on string metadata, so the check must live in rag. | Phase11RerunRagSuite D5/M3 PASS |
| RR6 | Guard object `Total`: a throw, a null instead of an Either, and Left(null) become Left. Validation runs inside the guard, so the result is Abstained(SynthesisFailed), which the API returns as 502. | Phase11RerunRagSuite D6 PASS |

## Null-return audit

Fixed:
1. synthesizer.synthesize;
2. synthesizer.version (falls back to "unknown-synthesizer");
3. GraphStore directCoauthors and boundedPaths (previously unguarded; now RetrievalFailed);
4. EvidenceBuilder.build;
5. Embedder embed and modelVersion;
6. VectorStore query (a null distance is kept as unknown);
7. ObjectStore get;
8. SemanticStage as a whole (empty context, "semantic stage failed");
9. EvidenceBundleTable put (bundleStore "failed: ..."; the answer stands);
10. clockMillis.

No change needed: DraftAnswer.decode, ClaimVerifier, ClaimText, ResearcherResolver, QueryPlanner, and GraphFacts (internal code, or already total).

## Versions

- semantic-context-format-v2 → v3.
- rag-evidence-bundle-v2 → v3.
- New: semantic-metadata-format-v1, pipeline-component-guards-v1.
- Template versions are unchanged (text, hashes, and chunk keys are unchanged).

## Mutation checks (implementer-observed, restored with sha256 matching)

- A, `isToken` set to blank-check only: Phase11Repair02Suite "Failed: Total 12, Failed 6" (all RR5 tests); contract D5/M3 failed.
- B, the original Try/flatten: "Failed: Total 12, Failed 1" (NPE); contract D6 failed.
- B2, guard removed: "Failed: Total 12, Failed 2".

## Results (implementer-observed)

- rag 80/80 (was 68).
- contractTests 186/186.
- `sbt -batch check` exit 0. Suites 160, 80, 73 (71 passed, 2 skipped), 50, 70, 75, 31, 186; 725 tests in all; markTestRun updated the marker.

## Proposals (non-blocking, defense in depth)

- storage-engineer: apply a printable-token rule in `VectorMetadata.validate` (VectorStore.scala:117) at PutVectors time.
- domain-engineer: core `Validators.provenance` (Validators.scala:51-54) should reject control and line-separator characters (at least CR, LF, TAB, NUL, U+0085, U+2028, U+2029). Whether to also forbid spaces and non-ASCII is a policy question (non-ASCII DOIs).

These are logged as follow-ups P11-F4 (storage) and P11-F5 (core).

## Risks

- A legitimate non-ASCII DOI or record key cannot be used as semantic context until the format is widened (with a version bump).
- The audit map gains 2 keys; nothing pins the key set.
- Live S3 Vectors read-back is still unverified.
