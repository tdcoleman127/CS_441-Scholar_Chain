Purpose:

Define the complete ScholarChain cloud environment as reviewed, reproducible code.

Why:

Manual cloud assembly creates drift, missing security controls, and a deployment that graders cannot reproduce. Terraform plans make intended changes visible before resources are created.

Source: the first text block is the course specification's Phase 12 work order, verbatim
(docs/project-description.md lines 1760-1861). The human decisions below were taken in
docs/agent-runs/phase-12/human-gate.md (D1-D14, all option A, 2026-11-19) and bind the work order.

Timebox (human rule): every part of Phase 12 is limited to 30-45 minutes of agent time. A part is one
agent run: the infra-engineer authoring run, each code-reviewer review, and each repair run. Record
the start time with `date` at the beginning of the run, check it at each milestone, and stop at 45
minutes. If the work is not finished, return STATUS: FAILED or BLOCKED-ON with exactly what is done,
what is not, and the next step, so the orchestrator can split the rest into another part. Never pad a
report to look complete. Suggested split for the authoring work if one run cannot finish it:
part A foundation, bootstrap, network, IAM/KMS, data lake; part B EMR, DynamoDB, Neptune, S3 Vectors,
ECR/ECS, Step Functions/SQS, observability; part C the six scripts, scans, and the plan.

Use this work order:

```text
Use the infra-engineer agent to create the ScholarChain AWS infrastructure under infra/terraform and the approved operational scripts under scripts.

Required Terraform modules or equivalent organization:

1. Foundation
- Provider versions pinned.
- Remote state design documented.
- Required tags.
- Budget and cost-allocation tags where course accounts allow.

2. Network
- VPC in at least two Availability Zones.
- Public subnets only for approved ingress components.
- Private application and data subnets.
- Route tables, NAT or controlled egress, and VPC endpoints where justified.
- Security groups chained by service role rather than broad CIDR access.

3. Identity and encryption
- Least-privilege roles for EMR, ECS tasks, Step Functions, deployment execution, loaders, and runtime readers.
- Separate runtime and deployment permissions.
- KMS keys or approved AWS-managed encryption choices.
- Secrets Manager records referenced by ARN, never secret values in Terraform source or plans.

4. Data lake
- Raw, curated, graph, RAG, artifact, log, and report S3 buckets or prefixes.
- Versioning where needed.
- Encryption, public-access blocking, lifecycle rules, and deletion safeguards.
- Glue catalog resources when used.

5. EMR
- Hadoop and Spark applications.
- Managed scaling or instance-fleet policy.
- Log and bootstrap configuration.
- Bounded default capacity.
- Spot task capacity only when failure behavior is handled.

6. DynamoDB
- Required tables and indexes.
- On-demand or autoscaled provisioned capacity with justification.
- Point-in-time recovery where appropriate.

7. Neptune
- Cluster or serverless configuration.
- Subnet and parameter groups.
- Encryption, backups, deletion protection for non-ephemeral environments.
- Loader role and read-only runtime role.

8. S3 Vectors
- Vector bucket resources.
- Index resources for every required index.
- Encryption, tags, and access policies.
- Use current Terraform provider resources supported at implementation time and pin the provider version.

9. Optional OpenSearch
- Deploy only when used by an implemented and evaluated hot or hybrid tier.
- Define access, encryption, logging, and capacity policy.

10. Runtime compute
- ECR repositories with immutable Git SHA tags and scan-on-push.
- ECS cluster and Fargate task definitions.
- Services for query, graph retrieval, vector retrieval, ranking, answer synthesis, verification, and workers, or a justified consolidation that preserves independent scaling where needed.
- Internal service discovery and approved ingress.

11. Workflow and messaging
- Step Functions state machines.
- SQS queues and dead-letter queues.
- EventBridge rules when used.

12. Observability
- CloudWatch log groups.
- Dashboards and alarms.
- Metrics for EMR, ECS, SQS, Neptune, DynamoDB, S3 Vectors clients, API latency, LLM usage, and errors.

13. Cost and safety
- Bounded defaults.
- Environment-specific variable files without secrets.
- Deletion protection for stateful resources in persistent environments.
- Explicit cleanup mode for the course environment.

Required scripts:
- plan.sh
- deploy.sh
- rollback.sh
- smoke-test.sh
- run-bounded-pipeline.sh
- cleanup.sh

Script requirements:
- bash strict mode.
- No interactive prompts inside agent-run scripts.
- Refuse dirty source trees before deployment.
- Tag images by Git SHA.
- Print greppable step markers.
- Verify the deployed revision.
- Fail loudly with evidence.
- Never hide terraform plan output.
- Cleanup requires explicit human invocation and lists affected resources before deletion.

Run terraform fmt -check, terraform validate, a non-destructive plan, shell syntax checks, and security scans selected by the team.
Present the complete plan summary, add/change/destroy counts, estimated high-cost resources, stateful replacements, IAM changes, and required human actions. Do not apply.
```

Human decisions for this phase (binding on the work order above):

