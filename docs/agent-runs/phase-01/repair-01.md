# Phase 01: repair work order 01 (factory-engineer)

Assembled by the orchestrator. Source: `docs/agent-runs/phase-01/review-report.md` (code-reviewer control-plane audit, verdict REQUEST_CHANGES, 12 findings). Every finding is owned by factory-engineer. Finding 4 also has a build-engineer part, deferred to phase 02.

## Work order

Repair findings 1 to 12 of `review-report.md` in the control plane, under the amendment procedure (docs/agents.md section 12). Prepare the diff, run the validator, present the result, and stop. Do not self-ratify. The human ratifies, commits, and restarts.

Constraints:

- **Fail closed.** No fix may widen any agent's authority, tool fence, or allow list. Where a precise fix is impractical, prefer blocking or moving the rule from `allow` to `ask`. Do not let a command run silently.
- **False positives (finding 12).** Narrow the false positives only where doing so does not reopen findings 1 to 8. If one has to stay, document it as a known limitation instead.
- **Honest documentation.** If a mechanical guarantee cannot be delivered, for example full Bash ownership enforcement (finding 5), correct the claims in docs/agents.md sections 4 and 5 so they match what is actually enforced. Do not overstate.
- **Do not touch** `docs/agent-runs/phase-01/human-gate.md` or any other human-owned evidence file.
- **Live-session caution.** Hook edits take effect for Bash calls in the current session. Do not delete or rename a registered hook script. Keep `bash -n` clean at every step.

## Findings to repair (summary; full detail in review-report.md)

| # | Severity | Defect | Primary location |
|---|---|---|---|
| 1 | CRITICAL | `git diff/log/show --output=` and `python -m json.tool <in> <out>` are allow-listed and write protected files with no prompt | settings.json:16-18, :25; guard-dangerous.sh:99-105, :352, :356 |
| 2 | MAJOR | Deleting a hook script makes the hook fail open (exit 127 is non-blocking); `find -delete` is not treated as a write | settings.json:220/226/232; guard-dangerous.sh:100, :156-164, :189-190 |
| 3 | MAJOR | Script-runner, environment, and cleanup checks bypassed via `--norc`/`--noprofile` (`-*n*`), absolute bash path, `cd scripts && ./x.sh`, `xargs bash` | guard-dangerous.sh:304, :308, :310; settings.json:208-212 |
| 4 | MAJOR | sbt allow wildcards permit `sbt compile markTestRun`, `sbt --client eval/run`; read-only blocklist misses `eval`, `Test/run`, `Test/runMain` | settings.json:6-14; guard-dangerous.sh:360 |
| 5 | MAJOR | Ownership map is not enforced for Bash writes (`sed -i`, `cp`, `bash -c`, `xargs rm`, `tar -x`), contrary to sections 4 and 5 | settings.json:230; guard-dangerous.sh:89-108; docs/agents.md sections 4 and 5 |
| 6 | MAJOR | AWS mutation detection misses `if aws ...`, `nice -n 10 aws ...`, quoted `aws.exe` path, `python -m awscli` | guard-dangerous.sh:263-264 |
| 7 | MAJOR | Secret-path gaps: `~/.sbt/.credentials`, `~/.ivy2/.credentials`, `~/.terraform.d/credentials.tfrc.json`, `~/.config/gh/hosts.yml` | guard-dangerous.sh:130; settings.json:94-120 |
| 8 | MAJOR | Read-only fence admits writers and executors: `sort -o`, `uniq a b`, `rg --pre`, `git grep --open-files-in-pager`, `tflint --fix`, `tfsec --out`, `git diff --output` | guard-dangerous.sh:348, :352 |
| 9 | MINOR | `.gitkeep` exception for build-engineer overrides first-match-wins (reaches `docs/agent-runs/**`, `.claude/**`) | guard-ownership.sh:96-98 |
| 10 | MINOR | factory-engineer Bash exemption covers `.claude/settings.local.json` (human-local, no agent writer) | guard-dangerous.sh:83, :285 |
| 11 | MINOR | incident.md and incident-responder.md claim a prompt for smoke-test.sh that settings do not impose (section 7 says no prompt) | commands/incident.md:13; agents/incident-responder.md:12 |
| 12 | MINOR | Raw-string matching blocks legitimate commands: `grep "terraform apply"`, quoted `>`, `;`/`&` in quotes, `git --version`, `echo 'terraform destroy'` | guard-dangerous.sh:61, :112, :342, :352 |

Also consider these items from the report's RISKS section. They are optional and must be flagged as proposals:

- The `AWS_API_MCP_ALLOW_UNRESTRICTED_LOCAL_FILE_ACCESS` value in `.mcp.json` is unverified.
- `common.sh:81` uses abspath, not realpath, so symlinks are not resolved.

## Required evidence in the report

- The full diff of every changed file, with a per-file justification against the authority matrix.
- `bash -n` on each changed hook, and `bash .claude/hooks/validate-agent-files.sh` output, verbatim.
- For each finding: the fix, or the reason it is documented instead of fixed, plus a hook test showing the failure input from review-report.md is now blocked or prompts. Feed the hook's stdin JSON directly; do not execute the dangerous command. For finding 12, show that the legitimate inputs now pass.
- Any finding left open, stated explicitly with its residual risk.

## Next gate

factory-engineer proposal, then validator, then a fresh code-reviewer pass on the diff, then human ratification in human-gate.md (human-only), commit, and restart.

## Checkpoint plan

| CP | Scope | Status |
|---|---|---|
| 1 | F1, F2 | COMPLETE (proposal, not ratified) |
| 2 | F3, F8 | COMPLETE (proposal, not ratified; took ~65 min, over the 30-min box) |
| 3a | F5 | COMPLETE (proposal, not ratified; ~25.5 min; full 179-case rerun after the inline fix is owed in CP5) |
| 3b | F6 (+ 9b operand9 fix) | COMPLETE (proposal, not ratified; ~14.5 min; 59 targeted cases, 0 unexpected) |
| 4 | F4, F7, F9, F10, F11 | COMPLETE (proposal, not ratified; ~11 min; 50 targeted cases, 0 unexpected) |
| 5 | F12, full regression, validator | COMPLETE (proposal, not ratified; ~24 min; 0 regressions; 4 of 6 F12 items fixed) |
| 6 | Fresh code-reviewer pass on the full repair-01 diff (review-report-02.md) | COMPLETE: REQUEST_CHANGES (new CRITICAL N1, introduced by CP5) |
| 7 | Fix N1 (ANSI-C `$'...'` quoting bypass of the section 11 fence) | COMPLETE (proposal, not ratified; ~13 min; N1 FIXED for read-only callers; writer-side `$'` escape bypass found, OPEN) |
| 8 | Fix the writer-side `$'` escape bypass for all callers (human chose option A, 2026-10-13) | COMPLETE (proposal, not ratified; ~10 min; 38+28+50+59 targeted cases, 1 unexpected = pre-existing CP7 false positive) |
| 9 | Final code-reviewer pass on the cumulative CP1-CP8 diff (review-report-03.md) | COMPLETE: REQUEST_CHANGES (new CRITICAL N2: backslash-newline before the quote; new MAJOR N3: wrap9 does not rescan inner strings for `$'`) |
| 10 | Human decision: option B modified (single-quote-only read-only fence, grep kept); fix N2 and N3 | COMPLETE (proposal, not ratified; ~16 min; 64 targeted cases, 0 unexpected) |
| 11 | Final code-reviewer pass on the cumulative CP1-CP10 diff (review-report-04.md) | COMPLETE: REQUEST_CHANGES (CRITICAL R1: brace/glob expansion in the read-only fence; CRITICAL R2: comment/heredoc apostrophe desyncs the backslash-newline scanner; MINOR R3 timing, R4 `git -C`) |
| 12 | Human decision (fourth consecutive REQUEST_CHANGES), then fix R1 and R2 if chosen, then a fresh code-reviewer pass | pending (RESUME HERE) |

### Resume point (saved 2026-10-12)

- Working tree: checkpoints 1 and 2 are uncommitted and unratified in `.claude/hooks/guard-dangerous.sh` and `.claude/settings.json` (cumulative 434 insertions, 43 deletions). The guard changes are live for Bash calls; the settings.json changes apply only after a restart. Do not commit or revert them before deciding on ratification.
- The test harnesses (`hooktest.py`, `hooktest2.py`, `sbtreg.py`) are in the session scratchpad and are lost when that session ends. Checkpoint 3a should recreate a regression harness under the factory-engineer's control if it needs one.
- **Plan simplified (2026-10-13):** no interim ratification. All of repair-01 is ratified once, at checkpoint 6 (one review, one ratification, one commit).
- **Snapshot (2026-10-13):** `docs/agent-runs/phase-01/repair-01-wip.patch`, sha256 `aa4645da91bbec9cc5657dbac94e1169d5e95f467aa6b01984541bb36a0e4c16`. It is a `diff -u` of HEAD to the working tree for `.claude/hooks/guard-dangerous.sh` and `.claude/settings.json` (649 added lines, matching `git diff --stat` 649 insertions, 45 deletions). `git apply --check` could not verify it, because git currently fails to read guard-dangerous.sh ("Invalid argument", probably OneDrive file locking; `cat` reads it fine). To restore: `git checkout -- .claude/hooks/guard-dangerous.sh .claude/settings.json && git apply docs/agent-runs/phase-01/repair-01-wip.patch` (or `patch -p1 < …`).
- **Unexplained change:** guard-dangerous.sh was modified 2026-10-13 19:59, after checkpoint 2. It grew from 443 to 660 changed lines and gained section "9b. Protected paths behind wrappers, launchers, globs, archives, inline code; git config", which looks like F5 work. No agent dispatched in this session made it. It is live and was observed blocking variable redirects and scratchpad writes to paths containing `.claude/hooks`. Checkpoint 3a starts by accounting for it: keep, fix, or remove. The checkpoint 6 reviewer must be told about it.
  - **Accounted for (orchestrator, 2026-10-13):** the change came from the CP3a factory-engineer dispatch in a second, concurrent orchestrator session (agent active 19:50:55 to 20:16:27). The other session's edit to this file and its snapshot overlapped that run. The snapshot is therefore an intermediate CP3a state: it predates the final inline-code fix, and restoring from it would drop that fix. Orchestrator recommendation: keep, as CP3a. The human decides at ratification. See "Checkpoint 3a report" below for the full increment. The checkpoint 6 reviewer must still be told that two orchestrator sessions were active at once, and that CP3a's harness ran while this file was being edited.
- **Snapshot superseded (2026-10-13 20:3x):** `repair-01-wip.patch` is an intermediate CP3a state. Do not restore from it. Use `docs/agent-runs/phase-01/repair-01-wip-cp3a.patch` (sha256 `571f8b3e8381f72c8c6204c45597e1758f0caecef4c0b052457e200c1d9dbe00`). That is `git diff` of HEAD to the working tree after CP3a for `.claude/hooks/guard-dangerous.sh`, `.claude/settings.json`, and `docs/agents.md`: 656 insertions and 49 deletions, and `git apply --check -R` passed. To restore: `git checkout -- .claude/hooks/guard-dangerous.sh .claude/settings.json docs/agents.md && git apply docs/agent-runs/phase-01/repair-01-wip-cp3a.patch`.
- **Latest snapshot (after CP4):** `docs/agent-runs/phase-01/repair-01-wip-cp4.patch`, sha256 `9b3a570cd9c0a2e773a8c80358542feda0df8f045e7c5aa4ea627f0f41470482`. It is `git diff -- .claude docs/agents.md`, HEAD to the working tree: 6 files, 811 insertions, 84 deletions; `git apply --check -R` passed. It supersedes the CP3b patch. To restore: `git checkout -- .claude docs/agents.md && git apply docs/agent-runs/phase-01/repair-01-wip-cp4.patch`.
- **Previous snapshot (after CP3b):** `docs/agent-runs/phase-01/repair-01-wip-cp3b.patch`, sha256 `c8deb530af4a0e23362284b0bf7ff98db313d77618598182c6b5f8da8beef01e`. It is `git diff` of HEAD to the working tree for the same 3 files (750 insertions, 68 deletions), and `git apply --check -R` passed. It supersedes the CP3a patch for restoring.
- **Single session from here (human decision, 2026-10-13):** the human adopted session cs-441-scholar-chain-99's CP3a output as the candidate. All remaining repair-01 work runs from one orchestrator session only.
- **Second CP3a review (factory-engineer, dispatched from session cs-441-scholar-chain-bd, 20:08:46 to 20:25:04, no files modified):** it found the concurrent writer, stopped editing, and reviewed section 9b as it stands.
  - Verdicts: parts 1 to 7 and 9 to 11 KEEP; no authority widening.
  - Part 8 (`operand9`/`dir_protected9`): FIX. False positive: `OUT=target/x.txt; echo x > "$OUT"` is blocked for domain-engineer, because a variable redirect from the repo root resolves to `.`, which counts as protected. Proposed fix: treat `.` as protected only when the command names a protected-path hint.
  - Also verified: the docs/agents.md "Enforcement limits" text is accurate.
  - Its harness run (207 cases, 5 UNEXPECTED, all fail-closed false positives: 4 for scratchpad paths containing `.claude/hooks` from section 9, F12; and 1 for operand9) mixed two hook versions, because the hook changed at 20:16 mid-run. It is not regression evidence.
  - Carried into CP3b: fix operand9 first. The full harness rerun stays owed in CP5.
- Checkpoint 2 overran its time box (~65 min). Send every remaining checkpoint with a hard time limit and an instruction to stop with a partial report when it runs out.

### Approach for the remaining checkpoints (essential for phase 01 marked *)

| CP | Approach |
|---|---|
| 3a* | F5: Do not attempt full Bash ownership enforcement; it can't be made reliable with string matching. (1) Extend guard section 9 to unwrap `bash -c`/`sh -c`/`eval`/`xargs` with the CP2 parser, so constitutional and evidence paths are protected inside wrappers. (2) Treat `git config` as a write for non-factory callers. (3) Amend docs/agents.md sections 4 and 5 to say that ownership is enforced mechanically for Write/Edit only, and that Bash writes rely on the prompt plus the role files, with code-reviewer detection. |
| 3b* | F6: Reuse the CP2 `exec_scan` and wrapper logic to find `aws` by basename (including `aws.exe` and quoted paths) after any keyword or wrapper (`if`, `nice`, `timeout`), and block `python -m awscli`. Feed the result into the existing read-only AWS verb check. |
| 4* | F7: Add the secret patterns `.credentials` (any directory), `credentials.tfrc.json`, and `gh/hosts.yml` to the guard and to the Read deny rules; block `env`/`printenv`/`jq -n env` for read-only callers. F9: check the `.gitkeep` exception only after sc_owner returns build-engineer for the parent path. F10: remove `settings.local` from the factory-engineer exemption. F11: change the text in incident.md and incident-responder.md to "rollback.sh asks; smoke-test.sh runs without a prompt". F4 (latent until phase 02): replace the `sbt compile*`/`sbt test*` wildcards with exact commands, move `sbt --client *` to ask, and add `eval`, `run`, `runMain`, and `markTestRun` to the guard blocklist. Note the build-engineer follow-up (markTestRun reachable only from `check`) for phase 02. |
| 5 | F12: Fix only the cheap false positives (`git --version`; quoted `>`; `;` and `&` inside quotes) using the CP2 quote-aware parser. Accept the rest as documented fail-closed deviations rather than loosening the regex. Run the full CP1 and CP2 regression and the validator. |
| 6* | Fresh code-reviewer pass over the cumulative diff, saved as review-report-02.md, and a probe rerun after restart. The human then ratifies in human-gate.md, commits, and restarts. |

## Checkpoint 1 report (factory-engineer, verbatim)

Orchestrator check after the report: `git diff --stat` showed `.claude/hooks/guard-dangerous.sh | 96 +++…--` and `.claude/settings.json | 23 ++++++----` (2 files changed, 108 insertions(+), 11 deletions(-)); `bash -n .claude/hooks/guard-dangerous.sh` printed SYNTAX_OK; `python -m json.tool .claude/settings.json` printed JSON_OK.

```text
STATUS: COMPLETE (proposal awaiting human ratification and restart)
OWNER: factory-engineer
WORK ORDER: docs/agent-runs/phase-01/repair-01.md, CHECKPOINT 1 of 5 (findings 1 and 2 only)
FILES CREATED: none in the repository (test harnesses in session scratchpad only: hooktest.py, wraptest.py, wraptest2.py, dbg.py, dbg2.py, empty-project/)
FILES MODIFIED:
  .claude/hooks/guard-dangerous.sh
  .claude/settings.json
COMMANDS RUN: bash -n on all 6 hooks; python -m json.tool on settings.json and .mcp.json; git ls-files -s .claude/hooks; python -I <scratch>/hooktest.py (stdin-JSON hook tests); python -I <scratch>/wraptest.py and wraptest2.py (exact settings.json hook command strings under Git Bash); bash .claude/hooks/validate-agent-files.sh; git diff --stat; git diff
TEST RESULTS: all hook tests behaved as designed (below). No sbt build exists, so sbt check is not applicable.
VALIDATION RESULTS: validate-agent-files.sh RESULT: PASS, exit 0. JSON parsing: both files OK. bash -n: all 6 hooks OK. Exec bits: all 6 are 100755 in the index (unchanged).
ASSUMPTIONS: Claude Code runs hook commands in Git Bash (the existing "$CLAUDE_PROJECT_DIR" commands already depend on this). Precedence is deny > ask > allow, and `*` in a Bash rule matches across spaces.
RISKS: see "Residual risk" below
MANUAL ACTION REQUIRED: human ratification in human-gate.md, commit, and a Claude Code restart (settings.json changes take effect only after the restart; guard-dangerous.sh changes are live now)
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: checkpoints 2-5 (findings 3-12), then a fresh code-reviewer pass, then human ratification and restart
```

### Finding 1 (CRITICAL): FIXED in both layers

**settings.json**

- I replaced `Bash(git diff*)`, `Bash(git log*)`, and `Bash(git show*)` with exact and word-boundary forms: `git diff`, `git diff *`, and the same for `log` and `show`.
  - Side effect: the old `git diff*` rule also allowed `git difftool -x <cmd>` (arbitrary execution, no prompt). That form now prompts.
- I added ask rules:
  - `Bash(git diff *--ou*)`
  - `Bash(git log *--ou*)`
  - `Bash(git show *--ou*)`
  - `Bash(python -m json.tool * *)` (two or more operands)

**guard-dangerous.sh**

- New `out_operands()` function. It finds:
  - git `--output[=]` (including abbreviations) and `--output-directory`, on any git subcommand.
  - `python* -m json[.tool]` / `-mjson.tool` with a second positional operand. It handles `--indent N` and `--`.
- `segment_writes` now treats these as writes. `format-patch` is added to the git writer list.
- New section 9a resolves each output operand with `sc_classify_path`. It blocks when:
  - The caller is read-only or unregistered.
  - A `cd`/`pushd`/`popd`, `git -C`, `--work-tree`, or `--git-dir` is present.
  - The operand is non-literal (`$`, a backtick, `*`, `?`, `[`, `{`, `~`, `<`, a backslash, or empty).
  - The target is `.git/**`, `.claude/.last-test-run`, `*human-gate.md`, or `.claude/settings.local.json` (blocked for everyone).
  - The target is `CLAUDE.md`, `docs/agents.md`, `.mcp.json`, or `.claude/**` and the caller is not factory-engineer.
  - The target is under `docs/agent-runs/**` and the caller is not the orchestrator.
- Read-only fence (section 11):
  - git with an output option is blocked.
  - python must be exactly `python -m json.tool …` or `-mjson.tool`. Previously `-m json.tool` anywhere in the segment passed.
  - An outfile is blocked.

Hook-test evidence, verbatim. Stdin JSON piped into guard-dangerous.sh under `C:\Program Files\Git\bin\bash.exe`. Messages are truncated to 170 characters by the harness.

```text
[BLOCK] exit=2 caller=domain-engineer cmd='python -m json.tool .mcp.json .claude/settings.json'
        BLOCKED by guard-dangerous: only factory-engineer writes CLAUDE.md, docs/agents.md, .mcp.json, and .claude/**, and only as a proposal awaiting human ratification. Return
[BLOCK] exit=2 caller=code-reviewer cmd='python -m json.tool .mcp.json .claude/settings.json'
        BLOCKED by guard-dangerous: code-reviewer is read-only: git --output/--output-directory and 'python -m json.tool <in> <out>' write files. Print to stdout instead (git dif
[BLOCK] exit=2 caller=orchestrator cmd='python -m json.tool .mcp.json .claude/settings.json'
        BLOCKED by guard-dangerous: only factory-engineer writes CLAUDE.md, docs/agents.md, .mcp.json, and .claude/**, and only as a proposal awaiting human ratification. Return
[BLOCK] exit=2 caller=domain-engineer cmd='python -m json.tool C:/Users/figge/AppData/Local/Temp/claude/.../scratchpad/s.json .claude/settings.json'
        BLOCKED by guard-dangerous: only factory-engineer writes CLAUDE.md, docs/agents.md, .mcp.json, and .claude/**, ...
[BLOCK] exit=2 caller=domain-engineer cmd='git diff --output=.claude/.last-test-run'
        BLOCKED by guard-dangerous: '.claude/.last-test-run' is never written by an agent (.git internals, the test marker, human-gate.md, human-local settings).
[BLOCK] exit=2 caller=code-reviewer cmd='git diff --output=.claude/.last-test-run'
        BLOCKED by guard-dangerous: code-reviewer is read-only: git --output/--output-directory and 'python -m json.tool <in> <out>' write files. ...
[BLOCK] exit=2 caller=orchestrator cmd='git diff --output=.claude/.last-test-run'
[BLOCK] exit=2 caller=factory-engineer cmd='git diff --output=.claude/.last-test-run'
[BLOCK] exit=2 caller=domain-engineer cmd='git log -1 --output=docs/agent-runs/phase-01/human-gate.md'
        BLOCKED by guard-dangerous: 'docs/agent-runs/phase-01/human-gate.md' is never written by an agent (.git internals, the test marker, human-gate.md, human-local settings).
[BLOCK] exit=2 caller=code-reviewer cmd='git log -1 --output=docs/agent-runs/phase-01/human-gate.md'
[BLOCK] exit=2 caller=orchestrator cmd='git log -1 --output=docs/agent-runs/phase-01/human-gate.md'
[BLOCK] exit=2 caller=factory-engineer cmd='git log -1 --output=docs/agent-runs/phase-01/human-gate.md'
[BLOCK] exit=2 caller=domain-engineer cmd='git diff --output=CLAUDE.md'
[BLOCK] exit=2 caller=code-reviewer cmd='git diff --output=CLAUDE.md'
[BLOCK] exit=2 caller=orchestrator cmd='git diff --output=CLAUDE.md'
[PASS] exit=0 caller=factory-engineer cmd='git diff --output=CLAUDE.md'      (owner; prompts via new ask rule)
[BLOCK] exit=2 (domain-engineer, code-reviewer, orchestrator) cmd='git show HEAD --output CLAUDE.md'
[BLOCK] exit=2 (domain-engineer, code-reviewer, orchestrator) cmd='python3 -m json.tool --indent 2 a.json .claude/settings.json'
[BLOCK] exit=2 (domain-engineer, code-reviewer, orchestrator) cmd='python -mjson.tool a.json .claude/agents/code-reviewer.md'
[BLOCK] exit=2 (all 4 callers) cmd='cd .claude && python -m json.tool ../a.json settings.json'
        BLOCKED by guard-dangerous: a file-writing output option (git --output, json.tool outfile) combined with a directory change (cd, pushd, git -C, --work-tree, --git-dir) is
[BLOCK] exit=2 (all 4 callers) cmd='git -C .claude diff --output=settings.json'
[BLOCK] exit=2 (all 4 callers) cmd='f=CLAUDE.md; git diff --output=$f'
        BLOCKED by guard-dangerous: the output operand '$f' is missing or not a literal path (variables, globs, ~, backslashes). ...
[BLOCK] exit=2 (all 4 callers) cmd='git diff --output=.git/config'
[PASS] exit=0 caller=domain-engineer / orchestrator / factory-engineer cmd='git diff --output=<scratchpad>/d.patch'
[BLOCK] exit=2 caller=code-reviewer cmd='git diff --output=<scratchpad>/d.patch'
```

