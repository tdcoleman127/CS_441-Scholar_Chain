# Phase 00-09 MINOR fixes

Prepared by: orchestrator, 2026-11-07. Companion to `phase-00-09-review.md`.

Scope: every item logged as MINOR, LOW, or VERY MINOR in the Phase 00-09 human gates, plus minor findings that the gates reference only by count (taken from the code-reviewer and agent reports they cite). The list is chronological by phase, then in the order logged. Items a later round closed are listed under "Closed" at the end of each phase so nothing is lost. CRITICAL, MAJOR, HIGH, and MEDIUM items are not here; see `phase-00-09-review.md` section 3.

Columns: **Owner** is the agent that fixes it (or human). **Target** is when the gate said to fix it.

## Phase 00

No MINOR items logged.

## Phase 01 (agent factory)

| ID | Item | Owner | Target |
|---|---|---|---|
| R3 | Hook runtime on very long commands not measured; a hook timeout fails open. **2026-11-07: confirmed and re-ranked to at least MAJOR by repair-02** (50,105-character command did not finish in 120 s; hook timeout is 20 s). Needs a fail-closed length cap; no longer MINOR, kept here for traceability | factory-engineer | follow-up to repair-02 |
| R4 | `git -C <path>` is rejected for code-reviewer as if it were `git -c`. **Fixed in the repair-02 proposal (not yet ratified)** | factory-engineer | repair-02 ratification |
| Probe 3 note | The guard matches text, not behavior: `echo 'terraform destroy'` is blocked (false positive, accepted) | factory-engineer | optional |
| Probe 5 | Deferred "to the end of Phase 2"; no gate records it being run | human / orchestrator | rerun now that `sbt check` exists |

## Phase 02 (build)

| ID | Item | Owner | Target |
|---|---|---|---|
| P02-1 (VERY MINOR) | Scala version facts were not rechecked; they came from the agent's own lookup | dependency-updater | before Phase 10 (human asked to check Scala 3 version) |
| P02-2 | Weak Docker image check: the `FROM` line in the Dockerfile could change to another image without failing the build; it should require the pinned image | build-engineer | next build change |
| P02-3 (housekeeping) | `phase-02/human-gate.md` still says `Accepted commit: <40-character hash>`; should be `313bb4bd025bfd86348bbbff19bb9afb9eec42f8` | human (human-gate files are human-only) | any time |

## Phase 03 (contracts and datasets)

| ID | Item | Owner | Target |
|---|---|---|---|
| R03-3 (LOW) | The rule against raw affiliation text in evidence snippets is documented but not checked automatically | domain-engineer / rag-engineer | later phase |
| P03-1 | The short-name docs in docs/agents.md understate two harmless over-blocks | factory-engineer | next docs/agents.md amendment |
| P03-2 | A few dataset facts are still unknown: OpenAlex snapshot, OpenCitations date, DBLP size (logged as partially closed) | dataset-engineer | before the batched AWS run |

Closed: F3 (sample-extract size cap) and F6 (backslash `--out` on Linux) were repaired in Round 3. F4 (stale agent report) was fixed in the report. Round 3 F4 (`case ... esac` over-block) belonged to the superseded Round 3 patch, which was never applied. Round 3 F5 became RR-06.

## Phase 04 (Hadoop)

Part A remaining risks (no severity given; logged as accepted risks):

| ID | Item | Owner | Target |
|---|---|---|---|
| P04A-1 | Rejects identify files by base name only; same-named files in different folders are ambiguous | hadoop-engineer | next Hadoop change |
| P04A-2 | Offsets are per file (decompressed-stream offsets for `.gz`); unsuffixed gzip is read raw | hadoop-engineer | next Hadoop change |
| P04A-3 | Pair skew: 279 of 285 pairs on one partition (one 24-author paper) | hadoop-engineer | EMR run |
| P04A-4 | REJECTED includes DBLP `www` person records (also counted as UNSUPPORTED_TYPE) | hadoop-engineer | next Hadoop change |

Part B:

