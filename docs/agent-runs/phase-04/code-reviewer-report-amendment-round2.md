# code-reviewer: main-class run rule amendment, round 2

Assembled by the orchestrator, 2026-11-01, from code-reviewer's final report. Reviewed: `run-evidence/amendment-r2.patch` (patched scratchpad copy only; nothing applied). The reviewer notes that a safety classifier cut off part of its session; findings are described from file:line, and factory-engineer builds the exact probe strings in its own harness.

**VERDICT: REQUEST_CHANGES** (2 MAJOR, 3 MINOR).

## Findings (file:line in the patched `guard-dangerous.sh`)

**N1. MAJOR: the R7C-JVM launcher fence has bypasses outside the documented residual risks.** Each was found by reading the code. Fix all four, or name each as an RR and correct section 7c; add a probe per form.

| Location | Gap | Fix |
|---|---|---|
| :1534, :1529 | The wrapper parser treats only single-slash `/c` as cmd's command switch, but Git Bash requires `cmd //c` | Accept `/c`, `//c`, and `/k` |
| :1560, :949 | `time` is in KEYWORDS and skipped before wrapper handling, so its `-p` option becomes the command word | Handle `time` as a wrapper with `-p`, or remove it from KEYWORDS for this scan |
| :1529 | `exec -a <name>`: the option argument is taken as the wrapped command | Add `-a` to exec's optarg set |
| :1281-1282, :1559 | Version queries are allowed after `VAR=value` prefixes, so a JAVA_TOOL_OPTIONS or JDK_JAVA_OPTIONS prefix starts a JVM that loads an agent. RR-15's claim holds only for R7C-MAIN. | Block version queries that have a JVM-option-variable prefix |

- **Impact:** any caller can launch any class, and hadoop-engineer can skip every R7C-MAIN check and its ask. Section 7c (lines 229-233) and both role files claim these launches are blocked. This is the same class as round-1 F1. Owner: factory-engineer.

**N2. MAJOR: the sbt run fence can be bypassed by feeding commands indirectly.** :486-505. `sbt_scan` is a denylist of words in the command text.
- **Gap:** sbt's built-in `<` (read commands from a file) and redirected or piped stdin bypass it, because the file's contents (for example `hadoop/runMain ...`) never appear in the text. Other built-ins that load and invoke classes are possibly unlisted (the reviewer recalls `apply`, perhaps `call`; unconfirmed).
- **Impact:** runMain or arbitrary code runs through sbt with only the default prompt. A marker reached this way still needs all tests to pass (build.sbt:252-257). The gap predates the amendment, but section 7c and the role files now claim the fence is closed.
- **Fix:** block a `<` word and input redirects or pipes into sbt, and consider an allowlist of sbt task words; or name it as an RR and correct section 7c. Owner: factory-engineer.

**N3. MINOR: the path resolver and MSYS disagree about `/tmp`.** :1418.
- **Gap:** Python resolves `/tmp` as `C:\tmp`, but /etc/fstab maps `/tmp` to the user temp directory, so a junction at `%TEMP%\<d>` is not seen.
- **Impact:** docs/agents.md:221's "covers junctions, symlinks, and 8.3 names" claim is false for `/tmp` spellings. Exploiting it needs a separate, prompted command.
- **Fix:** resolve through `cygpath -w`, or reject `/tmp` spellings.

**N4. MINOR: `lm_simple` ignores backslash escaping when checking `&`.** :1302-1303, and toks_of :1348-1350.
- **Gap:** an escaped `>` followed by `&1` passes the check, but bash parses it as a background `&` plus a second command, breaking the "no second command" rule.
- **Impact:** low; it needs an executable named `1` or `2` on PATH.
- **Fix:** track escape state, or reject a backslash outside quotes directly before `>` or `&`.

**N5. MINOR: the F5 fix is incomplete.** :1564.
- **Gap:** `jvm_seg` splits on `; | & ( )` and backticks regardless of quotes, so `grep -nE '(java|scala)' build.sbt` is blocked for every caller (an over-block that fails closed).
- **Impact:** docs/agents.md:233 and the report's F5 "Fixed" row are inaccurate.
- **Fix:** a quote-aware split, or document the limit.

## Round-1 findings

| Finding | Status |
|---|---|
| F1 | Resolved for quote-splice, backslash, colon-scope, kebab-case, and alias spellings (:472-505); fence incomplete (N2) |
| F2 | Resolved. Live files clean; the evidence patch is byte-identical to the scratch patch; exactly 6 files change |
| F3 | Resolved for literal, glob, and substitution launchers (RR-14 names the variable forms); the prefix case is partial (N1) |
| F4 | Resolved: the ask comes from LM_OK (:1471, :1712), and no early `exit 0` skips it |
| F5 | Partial (N5) |
| F6 | Resolved in substance; the `/tmp` claim fails (N3) |
| F7 | Moot (script withdrawn) |

## Other questions

- **R7C-MAIN abuse:** no way was found, through the checked arguments, to write a protected path, read secrets, or reach the network, apart from N3 and N4. Bash-built words defeat the RR-10 and RR-11 classes; `..` is normalized; realpath covers symlinks, junctions, and 8.3 names except `/tmp`; `~` and globs are rejected; remote schemes are blocked; arbitrary classes are limited by the table apart from RR-12 and RR-16. The N1 bypasses avoid R7C-MAIN entirely.
- **Floor invariants:** intact. settings.json changes only the timeout (20 → 60); deny and allow lists, terraform, force-push, secret, and Stop rules are unchanged.
- **Consistency:** section 5, section 7c, the section 12 list, the role files, and the validator agree with each other but not with the guard's behavior (N1, N2, N3, N5).
- **Report accuracy:** the counts match the evidence (validator 96 PASS; probes 42/43, 10/10, 57/58, 18/18), and the 18-case caveat is stated honestly. Inaccurate: F5 "Fixed", and F1 and F3 implying complete fences. Timing was not reproduced.

## Risks

1. Claude Code's behavior on a PreToolUse hook timeout is unverified. Guard run time grows with command length; if a timeout fails open, the whole guard is bypassable. The human should verify this before relying on the guard for long commands.
2. A trailing `;` in the classpath is dropped by `read -ra` (:1339) rather than rejected; an empty classpath element may mean the working directory (unverified).
3. Only 18 cases ran on the final guard; the full rerun is pending.

## Commands and evidence

The reviewer ran `git status`, `git diff --no-index` (live versus patched trees, settings.json, evidence versus scratch patch), `bash -n` on the two patched hooks (clean), `cat /etc/fstab`, and reads of the harness, cases, results, and validator output. Commands blocked by the reviewer fence: sha256sum, `git -C`, and a `;` chain. It did not run the validator or probes.

## Next gate

1. factory-engineer revises N1 to N5 (fix or document), restages the patch, and reruns the full suites with the new probes.
2. code-reviewer round 3.
3. Human ratification, apply, validator, restart.
