-------------------------------------
Phase 4: implement raw ingestion and Hadoop MapReduce

[Round 1-5]

[Part A]

Focus: hadoop-engineer

Create the first required big-data processing stage and prepare the records from which the collaboration graph will be built.

Reviewed:
- hadoop-engineer, ran report, repairs 1-3
- code-reviewer, rounds 1-5 (approved on Round 5)
- sbt check passed (core 143/143, hadoop 27/27), local pipeline ran

Decision: 

ACCEPTED, for Part A

Note: human-decisions-2026-10-27.md has my decisions

- Runtime: native Windows (winutils.exe + hadoop.dll in C:\hadoop\bin, HADOOP_HOME=C:\hadoop, on PATH)
- Deleted leftover sc-hadoop-it* temp folders
- Kept MentionPairCandidate and TeamRecord in the hadoop module
- Added the input file name to rejects (RejectRecord v2), done in repair 3
- Main-class run rule: routed to factory-engineer (now amendment round 2, pending my ratification)
- Missing HADOOP_HOME now fails the local test instead of skipping it, done in repair 3
- Evidence logs are gitignored; committed with git add -f

Manual changes:
- N/A

Remaining risk:
- Rejects identify files by base name only; same-named files in different folders would be ambiguous
- Offsets are per file (for .gz, offsets in the decompressed stream); unsuffixed gzip is read raw
- Pair skew: 279 of 285 pairs landed on one partition (one 24-author paper)
- REJECTED includes DBLP "www" person records (counted separately as UNSUPPORTED_TYPE)


Accepted commit: 8364c79e386046d58ca653ee9ecc81f2c65648f9
--------------------

[Rounds 1-2]

[Part B] 

Focus: hadoop-engineer 

agent built jobs 2, 3, 4+5, 7 (external sources, identifiers, blocking, match candidates, Spark export). entity-resolution-engineer reviewed the blocking (read-only).

Reviewed:
- Round 1: REQUEST_CHANGES (2 major, 3 minor). All fixed in repair 1.
- Round 2: APPROVE (2 minor, non-blocking).
- sbt check passed after repair 1: core 143/143, hadoop 50/50 (final numbers in Part C)

Decision: ACCEPTED, for Part B

Notes (details in human-decisions-2026-10-29.md):
- Accepted the 11 new record types in the hadoop module.
- Blocking-key ownership: option b. entity-resolution writes the spec,
  hadoop implements it.
- Entity-resolution findings kept as known risks; fix in a later phase.

My notes:
- Repair rounds took much longer than expected

Manual changes:
- N/A

Remaining risk:
- Blocking may miss true matches (split blocks, suffixes, name order); recall
  not yet measured.
- SourcePipelineMain exists but has never been run (waits on the main-class amendment)
- Real-data gaps: compressed arXiv, quoted CSV fields, very large blocks.
- 680 of 861 pairs skipped in the largest sample block (42 members, cap 10)
- Topic/institution records repeat per work; Spark must deduplicate them

Accepted commit: 3c554ace31c2f0bda1415d68a8cd06560f42584c

--------------------

--------------------

[Closeout] [Part C]

Focus: final Phase 4 steps (main class, EMR smoke script, adversarial tests, main-class rule)

