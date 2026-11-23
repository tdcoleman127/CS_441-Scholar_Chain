# Phase 12 review round 1: code-reviewer

Owner: code-reviewer (read-only). Delegated by the orchestrator on 2026-11-19.
Scope: infra/terraform/**, scripts/** (including the run-bounded-pipeline.sh diff), and .gitignore (prep-01 and prep-02).
Agent-reported elapsed time: 22:57:59 to 23:06:52 CST (about 9 min).

VERDICT: REQUEST_CHANGES (7 findings; no BLOCKER or HIGH)

The reviewer reports that none of the findings affects a resource in the current 137/0/0 plan. They all sit
on gated-off or operational paths: ALB and services, deploy, rollback, the pipeline runner, and EMR runtime.

## Reviewer limits

- terraform validate was blocked by the guard (`-chdir` is rejected in the read-only fence), so validate
  rests on the orchestrator's and the agent's runs.
- `git check-ignore` on the tfvars and tfstate names was blocked (secret-material rule). Ignore status was
  confirmed indirectly through `git status --untracked-files=all`.
- dev.tfvars, the .tfplan file and state were not opened.

## Findings

| # | Sev | Location | Summary | Owner |
|---|---|---|---|---|
| 1 | MEDIUM | modules/runtime/main.tf:203, :213 | ALB deletion protection follows `!data_cleanup_mode`, so applying enable_runtime=false cannot delete the ALB. Turning the D8 cost toggle off then requires data-loss mode. The CKV_AWS_150 skip reason is false. | infra-engineer |
| 2 | MEDIUM | envs/dev/variables.tf:373; runtime/main.tf:235; dev.tfvars.example:79 | The ALB health check defaults to /readyz, which always fails in AWS mode (UnavailableGraphStore, services Main.scala:23-26, ServicesApp.scala:157-169). Tasks churn, the circuit breaker trips and deploy fails while Fargate keeps billing. | human decision, then infra-engineer |
| 3 | MEDIUM | runtime/main.tf:293-295, :324-326; scripts/deploy.sh:190-193 | `ignore_changes = [task_definition]`, and deploy.sh copies the live revision. Terraform env/secret changes (for example release_run_id) never reach the services: silent drift and a provenance break (law 3). | human decision, then infra-engineer |
| 4 | MEDIUM | scripts/rollback.sh:120-121; modules/iam/main.tf:737-825 | The deployment role lacks ecs:ListTaskDefinitions. `\|\| true` swallows the AccessDenied, and rollback exits 3 with misleading evidence. | infra-engineer |
| 5 | MEDIUM | (absence) infra/terraform; run-bounded-pipeline.sh start-execution | No principal has states:StartExecution. /run-pipeline is blocked under the read-only profile. | human decision, then infra-engineer |
| 6 | LOW | modules/workflow/main.tf:222-233; README.md:140 | The CreateCluster failure Catch does not terminate a still-starting cluster (no ClusterId captured), so the cluster lives until the 1800 s idle backstop. The README claims every caught failure terminates. | infra-engineer |
| 7 | LOW | modules/iam/main.tf:68, :259-271 | The EMR instance profile has Put/Delete on curated graph/, curated/ and run-manifests/, which no bounded stage writes. Least privilege would limit it to bounded-smoke/*. | infra-engineer |

## Orchestrator spot-check (observed)

- runtime/main.tf:203 has the skip text "Dev ALB is created and removed with enable_runtime; deletion
  protection follows data_cleanup_mode instead", and :213 has `enable_deletion_protection = !var.data_cleanup_mode`. Finding 1 is confirmed.
- A grep of infra/terraform for `ListTaskDefinitions` and `StartExecution`: no match. rollback.sh:120-121
  ends with `|| true`. Findings 4 and 5 are confirmed.

## Verified correct (reviewer summary)

Public exposure (all buckets blocked, TLS-only, ALB gated off and the only CIDR rule); IAM (Bedrock limited
to the two exact D13 ARNs, wildcards only on actions without resource-level support, PassRole scoped,
deployment role separate); secrets (container only, valueFrom ARN, sensitive email, dummy examples); cost
bounds (no NAT, toggles default off, EMR caps, 30-day logs, budget 25 with alerts at 10/15/20 plus forecast
25); stateful safeguards; name and key drift against core, storage, rag and services (no mismatch); script
contract (D14 respected; plan.sh, deploy.sh and cleanup.sh gates present; Phase 04 behavior kept);
45 of 46 checkov skips true; ownership clean.

## Unverified assumptions (reviewer)

- Whether neptune-db:connect alone implies more than read access (infra-engineer to cite the Neptune IAM doc; Neptune is gated off).
- EMR 7.14 in a private subnet with no NAT.
- Prices from memory.
- Alarms have no SNS actions (disclosed). The S3 gateway endpoint has no policy (disclosed).

## Human decisions (given to the orchestrator in session, 2026-11-19; the human should transcribe them into human-gate.md)

- R1-F2: ALB health check defaults to `/healthz`. `/readyz` stays in smoke-test.sh as EXPECTED-FAIL until the Neptune adapter exists.
- R1-F3: deploy.sh bases each new revision on the Terraform-managed task definition (Terraform output) and rewrites only the image. Terraform owns env and secrets, deploy.sh owns the image, and there is no apply in deploy (D14 holds).
- R1-F5: new role `scholarchain-dev-pipeline-runner`, created only when the human lists principals that may assume it. It gets states:StartExecution on the pipeline state machine plus the exact read calls the script makes.

NEXT GATE (original): human decisions on findings 2, 3 and 5. Then an infra-engineer repair run (findings 1-7). Then
code-reviewer round 2. Then human sign-off on a fresh plan checksum.