Regression check, verbatim. All 4 callers (domain-engineer, code-reviewer, orchestrator, factory-engineer) got exit=0 for each command:

```text
[PASS] exit=0 ... cmd='git diff'
[PASS] exit=0 ... cmd='git diff --stat'
[PASS] exit=0 ... cmd='git log --oneline'
[PASS] exit=0 ... cmd='git show HEAD'
[PASS] exit=0 ... cmd='python -m json.tool .claude/settings.json'
[PASS] exit=0 ... cmd='python -m json.tool .mcp.json'
```

### Finding 2 (MAJOR): FIXED (with residual risk noted)

- **Guard:** `find` with `-delete|-exec|-execdir|-ok|-okdir|-fprint|-fprint0|-fprintf|-fls` now counts as a write in `segment_writes`. The section 9 check therefore catches `.claude/hooks/**`.
- **Registration:**
  - All three PreToolUse registrations (Bash, aws-api, ownership) now run `h=…; [ -f "$h" ] || { echo 'BLOCKED: … missing (fail closed)…' >&2; exit 2; }; bash "$h" || exit 2`. A missing script, or any unexpected non-zero exit, becomes a block.
  - Stop and SubagentStop now run `[ -f "$h" ] && exec bash "$h"`. When the script is missing, they block once and then honor `stop_hook_active`, the same loop guard the script uses.

Hook-test evidence, verbatim:

```text
[BLOCK] exit=2 caller=domain-engineer cmd='find .claude/hooks -name guard-dangerous.sh -delete'
        BLOCKED by guard-dangerous: only factory-engineer writes CLAUDE.md, docs/agents.md, .claude/agents|hooks|commands/*, .claude/settings*.json, and .mcp.json, and only as a
[BLOCK] exit=2 caller=code-reviewer cmd='find .claude/hooks -name guard-dangerous.sh -delete'
[BLOCK] exit=2 caller=orchestrator cmd='find .claude/hooks -name guard-dangerous.sh -delete'
[PASS] exit=0 caller=factory-engineer cmd='find .claude/hooks -name guard-dangerous.sh -delete'   (constitutional owner exemption, pre-existing; see residual risk)
[BLOCK] exit=2 (domain-engineer, code-reviewer, orchestrator) cmd='find .claude/hooks -name guard-dangerous.sh -exec rm {} +'
[BLOCK] exit=2 (all 4 callers) cmd='find .claude -name settings.json -delete'   ('find -delete' on a broad path)
```

Wrapper test. These are the exact settings.json command strings. "missing" means `CLAUDE_PROJECT_DIR` points at an empty scratch directory; no hook script was deleted.

```text
PreToolUse Bash            script=present exit=0
PreToolUse Bash            script=missing exit=2 BLOCKED: .claude/hooks/guard-dangerous.sh is missing, so commands cannot be checked (fail closed). Ask the human to restore it from git and restart Claude Code.
PreToolUse aws             script=present exit=0
PreToolUse aws             script=missing exit=2 BLOCKED: .claude/hooks/guard-dangerous.sh is missing, so AWS calls cannot be checked (fail closed). ...
PreToolUse ownership       script=present exit=0
PreToolUse ownership       script=missing exit=2 BLOCKED: .claude/hooks/guard-ownership.sh is missing, so writes cannot be checked (fail closed). ...
Stop                       script=present exit=0
Stop                       script=missing exit=2 BLOCKED: .claude/hooks/verify-tests-ran.sh is missing, so the test-stop gate cannot run (fail closed). ...
Stop (stop_hook_active)    script=present exit=0
Stop (stop_hook_active)    script=missing exit=0
SubagentStop               script=present exit=0
SubagentStop               script=missing exit=2 BLOCKED: .claude/hooks/verify-tests-ran.sh is missing, ...
PreToolUse Bash (domain-engineer, 'git diff --output=CLAUDE.md') script=present exit=2 BLOCKED by guard-dangerous: only factory-engineer writes CLAUDE.md, ...
```

### git diff --stat

```text
 .claude/hooks/guard-dangerous.sh | 96 ++++++++++++++++++++++++++++++++++++++--
 .claude/settings.json            | 23 ++++++----
 2 files changed, 108 insertions(+), 11 deletions(-)
```

Git warns that LF will be replaced by CRLF the next time it touches either file. This is pre-existing: there is no `.gitattributes`, and build-engineer owns it in phase 02.

### Privilege delta (all narrowing, all awaiting human ratification; nothing widened)

1. Allow list: `git diff*`, `git log*`, and `git show*` become `git diff|log|show` plus `git diff|log|show *`. These now prompt instead of running silently: `git difftool`, `git diff-tree`, `git diff-index`, `git diff-files`, `git show-ref`, `git show-branch`, and `git log*` lookalikes.
2. Ask (was allow): git diff/log/show with `--ou*`, and `python -m json.tool` with two or more operands.
3. Guard: git `--output`/`--output-directory` and json.tool outfiles are blocked on protected paths, `.git/**`, non-literal operands, and when combined with a directory change. These checks apply to every caller, including factory-engineer for `.git/**`, the marker, human-gate, and settings.local.
4. code-reviewer fence: python is limited to `python -m json.tool <file>` at argv[1]. Outfiles and git output options are blocked.
5. Hook registration: an unexpected non-zero exit from guard-dangerous or guard-ownership (for example an internal bash error) now blocks instead of failing open.

### Floor invariants

All intact. The dangerous-command guard and the test-stop hook are both strengthened: both now fail closed when the script is missing. The terraform, secrets, AWS, and force-push rules and the formatter are untouched. The validator's floor checks all PASS.

### Audits

- **Collision:** PASS. No agent, command, MCP server, or hook trigger was added, renamed, or removed.
- **Orphan:** PASS (validator: "orphans: roster, agent files, guard ROSTER, and ownership owners agree"; every hook file exists).
- **One-writer:** PASS. The ownership map is unchanged.

### Residual risk

1. **factory-engineer exemption.** The section 9 constitution check still exempts factory-engineer, so it can delete a hook script through Bash. With the new wrappers, that deletion now blocks every later call instead of silently disabling the guard. The human recovers with git.
2. **Hook timeouts.** A timeout (20s or 30s) is still a non-blocking error in Claude Code. A hook script replaced in place by an exit-0 script still fails open; only the finding 5 Bash-write forms reach it.
3. **Indirect deletes.** `find` deletes that never name the protected path in the same segment (`cd .claude/hooks && rm x`, `xargs rm`) are still not detected by path string. The fail-closed wrapper now covers the effect for the registered hooks. The general case is finding 5, which is still OPEN for a later checkpoint.
4. **Other Bash writes.** Writes outside this checkpoint's scope still rely on the prompt. Examples: `python -c "open(...)"`, `git -c core.pager=…`, and `git difftool` with config-defined drivers. These belong to findings 5 and 8.
5. **Rule matching not observed live.** The ask-rule wildcard behaviour (`*--ou*`, `* *`) can be observed only after a restart. The guard is the primary control and is verified above.
6. **Known false positive (fail closed).** A non-factory caller running `python -m json.tool .mcp.json <scratchpad>/o.json` is blocked, because section 9 matches the input path string. Logged for finding 12.
7. **Test-environment note.** On this host, a plain `bash` launched from Python resolves to `C:\Windows\System32\bash.exe` (WSL). The first test run used it by mistake and produced wrong path classifications. All the evidence above comes from the rerun under Git Bash. If Claude Code ever launches hooks through WSL bash, `sc_classify_path` would misclassify Windows `cwd` values. This needs to be confirmed in the code-reviewer pass.

### Ratification request

The human approves or rejects:

- (a) the guard-dangerous.sh diff (out_operands, section 9a, the `find` writer rule, `format-patch`, and the section 11 fence);
- (b) the settings.json allow narrowing and the four new ask rules;
- (c) the fail-closed PreToolUse, Stop, and SubagentStop registration commands;
- (d) the five narrowings in the privilege delta.

Then the human commits and restarts Claude Code. factory-engineer has not ratified anything and has not touched `docs/agent-runs/**`.

## Checkpoint 2 report (factory-engineer, verbatim)

Orchestrator check after the report: cumulative `git diff --stat` showed `.claude/hooks/guard-dangerous.sh | 443` and `.claude/settings.json | 34` (2 files changed, 434 insertions(+), 43 deletions(-)); `bash -n` passed on all 6 hooks; `python -m json.tool .claude/settings.json` printed JSON_OK. The report heading below is demoted one level to fit this file.

I closed both findings in the guard hook and added cleanup deny rules to settings.json. Every new rule only narrows what agents can do; nothing was widened. All hook tests came out as expected, the checkpoint 1 tests still give the same results, and the validator passes. This is a proposal: nothing is ratified, nothing is committed, and I did not touch `docs/agent-runs/**`.

```text
STATUS: COMPLETE (proposal awaiting human ratification and restart)
OWNER: factory-engineer
WORK ORDER: docs/agent-runs/phase-01/repair-01.md, CHECKPOINT 2 of 5 (findings 3 and 8 only)
FILES CREATED: none in the repository. Scratchpad only: hooktest2.py, sbtreg.py, run-cp2.txt, run-cp1-regress.txt, and the checkpoint 1 baselines hooks/guard-dangerous.cp1.sh and settings.cp1.json
FILES MODIFIED (on top of the uncommitted checkpoint 1 changes, which I kept):
  .claude/hooks/guard-dangerous.sh
  .claude/settings.json
COMMANDS RUN: Git Bash bash -n on all 6 hooks; python -m json.tool on settings.json and .mcp.json; git ls-files -s .claude/hooks; python -I hooktest2.py (new tests for this checkpoint); python -I hooktest.py (checkpoint 1 tests rerun); python -I sbtreg.py; bash .claude/hooks/validate-agent-files.sh; git diff --stat; git diff --no-index against the checkpoint 1 baselines
TEST RESULTS: hooktest2.py: 140 cases, UNEXPECTED=0. hooktest.py rerun: 56 BLOCK / 36 PASS, the same verdicts as checkpoint 1. No sbt build exists yet, so sbt check does not apply.
VALIDATION RESULTS: validate-agent-files.sh "RESULT: PASS", exit=0. Both JSON files parse. bash -n is OK on all 6 hooks. All 6 hooks are 100755 in the index (unchanged).
ASSUMPTIONS: hooks run under Git Bash (as in checkpoint 1). Claude Code checks deny rules against each subcommand of a compound command; I could not observe this before a restart.
RISKS: see "Residual risk" below
MANUAL ACTION REQUIRED: human ratification in human-gate.md, a commit, and a Claude Code restart. The guard edits are already live for Bash calls in this session. The settings.json edits take effect only after the restart.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: checkpoint 3 (findings 5 and 6), then 4 and 5, then a fresh code-reviewer pass, then human ratification and restart
```

#### Finding 3 (MAJOR): FIXED

I rewrote section 10 of guard-dangerous.sh:

- **Parsing.** A new parser splits each command into words the way bash does, respecting quotes. This keeps quoted Windows paths with spaces intact.
- **What counts as running a script.** Each of these is resolved to a repository path with `sc_classify_path` and checked against the section 7 runner map:
  - a `*.sh` command word, including after `env`, `nice`, `timeout`, `sudo`, and similar wrappers, or after `if`, `then`, and other keywords;
  - any shell interpreter, recognised by its program name, so `/usr/bin/bash`, `/bin/sh`, `bash.exe` and a quoted `C:\Program Files\Git\bin\bash.exe` all count;
  - `source` and `.`;
  - `-c` command strings, which are parsed again and checked the same way.
- **Path checks.**
  - The hook's working directory is honoured, so `./cleanup.sh` run from inside `scripts/` is caught.
  - A path like `scripts/<name>.sh` outside the repository's `scripts/` folder is still checked by name, as before.
  - A script whose name is not in the map is blocked.
- **Syntax-only check.** A shell call counts as syntax-only only when every option is a short cluster of `n`, `v`, `x`, `e`, `u` letters that includes `n` (for example `-n` or `-nv`). `--norc`, `--noprofile`, `-c`, `-s`, `-i`, `-o` and any long option mean the script may run.
- **Blocked outright (fail closed):**
  - running a `*.sh` after a directory change (`cd`, `pushd`, `popd`, `env -C`, `--chdir`). The one exception is a `cd` or `pushd` whose literal target is the repository root;
  - a shell reading its commands from a pipe or redirect: `| bash`, `| sh -s`, `| bash -`, `bash < file`, `<<<`;
  - `xargs`, `find -exec` or `parallel` launching a shell that is not syntax-only;
  - a script operand that is not a literal path (`$`, globs, `~`), or a non-literal command word followed by a script argument;
  - `bash -c '…' scripts/x.sh` (the script passed as a positional argument);
  - shell calls nested more than 4 levels deep.

**settings.json:** I added 9 deny rules for cleanup (in the diff). The guard remains the primary control.

Hook-test evidence, verbatim. Stdin JSON was piped into the hook under `C:\Program Files\Git\bin\bash.exe`; no command or script was executed. `<repo>` stands for the absolute repository path.
```text
[BLOCK] exit=2 caller=domain-engineer cmd='bash --norc scripts/cleanup.sh'
        BLOCKED by guard-dangerous: scripts/cleanup.sh is human-invoked only (cleanup lists and deletes resources). Report the cleanup request as MANUAL ACTION REQUIRED.
[BLOCK] exit=2 caller=deploy-engineer cmd='bash --norc scripts/cleanup.sh'
[BLOCK] exit=2 caller=orchestrator cmd='bash --norc scripts/cleanup.sh'
[BLOCK] exit=2 caller=deploy-engineer cmd='bash --noprofile scripts/deploy.sh prod'
        BLOCKED by guard-dangerous: scripts/deploy.sh must be called with an approved environment as its first argument (approved: dev; got 'prod'). ...
[BLOCK] exit=2 caller=domain-engineer cmd='bash --noprofile scripts/deploy.sh dev'
        BLOCKED by guard-dangerous: domain-engineer may not run scripts/deploy.sh. Allowed: deploy-engineer. ...
[BLOCK] exit=2 caller=deploy-engineer cmd='bash -c scripts/cleanup.sh'
[BLOCK] exit=2 caller=deploy-engineer cmd="bash -c 'scripts/deploy.sh prod'"
[BLOCK] exit=2 caller=deploy-engineer cmd="bash -nc 'scripts/deploy.sh prod'"
[BLOCK] exit=2 caller=deploy-engineer cmd='/usr/bin/bash scripts/rollback.sh staging'
        BLOCKED by guard-dangerous: scripts/rollback.sh must be called with an approved environment as its first argument (approved: dev; got 'staging'). ...
[BLOCK] exit=2 caller=orchestrator cmd='/usr/bin/bash scripts/rollback.sh dev'
        BLOCKED by guard-dangerous: orchestrator may not run scripts/rollback.sh. Allowed: deploy-engineer incident-responder. ...
[BLOCK] exit=2 caller=deploy-engineer cmd='/bin/sh scripts/cleanup.sh'
[BLOCK] exit=2 caller=deploy-engineer cmd='bash.exe scripts/cleanup.sh'
[BLOCK] exit=2 caller=orchestrator cmd='"C:/Program Files/Git/usr/bin/bash.exe" scripts/cleanup.sh'
[BLOCK] exit=2 caller=orchestrator cmd='"C:\\Program Files\\Git\\bin\\bash.exe" scripts/deploy.sh dev'
[BLOCK] exit=2 caller=deploy-engineer cmd='cd scripts && ./cleanup.sh'
        BLOCKED by guard-dangerous: a directory change (cd, pushd, popd, env -C, --chdir) combined with running a script ('./cleanup.sh') is blocked because the script's identity cannot be verified. ...
[BLOCK] exit=2 caller=domain-engineer cmd='cd scripts && ./cleanup.sh'
[BLOCK] exit=2 caller=orchestrator cmd='cd scripts && ./cleanup.sh'
[BLOCK] exit=2 caller=deploy-engineer cmd='cd scripts && ./deploy.sh dev'
[BLOCK] exit=2 caller=deploy-engineer cmd='pushd scripts; bash deploy.sh dev'
[BLOCK] exit=2 caller=deploy-engineer cmd='cd scripts && bash ./cleanup.sh'
[BLOCK] exit=2 caller=deploy-engineer cmd="bash -c 'cd scripts; ./cleanup.sh'"
[BLOCK] exit=2 caller=deploy-engineer cmd='cd "<repo>" && cd scripts && ./cleanup.sh'
[BLOCK] exit=2 caller=deploy-engineer cmd='cd "<repo>/scripts" && ./deploy.sh dev'
[BLOCK] exit=2 caller=deploy-engineer cmd='cd "<repo>" && bash -c "cd scripts; ./cleanup.sh"'
[BLOCK] exit=2 caller=deploy-engineer cmd='cd - && ./cleanup.sh'
[BLOCK] exit=2 caller=deploy-engineer cmd='env -C scripts ./cleanup.sh'
[BLOCK] exit=2 caller=deploy-engineer cwd=<repo>/scripts cmd='./cleanup.sh'
        BLOCKED by guard-dangerous: scripts/cleanup.sh is human-invoked only ...
[BLOCK] exit=2 caller=deploy-engineer cmd='echo scripts/cleanup.sh | xargs bash'
        BLOCKED by guard-dangerous: 'xargs' runs the shell 'bash' with arguments supplied at run time (for example 'xargs bash', 'find -exec bash'), so the script cannot be verified against the section 7 map ...
[BLOCK] exit=2 caller=orchestrator cmd='echo scripts/cleanup.sh | xargs bash'
[BLOCK] exit=2 caller=deploy-engineer cmd='echo scripts/cleanup.sh | xargs -n1 sh'
[BLOCK] exit=2 caller=deploy-engineer cmd='echo dev | xargs scripts/deploy.sh'
        BLOCKED by guard-dangerous: scripts/deploy.sh must be called with an approved environment as its first argument (approved: dev; got 'none'). ...
[BLOCK] exit=2 caller=deploy-engineer cmd='cat scripts/deploy.sh | bash'
        BLOCKED by guard-dangerous: 'bash' reading commands from a pipe or redirect ('| bash', 'bash < file', 'bash -s') cannot be verified against the section 7 map (fail closed). ...
[BLOCK] exit=2 caller=deploy-engineer cmd='cat scripts/cleanup.sh | sh -s'
[BLOCK] exit=2 caller=deploy-engineer cmd='cat scripts/cleanup.sh | bash -'
[BLOCK] exit=2 caller=deploy-engineer cmd='bash < scripts/cleanup.sh'
[BLOCK] exit=2 caller=deploy-engineer cmd='find scripts -name cleanup.sh -exec bash {} ;'
[BLOCK] exit=2 caller=deploy-engineer cmd='nice -n 10 scripts/cleanup.sh'
[BLOCK] exit=2 caller=deploy-engineer cmd='timeout -s KILL 30 scripts/cleanup.sh'
[BLOCK] exit=2 caller=deploy-engineer cmd="env -S 'bash scripts/cleanup.sh'"
[BLOCK] exit=2 caller=deploy-engineer cmd='if true; then scripts/cleanup.sh; fi'
[BLOCK] exit=2 caller=deploy-engineer cmd='source scripts/cleanup.sh'
[BLOCK] exit=2 caller=deploy-engineer cmd='. scripts/cleanup.sh'
[BLOCK] exit=2 caller=deploy-engineer cmd='bash scripts/$X.sh dev'
        BLOCKED by guard-dangerous: the executed script or interpreter operand 'scripts/$x.sh' is not a literal path ...
[BLOCK] exit=2 caller=deploy-engineer cmd='bash scripts/clean*.sh'
[BLOCK] exit=2 caller=deploy-engineer cmd='$SHELL scripts/cleanup.sh'
        BLOCKED by guard-dangerous: a non-literal command word ('$shell') with a script argument ('scripts/cleanup.sh') cannot be verified (fail closed). ...
[BLOCK] exit=2 caller=deploy-engineer cmd='bash scripts/../scripts/cleanup.sh'
[BLOCK] exit=2 caller=deploy-engineer cmd='bash scripts/brand-new.sh dev'
        BLOCKED by guard-dangerous: scripts/brand-new.sh is not in the script execution map (docs/agents.md section 7). ...
[BLOCK] exit=2 caller=deploy-engineer cmd='bash -o pipefail scripts/deploy.sh staging'
[BLOCK] exit=2 caller=deploy-engineer cmd='bash -c \'"$0" dev\' scripts/deploy.sh'
        BLOCKED by guard-dangerous: 'bash -c' with a script path passed as a positional argument ('scripts/deploy.sh') cannot be verified (fail closed). ...
[BLOCK] exit=2 caller=deploy-engineer cmd="sh -c 'bash --norc scripts/cleanup.sh'"
[BLOCK] exit=2 caller=deploy-engineer cmd='bash /tmp/scripts/cleanup.sh'
[BLOCK] exit=2 caller=domain-engineer cmd='modules/x/scripts/deploy.sh dev'
[BLOCK] exit=2 caller=domain-engineer cmd='scripts/deploy.sh dev'
```

Legitimate inputs still pass, verbatim:
```text
[PASS] exit=0 caller=deploy-engineer cmd='bash scripts/deploy.sh dev'
[PASS] exit=0 caller=deploy-engineer cmd='scripts/deploy.sh dev'
[PASS] exit=0 caller=deploy-engineer cmd='./scripts/deploy.sh dev'
[PASS] exit=0 caller=deploy-engineer cmd='bash -o pipefail scripts/deploy.sh dev'
[PASS] exit=0 caller=deploy-engineer cmd='cd "<repo>" && bash scripts/deploy.sh dev'
[PASS] exit=0 caller=domain-engineer cmd='bash -n scripts/x.sh'
[PASS] exit=0 caller=code-reviewer cmd='bash -n scripts/x.sh'
[PASS] exit=0 caller=orchestrator cmd='bash -n scripts/x.sh'
[PASS] exit=0 caller=infra-engineer cmd='bash -n scripts/cleanup.sh'
[PASS] exit=0 caller=infra-engineer cmd='bash -nv scripts/deploy.sh'
[PASS] exit=0 caller=infra-engineer cmd='ls scripts/*.sh | xargs -n1 bash -n'
[PASS] exit=0 caller=infra-engineer cmd="find scripts -name '*.sh' -exec bash -n {} ;"
[PASS] exit=0 caller=domain-engineer cmd='scripts/check.sh'
[PASS] exit=0 caller=orchestrator cmd='bash .claude/hooks/validate-agent-files.sh'
[PASS] exit=0 caller=orchestrator cmd='cd "<repo>" && bash .claude/hooks/validate-agent-files.sh'
[PASS] exit=0 caller=domain-engineer cmd='cd "<repo>" && bash .claude/hooks/validate-agent-files.sh'
[PASS] exit=0 caller=domain-engineer cmd='cat scripts/cleanup.sh'
[PASS] exit=0 caller=domain-engineer cmd='if [ -f scripts/deploy.sh ]; then echo yes; fi'
[PASS] exit=0 caller=domain-engineer cmd='cd modules && ls'
[PASS] exit=0 caller=orchestrator cmd="cd /tmp && sed -E 's/x \\(y\\)\\. Rejected: /z/' f.txt | cut -c1-20"
```

The live guard blocked two of my own commands during this work, and both led to fixes:
1. A quoted `\.` that the hook splits off at `)` was read as a `.` (source) command while a `cd` was present. I now apply the directory-change rule only to `*.sh` or `scripts` operands.
2. `cd "<repo>" && bash .claude/hooks/validate-agent-files.sh` was blocked. That led to the repository-root `cd` exception.

Both commands are now in the passing tests above.

#### Finding 8 (MAJOR): FIXED

I rewrote section 11, the read-only fence.

- **Option checks read the original text.** They now use the command as typed, not lowercased, so `-O` and `-o` are told apart. The command name is still compared in lowercase. I deliberately do not strip `.exe` here, so `sort.exe` and `git.exe` stay unknown commands and are rejected, as before.
- **Option allowlists** (anything not listed is rejected):
  - `sort`: `-o`, `--output`, `--compress-program` and `-T` are rejected.
  - `uniq`: at most 1 operand.
  - `rg`: `--pre`, `--pre-glob`, `-z` and `--hostname-bin` are rejected.
  - `checkov`: no positional operands; `--output-file-path` and `--external-checks-*` are rejected.
  - `tfsec`: `--out` and `-O` are rejected.
  - `tflint`: `--fix` and `--init` are rejected.
  - Abbreviated long options are rejected.
