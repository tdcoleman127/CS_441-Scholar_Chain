# factory-engineer: main-class run rule amendment, round 2 (Phase 04 closeout item 1)

Assembled by the orchestrator, 2026-11-01, from factory-engineer's final report.

**Orchestrator checks:**
- The patch is preserved at `run-evidence/amendment-r2.patch`; its sha256 `046bdc651e5eb083f2af2af0804304913c83ae867cda354541ed0178471b5c9d` matches the report.
- `git status --porcelain -- CLAUDE.md docs/agents.md .mcp.json .claude` is empty: the live constitutional files are untouched.
- Nothing has been applied or ratified.
- The harness flagged settings.json content in the agent's output. That is the proposed timeout change (R5), recorded below as a proposal only.

**STATUS: COMPLETE (staged; awaiting code review and human ratification).** Time box overrun (about 46 minutes).

## Summary

The round-1 vehicle (`scripts/run-local-main.sh`, W1, W2, S1 to S9) is withdrawn. As the human asked, the rule is one guard-checkable form of plain `java`.

- **R7C-MAIN.** hadoop-engineer only. `java [-Xmx/-Xms] -cp "<classpath>" <main-class> <args> [redirects]`, as one simple command from the repository root.
  - Main classes: DblpPipelineMain (output base is argument 3) and SourcePipelineMain (argument 1).
  - Classpath: `modules/{core,hadoop}/target/scala-*/classes` plus Coursier cache jars.
  - Inputs: existing paths strictly inside `datasets/samples/`.
  - Output base and redirects: the scratchpad or temp only.
  - The guard returns **ask** on every passing run.
- **R7C-JVM.** Every other direct JVM or cluster launch (java, javaw, jshell, hadoop jar, spark-submit, scala-cli, mvn, gradle, and others) is blocked for every caller, the orchestrator included. Version queries pass.
- **sbt fence (F1).** A second scan builds the words bash builds, catching quote-spliced and backslash spellings, colon scopes, kebab-case, `alias`, and `s''bt`. `sbt run` and `runMain` stay blocked.
- **Timeout.** The Bash guard hook timeout goes from 20 s to 60 s in settings.json; the ratified guard already takes about 20.6 s on a long literal classpath.

## F1 to F7 (round-1 review) resolution

| Finding | Resolution |
|---|---|
| F1 MAJOR: sbt fence beaten by quote splicing or backslash | Fixed (two-pass scan). `sbt 'hadoop/runM''ain ...'` and `sbt hadoop/ru\n` are BLOCK on the final guard |
| F2 MAJOR: unratified change was live | Fixed (process): staged only; `git apply` tested in a throwaway repo, byte-identical, LF |
| F3 MINOR: launcher via expansion or variables | Closed for R7C-MAIN (literal `java`, no `$`, no prefix, no `@argfile`). Remaining forms are documented as RR-14 and RR-15. `J=java; $J -cp a M` is ALLOWED (known gap RR-14) |
| F4 MINOR: ask came from a raw-text regex | Fixed: the ask comes from the `LM_OK` flag set by `lm_main_check` |
| F5 MINOR: false positives | Fixed: quoted argument strings are not scanned, assignments are skipped, launchers count only in command position |
| F6 MINOR: section 7c text disagreed with the guard | Fixed: inputs strictly in samples; per-class output-argument table; options blocked as program arguments |
| F7 MINOR: S1 to S9 gaps | Moot (script withdrawn); concerns re-homed (offline schemes blocked, no cd, a role-file `git status` instruction) |

## Files in the patch (none live)

- `.claude/hooks/guard-dangerous.sh` (+408)
- `.claude/hooks/validate-agent-files.sh` (+18)
- `.claude/agents/hadoop-engineer.md` (1 line)
- `.claude/agents/spark-engineer.md` (1 line)
- `docs/agents.md` (+49: section 5 row, "Three rules" line, new section 7c, section 12 list)
- `.claude/settings.json` (timeout 20 → 60)
- Not touched: CLAUDE.md, .mcp.json, commands, common.sh, guard-ownership.sh, verify-tests-ran.sh, format-scala.sh.

## Privilege delta

