# ScholarChain runbook (dev)

Draft for Phase 17, written from the script headers at commit `0c82710`. Only the `dev` environment
is approved (`docs/agents.md` section 7). **No command in this runbook was replayed in this draft**
except `bash -n scripts/*.sh` (all 10 passed) and `bash .claude/hooks/validate-agent-files.sh`
(`RESULT: PASS`, observed in a draft run on a clone of `0c82710`; raw output not archived; the Phase
17 final validator run will be archived). Every other command is "not replayed in this draft". The Phase 14 deployment and
smoke test were run by the human, not replayed by this draft; their observed results are in the
deploy-engineer report
[reports/deployments/2026-11-24-dev-phase-14.md](../reports/deployments/2026-11-24-dev-phase-14.md)
(written after `0c82710`, not yet committed; summary in section 1.6).

All commands run from the repository root in Git Bash. Values come from `terraform output runtime`
or from the human; never paste secret values into files or chat.

## 0. Who runs what

| Script | Runner through Claude (`docs/agents.md` section 7) | Prompt |
|---|---|---|
| `scripts/check.sh` | any agent | no |
| `scripts/package.sh` | any agent (`--push` is human-run) | ask |
| `scripts/plan.sh dev` | infra-engineer, deploy-engineer | no |
| `scripts/deploy.sh dev` | deploy-engineer | ask |
| `scripts/smoke-test.sh dev` | deploy-engineer, incident-responder, experiment-engineer | no |
| `scripts/rollback.sh dev <target>` | deploy-engineer, incident-responder | ask |
| `scripts/run-bounded-pipeline.sh dev` | deploy-engineer | ask |
| `scripts/run-experiment.sh <manifest>` | experiment-engineer | ask |
| `scripts/archive-evidence.sh ...` | deploy-engineer, experiment-engineer | ask |
| `scripts/cleanup.sh dev` | nobody: the human runs it outside Claude Code | denied |
| `terraform apply` | nobody: the human runs it | denied |

Phase 14 deviation: the human ran `deploy.sh` (with a deployment profile) and `smoke-test.sh` personally
in Git Bash; deploy-engineer verifies read-only and writes the report
(`docs/agent-runs/phase-14/human-gate.md`, "Manual interventions").

## 1. Deployment

### 1.1 Pre-flight (local, no AWS)

```bash
git status --porcelain          # must be empty (deploy.sh refuses a dirty tree)
sbt check                       # definition of done
bash scripts/check.sh           # all non-cloud gates; exit 0 = every gate passed, 3 = INCOMPLETE
```

### 1.2 Plan (read-only)

```bash
bash scripts/plan.sh dev | tee <scratch>/plan.log
```

- Uses the read-only profile and refuses static credentials. Writes
  `infra/terraform/envs/dev/plans/*.tfplan` (Git-ignored; holds variable values in plaintext; never
  share or archive it).
- Prints the plan SHA-256, the `Plan:` line, and `IAM-ACTION`, `SECURITY-ACTION`, `STATEFUL-ACTION`,
  `STATEFUL-DESTRUCTIVE`, and `UNPARSED-ACTION` markers; the account appears as its last 4 digits.
- Exit codes: 0 no or non-destructive changes; 2 usage or configuration refusal; 3 a terraform step
  failed; 4 the plan destroys or replaces something (human sign-off required).

### 1.3 Human approval and apply

1. code-reviewer reviews the plan summary.
2. The human adds this exact line to the phase `human-gate.md` and commits it:
   `APPROVED-DEPLOY-PLAN-SHA256: <64 lowercase hex> env=dev`
3. The human runs `terraform apply <plan file>` in `infra/terraform/envs/dev` outside Claude Code.

Revocation: any committed `human-gate.md` line that contains `REVOKED-DEPLOY-PLAN-SHA256` and the
checksum revokes it (`scripts/deploy.sh` header).

### 1.4 Image (first deployment only)

If the ECR repository has no image yet, the human pushes one built at a clean commit:

```bash
bash scripts/package.sh --push   # needs SCHOLARCHAIN_PUSH_PROFILE, SCHOLARCHAIN_AWS_REGION,
                                 # SCHOLARCHAIN_ECR_REPOSITORY_NAME; refuses admin-named push profiles
```

### 1.5 Deploy

```bash
export SCHOLARCHAIN_APPROVED_PLAN_SHA256=<approved checksum>
export SCHOLARCHAIN_AWS_REGION=<region>
export SCHOLARCHAIN_ECR_REPOSITORY_URL=<runtime.ecr_repository_url>
export SCHOLARCHAIN_API_TASK_DEFINITION_ARN=<runtime.task_definitions.api>
export SCHOLARCHAIN_WORKER_TASK_DEFINITION_ARN=<runtime.task_definitions.worker>
bash scripts/deploy.sh dev
```

What it does: checks a clean tree and the approval line at HEAD, builds the services image with
`sbt -batch dockerLocal` tagged with the 40-character Git SHA, pushes it (an existing immutable tag is
reused after a digest check), registers new task-definition revisions copied from the
Terraform-managed base revisions with only the image changed, rolls `scholarchain-dev-api` and
`scholarchain-dev-worker`, and verifies the running tasks carry that SHA and digest. It never runs
Terraform. Exit codes: 0 deployed and verified; 2 usage; 3 precondition refusal; 4 build, push, or
registration failed; 5 not stable; 6 revision verification failed.

### 1.6 Smoke test

```bash
export SCHOLARCHAIN_AWS_REGION=<region>
export SCHOLARCHAIN_SMOKE_BASE_URL=<runtime.alb_base_url>
export SCHOLARCHAIN_SMOKE_API_KEY=<plaintext key with query:write and evidence:read>   # never printed
bash scripts/smoke-test.sh dev
```

Checks: `GET /healthz` (200); `GET /readyz`; unauthenticated `POST /v1/query` (401); authenticated
bounded query (200); idempotent replay (`Idempotent-Replayed: true`, same `queryId`); idempotency
conflict (409); evidence fetch for the first cited evidence ID (200); unknown field (400
`INVALID_FIELD`); running task image tag equals the expected SHA. Exit 0 when every check passes
(`EXPECTED-FAIL` counts as a pass), 1 when any fails, 2 on usage refusal.

**Fixture mode (Phase 14).** With `runtime_mode = "fixture"` in `dev.tfvars`, the API serves the
synthetic `LocalFixture` release (researchers such as "Alice Adams" and "Bob Brown"). The default
smoke question names "Ada Sample 0001", a researcher in `datasets/samples/dblp/dblp-sample.xml`,
which the fixture does not contain. Set a fixture researcher and require readiness:

```bash
export SCHOLARCHAIN_SMOKE_QUESTION="Who are the coauthors of Alice Adams?"   # 1..200 chars of [A-Za-z0-9 .,?'-]
export SCHOLARCHAIN_SMOKE_REQUIRE_READY=1
bash scripts/smoke-test.sh dev
```

The human decision for Phase 14 is that fixture mode lets the smoke test pass with
`SCHOLARCHAIN_SMOKE_REQUIRE_READY=1`, and that `runtime_mode` returns to `aws` after Phase 15 loads
real data.

Observed result (Phase 14, commit `0c82710`, dev, fixture mode; run by the human, verified read-only
by deploy-engineer; source:
[reports/deployments/2026-11-24-dev-phase-14.md](../reports/deployments/2026-11-24-dev-phase-14.md)):

- `scripts/deploy.sh dev`: final line `RESULT status=DEPLOYED`, image digest
  `sha256:12049aababb263f53bffaae3eaa7d271cae18a15ce3c85fe7a94d2bfd8db94a2`, task definitions
  `scholarchain-dev-api:2` and `scholarchain-dev-worker:2` (report sections 2 and 7).
- `scripts/smoke-test.sh dev` with `SCHOLARCHAIN_SMOKE_REQUIRE_READY=1` and the question "Who are
  the coauthors of Alice Adams?": 9 of 9 checks PASS, `RESULT status=PASSED` (report section 4).
- Alarms: 0 metric and 0 composite alarms in state ALARM at verification time (report section 5).
- The query ran against the synthetic fixture (7 researchers), not real data. The report's verbatim
  log copies under `reports/deployments/2026-11-24-dev-phase-14/` match `.gitignore` (`*.log`), so a
  plain `git add` leaves them out (report section 10, item 5).

**`aws` mode before a live Neptune adapter.** `/readyz` is expected to be non-200 because
`UnavailableGraphStore` is composed (`modules/services/.../services/Main.scala`). Leave
`SCHOLARCHAIN_SMOKE_REQUIRE_READY` at 0: check 2 and the not-ready errors of checks 4, 5, and 7
(502 `DOWNSTREAM_FAILURE`, 503 `DEPENDENCY_UNAVAILABLE`, 503 `NOT_READY`) are reported as
`EXPECTED-FAIL`. Any other status fails.

## 2. Common failures

| Symptom | Likely cause (from the script or service source) | Action |
|---|---|---|
| `plan.sh` exits 3 at the fmt step | a local `.tf` or `dev.tfvars` is not formatted (happened in Phase 14 Step 1b attempt 1, `docs/agent-runs/phase-14/plan-infra-engineer-attempt-1-failed.md`) | human runs `terraform fmt` on the local file; rerun |
| `plan.sh` exits 4 | the plan destroys or replaces a resource | stop; human reviews `STATEFUL-DESTRUCTIVE` lines |
| `deploy.sh` exits 3 | dirty tree, missing or revoked approval line, runtime not applied, Docker not running | fix the precondition; never edit `human-gate.md` from an agent |
| `deploy.sh` exits 5 | service did not stabilize within `SCHOLARCHAIN_DEPLOY_STABLE_TIMEOUT_SECONDS` | `/incident`; inspect stopped-task reasons read-only; consider rollback |
| `deploy.sh` exits 6 | running tasks do not carry the expected SHA or digest | rollback to the last verified SHA |
| smoke check 3 or 4 returns 403 `FORBIDDEN` | the smoke key lacks `query:write` or `evidence:read` | fix the key digest entry in the api-key-digests secret (human) |
| 401 `UNAUTHENTICATED` with the key | key not 32-256 characters of `[A-Za-z0-9._~-]`, or digest not configured | recompute the digest with the services `hash-api-key` entry point (`Main.scala`) and update the secret (human) |
| 429 `RATE_LIMITED` | per-principal limit (`SCHOLARCHAIN_SVC_RATE_LIMIT_PER_S`, `..._BURST`) | wait `Retry-After` seconds |
| 503 `OVERLOADED` | worker pool queue full | reduce concurrency; scale only through Terraform and human approval |
| 503 `DEPENDENCY_UNAVAILABLE` or 502 `DOWNSTREAM_FAILURE` | circuit breaker open or a store failing (in `aws` mode: no Neptune adapter) | check readiness checks in `/readyz`; see above |
| smoke check 5 fails with more than one api task | idempotency records are in memory per task | run with one api task (dev default) |
| task exits at start with `service.config-invalid` | `SCHOLARCHAIN_RUNTIME_MODE` not `fixture`/`aws`, invalid `SCHOLARCHAIN_API_KEYS`, missing `SCHOLARCHAIN_RELEASE_RUN_ID` in `aws` mode, or release exports failed to load | inspect the logged problem list (no secrets are logged); fix Terraform variables; human applies |

## 3. Rollback

```bash
export SCHOLARCHAIN_AWS_REGION=<region>
bash scripts/rollback.sh dev <40-char git sha | api task-definition revision number>
```

Both services move to their newest revision whose image tag is that SHA, including the environment
that revision carried. No Terraform, no image build. A dirty tree is allowed (rollback may be needed
during an incident). The target image must still exist in ECR. Exit codes: 0 rolled back and verified;
2 usage; 3 target genuinely not found; 4 AWS error other than not-found; 5 not stable; 6 verification
failed. Follow with `bash scripts/smoke-test.sh dev`.

## 4. Incident escalation

1. Open `/incident` (`.claude/commands/incident.md`); incident-responder diagnoses read-only first
   (ECS service state and stopped-task reasons, alarms, logs) and writes `docs/incidents/**`.
2. Bounded, reversible levers only: redeploy of the same release, `scripts/rollback.sh`,
   `scripts/smoke-test.sh` (`docs/agents.md` section 8, "Autonomy follows reversibility").
3. Escalate to the human with an evidence dossier for anything involving data repair or deletion,
   schema changes, security (IAM, KMS, secrets, network exposure), Terraform apply or destroy,
   expensive scale changes, or three repair loops without APPROVE (`docs/agents.md` section 8).

Alarms defined in `infra/terraform/modules/observability/main.tf`: jobs DLQ depth, pipeline failed,
pipeline timed out, DynamoDB throttles, ALB p95 latency, ALB target 5xx, ALB ELB 5xx, ECS CPU,
Neptune at capacity; plus a CloudWatch dashboard.

## 5. Bounded pipeline run

```bash
export AWS_PROFILE=<profile that assumes scholarchain-dev-pipeline-runner>
export SCHOLARCHAIN_PIPELINE_STAGE=hadoop     # then spark-part-a and later stages (script header)
bash scripts/run-bounded-pipeline.sh dev
```

The read-only profile cannot run it (no `states:StartExecution`). Run through `/run-pipeline`
(deploy-engineer). Results: PENDING (Phase 15).

## 6. Cleanup

Human only, outside Claude Code (`scripts/cleanup.sh` header):

```bash
bash scripts/cleanup.sh dev              # list affected resources (read-only default)
bash scripts/cleanup.sh dev --execute    # typed confirmation "delete scholarchain-dev"
```

`--execute` stops running pipeline executions, terminates tagged transient EMR clusters, and
deregisters deploy.sh task-definition revisions. Everything else is in Terraform state and is removed
by `terraform destroy`, after one apply with `data_cleanup_mode = true` (turns off DynamoDB and ALB
deletion protection, sets `force_destroy` and `force_delete`). The script refuses `--execute` while any
table still has deletion protection. Data loss is permanent: export reports, run manifests, and
evidence first (`scripts/archive-evidence.sh`). Record before and after inventories (D20): PENDING.

To pause costs between sessions without destroying data, the Phase 14 human gate records: set
`enable_runtime = false` and the four interface endpoints to false, then plan and apply.
