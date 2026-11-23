# Phase 13 review round 2: code-reviewer (narrow: repair-01)

Owner: code-reviewer (read-only). Delegated by the orchestrator on 2026-11-22. Agent-reported duration: about 9 min. `bash -n` passed on all 8 scripts.

VERDICT: REQUEST_CHANGES (0 CRITICAL, 0 MAJOR, 4 MINOR)

## Round 1 findings
- FIXED: F1 (exact anchored approval at HEAD; CRLF harmless because of `.gitattributes`), F2, F4 (every policy-bearing type in use today), F5, F6 (by reading), F7 (records; stdout still full), F8, F9, F10 (run-experiment), and the package admin refusal.
- PARTLY FIXED: F3 (`--metric-queries` still uncontained, see N3).

## New findings

| ID | Sev | File | Finding |
|---|---|---|---|
| N1 | MINOR | archive-evidence.sh:236-246 | `redact_file` replaces every 12-digit run, including inside SHA-256s, git SHAs and digests (about 2.6% of SHA-256 values). Example: phase-01 repair-01's real hash `adba…cb6a8d185558526519f33d…` loses its 64-hex form, so the plan cross-check is silently skipped. This affects the spec gate "plan checksum is archived" |
| N2 | MINOR | deploy.sh:160, :172 | Revocation uses the same strict whole-line match as approval. `REVOKED-DEPLOY-PLAN-SHA256: <sha> env=dev`, a `- ` list prefix, a trailing space, backticks or an uppercase sha are not recognized, and the deploy proceeds (fail-open) |
| N3 | MINOR | archive-evidence.sh:153, 309, 433 | `--metric-queries` copies any `*.json` (e.g. `~/.claude/.credentials.json`) into the archive before the AWS call. Upload is blocked (FAILURES>0), but the file lands in a committable tree |
| N4 | MINOR | plan.sh:222-230 | `aws_s3_bucket_public_access_block` (2 in use) and security groups and rules are in no summary category. Turning off block_public_policy prints `IAM-ACTION none` and RESULT OK |

## Reviewer risks (non-findings)
- A UNC admin-share spelling of `--out` might count as outside the repository (unverified).
- 502 DOWNSTREAM_FAILURE is accepted as not-ready for any dependency. The spec gate needs REQUIRE_READY=1 and a working graph store.
- experiments/results/ and the new reports/deployments records dirty the tree (still open).
- Never executed: F6, F8, the package admin refusal, and `aws s3 cp` without --expected-bucket-owner (head-bucket and list do check the owner).

## Orchestrator verification (observed)
archive-evidence.sh:236-246 read: `sed -E 's/(^|[^0-9])[0-9]{12}([^0-9]|$)/…<account-redacted>…/g'`. A 12-digit run between hex letters matches, as N1 states.
