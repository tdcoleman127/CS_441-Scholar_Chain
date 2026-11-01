# hadoop-engineer: Phase 04 closeout, item 3 (SourcePipelineMain and the MINOR 1 test)

Assembled by the orchestrator, 2026-11-01, from hadoop-engineer's final report.

Orchestrator checks:
- `run-evidence/closeout-hadoop-check.log` shows `sbt check` `[success]` at 3:20:22 PM: core 143/143, hadoop 59/59.
- The `SourcePipelineMain` argument block in `scripts/run-bounded-pipeline.sh` (lines 354-364) matches the contract below.
- A later orchestrator `sbt check` at 3:22 PM (`run-evidence/closeout-orch-check1.log`) failed at scalafmtCheck on 2 unformatted files under `tests/contracts`. These are test-engineer's files, in progress; no hadoop file was involved.

**STATUS: COMPLETE.** The main class was not run (no `sbt run`, `runMain`, or `java`); the main-class rule is still an unratified amendment.

## Files

- Created: `modules/hadoop/src/main/scala/edu/uic/cs441/scholarchain/hadoop/SourcePipelineMain.scala`.
- Created: `modules/hadoop/src/test/scala/edu/uic/cs441/scholarchain/hadoop/SourceDriverArgsSuite.scala` (9 tests).
- Modified: `SourcePipelineSuite.scala` (MINOR 1 partition-placement assertions added to the existing part B test; no pin changed).
- `SourceJobs.scala` is unchanged. It was edited temporarily for the negative control and restored from a scratchpad backup; `git diff --stat` on it is empty.

## Commands

1. **Negative control:** commented out SourceJobs.scala:638 (the ExportPartitioner wiring) with sed, then ran `sbt -batch "hadoop/testOnly *SourcePipelineSuite *SourceDriverArgsSuite"`: exit 1, Failed 2 of 11.
   - SourcePipelineSuite failed at the new assertion (line 246, `misplaced.isEmpty`).
   - The other failure was a wording mismatch in the agent's own new test, since fixed.
   - The file was restored afterwards.
2. `sbt -batch fmt`: exit 0.
3. `sbt -batch check`: exit 0. core 143/143. hadoop 59/59 (was 50; +9 SourceDriverArgsSuite). No sbt lock contention.

## MINOR 1 assertions (Job 7, 3 partitions)

- Every exported record (759) is in part number `WorkIdPartitioner.partition(Export.partitionKey(line), 3)`.
- For works, mentions, and workextras, each work ID has exactly one part number (at least 7 work IDs).
- At least 2 partitions are used.

## SourcePipelineMain argument contract

```
edu.uic.cs441.scholarchain.hadoop.SourcePipelineMain <outputBase> <snapshotId> <runId> <reducers> <exportPartitions> <maxBlock> <sourceInput> [<sourceInput> ...]
```

- `outputBase`, `snapshotId`, and `runId` must equal DblpPipelineMain's values. The order differs from DblpPipelineMain's `<dblp.xml> <dblp.dtd> <outputBase> <snapshotId> <runId> [reducers] [oversize]`.
- The Job 1 and Job 6 outputs are derived through `Pipeline.outputPath` and must have `_SUCCESS`.
- `reducers` applies to Jobs 2, 3, and 4+5; `exportPartitions` to Job 7; `maxBlock` is the block cap. All three are required positive integers.
- **Exit 2** (reason and usage on stderr): fewer than 7 arguments; a blank argument; an invalid snapshot or run ID; an outputBase that `Pipeline.outputPath` rejects; a non-positive or non-numeric integer; a duplicate source input.
- **Exit 1:** missing upstream `_SUCCESS`, a job returning ok=false, or a job throwing.
- **On success:** one `JOB <name> ok= skipped= output= counters= framework=` line per job, then `LARGEST-BLOCK ...`, then exit 0. Reruns reuse completed outputs (idempotent).
- **Job order:** Job 2 source-normalize, Job 3 exact-ids, Jobs 4+5 blocking-candidates, Job 7 graph-export.

## Counters (sbt check run; all pins unchanged)

| Job | Counters |
|---|---|
| 1 | ACCEPTED 7 |
| 2 | INPUT 17, ACCEPTED 11, REJECTED 6, HEADER_SKIPPED 1, CITATIONS 4, UNSUPPORTED_TYPE 2, SCHEMA_FAILURE 4, MISSING_KEY 0, DUPLICATE+CONFLICT 0 |
| 3 | INPUT 18, EXACT_ID_KEY 20, LINKED_KEY 5, DUPLICATE 8, MISSING_KEY 4, CONFLICT 0 |
| 4+5 | INPUT 18, BLOCKS 17, OVERSIZE_BLOCK 2, CANDIDATES 268, MISSING_KEY 0 |
| 6 | AUTHOR_PAPERS 35, EMITTED_PAIR 285 |
| 7 | EXPORTED 759 = INPUT |

## Partitions, rejects, and blocks

- **Job 7 part files:** works, mentions, workextras, and mentionextras in parts 0-2; citations in parts 1-2; topics in part 1; institutions in part 2.
- **Rejects:** unchanged (6 Job 2 rejects under `rejects/v2`).
- **Largest block:** `m|member|c`, size 42, cap 10, 5 chunks, 181 pairs, 680 skipped.

## Risks and next gate

- **Risks:**
  - `SourcePipelineMain.main` has never been executed; only parseArgs, upstream, and checkUpstream are tested in process.
  - The Windows `java.exe` launcher expands an unquoted `*` into extra inputs. This cannot shift a fixed argument; a duplicate input is rejected. [Orchestrator note: code-reviewer closeout MINOR 2 found that only identical strings are rejected; a glob or `./` spelling of the same file passes. MINOR 1: the outputBase rejection branch is untested, though listed under exit 2 above.]
- **Next gate:** code-reviewer closeout review; after ratification, the local bounded main-class run.
