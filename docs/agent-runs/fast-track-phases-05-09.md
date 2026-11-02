# Fast track: Phases 05 to 09

Goal: finish Phases 05 to 09 as soon as possible. Every session is capped at 60 minutes and covers essentials only. Anything that does not fit is recorded as an open risk and the work moves on. The full original work orders stay in each `phase-NN/prompt.md`. The trimmed work orders below replace them for the fast track. Deferred items are listed so they can be picked up later.

## 1. What cannot be cut (mechanically enforced)

- `sbt check` must pass before a session can stop (Stop hook). If it is red at minute 45, cut scope until it is green. Do not leave it red.
- Every behavior change ships with a test in the owning module's test tree (CLAUDE.md law 2). Keep tests few and targeted.
- Ownership hooks. Each agent writes only its own module. New dependencies go through build-engineer.
- Provenance (law 3): every record keeps source dataset, source record ID, and pipeline run ID.

## 2. The 60-minute session

| Min | Step |
|---|---|
| 0-3 | Paste the trimmed work order and delegate to the owning agent |
| 3-38 | One implementation run by the owner |
| 38-45 | `sbt check`. If red, cut scope rather than debug at length |
| 45-52 | One code-reviewer round (work order in section 5) |
| 52-57 | One repair, CRITICAL findings only. Everything else becomes an open risk |
| 57-60 | Short-form `human-gate.md` (section 6), then commit |

## 3. Schedule

| Day | Session A | Session B (parallel, separate git worktree) |
|---|---|---|
| 1 | Dependency setup (WO-00, 30 min), then Phase 05 | Phase 07 |
| 2 | Phase 06a | Phase 08 (against fake inputs) |
| 3 | Phase 06b | Gold-set labeling (about 50 pairs, human) and gate notes |
| 4 | Phase 09 (plus a 30-minute overflow if needed) | Deferred-risk backlog |

Later, as separate tasks: infra-engineer Terraform pass, one batched AWS run (EMR for 06, the storage integration suite for 07, the 10k-vector ingestion for 08, real-model benchmarks for 09), and one combined test-engineer adversarial pass.

Phase 07 depends only on `core`, so it can run in parallel with 05 and 06. Phase 08 runs against fake Spark outputs until 06b is committed.

## 4. Trimmed work orders

### WO-00: dependency setup (build-engineer, 30 min)

```text
Use the build-engineer agent to add, in one change, the dependencies that fast-track Phases 06 to 09 need. Keep the module dependency table in CLAUDE.md intact.

Add:
- storage: AWS SDK for Java v2 clients for S3, DynamoDB, and S3 Vectors, plus whatever the Neptune bulk-load path needs (none if it only writes CSV manifests to S3).
- rag: a model client for embeddings and synthesis (for example the AWS SDK v2 Bedrock Runtime client). Only rag may depend on it.
- spark: no graph library unless one is available for the pinned Spark and Scala versions without a custom resolver. Plain DataFrame iterations are acceptable.

Pin every version in project/Versions.scala. Mark Hadoop and Spark Provided as before. Do not add dependencies to entity-resolution beyond core.
Run sbt check. Report the added entries, scopes, and any dependency you could not add, with the reason.
```

### WO-05: entity resolution (entity-resolution-engineer, 1 session)

```text
Use the entity-resolution-engineer agent to implement the essential ScholarChain entity-resolution subsystem against the Phase 3 contracts and Phase 4 MatchCandidate records. This is a time-boxed, essentials-only work order. Anything not listed is out of scope.

Implement:
- Exact identifier rules for DOI, arXiv ID, ORCID, OpenAlex ID, and Semantic Scholar ID.
- Normalization for Unicode, punctuation, whitespace, initials, name order, and DOI prefixes.
- Six feature functions: researcher-name similarity, initials compatibility, coauthor overlap, ORCID or external-ID agreement, title similarity, and year distance.
- A versioned scoring policy with separate accept, reject, and unresolved thresholds.
- Conflict detection when authoritative identifiers disagree.
- An Adjudicator port with a stub implementation that always returns abstain. No model client.
- Accepted, rejected, unresolved, and conflict outputs, each with provenance.
- A review-queue export (CSV or JSON Lines) of candidate pairs for human labeling.
- An evaluator that reads a labeled file and prints precision, recall, F1, a confusion matrix, coverage, and a threshold sweep, split by match path (exact ID, deterministic, probabilistic).

Tests: one targeted test per rule family and one evaluator test on a tiny labeled file.
Run sbt check and the evaluator on whatever labeled pairs exist.
Report the policy version, feature definitions, thresholds, the confusion matrix, and the unresolved rate.
```

