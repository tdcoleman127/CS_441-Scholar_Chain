# ScholarChain Terraform

Owner: infra-engineer (author). The human architect applies; no agent runs `terraform apply`,
`destroy`, or state commands (docs/agents.md sections 7 and 8).

## Layout

| Path | Purpose |
|---|---|
| `bootstrap/` | Remote-state bucket. Applied once, by hand, by the human. Local state. |
| `envs/dev/` | The only approved environment root (`dev`). S3 backend with native locking. |
| `modules/budget` | AWS Budgets budget (USD 25; alerts at 10, 15, 20) and optional cost-allocation tag activation. |
| `modules/network` | VPC, two AZs, public (ALB only), private app, private data subnets, gateway and optional interface endpoints, role-chained security groups. No NAT gateway. |
| `modules/kms` | One customer managed key for data at rest. |
| `modules/iam` | Least-privilege roles (EMR, ECS execution, API, worker, runtime reader, embedding ingestion, Neptune loader, Step Functions, deployment). |
| `modules/data_lake` | raw, samples, curated, artifacts, and logs buckets; logs-bucket delivery statements for S3 access logs, ALB access logs, and VPC flow logs. |
| `modules/dynamodb` | The five key-value tables (`pk`/`sk`, on-demand, CMK SSE, PITR, TTL on query-session). |
| `modules/vectors` | S3 Vectors bucket `scholarchain-dev-vectors` (SSE-KMS, CMK) and the five 1024-dim cosine indexes. |
| `modules/neptune` | Neptune Serverless 1.0-2.5 NCU, behind `enable_neptune` (default false); read grants on the cluster resource ID. |
| `modules/runtime` | ECR, ECS cluster, api/worker/embedding-ingestion task definitions, and, behind `enable_runtime`, the public ALB and the two services. |
| `modules/workflow` | SQS jobs queue and DLQ; Step Functions `scholarchain-dev-pipeline`, which creates, uses, and always terminates one transient EMR cluster per run. |
| `modules/observability` | One dashboard and the alarms (pipeline, DLQ, DynamoDB throttles; ALB, ECS, Neptune when enabled). |

All names come from `envs/dev/locals.tf`, which derives them from the code that reads them.

Provider: `hashicorp/aws` pinned to exactly `6.73.0` in both roots. Verified on 2026-11-19 with the
terraform MCP registry tools (latest version 6.73.0; its docs list `aws_s3vectors_vector_bucket`,
`aws_s3vectors_index`, and `aws_s3vectors_vector_bucket_policy`).

## Remote state design (decision D4)

1. The human applies `bootstrap/` once with the admin profile, outside Claude:
   `terraform -chdir=infra/terraform/bootstrap init` then `apply`. It creates
   `scholarchain-tfstate-<account id>-us-east-1`: versioned, SSE-S3 with a Bucket Key, all public
   access blocked, bucket-owner-enforced, TLS-only, `prevent_destroy`, and a bucket policy that
   denies `s3:DeleteBucket` to every principal. Noncurrent state versions expire after 90 days.
   The bootstrap root keeps its own small state locally; nothing else depends on it.
2. `envs/dev` uses the S3 backend with `use_lockfile = true` (native S3 lock object, no DynamoDB
   lock table), key `scholarchain/dev/terraform.tfstate`, `encrypt = true`. The bucket name is
   supplied with `-backend-config=backend.hcl` (copy `backend.hcl.example`; keep the copy
   uncommitted because it contains the account ID).
3. Plans run under the read-only profile `scholarchain-readonly` with `terraform plan -lock=false`,
   because that profile cannot write the `.tflock` object. Only the human applies, with locking on.
   `scripts/plan.sh` (part C) prints a marker saying the plan ran unlocked.
4. Agents never read state files (CLAUDE.md law 4).

### Local-backend fallback (until the bootstrap is applied)

```text
cd infra/terraform/envs/dev
cp local_backend_override.tf.example local_backend_override.tf
terraform init -reconfigure
```

