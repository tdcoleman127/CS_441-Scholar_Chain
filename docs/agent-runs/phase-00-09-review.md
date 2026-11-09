# Phase 00-09 review

Prepared by: orchestrator, 2026-11-07, at the human's request (prompt recorded in `5-9-human-gate.md`).
Sources: the human gates `phase-00/` to `phase-09/human-gate.md`, `5-9-human-gate.md`, `fast-track-complete.md`, `fast-track-phases-05-09.md`, `project/Versions.scala`, and the repository tree at commit `0166b4b`.
Limits: the orchestrator did not rerun `sbt check` for this review. Test counts are the ones recorded in the gates. The course specification is not in the repository, so risks are grouped by the human's notes (AWS spending starts at Phase 12, evaluation is Phase 16), not by exact Phase 10-17 titles.

## 1. What each phase did

| Phase | What was built | Gate result | Accepted commit |
|---|---|---|---|
| 00 | The `factory-engineer` seed role file. Attempt 1 was rejected because it allowed narrowing and self-approval | Accepted on attempt 2 | `97e1d2c` |
| 01 | The agent factory: 18 agents, 6 hooks, the validator (86/86), and repair-01 (10 checkpoints). Probes 1-4 and 6 pass; probe 5 was deferred | Accepted with open risks | `3d7287e` |
| 02 | The sbt multi-project build, Scala 3, the version ledger (`project/Versions.scala`), the pinned Docker image | Accepted | `313bb4b` |
| 03 | The dataset manifest (DBLP, OpenAlex, Crossref, OpenCitations, arXiv) and 20 core records with stable IDs and a config loader. Guard amendments R4-PROTNORM, R4-SHORTNAME, and RR-11 ratified | Accepted after 3 rounds | `58ff073` |
| 04 | Hadoop MapReduce jobs 1-7, `SourcePipelineMain`, the EMR pipeline script, 35 adversarial tests (3 real defects found and fixed) | Accepted in parts A, B, and C | `8364c79`, `3c554ac`, `06559d0` |
| 05 | WO-00: AWS SDK v2. WO-05: entity resolution (er-policy-v2; 2 MAJOR findings repaired) | Accepted with open risks | `2b5768f`, `8e51f53` |
| 06 | Spark part A (canonical researchers, coauthorship graph, metrics, components) and part B (communities, bridges, k-core, bounded paths, export manifests) | Accepted with open risks | `ab8c769`, `71bb200` |
| 07 | Storage layer: S3, DynamoDB, S3 Vectors, Neptune bulk load, in-memory fakes (3 MAJOR findings repaired) | Accepted with open risks | `abdff53` |
| 08 | Embedding ingestion, RAG templates, the fake embedder. 08x: core community ID and the topic and institution document types | Accepted with open risks | `2da237e`, `1a3a169` |
| 09 | Runtime Graph-RAG: query planner, researcher resolver, retrieval, evidence bundle, template synthesis, claim verifier. Benchmark B01-B19 all as expected | Accepted with open risks | `b9e0c69` |

Last recorded `sbt check` (Phase 09 gate): 406 passed, 0 failed (core 160, hadoop 66, rag 58, storage 59 with 2 skipped, contract tests 35, entity resolution 19, spark 9).

## 2. What is installed

Pinned in the ledger (`project/Versions.scala`):

| Item | Version |
|---|---|
| Scala | 3.3.8 (3.3 LTS line) |
| sbt | 1.12.15 |
| Java target | 17 |
| scalafmt | 3.11.5 |
| Hadoop (Provided, EMR 7.14.0) | 3.4.2 |
| Spark (Provided, EMR 7.14.0) | 3.5.8 |
| upickle (JSON) | 4.4.3 |
| Typesafe Config | 1.4.9 |
| munit (tests) | 1.3.6 |
| AWS SDK v2 | 2.57.8 |
| slf4j / logback | 2.0.17 / 1.5.38 |
| Container image | Corretto 17 on Amazon Linux 2023, pinned by digest |

The human asked to check the current Scala 3 version. The ledger holds 3.3.8. Whether a newer 3.3.x patch exists has not been checked; that check belongs to `dependency-updater`.

Local machine: native Windows Hadoop (`winutils.exe` and `hadoop.dll` in `C:\hadoop\bin`, `HADOOP_HOME=C:\hadoop`).

