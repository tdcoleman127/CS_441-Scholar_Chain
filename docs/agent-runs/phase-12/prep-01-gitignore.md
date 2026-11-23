# Phase 12 preparation 01: .gitignore Terraform entries

Owner: build-engineer. Work order: fix the hand-edit defects at the top of `.gitignore` and put all
Terraform patterns in one section, so `*.tfvars` and `*.tfplan` cannot be committed (2026-11-19).

STATUS: COMPLETE

Change (`git diff -- .gitignore`, verified by the orchestrator): the Terraform section gains
`*.tfvars` and `!*.tfvars.example` plus a two-line comment. The hand-edited lines at the top
(`*.tfvars`, `!*.tfvars.example`, a duplicate `*.tfplan`, `crash.log.` with a stray period, and
`.gitignore`, which ignored the file itself) are removed. Net: 5 insertions, 1 deletion.

Verification (agent-reported, verbatim exit codes):
- `git check-ignore -v infra/terraform/envs/dev/dev.tfvars`: matches `.gitignore:48:*.tfvars`, exit 0.
- `git check-ignore infra/terraform/envs/dev/dev.tfvars.example`: exit 1 (not ignored). With `-v`, git
  prints the negation match `!*.tfvars.example` and exits 0, which also means not ignored.
- `git check-ignore -v plan.tfplan`: matches `*.tfplan`, exit 0.
- `git check-ignore -v .gitignore`: exit 1 (not ignored).
- `sbt -batch check`: exit 0. 760 tests, 758 passed, 2 skipped, 0 failed. markTestRun updated.

Orchestrator checks:
- `git ls-files '*.tfvars' '*.tfplan'`: no output (no such file is tracked).
- The same check with `*.tfstate` in the pattern was blocked by guard-dangerous (secret-material
  floor invariant), as designed; no state file can exist yet because infra/terraform is empty.

Risks: `!*.tfvars.example` re-includes every `*.tfvars.example` file, so those files must hold dummy
values only (code-reviewer enforces this in the Phase 12 review).

Review: no separate code-reviewer run. The orchestrator proposes folding this diff into the Phase 12
code-reviewer pass (human decision).
