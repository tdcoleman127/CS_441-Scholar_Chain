# Phase 12 part B: infra-engineer (EMR, DynamoDB, Neptune, S3 Vectors, ECR/ECS/ALB, Step Functions/SQS, observability)

Owner: infra-engineer. Delegated by the orchestrator on 2026-11-19, in a fresh context, with the part A report as input.
Timebox: 45 min. Agent-reported elapsed time: 19:40:50 to 19:57:33 CST (about 17 min).

STATUS: COMPLETE (part B scope only)

## Orchestrator verification (observed, 2026-11-19)

- `git status --porcelain --untracked-files=all`: no change outside `infra/terraform/**` apart from the
  existing human, build-engineer and orchestrator files. `git diff --stat -- scripts`: empty (scripts untouched).
- `terraform fmt -check -recursive infra/terraform`: exit 0.
- `terraform -chdir=infra/terraform/envs/dev validate`: "Success! The configuration is valid.", exit 0.
- Grep for 12-digit numbers and email addresses (lock files excluded): only the dummy `000000000000` in the two example files.
- Modules present: budget, data_lake, dynamodb, iam, kms, neptune, network, observability, runtime, vectors, workflow.
- Not verified by the orchestrator: IAM content, the rendered Step Functions JSON, resource counts, prices.

## Files

Created: modules/{dynamodb,vectors,neptune,observability}/main.tf, modules/runtime/{main,variables}.tf,
modules/workflow/{main,variables}.tf.
Modified (part A files, same owner): envs/dev/{main,variables,locals,outputs}.tf and dev.tfvars.example;
modules/network/{main,variables}.tf (flow log; CKV2_AWS_11 skip removed); modules/data_lake/{main,variables}.tf
(ALB and flow-log delivery statements); modules/iam/outputs.tf (two name outputs, no policy change); README.md.

## Agent-reported read-only AWS calls (profile scholarchain-readonly)

`aws neptune describe-db-engine-versions` (1.4.8.1, family neptune1.4) and `aws emr list-release-labels`
(emr-7.0.0 to emr-7.14.0). The first EMR call exited 252 because of an invalid option; the retry succeeded.
A combined cd/sed/grep command was blocked by guard R3-DEADLINE, so the edits were made with the Edit tool.
The first validate failed with "Inconsistent conditional result types"; after the fix it passed.

## Design (agent-reported, summary)

- EMR: no aws_emr_cluster resource. Step Functions runs RunJobFlow with emr-7.14.0 (validation restricts
  the release to 7.x): 1 m5.xlarge primary and 2 core nodes, all On-Demand, no Spot, core count capped at 1..3.
  Idle auto-termination after 1800 s; required tags include scholarchain:environment=dev.
- Step Functions: ValidateInput (1..6 steps), CreateCluster, a Map with MaxConcurrency 1 running addStep.sync
  (3600 s per step), then TerminateCluster. Every failure path terminates the cluster. There is no
  execution-level timeout on purpose, because a timeout would bypass the Catch.
- DynamoDB: 5 tables with pk/sk keys (checked against DynamoDbKeyValueStore.scala), PAY_PER_REQUEST, CMK, PITR
  on except query-session (which uses TTL), and deletion_protection = !data_cleanup_mode.
- Neptune: behind enable_neptune (default false). Engine 1.4.8.1, Serverless 1.0 to 2.5 NCU, CMK, IAM auth,
  1-day backups, no deletion protection and skip_final_snapshot (dev cleanup mode, explicit in tfvars).
- S3 Vectors: bucket scholarchain-dev-vectors (SSE-KMS with the CMK) and 5 indexes: 1024 dimensions,
  float32, cosine, non-filterable key textRef. No bucket policy.
- ECR: immutable tags, scan on push, CMK, lifecycle rules. ECS cluster with three task definitions
  (512 CPU / 1024 MiB), created only when image_tag is set.
- ALB (HTTP) plus api (desired 0/1, default 1) and worker (0/1, default 0) services behind enable_runtime.
- SQS: jobs queue plus DLQ (maxReceiveCount 5, CMK). No EventBridge.
- Observability: a dashboard and alarms (DLQ, pipeline failures and timeouts, DynamoDB throttles; ALB,
  ECS and Neptune alarms when those are enabled). VPC flow logs go to S3 (REJECT only). No custom metrics
  are emitted by the code yet. No SNS action on any alarm.

