# Phase 01: control-plane audit (code-reviewer)

Assembled by the orchestrator from code-reviewer's final report, verbatim. code-reviewer has no Write tool. Work order: audit the control plane in full, report only.

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase-01 control-plane audit, report only. Scope: CLAUDE.md, docs/agents.md, .claude/settings.json, .claude/hooks/*, .claude/agents/*.md, .claude/commands/*, .mcp.json. Re-verify Probe 1 and Probe 2 and the observed hook behavior.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN:
- `git status`, `git diff --stat`, `git diff --cached --stat`, `ls -la .claude ...`, `wc -l ...`: OK. The tracked tree is clean, so the audit covers HEAD c81d9ef (control plane added in f563f3b). Untracked: docs/agent-runs/phase-01/{commit.txt,human-gate.md,validate-agent-files-output.txt}.
- `for f in .claude/agents/*.md; do ... cat ...; done`: BLOCKED by guard-dangerous (read-only fence rejects `for`). I read the files with Read instead.
- `grep -c "terraform apply" CLAUDE.md`: BLOCKED by guard-dangerous, terraform rule (false positive).
- `git --version && git diff -h ...`: BLOCKED (read-only fence rejects `git --version`).
- `python -m json.tool -h`: OK. Usage shows `[infile] [outfile]` and "outfile: write the output of infile to outfile".
- `git diff -h 2>&1 | grep -n -- "output"`: OK (short usage only).
- `rg --help 2>&1 | grep -n -A3 -- "--pre <COMMAND>"`: BLOCKED as "output redirection" (false positive: the `>` inside `<COMMAND>`).
- `grep -o ... "&lt;file&gt;..." git-diff.html`: BLOCKED with "Rejected: 'lt'" (false positive: the `;` and `&` in an HTML entity split the segment).
- `rg --help`: OK. `--pre=COMMAND`: "For each input PATH ... search the standard output of COMMAND PATH" (spawns a process per file).
- Grep of the local git-diff.html: `--output=<file>`: "Output to a specific file instead of stdout."
- `git log --stat --format="%h %s" -4`: OK.
TEST RESULTS: None run. No build.sbt or project/ exists yet (Glob found none), so `sbt Test/compile`, `sbt test`, and `sbt check` are not applicable.
VALIDATION RESULTS: I did not run `bash .claude/hooks/validate-agent-files.sh`: my fence allows `bash` only with `-n`. I read the human-captured output (docs/agent-runs/phase-01/validate-agent-files-output.txt, HEAD f563f3b, RESULT: PASS, exit 0). That PASS does not cover any finding below: the validator does not check guard semantics, compare fences to the section 5 table, or compare sc_owner to section 4 row order. I checked by hand and found these consistent with docs/agents.md: ROSTER (18), READ_ONLY_AGENTS=code-reviewer, APPROVED_ENVIRONMENTS=dev, script_runners, ENV_SCRIPTS, and all 18 `tools:` lines against the section 5 fences. I found no Agent, Task, or NotebookEdit grants. .mcp.json versions are pinned, secrets are only `${VAR}` references, the github endpoint is read-only, and aws-api has READ_OPERATIONS_ONLY=true.
ASSUMPTIONS:
- Claude Code hook semantics are as stated in .claude/hooks/common.sh:5-6: exit 2 blocks, any other non-zero exit is a non-blocking error.
- "Prompt remains" means default permission mode. I did not verify whether acceptEdits mode auto-approves filesystem Bash commands (cp, mv, sed) or whether Claude Code auto-approves some read-only commands (sort, uniq, rg) without a prompt. If either happens, findings 3 to 8 get worse.
- I did not run any write-capable exploit. Each bypass is verified by tracing the code paths, plus executed confirmation of the tool option semantics (json.tool outfile, git `--output`, rg `--pre`).
RISKS:
- The scripts/ directory and the sbt build do not exist yet. Findings 3 and 4 are latent until infra-engineer (phase scripts) and build-engineer (phase 02, markTestRun) create them, but the permissive rules are already committed.
- Not verified: whether `AWS_API_MCP_ALLOW_UNRESTRICTED_LOCAL_FILE_ACCESS="no-access"` is a value aws-api-mcp-server 1.5.6 recognizes (it may expect a boolean or a different variable). I had no aws-docs access.
- MCP images and packages are pinned by tag or version, not digest; transitive uvx dependencies are unpinned.
- No `.gitignore` exists yet, so `.claude/settings.local.json`, `.claude/.last-test-run`, and `.env` are not ignored. This belongs to build-engineer in phase 02.
- Symlink redirection is not reported: guard-ownership uses abspath, not realpath (common.sh:81). I could not confirm exploitability on this host, because Git Bash `ln -s` copies by default and I did not verify Claude Code's own symlink checks.
- Prior evidence re-check:
  - `rm .env` being blocked is intended behavior, not a defect: CLAUDE.md law 4 and section 4 say no agent writes secret material, and settings.json:162-165 plus guard :130 implement that. The human deletes it manually.
  - The `.env` Read denial is intended (settings.json:94).
  - Probe 1 exercised only the instruction layer, because no write was attempted. It does not show that Bash writes are mechanically fenced, and finding 5 shows they are not.
MANUAL ACTION REQUIRED:
- The human decides whether to ratify phase 01 with these findings open. I recommend no ratification until finding 1 is fixed.
- Findings 1 and 2 are floor and security matters, so the human architect should see them directly.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: factory-engineer amendment fixing findings 1 to 10 (plus build-engineer input on finding 4 in phase 02), then validator rerun, a fresh code-reviewer pass, and human ratification and restart.
```

## Verdict: REQUEST_CHANGES

Severity calibration: CRITICAL means a floor invariant or constitutional file falls with no human in the loop (allow-listed, no prompt). MAJOR means a mechanical guard is bypassed and only the human permission prompt remains, or a documented enforcement claim is false. On this basis I downgrade Probe 2 item (2) from CRITICAL to MAJOR, because none of those inputs is allow-listed.

## Findings

### 1. CRITICAL: Allow-listed commands write to constitutional files, human evidence, and the test marker with no prompt and no guard block.

- Files: .claude/settings.json:16-18 (`Bash(git diff*)`, `git log*`, `git show*`), .claude/settings.json:25 (`Bash(python -m json.tool *)`); .claude/hooks/guard-dangerous.sh:99-105 (the writer list in segment_writes lacks python, and the git sub-list lacks diff/log/show); :352 and :356 (the read-only fence admits both).
- Failure inputs, from any caller including code-reviewer and the orchestrator:
  - (a) `python -m json.tool .mcp.json .claude/settings.json`. This replaces settings.json with a file that has no `permissions` or `hooks` key.
  - (b) A writer agent first Writes `<scratchpad>/s.json` (guard-ownership.sh:66 exits 0 for TEMP), then runs `python -m json.tool <scratchpad>/s.json .claude/settings.json` with a permissive allow list.
  - (c) `git diff --output=.claude/.last-test-run`.
  - (d) `git log -1 --output=docs/agent-runs/phase-01/human-gate.md` or `git diff --output=CLAUDE.md`.
- Trace: segment_writes returns 1 at :91-107 (no `>`, the command word is python or git with sub `diff`/`log`), so the checks at :285, :288, :291, and :294 never fire. The permission prompt is skipped because of the allow rules.
- Impact:
  - Unratified replacement of settings.json: every deny rule and hook registration is lost at the next start, or permissions are widened. This violates CLAUDE.md law 6 and the floor.
  - The marker mtime is forged, so verify-tests-ran.sh:37 passes without `sbt check` and the Stop gate is defeated.
  - Human ratification records can be overwritten.
- Owner: factory-engineer. Escalate to the human architect.
- Verified by: execution for the option semantics (json.tool `outfile`, git-diff `--output=<file>`), code tracing for the guard path.

### 2. MAJOR: Hook wiring fails open when a hook script is deleted.

- Files: .claude/settings.json:220, 226, 232 (`bash "$CLAUDE_PROJECT_DIR/.claude/hooks/guard-*.sh"`); guard-dangerous.sh:189-190 (`find -delete` is blocked only on broad paths) and :100 (find is not a writer).
- Failure input: `find .claude/hooks -name guard-dangerous.sh -delete`. This needs one prompt. `.claude/hooks` is not in is_broad (:156-164), and section 9 matches the path, but `find` is not in the writer list.
- Impact: every later Bash hook invocation exits 127. Per common.sh:5-6 that is non-blocking, so all Bash floor rules (terraform, secrets, AWS, force push) stop working for the session. The guards claim to fail closed (guard-dangerous.sh:11) but do not for this case.
- Owner: factory-engineer.
- Verified by: code tracing.

### 3. MAJOR: The script-runner map, approved-environment check, and cleanup denial can be bypassed.

- Files: guard-dangerous.sh:304 (no basename on `c`), :308 (anchored `^(bash|sh|...)$`), :310 (`-*n*` treats any option containing "n" as syntax-only); settings.json:208-212 (the deny rules match only the literal prefixes).
- Failure inputs from any agent, each needing only a prompt:
  - `bash --norc scripts/cleanup.sh`
  - `bash --noprofile scripts/deploy.sh prod`
  - `/usr/bin/bash scripts/rollback.sh staging`
  - `cd scripts && ./cleanup.sh`
  - `echo scripts/cleanup.sh | xargs bash` (the segment after `|` contains no script path)
- Impact: the human-only cleanup script and non-approved environments are reachable by any agent, and the section 7 runner map is not enforced.
- Owner: factory-engineer.
- Verified by: code tracing. Latent until scripts/ exists.

### 4. MAJOR: sbt allow wildcards let any agent forge the test marker or run arbitrary build code with no prompt.

- Files: settings.json:6-14 (`sbt compile*`, `sbt test*`, `sbt check*`, `sbt --client *`); guard-dangerous.sh:360 (the read-only blocklist misses `eval`, `test/run`, `test/runmain`).
- Failure inputs:
  - `sbt compile markTestRun`: matches :7, no prompt. The guard sees no `.last-test-run` string, so the Stop gate is defeated once build-engineer creates markTestRun.
  - `sbt --client "eval ..."` and `sbt --client run`: no prompt.
  - code-reviewer `sbt Test/run`: passes the guard.
- Impact: CLAUDE.md law 2 and the Stop hook floor can be defeated without tests running.
- Owner: factory-engineer (settings and hook). build-engineer should also make markTestRun reachable only from `check`.
- Verified by: code tracing. Latent until phase 02.

### 5. MAJOR: The ownership map is not enforced for Bash writes, contrary to docs/agents.md section 4 ("enforced by guard-ownership.sh") and section 5 ("Ownership hook limits writes to tests/**").

- Files: settings.json:230 (the ownership hook matches only Edit|Write|MultiEdit|NotebookEdit); guard-dangerous.sh never consults sc_owner (no grep match for sc_owner or guard-ownership in that file); the section 9 path-string checks at :89-108 cover only constitution, marker, and evidence paths.
- Failure inputs:
  - domain-engineer: `sed -i 's/2.13/3.5/' build.sbt`
  - test-engineer: `cp <scratchpad>/Fix.scala modules/core/src/main/scala/Fix.scala`
  - Any non-factory agent can also bypass the constitution check, which does not unwrap wrappers (Probe 2 item 3 confirmed): `bash -c 'cp x CLAUDE.md'` (bash is not in the writer list), `echo CLAUDE.md | xargs rm` (the path is in a different segment), `tar -xf <scratchpad>/c.tar` (no path string at all).
- Impact: one-writer-per-artifact (CLAUDE.md law 1) rests only on the permission prompt and agent instructions. The documentation overstates what is mechanically enforced.
- Owner: factory-engineer (hook, or an honest correction to sections 4 and 5).
- Verified by: code tracing.

### 6. MAJOR: AWS mutation detection misses common invocation forms.

- File: guard-dangerous.sh:263-264 (aws is checked only when it is the command word or follows `-c|xargs|sudo|env|time|nohup|exec|command|then|do|else|!`).
- Failure inputs:
  - `if aws s3 rm s3://scholarchain-data --recursive; then :; fi` (the previous word is `if`)
  - `nice -n 10 aws dynamodb delete-table --table-name t` (cmd_index stops at `-n`, and the previous word is `10`)
  - `"/c/Program Files/Amazon/AWSCLIV2/aws.exe" s3 rb s3://b --force` (quote stripping splits the path)
  - `python -m awscli s3 rm s3://b/k`
- The settings deny rules at :169-207 are literal prefixes. I did not verify whether Claude Code's compound-command parsing or wrapper stripping catches the `if` and `nice` forms.
- Impact: the floor invariant against destructive AWS operations depends on the prompt, using the human's local CLI credentials, not the read-only MCP profile.
- Owner: factory-engineer.
- Verified by: code tracing.

### 7. MAJOR: Secret-file coverage gaps for credential files this stack actually uses.

- Files: guard-dangerous.sh:130; settings.json:94-120.
- Failure inputs: `cat ~/.sbt/.credentials`, `cat ~/.ivy2/.credentials`, `cat ~/.terraform.d/credentials.tfrc.json`, `cat ~/.config/gh/hosts.yml`, or Read of the same paths.
- Trace:
  - `/credentials([^a-z0-9_.]|$)` requires `/` immediately before the name and rejects a following `.`.
  - `\.credentials\.json` requires `.json`.
  - No Read deny rule matches these paths.
- Impact: sbt publish credentials, the TFE token, and the GitHub OAuth token are readable, which violates CLAUDE.md law 4.
- Owner: factory-engineer.
- Verified by: code tracing.

### 8. MAJOR: The read-only fence admits commands that write files or execute programs (Probe 2 item 4 confirmed, with additions).

- File: guard-dangerous.sh:348 (sort, uniq, rg, shellcheck, checkov, tfsec, tflint allowed with no option checks) and :352 (`git grep`, `git diff`, `git log`, `git show` allowed with any options).
- Failure inputs as code-reviewer:
  - `sort -o CLAUDE.md CLAUDE.md`
  - `uniq docs/agents.md docs/agents.md.tmp`
  - `rg --pre bash . some/file.sh` (runs `bash some/file.sh`, per the rg help I ran)
  - `git grep --open-files-in-pager=bash -e x -- f.sh`
  - `tflint --fix`
  - `tfsec --out f`
  - `git diff --output=f` (see finding 1)
- Impact: the section 5 claim "No Write, Edit ... Bash is fenced to evidence commands" is false. The reviewer can modify and execute.
- Owner: factory-engineer.
- Verified by: execution for rg `--pre` semantics, code tracing for the guard.

### 9. MINOR: The build-engineer `.gitkeep` exception overrides the first-match-wins rule in section 4.

- File: guard-ownership.sh:96-98. The exception runs after sc_owner, regardless of which row matched.
- Failure input: build-engineer Writes `docs/agent-runs/phase-01/.gitkeep`, `.claude/hooks/.gitkeep`, or `.claude/.gitkeep` with arbitrary non-empty content.
- Impact: a non-factory, non-human writer gets into human-evidence and constitutional directories.
- Owner: factory-engineer.
- Verified by: code tracing.

### 10. MINOR: factory-engineer is exempt from the Bash guard for `.claude/settings.local.json`, although section 4 says that file is human-local and written by no agent.

- File: guard-dangerous.sh:83 (PATH_ALT_CONST includes `settings(\.local)?`) and :285 (exemption by caller).
- Failure input: factory-engineer runs `cp <scratchpad>/l.json .claude/settings.local.json` (prompt only).
- Impact: local settings override project allow rules without ratification.
- Owner: factory-engineer.
- Verified by: code tracing.

### 11. MINOR: The documented prompt for smoke-test contradicts the settings.

- Files: .claude/commands/incident.md:13 and .claude/agents/incident-responder.md:12 say rollback.sh and smoke-test.sh each run "only after a human permission prompt".
- Failure input: incident-responder runs `scripts/smoke-test.sh dev`. This is allow-listed at settings.json:35-37 (consistent with docs/agents.md section 7 "no prompt"), so it runs without a prompt.
- Impact: the command and role text claim a gate that does not exist.
- Owner: factory-engineer.
- Verified by: code tracing.

### 12. MINOR: Raw-string matching blocks legitimate evidence commands.

- Files: guard-dangerous.sh:112 (terraform regex on the whole command), :342 (any `>`), :61 (splits on `;` and `&` inside quoted text), :352 (`git --version`).
- Failure inputs, executed and BLOCKED in this session:
  - `grep -c "terraform apply" CLAUDE.md`
  - `rg --help 2>&1 | grep -- "--pre <COMMAND>"`
  - a grep for `&lt;file&gt;`
  - `git --version`
- Prior observation: `echo 'terraform destroy'` was blocked.
- Traced: `git commit -m "docs: never run terraform apply"` would also be blocked.
- Impact: reviewers cannot grep the constitution for floor phrases, and blocked commands trigger retry loops.
- Owner: factory-engineer.
- Verified by: execution.