Deferred: LLM adjudication and caching, repository-URL rule, affiliation, venue, citation-neighborhood and source-reliability features, the full 200-pair gold set, error analysis with 10 false positives and 10 false negatives, and the Phase 04 recall findings E-F1 to E-F10.

### WO-06a: Spark, part A (spark-engineer, 1 session)

```text
Use the spark-engineer agent to implement Part A of the essential ScholarChain Spark pipeline. Time-boxed, essentials only.

Implement:
1. Canonical researcher and authorship builder: Researcher, Work, and AUTHORED tables from accepted entity links and Hadoop author-paper outputs. Keep unresolved mentions unmerged. Validate that every AUTHORED edge points to existing nodes.
2. Temporal weighted coauthorship graph: map pair candidates to canonical IDs, drop self-pairs, canonicalize pair order, aggregate duplicates, and store jointPublicationCount, firstCollaborationYear, lastCollaborationYear, and supporting work IDs or a stable pointer. The year window is a configuration parameter.
3. Per-researcher metrics for a window: P_r, C_r, T_r, A_r, M_r, W_r, and the work IDs responsible for M_r. Keep every component visible.
4. Connected components: component ID and size, largest-component fraction, singleton count.

Never collect the graph on the driver. Emit counts and invariant failures. Write a run manifest.
Tests: one small hand-checkable graph that verifies jobs 2 to 4 exactly.
Run sbt check and a local Spark test. Report graph counts, component counts, and output paths.
```

### WO-06b: Spark, part B (spark-engineer, 1 session)

```text
Use the spark-engineer agent to implement Part B of the essential ScholarChain Spark pipeline on top of the committed Part A. Time-boxed, essentials only.

Implement:
5. Bounded shortest paths for selected researcher pairs (iterative BFS with a depth limit), with path evidence for benchmark pairs.
6. Communities by label propagation with a fixed iteration count. Store membership, community size, and cross-community edges. Checkpoint the iterations.
7. Bridge measure: cross-community degree and the number of distinct communities each researcher touches.
8. K-core by iterative peeling.
10. Template-based semantic summaries (researcher profile, community, path) built only from computed data and source records.
11. Partitioned Parquet export of node, edge, metric, community, component, path-evidence, and summary tables, plus load manifests for Neptune, DynamoDB, S3 Vectors, and S3.

Tests: extend the hand-checkable graph to verify paths, communities, bridge counts, and coreness.
Run sbt check and a local Spark test. Report community counts, coreness distribution, path-test results, and output paths.
```

Deferred for 06: betweenness centrality, effective diameter or average path length, the full temporal comparison across windows (job 9 beyond the window parameter), Topic, Institution and Venue tables, DAG, partition and shuffle write-ups, and the bounded EMR run.

### WO-07: storage (storage-engineer, 1 session, parallel)

```text
Use the storage-engineer agent to implement the essential ScholarChain storage layer. Time-boxed, essentials only.

Implement:
- Port traits for object storage, graph loading and query, key-value tables, and vector storage.
- S3: prefix scheme (raw, curated, graph, semantic-context, reports, logs), versioned keys, run manifests, and content hashes.
- DynamoDB adapters for RunLedger, EntityLinkCache, QuerySession, EvidenceBundle, and ArtifactRegistry, with explicit partition and sort keys and conditional writes for idempotency.
- S3 Vectors adapter for researcher-profile-v1, collaboration-community-v1, paper-abstract-v1, collaboration-path-v1, and topic-institution-summary-v1: stable vector keys, compact filterable metadata, long text kept in S3 by reference, batched writes, bounded retries, and recorded rejects.
- Neptune: bulk-load CSV manifest generator for Researcher, Work, AUTHORED, and COAUTHORED_WITH (with jointPublicationCount, first and last year, window, and evidence pointer), and parameterized openCypher templates for direct coauthors and bounded paths.
- In-memory fakes for every port.

Tests: serialization round trip, idempotent reload, duplicate vector keys, malformed metadata, conditional-write behavior, and coauthor-edge endpoint validation, all against fakes. Write the AWS integration tests behind an explicit profile but do not run them.
Run sbt check. Report schemas, keys, metadata fields, batch sizes, retry policy, and idempotency strategy.
```

Deferred: OpenSearch (skipped), the optional ResearcherMetricCache, the remaining Neptune relationship types, the AWS integration run, and service limits checked against current documentation.