| Item | Kind | Change |
|---|---|---|
| W1 | Widened | hadoop-engineer may run the R7C-MAIN form, with an ask each time. Plain `java` was previously unguarded for every caller, so mechanically this also narrows. |
| N1 | Narrowed | JVM launchers blocked for all callers |
| N2 | Narrowed | sbt fence closure |
| N3 | Narrowed | glob, variable, and command-substitution launcher command words blocked |
| N4 | Narrowed (instruction) | spark-engineer.md: local sample runs go through `sbt check` tests or MANUAL ACTION REQUIRED |
| Config | Strengthened | guard timeout 60 s |

- **Unchanged:** tools lines, ownership, MCP, allow and deny rules.
- **Withdrawn from round 1:** run-local-main, the spark binding, the settings ask rules, the infra-engineer.md edit, and the generic JVM option fence.

## Floor invariants

The dangerous-command guard is intact and strengthened. The test-stop hook, formatter, secret-read denies, terraform apply and destroy denies, destructive AWS denies, and force-push denies are intact.

## Audits and checks

- **Audits:** collision, orphan, and one-writer all PASS. The validator negative test fails as intended.
- **Checks:** JSON PASS; `bash -n` on 6 hooks PASS; executable bits 100755; frontmatter (18 agents) PASS.
- **Validator on the patched copy:** exit 0, 96 PASS, 0 FAIL.

## Probe results

| Suite | Result |
|---|---|
| cases-main (43) | 42 match; 1 was a wrong expectation, retested in main2 |
| cases-main2 (10) | 10/10 |
| cases-fence (58) | 57 match; 1 real bypass (`"$(which java)" -cp a M`) found and fixed |
| Final-guard regression subset (18) | 18/18 |

**Caveat:** the three full suites ran on intermediate guard revisions. Only the 18-case subset ran on the final patch. **A full rerun against the final patch is required.**

- **Timing:** allowed run with a 2.5 KB classpath takes 20.6 s on the ratified guard and 21.4 s patched. How Claude Code handles a hook timeout (fail open or closed) is unverified.

## Ratification request (human decides each)

| Item | Decision requested |
|---|---|
| R1 | R7C-MAIN for hadoop-engineer |
| R2 | R7C-JVM fence for all callers |
| R3 | sbt fence closure (F1) |
| R4 | spark-engineer.md text |
| R5 | guard timeout 20 → 60 s |
| R6 | residual risks RR-12 to RR-16, each separately; RR-12 is the largest: the main class is not fenced once the JVM starts |
| R7 | withdraw the round-1 proposal |

**Process after ratification:**
1. `git apply` the patch from the repository root.
2. Confirm `git diff --stat` lists the 6 files.
3. Run `bash .claude/hooks/validate-agent-files.sh`.
4. Record the decision in human-gate.md.
5. Commit and restart.

Until then the ratified configuration governs, and hadoop-engineer does no local main-class run.

## Risks

- RR-12 to RR-16.
- The hook-timeout behavior is unverified.
- Guard latency on long commands is about 21 s.
- Only 18 probe cases ran on the final revision.
- Hadoop local staging goes to `C:\tmp\hadoop-<user>`.

## Next gate

1. Full probe rerun on the final patch.
2. code-reviewer review of the patch.
3. Human ratification, apply, validator, restart.
4. hadoop-engineer local bounded run.

## Addendum: full probe rerun on the final patch (2026-11-01)

Reported by factory-engineer after the orchestrator's follow-up; the patch was not modified (sha256 unchanged; final guard sha256 e07900ae4d71c15ca906408cdbda8b8c367257e6d249e6305b57398b69820163).

| Suite | Cases | Match | Mismatch |
|---|---|---|---|
| cases-main | 43 | 42 | 1 |
| cases-main2 | 10 | 10 | 0 |
| cases-fence | 58 | 58 | 0 |
| cases-rerun | 18 | 18 | 0 |
| cases-subst | 5 | 5 | 0 |
| Total | 134 | 133 | 1 |

- The one mismatch (cases-main.txt line 36, scratchpad root as output base) is a case-file expectation error: ASK is correct under section 7c rule 5, and cases-main2 line 1 tests the same command correctly.
- RR-14 (`J=java; $J -cp a M`) is still allowed, as recorded.
- **Orchestrator note:** these suites predate code-reviewer round 2 and contain no probes for the N1 to N5 forms (`code-reviewer-report-amendment-round2.md`). "No real bypass appeared" covers only the existing cases. The amendment remains deferred (human decision 6); round 3 must add N1 to N5 probes.
- Raw results (scratchpad, not preserved): `amendment-r2/final-res-*.txt`.
