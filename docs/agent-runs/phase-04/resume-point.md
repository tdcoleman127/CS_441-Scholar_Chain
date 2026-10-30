# Phase 04: where to resume

Written by the orchestrator. Updated at the end of the 2026-10-29 session. Read this first in the next session.

## Part B status (2026-10-29 session)

- Part A is committed (8364c79; commit hash recorded in 52ac59b).
- Work order: `part-b-work-order.md`. hadoop-engineer built jobs 2, 3, 4+5, and 7: `hadoop-engineer-partb-report.md`.
- `sbt check` exit 0 (`run-evidence/partb-check1.log`): core 143/143, hadoop 44/44; SourcePipelineSuite really executed.
- **code-reviewer part B round 1: REQUEST_CHANGES** (`code-reviewer-report-partb-round1.md`):
  - F1 MAJOR: Job 7 buffers and sorts whole pair groups in memory (OOM on giant teams).
  - F2 MAJOR: topics and institutions not exported; topic ID-to-label linkage lost.
  - F3 to F5 MINOR: Crossref MISSING_KEY miscount; no failing test for Job 2 DUPLICATE/CONFLICT; blockstats lack provenance.
- Same session, later: the human accepted the 11 hadoop-local record types and asked for the blocking review and a quick repair (`human-decisions-2026-10-29.md`).
  - hadoop-engineer repair 1 fixed F1 to F5 (`hadoop-engineer-partb-repair1-report.md`). `sbt check` exit 0 at 11:49:28 PM: core 143/143, hadoop 50/50 (`run-evidence/partb-repair1-check.log`).
  - **code-reviewer part B round 2: APPROVE**, with 2 non-blocking MINOR findings (`code-reviewer-report-partb-round2.md`): no test covers Job 7's partitioner wiring; the repair report overstated one test (annotated).
  - The entity-resolution review (`entity-resolution-engineer-review-partb.md`) has 3 HIGH recall findings (chunking splits blocks by source, a suffix becomes the surname, name order) plus E-F11: ownership of the blocking keys and features is ambiguous, so it needs a factory-engineer amendment and human ratification. Open human decisions: E-F11 option a or b; whether to fix E-F1 to E-F3 and E-F6 before the part B gate or record them as known risks.
- Nothing from part B is committed. Untracked or modified: `Jobs.scala` and 6 new files under `modules/hadoop`, plus the part B files in this folder and `run-evidence/partb-*.log` (gitignored; use `git add -f`).

### Next steps for part B, in order

1. hadoop-engineer repair 1: F1 and F2 (F3 to F5 recommended), with feature tests and `sbt check`.
2. code-reviewer part B round 2.
3. Human decisions:
   - Accept or reject the 11 hadoop-local record types (report RISKS 1). If they are promoted to core, domain-engineer does it.
   - Whether entity-resolution-engineer reviews the block keys, the feature list, and chunking (680 of 861 pairs skipped in the largest sample block).
4. test-engineer adversarial tests (parts A and B), then the human gate for part B, then the commit.

## Part A status (2026-10-27 session; accepted since)

- **Phase 04 part A (Job 1 DBLP extraction, Job 6 teams and pairs): ready for the human gate.**
  - Both jobs have executed locally on native Windows (HADOOP_HOME=C:\hadoop). Counters INPUT 13, ACCEPTED 7, REJECTED 6, EMITTED_PAIR 285; max team 24 (SampleP25); largest expansion 276. Runs are byte-deterministic.
  - Repair 3 added RejectRecord v2 (input file base name, `rejects/v2/`), pinned counters (F2), blank-argument rejection (F3), and made LocalPipelineSuite fail instead of skip on Windows without HADOOP_HOME.
  - code-reviewer round 5: **APPROVE, no findings** (`code-reviewer-report-round5.md`).
  - Last `sbt check` (check4.log, 2026-10-27 17:11): exit 0; core 143/143; hadoop 27/27; LocalPipelineSuite really executed.
- **Git:** nothing committed yet. Untracked: `modules/hadoop/src/main/scala/edu/...`, `modules/hadoop/src/test/scala/edu/...`, `docs/agent-runs/phase-04/`. Constitutional files are at HEAD.
- **Human decisions so far:** `human-decisions-2026-10-27.md`.

### Reports in this folder, in order

1. `hadoop-engineer-report.md`, `code-reviewer-report-round1.md`, `hadoop-engineer-repair1-report.md`, `code-reviewer-report-round2.md`, `hadoop-engineer-repair2-report.md`, `code-reviewer-report-round3.md` (previous session)
2. `hadoop-engineer-run-report.md` (first real execution)
3. `code-reviewer-report-round4.md` (APPROVE, 3 MINOR)
4. `hadoop-engineer-repair3-report.md`
5. `code-reviewer-report-round5.md` (APPROVE)
6. Deferred amendment: `factory-engineer-amendment-report.md` (round-1 proposal) and `code-reviewer-report-amendment-round1.md` (REQUEST_CHANGES). The round-2 revision was stopped by the orchestrator at the human's request to save time; no patch was staged and the live files were confirmed at HEAD.
7. Evidence logs and checksums: `run-evidence/` (`*.log` is gitignored; commit with `git add -f`).

## Next steps, in order

1. **Human: write `human-gate.md` for part A** (decisions in `human-decisions-2026-10-27.md`; note the main-class amendment as deferred).
2. **Human: commit**, then fill in `commit.txt`:
   - `git add modules/hadoop docs/agent-runs/phase-04`
   - `git add -f docs/agent-runs/phase-04/run-evidence/*.log`
3. **Human: restart Claude Code** so sessions inherit HADOOP_HOME and PATH. Without them, `sbt check` fails by design on Windows.

## Deferred

- **Main-class run rule (factory-engineer).** hadoop-engineer.md requires a local bounded run; guard-dangerous blocks `sbt run`/`runMain`; plain `java` is unguarded. Restart from the round-1 proposal plus the amendment review (F1 to F7). Stage as a patch (phase-03 practice), never edit live.
- **Known guard gap (F1 of the amendment review):** quote-spliced or backslash sbt run tasks (`sbt 'hadoop/runM''ain ...'`, `sbt hadoop/ru\n`) get past the ratified guard. Only the permission prompt covers it: deny such prompts.
- **test-engineer** adversarial tests for part A.
- **Phase 04 part B (hadoop-engineer):** Job 2 external normalizers, Job 3 exact identifiers, Job 4 blocking (oversize-block counter, largest block), Job 5 candidate features, Job 7 graph export.
- **infra-engineer:** bounded EMR smoke-run script.

## Known risks to keep in view

- RejectRecord v2 uses the input file base name; same-named files in different directories would be ambiguous (v3 if part B needs it).
- Offsets are per file and, for compressed input, decompressed-stream offsets.
- Unsuffixed gzip is read raw; one mapper decompresses a whole `.gz`; the scanner does not handle XML comments or CDATA.
- Pair partition skew: 279 of 285 pairs on partition 0 (SampleP25).
- REJECTED includes `www` person records (UNSUPPORTED_TYPE separates them).

## Session notes for the orchestrator

- OneDrive can lock `modules/*/target`; pause sync if sbt reports `being used by another process`.
- guard-dangerous blocks any Bash command containing the literal test-marker task name, and greps containing `(env)` or `Test run`.
- Use literal paths (no shell variables) in any Bash command that touches `docs/agent-runs/**`.
