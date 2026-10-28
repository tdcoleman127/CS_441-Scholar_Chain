# Phase 04 part A: hadoop-engineer report

Assembled by the orchestrator from the agent's final report (23:32 CDT, 2026-10-26). Phase time box: 23:15 to 23:45 CDT.
Scope: Job 1 (DBLP extraction) and Job 6 (publication team and pair extraction) only. Jobs 2, 3, 4, 5 and 7 are deferred to Phase 4b.

```text
STATUS: FAILED
  - The code and unit tests are in place, and `sbt check` passes.
  - The local-mode integration test and the local bounded run did NOT run. Windows has no HADOOP_HOME, winutils.exe or hadoop.dll.
  - No MapReduce counters or output paths were observed.

OWNER: hadoop-engineer

WORK ORDER: Phase 04 part A (time-boxed), from docs/agent-runs/phase-04/prompt.md. Implementation ran 23:16 to 23:31 CDT.

FILES CREATED (all under modules/hadoop/src/):
  main/scala/edu/uic/cs441/scholarchain/hadoop/DblpXmlInputFormat.scala
  main/scala/edu/uic/cs441/scholarchain/hadoop/DblpRecordParser.scala
  main/scala/edu/uic/cs441/scholarchain/hadoop/Teams.scala
  main/scala/edu/uic/cs441/scholarchain/hadoop/Jobs.scala
  test/scala/edu/uic/cs441/scholarchain/hadoop/DblpParserSuite.scala (6 tests)
  test/scala/edu/uic/cs441/scholarchain/hadoop/TeamsSuite.scala (3 tests)
  test/scala/edu/uic/cs441/scholarchain/hadoop/LocalPipelineSuite.scala (1 test, skipped on Windows without HADOOP_HOME)

FILES MODIFIED: none outside modules/hadoop.

COMMANDS RUN:
  sbt -batch hadoop/compile
    - Failed first, then passed after the fixes.
  sbt -batch hadoop/test (several runs)
    - The integration test failed with "HADOOP_HOME and hadoop.home.dir are unset".
    - A workaround then hit UnsatisfiedLinkError NativeIO$Windows.access0. The workaround was removed.
  sbt -batch fmt
  sbt -batch check
    - Every step reported [success].

TEST RESULTS (observed in sbt check):
  - Other modules: 143 passed, 0 failed.
  - hadoop: 10 total. 9 passed, 0 failed, 0 errors, 1 skipped (LocalPipelineSuite).

VALIDATION RESULTS:
  - The local bounded run was NOT done.
  - Not observed at run time: the MultipleOutputs layout, the glob input for Job 6, the idempotent skip, and determinism across reruns.

ASSUMPTIONS:
  - www and data records go to rejects as unsupported-record-type.
  - Entities come from dblp.dtd (scholarchain.dblp.dtd).
  - venueId is venue:dblp:<conf|journals>/<stream>.
  - The oversize-team threshold defaults to 50. Oversize teams still emit their pairs.
  - The run ID must be in run:v1:<hex> format.

RISKS:
  1. Pairs and teams use hadoop-local MentionPairCandidate and TeamRecord types, not the core contracts.
     - Core CoauthorPairCandidate has no author positions.
     - ResearcherPair accepts only researcher: IDs.
  2. The scanner does not handle XML comments or CDATA, and there is no test for this.
  3. An unterminated record is read to EOF, then rejected as truncated-record.
  4. The REJECTED counter includes www records. UNSUPPORTED_TYPE splits them out.
  5. The Job 1 reducer holds all values for one workId in memory.

MANUAL ACTION REQUIRED:
  - Provide a Hadoop-capable local runtime: HADOOP_HOME with winutils.exe and hadoop.dll for Hadoop 3.4.2, or a Linux/WSL/CI runner.
  - Then rerun sbt hadoop/test and the bounded run.

BLOCKED-ON OWNER:
  - domain-engineer (advisory): the pre-entity-resolution pair and team contracts.
  - build-engineer or infra-engineer: the local Hadoop runtime.

BLOCKED-ON EVIDENCE:
  - CoauthorPairCandidate(schemaVersion, researcherLow, researcherHigh, workId, year, provenance) has no position fields.
  - ResearcherPair.of rejects IDs that are not researcher: IDs.
  - Runtime errors:
    - FileNotFoundException: "HADOOP_HOME and hadoop.home.dir are unset".
    - UnsatisfiedLinkError: NativeIO$Windows.access0, at DiskChecker.checkAccessByFileMethods.

NEXT GATE: Rerun LocalPipelineSuite and DblpPipelineMain on a Hadoop-capable runner, then test-engineer, then code-reviewer.
```

