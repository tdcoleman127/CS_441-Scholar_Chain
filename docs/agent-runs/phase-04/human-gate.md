-------------------------------------
Phase 4: implement raw ingestion and Hadoop MapReduce

[Part A]

[Round 1-5]

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

[Part B] 

[Rounds 1-2]

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

[Part C]

[Closeout] 

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
Going forward for Phase 5
- Fresh session: the project rule in docs/agents.md is one fresh session per phase. Restart Claude Code after the commit. No amendment was applied, so the restart isn't required for ratification; it just gives Phase 5 a clean start.
- Work order: write docs/agent-runs/phase-05/prompt.md, as you did for prompt.md in Phase 4. Phase 5 is probably entity resolution (CLAUDE.md's pipeline order) but confirm against your course spec. Then tell the new session to read it, along with Phase 4's resume-point.md.
- Carry these into Phase 5:
entity-resolution-engineer writes the versioned blocking spec (option b), and the deferred recall findings E-F1 to E-F10 are reviewed against it;
factory-engineer round 3 of the main-class amendment, if you want local main-class runs.


Manual changes:
- N/A

Remaining risk:
- Main class and EMR script never executed; no Terraform yet
- No local main-class run rule (amendment deferred)
- Open minor findings: outputBase check untested; duplicate-input check only
  catches identical strings; one test leaves an sc-xxe* temp folder
- CDATA or comment containing a record start tag ends a record early (rejected)
- Blank normalized names reject the whole work; Indic vowel stripping can merge names

Accepted commit: 06559d0ccd5cff6837206e50014466a9ba8fe407