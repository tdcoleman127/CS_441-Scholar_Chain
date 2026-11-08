# ScholarChain agents: authority, ownership, routing, and escalation

This file is shared project memory. CLAUDE.md imports it into every session. It is a constitutional artifact owned by `factory-engineer`. A change takes effect only after the human architect ratifies it and Claude Code is restarted. Ratification records live in `docs/agent-runs/phase-NN/human-gate.md`.

Source of authority: the "Agent authority and ownership matrix" in the course project specification (ScholarChain README, section "Agent authority and ownership matrix"). Section 2 transcribes it verbatim. Sections 3 to 7 refine it for this repository's exact directory structure, as the specification requires.

## 1. Topology: hub and spoke

- The main Claude Code session is the orchestrator. It has no file under `.claude/agents/`. It interprets the human's work order, delegates to the owning subagent, reads reports, routes blocked work, and presents results. It does not perform a specialist's work inline. The ownership hook allows it to write only `docs/agent-runs/**`, where it assembles agent reports.
- Subagents do not invoke one another. No agent definition grants the `Agent` or `Task` tool, and the validator rejects any that does. All routing passes through the orchestrator, which keeps an observable audit trail.
- Each subagent starts fresh with its role file, this shared memory, the current work order, and any prior reports that are pasted into the work order explicitly.
- A blocked agent returns `STATUS: BLOCKED-ON` with `BLOCKED-ON OWNER` and `BLOCKED-ON EVIDENCE`. The orchestrator routes the dependency to that owner, validates the repair, and reruns the blocked agent in a fresh context.
- Findings from code-reviewer and test-engineer go to the owning agent. Reviewers never fix anything.
- No agent approves its own work. One phase uses one fresh session and normally one commit.

## 2. Authority matrix (transcribed from the course specification)

<!-- roster:begin -->
| Agent | Creates and owns | Must not do | Primary gate |
|---|---|---|---|
| `factory-engineer` | `CLAUDE.md`, `docs/agents.md`, `.claude/**`, `.mcp.json` | Application, data, build, infrastructure, or deployment work | Human constitutional ratification and code review |
| `build-engineer` | `build.sbt`, `project/*`, `.scalafmt.conf`, local compose files, packaging, `.gitignore` | Application source, version-only maintenance, Terraform, deployment | `sbt check` and code review |
| `domain-engineer` | `modules/core/**`, shared domain records, stable identifiers, serialization contracts, and configuration contracts | Dataset retrieval, distributed jobs, storage adapters, service routes, infrastructure | Contract tests, `sbt check`, and code review |
| `dataset-engineer` | `datasets/**`, dataset manifest, download and sampling scripts, schema notes, license records | Hadoop analytics, Spark analytics, entity-match policy | Dataset validation tests and manifest review |
| `hadoop-engineer` | `modules/hadoop/**`, MapReduce jobs, Hadoop integration tests, counters, rejected-record formats | Spark jobs, storage clients, service APIs | Local or EMR sample run and deterministic output checks |
| `entity-resolution-engineer` | `modules/entity-resolution/**`, blocking keys, match features, thresholds, calibration tools, match decision schemas | Graph analytics, API synthesis, infrastructure | Gold-set evaluation and match-policy review |
| `spark-engineer` | `modules/spark/**`, graph construction, graph analytics, evidence-path generation | Hadoop parser ownership, runtime API, Terraform | Spark tests, sample run, output invariants |
| `storage-engineer` | `modules/storage/**`, loaders and adapters for S3, Neptune, DynamoDB, S3 Vectors, optional OpenSearch | Terraform, answer synthesis, runtime HTTP routes | Contract tests and bounded load test |
| `rag-engineer` | `modules/rag/**`, query plans, retrieval fusion, evidence bundles, prompts, claim verification | HTTP transport, cloud deployment, dataset acquisition | Retrieval and groundedness evaluation |
| `service-engineer` | `modules/services/**`, REST models, routes, health checks, runtime composition | Hadoop and Spark algorithms, Terraform, agent constitution | API tests and `sbt check` |
| `test-engineer` | Adversarial and cross-module tests under `tests/**` and designated test source trees | Production implementation | Exact pass and fail report; no production edits |
| `code-reviewer` | No implementation artifacts; review reports only | Any write or edit | Read-only verified verdict |
| `infra-engineer` | `infra/terraform/**`, deployment, smoke-test, reproduction, cleanup scripts, and optional GitHub workflows | `terraform apply`, live deployment, application source | Format, validate, plan, script syntax checks, security scan, human approval |
| `deploy-engineer` | Deployment execution reports and release records | Authoring Terraform or deployment scripts | Approved plan, clean tree, smoke tests, rollback gate |
| `experiment-engineer` | `experiments/**`, `load-tests/**`, experiment manifests, raw result collectors | Altering production algorithms to improve reported results | Reproducible run and immutable raw results |
| `documentation-engineer` | `README.md`, `docs/**` except `docs/agents.md`, `docs/agent-runs/**`, and `docs/incidents/**`, plus the final report, diagrams, and demo script | Inventing results or changing implementation | Link check, command replay, evidence review |
| `incident-responder` | `docs/incidents/**`, diagnosis reports, approved rollback and bounded recovery actions | Schema changes, destructive data repair, infrastructure authorship | Human escalation for data, security, or destructive actions |
| `dependency-updater` | Version values in the approved dependency ledger and dependency update reports | Build structure, application features, major upgrades without approval | `sbt check`, vulnerability review, code review |
<!-- roster:end -->

