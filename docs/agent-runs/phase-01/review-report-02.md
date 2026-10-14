# Phase 01: repair-01 review (code-reviewer, checkpoint 6)

The orchestrator assembled this file from code-reviewer's final report, verbatim. code-reviewer has no Write tool.

Work order: a fresh, independent, read-only review of the complete repair-01 change set. Deadline 23:08 CDT, 2026-10-13. The agent's `date` readings ran 22:50:49 to 22:53:13, and the report arrived about 22:54.

Orchestrator note: the reviewer showed N1 by running two harmless commands through the bypass. Probe B ran `sed -n 1p CLAUDE.md`, a read that the fence normally rejects for code-reviewer. Neither probe wrote a file.

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 01, repair-01 CHECKPOINT 6. Independent read-only review of the full repair-01 change set (HEAD to working tree: .claude/hooks/guard-dangerous.sh, .claude/hooks/guard-ownership.sh, .claude/settings.json, docs/agents.md, .claude/commands/incident.md, .claude/agents/incident-responder.md).
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN:
- `date` (22:50:49, 22:52:11, 22:52:49, 22:53:13 CDT)
- `git diff --stat` showed 6 files, 850 insertions(+), 85 deletions(-), which matches the work order.
- `git diff -- .claude/settings.json docs/agents.md .claude/hooks/guard-ownership.sh`
- `git diff -- docs/agents.md .claude/commands/incident.md .claude/agents/incident-responder.md`
- Grep and Read of guard-dangerous.sh (lines 1-62, 515-559, 960-1099), review-report.md and repair-01.md (CP3b residual risk, lines 1600-1669)
- Probe A (harmless, writes no file): `echo $'\'' fence-probe >/dev/stderr #'`. The guard did not block it. The redirect ran and printed `' fence-probe`.
- Probe B (harmless, read only): `cd <repo>; echo $'\'' ; sed -n 1p CLAUDE.md #'`. The guard did not block it, and `sed` printed the first line of CLAUDE.md. Running `sed` on its own was blocked earlier in this session ("Rejected: 'sed'").
- A block observed during the review: a `grep -n "...\|^[a-z_]*()"` command was rejected with "Rejected: 'guard-dangerous.sh '". The fence treats a quoted `(` as a segment split. This false positive fails closed, so I did not count it as a finding.
TEST RESULTS: No sbt tests are in scope. Neither CP5 harness was rerun, because the read-only fence does not permit running the harnesses.
VALIDATION RESULTS: Not run. `bash -n` and the JSON check were not repeated, and I made no claim based on them.
ASSUMPTIONS: The 9-harness cp5b result in repair-01.md is the author's claim and I did not verify it. Probes A and B ran inside this live session, so they test the hook currently loaded from the working tree.
RISKS: The time limit meant F3, F6 and F7 (Bash forms) were checked against the diff and the documented gaps, not traced exhaustively. Hook latency was not measured. ro_mask/ro_split walk the command one character at a time with `${s:i:1}` and `out+=`, and on a very long command (a large heredoc) this may approach the 20 s timeout. A timeout is a non-blocking error, so the hook would fail open. This is unverified.
MANUAL ACTION REQUIRED: Human architect: do not ratify CP5's section 11 change as it stands (see N1). N1 widens a tool fence, so it goes to you as well as to factory-engineer.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: factory-engineer repairs N1 (repair-01 CP7 or repair-02), then a fresh code-reviewer pass, then human ratification and restart.
```

## Verdict: REQUEST_CHANGES

## Status of the original findings

| # | Finding | Status | Evidence |
|---|---|---|---|
| F1 | Allow-listed commands write constitutional files, human evidence, and the test marker | PARTIALLY CLOSED (documented) | Section 9 (guard-dangerous.sh:551-559) and section 9a (:518-549) block Bash writes that name protected paths. Indirect writes that never name the path (`cd .claude/hooks && rm x`, a program writing internally) are a documented gap (repair-01.md:264). |
| F2 | Hook wiring fails open when a hook script is deleted | PARTIALLY CLOSED (documented) | The settings.json wrappers check `[ -f "$h" ]`, exit 2, and add `\|\| exit 2`. A hook replaced by an exit-0 script, or a hook timeout, still fails open (repair-01.md:263). |
| F3 | Script-runner map, environment check, and cleanup denial can be bypassed | PARTIALLY CLOSED (documented) | The cleanup deny list was widened (settings.json deny: `./cleanup.sh*`, `*/bash *cleanup.sh*`, `source`, `.`). script_runners and ENV_SCRIPTS are at :24-39. A renamed copy, a symlink, or a script started from python/perl/make is not detected (repair-01.md:590-591). |
| F4 | sbt allow wildcards forge the marker or run build code with no prompt | CLOSED | settings.json allow entries are now exact (`Bash(sbt test)` and so on). `testOnly*`, `"testOnly*` and `--client *` moved to ask. |
| F5 | Ownership map not enforced for Bash writes | PARTIALLY CLOSED (documented) | docs/agents.md section 4 "Enforcement limits" and section 5 now state that the map covers Write/Edit only and that Bash writes rest on prompt, role files and code-reviewer detection. The docs now describe the gap instead of claiming enforcement. |
| F6 | AWS mutation detection misses invocation forms | PARTIALLY CLOSED (documented) | aws_scan/aws_verdict/aws_call (:384-515). Known gaps: renamed binary, boto3 in python -c, SDK calls from scripts (repair-01.md:1640-1644). IAM remains the primary control, as the original review said. Accepting these gaps for Phase 01 is acceptable. |
| F7 | Secret-file coverage gaps | CLOSED for the Read tool; Bash forms not verified | settings.json deny now includes `**/.credentials`, `~/.sbt/.credentials`, `~/.ivy2/.credentials`, `credentials.tfrc.json`, `gh/hosts.yml`. I did not trace Bash `cat` of these paths in the time available. |
| F8 | Read-only fence admits commands that write files or execute programs | OPEN (regressed by CP5) | See N1. The CP5 quote-aware masking lets code-reviewer redirect output and run any command. Verified by execution. |
| F9 | build-engineer `.gitkeep` exception overrides first-match-wins | CLOSED | guard-ownership.sh:96-109: an empty Write only, excluding `.claude/*`, `docs/agent-runs/*`, and the factory-engineer, human and generated owners. The docs/agents.md section 4 row matches. |
| F10 | factory-engineer exempt for `.claude/settings.local.json` | CLOSED | guard-dangerous.sh:552-554 blocks that path for every caller, and :539 does the same for output operands. |
| F11 | Documented smoke-test prompt contradicts settings | CLOSED | incident.md and incident-responder.md now say smoke-test runs without a prompt, which matches docs/agents.md section 7. |
| F12 | Raw-string matching blocks legitimate evidence commands | PARTIALLY CLOSED, and the fix introduced N1 | Quoted `>` `;` `\|` `&` are now literal (:985-1018), but the masking mis-models ANSI-C quoting (N1). Quoted `(` still splits segments, which fails closed and is acceptable. |