| ID | Item | Owner | Target |
|---|---|---|---|
| E-F8 (LOW-MEDIUM) | Mention context uses full coauthor names; use `surname|initial` | hadoop-engineer (per ER blocking spec) | ER blocking spec |
| E-F9 (LOW) | Self-exclusion from context uses the list index `authorPosition`; exclude by position match | hadoop-engineer | ER blocking spec |
| E-F10 (LOW) | MatchCandidateRecord and BlockStat carry no `blockingVersion` or `featureVersion` | hadoop-engineer | ER blocking spec |
| P04B-1 | Real-data gaps: compressed arXiv, quoted CSV fields, very large blocks | hadoop-engineer | before real-data run |
| P04B-2 | 680 of 861 pairs skipped in the largest sample block (42 members, cap 10) | hadoop-engineer / entity-resolution-engineer | with E-F1 window fix |
| P04B-3 | Topic and institution records repeat per work; Spark must deduplicate them | spark-engineer | when topic and institution tables are built |

E-F1 to E-F7 (HIGH and MEDIUM blocking-recall findings) and E-F11 (ownership) are tracked in `phase-04/entity-resolution-engineer-review-partb.md` and are not MINOR.

Part C (closeout):

| ID | Item | Owner | Target |
|---|---|---|---|
| CO-1 | `SourcePipelineMain` outputBase rejection branch is untested (SourcePipelineMain.scala:81-83) | hadoop-engineer | next Hadoop change |
| CO-2 | Duplicate-input check catches only identical strings (`./in/...`, `file:///...`, globs read the file twice) | hadoop-engineer | next Hadoop change |
| CO-3 | The XXE test leaves an `sc-xxe*` temp folder and `secret.txt` behind on every `sbt check` (HadoopMalformedInputSuite.scala:181-183) | test-engineer | next test pass |
| CO-4 | CDATA or a comment containing a record start tag ends a record early (the record is rejected) | hadoop-engineer | next Hadoop change |
| CO-5 | A blank normalized name rejects the whole work; Indic vowel stripping can merge names | hadoop-engineer / entity-resolution-engineer | gold-set calibration |
| CO-6 | Main class and EMR script never executed; no Terraform yet | deploy-engineer / infra-engineer | batched AWS run |

Deferred main-class amendment (fold into amendment round 3, factory-engineer): round 1 F3 (R7C-JVM bypass classes not named), F4 (run-local-main ask is a raw-text regex), F5 (undocumented false positives), F6 (section 7c text disagrees with the guard), F7 (S1-S9 gaps); round 2 N3 (path resolver and MSYS disagree about `/tmp`), N4 (`lm_simple` ignores backslash escaping on `&`), N5 (F5 fix incomplete).

Closed: Part A round 1 F2 and round 2 #2 (snapshotId and runId validation) before round 5 APPROVE. Part B round 1 F3 to F5 in repair 1. Part B round 2 #1 (Job 7 partitioner test) in the closeout. Report-wording items (closeout #3, closeout round 2 #2, Part B round 2 #2) were annotated in the reports.

## Phase 05 (WO-00 dependencies, WO-05 entity resolution)

| ID | Item | Owner | Target |
|---|---|---|---|
| R-00-1 | Services can import Bedrock and storage SDK classes through `dependsOn(rag, storage)`; reviewer greps each phase | service-engineer / code-reviewer | Phase 09+ (services phase) |
| R-00-2 | Only url-connection-client; streaming or async SDK calls need netty-nio-client | build-engineer | on request |
| R-00-3 | AWS SDK releases daily; expect frequent bump proposals | dependency-updater | ongoing |
| R-05-1 | Core MatchDecision cannot represent pair decisions, conflict outcome, or match path; ER emits a local ResolutionRecord | domain-engineer | before Phase 09 (overdue) |
| R-05-2 | Hadoop Job 5 candidates carry no names or ID values (proxy name score, no initials, ORCID-only conflicts) | hadoop-engineer | per blocking spec |
| R-05-3 | Token-set proxy name scores 1.0 for permuted or repeated labels; missing initials raise confidence | entity-resolution-engineer | gold-set calibration |
| R-05-4 | Policy UNCALIBRATED; no human-labeled pairs | human, then entity-resolution-engineer | now: worksheet ready at `modules/entity-resolution/gold/v1/labeling-worksheet-v1.csv` (48 pairs) |
| R-05-5 | ResolveMain reads one local file into one JVM; not an EMR-scale path | entity-resolution-engineer / spark-engineer | before EMR run |
| R-05-6 | ReviewItem lacks the ER run ID; `er-policy-v1.conf` holds er-policy-v2 | entity-resolution-engineer | next ER change |

