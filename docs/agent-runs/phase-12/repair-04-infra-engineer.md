# Phase 12 repair-04: infra-engineer (apply failures)

Owner: infra-engineer. Delegated by the orchestrator on 2026-11-22, after the human's apply of the signed plan
`dev-8da451a8460a-20261122T192256Z.tfplan` (SHA-256 ee69596e...95db) failed partway.
Agent-reported elapsed time: 14:00:46 to 14:07:47 CST (about 7 min).

STATUS: BLOCKED-ON human (read-only permission set lacks S3 Vectors metadata reads). Both code fixes are done; no valid plan yet.

## Apply failure (human-reported, 2026-11-22)

Everything else was created. Two failures:
1. `module.observability.aws_cloudwatch_dashboard.this`: PutDashboard InvalidParameterInput, 128 errors on widgets 3 and 4
   ("metrics has to be an array of array of strings").
2. `module.vectors.aws_s3vectors_index.this[*]` (all 5): CreateIndex 403 "Insufficient access to perform asynchronous indexing ...
   resource policy granting access to the S3 service principal 'indexing.s3vectors.amazonaws.com'".
The signed plan is consumed by the failed apply and must not be reused.

## Fixes (agent-reported; orchestrator-observed in the files)

| Fix | Change |
|---|---|
| Dashboard | observability/main.tf:317 (DynamoDB) and :328 (ECS): `flatten(...)` (recursive; collapsed the rows into loose strings, giving 40 + 24 = 64 bad entries, matching the errors) replaced by `[for p in setproduct(...) : [...]]`. A local `terraform console` check with dummy inputs (no state, no AWS) shows every row is an array (10 DynamoDB rows, 4 ECS rows). The other widgets were checked by hand. |
| KMS indexing statement | kms/main.tf:111-117: `aws:SourceArn` ArnLike now accepts `bucket/scholarchain-dev-vectors` and `bucket/scholarchain-dev-vectors/*` (index ARNs). Previously only the exact bucket ARN, which index ARNs cannot match. Action stays `kms:Decrypt` only. SourceAccount and EncryptionContextKeys are unchanged. Source: https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-vectors-data-encryption.html (read through aws-docs MCP; the doc example uses `bucket/*`, narrowed here to this bucket and its children). In-place update of `module.kms.aws_kms_key.data`, not a replacement. This is a KMS security change (docs/agents.md section 8: human sign-off). |

Validation: `terraform fmt -check` exit 0 and `validate` Success (both also observed by the orchestrator); checkov 552 passed / 0 failed / 46 skipped (unchanged).

## Plan attempt: FAILED during refresh

`bash scripts/plan.sh dev` exit 3. Partial output before the error: `1 to add, 1 to change, 0 to destroy` (dashboard create; KMS key policy update);
the 5 indexes were not evaluated. Redacted error:

```
Error: listing tags for S3 Vectors Vector Bucket (arn:aws:s3vectors:us-east-1:<ACCOUNT_ID>:bucket/scholarchain-dev-vectors)
  with module.vectors.aws_s3vectors_vector_bucket.this
operation error S3Vectors: ListTagsForResource, StatusCode: 403, AccessDeniedException:
User: arn:aws:sts::<ACCOUNT_ID>:assumed-role/AWSReservedSSO_ScholarChainReadOnly_<suffix>/<session> is not authorized
to perform: s3vectors:ListTagsForResource ... because no identity-based policy allows the s3vectors:ListTagsForResource action
```

Cause: the ScholarChainReadOnly permission set (AWS managed ReadOnlyAccess plus inline Denies, docs/agents.md section 9) does not grant
`s3vectors:ListTagsForResource`, which the provider needs to refresh the vector bucket (and later the indexes). This only surfaced now because the bucket exists.

## Orchestrator verification (observed, 2026-11-22)

- `terraform fmt -check -recursive infra/terraform`: exit 0. `terraform -chdir=infra/terraform/envs/dev validate`: Success.
- observability/main.tf:317 and :328 use setproduct; the only remaining `flatten` mention is the explanatory comment at :315. kms/main.tf:111-117 has both SourceArn values.
- plans/ holds `dev-8da451a8460a-20261122T192256Z.tfplan` (consumed, stale) and `dev-8da451a8460a-20261122T200621Z.tfplan`
  (ERRORED, incomplete, not applyable). Neither may be signed. Both are to be deleted by the next successful plan run.

## Manual action (human)

1. Add S3 Vectors metadata-read actions (no data-plane actions) to the ScholarChainReadOnly permission set's inline policy,
   keeping the existing Deny statements, and reprovision. This is a security change made by the human outside Terraform.
2. docs/agents.md section 9 describes the permission set's contents. A factory-engineer amendment should record the added
   read actions (constitutional; human ratification).
3. Then: infra-engineer replans (expected 6 add / 1 change / 0 destroy), code-reviewer round 5, human sign-off, and the human applies.
