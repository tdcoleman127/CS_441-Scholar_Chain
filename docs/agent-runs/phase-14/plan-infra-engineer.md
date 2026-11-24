# Phase 14, Step 1b: infra-engineer plan report (runtime, fixture mode)

Assembled by the orchestrator from infra-engineer's final report (attempt 2; attempt 1 is recorded in plan-infra-engineer-attempt-1-failed.md). Verbatim below. The account ID is redacted as `<account-id>`.

```text
STATUS: COMPLETE

Summary: The plan ran cleanly: 17 to add, 1 to change, 0 to destroy. No stateful resource is touched, nothing is replaced or destroyed, there is no drift block, and the plan has no IAM role or policy changes. The api task definition runs in fixture mode, uses the image tagged ef95a6b5..., and takes SCHOLARCHAIN_API_KEYS from the secret ARN. Running the stack costs about USD 0.10-0.11 per hour (estimated). Cost warning: `enable_runtime = false` alone does NOT stop the 4 interface endpoints. The four `interface_endpoints` flags must also be set to false (details under ESTIMATED HIGH-COST RESOURCES).

STATEFUL REPLACEMENTS OR DESTROYS:
None. plan.sh printed `[plan] STATEFUL-ACTION none` and `[plan] DESTRUCTIVE none`, and `ACTION-COUNTS create=17 update=1 destroy=0 replace=0 unparsed=0`. grep found no "Objects have changed outside of Terraform", "changed outside", "Warning", or "Error" lines, so there was no drift block. The single in-place update is the CloudWatch dashboard (stateless; details below).

OWNER: infra-engineer

WORK ORDER: Phase 14, Step 1b, attempt 2 (human-gate.md lines 55-69, as expanded by the orchestrator). Run `bash scripts/plan.sh dev` without applying. Report the checksum, counts with addresses, IAM, the fixture, image, and secret wiring, cost, apply commands for Git Bash and PowerShell, and post-apply checks.

FILES CREATED:
- None in the repository.
- Full plan.sh output, 1603 lines, in the session scratchpad (plan-1b-attempt2.log). It contains the full account ID; do not paste it into the repository.
- plan.sh wrote the plan file, which is git-ignored (`.gitignore:50:*.tfplan`): infra/terraform/envs/dev/plans/dev-ef95a6b5d14a-20261124T074204Z.tfplan

FILES MODIFIED: none. `git status --porcelain` was the same before and after: ` M docs/agent-runs/phase-14/human-gate.md` (human edit) and `?? docs/agent-runs/phase-14/plan-infra-engineer-attempt-1-failed.md`. dev.tfvars, Terraform, and scripts were not touched.

COMMANDS RUN (all output seen):
1. `git rev-parse HEAD` returned ef95a6b5d14a3e911420c5b37112d7a73a2eaf37. `git status --porcelain` is shown above.
2. `bash scripts/plan.sh dev > <scratchpad>/plan-1b-attempt2.log 2>&1` exited 0. Markers:
   - `[plan] ENV environment=dev aws_profile=scholarchain-readonly aws_region=us-east-1`
   - `[plan] ACCOUNT last4=9340`
   - identity `assumed-role/AWSReservedSSO_ScholarChainReadOnly_<suffix>`
   - `[plan] REVISION git_sha=ef95a6b5d14a3e911420c5b37112d7a73a2eaf37 tree=clean under infra/terraform`
   - Terraform v1.16.4 (the "out of date, latest 1.17.2" notice is advisory)
   - `STEP 1 OK: fmt` (the attempt-1 failure is fixed)
   - init uses the S3 backend and hashicorp/aws v6.73.0 from the lock file
   - `Success! The configuration is valid.`
   - `LOCK plan runs UNLOCKED (-lock=false)`
   - `PLAN-EXIT 2 (changes present)`
   - `RESULT status=OK`
3. `sha256sum plans/dev-ef95a6b5d14a-20261124T074204Z.tfplan` returned 8ce269bf42fb54f925bef35a5c990ac2529ea4d7e85ec320f8343089b9fa3668, the same as plan.sh printed. Size 174,817 bytes.
4. Read-only AWS CLI calls (profile scholarchain-readonly, us-east-1, with timeouts):
   - `ecs describe-services` for scholarchain-dev-api and scholarchain-dev-worker: both MISSING, so the creates will not collide.
   - `elbv2 describe-load-balancers --names scholarchain-dev-api`: LoadBalancerNotFound.
   - `elbv2 describe-target-groups --names scholarchain-dev-api`: TargetGroupNotFound.
   - `ecr describe-images imageTag=ef95a6b5...`: present, digest sha256:b317ca1a39634ca114b73daa47d4b97057bbed4f1ee50885ea2a7c207482b6a9, pushed 2026-11-24T01:32:06-06:00.
   - `ec2 describe-vpc-endpoints` in the dev VPC: only the S3 and DynamoDB gateway endpoints (available).
   - `ec2 describe-nat-gateways` in the dev VPC: none.
5. Read-only code checks:
   - The interface endpoints set no `policy`, so AWS applies its default endpoint policy (modules/network/main.tf:149-158).
   - The S3 gateway endpoint has no policy, so ECR layer pulls through S3 work (main.tf:132-138).
   - The logs bucket uses SSE-S3 (AES256) and has an AllowAlbAccessLogDelivery statement, so creating the ALB with access logs will not fail (modules/data_lake/main.tf:85-93, 187-193).
   - The execution role (unchanged) has ECR pull, log write, GetSecretValue on the secret ARN, and kms:Decrypt via Secrets Manager (modules/iam/main.tf:322-354).
   - Main.scala:136-139 emits `service.started` with `releaseRun`.
6. aws-docs: the PrivateLink cost page, the VPC pricing section, and the ECR encryption-at-rest page (cited below).
Not run: terraform apply, deploy, package, smoke, pipeline, or any AWS write.

TEST RESULTS: none in scope (plan only).

VALIDATION RESULTS: plan.sh step 1 `terraform fmt -check -recursive` OK. Step 3 `terraform validate`: "Success! The configuration is valid." No security scanner was run: checkov, tfsec, and trivy are not installed (Step 1a). This step changes no code.

PLAN SUMMARY (add/change/destroy, plan checksum):
- Exactly as printed:
  - `[plan] PLAN-FILE infra/terraform/envs/dev/plans/dev-ef95a6b5d14a-20261124T074204Z.tfplan`
  - `[plan] PLAN-SHA256 8ce269bf42fb54f925bef35a5c990ac2529ea4d7e85ec320f8343089b9fa3668`
  - `[plan] PLAN-SUMMARY Plan: 17 to add, 1 to change, 0 to destroy.`
- My own sha256sum gives the same value: 8ce269bf42fb54f925bef35a5c990ac2529ea4d7e85ec320f8343089b9fa3668.
- Update in place (1):
  1. module.observability.aws_cloudwatch_dashboard.this. dashboard_body changes to "(known after apply)" because the new widgets reference ALB and target-group ARN suffixes that are not known yet. It is stateless and keeps the same name (scholarchain-dev-overview).
- Create (17):
  - Network (4). Each interface endpoint has private DNS on, is in 1 subnet (subnet-02ba…, app AZ a), and uses SG sg-0c27…:
    2. module.network.aws_vpc_endpoint.interface["ecr_api"]
    3. module.network.aws_vpc_endpoint.interface["ecr_dkr"]
    4. module.network.aws_vpc_endpoint.interface["logs"]
    5. module.network.aws_vpc_endpoint.interface["secretsmanager"]
  - Runtime compute (8):
    6. module.runtime.aws_ecs_task_definition.this["api"]: 512 CPU / 1024 MiB, X86_64, read-only root filesystem, task role scholarchain-dev-api-task.
    7. module.runtime.aws_ecs_task_definition.this["worker"]: 512 / 1024, task role scholarchain-dev-worker-task. It has the same api-key secret and SCHOLARCHAIN_RUNTIME_MODE=fixture.
    8. module.runtime.aws_ecs_task_definition.this["embedding_ingestion"]: 512 / 1024, task role scholarchain-dev-embedding-ingestion, no secrets, fixture mode. Registered only; no service runs it.
    9. module.runtime.aws_ecs_service.api[0]: Fargate, desired 1, min/max 100/200 percent, circuit breaker enable and rollback, grace 90 s, assign_public_ip=false, two app subnets, SG sg-0ca3….
    10. module.runtime.aws_ecs_service.worker[0]: desired 0, max 100 percent, circuit breaker on, no public IP.
    11. module.runtime.aws_lb.api[0]: internet-facing (internal=false), two public subnets, SG sg-0a84…, drop_invalid_header_fields=true, deletion protection false (by design in dev), access logs to scholarchain-dev-logs-<account-id>/logs/alb.
    12. module.runtime.aws_lb_listener.http[0]: port 80 HTTP, forward to the target group.
    13. module.runtime.aws_lb_target_group.api[0]: target type ip, port 8080 HTTP. Health check /healthz, matcher 200, interval 30 s, healthy 2, unhealthy 3, deregistration delay 15 s.
  - Observability (5). No alarm_actions are rendered, so they show in the console only:
    14. module.observability.aws_cloudwatch_metric_alarm.alb_elb_5xx[0] (scholarchain-dev-alb-5xx)
    15. module.observability.aws_cloudwatch_metric_alarm.alb_latency_p95[0] (scholarchain-dev-api-latency-p95)
    16. module.observability.aws_cloudwatch_metric_alarm.alb_target_5xx[0] (scholarchain-dev-api-target-5xx)
    17. module.observability.aws_cloudwatch_metric_alarm.ecs_cpu["api"] (scholarchain-dev-api-cpu-high)
    18. module.observability.aws_cloudwatch_metric_alarm.ecs_cpu["worker"] (scholarchain-dev-worker-cpu-high)
- Outputs:
  - runtime.ecs_services_exist goes from false to true.
  - runtime.image_tag goes from "" to ef95a6b5d14a3e911420c5b37112d7a73a2eaf37.
  - alb_base_url, alb_dns_name, and task_definitions.{api, worker, embedding_ingestion} are known after apply.
- plan.sh classifications:
  - IAM-ACTION (policy-bearing) for the 4 interface endpoints, because the endpoint type can carry a policy. Here none is set.
  - SECURITY-ACTION for aws_lb.api[0] and aws_lb_listener.http[0].

FIXTURE, IMAGE AND SECRET CONFIRMATION (api task definition, as rendered in the plan; account shown as <account-id>):
- Runtime mode, plan log lines 1009-1010:
    + name  = "SCHOLARCHAIN_RUNTIME_MODE"
    + value = "fixture"
- Image, line 1030:
    + image = "<account-id>.dkr.ecr.us-east-1.amazonaws.com/scholarchain-dev-services:ef95a6b5d14a3e911420c5b37112d7a73a2eaf37"
- Secret, lines 1054-1058. The value comes from the ARN; no secret value is in the plan:
    + secrets = [ { name = "SCHOLARCHAIN_API_KEYS", valueFrom = "arn:aws:secretsmanager:us-east-1:<account-id>:secret:scholarchain/dev/api-key-digests-<suffix>" } ]
- Release run ID, line 1006: SCHOLARCHAIN_RELEASE_RUN_ID = "run:v1:079cb5d32f20d399305c3f95b3c7030611f398011b92347effe139561078f7fc" (LocalFixture.RunId).
- All 9 runtime_algorithm_env keys are present with the Step 1a values. SCHOLARCHAIN_SVC_PORT is 8080, which matches the container port and the target group.

IAM CHANGES:
- No IAM role, policy, attachment, or trust change is in the plan. The deployment role and all task and execution roles already exist and are only refreshed.
- New references to existing roles: the 3 task definitions use execution_role_arn scholarchain-dev-ecs-execution, and their task roles are scholarchain-dev-api-task, scholarchain-dev-worker-task, and scholarchain-dev-embedding-ingestion. Those roles' permissions are unchanged from the Phase 12 apply.
- Interface endpoint policies: none is set, so AWS uses its default full-access endpoint policy. That is wider than needed, but the endpoints are reachable only from the workload and EMR security groups (endpoints_from_clients rules: api, worker, emr_primary, emr_core), and IAM on the caller still applies. Optional hardening, not made: scope each endpoint policy to this account's principals and resources.

PUBLIC EXPOSURE (escalation item, human decision D12 already recorded in code):
- aws_lb.api is internet-facing on HTTP:80 with no TLS.
- The SG ingress rule alb_listener["0.0.0.0/0"] already exists in state from Phase 12 (sgr-0717…), and alb_ingress_cidrs = ["0.0.0.0/0"] in dev.tfvars.
- This apply makes it reachable for the first time. Bearer API keys would then cross the internet in cleartext.
- To narrow it, set alb_ingress_cidrs to your own /32 before planning again.

ESTIMATED HIGH-COST RESOURCES (us-east-1, per hour while on):
- Structural facts, verified:
  - Interface endpoints bill "for each VPC endpoint provisioned in each Availability Zone", even while pending, plus per GB processed (https://docs.aws.amazon.com/prescriptive-guidance/latest/integrate-third-party-services/architecture-1.html).
  - Elastic Load Balancing uses public IPv4 addresses, and those are billed outside the EC2 free tier (https://docs.aws.amazon.com/vpc/latest/userguide/what-is-amazon-vpc.html, "Pricing for Amazon VPC").
- Dollar rates: ESTIMATED. They are from my knowledge of the public pricing pages and were not fetched this session, because the aws-docs tool cannot read aws.amazon.com/pricing. Verify them at the URLs given.

| Item | Basis | USD per hour | Status |
|---|---|---|---|
| 4 interface endpoints x 1 AZ | 4 x 0.01 | 0.0400 | estimated (https://aws.amazon.com/privatelink/pricing/); the repo comment at modules/network/main.tf:148 agrees |
| ALB fixed hourly charge | 0.0225 | 0.0225 | estimated (https://aws.amazon.com/elasticloadbalancing/pricing/) |
| ALB LCU | 0.008 per LCU-hour; smoke traffic is a small fraction of 1 LCU | 0.000-0.008 | estimated |
| ALB public IPv4 (at least 2 addresses, 2 AZs) | 2 x 0.005 | 0.0100 | estimated (https://aws.amazon.com/vpc/pricing/, Public IPv4 tab); the charge itself is verified |
| 1 Fargate api task, 0.5 vCPU / 1 GB, Linux x86 | 0.5 x 0.04048 + 1 x 0.004445 | 0.0247 | estimated (https://aws.amazon.com/fargate/pricing/); the 20 GB default ephemeral storage is free; the task has no public IP |
| Worker service (desired 0), embedding task definition | not running | 0.0000 | verified from plan |
| 5 CloudWatch alarms | 5 x 0.10 per month / 730 hours | 0.0007 | estimated (https://aws.amazon.com/cloudwatch/pricing/) |
| Endpoint data processing, CloudWatch Logs ingestion, ALB log PUTs, cross-AZ traffic (tasks in AZ b reach endpoint ENIs in AZ a) | 0.01/GB processed, 0.50/GB ingested, 0.01/GB cross-AZ; image layers go over the free S3 gateway endpoint | less than 0.005 | estimated |
| NAT gateway | none in plan or live (verified by read-only describe) | 0.0000 | verified |
| Dashboard | already exists; an update adds no new dashboard | 0.0000 | verified from plan |

- Total: about USD 0.098-0.11 per hour (estimated).
- 2-hour session: about USD 0.20-0.22. 4-hour session: about USD 0.39-0.44.
- Endpoint and ALB partial hours probably round up to a full hour (estimated), so a short session still costs at least one full hour of each.
- The human's earlier USD 0.10-0.20 per hour guess is consistent. Using 1 AZ instead of 2 saves about USD 0.04 per hour.
- What stops the charges:
  - Set `enable_runtime = false`, AND set ecr_api, ecr_dkr, logs, and secretsmanager to false in `interface_endpoints`. Then infra-engineer runs plan.sh dev and the human applies the saved plan.
  - enable_runtime gates the ALB, listener, target group, services, task definitions, and the 5 alarms (modules/observability/main.tf:193, 208, 223, 238).
  - The interface endpoints are gated ONLY by `interface_endpoints` (envs/dev/main.tf:31; modules/network/main.tf:24-26, 150). With enable_runtime = false alone they keep billing about USD 0.04 per hour (about USD 29 per month).
  - The ALB has deletion protection false, so it is removed without data_cleanup_mode.
- What keeps billing while the runtime is off:
  - ECR image storage (about 162 MB, a few cents per month).
  - The KMS CMK (monthly key charge).
  - Secrets Manager secret (monthly).
  - S3 and DynamoDB storage, CloudWatch log storage, and the budget.
  - All of these were already billing before this plan.

SCRIPT GATES ENCODED: no script changes. Gates exercised in this run:
- plan.sh forced the read-only profile, refused static credentials, and printed the account as last-4 only.
- It printed the clean-tree REVISION, ran fmt -check (now passing), validate, and an unlocked plan.
- It printed the full plan, classified every action (unparsed=0), and exited 0 because nothing is destroyed or replaced.

REQUIRED ENVIRONMENT VARIABLES (names only):
- plan.sh: none. Optional: SCHOLARCHAIN_PLAN_TIMEOUT_SECONDS and SCHOLARCHAIN_PLAN_RECORD_FILE.
- Human apply: AWS_PROFILE. AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY, and AWS_SESSION_TOKEN must be unset.
- Later smoke test: SCHOLARCHAIN_AWS_REGION, SCHOLARCHAIN_SMOKE_BASE_URL, SCHOLARCHAIN_SMOKE_API_KEY, SCHOLARCHAIN_SMOKE_REQUIRE_READY, SCHOLARCHAIN_SMOKE_QUESTION, and SCHOLARCHAIN_SMOKE_EXPECTED_SHA.

=====================================================================
HUMAN COMMANDS (run by the human outside Claude)
=====================================================================

A1. Apply, PowerShell (VS Code terminal):
    Set-Location "C:\Users\figge\OneDrive\Desktop\UIC Folder\CS 441\CS_441-Scholar_Chain\infra\terraform\envs\dev"
    $plan = "plans\dev-ef95a6b5d14a-20261124T074204Z.tfplan"
    $expected = "8ce269bf42fb54f925bef35a5c990ac2529ea4d7e85ec320f8343089b9fa3668"
    $actual = (Get-FileHash $plan -Algorithm SHA256).Hash.ToLower()
    $actual
    $env:AWS_ACCESS_KEY_ID = $null
    $env:AWS_SECRET_ACCESS_KEY = $null
    $env:AWS_SESSION_TOKEN = $null
    $env:AWS_PROFILE = "scholarchain-admin"
    aws sso login --profile scholarchain-admin
    aws sts get-caller-identity --query Arn --output text
    # expect ...assumed-role/AWSReservedSSO_ScholarChainAdmin_<suffix>/...
    if ($actual -eq $expected) { terraform apply -input=false $plan } else { Write-Host "PLAN CHECKSUM MISMATCH: $actual - do not apply" }
    # expect: Apply complete! Resources: 17 added, 1 changed, 0 destroyed.
- The apply is inside the if-block so that a mismatch can never fall through, even when the lines are pasted one at a time.

A2. Apply, Git Bash (same result):
    cd "/c/Users/figge/OneDrive/Desktop/UIC Folder/CS 441/CS_441-Scholar_Chain/infra/terraform/envs/dev"
    PLAN=plans/dev-ef95a6b5d14a-20261124T074204Z.tfplan
    unset AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN
    export AWS_PROFILE=scholarchain-admin
    aws sso login --profile scholarchain-admin
    echo "8ce269bf42fb54f925bef35a5c990ac2529ea4d7e85ec320f8343089b9fa3668 *$PLAN" | sha256sum -c - && terraform apply -input=false "$PLAN"
    # sha256sum must print "<plan>: OK"; expect: Apply complete! Resources: 17 added, 1 changed, 0 destroyed.

Notes for both:
- plan.sh already set up .terraform/ in place for the S3 backend, so no init is needed.
- Applying a saved plan takes the state lock.
- If state changed since the plan, Terraform refuses the stale plan. In that case ask for a new Step 1b plan; do not plan by hand.
- wait_for_steady_state=false, so apply returns as soon as the service is created. Task health shows a few minutes later.

B. Read-only post-apply checks (Git Bash or PowerShell; profile scholarchain-readonly). Wait about 3-5 minutes after apply: image pull, then 90 s grace, then 2 health checks 30 s apart.
    aws ecs describe-services --cluster scholarchain-dev --services scholarchain-dev-api --profile scholarchain-readonly --region us-east-1 --cli-connect-timeout 10 --cli-read-timeout 30 --query "services[0].{running:runningCount,desired:desiredCount,pending:pendingCount,rollout:deployments[0].rolloutState,reason:deployments[0].rolloutStateReason}" --output json
    # expect running 1, desired 1, rollout COMPLETED (IN_PROGRESS is normal for the first minutes)
    aws elbv2 describe-target-groups --names scholarchain-dev-api --profile scholarchain-readonly --region us-east-1 --cli-connect-timeout 10 --cli-read-timeout 30 --query "TargetGroups[0].TargetGroupArn" --output text
    aws elbv2 describe-target-health --target-group-arn <arn from previous command> --profile scholarchain-readonly --region us-east-1 --cli-connect-timeout 10 --cli-read-timeout 30 --query "TargetHealthDescriptions[].TargetHealth.State" --output text
    # expect: healthy
    aws logs filter-log-events --log-group-name /scholarchain/dev/ecs/api --filter-pattern "service.started" --max-items 5 --profile scholarchain-readonly --region us-east-1 --cli-connect-timeout 10 --cli-read-timeout 30 --query "events[].message" --output text
    # expect a service.started event whose releaseRun is run:v1:079cb5d32f20d399305c3f95b3c7030611f398011b92347effe139561078f7fc
    # If the filter returns nothing, try the quoted pattern '"service.started"' (Git Bash) or '\"service.started\"' (PowerShell).
    terraform output -json runtime      # admin profile, from envs/dev: alb_base_url for the later smoke test

C. If the first deployment fails:
- Symptoms: runningCount 0, rolloutState FAILED, or stoppedReason CannotPullContainerError, ResourceInitializationError, or exit code 2.
- With circuit breaker rollback=true on a FIRST deployment, there is no earlier revision to roll back to. ECS marks the deployment FAILED and stops launching tasks. The apply itself still succeeds, because Terraform does not wait for steady state.
- Diagnose read-only:
  - `aws ecs list-tasks --cluster scholarchain-dev --service-name scholarchain-dev-api --desired-status STOPPED ...`, then `aws ecs describe-tasks ... --query "tasks[].{reason:stoppedReason,containers:containers[].reason}"`.
  - The api log group: look for `service.config-invalid`, which points to a bad secret value.
- Route the evidence to the orchestrator (incident-responder or infra-engineer). Do not mutate anything by hand.
- To stop costs while the fix is found, use the off procedure under ESTIMATED HIGH-COST RESOURCES (enable_runtime=false plus the four endpoints false, then plan and apply).

D. The smoke test is a separate, later step (deploy-engineer, or the human per human-gate.md line 3). Use SCHOLARCHAIN_SMOKE_REQUIRE_READY=1 and SCHOLARCHAIN_SMOKE_QUESTION="Who are the coauthors of Alice Adams?" (Step 1a 3c). Set SCHOLARCHAIN_SMOKE_EXPECTED_SHA=ef95a6b5d14a3e911420c5b37112d7a73a2eaf37 if it runs before deploy.sh moves the image to a newer HEAD.

ASSUMPTIONS:
- The secret's AWSCURRENT value is a valid digest array with the query:write and evidence:read scopes. It cannot be checked read-only; if it is wrong, the task exits 2.
- The image at ef95a6b5 behaves like the code read in Step 1a.
- ECR decrypts layers through its own KMS grant on pull, so the execution role needs no extra KMS permission (https://docs.aws.amazon.com/AmazonECR/latest/userguide/encryption-at-rest.html, steps 5-7; IAM requirements are listed only for creating and deleting a repository).
- The pricing rates above are not fetched figures.

RISKS:
- Public HTTP:80 from 0.0.0.0/0 with bearer API keys in cleartext. This is decision D12 and acceptable only for short fixture sessions; narrowing alb_ingress_cidrs to the human's /32 is recommended.
- Fixture mode serves synthetic data publicly. The Phase 14 evidence must say so.
- `enable_runtime = false` alone does not stop the endpoint charges (above).
- The interface endpoints use the default full-access endpoint policy (optional hardening).
- The worker task definition also gets the api-key secret. The worker service runs 0 tasks, so this has no effect now.
- The plan ran unlocked. If anything changes state before the apply, the saved plan is rejected as stale (fail safe).
- The plan file and the scratchpad log contain the full account ID. This report uses <account-id> and <suffix>.

MANUAL ACTION REQUIRED (human):
1. Sign off on plan checksum 8ce269bf42fb54f925bef35a5c990ac2529ea4d7e85ec320f8343089b9fa3668: 17 add / 1 change / 0 destroy, no IAM change, public ALB exposure and cost as above. Optionally narrow alb_ingress_cidrs first, which requires a new plan.
2. Apply with A1 (PowerShell) or A2 (Git Bash) outside Claude.
3. Run the B checks.
4. Turn the runtime off between sessions with BOTH settings.

BLOCKED-ON OWNER: none

BLOCKED-ON EVIDENCE: none

NEXT GATE: code-reviewer reviews the plan output (scratchpad log above). Then the human signs off at the Step 3 gate and applies the saved plan with the checksum check. Then come the read-only post-apply checks, then deploy and smoke test (separate steps).
```

## Orchestrator notes from the code-reviewer pass (verdict: APPROVE)

1. Correction (code-reviewer finding 1, MINOR, owner infra-engineer). The "What stops the charges" section above says enable_runtime gates the task definitions. It does not: task definitions depend only on `image_tag != ""` (infra/terraform/modules/runtime/main.tf:26, 178). With the off procedure (enable_runtime = false plus the four interface_endpoints false) and image_tag still set, the 3 task definitions stay registered. They are free and nothing runs them, so cost and safety are unaffected.
2. Post-apply check B, log filter (code-reviewer risk). `--filter-pattern "service.started"` may be rejected by CloudWatch because the term contains a non-alphanumeric character. A sturdier pattern is the JSON filter `{ $.event = "service.started" }` (StructuredLog writes the event name under "event", Observability.scala:39).
3. The off plan will contain destroys, so plan.sh will exit 4 and require human sign-off (scripts/plan.sh:343-347). That is expected.