The override file replaces the S3 backend with `backend "local"`. Remove it and run
`terraform init -migrate-state -backend-config=backend.hcl` after the bootstrap exists. Both
`backend.hcl` and `local_backend_override.tf` are ignored by `.gitignore` (patterns `backend.hcl`,
`*.backend.hcl`, and `*_override.tf`, added by build-engineer in phase-12 prep-02).

Validation without any backend: `terraform init -backend=false` then `terraform validate`.

## Tags and cost allocation

Provider `default_tags` on every resource: `scholarchain:project`, `scholarchain:environment`
(the key `scripts/run-bounded-pipeline.sh` checks on the EMR cluster), `scholarchain:managed-by`,
`scholarchain:owner`, `scholarchain:cost-center`. Cost-allocation activation of
`scholarchain:project`, `scholarchain:environment`, and `scholarchain:cost-center` is behind
`activate_cost_allocation_tags` because AWS Billing must first see the keys.

## Budget (decision D1)

`modules/budget`: `ANNUALLY` cost budget of USD 25 from `budget_start_date`, ACTUAL alerts at USD 10,
15, and 20, and a FORECASTED alert at USD 25. Credits and refunds are excluded so promotional credits
do not mask spend. Recipients come from `budget_notification_emails`, which has no default and is set
only in the uncommitted `dev.tfvars`. A budget alerts; it does not cap spend.

## Network cost notes (decision D10)

| Item | Toggle | us-east-1 price |
|---|---|---|
| NAT gateway | none (not created) | would be USD 0.045/h plus USD 0.045/GB |
| S3 gateway endpoint | always | free |
| DynamoDB gateway endpoint | always | free |
| `ecr_api` interface endpoint | `interface_endpoints.ecr_api` | USD 0.01/h per AZ (+ USD 0.01/GB) |
| `ecr_dkr` interface endpoint | `interface_endpoints.ecr_dkr` | USD 0.01/h per AZ (+ USD 0.01/GB) |
| `logs` interface endpoint | `interface_endpoints.logs` | USD 0.01/h per AZ (+ USD 0.01/GB) |
| `secretsmanager` interface endpoint | `interface_endpoints.secretsmanager` | USD 0.01/h per AZ (+ USD 0.01/GB) |
| `bedrock_runtime` interface endpoint | `interface_endpoints.bedrock_runtime` | USD 0.01/h per AZ (+ USD 0.01/GB) |
| `s3vectors` interface endpoint | `interface_endpoints.s3vectors` | USD 0.01/h per AZ (+ USD 0.01/GB) |

All interface endpoints default to off, and `interface_endpoint_az_count = 1` places them in one AZ.
All six on in one AZ: USD 0.06/h, about USD 43.80/month if left on, so enable them only while the
runtime stack runs. Who needs which: ECS Fargate tasks in private subnets need `ecr_api`, `ecr_dkr`
(image layers come through the S3 gateway endpoint), `logs` (awslogs driver), and `secretsmanager`
(API-key digest injection). The API and worker need `bedrock_runtime` and `s3vectors`. EMR 7.x in the
private data subnets needs only the S3 gateway endpoint.

## Data lake

| Bucket (`<prefix>-<role>-<account id>`) | Read by | Prefixes |
|---|---|---|
| raw | Hadoop jobs (`SCHOLARCHAIN_RAW_BUCKET`) | `raw/<source>/...` |
| samples | bounded pipeline (`SCHOLARCHAIN_SAMPLE_BUCKET`) | `samples/<source>/manifest_version=1/` |
| curated | runtime, Spark, loaders (`SCHOLARCHAIN_CURATED_BUCKET`) | `curated/`, `graph/`, `semantic-context/`, `reports/`, `run-manifests/`, `bounded-smoke/` |
| artifacts | EMR steps, deploy (`SCHOLARCHAIN_HADOOP_JAR_URI`) | `artifacts/<component>/<git sha>/` |
| logs | EMR, S3 access logs, ALB, VPC flow logs | `logs/emr/`, `logs/s3-access/<bucket>/`, `logs/alb/`, `logs/vpc-flow/` |

