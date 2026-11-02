Use the entity-resolution-engineer agent to implement the ScholarChain entity-resolution subsystem against the Phase 3 contracts and Phase 4 MatchCandidate records.

Required behavior:
- Exact identifier rules for DOI, arXiv ID, ORCID, OpenAlex ID, Semantic Scholar ID, and repository URL.
- Normalization for Unicode, punctuation, whitespace, title markup, initials, name order, and DOI prefixes.
- Feature functions for researcher-name similarity, initials, author order, coauthor overlap, affiliation compatibility, ORCID or external IDs, title similarity, year distance, venue compatibility, citation-neighborhood evidence when available, and source reliability.
- A versioned scoring policy.
- Separate thresholds for accepted, rejected, and unresolved results.
- Conflict detection when authoritative identifiers disagree.
- LLM adjudication only for a bounded unresolved band.
- Strict JSON schema for LLM adjudication with accept, reject, and abstain outcomes.
- No LLM call may alter source records or create an identifier not present in the candidates.
- Cache LLM adjudications by input hash, prompt version, model version, and policy version.
- Produce accepted, rejected, unresolved, and conflict outputs.
- Generate a review queue for human-labeled evaluation examples.

Evaluation requirements:
- At least 200 manually reviewed candidate pairs.
- Precision, recall, F1, confusion matrix, and coverage, reported separately for researcher-identity resolution and work resolution when both are implemented.
- Metrics by match path: exact ID, deterministic bibliographic, probabilistic, and LLM-adjudicated.
- Error analysis with at least ten false positives and ten false negatives when available.
- Threshold sensitivity analysis.

Run sbt check and the gold-set evaluation.
Report the policy version, feature definitions, thresholds, model and prompt versions, confusion matrix, unresolved rate, and representative errors.