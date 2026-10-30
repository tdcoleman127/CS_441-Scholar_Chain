# Phase 04 part B: work order for hadoop-engineer

Assembled by the orchestrator on 2026-10-29 from `prompt.md` (human-written; jobs 2, 3, 4, 5, and 7) and `resume-point.md`. Part A (jobs 1 and 6) was accepted and committed in 8364c79. The session is time-boxed to 30 minutes, so keep the scope bounded and say plainly what you did not finish.

## Scope (owner: hadoop-engineer; write only `modules/hadoop/**`)

Build on the existing `DblpRecordParser`, `DblpXmlInputFormat`, `Jobs`, and `Teams`. Do not break part A's behavior, counters, or outputs.

2. **External source normalization.** One normalizer per committed sample: `datasets/samples/openalex/openalex-works-sample.jsonl`, `crossref/crossref-works-sample.jsonl`, `arxiv/arxiv-oai-sample.xml`, `opencitations/opencitations-index-sample.csv`. Map fields into the core contracts (`WorkRecord`, `AuthorMention`, `ExternalId`, `Provenance`). Keep source-specific IDs, author identifiers, affiliations, topics, and abstracts when present. Malformed and unsupported records go to the versioned reject output (RejectRecord v2, `rejects/v2/`).
3. **Exact identifier extraction.** Normalize DOI, arXiv, ORCID, OpenAlex, and the other selected identifiers with the existing `core` functions (`IdFormat.normalizeSourceId`, `normalizeOrcid`, `normalizeOci`); do not reimplement them. Emit exact-identifier keys and DUPLICATE and CONFLICT counters.
4. **Researcher and work blocking.** Bounded candidate blocks from normalized name and initials, title, year, venue, and source. Add a configurable maximum block size, an OVERSIZE_BLOCK counter, and report the largest observed block. An oversize block must not reach a reducer unbounded.
5. **Candidate feature construction.** Join records within blocks and compute deterministic raw match features. Emit candidate records, not decisions. **No thresholds and no accept/reject/merge decisions:** match policy belongs to entity-resolution-engineer. `hadoop` may depend on `core` only (CLAUDE.md module table). If a new record type is needed, keep it in the hadoop module (as human decision 3 did for `TeamRecord` and `MentionPairCandidate`) and list it under RISKS for the human.
7. **Graph export preparation.** Partitioned output files for Spark (works, author mentions, teams, pair candidates, external identifiers, and any topics or institutions present). The output paths contain the run ID and the dataset snapshot, and the partition strategy is explicit.

## Required behavior (from prompt.md)

Counters cover input, accepted, rejected, duplicate, conflict, oversize block, emitted pair, missing key, and schema failure. No mutable shared state. Intermediate outputs are compressed. Deterministic inputs give byte-deterministic outputs. Provenance (source dataset, source record ID, pipeline run ID) is kept on every record (CLAUDE.md law 3). Every behavior change ships with a feature test under `modules/hadoop/src/test/**` that fails without the change (law 2).

## Gates before you report

- `sbt check` exit 0. HADOOP_HOME=C:\hadoop is set in this session, so LocalPipelineSuite must really execute.
- A local bounded run of the new jobs over the committed samples, through tests or plain `java`, never `sbt run` or `runMain` (the guard blocks them; the main-class amendment is still deferred). Save logs to `docs/agent-runs/phase-04/run-evidence/partb-*.log` only if you can do so with Write; otherwise put the counter values in your report.
- Report: job graph, mapper and reducer key-value contracts, partition counts, counters, rejected records, output paths, and the largest observed blocking group. Use the section 11 report contract.

## Notes

- OneDrive can lock `modules/*/target`. If sbt reports `being used by another process`, say so and stop; do not delete files.
- Any Bash command containing the literal test-marker task name is blocked by the guard.
- No new dependencies. If you need one, return BLOCKED-ON build-engineer.
