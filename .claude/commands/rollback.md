---
description: Roll a ScholarChain environment back to its last known-good release through deploy-engineer, with tightly limited autonomy.
argument-hint: <environment> [target-release-id]
---

# /rollback $ARGUMENTS

Responsible agent: `deploy-engineer`
During an open incident: `incident-responder` may run the same script as its one sanctioned recovery lever.

## Gates

1. Approved environment: listed in docs/agents.md section 7 (today only `dev`).
2. Known-good target: the target release is recorded under `reports/releases/` with its image digests and task revisions. If it is not, stop and escalate to the human.
3. Human confirmation: each `scripts/rollback.sh` execution triggers a permission prompt that the human answers.
4. Scope limit: a rollback only returns application images and task revisions to the recorded release. It never runs Terraform, changes schemas, deletes or repairs data, rotates secrets, or changes scaling bounds.

## Delegation

Use the deploy-engineer agent to roll environment `<environment>` back to release `<target or last known-good>` by executing only `scripts/rollback.sh <environment>`. First capture the evidence from the failing revision: stopped-task reasons, CloudWatch log excerpts, alarm states, and smoke-test output. Then roll back, wait for stabilization with a bounded timeout, verify the serving revision, run `scripts/smoke-test.sh <environment>`, and write a release record under `reports/releases/`.

## Limits on autonomy

- One rollback attempt per invocation. If the rollback fails or the smoke test still fails, stop and escalate with the evidence. Do not improvise.
- Data, schema, security, and destructive actions always go to the human architect.

## After the agent returns

Open `/incident` with the captured evidence. The question "why did the rolled-back release fail?" must have an owner before anyone deploys again.
