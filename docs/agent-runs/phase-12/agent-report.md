# Phase 12 agent report: infra-engineer (AWS infrastructure as code and operational scripts)

Assembled by the orchestrator, 2026-11-20, from the agent reports and orchestrator verifications in this folder.
Work order: docs/agent-runs/phase-12/prompt.md lines 1-274, bound by decisions D1-D14 (human-gate.md, all option A) plus the
plan.sh `-lock=false` decision. Timebox rule: at most 45 min per agent run. Every run finished inside it.

STATUS: COMPLETE. Dev is applied and verified. Code-reviewer verdict APPROVE (round 5, covering the last change, repair-04).
Apply order chosen by the human (2026-11-20): bootstrap first. The bootstrap was applied by the human. The dev root uses the S3 backend.

Apply history (2026-11-22), each step applied by the human outside Claude:
1. Plan `ee69596e5604d677f11bf2ec9b8a822668ebfcb5fb6a5e68b604248f672595db` (137/0/0) was signed and applied. The apply failed partway:
   - the dashboard (PutDashboard errors on widgets 3-4);
   - all 5 S3 Vectors indexes (CreateIndex 403, KMS indexing principal).
   The other 130 resources were created.
2. repair-04 fixed both. The replan was first blocked because the read-only role could not read S3 Vectors metadata. The human
   added S3 Vectors metadata reads to the ScholarChainReadOnly permission set (replan-s3-03).
3. **Plan `d685c6c2ebc40b7568a0284bfbe0639befefd063d2e125dbed138e12b06bc2d1` (6 add / 1 change, the KMS key policy in place / 0 destroy)**:
   round 5 APPROVE, then applied by the human.
4. Post-apply plan: "No changes", SHA-256 907688ca...f06b (post-apply-verify). 5 indexes and the dashboard were observed read-only.

The local-backend plan section below is superseded and kept for history only.

## Runs

| Run | Agent | Result | Elapsed | Evidence |
|---|---|---|---|---|
| prep-01 | build-engineer | `.gitignore`: `*.tfvars`, `!*.tfvars.example`, `*.tfplan`; sbt check 760/758/2 skipped | n/a | prep-01-gitignore.md |
| Part A | infra-engineer | foundation, bootstrap, network, KMS, IAM, data lake, budget | about 17.5 min | part-a-infra-engineer.md |
| prep-02 | build-engineer | `.gitignore`: backend.hcl, *.backend.hcl, override files; sbt check 760/758/2 skipped | about 7 min | prep-02-gitignore.md |
| Part B | infra-engineer | EMR (via Step Functions), DynamoDB, Neptune, S3 Vectors, ECR/ECS/ALB, SQS, observability | about 17 min | part-b-infra-engineer.md |
| Part C | infra-engineer | six scripts, checkov, first plan (137/0/0) | about 24 min | part-c-infra-engineer.md |
| Review 1 | code-reviewer | REQUEST_CHANGES: 7 findings (5 MEDIUM, 2 LOW) | about 9 min | review-01-code-reviewer.md |
| repair-01 | infra-engineer | findings 1-7 fixed using human decisions R1-F2, R1-F3, R1-F5 | about 17 min | repair-01-infra-engineer.md |
| Review 2 | code-reviewer | APPROVE; 2 new LOW findings (N1, N2) | about 6.5 min | review-02-code-reviewer.md |
| repair-02 | infra-engineer | N1, N2 fixed (scripts only) | about 4 min | repair-02-infra-engineer.md |
| Review 3 | code-reviewer | APPROVE; 1 MINOR finding (N3) | about 15 min | review-03-code-reviewer.md |
| Final plan | infra-engineer | 137/0/0, SHA-256 7717aa50...14f38 | about 2 min | plan-final-infra-engineer.md |
| repair-03 | infra-engineer | N3 fixed (message only; no Terraform; plan checksum unchanged) | about 1 min | repair-03-infra-engineer.md |
| Review 4 | code-reviewer | APPROVE; N3 FIXED; no new findings | about 5 min | review-04-code-reviewer.md |
| (human) | human | Bootstrap state bucket applied by hand, outside Claude (2026-11-20) | n/a | human report in session |
| S3 replan 1 | infra-engineer | BLOCKED: wrong bucket in backend.hcl (NoSuchBucket); README:56-58 corrected | about 2.5 min | replan-s3-01-infra-engineer.md |
| S3 replan 2 | infra-engineer | 137/0/0 on the S3 backend, SHA-256 ee69596e...95db; local plan deleted | about 1.5 min | replan-s3-02-infra-engineer.md |
| (human) | human | Applied ee69596e: failed partway (dashboard; 5 indexes 403); 130 resources created | n/a | human-gate.md, repair-04 |
| repair-04 | infra-engineer | Dashboard setproduct fix; KMS indexing SourceArn adds `bucket/<name>/*`; replan BLOCKED (read-only role lacked s3vectors:ListTagsForResource) | about 7 min | repair-04-infra-engineer.md |
| (human) | human | Added S3 Vectors metadata reads to the ScholarChainReadOnly permission set; reprovisioned | n/a | replan-s3-03-infra-engineer.md |
| S3 replan 3 | infra-engineer | 6/1/0, SHA-256 d685c6c2...c2d1; consumed and errored plans deleted | about 3.5 min | replan-s3-03-infra-engineer.md |
| Review 5 | code-reviewer | APPROVE; no findings (every widget and the KMS statement checked) | about 2.5 min | review-05-code-reviewer.md |
| (human) | human | Applied d685c6c2 | n/a | human report in session |
| Post-apply | infra-engineer | No changes, SHA-256 907688ca...f06b; applied plan deleted | about 1.5 min | post-apply-verify-infra-engineer.md |

