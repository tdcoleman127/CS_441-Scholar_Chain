# Phase 13 repair-03: infra-engineer (path-form canonicalization)

Owner: infra-engineer. Delegated by the orchestrator on 2026-11-22, for the defect the orchestrator observed during the repair-02 verification.
Agent-reported duration: about 6 min. File: scripts/run-experiment.sh only.

STATUS: COMPLETE

## Defect
On the reference machine, `git rev-parse --show-toplevel` and realpath of it print `C:/…`, while realpath of a relative path prints `/c/…`.
run-experiment.sh compared the two, so every valid relative manifest would have been refused (fail closed; Phase 15 blocked).

## Fix (agent-reported)
- :211-231: new helpers.
  - `canon_form` runs `cygpath -m` when cygpath exists.
  - `canon_path` is realpath plus canon_form.
  - `under_dir` is a case-insensitive strict containment check.
- :247-253: REPO_ROOT and MANIFEST_ABS are both canonical, and REPO_ROOT must be absolute. The ~<digit>, symlink, .json and tracked-file refusals are unchanged.
- New containment checks:
  - :313-315: the load-tests entrypoint must be inside load-tests/.
  - :363-372: metric_queries must be inside experiments/, not results/, and not a symlink.
  - :449-457: the results parent must equal experiments/results/<id>, with no symlinks.
- Deviation, based on evidence: the agent used `cygpath -m`, not `-u`. `-u` maps `C:/Users/…/AppData/Local/Temp` to `/tmp` through the MSYS mount table, so paths still spelled differently (harness 21/28 with `-u`).

## Evidence (agent-reported)
Harness p13r03/containment-harness.sh, run on extracted blocks with a git shim: 29/29 PASS.
- Accepted: relative, POSIX-absolute and drive-absolute paths inside experiments/, including a different case.
- Refused: ../ escapes, results/, the scratchpad, the repo root, .txt, `EXPERI~1`, missing files and directories.
- Control: the old logic refuses a valid relative manifest.
- Not tested: symlink and junction refusals (Git Bash cannot create symlinks by default).

## Orchestrator verification (observed, 2026-11-22)
- `bash -n scripts/run-experiment.sh`: ok.
- Harness rerun by the orchestrator: `SUMMARY pass=29 fail=0`. The real repository's canonical toplevel and scripts/plan.sh are both in `C:/…` form, and plan.sh is under the root.
- `cygpath -u "C:/Users/figge/AppData/Local/Temp/claude"` prints `/tmp/claude`; `cygpath -m "/c/Users/figge/AppData/Local/Temp/claude"` prints `C:/Users/figge/AppData/Local/Temp/claude`. That confirms the agent's latent risk in archive-evidence.sh (`canon_form` uses `-u`). Routed as repair-03b, archive-evidence.sh only.

## repair-03b: archive-evidence.sh canon_form `-u` to `-m` (2026-11-23)

STATUS: COMPLETE

Fix (agent-reported):
- :211-221: canon_form uses `cygpath -m`.
- :482: the harvest exclusion also canonicalizes.
- Every other call site was reviewed and needed no change.

Finding (agent-reported): with the old `-u`, a repository under the user Temp directory (mounted as /tmp) would accept a relative `--out .claude/x` or `--out docs/agent-runs/x`. REPO_ROOT became `/tmp/…` while OUT_ABS stayed `/c/…`, a protected-path bypass. The live repository is not under a mount.

Evidence (agent-reported):
- archive-temp-harness.sh: 40/40, including two control cases that show the old code accepting those paths.
- p13r02 metric-queries harness: 15/15 by inspection.

Orchestrator verification (observed, 2026-11-23):
- `bash -n` ok. canon_form at :220 is `cygpath -m`. The only other cygpath calls (:504, :562) are `-w` conversions for the AWS CLI.
- Harness rerun by the orchestrator, invoked with `C:/` paths: `SUMMARY pass=38 fail=2`. Both failures are the two OLD `-u` control cases, which expect the old code to accept. In this invocation the old code also refused, because OUT_ABS resolved to `/tmp/…`. The old bypass therefore depends on how the working directory is spelled. All 38 cases for the new code passed, including every protected-path refusal.
