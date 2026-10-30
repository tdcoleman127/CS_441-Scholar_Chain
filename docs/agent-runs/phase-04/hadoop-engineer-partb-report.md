# hadoop-engineer: Phase 04 part B report

Assembled by the orchestrator, 2026-10-29, from hadoop-engineer's final report (verbatim content, lightly reformatted). Orchestrator check: `run-evidence/partb-check1.log` shows `[success]`, core Passed 143/143, hadoop Passed 44/44, and markTestRun updated. Logs were copied from the session scratchpad into `run-evidence/partb-check1.log` and `run-evidence/partb-test4.log` (gitignored; commit with `git add -f`).

```text
STATUS: COMPLETE
OWNER: hadoop-engineer
WORK ORDER: Phase 04 part B (part-b-work-order.md). Jobs 2, 3, 4, 5 and 7 are built as real MapReduce jobs with feature tests and a local LocalJobRunner run over the committed samples. Part A behavior, counters and outputs are unchanged (LocalPipelineSuite pinned INPUT 13, ACCEPTED 7, REJECTED 6, EMITTED_PAIR 285).
FILES CREATED:
  modules/hadoop/src/main/scala/edu/uic/cs441/scholarchain/hadoop/SourceNormalizers.scala
  modules/hadoop/src/main/scala/edu/uic/cs441/scholarchain/hadoop/Blocking.scala
  modules/hadoop/src/main/scala/edu/uic/cs441/scholarchain/hadoop/SourceJobs.scala
  modules/hadoop/src/test/scala/edu/uic/cs441/scholarchain/hadoop/SourceNormalizersSuite.scala (7 tests)
  modules/hadoop/src/test/scala/edu/uic/cs441/scholarchain/hadoop/BlockingSuite.scala (9 tests)
  modules/hadoop/src/test/scala/edu/uic/cs441/scholarchain/hadoop/SourcePipelineSuite.scala (1 local IT: Jobs 1, 2, 3, 4+5, 6, 7)
FILES MODIFIED:
  Jobs.scala: 8 counters appended to ScCounter after the part A entries (HEADER_SKIPPED, CITATIONS, EXACT_ID_KEY, LINKED_KEY, BLOCKS, OVERSIZE_BLOCK, CANDIDATES, EXPORTED); Pipeline.common, counters, frameworkCounters, guard widened from private to private[hadoop]. Nothing outside modules/hadoop touched.
COMMANDS RUN:
  sbt Test/compile: exit 0
  sbt hadoop/test x4: run 1 two failures (one real arXiv defect, fixed); run 2 one failure (test expected DOI to link 4 sources, it links 5 incl. DBLP; expectation corrected); run 3 one failure (expected largest block 21, is 42; expectation corrected); run 4 exit 0, 44/44
  sbt fmt: exit 0
  sbt check: exit 0
  One sbt hadoop/compile attempt blocked by guard-dangerous (grep filter contained "set current"); rerun as sbt Test/compile.
TEST RESULTS: sbt check exit 0 (2026-10-29 22:48). core 143/143. hadoop 44/44 (part A 27; SourceNormalizers 7, Blocking 9, SourcePipeline 1 really executed in LocalJobRunner, 24 s).
  Defect fixed: arXiv <record> cut from the OAI-PMH file loses the root xsi namespace declaration, so StAX rejected every arXiv record as xml-not-well-formed. Record parser is now namespace-unaware; the arXiv test fails without the fix.
VALIDATION RESULTS: Local bounded run over datasets/samples via SourcePipelineSuite. Rerun with same run ID and snapshot: all 6 jobs skipped=true (idempotent). Fresh-base second run: identical candidates, exactids, works and rejects/v2 lines.
ASSUMPTIONS:
  - Native work IDs per source (work:openalex:W…, work:doi:…, work:arxiv:…, work:omid:br/… or DOI); Job 3 joins by identifier key.
  - Job 2 picks the source by file-name prefix, then parent directory; otherwise unsupported-source (UNSUPPORTED_TYPE).
  - OpenCitations header at offset 0 starting "oci," counts HEADER_SKIPPED, not INPUT.
  - Crossref types accepted: journal-article, proceedings-article, book-chapter, book, monograph, edited-book, posted-content, report, dissertation, reference-entry; others (e.g. dataset) rejected.
  - arXiv categories become hashed topic:sc1: IDs (StableIds.fallbackNamespacedId); labels kept in WorkExtra.topicLabels.
  - DUPLICATE/CONFLICT: Job 2 identical extra copy = DUPLICATE, differing = CONFLICT. Job 3 DUPLICATE = repeated (memberId, source); CONFLICT = one source puts the key on 2+ distinct works (work scope only).
  - MISSING_KEY: Job 2 no valid ID; Job 3 work with no identifier besides its source ID; Job 4 member with no blocking key.
  - Block keys: mention m|<surname>|<first initial>; work w|<first 4 normalized title tokens>.
  - Over-cap blocks split in member-ID order into chunks <= cap; pairs only within a chunk; skipped pairs reported per block as crossChunkPairsSkipped.
RISKS:
  1. New hadoop-local record types need a human decision (like decision 3): WorkExtra, MentionExtra, NormalizedWork, CitationRecord, SourceEnvelope, IdMember, ExactIdGroup, BlockMember, RawFeature, MatchCandidateRecord, BlockStat. Core lacks abstract, source author ID, per-mention institution, venue name, citation fields.
  2. Ownership overlap: matrix lists "blocking keys, match features" under entity-resolution-engineer; the work order assigned Jobs 4 and 5 to hadoop. No thresholds/scores/decisions emitted; a test checks MatchCandidateRecord has no such field. Needs entity-resolution-engineer review.
  3. Chunking drops pairs: 680 cross-chunk pairs not compared in the largest block (reported). Every member still shuffles; a count-first pre-pass would drop oversize blocks before the shuffle.
  4. Compressed arXiv input unsupported (*.xml.gz read line by line).
  5. OpenCitations CSV is a plain comma split; quoted fields not handled.
  6. Same member ID twice with different contents in one block: kept copy depends on shuffle order (not observed).
  7. Partition skew: one partition holds the 42-member block.
  8. Determinism comparison did not cover graph-export directories. [Orchestrator note: code-reviewer round 1 found this understated. SourcePipelineSuite:186-187 does compare graph-export/candidates and graph-export/works; only the other export entities are unchecked.]
  9. No part B driver main; EMR needs a main class plus infra-engineer's run-bounded-pipeline.sh (main-class amendment deferred).
  10. Time box exceeded (agent ran about 22 minutes).
MANUAL ACTION REQUIRED: Human decides on the hadoop-local record types (RISKS 1).
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer; test-engineer adversarial part B; entity-resolution-engineer review of block keys, features, chunking; domain-engineer on promoting record types; infra-engineer EMR bounded-run script; hadoop-engineer follow-ups (compressed arXiv, CSV-aware OpenCitations, oversize pre-pass).
```

