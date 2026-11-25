# ScholarChain

Evidence-backed collaboration analysis over DBLP and open scholarly datasets, built with Scala 3,
Hadoop MapReduce, Spark, AWS, and Graph-RAG, by a team of Claude Code subagents under human
ratification.

> **Draft status (Phase 17, early draft).** This README was drafted from repository state at commit
> `0c82710` before Phase 15 (pipeline and elasticity runs) and Phase 16 (evaluation) produced
> results. Every measured number is marked `PENDING (Phase 15)` or `PENDING (Phase 16)`. Each command
> is marked **replayed** (with the observed outcome in this draft) or **not replayed in this draft**.

## Authors

| Name | University email |
|---|---|
| `<STUDENT NAME>` | `<UNIVERSITY EMAIL>` |

## Submission links

| Item | Link |
|---|---|
| Private repository URL, final tag, commit SHA | PENDING |
| Final report | PENDING (`reports/final-report.md` has not been written yet) |
| Demonstration video | PENDING |
| Deployed application | PENDING |
| Archived experiment results | PENDING (Phase 15) |
| Resource inventory and planned cleanup date | PENDING |
| Instructor-approved deviations | PENDING (none recorded in the repository at `0c82710`) |

## Problem statement

Questions such as "who are this researcher's strongest collaborators in 2015-2020, and which bridge
researchers connect these two communities" need a graph built from many partly overlapping
bibliographic sources. The same person appears under different name spellings and identifiers, the
same paper appears in several datasets, and a language model asked directly will invent
collaborations. ScholarChain parses DBLP and enrichment datasets at scale with Hadoop MapReduce,
resolves researchers and works conservatively, builds a temporal weighted coauthorship graph and its
metrics with Spark, loads the results into AWS stores, and answers questions through a REST API whose
every claim cites evidence (graph facts or hashed text) and passes a deterministic verifier. A claim
that cannot be verified is labeled unsupported or the runtime abstains.

## Architecture summary

Full diagrams: [docs/architecture.md](docs/architecture.md). Data lineage:
[docs/data-lineage.md](docs/data-lineage.md).

- **Build time (batch):** bounded samples and snapshots under `datasets/` go to the S3 raw and samples
  buckets. A Step Functions state machine (`infra/terraform/modules/workflow`) starts a transient EMR
  cluster per stage. Hadoop jobs (`modules/hadoop`, Jobs 1-7) extract DBLP records, normalize
  OpenAlex, Crossref, arXiv, and OpenCitations records, extract exact identifiers, block candidates,
  expand publication teams into coauthor pairs, and prepare graph exports. Entity resolution
  (`modules/entity-resolution`) decides matches. Spark Part A and Part B (`modules/spark`) build
  canonical researchers and the coauthorship graph and compute metrics, communities, a bridge
  measure, k-core, and bounded evidence paths, then write a partitioned export with load manifests.
- **Stores:** S3 (curated exports), DynamoDB (`scholarchain-<env>-*` tables), S3 Vectors (vector
  bucket and index), and Amazon Neptune (optional, `enable_neptune`). Loaders are in
  `modules/storage`.
- **Runtime:** one Scala 3 services image (`modules/services`, built by `sbt dockerLocal`) runs as two
  ECS Fargate services, `api` and `worker`, behind an Application Load Balancer
  (`infra/terraform/modules/runtime`). The API calls the Graph-RAG pipeline in `modules/rag`: plan,
  resolve researchers, retrieve graph facts, build a bounded evidence bundle, synthesize typed
  claims, verify every claim deterministically, and answer or abstain.

## Agentic workflow summary

The repository was built by 18 Claude Code subagents (`.claude/agents/*.md`) coordinated by the main
session acting as orchestrator, under the constitution in [CLAUDE.md](CLAUDE.md) and
[docs/agents.md](docs/agents.md) (owned by factory-engineer and referenced here, not restated).

- Hub and spoke: subagents never call each other; the orchestrator routes work orders and reports.
- One owner per path, enforced for Write and Edit by `.claude/hooks/guard-ownership.sh` (docs/agents.md
  section 4).
- Dangerous Bash and AWS calls are filtered by `.claude/hooks/guard-dangerous.sh`; `terraform apply`
  and `destroy` are denied to every agent. The human applies plans.
- The Stop hook (`.claude/hooks/verify-tests-ran.sh`) requires a fresh `sbt check` marker before a
  session reports implementation work complete.
- Every phase leaves `prompt.md`, agent reports, review and repair records, `human-gate.md`, and
  `commit.txt` under [docs/agent-runs/](docs/agent-runs/) (human-owned evidence).

## Agent catalog

Transcribed from [docs/agents.md](docs/agents.md) (sections 2, 4, 5, 6, 7) and the frontmatter and
"Report" sections of each `.claude/agents/<name>.md`. Every agent returns the shared report contract
of docs/agents.md section 11 (`STATUS`, `OWNER`, `WORK ORDER`, `FILES CREATED`, `FILES MODIFIED`,
`COMMANDS RUN`, `TEST RESULTS`, `VALIDATION RESULTS`, `ASSUMPTIONS`, `RISKS`, `MANUAL ACTION
REQUIRED`, `BLOCKED-ON OWNER`, `BLOCKED-ON EVIDENCE`, `NEXT GATE`); "Extra report fields" lists what
the role file adds after them. "Prerequisites" is the first step of each role file's procedure plus
any precondition stated in docs/agents.md. Example instructions are illustrations of the form, not
transcripts of archived work orders.

### factory-engineer
- Purpose: creates and maintains the agent system from a human-ratified authority matrix; never self-ratifies.
- Owns: `CLAUDE.md`, `docs/agents.md`, `.mcp.json`, `.claude/settings.json`, `.claude/agents/*`, `.claude/hooks/*`, `.claude/commands/*`.
- Tools: Read, Grep, Glob, Write, Edit, Bash.
- Prerequisites: a stated constitutional change; human ratification and a Claude Code restart before it takes effect (docs/agents.md section 12).
- Example: "Use factory-engineer to propose an amendment mapping a new operational script in docs/agents.md section 7 and the guard tables. Present the full diff and stop."
- Verification: `bash .claude/hooks/validate-agent-files.sh`; human ratification.
- Report: its own contract (`.claude/agents/factory-engineer.md`, "Report contract"): Summary, Files touched, Privilege delta, Floor invariants, Audit results, Check results, Full diff, Ratification request.