```text
Budget (D1, D3)
- Cumulative project budget is USD 25 (course recommendation; not an AWS-enforced cap). I hold
  USD 100 in promotional credits, but the budget stays USD 25; the credits do not raise it.
  Define an AWS Budgets budget with alerts at USD 10, 15, and 20 in Terraform. Notification target:
  a Terraform variable with no default (I supply it in an uncommitted tfvars file; commit only
  *.tfvars.example with a dummy value). .gitignore ignores *.tfvars and *.tfplan.
- Every high-cost resource in the plan summary carries an hourly and a monthly estimate, and the
  report states the expected cost of one bounded dev pipeline run and one hour of the runtime stack.

Account, Region, environment (D2, D3)
- Region us-east-1. Environment dev only (docs/agents.md section 7).
- Account: my own AWS account (course policy). Do not hard-code the account ID; read it
  from aws_caller_identity.
- The plan runs under the read-only profile scholarchain-readonly. infra-engineer runs no mutating
  AWS call. The scholarchain-admin profile is mine, for applies outside Claude; never use it.

Remote state (D4, plus the locking decision)
- S3 backend with native S3 state locking (use_lockfile), in a separate bootstrap root under
  infra/terraform/bootstrap. I apply the bootstrap by hand, once, before the first dev plan. Until I
  do, plan the dev root with a documented local-backend fallback and say so in the report.
  infra-engineer never reads state files (CLAUDE.md law 4).
- plan.sh runs `terraform plan -lock=false` under the read-only profile, because that profile cannot
  write the .tflock object. This is safe because only I apply, and I apply with locking on. plan.sh
  prints a step marker saying the plan ran unlocked. The backend keeps use_lockfile = true.

Lifecycle of expensive resources (D5-D9)
- EMR: transient clusters, created per bounded pipeline run and terminated by the run
  (auto-termination idle timeout as a backstop). No long-running cluster.
- Spot: no Spot in Phase 12 (Hadoop and Spark retry behavior on lost task nodes has not been
  tested yet). Core and task capacity stays On-Demand and small.
- Neptune: Neptune Serverless with the minimum allowed capacity and max capacity 2.5 NCU, gated by
  a Terraform toggle (enable_neptune, default false) so that it exists only while I am loading or
  querying. Neptune Serverless bills at its minimum capacity while it exists; it does not scale to
  zero. Deletion protection off and skip_final_snapshot on in dev only, which is the course
  cleanup mode; the variable files make this explicit.
- Runtime stack (ECS services, load balancer): gated by a toggle (enable_runtime, default
  false) and desired count 0 or 1 in dev.
- OpenSearch: not deployed. No hot or hybrid tier is implemented and evaluated.

Network (D10)
- No NAT gateway by default. Use VPC endpoints (S3 and DynamoDB gateway endpoints; interface
  endpoints only where a private service actually needs them: ECR, CloudWatch Logs, Secrets Manager,
  Bedrock runtime, S3 Vectors if supported). Justify each interface endpoint by its hourly cost. If a
  component cannot work without internet egress, report it and propose the cheapest option instead of
  adding a NAT gateway on your own.

Runtime compute (D11, D12)
- Consolidation: one Fargate service for the REST API (modules/services, which composes rag
  in-process) plus one worker service for asynchronous jobs, instead of seven services. Justify this
  against the "independent scaling where needed" requirement.
- Ingress: one public Application Load Balancer is the only public component; HTTP only in
  dev unless a certificate is available; the API-key authentication from Phase 10 stays on.

Models (D13; IDs and Regions verified by the orchestrator against the Bedrock model cards, 2026-11-19)
- Provider: Amazon Bedrock, us-east-1, In-Region inference (no cross-Region inference profile).
- Generation model: amazon.nova-micro-v1:0 (Converse and InvokeModel). Terraform variable
  generation_model_id with this default.
- Embedding model: amazon.nova-2-multimodal-embeddings-v1:0, 1024 dimensions (synchronous
  InvokeModel for text). Terraform variables embedding_model_id and embedding_dimension with these
  defaults. Every S3 Vectors index uses dimension 1024.
- IAM: bedrock:InvokeModel (plus bedrock:InvokeModelWithResponseStream only if a caller needs it) on
  exactly the two foundation-model ARNs
  arn:aws:bedrock:us-east-1::foundation-model/<model id>. No wildcard model ARN. The runtime role gets
  both models; the embedding-ingestion role gets the embedding model only.
- Known code mismatch, not a blocker for Phase 12: modules/rag EmbeddingModels.ProductionModelId is
  still amazon.titan-embed-text-v2:0 (1024 dimensions), and no Bedrock client exists yet (only the
  fake embedder and TemplateSynthesizer). The dimension matches. Pass the model IDs to the services
  through the existing SCHOLARCHAIN_EMBEDDING_MODEL_ID and generation-model configuration keys, and
  list the mismatch under RISKS. The orchestrator routes the constant change and the Bedrock clients
  to rag-engineer as follow-up P12-F1; do not report it as BLOCKED-ON.

deploy.sh and apply (D14)
- deploy.sh does NOT run terraform apply. I apply the approved plan by hand. deploy.sh builds
  and pushes the Git-SHA image, updates the ECS service, and verifies the deployed revision.
  (If I want deploy.sh to apply an approved plan checksum, I must record it in this phase's
  human-gate.md and factory-engineer must amend docs/agents.md section 7 first.)
```