Human ratification records under `docs/agent-runs/**` are human-owned evidence, not agent-authored implementation artifacts. The orchestrator may assemble agent final reports there, but no agent rewrites a human decision.

## 3. Refinements to the transcription

These refinements keep section 2's intent and make it exact for this repository. Each one is part of the phase-01 ratification request.

1. **factory-engineer scope.** The specification row says `.claude/**`. The ratified seed (`.claude/agents/factory-engineer.md`, phase 00) lists `.claude/agents/*`, `.claude/hooks/*`, `.claude/commands/*`, and `.claude/settings.json`. This map keeps the narrower seed list. It does not widen the seed. The remaining `.claude/` paths are `.claude/.last-test-run`, which `sbt check` generates (no agent writes it), and `.claude/settings.local.json`, which is human-local, uncommitted, and written by no agent.
2. **Phase-01 one-time grant.** The phase-01 work order (`docs/agent-runs/phase-01/prompt.md`, written by the human) grants factory-engineer permission to create `docs/agent-runs/README.md` as the evidence-format scaffold, for phase 01 only. Every other `docs/agent-runs/**` file stays human-owned. After phase 01, `docs/agent-runs/README.md` is human-owned too. The ownership hook carries no standing exception for it.
3. **Feature tests and test territories.** Each module owner writes the feature tests for its own behavior changes under `modules/<module>/src/test/**`. Every behavior change ships with a test (CLAUDE.md law 2). `test-engineer` exclusively owns `tests/**`: contracts, integration, end-to-end, and adversarial suites. Those are the designated cross-module test source trees. Neither writes in the other's territory. A defect that a `tests/**` test exposes stays red and goes to the module owner.
4. **Dependency ledger.** The single version ledger is `project/Versions.scala` (requested in the phase-02 human gate, recorded by the phase-03 amendment). `build-engineer` remains the path owner: structure, `val` names, new or removed entries, comments, scopes, and the three mirror files the build checks against the ledger (`project/build.properties`, `.scalafmt.conf`, `docker/services/Dockerfile`). `dependency-updater` may change only version values, meaning the contents of existing string literals in that file, and only with the Edit tool. This is the only ratified split of one path between two writers (section 4). Bumps of `sbt`, `scalafmt`, or `javaRuntimeImage` also need a mirror-file edit, which dependency-updater cannot make, and the build's ledger consistency check fails while the two disagree. For those three, dependency-updater returns the proposed value as a report, and build-engineer makes the paired edit.
5. **Build-adjacent files.** `.gitattributes` (line endings, for example `*.sh text eol=lf`) and `docker/**` (image definitions) are packaging and build hygiene, so they go to build-engineer.
6. **reports/ split.** `reports/final-report.md` belongs to documentation-engineer. `reports/deployments/**` (deployment and pipeline-run reports) and `reports/releases/**` (release records and manifests) belong to deploy-engineer. Any other `reports/**` path has no owner until an amendment assigns one.
7. **prompts/ split.** `prompts/**` (runtime prompts and their output schemas) belongs to rag-engineer. The entity-resolution adjudication prompt and schema live inside `modules/entity-resolution/**`.
8. **Shared subsystems, split by verb.** S3 Vectors: infra-engineer writes the Terraform resources, storage-engineer writes the ingestion and query adapters, and rag-engineer writes the embedding text, chunking, and retrieval logic. Evaluation: entity-resolution-engineer and rag-engineer write the evaluation code in their own modules, and experiment-engineer runs approved evaluations and owns the raw results. Pipelines: module owners write the jobs, infra-engineer writes `scripts/run-bounded-pipeline.sh`, and deploy-engineer runs it.

## 4. Ownership map (enforced mechanically for Write and Edit by `.claude/hooks/guard-ownership.sh`)

Exactly one writer per path, with one ratified exception: the dependency-ledger row splits `project/Versions.scala` by verb, version values versus everything else (section 3, refinement 4). The first matching row wins. Paths are matched case-insensitively. A path that matches no row has no owner, and every Write or Edit to it is blocked until an amendment assigns one.

