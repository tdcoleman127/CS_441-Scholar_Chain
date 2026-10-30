# code-reviewer: Phase 04 part B, round 2 (after repair 1)

Assembled by the orchestrator, 2026-10-29, from code-reviewer's final report.

**VERDICT: APPROVE** (0 CRITICAL, 0 MAJOR, 2 MINOR; neither MINOR blocks).

The reviewer's own `sbt test` passed (`[success]` at 11:56:04 PM): core 143/143, hadoop 50/50. The reviewer did not run `sbt check` (it writes .claude/.last-test-run); the repair log shows `[success]` at 11:49:28 PM with the same counts. Three reviewer commands were blocked by guard-dangerous (double quotes, `git -C`, `cd ... &&`) and ran nothing.

## Verified

- **F1 fixed.** The composite key is `<entity>\t<pk'>\t<line>`, where pk' has every character below a space turned into a space. The byte sort keeps groups contiguous whatever bytes the line holds. The grouping comparator uses the same prefix via `getLength`; the partitioner hashes pk'. The reduce streams: `ReduceContextImpl` deserializes into the shared key on each `next()`, and `streamGroup` (SourceJobs.scala:498-503) holds one line at a time. Output is deterministic. BlockingSuite:139-155 fails against a buffering version.
- **F2 fixed.** Core Topic and Institution records are validated (SourceNormalizers.scala:242-245) and carry provenance. Pairing is per entry, reduced per ID with the smallest label winning (:180-183). Edge inputs are correct. Partitioning is on topicId and institutionId. SourceNormalizersSuite:89-127 fails without the fix.
- **F3 fixed.** SourceJobs.scala:286-290 compares key namespace with source wire name (Enums.scala:11-15, :173-176). BlockingSuite:81-91 fails against the old rule.
- **F4 fixed.** SourcePipelineSuite:95-129 fails if SourceJobs.scala:181-182 are deleted.
- **F5 fixed.** BlockStat has pipelineRunId and sourceDatasets; tested at BlockingSuite:104-116.
- **No regressions.** LocalPipelineSuite is unmodified. The Jobs.scala diff is the same as round 1. MISSING_KEY 7 → 4 and EXPORTED 755 → 759 are justified and match log lines 62 and 65; REDUCE_INPUT_GROUPS 95 and the part layout match line 69.
- **No stray files** (`git status --ignored`); no scratch scripts in the repository.

## Findings

1. **MINOR: no test fails if Job 7's partitioner wiring is removed (law 2).** SourceJobs.scala:638 `job.setPartitionerClass(classOf[ExportPartitioner])`. Delete it and all 50 tests pass: HashPartitioner on the whole composite key scatters one work's entities across part files, breaking the co-partitioning promised at :386-387. The determinism test compares two runs that would both use HashPartitioner. Fix direction: in SourcePipelineSuite, assert that each work ID's works, mentions, and workextras records land in the same part number. Owner: hadoop-engineer.
2. **MINOR: the repair report overstates one test (law 7).** hadoop-engineer-partb-repair1-report.md:9 says SourcePipelineSuite "checks each exported entity equals its inputs". SourcePipelineSuite.scala:226 checks only pairs, works, citations, and topics. Institutions and candidates are checked by count only; mentions, workextras, mentionextras, teams, authorpapers, and exactids are not compared. Owner: hadoop-engineer (wording); the orchestrator annotated the assembled file.

## Risks (not findings)

- Topic and Institution are emitted per (work, ID); Spark must deduplicate and pick one label when works disagree.
- A topic or institution ID with no label has no named node.
- Job 7 shuffle volume grows because the line is in the key.

## Next gate

Optionally, hadoop-engineer closes MINOR 1. Then test-engineer adversarial part B, and the entity-resolution blocking findings (`entity-resolution-engineer-review-partb.md`).
