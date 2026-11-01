# code-reviewer: Phase 04 closeout review, items 3 and 4

Assembled by the orchestrator, 2026-11-01, from code-reviewer's final report. Out of scope: tests/** (test-engineer still in progress) and the staged amendment.

**VERDICT: APPROVE** (3 MINOR, none blocking; no escalation).

## Evidence

- `git diff -- modules/hadoop`: only SourcePipelineSuite.scala changed (+19, lines 233-251). SourceJobs.scala has no diff.
- The 7 sample files the script expects exist.
- `bash -n` exit 0.
- sbt was not rerun (the tree is red at scalafmtCheck on test-engineer's in-progress files). `run-evidence/closeout-hadoop-check.log` matches the report: core 143/143, SourceDriverArgsSuite 9/9, hadoop 59/59, `[success]` 3:20:22 PM.
- No log of the negative control exists; the reviewer checked it by reasoning.

## Verified

- **Main class:** matches the tested wiring (same inputs as SourcePipelineSuite.runAll). The Job 1 and Job 6 `_SUCCESS` checks work, reruns go through Pipeline.guard, and the exit codes match the documentation.
- **Law 2:** SourceDriverArgsSuite cannot compile without the class. The MINOR 1 assertions really detect missing ExportPartitioner wiring, deterministically (String.hashCode).
- **Script:**
  - Section 7 interface: dev-only first argument, strict mode, no prompts, markers.
  - Bounds: MAX_STEPS=2, 3600 s cap, 90 s per AWS call, sample prefixes only.
  - The only mutation is `emr add-steps`; no terraform, secrets, cancel, or resize.
  - JSON inputs are regex-checked; AWS arguments are quoted.
  - Argument vectors match both main-class contracts. S3 file names start with the source wire name, so `sourceFor` resolves.
- **Ownership:** each change is within its owner's paths.

## Findings

1. **MINOR: the outputBase rejection branch is untested.** SourcePipelineMain.scala:81-83. Failure input: `parseArgs(Seq("x:y", "snap", <runId>, "2", "3", "500", "in/a.jsonl"))` returns Left "outputBase (argument 1)...", but no test runs this branch, and the report lists it as tested. Owner: hadoop-engineer.
2. **MINOR: the duplicate-input check catches only identical strings.** SourcePipelineMain.scala:24-25, 66, 84; report line 69. Failure input: `in/openalex/openalex-works-sample.jsonl 'in/openalex/*.jsonl'`, or the same file as `./in/...` or `file:///...`. The file is read twice and Job 2 counts DUPLICATE. Fix the wording, or normalize before comparing. Owner: hadoop-engineer.
3. **MINOR: the infra report overstates the bucket check.** The report says the sample, raw, and output buckets must all differ, but the script (run-bounded-pipeline.sh:187-193) checks the raw bucket only when SCHOLARCHAIN_RAW_BUCKET is set. Impact is limited to report accuracy: inputs are fixed `samples/.../manifest_version=1/` keys, so the full corpus is never read. Owner: infra-engineer (wording); the orchestrator annotated the report.

## Risks

`SourcePipelineMain.main` has never run. The script has never run against AWS (add-steps JSON, cygpath, and log layout untested).

## Next gate

Human ratifies the amendment, then the local bounded main-class run, then the part B gate.
