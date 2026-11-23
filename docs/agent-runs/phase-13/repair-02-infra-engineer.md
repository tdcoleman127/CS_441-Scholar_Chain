# Phase 13 repair-02: infra-engineer (review-02 N1-N4)

Owner: infra-engineer. Delegated by the orchestrator on 2026-11-22. Agent-reported duration: about 12 min.
Files: scripts/archive-evidence.sh, deploy.sh, plan.sh. Harnesses are in the scratchpad (p13r02/).

STATUS: COMPLETE

| ID | Fix (agent-reported) | Evidence (agent-reported) |
|---|---|---|
| N1 | archive-evidence.sh:260-283, 362-375, 404-422. Redacts the caller's exact account (from a read-only sts lookup, never printed), bounded by non-alphanumerics; otherwise only ARN, ECR and bucket contexts. A missing or altered archived plan checksum prints PLAN-CHECKSUM MISSING or ALTERED and counts as a failure (exit 4, no upload) | The real hash `adba…636bc` is unchanged in both modes, even with the account set to its inner 12-digit run. 40-digit SHAs, digests and 12-digit metrics unchanged |
| N2 | deploy.sh:160-192. Approval unchanged (strict). Revocation is `git grep -i -F` on the sha; the matched line must also contain `revoked-deploy-plan-sha256` (lowercased). No regex is built from input. A git grep error refuses | Five lenient forms (env suffix, list prefix, trailing space, backticks, uppercase) all revoke. Approval cases unchanged |
| N3 | archive-evidence.sh:345-354, 498-501. `--metric-queries` must realpath into `experiments/metrics/**.json` | Outside `.credentials.json`, `.aws`, a root .json, a `../` escape and a .txt are refused; relative, POSIX and drive-form paths inside are accepted |
| extra | archive-evidence.sh:215-217, 224, 299, 301. `canon_form` (`cygpath -u`): git toplevel prints `C:/…` but realpath of a relative path prints `/c/…`. Before the fix, relative inputs were wrongly refused, and a relative `--out .claude/…` or `docs/agent-runs/…` was treated as outside the repo (fail open, inferred, not run on the old code) | Relative and drive-form `--out` into .claude, docs/agent-runs and modules are refused; reports/releases accepted |
| N4 | plan.sh:232-238, 245, 278, 296, 313-314, 334. New SECURITY-ACTION category: public access blocks, ownership controls, bucket ACLs, security groups and rules, network ACLs, load balancers, listeners and listener rules | Fixture prints SECURITY-ACTION for the public-access-block update, the ingress rule and the listener. All 8 security-relevant types in use are covered. Earlier fixtures unchanged |

Agent-reported risks:
- Routes, route tables and the internet gateway are not in SECURITY-ACTION (human decision).
- SECURITY-ACTION over-reports.
- The new sts call, metrics and upload have never run against AWS.
- The path-form issue may affect other scripts (not checked; outside file scope).

## Orchestrator verification (observed, 2026-11-22)
- `bash -n` ok on archive-evidence.sh, deploy.sh, plan.sh and run-experiment.sh.
- Path forms confirmed: `realpath -e -- "$(git rev-parse --show-toplevel)"` prints `C:/Users/…/CS_441-Scholar_Chain`, and `realpath -e -- scripts/plan.sh` prints `/c/Users/…/scripts/plan.sh`.
- Follow-on check of the other scripts:
  - run-experiment.sh:225-227 compares a `C:/` REPO_ROOT with a `/c/` MANIFEST_ABS, so every valid relative manifest would be refused (fail closed). Routed to repair-03.
  - package.sh, check.sh, deploy.sh and run-bounded-pipeline.sh use cygpath only to hand paths to native tools, so they are unaffected.