Repository constraints for infra-engineer:

```text
- Derive resources from code that exists, not from the specification alone. Read the configuration
  contract in modules/core, the storage adapters and the AWS store factory in modules/storage, the
  S3 Vectors indexes and embedding dimensions used by modules/rag and modules/storage, and the
  services runtime composition in modules/services. Name every DynamoDB table, index, vector index,
  bucket prefix, and environment variable after what that code reads. Report any mismatch as
  BLOCKED-ON the owning agent instead of changing application code.
- scripts/run-bounded-pipeline.sh already exists (Phase 04, EMR smoke run over the sample mirror,
  reads SCHOLARCHAIN_EMR_CLUSTER_ID and related variables). Extend it for the Phase 12 transient
  cluster and the Spark stages; keep its existing bounds, step markers, and read-only preflight.
  Do not silently drop behavior.
- Phase 12 scripts are plan.sh, deploy.sh, rollback.sh, smoke-test.sh, run-bounded-pipeline.sh, and
  cleanup.sh. check.sh, package.sh, run-experiment.sh, and archive-evidence.sh belong to Phase 13
  unless deploy.sh cannot work without one of them; if so, say which and why.
- Every script that takes an environment takes it as the first positional argument and refuses
  anything other than dev (docs/agents.md section 7 script interface contract).
- Pin the AWS provider to a version whose S3 Vectors resources you verified in the Terraform
  registry. If the terraform MCP server is unavailable, say so and cite the source you used.
- No secret values, account IDs, or personal email addresses in committed files. Commit only
  *.tfvars.example files with dummy values.
- Security scan: checkov, run through uvx (`uvx checkov -d infra/terraform`), with no install.
  Report every failed check. For each one, fix it or justify a skip inline (`#checkov:skip=<ID>:<reason>`)
  and list the skips in the report. If uvx cannot fetch checkov, say so; do not substitute another tool
  silently.
- Respect the guard limits: keep commands to about 6 segments, and run fmt, validate, plan, and
  bash -n one at a time.
- Return the docs/agents.md section 11 report with the plan summary: add/change/destroy counts, every
  stateful resource and whether any replacement occurs, every IAM role with its actions and resource
  ARNs, every public-facing resource, high-cost resources with estimates, the security-scan tool and
  its findings, and the manual actions I must take.
```

Use this work order for review:

```text
Use the code-reviewer agent to perform an adversarial read-only review of the Phase 12 diff
(infra/terraform/** and scripts/**) and the plan output infra-engineer reported.

Review in this order:
1. Public exposure of buckets, databases, vector stores, and services.
2. IAM scope: wildcard actions or resources, runtime and deployment separation, the Neptune loader
   role versus the read-only role, the Bedrock model scope.
3. Secrets in source, variables, outputs, or plan output.
4. Unbounded or expensive defaults against the USD 25 budget (NAT, Neptune, EMR, ECS, interface
   endpoints, log retention).
5. Stateful resources: deletion protection, PITR, versioning, and any destroy or replacement.
6. Drift between Terraform names and what the application code reads.
7. Script contract: strict mode, no prompts, dirty-tree refusal, Git-SHA tags, step markers,
   revision verification, environment refusal, cleanup listing before deletion.
8. Ownership: no file outside infra/terraform/** and scripts/** changed.

Verify every candidate finding against the actual files before reporting it.
For each finding provide severity, file and line, concrete failure input, expected impact, and owning agent.
Return APPROVE or REQUEST_CHANGES. Do not edit any file.
```

Gate:

- Terraform plan contains no unexplained destroy or replacement of stateful resources.
- IAM policies are scoped to actual resources and actions.
- Buckets and databases are not public.
- Default capacity is bounded.
- Secrets do not appear in source or plan output.
- Human reviews and signs the plan before any apply.
- Code reviewer approves the infrastructure diff.

*pasted pre-delegation decisions
## Phase 12 pre-delegation decisions

D1. Budget (prompt.md line 125)
    Options: (a) USD 25 cumulative, AWS Budgets alerts at 10/15/20, alert email set in an uncommitted tfvars (rec)
             (b) different cap or thresholds: ____
    Decision: A
    Alert email goes in a local tfvars only, never committed: [ ] confirmed

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
    Embedding model ID and dimension (must match modules/rag): amazon.nova-2-multimodal-embeddings-v1.0
    Bedrock pricing and credit eligibility checked: [X] yes

D14. Apply path (line 176)
    Options: (a) deploy.sh never runs terraform apply; I apply by hand (rec)
             (b) deploy.sh applies an approved plan checksum (needs a factory-engineer amendment to agents.md section 7 first)
    Decision: A

    Additonally: For Terraform's lock in S3 backend, have (A) plan.sh runs terraform plan -lock=false under the read-only profile. That's safe because only you ever apply, and you apply with locking on.

Sign-off: all D1-D14 decided; [DECIDE] markers and the draft comment removed from prompt.md.  Date: 11-19-26