Data buckets: SSE-KMS (project key, Bucket Key), versioning, noncurrent versions expire after
`noncurrent_version_retention_days`, multipart uploads abort after 7 days, server access logs to the
logs bucket. Logs bucket: SSE-S3 (log delivery requires it), objects expire after
`log_retention_days`. All buckets block public access and deny non-TLS requests.

Cleanup mode: `data_cleanup_mode = true` sets `force_destroy` on the buckets and uses 7-day KMS and
secret recovery windows. It is false by default and is set only for an approved, human-run cleanup.
The same flag turns DynamoDB deletion protection off, sets `force_destroy` on the vector bucket and
`force_delete` on ECR. Apply once with the flag on before an approved destroy; Terraform cannot
change a protection flag and delete in the same run.

ALB deletion protection is a separate variable, `alb_deletion_protection` (default false in dev,
explicit in `dev.tfvars.example`). The ALB holds no data, and turning the runtime off
(`enable_runtime = false`) must remove it without data-loss mode, so it does not follow
`data_cleanup_mode` (review finding R1-F1).

## EMR: transient cluster per bounded run (decisions D5, D6)

There is no `aws_emr_cluster` resource. The state machine `scholarchain-dev-pipeline`
(`modules/workflow`) calls RunJobFlow (`createCluster.sync`) with:

- release `emr-7.14.0` (verified with `aws emr list-release-labels`, read-only, 2026-11-19). It
  matches `project/Versions.scala` (Hadoop 3.4.2, Spark 3.5.8 against EMR 7.14.0) and the Amazon
  Linux 2023 system buckets that `modules/iam` grants the instance profile. The variable refuses any
  release outside 7.x;
- applications Hadoop and Spark; one On-Demand primary and `emr_core_instance_count` (default 2,
  max 3) On-Demand core nodes of `m5.xlarge`; no task group and no Spot (D6). Managed scaling is
  added only if `emr_core_max_instance_count` exceeds the core count (default: equal, fixed size);
- `KeepJobFlowAliveWhenNoSteps` (steps are added after creation), an auto-termination idle timeout
  of `emr_idle_timeout_seconds` (default 1800) as the backstop, `StepConcurrencyLevel` 1;
- LogUri `s3://<logs bucket>/logs/emr/`, the first data subnet, the `emr_primary`, `emr_core`, and
  `emr_service_access` security groups, service role `scholarchain-dev-emr-service`, instance
  profile `scholarchain-dev-emr-instance`;
- Tags: every required tag, including `scholarchain:environment = dev`, which the Step Functions
  role's `elasticmapreduce:RequestTag` condition requires.

Steps run one at a time (Map, MaxConcurrency 1, `addStep.sync`, ActionOnFailure CONTINUE, per-step
timeout `emr_step_timeout_seconds`). After the cluster exists (ClusterId known), it is terminated
(`terminateCluster.sync`, 3 retries) on success and on every caught step failure. If
`createCluster.sync` itself fails or times out, no ClusterId is returned, so the failure path lists
clusters created after the execution started in a non-terminal state (`aws-sdk:emr:listClusters`;
the Step Functions role gets `elasticmapreduce:ListClusters` on `*`, which has no resource-level
support), matches the deterministic name `<prefix>-pipeline-<execution name>`, and requests
termination of the match (`terminateCluster`, under the environment ResourceTag condition). If the
lookup or termination itself fails, or termination is refused, the execution fails with
`ClusterCreationFailed`, and the cluster stops only at the idle backstop
(`emr_idle_timeout_seconds`, default 1800 s) or by human action. An execution that is stopped or
aborted by hand skips every Catch; the idle backstop also covers that case. Input contract: see the
header of `modules/workflow/main.tf`.

## Runtime consolidation (decision D11) against "independent scaling where needed"

The specification lists seven runtime services (query, graph retrieval, vector retrieval, ranking,
answer synthesis, verification, workers). The code has one deployable HTTP process:
`modules/services` composes `modules/rag` in-process (retrieval, fusion, ranking, synthesis, and
claim verification are library calls inside one request, `ServicesApp.scala`), and no module
exposes those stages over the network. Splitting them into seven Fargate services would add six
network hops, six task minimums, and service discovery for code that does not exist, inside a
USD 25 budget.

