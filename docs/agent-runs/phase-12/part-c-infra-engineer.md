# Phase 12 part C: infra-engineer (six scripts, checkov, plan)

Owner: infra-engineer. Delegated by the orchestrator on 2026-11-19, in a fresh context, with the part A and B reports as input.
Timebox: 45 min. Agent-reported elapsed time: 21:54:36 to 22:18:55 CST (about 24 min).

STATUS: COMPLETE (part C scope; code-reviewer and human plan sign-off still pending)

## Human prep (orchestrator-verified before delegation)

`infra/terraform/envs/dev/dev.tfvars` exists and is ignored (`.gitignore:48:*.tfvars`). A count of the
dummy placeholder `example.invalid` returned 0. The orchestrator did not read the file's contents.

## Orchestrator verification (observed, 2026-11-19)

- `sha256sum infra/terraform/envs/dev/plans/*.tfplan`: one file,
  `dev-8da451a8460a-20261120T041516Z.tfplan`,
  `3e3dbf842fedcc251e5cf756e8b6209d94d124db10f7eb34fe73d7681845130a`. This matches the agent's report.
- `git check-ignore -v`: the plan file is ignored (`.gitignore:50:*.tfplan`), and so is
  `local_backend_override.tf` (`.gitignore:60:*_override.tf`).
- `git status`: outside `infra/terraform/**` and `docs/agent-runs/phase-12/**`, the only changes are
  `scripts/run-bounded-pipeline.sh` (modified) and the five new scripts, plus the existing `.gitignore`
  and human-gate/prompt edits.
