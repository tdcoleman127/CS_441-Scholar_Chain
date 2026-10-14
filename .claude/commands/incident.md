---
description: Route a ScholarChain incident (alarm, failed smoke test, DLQ growth, timeouts, degraded API) to incident-responder for evidence-first diagnosis.
argument-hint: <symptom description, environment, and any alarm or trace IDs>
---

# /incident $ARGUMENTS

Responsible agent: `incident-responder`

## Gates

1. Diagnose before acting: the first output is an evidence-backed hypothesis, not a remediation.
2. Autonomous actions are limited to `scripts/rollback.sh <environment>` (when the latest release is the proximate cause) and `scripts/smoke-test.sh <environment>`. `rollback.sh` runs only after a human permission prompt; `smoke-test.sh` is read-only verification and runs without a prompt (docs/agents.md section 7).
3. Human escalation is mandatory for data repair or deletion, schema changes, security findings (credentials, IAM, exposure), Terraform changes, scaling beyond configured bounds, and any destructive action.
4. Timebox: if 15 minutes of investigation yields no credible hypothesis, stop and hand over the evidence dossier.
5. Never manufacture a destructive incident in a live shared course account.

## Delegation

Use the incident-responder agent to diagnose this ScholarChain incident: $ARGUMENTS. Follow its triage order (service and task state, CloudWatch alarms and logs, SQS queue depth and dead-letter queues, Step Functions executions, Neptune, DynamoDB, and S3 Vectors client errors, EMR step state). Cite evidence for every action. Write the incident report to `docs/incidents/<YYYY-MM-DD>-<slug>.md` with: incident ID, start and end time, detection source, user-visible impact, trace and run IDs, timeline, root cause, recovery action, data-integrity assessment, follow-up owner, and preventive test or control.

## After the agent returns

Route each follow-up to its owning agent through the orchestrator: code fixes to the module owner, tests to test-engineer, infrastructure to infra-engineer, and rule changes to factory-engineer.
