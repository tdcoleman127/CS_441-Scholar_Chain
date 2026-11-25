# Phase 14: deploy-engineer verification report

Assembled by the orchestrator from deploy-engineer's final report (2026-11-24, read-only verification after the human ran deploy.sh and smoke-test.sh). The full report is reports/deployments/2026-11-24-dev-phase-14.md.

```text
STATUS: COMPLETE
OWNER: deploy-engineer
WORK ORDER: Phase 14 deployment verification and report (read-only). The human ran deploy.sh and
smoke-test.sh (human-gate.md decision, line 3). No deploy, smoke, rollback, terraform, or AWS write
was run by the agent.

FILES CREATED:
- reports/deployments/2026-11-24-dev-phase-14.md
- reports/deployments/2026-11-24-dev-phase-14/deploy-dev.log (account ID replaced by <account>, 4 replacements)
- reports/deployments/2026-11-24-dev-phase-14/smoke-dev.log (0 replacements needed)
FILES MODIFIED: none. Nothing committed.

COMMANDS RUN (read-only, --profile scholarchain-readonly --region us-east-1):
ecs describe-services / list-tasks / describe-tasks / describe-task-definition (api:2, worker:2);
elbv2 describe-target-groups / describe-target-health; ecr describe-images (tag 0c827107...);
cloudwatch describe-alarms (state ALARM, metric and composite; prefix scholarchain-dev);
logs filter-log-events (service.started, ERROR, WARN). Git status, rev-parse, diff, check-ignore.
Guard blocks or failures, all rerun successfully: MSYS path conversion on the log group name
(rerun with MSYS_NO_PATHCONV=1); a combined ERROR|WARN filter (split into two); a sed and a heredoc
hit the 15 s guard deadline (replaced by a simpler sed and the Write tool).

TEST RESULTS: sbt check not rerun (marker: all tests passed 2026-11-23T06:34:36Z; no change under
modules, build.sbt, project, docker since e269427). Human smoke run: 9/9 PASS, RESULT status=PASSED,
REQUIRE_READY=1.

VALIDATION RESULTS (Phase 14 gate):
- Services healthy and ready: PASS. api ACTIVE desired 1 running 1, PRIMARY deployment COMPLETED,
  0 failed tasks; ALB target healthy; service.started logged; 0 ERROR and 0 WARN events.
  worker ACTIVE at desired 0 (intended).
- Digests match the commit: PASS. ECR tag, running task imageDigest, and deploy.sh all
  sha256:12049aababb263f53bffaae3eaa7d271cae18a15ce3c85fe7a94d2bfd8db94a2.
- Smoke evidence-backed on bounded fixtures: PASS (fixture mode, 7 synthetic researchers;
  evidence:v1:6bf64b1d...116c fetched with 200).
- No critical alarm: PASS. 0 metric and 0 composite alarms in ALARM; all 9 scholarchain-dev alarms OK
  (read 2026-11-25T02:14Z).
- Report archived: PARTIAL until committed. .gitignore:14 (*.log) ignores the two log copies; they
  need git add -f.

COMMIT SHA: 0c827107c5a0d8271619b013f81e5e9fa47b896a
PLAN CHECKSUM: 8ce269bf42fb54f925bef35a5c990ac2529ea4d7e85ec320f8343089b9fa3668 (env=dev)
TARGET: account ending 9340 / us-east-1 / dev / runtime mode fixture
REVISIONS: scholarchain-dev-api:2 (previous :1), task c6422cbb1170484daa5ed318d7b18b2c;
scholarchain-dev-worker:2 (previous :1), desired 0.
RELEASE RUN ID: run:v1:079cb5d32f20d399305c3f95b3c7030611f398011b92347effe139561078f7fc
service.started line: {"event":"service.started","port":8080,"mode":"fixture","researchers":7,"apiKeys":1,...}
ALARMS: none in ALARM.
ROLLBACK ACTIONS: none. Rollback base api:1, worker:1.

ASSUMPTIONS: ECS container health UNKNOWN (no container health check defined); the ALB check is
used as the health signal. The dynamodb-throttles alarm has no MetricName (likely metric math).
RISKS: deploy-dev.log lines 17-22 contain the local Windows profile path (verbatim copy). The ALB is
public on HTTP:80 (accepted in the gate). The 17/1/0 apply counts are the human's report; the agent
did not see the apply output. Runtime costs about USD 0.10-0.11/h while on (estimate).
MANUAL ACTION REQUIRED: commit the report and force-add the two log copies; turn the runtime off
between sessions; set runtime_mode back to aws after Phase 15.
BLOCKED-ON OWNER: none. BLOCKED-ON EVIDENCE: none.
NEXT GATE: human sign-off of Phase 14 in human-gate.md, then commit.
```
