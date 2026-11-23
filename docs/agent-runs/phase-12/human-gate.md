## Phase 12 pre-delegation decisions

D1. Budget (prompt.md line 125)
    Options: (a) USD 25 cumulative, AWS Budgets alerts at 10/15/20, alert email set in an uncommitted tfvars (rec)
             (b) different cap or thresholds: ____
    Decision: A
    Alert email goes in a local tfvars only, never committed: [X] confirmed

D2. Region and environment (line 133)
    Options: (a) us-east-1, dev only (rec)   (b) other Region: ____
    Decision: A

D3. AWS account (line 134)
    Options: (a) my own account; account ID read at plan time, never hard-coded (rec)
    Decision: A
    Promotional credits available? [X] yes, USD $100.00 total to use for the budget  [ ] no

D4. Remote state (line 140)
    Options: (a) S3 backend with native lockfile; separate bootstrap root I apply by hand once;
                 local-backend fallback until then (rec)
             (b) local state only for the course
    Decision: A

D5. EMR lifecycle (line 146)
    Options: (a) temporary cluster per pipeline run, terminated by the run, idle auto-termination as a backstop (rec)
             (b) long-running cluster
    Decision: A

D6. Spot capacity (line 148)
    Options: (a) no Spot in Phase 12; small On-Demand core and task nodes (rec)
             (b) Spot task nodes, which needs failure-handling evidence first
    Decision: A

D7. Neptune (line 150)
    Options: (a) Serverless, minimum capacity, low max NCU, enable_neptune toggle default false;
                 dev: no deletion protection, skip final snapshot (rec)
             (b) provisioned smallest instance   (c) skip Neptune and serve graph reads from S3/DynamoDB (spec deviation)
    Decision: A
    Max NCU: 2.5

D8. Runtime stack (line 154)
    Options: (a) ECS services and load balancer behind enable_runtime toggle default false; desired count 0 or 1 (rec)
             (b) always on in dev
    Decision: A

D9. OpenSearch (line 156)
    Options: (a) not deployed (rec)   (b) deploy (needs an implemented and evaluated hot or hybrid tier)
    Decision: A

D10. Network egress (line 159)
    Options: (a) no NAT; S3 and DynamoDB gateway endpoints; interface endpoints only where needed, each cost-justified (rec)
             (b) one NAT gateway
    Decision: A

D11. ECS service layout (line 166)
    Options: (a) 2 services: REST API (includes rag) and async worker, with a justification (rec)
             (b) the 7 services listed in the spec
    Decision: A

D12. Ingress (line 169)
    Options: (a) one public load balancer as the only public component; HTTP in dev; Phase 10 API-key auth stays on (rec)
             (b) HTTPS with a certificate: ____   (c) no public ingress; test through a private path only
    Decision: A

D13. Model provider (line 171)
    Options: (a) Amazon Bedrock; model ID is a variable; IAM limited to that model's ARN (rec)
    Decision: A
    Model ID: amazon.nova-micro-v1:0
    Embedding model ID and dimension (must match modules/rag): amazon.nova-2-multimodal-embeddings-v1:0
    Bedrock pricing and credit eligibility checked: [X] yes

D14. Apply path (line 176)
    Options: (a) deploy.sh never runs terraform apply; I apply by hand (rec)
             (b) deploy.sh applies an approved plan checksum (needs a factory-engineer amendment to agents.md section 7 first)
    Decision: A

    Additonally: For Terraform's lock in S3 backend, have (A) plan.sh runs terraform plan -lock=false under the read-only profile. That's safe because only you ever apply, and you apply with locking on.

Sign-off: all D1-D14 decided; [DECIDE] markers and the draft comment removed from prompt.md.  Date: 11-19-26

## Phase 12 review decisions (2026-11-19 / 2026-11-20)

R1-F2. ALB health check default: /healthz. /readyz stays in smoke-test.sh as EXPECTED-FAIL until a
       live Neptune adapter exists.                                          Decision: approved
R1-F3. deploy.sh bases each new task-definition revision on the Terraform-managed revision and
       rewrites only the image. Terraform owns env and secrets; deploy.sh owns the image; no apply
       inside deploy.sh (D14 holds).                                         Decision: approved
R1-F5. New role scholarchain-dev-pipeline-runner, created only when I list principals in
       pipeline_runner_principal_arns.                                       Decision: approved
N1/N2. Fix before sign-off (repair-02), reviewed in round 3.                 Decision: approved
N3.    Fix now (repair-03), plus a narrow review (round 4: APPROVE).          Decision: approved
prep-01/prep-02 (.gitignore) folded into the Phase 12 code review.           Decision: approved

