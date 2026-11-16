# Phase 11 human gate

Decision: ACCEPTED (2026-11-15)

Scope: test-engineer adversarial pass over Phases 00-10, code-reviewer review-01, repair-01..03, rerun-01, review-02 (REQUEST_CHANGES), review-03 (APPROVE).

Gate evidence: `sbt -batch check` exit 0, 760 tests (758 passed, 2 skipped), orchestrator-observed at 21:47. Agent-file and dataset validators PASS.

My decisions:
- ER policy STRICT: rejected/unresolved decisions veto merges; missing evidence never raises a score; name + initials alone never accept.
- A missing year blocks the deterministic accept (RR3). Keep the initials-unknown accept block.
- G1 recall cliff deferred to P11-F3. R03-1 logged as P11-F9. F1-F3 fixed in repair-03.
- docs/project-description.md is the human copy of the course specification.

Merge change (gold pilot, er-policy-v5 vs v2, same worksheet sha256 449c3b82..., n=48):
accepted merges 12 -> 9 (-1 TP, -2 FP); P 0.750 -> 0.889; R 0.391 -> 0.348; F1 0.514 -> 0.500; coverage 0.563 -> 0.500.
Changes are within the Wilson 95% intervals. APPROVED: precision-first tradeoff under STRICT. No threshold change; the policy stays uncalibrated.

Follow-ups: P11-F1..F11 and DOC-10-1 (register in review-03.md).
Manual interventions: the gold pilot was run by the human (runMain is guard-blocked for agents).

Previous Phases:
## Regression check of earlier phases (2026-11-15)

| Phase | Area | Check | Result |
|---|---|---|---|
| 00–01 | Agent system | `bash .claude/hooks/validate-agent-files.sh` | PASS |
| 02 | Build | `sbt check` format and compile steps | PASS |
| 03 | Datasets | `validate-datasets.sh` / `record-sample-checksums.sh --check` | PASS (361 checks, 0 failed, 7 warnings) / 7 files ok |
| — | Core (domain) | core tests | 160/160 |
| — | Hadoop ingestion | hadoop tests | 83/83 |
| — | Entity resolution | ER tests | 63/63 |
| 06a/06b | Spark graph and analytics | spark tests | 40/40 |
| 07–08 | Storage and embedding ingestion | storage tests | 73 (71 passed, 2 skipped) |
| 09 | Runtime Graph-RAG | rag tests | 80/80 |
| 10 | REST services | services tests | 70/70 |
| 11 | Cross-module and adversarial | contract tests | 191/191 |
| **All** | | **`sbt -batch check` exit 0** | **760 tests: 758 passed, 0 failed, 2 skipped** |

Accepted commit: 85067146fc04ef56a07b157c477c784534da696d
