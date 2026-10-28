# Phase 04 part A: hadoop-engineer repair loop 1 report

Assembled by the orchestrator from the agent's final report (2026-10-26, about 23:39 CDT).

## Status: FAILED

The fixes for F1 and F2 are written, but they are not compiled or tested. `sbt check` failed at compile with a Windows file lock, so no tests ran and nothing is verified.

## Files

Created:
- `modules/hadoop/src/test/scala/edu/uic/cs441/scholarchain/hadoop/RepairSuite.scala`, with 3 tests:
  - (a) `isSplitable` returns false for `.gz` and true for `.xml`. This is the test that fails without the F1 fix.
  - (b) A gzip decompress-and-scan gives the same records as the plain scan. This test would pass without the fix.
  - (c) The snapshotId check rejects `../../other`, `a/b`, `..`, `""`, `.hidden`, `a..b`, `x y` and `a\b`.

Modified:
- `DblpXmlInputFormat.scala`
  - Input is not splittable when a compression codec matches the file.
  - The record reader decompresses the whole file when it sees a codec and requires the split to start at 0.
- `Jobs.scala`
  - Adds `Pipeline.validateSnapshot`, which requires `^[A-Za-z0-9][A-Za-z0-9._-]*$` and rejects any value containing `..`.
  - `outputPath` calls it before building the path.

## Commands run

1. `sbt -batch check` at 23:36:15. FAILED at `hadoop / Test / scalafmtCheck` because RepairSuite.scala was not formatted.
2. `sbt -batch "hadoop/Test/scalafmt" check` at 23:36:56. scalafmt reformatted the file, then the compile step FAILED with:
   `java.nio.file.FileSystemException ... DblpRecordScanner.tasty -> ...classes.bak\...: The process cannot access the file because it is being used by another process`
3. `sbt -batch check` at 23:37:58. FAILED with the same FileSystemException.
4. `tasklist`: no java or sbt process was running. OneDrive.exe and OneDrive.Sync.Service.exe were running. The agent thinks OneDrive was locking `target/`, but did not prove it.

## Test results

None. The code did not compile.

## Manual action required

1. Stop OneDrive from locking `modules/hadoop/target`, for example by pausing sync or excluding `target/`.
2. Rerun `sbt check`.

## Next gate

A green `sbt check`, then a code-reviewer re-review of F1 and F2, then a non-skipped LocalPipelineSuite run.
