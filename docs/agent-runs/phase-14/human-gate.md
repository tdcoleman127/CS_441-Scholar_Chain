Recommended: fixture mode for Phase 14. The spec explicitly allows "bounded fixtures". infra-engineer adds a runtime_mode variable (default aws), you set fixture for now, and the smoke test can pass with REQUIRE_READY=1. You switch back to aws after Phase 15 loads data.

Recommended for the 2-day deadline: you run deploy.sh and smoke-test.sh yourself in Git Bash with the admin profile. deploy-engineer then verifies read-only and writes the deployment report. Record this as a manual intervention.

C. Cost. enable_runtime=true requires four interface endpoints (ECR api, ECR dkr, logs, Secrets Manager) in 2 AZs, plus the ALB and a Fargate task. My rough estimate is USD 0.10-0.20 per hour while on; that's from memory and not verified. Run it in sessions and turn it off between Phases 14/15 work.



Phase 14, Step 1a. Read docs/agent-runs/phase-14/human-gate.md and docs/project-description.md
"Phase 14" first. Delegate; do no specialist work inline.

infra-engineer: add a fixture runtime mode to the dev Terraform so the Phase 14 smoke test can pass
with SCHOLARCHAIN_SMOKE_REQUIRE_READY=1 before Phase 15 loads real data. Human decision: fixture mode
now, back to aws after Phase 15.

Facts:
- modules/services Main.scala already accepts SCHOLARCHAIN_RUNTIME_MODE=fixture|aws (LocalFixture is
  the synthetic release: Alice Adams, Bob Brown, ...).
- infra/terraform/envs/dev/main.tf:177 hardcodes SCHOLARCHAIN_RUNTIME_MODE = "aws".
- enable_runtime=true requires image_tag (a pushed 40-hex SHA), release_run_id (run:v1:<64 hex>), and
  every runtime_algorithm_env key (variables.tf:160-191).

Do:
1. Add variable runtime_mode (string, default "aws", validation: exactly "fixture" or "aws") and wire it
   to SCHOLARCHAIN_RUNTIME_MODE. No other behavior change when it is "aws".
2. Update dev.tfvars.example with runtime_mode and a comment: fixture is for Phase 14 only, before
   real data is loaded.