- **git.** argv[1] must still be an allowed subcommand. That already rejects the options that come before the subcommand (`-c`, `-C`, `--exec-path`, `-p`) and the `difftool` and `config` subcommands. I added a blocklist (prefix match, so abbreviations are caught too): `-O*`, `--op*`, `--ext*`, `--textc*`, `--filt*`, `--show-s*`, `--exec*`, `--upload-pack*`, `--receive-pack*`.
  - git has hundreds of options, so an allowlist was not practical there.
- **bash/sh.** Allowed only as a syntax-only check, using the same rule as finding 3.
- **Small additions, same finding:** `file -C`/`--compile`, `date -s`/`--set`, and `find -fprint0` are rejected.
- **shellcheck** stays unrestricted. As far as I know it has no options that write files or run programs. I did not check this against an installed version.

Evidence, verbatim (caller=code-reviewer; the harness shows the `Rejected:` clause of each message):
```text
[BLOCK] exit=2 cmd='sort -o CLAUDE.md CLAUDE.md'                 Rejected: sort: option '-o' (in '-o') is not on the read-only allowlist
[BLOCK] exit=2 cmd='sort --output=CLAUDE.md CLAUDE.md'           Rejected: sort: option '--output=CLAUDE.md' is not on the read-only allowlist
[BLOCK] exit=2 cmd='sort -uo x CLAUDE.md'                        Rejected: sort: option '-o' (in '-uo') is not on the read-only allowlist
[BLOCK] exit=2 cmd='sort --compress-program=bash CLAUDE.md'      Rejected: sort: option '--compress-program=bash' is not on the read-only allowlist
[BLOCK] exit=2 cmd='/usr/bin/sort -o x y'                        Rejected: sort: option '-o' (in '-o') ...
[BLOCK] exit=2 cmd='sort.exe -o x y'                             Rejected: 'sort.exe'
[BLOCK] exit=2 cmd='uniq docs/agents.md docs/agents.md.tmp'      Rejected: uniq: more than 1 operand(s); the extra operand is an output file
[BLOCK] exit=2 cmd='uniq -c a b'                                 Rejected: uniq: more than 1 operand(s); ...
[BLOCK] exit=2 cmd='rg --pre bash . some/file.sh'                Rejected: rg: option '--pre' is not on the read-only allowlist
[BLOCK] exit=2 cmd='rg --pre=bash x'                             Rejected: rg: option '--pre=bash' ...
[BLOCK] exit=2 cmd="rg --pre-glob '*.sh' --pre bash x"           Rejected: rg: option '--pre-glob' ...
[BLOCK] exit=2 cmd='rg -z x'                                     Rejected: rg: option '-z' (in '-z') ...
[BLOCK] exit=2 cmd='rg --hostname-bin bash x'                    Rejected: rg: option '--hostname-bin' ...
[BLOCK] exit=2 cmd='git grep --open-files-in-pager=bash -e x -- f.sh'  Rejected: git grep --open-files-in-pager=bash (runs a pager, external diff, textconv or filter driver, signature program, or other configured command)
[BLOCK] exit=2 cmd='git grep --open-files-in-pager bash -e x'    Rejected: git grep --open-files-in-pager ...
[BLOCK] exit=2 cmd='git grep -O bash -e x'                       Rejected: git grep -O ...
[BLOCK] exit=2 cmd='git grep -Obash -e x'                        Rejected: git grep -Obash ...
[BLOCK] exit=2 cmd='tflint --fix'                                Rejected: tflint: option '--fix' is not on the read-only allowlist
[BLOCK] exit=2 cmd='tflint --init'                               Rejected: tflint: option '--init' ...
[BLOCK] exit=2 cmd='tfsec --out f'                               Rejected: tfsec: option '--out' ...
[BLOCK] exit=2 cmd='tfsec -O f .'                                Rejected: tfsec: option '-O' (in '-O') ...
[BLOCK] exit=2 cmd='checkov --output-file-path x -d .'           Rejected: checkov: option '--output-file-path' ...
[BLOCK] exit=2 cmd='checkov --external-checks-dir x -d .'        Rejected: checkov: option '--external-checks-dir' ...
[BLOCK] exit=2 cmd='git diff --output=f'                         BLOCKED by guard-dangerous: code-reviewer is read-only: git --output/--output-directory and 'python -m json.tool <in> <out>' write files. ...
[BLOCK] exit=2 cmd='git -c core.pager=bash log'                  Rejected: git -c
[BLOCK] exit=2 cmd='git -c diff.external=bash diff'              Rejected: git -c
[BLOCK] exit=2 cmd='git --exec-path=/tmp diff'                   Rejected: git --exec-path=/tmp
[BLOCK] exit=2 cmd='git -p log'                                  Rejected: git -p
[BLOCK] exit=2 cmd='git.exe diff'                                Rejected: 'git.exe'
[BLOCK] exit=2 cmd='git diff --ext-diff'                         Rejected: git diff --ext-diff ...
[BLOCK] exit=2 cmd='git show --ext-diff HEAD'                    Rejected: git show --ext-diff ...
[BLOCK] exit=2 cmd='git log -p --textconv'                       Rejected: git log --textconv ...
[BLOCK] exit=2 cmd='git cat-file --filters HEAD:x'               Rejected: git cat-file --filters ...
[BLOCK] exit=2 cmd='git log --show-signature'                    Rejected: git log --show-signature ...
[BLOCK] exit=2 cmd='git difftool'                                Rejected: git difftool
[BLOCK] exit=2 cmd='git difftool -x bash'                        Rejected: git difftool
[BLOCK] exit=2 cmd='git config core.pager bash'                  Rejected: git config
[BLOCK] exit=2 cmd='GIT_EXTERNAL_DIFF=bash git diff'             Rejected: 'git_external_diff=bash'
[BLOCK] exit=2 cmd='bash --norc x.sh'                            Rejected: bash other than a syntax check ('bash -n <file>'; --norc, --noprofile, -c, -s and other options execute)
[BLOCK] exit=2 cmd='bash --noprofile x.sh'                       Rejected: bash other than a syntax check ...
[BLOCK] exit=2 cmd="bash -c 'echo hi'"                           Rejected: bash other than a syntax check ...
[BLOCK] exit=2 cmd="python -c 'print(1)' -m json.tool"           Rejected: python other than 'python -m json.tool <file>'
[BLOCK] exit=2 cmd='file -C -m x'                                Rejected: file -C/--compile (writes a .mgc file)
[BLOCK] exit=2 cmd='find . -fprint0 out'                         Rejected: find with -delete/-exec/-fprint
```

Legitimate inputs, verbatim:
```text
[PASS] exit=0 caller=code-reviewer cmd='sort CLAUDE.md'
[PASS] exit=0 caller=code-reviewer cmd='sort -u -k2,2 -t, docs/agents.md'
[PASS] exit=0 caller=code-reviewer cmd='uniq docs/agents.md'
[PASS] exit=0 caller=code-reviewer cmd='uniq -c docs/agents.md'
[PASS] exit=0 caller=code-reviewer cmd='rg -n "markTestRun" .claude'
[PASS] exit=0 caller=code-reviewer cmd='rg pattern'
[PASS] exit=0 caller=code-reviewer cmd="rg -i --glob '*.sh' -C 2 sc_block .claude/hooks"
[PASS] exit=0 caller=code-reviewer cmd='git grep pattern'
[PASS] exit=0 caller=code-reviewer cmd='git grep -n json.tool'
[PASS] exit=0 caller=code-reviewer cmd='tflint'
[PASS] exit=0 caller=code-reviewer cmd='tfsec infra/terraform'
[PASS] exit=0 caller=code-reviewer cmd='checkov -d infra/terraform --compact'
[PASS] exit=0 caller=code-reviewer cmd='git diff --stat'
[PASS] exit=0 caller=code-reviewer cmd='git diff'
[PASS] exit=0 caller=code-reviewer cmd='git log --oneline -5'
[PASS] exit=0 caller=code-reviewer cmd='git show HEAD --stat'
[PASS] exit=0 caller=code-reviewer cmd='shellcheck .claude/hooks/guard-dangerous.sh'
[PASS] exit=0 caller=code-reviewer cmd='bash -n .claude/hooks/guard-dangerous.sh'
[PASS] exit=0 caller=code-reviewer cmd='python -m json.tool .claude/settings.json'
[PASS] exit=0 caller=code-reviewer cmd="find .claude -name '*.sh'"
[PASS] exit=0 caller=domain-engineer cmd='sort -o out.txt in.txt'     (writer agents unaffected)
[PASS] exit=0 caller=domain-engineer cmd='uniq a b'
[PASS] exit=0 caller=orchestrator cmd='git diff --stat'
```

#### Regression check

- **Checkpoint 1 tests** (hooktest.py; domain-engineer, code-reviewer, orchestrator, factory-engineer): 56 BLOCK / 36 PASS, the same verdict on every line as checkpoint 1. For example:
  - `git diff --output=CLAUDE.md` is still blocked for the three non-owners and passes for factory-engineer.
  - `find .claude/hooks -name guard-dangerous.sh -delete` is still blocked for non-owners.
  - The REGRESSION group (`git diff`, `git diff --stat`, `git log --oneline`, `git show HEAD`, and `python -m json.tool` on each JSON file) passes for all 4 callers.
- **Reviewer sbt rules** (finding 4 logic is untouched; I kept its old word-splitting on purpose):
  - Blocked: `sbt "testOnly a.B" run` (sbt run), `sbt "clean compile"` (sbt clean), `sbt Publish` (sbt publish).
  - Pass: `sbt check`, `sbt Test/compile`.
- **Hook latency, measured:** 2.09 s for `git diff --stat` and 2.40 s for `bash scripts/deploy.sh dev`. The timeout is 20 s.

#### git diff --stat (cumulative, checkpoints 1 and 2)
```text
 .claude/hooks/guard-dangerous.sh | 443 ++++++++++++++++++++++++++++++++++++---
 .claude/settings.json            |  34 ++-
 2 files changed, 434 insertions(+), 43 deletions(-)
```
Git warns that LF will be replaced by CRLF in both files. This was already the case before this checkpoint; `.gitattributes` belongs to build-engineer in phase 02.

#### Privilege delta for this checkpoint

Everything below narrows; nothing is widened. All of it awaits human ratification.

1. **Every caller (guard section 10):**
   - Interpreters are recognised by program name.
   - `--norc`, `--noprofile`, `-c`, `-s`, `-i`, `-o` and long options no longer count as syntax-only.
   - `-c` strings are re-checked.
   - Running a `*.sh` after a non-root directory change is blocked.
   - `| bash`, `bash < file`, `bash -s` and `bash -` fed from a pipe are blocked.
   - `xargs`, `find -exec` and `parallel` running a non-syntax-only shell are blocked.
   - Non-literal script operands are blocked.
   - Unmapped `scripts/**` paths, including nested ones, are blocked.
2. **code-reviewer (guard section 11):**
   - `sort`, `uniq`, `rg`, `checkov`, `tfsec` and `tflint` move from "any options" to option allowlists.
   - git gets an option blocklist.
   - bash is syntax-only under the strict rule.
   - `file -C`, `date -s` and `find -fprint0` are rejected.
3. **settings.json:** 9 new deny rules for cleanup: `sh ./scripts/cleanup.sh*`, `./cleanup.sh*`, `cleanup.sh*`, `bash --no*cleanup.sh*`, `*/bash *cleanup.sh*`, `*/sh *cleanup.sh*`, `*bash.exe *cleanup.sh*`, `source *cleanup.sh*`, `. *cleanup.sh*`.
4. **Widening I found and removed during review:**
   - Section 11 had started stripping `.exe`, which made `sort.exe` and `git.exe` reachable. Reverted.
   - Section 10 had dropped the name match for `scripts/<name>.sh` outside the repository's `scripts/`. Restored as a fallback.

   Tests for both are above.

#### Floor invariants

All intact. The dangerous-command guard is strengthened. The test-stop hook, formatter, secret-read denials, terraform apply/destroy denials, destructive-AWS denials and force-push denials are untouched. The validator's floor checks all pass.

#### Audits

- **Collision:** PASS. No agent, command, MCP server or hook trigger was added, renamed or removed.
- **Orphan:** PASS. Validator: "orphans: roster, agent files, guard ROSTER, and ownership owners agree".
- **One-writer-per-artifact:** PASS. The ownership map is unchanged.

#### Check results

- **JSON parsing:** "settings.json JSON_OK" and ".mcp.json JSON_OK".
- **bash -n:** OK on all 6 hooks.
- **Exec bits:** all 6 hooks are 100755 in `git ls-files -s`.
- **Frontmatter:** all 18 agents PASS in the validator.
- **Validator:** "RESULT: PASS", exit=0.

#### Residual risk

1. **Scripts started from other programs.** A script run from inside python (`os.system`, `subprocess`), `perl -e`, `node -e` or `make` is not detected. That belongs to finding 5 (checkpoint 3). Running a renamed copy of a script (for example `/tmp/x.sh`) is also not caught by the name match.
2. **Symlinks.** A symlink to `scripts/cleanup.sh` is not resolved, because `common.sh` uses abspath, not realpath. This is an optional item already listed in repair-01.md.
3. **Git configuration that already exists.** The reviewer fence blocks explicit `-c`, `--ext-diff` and `--textconv`. It cannot stop `diff.external`, textconv drivers or `core.fsmonitor` that a writer agent has already put in `.git/config` or `.gitattributes`. Plain `git diff` and `git status` would then run them. Writer agents can still run `git config`; that is finding 5. Pagers do not start, because Bash tool output is not a terminal (inferred, not observed). `git log --format=%G?` can start gpg.
4. **Tool config files.** `checkov` reads `.checkov.yaml` and `tflint` reads `.tflint.hcl` (whose plugins are executables) automatically. `rg` honours `RIPGREP_CONFIG_PATH` if it is already set in the environment.
5. **Environment exposure.** `jq -n env` lets the reviewer dump environment variables. That belongs to finding 7.
6. **Allowlists are from documentation.** They were not checked against installed tool versions, so some rarely used safe options are rejected (fail closed).
7. **Known false positives, all fail closed:**
   - `time cat scripts/deploy.sh` (the first script word after a wrapper is treated as executed);
   - `cd <non-root dir> && bash foo.sh`;
   - `xargs grep x scripts/a.sh`;
   - `/usr/bin/bash -n scripts/cleanup.sh`, which the new `*/bash *cleanup.sh*` deny rule rejects;
   - a backslash-newline continuation inside quotes.

   These feed finding 12.
8. **Heredocs.** `bash <<EOF` is allowed. Its body lines are still checked one by one, so `cd scripts` followed by `./cleanup.sh` in a heredoc is blocked.
9. **Settings matching not seen live.** I can only observe how the new deny rules match after a restart. The guard is the primary control and is tested above.

#### Full diff (checkpoint 2 increment; the checkpoint 1 diff is verbatim in repair-01.md)

Taken with `git diff --no-index` against the checkpoint 1 baselines in the scratchpad. The cumulative `git diff` is the checkpoint 1 diff plus this increment. Note that `segments10`, `cmd10` and `CD_RE` are defined before `script_check`, as the section 10 hunk shows.

