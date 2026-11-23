# Phase 12 preparation 02: .gitignore backend and override entries

Owner: build-engineer. Work order: the human approved it on 2026-11-19, after part A raised the risk. Add
Terraform patterns so that `backend.hcl` (which holds the state bucket name, including the account ID)
and per-machine override files cannot be committed, while the `*.example` templates and
`.terraform.lock.hcl` stay tracked.

STATUS: COMPLETE

Change (Terraform section of `.gitignore` only): two comment blocks plus six patterns: `backend.hcl`,
`*.backend.hcl`, `override.tf`, `override.tf.json`, `*_override.tf`, `*_override.tf.json`. No `!`
re-include was needed, because these globs do not match `backend.hcl.example` or
`local_backend_override.tf.example`. No path under `infra/terraform/**` was touched.

Agent-reported verification: the `check-ignore` results matched every expectation in the work order.
`git ls-files -o --exclude-standard infra/terraform` still lists both lock files, every `*.example` and
every `*.tf` source. `sbt -batch check` exit 0: 760 tests, 758 passed, 2 skipped, 0 failed;
markTestRun updated.

Orchestrator checks (observed, 2026-11-19):
- `git check-ignore -v` on `envs/dev/backend.hcl`, `envs/dev/local_backend_override.tf` and
  `envs/dev/override.tf`: matched `.gitignore:55`, `:60` and `:58`, exit 0 (ignored).
- `git check-ignore` on `backend.hcl.example`, `local_backend_override.tf.example` and both
  `.terraform.lock.hcl` files: no output, exit 1 (not ignored).
- `git diff --stat -- .gitignore`: 14 insertions, 1 deletion against HEAD (prep-01 plus prep-02, both uncommitted).
- Tail of the scratchpad check.log: `[success] Total time: 289 s ... completed Nov 19, 2026, 7:49:18 PM`,
  with markTestRun updated.

Risks: any future file legitimately named `backend.hcl`, `*.backend.hcl` or `*_override.tf` would be hidden.
None exists today.

Review: fold into the Phase 12 code-reviewer pass together with prep-01 (human decision).
