# code-reviewer: Phase 04 amendment review, round 1 (local main-class runs, R7C-JVM)

Assembled by the orchestrator from the agent's final report, 2026-10-27. Subject: factory-engineer-amendment-report.md (unratified, live in the working tree).

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 04 adversarial review of factory-engineer's unratified amendment: section 7c (scripts/run-local-main.sh), rule R7C-JVM, the sbt fence change, and the related role, settings, validator and docs edits. Input for the human's decisions D1 to D7.
FILES CREATED: none
FILES MODIFIED: none

VERDICT: REQUEST_CHANGES

Two MAJOR findings, both fixable in a small revision; none CRITICAL. The diff only adds blocks, apart from the run-local-main mapping. Floor invariants intact.

F1. MAJOR: the sbt run fence can be defeated by quote splicing or a backslash. No residual risk covers this, and the new constitutional text says this form is blocked.
- Where: .claude/hooks/guard-dangerous.sh:469 and :473 (section 6b) split words with `tr "\"';&|()\`[:space:]" ' '`, turning each quote into a separator and never dropping backslashes; bash joins quoted parts and drops unquoted backslashes. Section 10c does not inspect sbt task names.
- Failure inputs (hadoop-engineer):
  - sbt 'hadoop/runM''ain edu.uic.cs441.scholarchain.hadoop.DblpPipelineMain datasets/samples/dblp/dblp-sample.xml datasets/samples/dblp/dblp.dtd file:///<SP>/runX dblp-samples-local run:v1:<hex> 2 50' -- section 6b sees `hadoop/runm` and `ain`; bash passes `hadoop/runMain ...`.
  - sbt hadoop/ru\n -- section 6b sees `ru\n`; bash passes `hadoop/run`.
- Section 10c then scans the quoted string `hadoop/runmain ...` (base name `runmain`, not a launcher), so it passes. Section 11 applies only to read-only agents.
- Impact: reopens round-4 item 4 with a trivial spelling; only the default permission prompt remains. docs/agents.md:115 (section 5), docs/agents.md:238 (N4), hadoop-engineer.md:22 and spark-engineer.md:23 claim a mechanical block; RR-12 to RR-16 do not list this gap. The gap dates from phase 01 but no phase record accepts it.
- Fix: tokenize each segment in section 6b with toks_of (as section 10c does) and recurse into quoted multi-word tokens; or record a new residual risk and correct lines 115, 238 and both role files.
- Owner: factory-engineer.