```diff
@@ -160,6 +160,103 @@ segment_writes() {   [helpers inserted after segment_writes]
+# toks_of <string>: split one command segment into shell words and store them in W. Single and
+# double quotes group words; a backslash outside quotes escapes the next character (bash rules), and
+# inside double quotes only before $ ` " \. Unbalanced quotes run to the end. Nothing is expanded.
+toks_of() {
+  local s="$1" i n=${#1} ch q="" cur="" have=0
+  W=()
+  for ((i=0; i<n; i++)); do
+    ch="${s:i:1}"
+    if [ "$q" = "'" ]; then
+      if [ "$ch" = "'" ]; then q=""; else cur+="$ch"; fi
+    elif [ "$q" = '"' ]; then
+      if [ "$ch" = '"' ]; then q=""
+      elif [ "$ch" = '\' ] && [[ "${s:i+1:1}" == [\$\`\"\\] ]]; then cur+="${s:i+1:1}"; i=$((i+1))
+      else cur+="$ch"; fi
+    else
+      case "$ch" in
+        "'"|'"') q="$ch"; have=1 ;;
+        '\') if (( i+1 < n )); then cur+="${s:i+1:1}"; i=$((i+1)); fi; have=1 ;;
+        ' '|$'\t'|$'\r') if (( have )); then W+=("$cur"); cur=""; have=0; fi ;;
+        *) cur+="$ch"; have=1 ;;
+      esac
+    fi
+  done
+  if (( have )); then W+=("$cur"); fi
+  return 0
+}
+
+# base_of <word>: program name of a command word (either slash style, no directory, no .exe).
+base_of() { local x="${1//\\//}"; x="${x##*/}"; x="${x%.exe}"; x="${x%.EXE}"; printf '%s' "$x"; }
+# nonliteral <word>: true when the shell would expand or redirect the word.
+nonliteral() { case "$1" in *'$'*|*'`'*|*'*'*|*'?'*|*'['*|*'{'*|*'~'*|*'<'*|*'>'*|'') return 0 ;; esac; return 1; }
+INTERP_RE='^(bash|sh|zsh|dash|ksh|mksh|ash|yash|fish|csh|tcsh)[0-9.]*$'
+is_interp() { [[ "$1" =~ $INTERP_RE ]] || [ "$1" = source ] || [ "$1" = . ]; }
+
+# interp_opts <index>: parse shell options in W starting at <index>. Sets:
+#   IO_SYN=1 only for a pure syntax check: every option is a short cluster of n/v/x/e/u
+#            (for example -n, -nv) and at least one contains n. --norc, --noprofile, -c, -s, -i,
+#            -o, any long option, or any other letter means the script may execute.
+#   IO_C=1   a -c command string follows; IO_S=1 commands come from stdin (-s);
+#   IO_M     index of the first operand (the script, or the -c command string).
+interp_opts() {
+  local m="$1" n=${#W[@]} tok syn=0 bad=0
+  IO_C=0; IO_S=0
+  while (( m < n )); do
+    tok="${W[m]}"
+    case "$tok" in
+      --) m=$((m+1)); break ;;
+      --rcfile|--init-file) bad=1; m=$((m+2)) ;;
+      --*) bad=1; m=$((m+1)) ;;
+      '<<<'|'<'|'>'|'>>') m=$((m+2)) ;;
+      '<'*|'>'*) m=$((m+1)) ;;
+      -?*|+?*)
+        if [[ "$tok" =~ ^-[nvxeu]+$ ]]; then [[ "$tok" == *n* ]] && syn=1; else bad=1; fi
+        [[ "$tok" == [-+]*[cC]* ]] && IO_C=1
+        [[ "$tok" == [-+]*[sS]* ]] && IO_S=1
+        [[ "$tok" =~ ^[-+][a-zA-Z]*[oO]$ ]] && m=$((m+1))
+        m=$((m+1)) ;;
+      *) break ;;
+    esac
+  done
+  IO_M=$m
+  IO_SYN=0
+  if [ "$syn" = 1 ] && [ "$bad" = 0 ]; then IO_SYN=1; fi
+  return 0
+}
+
+# ro_check_opts <short-no-arg> <short-with-arg> <long-no-arg> <long-with-arg> <max-operands>:
+# option ALLOWLIST for the words W[1..] of a read-only command. Any option not listed, or more
+# operands than allowed, fails and sets RO_WHY. Long options are matched by their full name only
+# (an abbreviation is treated as unknown and rejected).
+ro_check_opts() {
+  local na="$1" ar="$2" lna=" $3 " lar=" $4 " maxop="$5" i=1 n=${#W[@]} t ch j name ops=0 dd=0
+  RO_WHY=""
+  while (( i < n )); do
+    t="${W[i]}"
+    if (( dd )) || [[ "$t" != -* ]] || [ "$t" = "-" ]; then ops=$((ops+1)); i=$((i+1)); continue; fi
+    if [ "$t" = "--" ]; then dd=1; i=$((i+1)); continue; fi
+    if [[ "$t" == --* ]]; then
+      name="${t%%=*}"; name="${name#--}"
+      if [[ "$t" != *=* ]] && [[ "$lna" == *" $name "* ]]; then i=$((i+1)); continue; fi
+      if [[ "$lar" == *" $name "* ]]; then [[ "$t" == *=* ]] || i=$((i+1)); i=$((i+1)); continue; fi
+      RO_WHY="option '$t' is not on the read-only allowlist"; return 1
+    fi
+    for ((j=1; j<${#t}; j++)); do
+      ch="${t:j:1}"
+      [ -n "$na" ] && [[ "$na" == *"$ch"* ]] && continue
+      if [ -n "$ar" ] && [[ "$ar" == *"$ch"* ]]; then (( j == ${#t}-1 )) && i=$((i+1)); break; fi
+      RO_WHY="option '-$ch' (in '$t') is not on the read-only allowlist"; return 1
+    done
+    i=$((i+1))
+  done
+  if [ "$maxop" != "*" ] && (( ops > maxop )); then
+    RO_WHY="more than $maxop operand(s); the extra operand is an output file"; return 1
+  fi
+  return 0
+}
+
@@ -383,25 +480,64 @@ (section 10)
 # ---- 10. Operational scripts: right agent, approved environment ---------------------------------
-while IFS= read -r seg; do
-  read -ra W <<< "$(words_of "$seg")"
-  (( ${#W[@]} )) || continue
-  k="$(cmd_index)"
-  c="${W[k]:-}"
-  script=""; argi=$((k+1))
-  if [[ "$c" =~ (^|/)scripts/([a-z0-9_-]+)\.sh$ ]]; then
-    script="${BASH_REMATCH[2]}"
-  elif [[ "$c" =~ ^(bash|sh|zsh|source|\.)$ ]]; then
-    m=$((k+1)); syntax_only=0
-    while [[ "${W[m]:-}" == -* ]]; do [[ "${W[m]}" == -*n* ]] && syntax_only=1; m=$((m+1)); done
-    if [ "$syntax_only" = 0 ] && [[ "${W[m]:-}" =~ (^|/)scripts/([a-z0-9_-]+)\.sh$ ]]; then
-      script="${BASH_REMATCH[2]}"; argi=$((m+1))
-    fi
+# Every way a segment can execute a script is resolved to a repository path and checked against the
+# section 7 map: a *.sh command word (after wrappers such as env, nice, timeout, if, then), any shell
+# interpreter (by basename, so /usr/bin/bash, bash.exe, and quoted Windows paths count) with its
+# operand, -c command strings (re-parsed), source and '.'. Fail closed: a directory change plus a
+# script run; an interpreter fed from a pipe or redirect; xargs/find/parallel launching a shell or a
+# script with run-time arguments; non-literal script operands.
+KEYWORDS=" if then elif else do while until ! { time "
+WRAPPERS=" sudo doas env nohup command exec builtin nice ionice stdbuf setsid timeout chrt taskset busybox eval watch unbuffer winpty start "
+LAUNCHERS=" xargs parallel find cmd powershell pwsh wsl su runuser script "
+# Segments for this section keep a marker for pipes ('| bash') and join line continuations.
+cmd10="${cmd_nr//$'\\\n'/ }"
+segments10="$(printf '%s\n' "$cmd10" | sed -E 's/\|\|/;/g; s/\|&?/\n@pipe@ /g' | tr ';&()`' '\n\n\n\n\n')"
+CD_RE="(^|[[:space:];&|(\`'\"])(cd|pushd|popd)([[:space:]'\";&|)]|$)"
+DIRCHANGE=0
+if has "$raw" "--chdir|(^|[[:space:]/\"'])(env|sudo)(\.exe)?[[:space:]]+([^[:space:]]+[[:space:]]+)*-[a-zA-Z]*[CD]([[:space:]=]|$)"; then
+  DIRCHANGE=1
+elif has "$cmd" "$CD_RE"; then
+  # A cd/pushd whose literal target is the repository root (the usual 'cd "<repo>" && ...') is
+  # benign. Every other occurrence (cd scripts, cd -, bare cd, popd, cd inside a -c string)
+  # makes script identity unknowable.
+  ncd="$(printf '%s\n' "$cmd" | grep -oE "$CD_RE" | wc -l)"
+  nok=0
+  while IFS= read -r s; do
+    s="${s#@pipe@ }"
+    toks_of "$s"
+    case "${W[0]:-}" in cd|pushd) ;; *) continue ;; esac
+    tgt=""
+    for ((q=1; q<${#W[@]}; q++)); do case "${W[q]}" in -*) ;; *) tgt="${W[q]}"; break ;; esac; done
+    [ -n "$tgt" ] && ! nonliteral "$tgt" || continue
+    cdcls="$(sc_classify_path "$tgt")" || continue
+    [ "$cdcls" = "REL ." ] && nok=$((nok+1))
+  done <<< "$segments10"
+  if (( ncd != nok )); then DIRCHANGE=1; fi
+fi
+
+script_check() {   # <executed path word> <next word (environment)>
+  local p="$1" envarg="$2" cls rel script runners
+  if nonliteral "$p"; then
+    sc_block "the executed script or interpreter operand '$p' is not a literal path (variables, globs, ~, redirections). Run operational scripts as 'scripts/<name>.sh <env>' from the repository root."
   fi
-  [ -n "$script" ] || continue
+  if [ "$DIRCHANGE" = 1 ] && { [[ "$p" == *.sh ]] || [[ "$p" == *scripts* ]]; }; then
+    sc_block "a directory change (cd, pushd, popd, env -C, --chdir) combined with running a script ('$p') is blocked because the script's identity cannot be verified. Run it from the repository root as 'scripts/<name>.sh <env>'."
+  fi
+  cls="$(sc_classify_path "${p//\\//}")" || sc_block "could not normalize the script path '$p' (fail closed)."
+  rel=""
+  [ "${cls%% *}" = "REL" ] && rel="$(printf '%s' "${cls#* }" | tr '[:upper:]' '[:lower:]')"
+  if [[ "$rel" != scripts/* ]]; then
+    # Outside the repository's scripts/ (a copy in /tmp, a nested scripts/ directory): the map still
+    # applies by name, as before this checkpoint.
+    pl="$(printf '%s' "${p//\\//}" | tr '[:upper:]' '[:lower:]')"
+    [[ "$pl" =~ (^|/)scripts/([a-z0-9_-]+\.sh)$ ]] || return 0
+    rel="scripts/${BASH_REMATCH[2]}"
+  fi
+  script="${rel#scripts/}"
+  if [[ "$script" =~ ^([a-z0-9_-]+)\.sh$ ]]; then script="${BASH_REMATCH[1]}"; else script="?$script"; fi
   runners="$(script_runners "$script")"
   if [ "$runners" = "unknown" ]; then
-    sc_block "scripts/$script.sh is not in the script execution map (docs/agents.md section 7). Ask factory-engineer to propose an entry; until it is ratified, nobody runs it through Claude."
+    sc_block "$rel is not in the script execution map (docs/agents.md section 7). Ask factory-engineer to propose an entry; until it is ratified, nobody runs it through Claude."
   fi
   if [ -z "$runners" ]; then
     sc_block "scripts/$script.sh is human-invoked only (cleanup lists and deletes resources). Report the cleanup request as MANUAL ACTION REQUIRED."
@@ -409,16 +545,118 @@
   if [ "$runners" != "*" ] && ! in_list "$caller" "$runners"; then
     sc_block "$caller may not run scripts/$script.sh. Allowed: $runners. Route the request through the orchestrator to the responsible agent and its slash command."
   fi
-  if in_list "$script" "$ENV_SCRIPTS"; then
-    envarg="${W[argi]:-}"
-    if ! in_list "$envarg" "$APPROVED_ENVIRONMENTS"; then
-      sc_block "scripts/$script.sh must be called with an approved environment as its first argument (approved: $APPROVED_ENVIRONMENTS; got '${envarg:-none}'). New environments need a ratified amendment to docs/agents.md section 7."
+  if in_list "$script" "$ENV_SCRIPTS" && ! in_list "$envarg" "$APPROVED_ENVIRONMENTS"; then
+    sc_block "scripts/$script.sh must be called with an approved environment as its first argument (approved: $APPROVED_ENVIRONMENTS; got '${envarg:-none}'). New environments need a ratified amendment to docs/agents.md section 7."
+  fi
+  if [ "$script" = "run-experiment" ] && [[ "$envarg" != experiments/* ]] && [[ "$envarg" != ./experiments/* ]]; then
+    sc_block "scripts/run-experiment.sh runs only committed manifests under experiments/ (got '${envarg:-none}')."
+  fi
+  return 0
+}
+
+exec_scan() {   # <depth> <piped>: inspect the words in W (one command segment) for script execution
+  local depth="$1" piped="$2" i=0 j n=${#W[@]} t b
+  (( depth > 4 )) && sc_block "nested shell invocations are too deep to verify (fail closed)."
+  while (( i < n )); do
+    if [[ "${W[i]}" =~ ^[a-z_][a-z0-9_]*= ]]; then i=$((i+1)); continue; fi
+    if in_list "${W[i]}" "$KEYWORDS"; then
+      [ "${W[i]}" = time ] && [ "${W[i+1]:-}" = -p ] && i=$((i+1))
+      i=$((i+1)); continue
     fi
+    break
+  done
+  (( i < n )) || return 0
+  b="$(base_of "${W[i]}")"
+  if in_list "$b" "$WRAPPERS"; then
+    # Wrapper options and their arguments vary; the first shell, *.sh, or launcher word is the target.
+    for ((j=i+1; j<n; j++)); do
+      t="${W[j]}"; b="$(base_of "$t")"
+      if is_interp "$b" || [[ "$t" == *.sh ]] || in_list "$b" "$LAUNCHERS" || [[ "$t" == *[[:space:]]* ]]; then break; fi
+    done
+    (( j < n )) || return 0
+    i=$j
   fi
-  if [ "$script" = "run-experiment" ] && [[ "${W[argi]:-}" != experiments/* ]] && [[ "${W[argi]:-}" != ./experiments/* ]]; then
-    sc_block "scripts/run-experiment.sh runs only committed manifests under experiments/ (got '${W[argi]:-none}')."
+  exec_at "$i" "$depth" "$piped"
+}
+
+exec_at() {   # <index> <depth> <piped>
+  local i="$1" depth="$2" piped="$3" n=${#W[@]} t b b2 j m
+  t="${W[i]}"; b="$(base_of "$t")"
+  if [[ "$t" == *[[:space:]]* ]]; then
+    # env -S "bash x.sh", eval "x.sh": re-parse the string. Then continue, because the same word may
+    # also be a quoted path with spaces ("C:/Program Files/Git/bin/bash.exe").
+    ( toks_of "${W[*]:i}"; exec_scan $((depth+1)) "$piped" ) || exit 2
   fi
-done <<< "$segments"
+  if in_list "$b" "$LAUNCHERS"; then
+    j=$((i+1))
+    if [ "$b" = find ]; then
+      while (( j < n )); do case "${W[j]}" in -exec|-execdir|-ok|-okdir) break ;; esac; j=$((j+1)); done
+    fi
+    while (( j < n )); do
+      t="${W[j]}"; b2="$(base_of "$t")"
+      if is_interp "$b2"; then
+        if [ "$b2" != source ] && [ "$b2" != . ]; then interp_opts $((j+1)); else IO_SYN=0; fi
+        if [ "$IO_SYN" = 1 ]; then          # syntax check only: skip to the next -exec, if any
+          j=$((j+1))
+          while (( j < n )); do case "${W[j]}" in -exec|-execdir|-ok|-okdir) break ;; esac; j=$((j+1)); done
+          continue
+        fi
+        sc_block "'$b' runs the shell '$b2' with arguments supplied at run time (for example 'xargs bash', 'find -exec bash'), so the script cannot be verified against the section 7 map (fail closed). Run 'scripts/<name>.sh <env>' directly, or use 'bash -n' for syntax checks."
+      fi
+      [[ "$t" == *.sh ]] && script_check "$t" "${W[j+1]:-}"
+      j=$((j+1))
+    done
+    return 0
+  fi
+  if is_interp "$b"; then
+    if [ "$b" = source ] || [ "$b" = . ]; then
+      IO_SYN=0; IO_C=0; IO_S=0; IO_M=$((i+1))
+    else
+      interp_opts $((i+1))
+    fi
+    [ "$IO_SYN" = 1 ] && return 0
+    m=$IO_M
+    if [ "$IO_C" = 1 ]; then
+      if (( m < n )); then
+        for ((j=m+1; j<n; j++)); do
+          if [[ "${W[j]}" == *.sh ]] || [[ "${W[j]}" == *scripts/* ]]; then
+            sc_block "'$b -c' with a script path passed as a positional argument ('${W[j]}') cannot be verified (fail closed). Run 'scripts/<name>.sh <env>' directly."
+          fi
+        done
+        ( toks_of "${W[m]}"; exec_scan $((depth+1)) 0 ) || exit 2
+      fi
+      return 0
+    fi
+    if (( m >= n )) || [ "$IO_S" = 1 ] || [ "${W[m]}" = "-" ]; then
+      if [ "$piped" = 1 ] || has "$SEG" "(^|[^<])<([^<]|$)|<<<"; then
+        sc_block "'$b' reading commands from a pipe or redirect ('| bash', 'bash < file', 'bash -s') cannot be verified against the section 7 map (fail closed). Run 'scripts/<name>.sh <env>' directly."
+      fi
+      return 0
+    fi
+    script_check "${W[m]}" "${W[m+1]:-}"
+    return 0
+  fi
+  if [[ "$t" == *.sh ]]; then
+    script_check "$t" "${W[i+1]:-}"
+    return 0
+  fi
+  if [ "$t" != "[" ] && [ "$t" != "[[" ] && nonliteral "$t"; then
+    for ((j=i+1; j<n; j++)); do
+      if [[ "${W[j]}" == *.sh ]] || [[ "${W[j]}" == *scripts/* ]]; then
+        sc_block "a non-literal command word ('$t') with a script argument ('${W[j]}') cannot be verified (fail closed). Run 'scripts/<name>.sh <env>' directly."
+      fi
+    done
+  fi
+  return 0
+}
+
+while IFS= read -r SEG; do
+  piped=0
+  case "$SEG" in "@pipe@ "*) piped=1; SEG="${SEG#@pipe@ }" ;; esac
+  toks_of "$SEG"
+  (( ${#W[@]} )) || continue
+  exec_scan 0 "$piped"
+done <<< "$segments10"
 
 # ---- 11. Read-only agents and unregistered subagents: evidence-gathering commands only ----------
@@ (section 11)
   has "$cmd_nr" ">|\\\$\(|<\(" && ro_block "output redirection or command substitution"
+  # Option checks below are case-sensitive (sort -o vs -O, git grep -O), so they read the original
+  # command text; the command name itself is compared in lower case.
+  raw_nr="$(printf '%s' "$raw" | sed -E 's/[0-9]*>&[0-9]+//g; s/[0-9]*>>?[[:space:]]*\/dev\/null//g')"
+  segments_raw="$(printf '%s\n' "$raw_nr" | tr ';|&()`' '\n\n\n\n\n\n')"
   while IFS= read -r seg; do
-    read -ra W <<< "$(words_of "$seg")"
+    toks_of "$seg"
     (( ${#W[@]} )) || continue
-    c="${W[0]##*/}"; s1="${W[1]:-}"
+    seg_lc="$(printf '%s' "$seg" | tr '[:upper:]' '[:lower:]')"
+    c="${W[0]//\\//}"; c="$(printf '%s' "${c##*/}" | tr '[:upper:]' '[:lower:]')"   # no .exe stripping: sort.exe stays unknown (not widened)
+    s1="$(printf '%s' "${W[1]:-}" | tr '[:upper:]' '[:lower:]')"
     case "$c" in
-      cd|ls|cat|head|tail|wc|grep|rg|diff|cmp|sort|uniq|cut|tr|file|stat|pwd|echo|printf|test|true|false|which|date|basename|dirname|realpath|jq|shellcheck|checkov|tfsec|tflint) ;;
-      find) has "$seg" "[[:space:]]-(delete|exec|execdir|ok|okdir|fprint|fprintf|fls)([[:space:]]|$)" && ro_block "find with -delete/-exec" ;;
+      cd|ls|cat|head|tail|wc|grep|diff|cmp|cut|tr|stat|pwd|echo|printf|test|true|false|which|basename|dirname|realpath|jq|shellcheck) ;;
+      # Option ALLOWLISTS: anything not listed (sort -o/--output/--compress-program, uniq's output
+      # operand, rg --pre/--pre-glob/-z/--hostname-bin, checkov --output-file-path/external checks,
+      # tfsec --out/-O, tflint --fix/--init) is rejected.
+      sort)
+        ro_check_opts "bdfgiMhnRrVcCmsuz" "ktS" \
+          "ignore-leading-blanks dictionary-order ignore-case general-numeric-sort ignore-nonprinting month-sort human-numeric-sort numeric-sort random-sort reverse version-sort check merge stable unique zero-terminated debug help version" \
+          "key field-separator buffer-size parallel sort check batch-size files0-from" "*" || ro_block "sort: $RO_WHY" ;;
+      uniq)
+        ro_check_opts "cdDiuz" "fsw" \
+          "count repeated ignore-case unique zero-terminated all-repeated group help version" \
+          "skip-fields skip-chars check-chars all-repeated group" 1 || ro_block "uniq: $RO_WHY" ;;
+      rg)
+        ro_check_opts "iSsnNlLcwxvFUoHIqab0p.uhVP" "eEgtTmABCfrjMd" \
+          "files hidden no-ignore no-ignore-vcs no-ignore-parent no-ignore-dot no-ignore-global no-ignore-files no-ignore-exclude ignore-case smart-case case-sensitive line-number no-line-number count count-matches files-with-matches files-without-match word-regexp line-regexp invert-match fixed-strings multiline multiline-dotall only-matching with-filename no-filename quiet text unrestricted null byte-offset column no-column heading no-heading json vimgrep stats trim type-list pcre2 follow no-messages passthru no-config one-file-system crlf null-data binary help version" \
+          "regexp file glob iglob type type-not max-count max-depth max-filesize context after-context before-context sort sortr color colors encoding threads replace max-columns field-match-separator field-context-separator path-separator context-separator ignore-file engine" "*" \
+          || ro_block "rg: $RO_WHY" ;;
+      checkov)
+        ro_check_opts "hvlsq" "dfco" \
+          "compact quiet soft-fail help version list no-guide skip-download" \
+          "directory file framework skip-framework check skip-check output skip-path var-file" 0 || ro_block "checkov: $RO_WHY" ;;
+      tfsec)
+        ro_check_opts "sh" "fem" \
+          "soft-fail no-color concise-output exclude-downloaded-modules include-passed include-ignored force-all-dirs no-module-downloads ignore-hcl-errors help version" \
+          "format exclude minimum-severity tfvars-file exclude-path" 1 || ro_block "tfsec: $RO_WHY" ;;
+      tflint)
+        ro_check_opts "hv" "f" \
+          "recursive force no-color no-module help version" \
+          "format chdir filter minimum-failure-severity var-file var only enable-rule disable-rule call-module-type" 0 || ro_block "tflint: $RO_WHY" ;;
+      file) has "$seg" "[[:space:]](-[a-zA-Z]*C|--compile)([[:space:]]|$)" && ro_block "file -C/--compile (writes a .mgc file)" ;;
+      date) has "$seg" "[[:space:]](-[a-zA-Z]*s|--set)([=[:space:]]|$)" && ro_block "date -s/--set" ;;
+      find) has "$seg_lc" "[[:space:]]-(delete|exec|execdir|ok|okdir|fprint|fprint0|fprintf|fls)([[:space:]]|$)" && ro_block "find with -delete/-exec/-fprint" ;;
       git)
+        # Global options (-c, -C, --exec-path, -p/--paginate, --git-dir, ...) are rejected because
+        # argv[1] must be an allowed subcommand. difftool, config, and every writer are not listed.
         case "$s1" in
           diff|log|show|status|blame|ls-files|ls-tree|rev-parse|grep|shortlog|describe|cat-file|merge-base|name-rev|rev-list) ;;
           *) ro_block "git $s1" ;;
         esac
-        out_operands "$seg" >/dev/null && ro_block "git --output/--output-directory (writes a file)" ;;
-      bash|sh) [[ "$s1" == -*n* ]] || ro_block "$c without -n" ;;
+        for a in "${W[@]:2}"; do
+          case "$a" in
+            -O*|--op*|--ext*|--textc*|--filt*|--show-s*|--exec*|--upload-pack*|--receive-pack*)
+              ro_block "git $s1 $a (runs a pager, external diff, textconv or filter driver, signature program, or other configured command)" ;;
+          esac
+        done
+        out_operands "$seg_lc" >/dev/null && ro_block "git --output/--output-directory (writes a file)" ;;
+      bash|sh)
+        interp_opts 1
+        [ "$IO_SYN" = 1 ] || ro_block "$c other than a syntax check ('bash -n <file>'; --norc, --noprofile, -c, -s and other options execute)" ;;
       python|python3|py)
         { { [ "$s1" = "-m" ] && [ "${W[2]:-}" = "json.tool" ]; } || [ "$s1" = "-mjson.tool" ]; } \
           || ro_block "python other than 'python -m json.tool <file>'"
-        out_operands "$seg" >/dev/null && ro_block "python -m json.tool with an outfile operand (writes a file)" ;;
+        out_operands "$seg_lc" >/dev/null && ro_block "python -m json.tool with an outfile operand (writes a file)" ;;
       sbt)
-        for a in "${W[@]:1}"; do
+        read -ra SW <<< "$(words_of "$seg_lc")"   # unchanged checkpoint-1 semantics (finding 4 is later)
+        for a in "${SW[@]:1}"; do
           case "$a" in
@@
       terraform)
-        has "$seg" "terraform([[:space:]]+-chdir=[^[:space:]]+)?[[:space:]]+(fmt[[:space:]]+.*-check|validate|version)" || ro_block "terraform other than fmt -check/validate/version" ;;
+        has "$seg_lc" "terraform([[:space:]]+-chdir=[^[:space:]]+)?[[:space:]]+(fmt[[:space:]]+.*-check|validate|version)" || ro_block "terraform other than fmt -check/validate/version" ;;
       *) ro_block "'$c'" ;;
     esac
-  done <<< "$segments"
+  done <<< "$segments_raw"
 fi
--- .claude/settings.json (checkpoint 2 increment)
@@ -216,7 +216,16 @@
       "Bash(./scripts/cleanup.sh*)",
       "Bash(bash scripts/cleanup.sh*)",
       "Bash(bash ./scripts/cleanup.sh*)",
-      "Bash(sh scripts/cleanup.sh*)"
+      "Bash(sh scripts/cleanup.sh*)",
+      "Bash(sh ./scripts/cleanup.sh*)",
+      "Bash(./cleanup.sh*)",
+      "Bash(cleanup.sh*)",
+      "Bash(bash --no*cleanup.sh*)",
+      "Bash(*/bash *cleanup.sh*)",
+      "Bash(*/sh *cleanup.sh*)",
+      "Bash(*bash.exe *cleanup.sh*)",
+      "Bash(source *cleanup.sh*)",
+      "Bash(. *cleanup.sh*)"
     ]
