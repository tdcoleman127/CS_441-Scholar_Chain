# Phase 13 work order (draft for docs/agent-runs/phase-13/prompt.md; the human saves it)

Human request (2026-11-22): "Yes, draft the human-gates and start Phase 13". Deadline: about 2 days.
Base: HEAD 71978f2 (Phase 12 accepted at cf4ce53). Spec: docs/project-description.md lines 1873-1954.

Orchestrator defaults applied (the human may override in human-gate.md):
- P13-D1. No GitHub Actions workflows. The scripts are the only reproducibility interface (optional in the spec).
- P13-D2. package.sh builds and checksums locally by default. Pushing to ECR is opt-in (`--push`) and needs a write-capable
  profile supplied by the caller. It never defaults to scholarchain-admin, and no agent runs it with an admin profile.
- P13-D3. archive-evidence.sh writes to a caller-chosen local directory (`--out`). An S3 upload is opt-in and uses the same rule as P13-D2.
- P13-D4. Two parallel infra-engineer runs with disjoint files, then one code-reviewer round on both:
  - Part A: scripts/check.sh and scripts/package.sh.
  - Part B: scripts/run-experiment.sh and scripts/archive-evidence.sh, plus gap fixes to plan.sh, deploy.sh, smoke-test.sh and
    run-bounded-pipeline.sh against the Phase 13 spec.

Spec work order (verbatim, project-description.md lines 1886-1945): see that file. Gate (lines 1948-1954):
- A clean checkout can run all non-cloud validation through the documented script.
- Terraform planning is reproducible and the plan checksum is archived.
- Deployment cannot proceed without the documented human approval.
- Smoke tests exercise a real bounded collaboration query.
- The same scripts are used in the README reproduction instructions and final demonstration.

Note: saved on 2026-11-23, after the Phase 13 runs had started (work order given in session on 2026-11-22).