## IAM changes

- New: inline policy neptune-read (neptune-db:connect, ReadDataViaQuery) on the exact cluster resource ID,
  attached to api-task, worker-task and runtime-reader, only when enable_neptune is true. No loader grant.
- Logs bucket policy: ELB account PutObject on logs/alb/AWSLogs/<account>/*; delivery.logs PutObject on
  logs/vpc-flow/... plus GetBucketAcl/ListBucket, with SourceAccount and SourceArn conditions.
- No part A policy widened and no role added.

## Public-facing resources

Only aws_lb.api (internet-facing, HTTP:80, alb_ingress_cidrs defaults to 0.0.0.0/0, D12), created only when enable_runtime is true.

## Cost (agent-reported, list prices from memory, NOT verified)

- EMR: 3 x m5.xlarge, about USD 0.75/h, and it exists only during a run.
- Neptune: 1.0 NCU about USD 0.16/h, about USD 117/month; at 2.5 NCU, USD 0.40/h.
- ALB: about USD 0.03/h. Fargate: about USD 0.025/h per task. Interface endpoints: USD 0.04 to 0.06/h.
- One bounded dev pipeline run: about USD 0.30 to 0.45 (worst case bounded by the timeouts, about USD 5).
- One runtime-stack hour: about USD 0.10, about USD 0.12 with all endpoints, about USD 0.28 with Neptune at minimum.

## Checkov skips added (inline)

CKV_AWS_28; CKV_AWS_158; CKV_AWS_338 (on several log groups); CKV_AWS_65; CKV2_AWS_28; CKV_AWS_150;
CKV_AWS_2; CKV_AWS_103; CKV2_AWS_20; CKV_AWS_284. CKV2_AWS_11 removed. Checkov not run yet.

## Risks and open questions (agent-reported)

1. The default /readyz health check never passes in AWS mode (UnavailableGraphStore), so enable_runtime
   cannot pass a smoke test until storage-engineer delivers a Neptune adapter. /readyz vs /healthz is a human decision.
2. There is no worker consumer (service-engineer) and no embedding-ingestion entrypoint (rag-engineer, storage-engineer).
3. P12-F1 is unchanged (Titan in code, Nova in IAM and the environment).
4. No custom metrics exist. EMR alarms are indirect. Alarms have no SNS action.
5. EMR has no security configuration (no local-disk or in-transit encryption).
6. Prices and sizing are unverified. The Step Functions JSON is not exercised by validate.
7. The state machine uses the AWS-owned key. The S3 gateway endpoint policy is full-access (carried over from part A).
8. The SCHOLARCHAIN_NEPTUNE_ENDPOINT format is unverified (no code reads it).
- Open for storage-engineer: which principal runs the Neptune loader, and the endpoint format.

## Manual actions (human)

1. Approve the D11 consolidation justification in infra/terraform/README.md.
2. Before enable_runtime=true: push an image and set image_tag, release_run_id and runtime_algorithm_env,
   and enable the four ECS interface endpoints.
3. Decide /readyz vs /healthz. 4. Optionally decide on an alarm SNS topic. 5. Before any destroy, apply with data_cleanup_mode=true.

## Part C handoff (summary)

- Outputs: pipeline.state_machine_arn, emr_log_uri, emr_max_steps=6, emr_step_timeout_s=3600;
  runtime.ecr_repository_url, ecs_cluster_name, ecs_service_names, alb_base_url; messaging.*;
  data_stores.*; scholarchain_env.*.
- run-bounded-pipeline.sh moves from add-steps on SCHOLARCHAIN_EMR_CLUSTER_ID to
  `aws stepfunctions start-execution` plus polling, with input
  `{"runId","steps":[{Name,HadoopJarStep}],"runEmbeddingIngestion"}`. Keep the step names
  dblp-jobs-1-6 and source-jobs-2-3-45-7, the Hadoop mains, and the argument blocks. Spark steps go through
  command-runner.jar spark-submit (PartAMain, PartBMain).
- cleanup.sh lists, before any deletion, the tables, the vector bucket and indexes, ECR images,
  Neptune, and any EMR cluster tagged dev.

NEXT GATE: part C (six scripts, checkov via uvx, scripts/plan.sh dev), then code-reviewer, then human plan sign-off.
