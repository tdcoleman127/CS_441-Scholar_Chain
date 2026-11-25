# Agentic software factory evaluation (phases 00 to 14)

Status: measured from the archived records at commit `0c82710`. At that commit Phase 14 was in progress and its deployment was not recorded. Since then, the Phase 14 deployment has been recorded in files that are not yet committed: the deploy-engineer report [reports/deployments/2026-11-24-dev-phase-14.md](../../reports/deployments/2026-11-24-dev-phase-14.md) (deploy of `0c82710` to dev in fixture mode, `RESULT status=DEPLOYED`, smoke test 9 of 9 PASS) and the orchestrator's copy [phase-14/deploy-engineer.md](../agent-runs/phase-14/deploy-engineer.md). The counts below do not include that deployment step; it adds one deploy-engineer unit (`STATUS: COMPLETE`, read-only verification; reviewed in [phase-14/review-01.md](../agent-runs/phase-14/review-01.md): REQUEST_CHANGES, then one repair round; not first-pass). Phases 15 to 17 have only empty placeholder files at `0c82710`. The Phase 16 offline experiment results under [experiments/phase-16/offline/](../../experiments/phase-16/offline/results.md) are also uncommitted and are not counted here.

Raw counts, with a source path for every row: [factory-counts.csv](factory-counts.csv).

## 1. Method

### Sources read

- Every `human-gate.md` under [docs/agent-runs/](../agent-runs/) for phases 00 to 14, plus [phase-00-09-human-gate.md](../agent-runs/phase-00-09-human-gate.md) and [fast-track-complete.md](../agent-runs/fast-track-complete.md).
- Every archived review report: `phase-01/review-report*.md`, `phase-04/code-reviewer-report-*.md`, `phase-04/entity-resolution-engineer-review-partb.md`, `phase-10/review-0*.md`, `phase-11/review-0*.md`, `phase-12/review-0*-code-reviewer.md`, `phase-13/review-0*-code-reviewer.md`, and the review sections of [phase-03/agent-report.md](../agent-runs/phase-03/agent-report.md) and [phase-00-09-review.md](../agent-runs/phase-00-09-review.md).
- Every agent report with a `STATUS:` line (found with a search for `STATUS:` across `docs/agent-runs/**/*.md`).
- `git log` in a clone of `0c82710` (52 commits).

The evidence patches, probe outputs, and mutation copies under `phase-03/evidence/` and `phase-00-09-evidence/` were not re-run. Only the pass counts quoted in the gates and reports were used.

### What was counted, and how

| Metric | Definition used | How it was found |
|---|---|---|
| First-pass acceptance | An agent's deliverable in one work unit was accepted with no repair loop. A repair loop is any change requested by code-reviewer, by red adversarial tests, or by the human before acceptance, and also a pre-review revert. Read-only reviews, repair-only tasks, and BLOCKED-ON runs with no defect are marked n/a. | Gate decisions and the first review verdict for each unit |
| BLOCKED-ON events | A report whose status line is `BLOCKED-ON`, or a section the orchestrator titled as BLOCKED-ON. Hook message text that contains the word is not counted. | Search for `STATUS:` and `BLOCKED-ON` |
| Ownership events | (a) A write blocked by guard-ownership.sh, as recorded in a report or gate. (b) A reviewer finding about the ownership map, a module boundary, or the constitutional process. Reviewer statements that ownership was clean are counted separately. | Search for `guard-ownership`, `ownership`, `territory` in gates and reviews |
| Reviewer findings by severity | Severity-tagged findings in each archived review. The labels differ across phases, so they are kept in separate columns: CRITICAL/BLOCKER, MAJOR/HIGH, MEDIUM, MINOR, LOW (LOW-MEDIUM is counted as LOW), NIT. They are never converted from one scale to another. Findings that a review only re-grades from an earlier round are not counted again. | Review files. For fast-track phases 05 to 09 and for phases 02 and 14, only the counts written in the gate or agent report exist |
| Repair iterations | Repair reports (`repair-NN`, `*-repairN-*`) and repair rounds that the orchestrator recorded inside a combined report. Re-plans and work reruns are listed in the notes, not counted as repairs. | File names and report sections |
| Manual interventions | Items listed under a "Manual changes" or "Manual interventions" heading in a human gate. Designed human tasks (applying Terraform, ratification `git apply`, gold-set labeling) are listed in notes unless the gate lists them under that heading. | Human gates |
| Prevention events | A test, probe, hook, or script gate that recorded stopping an action or exposing a defect. Benign commands blocked by the guard (false positives) are counted separately. | Gates and reports |
| Substantial human rewrite | A human gate or report saying that the human rewrote an agent-generated artifact. | Human gates |

