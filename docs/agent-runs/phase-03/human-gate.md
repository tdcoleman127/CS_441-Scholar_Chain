-------------------------------------
Phase 3: define contracts, configuration, and dataset manifests

[Round 1]

Focus: dataset-engineer and domain-engineer 

built the dataset manifest and the core data contracts. build-engineer fixed line endings for the sample files.

Reviewed:
- dataset-engineer and domain-engineer built the dataset manifest and the core data contracts. build-engineer fixed line endings for the sample files. 
- Read agent-report.md. Five datasets were chosen: DBLP (main), OpenAlex, Crossref, OpenCitations and arXiv. Core has 20 data records, stable IDs, validation and a config loader.
- Re-checked by the orchestrator: sbt check passed (137/137), dataset tests passed (74/74), the amendment patch applies cleanly.

Decision: ACCEPTED, WITH OPEN RISKS
- Closed: my dataset decisions HD-03-01 to 05 (no ORCID bulk file; only institution IDs, not raw affiliation text, at runtime; Crossref by DOI only; small test downloads approved for later; Semantic Scholar left out). Graph-edge IDs no longer include evidence. OMID and PMID ID types added.
- Partially closed: Papers with Code left out (no license found). A few dataset facts are still unknown (OpenAlex snapshot, OpenCitations date, DBLP size).
- Open, moved to Round 2: approve the guard and ledger changes, close two stale questions in the manifest, code review.

Manual changes:
- N/A

Remaining risk:
- R03-1, HIGH: until the patch is applied, an agent working inside datasets/scripts could start a full download -- to fix in Round 2
- R03-2, MEDIUM: project/Versions.scala will have two writers (build-engineer for structure, dependency-updater for version numbers) -- to fix in Round 2
- R03-3, LOW: the rule against raw affiliation text in evidence snippets is documented but not automatically checked -- to fix in Round 2

Accepted commit: see Round 2

[Round 2]

Focus: approve the factory-engineer agent changes, then a final code review.

Reviewed:
- Validator on applied files: PASSED, applied files matched the previous patch
- Probe rerun against applied hooks all PASSED (75/75 expected)
- Agent changes
    -- dataset-engineer: closed Q-line-endings, Q-linkage-keys-without-core-namespace, and
  Q-dblp-confirmation-bound (HD-03-06: DBLP limit 1,200,000,000 bytes, human-run only).
  OpenCitations shard: 2026-09-01T15_0-11.zip, 180,939,784 bytes. Validator 361/0/7,
  dataset tests 96/0.
    -- code-reviewer: REQUEST_CHANGES. F1 MAJOR guard bypass of fetch.sh --full (variables, xargs);
  F2 MAJOR empty answer could be marked supported; F3 MINOR sample-extract size cap skipped;
  F4 MINOR stale agent report.
- sbt check: 137 of 137 PASSED

DECISION: ACCEPTED -- but not on it's own, round 2 agent amendments will apply going forward, findings will be repaired in Round 3

Manual changes:
Applied the approved patch with git apply
-- git apply docs/agent-runs/phase-03/evidence/phase-03-factory-amendment.patch
-- bash .claude/hooks/validate-agent-files.sh

Remaining risk:
- R03-1: closed for literal commands (D20/D22); variable and xargs forms carried to Round 3.
- R03-2: closed. Ledger value check probes L01-L21 behave as designed.
- R03-3: open (later phase).

Accepted commit: see Round 3

[Round 3]

Focus: repair the Round 2 review findings and ratify a narrowed follow-up guard patch.
(agent-report.md "Round 3 repairs" and "Round 4" sections.)

Repairs done:
- domain-engineer: an answer with no claims can only be unsupported or not-verified (F2). sbt check 143/143.
- dataset-engineer: sample-extract size cap applies to new folders, refuses backslash paths on Linux and symlinked --out (F3, F6). Dataset tests 124/0.
- factory-engineer: three guard patches were tried. I chose to narrow the fix (option 2) rather than block every Bash form.

Focus: approve the factory-engineer agent changes, then a final code review.

Ratification:
- I approve evidence/round4/phase-03-round4-final.patch as written:
  - R4-PROTNORM: dataset-script commands that name a protected path after normalization (e.g. .claude/./hooks) are blocked.
  - R4-SHORTNAME: Windows short names (CLAUDE~1, GIT~1) count as protected paths. I accept that commands mentioning HEAD~1 together with inline python, xargs, or $VAR write targets are now blocked for every agent.
  - The updated docs/agents.md sections 4 and 7b.
- I accept residual risks RR-01 to RR-10 (docs/agents.md 7b), covered by my permission prompt, the role files, and review, to be handled individually in later phases. For RR-10, I will deny any sample-extract.sh or fetch*.sh prompt whose path arguments contain a quote, a backslash, or a glob character.
- Superseded and NOT applied: evidence/round3/phase-03-round3.patch, evidence/round4/phase-03-round4.patch, evidence/round4/phase-03-round4-narrow.patch.
- I ratify evidence/round4-rerun/phase-03-rr11.patch (sha256 00cf69c94f566043dd37f78ff94fb89f2450d34ba223a210f6cd439d08e856ad). It adds RR-11 to docs/agents.md 7b only. I accept RR-11 (P10, P10c, P10d, P10f, P10h: absolute quoted or escaped-space repository-root paths with .claude/./, .claude//, or docs/./agent-runs get past R4-PROTNORM) as covered by the permission prompt. I will deny any sample-extract.sh or fetch*.sh prompt whose path arguments contain a quote, a backslash, a glob character, or an absolute path into the repository.


Reviewed:
- Before git applying: 
-- code-reviewer: APPROVE on the final patch (no code change from the reviewed narrow patch). One MINOR non-blocking wording issue in the short-name docs.
-- Narrow probes: 0 unexpected (new rules and regressions); mutations 2 of 2 caught. Validator RESULT: PASS. git apply --check clean.

- After restarting: 
(used these lines to validate twice)
git apply docs/agent-runs/phase-03/evidence/round4/phase-03-round4-final.patch
bash .claude/hooks/validate-agent-files.sh
-- validator RESULT: PASS; regression probes 116/116; new-rule probes 49/50, with P10 accepted as RR-11; sbt check 143/143.

Decision: ACCEPTED

Manual changes:
- Removed the Bash(bash datasets/scripts/sample-extract.sh *) allow rules from .claude/settings.json, so every sample-extract run asks me first.
- Applied the final patch: 
-- git apply docs/agent-runs/phase-03/evidence/round4/phase-03-round4-final.patch 
-- bash .claude/hooks/validate-agent-files.sh
- git apply docs/agent-runs/phase-03/evidence/round4-rerun/phase-03-rr11.patch
bash .claude/hooks/validate-agent-files.sh

Remaining risk:
- R03-3 (LOW): raw affiliation text in evidence snippets not checked automatically.
- RR-01 to RR-10: guard bypass classes, prompt-only (see docs/agents.md 7b).
- MINOR: short-name docs understate two harmless over-blocks; fix in a later docs pass.

Accepted commit: 58ff073eb5d2c79a07c1ee4e91ebb6dc112e1995
-------------------------------------