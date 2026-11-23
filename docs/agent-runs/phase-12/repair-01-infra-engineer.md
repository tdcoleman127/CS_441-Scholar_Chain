# Phase 12 repair-01: infra-engineer (code-reviewer round 1 findings 1-7)

Owner: infra-engineer. Delegated by the orchestrator on 2026-11-19, with review-01 and the human decisions R1-F2, R1-F3 and R1-F5 as input.
Agent-reported elapsed time: 05:09:52Z to about 05:27Z (about 17 min; timebox 45).

STATUS: COMPLETE

## Orchestrator verification (observed, 2026-11-20 UTC)

- `sha256sum infra/terraform/envs/dev/plans/*.tfplan`: one file, `dev-8da451a8460a-20261120T052239Z.tfplan`,
  `20076b4bed8c563e94402733bd0260d74d40d8a6a67545605c5e3507cae55b51`. This matches the report. The old plan file is gone.
- `git status`: only infra/terraform/**, scripts/** and the existing .gitignore and evidence files changed.
- `terraform fmt -check -recursive infra/terraform`: exit 0. `terraform -chdir=infra/terraform/envs/dev validate`: Success, exit 0.
- `bash -n` on deploy.sh, rollback.sh and run-bounded-pipeline.sh: all ok.
- Grep of the modules confirms: runtime/main.tf:213 `enable_deletion_protection = var.alb_deletion_protection`;
  iam/main.tf:266 `WriteBoundedOutputs`; iam/main.tf:776 adds `ecs:ListTaskDefinitions`; iam/main.tf:869 pipeline-runner role;
  workflow/main.tf:229 CreateCluster Catch now goes to `FindStartingCluster`; no `neptune-db:connect` action left (only a comment that cites the doc).
- **R2 confirmed:** `grep -c 'readyz' infra/terraform/envs/dev/dev.tfvars` returned `1`. Only the count was read.
  The human's local dev.tfvars still has the old `/readyz` line, which overrides the new `/healthz` default. The human must edit it.

## Fresh plan (agent-reported)

`bash scripts/plan.sh dev`: exit 0, **137 to add, 0 to change, 0 to destroy**, `DESTRUCTIVE none`, tree=DIRTY.
The count is unchanged because every new resource is gated off: the pipeline-runner role (no principals),
the ALB (enable_runtime) and the Neptune policy (enable_neptune). The other changes edit resources that were
already counted. Checkov: 552 passed, 0 failed, 46 skipped (48 skip comments, 2 of them new: CKV_AWS_356 on ListClusters, once in pipeline_runner and once in step_functions).

## Per finding (agent-reported)

| Finding | Change |
|---|---|
| F1 | New `alb_deletion_protection` variable (default false), separate from data_cleanup_mode. CKV_AWS_150 skip text is now true. |
| F2 | health_check_path defaults to `/healthz` (Router.scala:138-143: 200, no dependencies, no auth). smoke-test.sh already treats /readyz as EXPECTED-FAIL. |
| F3 | deploy.sh needs SCHOLARCHAIN_API/WORKER_TASK_DEFINITION_ARN (the Terraform base). It checks account, ACTIVE, family and a valid release_run_id, rewrites only the image, and prints PROVENANCE/BASE and release_run_id. ignore_changes is kept. README gives the procedure. |
| F4 | ecs:ListTaskDefinitions added to the deployment role. rollback.sh: AWS errors now exit 4 with EVIDENCE; no match stays exit 3. |
| F5 | `scholarchain-dev-pipeline-runner` role, count 0 until `pipeline_runner_principal_arns` is set; 1-hour sessions. |
| F6 | CreateCluster Catch goes to FindStartingCluster (aws-sdk emr:listClusters, CreatedAfter = execution start), then a Map that terminates the cluster matching the deterministic name, then Fail. A failed lookup or terminate goes to ClusterCleanupFailed. README corrected. |
| F7 | EMR writes limited to `curated/bounded-smoke/*`; reads unchanged. No stage writes run-manifests/ (grep). |
| Neptune | `neptune-db:connect` removed (deprecated all-access action, per the 1.2.0.0 release notes). Only ReadDataViaQuery remains. |

## IAM changes (agent-reported)

1. NEW pipeline-runner (not created now):
   - states:StartExecution, DescribeStateMachine, ListTagsForResource and ListExecutions on the pipeline state machine
   - states:DescribeExecution and GetExecutionHistory on its executions
   - emr:ListClusters on `*`
   - emr:DescribeCluster and ListSteps on cluster/*, with ResourceTag environment=dev
   - s3:ListBucket on curated (prefix bounded-smoke/*)
   - s3:GetObject on samples/, curated/bounded-smoke/, artifacts/ and logs/emr/
   - kms:Decrypt via s3
   - No write, PassRole or IAM actions.
2. Step Functions role: + elasticmapreduce:ListClusters on `*`.
3. Deployment role: + ecs:ListTaskDefinitions on `*`.
4. EMR instance profile: write/delete narrowed to curated/bounded-smoke/*.
5. Neptune reader: connect removed; only ReadDataViaQuery remains.

## Risks (agent-reported)

- R1: the F6 state-machine definition (aws-sdk parameters, StringEqualsPath, `$$.Execution.StartTime` for CreatedAfter) is checked by AWS only at apply. Suggestion: run the read-only `states validate-state-machine-definition` before apply.
- R2: dev.tfvars `/readyz` override. Orchestrator-confirmed above.
- R3: deploy.sh now needs the two base-ARN env vars, read from `terraform output runtime`.
- R4: a rollback restores an older release_run_id, by design (it is printed).

## Manual actions (human)

1. Edit your local dev.tfvars: change `health_check_path = "/readyz"` to `"/healthz"`, or delete the line so the default applies.
2. Optionally set `pipeline_runner_principal_arns`. This adds the role and needs a new plan and sign-off.
3. Copy decisions R1-F2, R1-F3 and R1-F5 into human-gate.md.
4. Sign off on a plan checksum after code-reviewer round 2. If dev.tfvars changes, the plan must be rerun.

NEXT GATE: code-reviewer round 2 on the repaired diff.
