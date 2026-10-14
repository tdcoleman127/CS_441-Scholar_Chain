# Phase 01: repair-01 final review (code-reviewer, checkpoint 11)

The orchestrator assembled this file from code-reviewer's final report, verbatim. code-reviewer has no Write tool.

Work order: a fresh, independent, read-only review of the cumulative repair-01 change set after CP10. Deadline 16:07 CDT, 2026-10-14. The agent's `date` readings start at 15:49:56; the report arrived about 15:53. No bypass was executed; every new finding is traced.

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 01, repair-01 final review (checkpoint 11). This is a fresh, read-only review of the cumulative repair-01 change set after CP10, with a hard deadline of 16:07 CDT on 2026-10-14.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN:
- `date` (15:49:56, 15:51:04, 15:52:01 CDT)
- `git -C '<repo>' diff --stat`. The guard rejected it with "Rejected: git -c". Section 11 lower-cases argv[1], so `-C` is read as `-c`. I got no diff output.
- `bash -n .claude/hooks/guard-dangerous.sh`: it exited with no output (syntax OK).
- I also used the Read and Grep tools on guard-dangerous.sh (lines 241-334, 742-771, 1045-1173) and on review-report-03.md.
TEST RESULTS: I ran no sbt tests because no Scala sources changed.
VALIDATION RESULTS: The `bash -n` syntax check of guard-dangerous.sh passed. I did not run validate-agent-files.sh. I did not execute any bypass: every finding below comes from reading the code.
ASSUMPTIONS:
- Because of the deadline and the rejected `git -C`, I did not re-read all 6 touched files in full. I reviewed the CP10 code paths the work order names: section 0 (sc_bs_newline, sc_hidden_quote, the launcher rule), wrap9 N3, and the section 11 fence.
- I did not re-trace the other files (guard-ownership.sh, settings.json, incident-responder.md, incident.md, docs/agents.md) in this pass.
- For what happens downstream after section 0, I rely on review-report-03's N2 trace. That trace found that sections 9 and 10 do not recover an obfuscated command word.
- The hook's bash does brace expansion and pathname expansion (both are bash defaults in a non-interactive shell).
RISKS: The read-only fence has a direct bypass that needs no planted file (R1 below). Section 0 can still be defeated by a comment or heredoc that leaves an odd apostrophe (R2 below). I did not measure hook timing.
MANUAL ACTION REQUIRED: Human architect: do not ratify repair-01 CP10 yet. R1 widens the read-only tool fence, letting it delete files and run programs. R2 re-opens the N2 floor-invariant bypass for every caller. Both go to you as well as to factory-engineer.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: factory-engineer repairs R1 and R2 (and optionally R3 and R4), then a fresh code-reviewer pass, then human ratification and restart.
```

## Verdict: REQUEST_CHANGES

## Status table (F1-F12, N1-N3)

| ID | Title | Status after CP10 | Evidence |
|---|---|---|---|
| F1 | Allow-listed commands write protected files | PARTIALLY CLOSED (documented) | Not re-traced. R2 still provides an obfuscated command word that writer_cmd cannot see. |
| F2 | Hook wiring fails open | PARTIALLY CLOSED (documented) | Not re-traced. The timeout fail-open remains as documented. |
| F3 | Script-runner map bypasses | PARTIALLY CLOSED (documented) | Not re-traced. |
| F4 | sbt allow wildcards | CLOSED | Unchanged. |
| F5 | Ownership map not enforced for Bash | PARTIALLY CLOSED (documented) | Unchanged. |
| F6 | AWS mutation detection gaps | PARTIALLY CLOSED (documented) | Not re-traced. |
| F7 | Secret-file coverage | CLOSED for Read; Bash forms not verified | Not re-traced. |
| F8 | Read-only fence admits writers and executors | OPEN | R1: brace expansion and globs smuggle forbidden options past ro_check_opts and the find check. |
| F9 | `.gitkeep` exception | CLOSED | Unchanged. |
| F10 | settings.local.json | CLOSED | Unchanged. |
| F11 | smoke-test prompt docs | CLOSED | Unchanged. |
| F12 | Raw-string matching blocks evidence commands | PARTIALLY CLOSED | Observed this session: `git -C <path> diff --stat` is rejected as "git -c" (R4). |
| N1 | ANSI-C quoting hides syntax from the section 11 fence | CLOSED for read-only callers | Section 11 rejects `$`, `\`, `"`, LF and CR outside single quotes (:1060, :1082). Inside single quotes everything is literal. |
| N2 | Backslash-newline hides `$'...'` | CLOSED for the original input; OPEN for one variant (R2) | sc_bs_newline (:297-313) catches backslash-LF and backslash-CR outside quotes and inside double quotes. The launcher rule (:326-331) catches them in any quote state when a shell launcher word appears. A trailing backslash at the end of the string is not a continuation, so that case is fine. The scanner does not model comments or heredoc bodies, so an odd apostrophe desynchronizes it (R2). |
| N3 | Inner command strings not re-checked | CLOSED for the traced forms | wrap9 re-runs sc_hidden_quote at depth>0 (:751) and on every token containing `$` (:763). The launcher rule covers backslash-LF inside quoted `bash -c`, `eval`, `env -S` and `su -c` strings. It shares R2's desync only when no launcher word appears, and then there is no inner shell to fold anything. |

## New findings, ranked

### R1. CRITICAL: brace expansion (and globs) in the section 11 fence lets the read-only reviewer delete files and run arbitrary programs.

