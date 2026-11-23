# Phase 12 repair-03: infra-engineer (code-reviewer round 3 finding N3)

Owner: infra-engineer. Delegated by the orchestrator on 2026-11-20 after the human chose "fix it now".
Agent-reported elapsed time: 00:43:52 to 00:44:42 CST (about 1 min). Change is limited to the message text in scripts/run-bounded-pipeline.sh.

STATUS: COMPLETE (not yet re-reviewed by code-reviewer)

## Change (agent-reported, orchestrator-observed)

scripts/run-bounded-pipeline.sh:723-729, in the known-cluster-ID branch of the "termination NOT verified" failure path:
- New line: `MANUAL expected cluster name=scholarchain-$ENVIRONMENT-pipeline-$EXECUTION_NAME; confirm that cluster $cluster_id has exactly this Name before terminating it, and do not terminate it if the Name differs`
- The inspect command now shows the name: `aws emr describe-cluster --cluster-id $cluster_id --query '[Cluster.Name,Cluster.Status.State]' --output text`
- The name matches the state machine's `States.Format('${var.name_prefix}-pipeline-{}', $$.Execution.Name)` (workflow/main.tf:105, :263),
  with name_prefix `scholarchain-<env>`.
- Unchanged: :721-722, the unknown-ID branch, RESULT, `die 4`, every gate and every other marker. Header and README not touched (neither describes these lines).

## Orchestrator verification (observed, 2026-11-20)

- `bash -n scripts/run-bounded-pipeline.sh`: ok. Lines 723-729 read as above.
- `find infra/terraform -newer <final plan file> -type f` (excluding .terraform/): no output. No Terraform file changed after the final plan.
- `sha256sum` of the final plan file: still `7717aa50947ab4b785a9ab673881e76379a71e0334388a6f3b1072dd41014f38`.

## Review

This change came after code-reviewer round 3 APPROVE. It is advisory text only, with no logic, gate or exit-code change,
but it has not been reviewed. The human decides whether a narrow round 4 is required before commit.
