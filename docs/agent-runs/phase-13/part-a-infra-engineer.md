# Phase 13 Part A: infra-engineer (check.sh, package.sh)

Owner: infra-engineer. Delegated by the orchestrator on 2026-11-22, in parallel with Part B (disjoint files). Base 71978f2.
Agent-reported duration: about 33 min.

STATUS: COMPLETE

## Files
- Created: scripts/check.sh (429 lines), scripts/package.sh (481 lines). Nothing else modified.
- Build output (Git-ignored): `target/package/71978f28e92857ea4476fd276dce2a18cc0f93a4-dirty-20261123T043758Z/` (3 jars, SHA256SUMS, manifest.json). Local image `scholarchain-services:71978f28…-dirty`.

## check.sh (offline definition of done)
Gates:
1. validate-agent-files.sh
2. JSON validity of settings.json and .mcp.json
3. `bash -n` on hooks, scripts and dataset scripts
4. validate-datasets.sh
5. terraform fmt
6. terraform validate for bootstrap
7. terraform validate for dev. Gates 6 and 7 run `init -backend=false -lockfile=readonly`, with a temporary TF_DATA_DIR and the local provider cache used read-only. They never touch backend.hcl, tfvars, state or the real .terraform/.
8. `sbt -batch check`
9. working tree unchanged

Every gate runs even after a failure. Exit 0 OK, 1 FAIL, 2 usage, 3 INCOMPLETE (a skipped gate).

Runs (agent-reported):
- Run 1: FAIL at gate 9 only. Part B was editing scripts concurrently, so the gate worked as designed.
- Run 2 (`--skip-sbt`): INCOMPLETE, exit 3, as designed.
- Run 3: void. The agent stopped it after editing check.sh.
- Run 4, on the final file: OK.

Run 4 sbt totals: 160, 80, 73 (71 passed, 2 skipped), 70, 63, 83, 40 and 191 tests, 0 failed. That is 760 tests, the same as the Phase 11 baseline.

## package.sh
- Builds the Hadoop, Spark and services jars and the services image, tagged by the 40-character SHA.
- Refuses a dirty tree unless `--allow-dirty`, which adds a `-dirty` suffix.
- Writes a verified SHA256SUMS and `manifest.json` (schema scholarchain.package.v1, parsed as JSON before it is published).
- `--push` is opt-in:
  - It needs SCHOLARCHAIN_PUSH_PROFILE. There is no default, the read-only profile is refused, and so are static credentials.
  - The repository is looked up with describe-repositories.
  - Tags must be IMMUTABLE and are never overwritten.
  - The registry digest is verified after the push.
  - **`--push` was never executed.**
- Refusal tests all exited as designed: exit 2 for `--push --allow-dirty`, an unknown flag, no push profile, the read-only push profile, and `--out` inside the repo but not ignored; exit 3 for a dirty tree without `--allow-dirty`.
- A defect found and fixed during the run: `"${arr[*]}"` under `IFS=$'\n\t'` made package run 1's manifest invalid JSON. Fixed with a join helper and a mandatory JSON parse.
- After package run 2, the agent added cleanup of created directories when `--out` is refused. Only `bash -n` and the refusal path were exercised on that final version.

## Follow-ups (other owners; not edited)
1. deploy.sh header: call package.sh instead of `sbt dockerLocal` (infra-engineer, next pass).
2. README reproduction steps use check.sh and package.sh (documentation-engineer, Phase 17).
3. dockerLocal also tags a mutable `0.1.0-SNAPSHOT` image (build-engineer, optional).
4. S3 upload of the job jars is not implemented (owner decision).

## Orchestrator verification (observed, 2026-11-22)
- `sha256sum`: check.sh `663172dbc4860621bb26c17d575c9971f11253ed43e0becc461312974d5da733`, package.sh
  `b144b38897c9daf121b0a2e5d38dc26aa64eb4e5e28a2f49723168210ab24a30`. Both match the report. `bash -n` passes on both.
- Scratchpad check-run4.log: STEP 0-9 OK, `[check] RESULT status=OK git_sha=71978f28… failed=none skipped=none`.
- manifest.json sha256 `3f4c20a5761039fa072e2ee8485528109f6869a20e09c94c2b554f96e52db58e` matches. `target/package` is Git-ignored.