## Phase 06 (Spark)

Part A (R-06a-1 is MAJOR and not listed):

| ID | Item | Owner | Target |
|---|---|---|---|
| R-06a-2 | Accepted work-scope links are counted but not applied; duplicate works across sources are not merged | entity-resolution-engineer / spark-engineer | when enrichment sources join |
| R-06a-3 | authorpapers input carries no ORCID, so no `researcher:orcid` IDs | hadoop-engineer / spark-engineer | later phase |
| R-06a-4 | Connected components needs one iteration per unit of diameter; localCheckpoint is not fault-tolerant (see R-06b-1) | spark-engineer | EMR run |
| R-06a-5 | supportingWorkIds stored inline with no size cap (pointer table added in 06b; see R-06b-2) | spark-engineer | partly closed |
| R-06a-6 | Manifest input checksums fingerprint the file listing, not contents | spark-engineer | next Spark change |
| R-06a-7 | Stage metrics come from an async SparkListener; many `count()` actions inflate stage counts | spark-engineer | next Spark change |
| R-06a-8 | Spark reads ER output, but producing it still needs single-JVM ResolveMain (same as R-05-5) | entity-resolution-engineer / spark-engineer | before EMR run |
| R-06a-9 | Compiled against Spark's Scala 2.13 artifacts; EMR Spark is built for Scala 2.12; unverified on EMR | build-engineer / spark-engineer | before EMR run (potential blocker) |
| R-06a-11 | Whole-cluster split also un-merges members ER accepted with no conflict (recall traded for safety) | entity-resolution-engineer | gold-set calibration |
| R-06a-13 | Mention-to-researcher join is an unsalted shuffle on anchorMentionId | spark-engineer | EMR run |
| R-06a-14 | Invariant failure path (exit 3) untested; a failing run still writes Parquet and manifest.json (manifests now carry invariant status) | spark-engineer / storage-engineer | partly closed in 06b |
| R-06a-15 | Outdated researcherAnchorRule text; unresolved-endpoint rows look like self-pairs; NumberFormatException instead of exit 2; `dropDuplicates` keeps an arbitrary row | spark-engineer | next Spark change |
| R-06a-16 | No sample-data run; only the unit fixture | spark-engineer / deploy-engineer | batched AWS run |

Part B:

| ID | Item | Owner | Target |
|---|---|---|---|
| R-06b-1 | Reliable checkpoint only if a checkpoint dir is set; PartBMain never sets it; files not cleaned up | spark-engineer / infra-engineer | before EMR run |
| R-06b-2 | Inline supportingWorkIds stays uncapped because core CoauthorEdge requires it | spark-engineer / domain-engineer | if prolific pairs prove large |
| R-06b-3 | A giant community concentrates in one row and task; hub vertices skew the vote | spark-engineer | EMR run |
| R-06b-4 | BFS cost grows with pairs times reachable vertices; small benchmark sets only | spark-engineer | EMR run |
| R-06b-5 | RAG inputs need joins; orcid null, empty institution, external, and topic IDs; exported works have empty externalIds, so Neptune loses DOIs | spark-engineer / rag-engineer | before batched AWS run |
| R-06b-6 | Edge confidence fixed at 1.0; ER match confidence not propagated | spark-engineer / entity-resolution-engineer | gold-set calibration |
| R-06b-7 | Fixture community split depends on researcher-ID hash order | spark-engineer | next Spark change |
| R-06b-8 | Several new invariants are structurally 0, so "15 invariants" overstates independent checking | spark-engineer | next Spark change |
| R-06b-9 | PartBMain silently drops malformed path-pair lines; checkpoint-mode comment is inaccurate | spark-engineer | next Spark change |
| R-06b-10 | PartBMain, stage metrics, skew write-ups, and EMR untested; only the local fixture ran | spark-engineer / deploy-engineer | batched AWS run |

