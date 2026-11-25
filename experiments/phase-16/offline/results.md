# Phase 16 offline results: groups 1 (entity resolution) and 2 (graph correctness)

Manifest: [manifest.yaml](manifest.yaml). Source commit `0c827107c5a0d8271619b013f81e5e9fa47b896a`.
These are offline, local runs only. No AWS call was made, and nothing under `modules/**` was changed.
Every number below was copied from the raw file linked next to it. No derivation script was needed,
because every figure is a count printed by the test framework.

**Archive note (git ignore).** `raw/01-er-test.log` and `raw/03-contract-graph-er.log` match the
repository ignore rule `.gitignore:14` (`*.log`), so a plain `git add experiments/phase-16` leaves
them out and the links and checksums below would point at files missing from the repository. The
other raw files are not ignored. `.gitignore` belongs to build-engineer and is not changed here. When
archiving, the human runs from the repository root, in addition to `git add experiments/phase-16`:

```
git add -f experiments/phase-16/offline/raw/01-er-test.log experiments/phase-16/offline/raw/03-contract-graph-er.log
```

See `raw_collection.git_ignore` in the manifest.

## Group 1: entity resolution

### 1a. Gold-set metrics (precision, recall, F1, confusion matrix, coverage, unresolved rate, by path)

**Not measured in this run.** The tool exists (`WorksheetEvalMain` in
`modules/entity-resolution/.../GoldWorksheet.scala`). It runs only through `sbt runMain`, and
`guard-dangerous.sh` (section 6b) blocks `runMain` for every agent. Run R04 in the manifest is
therefore pending a human run. See "Manual action" below.