- `terraform fmt -check -recursive infra/terraform`: exit 0.
- `bash -n` on plan, deploy, rollback, smoke-test, run-bounded-pipeline and cleanup: all ok.
- Grep of scripts/*.sh and infra/terraform sources for 12-digit numbers and email addresses: only the
  dummy `000000000000` in the two example files.
- Not verified by the orchestrator: the plan contents and counts (the plan file and its text were not
  opened, because they contain the account ID and variable values), the checkov totals, IAM content and prices.

## Plan (agent-reported)

- `bash scripts/plan.sh dev`, exit 0. UNLOCKED (`-lock=false`, D4), local-backend fallback,
  scholarchain-readonly, us-east-1.
- **Plan: 137 to add, 0 to change, 0 to destroy.** `[plan] DESTRUCTIVE none`.
- The plan covers the uncommitted working tree at HEAD 8da451a. A rerun after commit produces a new checksum.
- By module: data_lake 47, network 39, iam 20, runtime 6, vectors 6, workflow 5, dynamodb 5,
  observability 5, kms 2, budget 1, root secret 1.
- Gated off and absent: ALB, ECS services and task definitions, Neptune, interface endpoints,
  the deployment role, neptune-read policies. No NAT.
- Stateful creates: 5 S3 buckets, 5 DynamoDB tables, the vector bucket and 5 indexes, KMS key and alias,
  the ECR repository, the secret container (no value), 2 SQS queues.
- Plan text: the budget recipients block is marked sensitive, and 0 email-like strings were found. The
  account ID appears in ARNs and bucket names; never paste the plan text into evidence.
- IAM: 9 roles, 9 inline policies, 1 instance profile, 1 managed attachment. Actions and resources match
  parts A and B. Part C changed no IAM.
- Public: IGW, public route, 2 public subnets, and the unattached ALB security group (0.0.0.0/0:80, D12).
  Every bucket has a public access block, BucketOwnerEnforced and a TLS-only policy.

## Checkov (agent-reported)

The literal `uvx checkov` fails on Windows (no `checkov` executable). The working form is
`PYTHONUTF8=1 uvx --python 3.12 --from checkov python -m checkov.main -d infra/terraform --compact --quiet`
(same package).
Run 1: 525 passed / 13 failed / 32 skipped. Final: 525 passed / 0 failed / 45 skipped.
Part C added skips only (no attribute changes): neptune CKV_AWS_361, CKV2_AWS_58; network CKV2_AWS_5 x4,
CKV_AWS_260; runtime CKV2_AWS_20 (LB), CKV_AWS_378; data_lake CKV2_AWS_61, CKV_AWS_145, CKV_AWS_18,
CKV_AWS_21 (for_each false positives). The full A/B/C skip list is in the agent report.

## Scripts (agent-reported gates)

- All scripts: `set -Eeuo pipefail`, an ERR trap, dev-only as the first argument, `[name] STEP n` markers, `timeout` on cloud calls.
- plan.sh: refuses static keys and requires an assumed read-only role. Runs fmt, init, validate, then
  plan -lock=false -var-file=dev.tfvars into plans/*.tfplan. Prints the full output, the SHA-256 and the
  Plan: line, and exits 4 on any destroy or replacement.
- deploy.sh: no terraform (D14). Refuses a dirty tree before and after the build. Tags with the full
  40-character SHA and checks that ECR tags are immutable. Builds with `sbt -batch dockerLocal`, then
  registers revisions, updates services, waits for stable, and verifies task definition, tag and digest
  (exits 6 on mismatch). Needs neither package.sh nor check.sh.
- rollback.sh: targets a SHA or revision, checks the image exists in ECR, updates, waits, verifies. No dirty-tree refusal (incident use).
- smoke-test.sh: read-only. /healthz must return 200. /readyz is EXPECTED-FAIL until a Neptune adapter
  exists. Unauthenticated /v1/query must return 401. The API key goes through a mode-600 file and is never
  printed. Checks the running image SHA.
- run-bounded-pipeline.sh: keeps the Phase 04 bounds, sample-only inputs, bucket refusals, step names,
  mains, argument blocks and exit codes. Removes SCHOLARCHAIN_EMR_CLUSTER_ID and emr add-steps (D5).
  Its one mutation is `stepfunctions start-execution`. Stages: hadoop, spark-part-a and spark-part-b,
  each a separate execution.
- cleanup.sh: human only. Lists everything first. `--execute` needs a non-read-only profile, no table
  with deletion protection on, a TTY, and the typed text `delete scholarchain-dev`. It then prints the
  destroy commands for the human.

## Risks and open decisions (agent-reported)

1. Pipeline-runner IAM gap: no project role grants the states/emr/s3 reads and StartExecution that
   run-bounded-pipeline.sh needs, so it runs under the operator's own profile. A runner role needs a human decision.
2. deploy.sh copies the live service revision, so Terraform task-definition env/secret changes do not
   reach services (ignore_changes). A human should decide the procedure for this.
3. Revisions registered by deploy.sh are untagged (the deployment role has no ecs:TagResource).
4. The Spark stages depend on artifacts nobody produces yet: ER decisions in S3 (ResolveMain is local-only),
   a Spark assembly jar, a .conf in S3, a path-pairs file.
5. /readyz stays EXPECTED-FAIL and the ALB health check defaults to /readyz. The /healthz decision is still open.
6. Prices from memory, unverified. Runtime stack left on 24x7 is about USD 65 to 80/month, which exceeds the budget in about 10 days.
7. Carried over: P12-F1, no worker consumer, no EMR security configuration, S3 gateway endpoint full-access.

Follow-ups to route: entity-resolution-engineer or hadoop-engineer (ResolveMain between stages);
spark-engineer (argument blocks and sizes); build-engineer (Spark assembly jar and `--files` config);
storage-engineer (Neptune adapter).

## Manual actions (human)

1. Review and sign plan `dev-8da451a8460a-20261120T041516Z.tfplan`, SHA-256
   `3e3dbf842fedcc251e5cf756e8b6209d94d124db10f7eb34fe73d7681845130a` (137/0/0), or replan after the commit and sign the new checksum.
2. Decide bootstrap-first. Afterwards, delete local_backend_override.tf and create backend.hcl.
3. Decide the pipeline-runner principal and /healthz vs /readyz.
4. Run cleanup.sh only yourself, after a data_cleanup_mode=true apply.

NEXT GATE: code-reviewer adversarial review of infra/terraform/** and scripts/** (plus prep-01/02 .gitignore if the human folds them in), then human plan sign-off. Apply only by the human.
