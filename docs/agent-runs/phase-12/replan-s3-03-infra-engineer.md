# Phase 12 S3-backend replan, attempt 3: infra-engineer (after the failed apply and repair-04)

Owner: infra-engineer. Delegated by the orchestrator on 2026-11-22, after the human reported "permissions updated".

STATUS: COMPLETE

## Human action before this run (human-reported, 2026-11-22)

The human added S3 Vectors metadata reads to the ScholarChainReadOnly permission set's inline policy, kept the existing Deny
statements, reprovisioned and logged in again. The added actions: `s3vectors:GetVectorBucket`, `GetVectorBucketPolicy`, `GetIndex`,
`ListIndexes` and `ListTagsForResource` on `scholarchain-*` vector buckets and their indexes, and `ListVectorBuckets` on `*`.
No vector data-plane actions (`GetVectors`, `QueryVectors`, `ListVectors`) were added. This is a security change made by the human outside Terraform.
The orchestrator first had to correct a console sign-in to the wrong AWS account. No change was made in that account.

Orchestrator check (observed): `aws s3vectors list-indexes --vector-bucket-name scholarchain-dev-vectors --profile scholarchain-readonly`
returned 0 indexes. Before the fix, the plan failed with AccessDenied on ListTagsForResource.

## Plan (agent-reported marker lines)

```
Plan: 6 to add, 1 to change, 0 to destroy.
[plan] PLAN-FILE infra/terraform/envs/dev/plans/dev-8da451a8460a-20261122T214233Z.tfplan
[plan] PLAN-SHA256 d685c6c2ebc40b7568a0284bfbe0639befefd063d2e125dbed138e12b06bc2d1
[plan] DESTRUCTIVE none
[plan] RESULT status=OK plan_sha256=d685c6c2ebc40b7568a0284bfbe0639befefd063d2e125dbed138e12b06bc2d1
```

Steps 0-5 all OK: the read-only SSO identity, fmt, init (S3 backend, hashicorp/aws v6.73.0), validate, the unlocked plan (D4) and the summary.
`tree=DIRTY under infra/terraform`: the plan reflects the uncommitted Terraform at HEAD 8da451a.

Per-resource actions, from `terraform show -json` filtered to address and action only (no values, no `.variables`):

| Action | Address |
|---|---|
| update (in place) | `module.kms.aws_kms_key.data` |
| create | `module.observability.aws_cloudwatch_dashboard.this` |
| create | `module.vectors.aws_s3vectors_index.this["scholarchain-dev-collaboration-community-v1"]` |
| create | `module.vectors.aws_s3vectors_index.this["scholarchain-dev-collaboration-path-v1"]` |
| create | `module.vectors.aws_s3vectors_index.this["scholarchain-dev-paper-abstract-v1"]` |
| create | `module.vectors.aws_s3vectors_index.this["scholarchain-dev-researcher-profile-v1"]` |
| create | `module.vectors.aws_s3vectors_index.this["scholarchain-dev-topic-institution-summary-v1"]` |

Totals: 6 create, 1 update, 130 no-op, 0 delete or replace. No drift.

The KMS key policy change, in words: only the S3 Vectors indexing statement changes. Its `aws:SourceArn` ArnLike condition goes from the
bucket ARN alone to the bucket ARN plus `bucket/scholarchain-dev-vectors/*`. Every other statement and attribute is unchanged.
This is a KMS security change (docs/agents.md section 8).

The `data_stores` output appears as new. The failed apply never saved it to state. Outputs are not resources.

jq is not installed, so the agent wrote a small Python filter in the scratchpad (run with `python -I`). The unredacted plan log stays in the scratchpad and is not evidence.

## Orchestrator verification (observed, 2026-11-22)

- `sha256sum infra/terraform/envs/dev/plans/*.tfplan`: exactly one file, `dev-8da451a8460a-20261122T214233Z.tfplan`,
  `d685c6c2ebc40b7568a0284bfbe0639befefd063d2e125dbed138e12b06bc2d1`. This matches the marker. The consumed plan (ee69596e...) and the errored plan are deleted.
- `find infra/terraform -name '*.tf' -newer <plan file>` (excluding .terraform/): no output.
- Email-pattern search of the raw plan log: 0 matches.

## Next gate

Code-reviewer round 5 on the repair-04 fixes. Then the human signs this checksum, including the KMS key policy change, and applies
this exact file outside Claude with the admin profile and locking on.