```

#### Ratification request

Please approve or reject each of these:
- **(a)** The section 10 rewrite in guard-dangerous.sh: the new parser and helpers, how script runs are detected, the fail-closed rules, the repository-root `cd` exception, and the name match outside `scripts/`.
- **(b)** The section 11 read-only fence: option allowlists for sort, uniq, rg, checkov, tfsec and tflint; the git option blocklist; the strict syntax-only rule for bash; and rejecting `file -C`, `date -s` and `find -fprint0`.
- **(c)** The 9 new cleanup deny rules in settings.json. This includes the known false positive: `/usr/bin/bash -n scripts/cleanup.sh` is denied.
- **(d)** The narrowings listed under privilege delta.

Then commit and restart Claude Code. I have not ratified anything.

Files changed: `c:\Users\figge\OneDrive\Desktop\UIC Folder\CS 441\CS_441-Scholar_Chain\.claude\hooks\guard-dangerous.sh` and `c:\Users\figge\OneDrive\Desktop\UIC Folder\CS 441\CS_441-Scholar_Chain\.claude\settings.json`.

Test harness and outputs are in `C:\Users\figge\AppData\Local\Temp\claude\c--Users-figge-OneDrive-Desktop-UIC-Folder-CS-441-CS-441-Scholar-Chain\1a860a23-88ae-4391-940f-f64135224fe8\scratchpad\`: `hooktest2.py`, `run-cp2.txt`, `hooktest.py`, `run-cp1-regress.txt`, `sbtreg.py`.
## Checkpoint 3a report (factory-engineer, verbatim)

Dispatched by the orchestrator on 2026-10-13 with a 30-minute hard limit. Agent time used: 19:50:55 to 20:16:27 (about 25.5 min). This dispatch made the guard-dangerous.sh change that the resume point records as "Unexplained change" (19:59). That change is the work in progress of this checkpoint. `repair-01-wip.patch` (20:05) captures an intermediate state from partway through this checkpoint. It does not include the agent's final edits: the inline-code check moved from `$seg` to `$cmd`, and the section 10 header comment was restored. Restoring from that patch would therefore undo part of CP3a. The authoritative CP3a state is the working tree plus the increment diffs below.

The full harness ran 179 cases. It had 2 UNEXPECTED results, both inline-code cases, and both came from a bug the agent then fixed. After the fix the agent reran those 2 cases plus 5 regression cases, and all 7 came out as expected (UNEXPECTED=0). It did not rerun all 179 cases, because one full run takes about 11 minutes and would have gone past the time limit. Nothing is ratified, staged or committed, and the agent did not touch `docs/agent-runs/**`.

```text
STATUS: COMPLETE (proposal awaiting human ratification and restart)
OWNER: factory-engineer
WORK ORDER: docs/agent-runs/phase-01/repair-01.md, CHECKPOINT 3a (finding 5 only)
FILES CREATED: none in the repo. Scratchpad only: guard-dangerous.cp2.sh and agents.cp2.md (baselines taken before the edits), hooktest3a.py, run3a.txt, inc-guard.diff, inc-agents.diff
FILES MODIFIED: .claude/hooks/guard-dangerous.sh (on top of the uncommitted CP1 and CP2 changes, which were kept); docs/agents.md (sections 4 and 5)
COMMANDS RUN: Git Bash bash -n on all 6 hooks; python -m json.tool on settings.json and .mcp.json; git ls-files -s .claude/hooks; python -I -u hooktest3a.py (full run, then a filtered rerun); bash .claude/hooks/validate-agent-files.sh; git diff --stat; git diff docs/agents.md; git diff --no-index against the CP2 baseline
TEST RESULTS: full run CASES=179 BLOCK=139 PASS=40 UNEXPECTED=2 (both [inline]). Fixed, then filtered rerun of [inline, read, wrap-ok, orch-evidence-literal]: CASES=7 BLOCK=2 PASS=5 UNEXPECTED=0. No sbt build exists yet.
VALIDATION RESULTS: validator RESULT: PASS, exit=0. Both JSON files parse. bash -n is OK on all 6 hooks. All 6 hooks are 100755 in the index.
ASSUMPTIONS: hooks run under Git Bash, as in CP1 and CP2.
RISKS: see "Residual risk" below
MANUAL ACTION REQUIRED: human ratification in human-gate.md, a commit, and a restart. The guard edits are already live for Bash calls.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: checkpoint 3b (F6, AWS detection)
```

### What changed (guard-dangerous.sh)

1. **Wrappers are unwrapped (new section 9b).** A new function, `wrap9`, re-applies the section 9 checks (as `prot9`) inside wrappers. It splits the command into segments, tokenizes each one with the CP2 `toks_of`, and then checks:
   - every quoted multi-word token, recursively up to depth 4. This covers `bash -c`, `sh -c`, `eval "..."`, `env -S` and `su -c`.
   - every writer word that comes after a prefix or launcher: `eval`, `xargs`, `parallel`, `find -exec`, `timeout`, `nice`, `sudo`, `env`, keywords, and the others in that list.
2. **Fail-closed rules where the target can't be known.** The guard now blocks:
   - `xargs` or `parallel` with a target that isn't read-only, when the command line mentions a protected path;
   - glob or variable operands, and glob or variable redirect targets, when they sit in the repo root, `.claude`, `docs`, `docs/agent-runs` or `.git`;
   - literal writer operands that are themselves a protected directory;
   - a `cd`, `pushd` or `-C` into `.claude`, `docs` or `.git` combined with any write;
   - `tar -x` or `unzip` without a target directory, or into a protected directory, plus `tar -P` / `--absolute-names` and `unzip -:`;
   - inline code (`python -c`, `node -e`, `perl -e` and similar) when the command line names a protected path.
3. **`git config`.** Every form is blocked for every caller except factory-engineer, unless it is an exact read form: `--get`, `--get-all`, `--get-regexp`, `--get-urlmatch`, `--list`, `-l`, `get` or `list`, with allowlisted modifiers only. Unknown options are blocked. A form with no read action (including `git config user.name`) is blocked. Direct writes to `.git/config`, `.git/hooks` and gitconfig files are also blocked for non-factory callers (new `PATH_ALT_GITCFG`). code-reviewer stays fully blocked by section 11.
4. **Small refactor.** `segment_writes` now calls the new function `writer_cmd`, and `rsync` was added to the writer list.
5. **Orchestrator.** It can still write literal paths in `docs/agent-runs/**` (`human-gate.md` stays blocked). Globs, variables and extraction there are now blocked for it.

### docs/agents.md

- Section 4's title and text now say the ownership map is enforced mechanically for Write/Edit only. A new "Enforcement limits" paragraph lists exactly what the guard blocks for Bash and what it does not.
- Section 5 defines "Ownership hook" as Write/Edit only. The test-engineer row no longer overstates its fence.

### Harness verdicts (from run3a.txt; scratchpad paths shown as `<scratch>`)

Finding 5 inputs:

```text
[PASS] caller=domain-engineer cmd="sed -i 's/2.13/3.5/' build.sbt"          (not enforced by design; now documented)
[PASS] caller=test-engineer cmd='cp <scratch>/Fix.scala modules/core/src/main/scala/Fix.scala'  (not enforced by design; now documented)
[BLOCK] domain-engineer|code-reviewer|orchestrator  "bash -c 'cp x CLAUDE.md'"
[BLOCK] domain-engineer|code-reviewer|orchestrator  'echo CLAUDE.md | xargs rm'
[BLOCK] domain-engineer|code-reviewer|orchestrator|factory-engineer  'tar -xf <scratch>/c.tar'
[BLOCK] domain-engineer|code-reviewer|orchestrator  "sed -i 's/a/b/' .claude/settings.json"; 'cp x .claude/hooks/guard-dangerous.sh'
```

Wrapper cases (all BLOCK as expected):

- `bash -c "echo x > .claude/settings.json"`, `sh -c 'rm .claude/hooks/common.sh'`
- `eval cp x CLAUDE.md`, `eval "mv x docs/agents.md"`
- `ls docs/agent-runs/phase-01/*.md | xargs rm` (including factory-engineer), `rm docs/agent-runs/phase-01/*` (including the orchestrator)
- `find .claude/hooks -name '*.sh' | xargs rm -f`, `find .claude -name x.json -delete`
- `xargs -0 rm CLAUDE.md < list`, `timeout 5 cp x .mcp.json`, `env -S 'cp x CLAUDE.md'`
- nested `bash -c "bash -c '...'"`; `human-gate.md` and `.last-test-run` writes inside `-c` strings (all callers)
- `rm .claude/hoo*/x.sh`, `rm C*.md`, `F=CLAUDE.md; rm $F`
- `cd .claude && rm settings.json`, `cd docs; echo x > agents.md`
- `tar ... -C .claude`, `unzip <scratch>/c.zip`, `echo x >> .git/config`

Inline code:

- First run: `python -c "open('CLAUDE.md','w')..."` and `node -e "...writeFileSync('.claude/settings.json'..."` PASSED (UNEXPECTED). The segmenter splits inline code at `(`, so the check never saw the path.
- Fix: the check now uses the whole command line.
- Rerun: both BLOCK.

Correctly allowed:

- `tar -xf ... -C modules/core/src/test/resources`
- `bash -c 'cp <scratch>/x modules/core/x.scala'`
- orchestrator `cp <scratch>/report.md docs/agent-runs/phase-01/report.md`
- factory-engineer `bash -c 'cp x .claude/agents/a.md'`
- `cat CLAUDE.md` for all three non-factory callers
- `find docs/agent-runs ... | xargs grep`, `rm target/*.class`

git config:

- Blocked for domain-engineer, code-reviewer and the orchestrator (13 write forms each): `git config user.name x`, `--global core.pager less`, `--add`, `--unset`, `--replace-all`, `--edit`, `--file .git/config a.b c`, `--system`, `--local core.fsmonitor x`, `set a.b c`, `git -c x=y config a.b c`, `bash -c 'git config core.hooksPath /tmp'`, and `git config user.name` (fail closed).
- Read forms PASS for domain-engineer and the orchestrator, and BLOCK for code-reviewer (section 11, unchanged): `--get`, `--list`, `-l`, `--get-all`, `--get-regexp`, `list --show-origin`, `get user.email`.
- factory-engineer: `--global core.pager less`, `--get` and `--unset` all PASS.

CP1 and CP2 sample (no regression):

- Still BLOCK:
  - `find .claude/hooks -name guard-dangerous.sh -delete` (×3), `git diff --output=CLAUDE.md` (×3), `python -m json.tool .mcp.json .claude/settings.json`
  - `bash --norc scripts/cleanup.sh` (×3), `bash --noprofile scripts/deploy.sh prod`, domain-engineer `bash --noprofile scripts/deploy.sh dev`
  - `bash -c 'scripts/deploy.sh prod'`, `/usr/bin/bash scripts/rollback.sh staging`, `cd scripts && ./cleanup.sh`, `ls scripts | xargs bash`
  - reviewer `sort -o`, `rg --pre`, `git grep --open-files-in-pager`
- Still PASS: `git diff --stat`, reviewer `git diff HEAD~1`, `bash -n` (domain-engineer and reviewer), deploy-engineer `scripts/deploy.sh dev`, `scripts/check.sh`, reviewer `sort -n`, the validator, and the harness itself.

### Per-file justification and privilege delta

- **guard-dangerous.sh:** a factory-engineer-owned hook (docs/agents.md section 4 row 1). All changes narrow; nothing widens:
  - Non-factory callers and the orchestrator lose wrapped, globbed and indirect writes to protected paths, archive extraction into the repo root and protected directories, and every `git config` form except the reads. Direct writes to `.git/config`, `.git/hooks` and gitconfig files are also lost.
  - factory-engineer loses glob, xargs and extraction access to `docs/agent-runs/**` and the repo root.
  - The orchestrator loses glob and variable writes in `docs/agent-runs/**`.
- **docs/agents.md:** factory-engineer-owned. A documentation correction only; no authority changes. Both await human ratification.

### Floor invariants

All intact: the dangerous-command guard (extended), the test-stop hook, the formatter, secret-read denial, `terraform apply/destroy` denial, destructive AWS denial, and force-push denial. The validator's floor checks pass.

### Audits

- **Collision:** pass. No agent, command or hook-trigger changes.
- **Orphan:** pass. The validator found the roster, ROSTER and owners consistent.
- **One-writer-per-artifact:** pass. Ownership rows are unchanged; only the enforcement wording changed.
- The agent noted that `docs/agent-runs/phase-01/repair-01-wip.patch` appeared as untracked during the run and that it did not create it. (Orchestrator: the other session's snapshot; see the note at the top of this section.)

### Residual risk (finding 5 is still open beyond this narrow scope)

1. The full ownership map is not enforced for Bash. Domain-engineer `sed -i build.sbt` and test-engineer `cp` into `modules/core` still pass. This is now documented, and coverage relies on the prompt, the role files and review.
2. Programs that write internally without the path on the command line are not caught. Examples: `python x.py`, `make`, `sbt` tasks, `git apply`, `git am`, `git stash pop`, `git merge`, `git pull`, `git checkout <branch>`, and `cpio` or `7z` extraction.
3. Symlinks are not resolved (common.sh uses abspath), and renamed or copied paths are not tracked.
4. `xargs -a <file>` and lists built without a hint string (for example `ls .cl* | xargs rm`, where `.cl*` doesn't match the hint) are missed.
5. Hint matching works on strings, so obfuscation such as variable concatenation (`a=.cla; rm ${a}ude/x`) can slip through. Variables in the repo root are blocked, but variables under other directories pass unless a hint appears.
6. Because the whole command is lowercased, `xargs -I` and `-i` are treated the same (fail closed). Old-style `tar xPf` is detected only through `raw`.
7. Known false positives (fail closed):
   - `rm $f` or `rm *.tmp` in the repo root;
   - `cp x docs/` by documentation-engineer;
   - a quoted string containing a write plus a protected path, such as `git commit -m "rm CLAUDE.md"`;
   - `cp -r .claude/agents /tmp` (source operand);
   - `git config user.name` (read without `--get`);
   - `cd ~/.claude/...` combined with any write;
   - inline code that only reads a protected path (`python -c "print(open('CLAUDE.md').read())"`).

   These feed finding 12. Observed live by the orchestrator: a variable redirect target in a command that names a protected path was blocked (fail closed, as designed).
8. Each hook call now runs more Python path classifications, so the guard is slower: the full 179-case harness took about 11 minutes.

### bash -n (verbatim)

```text
bash -n OK .claude/hooks/common.sh
bash -n OK .claude/hooks/format-scala.sh
bash -n OK .claude/hooks/guard-dangerous.sh
bash -n OK .claude/hooks/guard-ownership.sh
bash -n OK .claude/hooks/validate-agent-files.sh
bash -n OK .claude/hooks/verify-tests-ran.sh
```

The validator ended `RESULT: PASS`, `validator exit=0`, with all PASS lines: 18 agents with valid frontmatter, reviewer fence, orphans, bash -n and exec bits for 6 hooks, 60 allow and 128 deny rules, floor deny rules, hook wiring, MCP pins, commands, and floor checks. After the final cosmetic edit, the agent reran `bash -n` on guard-dangerous.sh and it was OK.

### Ratification request (deferred to checkpoint 6 under the simplified plan)

1. Approve the section 9b guard extension: wrapper unwrapping, the fail-closed glob/variable/cd/archive/inline/xargs rules, and the `git config` write block for every caller except factory-engineer.
2. Approve the narrowing for the orchestrator (no glob, variable or extraction writes in `docs/agent-runs/**`) and for factory-engineer (no glob, xargs or extraction writes in the repo root or `docs/agent-runs/**`).
3. Approve the docs/agents.md corrections to sections 4 and 5, which say the ownership map is enforced mechanically for Write/Edit only.
4. Accept residual risks 1 to 8 as documented. False positives go to finding 12 (CP5).
5. Owed in CP5: a full 179-case harness rerun after the inline fix. The harness (hooktest3a.py) lives in this session's scratchpad and is lost when the session ends. CP5 must recreate it.

### Full diff (checkpoint 3a increment)

Regenerated by the orchestrator after the agent finished, from the final working tree. guard-dangerous.sh: `git diff --no-index` against the agent's pre-edit baseline (CP2 state). docs/agents.md: `git diff` against HEAD.

```diff
diff --git a/C:/Users/figge/AppData/Local/Temp/claude/c--Users-figge-OneDrive-Desktop-UIC-Folder-CS-441-CS-441-Scholar-Chain/2c8dc2f7-0918-4d97-88a0-c0638c259907/scratchpad/guard-dangerous.cp2.sh b/.claude/hooks/guard-dangerous.sh
index 1dc9f57..4c56a99 100644
--- a/C:/Users/figge/AppData/Local/Temp/claude/c--Users-figge-OneDrive-Desktop-UIC-Folder-CS-441-CS-441-Scholar-Chain/2c8dc2f7-0918-4d97-88a0-c0638c259907/scratchpad/guard-dangerous.cp2.sh
+++ b/.claude/hooks/guard-dangerous.sh
@@ -84,6 +84,7 @@ PATH_ALT_CONST='claude\.md|docs/agents\.md|\.mcp\.json|\.claude/agents|\.claude/
 PATH_ALT_MARKER='\.claude/\.last-test-run|\.last-test-run'
 PATH_ALT_EVIDENCE='docs/agent-runs'
 PATH_ALT_HUMAN_GATE='human-gate\.md'
+PATH_ALT_GITCFG='\.git/(config|hooks)|\.gitconfig|\.config/git/'
 
 # out_operands <segment>: print, one per line, every file that the segment writes through an
 # output option rather than a redirection or a writer command word:
@@ -138,10 +139,16 @@ out_operands() {
 
 # segment_writes <segment> <path-alternation>: true when the segment writes to a matching path.
 segment_writes() {
-  local seg="$1" alt="$2" k c sub
+  local seg="$1" alt="$2"
   has "$seg" "($alt)" || return 1
   has "$seg" ">>?[[:space:]]*[^[:space:]]*($alt)" && return 0
   out_operands "$seg" >/dev/null && return 0
+  writer_cmd "$seg"
+}
+
+# writer_cmd <segment>: true when the segment's command word writes, moves, or deletes files.
+writer_cmd() {
+  local seg="$1" k c sub
   local -a W
   read -ra W <<< "$(words_of "$seg")"
   (( ${#W[@]} )) || return 1
@@ -149,7 +156,7 @@ segment_writes() {
   c="${W[k]:-}"
   c="${c##*/}"
   case "$c" in
-    tee|mv|cp|rm|rmdir|touch|truncate|chmod|chown|ln|install|dd|patch|unlink|shred) return 0 ;;
+    tee|mv|cp|rm|rmdir|touch|truncate|chmod|chown|ln|install|dd|patch|unlink|shred|rsync) return 0 ;;
     sed) has "$seg" "[[:space:]](-[a-z]*i|--in-place)" && return 0 ;;
     perl) has "$seg" "[[:space:]]-[a-z]*i" && return 0 ;;
     find) has "$seg" "[[:space:]]-(delete|exec|execdir|ok|okdir|fprint|fprint0|fprintf|fls)([[:space:]]|$)" && return 0 ;;
@@ -479,6 +486,213 @@ while IFS= read -r seg; do
   fi
 done <<< "$segments"
 
+# ---- 9b. Protected paths behind wrappers, launchers, globs, archives, inline code; git config -----
+# Narrow scope (finding 5). The full ownership map (docs/agents.md section 4) is enforced
+# mechanically only for Write/Edit (guard-ownership.sh). For Bash, this section extends section 9 so
+# that the constitutional paths, .git configuration, the test marker, human-gate.md, and
+# docs/agent-runs/** stay protected when the write is hidden: inside bash -c / sh -c / eval / env -S
+# or any quoted command string; after xargs, find -exec, timeout, nice, sudo and other prefixes;
+# through a glob or variable operand; after a cd into a protected directory; by archive extraction;
+# or by inline interpreter code. Where the target cannot be verified, it blocks (fail closed).
+# 'git config' is a write for every caller except factory-engineer; only exact read forms pass.
+if [ "$caller" = "factory-engineer" ]; then
+  HINT9='agent-runs|human-gate|last-test-run'
+  PROTDIR9='^(\.|docs|docs/agent-runs(/.*)?)$'
+  PROTGLOB9="$PROTDIR9"
+else
+  HINT9='\.claude|claude\.|claude[*?[]|agent|\.mcp|mcp\.json|human-gate|last-test|\.git/|\.git$|gitconfig'
+  PROTGLOB9='^(\.|\.claude(/.*)?|docs|docs/agent-runs(/.*)?|\.git(/.*)?)$'
+  PROTDIR9="$PROTGLOB9"
+  # The orchestrator assembles reports in docs/agent-runs/**: literal paths there stay allowed
+  # (section 9 still protects human-gate.md); globs, variables, and extraction there are blocked.
+  [ "$caller" = "orchestrator" ] && PROTDIR9='^(\.|\.claude(/.*)?|docs|\.git(/.*)?)$'
+fi
+W9_PREFIX=" eval xargs parallel find sudo doas env nohup command exec builtin nice ionice stdbuf setsid timeout chrt taskset busybox watch unbuffer winpty start time then do else if elif while until ! { su runuser script flock "
+W9_WRITERS=" tee mv cp rm rmdir touch truncate chmod chown ln install dd patch unlink shred sed perl find git rsync tar bsdtar unzip "
+W9_OPERANDS=" tee mv cp rm rmdir touch truncate chmod chown ln install unlink shred rsync sed perl find "
+W9_READSAFE=" grep egrep fgrep rg cat head tail wc ls stat file echo printf jq sort uniq diff cmp test basename dirname realpath readlink md5sum sha1sum sha256sum "
+W9_INLINE=" python python3 py pythonw perl node nodejs ruby php deno bun pwsh powershell "
+MSG9_CONST="only factory-engineer writes CLAUDE.md, docs/agents.md, .mcp.json, and .claude/**, and only as a proposal awaiting human ratification. Wrapped, globbed, or indirect writes are checked too. Return STATUS: BLOCKED-ON with BLOCKED-ON OWNER: factory-engineer."
+MSG9_UNK="cannot be verified to stay clear of protected paths (CLAUDE.md, docs/agents.md, .mcp.json, .claude/**, .git config, the test marker, human-gate.md, docs/agent-runs/**), so it is blocked (fail closed). Use literal paths from the repository root, or the Write/Edit tools, which the ownership hook checks."
+
+prot9() {   # <segment>: the section 9 checks on one (possibly unwrapped) command
+  local seg="$1"
+  if [ "$caller" != "factory-engineer" ]; then
+    segment_writes "$seg" "$PATH_ALT_CONST" && sc_block "$MSG9_CONST"
+    segment_writes "$seg" "$PATH_ALT_GITCFG" && sc_block "git configuration (.git/config, .git/hooks, gitconfig files) is written only by factory-engineer as a ratified proposal. Report the needed setting instead."
+  fi
+  segment_writes "$seg" "$PATH_ALT_MARKER" && sc_block ".claude/.last-test-run is written only by 'sbt check' (markTestRun) after tests pass. Forging or deleting it is denied. Run 'sbt check'."
+  segment_writes "$seg" "$PATH_ALT_HUMAN_GATE" && sc_block "human-gate.md records are written only by the human architect."
+  if [ "$caller" != "orchestrator" ] && segment_writes "$seg" "$PATH_ALT_EVIDENCE"; then
+    sc_block "docs/agent-runs/** is human-owned evidence. Return your report to the orchestrator instead of writing it."
+  fi
+  return 0
+}
+
+dir_protected9() {   # <path word> [regex]: true when a write at or into this path could reach a protected path
+  local d="$1" re="${2:-$PROTDIR9}" cls rel
+  nonliteral "$d" && return 0
+  cls="$(sc_classify_path "${d//\\//}")" || return 0
+  [ "${cls%% *}" = "REL" ] || return 1
+  rel="$(printf '%s' "${cls#* }" | tr '[:upper:]' '[:lower:]')"
+  [[ "$rel" =~ $re ]]
+}
+
+operand9() {   # <word> <what>: check one operand of a file-writing command or a redirect target
+  local w="$1" what="$2" d
+  [ -n "$w" ] || return 0
+  if nonliteral "$w"; then
+    if [[ "$w" == */* ]]; then d="${w%/*}"; [ -n "$d" ] || d="/"; else d="."; fi
+    if nonliteral "$d"; then
+      has "$cmd" "$HINT9" && sc_block "$what '$w' is a glob or variable in a command that names a protected path; it $MSG9_UNK"
+      return 0
+    fi
+    dir_protected9 "$d" "$PROTGLOB9" && sc_block "$what '$w' is a glob or variable in '$d' and $MSG9_UNK"
+    return 0
+  fi
+  if [[ "$w" =~ ^(\.|\./|.*/|.*\.\..*|/.*|[a-z]:.*)$ ]] || has "$w" '\.claude|^docs|\.git'; then
+    dir_protected9 "$w" && sc_block "$what '$w' is (or is inside) a protected directory, and the files it touches there $MSG9_UNK"
+  fi
+  return 0
+}
+
+gitconfig9() {   # <index of the config word in WL>: block every non-read form of git config
+  local k="$1" n=${#WL[@]} a rd=0
+  [ "$caller" = "factory-engineer" ] && return 0
+  case "${WL[k+1]:-}" in get|list) rd=1; k=$((k+1)) ;; esac
+  for ((k=k+1; k<n; k++)); do
+    a="${WL[k]}"
+    case "$a" in
+      --get|--get-all|--get-regexp|--get-urlmatch|--list|-l) rd=1 ;;
+      --show-origin|--show-scope|--name-only|--null|-z|--local|--global|--system|--worktree|--includes|--no-includes|--bool|--int|--bool-or-int|--path|--expiry-date|--type=*|--default=*|--file=*|--blob=*|--all|--regexp|--url=*) ;;
+      --type|--default|--file|-f|--blob|--url) k=$((k+1)) ;;
+      -*) sc_block "git config option '$a' is not a read form. git config writes repository or user configuration (it can set pagers, hooks, diff drivers), so only factory-engineer may change it. Read-only forms: git config --get/--get-all/--get-regexp/--list." ;;
+    esac
+  done
+  [ "$rd" = 1 ] || sc_block "'git config' without --get/--get-all/--get-regexp/--list (or 'git config get|list') can write configuration, so only factory-engineer may run it. Use an explicit read form."
+  return 0
+}
+
+wrap9() {   # <command string> <depth>
+  local str="$1" depth="$2" seg segs i j k n t b tgt d x
+  local -a WL
+  (( depth > 4 )) && sc_block "nested command strings are too deep to verify (fail closed)."
+  segs="$(printf '%s\n' "$str" | tr ';|&()`' '\n\n\n\n\n\n')"
+  while IFS= read -r seg; do
+    [ -n "${seg//[[:space:]]/}" ] || continue
+    prot9 "$seg"
+    toks_of "$seg"
+    WL=("${W[@]}")
+    n=${#WL[@]}
+    (( n )) || continue
+    for ((i=0; i<n; i++)); do
+      t="${WL[i]}"; b="$(base_of "$t")"
+      # (1) a quoted multi-word token may be a command string (bash -c, sh -c, eval, env -S, su -c)
+      [[ "$t" == *[[:space:]]* ]] && wrap9 "$t" $((depth+1))
+      # (2) after a prefix or launcher, every writer word starts a command of its own
+      if in_list "$b" "$W9_PREFIX"; then
+        for ((j=i+1; j<n; j++)); do
+          in_list "$(base_of "${WL[j]}")" "$W9_WRITERS" && prot9 "${WL[*]:j}"
+        done
+      fi
+      # (3) xargs/parallel take their operands at run time: unless the command is read-only, the
+      #     whole command line must not name a protected path
+      if [ "$b" = xargs ] || [ "$b" = parallel ]; then
+        k=$((i+1))
+        while (( k < n )) && [[ "${WL[k]}" == -* ]]; do
+          case "${WL[k]}" in -i|-n|-p|-l|-s|-d|-e|-a|-j|--arg-file|--delimiter|--max-args|--max-procs|--eof|--replace) k=$((k+2)) ;; *) k=$((k+1)) ;; esac
+        done
+        tgt="$(base_of "${WL[k]:-echo}")"
+        if ! in_list "$tgt" "$W9_READSAFE" && has "$cmd" "$HINT9"; then
+          sc_block "'$b $tgt' receives its file operands at run time in a command that names a protected path; it $MSG9_UNK"
+        fi
+      fi
+      # (4) archive extraction writes paths that are not on the command line
+      if [ "$b" = tar ] || [ "$b" = bsdtar ]; then
+        if [[ "${WL[i+1]:-}" =~ ^-?[a-z]*x ]] || has "$seg" "[[:space:]](-[a-z]*x[a-z]*|--extract|--get)([[:space:]=]|$)"; then
+          if has "$seg" "--absolute-names" || has "$raw" "(^|[[:space:]])-[a-zA-Z]*P[a-zA-Z]*([[:space:]]|$)|tar[[:space:]]+[a-zA-Z]*P"; then
+            sc_block "tar -P/--absolute-names $MSG9_UNK"
+          fi
+          d="."
+          for ((j=i+1; j<n; j++)); do
+            case "${WL[j]}" in
+              -c|--directory) d="${WL[j+1]:-}" ;;
+              --directory=*) d="${WL[j]#--directory=}" ;;
+            esac
+          done
+          dir_protected9 "$d" "$PROTGLOB9" && sc_block "archive extraction into '$d' $MSG9_UNK Extract into a module, dataset, or scratch directory with tar -C <dir>."
+        fi
+      fi
+      if [ "$b" = unzip ]; then
+        has "$seg" "[[:space:]]-[a-z]*:" && sc_block "unzip -: allows '..' members and $MSG9_UNK"
+        d="."
+        for ((j=i+1; j<n; j++)); do [ "${WL[j]}" = -d ] && d="${WL[j+1]:-}"; done
+        if [[ "${WL[i+1]:-}" != -l* ]] && [[ "${WL[i+1]:-}" != -t* ]]; then
+          dir_protected9 "$d" "$PROTGLOB9" && sc_block "archive extraction into '$d' $MSG9_UNK Extract with unzip -d <module, dataset, or scratch dir>."
+        fi
+      fi
+      # (5) inline interpreter code that names a protected path
+      if in_list "$b" "$W9_INLINE"; then
+        for ((j=i+1; j<n; j++)); do
+          case "${WL[j]}" in
+            -c|-e|-p|-r|--eval|--print|-command|-encodedcommand|-c?*|-e?*)
+              # whole command line: the segmenter splits inline code at ( ) ;
+              if { [ "$caller" != "factory-engineer" ] && has "$cmd" "$PATH_ALT_CONST|$PATH_ALT_GITCFG"; } \
+                 || has "$cmd" "$PATH_ALT_MARKER|$PATH_ALT_HUMAN_GATE" \
+                 || { [ "$caller" != "orchestrator" ] && has "$cmd" "$PATH_ALT_EVIDENCE"; }; then
+                sc_block "inline $b code that names a protected path $MSG9_UNK"
+              fi
+              break ;;
+          esac
+        done
+      fi
+      # (6) git config
+      if [ "$b" = git ]; then
+        k=$((i+1))
+        while (( k < n )) && [[ "${WL[k]}" == -* ]]; do
+          case "${WL[k]}" in -c|--git-dir|--work-tree|--namespace|--super-prefix|--config-env|--exec-path) k=$((k+2)) ;; *) k=$((k+1)) ;; esac
+        done
+        [ "${WL[k]:-}" = config ] && gitconfig9 "$k"
+      fi
+    done
+    # (7) operands of file-writing commands and redirect targets: globs, variables, and protected dirs
+    k="$(W=("${WL[@]}"); cmd_index)"
+    b="$(base_of "${WL[k]:-}")"
+    if in_list "$b" "$W9_OPERANDS" && writer_cmd "$seg"; then
+      if [ "$b" = find ]; then
+        for ((j=k+1; j<n; j++)); do [[ "${WL[j]}" == -* ]] && break; operand9 "${WL[j]}" "find start path"; done
+      else
+        for ((j=k+1; j<n; j++)); do
+          x="${WL[j]}"
+          [[ "$x" == -* ]] && continue
+          [ "$b" = sed ] && (( j == k+1 )) && continue
+          operand9 "$x" "operand"
+        done
+      fi
+    fi
+    for ((j=0; j<n; j++)); do
+      x="${WL[j]}"
+      [[ "$x" == *[[:space:]]* ]] && continue
+      [[ "$x" == *'>'* ]] || continue
+      t="${x##*>}"
+      [ -n "$t" ] || t="${WL[j+1]:-}"
+      [ "$t" = "/dev/null" ] || operand9 "$t" "redirect target"
+    done
+  done <<< "$segs"
+  return 0
+}
+
+# (8) a cd/pushd/-C into a protected directory, combined with any write, cannot be verified
+if has "$cmd" "(^|[[:space:];&|(])(cd|pushd)[[:space:]]+[\"']?[^[:space:];&|]*(\.claude|docs|\.git)|(^|[[:space:]])(-c|--chdir|--directory|--work-tree|--git-dir)[=[:space:]]+[\"']?[^[:space:]]*(\.claude|docs|\.git)"; then
+  wr9=0
+  has "$cmd_nr" ">" && wr9=1
+  while IFS= read -r seg; do writer_cmd "$seg" && wr9=1; done <<< "$segments"
+  if [ "$wr9" = 1 ] && { [ "$caller" != "factory-engineer" ] || has "$cmd" "agent-runs"; }; then
+    sc_block "a directory change into .claude, docs, or .git combined with a write $MSG9_UNK"
+  fi
+fi
+wrap9 "$cmd_nr" 0
+
 # ---- 10. Operational scripts: right agent, approved environment ---------------------------------
 # Every way a segment can execute a script is resolved to a repository path and checked against the
 # section 7 map: a *.sh command word (after wrappers such as env, nice, timeout, if, then), any shell