Independent scaling is preserved where the load profiles differ:

- the API service scales with synchronous request load (ALB target, desired count 0 or 1 in dev);
- the worker service scales with the SQS jobs backlog, separately from the API (its own service,
  task size, and desired count; 0 until a consumer exists in code);
- the embedding-ingestion task runs per pipeline execution (Step Functions RunTask) and never
  competes with the API for capacity;
- EMR and Neptune scale on their own (transient cluster size; Neptune NCU range).

The stages inside the API share one request deadline and one evidence bundle, so scaling them
separately would not change their latency budget. If a stage later becomes a separate process
(for example a retrieval service), it gets its own task family and service using the same pattern;
the `runtime_reader` role already exists for a read-only split-out service.

No internal service discovery is created: no service calls another by name.

## Runtime health, deploy, rollback, and pipeline principals (review round 1 repairs)

ALB health check (human decision R1-F2). `health_check_path` defaults to `/healthz`, which the
services router answers with a constant 200 and no dependency call (`Router.scala`, the `healthz`
route; `JdkHttpServer.scala` also answers it under load shedding). `/readyz` fails in AWS mode until
the Neptune adapter exists (UnavailableGraphStore), so with `/readyz` as the target health check the
tasks would churn and the circuit breaker would fail every deploy. `scripts/smoke-test.sh` keeps
`/readyz` as an EXPECTED-FAIL check; `SCHOLARCHAIN_SMOKE_REQUIRE_READY=1` makes it required.

Deploy procedure (human decision R1-F3; D14 holds, deploy.sh never runs Terraform). Terraform owns
the task definition: environment (including `release_run_id`), secrets by ARN, roles, sizes.
`scripts/deploy.sh` owns only the image. The services keep `ignore_changes = [task_definition]`.

1. Change the environment in Terraform (for example `release_run_id` in `dev.tfvars`), run
   `scripts/plan.sh dev`, and the human applies the approved plan. Terraform registers a new
   revision of each family; the running services do not change yet.
2. Read the base revisions with `terraform output runtime` (`task_definitions.api`,
   `task_definitions.worker`) and pass them as `SCHOLARCHAIN_API_TASK_DEFINITION_ARN` and
   `SCHOLARCHAIN_WORKER_TASK_DEFINITION_ARN`.
3. deploy-engineer runs `scripts/deploy.sh dev`. It copies each Terraform base revision, rewrites only
   the image to `<repository>:<40-char Git SHA>`, registers it, rolls the service, waits for stable,
   and verifies the task definition, tag, and digest. It prints the base revision and its
   `release_run_id` (`PROVENANCE` marker, also in the `RESULT` line and the record file).

`scripts/rollback.sh` moves both services to the newest earlier revision whose image tag is the
target SHA, as a whole revision, including the `release_run_id` that revision carried (printed in
its `PLAN` marker). A later apply does not undo it (ignore_changes); the next deploy re-bases on the
Terraform output. It needs `ecs:ListTaskDefinitions`, which the deployment role has (R1-F4). An AWS
error while listing or describing revisions fails with exit 4 and the CLI error as `EVIDENCE`,
separately from "no matching revision" (exit 3).

Pipeline principal (human decision R1-F5). `scripts/run-bounded-pipeline.sh` runs as the role
`scholarchain-dev-pipeline-runner` (output `pipeline_runner_role_arn`), created only when the human
lists `pipeline_runner_principal_arns`, trust limited to those principals, 1-hour sessions. It grants
exactly the script's calls: `states:StartExecution`, `DescribeStateMachine`, `ListTagsForResource`,
and `ListExecutions` on the pipeline state machine; `DescribeExecution` and `GetExecutionHistory` on
its executions; `elasticmapreduce:ListClusters` (`*`, no resource-level support); `DescribeCluster`
and `ListSteps` on clusters tagged `scholarchain:environment = dev`; `s3:ListBucket` on the curated
bucket under `bounded-smoke/`; `s3:GetObject` on `samples/`, curated `bounded-smoke/`, `artifacts/`,
and `logs/emr/`; `kms:Decrypt` through S3. No data write, no PassRole, no IAM. The read-only profile
cannot start an execution.