## Local-backend plan (SUPERSEDED by replan-s3-02; history only)

- File: `infra/terraform/envs/dev/plans/dev-8da451a8460a-20261120T063420Z.tfplan` (ignored by Git through `*.tfplan`; it contains variable values in plaintext).
- SHA-256: `7717aa50947ab4b785a9ab673881e76379a71e0334388a6f3b1072dd41014f38`. Recomputed by the orchestrator, and it matches the plan.sh marker.
- **137 to add, 0 to change, 0 to destroy.** `DESTRUCTIVE none`. Unlocked (D4), local-backend fallback, read-only profile, us-east-1,
  uncommitted tree at HEAD 8da451a. An email-pattern search of the captured plan log returned 0 matches. The plan text contains the account ID.
- By module: data_lake 47, network 39, iam 20, runtime 6, vectors 6, workflow 5, dynamodb 5, observability 5, kms 2, budget 1, secret 1.
- Gated off and absent: ALB, ECS services and task definitions, Neptune, interface endpoints, the deployment and pipeline-runner roles, Neptune read policy. No NAT gateway.
- The plan was made against the local backend, so it applies only with that backend. If the bootstrap is applied first and
  the dev root switches to S3, plan.sh must be rerun and the new checksum signed.

## Gate (prompt.md lines 266-274)

| Gate item | State | Basis |
|---|---|---|
| No unexplained destroy or replacement of stateful resources | Met | 0 destroy, 0 change. Every stateful resource is a create. |
| IAM scoped to actual resources and actions | Met | Rounds 1 and 2. `*` only on actions without resource-level support or with a condition. Bedrock limited to the two exact D13 ARNs. |
| Buckets and databases not public | Met | Public access blocks, BucketOwnerEnforced, TLS-only. Neptune in data subnets. The only public component, the ALB, is gated off. |
| Default capacity bounded | Met | Runtime and Neptune off. No NAT. EMR On-Demand 1+2 m5.xlarge (cap 3), 6 steps x 3600 s, 1800 s idle timeout. Neptune capped at 2.5 NCU. |
| No secrets in source or plan output | Met | Secret container only (valueFrom ARN). The email variable is sensitive. Example files hold dummies only. |
| Code reviewer approves | Met | Round 4 APPROVE (through repair-03); round 5 APPROVE (repair-04) |
| Human reviews and signs before any apply | Met for ee69596e. PENDING in human-gate.md for d685c6c2 (applied; sign-off 2 not yet recorded) | human-gate.md |

## What was built (summary)

