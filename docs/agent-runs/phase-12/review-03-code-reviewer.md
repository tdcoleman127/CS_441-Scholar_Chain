# Phase 12 review round 3: code-reviewer (narrow: repair-02)

Owner: code-reviewer (read-only). Delegated by the orchestrator on 2026-11-20.
Scope: the repair-02 changes in scripts/rollback.sh and scripts/run-bounded-pipeline.sh. Terraform unchanged.
Agent-reported time: started 00:31:52 CST, about 15 min.

VERDICT: APPROVE (N1 FIXED, N2 FIXED, no regressions; 1 MINOR non-blocking finding N3)

## Status

- **N1: FIXED.** `aws_read` captures the exit code in an `||` list, so neither errexit nor the ERR trap fires before
  classification. Every call site uses `aws_read ... || aws_read_fail "$?"`. Exit 124 always maps to 4. The not-found
  patterns are anchored on the parenthesised AWS error code (ImageNotFound/RepositoryNotFound, ClusterNotFound,
  ClientException "unable to describe task definition"), so AccessDenied, Throttling, ExpiredToken, timeouts and SSO
  errors cannot match. A MISSING service exits 3. The sts preflight is read-only. The header exit table matches the code.
- **N2: FIXED.** "termination verified" requires both a non-termination-failure exec_error and an observed
  TERMINATED/TERMINATED_WITH_ERRORS state. A failed describe gives UNKNOWN, which prints MANUAL. The remaining
  `2>/dev/null || true` reads at :688 and :692 are acceptable best-effort diagnostics: a failed read leads to MANUAL,
  or to a true "verified" for this run's own cluster. The only cost is lost diagnostic detail.
- **Regression:** none. Exit codes, marker names (new: CALLER) and the README claims still hold. `bash -n` is clean for both scripts.

## New finding

| # | Sev | Location | Summary | Owner |
|---|---|---|---|---|
| N3 | MINOR (non-blocking) | scripts/run-bounded-pipeline.sh:693 with :721-724 | If RunJobFlow fails synchronously while another non-terminal EMR cluster created after the execution start exists in the account, FindStartingCluster records that cluster's j-ID. The MANUAL line then names it without the expected cluster name, so a human might terminate an unrelated cluster. Unlikely in a single-project dev account. Suggested fix: print the expected name `scholarchain-<env>-pipeline-<execution>` on the MANUAL line even when the ID is known, and tell the human to compare names before terminating. | infra-engineer |

Orchestrator spot-check (observed): run-bounded-pipeline.sh:723-724 prints only `describe-cluster --cluster-id $cluster_id`
when the ID is known. The expected name appears only in the `list-clusters` branch (:726). N3 is confirmed.

## Reviewer notes

- R-a (cosmetic, unverified): with `set -E`, the ERR trap may print an extra "unexpected error" line inside the command
  substitution at rollback.sh:82. It cannot change the exit code.
- The scan-loop describe calls (:206, :215) exit 4 but print stderr without the EVIDENCE prefix (disclosed in repair-02).
- R-b (dev.tfvars not saved) is now resolved: the human saved `/healthz` at Nov 20 00:33 (orchestrator-verified), and the final plan used it.

NEXT GATE: human decision on N3 (fix now or defer), then human sign-off on the final plan checksum.
