# Phase 12 review round 5: code-reviewer (narrow: repair-04)

Owner: code-reviewer (read-only). Delegated by the orchestrator on 2026-11-22.
Scope: infra/terraform/modules/observability/main.tf (dashboard metrics fix) and infra/terraform/modules/kms/main.tf (the S3 Vectors indexing statement),
checked against the plan `dev-8da451a8460a-20261122T214233Z.tfplan` (SHA-256 d685c6c2ebc40b7568a0284bfbe0639befefd063d2e125dbed138e12b06bc2d1).

VERDICT: APPROVE. No findings.

## Checks (reviewer)

1. Dashboard (observability/main.tf:271-370). The reviewer checked every widget, not only 3 and 4.
   - Widgets 0-5 and 7 each give arrays of string arrays, or expression rows. Widget 7 (ALB, only when enable_runtime) ends each row with a `{stat=...}` options object.
   - Widget 3 (DynamoDB, :317) gives 10 rows of 4 strings, and widget 4 (ECS, :328) gives 4 rows of 6 strings, through setproduct.
   - Widget 6 is text.
   - No metric array can hold a number or a null, and no metrics array can be empty for the inputs envs/dev passes. ALB suffixes use `coalesce(..., "none")`. Neptune uses a literal identifier.
2. KMS (kms/main.tf:93-130).
   - The statement grants only `kms:Decrypt` to `indexing.s3vectors.amazonaws.com`.
   - Conditions: ArnLike aws:SourceArn on this bucket and on `bucket/<name>/*`, which covers `bucket/<name>/index/<idx>`. Also aws:SourceAccount, and ForAnyValue:StringEquals kms:EncryptionContextKeys.
   - It is narrower than the doc example `bucket/*`.
   - The other statements are unchanged and widen no principal.
   - Wiring: `local.vector_bucket_name` = `scholarchain-dev-vectors` is passed to both module.kms (envs/dev/main.tf:51) and module.vectors (:134).
3. Scope: nothing else changed beyond the two fixes and their comments. No .tf file is newer than the plan.
4. `terraform fmt -check -recursive infra/terraform`: clean. `validate`: Success.

Reviewer limits:
- No web tool. The KMS statement shape rests on the repair-04 record (the doc was read through aws-docs) and on the reviewer's own knowledge, not a direct reading.
- The tree is untracked, so repair-04 could not be diffed on its own. The before-state comes from the repair-04 record and the replan-s3-03 plan summary.

## Risks (non-findings)

- The observability module does not check that its table and service lists are non-empty. Current inputs always supply 5 tables (locals.tf:38) and 2 services (locals.tf:55-58).
- KMS key-policy changes are eventually consistent. Terraform orders the index creates after the key update (the indexes depend on the bucket, which depends on the key). If CreateIndex still returns 403 right after the policy update, wait briefly, replan and apply again before suspecting the statement.

## Orchestrator verification (observed)

kms/main.tf:93-130 was printed. It shows exactly the statement described: `actions = ["kms:Decrypt"]`, the two SourceArn values, SourceAccount and EncryptionContextKeys.

NEXT GATE: the human signs checksum d685c6c2ebc40b7568a0284bfbe0639befefd063d2e125dbed138e12b06bc2d1, including the KMS key policy change
(a security change, docs/agents.md section 8), and applies that exact plan file outside Claude with the admin profile and locking on.
