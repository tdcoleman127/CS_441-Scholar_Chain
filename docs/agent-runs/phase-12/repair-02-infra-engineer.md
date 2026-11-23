# Phase 12 repair-02: infra-engineer (code-reviewer round 2 findings N1, N2)

Owner: infra-engineer. Delegated by the orchestrator on 2026-11-20 after the human chose "fix now, then plan".
Agent-reported elapsed time: 00:26:19 to 00:30:07 CST (about 4 min). Scripts only; no Terraform change, no plan.

STATUS: COMPLETE

## Orchestrator verification (observed, 2026-11-20)

- `git status`: only scripts/run-bounded-pipeline.sh (modified) and the already-untracked scripts/infra/evidence files. No other change.
- `bash -n scripts/rollback.sh`: ok. `bash -n scripts/run-bounded-pipeline.sh`: ok.
- A grep for `2>/dev/null || true` in rollback.sh: no match (N1 masking removed).
  run-bounded-pipeline.sh still has it at :688 (get-execution-history `[error,cause]` read) and :692 (execution
  history read). Both are diagnostic reads on the failure path. Left for code-reviewer round 3 to judge.
- run-bounded-pipeline.sh:717 `CLUSTER ... (termination verified)` and :721 `MANUAL cluster=... termination NOT verified` are present.
- dev.tfvars:79 still reads `health_check_path    = "/readyz"`, and the file's mtime is unchanged (Nov 19 21:52).
  The human reported the edit as done, but it is not saved on disk. Only that line was printed.

## Changes (agent-reported)

| # | Change |
|---|---|
| N1 | rollback.sh:72-101 adds the helpers `aws_read` and `aws_read_fail`: stderr is captured and printed as EVIDENCE; a timeout (124) or any non-not-found error exits 4; a matching not-found exits 3. A `sts get-caller-identity` preflight (:135-138) fails expired sessions first with exit 4. The describe-task-definition, ECR describe-images and describe-services calls (old :109, :115-117, :122-123) now classify errors. A service MISSING from failures[] exits 3. Header and exit table updated. |
| N2 | run-bounded-pipeline.sh:690-729: termination counts as verified only if the execution error is neither ClusterTerminationFailed nor ClusterCreationFailed and the cluster state is TERMINATED or TERMINATED_WITH_ERRORS. Otherwise it prints MANUAL lines naming the cluster ID (or `unknown`), the 1800 s idle backstop, and a read-only inspect command. Still exits 4. |

Agent-reported test: an offline stub test of the two N1 helpers (scratchpad n1-classify-test.sh, no AWS) passed 9 of 9 cases.

## Exit codes (agent-reported)

- rollback.sh: 0 ok; 2 usage/config; 3 genuine not-found or precondition; 4 other AWS error, timeout or failed update; 5 not stable; 6 verification failed.
- run-bounded-pipeline.sh: meanings unchanged (0, 2, 3, 4, 5).

## Assumptions and risks (agent-reported)

- The wording for a missing revision from DescribeTaskDefinition (`ClientException ... Unable to describe task definition`) is assumed. If it is wrong, the error falls back to exit 4 with EVIDENCE (fails loud).
- A TERMINATING cluster state prints MANUAL, which can be a false alarm on PipelineStepFailed. This is honest by design.
- The two scan-loop describe-task-definition calls already exit 4, but their stderr goes to the terminal instead of EVIDENCE lines (left unchanged).

NEXT GATE: code-reviewer round 3 on N1 and N2. Then the human saves dev.tfvars, the orchestrator reruns plan.sh, and the human signs off.
