# Phase 13 review round 1: code-reviewer

Owner: code-reviewer (read-only). Delegated by the orchestrator on 2026-11-22. Scope: the 4 new and 4 modified scripts against HEAD 71978f2.
Agent-reported duration: about 12 min. `bash -n` passed on all 8 scripts.

VERDICT: REQUEST_CHANGES (4 MAJOR, 6 MINOR)

| ID | Sev | File | Finding |
|---|---|---|---|
| F1 | MAJOR | deploy.sh:152-157 | Approval check is an unanchored substring search across every phase's gate. Any 64-hex value passes: the phase-03 patch checksum, the post-apply no-change plan, the consumed plan ee69596e. Approvals never expire |
| F2 | MAJOR | smoke-test.sh:179-189 | Not-ready state: UnavailableGraphStore gives 502 DOWNSTREAM_FAILURE (503 only after the breaker opens at 5 failures), but only 503 counts as EXPECTED-FAIL. A correct fresh service fails the smoke test, nondeterministically |
| F3 | MAJOR | archive-evidence.sh:107-108, 163-177, 308-326 | `--experiment-dir`, `--package-dir` and `--run-record` accept any path. `~/.aws` CLI and SSO caches (session credentials, SSO token) would be archived and, with `--upload`, sent to S3 |
| F4 | MAJOR | plan.sh:220 | IAM summary matches only `aws_iam_*` and `*_policy` types. Inline policies (KMS key policy, SQS, Secrets Manager, VPC endpoint, ECR) are missed. Plan d685c6c2 (a KMS key-policy security change) would print `IAM-ACTION none` |
| F5 | MINOR | plan.sh:251-255 | Fallback for unparsed destroy or replace lines runs only when nothing else was parsed (deposed objects, for_each keys with spaces). `aws_sqs_queue` missing from STATEFUL_TYPE_RE. Exit 4 still holds |
| F6 | MINOR | run-bounded-pipeline.sh:793-798 | Two exits after SUCCEEDED write no record. FAILED records have an empty `steps=` |
| F7 | MINOR | run-bounded-pipeline.sh:643-668 | Records carry full S3 URIs whose bucket names embed the 12-digit account ID |
| F8 | MINOR | archive-evidence.sh:143, 386-399 | `--upload` accepts any bucket and proceeds even when the archive has failures |
| F9 | MINOR | archive-evidence.sh:337-338 | Test-report harvest re-collects earlier in-repo archives; `--out` can be any non-protected repo path (other owners' trees) |
| F10 | MINOR | run-experiment.sh:116-118, 505, 551 | Redacts every standalone 12-digit number, rewriting raw metric values. Contradicts immutable raw results |

## Reviewer risks (non-findings)
- check.sh is untested from a clean checkout without a provider cache.
- With SCHOLARCHAIN_SMOKE_REQUIRE_READY=0, an always-503 query path still exits 0. The spec gate needs REQUIRE_READY=1.
- experiments/results/ is neither committed nor ignored, so deploy.sh and package.sh refuse a dirty tree after any experiment until the results are committed.
- The smoke API key needs both `query:write` and `evidence:read`.
- package.sh `--push` only warns on an "admin" profile, while the other two scripts refuse one.
- Never executed: package `--push`, archive `--upload`, metrics export, the copy and redaction paths, and run-experiment's own exit 3.

## Checked and found sound (reviewer)
- package.sh push preflight.
- run-experiment.sh parser, allowlist and containment.
- smoke-test.sh field names, codes, headers and request budget against the service code.
- check.sh offline Terraform handling.
- plan.sh last-4 printing and record-file containment.
- Ownership: every change is under scripts/**.

## Orchestrator verification (observed)
- F1: `git grep -l -F -e 00cf69c9…856ad HEAD -- 'docs/agent-runs/*/human-gate.md'` returns phase-03/human-gate.md. A non-plan checksum satisfies the check.
- F2: ApiError.scala:55 maps DownstreamFailure to 502 DOWNSTREAM_FAILURE. Resilience.scala:98 and :100 map Unavailable and Failed to it.