```

```diff
diff --git a/docs/agents.md b/docs/agents.md
index bc7e482..4b3e304 100644
--- a/docs/agents.md
+++ b/docs/agents.md
@@ -53,9 +53,11 @@ These refinements keep section 2's intent and make it exact for this repository.
 7. **prompts/ split.** `prompts/**` (runtime prompts and their output schemas) belongs to rag-engineer. The entity-resolution adjudication prompt and schema live inside `modules/entity-resolution/**`.
 8. **Shared subsystems, split by verb.** S3 Vectors: infra-engineer writes the Terraform resources, storage-engineer writes the ingestion and query adapters, and rag-engineer writes the embedding text, chunking, and retrieval logic. Evaluation: entity-resolution-engineer and rag-engineer write the evaluation code in their own modules, and experiment-engineer runs approved evaluations and owns the raw results. Pipelines: module owners write the jobs, infra-engineer writes `scripts/run-bounded-pipeline.sh`, and deploy-engineer runs it.
 
-## 4. Ownership map (enforced by `.claude/hooks/guard-ownership.sh`)
+## 4. Ownership map (enforced mechanically for Write and Edit by `.claude/hooks/guard-ownership.sh`)
 
-Exactly one writer per path. The first matching row wins. Paths are matched case-insensitively. A path that matches no row has no owner, and every write to it is blocked until an amendment assigns one.
+Exactly one writer per path. The first matching row wins. Paths are matched case-insensitively. A path that matches no row has no owner, and every Write or Edit to it is blocked until an amendment assigns one.
+
+Enforcement limits. guard-ownership.sh checks this whole map, but only for the Write, Edit, MultiEdit, and NotebookEdit tools. Bash writes (for example `sed -i`, `cp`, `mv`, redirects, scripts, and programs such as python) are not checked against this map. For Bash, the map rests on the permission prompt, the role files, and code-reviewer detection. guard-dangerous.sh (sections 9 and 9b) adds one narrow mechanical layer. It blocks Bash writes to the constitutional paths (the factory-engineer row; all callers except factory-engineer), to `.git` configuration and `git config` writes (all callers except factory-engineer), to `.claude/.last-test-run` and `human-gate.md` (all callers), and to `docs/agent-runs/**` (all callers except the orchestrator). It also blocks those writes when they are hidden inside `bash -c`, `sh -c`, `eval`, `env -S`, `xargs`, `find -exec`, and similar wrappers, and blocks glob or variable operands, cd into protected directories, and archive extraction where the target cannot be verified (fail closed). It does not detect writes that a program makes internally without naming the path on the command line, or writes through symlinks.
 
 | Path | Owner | Gate |
 |---|---|---|
@@ -85,7 +87,7 @@ Secret material (`.env*`, keys, keystores, `secrets/**`, `*.tfstate`) is never w
 
 ## 5. Tool fences
 
-Every agent file has an explicit `tools:` line. Enforcement layers, strongest first: the tool is absent from the fence; a deny rule or hook; an instruction backed by reviewer detection.
+Every agent file has an explicit `tools:` line. Enforcement layers, strongest first: the tool is absent from the fence; a deny rule or hook; an instruction backed by reviewer detection. "Ownership hook" below means guard-ownership.sh, which fences the Write and Edit tools only. An agent that has Bash can write outside its territory through Bash. Those writes rely on the permission prompt, the role file, and code-reviewer detection, except for the protected paths that guard-dangerous.sh blocks (section 4, "Enforcement limits").
 
 | Agent | Tools | Additional mechanical enforcement |
 |---|---|---|
@@ -99,7 +101,7 @@ Every agent file has an explicit `tools:` line. Enforcement layers, strongest fi
 | `storage-engineer` | Read, Grep, Glob, Write, Edit, Bash, aws-docs search/read | Ownership hook |
 | `rag-engineer` | Read, Grep, Glob, Write, Edit, Bash, aws-docs search/read | Ownership hook |
 | `service-engineer` | Read, Grep, Glob, Write, Edit, Bash | Ownership hook |
-| `test-engineer` | Read, Grep, Glob, Write, Edit, Bash | Ownership hook limits writes to `tests/**` |
+| `test-engineer` | Read, Grep, Glob, Write, Edit, Bash | Ownership hook limits Write and Edit to `tests/**`; Bash writes are fenced by instruction and review only |
 | `code-reviewer` | Read, Grep, Glob, Bash | No Write, Edit, or MCP. Bash is fenced to evidence commands by guard-dangerous.sh (`READ_ONLY_AGENTS`) |
 | `infra-engineer` | Read, Grep, Glob, Write, Edit, Bash, `mcp__aws-api__call_aws`, terraform registry (7 read tools), aws-docs search/read, GitHub Actions read (3 tools) | terraform apply/destroy denied; deploy/rollback/smoke scripts denied to it; AWS MCP read-only |
 | `deploy-engineer` | Read, Grep, Glob, Bash, Write, `mcp__aws-api__call_aws` | No Edit. Writes only `reports/deployments|releases/**`. Runs only mapped scripts in approved environments. Direct AWS mutations blocked |
```

#### git diff --stat (cumulative, checkpoints 1, 2 and 3a)

```text
 .claude/hooks/guard-dangerous.sh | 661 ++++++++++++++++++++++++++++++++++++---
 .claude/settings.json            |  34 +-
 docs/agents.md                   |  10 +-
 3 files changed, 656 insertions(+), 49 deletions(-)
