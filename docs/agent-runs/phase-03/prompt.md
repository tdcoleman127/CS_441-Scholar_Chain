Use the dataset-engineer and domain-engineer, in separate delegated work orders, to create the ScholarChain data contracts and dataset manifest.

Dataset-engineer work:
- Create datasets/dataset-manifest.yaml for DBLP and every selected enrichment dataset.
- Include source, license, snapshot, format, size estimate, S3 prefixes, checksums, subset rule, schema, linkage keys, update method, limitations, privacy notes, and validation commands.
- Create bounded download and sample-generation scripts under datasets/scripts.
- Create representative legal sample files under datasets/samples, small enough for the repository.
- Create schema notes under datasets/schemas.
- Scripts must be idempotent and must not download a full corpus by default.

Domain-engineer work:
- Create immutable Scala domain records for WorkRecord, AuthorMention, CanonicalResearcher, AuthorshipRecord, PublicationTeam, CoauthorPairCandidate, CoauthorEdge, ResearcherMetrics, Venue, Institution, Topic, Provenance, MatchDecision, GraphNode, GraphEdge, EvidenceItem, EvidenceBundle, QueryPlan, AnswerClaim, and AnswerResponse.
- Define stable researcher, work, edge, evidence, and run ID functions.
- Define enums for source type, match outcome, evidence type, collaboration metric, query operation, and verification status.
- Define a configuration loader using environment variables plus versioned HOCON or YAML files. Do not hardcode bucket names, endpoints, thresholds, model IDs, AWS account IDs, graph windows, or bridge-algorithm parameters.
- Create serialization and schema-version tests.
- Freeze representative JSON wire examples in contract tests.
- Add validation that rejects missing provenance, invalid confidence ranges, unsupported schema versions, malformed stable IDs, invalid undirected pair ordering, and time windows where start year exceeds end year.

Run sbt check.
Report sample serialized records, stable ID formulas, pair canonicalization rule, configuration precedence, schema versions, and known unresolved dataset questions.

work ID: namespace plus authoritative source ID when available
researcher ID: canonical external identifier when validated, otherwise a versioned ScholarChain identity ID
coauthor edge ID: hash of canonical ordered researcher IDs plus graph-window and policy version
chunk or profile ID: hash of entity ID, source version, document type, ordinal when needed, and text hash
graph edge ID: hash of edge type, canonical endpoints, policy version, and source evidence
run ID: timestamp-independent unique identifier stored with an immutable run manifest