Phase-row severity totals add up the review rows of that phase. Agent rows assign a finding to the agent the review names as its owner. Findings that have no named owner, or were not checked, are listed in the CSV notes and are not assigned to any agent.

## 2. Measured facts

### 2.1 Per phase

| Phase | Owning agents | Gate outcome | Review rounds | Repairs | BLOCKED-ON | First review verdict | Manual interventions |
|---|---|---|---|---|---|---|---|
| 00 | factory-engineer | Attempt 1 rejected, attempt 2 accepted | 0 (no reviewer record) | 1 rerun | not recorded | n/a | not recorded |
| 01 | factory-engineer | Round 1 REJECTED; Round 2 ACCEPTED WITH OPEN RISKS | 4 | 1 (10 checkpoints) | 0 | REQUEST_CHANGES | 3 |
| 02 | build-engineer | ACCEPTED (Round 1) | 1 | 0 | 0 | APPROVE | 0 |
| 03 | dataset-, domain-, build-, factory-engineer | ACCEPTED after 3 human rounds | 4 | 4 | 1 | REQUEST_CHANGES | 4 |
| 04 | hadoop-, entity-resolution-, domain-, infra-, test-, factory-engineer | Parts A, B, and closeout ACCEPTED; amendment DEFERRED | 11 code-reviewer + 1 entity-resolution read-only | 7 | 1 | REQUEST_CHANGES (Part A, Part B, amendment); APPROVE (closeout) | 0 under "Manual changes" |
| 05 | build-engineer, entity-resolution-engineer | 2 rounds ACCEPTED WITH OPEN RISKS | 3 | 1 | not recorded | APPROVE (WO-00); REQUEST_CHANGES (WO-05) | 0 |
| 06 | spark-engineer | 2 rounds ACCEPTED WITH OPEN RISKS | 3 | 1 | not recorded | REQUEST_CHANGES (06a); APPROVE (06b) | 0 |
| 07 | storage-engineer | ACCEPTED WITH OPEN RISKS | 2 | 1 (+1 rerun after an ownership block) | not recorded | REQUEST_CHANGES | 0 |
| 08 | storage-, rag-, domain-engineer | 2 rounds ACCEPTED WITH OPEN RISKS | 2 | 0 (1 pre-review revert) | not recorded | APPROVE (both) | 0 |
| 09 | rag-engineer | ACCEPTED WITH OPEN RISKS | 2 | 1 | not recorded | REQUEST_CHANGES | 0 |
| 00-09 follow-up | factory-engineer, entity-resolution-engineer | repair-02b, repair-03c, AMD-AWSMCP r1 ACCEPTED | 6 | 4 | 1 | REQUEST_CHANGES (repair-02, AMD); APPROVE (repair-03) | 1 |
| 10 | service-, storage-engineer | ACCEPTED WITH OPEN RISKS | 3 | 1 | 0 | REQUEST_CHANGES | 0 |
| 11 | test-engineer + 5 module owners | ACCEPTED | 3 | 3 rounds (+1 adversarial rerun) | 1 | REQUEST_CHANGES | 1 |
| 12 | infra-engineer | ACCEPTED | 5 | 4 (+3 re-plans) | 2 | REQUEST_CHANGES | 2 |
| 13 | infra-, experiment-engineer | ACCEPTED WITH OPEN RISKS | 4 | 4 | 1 | REQUEST_CHANGES | 0 |
| 14 (in progress at `0c82710`) | infra-engineer (deploy-engineer after `0c82710`, not counted) | Steps 1a, 1b-0, 1b signed off; deployment recorded after `0c82710` in the uncommitted [deploy-engineer report](../../reports/deployments/2026-11-24-dev-phase-14.md) | 3 (from the gate; no review files) | 1 failed plan attempt | 0 | APPROVE (all 3 steps) | 4 |
| 15-17 | none | placeholder files are 0 bytes | not recorded | not recorded | not recorded | not recorded | not recorded |

Totals for phases 00 to 14 and the 00-09 follow-up: 7 BLOCKED-ON events (section 2.3) and 5 reports with `STATUS: FAILED`, 2 of which were intended red-test outcomes.

