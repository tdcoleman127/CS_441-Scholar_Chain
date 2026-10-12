---
description: Deploy the exact approved ScholarChain commit and Terraform plan to an approved environment through deploy-engineer.
argument-hint: <environment> <approved-plan-checksum>
---

# /deploy $ARGUMENTS

Responsible agent: `deploy-engineer`
Escalation owner on failure: `incident-responder` (diagnosis), human architect (anything destructive or security-relevant)

## Gates

Before delegating, the orchestrator confirms each gate from primary evidence. If any gate fails, stop and report which one. Do not delegate.

1. Human approval: `docs/agent-runs/<current phase>/human-gate.md` records the approved Terraform plan checksum, target AWS account, Region, environment, and cost bound. The checksum in `$ARGUMENTS` must match it.
2. Approved environment: the environment is listed in docs/agents.md section 7. Today that is only `dev`.
3. Infrastructure state: the human has applied the approved plan, or the human gate explicitly approves the documented apply path. Agents never run `terraform apply`.
4. Clean tree: `git status --porcelain` is empty and HEAD is the approved commit.
5. Definition of done: `sbt check` passed at HEAD, and code-reviewer returned APPROVE for this commit.

## Delegation

Use the deploy-engineer agent to deploy commit `<HEAD SHA>` with approved plan checksum `<checksum>` to environment `<environment>` by executing only `scripts/deploy.sh <environment>`. Wait for service stabilization with a bounded timeout. Verify deployed image digests and task revisions against the commit. Run `scripts/smoke-test.sh <environment>`. On any failed gate, collect evidence first, then run `scripts/rollback.sh <environment>` and write an incident note for incident-responder. Write the deployment report under `reports/deployments/`.

## After the agent returns

- Every `scripts/deploy.sh` and `scripts/rollback.sh` execution triggers a permission prompt. The human answers it; the orchestrator never pre-approves.
- If the report says `STATUS: BLOCKED-ON` or a rollback happened, open `/incident` before any new deploy. Someone must explain why the release failed.
- The human archives the report in the phase record.
