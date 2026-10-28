# code-reviewer: Phase 04 part A, round 4 (run evidence and post-round-3 changes)

Assembled by the orchestrator, verbatim in substance from the agent's final report, 2026-10-27.

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 04 part A, round 4 (step 3 of docs/agent-runs/phase-04/resume-point.md). Review the run evidence and hadoop-engineer's changes since round 3 (Jobs.scala, LocalPipelineSuite.scala, DriverArgsSuite.scala).
FILES CREATED: none
FILES MODIFIED: none. My `sbt check` run regenerated `.claude/.last-test-run` through the test-marker task.

VERDICT: APPROVE

The jobs ran, the logs back up every number in the report, and the expected values are correct for the sample data. No CRITICAL or MAJOR issues. 3 MINOR findings. F1 needs the human before the commit. F2 and F3 can be fixed now or in part B.

FINDINGS (ranked)

F1. MINOR. The cited evidence logs are gitignored, so a normal commit leaves them out.
- File: .gitignore:14 has `*.log`, which matches every file in docs/agent-runs/phase-04/run-evidence/ except the checksums.
- Failure input: `git status --porcelain --ignored --untracked-files=all docs/agent-runs` lists `!! check1.log`, `!! check3.log`, `!! runA.log`, `!! runC.log`; only the four .sha256 files show as `??`. `git add docs/agent-runs/phase-04` commits the reports without the logs.
- Impact: check3.log is the only proof that LocalPipelineSuite executed and produced the counters.
- Related gap: check2.log (scalafmt failure; law 7 negative result) and runA-2.log (skipped=true idempotency rerun) were only in the scratchpad.
- Owner: the human (docs/agent-runs/**). Either `git add -f` the logs, or store copies under a non-ignored extension. For future phases, build-engineer could add `!docs/agent-runs/**/*.log` to .gitignore.

F2. MINOR. The integration test does not pin the pair count, so losing pair emission would still pass.
- File: modules/hadoop/src/test/scala/edu/uic/cs441/scholarchain/hadoop/LocalPipelineSuite.scala:72 compares the pairs line count with the EMITTED_PAIR counter only.
- Failure input: delete Jobs.scala:178-181 (the `Teams.pairsFor(env).foreach` block in TeamReducer). The pairs directory is never created, `lines(...)` at :13 returns 0, the counter is 0, `assertEquals(0L, 0L)` passes; the determinism check at :79 compares two empty lists. TeamsSuite tests `pairsFor` but not the reducer.
- Impact: a broken pair-emission path in the reducer would ship with a green sbt check.
- Fix: assert observed values: EMITTED_PAIR == 285, AUTHOR_PAPERS == 35, OVERSIZE_TEAM == 4 at threshold 2, and 6 rejects split 1 missing-key / 3 unsupported / 2 schema failures.
- Owner: hadoop-engineer.

F3. MINOR. An empty output base passes parseArgs and the job writes at the filesystem root.
- File: Jobs.scala:361-380 checks only arity, snapshot, run ID and integers; outputPath at Jobs.scala:212 builds `s"$base/run=..."`.
- Failure input: `DblpPipelineMain in.xml dblp.dtd "" snap run:v1:<64hex>` (a quoted but unset "$OUT"). Path becomes `/run=.../snapshot=snap/dblp-extract`: C:\run=... locally, hdfs:///run=... on EMR. The doc comment at :345-346 says a bad vector fails before any job runs.
- Impact: output lands in an unexpected place instead of exit 2. Nothing is overwritten (_SUCCESS guard).
- Fix: reject blank positional arguments in parseArgs.
- Owner: hadoop-engineer.

ANSWERS TO THE SPECIFIC CHECKS

1. Logs match the report. check3.log ends in [success], no [error]/[warn]; core Passed 143; hadoop Passed: Total 19, Failed 0, Errors 0, Passed 19; DriverArgsSuite 4, RepairSuite 5, DblpParserSuite 6, TeamsSuite 3. LocalPipelineSuite really executed: `IT counters` and `IT framework` lines printed, 6.456 s run time, no `==> s ... skipped`, no Skipped count. Caveat: the per-suite line `0 failed, 0 ignored, 1 total` alone does not prove execution; a skipped run prints the same line. Counters (check3.log, runC.log): Job 1 INPUT=13, ACCEPTED=7, REJECTED=6 (MISSING_KEY=1, UNSUPPORTED_TYPE=3, SCHEMA_FAILURE=2); Job 6 EMITTED_PAIR=285, AUTHOR_PAPERS=35, TEAMS=7; OVERSIZE_TEAM 4 in the test (threshold 2), 0 in runC (threshold 50). Checksums: across runA/B/C/D.sha256 all 16 lines occur exactly 4 times. runC directory counted directly: main 3|4, works 3|4, mentions 28|7, rejects 4|2; teams 3|4, authorpapers 28|7, pairs 279|6; pairs per work 276/3/3/3; 6 reject records match; runA-2.log shows skipped=true; no runX directory.

2. Expected values are correct, counted by hand. dblp-sample.xml 10 records + dblp-malformed.xml 3 = 13. 7 accepted: CarberryMS24 3, abs-2401-99901 3, SampleIP23 3, SampleP25 24 (3 named + Collab Member 01-21), Sample00 1, conf/scsample/2024 0 (editors only), Good24 1. 6 rejected: keyless article, 3 www, BadEntity24 (undeclared entity), Truncated24. Pairs 276+3+3+3 = 285; team sizes sum to 35. `grep -b` puts opening tags at 3859, 4031, 4285, 4454 and 643, 812, matching reject offsets.

3. parseArgs: <5 or >7 rejected before indexing; snapshot and run ID through existing validators; posInt rejects 0, negatives, non-numbers, Int overflow; errors lead to sys.exit(2). Any in-range shift moves the run ID out of position 4 and is caught. No defect other than F3. frameworkCounters drops the ScholarChain group by enum class name (confirmed in runC); group-prefixed keys cannot collide; skipped run gets the empty default (LocalPipelineSuite.scala:76). DriverArgsSuite genuinely fails without the fix: removing `> 7` sends the 8-arg vector to the snapshot check, failing the assertion at DriverArgsSuite.scala:39; removing `< 5` makes good.take(4) throw at args(4). Fix is in place at Jobs.scala:367.

4. Process: neither item is a finding against this change. The java @argfile run: the role file (.claude/agents/hadoop-engineer.md:22) and prompt.md:52 require a local bounded run; guard-dangerous.sh:455 blocks sbt run/runMain and says to report main-class needs as MANUAL ACTION REQUIRED. The rules contradict each other; the agent ran its own code, wrote only to the scratchpad, and disclosed it. Route to factory-engineer: sanction a local-run path (e.g. a section-7-mapped script by infra-engineer) or forbid it explicitly. Plain `java -cp` also escapes the guard's run-code block for every agent. Scalafmt bypass: check2.log shows the failure, sbt fmt fixed it, check3 and my run passed scalafmtCheckAll.

5. LocalPipelineSuite without HADOOP_HOME: not silent (MUnit prints `==> s ... skipped`, summary says Skipped 1), but sbt check still exits 0 and writes the test marker. Acceptable for the part A gate because check3.log shows a real run. Needs routing (human decision): recommended infra-engineer Linux CI workflow under .github/workflows/**; alternatively hadoop-engineer adds an opt-in env var that turns the skip into a failure.

COMMANDS RUN
- git status --porcelain --untracked-files=all, and with --ignored on docs/agent-runs.
- Read the three changed hadoop files, TeamsSuite.scala, resume point, round-3 report, run report, check1/check3/runA/runC logs, four .sha256 files, scratchpad check2.log and runA-2.log, both sample XML files, build.sbt, .gitignore, hadoop-engineer role file.
- cat of the .sha256 files | sort | uniq -c: 16 distinct lines, each x4.
- grep -b -n for record opening-tag offsets; grep -c / grep -o on scratchpad runC output.
- ls of scratchpad and %TEMP%: no sc-hadoop-it* directories remain.
- sbt -batch check with no prefix: success.
- The fence blocked the HADOOP_HOME=... PATH=... prefix, so LocalPipelineSuite was not rerun by the reviewer; relied on check1.log and check3.log.

TEST RESULTS (reviewer's sbt -batch check, 4:52 PM, no HADOOP_HOME): every step [success]; core Passed 143/143; hadoop Passed: Total 19, Failed 0, Errors 0, Passed 18, Skipped 1; DriverArgsSuite 4/4; LocalPipelineSuite skipped as expected.

VALIDATION RESULTS: every expected value checks out against the sample data and runC output. Runs A-D byte-identical.

ASSUMPTIONS: run-evidence/ files are faithful copies of the scratchpad originals (scratchpad check3.log 10703 bytes and runC.log consistent). The leftover sc-hadoop-it* temp directories are already gone; who deleted them was not determined.

RISKS (not findings): determinism runs all used the same run ID, snapshot and reducer count. Pair partition 0 holds 279 of 285 pairs (SampleP25 skew). RejectRecord v1 has no file name (decision before part B). Round-3 risks still stand: decompressed-stream offsets, unsuffixed gzip read raw, one mapper per .gz.

MANUAL ACTION REQUIRED:
- Human, before the commit: commit check1.log, check3.log, runA.log, runC.log (F1); consider preserving check2.log and runA-2.log.
- Human: decide how the local main-class run is sanctioned (factory-engineer) and whether a Linux CI runner should guarantee LocalPipelineSuite runs (infra-engineer).

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: route F2 and F3 to hadoop-engineer (MINOR, non-blocking); human commits evidence (F1); human decides pair/team contracts and RejectRecord v2; human writes human-gate.md and commits.
```

Orchestrator note: after this report, check2.log and runA-2.log were copied from the scratchpad into run-evidence/.