Enforcement limits. guard-ownership.sh checks this whole map, but only for the Write, Edit, MultiEdit, and NotebookEdit tools. Bash writes (for example `sed -i`, `cp`, `mv`, redirects, scripts, and programs such as python) are not checked against this map. For Bash, the map rests on the permission prompt, the role files, and code-reviewer detection. guard-dangerous.sh (sections 9 and 9b) adds one narrow mechanical layer. It blocks Bash writes to the constitutional paths (the factory-engineer row; all callers except factory-engineer), to `.git` configuration and `git config` writes (all callers except factory-engineer), to `.claude/.last-test-run` and `human-gate.md` (all callers), and to `docs/agent-runs/**` (all callers except the orchestrator). It also blocks those writes when they are hidden inside `bash -c`, `sh -c`, `eval`, `env -S`, `xargs`, `find -exec`, and similar wrappers, and blocks glob or variable operands, cd into protected directories, and archive extraction where the target cannot be verified (fail closed). Windows 8.3 short names (`CLAUDE~1` for `.claude`, `GIT~1` for `.git`) count as naming a protected path (rule R4-SHORTNAME). A write operand or redirect target that contains `~<digit>` is blocked when it could reach a protected path: its directory is itself a variable or glob, or it is in the repository root or a protected directory. An operand in a literal, unprotected directory stays allowed (`cp /tmp/sc/a.txt /tmp/sc/b~1`). The inline-interpreter check and the `xargs`/`parallel` check read the whole command line, so any command that contains `~<digit>` anywhere, including a revision such as `HEAD~1`, is blocked for every caller if it also runs inline interpreter code (`git diff HEAD~1 | python -c '...'`) or an `xargs`/`parallel` target that is not a read-only command. It does not detect writes that a program makes internally without naming the path on the command line, writes through symlinks, output options of programs it does not model, or paths that inline interpreter code builds at run time (residual RR-08, section 7b).

Ledger value check (guard-ownership.sh, `LEDGER_PATH` and `LEDGER_EDITOR`). An Edit by dependency-updater to `project/Versions.scala` passes only if three things hold. First, replacing every one-line double-quoted literal in `old_string` and `new_string` with `""` leaves identical text, so no `val`, name, comment, operator, or entry is added, removed, or changed. Second, every changed literal contains a digit before and after the edit. Third, the new literal matches `^[0-9A-Za-z][0-9A-Za-z._:@/-]*$` and contains neither `latest` nor `SNAPSHOT`. A backslash or an unbalanced quote fails closed, and Write, MultiEdit, and NotebookEdit are refused. Two things are not mechanical and rest on the role file plus code-reviewer: a Bash write to the ledger, and whether a well-formed value is the right one (for example a major bump, or a Hadoop or Spark value that EMR does not ship).

| Path | Owner | Gate |
|---|---|---|
| `CLAUDE.md`, `docs/agents.md`, `.mcp.json`, `.claude/settings.json`, `.claude/agents/*`, `.claude/hooks/*`, `.claude/commands/*` | `factory-engineer` | Human ratification and restart; validator |
| `.claude/.last-test-run` | generated by `sbt check` (markTestRun) | Never written directly |
| other `.claude/*` (for example `settings.local.json`) | human, uncommitted | None by agents |
| `docs/agent-runs/**` | human (the orchestrator may assemble reports; `human-gate.md` is human-only) | Human decision |
| `docs/incidents/**` | `incident-responder` | Human escalation rules (section 8) |
| `docs/**` (all other), `README.md`, `reports/final-report.md` | `documentation-engineer` | Link check, command replay, evidence review |
| `reports/deployments/**`, `reports/releases/**` | `deploy-engineer` | Approved plan, smoke tests |
| `project/Versions.scala` (the dependency ledger) | `build-engineer` (structure, entries, names, comments, scopes); `dependency-updater` (version values only: contents of existing string literals, Edit tool only, ledger value check above) | `sbt check`, vulnerability review, code review |
| `build.sbt`, `project/**`, `.scalafmt.conf`, `.gitignore`, `.gitattributes`, `docker-compose*.y*ml`, `compose*.y*ml`, `docker/**`, and empty `.gitkeep` placeholders anywhere except `.claude/**`, `docs/agent-runs/**`, and paths owned by factory-engineer, the human, or generated | `build-engineer` | `sbt check`, code review |
| `modules/core/**` | `domain-engineer` | Contract tests, `sbt check`, code review |
| `modules/hadoop/**` | `hadoop-engineer` | Sample run, deterministic outputs |
| `modules/entity-resolution/**` | `entity-resolution-engineer` | Gold-set evaluation, match-policy review |
| `modules/spark/**` | `spark-engineer` | Spark tests, output invariants |
| `modules/storage/**` | `storage-engineer` | Contract tests, bounded load test |
| `modules/rag/**`, `prompts/**` | `rag-engineer` | Retrieval and groundedness evaluation |
| `modules/services/**` | `service-engineer` | API tests, `sbt check` |
| `tests/**` | `test-engineer` | Exact pass/fail report |
| `datasets/**` | `dataset-engineer` | Manifest validation |
| `infra/terraform/**`, `scripts/**`, `.github/workflows/**` | `infra-engineer` | fmt, validate, plan, `bash -n`, security scan, human approval |
| `experiments/**`, `load-tests/**` | `experiment-engineer` | Reproducible run, immutable raw results |
| none (review reports are returned, not written) | `code-reviewer` | Read-only verdict |

Secret material (`.env*`, keys, keystores, `secrets/**`, `*.tfstate`) is never written by any agent. Writes outside the repository are limited to the session scratchpad and temp directories.

## 5. Tool fences

Every agent file has an explicit `tools:` line. Enforcement layers, strongest first: the tool is absent from the fence; a deny rule or hook; an instruction backed by reviewer detection. "Ownership hook" below means guard-ownership.sh, which fences the Write and Edit tools only. An agent that has Bash can write outside its territory through Bash. Those writes rely on the permission prompt, the role file, and code-reviewer detection, except for the protected paths that guard-dangerous.sh blocks (section 4, "Enforcement limits").

| Agent | Tools | Additional mechanical enforcement |
|---|---|---|
| `factory-engineer` | Read, Grep, Glob, Write, Edit, Bash | Only writer of constitutional paths (ownership hook); human ratification |
| `build-engineer` | Read, Grep, Glob, Write, Edit, Bash, aws-docs search/read | Ownership hook |
| `domain-engineer` | Read, Grep, Glob, Write, Edit, Bash | Ownership hook |
| `dataset-engineer` | Read, Grep, Glob, Write, Edit, Bash, WebFetch | Ownership hook; WebFetch asks the human every time; dataset scripts per section 7b (fetch scripts ask; `fetch.sh --full` blocked) |
| `hadoop-engineer` | Read, Grep, Glob, Write, Edit, Bash | Ownership hook |
| `entity-resolution-engineer` | Read, Grep, Glob, Write, Edit, Bash | Ownership hook |
| `spark-engineer` | Read, Grep, Glob, Write, Edit, Bash | Ownership hook |
| `storage-engineer` | Read, Grep, Glob, Write, Edit, Bash, aws-docs search/read | Ownership hook |
| `rag-engineer` | Read, Grep, Glob, Write, Edit, Bash, aws-docs search/read | Ownership hook |
| `service-engineer` | Read, Grep, Glob, Write, Edit, Bash | Ownership hook |
| `test-engineer` | Read, Grep, Glob, Write, Edit, Bash | Ownership hook limits Write and Edit to `tests/**`; Bash writes are fenced by instruction and review only |
| `code-reviewer` | Read, Grep, Glob, Bash | No Write, Edit, or MCP. Bash is fenced to evidence commands by guard-dangerous.sh (`READ_ONLY_AGENTS`). Single quotes are the only quoting allowed. Outside them, braces, glob characters (`{ } * ? [ ]`), and a word-initial `~` (including right after an unspaced `|`) are rejected, so patterns must be quoted (`find . -name '*.sh'`). `git -C <dir>` is allowed only when `<dir>` is the repository root and contains no `..` path segment (the root check is lexical, so `sub/..` through a symlink or junction is refused) |
| `infra-engineer` | Read, Grep, Glob, Write, Edit, Bash, `mcp__aws-api__call_aws`, terraform registry (7 read tools), aws-docs search/read, GitHub Actions read (3 tools) | terraform apply/destroy denied; deploy/rollback/smoke scripts denied to it; AWS MCP read-only |
| `deploy-engineer` | Read, Grep, Glob, Bash, Write, `mcp__aws-api__call_aws` | No Edit. Writes only `reports/deployments|releases/**`. Runs only mapped scripts in approved environments. Direct AWS mutations blocked |
| `experiment-engineer` | Read, Grep, Glob, Write, Edit, Bash, `mcp__aws-api__call_aws` | Runs only `scripts/run-experiment.sh` with `experiments/` manifests |
| `documentation-engineer` | Read, Grep, Glob, Write, Edit, Bash | Ownership hook excludes `docs/agents.md`, `docs/agent-runs/**`, and `docs/incidents/**` |
| `incident-responder` | Read, Grep, Glob, Bash, Write, Edit, `mcp__aws-api__call_aws`, aws-docs search/read | Writes only `docs/incidents/**`; may run only rollback and smoke-test scripts |
| `dependency-updater` | Read, Grep, Glob, Edit, Bash, WebSearch, WebFetch, GitHub releases read (2 tools) | No Write. The ownership hook lets it Edit only version values in `project/Versions.scala` (section 4, ledger value check) and blocks every other path. Bash writes are fenced by instruction and review only |

Two rules apply to every agent and to the orchestrator, through hooks and deny rules. Secrets are never read. AWS is never mutated directly: the guard allows only read-only AWS CLI operations, and every change goes through Terraform (human-applied) or an approved script.

guard-dangerous.sh also blocks two command shapes for every caller, because it cannot see what bash would make of them. First, a backslash directly before a newline or carriage return, in any quote state. Second, `$'` or `$"` anywhere, even inside quotes, in a command that is multi-line or contains `${`, `$(` (which covers `$((`), a backtick, `$[`, `<(`, or `>(`. A `#` comment, a heredoc body, or the fresh quoting context inside a substitution can hide ANSI-C quoting from the guard. So `grep -n 'foo$' $(git ls-files)` is blocked. Run the substitution as a separate command, anchor without a trailing `$` (`grep -nx '.*foo'`), or use the Write tool for file content.