Closed: R-06a-10 (edge column mapping) in 06b. R-06a-12 decided in 06b (always-0 fields kept for schema stability).

## Phase 07 (storage)

| ID | Item | Owner | Target |
|---|---|---|---|
| R-07-1 | openCypher syntax, empty Int fields in bulk-load CSV, S3 Vectors limits and filter syntax unverified (aws-docs MCP down) | storage-engineer | before batched AWS run |
| R-07-4 | No live Neptune query executor (no neptunedata client); only the in-memory fake | build-engineer / storage-engineer | batched AWS run |
| R-07-5 | Newlines in titles flattened to spaces in the bulk-load CSV | storage-engineer | next storage change |
| R-07-6 | DynamoDB and S3 adapters have no emulator contract tests | storage-engineer | batched AWS run |
| R-07-7 | No versioned step-status transition; a status change is a ConditionalConflict | storage-engineer | when a runner writes steps |
| R-07-8 | Neptune manifest rejectCount can exceed distinct ledger reject items | storage-engineer | next storage change |
| R-07-9 | Reject digest is a 64-bit prefix with no domain tag; NFC-equivalent rejects collapse | storage-engineer | next storage change |
| R-07-10 | S3 404-after-409 retry tested only against a stub client | storage-engineer | batched AWS run |
| R-07-11 | `rejects(runId, limit)` truncates with no pagination | storage-engineer | next storage change |
| R-07-12 | Path templates order only by length; openCypher can revisit nodes (the fake never does) | storage-engineer | with the live executor |
| R-07-13 | Infra must create vector indexes `<storage.vector-index>-<logical>`, textRef non-filterable, TTL on query-session ttl, storage IAM list without delete actions | infra-engineer | Terraform pass |
| R-07-14 | Worktree sessions cannot write (guard-ownership resolves paths against the main tree) | factory-engineer | optional amendment |

Closed: R-07-2 (edge columns) in 06b. R-07-3 (topic and institution DocumentType) in 08x.

## Phase 08 (embedding ingestion and 08x core follow-up)

| ID | Item | Owner | Target |
|---|---|---|---|
| R-08-1 | No core semantic-summary record; SemanticSummary is storage-local | domain-engineer | next core change |
| R-08-4 | Chunk ID excludes the window; relies on window-specific sourceVersion | rag-engineer / spark-engineer | keep in producers |
| R-08-5 | Changed text leaves the stale vector in the index (no delete path; data destruction needs a human decision) | storage-engineer / human | before batched AWS run |
| R-08-6 | A changed model version upserts in place; old and new model vectors coexist until a full re-run | storage-engineer | batched AWS run |
| R-08-7 | Failure REJECT items accumulate across replays | storage-engineer | next storage change |
| R-08-8 | GetVectors 100-key limit, Titan V2 model ID, and 1024 dimension unverified (aws-docs MCP down) | storage-engineer / rag-engineer | before batched AWS run |
| R-08-9 | Abstracts truncated at 1500 code points instead of chunked | rag-engineer | later phase |
| R-08-10 | Fake embedder is lexical only; path summaries use the source researcher as entityId | rag-engineer | real-model benchmark |
| R-08-11 | An aborted run records notAttempted only in the returned report, not the ledger | storage-engineer | next storage change |
| R-08-12 | batchSize 101-500 fails every batch with the fake embedder (default 100 is safe) | storage-engineer / rag-engineer | next change |
| R-08-13 | researcherProfile does not check that representative works are in the window or belong to the researcher | rag-engineer | next rag change |
| R-08-14 | A failed PutVectors followed by a successful replay under another run orphans a text object | storage-engineer / human | cleanup with R-08-5 |
| R-08x-1 | No producer or template for topic or institution summaries; that index stays empty | rag-engineer / spark-engineer | when topic and institution tables exist |
| R-08x-2 | RAG community pre-checks duplicate core validation; error text could drift | rag-engineer | next rag change |
| R-08x-3 | No golden-value test pins a specific chunk ID string | domain-engineer | next core change |
| R-08x-4 | EmbeddingIngestion's public router parameter could route a type to an unsuitable index | storage-engineer | next storage change |

