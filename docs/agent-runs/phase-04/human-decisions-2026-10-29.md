# Phase 04: human decisions stated in session, 2026-10-29

Recorded by the orchestrator from the human's chat message, as input for `human-gate.md` (part B). This is not the gate. The human writes `human-gate.md`.

| # | Question (source) | Human decision | Routed to |
|---|---|---|---|
| 1 | Hadoop-local record types (`hadoop-engineer-partb-report.md` RISKS 1: WorkExtra, MentionExtra, NormalizedWork, CitationRecord, SourceEnvelope, IdMember, ExactIdGroup, BlockMember, RawFeature, MatchCandidateRecord, BlockStat) | Accept them in the hadoop module | none (no change) |
| 2 | Block keys, feature list, and chunking policy (report RISKS 2 and 3; `code-reviewer-report-partb-round1.md` reviewer risks) | Review them | entity-resolution-engineer (read-only review; findings go to hadoop-engineer) |
| 3 | code-reviewer part B round 1 REQUEST_CHANGES (F1 to F5) | Run the repair quickly | hadoop-engineer (part B repair 1) |
| 4 | Ownership of blocking keys, raw match features, and the block cap or window policy (`entity-resolution-engineer-review-partb.md`, E-F11) (stated 2026-10-30) | Option b: entity-resolution-engineer publishes a versioned blocking and feature spec in its own module; hadoop-engineer implements it in modules/hadoop; entity-resolution-engineer signs off on each change. No module-table or constitutional change | entity-resolution-engineer (spec, in a later phase), then hadoop-engineer |
| 5 | Entity-resolution recall findings E-F1 to E-F10 (stated 2026-10-30) | Record them as known risks for part B; do not fix in phase 04 | none now; deferred |
| 6 | Main-class run rule amendment round 2 (`code-reviewer-report-amendment-round2.md`: REQUEST_CHANGES, N1 and N2 MAJOR) (stated 2026-11-01) | Defer: finish Phase 4 without ratifying; the amendment stays unratified and the live constitution is unchanged | factory-engineer round 3, in a later phase |
