# Phase 13 human gate: create reproducible build, deployment, and experiment automation

| | |
|---|---|
| **Decision** | ACCEPTED WITH OPEN RISKS |
| **Accepted commit** | `e269427c4656aaf258557d5c693cddcd94ab573d` |
| **Date** | 2026-11-22 to 2026-11-23 |
| **Work order** | `docs/agent-runs/phase-13/prompt.md` (saved after the runs started) |
| **Built by** | infra-engineer (Part A, Part B, repairs 01-04); experiment-engineer (dry runs) |
| **Reviewed by** | code-reviewer, 4 rounds (final APPROVE) |
| **Gate evidence** | `bash scripts/check.sh` RESULT status=OK, 760 tests (758 passed, 2 skipped), 0 failed |

## Rounds
- Review 1: REQUEST_CHANGES (F1-F4 major, F5-F10 minor), repair-01
- Review 2: REQUEST_CHANGES (N1-N4 minor), repair-02, repair-03/03b (Windows path forms)
- Review 3: REQUEST_CHANGES (R3-1 minor), repair-04
- Review 4: APPROVE

## My decisions
- P13-D1. No GitHub Actions; scripts are the reproducibility interface: **approved**
- P13-D2. package.sh pushes to ECR only with --push and a non-admin, non-readonly profile: **approved**
- P13-D3. archive-evidence.sh archives locally; S3 upload opt-in: **approved**
- P13-D4. Two parallel infra-engineer parts with disjoint files, one review: **approved**
- Deploy approval format: deploy.sh accepts only my exact line
  `APPROVED-DEPLOY-PLAN-SHA256: <sha> env=dev` in a committed gate; `REVOKED-DEPLOY-PLAN-SHA256: <sha>` withdraws it: **approved**
- Dry run 1 (out-of-tree manifest): the guard refusal is accepted as evidence: **[approved / I ran it myself: EXIT=3]**
- SECURITY-ACTION for routes, route tables, internet gateway, assign_public_ip, map_public_ip_on_launch: **[deferred to a follow-up / not needed]**
- experiments/results/: **[I commit results after each experiment / build-engineer gitignores them]**

## Manual interventions
- None. (No AWS changes, no Terraform changes, no plan in this phase.)

## Open risks
| ID | Severity | Risk | Owner, when |
|---|---|---|---|
| — | — | Never executed: package --push, archive --upload/metrics, run-experiment end to end, deploy end to end, live smoke test | deploy-/experiment-engineer, Phases 14-15 |
| — | — | check.sh not yet run from a checkout with no Terraform provider cache | infra-engineer |
| — | — | Phase 15 needs ECS autoscaling and a raised EMR core maximum (Terraform, plan, my sign-off) | infra-engineer, human |
| — | — | Smoke query needs REQUIRE_READY=1 and a working graph store (UnavailableGraphStore today) | storage-/service-engineer |
| — | — | README must use check.sh and package.sh | documentation-engineer, Phase 17 |
