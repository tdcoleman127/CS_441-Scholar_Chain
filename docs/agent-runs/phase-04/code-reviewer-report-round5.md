# code-reviewer: Phase 04 part A, round 5 (hadoop-engineer repair 3)

Assembled by the orchestrator from the agent's final report, 2026-10-27.

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 04 part A, round 5. Re-review hadoop-engineer repair 3: F2 and F3 from round 4, RejectRecord v2 (human decision 4), and the skip-to-failure change in LocalPipelineSuite (human decision 6).
FILES CREATED: none
FILES MODIFIED: none

VERDICT: APPROVE

FINDINGS: none. No CRITICAL, MAJOR or MINOR issue verified against the code with a concrete failing input.

1. F2 and F3 fixed; tests fail without the fixes.
- F2: TeamReducer.process (Jobs.scala:222-238) is pure and runs without a cluster; the reducer (Jobs.scala:191-200) is a thin binding. TeamReducerSuite pins EMITTED_PAIR 285, AUTHOR_PAPERS 35, TEAMS 7, OVERSIZE_TEAM 4 at threshold 2, and 276 pairs for the largest work, on every platform. LocalPipelineSuite.scala:62-65 and :100 pin the same end to end. revert-f2.log: TeamReducerSuite:42, :70, :80 and LocalPipelineSuite:65 fail ("Failed: Total 5, Failed 4"). Deleting the process(...) call itself is caught only by LocalPipelineSuite, which can no longer skip.
- F3: Jobs.scala:425 and :431-435 reject any blank argument after the arity check; main exits 2. revert-f3-a.log fails at DriverArgsSuite.scala:57 (i=0 b='' Right(Args(,in/dblp.dtd,...))).
- Current Jobs.scala is byte-identical to scratchpad/repair3-fixed/Jobs.scala (git diff --no-index empty): restore after each revert confirmed.

2. RejectRecord v2 correct; refactor output-neutral.
- repair3-orig/Jobs.scala vs current: the TeamReducer change is a mechanical move (same counters and order, same named outputs with base path s"$output/part", same sort-then-head selection).
- inputFileName uses FileSplit.getPath.getName; DblpXmlInputFormat extends FileInputFormat and yields only FileSplits (DblpXmlInputFormat.scala:22, :104); any other split type throws rather than writing a reject without provenance.
- inputFile set once in setup (Jobs.scala:67); correct since each mapper handles one split.
- runC.sha256 vs runE.sha256: only rejects/v1/part-m-0000{0,1} -> rejects/v2/part-m-0000{0,1}; the other 14 checksums identical. runE.sha256 == runF.sha256.
- runE tree has only rejects/v2/ (no v1). Six records: dblp-sample.xml at 3859/4031/4285/4454, dblp-malformed.xml at 643/812, matching the round-4 hand-verified offsets.
- LocalPipelineSuite checks rejects and pairs determinism across output bases (:108-113) and asserts rejects/v1 absent (:80).
- No remaining reader of rejects/v1 or the v1 schema outside docs/agent-runs.

3. Skip-to-failure matches decision 6.
- windowsPrerequisiteProblem (LocalPipelineSuite.scala:139-153) returns None for any os.name without "win" (Linux and "Mac OS X" still run). On Windows it fails when HADOOP_HOME is unset or blank, or bin lacks winutils.exe or hadoop.dll.
- b-noprefix.log: `==> X ... LocalPipelineSuite.scala:43 HADOOP_HOME is not set ...`, "Failed: Total 2, Failed 1, Passed 1", TestsFailedException. No skip.
- check4.log (5:11:36 PM): every step [success]; core 143/143; hadoop 27/27; IT counters and IT framework lines present (job6 EMITTED_PAIR 285, OVERSIZE_TEAM 4); LocalPipelineSuite "0 failed, 0 ignored, 2 total 5.157s"; no `==> s`, no Skipped count; the test-marker task ran.
- check4.log is newer than every hadoop source file (Jobs.scala 17:07:38, tests 17:03:01).

Ownership: every changed source path is under modules/hadoop/** (hadoop-engineer); other untracked files under docs/agent-runs/**. git diff HEAD --stat empty: no tracked file changed; constitutional files at HEAD.

COMMANDS RUN: git status --short --untracked-files=all; git diff HEAD --stat (empty); git diff --no-index (repair3-fixed vs current Jobs.scala: empty; repair3-orig vs current; runC vs runE sha256; runE vs runF sha256: empty); ls -la on run-evidence, scratchpad, repair3-orig, repair3-fixed, hadoop source trees; cat check4.log and b-noprefix.log; grep on revert logs and runE/runF logs; find and grep on scratchpad runE rejects; grep for rejects/v1 and RejectRecord consumers; read all changed sources and DblpXmlInputFormat split handling. Fence-blocked and not retried: cd with double quotes, git -C, sha256sum, a `;`-joined command. sbt not run, per the work order.

TEST RESULTS: none run; relied on check4.log, revert-f2.log (Failed 4 of 5), revert-f3-a.log (Failed 4 of 8), b-noprefix.log (Failed 1 of 2).

VALIDATION RESULTS: runE and runF byte-identical; runE matches runC except rejects v1 -> v2; reject records carry correct base names and per-file offsets.

ASSUMPTIONS: run-evidence logs are faithful copies (sizes match: check4.log 11059, b-noprefix.log 2897, r3-test1.log 7894, revert-f2.log 5332, revert-f3-a.log 7084, runE.log 5057, runF.log 5058).

RISKS (disclosed, not findings): inputFile base-name ambiguity across directories (v3 if part B reads several directories); the Windows "HADOOP_HOME set but bin missing a file" branch has no unit test; sbt check fails on Windows in any session lacking HADOOP_HOME, by design; still open: decompressed-stream offsets, unsuffixed gzip read raw, one mapper per .gz, pair partition skew (279 of 285 on partition 0).

MANUAL ACTION REQUIRED: commit the *.log evidence with `git add -f docs/agent-runs/phase-04/run-evidence/*.log`; restart future reviewer sessions with HADOOP_HOME and PATH inherited.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: test-engineer (per the repair-3 report), then the human part A gate.
```
