# Phase 11 review-02: code-reviewer (repair-01 + repair-02)

Assembled by the orchestrator from the code-reviewer final report, 2026-11-15. Scope: `git diff HEAD` (8bb4cbc) plus untracked files under modules/ and tests/.

VERDICT: REQUEST_CHANGES. No BLOCKER, no MAJOR. 3 MINOR, 2 NIT, 1 record-only NIT.

## Reviewer evidence (observed by the reviewer)

`sbt -batch check` exit 0 (451 s):
- core 160/160, storage 73 (71 passed, 2 skipped), rag 80/80, services 70/70, ER 50/50, contractTests 186/186, spark 31/31, hadoop 75/75;
- every format step "[success]"; markTestRun updated the marker.

Other checks:
- Ownership: every changed file is in its owner's territory; no constitutional or build file changed.
- Module boundaries: spark → er (allowed by build.sbt:178), rag → storage port (allowed).
- Human decisions: all implemented.
- Docs: none contradicted; the API changes are still undocumented (DOC-10-1).

## Findings

| ID | Severity | File:line | Scenario | Impact | Owner |
|---|---|---|---|---|---|
| F1 | MINOR | hadoop DblpXmlInputFormat.scala:159-168 (resync), doc :86-93 | A split starts at s > 0; well-formed records R1..Rn are followed, within 64 KiB, by a record whose character data holds a literal `]]>` (malformed XML). Resync skips to that `]]>`, so R1..Rk are never emitted by any reader. | Silent loss of well-formed records (no reject, no counter), dependent on split placement. This contradicts "reject, never drop". At HEAD only the malformed record was rejected, and it was counted. Unlikely on real dumps. | hadoop-engineer |
| F2 | MINOR | spark PartA.scala:235-253 (no single-run check); ER Outputs.scala:114-128 (non-atomic overwrite, accepted.jsonl written first) | ResolveMain is rerun into an existing outDir and killed after accepted.jsonl but before unresolved.jsonl. Spark reads R2's links with R1's stale vetoes, so a new A-C veto is missing. A 0-byte veto file is also read as "no vetoes". | Over-merge despite the veto; the same fail-open class RR1 closed. | spark-engineer (fail on more than one distinct provenance.pipelineRunId); entity-resolution-engineer (atomic temp-dir plus rename, or a completion manifest with counts) |
| F3 | MINOR | ER Policy.scala:117-133 (no range check on `mention-coauthor-min`); Resolver.scala:395-398; StrictPolicySuite.scala:177 | A policy with `deterministic.mention-coauthor-min = 0.0` validates. Since M1, empty coauthors give 0.0, so two single-author "J. Smith" mentions are Accepted on name-exact+coauthor-overlap. | Name + initials + year reach Accepted, violating STRICT. Latent (default 0.5). The "(b) any thresholds" test never varies the deterministic minimums. | entity-resolution-engineer |
| F4 | NIT | ER Resolver.scala:210-211 | The doc says missing evidence can cause neither accept nor reject. A mononym single-author pair with disjoint titles and a 20-year gap is Rejected only because empty coauthors count as 0.0 (M1). | The doc is contradicted (rerun G2). Precision-safe. | entity-resolution-engineer |
| F5 | NIT | rag Evidence.scala:144-146 | The "byte-identical bundles" comment is wrong once truncation applies (60 items or 24k characters): truncation runs in retrieval order before the sort. | Only the comment overstates (rerun G3). | rag-engineer |
| F6 | NIT (record) | repair-01-hadoop.md:19 vs DblpXmlInputFormat.scala:69-71 | The report says unclosed openers cannot swallow later records. One whose closer appears within 64 KiB does (rerun G5). The code comment is accurate. | Evidence-record overstatement; human-owned path. | human (may annotate) |

Checked and dropped by the reviewer: null semantic provenance; allow-list vs storage-written values; globalVictim order (LinkedHashMap); the linkOk vs linkRecs filters; gold-worksheet join after the policy bump; consumers of the new spark columns.

## Review-01 status

All review-01 findings are FIXED: D1-D6, D3-floor, D6-nested, M1-M5, R-09-11, N2, CO-4, NIT-1, NIT-2, and rerun RR1-RR6. Residuals:
- D1 → F2;
- D3-floor → F3;
- CO-4 → F1;
- M1: the view path folds "Last, First" and Hadoop does not;
- D4 → G4.

## Manual actions raised

- docs/project-description.md is untracked in git. repair-01-rag.md says "committed by the human", so commit it or correct that record.
- Commit the staged rename er-policy-v1.conf → er-policy-v4.conf with the edits.
- Run the v4 gold pilot (runMain is blocked for agents).

## Human decision (2026-11-15)

Fix F1, F2, and F3 in repair-03, with the F4 and F5 comment fixes alongside, then a short review-03. Sequence:
1. entity-resolution-engineer: F2 producer side (atomic output plus a completion manifest), F3, F4.
2. spark-engineer: F2 consumer side (verify the manifest; single run ID).
3. hadoop-engineer: F1.
4. rag-engineer: F5.

## Reviewer routing proposal

- hadoop-engineer: F1.
- spark-engineer: F2 (check).
- entity-resolution-engineer: F2 (atomic output or manifest), F3, F4.
- rag-engineer: F5.
- test-engineer (optional): red tests for F2 and F3.
- documentation-engineer: DOC-10-1.
