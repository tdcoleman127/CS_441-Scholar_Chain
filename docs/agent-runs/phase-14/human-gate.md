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



Step 1b-0 approved and applied, plan checksum ---, result 2 added / 0 changed / 0 destroyed;
the deployment role is trusted to the ScholarChainAdmin SSO role;
you applied it manually with scholarchain-admin.
