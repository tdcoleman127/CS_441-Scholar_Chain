# code-reviewer: Phase 04 closeout, round 2 (repairs and adversarial suites)

Assembled by the orchestrator, 2026-11-01, from code-reviewer's final report.

**VERDICT: APPROVE** (2 MINOR, non-blocking).

- **Scope:** test-engineer's adversarial suites; domain-engineer's DOI resolver prefixes; hadoop-engineer's scanner recovery and `\p{Cf}` strip. Items 3 and 4 were approved earlier (`code-reviewer-report-closeout.md`).
- **Evidence:** the reviewer did not run sbt; it relied on `run-evidence/closeout-orch-check2.log` (core 145, hadoop 66 including ScannerRecoverySuite 7, contractTests 35, `[success]` 3:42:20 PM). Every in-scope file was last modified between 15:29 and 15:37, before that run, so the log covers the reviewed code.
- **Other checks:** `cat -A` confirms the invisible characters are really in the test literals. Two reviewer commands were blocked by guard-dangerous and changed nothing.

## Verified

- **Scanner (DblpXmlInputFormat.scala:61-124):**
  - Pushback leaves `pos` at the next tag's offset.
  - No infinite loop: the tag name is consumed before another `<`.
  - Split ownership: no tag is claimed by both readers or by neither, including boundaries inside a tag and a tag exactly at `end`.
  - The truncated marker is still added only at EOF.
  - The arXiv reader uses the same class (SourceJobs.scala:68).
  - The tests cover every split size and exact offsets, and fail without the fix.
- **DOI (IdFormat.scala:86-92, 169-174):** one prefix added; no existing prefix starts with another; at most one is stripped; the body check is unchanged; every previously accepted input gives the same string, so no existing ID changes. Covered end to end by HadoopIdsTeamsBlocksSuite.scala:42.
- **`\p{Cf}` strip (DblpRecordParser.scala:86-93):** `normalizedName` feeds only blocking keys and the stored field, not `mentionId` (line 229), so no stable ID changes. New collisions occur only between names that differ by format characters (ZWNJ, ZWJ), which is intended for blocking.
- **Adversarial suites:** real values are asserted (for example 59,961 = 12 × 4950 + 561); no shared mutable state; independent of test order.
- **Ownership:** every change is in its owner's tree; no constitutional paths were touched.

## Findings

1. **MINOR (cleanup).** HadoopMalformedInputSuite.scala:181-183: the XXE test never deletes its `sc-xxe*` temp directory or `secret.txt`, so each `sbt check` leaves one behind. Owner: test-engineer.
2. **MINOR (law 7).** test-engineer-closeout-report.md said "n = 1 to 1000", but the test checks n in {1, 2, 3, 7, 16, 64, 1000}. Owner: orchestrator; corrected in the report.

## Risks (not findings)

- A CDATA section or XML comment containing a record start tag would end a valid record early: two rejected fragments, nothing dropped silently. dblp.xml uses neither.
- Production DBLP `http://dx.doi.org/` ees now join by DOI (intended). The samples contain none, so the pins are unchanged.

## Next gate

The human phase 04 closeout gate.