## 6. Lifecycle routing

| Lifecycle stage | Trigger vocabulary | Owning agent | Gate, then follow-up |
|---|---|---|---|
| Agent system, hooks, permissions, MCP, routing | agent, hook, permission, settings, MCP, CLAUDE.md, routing | `factory-engineer` | Validator, human ratification, restart |
| Build and skeleton | build.sbt, sbt, module wiring, plugin, scalafmt, packaging, Docker image, new dependency | `build-engineer` | `sbt check`, then code-reviewer |
| Version maintenance | bump, upgrade, CVE, advisory, patch or minor version | `dependency-updater` | `sbt check`, then code-reviewer, human merge |
| Contracts and configuration | domain record, stable ID, wire format, schema version, configuration loader | `domain-engineer` | Contract tests, then code-reviewer |
| Datasets | DBLP, OpenAlex, Crossref, ORCID, arXiv, snapshot, license, manifest, sample | `dataset-engineer` | Manifest validation, then code-reviewer |
| Raw ingestion | MapReduce, mapper, reducer, DBLP XML parse, blocking, pair expansion, counters | `hadoop-engineer` | Sample run, then test-engineer, then code-reviewer |
| Entity resolution | match, merge, split, threshold, adjudication, gold set, precision, recall | `entity-resolution-engineer` | Gold-set evaluation, then code-reviewer |
| Graph analytics | Spark, coauthorship graph, degree, components, paths, communities, bridges, k-core | `spark-engineer` | Known-graph tests, then code-reviewer |
| Storage and loading | Neptune, DynamoDB, S3 adapter, S3 Vectors ingestion, loader, conditional write | `storage-engineer` | Contract tests, bounded load, then code-reviewer |
| Runtime Graph-RAG | query plan, retrieval fusion, embedding text, prompt, evidence bundle, claim verification | `rag-engineer` | Groundedness evaluation, then code-reviewer |
| REST services | route, endpoint, /v1/query, request validation, health, readiness | `service-engineer` | API tests, then code-reviewer |
| Adversarial and cross-module tests | adversarial, end-to-end, integration, contract suite, fuzz | `test-engineer` | Exact counts; failures go to owners |
| Review | review, approve, audit diff | `code-reviewer` | APPROVE or REQUEST_CHANGES |
| Infrastructure and automation | Terraform, IAM, VPC, EMR, ECS, plan, operational script, workflow | `infra-engineer` | fmt, validate, plan, human plan sign-off, code-reviewer |
| Deployment, release rollback, bounded pipeline run | /deploy, /rollback, /run-pipeline, release | `deploy-engineer` | Section 7 gates, smoke tests |
| Experiments and evaluation runs | /experiment, load test, elasticity, latency, retrieval modes, evaluation run | `experiment-engineer` | Approved manifest, immutable raw results |
| Documentation and final report | README, runbook, architecture, threat model, final report, demo script | `documentation-engineer` | Command replay, evidence links |
| Incidents | /incident, alarm, outage, DLQ, timeout, degraded latency | `incident-responder` | Evidence-first diagnosis, human escalation |
| Cleanup of cloud resources | cleanup, teardown | human architect runs `scripts/cleanup.sh` (written by infra-engineer) | Before-and-after resource inventory |

If a request matches no row, or matches two rows in a way the trigger vocabulary cannot settle, the orchestrator treats it as ambiguous ownership (section 8).

## 7. Operational scripts and approved environments (enforced by guard-dangerous.sh)

Approved environments: `dev`. Adding an environment is a constitutional amendment.

Path resolution. The guard resolves each executed script path against the command's working directory, case-insensitively, before it picks a map. A path that resolves into the repository-root `scripts/` directory uses the table below. A path that resolves into `datasets/scripts/` uses section 7b. A path that resolves into neither but ends in `scripts/<name>.sh`, such as a copy in a temp directory or a nested `scripts/` directory, is still checked against the table below by name (fail closed). Any `cd`, `pushd`, `popd`, `env -C`, or `--chdir` combined with running a script is blocked, unless its literal target is the repository root. A directory change therefore cannot change which map applies.

Script interface contract for infra-engineer: every script in `ENV` below takes the environment name as its first positional argument, uses bash strict mode, has no interactive prompts, and prints greppable step markers.

| Script | Who may run it through Claude | ENV | Permission prompt |
|---|---|---|---|
| `scripts/check.sh` | any agent | no | no |
| `scripts/package.sh` | any agent | no | ask |
| `scripts/plan.sh` | infra-engineer, deploy-engineer | yes | no |
| `scripts/deploy.sh` | deploy-engineer | yes | ask |
| `scripts/rollback.sh` | deploy-engineer, incident-responder | yes | ask |
| `scripts/smoke-test.sh` | deploy-engineer, incident-responder, experiment-engineer | yes | no |
| `scripts/run-bounded-pipeline.sh` | deploy-engineer | yes | ask |
| `scripts/run-experiment.sh` | experiment-engineer (manifest under `experiments/`) | no | ask |
| `scripts/archive-evidence.sh` | deploy-engineer, experiment-engineer | no | ask |
| `scripts/cleanup.sh` | nobody: the human runs it outside Claude | n/a | denied |
| any other `scripts/*.sh` | nobody until an amendment maps it | n/a | blocked |