Not built yet:
- `infra/terraform/` contains only `.gitkeep`.
- `modules/services/` is empty.
- `scripts/run-bounded-pipeline.sh` is the only script, and it has never been run.
- No AWS resources exist. Nothing has run on EMR, Neptune, DynamoDB, or S3 Vectors. All tests run against fakes.

## 3. Risks still open before Phases 10-17

### 3.1 Critical, with no record of closure
- **Phase 01 R1 and R2 (CRITICAL guard bypasses).** R1: brace or glob expansion gets past the `code-reviewer` read-only fence. R2: an apostrophe in a comment or heredoc hides `$'...'` and backslash-newline from the guard. Both were assigned to a factory-engineer "repair-02 at the start of Phase 2". No later gate mentions repair-02. R3 and R4 (MINOR) are also still open.
- **R-06a-1 (MAJOR).** The rule for which mention becomes a researcher's ID is decided in Spark. It should move to entity resolution before the first real-data run, or every researcher ID changes later.
- **R-06a-9.** The Spark jobs compile against Scala 2.13 artifacts, but EMR's Spark is built for Scala 2.12. This could block EMR runs entirely and has never been tested (build-engineer and spark-engineer).

### 3.2 Before the first paid AWS run (Phase 12)
- AWS facts unverified because the aws-docs MCP server never connected: R-07-1, R-08-8, R-09-8 (openCypher syntax, S3 Vectors limits, the Titan V2 model ID and dimension).
- R-06b-1: Spark checkpointing on EMR probably falls back to local checkpoints, which are not fault-tolerant.
- Storage and provenance gaps: R-09-2 (no storage lookup port; RAG loads whole export objects into memory), R-09-3 (edges carry no provenance, against CLAUDE.md law 3), R-09-4 (no pair-edge template), R-06b-5 (exported works have empty `externalIds`, so Neptune loses DOIs).
- R-05-5 / R-06a-8: entity resolution (`ResolveMain`) runs in one JVM, with no EMR path.
- R-07-13: the vector index names, TTL, and IAM list that the Terraform pass needs.
- R-08-5 / R-08-14: deleting stale vectors is data destruction and needs a human decision.
- The local main-class run-rule amendment was deferred in Phase 04 (review found 2 MAJOR fence bypasses).