### WO-08: embedding ingestion (two work orders, 1 session)

Storage part (about 25 min):

```text
Use the storage-engineer agent to build the essential embedding ingestion path. Time-boxed.

Implement a manifest-driven batch design (no SQS): read semantic-summary records, skip any whose text hash and embedding model version are unchanged, write vectors in batches through the S3 Vectors port, store source text in S3, and record failures and retry counts in RunLedger. Metadata: vector key, researcher or community ID, window, document type, source dataset, model version, text hash, and run ID.
Tests against the fakes: skip-unchanged, duplicate keys, and failure recording.
Run sbt check. Report the metadata schema and batch policy.
```

RAG part (about 25 min):

```text
Use the rag-engineer agent to define the essential embedding content. Time-boxed.

Implement deterministic rendering templates for researcher profiles, community profiles, collaboration paths, and representative-paper summaries; an embedding model version constant; an embedder port with a deterministic fake; and a compact semantic-context format for synthesis.
Tests: template determinism, and a retrieval smoke test with the fake embedder where known neighbors are returned.
Run sbt check. Report template versions and the model version.
```

Deferred: chunking for long abstracts, the bounded AWS ingestion of 10,000 vectors, throughput and latency figures.

### WO-09: runtime Graph-RAG (rag-engineer, 1 session plus optional 30-min overflow)

```text
Use the rag-engineer agent to implement the essential ScholarChain runtime workflow. Time-boxed, essentials only. Run against the storage fakes.

Implement:
1. QueryPlanner: rule-based parsing into a typed QueryPlan with an allowlist of operations (direct coauthors, shortest path, metric lookup and ranking, component, community, bridge, temporal window). Never emit arbitrary Cypher or AWS operations from user text.
2. ResearcherResolver: map names to canonical IDs using entity-resolution outputs, return multiple candidates when ambiguous, and abstain rather than guess.
3. Retrieval stages for stored metrics, bounded paths (depth, result-count, and timeout limits), communities and bridges, and semantic context, each returning values with metric version, run ID, and provenance. Vector similarity is never evidence of an edge or a metric.
4. EvidenceBuilder: bounded EvidenceBundle with graph evidence kept separate from semantic context.
5. AnswerSynthesis behind a model port with a template implementation. Every material claim is a typed AnswerClaim with evidence IDs.
6. Deterministic ClaimVerifier: check identities, numbers, path hops, community claims, and windows against the bundle, and return VERIFIED, PARTIALLY_SUPPORTED, UNSUPPORTED, or CONFLICTING.
7. Audit record: plan, resolved IDs, evidence IDs, versions, latency, verification result, and answer hash.

Version every prompt and schema. Treat retrieved text as untrusted data. Abstain when identity or evidence is insufficient.
Tests: about 10 benchmark queries against fakes, including one ambiguous name, one unsupported claim, and one prompt-injection title.
Run sbt check and the benchmark suite. Report allowed operations, query templates, verification rules, abstention behavior, and benchmark results.
```

Deferred: LLM-based planning and synthesis, structured-output retry policy, token budgets, the real-model benchmark, and the full failure-mode analysis.

## 5. Code-reviewer work order (one round per session)

```text
Use the code-reviewer agent to review the Phase NN change set (git diff against the last accepted commit). Time-boxed: report at most 5 findings, CRITICAL and MAJOR only. No probe campaigns, braces, or globs. For each finding give severity, file:line, the concrete failing input, and the owning agent. Return APPROVE or REQUEST_CHANGES.
```

## 6. Short-form human gate

```markdown
## Round 1: fast track (YYYY-MM-DD)

Work order: docs/agent-runs/fast-track-phases-05-09.md, WO-NN
Reviewed: <diff range>; sbt check <PASS, tests passed/failed>; reviewer <APPROVE | REQUEST_CHANGES>
Decision: ACCEPTED WITH OPEN RISKS
- Fixed in session: <IDs>
- Open: <ID, severity, one line, owner, target task>
Deferred scope: see WO-NN "Deferred" list
Manual changes: <none, or the change and the command>
Accepted commit: <40-character hash>
```

## 7. Risks accepted by the fast track

- Gold-set metrics rest on fewer than 200 labeled pairs unless the instructor approves a smaller set.
- No LLM adjudication and no real-model groundedness numbers until the batched run.
- No EMR, Neptune, DynamoDB, or S3 Vectors evidence until the infra-engineer Terraform pass and the batched AWS session.
- Defects that a second review round would have caught can reach later phases. The combined test-engineer adversarial pass is the safety net.
