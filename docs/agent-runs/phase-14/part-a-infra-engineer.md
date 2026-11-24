# Phase 14, Step 1a: infra-engineer report

Assembled by the orchestrator from infra-engineer's final report (verbatim below). Work order: docs/agent-runs/phase-14/human-gate.md lines 9-52.

```text
STATUS: COMPLETE

STATEFUL REPLACEMENTS OR DESTROYS:
None from this diff. No stateful resource is touched. The change only alters a local value that feeds the ECS task definition's container_definitions. With the default runtime_mode = "aws" the rendered value is still "aws" (asserted by the new test run "default_is_aws"), so the change on its own should give an empty plan. That is inferred from the code and the test; I did not run a plan (the work order said not to).
Later operational note: switching runtime_mode (aws to fixture, or back after Phase 15) replaces aws_ecs_task_definition. Task definitions hold no data, and the ECS services ignore task_definition drift (modules/runtime/main.tf:293-295). So after the switch-back apply, deploy.sh has to roll the new revision out. deploy.sh copies the Terraform base revision, so the mode value goes with it.

OWNER: infra-engineer

WORK ORDER: Phase 14, Step 1a (docs/agent-runs/phase-14/human-gate.md lines 9-52). Add a fixture runtime mode to the dev Terraform, answer the fixture-mode questions, give the bootstrap commands and the dev.tfvars lines, and run fmt, validate, and check.sh --skip-sbt. No plan, no apply.

FILES CREATED:
- infra/terraform/envs/dev/tests/runtime_mode.tftest.hcl (terraform test, plan-only against a mocked AWS provider: no credentials, no AWS call, no backend, no state. 5 runs: default is aws, fixture is wired, and "Fixture", "", and "local" are rejected.)

FILES MODIFIED:
- infra/terraform/envs/dev/variables.tf (new variable runtime_mode: string, default "aws", validation contains(["aws","fixture"]))
- infra/terraform/envs/dev/main.tf (SCHOLARCHAIN_RUNTIME_MODE = var.runtime_mode, previously hardcoded "aws"; added a comment on what fixture mode reads)
- infra/terraform/envs/dev/dev.tfvars.example (runtime_mode = "aws", with a comment that fixture is for Phase 14 only, before real data is loaded)
- scripts/check.sh (steps 6 and 7 now also run `terraform test` in the same temporary TF_DATA_DIR when a root has tests/*.tftest.hcl; they print a TF-TEST marker, and a test failure fails the gate. Step numbering is unchanged, and the gate still makes no AWS call.)
Not touched: modules/**, dev.tfvars, and docs/agent-runs/** (the human's own edits to phase-14 prompt.md and human-gate.md are still uncommitted in the tree).

COMMANDS RUN (all output seen):
1. Read-only AWS (profile scholarchain-readonly, us-east-1):
   - `aws ecr describe-repositories --repository-names scholarchain-dev-services ...` returned `scholarchain-dev-services IMMUTABLE KMS`.
   - `aws ecr describe-images ... --query 'length(imageDetails)'` returned `0`. The repository has no image yet.
   - `aws secretsmanager describe-secret --secret-id scholarchain/dev/api-key-digests --query VersionIdsToStages` returned one version with AWSCURRENT. A value exists, but its content is unknown: I cannot read it and did not try.
   - `aws iam get-role --role-name scholarchain-dev-deployment` returned NoSuchEntity. The deployment role does not exist, so deployer_principal_arns is empty in the applied config.
2. Local hashing in the scratchpad to compute LocalFixture.RunId: `run:v1:079cb5d32f20d399305c3f95b3c7030611f398011b92347effe139561078f7fc`. I checked the encoder against core's golden vector (CommunityIdSuite.scala:24-29) and it reproduced `cocomm:v1:7411f02d...4431` exactly.
3. `terraform fmt -check -recursive -diff -no-color infra/terraform`: no output, fmt_exit=0.
4. `terraform test` (envs/dev, temporary TF_DATA_DIR, init -backend=false -lockfile=readonly -plugin-dir=local cache): "Success! 5 passed, 0 failed."
5. Negative control: with main.tf temporarily set back to the hardcoded "aws", terraform test gave "Failure! 4 passed, 1 failed" (run "fixture_is_wired": actual "aws", expected "fixture"). main.tf was then restored.
6. `bash -n scripts/check.sh`: exit 0.
7. `bash scripts/check.sh --skip-sbt` (run twice; final run): exit 3. Steps 0-7 and 9 OK; step 6 "Success! The configuration is valid." with TF-TEST NONE; step 7 "Success! The configuration is valid." and "Success! 5 passed, 0 failed.", TF-TEST PASS; step 8 SKIPPED; step 9 tree unchanged. Final line: `[check] RESULT status=INCOMPLETE git_sha=d9f182f4bd28ed26dec8280b6e19052e0efd2890 failed=none skipped=8 (not the definition of done)`.
Not run: terraform plan (as instructed), apply, sbt check, and any deploy, smoke, package, or pipeline script.

TEST RESULTS: terraform test for envs/dev passed 5 of 5. With the wiring reverted it fails 1 of 5, so the test does catch the change. sbt was not run: no Scala source changed, and the work order asked for --skip-sbt.

VALIDATION RESULTS: fmt -check passed. terraform validate passed for bootstrap and envs/dev (check.sh steps 6 and 7). bash -n passed on every script (check.sh step 3). validate-agent-files passed (step 1). Security scan: none was run. checkov, tfsec, and trivy are not installed (`command -v` exit 1). The diff adds no resource, IAM, or network change.

PLAN SUMMARY (add/change/destroy, plan checksum): Not run, per the work order (dev.tfvars is not set yet). That is Step 1b.

IAM CHANGES: None.

ESTIMATED HIGH-COST RESOURCES: None from this diff. Turning on the runtime (Step 1b) adds 4 interface endpoints, the ALB, and 1 Fargate task. The repository's own comment (modules/network/main.tf:148) says USD 0.01/h per endpoint per AZ; I have not verified that figure. Using interface_endpoint_az_count = 1 instead of 2 halves the endpoint cost (see item 5).

SCRIPT GATES ENCODED: check.sh steps 6 and 7 now run `terraform test` (mock_provider, no credentials, no AWS call) whenever a root has tests/*.tftest.hcl. A test failure fails the gate with the marker `TF-FAIL root=<root> step=test exit=<rc>`. On success it prints `TF-TEST root=<root> status=PASS|NONE`. The validate failure path now returns before the tests run.

REQUIRED ENVIRONMENT VARIABLES (names only):
- package.sh --push: SCHOLARCHAIN_PUSH_PROFILE, SCHOLARCHAIN_AWS_REGION, SCHOLARCHAIN_ECR_REPOSITORY_NAME. AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY, and AWS_SESSION_TOKEN must be unset.
- Secret write: AWS_PROFILE (the human's write-capable profile).
- smoke-test.sh: SCHOLARCHAIN_AWS_REGION, SCHOLARCHAIN_SMOKE_BASE_URL, SCHOLARCHAIN_SMOKE_API_KEY, SCHOLARCHAIN_SMOKE_REQUIRE_READY, SCHOLARCHAIN_SMOKE_QUESTION (required for fixture mode, see 3c), and SCHOLARCHAIN_SMOKE_EXPECTED_SHA (optional; see 3c).

FACT CHECK OF THE WORK ORDER
- Fact 1 is correct. Main.scala accepts fixture or aws (Main.scala:105-108), and there is no default: anything else is the error "SCHOLARCHAIN_RUNTIME_MODE must be fixture or aws" (Main.scala:108).
- Fact 2 is correct. main.tf:177 hardcoded `SCHOLARCHAIN_RUNTIME_MODE = "aws"` before this change. Line numbers have now shifted by 2.
- Fact 3 is correct, with two clarifications. The enable_runtime validations are in variables.tf:160-193 (before this change). (a) They check shape only. image_tag is regex `^[0-9a-f]{40}$` (173-176); Terraform cannot check that the image was actually pushed. runtime_algorithm_env is checked for key presence only (183-192); values are not checked. (b) enable_runtime also requires the four interface endpoints ecr_api, ecr_dkr, logs, and secretsmanager (165-171).
- Item 4's premise is partly wrong. `terraform output runtime` contains ecr_repository_name only (outputs.tf:43-58). The region is in `terraform output scholarchain_env` (outputs.tf:12). SCHOLARCHAIN_PUSH_PROFILE is not a Terraform output at all. package.sh also refuses any push profile whose name contains "admin" (package.sh:208-210), the read-only profile (194-197), and static credentials in the environment (199-201). So the "admin profile" plan in human-gate.md line 3 cannot be used for package.sh --push (see item 4, step C). deploy.sh has no such admin-name check.

=====================================================================
ITEM 3. WHAT FIXTURE MODE NEEDS (read-only analysis; file:line cited)
=====================================================================

3a. Is release_run_id still required in fixture mode, and which value is correct?
- The service does not read it in fixture mode. The fixture branch is `case Some("fixture") => Right(LocalFixture.build().dependencies)` (Main.scala:106). SCHOLARCHAIN_RELEASE_RUN_ID is read only inside AwsComposition.dependencies (Main.scala:61-62), and that runs only for "aws" (Main.scala:107).
- Terraform still requires it whenever enable_runtime = true: regex `^run:v1:[0-9a-f]{64}$` (variables.tf:178-181).
- Correct value: the fixture's own run ID, LocalFixture.RunId (LocalFixture.scala:36-37, `StableIds.runId("services-local-fixture", "fixture-v1", "sha256:" + "0"*64, Nil, 1)`). That is the release the process actually serves, and it logs it at startup as `releaseRun` (Main.scala:139, from deps.facts.releaseRunId). Using it keeps the task definition's SCHOLARCHAIN_RELEASE_RUN_ID, deploy.sh's PROVENANCE marker, and the service.started log consistent. A made-up value that matches the regex would also start the service, but it would misstate provenance.
- Value: `run:v1:079cb5d32f20d399305c3f95b3c7030611f398011b92347effe139561078f7fc`
  I computed this outside Scala from the documented formula: StableIds.runId (StableIds.scala:298-314), hashed (StableIds.scala:52-53, prefix "run", version "v1"), and CanonicalHash length-prefixed SHA-256 (CanonicalHash.scala:11-46). The same encoder reproduced core's golden community ID exactly. After deploy, the human can confirm it in the api log event `service.started` (field releaseRun).

3b. Which runtime_algorithm_env keys does the process load in fixture mode, and which values match the fixture?
- None. ConfigLoader.load is called only in AwsComposition.dependencies (Main.scala:59). In fixture mode the process reads only:
  - SCHOLARCHAIN_SVC_* (ServiceSettings.fromEnv, Main.scala:103; ServiceSettings.scala:50-146),
  - SCHOLARCHAIN_API_KEYS (Main.scala:104; Auth.scala:64),
  - SCHOLARCHAIN_RUNTIME_MODE (Main.scala:105).
- Terraform still requires all nine keys to be present (variables.tf:183-192). The values below match the fixture where it has a counterpart. Where it does not, they satisfy ConfigLoader's checks (ConfigLoader.scala:186-202), in case the mode is flipped back to aws with the same map:
  - SCHOLARCHAIN_GRAPH_WINDOW_START_YEAR = "2015" and SCHOLARCHAIN_GRAPH_WINDOW_END_YEAR = "2020": LocalFixture.Window (LocalFixture.scala:33).
  - SCHOLARCHAIN_GRAPH_POLICY_VERSION = "coauthor-policy-v1": LocalFixture.Policy (LocalFixture.scala:34).
  - SCHOLARCHAIN_BRIDGE_ALGORITHM = "label-propagation-v1-synthetic": LocalFixture.Algorithm (LocalFixture.scala:35). This is the algorithm version on the fixture's community and graph-metrics rows. The fixture has no separate bridge algorithm; carol and dave are bridges by construction (LocalFixture.scala:28-30, 121).
  - SCHOLARCHAIN_BRIDGE_MAX_ITERATIONS = "10": no fixture counterpart; it must be at least 1 (ConfigLoader.scala:199).
  - SCHOLARCHAIN_BRIDGE_MIN_EDGE_WEIGHT = "1.0": the smallest fixture edge weight is 1 (frank-gina, LocalFixture.scala:84), so 1.0 excludes no fixture edge. It must be finite and at least 0 (ConfigLoader.scala:200-202).
  - SCHOLARCHAIN_ER_POLICY_VERSION = "er-policy-v5", SCHOLARCHAIN_ER_AUTO_MATCH_THRESHOLD = "0.85", SCHOLARCHAIN_ER_REVIEW_THRESHOLD = "0.55": the fixture has no entity-resolution data. These are the nearest counterparts in modules/entity-resolution/src/main/resources/scholarchain/er-policy-v5.conf:61-71 (policy-version, accept 0.85, reject 0.55). They satisfy [0,1] and review <= auto (ConfigLoader.scala:190-196). The real mapping for Phase 15 belongs to entity-resolution-engineer and the release's versioned configuration.

3c. Does /readyz return 200 in fixture mode, and would smoke-test.sh with SCHOLARCHAIN_SMOKE_REQUIRE_READY=1 pass?
- /readyz should return 200. Router returns 200 only when every check passes (Router.scala:127-134). The checks are in ServicesApp.scala:150-184:
  - api-keys: the authenticator is non-empty. ApiKeys.parse rejects an empty array (Auth.scala:81), so a process that started at all passes.
  - graph-facts: the fixture release has 7 researchers (LocalFixture.scala:50-58, 102-103).
  - graph-store: InMemoryGraphStore over the fixture edges (LocalFixture.scala:170), probed with directCoauthors in the 2015-2020 window.
  - evidence-store: InMemoryKeyValueStore (LocalFixture.scala:171).
  The existing unit test AuthApiSuite.scala:68 asserts /readyz 200 over LocalFixture (TestKit.scala:98-117). This is verified by reading the code and an existing test, not by a deployed run.
- smoke-test.sh with REQUIRE_READY=1 and its DEFAULT question would FAIL at check 7:
  - The default question is "Who are the coauthors of Ada Sample 0001?" (smoke-test.sh:112). Ada Sample 0001 is not in LocalFixture (names at LocalFixture.scala:50-58).
  - An unknown researcher gets 200 ABSTAINED (reason unknown-researcher) with an empty claims array (QueryApiSuite.scala:142-150). Checks 4, 5, 6, and 8 pass on that.
  - Check 7 finds no evidence:v1 ID in "claims". Because ready=1, the not-ready exemption does not apply (smoke-test.sh:167, 243-254), so check 7 is FAIL.
- Fix, with no code change: set `SCHOLARCHAIN_SMOKE_QUESTION="Who are the coauthors of Alice Adams?"`. It matches the script's question regex (smoke-test.sh:113-114). With that question:
  - checks 1-2: /healthz 200 (Router.scala:138-143), /readyz 200 (above).
  - check 3: no credentials gives 401 UNAUTHENTICATED (Auth.scala:46).
  - check 4: 200 ANSWERED, every claim VERIFIED with evidenceIds (QueryApiSuite.scala:8-30). The smoke body's extra fields are within bounds: mode synchronous; graphLimit 5 (max 100, QueryRequest.scala:185, GraphQueries.scala:63); maxPathDepth 2 (max 4, QueryRequest.scala:189); vectorLimit 5 (max 10, QueryRequest.scala:187, SemanticContext.scala:71). Fixture mode has no semantic wiring (Dependencies.semantic defaults to None, ServicesApp.scala:27; LocalFixture.scala:172), so the vector stage is simply off (Workflow.scala:61-64).
  - check 5: idempotent replay returns 200 with Idempotent-Replayed: true and the same queryId (QueryService.scala:139-160; Router.scala:113).
  - check 6: a different graphLimit changes requestHash (QueryRequest.scala:44-46), giving 409 IDEMPOTENCY_CONFLICT (QueryService.scala:140-141).
  - check 7: GET /v1/evidence/{id} returns 200 with the same evidenceId (EvidenceLifecycleSuite.scala:12-40). Evidence IDs have the form evidence:v1:<64 hex>, and "claims" precedes "overallStatus" in QueryResponse (ApiModels.scala:61-71), as the script's extraction expects.
  - check 8: an unknown field gives 400 INVALID_FIELD (QueryApiSuite.scala:89).
  - check 9: the running image tag must equal SCHOLARCHAIN_SMOKE_EXPECTED_SHA, which defaults to `git rev-parse HEAD` (smoke-test.sh:110, 271-278). After deploy.sh (which builds and pushes HEAD) this matches. If the smoke test runs right after apply and before deploy.sh, set SCHOLARCHAIN_SMOKE_EXPECTED_SHA to the image_tag value.
  - Request budget: 4 POSTs plus 1 authenticated GET, within burst 10 (ServiceSettings.scala:31-32).
- The services do not need a change, so this is not BLOCKED-ON service-engineer.

3d. Does fixture mode still need the API key digest secret, and which scopes does the smoke test use?
- Yes. SCHOLARCHAIN_API_KEYS is parsed before the mode branch (Main.scala:104). A missing or invalid value logs service.config-invalid and exits 2 (Main.scala:111-117). ECS injects it from the secret by ARN (modules/runtime/main.tf:30, 42).
- Scopes: query:write for POST /v1/query (Router.scala:91; smoke checks 4, 5, 6, 8) and evidence:read for GET /v1/evidence/{id} (Router.scala:177-183; smoke check 7). smoke-test.sh:45-46 says the same.

=====================================================================
ITEM 4. BOOTSTRAP ORDER AND EXACT HUMAN COMMANDS (Git Bash, run by the human outside Claude)
=====================================================================

Order:
1. Review and commit (code-reviewer first, then the human). package.sh refuses a dirty tree (package.sh:230-234), and the image tag is the HEAD SHA. The tree currently also has the human's uncommitted phase-14 prompt.md and human-gate.md edits.
2. Confirm the ECR repository exists (read-only).
3. Put the API key digest into the secret.
4. Push the starting image with package.sh --push.
5. Set dev.tfvars (item 5).
6. Step 1b: infra-engineer runs plan.sh dev; the human approves and applies.
7. deploy.sh and smoke-test.sh, with SCHOLARCHAIN_SMOKE_QUESTION set (3c).

Why the image and the secret must come before apply: with enable_runtime = true and api_desired_count = 1, apply registers the task definition with `<repo>:<image_tag>` and creates the api service (modules/runtime/main.tf:177-197, 262-298), and ECS starts a task immediately.
- A missing tag makes the task fail to pull its image (CannotPullContainerError), and the deployment circuit breaker (modules/runtime/main.tf:288-291) marks the first deployment failed with nothing to roll back to.
- At task start ECS reads the secret by ARN. A secret value without a valid entry makes the process exit 2 (Main.scala:104, 111-117).
- The secret and the push do not depend on each other.

A. Confirm the ECR repository exists (read-only). I ran this today: `scholarchain-dev-services IMMUTABLE KMS`, 0 images.
    aws ecr describe-repositories --repository-names scholarchain-dev-services \
      --profile scholarchain-readonly --region us-east-1 \
      --query 'repositories[0].[repositoryName,imageTagMutability]' --output text
    # expected: scholarchain-dev-services   IMMUTABLE

B. API key digest secret
- Exact JSON format (Auth.scala:74-116):
  `[{"principal":"<label>","sha256":"<64 lowercase hex>","scopes":["query:write","evidence:read"]}]`
  - 1 to 100 entries.
  - Each entry has exactly the keys principal, sha256, and scopes; any other key is rejected.
  - principal matches `^[a-z0-9][a-z0-9-]{0,63}$`.
  - sha256 matches `^[0-9a-f]{64}$`.
  - scopes come from query:write, query:read, evidence:read, graph:read, ops:metrics.
  - Principals must be unique, and so must digests.
- Digest algorithm (Auth.scala:70-72 and CanonicalHash.scala:39-44): the lowercase hex SHA-256 of the token's UTF-8 bytes, with no trailing newline. The token must match `^[A-Za-z0-9._~-]{32,256}$` (Auth.scala:66), and the service trims the presented token (Auth.scala:52). The image also has `hash-api-key`, which reads the token on stdin and prints this digest (Main.scala:88-93).
- The secret already has an AWSCURRENT version with unknown content (observed). put-secret-value replaces the current value, and the old one becomes AWSPREVIOUS. Include any other entries you still need in the same array.
    # Human only, outside Claude. <placeholders> in angle brackets. No real key appears here.
    export AWS_PROFILE=<your-write-capable-profile>      # needs secretsmanager:PutSecretValue on the secret and use of the CMK
    umask 077
    KEYDIR="$HOME/.scholarchain-local"                    # outside the repository; never commit
    mkdir -p "$KEYDIR"
    openssl rand -hex 32 > "$KEYDIR/dev-smoke-api-key"    # plaintext key: 64 hex chars, matches TokenShape; keep for SCHOLARCHAIN_SMOKE_API_KEY
    DIGEST="$(tr -d '\r\n' < "$KEYDIR/dev-smoke-api-key" | sha256sum | cut -d' ' -f1)"
    printf '[{"principal":"smoke-client","sha256":"%s","scopes":["query:write","evidence:read"]}]' "$DIGEST" > "$KEYDIR/api-key-digests.json"
    aws secretsmanager put-secret-value --region us-east-1 --cli-connect-timeout 10 --cli-read-timeout 30 \
      --secret-id scholarchain/dev/api-key-digests \
      --secret-string "file://$(cygpath -m "$KEYDIR/api-key-digests.json")"
    rm -f "$KEYDIR/api-key-digests.json"; unset DIGEST
    # Read-only check that a new AWSCURRENT version exists (no value is read):
    aws secretsmanager describe-secret --secret-id scholarchain/dev/api-key-digests \
      --profile scholarchain-readonly --region us-east-1 --query VersionIdsToStages
    # Later, for the smoke test:
    # export SCHOLARCHAIN_SMOKE_API_KEY="$(tr -d '\r\n' < "$HOME/.scholarchain-local/dev-smoke-api-key")"
(`cygpath -m` is needed because aws.exe is a Windows program and does not understand a /c/... path inside file://.)

C. Push the starting image (package.sh --push)
- Prerequisites: Docker engine running; sbt and Python 3.8+ on PATH; a clean tree.
- SCHOLARCHAIN_PUSH_PROFILE must name a profile that can push to scholarchain-dev-services. Its name must not contain "admin" and it must not be scholarchain-readonly (package.sh:194-210). The deployment role scholarchain-dev-deployment does not exist (observed NoSuchEntity), so you have two choices; this is your decision:
  (i) An existing non-admin profile of yours that has ECR push permissions on this repository.
  (ii) Least privilege: add your principal to deployer_principal_arns. That creates scholarchain-dev-deployment, whose push rights cover this repository only (modules/iam/main.tf:744-847). Then add a CLI profile with role_arn set to that role and source_profile set to your login profile. This is an IAM change that needs its own plan and apply BEFORE the push, so it means one extra apply cycle.
  Do not rename an admin profile to get past the check; that would defeat the gate.
    cd "/c/Users/figge/OneDrive/Desktop/UIC Folder/CS 441/CS_441-Scholar_Chain"
    git status --porcelain            # must print nothing
    git rev-parse HEAD                # this 40-hex SHA becomes image_tag
    cd infra/terraform/envs/dev       # terraform output reads state: use the profile you use for Terraform
    terraform output -json runtime    # shows ecr_repository_name (expected scholarchain-dev-services)
    export SCHOLARCHAIN_ECR_REPOSITORY_NAME="$(terraform output -json runtime | python -c "import json,sys; sys.stdout.write(json.load(sys.stdin)['ecr_repository_name'])")"
    export SCHOLARCHAIN_AWS_REGION="$(terraform output -json scholarchain_env | python -c "import json,sys; sys.stdout.write(json.load(sys.stdin)['SCHOLARCHAIN_AWS_REGION'])")"
    cd ../../../..
    export SCHOLARCHAIN_PUSH_PROFILE=<non-admin-profile-that-can-push-to-scholarchain-dev-services>
    unset AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN
    bash scripts/package.sh --push
    # expect: [package] REVISION git_sha=<sha> ... ; [package] IMAGE-REGISTRY repository=scholarchain-dev-services tag=<sha> digest=sha256:... pushed=true ; [package] RESULT status=OK ... pushed=true
    aws ecr describe-images --repository-name scholarchain-dev-services --image-ids imageTag=<sha> \
      --profile scholarchain-readonly --region us-east-1 --query 'imageDetails[0].imageDigest' --output text
(`sys.stdout.write` avoids the trailing CR that Windows Python's print adds, which would fail package.sh's name regex. The values are fixed by the code anyway: scholarchain-dev-services (locals.tf:53) and us-east-1 (variables.tf:7).)

Note: deploy.sh needs the APPROVED-DEPLOY-PLAN-SHA256 line committed in a human-gate.md, so HEAD will move after this push. deploy.sh then builds and pushes the new HEAD and registers revisions copied from the Terraform base revision, so runtime_mode = fixture carries over. image_tag in dev.tfvars can stay at the bootstrap SHA.

=====================================================================
ITEM 5. LINES TO SET IN YOUR LOCAL dev.tfvars (I did not read or edit it)
=====================================================================
    enable_runtime = true
    runtime_mode   = "fixture" # Phase 14 only; set back to "aws" after Phase 15 loads data
    image_tag      = "<40-hex SHA pushed by package.sh --push (its REVISION git_sha=...)>"
    release_run_id = "run:v1:079cb5d32f20d399305c3f95b3c7030611f398011b92347effe139561078f7fc" # LocalFixture.RunId

    runtime_algorithm_env = {
      # Not read by the service in fixture mode (Main.scala:106); Terraform requires every key.
      SCHOLARCHAIN_ER_POLICY_VERSION       = "er-policy-v5"
      SCHOLARCHAIN_ER_AUTO_MATCH_THRESHOLD = "0.85"
      SCHOLARCHAIN_ER_REVIEW_THRESHOLD     = "0.55"
      SCHOLARCHAIN_GRAPH_WINDOW_START_YEAR = "2015"
      SCHOLARCHAIN_GRAPH_WINDOW_END_YEAR   = "2020"
      SCHOLARCHAIN_GRAPH_POLICY_VERSION    = "coauthor-policy-v1"
      SCHOLARCHAIN_BRIDGE_ALGORITHM        = "label-propagation-v1-synthetic"
      SCHOLARCHAIN_BRIDGE_MAX_ITERATIONS   = "10"
      SCHOLARCHAIN_BRIDGE_MIN_EDGE_WEIGHT  = "1.0"
    }

    # The variable is an object with all six attributes required (variables.tf:93-111).
    interface_endpoints = {
      ecr_api         = true
      ecr_dkr         = true
      logs            = true
      secretsmanager  = true
      bedrock_runtime = false
      s3vectors       = false
    }
    interface_endpoint_az_count = 1 # 1 is enough: private DNS, SG-chained access from both app subnets (modules/network/main.tf:27, 149-158, 295-331); 2 doubles the endpoint cost
    api_desired_count           = 1
    worker_desired_count        = 0
    health_check_path           = "/healthz" # unchanged; /readyz would also pass in fixture mode

ASSUMPTIONS:
- The services image built from HEAD behaves like the code I read: Main.scala, LocalFixture.scala, ServicesApp.scala, Auth.scala, and ConfigLoader.scala at d9f182f.
- The readiness and smoke-test conclusions come from reading the code and existing unit tests (AuthApiSuite, QueryApiSuite, EvidenceLifecycleSuite), not from a deployed run.
- The fixture run ID was computed outside Scala. The encoder reproduced a core golden vector, but the authoritative confirmation is the `releaseRun` field in the deployed service.started log.
- The 0.85 and 0.55 ER values are nearest counterparts of accept and reject. They are inert in fixture mode.

RISKS:
- With the default SCHOLARCHAIN_SMOKE_QUESTION, the REQUIRE_READY=1 smoke test fails at check 7 (3c). The human or deploy-engineer must set SCHOLARCHAIN_SMOKE_QUESTION="Who are the coauthors of Alice Adams?". The smoke-test.sh header does not mention this. A one-line comment there is an optional follow-up I did not make, to keep the diff minimal.
- package.sh refuses admin-named push profiles, and no deployment role exists. Without a non-admin push profile, the bootstrap needs an extra IAM apply (item 4C).
- The existing secret value is unknown. Overwriting it drops any earlier entries that are not included again.
- Fixture mode serves SYNTHETIC data from a public ALB, with API-key auth on. This is acceptable only as Phase 14 evidence. The Phase 14 report must say the smoke answer is synthetic fixture data, and runtime_mode must go back to "aws" (then apply and deploy.sh) after Phase 15.
- The fixture's evidence and idempotency stores are in memory per task. That is fine with api_desired_count = 1.
- check.sh's terraform test relies on mock_provider (Terraform 1.7 or later; the pinned floor is 1.10). If a future module computes something from a mocked data source (for example jsondecode of a policy), the test may need more mock defaults.

MANUAL ACTION REQUIRED (human):
1. Have code-reviewer review this diff, then commit it together with your phase-14 docs edits. package.sh needs a clean tree.
2. Choose the push profile path (item 4C, (i) or (ii)).
3. Run the secret write (4B) and the image push (4C) yourself.
4. Set the dev.tfvars lines (item 5).
5. Request Step 1b (plan.sh dev).

BLOCKED-ON OWNER: none

BLOCKED-ON EVIDENCE: none

NEXT GATE: code-reviewer reviews this diff. Then the human commits and completes bootstrap steps A to C and the tfvars. Then Step 1b: infra-engineer runs `bash scripts/plan.sh dev` and reports the plan checksum, counts, IAM, and cost for the human's plan sign-off. `sbt check` was not run (no Scala change; the work order asked for --skip-sbt), so check.sh is INCOMPLETE and not yet the definition of done.
```