F2. MAJOR: the unratified constitutional change is already live (D7), contrary to section 12, and the live part includes a widening.
- Where: docs/agents.md:323 (section 12 step 4: "Until then, the previous ratified configuration governs"); docs/agent-runs/phase-00/human-gate.md:16. The guard is read fresh on every call.
- Precedent: phase 01 edited in place and recorded it as a problem (phase-01/old-human-gate.md:161). Phase 03 corrected it: factory-engineer staged a patch and the human ran git apply after ratification (phase-03/agent-report.md:297; phase-03/human-gate.md:50-51, :90-101). Phase 04 reverts to editing in place.
- Not only narrowing: the W1/W2 mapping is live and not inert. Bash writes to scripts/** are not mechanically fenced, so `cat > scripts/run-local-main.sh <<'EOF' ... EOF` by hadoop-engineer is allowed today, and `scripts/run-local-main.sh hadoop edu.uic.cs441.scholarchain.hadoop.X datasets/samples/dblp/dblp.dtd <SP>/o` then gets ASK instead of the ratified guard's "unknown script" BLOCK. An unreviewed script body would be one prompt away.
- Fix: factory-engineer stages the amendment as a patch under docs/agent-runs/phase-04/evidence/, the 7 files are restored, the human applies the patch after ratification; or the human explicitly accepts the live state in human-gate.md.
- Owner: factory-engineer; the decision belongs to the human architect.

F3. MINOR: R7C-JVM bypass classes covered only by RR-13's general title; they should be named so D4 and D5 are informed.
- (a) Launcher built by expansion, argfile not first: `"$(which java)" -Dx=1 -Dy=2 @<SP>/java.args edu...DblpPipelineMain ...`; `J=ja; ${J}va -Dx=1 @<SP>/java.args Main`; `/c/*/*/*/bin/jav?.exe -Dx=1 @args Main`. Line 1466 checks only A[i+1] for @file; line 1468 needs literal java/jdk/...; in the wrapper branch @file after an option is skipped by design. The java launcher expands @argfiles at any position before the main class.
- (b) JVM option variables set without a literal NAME= word: `printf -v JAVA_TOOL_OPTIONS -- -javaagent:x.jar; export JAVA_TOOL_OPTIONS; sbt test` (line 1488 skips printf, in JVM_DATA; line 1400 needs `=`); `JAVA_TOOL_OPTIONS+=-javaagent:x.jar sbt test` (line 1483's regex misses `+=`; code reading only, not executed).
- Impact: contradicts docs/agents.md:236-237 and the role files' "the guard blocks it". Default permission prompt still applies.
- Fix: name these in RR-13 or a new RR, or fail closed on a non-literal command word followed by @file anywhere, and on printf -v, read, or += targeting JVM_OPTION_VARS.
- Owner: factory-engineer.

F4. MINOR: the guard's ask for run-local-main is a regex on raw text, so a spliced script name runs without the guard's ask.
- Where: guard-dangerous.sh:1627 vs :1244.
- Failure input: `scripts/run-local-mai''n.sh hadoop edu.uic.cs441.scholarchain.hadoop.DblpPipelineMain datasets/samples/dblp/dblp.dtd <SP>/o` -- toks_of joins quotes so local_main_check runs and passes; the section 12 regex and the settings.json ask rules do not match; guard exits 0 with no ask.
- Impact: docs/agents.md:231 (rule 7, "the guard returns ask for every other form") is false.
- Fix: emit the ask from script_check whenever script = run-local-main has passed local_main_check.
- Owner: factory-engineer.

F5. MINOR: undocumented false positives.
- `sbt "hadoop / Test / testOnly X"` blocked (line 1490 sees command word hadoop); sbt prints this spelling itself (phase-04/hadoop-engineer-repair1-report.md:27).
- `env HADOOP_HOME=$HOME/hadoop sbt -batch check` blocked (jvm_wrap_word takes base name hadoop from the assignment word; line 1411 treats `$` as a variable path).
- `sed -i 's/hadoop jar/X/' f` and similar quoted strings starting with a launcher name after a non-JVM_DATA command are blocked.
- None broke a phase 02-04 workflow; the phase-04 `HADOOP_HOME='C:\hadoop' PATH="/c/hadoop/bin:$PATH" sbt -batch check` passes. The block message wrongly routes to run-local-main.
- Fix: document at line 240, or skip assignment words in jvm_wrap_word.
- Owner: factory-engineer.

F6. MINOR: section 7c Reads/Writes bullets disagree with the guard.
- docs/agents.md:217 says reads only from datasets/samples/**; the guard admits any scratchpad or temp path as input (cases.txt:63 relies on it). %TEMP% holds other sessions' scratchpads.
- Spark's real input is Hadoop output; the guard does not allow spark to read modules/hadoop/target/**. The Hadoop-to-Spark chain is unspecified.
- datasets/samples/... is accepted as an output argument (smp=1 for every word); only S5's "must exist" stops it.
- getopt-attached values (`-o.claude`) are treated as bare words and pass. Low impact given RR-12.
- Fix: align lines 217-218 with rule 3 and S5; state the spark input path.
- Owner: factory-engineer.

F7. MINOR: S1-S9 gaps.
- S4 removes AWS variables, but the default credential chain still reads %USERPROFILE%\.aws\credentials and config; set AWS_SHARED_CREDENTIALS_FILE and AWS_CONFIG_FILE to a nonexistent path.
- S5 runs the JVM in a fresh working directory but rewrites only a leading datasets/samples/; accepted relative outputs (modules/hadoop/target/local-run/r1, cases.txt:62) would resolve under the fresh directory. Require every accepted path to be rewritten absolute.
- S8 does not order its baseline snapshot relative to S3's sbt export; with --ignored --untracked-files=all, compiling modules/core can create ignored files outside modules/<module>/target/, so S8 could fail every run.
- No requirement covers HADOOP_HOME, winutils or hadoop.dll on native Windows (human decision 1), and S3 forbids caller -D properties.
- Owner: factory-engineer (docs/agents.md), before D6.

ANSWERS
1. Bypasses: F1, F3, F4. ANSI-C forms ($'\x6aava') already rejected by section 0. cmd /c, env -S, eval, xargs, find -exec, wrapper and nested-string paths work as described.
2. Over-blocking: allowed (traced): sbt check with prefix, sbt fmt, sbt -batch -error "export hadoop/Test/fullClasspath", bash datasets/scripts/..., grep/ls/git log with hadoop/java/spark words, hadoop checknative -a 2>&1. `command -v java` blocked (documented generic-prefix class). New false positives in F5.
3. local_main_check holds (code reading) against RR-10/RR-11 spellings, quoted and backslash-escaped Windows paths with spaces, native backslash paths, c: drive-relative, file: forms, //?/ and UNC, trailing dots, ADS, ~, $, <, process substitution, unterminated redirects. Gaps: F6 and RR-16.
4. S1-S9: F7.
5. Consistency: docs sections, role files, settings ask rules and validator agree with each other; text disagrees with behavior in F1, F3, F4, F6.
6. Floor invariants intact: only 3 changed lines across settings.json and the floor files, all new ask entries; no deny rule changed; guard sections 0-9 and 11 unchanged apart from 6b; `|| exit 2` makes new code fail closed.
7. Process: F2.
8. Latency: not verified. timing.sh measured 3 short commands only. Section 10c adds ~4-6 subshells per segment plus ~3 per word after a wrapper; long commands scale with length. Whether a guard exceeding the 20 s timeout fails open was not verified.

COMMANDS RUN: git status; git diff --stat; git diff on the guard, role files, validator, settings.json; git diff on floor files | grep -c (3); wc -l on hooks; bash -n on guard and validator (clean); python -m json.tool .claude/settings.json | wc -l (parses, 328); ls -la scratchpad; ls -d on a backslash cwd path.
Blocked by the live guard (evidence): `cd "<root>" && git status` (double quotes rejected); `git -C '<root>' status` (rejected as git -c after lower-casing; existing reviewer over-block); `bash <SP>/probe.sh <SP>/cases-floor.txt`; `bash .claude/hooks/validate-agent-files.sh` (reviewer fence).
TEST RESULTS: none; no Scala or build changes.
VALIDATION RESULTS: bash -n passes; settings.json parses. Validator and probe harness not run by the reviewer (factory-engineer reports PASS and 148/148). Findings come from code reading, not guard execution.
ASSUMPTIONS: Claude Code prompts for Bash commands not on the allow list; java expands @argfiles at any position before the main class; bash accepts NAME+=value as a prefix assignment (not executed).
RISKS: hook timeout behavior and long-command latency unmeasured; RR-12 dominates once the script exists.
MANUAL ACTION REQUIRED: human decides D7 in light of F2; someone outside the reviewer fence runs the validator and probes with F1, F3, F4, F5 inputs added.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: executed probe run for F1, F3, F4, F5 inputs; long-command timing (200-line heredoc, 300-word timeout command) for old and new guard.
NEXT GATE: factory-engineer revises (fix F1, F4; document or fix F3, F5, F6; amend S4, S5, S8; stage as a patch per F2); code-reviewer reviews the revision; human ratifies D1-D7 and restarts. F2 also goes to the human architect.
```
