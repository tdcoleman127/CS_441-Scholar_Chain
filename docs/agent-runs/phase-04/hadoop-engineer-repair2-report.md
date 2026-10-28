# Phase 04 part A: hadoop-engineer repair loop 2 report

Assembled by the orchestrator from the agent's final report (2026-10-27), plus the orchestrator's own `sbt check` run.

## Status: COMPLETE

This loop fixes the two code-reviewer round-2 findings and makes codec detection ignore suffix case.

## Files modified (all under modules/hadoop/src/)

- **`Jobs.scala`**
  - Adds `Pipeline.validateRunId`, which uses `IdFormat.isValid(IdKind.Run, _)`.
  - `outputPath` now validates the runId and then the snapshot. Every path builder, including `runTeams`, is covered.
- **`DblpXmlInputFormat.scala`**
  - Adds `codecFor(conf, path)`, which tries the exact suffix and then the lower-cased file name, so `.GZ` is detected.
  - Both `isSplitable` and `initialize` use it.
- **`RepairSuite.scala`**
  - Removes the old test (b), which copied the reader logic instead of calling it.
  - New tests drive the production `DblpXmlInputFormat` through `getSplits`, `createRecordReader`, `initialize` and `nextKeyValue` on temp files. They cover:
    - plain input with split max size 512, which gives more than 1 split and 10 records;
    - `.gz` and `.GZ` copies, which give exactly 1 split each, with records identical to the plain file;
    - a truncated gzip tail, which comes back with the `#TRUNCATED#` marker and the exact bytes, not dropped;
    - runId rejection: `x/../../y`, `run:v1:ab`, `""`, `run:v1:../../y` and `null`; also `runTeams` with `x/../../y` throws.
- **`DblpParserSuite.scala`**
  - The key-function test now uses a valid `Fixtures.runId` instead of `run:v1:ab`.

## Evidence that the new tests catch the original bug (implementer-observed)

1. The implementer temporarily forced `initialize` onto the raw-seek path for `.gz` input.
2. With that change, `sbt -batch "hadoop/testOnly *RepairSuite"` reported `Failed: Total 5, Failed 2`:
   - the records-equal assertion failed at RepairSuite.scala:81;
   - the truncated-count assertion failed at RepairSuite.scala:94.
3. The file was then restored, and `grep -c "TEMP REVERT"` returned 0.

The runId test was checked by reasoning only, not by a revert.

The gzip reads ran on the local Windows file system without winutils or HADOOP_HOME.

## sbt check (orchestrator-observed, 2026-10-27 01:25 CDT)

- Exit code 0, and every step reported [success].
- Other modules: 143 total, 143 passed, 0 failed, 0 errors.
- hadoop: 15 total, 14 passed, 0 failed, 0 errors, 1 skipped.
  - DblpParserSuite 6, TeamsSuite 3, RepairSuite 5.
  - LocalPipelineSuite is skipped because there is no HADOOP_HOME.

## Still open

- LocalPipelineSuite and the local bounded run have never executed, so job counters and output paths have not been observed.
- For compressed input, record and reject offsets are decompressed-stream offsets. This is not documented next to the reader.
- Gzip data without a codec suffix is still read as raw bytes.
- Jobs.scala:5 has an unused-import warning (`Validators.given`) that predates this loop.

## Next gate

1. code-reviewer round 3. This is the last loop before human escalation under docs/agents.md section 8.
2. A non-skipped LocalPipelineSuite run.
