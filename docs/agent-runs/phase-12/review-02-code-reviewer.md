# Phase 12 review round 2: code-reviewer

Owner: code-reviewer (read-only). Delegated by the orchestrator on 2026-11-19, after repair-01.
Scope: the full Phase 12 change set (infra/terraform/**, scripts/**, .gitignore).
Agent-reported elapsed time: 23:29:52 to 23:36:16 CST (about 6.5 min).

VERDICT: APPROVE (all round 1 findings FIXED; 2 new LOW findings)

Reviewer limits: terraform validate was blocked by the fence (`-chdir`) and rests on the orchestrator's observed
run. fmt -check and the three bash -n checks were observed passing. dev.tfvars, the .tfplan file and state were not opened.

## Round 1 status

| # | Status | Key evidence |
|---|---|---|
| F1 | FIXED | runtime/main.tf:213 uses var.alb_deletion_protection (default false). The CKV_AWS_150 skip text is true. |
| F2 | FIXED (R1-F2) | Default is /healthz. Router.scala:138-143 returns a constant 200. smoke-test keeps /readyz as EXPECTED-FAIL. |
| F3 | FIXED (R1-F3; D14 holds) | deploy.sh requires the Terraform base ARNs, rewrites only the image and prints provenance. No terraform call. |
| F4 | FIXED for the listed call | ListTaskDefinitions added. rollback.sh list/describe now exit 4. A related gap remains (N1). |
| F5 | FIXED (R1-F5) | Every pipeline-runner grant maps to a call in run-bounded-pipeline.sh. No extra grants, and no write, PassRole or IAM. |
| F6 | FIXED | The FindStartingCluster/TerminateStartingCluster logic is correct. The match is limited to this execution's name. Every path ends in a terminate request or an explicit Fail. README is accurate. |
| F7 | FIXED | Writes are limited to curated/bounded-smoke/*. Every current stage writes there. Reads unchanged. |

## New findings

| # | Sev | Location | Summary | Owner |
|---|---|---|---|---|
| N1 | LOW | scripts/rollback.sh:115-117 (also :109, :122-123) | The ECR describe-images call keeps `2>/dev/null \|\| true`, so any AWS error (expired SSO session, throttling, timeout) exits 3 with a false "expired by lifecycle?" message. The describe-task-definition and describe-services calls behave the same way. The header says exit 4 means an AWS read error. | infra-engineer |
| N2 | LOW | scripts/run-bounded-pipeline.sh:706 | Every non-SUCCEEDED execution prints "the cluster was terminated by the state machine". That is false on the ClusterTerminationFailed and ClusterCleanupFailed paths, so the operator may miss a cluster that keeps billing until the 1800 s idle backstop. | infra-engineer |

Orchestrator spot-check (observed): rollback.sh:115-117 shows `2>/dev/null || true` followed by
`die 3 "... not in ECR (expired by lifecycle?) ..."`. run-bounded-pipeline.sh:706 shows
`die 4 "... and the cluster was terminated by the state machine"`. Both findings confirmed.

## Unverified assumptions (reviewer)

- A1: Step Functions accepts the ISO-8601 `$$.Execution.StartTime` string for `CreatedAfter`. Covered by
  the read-only `aws stepfunctions validate-state-machine-definition` (syntax only) before apply.
- A2: an empty ListClusters result still includes `"Clusters": []`. If the key is missing, the execution fails
  with an unclear error, but no cluster is left running.
- A3: the Neptune doc claims were not fetched by the reviewer. Neptune is gated off.
- A4: EMRFS `_$folder$` markers above bounded-smoke/. This gap is unchanged since round 1.

## Risks (reviewer)

- R-a (by design, R1-F5): the pipeline-runner can call StartExecution directly and skip the script's
  sample-only checks. The state machine still bounds steps, time, cluster size and write prefix, but raw/*
  reads stay possible through the EMR instance profile. "Sample inputs only" is enforced by the script, not by IAM.
- R-b: the local dev.tfvars `/readyz` line (a human item).

NEXT GATE: decide whether N1 and N2 are fixed before sign-off. Then the human edits dev.tfvars, plan.sh is
rerun, and the human signs off on the fresh checksum.
