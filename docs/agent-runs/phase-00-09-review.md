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