Neptune data access (reviewer assumption). The read policy grants `neptune-db:ReadDataViaQuery`
only. `neptune-db:connect` is the deprecated pre-1.2.0.0 action that granted all data access; the
engine 1.2.0.0 release notes
(https://docs.aws.amazon.com/neptune/latest/userguide/engine-releases-1.2.0.0.html) say policies
based on it must move to the granular actions, and the read-only query example
(https://docs.aws.amazon.com/neptune/latest/userguide/iam-data-access-examples.html) uses
`ReadDataViaQuery` alone. It is therefore not granted.

## Stateful resources

| Resource | Deletion protection | Recovery |
|---|---|---|
| S3 data buckets (raw, samples, curated, artifacts) | `force_destroy` only in cleanup mode | versioning, noncurrent expiry 30 days |
| DynamoDB (5 tables) | on unless `data_cleanup_mode` | PITR on, except query-session (TTL) |
| S3 Vectors bucket and 5 indexes | `force_destroy` only in cleanup mode | none (rebuilt by embedding ingestion); index attributes are ForceNew |
| Neptune Serverless (when enabled) | off in dev (D7 cleanup mode, explicit in tfvars) | automated backups 1 day; no final snapshot in dev |
| KMS key | 30-day window (7 in cleanup mode) | rotation on |
| ECR repository | `force_delete` only in cleanup mode | images are rebuilt from Git |
| SQS jobs queue and DLQ | none | messages retained 4 and 14 days |

## Cost estimates (us-east-1 On-Demand list prices from memory, not verified against the price list)

| Resource | Exists when | Hourly | Monthly if left on (730 h) |
|---|---|---|---|
| EMR cluster, 3 x m5.xlarge (EC2 USD 0.192 + EMR USD 0.048 per node-hour) plus EBS | during a pipeline run only | about USD 0.75 | about USD 550 (never left on: terminated by the run, idle backstop 30 min) |
| Neptune Serverless at min 1.0 NCU (about USD 0.16 per NCU-hour) | `enable_neptune` | USD 0.16 (USD 0.40 at the 2.5 NCU cap) | USD 117 (USD 293 at the cap) |
| Public ALB (USD 0.0225/h plus LCU) | `enable_runtime` | about USD 0.03 | about USD 22 |
| Fargate API task, 0.5 vCPU / 1 GiB | `enable_runtime`, desired 1 | about USD 0.025 | about USD 18 |
| Fargate worker task, 0.5 vCPU / 1 GiB | `worker_desired_count = 1` | about USD 0.025 | about USD 18 |
| Interface endpoints for ECS (4 of 6, one AZ) | `enable_runtime` requires them | USD 0.04 (USD 0.06 with all six) | USD 29 (USD 44) |
| CloudWatch alarms (13 alarm metrics always on, +6 with runtime and Neptune) | always | - | about USD 1.30 (USD 1.90) |
| KMS key, secret, DynamoDB idle, S3 Vectors, ECR storage, dashboard | always | - | about USD 2-3 |

Expected cost of one bounded dev pipeline run (two Hadoop steps over the sample mirror): about
10 minutes of cluster start-up plus 10 to 20 minutes of steps, so 25 to 35 billed minutes of the
3-node cluster, about USD 0.30 to 0.45, plus cents for Step Functions, S3, and logs. The worst
case is bounded by the timeouts: `emr_max_steps` (6) x `emr_step_timeout_seconds` (1 h) plus
creation (30 min) is about USD 5.

One hour of the runtime stack: ALB + one API task + four interface endpoints is about USD 0.10;
with the Bedrock and S3 Vectors endpoints about USD 0.12; with Neptune at its minimum about
USD 0.28. Left on for a month this exceeds the USD 25 budget, which is why both toggles default
to false.
