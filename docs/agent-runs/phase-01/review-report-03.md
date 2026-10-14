# Phase 01: repair-01 final review (code-reviewer, checkpoint 9)

The orchestrator assembled this file from code-reviewer's final report, verbatim. code-reviewer has no Write tool.

Work order: a fresh, independent, read-only review of the cumulative repair-01 change set after CP7 and CP8. Deadline 23:45 CDT, 2026-10-13. The agent's `date` readings start at 23:27:43; the report arrived about 23:31. Per the work order, no bypass was executed. Every new finding is verified by tracing.

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 01, repair-01 final review (checkpoint 9). Fresh, read-only review of the cumulative repair-01 change set (git diff HEAD to working tree: 6 files, 885 insertions, 85 deletions, the same as docs/agent-runs/phase-01/repair-01-wip-cp8.patch). Priorities: N1 closure, soundness of CP8 section 0 (sc_dollar_quote), any widening or fail-open path from CP7/CP8, and the F1-F12 + N1 status table.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN:
- `date` (23:27:43, 23:29:23, 23:30:10 CDT): succeeded
- `git status --short` and `git diff --stat`: succeeded (6 modified files, 885 insertions and 85 deletions, as the work order says)
- `grep -n` for section markers in review-report-02.md and guard-dangerous.sh: succeeded
- `sed -n ...` on guard-dangerous.sh: BLOCKED by my fence (sed is not allowlisted). I switched to the Read tool.
- `date; grep -n "segment_writes()" -A12 ...`: BLOCKED by my fence. The quoted `(` split the segment, which is the known F12 fail-closed behaviour. I switched to the Grep tool.
- Read/Grep tool calls: guard-dangerous.sh lines 40-200, 260-305, 600-830, 990-1100; settings.json patterns.
TEST RESULTS: not run. No Scala or test sources are in the change set. I did not run sbt.
VALIDATION RESULTS: not run. I did not run `bash .claude/hooks/validate-agent-files.sh` (factory-engineer's gate) or `bash -n` in this pass. The CP7 and CP8 validator output claims in repair-01.md are unverified by me.
ASSUMPTIONS:
- bash removes an unquoted backslash-newline (line continuation) from the input before it recognises tokens, so `$\<LF>'...'` lexes as `$'...'`. This is documented bash behaviour (Bash manual 3.1.2.1, "Escape Character"). Per the work order, I did not run the bypass to prove it.
- The Claude Code permission layer prompts for `bash -c ...`, because no allow rule matches it (settings.json:24-96 allow only `bash -n *` and named scripts). Whether a permission prompt appears for the N2 form `echo $\<LF>'...'` depends on Claude Code's own command parsing, which I did not verify.
RISKS:
- Hook timeout (20 s) still fails open (F2 residual). CP7/CP8 add three more per-character bash loops over the full raw command: sc_dollar_quote, ro_mask called twice more, and ro_split. Each loop is linear, but bash substring indexing is slow on very large inputs, such as a heredoc of 100 KB or more. I did not measure it. It is a residual risk, not a verified finding.
- F7 Bash forms (`cat ~/.sbt/.credentials` and similar) are still untraced.
MANUAL ACTION REQUIRED: Human architect: do not ratify repair-01 as it stands. N2 re-opens N1 (a read-only fence bypass) with a one-character variant, and it also defeats CP8 section 0 for every caller. N2 widens a tool fence and weakens a floor invariant, so it goes to you as well as to factory-engineer.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: factory-engineer repairs N2 and N3 (repair-01 CP10 or repair-02), then a fresh code-reviewer pass, then human ratification and restart.
```

## Verdict: REQUEST_CHANGES

## F1-F12 + N1 status (updated from review-report-02.md)

| ID | Title | Status at CP9 | Evidence |
|---|---|---|---|
| F1 | Allow-listed commands write protected files | PARTIALLY CLOSED (documented) | Unchanged since review 02. N2/N3 add a new indirect route: an obfuscated command word that writer_cmd (guard-dangerous.sh:151-169) cannot see. |
| F2 | Hook wiring fails open | PARTIALLY CLOSED (documented) | The wrappers are unchanged (settings.json:244/250/256). Timeout fail-open remains. |
| F3 | Script-runner map bypasses | PARTIALLY CLOSED (documented) | Not re-examined in this pass. No CP7/CP8 change touches it. |
| F4 | sbt allow wildcards | CLOSED | Unchanged. |
| F5 | Ownership map not enforced for Bash | PARTIALLY CLOSED (documented) | Unchanged. |
| F6 | AWS mutation detection gaps | PARTIALLY CLOSED (documented) | Unchanged. |
| F7 | Secret-file coverage | CLOSED for Read; Bash forms not verified | Not re-traced. |
| F8 | Read-only fence admits writers and executors | OPEN | CP7 closed the literal `$'`/`$"` form, but N2 (line continuation) restores the N1 capability. |
| F9 | `.gitkeep` exception | CLOSED | Unchanged. |
| F10 | settings.local.json | CLOSED | Unchanged. |
| F11 | smoke-test prompt docs | CLOSED | Unchanged. |
| F12 | Raw-string matching blocks evidence commands | PARTIALLY CLOSED | Quoted `(` still splits segments and fails closed. Observed again this session: `grep -n "segment_writes()" ...` was rejected. |
| N1 | ANSI-C quoting hides redirects and separators from the section 11 fence | PARTIALLY CLOSED; the bypass class is still OPEN through N2 | CP7 blocks the literal `$'` and `$"` (guard-dangerous.sh:1051), and an unbalanced mask (:1052). Literal N1 inputs, `$"..."`, and `\'` outside quotes are now caught. An open `''` concatenation is caught by :1052. The backslash-newline variant (N2) is not caught. |

## New findings, ranked

### N2. CRITICAL: backslash-newline between `$` and the quote hides ANSI-C quoting from section 0, section 11 (:1051), ro_mask, and toks_of. This re-opens N1 and defeats CP8 for every caller.

- **Files:** .claude/hooks/guard-dangerous.sh:274-296 (sc_dollar_quote), :1051 (raw `case`), :1017-1037 (ro_mask), :57-61 (cmd/cmd_nr/segments do no line-continuation folding).
- **Mechanism (traced):** bash deletes an unquoted `\<LF>` before it lexes, so `$\<LF>'...'` becomes `$'...'`. The guard models `\` as "escape the next character", and here that character is the LF:
  - sc_dollar_quote :287 skips the LF. At :288 the `$` has `nx` = `\`, which is not a quote, so the scan returns 1 and section 0 passes.
  - :1051 looks for the substring `$'`. The raw text holds `$\<LF>'`, so this check passes too.
  - ro_mask :1030 writes `__` for `\<LF>` and then treats `'\'` as an ordinary single-quoted string. So it masks whatever text bash actually sees as unquoted.
- **Concrete input 1** (code-reviewer, fence bypass, the same class as N1). The Bash command is the two lines:

  ```text
  echo $\
  '\'' > out.txt; sed -n 1p CLAUDE.md #'
  ```

  - Bash runs `echo $'\''`, which prints `'`. It then redirects to out.txt, runs `sed`, and treats the trailing `#'` as a comment.
  - The guard sees no `$'` and balanced quotes. ro_mask hides ` > out.txt; sed ... #`, so neither the `>` check (:1053) nor ro_split finds a redirect or a segment break. The only segment's command word is `echo`, which is allowlisted at :1065.
  - Sections 1-10 do see the raw `;`, but `out.txt` and `sed -n` are not protected writes.
  - **Impact:** code-reviewer (and any unregistered subagent) can write arbitrary files and run arbitrary programs. That is exactly the N1 impact.
- **Concrete input 2** (any non-factory caller with Bash, against a floor invariant). The command `$\` followed on the next line by `'\x74ee' CLAUDE.md </dev/null`:
  - Bash runs `tee CLAUDE.md` and truncates the constitution.
  - Section 0 misses it, as traced above.
  - Section 9: `segments` (:61) splits on the LF, so the second segment is `'\x74ee' claude.md`. words_of strips the quotes and leaves the command word `\x74ee`, which writer_cmd (:159-160) does not recognise, so segment_writes (:142-148) returns false. wrap9/prot9 (:707-714) take the same path.
  - No settings.json deny matches. Only the permission prompt stands between the agent and the write, and it shows an obfuscated command.
- **Verified by:** tracing only (per the work order, no execution).
- **Owner:** factory-engineer (.claude/hooks/*). Human architect escalation: it widens a tool fence and weakens a floor invariant.
- **Suggested direction (the owner decides):** fold `\<LF>` (and `\<CR><LF>`) out of `raw` before every scan (sections 0-11), or have sc_dollar_quote and :1051 treat `$` followed by any run of `\<LF>` and then a quote as a hit.

### N3. MAJOR: the author's residual risk 2 is a real guard bypass to a constitutional file. Section 9b's unwrapping does not re-run section 0 on the inner command string.

- **Files:** .claude/hooks/guard-dangerous.sh:707-722 (wrap9 recurses into whitespace tokens but calls only prot9 and its own checks), :294 (sc_dollar_quote runs once, on the outer raw text).
- **Concrete input:** `bash -c '$'"'"'\x74ee'"'"' CLAUDE.md' </dev/null` from any agent other than factory-engineer.
  - Outer scan: `$` is inside single quotes, and `'` is inside `"'"`, so sc_dollar_quote returns 1.
  - toks_of builds the inner token `$'\x74ee' CLAUDE.md`, and wrap9 recurses into it (:722). prot9 then calls segment_writes. words_of leaves the command word `$\x74ee`, which is not a writer, so nothing blocks.
  - sc_dollar_quote is never applied to that inner string, even though it contains a literal `$'`. The inner bash decodes it to `tee CLAUDE.md`.
- **Mitigation:** no allow rule matches `bash -c` (settings.json:24-96), so the human gets a permission prompt. code-reviewer is stopped at section 11 because `bash` is not allowlisted. That is why this is MAJOR, not CRITICAL.
- **Why it is not an acceptable documented gap:** the fix is cheap. Call sc_dollar_quote on `str` at the top of wrap9 when depth > 0. That one change also covers `eval '...'` and `su -c` literals that toks_of unwraps.
- **`printf %b` / `eval "$(...)"` forms:** an `eval` of runtime-generated text cannot be verified statically. For read-only agents, section 11 blocks `$(` (:1053). For others, it relies on the prompt and remains an acceptable documented gap.
- **Verified by:** tracing.
- **Owner:** factory-engineer.

### Checked and not findings

- `\'` outside quotes, `$"..."`, an unclosed `''` concatenation, and `$` before a closing `"` (`grep -E "x$"`) are handled as the CP8 comment at :268-273 states. The `\"` case inside double quotes (:283) is handled.
- Heredoc bodies are scanned as raw text. This fails closed, which can over-block but cannot widen access.
- No unbound-variable or non-2 exit path is introduced in :274-296 or :1051-1052. `${s:i+1:1}` and `${s:i+1:2}` past the end of the string expand to empty, so they are safe under `set -u`.

## Recommendation for ratification

Do not ratify yet. After factory-engineer fixes N2 (folding backslash-newline before every scan) and N3 (re-running sc_dollar_quote inside wrap9 at depth > 0), and a fresh reviewer pass confirms both, the human would be accepting exactly this:

1. The documented partial closures of F1, F2 (including the hook-timeout fail-open), F3, F5 and F6.
2. F7 closed for the Read tool only, with the Bash forms untraced.
3. F12's quoted-`(` over-blocking, which fails closed.
4. Runtime-generated command text (`eval "$(...)"`, `printf %b | bash`) being guarded only by the permission prompt for agents that are not read-only.

F4, F9, F10 and F11 are closed. Everything else in CP7 and CP8 that I examined is sound.
