Use the storage-engineer and rag-engineer in separate delegated work orders to build the embedding and S3 Vectors ingestion path.

Storage-engineer work:
- Implement a bounded embedding work queue using SQS or a manifest-driven batch design.
- Implement vector write batches through the S3 Vectors API.
- Store authoritative source text and generated profile documents in ordinary S3.
- Store vector key, researcher or community ID, relevant time window, topic or institution IDs, document type, source dataset, model version, text hash, and run ID as compact metadata.
- Detect and skip unchanged text by content hash and embedding model version.
- Record failures and retry counts in RunLedger.

Rag-engineer work:
- Define deterministic rendering templates for researcher profiles, community profiles, collaboration paths, and representative-paper summaries.
- Define chunking policies for permitted abstracts or longer text when used.
- Define embedding model versioning.
- Create retrieval smoke tests with known expected researcher, topic, or community neighbors.
- Create a compact semantic-context format for the synthesis agent.

Required indexes:
- researcher-profile-v1
- collaboration-community-v1
- paper-abstract-v1
- collaboration-path-v1
- topic-institution-summary-v1

Run the local contract tests and a bounded AWS ingestion of at least 10,000 vectors or an instructor-approved smaller corpus when the selected dataset subset does not produce 10,000 meaningful units.
Report throughput, rejected records, duplicate handling, query latency samples, index counts, metadata schema, and embedding model version.