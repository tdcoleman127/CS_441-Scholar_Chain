# ScholarChain phase evidence

This directory is the audit trail of the agentic build. It is human-owned evidence. The orchestrator may assemble agent final reports here. No agent rewrites a human decision, and `human-gate.md` files are written only by the human architect. factory-engineer created this README once, under the phase-01 grant recorded in docs/agents.md section 3. From now on it is human-owned.

## Layout

One directory per phase: `docs/agent-runs/phase-NN/`, with two-digit `NN` matching the phase number in the course specification.

| File | Required | Written by | Content |
|---|---|---|---|
| `prompt.md` | always | human, saved before the run | The exact work order given to the orchestrator, verbatim. Never edited after the run starts. A changed work order means a new attempt. |
| `report.md` | always | orchestrator (verbatim copy) | The owning agent's final report in the docs/agents.md section 11 contract, unedited. For several attempts, use `## Attempt N` sections or `report-attempt-N.md`. |
| `validation.md` | when commands ran | orchestrator (verbatim copy) | Deterministic command output the gate relies on (`sbt check`, `validate-agent-files.sh`, `terraform validate`, plan summaries, probe transcripts), each with its exact command line. |
| `review.md` | when the phase requires review | orchestrator (verbatim copy) | code-reviewer verdict and findings. |
| `tests.md` | when the phase requires test-engineer | orchestrator (verbatim copy) | test-engineer counts, failing tests, and owners. |
| `repairs.md` | when findings were routed | orchestrator | For each finding: ID, owner, the repair work order, the rerun evidence, and the reviewer's re-check. |
| `human-gate.md` | always | human only | One section per attempt: what was reviewed, the decision (Accepted or Rejected), the reasons, any ratification statement, manual interventions, and approvals with their exact values (plan checksum, environment, cost bound). |
| `versions.md` | phase 00 and on any toolchain change | human or orchestrator | Claude Code version, model configuration when known, JDK, sbt, Scala, Terraform, AWS CLI, Python, uv, Docker. |

## Commit protocol

1. Save `prompt.md` before starting a fresh session.
2. Run the phase. Save the reports and validation output. Write `human-gate.md`.
3. Commit the accepted phase (normally one commit).
4. Record that commit's hash in `human-gate.md` in a second commit, then restart Claude Code before the next phase.

## What is never stored here

- Hidden model reasoning. Only the work order, the agent's final report, deterministic command output, review and test findings, repair records, and the human decision are kept.
- Secrets, credentials, tokens, account-specific secret values, or raw `.env` contents. Redact them before saving any output.
- Edited or summarized agent reports presented as originals. Annotations go in `human-gate.md`.