Reviewed:
- hadoop-engineer: SourcePipelineMain + Job 7 partitioner test
- infra-engineer: scripts/run-bounded-pipeline.sh (bash -n only; not executed)
- test-engineer: 35 adversarial tests; found 3 real defects, all fixed
  (DBLP scanner, invisible characters in names, http://dx.doi.org/ DOIs)
- code-reviewer closeout rounds 1 and 2: APPROVE (5 minor, non-blocking)
- Final sbt check: core 145/145, hadoop 66/66, contractTests 35/35

Decision: ACCEPTED, for closeout. Phase 4 complete.

Main-class amendment (round 2): DEFERRED, not ratified. Review returned
REQUEST_CHANGES (2 major fence bypasses). Live constitution unchanged.
Round 3 in a later phase.

My notes:
-

Manual changes:
- N/A

Remaining risk:
- Main class and EMR script never executed; no Terraform yet
- No local main-class run rule (amendment deferred)
- Open minor findings: outputBase check untested; duplicate-input check only
  catches identical strings; one test leaves an sc-xxe* temp folder
- CDATA or comment containing a record start tag ends a record early (rejected)
- Blank normalized names reject the whole work; Indic vowel stripping can merge names

Accepted commit:





















--------------------
COMING UP TO DO:
Part A: It built the first big-data stage for DBLP alone: MapReduce jobs that parse the raw DBLP XML into work and author records, then expand each paper's author team into the coauthor pairs that the collaboration graph will be built from.

Part B: It brings in the other scholarly datasets (OpenAlex, Crossref, OpenCitations, arXiv), extracts their identifiers, and prepares the blocking groups and match candidates that entity resolution needs, then packages everything into the partitioned export files that Spark will read to build the graph.

--------------------
FINAL PHASE 4 STEPS:
Phase 04, final steps (closeout). Read docs/agent-runs/phase-04/resume-point.md first.
Delegate everything; the orchestrator does no specialist work.

STAGE 1 (this session; items 1 and 2 can run in parallel)

1. factory-engineer: main-class run rule amendment, round 2.
   - Start from factory-engineer-amendment-report.md and
     code-reviewer-report-amendment-round1.md (F1 to F7).
   - Rule needed: hadoop-engineer may run a hadoop main class locally for a
     bounded run over datasets/samples. Use the form the guard can check
     (for example plain `java -cp ... <MainClass>`), and keep `sbt run`/`runMain`
     blocked. Also address the quote-spliced/backslash sbt run gap (amendment F1).
   - Stage the change as a patch in the scratchpad. Never edit the live
     constitutional files. Run the validator against the patched copy.
   - Present the full diff, then code-reviewer reviews the amendment.
   - STOP for my ratification.

2. test-engineer: adversarial tests for phase 04 parts A and B, under tests/** only.
   - Targets: malformed and truncated XML/JSON/CSV, Unicode and confusable names,
     duplicate DOIs, giant teams, oversize blocks, reject output, provenance on
     every record, determinism, and Job 7 co-partitioning (one work's entities
     land in the same part file).
   - Report exact pass/fail counts. Failing tests stay red and go to hadoop-engineer.

STAGE 2 (new session, after I ratify, commit, and restart)

3. hadoop-engineer: add a part B driver main class (SourcePipelineMain) for
   jobs 2, 3, 4+5 and 7, with argument validation tests. Do a local bounded run
   under the ratified rule. Also close round-2 MINOR 1 (a test that fails if
   Job 7's partitioner wiring is removed).
4. infra-engineer: write scripts/run-bounded-pipeline.sh, the bounded EMR
   smoke run for jobs 1 to 7 over the sample mirror.
   - Interface (agents.md section 7): environment first argument (dev only),
     bash strict mode, no prompts, greppable step markers, explicit size and
     time bounds.
   - Validate with bash -n plus shellcheck if available. No execution, no
     terraform apply.
   - Any missing Terraform (EMR, buckets, IAM) goes to fmt/validate/plan only,
     or is listed as BLOCKED-ON.
   - deploy-engineer runs the script later, not in this phase.
5. code-reviewer: closeout review of the stage 2 change set and test-engineer's tests.
6. Route any failing tests or findings to their owners, rerun, and update
   resume-point.md. Then stop for my part B gate and commit.

Gates: sbt check exit 0 before any report. Report per docs/agents.md section 11.
[Part B]  Rounds 1-2

Focus: hadoop-engineer built jobs 2, 3, 4+5, 7 (external sources, identifiers,
blocking, match candidates, Spark export). entity-resolution-engineer reviewed
the blocking (read-only).

Reviewed:
- Round 1: REQUEST_CHANGES (2 major, 3 minor). All fixed in repair 1.
- Round 2: APPROVE (2 minor, non-blocking).
- sbt check passed: core 143/143, hadoop 50/50.

Decision: ACCEPTED, for Part B

Notes (details in human-decisions-2026-10-29.md):
- Accepted the 11 new record types in the hadoop module.
- Blocking-key ownership: option b. entity-resolution writes the spec,
  hadoop implements it.
- Entity-resolution findings kept as known risks; fix in a later phase.

My notes:
-

Manual changes:
- N/A

Remaining risk:
- Blocking may miss true matches (split blocks, suffixes, name order); recall
  not yet measured.
- Part B runs only through tests until a main class exists (closeout stage 2).
- Real-data gaps: compressed arXiv, quoted CSV fields, very large blocks.

Accepted commit:

[Part B]  Rounds 1-2

Focus: hadoop-engineer built jobs 2, 3, 4+5, 7 (external sources, identifiers,
blocking, match candidates, Spark export). entity-resolution-engineer reviewed
the blocking (read-only).

Reviewed:
- Round 1: REQUEST_CHANGES (2 major, 3 minor). All fixed in repair 1.
- Round 2: APPROVE (2 minor, non-blocking).
- sbt check passed: core 143/143, hadoop 50/50.

Decision: ACCEPTED, for Part B

Notes (details in human-decisions-2026-10-29.md):
- Accepted the 11 new record types in the hadoop module.
- Blocking-key ownership: option b. entity-resolution writes the spec,
  hadoop implements it.
- Entity-resolution findings kept as known risks; fix in a later phase.

My notes:
-

Manual changes:
- N/A

Remaining risk:
- Blocking may miss true matches (split blocks, suffixes, name order); recall
  not yet measured.
- Part B runs only through tests until a main class exists (closeout stage 2).
- Real-data gaps: compressed arXiv, quoted CSV fields, very large blocks.

Accepted commit:
