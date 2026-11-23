# Phase 13 Part B: infra-engineer (run-experiment.sh, archive-evidence.sh, gap fixes)

Owner: infra-engineer. Delegated by the orchestrator on 2026-11-22, in parallel with Part A (disjoint files). Base 71978f2.
Agent-reported duration: about 18 min.

STATUS: COMPLETE (authoring). Neither new script has been executed. The guard blocks infra-engineer from running them (docs/agents.md section 7), as designed.

## Files
- Created: scripts/run-experiment.sh, scripts/archive-evidence.sh.
- Modified: scripts/plan.sh, deploy.sh, smoke-test.sh, run-bounded-pipeline.sh (4 files, +293/-45).
- No Terraform edited; sbt not run.

## Gap fixes (agent-reported, against spec lines 1902-1921)

| Script | Gap | Fix |
|---|---|---|
| plan.sh | No target account; no IAM or stateful summary; the summary did not outlive the run | `ACCOUNT last4=NNNN`; ACTION-COUNTS, IAM-ACTION, STATEFUL-ACTION and STATEFUL-DESTRUCTIVE markers (unparsed destroy or replace lines fail closed); optional SCHOLARCHAIN_PLAN_RECORD_FILE under reports/deployments/ |
| deploy.sh | No approved plan checksum | SCHOLARCHAIN_APPROVED_PLAN_SHA256 required. The value must appear in a committed `docs/agent-runs/*/human-gate.md` at HEAD (`git grep -F`). Still never applies (D14) |
| smoke-test.sh | Unbounded query; no idempotency check or evidence fetch; one error path | Bounded synchronous query (graphLimit 5, maxPathDepth 2, vectorLimit 5) for "Ada Sample 0001" (from the DBLP sample). The same Idempotency-Key replays with `Idempotent-Replayed: true`; the same key with a different body gives 409 IDEMPOTENCY_CONFLICT. GET /v1/evidence for the first claim. 401 UNAUTHENTICATED and 400 INVALID_FIELD |
| run-bounded-pipeline.sh | Record only on success; no configuration or times | write_record() on TIMEOUT, FAILED, FAILED-CLUSTER-UNVERIFIED and COMPLETED, with configuration, input URIs, execution-input SHA-256 and start/end UTC |

## New scripts (agent-reported)
- **run-experiment.sh:**
  - Manifest: flat JSON, schema `scholarchain-experiment/v1`, strict pure-bash parser. It must be a tracked file inside experiments/, from a clean tree.
  - Command allowlist: smoke-test.sh, a tracked load-tests/*.sh, or none.
  - Caps: 7200 s, concurrency 100, USD 25.
  - Results go to experiments/results/<id>/<run-id>/, which is never overwritten and is sealed with SHA256SUMS.
  - It cannot start the pipeline, which only deploy-engineer may run. Pipeline experiments use command "none" plus pipeline_run_id.
- **archive-evidence.sh:**
  - `--out` must be new or empty, outside protected paths.
  - Never copies tfvars, backend.hcl, tfstate, tfplan, .env*, keys or secrets/. Plan files are hashed, never copied.
  - Redacts 12-digit numbers. Metrics export is read-only with a window of at most 14 days. Upload is opt-in and refuses read-only and admin profiles.

## Terraform follow-ups (not done; each needs a plan and human sign-off)
- No `aws_appautoscaling_*` resources for ECS. Phase 15 needs target tracking.
- EMR managed scaling needs `emr_core_max_instance_count` raised above `emr_core_instance_count` (human decision).

## Orchestrator verification (observed, 2026-11-22)
- `bash -n` on all six scripts: ok.
- `git diff --stat`: 4 files changed, 293 insertions, 45 deletions, matching the report.