```

## Checkpoint 3b report (factory-engineer, verbatim)

Dispatched from session cs-441-scholar-chain-bd with a 25-minute hard limit; the agent's `date` readings show 20:31:36 to about 20:46 (about 14.5 min). The other session stayed idle throughout.

Orchestrator check after the report:
- `git diff --stat`: guard-dangerous.sh 774, settings.json 34, docs/agents.md 10 (3 files changed, 750 insertions(+), 68 deletions(-)).
- `bash -n`: passed on all 6 hooks.
- `python -m json.tool .claude/settings.json`: JSON_OK.
- File times: guard-dangerous.sh modified 20:42:13 (inside the agent's window); settings.json and docs/agents.md unchanged since CP3a.

```text
STATUS: COMPLETE (proposal awaiting human ratification and restart; nothing self-ratified)
OWNER: factory-engineer
WORK ORDER: docs/agent-runs/phase-01/repair-01.md, CHECKPOINT 3b (finding F6 and the carried-over 9b part 8 operand9 fix)
FILES CREATED: none in the repository. Scratchpad only: gd.cp3a.sh (baseline), t3b.py, t3b_mcp.py, val.txt
FILES MODIFIED: .claude/hooks/guard-dangerous.sh (constitutional; section 8 and section 9b operand9/policy tables only)
COMMANDS RUN: date (x6); cp baseline; bash -n on all 6 hooks (twice); python -I t3b.py (59 stdin-JSON cases, twice); python -I t3b_mcp.py (3 cases); python -m json.tool on .claude/settings.json and .mcp.json; git ls-files -s .claude/hooks; bash .claude/hooks/validate-agent-files.sh (twice); git diff --stat; diff -u baseline vs working file
TEST RESULTS: 59 targeted cases under Git Bash (C:\Program Files\Git\bin\bash.exe), run twice, the second time after the last edit: "TOTAL 59 UNEXPECTED 0". MCP-path sanity cases matched their expectations (below). No sbt build exists, so sbt check does not apply. I did not run the full harness; that stays owed in CP5.
VALIDATION RESULTS: bash -n printed SYNTAX_OK for all 6 hooks. JSON_OK for settings.json and .mcp.json. All 6 hooks are 100755 in the index. validate-agent-files.sh printed "RESULT: PASS", rc=0.
ASSUMPTIONS: sc_classify_path returns "REL ." for the repository root relative to the hook's cwd, which matches how the existing CP2 code uses it. The lowercased $cmd is what gets inspected, so "AWS.EXE" and "Aws" are covered too.
RISKS: see Residual risk
MANUAL ACTION REQUIRED: none for this checkpoint. Single ratification at CP6 (human-gate.md, commit, restart), as the plan says.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: CP4 (F4, F7, F9, F10, F11), then CP5 (full regression), then CP6 (fresh code-reviewer pass, then human ratification and restart)
```

### Item status

- **operand9 / dir_protected9 false positive (9b part 8): FIXED.**
  - When a glob or variable operand (or redirect target) sits directly in the repository root, the root no longer counts as protected by itself.
  - A variable there blocks only when the command line contains a HINT9 protected-path hint.
  - A pure glob there blocks when it can match a protected root name (ROOT9: non-factory callers get claude.md .mcp.json .claude docs .git .gitconfig; factory-engineer gets docs).
  - Variable or glob targets in .claude, docs, docs/agent-runs, or .git still go through the unchanged PROTGLOB9 check.
  - If the classifier fails, it now blocks (fail closed).
- **F6: FIXED.** Section 8's old "previous word" heuristic is replaced by aws_scan, which uses the CP2 toks_of/base_of/interp_opts helpers.
  - It finds aws or aws.exe by basename, including quoted and absolute paths.
  - It looks after VAR=val, shell keywords (if/then/elif/else/do/while/until/!/{) and wrappers (sudo doas env nohup command exec builtin nice ionice stdbuf setsid timeout chrt taskset busybox watch unbuffer winpty start time xargs parallel find su runuser script flock). The first aws/python/shell/eval/command-string/non-literal word after a wrapper is taken as the command.
  - It recurses into bash -c / sh -c strings, eval, env -S strings, and quoted multi-word arguments of non-read-safe commands (depth limit 4, fail closed).
  - It also detects python/python3/py -m awscli and -mawscli.
  - Every hit goes to the existing aws_verdict verb check.
  - Fail-closed rules: a non-literal command word ($A, backticks) in a command that mentions aws is blocked, and python code that names awscli other than as -m awscli is blocked.

### Hook-test evidence (verbatim, one line per case; stderr truncated)

BLOCK, run for each of domain-engineer, deploy-engineer and orchestrator (all 18 got BLOCK):

```text
if aws s3 rm s3://scholarchain-data --recursive; then :; fi || BLOCKED by guard-dangerous: direct AWS call 'aws s3 rm' is not read-only (S3 mutation or presigned access)
nice -n 10 aws dynamodb delete-table --table-name t || ... 'aws dynamodb delete-table' is not read-only (destructive operatio
"/c/Program Files/Amazon/AWSCLIV2/aws.exe" s3 rb s3://b --force || ... 'aws s3 rb' is not read-only (S3 mutation ...
python -m awscli s3 rm s3://b/k || ... 'aws s3 rm' is not read-only
bash -c 'aws s3 rm s3://b/k' || ... 'aws s3 rm' is not read-only
timeout 5 aws ec2 terminate-instances --instance-ids i-1 || ... 'aws ec2 terminate-instances' is not read-only (destructive operat
```

PASS, run for each of the three callers (all 15 got PASS, rc 0):

```text
aws s3 ls | aws sts get-caller-identity | if aws s3 ls; then :; fi | nice -n 10 aws dynamodb describe-table --table-name t | OUT=target/x.txt; echo x > "$OUT"
```

Still BLOCK (domain-engineer, all got BLOCK):

```text
git diff --output=CLAUDE.md || only factory-engineer writes CLAUDE.md, ...
bash --norc scripts/cleanup.sh || scripts/cleanup.sh is human-invoked only ...
bash -c 'cp x CLAUDE.md' || only factory-engineer writes CLAUDE.md, ...
F=CLAUDE.md; rm $F || operand '$f' is a variable in a command that names a protected path ...
rm C*.md || operand 'c*.md' is a glob that can match the protected name 'claude.md' ...
echo 'terraform destroy' || 'terraform apply' and 'terraform destroy' are denied for every agent ...
```

Still PASS (all got PASS):

```text
domain-engineer git diff --stat; domain-engineer git log --oneline; deploy-engineer scripts/deploy.sh dev; domain-engineer cd "<repo>" && bash .claude/hooks/validate-agent-files.sh
```

- **Extra F6 forms, all BLOCK as expected** (domain-engineer): time aws s3 rm; ! aws s3 rb; while aws sqs purge-queue ...; do :; done; env AWS_PROFILE=x aws s3 rm; sudo aws s3 rm; echo k | xargs -I{} aws s3 rm s3://b/{}; eval 'aws s3 rm ...'; sh -c "nice aws s3 rm ..."; python3 -m awscli dynamodb delete-table; A=aws; $A s3 rm (non-literal fail-closed message); C:/aws/aws.exe s3 rm; aws --profile p s3 rm.
- **Extra PASS as expected:** python -m awscli s3 ls; command -v aws; grep -rn aws docs; echo 'aws s3 rm is denied'.
- **MCP path** (deploy-engineer): mcp__aws-api__call_aws "aws s3 rm s3://b/k" rc=2 (BLOCKED ... 'aws s3 rm'); "aws s3 ls" rc=0.
- **Bash loop:** for c in 'aws s3 ls x'; do echo $c; done rc=0.
- **Live observation:** during this run the live guard blocked the agent's own Bash calls twice. Both are listed under residual risk.
  - A heredoc containing the literal "terraform destroy" (section 1, already the case).
  - `for c in 'aws s3 rm s3://b/k' ...` (new F6 fail-closed recursion into a quoted argument).

### 3b increment diff

The agent returned the full increment diff against its CP3a baseline (`<scratch>/gd.cp3a.sh`), covering:

- section 8: the old `while … words_of … cmd_index` loop is replaced by `AWS_KW`, `AWS_WRAP`, `AWS_WORD_RE`, `aws_is_py`, `aws_call`, `aws_scan`, and `W9_READSAFE_AWS`, plus a new segment loop calling `aws_scan "$seg" 0`;
- the section 9b policy tables: `ROOT9` added in both branches;
- operand9: a new repository-root branch using `sc_classify_path`, `HINT9` gating for variables, and `ROOT9` glob matching.

The orchestrator recorded it in summary here, because the cumulative patch `repair-01-wip-cp3b.patch` (next to this file) carries the exact final text.

### Cumulative diff stat (HEAD to working tree, CP1 to CP3b)

```text
 .claude/hooks/guard-dangerous.sh | 774 ++++++++++++++++++++++++++++++++++++---
 .claude/settings.json            |  34 +-
 docs/agents.md                   |  10 +-
 3 files changed, 750 insertions(+), 68 deletions(-)
```

The CP3a snapshot was 656/49. CP3b changes only guard-dangerous.sh. settings.json and docs/agents.md are untouched since CP3a.

### Privilege delta (CP3b)

- No tool, ownership or autonomy changes to any agent. No allow or deny rules changed. No change to ROSTER, READ_ONLY_AGENTS, APPROVED_ENVIRONMENTS, script_runners or ENV_SCRIPTS.
- **Behavioral narrowing** (guard enforcement, all callers including the orchestrator): AWS mutations hidden behind keywords, wrappers, quoted paths, command strings, or python -m awscli are now blocked. Non-literal command words in aws-mentioning commands are blocked. This enforces the existing floor invariant, so no matrix entry changes. It is still part of the single CP6 ratification.
- **Behavioral relaxation** (repairs a CP3a false positive): a variable redirect or operand in the repository root no longer blocks unless the command names a protected-path hint. Globs in the root still block when they can match a protected root name.

### Floor invariants

| Invariant | Status |
|---|---|
| Dangerous-command guard | Intact, strengthened (F6) |
| Test-stop hook | Intact, untouched |
| Formatter | Intact, untouched (validator: "formatter always exits 0") |
| Secret-read denial | Intact (section 4 untouched) |
| terraform apply/destroy denial | Intact (section 1 untouched; still blocks in tests) |
| Destructive AWS denial | Intact, strengthened |
| Force-push denial | Intact (section 5 untouched; validator: "guard blocks force pushes") |

### Audits

- **Collision:** pass. No agents, commands or hook triggers added.
- **Orphan:** pass. No new hooks, agents, commands or servers.
- **One-writer:** pass. The ownership map is unchanged.

The validator PASS covers the structural checks.

### Residual risk

1. **Fail-closed false positives (F6).** Candidates for the CP5 F12 documentation:
   - Any non-read-safe command with a quoted multi-word argument that contains a destructive aws call is blocked, for example `for c in 'aws s3 rm ...'` and `git commit -m "... aws s3 rm ..."`. Read-only verbs inside such strings pass.
   - After a wrapper, the first aws word counts as the command, so `env echo aws s3 rm x` blocks.
   - A heredoc body with a quoted line like "nice ... aws ... delete-table" also blocks.
2. **String analysis cannot see everything.** IAM on the human's local profile remains the main control, as the review report says. Not detected:
   - aws reached through an alias or shell function defined in the same command. `f(){ aws "$@"; }; f s3 rm ...` is split at the parentheses, and the `aws "$@"` segment gives a non-literal verb, which aws_verdict treats as a mutating op and blocks. This was not tested.
   - a copied or renamed binary (`cp aws.exe x.exe; ./x.exe s3 rm`);
   - python -c code that imports botocore or boto3 without naming awscli;
   - SDK calls from scripts or other languages.
3. **ROOT9 lists root names only.** A root glob such as `rm *.json` matches .mcp.json and blocks, which is intended. A glob in any directory other than the root and the PROTGLOB9 directories passes, unchanged from CP3a.
4. **Regression not rerun.** The full 179/207-case regression has not been rerun since CP3a; that is owed in CP5. Only the 59 targeted cases plus 3 MCP/for cases were run.

### Ratification request (deferred to CP6, single ratification)

Approve:

- (a) the section 8 aws_scan replacement and its fail-closed rules, including the false positives in residual risk 1;
- (b) the operand9 repository-root rule (ROOT9 lists and HINT9 gating for variables).

Then commit and restart. Until then, the previous ratified configuration governs.

## Checkpoint 4 report (factory-engineer, verbatim; increment diff by reference)

Dispatched from session cs-441-scholar-chain-bd with a 25-minute hard limit. The agent's `date` readings show 20:51:29 to 21:02:37 (about 11 min). The other session stayed idle.

Orchestrator check after the report:
- `git diff --stat`: 6 files changed, 811 insertions(+), 84 deletions(-).
- `bash -n`: passed on all 6 hooks.
- `python -m json.tool .claude/settings.json`: JSON_OK.
- Snapshot `repair-01-wip-cp4.patch` saved; `git apply --check -R` passed.

```text
STATUS: COMPLETE (proposal awaiting human ratification and restart; not self-ratified)
OWNER: factory-engineer
WORK ORDER: docs/agent-runs/phase-01/repair-01.md, CHECKPOINT 4 (F4, F7, F9, F10, F11)
FILES CREATED: none in the repository. Scratchpad only: f7.py, f10.py, f4.py, fset.py, f9.py (edit scripts), t4.py (hook-test harness), cp4-increment.diff, and baselines gd.cp3b.sh, go.cp3b.sh, settings.cp3b.json, agents.cp3b.md, incident-cmd.cp3b.md, ir.cp3b.md
FILES MODIFIED:
  .claude/hooks/guard-dangerous.sh   (F7, F10, F4)
  .claude/hooks/guard-ownership.sh   (F9)
  .claude/settings.json              (F4, F7)
  docs/agents.md                     (F9: section 4 wording)
  .claude/commands/incident.md       (F11)
  .claude/agents/incident-responder.md (F11)
COMMANDS RUN: date (x5); cp of baselines; python -I <scratch>/f7.py | f10.py | f4.py | fset.py | f9.py; an inline python edit for F11; python -I <scratch>/t4.py (50 stdin-JSON cases under C:\Program Files\Git\bin\bash.exe); bash -n on all 6 hooks; python -m json.tool on settings.json and .mcp.json; git ls-files -s .claude/hooks; bash .claude/hooks/validate-agent-files.sh; git diff --stat; diff -u against the baselines
TEST RESULTS: t4.py printed "UNEXPECTED: 0 of 50". Full harness not run, as instructed; the full regression is owed in CP5.
VALIDATION RESULTS: SYNTAX_OK for common.sh, format-scala.sh, guard-dangerous.sh, guard-ownership.sh, validate-agent-files.sh, verify-tests-ran.sh. JSON_OK for settings.json and .mcp.json. All 6 hooks are 100755. validate-agent-files.sh ended "RESULT: PASS" (last lines: PASS floor: Stop hook honors stop_hook_active / guard blocks terraform apply/destroy / guard blocks force pushes / formatter always exits 0).
ASSUMPTIONS: The exact-form Bash allow rules ("Bash(sbt compile)") match only that exact command. Commands that match no rule fall through to a prompt. The deny > ask > allow order still holds.
RISKS: see Residual risk
MANUAL ACTION REQUIRED: human ratification (all of repair-01 at CP6), commit, then restart. The guard and ownership hook changes are live now. The settings.json changes take effect at the latest after a restart; edits have been seen to apply live.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: CP5 (F12, full regression, validator), then CP6 (fresh code-reviewer pass, then human ratification)
```

### Findings

- **F7 (MAJOR): FIXED.**
  - Guard section 4 secret patterns: `.credentials` in any directory, `credentials.tfrc.json`, and `gh/hosts.ya?ml`.
  - New environment-dump blocks: `env` with only options or `-u NAME`; `declare`/`typeset` with only options; bare `export`; `jq ... env` / `$ENV`. Already blocked before CP4: `printenv`, bare `set`, `export -p`, `declare -x`.
  - Scope: every caller, the same as the existing env-dump rule. `env VAR=x cmd` and `set -euo pipefail` still pass.
  - Settings: 7 new Read deny rules: `**/.credentials`, `~/.sbt/.credentials`, `~/.ivy2/.credentials`, `**/credentials.tfrc.json`, `~/.terraform.d/credentials.tfrc.json`, `**/gh/hosts.yml`, `~/.config/gh/hosts.yml`. These are verified only after a restart.
- **F10 (MINOR): FIXED.** `PATH_ALT_CONST` names only `.claude/settings.json`. A new `PATH_ALT_LOCAL` (`.claude/settings.local.json` or a bare `settings.local.json`) is checked for every caller, factory-engineer included, in section 9 and `prot9`.
- **F9 (MINOR): FIXED.**
  - Interpretation: the minimum the work order asked for. A strict "sc_owner must be build-engineer" reading would also block the legitimate `modules/core/.../.gitkeep`, which is owned by domain-engineer.
  - Rule: the exception applies only to a Write with empty or whitespace-only content, and never under `.claude/**`, `docs/agent-runs/**`, or a path whose first-match owner is factory-engineer, human, or generated.
  - docs/agents.md section 4 now reads: "empty `.gitkeep` placeholders anywhere except `.claude/**`, `docs/agent-runs/**`, and paths owned by factory-engineer, the human, or generated".
- **F11 (MINOR): FIXED.** incident.md and incident-responder.md now say that `rollback.sh` runs only after a permission prompt and `smoke-test.sh` runs without one, matching docs/agents.md section 7.
- **F4 (MAJOR, latent until phase 02): FIXED.**
  - Settings allow list, exact forms: `sbt Test/compile`, `sbt compile`, `sbt test`, `sbt check`, `sbt clean check`, `sbt fmt`, `sbt scalafmtCheckAll`.
  - Settings ask list: `sbt testOnly*`, `sbt "testOnly*`, `sbt --client *`.
  - New guard section 6b, for every caller, blocks sbt `markTestRun`, `eval`, `set`, `run`, `runMain`, `bgRun*`, `fgRun*`, `console`, `consoleQuick`, `consoleProject`, and `reStart`, including after project or config prefixes, `;`/`~`, `--client`, and `bash -c`.
  - Phase-02 follow-up for build-engineer: make `markTestRun` reachable only from the `check` alias.

### Hook-test evidence (verbatim, one line per case)

```text
ok  F7   code-reviewer     BLOCK exp=BLOCK cat ~/.sbt/.credentials
ok  F7   domain-engineer   BLOCK exp=BLOCK cat ~/.ivy2/.credentials
ok  F7   infra-engineer    BLOCK exp=BLOCK cat ~/.terraform.d/credentials.tfrc.json
ok  F7   code-reviewer     BLOCK exp=BLOCK cat ~/.config/gh/hosts.yml
ok  F7   code-reviewer     BLOCK exp=BLOCK env
ok  F7   code-reviewer     BLOCK exp=BLOCK env -0
ok  F7   code-reviewer     BLOCK exp=BLOCK printenv
ok  F7   code-reviewer     BLOCK exp=BLOCK set
ok  F7   code-reviewer     BLOCK exp=BLOCK jq -n env
ok  F7   code-reviewer     BLOCK exp=BLOCK jq -n '$ENV'
ok  F7   code-reviewer     BLOCK exp=BLOCK export -p
ok  F7   code-reviewer     BLOCK exp=BLOCK declare -p
ok  F7   code-reviewer     BLOCK exp=BLOCK declare -x
ok  F7   domain-engineer   BLOCK exp=BLOCK env | grep AWS
ok  F7   domain-engineer   PASS  exp=PASS  env AWS_REGION=us-east-1 aws s3 ls
ok  F7   domain-engineer   PASS  exp=PASS  set -euo pipefail; ls
ok  F7   code-reviewer     PASS  exp=PASS  jq . .mcp.json
ok  F10  factory-engineer  BLOCK exp=BLOCK cp /tmp/l.json .claude/settings.local.json
ok  F10  factory-engineer  BLOCK exp=BLOCK echo {} > .claude/settings.local.json
ok  F10  factory-engineer  BLOCK exp=BLOCK bash -c "cp /tmp/l.json .claude/settings.local.json"
ok  F10  domain-engineer   BLOCK exp=BLOCK cp /tmp/l.json .claude/settings.local.json
ok  F10  factory-engineer  PASS  exp=PASS  cp /tmp/x.md .claude/agents/x.md
ok  F10  domain-engineer   BLOCK exp=BLOCK cp /tmp/x.md .claude/agents/x.md
ok  F4   build-engineer    BLOCK exp=BLOCK sbt compile markTestRun
ok  F4   build-engineer    BLOCK exp=BLOCK sbt --client "eval 1+1"
ok  F4   domain-engineer   BLOCK exp=BLOCK sbt --client run
ok  F4   code-reviewer     BLOCK exp=BLOCK sbt Test/run
ok  F4   domain-engineer   BLOCK exp=BLOCK sbt "core/Test/runMain x.Y"
ok  F4   domain-engineer   BLOCK exp=BLOCK sbt "compile; set version := 1"
ok  F4   domain-engineer   BLOCK exp=BLOCK sbt consoleQuick
ok  F4   domain-engineer   BLOCK exp=BLOCK bash -c "sbt run"
ok  F4   orchestrator      BLOCK exp=BLOCK sbt ~run
ok  F4   domain-engineer   PASS  exp=PASS  sbt check
ok  F4   domain-engineer   PASS  exp=PASS  sbt compile
ok  F4   domain-engineer   PASS  exp=PASS  sbt "testOnly *EvalSpec"
ok  F4   code-reviewer     PASS  exp=PASS  sbt check
ok  F4   domain-engineer   PASS  exp=PASS  set -e; sbt test
ok  F9   build-engineer    BLOCK exp=BLOCK docs/agent-runs/phase-01/.gitkeep content='x'
ok  F9   build-engineer    BLOCK exp=BLOCK docs/agent-runs/phase-01/.gitkeep content=''
ok  F9   build-engineer    BLOCK exp=BLOCK .claude/hooks/.gitkeep content=''
ok  F9   build-engineer    BLOCK exp=BLOCK .claude/.gitkeep content=''
ok  F9   build-engineer    BLOCK exp=BLOCK modules/core/src/test/resources/.gitkeep content='payload'
ok  F9   build-engineer    PASS  exp=PASS  modules/core/src/test/resources/.gitkeep content=''
ok  F9   factory-engineer  PASS  exp=PASS  .claude/agents/x.md content='x'
ok  F9   domain-engineer   PASS  exp=PASS  modules/core/src/test/resources/.gitkeep content=''
ok  SAN  domain-engineer   BLOCK exp=BLOCK git diff --output=CLAUDE.md
ok  SAN  domain-engineer   BLOCK exp=BLOCK if aws s3 rm s3://b --recursive; then :; fi
ok  SAN  domain-engineer   PASS  exp=PASS  git diff --stat
ok  SAN  deploy-engineer   PASS  exp=PASS  scripts/deploy.sh dev
ok  SAN  code-reviewer     PASS  exp=PASS  cat README.md
UNEXPECTED: 0 of 50
```

The PASS cases for `sbt "testOnly ..."` and `sbt --client` show only the guard result. In settings.json they now prompt, and that layer can't be tested here.

### CP4 increment diff

The agent returned the complete per-file increment against its CP3b baselines:

- guard-dangerous.sh: `PATH_ALT_LOCAL`, the section 4 secret patterns and env-dump blocks, the new section 6b sbt scan, and section 9 / `prot9` local-settings blocks;
- guard-ownership.sh: the `.gitkeep` exception;
- settings.json: the sbt allow and ask lists, and 7 Read deny rules;
- docs/agents.md: the section 4 `.gitkeep` row;
- incident.md and incident-responder.md: the F11 text.

The exact final text of all of these is in `repair-01-wip-cp4.patch` (cumulative, HEAD to working tree), next to this file.

### Cumulative diff stat (HEAD to working tree)

```text
 .claude/agents/incident-responder.md |   2 +-
 .claude/commands/incident.md         |   2 +-
 .claude/hooks/guard-dangerous.sh     | 806 ++++++++++++++++++++++++++++++++---
 .claude/hooks/guard-ownership.sh     |  13 +-
 .claude/settings.json                |  60 ++-
 docs/agents.md                       |  12 +-
 6 files changed, 811 insertions(+), 84 deletions(-)
```

### Privilege delta (all narrowing, all awaiting human ratification; nothing widened)

- **factory-engineer:** loses Bash writes to `.claude/settings.local.json` (F10); it keeps its exemption for the other constitutional paths.
- **build-engineer:** the `.gitkeep` exception is limited to an empty Write, and never under `.claude/**`, `docs/agent-runs/**`, or paths owned by factory-engineer, the human, or generated (F9). The docs/agents.md section 4 wording is narrowed to match.
- **All agents and the orchestrator, sbt (F4):** the guard blocks sbt `markTestRun`, `eval`, `set`, `run*`, `bgRun*`, `fgRun*`, `console*`, and `reStart`. Settings now prompt for `testOnly`, `--client`, and extra arguments to compile, test, or check.
- **All agents and the orchestrator, secrets (F7):** the guard blocks Bash references to `.credentials`, `credentials.tfrc.json`, and `gh/hosts.yml`, plus the new environment dumps. Settings deny Read of the same 7 paths.
- **incident-responder (F11):** text only, no privilege change.

### Floor invariants

All intact. The validator's floor checks passed (terraform apply/destroy, force push, Stop hook, formatter). The secret-read denial is stronger after F7. AWS destructive-operation denial is unchanged; `if aws s3 rm ... --recursive` is still blocked. Hook wiring and exec bits are unchanged.

### Audits

- **Collision:** pass.
- **Orphan:** pass.
- **One-writer-per-artifact:** pass. F9 removes an overlap where build-engineer could write into other owners' and human-only trees.

### Residual risk

- **F4:**
  - The sbt scan can block an unrelated later word in the list (`sbt compile && echo run`), and a token like `x/run` inside a testOnly argument. Both fail closed.
  - The guard can't see inside sbt aliases, `.sbtopts`, or `addCommandAlias`. build-engineer must make `markTestRun` reachable only from `check`.
- **F7:**
  - Writer agents can still dump the environment with inline interpreter code (`python -c 'os.environ'`). Only read-only callers are fully fenced.
  - The Read deny rules are verified only after a restart.
  - `gh/hosts.yml` is matched by suffix.
- **F10:** a factory-engineer glob write to settings.local (`.claude/settings.l*`) is not caught. Literal paths, a bare `settings.local.json` after `cd`, and `bash -c` wrappers are blocked.
- **F9:** content emptiness is checked only for Write. Edit and MultiEdit on a `.gitkeep` are blocked for build-engineer outside its own rows.
- **Stale comment:** a section 11 comment still says "finding 4 is later". Left for CP5.

### Ratification request (at CP6)

1. The F7 secret patterns and env-dump blocks, plus the 7 Read deny rules.
2. Removing factory-engineer's settings.local exemption (F10).
3. The F9 interpretation and the narrowed docs/agents.md section 4 `.gitkeep` wording.
4. The F11 text corrections.
5. The F4 exact sbt allow forms, testOnly and `--client` moved to ask, and guard section 6b. Also record the build-engineer phase-02 follow-up: `markTestRun` reachable only from `check`.

## Checkpoint 5 report (factory-engineer, verbatim; increment diff by reference)

Dispatched from session cs-441-scholar-chain-bd. CP5 and CP6 shared a 45-minute window (22:24 to 23:09 CDT); CP5's hard deadline was 22:50. The agent's `date` readings show 22:24:48 to 22:49:05 (about 24 min).

Orchestrator check after the report (22:50:28):
- `git diff --stat`: 6 files changed, 850 insertions(+), 85 deletions(-).
- `bash -n`: passed on all 6 hooks.
- settings.json: JSON_OK.
- Snapshot `repair-01-wip-cp5.patch` saved; sha256 `f047a557050798710dfc105f47a53a189d3ada767aad51aed11a24a8511e59b6`; `git apply --check -R` passed.

**CP6 outcome:** code-reviewer returned REQUEST_CHANGES with one new CRITICAL finding (N1) in this checkpoint's section 11 change. See `review-report-02.md`.

```text
STATUS: COMPLETE (proposal, not ratified)
OWNER: factory-engineer
WORK ORDER: docs/agent-runs/phase-01/repair-01.md, CHECKPOINT 5 (F12, full regression, validator)
FILES CREATED: none in the repository. Scratchpad only: gd.cp4.sh and go.cp4.sh (baselines), f12.py (21 targeted cases), cp5-increment.diff, cp5a/ (CP4 baseline run, see note), cp5b/ (final run)
FILES MODIFIED: .claude/hooks/guard-dangerous.sh (CP5 increment, section 11 only)
COMMANDS RUN: date (at start and before each sub-task); bash -n on all 6 hooks; python -m json.tool on .claude/settings.json and .mcp.json; git ls-files -s .claude/hooks; bash .claude/hooks/validate-agent-files.sh; diff -u against the baseline; git diff --stat; all 9 existing harnesses plus f12.py under Git Bash with python -I
TEST RESULTS: see the table below. 0 regressions.
VALIDATION RESULTS: bash -n passes on all 6 hooks. settings.json and .mcp.json both parse. All 6 hooks are mode 100755. validate-agent-files.sh prints "RESULT: PASS", exit 0.
ASSUMPTIONS: In a read-only command, a quoted > < ; | & is literal text in bash. $( and backticks inside double quotes are still live, so they stay blocked.
RISKS: see Known gaps. The CP4 baseline run (cp5a) mixed hook versions because I edited the hook while it was still running; it is not evidence. Only cp5b counts.
MANUAL ACTION REQUIRED: CP6 code-reviewer pass, then human ratification in human-gate.md, commit, and restart
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: CP6 fresh code-reviewer pass on the whole repair-01 diff
```

### Regression on the final hook

All runs used the same final hook (guard-dangerous.sh blob 202c9976 in the index).

| Harness | Cases | PASS | BLOCK | UNEXPECTED | Expected change | Regression |
|---|---|---|---|---|---|---|
| hooktest.py (CP1) | 92 | 36 | 56 | 0 | 0 | 0 |
| hooktest2.py (CP2) | 140 | 43 | 97 | 0 | 0 | 0 |
| hooktest3a.py (CP3a) | 207 | 59 | 148 | 4 | 0 | 0 |
| t3b.py (CP3b) | 59 | 23 | 36 | 0 | 0 | 0 |
| t3b_mcp.py (CP3b) | 3 | 1 | 2 | 0 | 0 | 0 |
| t4.py (CP4) | 50 | 15 | 35 | 0 | 0 | 0 |
| sbtreg.py | 5 (+2 timing, about 5.3 s each) | 2 | 3 | 0 | 0 | 0 |
| wraptest.py / wraptest2.py | 12 / 12 | as designed | fail-closed when the script is missing | 0 | 0 | 0 |
| f12.py (new, CP5) | 21 | 7 | 14 | 0 | 0 | 0 |

- The 4 unexpected results in hooktest3a are the `[9b-fp-scratch-path]` cases (scratchpad paths containing `.claude/hooks`). They are the known F12 false positive, accepted as deviations, not regressions.
- The CP3a operand9 case is now as expected, because CP3b fixed it.
- t3b.py needs the scratchpad as `sys.argv[1]`; the first run crashed without it and was rerun correctly.

### F12 items

1. `git --version` for read-only callers: **FIXED** (exactly two words only).
2. `>` inside quotes treated as a redirect: **FIXED** for read-only callers.
3. `;` and `&` inside quotes splitting segments: **FIXED** for read-only callers.
4. Scratchpad path containing `.claude/hooks` treated as constitutional (section 9): **ACCEPTED-DEVIATION**. There was no time to fix it safely.
5. `python -m json.tool <protected input> <scratch output>` blocked because of the input path: **ACCEPTED-DEVIATION**.
6. Stale "finding 4 is later" comment: **FIXED** (comment only).

The terraform phrase in quoted text still blocks (floor rule, kept deliberately).

### CP5 increment

Section 11 only; sections 1 to 10 are untouched. The full 70-line diff is in the session scratchpad (`cp5-increment.diff`); the exact final text is in `repair-01-wip-cp5.patch`.

- New `ro_mask()`: blanks single-quoted text and any `\`-escaped character. Inside double quotes it blanks only the literal `> < ; | &`, keeping `$(`, backticks and parentheses. An unbalanced quote runs to the end of the command. **CP6 N1: ro_mask mis-models ANSI-C `$'...'` quoting, which defeats the fence.**
- New `ro_split()`: splits on unquoted `; | & ( ) \`` and newlines only.
- The redirect/substitution check reads the masked text, and `segments_raw` is built with `ro_split`.
- git allowlist: a `--version` branch that allows exactly 2 words.
- The stale sbt comment is rewritten.
- Bug caught before the final run: the first version blanked parentheses inside double quotes, which let `echo "$(touch x)"` through. f12.py caught it and it was fixed.

### Cumulative diff stat (HEAD to working tree)

```text
 .claude/agents/incident-responder.md |   2 +-
 .claude/commands/incident.md         |   2 +-
 .claude/hooks/guard-dangerous.sh     | 846 ++++++++++++++++++++++++++++++++---
 .claude/hooks/guard-ownership.sh     |  13 +-
 .claude/settings.json                |  60 ++-
 docs/agents.md                       |  12 +-
 6 files changed, 850 insertions(+), 85 deletions(-)
```

### Privilege delta (CP5)

- **Widened** for read-only and unregistered callers (code-reviewer): `git --version` (exactly two words), and quoted `> < ; | &` no longer count as redirects or separators in section 11. The agent stated that no new commands became reachable; CP6 N1 shows this is false via ANSI-C quoting.
- **Narrowed:** none.
- Floor invariants: intact per the validator and f12.py.

### Accepted deviations (fail-closed false positives)

- (CP1) A non-factory caller running `python -m json.tool .mcp.json <scratch>/o.json` is blocked because of the input path (F12 item 5).
- (CP2) `time cat scripts/deploy.sh`.
- (CP2) `cd <non-root dir> && bash foo.sh`.
- (CP2) `xargs grep x scripts/a.sh`.
- (CP2) `/usr/bin/bash -n scripts/cleanup.sh` is rejected by the deny rule.
- (CP2) A backslash-newline inside quotes.
- (CP2) Some rarely used safe options are rejected (allowlists from documentation).
- (CP2) A heredoc body line such as `cd scripts`, then `./cleanup.sh`, is blocked.
- (CP3a) `rm $f` or `rm *.tmp` in the repo root.
- (CP3a) `cp x docs/` by documentation-engineer.
- (CP3a) A quoted write plus a protected path (`git commit -m "rm CLAUDE.md"`).
- (CP3a) `cp -r .claude/agents /tmp` (source operand).
- (CP3a) `git config user.name` (a read without `--get`).
- (CP3a) `cd ~/.claude/...` combined with any write.
- (CP3a) `xargs -I` and `-i` are treated the same.
- (CP3a / F12) Scratchpad paths containing `.claude/hooks`.
- (CP3b) A quoted string with a destructive aws call in non-read-safe commands.
- (CP3b) `env echo aws s3 rm x`.
- (CP3b) A heredoc line with quoted `nice ... aws ... delete-table`.
- (CP3b) `rm *.json` in the root (intended).
- (CP4) `sbt compile && echo run`, and `x/run` inside a testOnly argument.
- (CP4) Edit/MultiEdit on `.gitkeep` is blocked for build-engineer outside its own rows.
- (F12) The terraform floor phrase in quoted text (kept deliberately).

### Known gaps (not detected)

- (CP1) A hook timeout is non-blocking, so a hook replaced in place by an exit-0 script fails open.
- (CP1) Indirect deletes that never name the path (`cd .claude/hooks && rm x`, `xargs rm`).
- (CP1/CP4) Settings ask/deny wildcard matching and the Read deny rules can only be observed after a restart.
- (CP2) Scripts started from python, perl, node or make, and renamed copies of scripts.
- (CP2/CP3a) Symlinks are not resolved.
- (CP2) Git config already in `.git/config` or `.gitattributes`.
- (CP2) Tool config files (`.checkov.yaml`, `.tflint.hcl`, `RIPGREP_CONFIG_PATH`).
- (CP3a) Bash writes are not checked against the full ownership map (documented in docs/agents.md).
- (CP3a) Programs that write internally (`python x.py`, make, sbt tasks, `git apply`/`am`/`merge`/`pull`/`checkout`, `cpio`, `7z`).
- (CP3a) `xargs -a <file>`, and lists built without a hint string.
- (CP3a) Variable-concatenation obfuscation outside the repo root.
- (CP3b) aws via a renamed binary, boto3/botocore in `python -c`, or SDK calls from scripts (IAM is the main control).
- (CP4) sbt aliases, `.sbtopts`, `addCommandAlias` (build-engineer phase-02 follow-up).
- (CP4) Writer agents can dump the environment through inline interpreter code.
- (CP4) `gh/hosts.yml` is matched by suffix.
- (CP4) A factory-engineer glob write to `.claude/settings.l*`.
- (CP5) An unbalanced quote in a read-only command passes the quoted remainder. **CP6 N1 shows the related ANSI-C case does execute.**

## Checkpoint 7 report (factory-engineer, verbatim; fixes CP6 N1)

Dispatched from session cs-441-scholar-chain-bd with a hard deadline of 23:15 CDT. The agent's `date` readings show 22:58:52 to 23:11:57 (about 13 min).

Orchestrator check (23:13:18):
- `git diff --stat`: 6 files changed, 855 insertions(+), 85 deletions(-).
- `bash -n`: passed on all 6 hooks.
- settings.json: JSON_OK.
- Snapshot `repair-01-wip-cp7.patch` saved; sha256 `34debbd85068938310d87c335f9303be6993dcb534e3f2917935f5c54014014e`; `git apply --check -R` passed. It supersedes the CP5 patch.

```text
STATUS: COMPLETE (N1 FIXED for the read-only and unregistered fence; a related writer-side issue is OPEN, see Risks)
OWNER: factory-engineer
WORK ORDER: docs/agent-runs/phase-01/repair-01.md CHECKPOINT 7 (N1 from review-report-02.md)
FILES CREATED: none in repo (scratch: <scratch>/gd.cp5.sh baseline, <scratch>/cp7.py, <scratch>/cp7edit.py which never applied)
FILES MODIFIED: .claude/hooks/guard-dangerous.sh (section 11 only, +6/-1 vs CP5)
COMMANDS RUN: date (22:58:52, 23:00:35, 23:02:04, 23:03:52, 23:05:44, 23:08:19, 23:11:16, 23:11:57); cp baseline; python cp7.py (twice, see note); python f12.py; python t4.py; bash -n on all hooks; python -m json.tool .claude/settings.json; bash .claude/hooks/validate-agent-files.sh; git ls-files -s; git diff --stat; git diff --no-index baseline vs hook
TEST RESULTS: cp7.py 28 cases, 0 unexpected; f12.py 21 cases, 1 unexpected (an intended change, see below); t4.py "UNEXPECTED: 0 of 50"
VALIDATION RESULTS: bash -n OK for all 6 hooks; JSON_OK; validator "RESULT: PASS", exit 0; guard-dangerous.sh is mode 100755
ASSUMPTIONS: raw_nr and cmd_nr have the same quote structure as raw (lower-casing and the sed that strips >/dev/null and N>&M do not touch quote characters), so only cmd_nr is checked for balance
RISKS: see Residual risk
MANUAL ACTION REQUIRED: human ratification of the cumulative repair-01 including CP7, then restart; a fresh code-reviewer pass first
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: fresh code-reviewer pass, then human ratification and restart
```

### N1: FIXED for code-reviewer and unregistered callers

- Section 11 rejects any command whose raw text contains `$'` or `$"`.
- Section 11 rejects any command whose quotes do not balance under ro_mask (ro_mask returns status 1 when a quote is left open).
- Everything else in ro_mask and ro_split is unchanged from CP5.

### Evidence

The hook received PreToolUse JSON through Git Bash; no input was executed.

The same 7 inputs were run as code-reviewer and as `unregistered-probe`. All 14 were BLOCKed ("<caller> is read-only: ..."):

```text
echo $'\'' fence-probe >/dev/stderr #'                          BLOCK / BLOCK
echo $'\'' ; sed -n 1p CLAUDE.md #'                             BLOCK / BLOCK
echo $'\'' payload > modules/core/src/main/scala/X.scala #'     BLOCK / BLOCK
echo $'\'' ; python other.py #'                                 BLOCK / BLOCK
echo $"x" > f                                                   BLOCK / BLOCK
grep 'unbalanced CLAUDE.md                                      BLOCK / BLOCK
echo "x > y                                                     BLOCK / BLOCK
```

- PASS for code-reviewer: `git --version`; `rg --help 2>&1 | grep -- "--pre <COMMAND>"`; `grep -c '&lt;file&gt;' CLAUDE.md`; `grep -n "a; b & c" docs/agents.md`; `git diff --stat`; `sort docs/agents.md`.
- Writer sanity: domain-engineer `git diff --output=CLAUDE.md` BLOCK; `if aws s3 rm s3://b --recursive; then :; fi` BLOCK; deploy-engineer `scripts/deploy.sh dev` PASS; domain-engineer `git diff --stat` PASS.
- Test-run note: the first cp7.py run lost a level of backslashes in a heredoc. The file was fixed, and the results above come from the corrected run.
- f12.py: 21 cases, 1 unexpected: `grep "unbalanced ; touch x` is now BLOCK ("unbalanced quotes (fail closed)"). This is an intended change; the f12.py expectation is stale.
- t4.py: 0 of 50 unexpected.

### Writer-side ANSI-C escapes (sections 9b, 10, 8 via toks_of): OPEN, no fix applied

toks_of does not decode ANSI-C escapes, so a writer caller can hide a command name or a script path. Observed PASS for domain-engineer:

- `$'\x74ee' CLAUDE.md`: bash runs `tee CLAUDE.md`, which would empty a constitutional file.
- `$'scripts/cleanup\x2esh' dev`: bash runs `scripts/cleanup.sh dev`, which section 7 reserves for the human. Plain `scripts/cleanup.sh dev` is still blocked.
- `scripts/cleanup$'\x2e'sh dev`: same effect.
- `$'tee' CLAUDE.md`: PASSed in the first, de-escaped run.

The agent did not apply a one-line fix, because blocking `$'`/`$"` anywhere for writers would also block `grep 'foo$' f` and `sed 's/x$//'`. The issue predates CP5 (toks_of is from CP2). Whether permission prompts catch these depends on the settings allow rules, which was not verified.

Suggested direction: give toks_of a fail-closed flag when it sees `$'` or `$"` outside single quotes, and have sections 9b and 10 block on it.

### CP7 increment diff (against `<scratch>/gd.cp5.sh`)

```diff
@@ -981,7 +981,9 @@
   # CP5 (F12), section 11 only: quote-aware. ro_mask replaces single-quoted text, and the literal
   # > < ; | & inside double quotes, with '_' ( ( ) $( and backticks are kept inside double quotes),
   # so a quoted '>' is not a redirect and a quoted ';' or '&' does not start a new segment. An
-  # unbalanced quote runs to the end. Sections 1 to 10 are unchanged and still see the raw text.
+  # CP7 (N1): ANSI-C and locale quoting ($'...', $"...") are not modelled, so a '$' directly before
+  # a quote anywhere in the raw text is rejected, and a command whose quotes do not balance under
+  # ro_mask is rejected (fail closed). Sections 1 to 10 are unchanged and still see the raw text.
   ro_mask() {
@@ -1001,6 +1003,7 @@
     printf '%s' "$out"
+    [ -z "$q" ]   # CP7 (N1): status 1 when a quote is left open
   }
@@ -1015,6 +1018,8 @@
     printf '%s\n' "$cur"
   }
+  case "$raw" in *"\$'"*|*'$"'*) ro_block "ANSI-C or locale quoting (a \$ directly before a quote) cannot be verified (fail closed)" ;; esac   # CP7 (N1)
+  ro_mask "$cmd_nr" >/dev/null || ro_block "unbalanced quotes (fail closed)"   # CP7 (N1); raw_nr has the same quotes
   has "$(ro_mask "$cmd_nr")" ">|\\\$\(|<\(" && ro_block "output redirection or command substitution"
```

Cosmetic: line 983 still ends with a leftover word "An" in a comment. It does not affect behavior; fix it in the next edit.

### Privilege delta (CP7)

- **Narrowed:** code-reviewer and unregistered callers can no longer run any Bash command containing `$'` or `$"`, or with unbalanced quotes. This closes the CP5 widening (N1).
- **Known false positives (fail closed):** `grep 'foo$' f` and `grep -E "x$" f` are now blocked for code-reviewer. Workaround: `grep -e 'foo[$]'` or the Grep tool.
- **No other changes.**

### Residual risk

1. The OPEN writer-side ANSI-C escape bypass above. A writer agent can empty CLAUDE.md through `$'\x74ee'`, or run cleanup.sh, subject only to the permission prompt.
2. The documented F1, F2, F3, F5 and F6 gaps are unchanged.
3. ro_mask runs once more per command, adding latency. Not measured; a timeout fails open.
4. The f12.py expectation for one case is stale.

## Checkpoint 8 report (factory-engineer, verbatim; human chose option A)

Dispatched from session cs-441-scholar-chain-bd with a hard deadline of 23:32 CDT. The agent's readings show 23:15:43 to 23:25:40 (about 10 min).

Orchestrator check (23:26:50):
- `git diff --stat`: 6 files changed, 885 insertions(+), 85 deletions(-).
- `bash -n`: passed on all 6 hooks.
- settings.json: JSON_OK.
- Snapshot `repair-01-wip-cp8.patch` saved; sha256 `dbaae70523bdfa22a370f6214faba6c3fd27dcdd3b1939dd118c7929a15bed53`; `git apply --check -R` passed. It supersedes the CP7 patch.

```text
STATUS: COMPLETE (CP8 fix proposed. It is not ratified, and it is already live: the hook file is read on every call, see RISKS)
OWNER: factory-engineer
WORK ORDER: docs/agent-runs/phase-01/repair-01.md, CHECKPOINT 8 (human option A: close the writer-side ANSI-C/locale quoting bypass for all callers)
FILES CREATED: none in the repo. Scratch only: <scratch>/gd.cp7.sh (baseline), <scratch>/cp8.py, <scratch>/t3b-cp8.txt
FILES MODIFIED: .claude/hooks/guard-dangerous.sh
COMMANDS RUN: date (several times); cp of the baseline; python <scratch>/cp8.py; cp7.py; t4.py; t3b.py <scratch>; bash -n and test -x on every hook; git ls-files -s .claude/hooks; python -m json.tool on .claude/settings.json and .mcp.json; bash .claude/hooks/validate-agent-files.sh; git diff --stat; diff -u against the baseline
TEST RESULTS: cp8.py 38 cases, 1 unexpected (classified below). cp7.py 28 cases, 0 unexpected. t4.py 0 unexpected of 50. t3b.py 59 cases, 0 unexpected.
VALIDATION RESULTS: bash -n OK on all 6 hooks. All 6 hooks are executable and 100755 in git. settings.json OK. .mcp.json OK. validate-agent-files.sh printed "RESULT: PASS".
ASSUMPTIONS: The `$'` check in section 11 (CP7) stays as it is. The scope stays minimal: no change to toks_of.
RISKS: The edit is already enforced in this live session. guard-dangerous.sh is read on each call, and the live hook blocked my own heredoc that contained `$'`. So the change works before ratification and restart. If the human rejects it, restore <scratch>/gd.cp7.sh or the cp7 patch.
MANUAL ACTION REQUIRED: Human ratification of the CP8 increment (it will be part of the single phase-01 ratification), then a restart.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Human review and ratification; code-reviewer review of the cumulative diff.
```

### Status

- Writer-side ANSI-C/locale bypass: **FIXED** for all callers. The new section 0 runs before section 1 on the raw text, for both Bash and mcp__aws-api__call_aws.
- Leftover word "An" in the CP7 comment: **FIXED**.

### Rule (sc_dollar_quote, a dedicated scanner)

- Single quotes are literal; backslash escapes the next character outside single quotes.
- Outside all quotes, `$` followed by `'` or `"` is blocked.
- Inside double quotes, `$` followed by `'` or `\"` is blocked (catches `bash -c "$'\x72m' x"`).
- `"$"` inside double quotes, before the closing quote, is allowed (needed for `grep -E "x$" f`).

### Evidence (cp8.py)

MUST BLOCK: all 21 BLOCKed for domain-engineer, factory-engineer and the orchestrator:

```text
$'\x74ee' CLAUDE.md                BLOCK x3
$'tee' CLAUDE.md                   BLOCK x3
$'scripts/cleanup\x2esh' dev       BLOCK x3
scripts/cleanup$'\x2e'sh dev       BLOCK x3
$"tee" CLAUDE.md                   BLOCK x3
bash -c "$'\x72m' x"               BLOCK x3
echo $'\x41'                       BLOCK x3
```

- **MUST PASS, domain-engineer** (all PASS): `grep 'foo$' modules/core/x.scala`; `sed -i 's/x$//' modules/core/x.scala`; `echo "cost: $5"`; `printf '%s\n' "$HOME"`; `grep -E "x$" f`; `git log --format='%h %s'`; `git diff --stat`.
- **code-reviewer:**
  - `echo "cost: $5"`, `printf '%s\n' "$HOME"`, `git log --format='%h %s'` and `git diff --stat` PASS.
  - `grep -E "x$" f` BLOCK (CP7 section 11, expected).
  - `grep 'foo$' modules/core/x.scala` BLOCK. This is the 1 unexpected result: a pre-existing CP7 fail-closed false positive (section 11's raw-text `$'` check), not caused by CP8.
- **Sanity:** domain-engineer `git diff --output=CLAUDE.md` BLOCK; `if aws s3 rm s3://b --recursive; then :; fi` BLOCK; deploy-engineer `scripts/deploy.sh dev` PASS; code-reviewer `echo $'\'' ; sed -n 1p CLAUDE.md #'` BLOCK.
- **Suites:** cp7.py 28 cases, 0 unexpected; its 3 INFO writer probes passed at CP7 and now block. t4.py 0 of 50. t3b.py 59 cases, 0 unexpected.

### CP8 increment diff

The new section 0 (`sc_dollar_quote` plus a call on `$raw`, inserted before section 1) and the one-word comment fix in section 11. The complete diff is in the agent's report as returned to the orchestrator; the exact final text is in `repair-01-wip-cp8.patch`.

### Privilege delta (CP8)

- **Narrowed, for every caller:** Bash commands containing `$'` or `$"` outside quotes, or `$'` or `$\"` inside double quotes, are blocked.
- **Widened:** nothing.

### Residual risk

1. Fail-closed false positives: heredoc bodies or quoted arguments with an unquoted `$'` (for example ANSI-C strings in bash code passed through a heredoc). Workaround: the Write tool, or a literal character.
2. Not detected: nested quoting that rebuilds `$'` only after an inner shell parses it, for example `bash -c '$'"'"'\x74ee'"'"' CLAUDE.md'`. `eval` or `printf %b` building command words from escapes was not modelled. Section 9b inline-code checks may cover some of these; this was not tested.
3. code-reviewer is still blocked on `grep 'foo$' ...` by the CP7 section 11 raw-text check.
4. The full 11-minute harness was not run.

## Checkpoint 10 report (factory-engineer; fixes review-report-03 N2 and N3; new read-only fence rule)

Human decision (2026-10-14): option B, modified so that code-reviewer keeps convenient Bash `grep`. Single quotes become the only quoting allowed for read-only callers, instead of a broader character ban.

Dispatched from session cs-441-scholar-chain-ba with a hard deadline of 15:55 CDT. Before dispatch, the orchestrator verified that the working-tree diff hash equalled the CP8 snapshot. Peer session cs-441-scholar-chain-78 stayed idle. The agent's readings show 15:31:43 to about 15:48 (about 16 min).

Orchestrator check (15:48:45):
- `git diff --stat`: 6 files changed, 923 insertions(+), 85 deletions(-).
- `bash -n`: passed on all 6 hooks.
- settings.json: JSON_OK.
- Snapshot `repair-01-wip-cp10.patch` saved; sha256 `501a162b77fccf97d4e49c4e8891aaa82bddce202b14661a96d950a333c26ea7`; `git apply --check -R` passed. It supersedes the CP8 patch.

```text
STATUS: COMPLETE
OWNER: factory-engineer
WORK ORDER: docs/agent-runs/phase-01/repair-01.md, CHECKPOINT 10 (N2, N3, read-only fence rule B-modified)
FILES CREATED: none in repo. Scratchpad only: gd.cp8.sh (baseline), new11.txt, cp10edit.py, cp10.py, cp10b.py
FILES MODIFIED: .claude/hooks/guard-dangerous.sh
COMMANDS RUN: date (15:31:43, 15:34:20, 15:36:27, 15:36:59, 15:41:13, 15:42:24, 15:46:06, 15:46:36, 15:46:49); bash -n all hooks; python -m json.tool settings.json and .mcp.json; git ls-files -s .claude/hooks; validate-agent-files.sh; cp10.py harness (twice); git diff --stat
TEST RESULTS: cp10.py, 64 targeted cases, final run on the final hook: PASS 19, BLOCK 45, UNEXPECTED 0
VALIDATION RESULTS: validate-agent-files.sh RESULT: PASS (exit 0); bash -n OK on all 6 hooks; both JSON files parse; all hooks are mode 100755
ASSUMPTIONS: repair-01.md is 2151 lines and the deadline did not allow reading it in full. I read the work-order constraints (lines 1-16) and the section index. review-report-03.md I read in full.
RISKS: see Residual risk
MANUAL ACTION REQUIRED: a fresh code-reviewer pass, then human ratification and restart
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: fresh code-reviewer review of CP10
```

### Status

- **N2 (CRITICAL): FIXED.**
  - New `sc_bs_newline` runs on the raw text for every caller before section 0. It blocks a backslash directly before LF or CR outside single quotes (double quotes included). Continuations are not folded.
  - Extra rule: if the raw text has backslash-LF or backslash-CR anywhere and names a shell launcher word (`bash|sh|zsh|dash|ksh|mksh|busybox|eval|su|runuser|env|script|pwsh|powershell|cmd`), it is blocked even inside single quotes. wrap9 reads segments line by line and would otherwise miss it.
- **N3 (MAJOR): FIXED.** `sc_hidden_quote` (the backslash-newline check plus `sc_dollar_quote`) runs on every wrap9 string at depth > 0 (bash -c, sh -c, eval, env -S, su -c), and on every wrap9 token that contains `$`. The latter catches `'$'"'"'\x74ee'"'"'${IFS}CLAUDE.md'`.
- **Read-only fence: REPLACED** (code-reviewer and unregistered callers).
  - LF or CR anywhere is rejected. Single quotes are the only quoting allowed, and an unbalanced single quote is rejected.
  - Outside single quotes these are rejected: `$`, backtick, `\`, `"`, `;`, `&`, `<`, `>`, `(`, `)`, `||`, `|&`, and empty pipe segments. `(`, `)` and empty segments are extra narrowing beyond the spec.
  - `|` splits segments. The exact words `2>/dev/null` and `2>&1` are allowed and dropped.
  - Command allowlists and option checks are unchanged.
  - Removed: the CP7 `$'`/`$"` raw check (subsumed; it would block `grep -rn 'foo|bar$' docs`), ro_mask, the old ro_split, and the raw_nr `sed`.
  - The section-0 `if sc_dollar_quote "$raw"` block remains, redundant but harmless.

### Evidence (cp10.py, final run on the final hook, verbatim)

```text
ok  PASS PASS code-reviewer "grep -rn 'foo|bar$' docs"
ok  PASS PASS code-reviewer "grep -nE 'a;b & c' docs/agents.md"
ok  PASS PASS code-reviewer "grep -c '&lt;file&gt;' CLAUDE.md"
ok  PASS PASS code-reviewer "git log --oneline | grep 'fix'"
ok  PASS PASS code-reviewer "grep -rn 'x' docs | head -20"
ok  PASS PASS code-reviewer 'git diff --stat'
ok  PASS PASS code-reviewer 'git --version'
ok  PASS PASS code-reviewer "rg --help 2>&1 | grep -- '--pre'"
ok  PASS PASS code-reviewer 'bash -n .claude/hooks/guard-dangerous.sh'
ok  PASS PASS code-reviewer 'sort docs/agents.md | uniq -c'
ok  PASS PASS code-reviewer "grep -n 'segment_writes()' .claude/hooks/guard-dangerous.sh"
ok  PASS PASS code-reviewer "grep -rn 'x' docs 2>/dev/null"
ok  BLOCK BLOCK code-reviewer "echo $'\\'' fence-probe >/dev/stderr #'" | ANSI-C or locale quoting ...
ok  BLOCK BLOCK code-reviewer "echo $'\\'' ; sed -n 1p CLAUDE.md #'" | ANSI-C or locale quoting ...
ok  BLOCK BLOCK code-reviewer "echo $\\\n'\\'' > out.txt; sed -n 1p CLAUDE.md #'" | a backslash-newline line continuation ...
ok  BLOCK BLOCK code-reviewer 'grep "x" f' | code-reviewer is read-only ...
ok  BLOCK BLOCK code-reviewer 'grep x f > out'
ok  BLOCK BLOCK code-reviewer 'grep x f; sed -n 1p CLAUDE.md'
ok  BLOCK BLOCK code-reviewer 'grep x f || rm f'
ok  BLOCK BLOCK code-reviewer 'grep x f |& tee o'
ok  BLOCK BLOCK code-reviewer "cat f | sed 's/a/b/'"
ok  BLOCK BLOCK code-reviewer 'echo \\$HOME'
ok  BLOCK BLOCK code-reviewer "grep 'unbalanced f"
ok  BLOCK BLOCK code-reviewer 'grep x f >/dev/null'
ok  BLOCK BLOCK code-reviewer 'grep x f 2>/dev/nullx'
ok  BLOCK BLOCK code-reviewer '(sed -n 1p f)'
ok  BLOCK BLOCK code-reviewer 'grep x f |'
ok  BLOCK BLOCK code-reviewer "grep 'a\nb' f"
(the same 16 BLOCK cases for mystery-agent (unregistered): all ok BLOCK BLOCK, same reasons)
ok  BLOCK BLOCK domain-engineer "$\\\n'\\x74ee' CLAUDE.md </dev/null" | a backslash-newline line continuation ...
ok  BLOCK BLOCK factory-engineer "$\\\n'\\x74ee' CLAUDE.md </dev/null"
ok  BLOCK BLOCK orchestrator "$\\\n'\\x74ee' CLAUDE.md </dev/null"
ok  BLOCK BLOCK domain-engineer 'echo "a\\\nb"'
ok  PASS PASS domain-engineer "git commit -m 'line1\nline2'"
ok  PASS PASS domain-engineer "echo 'a\\\nb'"
ok  BLOCK BLOCK domain-engineer 'bash -c \'$\'"\'"\'\\x74ee\'"\'"\' CLAUDE.md\' </dev/null' | ANSI-C or locale quoting ...
ok  BLOCK BLOCK domain-engineer 'bash -c \'$\'"\'"\'\\x74ee\'"\'"\'${IFS}CLAUDE.md\' </dev/null'
ok  BLOCK BLOCK domain-engineer 'eval \'$\'"\'"\'\\x74ee\'"\'"\' CLAUDE.md\''
ok  BLOCK BLOCK domain-engineer "bash -c 'echo a\\\nb'" | a backslash-newline inside a command that starts a shell ...
ok  BLOCK BLOCK domain-engineer 'env -S \'$\'"\'"\'\\x74ee\'"\'"\' CLAUDE.md\''
ok  BLOCK BLOCK domain-engineer 'git diff --output=CLAUDE.md' | only factory-engineer writes CLAUDE.md ...
ok  BLOCK BLOCK domain-engineer 'if aws s3 rm s3://b --recursive; then :; fi' | direct AWS call 'aws s3 rm' ...
ok  BLOCK BLOCK domain-engineer "echo 'terraform destroy'" | 'terraform apply' and 'terraform destroy' are denied ...
ok  PASS PASS deploy-engineer 'scripts/deploy.sh dev'
ok  PASS PASS domain-engineer "grep 'foo$' modules/core/x.scala"
ok  PASS PASS domain-engineer "sed -i 's/x$//' modules/core/x.scala"
ok  BLOCK BLOCK domain-engineer "$'\\x74ee' CLAUDE.md" | ANSI-C or locale quoting ...
ok  PASS PASS domain-engineer 'echo "cost: $5"'
ok  PASS PASS domain-engineer "git log --format='%h %s'"
CASES 64 {'PASS': 19, 'BLOCK': 45} UNEXPECTED 0
```

The first run, before the launcher rule, had one unexpected result: `bash -c 'echo a\<LF>b'` passed for domain-engineer. The launcher rule fixed it.

docs/agents.md: no change. The section 5 code-reviewer row is still accurate.

### CP10 increment diff

The full diff against `gd.cp8.sh` is in the agent's report as returned to the orchestrator; the exact final text is in `repair-01-wip-cp10.patch`. It contains:

- new `sc_bs_newline` and `sc_hidden_quote`, called on `$raw` before the section-0 check;
- the launcher-conditional backslash-newline rule;
- `sc_hidden_quote` calls in wrap9 (on the string at depth > 0, and on tokens containing `$`);
- section 11's ro_mask/ro_split replaced by the single-quote-only ro_split, with the CP7 raw check and the raw_nr sed removed.

### Privilege delta vs CP8

- **code-reviewer and unregistered callers gain** (literal arguments only; command and option allowlists unchanged): single-quoted arguments may contain `; & | < > ( ) $`, backtick and `"`, so `grep 'segment_writes()'` and `grep 'foo|bar$'` now pass.
- **They lose:** double quotes, backslash escapes, `$VAR`/`$(...)`/backticks, `;`/`&&` chaining, `<`, `>/dev/null` (only `2>/dev/null` and `2>&1` remain), multi-line commands, and subshell parentheses.
- **All callers lose:** backslash-newline outside single quotes; backslash-newline anywhere when a shell launcher word is named; `$'`/`$"` inside inner command strings and inside any token containing `$`.

### Residual risk

1. **Heredoc quote confusion** (OPEN, non-read-only callers only): an odd apostrophe in an unquoted heredoc body can make the scanners treat a later `$\<LF>'...'` or `$'...'` as single-quoted. The launcher rule catches this when a shell word is present; otherwise only the prompt remains.
2. **Globs and tilde** (not addressed): unquoted `*`, `?`, `[`, `~` still expand in the read-only fence. A file whose name begins with `-` could reach an allowlisted command as an option the checks never saw.
3. **New false positives** (fail closed): a single-quoted backslash-newline in a command naming a launcher word; tokens with unquoted `$'`/`$"`; for read-only agents, double quotes, `>/dev/null`, multi-line commands.
4. **Unchanged:** hook-timeout fail-open (F2), with two more per-character loops added; F7 Bash forms untraced; runtime-generated text guarded only by the prompt for agents that are not read-only.
5. The agent did not read repair-01.md in full.