Phases accepted with no repair loop at all: phase 02 only. Phase 08 had no review loop, but one domain-engineer change was reverted before review. The three phase-14 steps passed first review, and the phase is not finished.

### 2.2 First-pass acceptance by agent

Implementation work units only. P11 repair tasks are shown separately because they are repairs of defects found by test-engineer.

| Agent | Units | First-pass | Units (yes / no) | Source rows |
|---|---|---|---|---|
| factory-engineer | 7 | 0 | P00 no, P01 no, P03 no, P04 amendment no, follow-up repair-02 no, repair-03 no, AMD-AWSMCP no | CSV phase 00, 01, 03, 04, 00-09 follow-up |
| build-engineer | 3 | 3 | P02 yes, P03 line endings yes, P05 WO-00 yes | CSV 02, 03, 05 |
| domain-engineer | 2 | 0 | P03 no, P08 WO-08x no (pre-review revert) | CSV 03, 08 |
| dataset-engineer | 1 | 0 | P03 no | CSV 03 |
| hadoop-engineer | 3 | 0 | P04 Part A no (first report and first repair both FAILED), Part B no, closeout no | CSV 04 |
| entity-resolution-engineer | 1 (+1 not recorded) | 0 | P05 no; follow-up gold worksheet: review not recorded | CSV 05, 00-09 follow-up |
| spark-engineer | 2 | 1 | 06a no, 06b yes | CSV 06 |
| storage-engineer | 3 | 2 | P07 no, P08 WO-08 yes, P10 F6 yes | CSV 07, 08, 10 |
| rag-engineer | 2 | 1 | P08 WO-08 yes, P09 no | CSV 08, 09 |
| service-engineer | 1 | 0 | P10 no | CSV 10 |
| infra-engineer | 6 | 4 | P04 closeout yes, P12 no, P13 no, P14 1a yes, 1b-0 yes, 1b yes | CSV 04, 12, 13, 14 |
| test-engineer | 2 | 1 | P04 closeout yes, P11 no | CSV 04, 11 |
| experiment-engineer | 1 | n/a | P13 dry runs: BLOCKED-ON a human decision, no defect | CSV 13 |
| deploy-engineer, dependency-updater, documentation-engineer, incident-responder | 0 | not recorded | No archived run in phases 00 to 14 at `0c82710`. After that commit, deploy-engineer wrote the uncommitted Phase 14 deployment verification report (not counted here) | none found at `0c82710`; [reports/deployments/2026-11-24-dev-phase-14.md](../../reports/deployments/2026-11-24-dev-phase-14.md) |

Total: 12 first-pass of 33 agent-unit pairs. WO-08 is counted once for storage-engineer and once for rag-engineer. P11 repair tasks: service-engineer's repair passed the next review with no finding against it. The repairs by entity-resolution-, spark-, rag-, hadoop-, and test-engineer each drew at least one later finding.

### 2.3 BLOCKED-ON events (7)

