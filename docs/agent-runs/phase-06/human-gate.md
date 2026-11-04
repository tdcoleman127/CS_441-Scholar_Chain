## Round 1: fast track (2026-11-02) — WO-06a Spark part A

Work order: docs/agent-runs/fast-track-phases-05-09.md, WO-06a
Reviewed: untracked modules/spark/src/** (PartA, ConnectedComponents, Support, PartASuite) vs 2f0d1c9; sbt check PASS (ER 19, core 145, storage 45 passed/2 skipped, hadoop 66, contractTests 35, spark 3; 0 failed; observed by orchestrator); reviewer REQUEST_CHANGES (F1, F2 MAJOR), repair-01, re-review APPROVE
Decision: ACCEPTED WITH OPEN RISKS
- Deviation from fast track: human chose to repair two MAJOR findings (plan repairs CRITICAL only); F2 policy (split conflicted clusters to singletons) was a human decision
- Fixed in session: F1 (forced broadcast of the graph-scale identity-cluster table collected it on the driver; hint removed, plan test added), F2 (conflicted clusters were merged and flagged, contrary to ER's never-merge rule; members now split to singletons with status conflicted-split, new invariants conflicted-split-not-singleton and conflict-pair-same-researcher). Fixture after repair: 14 researchers, 18 AUTHORED, 8 of 13 pairs kept, 6 edges, 5 components, largest-component fraction 0.5, all invariants 0
- Open: R-06a-1, MAJOR, researcher-ID anchor rule (smallest mention ID in cluster) is decided in Spark; ER should own it and emit cluster anchors, or every ID changes later; entity-resolution-engineer, before the first real-data run
- Open: R-06a-2, MINOR, accepted work-scope links are counted but not applied, so duplicate works across sources are not merged (safe for DBLP-only); entity-resolution-engineer / spark-engineer, when enrichment sources join
- Open: R-06a-3, MINOR, authorpapers input carries no ORCID, so no researcher:orcid IDs are produced; hadoop-engineer / spark-engineer, later phase
- Open: R-06a-4, MINOR, connected components needs about one iteration per unit of diameter and uses localCheckpoint (not fault-tolerant on EMR); fails closed at ccMaxIterations; spark-engineer, Phase 06b or EMR run
- Open: R-06a-5, MINOR, supportingWorkIds stored inline with no size cap and no pointer table; spark-engineer, Phase 06b export
- Open: R-06a-6, MINOR, manifest input checksums fingerprint the file listing (path, length), not contents; spark-engineer, next Spark change
- Open: R-06a-7, MINOR, stage metrics come from an async SparkListener (may miss final events); many count() actions inflate stage counts; spark-engineer, next Spark change
- Open: R-06a-8, MINOR, carried from R-05-5: Spark reads ER output files, but producing them still needs single-JVM ResolveMain; entity-resolution-engineer / spark-engineer, before EMR run
- Open: R-06a-9, MINOR, job compiles against Spark's Scala 2.13 artifacts; EMR Spark is built for Scala 2.12; unverified on EMR; build-engineer / spark-engineer, batched AWS run
- Open: R-06a-10, MINOR, Spark edge columns (jointPublicationCount, firstCollaborationYear, lastCollaborationYear) differ from core CoauthorEdge (coauthoredWorkCount, firstYear, lastYear); Phase 07 bulk load maps from core; spark-engineer / domain-engineer, Phase 06b export
- Open: R-06a-11, MINOR, whole-cluster split also un-merges members ER accepted with no conflict (x9 in fixture), trading recall for safety; size visible in conflictedClusterMentionsSplit; a finer link-cut policy is a human decision; entity-resolution-engineer, gold-set calibration
- Open: R-06a-12, MINOR, conflicted-endpoint reason and excludedConflictPairOccurrences are now structurally 0, kept only for schema stability; spark-engineer, decide in Phase 06b
- Open: R-06a-13, MINOR, mention-to-researcher join is now an unsalted shuffle on anchorMentionId; skew bounded by maxIdentityClusterSize; spark-engineer, EMR run
- Open: R-06a-14, MINOR, invariant failure path (exit 3) untested; pair-accounting and conflicted-split-not-singleton are structurally 0; F1 test covers attachResearchers only; a run that fails invariants still writes Parquet and manifest.json, so loaders must gate on invariants.json; spark-engineer / storage-engineer, Phase 06b
- Open: R-06a-15, MINOR, params.json researcherAnchorRule text is outdated for split mentions; unresolved-endpoint excluded rows look like self-pairs; PartAMain throws NumberFormatException instead of exit 2 on bad numeric args; dropDuplicates keeps an arbitrary row when duplicates differ; spark-engineer, next Spark change
- Open: R-06a-16, MINOR, no sample-data run: no Hadoop or ER outputs exist in the repo, so only the unit fixture was run; spark-engineer / deploy-engineer, batched AWS run
Deferred scope: see WO-06a "Deferred" list (WO-06b section)
Manual changes: none
Accepted commit: ab8c7696aeaa9b4d9fd21a55c1819476f902f459


## Round 2: fast track (2026-11-03) — WO-06b Spark part B

Work order: docs/agent-runs/fast-track-phases-05-09.md, WO-06b
Reviewed: modules/spark/src/** (3 new: GraphAlgorithms, PartB, PartBSuite; 3 modified: ConnectedComponents, Support, PartASuite) vs 8843d16; sbt check PASS (ER 19, core 160, storage 59 passed/2 skipped, rag 17, spark 9, contractTests 35, hadoop 66; 0 failed; observed by orchestrator); reviewer APPROVE (0 CRITICAL, 0 MAJOR)
Decision: ACCEPTED WITH OPEN RISKS
- Fixed in session: none needed
- Ownership clarifications applied by orchestrator: job 10 exports summary-input tables mapped to rag's input classes (rag owns rendering); job 11 writes Parquet plus one export manifest per target (storage owns loaders and Neptune CSV)
- Built: label propagation (label-propagation-v1-it<N>-selfvote-minlabel, synchronous, closed-neighbourhood vote, smallest-label tie-break, community IDs from core StableIds.communityId per R-08x-5), bridges (crossCommunityDegree, distinctCommunitiesTouched), k-core peeling (fails closed at kcoreMaxRounds), bounded BFS (statuses found, beyond-depth-limit, disconnected, endpoint-missing, invalid-pair), core-shaped export (researchers, works, coauthor-edges, researcher-metrics decode as core records), edge-supporting-works pointer table, 15 export tables, manifests for neptune, dynamodb, s3-vectors, s3 with combined Part A + Part B invariantStatus. Fixture: 6 communities [3,2,2,1,1,1], coreness {0:3, 1:4, 2:3}, A→D3 and X8→C found at depth 2, other statuses as expected, all 15 Part B and 18 Part A invariants 0; tamper test shows a Part A failure as "fail" in every manifest
- Closed by this round: R-06a-10 / R-07-2 (export maps jointPublicationCount→coauthoredWorkCount, firstCollaborationYear→firstYear, lastCollaborationYear→lastYear; rows decode as core CoauthorEdge); R-06a-12 decided (always-0 fields kept for schema stability); R-06a-14 partly (manifests carry invariant status so loaders can refuse a failing run)
- Open: R-06b-1, MINOR, R-06a-4 partly addressed: reliable checkpoint only when a SparkContext checkpoint dir is set, else localCheckpoint; checkpoint files not cleaned up; PartBMain never calls setCheckpointDir, and the advised spark.checkpoint.dir key may only be honored from Spark 4.0 (ledger pins 3.5.8; unverified), so EMR likely falls back to local checkpoints; spark-engineer / infra-engineer, before EMR run
- Open: R-06b-2, MINOR, inline supportingWorkIds stays uncapped because core CoauthorEdge requires it (pointer table added); spark-engineer / domain-engineer, if prolific pairs prove large
- Open: R-06b-3, MINOR, a giant community concentrates memberIds and the community-ID hash input in one row and task; hub vertices skew the label-propagation vote; spark-engineer, EMR run
- Open: R-06b-4, MINOR, BFS cost grows with pairs × reachable vertices; intended for small benchmark sets only; spark-engineer, EMR run
- Open: R-06b-5, MINOR, rag inputs need joins from exported IDs; orcid null, institutionIds, externalIds and topicIds empty, no abstracts, no betweenness or pagerank; exported WorkRecord rows have empty externalIds because Part A works drop the Hadoop externalIds, so Neptune loses DOIs; spark-engineer / rag-engineer, Phase 09 or later
- Open: R-06b-6, MINOR, edge confidence fixed at 1.0; ER match confidence not propagated; spark-engineer / entity-resolution-engineer, gold-set calibration
- Open: R-06b-7, MINOR, fixture community split depends on researcher-ID hash order; test compares that split with a reference transcription (refLp), backed by order-free assertions; spark-engineer, next Spark change
- Open: R-06b-8, MINOR, several new invariants are structurally 0 (community-size-members, export-edge-count, community-id-duplicate except on hash collision, first clause of bridge-inconsistent), so "15 invariants" overstates independent checking; spark-engineer, next Spark change
- Open: R-06b-9, MINOR, PartBMain silently drops malformed path-pair lines (no status row); GraphAlgorithms comment says checkpoint mode is in each run's params.json but only Part B records it; spark-engineer, next Spark change
- Open: R-06b-10, MINOR, PartBMain, stage metrics, skew and partition write-ups, and EMR are untested; only the local fixture ran; spark-engineer / deploy-engineer, batched AWS run
Deferred scope: see WO-06b "Deferred" list
Manual changes: none
Accepted commit: 71bb200db5f31c1f08bb4e175112d28dce9c115d
