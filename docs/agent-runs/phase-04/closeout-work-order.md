# Phase 04 closeout: work order

Assembled by the orchestrator on 2026-11-01 from the human's instruction ("make the final steps and finish Phase 4 within 20 min") and the closeout prompt the orchestrator drafted for the human in session. The session is time-boxed to 20 minutes, so the agents run in parallel.

| # | Agent | Task | Stops at |
|---|---|---|---|
| 1 | factory-engineer | Main-class run rule amendment, round 2, from `factory-engineer-amendment-report.md` and `code-reviewer-report-amendment-round1.md` (F1 to F7). Staged as a patch in the scratchpad; live constitutional files are not edited. | Human ratification and restart |
| 2 | test-engineer | Adversarial tests for parts A and B under `tests/**`, with exact pass and fail counts. Failing tests stay red. | Report |
| 3 | hadoop-engineer | Add `SourcePipelineMain` for jobs 2, 3, 4+5 and 7, with argument-validation tests. Close round-2 MINOR 1 (Job 7 partitioner wiring test). No local main-class run until the amendment is ratified. | `sbt check` |
| 4 | infra-engineer | `scripts/run-bounded-pipeline.sh`: bounded EMR smoke run of jobs 1 to 7 over the sample mirror. Follows the section 7 interface. Gate: `bash -n`, plus shellcheck if available. No execution and no terraform apply. | Report |
| 5 | code-reviewer | Closeout review of items 2 to 4, and a review of the staged amendment patch from item 1. | Verdicts |

After this session the human:
- ratifies or rejects the amendment;
- restarts Claude Code;
- has hadoop-engineer run the local bounded main-class run under the ratified rule (if wanted before the gate);
- writes the part B gate and commits.
