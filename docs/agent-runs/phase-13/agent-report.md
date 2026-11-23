# Phase 13 agent report: infra-engineer (reproducible build, deployment and experiment automation)

Assembled by the orchestrator on 2026-11-23 from the agent reports and orchestrator verifications in this folder.
Work order: docs/project-description.md lines 1873-1954, with orchestrator defaults P13-D1 to P13-D4 (below). The draft for
`prompt.md` was given to the human in the session; the runs started before it was saved. Base: HEAD 71978f2 (Phase 12 accepted at cf4ce53).

STATUS: COMPLETE. Code-reviewer verdict APPROVE (round 4). `bash scripts/check.sh` RESULT status=OK on the final scripts.
No Terraform change, no plan, no AWS mutation in this phase.

## Runs

| Run | Agent | Result | Elapsed | Evidence |
|---|---|---|---|---|
| Part A | infra-engineer | check.sh, package.sh; check run 4 OK; package run 2 OK (no push) | about 33 min | part-a-infra-engineer.md |
| Part B | infra-engineer | run-experiment.sh, archive-evidence.sh; gap fixes to plan, deploy, smoke-test, run-bounded-pipeline | about 18 min | part-b-infra-engineer.md |
| Dry runs | experiment-engineer | archive OK (exit 0); out-of-tree manifest refused by the guard before the script ran | about 4 min | dryrun-experiment-engineer.md |
| Review 1 | code-reviewer | REQUEST_CHANGES: F1-F4 MAJOR, F5-F10 MINOR | about 12 min | review-01-code-reviewer.md |
| repair-01 | infra-engineer | F1-F10 fixed; package `--push` refuses admin profiles | about 18 min | repair-01-infra-engineer.md |
| Review 2 | code-reviewer | REQUEST_CHANGES: N1-N4 MINOR | about 9 min | review-02-code-reviewer.md |
| repair-02 | infra-engineer | N1-N4 fixed; archive path-form fix | about 12 min | repair-02-infra-engineer.md |
| repair-03, 03b | infra-engineer | Path form (`cygpath -m`) in run-experiment.sh (orchestrator-found) and archive-evidence.sh | about 6 + 4 min | repair-03-infra-engineer.md |
| Review 3 | code-reviewer | REQUEST_CHANGES: R3-1 MINOR | about 6 min | review-03-code-reviewer.md |
| repair-04 | infra-engineer | R3-1 fixed (plain-text gate check, `--text` revocation) | about 4.5 min | repair-04-infra-engineer.md |
| Review 4 | code-reviewer | APPROVE; no findings | about 4 min | review-04-code-reviewer.md |
| Final check | orchestrator | `bash scripts/check.sh`: first run gate 9 FAIL (the orchestrator wrote review-04 during the run); rerun with no writes: RESULT OK | about 8 min each | this report |

## What was built

| Script | Who may run it (docs/agents.md section 7) | What it does |
|---|---|---|
| check.sh (new) | any agent, no prompt | Offline definition of done. Gates: validator, JSON validity, `bash -n`, dataset validation, terraform fmt, terraform validate (bootstrap and dev; `-backend=false`, `-lockfile=readonly`, temporary TF_DATA_DIR), `sbt -batch check`, working tree unchanged. Exit 0 OK, 1 FAIL, 3 INCOMPLETE |
| package.sh (new) | any agent, ask | Hadoop, Spark and services jars plus the services image, tagged by the 40-character SHA; SHA256SUMS and manifest.json. `--push` is opt-in: refuses read-only and admin profiles and static credentials; immutable tags; registry digest verified |
| run-experiment.sh (new) | experiment-engineer, ask | Runs only committed `experiments/**.json` manifests (`scholarchain-experiment/v1`) with a command allowlist and caps (7200 s, concurrency 100, USD 25). Writes sealed raw results to `experiments/results/<id>/<run-id>/`, never overwritten |
| archive-evidence.sh (new) | deploy-engineer, experiment-engineer, ask | Evidence archive with a SHA-256 index. Never copies tfvars, backend.hcl, state, plan files or credential directories. Redacts only the caller's account. A missing or altered plan checksum is a failure. Upload is opt-in, to a `scholarchain-` bucket owned by the caller |
| plan.sh (changed) | infra-engineer, deploy-engineer | Adds account last-4, ACTION-COUNTS, IAM-ACTION (including policy-bearing types), SECURITY-ACTION, STATEFUL-ACTION and UNPARSED-ACTION markers; fails closed on destroy or replace. Optional record under reports/deployments/ |
| deploy.sh (changed) | deploy-engineer, ask | Requires SCHOLARCHAIN_APPROVED_PLAN_SHA256 and the exact committed line `APPROVED-DEPLOY-PLAN-SHA256: <sha> env=<env>` in a human-gate.md at HEAD. Refused if any committed gate holds a revocation line (lenient match), or if any committed gate is not plain text. Still never applies (D14) |
| smoke-test.sh (changed) | deploy-engineer, incident-responder, experiment-engineer | Health and readiness; a bounded query; idempotent replay and 409 conflict; evidence fetch; 401 and 400 error paths. Not-ready is classified by status plus error code |
| run-bounded-pipeline.sh (changed) | deploy-engineer, ask | Writes a run record on every outcome, with the account redacted in bucket URIs |

