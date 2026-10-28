# Phase 04 part A: code-reviewer report, round 2 (re-review of F1 and F2)

Assembled by the orchestrator from the agent's final report (2026-10-27, about 00:34 CDT).

## Verdict: REQUEST_CHANGES

- **Commands run:**
  - `git status`: untracked files only, under docs/agent-runs/phase-04/ and modules/hadoop/src/.
  - `sbt -batch hadoop/test`: [success] at 2026-10-27 00:33:59.
- **Test results (hadoop):** 13 total, 12 passed, 0 failed, 0 errors, 1 skipped.
  - DblpParserSuite 6, TeamsSuite 3, RepairSuite 3.
  - LocalPipelineSuite skipped.
  - This matches the orchestrator's `sbt check` at 23:58.

## Findings

### 1. MAJOR: the decompressing record-reader branch has no test (CLAUDE.md law 2)

- **Where:** DblpXmlInputFormat.scala:98-108, with tests at RepairSuite.scala:16-45.
- **Evidence:**
  - No test constructs `DblpXmlRecordReader`.
  - Test (a) checks only `isSplitable`.
  - Test (b) copies the decompress-and-scan logic instead of calling the reader.
- **Failure input:** revert `initialize` to the round-1 raw-seek code.
  - All 12 non-skipped tests still pass.
  - `dblp.xml.gz` then reads as INPUT≈0, the run looks green, and `_SUCCESS` is reused.
  - That is the original F1 failure, with no test to catch it.
- **Fix:** add a test that writes a gzipped temp `.gz` file and drives the input format or record reader through `getSplits`, `initialize` and `nextKeyValue`. It should assert:
  - one split;
  - the same records as the plain file;
  - a truncated-tail case.
- **Owner:** hadoop-engineer.

### 2. MINOR: runId is not validated in `outputPath` or `runTeams`

- **Where:** Jobs.scala:190, :201-203 and :278-290.
- **Cause:** only `runExtract` validates runId (:253).
- **Failure input:** `Pipeline.runTeams(conf, p, "/out", "x/../../y", "snap", 2, 50)` resolves to `/out/y/snapshot=snap/team-extract`, which is outside the run partition.
- **Reachability:** only direct API callers. `DblpPipelineMain` validates the same runId in `runExtract` first.
- **Fix:** validate runId in `outputPath`, or at the top of `runTeams`, and add a test.
- **Owner:** hadoop-engineer.

## Verified closed

### F1 code

- **Splitting:** `isSplitable` is false for any matched codec, so a `.gz` file gets one split with start 0.
- **EOF:** an unclosed record is marked truncated, not dropped.
- **Resources:** `close()` closes the whole stream chain.
- **getProgress:** safe.
- **Job 6:** unaffected, because TextInputFormat handles codecs itself.
- **Test (a):** fails without the `isSplitable` change.

### F2 snapshot

- Both path builders go through `validateSnapshot`.
- The regex is ASCII-only and must match the whole string.
- Any value containing `..` is rejected.
- Windows reserved names are not a problem, because the path segment is `snapshot=CON`, not `CON`.

### Regressions

None found:
- Split-boundary ownership and determinism are unchanged.
- Counters, the idempotency guard and module boundaries are unchanged.
- Ownership is respected.

## Risks (not verified, so not raised as findings)

- **Codec detection:** uses only the file suffix, case-sensitively. A file named `.GZ`, or gzip data without the `.gz` suffix, would be read as raw bytes.
- **Local Windows paths:** trailing-dot or case variants of a snapshot may collide on the local file system.
- **Single mapper:** one mapper decompresses the whole DBLP dump. An unclosed record buffers the rest of the file in memory.
- **Offsets:** for compressed input, record keys and reject offsets are offsets into the decompressed stream. This is not documented.
- **Integration test:** LocalPipelineSuite has still never run.

## Next gate

1. hadoop-engineer fixes findings 1 and 2.
2. `sbt check` runs green.
3. A fresh code-reviewer pass.
4. A non-skipped LocalPipelineSuite run.
