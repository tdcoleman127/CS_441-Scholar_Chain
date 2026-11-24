## Phase 14: apply and deploy through human approval

Purpose:

Create the approved cloud environment and deploy the validated application.

Why:

Authoring infrastructure and operating infrastructure are separate duties. The deployment agent executes approved machinery and reports reality; it does not improvise unreviewed resources.

Before starting:

- Human verifies the Terraform plan checksum.
- Human confirms the target AWS account and Region.
- Human confirms cost bounds.
- Human confirms no unexplained destroy or replacement.
- Human approves the documented deployment command and approved Terraform apply path.

Use this work order after approval:

```text
Use the deploy-engineer agent to deploy the exact approved ScholarChain commit and Terraform plan to the approved course environment.

Requirements:
- Refuse a dirty working tree.
- Record commit SHA, plan checksum, target account, Region, and environment.
- Execute only approved source-controlled deployment scripts.
- Do not edit Terraform, scripts, or application source.
- Wait for service stabilization with a bounded timeout.
- Verify deployed image digests and task revisions.
- Run smoke-test.sh.
- On failure, collect evidence and follow the approved rollback policy.
- Do not perform destructive data repair.
- Return a deployment report with outputs, health status, smoke results, revisions, alarms, and any rollback action.
```

Gate:

- Services are healthy and ready.
- Deployed image digests match the commit.
- Smoke query returns an evidence-backed response using bounded fixtures or loaded sample data.
- No critical alarm remains active.
- Deployment report is archived.