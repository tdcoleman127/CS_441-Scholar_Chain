---
description: Run an approved ScholarChain experiment (elasticity, query load, retrieval modes, failure recovery, evaluation) from a committed manifest through experiment-engineer.
argument-hint: <experiments/.../manifest.yaml> <environment>
---

# /experiment $ARGUMENTS

Responsible agent: `experiment-engineer`

## Gates

1. Committed manifest: the manifest is under `experiments/`, committed at HEAD, and states commit SHA, dataset snapshot, configuration, resource limits, concurrency levels, duration, Region, and metric sources.
2. Human approval: the phase human gate records approval of the manifest and its cost bound before the run.
3. Approved environment: listed in docs/agents.md section 7 (today only `dev`). Elasticity changes come only from configured scaling policies, never from manual scale commands.
4. No tuning during a run: production algorithms, thresholds, and configuration stay unchanged for the run. A change means a new manifest and a new run.
5. Raw results are immutable after collection. Failed runs are kept and reported.
6. Human confirmation: each `scripts/run-experiment.sh` execution triggers a permission prompt that the human answers.

## Delegation

Use the experiment-engineer agent to execute `scripts/run-experiment.sh <manifest>` against environment `<environment>`. Collect raw metrics with synchronized timestamps into the run's raw-results directory, then derive tables and plots only through committed scripts. Report the run IDs, start and end times, observed scale-out and scale-in events, p50, p95, and p99 latency, throughput, error rates, estimated cost, and limitations.

## After the agent returns

documentation-engineer writes the results only after the raw outputs are frozen. A suspected production defect goes to the owning module agent, never to a mid-run fix.
