# Phase 11 repair-03: entity-resolution-engineer (F2 producer side, F3, F4)

Assembled by the orchestrator from the entity-resolution-engineer report, 2026-11-15. Items: F2 (producer side), F3, and F4 (review-02.md).

## Orchestrator spot check (observed)

- Files newer than review-02.md are only in modules/entity-resolution: er-policy-v5.conf; Evaluator, Outputs, Policy, Resolver; DecisionOutputsSuite (new), ResolverSuite, StrictPolicySuite.
- No Scala source is newer than `.claude/.last-test-run` (written by markTestRun during the agent's `sbt check`).

STATUS: COMPLETE.

## F2, producer side: the manifest contract (er-outputs-v2)

- File: `_DECISIONS_MANIFEST.json`, written LAST in the ER outDir.
- Fields:
  - `manifestSchemaVersion` = 1;
  - `pipelineRunId` (run:v1);
  - `policyVersion`, `featuresVersion`;
  - `schemaVersion` = 2;
  - `files`: an array for accepted, rejected, unresolved, and conflict, each with `{name, recordCount, sha256}`.
- Writer (`Outputs.writeRun` / `writeAll`): every record must share the run ID, policy version, features version, and schema 2. Otherwise it throws and outDir is untouched.
- Verifier: `Outputs.verify(Path)` or `Outputs.verifyWith(manifestBytes, open)`, returning `Either[List[String], DecisionsManifest]`.
  - Header rules H1-H5 (schema version, run ID, versions, schema 2, exactly the 4 files).
  - Per-file rules:
    - V1: the file exists.
    - V2: the sha256 matches (a 0-byte veto file means "no vetoes" only if the manifest says so).
    - V3: the record count matches.
    - V4: every line parses, its status matches the file, and its run ID and versions match the manifest.
- Local atomicity:
  - Files are staged in a hidden sibling directory and published by ATOMIC_MOVE.
  - An existing directory is swapped out, then only owned files are deleted, never recursively.
  - The publish refuses a directory holding foreign files or links.
  - There is no copy fallback.
- HDFS/S3: ResolveMain writes locally only. A copier must copy all files. Any partial or mixed copy fails V2-V4.

## F3

- The deterministic mention and work accepts now require `corroborated`.
- `ScoringPolicy.validated` checks:
  - `mention-coauthor-min` in (0,1] and ≥ the corroboration coauthor minimum;
  - `work-title-min` in (0,1] and ≥ the title-similarity minimum (the same bug class);
  - `mention-name-min` in (0,1];
  - year gaps ≥ 0;
  - every number finite;
  - accept > 0;
  - weights ≥ 0;
  - sweep values in [0,1].
- `withCoreSettings` uses the same validation.
- StrictPolicySuite "(b)" now varies the deterministic minimums: 4 threshold policies × 5 deterministic variants × 2 scopes.

## F4

The `band` doc now states the M1 exception precisely (comment only).

## Versions

- er-policy-v5 (`git mv`; the index shows `RM er-policy-v1.conf -> er-policy-v5.conf`). No threshold or weight changed, and no decision changes at the bundled values. decisionIds change.
- er-features-v4 and ResolutionRecord schema 2 are unchanged.
- New manifest (manifestSchemaVersion 1).

## Mutation checks (implementer-observed, restored with sha256 matching)

| Mutation | Result |
|---|---|
| M1: no `corr` on the mention accept | "Failed: Total 63, Failed 2" |
| M2: no `corr` on the work accept | "Failed: Total 63, Failed 2" |
| M3: no mention-coauthor-min rule | "Failed: Total 63, Failed 2" |
| M4: in-place, non-atomic writes | "Failed: Total 63, Failed 1" |
| M5: verifier ignores run ID and sha256 | "Failed: Total 63, Failed 3" |

## Results (implementer-observed)

- ER 63/63 (was 50).
- spark 31/31.
- contractTests 186/186.
- `sbt -batch check` exit 0 (527 s): services 70, storage 73 (71 passed, 2 skipped), core 160, rag 80, ER 63, spark 31, hadoop 75, contracts 186; markTestRun updated the marker.

## Risks

- Spark does not verify the manifest yet (next step).
- Fixtures that edit ER files after `writeAll` (spark's own, and tests/** IdentityVetoEndToEndSuite) will fail verification once spark enforces it. V4 requires schema 2 on every line, so v1 lines cannot sit in a verified directory.
- Upload scripts must copy all files.
- Stale hidden staging and replaced directories may remain after a hard kill; they are not auto-cleaned.
- The policy is still UNCALIBRATED.

## Manual action (human)

- Commit the staged rename with the edits.
- Run the v5 gold pilot (same command as before).
# Phase 11 repair-03: spark-engineer (F2 consumer side)

Assembled by the orchestrator from the spark-engineer report, 2026-11-15. Item: F2, consumer side (review-02.md), enforcing the manifest contract in repair-03-er.md.

Orchestrator spot check (observed): files newer than repair-03-er.md are only in modules/spark (PartA.scala, Support.scala, IdentityVetoSuite.scala, PartASuite.scala).

STATUS: COMPLETE for spark scope. 9 contract tests are red pending a test-engineer fixture update. This is intended fail-closed behavior, not a regression.

## Fix

`ErManifestGate` (Support.scala), run at the start of `PartA.runInner`.

Step 1 (driver), before the run ID is derived and before any record is read:
- `<er>/_DECISIONS_MANIFEST.json` is read through the Hadoop FS (local, HDFS, or S3). Missing or over 1 MiB fails.
- `Outputs.verifyWith` streams every file through header rules H1-H5 and file rules V1-V4.
- A Left fails the run with IllegalStateException.
- There is no unverified fallback.

Run ID: the manifest sha256 is added as a run input. The run ID is therefore content-addressed on the ER decisions. The hash is not added to configFingerprint, because it is an input, not configuration.

Step 2: `PartA.erRunProblems`, after the records are read and before the first write:
- Spark's per-file counts must equal the manifest recordCount.
- Exactly one distinct provenance.pipelineRunId, equal to the manifest's.

ErRecordCheck (RR1) is unchanged and still runs.

Output changes:
- params.json: `erManifestRule`, `erDecisions` (manifest sha, run ID, versions, files).
- counts.json: `erDecisionDistinctRunIds`.
- manifest.json: one more input.
- All Part A run IDs change compared with repair-02.

## Tests

- spark 40/40 (was 31): IdentityVetoSuite 18, ConstrainedClosureSuite 13, PartBSuite 6, PartASuite 3.
- New IdentityVetoSuite tests:
  - stale veto files beside a fresh accepted.jsonl;
  - a 0-byte veto file not in the manifest;
  - missing manifest;
  - forged manifests (4 cases);
  - a mixed run ID with a resealed manifest;
  - the erRunProblems unit test;
  - params and run-input recording;
  - RR1 regenerated-manifest and ErRecordCheck-reachability tests.
- Every failing-run test asserts that no output exists.

## Mutation checks (implementer-observed, restored with sha256 matching)

| Mutation | Result | Note |
|---|---|---|
| M1: per-file verification off | "Failed: Total 18, Failed 8" | The runs still failed closed through ErRecordCheck or the Spark-side check; the tests asserted the verifier's message. Defense in depth confirmed. |
| M2: M1 plus the Spark-side check off | "Failed: Total 18, Failed 8" | Stale, 0-byte, forged, and mixed runs completed silently: the pre-fix over-merge class. |
| M3: M2 plus an unverified fallback | "Failed: Total 18, Failed 9" | |
| M4: `runProblems` = Nil | "Failed: Total 18, Failed 1" | |

## Contract tests (implementer-observed)

"Failed: Total 186, Failed 9, Errors 0, Passed 177". All 9 are in tests/contracts IdentityVetoEndToEndSuite, and all fail with "_DECISIONS_MANIFEST.json missing": the `fixture()` writes ER lines without a manifest. They are:
- STRICT;
- several vetoes;
- conflict plus rejected;
- unresolved v1 line plus the never-candidate rule;
- work-scope;
- determinism;
- refused-link provenance;
- Law 3 veto dataset;
- missing ER file (it failed closed, but the message names the manifest, not the file).

GraphAnalyticsSuite 6/6 passed (it uses `Outputs.writeAll`). All 9 are classified as intended fail-closed behavior. No spark bug was found.

Fixture guidance: V4 admits only schema-2 lines with one run ID, policy version, and features version, so v1 lines (`toV1`) and hand-written "er-policy-v3"/"er-features-v3" records cannot sit in a verified directory. Write through `Outputs.writeRun`. v1 coverage can remain only as a must-fail case.

## Risks

- Driver-side verification reads every ER file once, single-threaded (SHA-256 plus a per-line parse), before the job starts. Not measured at full-corpus scale. A distributed alternative would need an ER contract change and a human decision. Logged as follow-up P11-F7.
- TOCTOU: a change between verification and the read is caught only via count or run ID.
- The manifest is an integrity check, not authentication.
- Existing ER outputs without a manifest must be regenerated.
- The unparseable branch of ErRecordCheck is now reachable only in its unit test.

# Phase 11 repair-03: test-engineer (IdentityVetoEndToEndSuite fixture for the ER manifest)

Assembled by the orchestrator, 2026-11-15. Interrupted once by the usage limit and resumed with no files lost. Orchestrator check (observed): no Scala source is newer than `.claude/.last-test-run`.

STATUS: COMPLETE. No red tests, no production defect. Only IdentityVetoEndToEndSuite.scala was edited. The modules/ sha256 (195 files) is identical before and after.

- Fixture: published through `Outputs.writeRun`. Hand-built records are `copy` of real Resolver decisions (one run, one policy version, one features version, schema 2). `reseal()` is used only for reordered files and attacks. `failsBeforeOutput` requires an IllegalStateException and no output.
- Changed tests:
  - the v1-line test becomes a schema-2 must-pass plus 8 must-fail cases (4 files × resealed/not; V4 alone catches resealed lines);
  - determinism proves files were reordered and resealed;
  - missing file must name the file (V1);
  - truncated line strengthened to fail before output, resealed and not.
- New tests:
  - a precondition that the adversarial records survive the writer;
  - F2 stale vetoes (control merge, attack with and without reseal, positive control with R2 republished and no staging leftovers);
  - F2 0-byte veto;
  - F2 mixed run, policy-version, and features-version appends with reseal.
- Results (implementer-observed):
  - IdentityVetoEndToEndSuite 16/16 (was 11, with 9 red); contractTests 191/191.
  - `sbt -batch check` exit 0 (288 s): core 160, rag 80, storage 73 (71 passed, 2 skipped), ER 63, services 70, hadoop 75, spark 40, contracts 191.
- Residual: someone able to write the ER directory can empty a veto file and recompute the manifest (integrity, not authentication). TOCTOU is pinned only by spark's unit test.

# Phase 11 repair-03: hadoop-engineer (F1)

Assembled by the orchestrator, 2026-11-15. Orchestrator check (observed): only DblpXmlInputFormat.scala and the new ScannerHandoffSuite.scala changed.

STATUS: COMPLETE. No counter or reject changes were needed.

- Fix:
  - The resync predicate g(b) is now monotone. It skips to the first `]]>` within 64 KiB only if no counted opener lies before it; g(0) = 0.
  - Hand-off: the reader whose split ends at `end` computes the same g(end) as the next reader and owns every start tag its lexer finds in [end, g(end)). It continues until both lexers agree on state at a common boundary.
  - Result: every split placement gives exactly the whole-file records. The stray-`]]>` record is rejected exactly once.
  - This also closes a second silent-loss case: a split inside a comment that holds `<![CDATA[`.
- Limits (class comment):
  - Phantoms from a split inside a comment (unchanged).
  - A start tag can be owned twice only with a split inside a comment, or inside CDATA text that holds `<![CDATA[`, and with splits under about 128 KiB. Job 1 deduplicates and counts DUPLICATE, so it is never a loss.
  - Input without comments is exactly-once.
- Tests: ScannerHandoffSuite (8): every 2-reader split point, a 3-reader grid, split-size independence from 1 to the document length, near and beyond SectionLimit, comment plus CDATA, and the real record reader on FileSplits.
- Mutation (pre-fix scanner; implementer-observed): "Failed: Total 8, Failed 7" (6 of 7 records vanished with no reject); restored, sha256 matches.
- Results (implementer-observed): hadoop 83/83 (was 75); contractTests 191/191 (test-engineer's hadoop suites unchanged and green). Full `sbt check` not run.
- Risk: up to about 128 KiB of lookahead past a split end (negligible at EMR split sizes).

# Phase 11 repair-03: rag-engineer (F5)

Assembled by the orchestrator, 2026-11-15. STATUS: COMPLETE, comment-only (Evidence.scala).

- The bundle Scaladoc (:144-155) now says byte-identical replay holds only while no bound cuts anything (60 graph facts, 24k excerpt characters, MaxSemanticItems). Past a bound, a different retrieval order can keep a different subset, which gives a different bundle ID: no conflict, but no dedup. The inline comment above the canonical sort says the same.
- It also documents a second case the review missed: the semantic `take(MaxSemanticItems)` cuts before the sort.
- Results (implementer-observed): `rag/scalafmtCheckAll rag/test`, format [success], 80/80.
- Proposal for a later phase, logged as follow-up P11-F8: truncate on a deterministic plan-derived rank (operation priority, hop, ID; rounded similarity, ID), with rag-evidence-bundle-v4 and a test above the bounds.
