# Phase 03: next session (Round 4 repair and finish)

Written by the orchestrator at the end of the Round 3 session. The full record is in `agent-report.md` (Rounds 2 and 3). The Round 3 patch and its evidence are in `evidence/round3/`.

## Where things stand

- Done and verified:
  - **Datasets:** dataset-engineer's Round 2 manifest work: closed questions, HD-03-06 setting a DBLP limit of 1,200,000,000 bytes, the OpenCitations shard choice.
  - **Core fix:** the empty-"supported"-answer fix (code-reviewer finding 2).
  - **Sample cap:** the sample-extract cap fix (finding 3).
  - **Checks:**
    - `sbt check`: 143 passed, 0 failed.
    - Dataset tests: 108 passed, 0 failed.
    - Validator: 361 passed, 0 failed, 7 warnings.
- Not done: code-reviewer returned REQUEST_CHANGES on Round 3 (F1 to F6). **Do not apply `evidence/round3/phase-03-round3.patch` as-is.**
- **F1 is CRITICAL and affects the live configuration.** settings.json auto-allows `sample-extract.sh`, and its protected-path check does not normalize paths. An agent could run `--out .claude/./hooks/common.sh` and overwrite a hook with no prompt.
- Repair loops without an APPROVE: 2. If Round 4 also ends in REQUEST_CHANGES, docs/agents.md section 8 ("repeated gate failure") applies, and the human decides.

## Step 0 (human, before starting the session): close the live F1 gap

Choose one:

- (a) Edit `.claude/settings.json` yourself. Remove the `Bash(bash datasets/scripts/sample-extract.sh *)` allow rules (around lines 45 to 47), so every run asks you first. Record it under "Manual changes" in human-gate.md, then restart Claude Code.
- (b) Leave it as it is, and include the settings change in factory-engineer's Round 4 patch (the prompt below asks for it).

Until one of these is in place, deny any `sample-extract.sh` command whose `--out` points outside `datasets/samples/` or the scratchpad.

## Step 1: Round 4 repair prompt (paste into a fresh session)

```text
Phase 03, Round 4 repair. Read docs/agent-runs/phase-03/agent-report.md (Rounds 2 and 3) and
docs/agent-runs/phase-03/next-session.md first. Delegate in parallel; do no specialist work inline.

factory-engineer: revise the staged patch docs/agent-runs/phase-03/evidence/round3/phase-03-round3.patch
into a new patch staged in the scratchpad (do NOT edit live constitutional files). Fix code-reviewer
Round 3 findings F1-F5:
- F1 CRITICAL: the dataset-script protected-path check (guard-dangerous.sh:934 and R3-PROTWHOLE) uses
  a raw regex. Resolve every path-like argument with sc_classify_path (as operand9/9a do), so that
  `.claude/./hooks/x`, `.claude//hooks/x`, `docs/./agent-runs/x` and case variants are caught. Also
  remove or narrow the settings.json auto-allow for sample-extract.sh unless the hook alone is proven to
  block every input from the reviewer.
- F2 MAJOR: launchers that run a run-time-chosen program: `find ... -exec env {} ... --full`,
  `xargs -I % % ...`, `xargs -a args.txt env`. Treat WRAPPERS (env, nice, command, exec, timeout,
  stdbuf, flock, ...) and any -I/--replace string under xargs/parallel/find -exec as program selectors.
- F3 MAJOR: coproc, trap '<cmd>' <sig>, and flock forms never reach dataset_script_check. Model them
  (re-parse trap strings; add flock to section 10 WRAPPERS). Also handle case variants such as `Fetch.sh`,
  given the case-sensitive settings deny rule.
- F4 MINOR: R3-CMDWORD blocks `case a in b) ... ;; *) ... ;; esac`. Remove the over-blocking, or
  document it and have the human ratify it.
- F5 MINOR: make the docs/agents.md 7b text match what OUTFULL really blocks (extensionless copies), or
  extend OUTFULL to every command word that resolves outside the repository.
Add a probe for every failure input in the Round 3 review (in agent-report.md). Rerun the whole probe
set (141 or more; the proposal must have 0 unexpected), one mutation per new rule, the validator,
bash -n, the JSON parse, and git apply --check against the live tree. Report the patch path, the
baseline and proposal summaries, residual risks, and the ratification asks.

dataset-engineer: fix code-reviewer Round 3 F6 in datasets/scripts/sample-extract.sh. On non-Windows
hosts a backslash in --out is a filename character, but resolve_out treats it as a separator, so the
cap is skipped. Refuse a backslash in --out on non-Windows hosts, or resolve and write the same
normalized path. Write the test first and show it fails, then fix. Run validate-datasets.sh,
record-sample-checksums.sh --check, and run-tests.sh.

Orchestrator: when both report, copy the factory evidence to docs/agent-runs/phase-03/evidence/round4/,
run git apply --check on the new patch, rerun the dataset checks, run sbt check, and append a Round 4
section to agent-report.md. Then run a fresh code-reviewer on the whole phase-03 change set, including
the round4 patch, with every reviewer failure input from Round 3 rechecked. Stop and present the
verdict and the ratification asks to the human.
```

## Step 2: finish Phase 03 (after code-reviewer returns APPROVE)

1. **Ratify.** Fill in human-gate.md: a Round 3/4 section with the ratification decisions for the round4 patch (rule groups, the copy fence, the global narrowings, and the accepted residual risks).
2. **Apply and validate:**
   ```bash
   git apply docs/agent-runs/phase-03/evidence/round4/<patch>.patch
   bash .claude/hooks/validate-agent-files.sh
   ```
3. **Restart Claude Code.**
4. **New session, after the restart:**
   - factory-engineer reruns the probe harness against the applied hooks (expected: 0 unexpected);
   - the orchestrator runs `sbt check` and the dataset checks;
   - code-reviewer gives a final pass on the applied files.
5. **Fill in human-gate.md:**
   - Reviewed: validator, probe rerun, code-reviewer verdict, sbt check;
   - Decision;
   - Manual changes: both `git apply` runs, plus any settings.json edit from Step 0;
   - Remaining risk: R03-3, plus the residual risks accepted in the ratification.
6. **Commit** (from human-gate.md, with `docs/agent-runs/phase-03` already covering `next-session.md` and `evidence/round3`/`round4`):
   ```bash
   git add .gitattributes CLAUDE.md docs/agents.md .claude/settings.json .claude/agents .claude/hooks datasets modules/core/src docs/agent-runs/phase-03
   git status --short   # confirm no target/ dirs or settings.local.json are staged
   git commit -m "Phase 03: dataset-engineer and domain-engineer created data contracts and dataset manifest; factory-engineer amendments ratified, Round 4: ACCEPTED"
   ```
7. **Record the commit:** write the hash into `commit.txt` and human-gate.md, then:
   ```bash
   git add docs/agent-runs/phase-03/commit.txt docs/agent-runs/phase-03/human-gate.md
   git commit -m "Phase 03: record accepted commit: <hash>"
   ```

Note: `evidence/round3/mut/` holds deliberately mutated copies of the hooks. They are inert evidence and must never be copied back into `.claude/hooks/`.