Secondary evidence, NOT a raw result of this experiment: a human ran the same evaluator on the same
worksheet (sha256 `449c3b82...52e4`, equal to this run's checksum) under the same policy
(`er-policy-v5`) on 2026-11-15. Its output was summarized in `docs/agent-runs/phase-11/review-03.md`
and `docs/agent-runs/phase-11/human-gate.md`. The verbatim output was not kept. No ER, Spark, or core
code changed between that acceptance (85067146) and the source commit. Summary from those records:

| Path | n | TP | FP | TN | FN | U+ | U- |
|---|---|---|---|---|---|---|---|
| exact-id | 8 | 6 | 1 | 0 | 0 | 0 | 1 |
| deterministic | 17 | 2 | 0 | 8 | 7 | 0 | 0 |
| probabilistic | 23 | 0 | 0 | 0 | 0 | 8 | 15 |
| llm-adjudicated | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| overall (sum of the rows) | 48 | 8 | 1 | 8 | 7 | 8 | 16 |

The by-path rows are as recorded. The overall row is their sum, computed here, not quoted. The records
give no overall U- figure. Overall (as recorded there): P 0.889 [0.565, 0.980], R 0.348 [0.188, 0.551], F1 0.500, coverage 0.500
(so unresolved rate 0.500). These figures need a fresh, verbatim human run before they are cited as
Phase 16 results.

### 1b. ER module tests (evaluator, resolver, strict policy, gold worksheet integrity)

Raw: [raw/01-er-test.log](raw/01-er-test.log) (run R01, exit 0; git-ignored by `*.log`, archive
with `git add -f`, see the archive note above).

| Suite | Tests | Failed |
|---|---|---|
| NormalizeIdentifiersSuite | 4 | 0 |
| FeaturesSuite | 5 | 0 |
| EvaluatorSuite | 1 | 0 |
| ResolverSuite | 11 | 0 |
| DecisionOutputsSuite | 10 | 0 |
| GoldWorksheetSuite | 7 | 0 |
| StrictPolicySuite | 25 | 0 |
| **Total** | **63 passed** | **0** (0 errors, 0 ignored) |

GoldWorksheetSuite includes "committed worksheet and manifest equal a regeneration from the
committed inventory" and "every mention's source record ID occurs in the committed sample file".
Both passed, so the gold set is intact and traceable to the committed samples.

## Group 2: graph correctness

### 2a. Known small-graph tests (modules/spark)

Raw: [raw/02-spark-test.log.gz](raw/02-spark-test.log.gz) (run R02, exit 0; stored gzip-compressed,
11,579,704 bytes (about 11.6 MB) uncompressed, mostly Spark INFO lines; read it with `gunzip -c`, checksums in the manifest).

| Suite | Tests | Failed | Covers |
|---|---|---|---|
| PartASuite | 3 | 0 | connected components on empty, path, star, clique, and disconnected graphs; Part A jobs 1-4 on a hand-checked fixture (exact degree and weighted degree, metrics, components, and the referential-integrity, duplicate, dangling, and edge-support invariants) |
| PartBSuite | 6 | 0 | label propagation (communities), k-core coreness, bounded BFS paths, bridge measure, Part B jobs 5-11 with invariant status `pass` |
| ConstrainedClosureSuite | 13 | 0 | veto-constrained identity closure (chain, star, clique, bridge, random graphs) |
| IdentityVetoSuite | 18 | 0 | identity vetoes and ER-record integrity end to end inside Spark |
| **Total** | **40 passed** | **0** | |

### 2b. Cross-module graph and ER adversarial suites (tests/contracts)

Raw: [raw/03-contract-graph-er.log](raw/03-contract-graph-er.log) (run R03, exit 0; git-ignored by
`*.log`, archive with `git add -f`, see the archive note above).

| Suite | Tests | Failed | Covers |
|---|---|---|---|
| GraphAnalyticsSuite | 6 | 0 | connected components, k-core, bounded BFS, bridge measure, and label propagation against independent reference implementations on random, noisy, and disconnected graphs; a common-name bridge never merges ER-rejected mentions |
| IdentityVetoEndToEndSuite | 16 | 0 | ER decisions through Spark identity closure, end to end |
| EntityResolutionAdversarialSuite | 10 | 0 | adversarial ER inputs |
| ErStrictCrossPathSuite | 6 | 0 | strict ER policy, same decision on the entity-view and Hadoop paths |
| **Total** | **38 passed** | **0** (0 errors, 0 ignored) | |

These suites belong to test-engineer. This run only executed them.

### 2c. Referential integrity, duplicate rate, orphan rate, edge-support rate on the committed samples

**Not measured as rates on the committed samples.** The checks exist as Spark Part A invariants
(`edge-dangling-endpoint`, `edge-support-missing-work`, `edge-duplicate-pair`, `edge-duplicate-id`,
`authored-dangling-researcher`, `authored-dangling-work`) and counts (`erAcceptedMentionLinksDangling`,
`inputAuthorPaperDuplicateMention`, `pairsCollapsedSameWorkDuplicate`). They are written to
`invariants.json` and `counts.json` by a real pipeline run. In this run they were exercised only on the
hand-built test fixtures (2a, 2b), where they behaved as the tests expect. Getting them on the committed
samples needs the Hadoop, ER, and Spark mains (`runMain`, blocked for agents) or the bounded EMR
pipeline (`scripts/run-bounded-pipeline.sh`, run by deploy-engineer). No pipeline run is recorded in
`reports/deployments/`. The sampled provenance audit was not run either.

## Failed or blocked runs

- R04 (gold-set evaluation under er-policy-v5): blocked by the guard, not run. Kept in the manifest.
- No test failed in R01, R02, or R03 (141 tests in total: 63 + 40 + 38).

## Limitations

- The gold set has 48 hand-picked hard pairs from synthetic CC0 samples. Wilson 95% intervals are wide,
  and the rates describe the hard strata, not a population. The ER policy is still uncalibrated.
- The graph tests use small hand-built fixtures. They show that the algorithms are correct on known
  graphs. They do not measure integrity rates on real or sample-scale data.
- The raw logs were redacted at collection: the user-profile path prefix, the host name, and the user
  name are replaced with placeholders. No other byte changed. Checksums are in the manifest. The 01 and 02 raw files were
  regenerated twice from the same verbatim capture while the redaction rule was being completed, before
  any result was read from them.
- One read-only command ran in the main repository by mistake:
  `bash datasets/scripts/record-sample-checksums.sh --check` (verify only, output
  `result=ok files=7`). It wrote nothing. The clone's own check (`sha256sum -c`) also passed 7/7.

## Manual action

The human runs, from the repository root (or this clone):

```
sbt "entityResolution/runMain edu.uic.cs441.scholarchain.er.WorksheetEvalMain modules/entity-resolution/gold/v1/labeling-worksheet-v1.csv"
```

and saves the verbatim output as `experiments/phase-16/offline/raw/04-er-gold-eval-v5.txt` (a `.txt`
name, so the `*.log` ignore rule does not apply to it). That file
then supplies section 1a in full: the confusion matrix, P, R, F1, coverage, and unresolved rate by path
and overall, the threshold sweep, and the per-stratum table.