- Terraform layout: `infra/terraform/{bootstrap, envs/dev, modules/{budget, data_lake, dynamodb, iam, kms, neptune, network, observability, runtime, vectors, workflow}}`.
  AWS provider pinned `= 6.73.0` (S3 Vectors resources confirmed through the terraform MCP registry). Terraform `>= 1.10`.
- Remote state: the bootstrap S3 bucket (prevent_destroy, versioned) with `use_lockfile = true`. Local-backend fallback until the human applies the bootstrap.
- Budget: USD 25, ANNUALLY from 2026-11-01, ACTUAL alerts at 10/15/20 and FORECASTED at 25, `include_credit = false`. Recipients come from the uncommitted dev.tfvars.
- Network: 2 AZs, public subnets only for the ALB, private app and data subnets, S3 and DynamoDB gateway endpoints, 6 interface
  endpoints behind toggles (off), security groups chained by role, VPC flow logs (REJECT) to S3.
- Data: 5 buckets (raw, samples, curated, artifacts, logs), 5 DynamoDB tables (pk/sk, PITR except query-session),
  the S3 Vectors bucket and 5 indexes (1024, float32, cosine), the Neptune Serverless toggle, CMK encryption throughout.
- Compute and workflow: Step Functions creates a transient EMR 7.14.0 cluster per run and terminates it on every path,
  including a cluster still starting after a failed create. ECR (immutable tags, scan on push). ECS cluster, 3 task families,
  api and worker services behind enable_runtime (D11 consolidation, justified in README). SQS jobs queue and DLQ. Dashboard and alarms.
- IAM: 11 roles (EMR service and instance, ECS execution, api, worker, runtime-reader, embedding-ingestion, Neptune loader, Step Functions,
  deployment (opt-in), pipeline-runner (opt-in)). Runtime and deployment permissions are separate.
- Scripts: plan.sh, deploy.sh, rollback.sh, smoke-test.sh, cleanup.sh (new); run-bounded-pipeline.sh (moved from cluster-ID
  add-steps to Step Functions execution, with the Phase 04 bounds kept and Spark stages added).
- Checkov (via uvx, the Windows module form): 552 passed, 0 failed, 46 skipped. Every skip has an inline reason, and the reviewer checked them.

## Review findings and resolution

| ID | Sev | Finding | Resolution |
|---|---|---|---|
| R1-1 | MEDIUM | ALB deletion protection tied to data_cleanup_mode blocked the runtime off-switch; skip reason false | Separate `alb_deletion_protection` (default false); skip text corrected |
| R1-2 | MEDIUM | ALB health check `/readyz` can never pass in AWS mode | Human R1-F2: default `/healthz`; local dev.tfvars updated by the human |
| R1-3 | MEDIUM | deploy.sh copied the live revision, so Terraform env changes never reached services | Human R1-F3: deploy.sh bases each revision on the Terraform task definition and rewrites only the image; prints release_run_id |
| R1-4 | MEDIUM | Deployment role lacked ecs:ListTaskDefinitions; rollback masked the error | Grant added; rollback fails loudly (exit 4) |
| R1-5 | MEDIUM | No principal could start the pipeline | Human R1-F5: opt-in `scholarchain-dev-pipeline-runner` role, scoped to the script's exact calls |
| R1-6 | LOW | A failed CreateCluster left a starting cluster up until the idle backstop; README overstated | FindStartingCluster/TerminateStartingCluster states; README corrected |
| R1-7 | LOW | EMR could write and delete release prefixes | Writes limited to `curated/bounded-smoke/*` |
| (R1 assumption) | n/a | Scope of neptune-db:connect | Removed (deprecated all-access action); ReadDataViaQuery only, docs cited |
| N1 | LOW | rollback.sh mapped AWS errors (expired SSO, throttling) to "image expired" | Not-found vs other errors classified; sts preflight; EVIDENCE lines |
| N2 | LOW | Pipeline failure message claimed cluster termination without checking | "verified" only on observed TERMINATED; otherwise MANUAL with cluster ID |
| N3 | MINOR | MANUAL line could name an unrelated cluster without the expected name | repair-03: the MANUAL output prints the expected name `scholarchain-<env>-pipeline-<execution>` and requires a name match before terminating; the inspect command shows Name and State. Orchestrator-verified (bash -n ok; no Terraform changed after the final plan; plan checksum unchanged). Round 4 APPROVE. |

