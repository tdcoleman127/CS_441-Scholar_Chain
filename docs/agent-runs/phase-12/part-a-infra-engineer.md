# Phase 12 part A: infra-engineer (foundation, bootstrap, network, IAM/KMS, data lake)

Owner: infra-engineer. Delegated by the orchestrator, 2026-11-19. Timebox 45 min; agent-reported
elapsed 19:20:17 to 19:37:39 CST (about 17.5 min).

STATUS: COMPLETE (part A scope only)

## Orchestrator verification (observed, 2026-11-19)

- `git status --porcelain --untracked-files=all`: the only new files are under `infra/terraform/**`
  (30 files, listed below). No file outside the agent's territory changed. `.gitignore` and
  `docs/agent-runs/phase-12/*` show only the pre-existing human and build-engineer changes.
- `terraform fmt -check -recursive infra/terraform`: exit 0.
- `terraform -chdir=infra/terraform/envs/dev validate`: "Success! The configuration is valid.", exit 0.
- `terraform -chdir=infra/terraform/bootstrap validate`: "Success! The configuration is valid.", exit 0.
- Grep for 12-digit numbers and email addresses (excluding `.terraform/`): only the dummy
  `000000000000` in `dev.tfvars.example` and `backend.hcl.example`, plus provider hashes in the lock files.
- Not verified by the orchestrator: IAM content, resource counts, cost figures, registry claims.
  Those are for code-reviewer and part C's plan.

## Files created (infra/terraform/)

README.md; bootstrap/{versions,variables,main,outputs}.tf, bootstrap/.terraform.lock.hcl;
envs/dev/{versions,providers,variables,locals,main,outputs}.tf, envs/dev/.terraform.lock.hcl,
envs/dev/{dev.tfvars.example, backend.hcl.example, local_backend_override.tf.example};
modules/network/{versions,variables,main,outputs}.tf; modules/kms/main.tf;
modules/iam/{variables,main,outputs}.tf; modules/data_lake/{main,variables,outputs}.tf;
modules/budget/main.tf.

## Agent-reported commands

terraform v1.16.4 windows_amd64. fmt -check first failed (exit 3, two files), fixed with whitespace-only
changes, then exit 0. `init -backend=false` on both roots installed hashicorp/aws v6.73.0. validate on
both roots passed, and passed again after a KMS key policy change. No plan, no AWS CLI calls, no state
reads, no admin profile. A `grep` on .gitignore aborted (exit 134), so the agent reran it with the Grep tool.

## Provider pin

hashicorp/aws `= 6.73.0` in both roots; child modules `>= 6.73.0, < 7.0.0`; Terraform `>= 1.10.0, < 2.0.0`.
Source: terraform MCP. `get_latest_provider_version` returned 6.73.0, and `search_providers(s3vectors, 6.73.0)`
listed aws_s3vectors_index, aws_s3vectors_vector_bucket, aws_s3vectors_vector_bucket_policy.

## IAM roles (P = scholarchain-dev; agent-reported, summary)