### build-engineer
- Purpose: the sbt multi-project build, formatting rules, packaging, Docker image, aliases `fmt`, `check`, `packageJobs`, `dockerLocal`.
- Owns: `build.sbt`, `project/**` (structure of `project/Versions.scala`), `.scalafmt.conf`, `.gitignore`, `.gitattributes`, compose files, `docker/**`, `.gitkeep` placeholders.
- Tools: Read, Grep, Glob, Write, Edit, Bash, aws-docs search/read.
- Prerequisites: read CLAUDE.md, docs/agents.md, and the current build files.
- Example: "Use build-engineer to add a test dependency to the rag module and pin it in project/Versions.scala."
- Verification: `sbt check`, then code-reviewer.
- Extra report fields: `VERSION LEDGER`, `MODULE GRAPH`, `ENVIRONMENT INPUTS`, `COMPATIBILITY RISKS`.

### domain-engineer
- Purpose: immutable domain records, stable IDs, pair canonicalization, serialization and schema versions, configuration loader.
- Owns: `modules/core/**`.
- Tools: Read, Grep, Glob, Write, Edit, Bash.
- Prerequisites: read CLAUDE.md, docs/agents.md, and `modules/core`; identify every consumer of the change.
- Example: "Use domain-engineer to add a schema-versioned field to EvidenceBundle with a contract test."
- Verification: contract tests, `sbt check`, code-reviewer.
- Extra report fields: `SAMPLE SERIALIZED RECORDS`, `STABLE ID FORMULAS`, `PAIR CANONICALIZATION RULE`, `CONFIGURATION PRECEDENCE`, `SCHEMA VERSIONS`.

### dataset-engineer
- Purpose: dataset manifest, bounded download and sampling scripts, samples, schema notes, license records.
- Owns: `datasets/**`.
- Tools: Read, Grep, Glob, Write, Edit, Bash, WebFetch (asks every time).
- Prerequisites: read CLAUDE.md, docs/agents.md, and `datasets/`; confirm the selected datasets. Dataset scripts run from the repository root (docs/agents.md section 7b).
- Example: "Use dataset-engineer to add a bounded OpenAlex sample with license and checksum records."
- Verification: `bash datasets/scripts/validate-datasets.sh`; manifest review.
- Extra report fields: `DATASETS`, `UNRESOLVED DATASET QUESTIONS`.

### hadoop-engineer
- Purpose: MapReduce jobs for DBLP extraction, source normalization, exact identifiers, blocking, team and pair expansion, graph export preparation, counters, rejected records.
- Owns: `modules/hadoop/**`.
- Tools: Read, Grep, Glob, Write, Edit, Bash.
- Prerequisites: read CLAUDE.md, docs/agents.md, `modules/core` contracts, and `datasets/` samples.
- Example: "Use hadoop-engineer to add a counter for oversize teams in Job 6 with a test."
- Verification: local or EMR sample run, deterministic outputs, test-engineer, code-reviewer.
- Extra report fields: `JOB GRAPH`, `KEY-VALUE CONTRACTS`, `PARTITION COUNTS`, `COUNTERS`, `REJECTED RECORDS`, `OUTPUT PATHS`, `MAX TEAM SIZE AND LARGEST PAIR EXPANSION`, `LARGEST BLOCK`.

### entity-resolution-engineer
- Purpose: identifier rules, normalization, blocking keys, match features, versioned thresholds, adjudication, gold-set evaluation.
- Owns: `modules/entity-resolution/**`.
- Tools: Read, Grep, Glob, Write, Edit, Bash.
- Prerequisites: read CLAUDE.md, docs/agents.md, the `modules/core` match contracts, and the current policy.
- Example: "Use entity-resolution-engineer to evaluate policy v1 against the gold set and report precision and recall by path."
- Verification: gold-set evaluation, match-policy review, code-reviewer.
- Extra report fields: `POLICY VERSION`, `FEATURES`, `THRESHOLDS`, `MODEL AND PROMPT VERSIONS`, `CONFUSION MATRIX`, `PRECISION/RECALL/F1 BY PATH`, `UNRESOLVED RATE`, `REPRESENTATIVE ERRORS`.

### spark-engineer
- Purpose: canonical researchers, temporal weighted coauthorship graph, metrics, components, paths, communities, bridges, k-core, evidence paths.
- Owns: `modules/spark/**`.
- Tools: Read, Grep, Glob, Write, Edit, Bash.
- Prerequisites: read CLAUDE.md, docs/agents.md, `modules/core`, and the Hadoop and entity-resolution output formats.
- Example: "Use spark-engineer to add a known-graph test for the bridge measure."
- Verification: Spark tests, sample run, output invariants, code-reviewer.
- Extra report fields: `JOB DAG`, `METRIC DEFINITIONS AND PARAMETERS`, `OUTPUT INVARIANTS`, `STAGE METRICS (shuffle, skew)`, `OUTPUT PATHS`.

### storage-engineer
- Purpose: storage ports, adapters, and idempotent loaders for S3, Neptune, DynamoDB, S3 Vectors (OpenSearch optional, not implemented).
- Owns: `modules/storage/**`.
- Tools: Read, Grep, Glob, Write, Edit, Bash, aws-docs search/read.
- Prerequisites: read CLAUDE.md, docs/agents.md, `modules/core`, and the Spark output formats; check service API details with aws-docs.
- Example: "Use storage-engineer to add a conditional-write test for the DynamoDB run record."
- Verification: contract tests, bounded load test, code-reviewer.
- Extra report fields: `STORE KEYS AND IDEMPOTENCY RULES`, `QUERY BOUNDS`, `RETRY AND TIMEOUT POLICY`, `LOAD TEST RESULTS`, `REQUIRED IAM ACTIONS (for infra-engineer)`.

