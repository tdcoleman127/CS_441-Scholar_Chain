Use the hadoop-engineer agent to implement the ScholarChain Hadoop MapReduce pipeline.

Required jobs:

1. DBLP record extraction
- Read complete DBLP publication records without splitting an XML record across mappers.
- Support the selected DBLP record types present in the chosen snapshot.
- Extract DBLP key, title, author sequence, year, venue fields, ee links, URLs, and record type.
- Emit valid normalized WorkRecord and AuthorMention records.

2. External source normalization
- Implement one normalizer per selected dataset.
- Map source fields into shared contracts without losing source-specific IDs.
- Preserve author identifiers, affiliations, topic identifiers, abstracts, and work identifiers when available.
- Write malformed and unsupported records to versioned reject outputs.

3. Exact identifier extraction and join preparation
- Normalize DOI, arXiv, ORCID, OpenAlex, Semantic Scholar, and other selected identifiers.
- Emit exact-identifier keys and duplicate or conflict counters.

4. Researcher and work blocking
- Emit bounded candidate blocks using normalized names, initials, coauthor context, title, year, venue, affiliation, and source.
- Prevent pathological common-name or title blocks from overwhelming reducers.

5. Candidate feature construction
- Join records within blocks.
- Compute deterministic match features.
- Emit MatchCandidate records rather than final guesses hidden inside logs.

6. Publication team extraction
- Emit one publication-team record containing the ordered author mentions for every retained work.
- Emit author-paper records keyed by author mention and work ID.
- Emit candidate unordered coauthor pairs for every paper with at least two authors.
- A paper with n authors generates n * (n - 1) / 2 candidate pairs before duplicates are consolidated.
- Preserve work ID, year, author position, and source provenance on every pair record.
- Count and report unusually large author teams because they can create quadratic pair expansion.

7. Graph export preparation
- Emit partitioned work, author-mention, team, pair-candidate, topic, institution, and other selected enrichment files for Spark.

Required operational behavior:
- Hadoop counters for input, accepted, rejected, duplicate, conflict, oversize block, oversize author team, emitted pair, missing key, and schema failure records.
- Explicit partition strategy.
- Idempotent output paths containing run ID and dataset snapshot.
- No mutable shared state across map or reduce tasks.
- Compression for intermediate outputs.
- Deterministic outputs for deterministic inputs.
- Unit tests for parsers, pair generation, normalization, and key functions.
- Mini-cluster or local integration tests for representative jobs.
- A bounded EMR smoke-run script owned by the infrastructure workflow, not by this agent.

Run sbt check and the local bounded pipeline.
Report job graph, mapper and reducer key-value contracts, partition counts, counters, rejected records, output paths, maximum observed team size, largest pair expansion, and the largest observed blocking group.

DBLP XML --------------------> DBLP parser --------------------------+
                                                                    |
OpenAlex/ORCID/etc. ---------> source normalizers ------------------+--> identity-link inputs
                                                                    |
publication teams -----------> author-paper and pair expansion -----+--> coauthor pair candidates
                                                                    |
all normalized records ------> blocking-key mappers ----------------+--> candidate identity groups
                                                                    |
all prepared records -----------------------------------------------+--> S3 curated MapReduce output