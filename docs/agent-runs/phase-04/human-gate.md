-------------------------------------
Phase 4: implement raw ingestion and Hadoop MapReduce

[Round 1-5]

[Part A]

Focus: hadoop-engineer

Create the first required big-data processing stage and prepare the records from which the collaboration graph will be built.

Reviewed:
- 

Decision: 

ACCEPTED, for Part A

Note: human-decisions-2026-10-27.md has my decisions

-For native Windows. `winutils.exe` and `hadoop.dll` in `C:\hadoop\bin`, user-level `HADOOP_HOME=C:\hadoop`, `C:\hadoop\bin` on PATH | hadoop-engineer (run report) |
- Deleted leftover `sc-hadoop-it*` temp folders (run report, MANUAL ACTION REQUIRED)
- Kept `MentionPairCandidate` and `TeamRecord` in the hadoop module | none (no change) |
- Added the input file name: RejectRecord v2 and routed to hadoop-engineer (repair 3)
- Factory-engineer writes an explicit rule: the human records it in the phase-04 human gate, routed to factory-engineer (amendment proposal; needs ratification and restart)
- Turn the skip into a failure, keeping the check inside hadoop-engineer's scope (no CI runner), routed to hadoop-engineer (repair 3)
- Regarding the evidence logs ignored by `.gitignore` (round 4, F1),  Pending: will commit myself w/ `git add -f docs/agent-runs/phase-04/run-evidence/*.log`

Manual changes:
- N/A

Remaining risk:
- 

Accepted commit: see Part B
--------------------

[Round X-X]

[Part B]

Focus:

Reviewed:

Decision: 

Note:

Manual changes:

Remaining risk:

Accepted commit:

--------------------
COMING UP TO DO:
Part A: It built the first big-data stage for DBLP alone: MapReduce jobs that parse the raw DBLP XML into work and author records, then expand each paper's author team into the coauthor pairs that the collaboration graph will be built from.

Part B: It brings in the other scholarly datasets (OpenAlex, Crossref, OpenCitations, arXiv), extracts their identifiers, and prepares the blocking groups and match candidates that entity resolution needs, then packages everything into the partitioned export files that Spark will read to build the graph.