3. Read Main.scala, LocalFixture.scala, and core's ConfigLoader read-only and state exactly what fixture
   mode needs:
   - Is release_run_id still required, and which value is correct (the fixture run ID, or a dummy
     that matches the regex)?
   - Which runtime_algorithm_env keys does the process load, and which values match the fixture?
   - Does /readyz return 200 in fixture mode?
   Do not edit modules/**. If the services need a change, return BLOCKED-ON service-engineer with
   the evidence.
4. Bootstrap order. Give the exact human commands to:
   - push a starting image with scripts/package.sh --push (SCHOLARCHAIN_PUSH_PROFILE,
     SCHOLARCHAIN_AWS_REGION, SCHOLARCHAIN_ECR_REPOSITORY_NAME from terraform output runtime);
   - confirm the ECR repository already exists from Phase 12;
   - put an API key digest with the query:write and evidence:read scopes into the
     scholarchain/dev/api-key-digests secret (format; no real values).
5. Give the exact lines I must set in my local dev.tfvars:
   - enable_runtime = true, runtime_mode = "fixture", image_tag, release_run_id, runtime_algorithm_env;
   - interface_endpoints ecr_api, ecr_dkr, logs, secretsmanager = true;
   - interface_endpoint_az_count, api_desired_count = 1, worker_desired_count = 0.
   Do not edit dev.tfvars yourself.
6. Run terraform fmt -check, terraform validate, and bash scripts/check.sh --skip-sbt. Do not run plan
   yet (dev.tfvars is not set).

Report in the agent report contract. Orchestrator: save the report as
docs/agent-runs/phase-14/part-a-infra-engineer.md. Then run code-reviewer on the diff. Stop and give me
the bootstrap commands and the tfvars lines.


Phase 14, Step 1b. Read docs/agent-runs/phase-14/part-a-infra-engineer.md first. I have pushed image
<40-hex SHA>, set the API key digest, and set dev.tfvars (enable_runtime=true, runtime_mode="fixture").

infra-engineer: run bash scripts/plan.sh dev. Report:
- plan_sha256 and the plan file path;
- add/change/destroy counts, with stateful resources and any destroy or replacement first (explain
  each one);
- IAM changes;
- confirmation that SCHOLARCHAIN_RUNTIME_MODE=fixture is in the planned task definition;
- an hourly and per-session cost estimate for the interface endpoints (count × AZs), the ALB, and one
  Fargate task, each figure marked verified (with source) or estimated.
Do not apply.

Orchestrator: save the report as docs/agent-runs/phase-14/plan-infra-engineer.md. Run code-reviewer on
the plan output. Stop and present the checksum, the counts, and the cost for my Step 3 gate.

## Phase 14 human sign-off (2026-11-24)

### Step 1a: runtime_mode fixture variable
- Accepted. code-reviewer APPROVE. Committed in ef95a6b.
- Decision: fixture mode for Phase 14 only; set runtime_mode back to "aws" after Phase 15 loads real data.

### Step 1b-0: deployment role
- Approved and applied by the human outside Claude with scholarchain-admin.
- Plan sha256 f031d9c9ed5a5f827d5774040c603a0e6be7d7800c69c88600ba6b90a3f0e26a; 2 added, 0 changed, 0 destroyed. code-reviewer APPROVE.
- scholarchain-dev-deployment is trusted to the ScholarChainAdmin SSO role only. Reason: package.sh refuses admin-named push profiles. Bootstrap image ef95a6b5d14a3e911420c5b37112d7a73a2eaf37 pushed with scholarchain-deploy.

### Step 1b: runtime plan (fixture mode)
- Plan dev-ef95a6b5d14a-20261124T074204Z.tfplan, sha256 8ce269bf42fb54f925bef35a5c990ac2529ea4d7e85ec320f8343089b9fa3668.
- 17 to add, 1 to change, 0 to destroy; no IAM changes; no stateful actions. code-reviewer APPROVE (one minor report correction).
- Target: account ending 9340, us-east-1, environment dev.
- Cost accepted: about USD 0.10-0.11 per hour while on (estimated). Turn off with enable_runtime = false AND the four interface endpoints false.
- Public exposure: ACCEPTED for short fixture sessions (ALB 0.0.0.0/0, HTTP:80; API key required for /v1; synthetic data).

APPROVED-DEPLOY-PLAN-SHA256: 8ce269bf42fb54f925bef35a5c990ac2529ea4d7e85ec320f8343089b9fa3668 env=dev

### Manual interventions
- Human applied plans outside Claude with scholarchain-admin.
- Human wrote the API key digest secret and pushed the bootstrap image with package.sh --push.
- Human ran terraform fmt on the local dev.tfvars after Step 1b attempt 1 failed the fmt check.
- Human runs deploy.sh (scholarchain-deploy profile) and smoke-test.sh in Git Bash; deploy-engineer verifies read-only and writes the deployment report.

### Phase 14 results
- Step 1b applied by the human with scholarchain-admin: Apply complete! Resources: 17 added, 1 changed, 0 destroyed.
- deploy.sh dev (human, scholarchain-deploy profile): RESULT status=DEPLOYED, commit 0c827107c5a0d8271619b013f81e5e9fa47b896a, digest sha256:12049aababb263f53bffaae3eaa7d271cae18a15ce3c85fe7a94d2bfd8db94a2, scholarchain-dev-api:2, scholarchain-dev-worker:2.
- smoke-test.sh dev (human; REQUIRE_READY=1; fixture question "Who are the coauthors of Alice Adams?"): 9/9 PASS, RESULT status=PASSED. Answer is synthetic fixture data.
- deploy-engineer read-only verification: healthy and ready, digest matches, 0 alarms in ALARM. Report: reports/deployments/2026-11-24-dev-phase-14.md.
- Rollback: none needed. Rollback base api:1 / worker:1.
- Runtime left ON (about USD 0.10-0.11/h, estimated) for Phase 15; turned off at final cleanup.
- Known limitation found during Phase 14: aws runtime mode has no live Neptune adapter (UnavailableGraphStore), no S3 Vectors retriever, and a template synthesizer; fixture mode is the deployed configuration.
- Phase 14 gate: ACCEPTED.

- Reviewed: deploy report, logs, Phase 16 offline results, Phase 17 drafts. code-reviewer rounds 1 (REQUEST_CHANGES, 1 major / 6 minor), 2 (REQUEST_CHANGES, 2 minor), 3 (APPROVE). See review-01..03.md.
- Remaining risks: ALB is public on HTTP:80 while on; full account ID already in committed history (docs/agent-runs/phase-00-09-review.md), accepted because the repository is private; Phase 16 offline run approved here as $0 local work.
- Accepted commit: recorded in commit.txt.