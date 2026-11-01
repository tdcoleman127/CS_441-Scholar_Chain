# test-engineer: Phase 04 closeout, item 2 (adversarial tests, parts A and B)

Assembled by the orchestrator, 2026-11-01, from test-engineer's final report.

**STATUS: FAILED** (as intended by law 2). The suites are written and run. `sbt check` fails on 3 red tests, each exposing a real production defect. Format check, compile, and Test/compile pass. markTestRun did not run.

## Files and commands

- **Files created:** under `tests/contracts/src/test/scala/edu/uic/cs441/scholarchain/tests/hadoop/`:
  - `AdvFixtures.scala`
  - `HadoopMalformedInputSuite.scala`
  - `HadoopIdsTeamsBlocksSuite.scala`
- **No build change:** contractTests already dependsOn hadoop.
- **Commands:**
  - `contractTests/Test/compile`: the first try failed on the agent's own broken literal, then passed after the fix.
  - `contractTests/test`: 32/3.
  - Format: fixed, then checked.
  - `sbt check`: fmt, compile, and Test/compile passed; the test step failed on the 3 tests.
  - One grep was blocked by guard-dangerous (its pattern contained "Test run"); no sbt lock occurred.

## Counts (from sbt check)

| Suite | Passed | Failed | Skipped |
|---|---|---|---|
| core | 143 | 0 | 0 |
| hadoop | 59 | 0 | 0 |
| contractTests HadoopMalformedInputSuite | 17 | 2 | 0 |
| contractTests HadoopIdsTeamsBlocksSuite | 15 | 1 | 0 |

## Failing tests (stay red; routed to owners)

1. **DBLP scanner swallows records after an unclosed one.** Input: `<article key="journals/tst/A">…<title>Broken` (never closed), then a well-formed B (inproceedings) and C (article).
   - Expected: 3 records, with B and C accepted. Actual: 1 record at offset 8.
   - Cause: `readRecord` reads A up to the next `</article>`, absorbing B and C, which get neither an accept nor a reject. A wrong close tag does the same.
   - Impact: valid records are lost silently, and INPUT undercounts. ArxivRecordReader shares the scanner.
   - Owner: **hadoop-engineer** (DblpXmlInputFormat.scala, DblpRecordScanner.readRecord).
2. **Invisible format characters split names.** Input: `normalizeName("Mül­ler")`, and the same with `​`.
   - Expected: "muller". Actual: "mul ler".
   - Impact: the mention lands in block `m|ler|m` instead of Müller's block (a missed candidate), and labelExact turns false.
   - Owner: **hadoop-engineer** (DblpRecordParser.normalizeName; for example, strip `\p{Cf}` first).
3. **Legacy `http://dx.doi.org/` DOI dropped.** Input: DBLP `<ee>http://dx.doi.org/10.1000/ABC</ee>`.
   - Expected: `ExternalId(Doi, "10.1000/abc")`. Actual: Nil.
   - Cause: `IdFormat.normalizeSourceId(Doi, …)` strips `https://doi.org/`, `http://doi.org/`, `https://dx.doi.org/`, and `doi:`, but not `http://dx.doi.org/`.
   - Impact: the exact-ID join to Crossref, OpenAlex, and arXiv is lost silently.
   - Owner: **domain-engineer** (modules/core/.../ids/IdFormat.scala).

## Coverage (pure-function tests; no LocalJobRunner)

1. **Malformed input becomes a reject, never a crash:**
   - every proper prefix of each source format;
   - entities, garbage bytes, and mismatched tags;
   - hostile JSON (200,000 levels deep, huge numbers);
   - XXE rejected, with file contents never leaked;
   - RejectRecord v2 fields, the 500-character detail cap, and the reason-to-counter mapping.
2. **Unicode:**
   - entities and astral characters;
   - NFC and NFD, fullwidth letters, ligatures, and Turkish İ;
   - a Cyrillic look-alike never shares a key with the Latin name;
   - JSON `\u` escapes;
   - LaTeX `M\"{u}ller` matches `Müller`.
3. **Exact IDs:**
   - one DOI written 4 ways links, with no conflict;
   - same-source conflicts are counted;
   - attestations dedupe;
   - groups are permutation-invariant;
   - OMID and DOI share a key;
   - ORCID forms collapse, and a bad checksum goes to invalidOrcid.
4. **Giant team:** 300 authors give 44,850 pairs, OVERSIZE_TEAM=1, and AUTHOR_PAPERS=300. The 299 and 300 boundaries hold, and pairCount does not overflow.
5. **Oversize block:** 1,234 members at cap 100 give chunks of at most 100, disjoint, 13 chunks. Pairs (59,961), the counters, and skipped pairs are exact. Cap boundaries hold, and cap 1 throws.
6. **Provenance:** checked on every emitted record type, including both sides of a candidate, BlockStat, and RejectRecord.
7. **Job 7 co-partitioning:**
   - partitionKey equals workId;
   - all of a work's entities share a partition for n in {1, 2, 3, 7, 16, 64, 1000} (corrected by the orchestrator after code-reviewer closeout round 2, MINOR 2; the earlier wording "n = 1 to 1000" overstated it);
   - a composite key with tabs, newlines, and Unicode round-trips;
   - the grouping comparator groups by entity.

## Assumptions

- Expected behaviors for tests 1 and 2 are test-engineer's reading of "rejected, not dropped" and of blocking normalization. Owners may challenge them through the orchestrator; the tests will not be weakened.

## Residual risks (not asserted)

- A blank normalized name (for example "…" or emoji only) rejects the whole work, losing its valid coauthors. Policy owners: hadoop-engineer and domain-engineer.
- An OpenCitations row cut inside the last flag still has 7 columns; a garbage flag becomes None.
- Stripping `\p{M}` erases Devanagari and Thai vowel signs (for example किरण and करण collide), an over-merge risk.
- A DTD entity expanding to markup is untested (none exists in the DBLP DTD).
- LocalJobRunner runs over malformed files are left to the module suites.

## Next gate

Owners fix the defects with their own module tests; then `sbt check`; then code-reviewer.