### 3.3 Human tasks
- Gold-set labeling, about 50 pairs (R-05-4). Entity resolution stays uncalibrated until this is done. R-05-3, R-06a-11, and R-06b-6 depend on it.
- Cost review before Phase 16 (the human's own note).

### 3.4 Carry into the services phase
- R-00-1 (services can import Bedrock and storage SDK classes), R-09-5 (the audit record is returned, not persisted), R-09-1 (some RAG types should move to core).

### 3.5 Accepted residual risks and housekeeping
- RR-01 to RR-11: guard bypass classes covered only by the permission prompt. Keep denying prompts whose path arguments contain quotes, backslashes, globs, or absolute repository paths.
- R-03-3: raw affiliation text in evidence is not checked automatically.
- Phase 02: the Docker `FROM` check is weak.
- `phase-02/human-gate.md` still says `Accepted commit: <40-character hash>`. It should be `313bb4bd025bfd86348bbbff19bb9afb9eec42f8` (see `phase-02/commit.txt`).
- R-07-14 (optional): worktree sessions cannot write files.
- About 40 more MINOR items, each with a named owner. The full list is in `phase-00-09-minor-fixes.md`.

### 3.6 Recommended order before Phase 10
1. Confirm repair-02 for R1 and R2.
2. Fix the MCP connections.
3. Do the gold-set labeling.

## 4. Follow-up findings, 2026-11-07

Recorded by the orchestrator while acting on section 3.6.

- **R1 confirmed open (static read).** In `.claude/hooks/guard-dangerous.sh` section 11, `ro_split` rejects `` $ ` \ " ; & < > ( ) `` outside single quotes but not `{ } * ?`. A token like `{--pre=echo,x}` does not start with `-`, so `ro_check_opts` counts it as an operand, and bash then expands it into `--pre=echo x`.
- **R2 confirmed open (static read).** `sc_bs_newline` and `sc_dollar_quote` (section 0) still do not model comments or heredoc bodies. An odd apostrophe puts the scanner into a false single-quote state.
- **repair-02 proposal: COMPLETE, not applied (factory-engineer; about 10 minutes over the 30-minute time box).**
  - Evidence: `phase-00-09-evidence/repair-02/repair-02.patch` (sha256 `d37291da57cdf7eb6974b494bcde270af10954ee5b17263451e9f71106dba3db`) and `repair-02-probes.txt` (sha256 `dd0f7df720a98c7b543881cbb9112f80e596ae9d473b2e3e64e3f53367a26397`). The orchestrator re-verified both hashes and `git apply --check` (OK).
  - Live hook, probed through the hook's JSON input only (no probe command was executed): R1, R2, and R4 confirmed. 54 probes, 29 decided wrongly.
  - R1 is broader than reported: quoted or split find/file/date options also pass (`find . -name zz '-delete'`, `date '-s' ...`).
  - R2 is broader than reported: `$'...'` after a comment apostrophe passes even with no line continuation, for every caller, including the orchestrator and factory-engineer.
  - Patched copy: 54/54 new probes as expected (27 bypasses blocked, 16 legitimate forms allowed). Phase-03 regression: 146/146 Bash cases OK. The 2 unexpected rows were guard-ownership cases the harness misrouted. `bash -n` OK on 6 hooks; validator PASS (92).
  - Closes R1, R2, and R4.
  - Privilege delta: NARROWED for code-reviewer (braces, globs, leading `~`, quoted find/file/date options). NARROWED for all callers (the two R2 shapes; multi-line commands or commands containing `${` may not contain `$'` or `$"` anywhere). WIDENED narrowly for code-reviewer (`git -C <repository root>` only).
  - **R3 confirmed and re-ranked to at least MAJOR.** A 50,105-character command did not finish in 120 s under load, and settings.json gives the hook a 20 s timeout, so any caller can pad a command until the guard fails open. Not fixed: a fail-closed length cap needs a measured bound (follow-up repair).
  - Next gate: fresh code-reviewer pass on the patch; human ratification of (a) R1 narrowing, (b) R2 narrowing, (c) R4 `git -C` widening, (d) R3 re-rank and a length-cap follow-up; then the human runs `git apply`, commits, and restarts.
- **code-reviewer on repair-02: REQUEST_CHANGES (repair loop 1).**
  - Coverage: the patch hash could not be checked inside the reviewer's fence (the orchestrator had already verified it); the probe-file spot-check was not done.
  - Verified closed by reading the code: R1 braces and globs; find/file/date checks on quote-removed words; R2 backslash-newline in any quote state; R4 `-C` handling. No other agent's fence changed.
  - F1 MAJOR: the new `$'` rule triggers on `${` but not on `$(` or backticks, which open the same unmodelled quoting context inside double quotes. A single-line command can still desync the scanner. Same class as R2, every caller except code-reviewer.
  - F2 MINOR: `~` right after an unspaced pipe is not rejected (`ls|~/bin/cat x`); no new capability, but the docs overstate the rule.
  - F3 MINOR: the `git -C` root check is lexical, so `sub/..` through a planted symlink resolves elsewhere.
  - Out of scope, logged for a separate repair: the read-only terraform check matches `terraform fmt -check=false` and a `#` comment containing `-check`.
  - R3: the reviewer recommends it NOT block this patch; track it as a separate repair with a residual ID (length cap, or fail closed on timeout).
  - Routed F1-F3 to factory-engineer as repair-02b (proposal only), to be followed by a fresh code-reviewer pass that includes the probe spot-check.
- **repair-02b proposal: COMPLETE, not applied (factory-engineer).**
  - Combined patch (repair-02 plus 02b, against the live tree): `phase-00-09-evidence/repair-02/repair-02b.patch`, sha256 `76ad10ef194ae376075e1c361c3ce945575eafa135fa962e6554516b693d6931`. Probes: `repair-02b-probes.txt`, sha256 `50b4603b47d9c43c7e03dbf1e5e400ea28e4c1081b4218c86d7f433f6964ab77`. The orchestrator re-verified both hashes, `git apply --check` (OK), and that the live `.claude/**`, CLAUDE.md, docs/agents.md, and `.mcp.json` are unchanged. **This patch supersedes `repair-02.patch`; apply only `repair-02b.patch`.**
  - All three findings were confirmed on the repair-02 baseline: 37 new probes, 12 decided wrongly, including the F1 `$"` locale variant.
  - Patched copy: 37/37 new probes OK, 54/54 repair-02 probes OK, 146/146 phase-03 Bash regression cases OK. `bash -n` OK; validator PASS (18 agents); hook mode kept at 100755.
  - F1 fixed: the `$'`/`$"`-anywhere fallback also triggers on `$(`, a backtick, `$[`, `<(`, and `>(`. F2 fixed: `~` after an unspaced `|` rejected. F3 fixed: any `..` segment in the `git -C` operand rejected before the root check.
  - New over-blocks: for every caller, a single-line command with a substitution and `$'` or `$"` anywhere (for example `grep -n 'foo$' $(git ls-files '*.md')`); for code-reviewer, `..` spellings of the root in `git -C`.
  - Ratification decisions requested: (a) F1 fix with its extra triggers and over-block; (b) F2 fix; (c) F3 fix (reject `..` rather than resolve physically); (d) updated docs/agents.md section 5 text; (e) the terraform `-check` issue stays logged and out of scope. These are in addition to the repair-02 decisions above.
  - Next gate: fresh code-reviewer pass with the probe spot-check.
- **code-reviewer on repair-02b: APPROVE (0 findings).**
  - F1, F2, and F3 verified closed by reading the code; the repair-02 parts (R1, find/file/date on W, backslash-newline anywhere, R4 shift) are unchanged.
  - Probe spot-check: 12 cases traced through the patched code (5 expected ALLOW, 7 expected BLOCK); every recorded decision matches.
  - docs/agents.md text matches the code. Privilege delta: all narrowing except `git -C <root>`; no other fence or floor-invariant section touched.
  - Coverage limits stated by the reviewer: the validator, `bash -n`, and the probe harness were not rerun inside its fence (figures taken from factory-engineer's evidence); the search for further F1-class constructs was not exhaustive.
  - Notes for ratification: the `git -C <root>` allowance also applies to unregistered subagents (section 11 covers them too); harmless, but wider than the docs' "code-reviewer" wording. Any path segment starting with `..` is refused (over-block only).
  - R3 (timeout fail-open) remains open as a separate factory-engineer repair.
  - Repair loops used: 1. Next gate: human ratification of decisions (a)-(d) from repair-02 and (a)-(e) from repair-02b; the human applies only `repair-02b.patch`, runs the validator, commits, and restarts.
- **MCP connection causes** (each failure came from the local environment, not from `.mcp.json`):

| Server | Cause | Status |
|---|---|---|
| aws-docs | `uv`/`uvx` not installed | `uv` 0.13.7 installed with winget; package `awslabs.aws-documentation-mcp-server@1.2.2` cached. Takes effect after a Claude Code restart |
| aws-api | `uv`/`uvx` not installed; profile env var not set | `uv` installed; `awslabs.aws-api-mcp-server@1.5.6` cached. The human must still create the read-only AWS profile `scholarchain-readonly`, which agents may not inspect |
| terraform | Docker Desktop daemon not running | Docker started (29.2.0); image `hashicorp/terraform-mcp-server:1.3.0` pulled. Docker must be running when Claude Code starts |
| github | `SCHOLARCHAIN_GITHUB_READONLY_PAT` not set, so the Authorization header was empty | Human action: create a fine-grained read-only PAT for this repository and set it as a user environment variable |

- **Gold-set labeling preparation: COMPLETE (entity-resolution-engineer).**
  - Worksheet: `modules/entity-resolution/gold/v1/labeling-worksheet-v1.csv`, 48 real sample pairs, blind (no scores, decisions, or suggestions), with provenance on both sides. Guide: `LABELING-GUIDE.md` in the same folder.
  - Strata (selected/available): shared-researcher-id 7/7, same-record-other-position 5/45, identical-name-other-record 9/46, homonym-number-suffix 7/46, spelling-variant 6/7, name-order 1/1 (quota 3), initials-or-middle-name 6/24, shared-name-token 3/14, different-name 4/630. Selection seed `scholarchain-er-gold-v1`, rule er-gold-selection-v1.
  - New loader `Evaluator.loadWorksheet` and `WorksheetEvalMain` (P/R/F1 per path and per stratum, Wilson 95% intervals, threshold sweep). GoldWorksheetSuite 7/7.
  - `sbt check` exit 0 (observed by the agent; marker `.claude/.last-test-run` updated 2026-11-08T00:22:11Z): core 160, storage 59 passed / 2 skipped, rag 58, entity resolution 26, hadoop 66, contract tests 35, spark 9; 0 failed.
  - Policy er-policy-v2 unchanged and still UNCALIBRATED. Thresholds unchanged (accept 0.85, reject 0.55).
  - Limits: all sample records are synthetic, with only 41 usable mentions and one valid ORCID (Josiah Carberry). 48 pairs can start calibration but not finish it; a larger set needs real sample data from dataset-engineer. Metrics will be stratum-weighted, not population rates.
  - Human next steps: (1) label the worksheet in place (same / different / unsure, UTF-8); (2) run `sbt "entityResolution/runMain edu.uic.cs441.scholarchain.er.WorksheetEvalMain modules/entity-resolution/gold/v1/labeling-worksheet-v1.csv --labeler <name>"` (runMain is blocked for agents); (3) commit the labeled worksheet. The diff still needs a code-reviewer pass. The labels themselves are a human task (docs/agents.md section 10; a set labeled by the system it evaluates would be circular).

## 2026-11-07 follow-up 2: repair-03 (R3, guard timeout fail-open)

Baseline: HEAD 313073f (repair-02b applied). After the commit, the orchestrator re-verified: the repair-02b patch sha256 matches the commit message, `git apply --check -R` OK, the commit's constitutional diff is identical to the patch, `bash -n` OK on 6 hooks, validator PASS, `sbt check` [success] (174 s).

- **repair-03 proposal: COMPLETE, not applied (factory-engineer; about 65 minutes against a 30-minute time box, mostly probe wall time).**
  - Evidence: `phase-00-09-evidence/repair-03/repair-03.patch` (sha256 `90bd6690cf01b9fbef5d483d1bd72d734d124bb92184824740923f00a46a087c`) and `repair-03-probes.txt` (sha256 `8f2c97d53de7b3cbe3838adb4901f3eb37215c2fe29d3d2c98b7d28561c8a5c2`). The orchestrator re-verified both hashes after copying, `git apply --check -v` OK on 313073f, the live constitutional files unchanged, and GNU `timeout` 8.32 present at `/usr/bin/timeout`.
  - R3 is broader than reported: guard run time grows with the number of segments, not only with length. On the baseline, a 59-byte 12-segment `;` command took 18.5 s, a 119-byte one 38.2 s, and a 4 KB word list 22.3 s, so short commands already fail open.
  - Fix: rule R3-LENCAP (block hook input over 65,536 bytes; block a Bash `command` or AWS MCP `cli_command` over 2,048 bytes, before parsing) and rule R3-DEADLINE (the check runs as a child under `timeout -k 1 15`; only exit 0 or 2 pass through; a timeout, any other status, or a missing `timeout` binary blocks). New residual RR-12 (deadline blocks depend on load). settings.json and CLAUDE.md unchanged.
  - Results on the patched copy: new R3 probes 21/21, repair-02 54/54, repair-02b 37/37, phase-03 Bash regression 146/146; `bash -n` OK; validator PASS (92); mode 100755 kept. Deadline-path probes blocked at 15.2 s.
  - Longest legitimate command measured: 254 bytes. Near-deadline legitimate commands: six-command `&&` chains (L06/L08) at 12.5 s patched, 15.5-16.3 s on the baseline run.
  - Logged, not fixed: R3-PERF (about 1-1.5 s per segment; about 4 s floor from Python start-up), R3-OWN (guard-ownership.sh linear in payload size; about 8 MiB or more to reach 20 s), R3-STOP (verify-tests-ran.sh cost grows with changed files).
  - Privilege delta: NARROWED for every caller (length caps, deadline, hard dependency on GNU `timeout`). Nothing widened. No roster, fence, ownership, or floor change.
- **code-reviewer on repair-03: APPROVE (2 MINOR, non-blocking).**
  - No `--sc-inner` bypass (settings.json passes no arguments; hook input reaches the child only on stdin). Exit-code handling is sound (`set -uo pipefail`, no `-e`; ask JSON passes through on exit 0; a kill after printing ask gives 124/137 and blocks). The outer process does no Python and no input-dependent work before the fork. Byte counting confirmed in bytes (R3-11). Spot-check of R3-01, R3-02, R3-07/07b, R3-12, R3-14, R3-18 and phase-03 rows A01, A07, A08, D05, R1-09: all match.
  - F1 MINOR: the docs' definition of a "segment" (`;`, `|`, `&&`, `||`, newlines) does not match the hook's `tr ';|&()\`'` split, which ignores quoting, counts `&&`/`||` twice, and also counts `(`, `)`, and backticks. Example: `grep -rnE 'a|b|...|l' modules | wc -l` is 13 segments to the hook. Usability only; fail closed.
  - F2 MINOR: no probe exercises the 65,536-byte input cap (65,536 allowed through / 65,537 blocked).
  - Risks noted, no verified failure input: about 4 s of margin outside the deadline under extreme load; routine `&&` chains, parallel Bash calls, or a background sbt may cause intermittent deadline blocks; the 2,048-byte cap blocks long heredoc commits and long AWS MCP queries (use the Write tool and `git commit -F`); a host without GNU `timeout` blocks every call; locale restoration after `local LC_ALL=C` not confirmed (no observed decision change).
  - Coverage limits stated by the reviewer: it could not apply the patch, run `bash -n` on the patched hook, re-run probes, or hash the evidence (the orchestrator did).
  - Repair loops used: 0. Next gate: human ratification of factory-engineer's decisions 1-7 (caps, 15 s deadline Option A or a new Option B proposal with a 30 s hook timeout and 24 s deadline, `timeout` dependency, privilege narrowing, RR-12 and R3-PERF, R3-OWN and R3-STOP tracking, then apply, commit, restart). F1 and F2 may be fixed in a repair-03b before ratification or tracked as minor items.
- **repair-03b: BLOCKED-ON (factory-engineer, 2026-11-08). The new F2 boundary probe found a fail-open defect in repair-03; do not ratify `repair-03.patch`.**
  - Evidence (orchestrator re-verified hashes after copying): `phase-00-09-evidence/repair-03/repair-03b-DRAFT-not-ready.patch` (sha256 `300a5f68224e77b3b83da811d287093bfd982e1c661e27d3546716f09cdb29a0`, combined 03 + 03b, still contains the defect) and `repair-03b-probes.txt` (sha256 `795047b6d2d12f688a2f473d0581c4829cc7d8f80d29f25f35f48a5552b908b5`; see sections 2.2, 2.5, and 3).
  - Defect: hook input of exactly 65,536 bytes passes R3-LENCAP. The outer guard then passes it to the child with `<<< "$SC_INPUT"`, which appends a newline (65,537 bytes). Bash 5.2.37 (Git for Windows) here-strings over 65,536 bytes deadlock in the outer process before `timeout` starts, so R3-DEADLINE never runs. Claude Code would time the hook out at 20 s and run the call unchecked. A padded `description` field can hit this deliberately. Measured: 65,535 allowed (5.1 s), 65,536 hung (R3B-01 still running at 60 s; R3B-03 instrumented shows no timeout process started), 65,537 blocked (0.2 s). The live hook (313073f) has no here-string and was not probed at this size.
  - code-reviewer's repair-03 APPROVE did not catch this: its F2 finding was that this exact boundary was untested.
  - Done in the draft: F1 docs paragraph rewritten to match the hook's character-level segment count (checked with the hook's own split pipeline, 13/13); "allow or ask" wording; RR-12 margin clause; the 124/137 block message text updated (string only). Optional probes R3B-07 (missing timeout) and R3B-08 (child exit 3) both block.
  - Hung probe processes left running (background task b6leaqi3c; Windows pids 110964, 9752, 4244). factory-engineer's attempts to stop them were denied by the permission classifier; the orchestrator did not stop them on its behalf. Human action.
  - Next gate: choose the fix (a: `SC_MAX_INPUT_BYTES=65535`; b: pipe the input to the child instead of a here-string), repair-03c, then a fresh code-reviewer pass.
- **Human decision (2026-11-08): fix option (a), `SC_MAX_INPUT_BYTES=65535`, "for now". Option (b) is recorded as future hardening.**
- **repair-03c proposal: COMPLETE, not applied (factory-engineer; about 35 minutes against a 30-minute time box).**
  - Evidence: `phase-00-09-evidence/repair-03/repair-03c.patch` (sha256 `adba885751aeac1735ad860419904512cb6a8d185558526519f33d24ade636bc`; combined 03 + 03b + 03c against 313073f; **supersedes `repair-03.patch` and the 03b draft, so apply only this one**) and `repair-03c-probes.txt` (sha256 `103b8de5a1f5ceb1f71f31ef02cb2b9e07e7a6145dc9d30953569eb77d684976`). After copying, the orchestrator re-verified both hashes, ran `git apply --check -v` on the live tree (both files OK), and confirmed the live constitutional files are unchanged.
  - Change from the 03b draft: the hook cap goes from 65536 to 65535, with a comment. The docs/agents.md section 5 paragraph now says the cap is 65,535 bytes and gives the reason: the here-string appends 1 byte, and on bash 5.2.37 here-strings over 65,536 bytes deadlock. It says the bound has no spare byte and depends on the bash build, and it names option (b) as future hardening.
  - Probes, run on the patched copy with every hook run under an outer `timeout -k 2 40`:
    - Boundary: 18/18. 65,534 and 65,535 bytes allowed in about 3.6 s; 65,536 and 65,537 bytes blocked in 0.2 s; UTF-8 counted in bytes; AWS MCP matcher covered; a missing `timeout` or a child exit 3 blocks.
    - R3C-04: the 65,535-byte input reaches the child under the guard's own `timeout -k 1 15`, with no deadlock.
    - Regression: R3 21/21, repair-02 54/54, repair-02b 37/37, phase-03 Bash 146/146.
    - `bash -n` OK on 6 hooks; validator PASS (92); mode 100755 kept; round-trip apply equals the probed mirror.
  - Privilege delta: NARROWED for every caller, 1 byte stricter than 03/03b. Nothing widened.
  - factory-engineer observed a stray bash process running the LIVE hook (MSYS pid 46957, parent gone), likely Claude Code's own hook run on a long tool call. That fits R3 on the live hook. By the time the orchestrator checked, it had exited.
- **code-reviewer on repair-03c: APPROVE, no findings.**
  - The size check and the here-string use the same `SC_INPUT`, assigned once, so the largest possible here-string is 65,536 bytes, the measured safe size.
  - Trailing newlines stripped by `$(cat)` are harmless, because the cap measures exactly what goes into the here-string (R3C-11, R3C-12).
  - Byte counting is confirmed by R3C-14: 65,536 bytes of 2-byte UTF-8 was blocked.
  - No Python and no parsing happen before the fork.
  - The docs text matches the hook: values, rationale, F1 segment wording, the RR-12 margin of about 4 s, and option (b). F2 is addressed by R3C-02 to R3C-07.
  - Floor intact: terraform apply and force push are blocked at and below the cap, and `.env` reads are blocked. Exit-code handling is sound.
  - The reviewer confirmed the probed copy matches the patch hunks line for line.
  - Risks noted, no failure input:
    - Zero margin depending on the Git for Windows build. An update could lower the limit, and nothing re-tests this boundary automatically. Option (b) or an automated boundary check would fix it (owner factory-engineer).
    - The pre-fork `$(cat)` read has no size limit, as on the live hook; inputs over about 65 KB were not probed.
    - A native grandchild surviving the timeout kill could hold the pipes open; the harness would not detect it.
  - Coverage limits stated by the reviewer: it could not hash, apply, run `bash -n`, or run probes, and it read only the pass totals for probes-file lines 300-509.
  - Leftover hung repair-03b probe processes are still running (MSYS pids 6747, 6757, 6759, 6778, 13105, 13110). Human action.
  - Repair loops used: 0. Next gate: human ratification of decisions 1-7 in the human gate:
    1. caps of 65,535 input bytes and 2,048 command bytes;
    2. a 15 + 1 s deadline under the existing 20 s hook timeout (Option A);
    3. the `/usr/bin/timeout` dependency;
    4. the narrowing for every caller;
    5. RR-12, R3-PERF, and the zero-margin residual;
    6. tracking R3-OWN, R3-STOP, and option (b);
    7. apply `repair-03c.patch` only.

    Then commit and restart. After the restart, the orchestrator re-verifies `bash -n`, the validator, and a live R3C-03/R3C-05 boundary spot-check.

## 2026-11-08 follow-up 3: gold-set pilot (R-05-4)

- **Labeling: done by the human (labeler TC), 48/48 pairs**, using a local side-by-side labeling page that the orchestrator wrote in the session scratchpad. The page is not in the repository. A Node round-trip check on the real worksheet showed 0 changes to non-label cells across 48 rows and 29 columns. The page shows no score, decision, or suggested label.
- **`sbt check` with the labeled worksheet: [success]** (327 s, 0 failed). GoldWorksheetSuite confirms that only the label, labeler, and notes columns differ from a regeneration. Run by the orchestrator, before repair-03c was applied.
- **Evaluation, run by the human** (runMain is blocked for agents). Report: `phase-00-09-evidence/gold-eval-v1.txt` (sha256 `e3fe186080ffbc707547c5c4b017bb1b3904ceff7298ff5bfe1414216ea7346b`).
  - The worksheet sha256 printed by the evaluator (`449c3b828d8495ca999a2d05d1cdc7b0f42fdca07a6ac7b627a66a1e163152e4`) matches the file on disk.
  - The first attempt failed with a PowerShell / `sbt.bat` quoting error. It was re-run successfully.
  - Policy er-policy-v2, features er-features-v2. Counts: labeled 48, unlabeled 0, unsure 0, untraced 0, malformed 0. Labels: 23 same, 25 different.

    | Path | n | TP | FP | TN | FN | U+ | U- | P [95%] | R [95%] | Coverage |
    |---|---|---|---|---|---|---|---|---|---|---|
    | exact-id | 8 | 6 | 1 | 0 | 0 | 0 | 1 | 0.857 [0.487, 0.974] | 1.000 [0.610, 1.000] | 0.875 |
    | deterministic | 18 | 3 | 0 | 8 | 7 | 0 | 0 | 1.000 [0.439, 1.000] | 0.300 [0.108, 0.603] | 1.000 |
    | probabilistic | 22 | 0 | 2 | 0 | 0 | 7 | 13 | 0.000 [0.000, 0.658] | 0.000 [0.000, 0.354] | 0.091 |
    | llm-adjudicated | 0 | - | - | - | - | - | - | n/a | n/a | n/a |
    | **overall** | 48 | 9 | 3 | 8 | 7 | 7 | 14 | 0.750 [0.468, 0.911] | 0.391 [0.222, 0.592] | 0.563 (unresolved 0.438) |

    U+ and U- are pairs left unresolved or flagged as conflicts, split by the human label (same or different).
  - **Observations.** Each is recorded for its owner. No threshold or policy change follows from them.
    - All 7 false negatives come from the deterministic path, where recall is 0.300. By stratum: spelling-variant 3, same-record-other-position 2, different-name 1, name-order 1.
    - The 2 same-record-other-position pairs were labeled `same`, but the labeling guide says two positions on one paper are normally two people. The human may want to re-check those two labels.
    - False positives: 1 on exact-id (stratum shared-researcher-id: a shared identifier on a pair labeled `different`), and 2 on probabilistic (homonym-number-suffix 1, initials-or-middle-name 1).
    - The probabilistic path leaves 20 of 22 pairs unresolved. The LLM-adjudication path was not exercised (n=0).
    - Threshold sweep: lowering accept to 0.60 raises recall to 0.696 and lowers precision to 0.533. Raising reject to 0.70 lowers unresolved to 0.104 but adds 5 false negatives.
  - **Limits.**
    - All records are synthetic, and 48 pairs are far below the spec's minimum of 200 (project-description.md lines 464 and 1230). The intervals are wide.
    - The spec's error analysis needs at least 10 false positives and 10 false negatives; this set has 3 and 7.
    - **er-policy-v2 stays UNCALIBRATED, with thresholds unchanged** (accept 0.85, reject 0.55). Tuning on this set would mean fitting synthetic data.
  - **R-05-4 status: pilot done; the tooling works end to end.** The real gold set is still open: dataset-engineer pulls a bounded real DBLP and enrichment sample, entity-resolution-engineer builds a review queue of at least 200 pairs (researcher pairs, plus work pairs if implemented), and the human labels it. Deterministic-path recall and the exact-id false positive go to entity-resolution-engineer, to be re-checked on the real set.