1. P-emr-service: AmazonEMRServicePolicy_v2 (limited to resources carrying the managed-policy tag), plus PassRole on P-emr-instance to ec2.
2. P-emr-instance: read samples/raw/artifacts prefixes; read and write curated bounded-smoke/, curated/, graph/, run-manifests/; put logs/emr/; EMR system buckets; KMS via s3.
3. P-ecs-execution: ECR pull on P-services, logs on /scholarchain/dev/ecs/*, GetSecretValue on the api-key-digests secret only.
4. P-api-task: curated graph/ and semantic-context/ read; DynamoDB per table (writes only to query-session and evidence-bundle); s3vectors Query/Get on 5 indexes; bedrock:InvokeModel on both exact model ARNs; sqs SendMessage on P-jobs.
5. P-worker-task: same as the API task, but the SQS grant is the receive/delete set.
6. P-runtime-reader: strictly read-only (S3, DynamoDB, s3vectors); no Bedrock.
7. P-embedding-ingestion: semantic-context/ write; s3vectors PutVectors; bedrock:InvokeModel on the embedding model ARN only.
8. P-neptune-loader: rds trust; read curated graph/ only.
9. P-step-functions: RunJobFlow on * with RequestTag environment=dev; cluster actions with ResourceTag; RunTask on the embedding-ingestion family; PassRole scoped.
10. P-deployment: created only if deployer_principal_arns is non-empty; ECR push, ECS UpdateService on the api and worker services, PassRole; no data, state, or IAM-write access.
Wildcard resources only where the action has no resource-level permissions (ecr:GetAuthorizationToken,
ecs:RegisterTaskDefinition, ELB describe actions, CloudWatch Logs vended delivery). No neptune-db grants
yet; part B attaches them once the cluster resource ID exists.

## Cost (agent-reported, list prices from memory, not verified)

No NAT. Six interface endpoints, each a toggle, all off by default: USD 0.01/h per AZ each, all six in one
AZ about USD 43.80/month. CMK about USD 1/month, secret USD 0.40/month. Part A idle is about USD 1.40/month
plus S3. Budget: ANNUALLY from 2026-11-01, include_credit = false, ACTUAL alerts at 10/15/20 and a
FORECASTED alert at 25.

## Part B handoff (names fixed in envs/dev/locals.tf)

- DynamoDB: scholarchain-dev-{run-ledger, entity-link-cache, query-session, evidence-bundle, artifact-registry}, pk/sk keys, no GSIs.
- S3 Vectors bucket: scholarchain-dev-vectors, SSE-KMS with the CMK ARN.
- S3 Vectors indexes: scholarchain-dev-{researcher-profile-v1, collaboration-community-v1, paper-abstract-v1, collaboration-path-v1, topic-institution-summary-v1}, 1024, float32.
- ECR: scholarchain-dev-services. ECS cluster scholarchain-dev; services -api and -worker; task families -api, -worker, -embedding-ingestion.
- Logs: /scholarchain/dev/ecs/{api,worker,embedding-ingestion}, /scholarchain/dev/states/pipeline.
- SQS: scholarchain-dev-jobs plus scholarchain-dev-jobs-dlq.
- State machine: scholarchain-dev-pipeline. EMR clusters must pass the tag scholarchain:environment=dev.
- Neptune cluster: scholarchain-dev-graph; attach neptune-db read grants on the exact resource ID.
- ALB access-log statement on the logs bucket (logs/alb/*); VPC flow logs (checkov skip until then).

## Risks and follow-ups (agent-reported)

- P12-F1 (rag-engineer): ProductionModelId is still Titan; IAM allows only Nova, so a Titan call would be denied.
- storage-engineer and service-engineer: AWS mode uses UnavailableGraphStore, so readiness fails; there is no SQS worker consumer and no Bedrock client.
- storage-engineer: no role or code path owns the entity-link-cache loader (owner decision needed).
- build-engineer: .gitignore does not cover `backend.hcl`, `local_backend_override.tf`, `override.tf`, `*_override.tf`. backend.hcl would hold the account ID.
- The S3 gateway endpoint keeps its default full-access policy.
- EMR system bucket names assume EMR 7.x.
- Lock files hold windows_amd64 hashes only.
- alb_ingress_cidrs defaults to 0.0.0.0/0 (D12).
- Checkov not run yet (part C).

## Manual actions (human)

1. Apply infra/terraform/bootstrap once with the admin profile, outside Claude; then create an uncommitted backend.hcl from the example.
2. Until then, use the local-backend fallback for the dev plan.
3. Create envs/dev/dev.tfvars with the real budget address.
4. Store the API-key digests in the secret by CLI after it exists.
5. Optionally set deployer_principal_arns and activate_cost_allocation_tags.

NEXT GATE: part B, then part C (scripts, checkov, plan.sh dev), then code-reviewer, then human plan sign-off.