### rag-engineer
- Purpose: query plans, researcher resolution, graph and semantic retrieval, fusion, embedding text, evidence bundles, prompts, synthesis, claim verification, injection defenses.
- Owns: `modules/rag/**`, `prompts/**`.
- Tools: Read, Grep, Glob, Write, Edit, Bash, aws-docs search/read.
- Prerequisites: read CLAUDE.md, docs/agents.md, `modules/core` evidence contracts, and storage ports.
- Example: "Use rag-engineer to add a verifier rule test for a claim citing an out-of-bundle evidence ID."
- Verification: retrieval and groundedness evaluation, code-reviewer.
- Extra report fields: `QUERY OPERATIONS ALLOWLIST`, `PROMPT AND MODEL VERSIONS`, `RETRIEVAL METRICS`, `CLAIM SUPPORT RATE`, `INJECTION TEST RESULTS`.

### service-engineer
- Purpose: REST models and routes, error model, validation, authentication, tracing, health and readiness, runtime composition.
- Owns: `modules/services/**`.
- Tools: Read, Grep, Glob, Write, Edit, Bash.
- Prerequisites: read CLAUDE.md, docs/agents.md, the API contract, and the `rag` and `storage` ports.
- Example: "Use service-engineer to return 422 UNSUPPORTED_CONSTRAINT for a venue filter, with an API test."
- Verification: API tests, `sbt check`, code-reviewer.
- Extra report fields: `ROUTES CHANGED (method, path, status codes)`, `LIMITS AND TIMEOUTS`, `SAMPLE REQUEST AND RESPONSE`.

### test-engineer
- Purpose: adversarial and cross-module tests with exact pass and fail counts; never fixes production code.
- Owns: `tests/**`.
- Tools: Read, Grep, Glob, Write, Edit, Bash (Write and Edit limited to `tests/**`).
- Prerequisites: read CLAUDE.md, docs/agents.md, the contracts, and the modules under test.
- Example: "Use test-engineer to attack the Phase 10 API with malformed JSON, oversize bodies, and replayed idempotency keys."
- Verification: exact pass/fail report; failures go to the module owner.
- Extra report fields: `COUNTS (passed/failed/skipped, by suite)`, `FAILING TESTS`, `REQUIREMENT COVERAGE`, `RESIDUAL RISKS`.

### code-reviewer
- Purpose: adversarial read-only review; returns APPROVE or REQUEST_CHANGES.
- Owns: nothing (review reports are returned, not written).
- Tools: Read, Grep, Glob, Bash (fenced to evidence commands by guard-dangerous.sh `READ_ONLY_AGENTS`).
- Prerequisites: `git diff` and `git status` for the full change set; read every touched file.
- Example: "Use code-reviewer to review the uncommitted Phase 13 diff."
- Verification: verified read-only verdict.
- Extra report fields: `VERDICT: APPROVE | REQUEST_CHANGES`, `FINDINGS` (severity, file:line, failure input, impact, owner).

### infra-engineer
- Purpose: Terraform for the whole AWS environment and every operational script.
- Owns: `infra/terraform/**`, `scripts/**`, `.github/workflows/**`.
- Tools: Read, Grep, Glob, Write, Edit, Bash, `mcp__aws-mcp__aws___call_aws`, Terraform registry (7 read tools), aws-docs search/read, GitHub Actions read (3 tools).
- Prerequisites: read CLAUDE.md, docs/agents.md (sections 7 and 8), and the current Terraform; inspect live state read-only.
- Example: "Use infra-engineer to run `bash scripts/plan.sh dev` and report the plan checksum, counts, IAM changes, and cost. Do not apply."
- Verification: `terraform fmt -check`, `terraform validate`, `scripts/plan.sh dev`, `bash -n`, security scan, human plan sign-off, code-reviewer.
- Extra report fields: `STATEFUL REPLACEMENTS OR DESTROYS`, `PLAN SUMMARY`, `IAM CHANGES`, `ESTIMATED HIGH-COST RESOURCES`, `SCRIPT GATES ENCODED`, `REQUIRED ENVIRONMENT VARIABLES (names only)`.

### deploy-engineer
- Purpose: executes approved operations only through approved scripts; writes deployment and release records.
- Owns: `reports/deployments/**`, `reports/releases/**`.
- Tools: Read, Grep, Glob, Bash, Write, `mcp__aws-mcp__aws___call_aws` (no Edit).
- Prerequisites: approved plan checksum in a committed human gate, clean tree, HEAD is the approved commit, `sbt check` passed, code-reviewer APPROVE.
- Example: "Use deploy-engineer to run /deploy for dev at the approved plan checksum and write the deployment report."
- Verification: smoke tests and rollback gate.
- Extra report fields: `COMMIT SHA`, `PLAN CHECKSUM`, `ACCOUNT / REGION / ENVIRONMENT`, `IMAGE DIGESTS AND TASK REVISIONS`, `HEALTH AND SMOKE RESULTS`, `ALARMS`, `ROLLBACK ACTIONS`, and for pipeline runs `RUN IDS, COUNTERS, OUTPUT PREFIXES, COST ESTIMATE`.

### experiment-engineer
- Purpose: experiment manifests, load generators, raw result collectors, derived tables and plots; runs approved experiments.
- Owns: `experiments/**`, `load-tests/**`.
- Tools: Read, Grep, Glob, Write, Edit, Bash, `mcp__aws-mcp__aws___call_aws`.
- Prerequisites: read CLAUDE.md, docs/agents.md (sections 7 and 8), and the manifest; human approval of the cost bound recorded.
- Example: "Use experiment-engineer to run /experiment with experiments/<manifest>.json."
- Verification: reproducible run, immutable raw results.
- Extra report fields: `MANIFEST`, `RUN IDS AND START/END TIMES`, `SCALING EVENTS`, `KEY METRICS`, `ESTIMATED COST`, `FAILED RUNS`, `RAW DATA LOCATIONS`, `LIMITATIONS`.

