# domain-engineer: Phase 04 closeout repair (failing test 3, `http://dx.doi.org/`)

Assembled by the orchestrator, 2026-11-01, from domain-engineer's final report.

**STATUS: BLOCKED-ON hadoop-engineer.** The fix and its feature test are done. The full `sbt check` was never observed green:
- The first run failed scalafmtCheck on hadoop-engineer's in-progress `DblpXmlInputFormat.scala:89`.
- Two later runs hit `ServerAlreadyBootingException` (the sbt lock held by hadoop-engineer's parallel build).

No contractTests counts were observed.

## Change

- **Modified `modules/core/src/main/scala/edu/uic/cs441/scholarchain/core/ids/IdFormat.scala`:**
  - New public `IdFormat.DoiResolverPrefixes`: `https://doi.org/`, `http://doi.org/`, `https://dx.doi.org/`, `http://dx.doi.org/`, `doi:`.
  - The input is still lowercased first, and at most one prefix is stripped.
  - The body check is unchanged: starts with `10.`, contains `/`, and has no whitespace or control characters.
- **Modified `modules/core/src/test/scala/edu/uic/cs441/scholarchain/core/ids/StableIdsSuite.scala`, two new tests:**
  - All 11 prefix variants (any case, whitespace, a bare DOI) give `10.1000/abc` and `work:doi:10.1000/abc`.
  - Validation is not loosened. Each of these is rejected:
    - an empty body;
    - `11.` instead of `10.`;
    - a body with no `/`;
    - a body with a space;
    - a double prefix;
    - `www.dx.doi.org`;
    - `ftp://`;
    - `http:/`.

## Commands and results

1. `sbt fmt`: success; only the two core files changed.
2. `sbt check`: exit 1 (hadoop scalafmtCheck on hadoop-engineer's in-progress file).
3. `sbt core/scalafmtCheckAll core/test`: success. **core 145/145** (StableIdsSuite 24/24).
4. **Mutation check:** the `http://dx.doi.org/` entry was removed with sed, and StableIdsSuite failed 1 of 24 (StableIdsSuite.scala:138). The file was restored from a scratchpad backup and verified.
5. `sbt check` twice more: exit 2 both times (sbt lock).

## Assumptions and risks

- Inputs are only widened: every previously valid DOI gives the same string and ID. There is no ID-formula, wire, or schema-version change.
- `www.doi.org`, `www.dx.doi.org`, and scheme-less `doi.org/` stay rejected on purpose.
- Intended effect: `http://dx.doi.org/` DOIs now produce DOI work IDs. Call sites: DblpRecordParser.scala:162 and SourceNormalizers.scala:152 and :382.
- Unverified: whether test-engineer's contract test now passes. It should, because DblpRecordParser.scala:162 calls the fixed function directly.

## Next gate

The orchestrator reruns `sbt check` after hadoop-engineer finishes; then code-reviewer reviews the core diff.