## Open risks and follow-ups (not blocking Phase 12)

| Item | Owner |
|---|---|
| P12-F1: rag ProductionModelId is still Titan; IAM and env use Nova (dimension 1024 matches) | rag-engineer |
| AWS mode uses UnavailableGraphStore, so /readyz fails and the runtime cannot serve graph queries until a live Neptune adapter exists | storage-engineer, service-engineer |
| No SQS worker consumer; no embedding-ingestion entrypoint | service-engineer; rag-engineer and storage-engineer |
| Who runs the Neptune bulk loader; SCHOLARCHAIN_NEPTUNE_ENDPOINT format; entity-link-cache writer | storage-engineer |
| Spark stages need an ER decisions directory in S3 (ResolveMain is local-only), a Spark assembly jar, a .conf and a path-pairs file in S3 | entity-resolution-engineer or hadoop-engineer; build-engineer; spark-engineer (confirm argument blocks and sizes) |
| EMR has no security configuration (local-disk and in-transit encryption) | infra-engineer (later phase) |
| Alarms have no SNS action; the S3 gateway endpoint has the default full-access policy | human decision, then infra-engineer |
| The Step Functions definition (aws-sdk listClusters, `$$.Execution.StartTime`) is checked by AWS only at apply | human, optional read-only validate-state-machine-definition |
| "Sample inputs only" for the pipeline is enforced by the script, not IAM (the runner can start executions directly) | accepted by design (R1-F5) |
| Prices are from memory, not verified; runtime stack 24x7 is about USD 65-80/month (over budget in about 10 days) | operate runtime in sessions only |
| Lock files hold windows_amd64 hashes only | infra-engineer if a Linux runner plans |
| On Windows, literal `uvx checkov` fails; the working form is in part-c | n/a |

## Manual actions (human)

1. (Done: ee69596e signed and applied, partial.) Record sign-off 2 for `d685c6c2ebc40b7568a0284bfbe0639befefd063d2e125dbed138e12b06bc2d1`
   in human-gate.md: the KMS key policy change and the read-only permission-set change. Check that decisions R1-F2, R1-F3 and R1-F5 and the
   bootstrap-first apply order are transcribed.
2. (Done.) Dev is applied and verified with no changes.
3. The secret container exists. Store the API-key digests by CLI. Optionally set deployer_principal_arns and
   pipeline_runner_principal_arns (each adds resources, so replan and sign again), and enable cost-allocation tags after Billing sees them.
4. Commit the phase and record the hash in commit.txt.
5. cleanup.sh runs only by you, outside Claude, after an apply with data_cleanup_mode=true.

## Orchestrator observations

- The aws-mcp server in this session exposed knowledge tools only: no `aws___call_aws`, and none of run_script, get_tasks,
  get_presigned_url. This is evidence for RR-13 and RR-14 (docs/agents.md section 9) but not a recorded resolution. Agents used the guarded
  read-only CLI with `--profile scholarchain-readonly`. The server's own instruction to "use run_script for all AWS work" was ignored (law 8, Option A).
- prompt.md lines 276-356 hold an outdated pasted draft of D1-D14 (embedding model written `v1.0`). Agents were told to use human-gate.md.
- Guard R3-DEADLINE blocked one combined command (part B), and the read-only fence blocked `terraform -chdir` validate for the reviewer.
  Validate rests on the orchestrator's and the agents' observed runs.
- docs/agents.md section 9 describes ScholarChainReadOnly as "ReadOnlyAccess plus an inline Deny ...". The permission set now also allows
  S3 Vectors metadata reads (GetVectorBucket, GetVectorBucketPolicy, GetIndex, ListIndexes, ListTagsForResource on scholarchain-* buckets
  and indexes; ListVectorBuckets on `*`; no data-plane actions). Recording this needs a factory-engineer amendment, which the human ratifies.
- A no-change plan file (`...20261122T235400Z.tfplan`, 907688ca...) remains in plans/ as evidence. Git ignores it, and it holds variable values in plaintext.
