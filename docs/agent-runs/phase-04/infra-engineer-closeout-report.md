# infra-engineer: Phase 04 closeout, item 4 (bounded EMR smoke-run script)

Assembled by the orchestrator, 2026-11-01, from infra-engineer's final report. The orchestrator reran `bash -n scripts/run-bounded-pipeline.sh` and it passed.

**STATUS: COMPLETE.** `scripts/run-bounded-pipeline.sh` is written and passes `bash -n`. shellcheck was not run (not installed). The script was not executed.

- **Files:** created `scripts/run-bounded-pipeline.sh`; nothing modified. No Terraform written or planned (infra/terraform is empty; `scripts/plan.sh` does not exist). No IAM changes, no resources created. The aws-api MCP server failed to connect, so no live AWS was inspected.
- **Commands:** read-only inspection; `bash -n` OK; `command -v shellcheck` not installed; `file` reports LF line endings. Two commands were blocked by guard-dangerous (a multi-line sed; a `$'\r'` grep) and were redone.

## Gates encoded in the script

- **Usage:** exactly one argument; only `dev` accepted (exit 2). Strict mode, ERR and cleanup traps, no prompts. Markers: `[run-bounded-pipeline] STEP <n> BEGIN|OK`, `RESULT status=...`, and `FAIL:` on stderr.
- **Configuration (fails closed):** required variables are format-checked. The sample, raw, and output buckets must all differ, so the full corpus is never an input. [Orchestrator note: code-reviewer closeout MINOR 3 found this overstated. The raw-bucket comparison runs only when SCHOLARCHAIN_RAW_BUCKET is set (run-bounded-pipeline.sh:187-193). Inputs are still fixed sample keys.] The snapshot ID uses the `Pipeline.validateSnapshot` rule; the run ID must be `run:v1:<64 hex>`. Step timeout is capped at 3600 s (default 1800).
- **Clean tree and revision:** refuses a dirty tree, and requires the HEAD SHA in the jar URI.
- **Cluster checks (read-only, 90 s timeouts):** caller identity; state WAITING or RUNNING; tag `scholarchain:environment` equals the environment argument; no active steps.
- **Inputs:** `head-object` on the jar and all seven sample inputs. Refuses an existing output prefix.
- **Bounds:** MAX_STEPS=2; fixed reducers 2, oversize 50, export partitions 2, block cap 500. `ActionOnFailure=CONTINUE`. The only mutating call is `emr add-steps`; no cluster changes and no auto-cancel.
- **Order:** steps run one at a time. Failure exits 4; timeout exits 5 and prints the manual cancel command.
- **Output:** counters read from step logs (best effort); prints `OUTPUT` paths `s3://<curated>/bounded-smoke/hadoop/env=dev/run=<run-v1-hex>/snapshot=<snapshot>/<job>`. An optional run record can be written under `reports/deployments/` only.
- **Steps:**
  - Step 1: `DblpPipelineMain <dblp glob> <dblp.dtd> <outputBase> <snapshotId> <runId> 2 50`.
  - Step 2: `SourcePipelineMain <outputBase> <snapshotId> <runId> 2 2 500 <openalex> <crossref> <opencitations> <arxiv>`, in a block marked `>>> SourcePipelineMain argument block`.

## Environment variables (names only)

- Required: SCHOLARCHAIN_AWS_REGION, SCHOLARCHAIN_EMR_CLUSTER_ID, SCHOLARCHAIN_SAMPLE_BUCKET, SCHOLARCHAIN_CURATED_BUCKET, SCHOLARCHAIN_HADOOP_JAR_URI.
- Optional: SCHOLARCHAIN_RAW_BUCKET, SCHOLARCHAIN_PIPELINE_SNAPSHOT_ID, SCHOLARCHAIN_RUN_ID, SCHOLARCHAIN_PIPELINE_STEP_TIMEOUT_SECONDS, SCHOLARCHAIN_PIPELINE_RECORD_FILE, AWS_PROFILE.

## Assumptions

- Resource names come from environment variables, not `terraform output`: Terraform state is denied to agents, and no Terraform exists yet.
- The default snapshot is `sample-manifest-v1`.
- Without SCHOLARCHAIN_RUN_ID, the run ID is a sha256 over the script, environment, Git SHA, snapshot, UTC time, and PID.
- The cluster carries the environment tag and a log URI.
- Samples are mirrored under `samples/<source>/manifest_version=1/` with their original names.
- The DTD is read through the Hadoop file system, so an S3 URI works.

## Risks

1. SourcePipelineMain was new and unreviewed when the script was written. The argument block matches its scaladoc contract `<outputBase> <snapshotId> <runId> <reducers> <exportPartitions> <maxBlock> <sourceInput>...`.
2. The generated run ID is not the `StableIds.runId` RunManifest formula (domain-engineer and storage-engineer own the manifest); pass SCHOLARCHAIN_RUN_ID for a formula-correct ID.
3. `sample_bucket` is not in ConfigLoader.EnvBindings (owner domain-engineer), so the script reads an environment variable directly.
4. Untested against AWS: the hand-built add-steps JSON (no jq), `cygpath` handling, and the log layout.
5. Counters can be unavailable if EMR log upload is slow.
6. A step that times out keeps running; the script only prints the cancel command.
7. The jar-path SHA convention is not yet defined by any packaging script.

## Manual action, blocked items, and next gate

- **Manual action:** the human answers the ask prompt when deploy-engineer runs the script, commits first (the script needs a clean tree), and provides the EMR cluster, buckets, and jar through Terraform that is not yet written.
- **Blocked-on:** none for the script. A live run needs applied Terraform and an uploaded jar.
- **Next gate:**
  1. code-reviewer (closeout item 5).
  2. A later phase authors the Terraform: provider and backend, KMS, buckets, EMR roles, a bounded tagged EMR cluster, budgets, outputs, `scripts/plan.sh`, and a package-and-upload script.
  3. Install shellcheck and rerun it.
