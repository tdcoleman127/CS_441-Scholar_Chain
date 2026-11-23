# Phase 13 repair-04: infra-engineer (review-03 R3-1)

Owner: infra-engineer. Delegated by the orchestrator on 2026-11-23. Agent-reported duration: about 4.5 min. File: scripts/deploy.sh only.

STATUS: COMPLETE

## Fix (agent-reported)
- :36-46: the header documents `git grep --text` revocation and the plain-text requirement.
- :118: `od` and `wc` added to the required tools.
- :175-195: new plain-text check before both searches. Every committed gate at HEAD must have its blob size equal to its size after NUL removal (a NUL anywhere means binary or UTF-16), and must not start with an `fffe` or `feff` BOM. Each refusal is `die 3` and names the file. An enumeration error refuses.
- :199, :207: any `Binary file` output line refuses. These are unreachable after the check and verified by inspection only.
- :205: the revocation search uses `--text`. Approval stays strict and revocation stays lenient.

## Evidence (agent-reported)
Harness p13r03/approval-harness.sh: 21/21.
- The real repository at HEAD: bare checksums refused; every committed gate passes the plain-text check, so there is no false refusal today.
- Strict approval cases unchanged.
- All seven lenient revocation forms revoke.
- Refused, naming the file: a gate with approval, revocation and a NUL byte; a NUL after 9000 bytes in an unrelated gate; UTF-16LE with and without a BOM; FE FF without NULs.
- Control: the pre-fix block deploys (exit 0) in both R3-1 scenarios.

## Orchestrator verification (observed, 2026-11-23)
- `bash -n scripts/deploy.sh`: ok.
- Harness rerun by the orchestrator: `SUMMARY pass=21 fail=0`. That includes the phase-93 (UTF-16LE without a BOM) and phase-95 (FE FF BOM) refusals naming the file, and both OLD control cases deploying, which reproduces R3-1.

Note for the human: write gate files as UTF-8 (VS Code default). Windows PowerShell 5.1 `>` writes UTF-16LE, which deploy.sh now refuses.