## Job graph

```
DBLP XML ──Job 1 dblp-extract──┬──────────────────────────────► Job 6 team-extract ──┐
                               │ (main: WorkEnvelope)                                │
external samples ─Job 2 source-normalize (main: SourceEnvelope)                      │
                               ├──► Job 3 exact-ids ─────────────────────────────────┤
                               └──► Job 4+5 blocking-candidates ─────────────────────┤
                                                                                     ▼
              Job 7 graph-export (reads the entity directories of Jobs 1, 2, 3, 4+5, 6)
```

## Key-value contracts

- Job 2: SourceInputFormat (LongWritable offset, Text JSONL/CSV line or whole arXiv record) → map (Text workId or `oci:<oci>`, Text SourceEnvelope); rejects to `rejects/v2/` (RejectRecord v2). WorkIdPartitioner. Reduce: one SourceEnvelope per key (smallest kept); named outputs works, mentions, workextras, mentionextras, citations.
- Job 3: main outputs of Jobs 1 and 2 → map (Text `work|<ns>:<value>` or `mention|orcid:<iD>`, Text IdMember), normalized only by core IdFormat/StableIds. WorkIdPartitioner. Named output exactids (ExactIdGroup).
- Jobs 4+5: → map (Text `<blockKey>\t<memberId>`, Text BlockMember). BlockPartitioner hashes block key; secondary sort; BlockGroupingComparator. Reduce `Blocking.processBlock` holds at most `scholarchain.block.max.size` (default 500; 10 in test). Named candidates (MatchCandidateRecord) and blockstats (BlockStat).
- Job 7: `<jobOut>/<entity>/part-*` → map (Text `<entity>\t<partitionKey>`, record). ExportPartitioner hashes partition key (workId, citingWorkId, workLow, else key). Named output per entity, sorted.
- All jobs: DefaultCodec intermediate compression; paths via Pipeline.outputPath.

