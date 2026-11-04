## Round 1: fast track (2026-11-03) — WO-08 embedding ingestion (storage part + RAG part)

Work order: docs/agent-runs/fast-track-phases-05-09.md, WO-08 (storage part, then RAG part, run sequentially in the main tree)
Reviewed: modules/storage/src/** (2 new, 5 modified) and modules/rag/src/** (7 new) vs 3c8c966; sbt check PASS (ER 19, storage 57 passed/2 skipped, core 145, rag 16, hadoop 66, contractTests 35, spark 3; 0 failed; observed by orchestrator); reviewer APPROVE (0 CRITICAL, 0 MAJOR)
Decision: ACCEPTED WITH OPEN RISKS
- Fixed in session: none needed
- Built: storage EmbeddingIngestion (skip-unchanged via VectorStore.getMetadata, text written to S3 before PutVectors, per-record REJECT items with attempt counts, outcome-encoded STEP names, abort after 3 consecutive empty batches); rag templates researcher-profile-template-v1, community-profile-template-v1, collaboration-path-template-v1, paper-summary-template-v1; Embedder port, HashedBagOfTokensEmbedder fake (scholarchain-fake-hashed-bow-v1, dim 256), production constant amazon.titan-embed-text-v2:0 (dim 1024, never called); semantic-context-format-v1 and SemanticRetriever. Smoke test: 4 profiles ingested through the fakes, hit@1 4/4, replay skipped 4/4
- Open: R-08-1, MINOR, no core semantic-summary record; SemanticSummary is storage-local; domain-engineer, when Phase 06b fixes the Spark summary format
- Open: R-08-2, MINOR, carried from R-07-3: core DocumentType has no topic or institution value, so nothing can be ingested into topic-institution-summary-v1 and no template exists; domain-engineer, before Phase 09
- Open: R-08-3, MINOR, core has no community IdKind, so community summaries (rag-local community:v1 IDs) are rejected by storage validation and collaboration-community-v1 stays empty; test asserts the rejection; domain-engineer, then rag-engineer switches communityId, before Phase 09
- Open: R-08-4, MINOR, chunk ID excludes the window; mitigated by window-specific sourceVersion (<template>/<start>-<end>); producers must keep this; rag-engineer / spark-engineer, Phase 06b
- Open: R-08-5, MINOR, changed text gives a new key and the stale vector stays in the index (no delete path); removal is data destruction and needs a human decision; storage-engineer / human, before batched AWS run
- Open: R-08-6, MINOR, a changed model version upserts in place, so old-model and new-model vectors coexist in one index until a full re-run (filter on modelVersion available; retriever always filters); storage-engineer, batched AWS run
- Open: R-08-7, MINOR, failure REJECT items accumulate across replays (append-only audit); rejects(runId) still shows earlier failures after a successful replay; storage-engineer, next storage change
- Open: R-08-8, MINOR, GetVectors 100-key limit, Titan V2 model ID and 1024 dimension unverified against AWS docs (aws-docs MCP did not connect); storage-engineer / rag-engineer, before batched AWS run
- Open: R-08-9, MINOR, abstracts truncated at 1500 code points instead of chunked (chunking deferred); rag-engineer, later phase
- Open: R-08-10, MINOR, fake embedder is lexical only, so the smoke test proves plumbing, not semantic quality; path summaries use the source researcher as entityId (no path IdKind); rag-engineer, real-model benchmark
- Open: R-08-11, MINOR, an aborted run records notAttempted only in the returned IngestionReport, not in the ledger; storage-engineer, next storage change
- Open: R-08-12, MINOR, batchSize accepts 1..500 but the fake embedder refuses more than 100 texts per call, so batchSize 101-500 with it fails every batch (default 100 is safe); storage-engineer / rag-engineer, next change
- Open: R-08-13, MINOR, researcherProfile does not check that representative works are in the window or belong to the researcher; rag-engineer, Phase 09 or when 06b supplies real inputs
- Open: R-08-14, MINOR, a PutVectors failure under one run followed by a successful replay under another run orphans the first run's semantic-context text object; storage-engineer / human, cleanup with R-08-5
Deferred scope: see WO-08 "Deferred" list (chunking, bounded AWS ingestion of 10,000 vectors, throughput and latency)
Manual changes: none
Accepted commit: 2da237eb9b14a0b1c131e50405ba14d339b42572