### documentation-engineer
- Purpose: README, docs (except docs/agents.md, docs/agent-runs/**, docs/incidents/**), final report, diagrams, demo script.
- Owns: `README.md`, `docs/**` (with those exclusions), `reports/final-report.md`.
- Tools: Read, Grep, Glob, Write, Edit, Bash.
- Prerequisites: read CLAUDE.md, docs/agents.md, archived phase evidence, and experiment outputs.
- Example: "Use documentation-engineer to update the runbook from the Phase 14 deployment report."
- Verification: link check, command replay, evidence review.
- Extra report fields: `COMMANDS REPLAYED (with outcome)`, `CLAIMS WITHOUT EVIDENCE`, `BROKEN LINKS`.

### incident-responder
- Purpose: evidence-first diagnosis; bounded rollback and recovery actions.
- Owns: `docs/incidents/**`.
- Tools: Read, Grep, Glob, Bash, Write, Edit, `mcp__aws-mcp__aws___call_aws`, aws-docs search/read.
- Prerequisites: diagnose read-only first (service state, stopped-task reasons); may run only rollback and smoke-test scripts.
- Example: "Use incident-responder to run /incident for failing /readyz on dev."
- Verification: human escalation for data, security, or destructive actions.
- Extra report fields: `INCIDENT ID`, `HYPOTHESIS AND EVIDENCE`, `ACTIONS TAKEN`, `DATA-INTEGRITY ASSESSMENT`, `FOLLOW-UPS (owner, item)`.

### dependency-updater
- Purpose: patch and minor version bumps in the dependency ledger, with release-note and vulnerability analysis.
- Owns: version values (string literals only) in `project/Versions.scala`.
- Tools: Read, Grep, Glob, Edit, Bash, WebSearch, WebFetch, GitHub releases read (2 tools); no Write.
- Prerequisites: list current versions from `project/Versions.scala`.
- Example: "Use dependency-updater to check for patch releases of the AWS SDK and update the ledger."
- Verification: `sbt check`, vulnerability review, code-reviewer, human merge.
- Extra report fields: `CHANGES (library, old version -> new version, reason, risk)`, `VULNERABILITIES ADDRESSED`, `DEFERRED MAJOR UPGRADES`.

## Required local software

Versions are pinned in `project/Versions.scala`, `project/build.properties`, and the Terraform lock
files; this list names the tools only.

- JDK and sbt (sbt version from `project/build.properties`).
- Git and Git Bash (the hooks and scripts are bash; the reference machine is Windows with Git for
  Windows, see docs/agents.md section 5 for the bash dependency).
- Terraform (for `scripts/check.sh` steps 5-7 and `scripts/plan.sh`).
- Docker engine (for `sbt dockerLocal`, `scripts/package.sh` without `--skip-image`, and
  `scripts/deploy.sh`).
- Python 3.8+ (used by `datasets/scripts/validate_datasets.py` and `scripts/package.sh`).
- AWS CLI v2 (cloud steps only).
- Claude Code; `uv` (for `uvx`) and Docker for the MCP servers in `.mcp.json` (docs/agents.md section 9).

## AWS prerequisites

- An AWS account and Region approved by the human (the archived plans target `us-east-1`).
- IAM Identity Center profiles. Agents use only the read-only profile `scholarchain-readonly`
  (permission set ScholarChainReadOnly, docs/agents.md section 9). The human uses separate profiles
  for apply, image push, deployment, and pipeline runs; the scripts refuse admin-named push profiles
  (`docs/agent-runs/phase-14/human-gate.md`).
- `aws sso login --sso-session scholarchain` before starting Claude Code (docs/agents.md section 9).
- Terraform backend: `infra/terraform/envs/dev/backend.hcl` created by the human from
  `backend.hcl.example`, or the local-backend fallback `local_backend_override.tf` (see
  `scripts/plan.sh` header).
- `infra/terraform/envs/dev/dev.tfvars` created by the human from `dev.tfvars.example` (uncommitted).

## Environment variable names (no values)

Names only; values are never committed. Secrets are referenced by ARN from AWS Secrets Manager.

| Used by | Variables |
|---|---|
| MCP servers (`.mcp.json`) | `SCHOLARCHAIN_GITHUB_READONLY_PAT` (secret, shell only), `SCHOLARCHAIN_AWS_READONLY_PROFILE`, `AWS_REGION` |
| Services process (`modules/services`) | `SCHOLARCHAIN_RUNTIME_MODE` (`fixture` or `aws`, required), `SCHOLARCHAIN_API_KEYS` (JSON array of key digests, injected from Secrets Manager), `SCHOLARCHAIN_RELEASE_RUN_ID` (aws mode), `SCHOLARCHAIN_SVC_*` limits (for example `SCHOLARCHAIN_SVC_PORT`, `SCHOLARCHAIN_SVC_MAX_BODY_BYTES`, `SCHOLARCHAIN_SVC_RATE_LIMIT_PER_S`, `SCHOLARCHAIN_SVC_SYNC_DEADLINE_MS`), plus the core configuration variables (`SCHOLARCHAIN_AWS_REGION`, `SCHOLARCHAIN_CURATED_BUCKET`, `SCHOLARCHAIN_DYNAMODB_TABLE_PREFIX`, `SCHOLARCHAIN_GRAPH_*`, `SCHOLARCHAIN_ER_*`, `SCHOLARCHAIN_BRIDGE_*`) |
| `scripts/check.sh` | optional `SCHOLARCHAIN_CHECK_SBT_TIMEOUT_SECONDS`, `SCHOLARCHAIN_CHECK_TF_TIMEOUT_SECONDS` |
| `scripts/plan.sh` | optional `SCHOLARCHAIN_PLAN_TIMEOUT_SECONDS`, `SCHOLARCHAIN_PLAN_RECORD_FILE` |
| `scripts/package.sh --push` | `SCHOLARCHAIN_PUSH_PROFILE`, `SCHOLARCHAIN_AWS_REGION`, `SCHOLARCHAIN_ECR_REPOSITORY_NAME` |
| `scripts/deploy.sh` | `SCHOLARCHAIN_APPROVED_PLAN_SHA256`, `SCHOLARCHAIN_AWS_REGION`, `SCHOLARCHAIN_ECR_REPOSITORY_URL`, `SCHOLARCHAIN_API_TASK_DEFINITION_ARN`, `SCHOLARCHAIN_WORKER_TASK_DEFINITION_ARN`; optional `SCHOLARCHAIN_DEPLOY_RECORD_FILE`, `SCHOLARCHAIN_DEPLOY_STABLE_TIMEOUT_SECONDS`, `AWS_PROFILE` |
| `scripts/smoke-test.sh` | `SCHOLARCHAIN_AWS_REGION`, `SCHOLARCHAIN_SMOKE_BASE_URL`, `SCHOLARCHAIN_SMOKE_API_KEY`; optional `SCHOLARCHAIN_SMOKE_EXPECTED_SHA`, `SCHOLARCHAIN_SMOKE_REQUIRE_READY`, `SCHOLARCHAIN_SMOKE_QUESTION` |
| `scripts/rollback.sh` | `SCHOLARCHAIN_AWS_REGION`; optional `SCHOLARCHAIN_ROLLBACK_RECORD_FILE`, `SCHOLARCHAIN_DEPLOY_STABLE_TIMEOUT_SECONDS` |
| `scripts/run-bounded-pipeline.sh` | `SCHOLARCHAIN_PIPELINE_STAGE` and the stage inputs named in its header (for example `SCHOLARCHAIN_PIPELINE_STATE_MACHINE_ARN`, `SCHOLARCHAIN_HADOOP_JAR_URI`, `SCHOLARCHAIN_SPARK_JAR_URI`, `SCHOLARCHAIN_HADOOP_RUN_OUTPUT`, `SCHOLARCHAIN_ER_DECISIONS_URI`, `SCHOLARCHAIN_SPARK_PART_A_RUN_DIR`, `SCHOLARCHAIN_SPARK_PATH_PAIRS_URI`), `AWS_PROFILE` (pipeline-runner role) |
| `scripts/run-experiment.sh` | manifest-driven; `SCHOLARCHAIN_EXPERIMENT_*` names are set by the script for the experiment (see its header) |
| `datasets/scripts/*` | `SCHOLARCHAIN_DATA_DIR`, `SCHOLARCHAIN_CROSSREF_MAILTO` (see `datasets/dataset-manifest.yaml`) |

## Build, format, test, and package (local, no AWS)

All commands run from the repository root.

| Purpose | Command | Replay status in this draft |
|---|---|---|
| Format all Scala sources | `sbt fmt` | not replayed in this draft (it rewrites files) |
| Definition of done: format check, compile, Test/compile, every unit and contract test, marker | `sbt check` | observed in a draft run on a clean clone of `0c82710` (`sbt -batch check`: exit 0, 760 tests, 0 failed, 2 skipped); raw log not archived in the repository; the Phase 17 final `sbt clean check` run will be archived (see "Replay record" below) |
| Clean build from scratch | `sbt clean check` | not replayed in this draft |
| Validate agents, hooks, settings, MCP, commands | `bash .claude/hooks/validate-agent-files.sh` | observed in a draft run on a clean clone of `0c82710`: `RESULT: PASS`, 103 check lines, 0 `FAIL`; raw output not archived; the Phase 17 final validator run will be archived |
| Non-cloud repository gate (validator, JSON, `bash -n`, datasets, Terraform fmt/validate/test, `sbt check`, tree unchanged) | `bash scripts/check.sh` | not replayed in this draft |
| Same without sbt or Terraform | `bash scripts/check.sh --skip-sbt` / `--skip-terraform` (exit 3 = INCOMPLETE) | not replayed in this draft |
| Dataset manifest, schemas, sample checksums | `bash datasets/scripts/validate-datasets.sh` | not replayed in this draft |
| Verify sample checksums | `bash datasets/scripts/record-sample-checksums.sh --check` | not replayed in this draft |
| EMR job jars | `sbt packageJobs` | not replayed in this draft |
| Local services image (Docker) | `sbt dockerLocal` | not replayed in this draft |
| Jars, assembly, image, and manifest at the current commit | `bash scripts/package.sh` (asks the human; `--skip-image` for jars only) | not replayed in this draft |
| Shell syntax of the operational scripts | `bash -n scripts/<name>.sh` | replayed: all 10 scripts under `scripts/` passed |

## Bounded sample run

- **Local, no AWS:** this draft found no source-controlled script for a local end-to-end pipeline
  run. The closest local equivalent is `sbt check`, whose suites run the jobs and analytics on small inputs. Suites that read the committed
  samples under `datasets/samples/` include `modules/hadoop/src/test/.../DblpParserSuite.scala`,
  `SourcePipelineSuite.scala`, `SourceNormalizersSuite.scala`, `RejectRecordSuite.scala`,
  `modules/entity-resolution/src/test/.../GoldWorksheetSuite.scala`, and
  `tests/contracts/.../hadoop/HadoopCo4SamplesSuite.scala` (found by searching the test sources
  for `datasets/samples`). They ran inside the draft `sbt check` run, whose raw log is not archived
  (see "Replay record").
- **Cloud (bounded EMR run):** `scripts/run-bounded-pipeline.sh dev`, with
  `SCHOLARCHAIN_PIPELINE_STAGE` set to `hadoop` (default), `spark-part-a`, and the later stages named
  in the script header. Runner: deploy-engineer through `/run-pipeline`, with an ask prompt, under the
  pipeline-runner role. Not replayed in this draft. Run IDs, counters, and outputs: PENDING (Phase 15).

## Terraform format, validate, and plan

| Purpose | Command | Replay status |
|---|---|---|
| Format check | `terraform fmt -check -recursive infra/terraform` | not replayed in this draft (also step 5 of `scripts/check.sh`) |
| Validate (no backend, no credentials) | run by `scripts/check.sh` steps 6-7 (`init -backend=false` into a temporary `TF_DATA_DIR`, then `terraform validate` and `terraform test`) | not replayed in this draft |
| Plan (read-only profile, saved unlocked plan, summary with SHA-256) | `bash scripts/plan.sh dev` | not replayed in this draft (needs AWS; infra-engineer or deploy-engineer only) |
| Apply | the human runs `terraform apply <plan file>` in `infra/terraform/envs/dev` outside Claude Code | never run by an agent |

Archived plan evidence: [docs/agent-runs/phase-12/](docs/agent-runs/phase-12/) and
[docs/agent-runs/phase-14/](docs/agent-runs/phase-14/) (human gate with the approved plan checksum).

## Deployment and smoke test

Procedure details and failure handling: [docs/runbook.md](docs/runbook.md).

```bash
bash scripts/plan.sh dev                 # infra-engineer or deploy-engineer; record the plan SHA-256
# human: review, approve in docs/agent-runs/phase-NN/human-gate.md, terraform apply <plan file>
bash scripts/deploy.sh dev               # deploy-engineer (ask prompt); needs SCHOLARCHAIN_APPROVED_PLAN_SHA256
bash scripts/smoke-test.sh dev           # deploy-engineer, incident-responder, experiment-engineer
bash scripts/rollback.sh dev <git-sha>   # deploy-engineer, incident-responder (ask prompt)
```

None of these was replayed in this draft (AWS calls were out of scope). Phase 14 deployment and
smoke results, as recorded by deploy-engineer (read-only verification after the human ran the
scripts): [reports/deployments/2026-11-24-dev-phase-14.md](reports/deployments/2026-11-24-dev-phase-14.md)
(written after `0c82710`, not yet committed). It records the deploy of commit `0c82710` to dev in
fixture mode: `scripts/deploy.sh dev` ended `RESULT status=DEPLOYED` with image digest
`sha256:12049aababb263f53bffaae3eaa7d271cae18a15ce3c85fe7a94d2bfd8db94a2` and task definitions
`scholarchain-dev-api:2` and `scholarchain-dev-worker:2`; `scripts/smoke-test.sh dev` with
`SCHOLARCHAIN_SMOKE_REQUIRE_READY=1` and the fixture question passed 9 of 9 checks; 0 alarms were in
state ALARM. The answers came from the synthetic fixture, not real data. No release record exists
under `reports/releases/` yet.

## API query examples

Route reference: [docs/api-reference.md](docs/api-reference.md). Replace `$BASE` with the ALB base
URL (`terraform output runtime`, field `alb_base_url`) and `$KEY` with a plaintext API key whose
SHA-256 digest is configured. Not replayed in this draft.

```bash
curl -s "$BASE/healthz"
curl -s "$BASE/readyz"
curl -s -X POST "$BASE/v1/query" \
  -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
  -H "Idempotency-Key: demo-0001" \
  --data '{"question": "Who are the coauthors of Alice Adams?", "mode": "synchronous",
           "retrieval": {"graphLimit": 5, "maxPathDepth": 2, "vectorLimit": 5}}'
curl -s -H "Authorization: Bearer $KEY" "$BASE/v1/evidence/<evidenceId>"
curl -s -H "Authorization: Bearer $KEY" "$BASE/v1/explain/<queryId>"
```

"Alice Adams" is a researcher in the synthetic fixture release (`modules/services/.../services/LocalFixture.scala`)
served when `SCHOLARCHAIN_RUNTIME_MODE=fixture`. For the real release use a researcher from the
loaded data. Example answers: PENDING (Phase 15).

## Experiment reproduction

```bash
bash scripts/run-experiment.sh experiments/<manifest>.json        # experiment-engineer, ask prompt
bash scripts/archive-evidence.sh --out <new dir> --experiment-dir <run dir>   # ask prompt
```

At `0c82710`, `experiments/` and `load-tests/` contained only `.gitkeep`. Since then, one
experiment has been added (not yet committed):
[experiments/phase-16/offline/](experiments/phase-16/offline/) with
[manifest.yaml](experiments/phase-16/offline/manifest.yaml) and
[results.md](experiments/phase-16/offline/results.md). It ran offline, local tests only (no AWS):
the entity-resolution module tests (63 passed, 0 failed), the Spark module tests (40 passed, 0
failed), and four cross-module graph and ER contract suites (38 passed, 0 failed), each with a raw
log under `experiments/phase-16/offline/raw/`. These runs did not use `scripts/run-experiment.sh`;
the manifest records the exact `sbt` commands. The gold-set evaluation (run R04) was not run, because
the guard blocks `sbt runMain` for agents; it waits for a human run. Integrity rates on the committed
samples were not measured. `load-tests/` is still empty. None of these commands was replayed by this
draft. Pipeline, elasticity, and load-test results: PENDING (Phase 15). The agentic-factory
evaluation is in [docs/evaluation/factory.md](docs/evaluation/factory.md); the full evaluation
write-up (`docs/evaluation.md`): PENDING (Phase 16).

## Cleanup

`scripts/cleanup.sh` is human-only and denied to every agent (docs/agents.md section 7).

```bash
bash scripts/cleanup.sh dev              # list affected resources (read-only, default)
bash scripts/cleanup.sh dev --execute    # typed confirmation, deletes resources Terraform does not track
# human: apply once with data_cleanup_mode = true, then terraform destroy in infra/terraform/envs/dev
```

Not replayed in this draft. Before and after inventories: PENDING. See
[docs/runbook.md](docs/runbook.md#6-cleanup).

## Reproducing the agentic workflow

This section lets a grader re-run the factory, not just the product. Commands that were replayed in
this draft say so; all others are marked "not replayed in this draft".

### 1. Prepare and start Claude Code

1. Clone the repository and check out the submitted tag. Confirm `git status` is clean.
2. Install the local software above, plus `uv` and Docker for the MCP servers.
3. Export only the environment variable names the MCP servers need (`SCHOLARCHAIN_GITHUB_READONLY_PAT`,
   optionally `SCHOLARCHAIN_AWS_READONLY_PROFILE` and `AWS_REGION`), and run
   `aws sso login --sso-session scholarchain` if AWS reads are needed.
4. Start Claude Code from the repository root (`claude`). Approve each project MCP server when asked
   (docs/agents.md section 9). The main session is the orchestrator; it has no agent file.
5. Confirm the session loaded `CLAUDE.md` and `docs/agents.md` (CLAUDE.md imports docs/agents.md).

Not replayed in this draft (this draft ran inside an existing session).

### 2. Validate the agent system

```bash
bash .claude/hooks/validate-agent-files.sh
```

Observed in a draft run on a clean clone of `0c82710`: final line `RESULT: PASS`, 103 `PASS`/`FAIL`
lines, 0 `FAIL`. The raw output was not archived in the repository; the Phase 17 final validator run
will be archived. It checks agent frontmatter and tool fences, hook wiring, settings, MCP posture,
command files, and floor invariants (for example "guard blocks terraform apply/destroy").
`bash scripts/check.sh` runs it as step 1 together with the other non-cloud gates (not replayed).

### 3. Invoke each agent through the orchestrator

Give the orchestrator a work order that names the agent, for example "Use the hadoop-engineer agent
to ...". The orchestrator delegates through the Agent tool; subagents never invoke each other. Use
the example instructions in the [agent catalog](#agent-catalog). The frozen slash commands map to
agents and gates: `/deploy` and `/rollback` and `/run-pipeline` (deploy-engineer), `/experiment`
(experiment-engineer), `/incident` (incident-responder); see `.claude/commands/*.md`. Each agent
returns the section-11 report; the orchestrator saves it under `docs/agent-runs/phase-NN/`.

### 4. Replay one bounded phase

Recommended: Phase 10 (REST services) or Phase 11 (adversarial tests), because they need no AWS.

1. Create a branch from the parent of the phase's accepted commit. For Phase 10 the accepted commit
   is recorded in `docs/agent-runs/phase-10/commit.txt` (`f78fb62...`), and its parent is `67c13e0`:
   `git switch -c replay-phase-10 67c13e0`.
   That file holds the SHA followed by a space and no newline, so `"$(cat ...)^"` is an invalid
   revision. To read it from the file, strip whitespace:
   `git switch -c replay-phase-10 "$(tr -d '[:space:]' < docs/agent-runs/phase-10/commit.txt)^"`.
   Replayed in this draft (2026-11-24) with `git rev-parse`: `f78fb62` resolves to
   `f78fb622cce39973cfb22070ae3809f266500f10`, `67c13e0` and the stripped `commit.txt` form with `^`
   both resolve to `67c13e04f719c4f587818077f9b51e788986f89d`, and the `cat` form fails with
   `fatal: ambiguous argument`. The `git switch` itself was not run.
   (`docs/agent-runs/phase-09/commit.txt` is empty; phases 05-09 were fast-tracked, see
   `docs/agent-runs/fast-track-phases-05-09.md`.)
2. Paste `docs/agent-runs/phase-10/prompt.md` to the orchestrator as the work order.
3. Let it delegate to service-engineer, then test-engineer, then code-reviewer, and repair loops as
   needed (docs/agents.md section 1).
4. Record the reports in a scratch directory (not over the archived `docs/agent-runs/`).

Not replayed in this draft.

### 5. Run the test and review gates

```bash
sbt check                                   # definition of done; the Stop hook requires it
bash scripts/check.sh                       # all non-cloud gates in one run
```

Then ask the orchestrator: "Use test-engineer to run the full suite and report exact counts" and
"Use code-reviewer to review the diff". A phase is accepted only after code-reviewer returns
`VERDICT: APPROVE` and the human writes `human-gate.md`. `sbt check` was observed in a draft run on a
clone of `0c82710`, with the raw log not archived (see "Replay record"); `scripts/check.sh` was not
replayed.

### 6. Compare reproduced evidence with the archived run

| Compare | Reproduced | Archived |
|---|---|---|
| Changed files | `git diff --stat 67c13e0..HEAD` on your branch | `git show --stat f78fb62` |
| Test counts | your test-engineer report and `sbt check` output | `docs/agent-runs/phase-10/` and `phase-11/` reports |
| Review verdicts and findings | your code-reviewer report | `docs/agent-runs/phase-10/review-0N.md` |
| Human decisions | none (the grader is not the human architect) | `docs/agent-runs/phase-10/human-gate.md` |

Agent output is not deterministic, so compare behavior (routes, status codes, test counts, verdicts),
not text. Differences are findings to report, not failures to hide.

## Known limitations (verified in source at `0c82710`)

- **No live Neptune query path in the services.** In `aws` mode `modules/services/.../services/Main.scala`
  wires `UnavailableGraphStore`, which fails every graph call, so `/readyz` is not ready and bounded
  path queries fail closed. Graph facts in `aws` mode come from release exports in S3
  (`GraphExportReader`). `scripts/smoke-test.sh` check 2 treats this as `EXPECTED-FAIL` unless
  `SCHOLARCHAIN_SMOKE_REQUIRE_READY=1`.
- **Semantic retrieval is not wired in the deployed composition.** `AwsComposition.dependencies`
  leaves `semantic = None`, so S3 Vectors context is not used by the deployed API at this commit.
- **Answer synthesis is template-based.** `ServicesApp` defaults to `TemplateSynthesizer`, and
  `AwsComposition` does not override it, so no model client is wired into the services composition
  (the Bedrock runtime client is declared only in `rag`, per `build.sbt`). `prompts/answer-synthesis/v1/` exists for a model synthesizer.
- **Phase 14 used fixture mode.** The deployed runtime serves the synthetic `LocalFixture` release
  (human decision, `docs/agent-runs/phase-14/human-gate.md`), to be switched to `aws` after Phase 15.
- **Two ECS services from one image** (`api`, `worker`), not three independently deployable images.
  The specification's baseline asks for at least three independently deployable runtime services;
  this gap is stated, not resolved.
- **Optional OpenSearch tier: not implemented** (no OpenSearch code in `modules/` or `infra/`).
- **In-memory idempotency and query records** in the API task (`ServicesApp`); with more than one api
  task, replay can miss (stated in `scripts/smoke-test.sh`).
- **Public HTTP listener** on the ALB (port 80, 0.0.0.0/0) accepted for short fixture sessions only
  (Phase 14 human gate). API key required for `/v1`.
- Guard residual risks RR-01 to RR-15 (docs/agents.md sections 7b and 9).
- Pipeline, elasticity, retrieval, and answer-quality results: PENDING (Phase 15, Phase 16).

## Deliverable index

| ID | Deliverable | Location | Status |
|---|---|---|---|
| D01 | Private Git repository | URL, tag, commit: PENDING | PENDING |
| D02 | Agentic constitution | [CLAUDE.md](CLAUDE.md), [docs/agents.md](docs/agents.md), [.claude/agents/](.claude/agents/), [.claude/settings.json](.claude/settings.json), [.claude/hooks/](.claude/hooks/), [.claude/commands/](.claude/commands/), [.mcp.json](.mcp.json), version ledger [project/Versions.scala](project/Versions.scala), invocation: [agent catalog](#agent-catalog) | present |
| D03 | Agent run evidence | [docs/agent-runs/](docs/agent-runs/) (phases 00-14 with human gates and `commit.txt`; phases 15-17 contain placeholders) | partial |
| D04 | Deterministic build | [build.sbt](build.sbt), [project/](project/), [.scalafmt.conf](.scalafmt.conf), `sbt check` | present |
| D05 | Dataset package | [datasets/dataset-manifest.yaml](datasets/dataset-manifest.yaml), [datasets/schemas/](datasets/schemas/), [datasets/samples/](datasets/samples/) (with `SHA256SUMS`), [datasets/scripts/](datasets/scripts/) | present |
| D06 | Hadoop package | [modules/hadoop/](modules/hadoop/), [tests/contracts/.../hadoop](tests/contracts/src/test/scala/edu/uic/cs441/scholarchain/tests/hadoop/), [scripts/run-bounded-pipeline.sh](scripts/run-bounded-pipeline.sh); run counters PENDING (Phase 15) | code present; run PENDING |
| D07 | Entity-resolution package | [modules/entity-resolution/](modules/entity-resolution/), gold-set evaluation output [docs/agent-runs/phase-00-09-evidence/gold-eval-v1.txt](docs/agent-runs/phase-00-09-evidence/gold-eval-v1.txt); evaluation write-up PENDING (Phase 16) | partial |
| D08 | Spark package | [modules/spark/](modules/spark/); bounded run results PENDING (Phase 15) | code present; run PENDING |
| D09 | Storage package | [modules/storage/](modules/storage/) (S3, DynamoDB, S3 Vectors, Neptune bulk load); OpenSearch not implemented | present |
| D10 | Runtime collaboration-analysis package | [modules/rag/](modules/rag/), [prompts/](prompts/) | present |
| D11 | Microservices package | [modules/services/](modules/services/), [docs/api-reference.md](docs/api-reference.md) | present |
| D12 | Terraform package | [infra/terraform/](infra/terraform/), plans and reviews in [docs/agent-runs/phase-12/](docs/agent-runs/phase-12/) and [phase-14](docs/agent-runs/phase-14/); security scan results: PENDING (not located in this draft) | partial |
| D13 | Reproducibility automation | [scripts/](scripts/) (check, package, plan, deploy, smoke-test, run-bounded-pipeline, run-experiment, archive-evidence, rollback, cleanup); no CI workflow (`.github/workflows/` absent) | present |
| D14 | Deployment package | Phase 14 deployment report [reports/deployments/2026-11-24-dev-phase-14.md](reports/deployments/2026-11-24-dev-phase-14.md) (fixture mode; written after `0c82710`, not yet committed; its two log copies are excluded by `.gitignore`); [reports/releases/](reports/releases/): no release manifest yet | partial |
| D15 | Experiment package | [experiments/phase-16/offline/](experiments/phase-16/offline/) (manifest, results, raw logs; offline tests only; gold-set run R04 not run; not yet committed); [load-tests/](load-tests/) empty; Phase 15 pipeline and load experiments PENDING | partial |
| D16 | Evaluation package | [docs/evaluation/factory.md](docs/evaluation/factory.md) and [factory-counts.csv](docs/evaluation/factory-counts.csv) (agentic factory); offline ER and graph test results in [experiments/phase-16/offline/results.md](experiments/phase-16/offline/results.md); gold-set metrics, retrieval, and answer-quality evaluation and `docs/evaluation.md` PENDING (Phase 16) | partial |
| D17 | Documentation package | this README, [docs/architecture.md](docs/architecture.md), [docs/data-lineage.md](docs/data-lineage.md), [docs/threat-model.md](docs/threat-model.md), [docs/runbook.md](docs/runbook.md), [docs/api-reference.md](docs/api-reference.md); `docs/service-limits.md` and `docs/evaluation.md` PENDING | partial (draft) |
| D18 | Final report | `reports/final-report.md` | PENDING |
| D19 | Demonstration video | link | PENDING |
| D20 | Cleanup evidence | before and after inventories | PENDING |

## Replay record for this draft

Observed in a draft run on a clean clone of commit `0c82710` (not in the working repository). The raw
outputs of the validator and `sbt check` runs below were kept only in a session scratchpad and are
not archived in this repository, so treat them as observations, not as archived evidence. The Phase
17 final `sbt clean check` and validator runs will be archived with their raw logs.

- `bash .claude/hooks/validate-agent-files.sh`: `RESULT: PASS`; 103 check lines; 0 `FAIL` (raw
  output not archived).
- `bash -n` on each of the 10 files in `scripts/`: all passed.
- `git rev-parse` on the Phase 10 revisions in "Replay one bounded phase" (run in the working
  repository on 2026-11-24): results quoted there.
- `sbt -batch check` (completed 2026-11-24 19:51:50 CST per the sbt log; raw log not archived): exit code 0, final line
  `[success] Total time: 478 s`, `markTestRun` updated the clone's marker. Eight aggregated test
  summaries were printed: Total 160, 73, 70, 80, 63, 40, 191, 83 (sum 760), with Failed 0 and Errors
  0 in each, Passed 758 overall, and Skipped 2 (both in the summary that reports `Passed 71, Skipped
  2`). Parallel output interleaving prevents attributing each summary to one sbt project from the log
  alone. Raw evidence: none in the repository. The full sbt log of that run exists only in a session
  scratchpad; it is not archived, and these counts cannot be checked from the repository until the
  Phase 17 final run is archived.
- The 478 s duration is not a performance result: it depends on the machine and on concurrent load
  (other work may have been running on the same machine at the time).

The Phase 14 deployment and smoke results and the Phase 16 offline experiment results quoted in this
README were run by others (the human; experiment-engineer) and are cited from their reports, not
replayed by this draft. Everything else in this README is marked "not replayed in this draft".
