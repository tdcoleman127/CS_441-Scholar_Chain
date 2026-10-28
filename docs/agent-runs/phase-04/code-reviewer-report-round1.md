# Phase 04 part A: code-reviewer report, round 1

Assembled by the orchestrator from the agent's final report (2026-10-26, about 23:33 CDT).

## Verdict: REQUEST_CHANGES

- **Scope:** uncommitted modules/hadoop/** files for Job 1 and Job 6.
- **Commands run:** `date`.
  - `git status` was blocked by the read-only fence, because the repo path needs double quotes. The reviewer used Glob instead.
  - The reviewer did NOT run `sbt hadoop/test` or `sbt check`, so the implementer's "9 passed, 1 skipped" is not independently confirmed.

## Findings

### F1 MAJOR: compressed input is read as raw bytes and lost silently

- **Where:** DblpXmlInputFormat.scala:22-26 and :91-93.
- **Cause:** `isSplitable` is not overridden, so it defaults to true. The reader uses `fs.open` and `seek` with no CompressionCodecFactory.
- **Failure input:** DBLP's `dblp.xml.gz` (dataset manifest lines 143 and 153).
- **Impact:**
  - INPUT is about 0, and the run still looks green.
  - The `_SUCCESS` guard then reuses the empty output on every rerun.
- **Owner:** hadoop-engineer.

### F2 MINOR: snapshotId is not validated before it goes into the output path

- **Where:** Jobs.scala:193-194 and :318.
- **Failure input:** `../../other`, which escapes the run partition.
- **Impact:** the `_SUCCESS` reuse guard could pick up an unrelated job's output as this run's result.
- **Owner:** hadoop-engineer.

## Checked and found no defect

1. **Split-boundary ownership:** correct. A record still open at EOF is marked truncated, not dropped.
2. **Pairs:** exactly n(n-1)/2, in canonical order. Every pair carries workId, year, both positions, and provenance. The hadoop-local MentionPairCandidate and TeamRecord types are justified.
3. **Determinism:** reducers sort their values, and the partitioner uses the JDK-specified String.hashCode. There is no shared mutable state.
4. **Counters:** all the counters required for Job 1 and Job 6 are present.
5. **Idempotency guard:** an existing `_SUCCESS` means reuse; partial output fails the job.
6. **Module boundaries:** the module depends on core plus Hadoop (Provided) only. Imports are core, Hadoop, upickle, and the JDK.
7. **LocalPipelineSuite skip:** the skip is honest (munit `assume`, reported as skipped). But no job wiring has ever executed, and this suite would not catch F1 because its fixtures are uncompressed.

## Not checked (time box)

- A full read of DblpRecordParser.scala.
- The test suites, including whether each test would fail if its behavior broke.
- An sbt run.

## Next gate

- hadoop-engineer fixes F1 and F2.
- A fresh code-reviewer pass.
- A non-skipped LocalPipelineSuite run on Linux, WSL, or with HADOOP_HOME.