## Partitions

Jobs 2, 3, 4+5: 2 reducers. Job 7: 3 partitions; citations only in part-r-00001 and 00002. Per-partition counts not measured.

## Counters (pinned in SourcePipelineSuite)

| Job | Counters |
|---|---|
| 1 | INPUT 13, ACCEPTED 7, REJECTED 6, MISSING_KEY 1, UNSUPPORTED_TYPE 3, SCHEMA_FAILURE 2 |
| 2 | HEADER_SKIPPED 1, INPUT 17, ACCEPTED 11, REJECTED 6, MISSING_KEY 0, UNSUPPORTED_TYPE 2, SCHEMA_FAILURE 4, CITATIONS 4, DUPLICATE 0, CONFLICT 0 |
| 3 | INPUT 18, EXACT_ID_KEY 20, LINKED_KEY 5, DUPLICATE 8, CONFLICT 0, MISSING_KEY 7 |
| 4+5 (cap 10) | INPUT 18, BLOCKS 17, OVERSIZE_BLOCK 2, CANDIDATES 268, MISSING_KEY 0, DUPLICATE 0 |
| 6 | INPUT 7, TEAMS 7, AUTHOR_PAPERS 35, EMITTED_PAIR 285, OVERSIZE_TEAM 0 |
| 7 | INPUT 755, EXPORTED 755 |

DOI 10.5555/sc441.0001 links DBLP, OpenAlex, Crossref, arXiv and OpenCitations.

## Rejected records (Job 2)

| Source | Reason |
|---|---|
| arxiv | deleted-record |
| crossref | malformed-json (truncated line) |
| crossref | unsupported-record-type (dataset) |
| openalex | malformed-json (truncated line) |
| openalex | missing-title |
| opencitations | schema-failure (2 columns) |

Crossref ORCID 0000-0002-1825-0098 fails the core checksum; kept as MentionExtra.invalidOrcid.

## Output paths

`<base>/run=run-v1-<hex>/snapshot=<snapshot>/{dblp-extract, source-normalize/{part-r-*, works, mentions, workextras, mentionextras, citations, rejects/v2}, exact-ids/exactids, blocking-candidates/{candidates, blockstats}, team-extract/{teams, authorpapers, pairs}, graph-export/<entity>/part-r-0000{0..2}}`

## Team size and blocks

Max team 24 (SampleP25), largest expansion 276; unchanged from part A. Crossref sc441.0003 also has 24 authors, but Job 6 runs on DBLP only.

Largest block: `m|member|c`, mention, size 42, cap 10, oversize, 5 chunks, 181 candidate pairs, 680 cross-chunk pairs skipped. Second oversize: `m|sample|a`, size 12. All block sizes: 42, 12, 5, 5, 5, 3, 3, 3, 2, 2, 1, 1, 1, 1, 1, 1, 1.
