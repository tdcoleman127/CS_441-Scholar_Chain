# entity-resolution-engineer: Phase 04 part B blocking review (read-only)

Assembled by the orchestrator, 2026-10-29, from entity-resolution-engineer's final report. Requested by the human (`human-decisions-2026-10-29.md`, decision 2). The reviewer wrote and edited nothing. One Bash command was blocked by guard-dangerous and ran nothing. The reviewer ran no sbt or tests and measured no precision or recall, because the entity-resolution module is empty (only .gitkeep files). Every recall effect below comes from reading the code and samples.

## Findings (fixes go to hadoop-engineer unless marked)

**F1. HIGH (recall): member-ID-order chunking splits work blocks by source.** Blocking.scala:203-204 and 231-237, with StableIds.scala:17-18. Work IDs are namespace-prefixed, so an oversize work block sorts arxiv < dblp < doi < openalex < sc1, and each chunk is mostly one source. Failure input: `w|introduction` with 10 dblp and 10 openalex works, cap 10, forms 0 of the 100 cross-source pairs. Mention IDs are SHA-256, so mention chunks are a random sample keeping about cap/n of the pairs (21% in the 42-member block). Chunk boundaries shift when any record is added. Recommendation: a bounded sorted-neighbourhood window. Composite key `<blockKey>\t<sortKey>\t<memberId>` (sortKey is the full normalized name without suffix, or the full normalized title); a ring buffer of W-1 members; O(W) memory; at most n·(W-1) pairs; W from configuration. An optional second pass on another sort key. No year sub-blocking for mentions; for works only overlapping year buckets. BlockStat should split skipped pairs into same-source and cross-source counts.

**F2. HIGH: a generational suffix becomes the surname.** Blocking.scala:103; SourceNormalizers.scala:430. Sample: arxiv-oai-sample.xml:48 "A. Sample Jr" gives `m|jr|a`, while OpenAlex "A. Sample" gives `m|sample|a`. All Jr/II/III authors collapse into `m|jr|*` and `m|iii|*`. Recommendation: strip jr, sr, ii, iii, iv before choosing the surname, and keep the suffix as a separate field.

**F3. HIGH: one key per member, and it depends on name order.** Blocking.scala:109-113; SourceNormalizers.scala:274 prefers `raw_author_name`. "Wang Wei" gives `m|wei|w` against DBLP "Wei Wang" `m|wang|w`; "Smith, John" gives `m|john|s`. Recommendation: parse "X, Y" into "Y X"; use `display_name` for the blocking label; emit a second, sorted-token key for two-token names.

**F4. MEDIUM: name variants.** Blocking.scala:103 and 111; DblpRecordParser.scala:82-89. Middle name used as first name; compound Hispanic surnames; surname-only names; letters NFKD does not decompose (ł ø đ ß æ œ ı; the sample's "Łucja Próbka"); ü versus ue; native script. Recommendation: an ASCII fold table, umlaut-expansion keys, and a surname-only key bounded by the F1 window.

**F5. MEDIUM: brittle title key.** Blocking.scala:98-100 and 116-118; SourceNormalizers.scala:136. Leading stop words, hyphenation, undecoded `&amp;`, "Erratum to:" prefixes, and generic titles that make huge blocks. Recommendation: decode entities, drop stop words, a second key from the 3 longest tokens sorted, and a sub-key or exact-ID-only matching for titles of 2 tokens or fewer.

**F6. MEDIUM: missing values look like negative evidence.** Blocking.scala:154-156 and 171-174. jaccard returns Some(0.0) when both sides are empty; sharedContext and sharedExactKeys return Some(0) when either is empty; sameVenue is 0 across venue namespaces. Recommendation: return None when either input is empty or the values cannot be compared.

**F7. MEDIUM: missing features.** Blocking.scala:32-44 and 164-178: conflicting exact keys (same namespace, different value); the OpenAlex author ID (MentionExtra.sourceAuthorId never reaches BlockMember); mention institution IDs; DBLP homonym-suffix equality or conflict; a position gap on a shared work; name-component features (initial compatibility, middle-initial conflict, Jaro-Winkler). Add them as raw features, with None when not computable.

**F8. LOW-MEDIUM:** mention context uses full coauthor names (Blocking.scala:148). Use `surname|initial`.

**F9. LOW:** self-exclusion from the context uses the list index authorPosition (Blocking.scala:148). Exclude by position match instead.

**F10. LOW:** MatchCandidateRecord and BlockStat (Blocking.scala:53-64, 71-80) carry no `blockingVersion` or `featureVersion`.

**F11. MEDIUM (ownership; escalation).** No feature leaks a decision. But the cap and the chunking act as an implicit candidate-rejection policy, set by a hadoop config default (SourceJobs.scala:41-42) rather than a versioned entity-resolution policy. The matrix gives "blocking keys, match features" to entity-resolution-engineer, while the lifecycle routing gives the blocking jobs to hadoop-engineer, and the module table leaves no shared home for the functions. Under docs/agents.md section 8 this is ambiguous ownership: factory-engineer proposes, the human ratifies. Candidates: (a) amend the module table so `hadoop` may depend on `entity-resolution` (pure functions; no cycle), with entity-resolution owning versioned `BlockingKeys`, `RawFeatures`, and the cap or window policy (recommended); (b) entity resolution publishes a versioned spec that hadoop implements, with entity-resolution sign-off on each change.

## Reviewer notes

- Features as built: labelExact, labelTokenJaccard, sameWork, sameSource, yearGap, sameVenue, sharedExactKeys, sharedContext, contextJaccard, orcidEqual (mentions only).
- Thresholds: none. Cap 500 (`scholarchain.block.max.size`).
- No gold set, confusion matrix, or precision, recall, and F1. Representative errors are predicted, not measured.
- Assumptions: full-DBLP block sizes are estimates. Family-first and comma name forms are known real-data patterns, not seen in the sample.
- Manual action: the human decides F11. A human-labelled gold set covering suffixes, family-first names, and cross-source work pairs is needed to measure blocking recall.
- Next gate: hadoop-engineer repairs, code-reviewer, then an entity-resolution pair-completeness evaluation on a gold set (experiment-engineer runs it).