`terraform apply` is denied to every agent. By default the human architect applies the approved plan. Whether `scripts/deploy.sh` may itself apply a human-approved plan checksum is a separate decision. The human must record it in a phase human gate, and it needs an amendment here before infra-engineer writes it.

### 7b. Dataset scripts (`datasets/scripts/*.sh`, written by dataset-engineer; enforced by guard-dangerous.sh `dataset_script_runners`)

Run them from the repository root as `bash datasets/scripts/<name>.sh ...`.

| Script | Who may run it through Claude | Permission prompt | Bounds |
|---|---|---|---|
| `validate-datasets.sh` | any agent | no | Offline and read-only |
| `record-sample-checksums.sh --check` | any agent | no | Verify only |
| `record-sample-checksums.sh` (write mode, no `--check`) | dataset-engineer | no | Rewrites `datasets/samples/SHA256SUMS` only |
| `sample-extract.sh` | dataset-engineer | yes (settings.json has no allow rule for it) | Offline. Writes only its `--out` file |
| `fetch.sh` with `--plan` (the default) or `--metadata` | dataset-engineer | ask | Uses the network. The script caps metadata downloads at 50 MiB |
| `fetch.sh --full` | nobody: human-invoked only, until a ratified amendment and a phase human gate approve a size bound | n/a | blocked |
| `fetch-by-id.sh` | dataset-engineer | ask | Uses the network. The script caps runs at 500 IDs, 5 arXiv pages, and 5 MiB per response |
| `lib.sh` | nobody: the other scripts source it | n/a | blocked |
| any other `datasets/scripts/*.sh` | nobody until an amendment maps it | n/a | blocked |