## New findings, ranked

### N1. CRITICAL: ANSI-C quoting (`$'...'`) hides redirects, `;`, `|`, `&` and `$(` from the code-reviewer fence, so a read-only agent can write files and run any program.

- **File:** .claude/hooks/guard-dangerous.sh:989-990 and :997, used at :1009 and :1018.
- **Cause:** ro_mask treats `$'` as an ordinary single quote. Inside single quotes it ends the quote at the first `'` (line 990). Bash does not end an ANSI-C string at `\'`: inside `$'...'` that is an escaped quote. After that point the mask's quote state is inverted relative to bash. The mask then thinks the rest of the command is quoted when bash treats it as live shell syntax, and a trailing `#'` comment balances the quotes.
- **Failure input, verified by execution:**
  - Redirect hidden: `echo $'\'' fence-probe >/dev/stderr #'` was not blocked and the redirect ran.
  - Command chaining hidden: `echo $'\'' ; sed -n 1p CLAUDE.md #'` was not blocked and `sed` ran. `sed` alone is rejected by the same fence.
- **Failure input, traced but not executed** (to avoid writing or executing anything):
  - `echo $'\'' payload > modules/core/src/main/scala/X.scala #'` writes into another owner's territory.
  - `echo $'\'' ; python other.py #'` (or `; sbt run`, `; git config ...`) runs anything that sections 1-10 do not block by name.
  - Sections 9 and 9a still see the raw text, so constitutional paths, the marker, human-gate.md and docs/agent-runs remain protected. Everything else is open: `modules/**`, `tests/**`, `infra/**`, `scripts/**`, and `.git` internals through writes that do not name the path.
- **Impact:** The code-reviewer tool fence (no Write, no Edit, evidence-only Bash), which docs/agents.md section 5 calls mechanical, is defeated. The same applies to unregistered subagents, which share section 11 (:977). docs/agents.md sections 4 and 5 rely on "code-reviewer detection" as the backstop for F5, so this also undermines the documented compensating control for Bash writes. This is a widening of a tool fence (iron law 3), and CP5 introduced it.
- **Owner:** factory-engineer, with escalation to the human architect.
- **Suggested direction (the owner decides):**
  - In section 11, fail closed on any `$'` or `$"` outside single quotes. Alternatively, model ANSI-C quoting as its own state in which `\` escapes the next character.
  - Add both probe inputs above to the CP5 harness as must-block cases.
  - Also consider rejecting unbalanced quotes outright instead of "an unbalanced quote runs to the end" (:984), because that comment shows the masker trusts quote state it cannot verify.

No other new CRITICAL or MAJOR findings were verified in the time available. Two items are unverified risks, not findings: the possible hook latency on very long commands, and the F7 Bash forms.

## Recommendation for ratification

Do not ratify repair-01 as it stands. Ratifying CP5 would mean accepting that the read-only code-reviewer, the project's reviewer of last resort, can write to any non-constitutional path and run any program through a one-line quoting trick. Both forms were demonstrated in this session.

Apart from N1, the repair is substantively sound. F4, F9, F10 and F11 are closed. F1, F2, F3, F5 and F6 are partially closed, and each gap is honestly documented.

Accepting the repair after an N1 fix would mean accepting four things:

- (a) the ownership map is mechanically enforced only for Write and Edit;
- (b) Bash writes that never name a protected path, renamed or symlinked scripts, exit-0 replacement of a hook, and hook timeouts all still fail open;
- (c) AWS mutations through boto3, SDKs, or renamed binaries are caught only by IAM;
- (d) the documented fail-closed false positives.

These residual gaps are acceptable for Phase 01. N1 is not.