| Phase | Agent | Blocked on | Source |
|---|---|---|---|
| 03 | factory-engineer | its own evidence run (time box reached; it ran about 82 minutes against a 30-minute box) | [phase-03/agent-report.md](../agent-runs/phase-03/agent-report.md) line 438 |
| 04 | domain-engineer | hadoop-engineer (scalafmt failure on an in-progress file, then an sbt server lock from a parallel build) | [domain-engineer-closeout-report.md](../agent-runs/phase-04/domain-engineer-closeout-report.md) |
| 00-09 follow-up | factory-engineer | repair-03b: a new probe found a fail-open defect in repair-03 | [phase-00-09-review.md](../agent-runs/phase-00-09-review.md) line 167 |
| 11 | entity-resolution-engineer | the gold-pilot rerun only (the guard blocks `runMain`) | [phase-11/repair-01.md](../agent-runs/phase-11/repair-01.md) line 40 |
| 12 | infra-engineer | human (wrong bucket value in the human's local backend file) | [replan-s3-01-infra-engineer.md](../agent-runs/phase-12/replan-s3-01-infra-engineer.md) |
| 12 | infra-engineer | human (the read-only permission set lacked S3 Vectors metadata reads) | [repair-04-infra-engineer.md](../agent-runs/phase-12/repair-04-infra-engineer.md) |
| 13 | experiment-engineer | human decision on dry run 1 (no defect) | [dryrun-experiment-engineer.md](../agent-runs/phase-13/dryrun-experiment-engineer.md) |

`STATUS: FAILED` reports (5): phase-04 `hadoop-engineer-report.md` and `hadoop-engineer-repair1-report.md`; phase-04 `test-engineer-closeout-report.md` (intended); phase-11 `agent-report.md` (intended, 9 red tests); phase-14 `plan-infra-engineer-attempt-1-failed.md`.

### 2.4 Reviewer findings by severity

Archived code-reviewer review files (40 rounds: 21 REQUEST_CHANGES, 19 APPROVE). Totals of the review rows in the CSV:

| CRITICAL/BLOCKER | MAJOR/HIGH | MEDIUM | MINOR | LOW | NIT |
|---|---|---|---|---|---|
| 6 | 29 | 5 | 75 | 4 | 10 |

The same counts by phase:

| Phase | CRITICAL | MAJOR | MEDIUM | MINOR | LOW | NIT |
|---|---|---|---|---|---|---|
| 01 | 5 | 8 | 0 | 6 | 0 | 0 |
| 03 | 1 | 5 | 0 | 7 | 0 | 0 |
| 04 (code-reviewer) | 0 | 8 | 0 | 23 | 0 | 0 |
| 00-09 follow-up | 0 | 2 | 0 | 8 | 0 | 0 |
| 10 | 0 | 2 | 0 | 8 | 0 | 3 |
| 11 | 0 | 0 | 0 | 11 | 0 | 7 |
| 12 | 0 | 0 | 5 | 1 | 4 | 0 |
| 13 | 0 | 4 | 0 | 11 | 0 | 0 |

Counts that exist only as gate or report text, not as review files (not included above):

- Phase 02: APPROVE with 3 minor problems ([phase-02/agent-report.md](../agent-runs/phase-02/agent-report.md) line 38).
- Fast track 05 to 09: 8 MAJOR across 9 review rounds (05: 2, 06a: 2, 07: 3, 09: 1). The reviewer's MINOR counts were not recorded. The gates log 80 MINOR open risks and 1 MAJOR open risk (R-06a-1) at acceptance (05: 9, 06: 25 MINOR + 1 MAJOR, 07: 14, 08: 19, 09: 13; counted from lines that begin `- Open: R-`). These are open-risk entries, not necessarily reviewer findings.
- Phase 04 entity-resolution-engineer read-only review: 3 HIGH, 5 MEDIUM, 1 LOW-MEDIUM, 2 LOW.
- Phase 11 review-01 also confirmed 9 red tests as production defects.
- Phase 14: three APPROVE verdicts, one minor report correction (human gate).

Findings that an agent report overstated its own evidence (CLAUDE.md law 7), counted from the review rows: 5. They are phase-04 Part B round 2, closeout round 1 (infra report), closeout round 2 (test-engineer report), phase-11 review-02 F6, and the phase-14 Step 1b report correction (gate).

### 2.5 Ownership events

- Hook blocks by guard-ownership.sh: 1. In phase 07, the first WO-07 attempt ran in a `.claude/worktrees` git worktree. guard-ownership.sh resolved paths against the main tree and blocked every write, so nothing was written ([phase-07/human-gate.md](../agent-runs/phase-07/human-gate.md) line 6, open risk R-07-14). This was an agent working on its own module from an unowned path, not an attempt to write another owner's files.
- Reviewer findings about ownership, boundaries, or the constitutional process: 4.
  - Phase 04 entity-resolution review F11 MEDIUM: ambiguous ownership of blocking keys, resolved by a human decision (option b).
  - Phase 04 amendment round 1 F2 MAJOR: an unratified constitutional change was already live in the working tree.
  - Phase 10 review-01 F6 MINOR: services imported the AWS SDK, outside the CLAUDE.md module boundary. It was resolved with storage-engineer factories.
  - Phase 06a open risk R-06a-1 MAJOR: the researcher-ID anchor rule is decided in Spark, but entity resolution should own it.
- Reviews that recorded ownership as clean: 9 (phase-04 Part A rounds 2 and 5, closeout rounds 1 and 2; phase-11 reviews 1 to 3; phase-12 review-01; phase-13 review-01).
- Negative finding (fence gap, not an ownership-map violation): in phase 04 hadoop-engineer ran its main class through `java @argfile`, and no guard blocked it, although `sbt run` is blocked. The reviewer routed it to factory-engineer ([code-reviewer-report-round4.md](../agent-runs/phase-04/code-reviewer-report-round4.md) item 4). The local-run amendment that followed was deferred in phase 04, and docs/agents.md at `0c82710` has no local-run section (sections 7 and 7b only).

### 2.6 Tests, probes, hooks, and script gates that prevented an unsafe action or exposed a defect

| Phase | Mechanism | What it recorded | Source (under `docs/agent-runs/`) |
|---|---|---|---|
| 01 | Designed probes 1-4 and 6 | Wrong-owner edit refused, reviewer had no write tools, `terraform destroy` text blocked, `.env` read denied. Probe 3 is also a false positive (plain `echo` blocked) | phase-01/human-gate.md |
| 03 | Mutation runs | Round 3: 14 of 14 detected. Round 4 narrow: 2 of 2 detected | phase-03/agent-report.md |
| 03 | code-reviewer | Round 3 F1 CRITICAL: an auto-allowed dataset script could overwrite a hook file in the live configuration. The human removed the allow rules | phase-03/agent-report.md; phase-03/human-gate.md |
| 04 | test-engineer adversarial suite | 35 tests found 3 real defects, all fixed by owners | phase-04/human-gate.md |
| 07 | guard-ownership.sh | Worktree writes blocked (fail closed) | phase-07/human-gate.md |
| 08 | not recorded | A core change that broke storage (`scala.MatchError`) was caught and reverted before review | phase-08/human-gate.md |
| 09 | Feature test | PairConfirmationSuite: 5 of 6 tests failed against the old code | phase-09/human-gate.md |
| 00-09 follow-up | factory-engineer boundary probe | Found a fail-open defect in repair-03 after code-reviewer approved it, so repair-03 was not ratified | phase-00-09-review.md |
| 10, 11 | guard-dangerous.sh | Blocked agents from launching main classes. Consequences: no local services run (P10), and the gold pilot was run by the human (P11) | phase-10/human-gate.md; phase-11/human-gate.md |
| 11 | test-engineer | 9 red tests confirmed as production defects; rerun-01 added 6 more red tests | phase-11/review-01.md; phase-11/rerun-01.md |
| 13 | guard-dangerous.sh | Refused an out-of-tree experiment manifest (dry run 1); infra-engineer cannot run deploy-side scripts | phase-13/dryrun-experiment-engineer.md; phase-13/part-b-infra-engineer.md |
| 14 | plan.sh, package.sh | plan.sh stopped at `terraform fmt -check` with no plan written. package.sh refuses admin-named push profiles | phase-14/plan-infra-engineer-attempt-1-failed.md; phase-14/human-gate.md |

Benign commands blocked by the guard (false positives, nothing unsafe intended): 17 recorded in phase-04 reports and 4 in phase-12 reports (CSV phase rows 04 and 12). The same rule (R3-DEADLINE) blocked 4 of this evaluation's own read-only commands. That is an observation from this session, not part of the archived record.

### 2.7 Manual interventions (as listed in the gates)

| Phase | Items |
|---|---|
| 01 | `git update-index --chmod=+x` on 6 hooks (the agent reported 100755; git had 100644); `git config core.autocrlf input`; a `.env` created and deleted for probe 4 |
| 03 | `git apply` of the Round 2 amendment; removed the sample-extract allow rules from `.claude/settings.json`; `git apply` of the final patch; `git apply` of the RR-11 patch |
| 00-09 follow-up | IAM permission set and CLI profile repair (the read-only profile had been signing in with the admin role, and the inline Deny was missing) |
| 11 | Gold pilot run by the human (`runMain` is guard-blocked for agents) |
| 12 | Corrected a wrong bucket value in the human's local backend file; added S3 Vectors metadata reads to the read-only permission set |
| 14 | Human applied plans outside Claude; wrote the API-key digest secret and pushed the bootstrap image; ran `terraform fmt` on the local tfvars; runs deploy.sh and smoke-test.sh personally |
| 02, 05-10, 13 | none |
| 00, 04 | 00: not recorded. 04: all three parts say "N/A", but the decision notes record deleting leftover temp folders and force-adding gitignored logs |

### 2.8 Agent artifacts that needed substantial human rewrite

0 recorded. No gate or report says the human rewrote an agent-generated artifact. Smaller human edits to agent artifacts were recorded: the phase-03 settings.json allow-rule removal and the phase-01 file-mode change. Rejected work (phase 00 attempt 1, phase 01 Round 1) was rerun by the agent, not rewritten by the human.

## 3. Interpretation (not measured; the author's reading of section 2)

- The constitutional work (factory-engineer) never passed first time: 0 of 7 units. It also produced all 6 recorded CRITICAL findings. This matches the scope: guard rules written in shell, tested against adversarial bypasses. It suggests that guard changes need the probe and mutation evidence that phases 03 and 00-09 eventually required.
- Build work passed first time in all 3 units. Feature modules were mixed: spark 1 of 2, storage 2 of 3, rag 1 of 2, but hadoop 0 of 3, entity resolution 0 of 1, and services 0 of 1. Infrastructure passed first time in the small, bounded phase-14 steps but not in the large phase-12 and phase-13 change sets.
- Review rounds per phase went down after phase 04 (11 rounds) to 3 to 5. Part of that drop is a process change rather than a quality change: the fast track (phases 05 to 09) allowed one review and one repair, and turned the rest into open risks (80 MINOR open risks logged).
- Independent checks caught things that code-reviewer missed: test-engineer red tests in phases 04 and 11, and factory-engineer's own probe after the repair-03 APPROVE. A single reviewer verdict was not enough on its own for safety-critical guard code.
- Most recorded guard blocks were false positives on harmless commands, not prevented attacks. The guard's cost is real friction (blocked reads, timeouts). Its proven value in these records is fail-closed behavior plus the designed probes, not organic catches of agent misbehavior.
- The 9 reviews that checked ownership found no violation, and only one hook block of an ownership kind is recorded. The ownership fence appears to have held, but the records cannot show attempts that were never written down.

## 4. Threats to validity

1. **Missing records.** Phases 05 to 09 have no archived agent or review reports. Their counts come from the short-form human gates, so BLOCKED-ON events and MINOR reviewer findings there are "not recorded". Phase 00 has no reviewer record. Phase 14 has no archived review files, and its `agent-report.md` and `commit.txt` are empty. `phase-09/commit.txt` is empty. Phases 15 to 17 are empty placeholders.
2. **Inconsistent formats.** Severity scales differ: CRITICAL/MAJOR/MINOR (most phases), BLOCKER/MAJOR/MINOR/NIT (phases 10 and 11), BLOCKER/HIGH/MEDIUM/LOW (phase 12), and HIGH/MEDIUM/LOW (the entity-resolution review). They are kept in separate columns and never mapped onto each other. Review findings were counted from severity-tagged headings, tables, or list items, and a finding written without a severity tag would be missed.
3. **Self-reported and orchestrator-assembled counts.** Most review files say "assembled by the orchestrator from the agent's final report". Reviewers often did not run `sbt check`, because it writes the test marker, so many test counts are the implementer's claims. In phase 03, one agent report never reached the orchestrator and was reconstructed from evidence files. Human gates are human-written. Phase 13 still contains unresolved bracketed choices, the phase-12 sign-off was written after the apply, and the phase-13 work order was saved after the runs started.
4. **Definitions are this evaluation's own.** "First-pass", "repair iteration", and "prevention event" are defined in section 1. Other reasonable definitions give different numbers. For example, counting the phase-14 Step 1b failed plan attempt as a non-first-pass would make infra-engineer 3 of 6.
5. **Unrecorded events.** Guard blocks and refusals are counted only when an agent or gate wrote them down. Benign blocks are certainly under-counted. The 4 blocks of this session show they happen often.
6. **Records not read in full.** Read only through searches for status, verdict, severity, ownership, and guard lines: `phase-01/repair-01.md` (over 2,000 lines), `phase-01/old-human-gate.md` (superseded), `phase-03/next-session.md`, `phase-04/human-decisions-*.md`, `phase-04/resume-point.md`, the phase-12 and phase-13 part, plan, and repair reports, and `phase-00-09-minor-fixes.md`. An event described in those files without the searched words would be missed.
7. **Counting by one reader, not replayed.** All counts were made by one agent reading the files in a single time-boxed pass, with no second counter. Spot checks recomputed the CSV review-row totals with a script (`csv.reader`): 90 rows, 21 columns each, every row with a source. The evidence probes and patches were not re-run.
8. **Git history.** The 52 commits use two author identities: 50 under the configured git user, plus the initial commit and one README fix under a second identity. No commit has an agent author identity. Co-author trailers were not counted. The history shows record and redo commits (for example `2f0d1c9` "(REDO)", and `24a5f20`/`68d287f` sharing one message), so commit counts are not a measure of work units and were not used as one.
