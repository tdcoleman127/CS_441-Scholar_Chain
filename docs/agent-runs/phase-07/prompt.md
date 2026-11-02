Use the storage-engineer agent to implement ports, adapters, loaders, and contract tests for the ScholarChain storage layer.

Required adapters:

1. Ordinary S3
- Raw, curated, graph, semantic-context, reports, and logs prefixes.
- Versioned object keys and run manifests.
- Content hashes and source references.

2. Neptune
- Bulk loader manifests for Researcher, Work, Venue, Institution, Topic, and selected context nodes.
- Load AUTHORED, COAUTHORED_WITH, PUBLISHED_IN, AFFILIATED_WITH, HAS_TOPIC, RESOLVES_TO, DERIVED_FROM, and SUPPORTED_BY relationships as required by the implemented graph model.
- Store coauthor-edge properties including jointPublicationCount, firstCollaborationYear, lastCollaborationYear, selected time window, and evidence pointer.
- Store researcher collaboration metrics needed for bounded runtime lookup when appropriate.
- Use idempotent identifiers and parameterized openCypher queries.
- Give runtime services read-only graph credentials.

3. DynamoDB
- RunLedger table.
- EntityLinkCache table.
- QuerySession table.
- EvidenceBundle table.
- ArtifactRegistry table.
- Optional ResearcherMetricCache table when justified by access patterns.
- Explicit partition and sort keys.
- Conditional writes for idempotency.
- TTL only where data is genuinely disposable.

4. S3 Vectors
- Create and query vector-bucket and index abstractions.
- Store embeddings with stable vector keys and compact filterable metadata.
- Store long text or generated profiles in ordinary S3 and keep only references in vector metadata.
- Support metadata filters for researcher, year window, source, topic, institution, community, and document type when permitted by service limits.
- Batch writes, retry transient failures, and record rejected vectors.

5. Optional OpenSearch
- Only when the team implements hybrid lexical and vector retrieval or a measured hot tier.
- Define synchronization from durable S3 and S3 Vectors data.
- Do not make OpenSearch an undocumented second source of truth.

Required vector indexes:
- researcher-profile-v1
- collaboration-community-v1
- paper-abstract-v1
- collaboration-path-v1
- topic-institution-summary-v1

Required tests:
- Serialization and deserialization.
- Idempotent reload.
- Missing and malformed metadata.
- Duplicate vector keys.
- Neptune coauthor-edge endpoint and evidence validation.
- DynamoDB conditional-write behavior.
- Local fakes or test containers where possible.
- Bounded AWS integration tests behind an explicit profile.

Run sbt check and the bounded storage integration suite.
Report schemas, keys, indexes, metadata fields, batch sizes, retry policy, idempotency strategy, service limits captured from current documentation, and cleanup procedures.