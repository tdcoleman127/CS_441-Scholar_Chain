# Phase 13 review round 4: code-reviewer (very narrow: repair-04, deploy.sh)

Owner: code-reviewer (read-only). Delegated by the orchestrator on 2026-11-23. `bash -n scripts/deploy.sh` passed.

VERDICT: APPROVE (0 CRITICAL, 0 MAJOR, 0 MINOR). R3-1 FIXED. No new findings.

## Checks (reviewer)
1. **R3-1:**
   - The revocation search uses `--text` (:205).
   - The plain-text check (:182-195) refuses any NUL byte, which covers UTF-16 with or without a BOM, and refuses an FF FE or FE FF start.
2. **Gate listing versus searches:**
   - All three use the same HEAD and pathspec; `--text -e ''` lists every non-empty gate.
   - Empty gates are not listed. Observed: phase-13 to phase-17, harmless because they have no lines and no NUL.
   - Paths with spaces work. Quoted or coloured output fails closed at `git cat-file` or the `HEAD:*` check.
3. **NUL and BOM checks:**
   - Every failure path refuses (`|| die 3`, pipefail, the ERR trap).
   - The `|| true` at :192 only absorbs SIGPIPE from `od -N2`.
4. **No regression:** approval is still the strict anchored `-l -E`; revocation is still lenient.
5. **Nothing else new:** head, od and wc are added to the tool check and used.

## Reviewer notes (non-findings)
- The listing, approval and revocation each re-resolve HEAD instead of `$GIT_SHA`. A human commit during a deploy could make them look at different commits (older than repair-04; optional hardening by infra-engineer).
- A committed gate with a NUL byte blocks every deploy until it is fixed (fail closed by design).
- The header says `phase-*`, but the pathspec matches any subdirectory (older; only the human writes gates).
