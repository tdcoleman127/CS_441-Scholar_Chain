# Phase 14 review round 1: code-reviewer (deployment evidence plus offline drafts)

Assembled by the orchestrator from code-reviewer's final report, 2026-11-24. Scope: uncommitted change set at HEAD 0c82710 (Phase 14 deployment report and logs, orchestrator-assembled deploy-engineer.md, human-gate.md approval line, Phase 16 offline results, Phase 17 early drafts, factory evaluation). Local user names are redacted.

VERDICT: REQUEST_CHANGES (0 CRITICAL, 1 MAJOR, 6 MINOR)

| # | Severity | Where | Finding | Owner |
|---|---|---|---|---|
| 1 | MAJOR | experiments/phase-16/offline/manifest.yaml, results.md | raw/01-er-test.log and raw/03-contract-graph-er.log are ignored by .gitignore:14 (`*.log`); manifest and results cite them without saying so, so a plain commit leaves 101 of 141 passes without raw evidence | experiment-engineer (human force-adds) |
| 2 | MINOR | reports/deployments/2026-11-24-dev-phase-14.md:12-14 | human-gate line numbers come from the uncommitted working copy, not HEAD (APPROVED line is 96 at HEAD) | deploy-engineer |
| 3 | MINOR | reports/deployments/2026-11-24-dev-phase-14/deploy-dev.log:17-22 | local Windows user path in a log the report recommends force-adding | deploy-engineer |
| 4 | MINOR | README.md, docs/runbook.md, docs/evaluation/factory.md, factory-counts.csv | say deployment and experiment evidence is absent while the same change set adds it (stale, not invented) | documentation-engineer |
| 5 | MINOR | docs/architecture.md:93-103, 121-123 | diagram shows ER on EMR and automated storage, ingestion, and Neptune loads; run-bounded-pipeline.sh runs only hadoop, spark-part-a, spark-part-b, ER off-cluster, ingestion hard-coded off, storage has no main() | documentation-engineer |
| 6 | MINOR | README.md:450, 476 | replay command uses `$(cat docs/agent-runs/phase-10/commit.txt)`; the file has a trailing space, so the revision is invalid | documentation-engineer |
| 7 | MINOR | README.md:306, 308, 429, 538-546 | sbt check (760 tests) and validator results cited with raw logs only in a session scratchpad | documentation-engineer |

Verified with no finding:
- Deployment report against the logs: commit 0c827107..., plan sha256 8ce269bf..., digest sha256:12049aab..., api:2 and worker:2 (previous :1), smoke 9/9 PASS with ready=1, 4 account redactions, no module or build change since e269427.
- deploy-engineer.md agrees with the report.
- human-gate.md: the uncommitted diff removes only the 7-line draft; the APPROVED line is intact; no REVOKED token.
- Secrets and personal data: no account ID, account ARN, AWS key, token, or API-key value in any new file; the user path appears only in deploy-dev.log (finding 3).
- Phase 16 counts: R01 63 and R03 38 present in the raw logs; gold figures match docs/agent-runs/phase-11/review-03.md:67-77.
- factory-counts.csv: four review rows spot-checked against sources; severity totals 6/29/5/75/4/10 and 40 rounds recomputed; 52 commits.
- Runtime claims, verified in code: (a) AWS mode composes UnavailableGraphStore (Main.scala:23-27, 72), so /readyz is not ready; (b) AwsComposition passes neither semantic nor synthesizer (defaults None and TemplateSynthesizer); (c) GraphExports reads part-00000.jsonl and the only writer in src/main is LocalFixture (no production producer); (d) Terraform has two ECS services (api, worker) plus three task-definition families.

Not verifiable by the reviewer (fence or no AWS access): manifest sha256 values and the R02 count (sha256sum and gunzip blocked); AWS-sourced report claims (target health, alarms, logs, ECR).

Risks noted: the full account ID is already committed in docs/agent-runs/phase-00-09-review.md:346 (human decision); the Phase 16 run has no recorded human approval.

Routing: finding 1 to experiment-engineer, 2-3 to deploy-engineer, 4-7 to documentation-engineer, then a fresh code-reviewer pass.
