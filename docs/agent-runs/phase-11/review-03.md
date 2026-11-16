# Phase 11 review-03: code-reviewer (repair-03)

Assembled by the orchestrator from the code-reviewer report, 2026-11-15. Scope: the repair-03 changes for review-02 findings F1-F5.

VERDICT: APPROVE.

## Status of F1-F5

| ID | Status | Reviewer verification |
|---|---|---|
| F1 | FIXED | g(b) is monotone. The only exception is inside the `]]>` token, which holds no `<`. Hand-off ownership means no tag is lost. A tag can be double-owned only when a third boundary falls inside a disagreement window (documented). Peeks are bounded. Tests discriminate (7 of 8 failed on the pre-fix scanner). |
| F2 | FIXED | Writer: pre-checks, refusal of foreign or linked directories, UUID staging, ATOMIC_MOVE with no fallback, undo, deletion of owned files only (never recursive). Verifier: H1-H5, V1-V4 (the digest covers every byte). Spark: the gate runs before any read with no fallback, plus `erRunProblems`; the manifest sha is a run input. Windows ATOMIC_MOVE failure modes all fail safe. |
| F3 | FIXED | `validated` enforces the coauthor and title minimums against the corroboration minimums, finite values, accept > 0, weights ≥ 0, and sweep values in [0,1]; `withCoreSettings` validates too. Both deterministic accepts require corroboration. StrictPolicySuite "(b)" covers 20 policies × 2 scopes. |
| F4 | FIXED | The M1 exception doc is accurate (Resolver.scala:213-219). |
| F5 | FIXED | Evidence.scala:146-155 and :227-230 match `build`. |

## Findings (not regressions)

| ID | Severity | File:line | Scenario | Impact | Owner |
|---|---|---|---|---|---|
| R03-1 | MINOR (residual) | spark PartA.scala:286 (decisions persisted MEMORY_AND_DISK); gates only at :223 and :302 | On EMR, after the gates pass, an operator re-syncs a different ER run into the same S3 prefix. A lost executor then recomputes a cached veto partition from the new files, while the old accepted links stay cached. No gate runs again. | A silent over-merge (F2 class). repair-03's "caught via count or run ID" understates this. Fixes: `decisions.localCheckpoint(eager = true)` before the gates, or a manifest re-check before commit. | spark-engineer |
| R03-2 | NIT | hadoop DblpXmlInputFormat.scala:113-116 vs Jobs.scala:89-96 | Under the documented double-ownership limit, a record that fails parsing is rejected twice (rejects are not deduplicated). | Counter drift only; no loss. The comment's "counted duplicate" holds for accepted records only. | hadoop-engineer (comment) |
| R03-3 | NIT (record) | repair-03.md (hadoop section, "about 128 KiB") vs DblpXmlInputFormat.scala:105-107 | Read-past can exceed 128 KiB when the input chains sections that the two lexers read differently; the buffer stays bounded. The code comment is accurate. | Evidence-record overstatement. | human (may annotate) |

## Human decision (2026-11-15)

Log R03-1 and R03-2 as follow-ups and proceed to the Phase 11 human gate.

## Phase 11 follow-up register (for the human gate)

| ID | Owner | Item |
|---|---|---|
| P11-F1 | rag-engineer | GraphExportReader: reject duplicate metric rows at load time (then test-engineer adjusts the D2 setup) |
| P11-F2 | hadoop-engineer | Cap the record buffer size (bounded record size) |
| P11-F3 | entity-resolution-engineer, then hadoop-engineer | G1 recall cliff: Hadoop path accepts only on ORCID; carry initials or name strings; decide after the v5 gold pilot |
| P11-F4 | storage-engineer | Printable-token rule in `VectorMetadata.validate` |
| P11-F5 | domain-engineer | Core `Validators.provenance`: reject control and line-separator characters |
| P11-F6 | (unused) | |
| P11-F7 | spark-engineer, entity-resolution-engineer | Driver-side ER manifest verification cost at full-corpus scale |
| P11-F8 | rag-engineer | Truncate bundles on a deterministic plan-derived rank (rag-evidence-bundle-v4) |
| P11-F9 | spark-engineer | R03-1: eager checkpoint of ER decisions before the gates (or a re-check before commit) |
| P11-F10 | hadoop-engineer | R03-2: class comment, rejects under double ownership are not deduplicated |
| P11-F11 | entity-resolution-engineer | Gold pilot v5: 7 deterministic-path false rejects (labeled match, decided reject; under STRICT these veto merges). By stratum: spelling-variant 3, same-record-other-position 2, name-order 1, different-name 1. Analyze the reject rules (same-work, incompatible-initials, name order) against these pairs. |
| DOC-10-1 | documentation-engineer | Services API docs, including null researcher fields, 503 OVERLOADED at the global cap, and 415 on non-exact JSON |

## Other checks

- Ownership and boundaries: all paths are in their owners' territory; spark → er is allowed; no build or constitutional change.
- Law 2: every behavior change has a test that fails without it.
- Law 3: the ER run ID, versions, and hashes are recorded in params.json and the run manifest.
- The reviewer did not run sbt and relied on the orchestrator's `sbt -batch check` exit 0 at 21:47 (403 s).

## Residual risks noted

- P11-F7: driver-side verification cost.
- Stale `.staging-*` and `.replaced-*` directories are not auto-cleaned.
- On Windows, a `deleteOwned` failure after a successful swap reports failure although the new run is published (safe but misleading).
- The ER policy is still UNCALIBRATED.

## Gold pilot v5 (human-run, 2026-11-15; output pasted into the orchestrator session)

The human ran `WorksheetEvalMain` because runMain is guard-blocked for agents. Worksheet sha256 449c3b82…52e4 matches the v2 run (gold-eval-v1.txt). Policy er-policy-v5, features er-features-v4. labeled=48, unlabeled 0, unsure 0, malformed 0.

| Overall | v2 (gold-eval-v1.txt) | v5 | Delta |
|---|---|---|---|
| Accepted merges (TP+FP) | 12 (9+3) | 9 (8+1) | −3 (−1 TP, −2 FP) |
| Precision | 0.750 | 0.889 [0.565, 0.980] | +0.139 |
| Recall | 0.391 | 0.348 [0.188, 0.551] | −0.043 |
| F1 | 0.514 | 0.500 | −0.014 |
| Coverage | 0.563 | 0.500 | −0.063 |

v2 TP and FP are derived from the recorded P and R with 23 labeled matches (v5: TP 8 + FN 7 + U+ 8 = 23).

By path (v5):
- exact-id: n=8, TP 6, FP 1, U- 1.
- deterministic: n=17, TP 2, TN 8, FN 7.
- probabilistic: n=23, all unresolved (coverage 0.000; U+ 8, U- 15).
- llm-adjudicated: n=0.

Reading (orchestrator):
- The change matches the predicted direction: precision up, recall and coverage down.
- The precision and recall changes fall inside the Wilson 95% intervals at n=48, so the change is not statistically distinguishable at this sample size.
- Probabilistic coverage of 0 is G1 (P11-F3) measured on the gold set, not just reasoned from code.
- The 7 deterministic false rejects are logged as P11-F11.
- The sweep shows that lowering accept to 0.65-0.75 would give TP 11, FP 3 (P 0.786, R 0.478, F1 0.595). No threshold change is proposed: the policy stays uncalibrated, and any change needs a human decision.

Pending: the human decides on the accepted-merge delta (−3) against v2 and records the decision in phase-11/human-gate.md.
