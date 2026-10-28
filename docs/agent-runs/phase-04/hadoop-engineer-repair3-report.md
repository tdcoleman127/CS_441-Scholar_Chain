# hadoop-engineer: Phase 04 part A, repair 3 (F2, F3, RejectRecord v2, skip becomes failure)

Assembled by the orchestrator from the agent's final report, 2026-10-27. The cited logs are copied into `run-evidence/` (check4.log, runE.log, runF.log, runE.sha256, runF.sha256, revert-f2.log, revert-f3-a.log, b-noprefix.log, r3-test1.log). `SP` below is the session scratchpad, which is temporary.

```text
STATUS: COMPLETE

All four items are done: F2, F3, scope addition A (RejectRecord v2) and scope addition B (the skip is now a failure). The final `sbt -batch check` with the prefix exited 0: core 143/143, hadoop 27/27. LocalPipelineSuite really ran (IT counters and IT framework lines, 5.157s, no skipped line). Each fix fails its test when temporarily reverted and was then restored (Jobs.scala sha256 matched the fixed backup each time). Bounded runs E and F are byte-identical. Compared with runC, only the two reject files changed; they are now under rejects/v2/ and name their input file.

OWNER: hadoop-engineer

WORK ORDER: Phase 04 part A, repair 3. F2 and F3 from code-reviewer-report-round4.md. Scope addition (human decisions, 2026-10-27): A, RejectRecord v2 with input file provenance under rejects/v2; B, LocalPipelineSuite fails on Windows without HADOOP_HOME instead of skipping. Rerun the bounded pipeline as E and F. Final check log check4.log.

FILES CREATED:
- modules/hadoop/src/test/scala/edu/uic/cs441/scholarchain/hadoop/TeamReducerSuite.scala: 3 tests, every platform, no cluster. Input is Job 1's map output for both samples, run through TeamReducer.process.
- modules/hadoop/src/test/scala/edu/uic/cs441/scholarchain/hadoop/RejectRecordSuite.scala: 3 tests (file base name; schema 2 and rejects/v2; the 6 sample rejects with file names and offsets).

FILES MODIFIED (all still untracked):
- modules/hadoop/src/main/scala/edu/uic/cs441/scholarchain/hadoop/Jobs.scala
  - TeamReducer body moved to `object TeamReducer { trait Sink; def process(values: Seq[String], threshold: Int, sink: Sink) }`; the reducer binds the sink to ctx.getCounter and mos.write(output, ..., s"$output/part"). Output unchanged: every non-reject file in runE matches runC's checksums.
  - object DblpExtractMapper adds RejectSchemaVersion = 2, RejectBase = "rejects/v2/part", inputFileName(split), rejectRecord(...). The mapper sets inputFile in setup from ctx.getInputSplit.
  - parseArgs rejects any isBlank argument after the arity check ("argument N is blank (empty or whitespace only; was a quoted variable unset?)" plus usage), so main exits 2.
  - Job 1 and Job 6 contract comments and the driver doc comment updated.
- modules/hadoop/src/main/scala/edu/uic/cs441/scholarchain/hadoop/Teams.scala: RejectRecord adds `inputFile: String` before inputOffset, with doc comments (base name; offset per file; decompressed-stream offset for compressed input).
- modules/hadoop/src/test/scala/edu/uic/cs441/scholarchain/hadoop/LocalPipelineSuite.scala
  - assume replaced by fail through LocalPipelineSuite.windowsPrerequisiteProblem, which also checks bin\winutils.exe and bin\hadoop.dll exist. New unit test for that check (Windows fails; Linux and Mac OS X give None).
  - Pins: Job 1 MISSING_KEY 1, UNSUPPORTED_TYPE 3, SCHEMA_FAILURE 2; Job 6 TEAMS 7, AUTHOR_PAPERS 35, OVERSIZE_TEAM 4, EMITTED_PAIR 285; pairs line count 285 (INPUT 13, ACCEPTED 7, REJECTED 6 already pinned).
  - Rejects: no rejects/v1; the 6 rejects/v2 records have schema 2 and a pinned (inputFile, offset, reason) set. The rerun into a different output base must give identical rejects and pairs.
- modules/hadoop/src/test/scala/edu/uic/cs441/scholarchain/hadoop/DriverArgsSuite.scala: new test; each of 7 positions set to "", "   " or "\t" must give "argument N is blank".

COMMANDS RUN (prefix HADOOP_HOME='C:\hadoop' PATH="/c/hadoop/bin:$PATH" on every sbt and java command except item 6):
1. Backed up originals to SP\repair3-orig (Jobs.scala, LocalPipelineSuite.scala, DriverArgsSuite.scala). Teams.scala not backed up; no revert needed it.
2. sbt -batch fmt: [success], reformatted 3 + 1 sources.
3. sbt -batch hadoop/test (r3-test1.log): EXIT=0, Passed: Total 27, Failed 0, Errors 0, Passed 27.
4. Revert F2 (deleted the Teams.pairsFor(env).foreach block in TeamReducer.process, Jobs.scala 235-238); sbt -batch "hadoop/testOnly *TeamReducerSuite *LocalPipelineSuite" (revert-f2.log): EXIT=1. TeamReducerSuite:42 -285 +0; :70 -285 +0; :80 assertion failed; LocalPipelineSuite.scala:65 (EMITTED_PAIR == 285L). Failed: Total 5, Failed 4. Restored from SP\repair3-fixed\Jobs.scala (sha256 4ec22c26...eae03a matches; pairs-block count 1).
5. Revert F3 and A (line 431 `else if blank >= 0` -> `else if false`; line 109 getPath.getName -> getPath.toString; line 127 inputFile -> ""); sbt -batch "hadoop/testOnly *DriverArgsSuite *RejectRecordSuite" (revert-f3-a.log): EXIT=1. DriverArgsSuite.scala:57 (i=0 b='' Right(Args(,in/dblp.dtd,...))); RejectRecordSuite base name -dblp-sample.xml +file:/C:/tmp/a/datasets/samples/dblp/dblp-sample.xml; schema-2 test; six-rejects test :60 _1 "dblp-sample.xml" -> "". Failed: Total 8, Failed 4. Restored (sha256 matches; lines 109, 127, 431 show fixed text).
6. B without the prefix (shell printed `HADOOP_HOME set in this shell: no`): sbt -batch "hadoop/testOnly *LocalPipelineSuite" (b-noprefix.log): EXIT=1. LocalPipelineSuite.scala:43 "HADOOP_HOME is not set. LocalPipelineSuite runs Hadoop's LocalJobRunner, which on Windows needs HADOOP_HOME set to a Hadoop install whose bin directory contains winutils.exe and hadoop.dll (and that bin on PATH). This integration test fails ...". Failed: Total 2, Failed 1, Passed 1 (prerequisite unit test passed).
7. sbt -batch -error "export hadoop/Test/fullClasspath": EXIT=0, classpath unchanged; reused SP\java.args.
8. java @SP/java.args edu.uic.cs441.scholarchain.hadoop.DblpPipelineMain 'datasets/samples/dblp/dblp-{sample,malformed}.xml' 'datasets/samples/dblp/dblp.dtd' file:///SP/run{E,F} dblp-samples-local run:v1:605a6a0b610befb08cf557002b7091e46bc13e492c71687a1a65326d1d870887 2 50. runE and runF new. Both EXIT=0, both jobs ok=true skipped=false. E==F sha256 lists identical (16 files); diff -r E F (incl .crc) no differences. diff runC.sha256 runE.sha256 differs only in rejects/v1/part-m-0000{0,1} vs rejects/v2/part-m-0000{0,1}.
9. sbt -batch check > check4.log (11059 bytes): EXIT=0.
- Two grep commands were blocked by guard-dangerous because of pattern text ("(env)" read as an environment dump; "Test run" read as sbt run). Rerun without those patterns. No file changed.

TEST RESULTS (check4.log): every step [success]; 0 [error], 0 [warn], 0 "skipped". core Passed: Total 143. hadoop Passed: Total 27, Failed 0, Errors 0, Passed 27 (DblpParserSuite 6, TeamsSuite 3, TeamReducerSuite 3 new, DriverArgsSuite 5, RepairSuite 5, RejectRecordSuite 3 new, LocalPipelineSuite 2). LocalPipelineSuite ran: `IT counters job1=HashMap(... UNSUPPORTED_TYPE -> 3, REJECTED -> 6, ACCEPTED -> 7, INPUT -> 13, MISSING_KEY -> 1, SCHEMA_FAILURE -> 2 ...) job6=HashMap(OVERSIZE_TEAM -> 4, ACCEPTED -> 7, AUTHOR_PAPERS -> 35, INPUT -> 7, TEAMS -> 7, EMITTED_PAIR -> 285 ...)`; `IT framework job1=TreeMap(...)`; `0 failed, 0 ignored, 2 total 5.157s`; no `==> s` line. The test-marker step updated .claude\.last-test-run.

VALIDATION RESULTS: E and F byte-identical (16 files incl .crc). All non-reject outputs byte-identical to runC. runE: teams 7, authorpapers 35, pairs 285.

ASSUMPTIONS:
- inputFile holds the base name, not the full URI: the URI embeds storage location (repo path, temp dir, S3 bucket), so the same snapshot would give different reject bytes on a laptop and on EMR. The output base never reaches reject records; LocalPipelineSuite checks this.
- Same run ID and snapshot as runs A-D for comparability.

RISKS:
- Two input files with the same base name in different directories under one glob would give ambiguous rejects. Full DBLP is one dblp.xml.gz. Multi-directory inputs in part B may need a relative path (RejectRecord v3).
- Every sbt check on Windows now fails unless HADOOP_HOME and PATH are set, including code-reviewer's (its fence blocks the prefix). Sessions started before the user-level variables existed get a red check.
- Consumers of rejects/v1 (none known) must move to v2.
- Deleting the TeamReducer.process(...) call itself is caught only by LocalPipelineSuite, which now fails rather than skips.
- Still open: decompressed-stream offsets, unsuffixed gzip read raw, one mapper per .gz, pair partition skew (279 of 285 on partition 0).

MANUAL ACTION REQUIRED: logs need `git add -f` (*.log gitignored, round-4 F1). Reviewer sessions must inherit HADOOP_HOME and PATH (restart).

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer re-reviews F2, F3, A and B; then test-engineer; then the human part A gate.

KEY-VALUE CONTRACTS: Job 1 named output rejects: (NullWritable, RejectRecord v2 JSON) under rejects/v2/; fields rejectSchemaVersion, reason, sourceDataset, sourceRecordId, snapshotId, pipelineRunId, inputFile (base name), inputOffset (per file), detail. Everything else unchanged; TeamRecord, AuthorPaperRecord and MentionPairCandidate stay in the hadoop module (human decision).

PARTITION COUNTS (runE): as runC apart from rejects/v2 (part-m-00000 4 from dblp-sample.xml, part-m-00001 2 from dblp-malformed.xml).

COUNTERS (runE = runF): Job 1 INPUT 13, ACCEPTED 7, REJECTED 6, MISSING_KEY 1, UNSUPPORTED_TYPE 3, SCHEMA_FAILURE 2, rest 0. Job 6 INPUT 7, ACCEPTED 7, TEAMS 7, AUTHOR_PAPERS 35, EMITTED_PAIR 285, OVERSIZE_TEAM 0 (threshold 50; 4 in the test at threshold 2), rest 0.

REJECTED RECORDS (runE rejects/v2; RID = run:v1:605a6a0b610befb08cf557002b7091e46bc13e492c71687a1a65326d1d870887):
part-m-00000:
{"rejectSchemaVersion":2,"reason":"missing-key","sourceDataset":"dblp","sourceRecordId":null,"snapshotId":"dblp-samples-local","pipelineRunId":"RID","inputFile":"dblp-sample.xml","inputOffset":3859,"detail":"article"}
{"rejectSchemaVersion":2,"reason":"unsupported-record-type","sourceDataset":"dblp","sourceRecordId":"homepages/99/990001","snapshotId":"dblp-samples-local","pipelineRunId":"RID","inputFile":"dblp-sample.xml","inputOffset":4031,"detail":"www"}
{"rejectSchemaVersion":2,"reason":"unsupported-record-type","sourceDataset":"dblp","sourceRecordId":"homepages/99/990002","snapshotId":"dblp-samples-local","pipelineRunId":"RID","inputFile":"dblp-sample.xml","inputOffset":4285,"detail":"www"}
{"rejectSchemaVersion":2,"reason":"unsupported-record-type","sourceDataset":"dblp","sourceRecordId":"homepages/99/990009","snapshotId":"dblp-samples-local","pipelineRunId":"RID","inputFile":"dblp-sample.xml","inputOffset":4454,"detail":"www"}
part-m-00001:
{"rejectSchemaVersion":2,"reason":"undeclared-entity","sourceDataset":"dblp","sourceRecordId":"journals/scsample/BadEntity24","snapshotId":"dblp-samples-local","pipelineRunId":"RID","inputFile":"dblp-malformed.xml","inputOffset":643,"detail":"notdeclared"}
{"rejectSchemaVersion":2,"reason":"truncated-record","sourceDataset":"dblp","sourceRecordId":"conf/scsample/Truncated24","snapshotId":"dblp-samples-local","pipelineRunId":"RID","inputFile":"dblp-malformed.xml","inputOffset":812,"detail":"record has no closing tag before end of input"}
sha256: part-m-00000 bf7dbafad83f4ba294d941f720d8149c5235bce8e2a229a7ceb0488b851a3cfa; part-m-00001 d27548c2fbf0ebd5ee1057b9e7cc0bb62da0f6e49d7c2d33ed251f1869f9338a. Identical in runF.

MAX TEAM SIZE AND LARGEST PAIR EXPANSION: 24 authors (SampleP25); 276 = 24*23/2; total 285. TeamReducerSuite checks this without a cluster.

LARGEST BLOCK: not applicable (Job 4 is part B).
```