## Gate (spec lines 1948-1954)

| Gate item | State | Basis |
|---|---|---|
| A clean checkout can run all non-cloud validation through the documented script | Met, with a caveat | check.sh RESULT OK (orchestrator-observed). It ran with the local Terraform provider cache; the registry-download path for a checkout with no cache has not been run |
| Terraform planning is reproducible and the plan checksum is archived | Met (scripts) | plan.sh markers and an optional record; archive-evidence.sh archives `[plan]` markers and fails on a missing or altered checksum. No plan was run in this phase |
| Deployment cannot proceed without the documented human approval | Met | deploy.sh exact committed approval line, revocation, plain-text check. No approval line exists yet, so every deploy is refused today |
| Smoke tests exercise a real bounded collaboration query | Met in the script; not yet run live | Bounded query plus evidence fetch. It needs `SCHOLARCHAIN_SMOKE_REQUIRE_READY=1` and a working graph store (currently UnavailableGraphStore in AWS mode) |
| The same scripts are used in the README and the final demonstration | Pending | documentation-engineer, Phase 17 |

## Final check (orchestrator-observed, 2026-11-23)
- `bash scripts/check.sh`, first run: STEP 0-8 OK; STEP 9 FAIL. The orchestrator wrote review-04-code-reviewer.md during the run, so the tree changed. The gate worked as designed.
- Rerun with no writes: STEP 0-9 OK, `[check] RESULT status=OK git_sha=71978f28… failed=none skipped=none`, exit 0.
- sbt totals: 160, 80, 63, 70, 73 (71 passed, 2 skipped), 83, 40 and 191 tests, 0 failed. That is 760 tests, matching the Phase 11 baseline.

## Never executed (reviewed by reading or with offline harnesses only)
- `package.sh --push`.
- archive-evidence `--upload`, the metrics export and the sts lookup.
- run-experiment.sh end to end (only its containment block, through a harness).
- The pipeline record paths after a SUCCEEDED execution.
- smoke-test.sh against a live service.
- deploy.sh end to end.

## Open items and follow-ups

| Item | Owner or decider |
|---|---|
| SECURITY-ACTION excludes routes, route tables, the internet gateway, ECS assign_public_ip and subnet map_public_ip_on_launch | human decision; then infra-engineer |
| experiments/results/ and new reports/deployments records dirty the tree, so deploy and package refuse until they are committed | human decision (commit each time) or build-engineer (`.gitignore`) |
| Dry run 1 evidence: accept the guard refusal, or run the script yourself to show its own exit 3 | human |
| deploy.sh re-resolves HEAD instead of `$GIT_SHA` for approval checks (optional hardening) | infra-engineer |
| deploy.sh header says to call package.sh instead of `sbt dockerLocal` | infra-engineer |
| Phase 15 needs ECS autoscaling (no aws_appautoscaling resources) and a raised EMR core maximum | infra-engineer (plan), human (sign-off) |
| Pipeline experiments cannot start the pipeline from run-experiment.sh (only deploy-engineer may run it); they use command "none" plus pipeline_run_id | accepted by design, or an amendment |
| README reproduction steps use check.sh and package.sh | documentation-engineer, Phase 17 |
| shellcheck is not installed, so no script lint was run | human (optional install) |

## Manual actions (human)
1. Save the Phase 13 work order in `prompt.md` (draft in the session scratchpad), noting it was saved after the runs started.
2. Write the Phase 13 human-gate.md (UTF-8). Record the decisions above.
3. Commit Phase 13 and record the hash.
4. For any future deploy: add `APPROVED-DEPLOY-PLAN-SHA256: <plan sha> env=dev` on its own line in a committed human-gate.md.