## Job graph

```text
datasets/samples/dblp/*.xml
  -> Job 1 dblp-extract: DblpXmlInputFormat -> DblpExtractMapper -> WorkIdPartitioner -> DblpExtractReducer
  -> outputs: part-r-* (WorkEnvelope JSON), works/, mentions/, rejects/v1/
  -> Job 6 team-extract: TextInputFormat over part-r-* -> TeamMapper -> WorkIdPartitioner -> TeamReducer
  -> outputs: teams/, authorpapers/, pairs/
```

## Key-value contracts

### Job 1: dblp-extract

- **Map input:** (LongWritable offset, Text with the whole record element).
- **Map output:** (Text workId, Text WorkEnvelope JSON).
- **Rejects:** named output `rejects/v1/part`, as (NullWritable, RejectRecord JSON).
- **Reduce output:**
  - main output: WorkEnvelope.
  - `works`: WorkRecord.
  - `mentions`: AuthorMention.
- **Duplicates:** values are sorted and the smallest is kept. Identical copies count as DUPLICATE. Copies that differ count as CONFLICT.

### Job 6: team-extract

- **Map input:** (LongWritable, Text WorkEnvelope JSON).
- **Map output:** (Text workId, Text WorkEnvelope JSON).
- **Reduce output:**
  - `teams`: TeamRecord.
  - `authorpapers`: AuthorPaperRecord.
  - `pairs`: MentionPairCandidate, n(n-1)/2 per work.

### Shared settings

- Intermediate map output is compressed with DefaultCodec.
- Both jobs default to 2 reducers, using WorkIdPartitioner: `(workId.hashCode & Int.MaxValue) % n`.

## Counters

- **Defined:** INPUT, ACCEPTED, REJECTED, DUPLICATE, CONFLICT, MISSING_KEY, SCHEMA_FAILURE, UNSUPPORTED_TYPE, OVERSIZE_TEAM, EMITTED_PAIR, TEAMS, AUTHOR_PAPERS.
- **Observed MapReduce values:** none. No job ran.
- **Parser level, observed in unit tests:**
  - dblp-sample.xml: 10 records. 6 accepted, 4 rejected.
  - dblp-malformed.xml: 3 records. 1 accepted, 2 rejected.

## Rejected records (parser level, observed in unit tests)

| Record | Reason |
|---|---|
| homepages/99/990001 | unsupported-record-type |
| homepages/99/990002 | unsupported-record-type |
| homepages/99/990009 | unsupported-record-type |
| keyless `<article mdate="2024-08-08">` | missing-key |
| journals/scsample/BadEntity24 | undeclared-entity |
| conf/scsample/Truncated24 | truncated-record |

Reject schema v1 fields: rejectSchemaVersion, reason, sourceDataset, sourceRecordId, snapshotId, pipelineRunId, inputOffset, detail.

## Output paths (layout only; nothing was written)

```text
<base>/run=run-v1-<hex>/snapshot=<snapshotId>/dblp-extract/{part-r-*, works/, mentions/, rejects/v1/}
<base>/run=run-v1-<hex>/snapshot=<snapshotId>/team-extract/{teams/, authorpapers/, pairs/}
```

Idempotency rules:
- If `_SUCCESS` exists, the existing output is reused.
- If the directory exists without `_SUCCESS`, the job fails. Nothing is overwritten.

## Team size and pair expansion

These numbers come from an awk count over the sample XML, not from a pipeline run.

- **Largest team:** journals/scsample/SampleP25, with 24 authors.
- **Largest pair expansion:** 276 pairs, from SampleP25.
- **Expected total pairs:** 285.
- **Expected OVERSIZE_TEAM:** 0 at the default threshold of 50.

## Largest blocking group

Not applicable. Blocking is Job 4, which is deferred.
