# Phase 13 dry runs: experiment-engineer

Delegated by the orchestrator on 2026-11-22. infra-engineer is not allowed to run run-experiment.sh or archive-evidence.sh (docs/agents.md section 7).
No AWS calls; nothing committed.

STATUS: BLOCKED-ON human decision for dry run 1 (no defect). Dry run 2 passed.

## 1. run-experiment.sh with a manifest outside experiments/
- Command: `bash scripts/run-experiment.sh <scratchpad>/exp-outside.json`
- Result: refused by guard-dangerous.sh before the script started: "scripts/run-experiment.sh runs only committed manifests under experiments/".
- The script's own exit-3 refusal (run-experiment.sh:211-221) was therefore not exercised. `git status --short experiments/` was empty before and after.
- Decision for the human: accept the guard refusal as the evidence, or run the same command yourself outside Claude to show the script-level exit 3.

## 2. archive-evidence.sh, local only
- Command: `bash scripts/archive-evidence.sh --out <scratchpad>/archive-dryrun-1 --no-test-reports`
- Result: exit 0, `RESULT status=ARCHIVED`, git_sha 71978f2, tree=dirty (uncommitted Phase 13 scripts). The archive holds release.txt, redactions.txt (empty) and SHA256SUMS (2 entries).
- Not exercised: the copy and redaction paths (copied=0), metrics export, upload.

## Orchestrator verification (observed)
`sha256sum -c SHA256SUMS` in the archive directory: redactions.txt OK, release.txt OK.