- **Where:** `.claude/hooks/guard-dangerous.sh:1082` (the rejected-character set) and `:246` (ro_check_opts).
  - The rejected set does not include `{`, `}`, `,`, `*`, `?`, `[` or `~`.
  - ro_check_opts treats any token that does not start with `-` as an operand.
  - The find check at `:1134` requires whitespace directly before `-delete`, `-exec` and the rest.
- **Concrete inputs** (each passes ro_split, then bash expands it):
  - `rg x {--pre=rm,.}` expands to `rg x --pre=rm .`. ripgrep then runs `rm <file>` for every file it searches. That deletes every non-hidden, non-ignored file in the repository, including CLAUDE.md, docs/agents.md and all of `modules/**`. No planted file is needed.
  - `rg x {--pre=bash,tests}` runs bash on every file under `tests/`. That is arbitrary execution of whatever a writer agent put there.
  - `find . {-delete,-print}` expands to `find . -delete -print`. The `:1134` regex sees `{-delete` and does not match, so find deletes the tree.
  - `sort {-o,modules/core/x} README.md` writes a file. sort's maxop is `*`.
- **Verified by:** tracing only, not executed (destructive).
- **Impact:** a fence widening and a data-loss path for code-reviewer and for every unregistered subagent. The sbt/permission layer is not involved, because these are plain rg, find and sort calls.
- **Residual risk 2 (planted files) is real and rated MAJOR as a sub-case:**
  - A writer agent can Write a file named `--pre=bash` or `-oCLAUDE.md` inside its own territory, for example `modules/core/--pre=bash`. The guard-ownership map allows that path.
  - An ordinary reviewer command such as `rg TODO modules/core/*` or `sort modules/core/*` then expands to the forbidden option.
  - Brace expansion makes the planting unnecessary, which is why R1 as a whole is CRITICAL.
- **Fix direction:**
  - In ro_split, reject `{`, `}`, `*`, `?`, `[`, `]` and a leading `~` outside single quotes. Alternatively, run the hook's check against the expanded words. Rejecting them is the fail-closed choice.
  - Make the find check token-based.
- **Owner:** factory-engineer. Also escalate to the human architect (CRITICAL data loss and fence widening).

### R2. CRITICAL: an odd apostrophe in a comment or heredoc body desynchronizes sc_bs_newline and sc_dollar_quote. This re-opens N2 for every non-read-only caller.

- **Where:** `.claude/hooks/guard-dangerous.sh:274-293` and `:297-313`.
  - Both scanners treat every `'` outside double quotes as a quote delimiter. Bash ignores a `'` inside a `#` comment or an unquoted heredoc body.
  - The launcher rule (`:326-331`) only helps when a launcher word appears.
- **Concrete input** (writer caller; LF shown as a newline):

  ```text
  true # it's
  $\
  '\x74ee' CLAUDE.md
  ```

  - The scanners open a quote at `it's`. They close it at the `'` before `\x74ee`, so the `$\<LF>` sits inside a quote they consider single-quoted.
  - Bash ignores the comment, folds `$\<LF>'\x74ee'` into `$'\x74ee'`, and runs `tee CLAUDE.md`.
  - The variant `cat <<EOF` with a body line `it's` followed by `EOF` works the same way. That is the author's residual risk 1.
  - No launcher word (bash, sh, eval, env, ...) appears, so `:326` does not fire.
- **Verified by:** tracing. Section 0 is traced; downstream, I rely on review-report-03's N2 trace (sections 9 and 10 do not see the obfuscated command word).
- **Impact:** a constitutional file can be written with no hint the guard can see, which bypasses a floor invariant. The Claude Code permission prompt is the only remaining layer. Read-only callers are not affected, because section 11 rejects LF (`:1060`).
- **Fix direction:**
  - Simplest and fail closed: reject any backslash immediately before LF or CR anywhere in raw, regardless of quote state, for every caller. That extends `:326-331` unconditionally.
  - Alternatively, reject `$` directly followed by a backslash anywhere.
- **Owner:** factory-engineer. Also escalate to the human architect.

### R3. MINOR: I could not assess the hook's runtime cost.

- The per-character loops (sc_dollar_quote, sc_bs_newline, ro_split, toks_of, and wrap9 running sc_hidden_quote on each `$` token at up to depth 4) all scale with command length. I did not measure them against the 20 s timeout, and a timeout fails open (F2).
- **Concrete input:** none is verified. A timing test on a roughly 50 KB command is recommended.
- **Owner:** factory-engineer (measure). This stays a documented gap if it is not measured.

### R4. MINOR (F12 usability): `git -C <abs-path> diff` is rejected as "git -c".

- **Where:** `.claude/hooks/guard-dangerous.sh:1101` lower-cases argv[1], and `:1138-1141` rejects it.
- **Observed this session:** `git -C '<repo>' diff --stat` was rejected as "git -c".
- **Impact:** the agent's working directory is reset between Bash calls, so the reviewer cannot run `git diff` against the repository unless the session cwd happens to be the repository. This blocks step 1 of the review procedure.
- **Fix direction:** allow exactly `-C <literal path>` before the subcommand, compared case-sensitively.
- **Owner:** factory-engineer.

## Recommendation for ratification

Do not ratify CP10 as it stands. After factory-engineer fixes R1 (reject brace and glob characters in the read-only fence, and make the find check token-based) and R2 (reject backslash-newline unconditionally), and a fresh reviewer pass confirms both, the human would be accepting exactly these:

- the documented partial closures F1, F2 (timeout fail-open), F3, F5, F6 and F7 (Bash forms);
- R3 (performance not measured) and R4 (usability), unless they are fixed;
- the Claude Code permission prompt as the remaining layer for Bash writes the ownership map does not cover.
