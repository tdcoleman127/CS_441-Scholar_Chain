---
description: Run the bounded ScholarChain Hadoop and Spark data pipeline in an approved environment through approved scripts, operated by deploy-engineer.
argument-hint: <environment> <dataset-snapshot-id>
---

# /run-pipeline $ARGUMENTS

Responsible agent: `deploy-engineer`

## Gates

1. Approved environment: listed in docs/agents.md section 7 (today only `dev`).
2. Dataset snapshot: the snapshot ID exists in `datasets/dataset-manifest.yaml` with checksums and a subset rule. Full-corpus runs need an explicit human approval with a cost estimate.
3. Bounded capacity: EMR capacity comes only from the committed configuration and Terraform defaults. No scale-out flags or ad hoc EMR CLI calls; the guard hook blocks them.
4. Deployed code: the job artifacts were packaged from a clean commit with `sbt check` passing, and their checksums are recorded.
5. Human confirmation: each `scripts/run-bounded-pipeline.sh` execution triggers a permission prompt that the human answers.

## Delegation

Use the deploy-engineer agent to execute `scripts/run-bounded-pipeline.sh <environment>` for dataset snapshot `<snapshot-id>`. Record the run IDs, commit SHA, dataset snapshot, configuration, EMR cluster and step IDs, Hadoop counters, Spark stage summaries, output S3 prefixes, start and end times, and an estimated cost. Do not change algorithms, configuration, or capacity during the run. Write the run report under `reports/deployments/`.

## After the agent returns

Failed runs are reported, never re-run silently. A failure that looks like a code defect goes to the owning module agent (hadoop-engineer, entity-resolution-engineer, spark-engineer, or storage-engineer). An operational failure goes to `/incident`.
