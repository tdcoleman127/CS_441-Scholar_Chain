# code-reviewer: Phase 04 part B, round 1

Assembled by the orchestrator, 2026-10-29, from code-reviewer's final report.

**VERDICT: REQUEST_CHANGES** (2 MAJOR, 3 MINOR). Baseline HEAD 52ac59b; change set is the uncommitted working tree under modules/hadoop.

Reviewer's own run: `sbt test` exit 0 at 10:57:53 PM; core 143/143, hadoop 44/44; SourcePipelineSuite really ran in LocalJobRunner (21.7 s). `sbt check` was not run by the reviewer (it writes .claude/.last-test-run); partb-check1.log shows the check passing at 10:48:42 PM. Two early reviewer commands were blocked by guard-dangerous (double quotes; `git -C` read as `git -c`) and ran nothing.

## Findings

**F1. MAJOR: Job 7 holds and sorts a whole (entity, partition key) group in memory.**
SourceJobs.scala:403 `values.asScala.map(_.toString).toVector.sorted.foreach`. Pairs are keyed by workId, so all n(n-1)/2 pairs of one work land in one reduce call. Part A streams pairs (Teams.pairsFor iterator, Teams.scala:127) and still emits every pair for oversize teams (Jobs.scala:168-171). Failure input: one DBLP record with 3,000 authors gives 4,498,500 pair lines (~0.5 KB each), so Job 7 builds a >2 GB Vector in one call. Impact: reducer OOM on the full corpus. Fix direction: secondary sort with the line (or its hash) in the composite key; partition and group on entity plus partition key. Owner: hadoop-engineer.

**F2. MAJOR: Job 7 exports no topics or institutions; topic ID-to-label linkage is lost.**
SourceJobs.scala:410-421 (`Export.Entities` lacks topics and institutions); SourceNormalizers.scala:182 (`topicIds.distinct.sorted`) versus :220 (`topicLabels` in source order); :305-307 versus :311 (IDs and labels filtered separately). Work order item 7 requires "any topics or institutions present", and the report did not list it as unfinished. Failure input: OpenAlex `"topics":[{"id":"https://openalex.org/T2","display_name":"B"},{"id":"https://openalex.org/T1","display_name":"A"}]` gives topicIds [T1, T2] and topicLabels [B, A]. A label without an id shifts the lists further. Institutions keep IDs only; display_name dropped. Impact: Spark cannot build named topic or institution nodes. Fix direction: emit topic and institution records with ID, label, and provenance, and export them in Job 7. Owner: hadoop-engineer (domain-engineer if promoted to core).

**F3. MINOR: Job 3 MISSING_KEY counts every Crossref work, even linked ones.**
SourceJobs.scala:274-279 (`workKeys.size <= ownKey.size`); a Crossref work ID is `work:doi:<doi>`, so its own key is its only external ID. The Crossref line for 10.5555/sc441.0001 adds MISSING_KEY +1 while being a LINKED_KEY member (SourcePipelineSuite:133-137); 3 of the pinned 7 are Crossref. Impact: the counter stops signalling real missing identifiers. Owner: hadoop-engineer.

**F4. MINOR: Job 2 DUPLICATE/CONFLICT counting has no test that fails without it (law 2).**
SourceJobs.scala:178-179; only SourcePipelineSuite:113 checks `DUPLICATE + CONFLICT == 0`. Deleting lines 178-179 leaves all 44 tests green. Owner: hadoop-engineer.

**F5. MINOR: `blockstats` records carry no provenance (law 3).**
Blocking.scala:71-80; BlockStat has no run ID or source. A copied blockstats line names no run. All other new records carry provenance. Owner: hadoop-engineer.

## Verified, no finding

- Work-order compliance: jobs 2, 3, 4+5, 7 exist with the stated contracts; no thresholds, scores, or decisions (BlockingSuite:113-114 checks); imports are core, Hadoop, upickle, JDK only; build.sbt:126-127 `hadoop.dependsOn(core)`; nothing outside modules/hadoop changed besides orchestrator files.
- Identifier reuse via IdFormat and StableIds; interpolated work IDs equal StableIds.workId output (style only).
- Bounded blocking: processBlock holds at most maxBlock; partitioner and grouping comparator agree on the block key; tab ordering keeps blocks contiguous; no shared reducer state.
- Counters match both logs (268 = 181+46+30+9+2; 680 = 861-181; 17 = 11+6).
- arXiv namespace fix confirmed by reading: `xsi` declared only on the OAI-PMH root; the test would fail without SourceNormalizers.scala:401. DTDs and external entities stay disabled.
- Part A unchanged: ScCounter append-only; private[hadoop] is package-internal; LocalPipelineSuite pinned values pass. Side effect: DblpPipelineMain.report prints 8 extra zero counters (harmless).
- Report accuracy: matches partb-check1.log lines 64-73 and partb-test4.log. One understatement: RISK 8 says determinism skips graph-export, but SourcePipelineSuite:186-187 compares graph-export/candidates and graph-export/works; only the other export entities are unchecked.

## Reviewer risks (not findings)

- Hot blocks still go to one reducer: memory is bounded, time and I/O are not.
- Chunking drops 79% of pairs in the largest sample block (680 of 861); entity-resolution-engineer should review.
- Matrix lists blocking keys and match features under entity-resolution-engineer; the human prompt gave jobs 4 and 5 to hadoop. Not a path violation.

## Next gate

hadoop-engineer repairs F1 and F2 (F3 to F5 optional), then code-reviewer round 2. Then test-engineer adversarial part B and entity-resolution-engineer review of block keys, features, and chunking.
