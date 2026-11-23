# Phase 13 repair-01: infra-engineer (review-01 F1-F10)

Owner: infra-engineer. Delegated by the orchestrator on 2026-11-22. Agent-reported duration: about 18 min.

STATUS: COMPLETE. All ten findings fixed, in scripts only. Offline harnesses are in the scratchpad (p13r01/).

| ID | Fix (agent-reported) | Evidence |
|---|---|---|
| F1 | deploy.sh:155-178. It needs the exact committed line `APPROVED-DEPLOY-PLAN-SHA256: <sha> env=<env>` and refuses if `REVOKED-DEPLOY-PLAN-SHA256: <sha>` is committed. This git has no `git grep -x`, so the agent used an anchored ERE after a character-class check. A git grep error fails closed | Harness: bare checksums (phase-03 patch, d685c6c2), the wrong env, a revoked line, a sha embedded in other text, and an uncommitted line all exit 3. A committed line exits 0 |
| F2 | smoke-test.sh:170-176, 196-250. EXPECTED-FAIL only for 502 DOWNSTREAM_FAILURE, 503 DEPENDENCY_UNAVAILABLE and 503 NOT_READY, by status plus code. The header now states the key needs query:write and evidence:read | Harness: the three pairs give EXPECTED-FAIL; 503 OVERLOADED, 502 NOT_READY, 500, 504 and 403 give FAIL |
| F3 | archive-evidence.sh. Inputs are realpath-contained (run records under reports/deployments/, experiment dirs exactly experiments/results/<id>/<run-id>, package dirs must hold a scholarchain.package.v1 manifest). Paths containing .aws, .ssh, .docker, .kube, .gnupg or secrets are denied | Harness: `.aws/sso/cache`, `.AWS`, `.ssh` and `secrets/` refused. The symlink case is untested (MSYS ln) |
| F4 | plan.sh:230. Policy-bearing types (KMS key, SQS, Secrets Manager, VPC endpoint, ECR, S3 bucket and vector bucket policy, SNS, Lambda permission) print `IAM-ACTION (policy-bearing)` | d685c6c2-shaped fixture prints `IAM-ACTION (policy-bearing) update module.kms.aws_kms_key.data` |
| F5 | plan.sh:270-294. Every unclassified action line is always reported (UNPARSED-ACTION; destroy or replace becomes STATEFUL-DESTRUCTIVE or DESTRUCTIVE). aws_sqs_queue added to the stateful types | Mixed fixture: the for_each key with a space and the deposed object are both reported; RESULT DESTRUCTIVE |
| F6 | run-bounded-pipeline.sh:770, 815-827. Records written for SUCCEEDED-OUTPUT-UNREADABLE and SUCCEEDED-STEP-COUNT-MISMATCH; FAILED records list steps | bash -n only (these paths need AWS) |
| F7 | run-bounded-pipeline.sh:636-650. Record URIs show the account as `xxxxxxxx<last4>` | Harness with a dummy account |
| F8 | archive-evidence.sh. Upload only to a `scholarchain-` bucket owned by the caller's account (head-bucket with --expected-bucket-owner); refused when FAILURES>0 | bash -n only |
| F9 | archive-evidence.sh. An in-repo `--out` only under reports/releases or reports/deployments; the harvest covers only modules/*/target/test-reports and tests/**/target/test-reports | Harness: earlier archives and experiments/results excluded |
| F10 | run-experiment.sh:118-131. Redacts only the caller's account; when unknown, only ARN, ECR and bucket contexts | Harness: 12-digit metric values unchanged in both modes |
| extra | package.sh:207-210. `--push` refuses a profile name containing "admin" | bash -n only |

## Agent-reported residual risks
- run-bounded-pipeline still prints full URIs, including the account, on stdout (records are redacted).
- The policy-bearing list is fixed and over-reports (any update of those types is flagged).
- Security-group changes are not flagged as IAM-ACTION.
- F6, F8 and the package admin refusal have never run.
- experiments/results/ dirtying the tree is still open (build-engineer `.gitignore` or a human decision).

## Orchestrator verification (observed, 2026-11-22)
- `bash -n` on all 8 Phase 13 scripts: ok.
- deploy.sh:155-178 read: exact anchored approval and revocation lines, both git grep errors fail closed.
- Plan harness rerun by the orchestrator:
  - plan-kms-only.txt gives `IAM-ACTION (policy-bearing) update module.kms.aws_kms_key.data`, `DESTRUCTIVE none`.
  - plan-mixed.txt gives `unparsed=2`, both UNPARSED-ACTION lines, STATEFUL-DESTRUCTIVE for the SQS replace and the bucket, DESTRUCTIVE for the deposed object, RESULT DESTRUCTIVE.
- `git grep -E '^APPROVED-DEPLOY-PLAN-SHA256: ' HEAD -- 'docs/agent-runs/*/human-gate.md'`: no match (rc=1), so deploy.sh currently refuses every checksum.
