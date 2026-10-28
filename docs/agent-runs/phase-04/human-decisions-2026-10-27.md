# Phase 04: human decisions stated in session, 2026-10-27

Recorded by the orchestrator from the human's chat messages, as input for `human-gate.md`. This is not the gate. The human writes `human-gate.md`.

| # | Question (source) | Human decision | Routed to |
|---|---|---|---|
| 1 | Runtime for the MapReduce jobs (resume-point step 1) | Option 1: native Windows. `winutils.exe` and `hadoop.dll` in `C:\hadoop\bin`, user-level `HADOOP_HOME=C:\hadoop`, `C:\hadoop\bin` on PATH | hadoop-engineer (run report) |
| 2 | Leftover `sc-hadoop-it*` temp folders (run report, MANUAL ACTION REQUIRED) | The human deleted them | none |
| 3 | Pair and team contracts (resume-point step 4) | Keep `MentionPairCandidate` and `TeamRecord` in the hadoop module | none (no change) |
| 4 | Reject provenance with multiple input files (run report RISKS; round 4 risks) | Add the input file name: RejectRecord v2 | hadoop-engineer (repair 3) |
| 5 | Local main-class run versus guard-dangerous (round 4, item 4) | factory-engineer writes an explicit rule; the human records it in the phase-04 human gate | factory-engineer (amendment proposal; needs ratification and restart) |
| 6 | LocalPipelineSuite skipping without HADOOP_HOME (round 4, item 5) | Turn the skip into a failure, keeping the check inside hadoop-engineer's scope (no CI runner) | hadoop-engineer (repair 3) |
| 7 | Evidence logs ignored by `.gitignore` (round 4, F1) | Pending: the human commits with `git add -f docs/agent-runs/phase-04/run-evidence/*.log` | human |