## Apply order

Bootstrap first (D4). I applied infra/terraform/bootstrap by hand with the admin profile, outside
Claude, on 2026-11-20. I then created the uncommitted backend.hcl and deleted local_backend_override.tf.

Manual intervention: my first backend.hcl had a wrong bucket value (replan-s3-01 BLOCKED,
NoSuchBucket). I corrected it on 2026-11-22, and replan-s3-02 succeeded.

## Plan sign-off

Plan file: infra/terraform/envs/dev/plans/dev-8da451a8460a-20261122T192256Z.tfplan
SHA-256:   ee69596e5604d677f11bf2ec9b8a822668ebfcb5fb6a5e68b604248f672595db
Counts:    137 to add, 0 to change, 0 to destroy (DESTRUCTIVE none), S3 backend, plan unlocked (D4)
Reviewed:  agent-report.md, replan-s3-02-infra-engineer.md, code-reviewer rounds 1-4 (round 4 APPROVE)
Estimated standing cost at these defaults: about USD 5/month (runtime, Neptune, interface endpoints off)

I approve applying exactly this plan file on Sunday (11/22/26).      

## Post-apply record

Apply 1 (plan ee69596e…): Get-FileHash matched: [X] yes
  Result: failed partway on 2026-11-22. All resources created except the CloudWatch dashboard and the 5 S3 Vectors indexes.
Apply 2 (plan d685c6c2…): Get-FileHash matched: [X] yes
  Result: "Apply complete! Resources: 6 added, 1 changed, 0 destroyed."   Date: 2026-11-22
Post-apply check: no-change plan 907688cacd01420dfdad41657a0beb6a3bd1ea9716b8a3e0809dc8d1ce4cf06b,
  "No changes. Your infrastructure matches the configuration." (post-apply-verify-infra-engineer.md)
API-key digests stored in scholarchain/dev/api-key-digests: [ ] yes (value not recorded here)
Manual interventions: wrong backend.hcl bucket (fixed); added S3 Vectors metadata reads to the
  ScholarChainReadOnly permission set in IAM Identity Center.

Note: this sign-off was written after the apply. I applied the plan on 11/22/26 after review-05 APPROVE.


## Plan sign-off 2 (after the partial apply of ee69596e)

The apply of plan ee69596e5604d677f11bf2ec9b8a822668ebfcb5fb6a5e68b604248f672595db on 2026-11-22 failed partway
(dashboard PutDashboard errors on widgets 3-4; 5 S3 Vectors CreateIndex 403). Everything else was created. That plan is consumed.
Repair-04 fixed both (review-05 APPROVE).

I added S3 Vectors metadata-read actions (GetVectorBucket, GetVectorBucketPolicy, GetIndex, ListIndexes,
ListTagsForResource on scholarchain-* buckets and indexes; ListVectorBuckets on *) to the ScholarChainReadOnly
permission set, kept its Deny statements, and reprovisioned. No vector data-plane actions were added.

I approve plan infra/terraform/envs/dev/plans/dev-8da451a8460a-20261122T214233Z.tfplan,
SHA-256 d685c6c2ebc40b7568a0284bfbe0639befefd063d2e125dbed138e12b06bc2d1:
6 to add (CloudWatch dashboard, 5 S3 Vectors indexes), 1 to change (KMS data key policy, in place:
the indexing.s3vectors.amazonaws.com kms:Decrypt statement's SourceArn now also matches bucket/scholarchain-dev-vectors/*),
0 to destroy. I approve this KMS key policy change as a security change. I will apply this exact file outside Claude,
with the scholarchain-admin profile and state locking on.

Again, I approve this on 11/22/26

## Summary
Phase 12: AWS infrastructure as code and operational scripts, dev applied: ACCEPTED

- Terraform (aws 6.73.0): bootstrap state bucket, S3 backend with lockfile; VPC with no NAT, KMS CMK,
  scoped IAM, 5-bucket data lake, 5 DynamoDB tables, S3 Vectors bucket and 5 indexes, transient EMR
  through Step Functions, ECS/ALB and Neptune behind toggles (off), SQS, dashboard and alarms, USD 25 budget
- Scripts: plan, deploy, rollback, smoke-test, cleanup; run-bounded-pipeline moved to Step Functions
- Dev applied by the human (partial apply fixed by repair-04); post-apply plan shows no changes
- Code review rounds 1-5 APPROVE; repairs 01-04


Accepted commit hash: 