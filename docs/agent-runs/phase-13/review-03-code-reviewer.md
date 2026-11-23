# Phase 13 review round 3: code-reviewer (narrow: repair-02, repair-03, repair-03b)

Owner: code-reviewer (read-only). Delegated by the orchestrator on 2026-11-23. `bash -n` passed on all 8 scripts.

VERDICT: REQUEST_CHANGES (0 CRITICAL, 0 MAJOR, 1 MINOR)

## Earlier findings
- N1 FIXED. Redaction can no longer touch hex. A MISSING or ALTERED plan checksum gives exit 4 and no upload.
- N2 FIXED for all five lenient revocation forms; approval unchanged and strict.
- N3 FIXED. `--metric-queries` must be inside experiments/metrics/; prefix-named directories are refused.
- N4 FIXED for every security-relevant type in use. The match is anchored, so aws_lb_target_group is not matched as lb.
- repair-03 and repair-03b: correct. `cygpath -m` gives one form for `/c/`, `C:/` and `/tmp` aliases; comparisons are lower-cased and use `/`-terminated prefixes. No weakened refusal and no new fail-open found. On Linux canon_form is the identity.

## New finding

| ID | Sev | File | Finding |
|---|---|---|---|
| R3-1 | MINOR | deploy.sh:178, 181-191 (header :36-41) | The revocation search has no `--text`. For a gate blob git treats as binary (a NUL byte in the first 8000 bytes), git grep prints only `Binary file … matches` with exit 0. The line parser sees no token and no checksum, so the revocation is ignored, while approval (`-l`) still matches. A revocation in a UTF-16 gate (PowerShell 5.1 `>` default) also never matches |

## Reviewer notes (non-findings)
- Routes, route tables, the internet gateway, ECS `assign_public_ip` and subnet `map_public_ip_on_launch` are outside SECURITY-ACTION (human decision). Exposing tasks directly would also need an api security group ingress change, which does print SECURITY-ACTION.
- On a case-sensitive filesystem, `Experiments/` would pass as `experiments/` (still inside the repo; manifests must be tracked).
- archive-evidence.sh with neither `--plan-log` nor `--plan-file` still archives with no plan checksum (by design; the gate runner must pass the flags).
- A UNC `--out` is unverified. The sts lookup, metrics export and upload have never run against AWS.