Closed: R-08-2 and R-08-3 in 08x. R-08x-5 in 06b (community IDs computed with `StableIds.communityId`).

## Phase 09 (runtime Graph-RAG)

| ID | Item | Owner | Target |
|---|---|---|---|
| R-09-1 | RAG-local PlanOperation, Verdict, typed claims, AuditRecord, and export row types should move to core | domain-engineer | next core change |
| R-09-2 | No storage port for metrics, communities, bridges, components, or name lookup; the size bound is checked only after download | storage-engineer | before batched AWS run |
| R-09-3 | Coauthor rows carry no edge window, policy, or provenance (CLAUDE.md law 3) | storage-engineer / rag-engineer | before batched AWS run |
| R-09-4 | Path rows carry no hop attributes; no pair-edge template; pairs where both sides exceed 100 coauthors abstain | storage-engineer | next storage change |
| R-09-5 | Audit record returned, not persisted | storage-engineer / service-engineer | services phase |
| R-09-6 | Resolver prefers exact over initials matches; ranking ties broken by ID without disclosure | rag-engineer | next rag change |
| R-09-7 | Planner covers English templates only (fails closed) | rag-engineer | LLM planning phase |
| R-09-8 | Nothing Bedrock or S3 Vectors related verified against AWS docs (aws-docs MCP down) | rag-engineer | before real-model benchmark |
| R-09-9 | `prompts/answer-synthesis/v1` schema is loose and unused | rag-engineer | LLM synthesis phase |
| R-09-10 | Neptune path templates have no tie-break under LIMIT; answers and hashes can change between runs | storage-engineer | with the live executor |
| R-09-11 | A non-template synthesizer can keep a semantic ID on a verified claim; export rows not checked against the release run ID | rag-engineer | before LLM synthesis |
| R-09-12 | With parallel edges and capped lists, a supported answer can silently omit unread edges; more than 20 parallel edges only partly confirmed | rag-engineer | next rag change |
| R-09-13 | Live Neptune execution of every template untested | storage-engineer / deploy-engineer | batched AWS run |

## Suggested grouping for remediation

These are not new decisions, only a way to batch the work by owner:

- **Before the batched AWS run (Phase 12):** R-07-1, R-08-8, R-09-8 (needs aws-docs MCP), R-06a-9, R-06b-1, R-06b-5, R-09-2, R-09-3, R-05-5 / R-06a-8, R-07-13, R-08-5 / R-08-14 (human decision), P03-2.
- **Gold-set calibration:** R-05-3, R-05-4, R-06a-11, R-06b-6, CO-5.
- **Services phase:** R-00-1, R-09-5.
- **Next core change (domain-engineer):** R-05-1, R-08-1, R-08x-3, R-09-1, R-03-3.
- **Next Spark change (spark-engineer):** R-06a-6, R-06a-7, R-06a-15, R-06b-7, R-06b-8, R-06b-9.
- **Next storage change (storage-engineer):** R-07-5, R-07-8, R-07-9, R-07-11, R-08-7, R-08-11, R-08x-4, R-09-4.
- **Next rag change (rag-engineer):** R-08-13, R-08x-2, R-09-6, R-09-12.
- **Next Hadoop change (hadoop-engineer):** P04A-1, P04A-2, P04A-4, CO-1, CO-2, CO-4, plus the E-F8 to E-F10 blocking-spec items.
- **factory-engineer:** R3, R4 (repair-02), P03-1, R-07-14, and the main-class amendment round 3 items.
- **Human:** P02-3, R-05-4 labels, R-08-5 decision, Probe 5.
