## Round 1: fast track (2026-11-02) — WO-07 storage

Work order: docs/agent-runs/fast-track-phases-05-09.md, WO-07
Reviewed: untracked modules/storage/src/** (14 main, 6 test) vs 2f0d1c9; sbt check PASS (ER 19, core 145, storage 45 passed/2 skipped, hadoop 66, contractTests 35, spark 3; 0 failed; observed by orchestrator); reviewer REQUEST_CHANGES (F1, F2, F3 MAJOR), repair-01, re-review APPROVE
Decision: ACCEPTED WITH OPEN RISKS
- Deviation from fast track: human chose to repair three MAJOR findings (plan repairs CRITICAL only); first attempt ran in a .claude/worktrees git worktree and was blocked by guard-ownership (nothing written), so WO-07 was rerun in the main tree after Phase 06a
- Fixed in session: F1 (Neptune bulk-load writer aborted on its own rejects because the reject key was not unique; sort key is now REJECT#<scope>#<key>#<digest of reason and provenance>), F2 (vector reload failed when conflicting duplicates arrived in a different order, and dropped the second record's provenance; one reject per record, order-independent), F3 (RunLedger putStep reported a changed status as AlreadyPresent; status now in the hashed payload, so a change is a ConditionalConflict); also S3 HeadObject 404 after PutObject 409 is now retryable
- Open: R-07-1, MINOR, Neptune openCypher syntax (LIMIT $limit, all()), empty Int fields in bulk-load CSV, S3 Vectors limits (500 per PutVectors, topK 30, 2 KB filterable metadata) and filter syntax unverified against AWS docs (aws-docs MCP did not connect); storage-engineer, before batched AWS run
- Open: R-07-2, MINOR, Spark 06a edge columns differ from core CoauthorEdge; bulk load maps from core, so Spark output needs a mapping (same as R-06a-10); spark-engineer / domain-engineer, Phase 06b
- Open: R-07-3, MINOR, core DocumentType has no topic or institution value, so topic-institution-summary-v1 vectors lack a precise docType; domain-engineer, before Phase 08
- Open: R-07-4, MINOR, no live Neptune query executor (no neptunedata client, by design); GraphStore has only the in-memory fake; build-engineer / storage-engineer, Phase 09 or batched AWS run
- Open: R-07-5, MINOR, newlines in titles are flattened to spaces in the bulk-load CSV; storage-engineer, next storage change
- Open: R-07-6, MINOR, DynamoDB and S3 adapters have no emulator contract tests (fakes plus gated live suite only); storage-engineer, batched AWS run
- Open: R-07-7, MINOR, no versioned step-status transition; changing a step's status is a ConditionalConflict; storage-engineer, when a runner writes steps
- Open: R-07-8, MINOR, Neptune manifest rejectCount can exceed distinct ledger reject items because identical rejects collapse; storage-engineer, next storage change
- Open: R-07-9, MINOR, reject digest is a 64-bit prefix (a collision surfaces as ConditionalConflict), has no domain tag, and NFC-equivalent rejects collapse into one item; storage-engineer, next storage change
- Open: R-07-10, MINOR, S3 404-after-409 retry is tested only against a stub S3Client; storage-engineer, batched AWS run
- Open: R-07-11, MINOR, rejects(runId, limit) truncates past the limit with no pagination (MANIFEST and STEP items share the partition); storage-engineer, next storage change
- Open: R-07-12, MINOR, path templates order only by length (ties nondeterministic under LIMIT) and openCypher can revisit nodes, while the fake never does; storage-engineer, with the live executor
- Open: R-07-13, MINOR, infra must create vector indexes named <storage.vector-index>-<logical> with textRef non-filterable, TTL on query-session ttl, and grant the storage IAM list (no delete actions); infra-engineer, Terraform pass
- Open: R-07-14, MINOR, process: agent worktree sessions cannot write, because guard-ownership resolves paths against the main tree and .claude/worktrees/* has no owner; fast-track Session B runs sequentially in the main tree until factory-engineer proposes an amendment and the human ratifies it
Deferred scope: see WO-07 "Deferred" list
Manual changes: none
Accepted commit: <40-character hash>
