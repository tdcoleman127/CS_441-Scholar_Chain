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


--Phase 06b upcoming--