Additional rules:
- The ask prompt is enforced in two places. settings.json has ask rules for the repository-relative forms. guard-dangerous.sh also returns a permission decision of `ask` for any dataset-engineer command that names `fetch.sh` or `fetch-by-id.sh`, so absolute-path and other forms prompt too.
- A dataset-script command that also names a protected path is blocked. Protected paths are the constitutional files, `docs/agent-runs/**`, `human-gate.md`, `.claude/.last-test-run`, and `.git/`.
- Protected paths after normalization (rule R4-PROTNORM). When the guard sees a dataset script run, it splits the whole command into fragments at whitespace, quote characters, and shell operators. These fragments are not the words bash builds (see RR-10). Each fragment, and each value after `=` in a fragment (`--out=x`, `SCHOLARCHAIN_DATA_DIR=x`), is resolved against the working directory: `.`, `..`, repeated slashes, backslashes, absolute and drive paths, and case, and also through the file system (8.3 short names, trailing dots, symlinks, junctions). The command is blocked if any word resolves to `CLAUDE.md`, `docs/agents.md`, `.mcp.json`, `.claude/**`, `.git/**`, `docs/agent-runs/**`, or any `human-gate.md`. Words under `/proc/` (MSYS `/proc/self/cwd` is the working directory), UNC or device paths (`//host`, `//?/`), and words containing `~<digit>` are blocked without resolution (fail closed). So `--out .claude/./hooks/common.sh`, `--out datasets/../.claude/hooks/x`, `--dest docs//agent-runs`, `--dest .claude./hooks`, and `SCHOLARCHAIN_DATA_DIR=CLAUDE~1/hooks` are blocked. Spellings that bash rebuilds from quotes, backslashes, or globs are not (RR-10).
- Prompts. `sample-extract.sh`, `fetch.sh`, and `fetch-by-id.sh` have no settings.json allow rule, so every run of them through Claude asks the human, whatever form the command takes. Only `validate-datasets.sh` and `record-sample-checksums.sh` (repository-relative forms) are auto-allowed.
- The read-only fence (code-reviewer) still allows only `bash -n` on these scripts.
- Residual risks. The guard does not fence the bypass classes below. Each relies on the permission prompt (none of these forms is on the allow list), the role files, and code-reviewer detection. Each has an ID so it can be decided separately:
  - RR-01 (phase-03 Round 2 review, item a): a variable supplies an argument, for example `F=--full; bash datasets/scripts/fetch.sh dblp $F`.
  - RR-02 (Round 2, item b): a launcher supplies the arguments at run time, for example `xargs datasets/scripts/fetch.sh dblp < args.txt`.
  - RR-03 (Round 2, item c): a protected destination built at run time (`X=docs/agent-ru; ... --out ${X}ns/x`). An unquoted literal assignment such as `D=docs/agent-runs` or `SCHOLARCHAIN_DATA_DIR=docs/agent-runs` is blocked by R4-PROTNORM. A quoted, backslash, or glob spelling of the same value is not (RR-10).
  - RR-04 (Round 3 review F2): a launcher starts a program chosen at run time: `find datasets/scripts -name 'fetch.s?' -exec env {} ...`, `xargs -I % % ...`, `xargs -a args.txt env`.
  - RR-05 (Round 3 review F3): forms the command parser does not follow: `coproc`, `trap '<command>' EXIT`, `flock <lock> <script>`, `alias`, and similar. The settings.json deny rule `Bash(*fetch.sh *--full*)` is case-sensitive, so `Fetch.sh` escapes it. The guard lower-cases commands, but only where its parser reaches the script.
  - RR-06 (Round 3 review F5): a renamed copy run without `.sh` or outside `scripts/`, for example `/tmp/sc/g dblp --full`.
  - RR-07 (Round 2 residual R6-R8): copying, linking, or renaming a dataset script and running the copy, for example `cp datasets/scripts/fetch.sh /tmp/g.sh; bash /tmp/g.sh dblp --full`.
  - RR-08 (Round 4 finding NF2): output options of programs the guard does not model, such as `curl -o .claude/hooks/x URL`, `wget -O`, and `sort -o`, and paths that inline interpreter code builds at run time or spells with a trailing dot (`.claude./hooks`, which native Windows programs normalize).
  - RR-09: `datasets/scripts/validate_datasets.py`, `datasets/tests/run-tests.sh`, scripts that another script calls internally, `make`, and script files written with the Write tool in the scratchpad or a temp directory.
  - RR-10 (Round 4 review F1): lexical spellings of a protected path get past R4-PROTNORM. The rule splits the command at quote characters and treats `\` as a path separator, but bash joins quoted parts into one word and drops unquoted backslashes. The rule also does not expand globs. Examples: `--out .cl'aude'/hooks/common.sh`, `--out .cl\aude/hooks/common.sh`, `--out docs/agent''-runs/...`, `SCHOLARCHAIN_DATA_DIR=docs/agent''-runs ...`, and `--out .claud?/hooks/...`. Only the permission prompt covers this: `sample-extract.sh`, `fetch.sh`, and `fetch-by-id.sh` have no allow rule, so the human is asked before every run. The human should deny any such prompt whose path arguments (`--out`, `--dest`, `--root`, `--input`, `--dois`, `--ids`, `--sources`, or a `SCHOLARCHAIN_DATA_DIR=` value) contain a quote, a backslash, or a glob character (`*`, `?`, `[`).
  - RR-11 (phase-03 Round 3 post-apply probe rerun, probes P10, P10f, and P10h; also P10c and P10d in the same rerun): an absolute path to the live repository root gets past R4-PROTNORM when the root contains spaces and is written quoted or with backslash-escaped spaces, followed by a `.`-segment or doubled-slash spelling of a protected directory. Examples: `--out "<repo root>/.claude/./hooks/x.csv"`, `--out '<repo root>/.claude/./hooks/x.csv'`, `--out "<repo root>/.claude//hooks/x.csv"`, `--out "<repo root>/docs/./agent-runs/x.csv"`, and `--out <repo\ root>/.claude/./hooks/x.csv`, where the root here is `C:/Users/figge/OneDrive/Desktop/UIC Folder/CS 441/CS_441-Scholar_Chain`. The same rerun blocked `"<repo root>/.git/./config"` (P10g) and `"<repo root>/datasets/../.claude/hooks/x.csv"` (P10e). The cause is likely that R4-PROTNORM splits the command at whitespace, quote characters, and backslashes, so the root breaks into fragments and the last fragment (for example `441/CS_441-Scholar_Chain/.claude/./hooks/x.csv`) is resolved against the working directory instead of the root, and does not reach a protected path. The rerun did not isolate the cause. Only the permission prompt covers this: `sample-extract.sh`, `fetch.sh`, and `fetch-by-id.sh` have no allow rule, so the human is asked before every run. Extending the RR-10 rule, the human should also deny any such prompt whose path arguments are an absolute path into the repository.

## 8. Escalation policy

The agent prepares the escalation artifact, then stops. The human decides.

| Trigger | Escalation artifact | Decider |
|---|---|---|
| Constitutional change (anything factory-engineer owns, any tool, ownership, or autonomy change, widening or narrowing) | Full diff, per-file matrix justification, audits, validation output | Human ratification, then restart |
| Data destruction or repair (S3 deletes, DynamoDB or Neptune deletes, vector index removal, reprocessing that overwrites outputs) | Exact statement or command, blast radius, backup or restore evidence, rollback plan | Human signs, then a human runs it |
| Security change (IAM, KMS, Secrets Manager, network exposure, authentication, credential exposure) | Finding, evidence, proposed Terraform diff or fix owner | Human |
| Terraform apply or destroy, or state surgery | Plan file checksum, add/change/destroy counts, stateful replacements first, IAM changes, cost estimate | Human runs apply; destroy only during approved cleanup |
| Deployment | `/deploy` gates: approved checksum, clean tree, sbt check, reviewer APPROVE | Human approves the gate and answers each script prompt |
| Expensive scale change (EMR capacity or fleet changes, raised ECS or worker maximums, full-corpus runs, higher model concurrency or token budgets, optional OpenSearch) | Cost estimate, bounds, duration, rollback | Human |
| Ambiguous ownership (no owner, or two plausible owners) | Path or request, candidate owners, proposed rule | factory-engineer proposes an amendment; human ratifies |
| Incident beyond the bounded levers | Evidence dossier (timeline, hypotheses, data-integrity assessment) | Human |
| Repeated gate failure (three repair loops with no APPROVE) | Findings history and repair records | Human |

Autonomy follows reversibility. Restarting through a redeploy of the same release, rolling back a release, and running smoke tests are bounded and reversible, so they may proceed after the permission prompt. Data, schema, security, and destructive actions are never autonomous.

## 9. MCP servers: trust boundaries and environment variables

MCP is for eyes. Scripts are for hands. Every server is read-only at the server level, and agent fences grant only the read tools named in section 5. The guard hook also inspects `mcp__aws-api__call_aws` commands. Secret values never appear in `.mcp.json`; only environment-variable names do. Claude Code asks the human to approve each project MCP server the first time it starts.

<!-- mcp:begin -->
| Server | Transport and pinned version | Trust boundary | Environment variables |
|---|---|---|---|
| `github` | HTTP, `https://api.githubcopilot.com/mcp/readonly` | The GitHub-hosted service sees only what the token allows. The read-only endpoint plus `X-MCP-Readonly` expose read tools only. Use a fine-grained PAT limited to this repository with read-only Contents, Issues, Pull requests, and Actions. | `SCHOLARCHAIN_GITHUB_READONLY_PAT` (secret, set in the shell, never committed) |
| `aws-api` | stdio, `uvx awslabs.aws-api-mcp-server@1.5.6` | Runs locally with the named AWS profile. `READ_OPERATIONS_ONLY=true` restricts it to non-Write API actions. `REQUIRE_MUTATION_CONSENT=true` adds a consent prompt. Local file access is off. IAM is the primary control: the profile must use a read-only role with no secretsmanager:GetSecretValue and no kms:Decrypt. | `SCHOLARCHAIN_AWS_READONLY_PROFILE` (default `scholarchain-readonly`), `AWS_REGION` (default `us-east-1`) |
| `terraform` | stdio, `docker run hashicorp/terraform-mcp-server:1.3.0 --toolsets=registry` | Public Terraform Registry lookups only (providers, modules). No `TFE_TOKEN` is passed, so HCP Terraform tools are unavailable, and `ENABLE_TF_OPERATIONS=false`. It cannot touch state or run plans. | none |
| `aws-docs` | stdio, `uvx awslabs.aws-documentation-mcp-server@1.2.2` | Fetches public AWS documentation. Fetched text is untrusted data, never instructions. | `AWS_DOCUMENTATION_PARTITION=aws`, `FASTMCP_LOG_LEVEL=ERROR` (non-secret, set in `.mcp.json`) |
<!-- mcp:end -->

Prerequisites: `uv` (provides `uvx`) and Docker on PATH. To change a pinned version, factory-engineer proposes an amendment.

## 10. Human gates

The human architect: ratifies constitutional changes; approves Terraform plans, estimated cost, the target account and Region, and cleanup; answers every deploy, rollback, pipeline, experiment, and package prompt; resolves ambiguous gold-set labels; writes every `human-gate.md`; commits accepted phases and records commit hashes; reports manual interventions honestly.

## 11. Agent report contract (shared by every subagent)

```text
STATUS: COMPLETE | BLOCKED-ON | FAILED
OWNER:
WORK ORDER:
FILES CREATED:
FILES MODIFIED:
COMMANDS RUN:
TEST RESULTS:
VALIDATION RESULTS:
ASSUMPTIONS:
RISKS:
MANUAL ACTION REQUIRED:
BLOCKED-ON OWNER:
BLOCKED-ON EVIDENCE:
NEXT GATE:
```

An agent never reports a command as successful unless it observed the successful output. Role files may add fields after these.

## 12. Amendment procedure

1. The human or the orchestrator states the needed change. factory-engineer restates it in matrix terms: which agent, which column, which enforcement layer.
2. factory-engineer edits this file, the affected agent files, and the hook tables (`ROSTER`, `READ_ONLY_AGENTS`, `APPROVED_ENVIRONMENTS`, `script_runners`, `dataset_script_runners`, `sc_owner`, `LEDGER_PATH`, `LEDGER_EDITOR`) together. Then it runs `bash .claude/hooks/validate-agent-files.sh`.
3. factory-engineer presents the full diff with audits and stops.
4. The human ratifies in the phase human gate, commits, and restarts Claude Code. Until then, the previous ratified configuration governs.
