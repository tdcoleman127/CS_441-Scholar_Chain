# Phase 14 agent reports (index)

Assembled by the orchestrator. Phase 14: apply and deploy through human approval (fixture mode). Each file below is an orchestrator-assembled copy of a subagent's final report, in order.

| Step | Agent | Report | Outcome |
|---|---|---|---|
| 1a | infra-engineer | [part-a-infra-engineer.md](part-a-infra-engineer.md) | runtime_mode fixture variable; code-reviewer APPROVE; committed in ef95a6b |
| 1b-0 | infra-engineer | [plan-1b0-infra-engineer.md](plan-1b0-infra-engineer.md) | deployment role plan, 2 to add; code-reviewer APPROVE; human applied |
| 1b, attempt 1 | infra-engineer | [plan-infra-engineer-attempt-1-failed.md](plan-infra-engineer-attempt-1-failed.md) | FAILED: plan.sh fmt check on the local dev.tfvars (negative result kept) |
| 1b, attempt 2 | infra-engineer | [plan-infra-engineer.md](plan-infra-engineer.md) | plan sha256 8ce269bf...3668, 17/1/0; code-reviewer APPROVE with one minor correction (orchestrator note in the file) |
| Deploy and smoke | human (decision in human-gate.md) | [../../../reports/deployments/2026-11-24-dev-phase-14.md](../../../reports/deployments/2026-11-24-dev-phase-14.md) | deploy.sh RESULT status=DEPLOYED (0c82710, sha256:12049aab...94a2); smoke-test.sh 9/9 PASS |
| Verification | deploy-engineer | [deploy-engineer.md](deploy-engineer.md) | read-only verification COMPLETE; all gate items PASS except archive (completed by the commit) |
| Review round 1 | code-reviewer | [review-01.md](review-01.md) | REQUEST_CHANGES: 1 MAJOR, 6 MINOR (deployment report, Phase 16 offline results, Phase 17 drafts) |
| Repair 1 | experiment-engineer, deploy-engineer, documentation-engineer | summarized in review-02.md | findings 1 to 7 repaired |
| Review round 2 | code-reviewer | [review-02.md](review-02.md) | REQUEST_CHANGES: 2 MINOR (all 7 round-1 findings FIXED) |
| Repair 2 | documentation-engineer, experiment-engineer | summarized in review-03.md | F1 and F2 repaired |
| Review round 3 | code-reviewer | [review-03.md](review-03.md) | APPROVE |

Also in this change set, produced in parallel while the human deployed (reviewed in rounds 1 to 3, belonging to later phases):
- Phase 16 offline evaluation (experiment-engineer): experiments/phase-16/offline/ (ER 63, Spark 40, contract 38 tests passed; gold-set R04 needs a human runMain).
- Phase 16 factory evaluation (documentation-engineer): docs/evaluation/factory.md, factory-counts.csv.
- Phase 17 early drafts (documentation-engineer): README.md, docs/architecture.md, threat-model.md, runbook.md, api-reference.md, data-lineage.md.

Known limitation recorded in this phase (verified in code by code-reviewer, review-01): AWS runtime mode composes UnavailableGraphStore (no live Neptune adapter), no semantic retriever, and the template synthesizer; no production writer exists for the JSONL graph exports the runtime reads. Fixture mode is the deployed configuration.
