# hadoop-engineer: Phase 04 closeout repair (failing tests 1 and 2)

Assembled by the orchestrator, 2026-11-01, from hadoop-engineer's final report.

**Orchestrator verification:** an orchestrator `sbt check` ran at 3:42:20 PM (`run-evidence/closeout-orch-check2.log`): exit 0; core 145/145, hadoop 66/66, contractTests 35/35; markTestRun updated.

**STATUS: COMPLETE.** Both defects are fixed; no part A or part B pin changed.

## Changes

- **DblpXmlInputFormat.scala (DblpRecordScanner):**
  - A new `peekStartTag()` helper (mark/reset lookahead) is shared by `next()` and `readRecord`, and `read()` has a one-byte pushback.
  - If a top-level record start tag of the scanner's types appears before the matching close tag, the broken record ends just before that `<`. The `<` is pushed back and `pos` is decremented, so the next record's offset stays exact.
  - A broken record is emitted without the truncated marker, so the parser rejects it as `xml-not-well-formed` under its own key. A record open at EOF keeps `#TRUNCATED#` (truncated-record unchanged).
  - The scaladoc states the rule.
- **DblpRecordParser.scala (normalizeName):** the strip regex is now `[\p{M}\p{Cf}]+`; matches are removed, not replaced by spaces.
- **New ScannerRecoverySuite.scala (7 tests):**
  - an unclosed record and a wrong-close-tag record mid-file;
  - identical boundaries for every split size from 1 to file length + 1;
  - a record open at EOF still gives truncated-record;
  - arXiv scanner recovery at several split sizes;
  - normalizeName with U+00AD, U+200B, BOM, and U+2060.
- **Side effect:** `sbt fmt` formats the whole build, so it also reformatted one modules/core source (formatting only; domain-engineer's in-progress edit). No manual core edits.
- **Not this agent's change:** SourcePipelineSuite.scala's +19 lines are from closeout item 3.

## Commands

- `sbt -batch fmt "hadoop/testOnly ..."`: 20/20.
- `sbt -batch check`: exit 0.
- Two Bash commands were blocked by guard-dangerous (`$'\r'` quoting; a "Test run" grep pattern) and changed nothing.

## Results (sbt check)

| Suite | Passed | Failed |
|---|---|---|
| core | 145 | 0 |
| hadoop | 66 | 0 (+7) |
| contractTests HadoopMalformedInputSuite | 19 | 0 |
| contractTests HadoopIdsTeamsBlocksSuite | 16 | 0 |

## Pins (old = new)

- Part A job 1: INPUT 13, ACCEPTED 7, REJECTED 6, UNSUPPORTED_TYPE 3, MISSING_KEY 1, SCHEMA_FAILURE 2.
- Part A job 6: INPUT 7, TEAMS 7, AUTHOR_PAPERS 35, EMITTED_PAIR 285 (OVERSIZE_TEAM 4 at the IT threshold).
- Part B blocking: INPUT 18, BLOCKS 17, CANDIDATES 268, OVERSIZE_BLOCK 2.
- Every record in dblp-malformed.xml except the last is closed, and the last is cut at EOF, so counts do not change.

## Assumptions and risks

- **Assumption:** a top-level record start tag never appears legitimately inside a record (text `<` must be escaped; the samples have no CDATA).
- **Risk:** a CDATA section containing such a tag would end a record early. That record is rejected, not dropped.
- **Side benefit:** the fix also removes a latent split-duplication path.
- **Unchanged:** the residual risks test-engineer listed (Indic `\p{M}` stripping; a blank normalized name rejecting the work).

## Next gate

code-reviewer reviews DblpXmlInputFormat.scala, DblpRecordParser.scala, and ScannerRecoverySuite.scala.
