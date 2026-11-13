## Phase 10 (2026-11-13): REST microservices

Work order: docs/agent-runs/phase-10/prompt.md
Built by: service-engineer, with storage-engineer for F6
Reviewed by: code-reviewer, three rounds
- Review 1: changes requested (2 major, 6 minor, 2 small)
- Review 2: approved after the fixes
- Review 3: approved the F6 change; F6 closed

Decision: ACCEPTED WITH OPEN RISKS

What was built:
- REST API in Scala 3 on the JDK's built-in HTTP server (no new dependency)
- Endpoints: POST /v1/query, job status and result, evidence lookup,
  graph node and neighbors, explain, /healthz, /readyz, /metrics
- Typed errors, request and trace IDs, Idempotency-Key, timeouts, retries,
  circuit breakers, rate limiting, and bounded in-memory records

Tests: sbt check passed. services 61 of 61 passed. Full build 488 tests:
486 passed, 0 failed, 2 skipped (storage live-AWS tests).

My decisions:
- Authentication design APPROVED: Bearer API keys stored only as SHA-256
  hashes, keys loaded from Secrets Manager, scopes per route, users can only
  read their own queries and jobs, /healthz and /readyz open without login.
  I accept the risks: keys do not expire until rotated, HTTPS depends on the
  load balancer, and there is no lockout after repeated failed logins.
- F6: services must not import the AWS SDK. I chose to have storage-engineer
  add AWS store factories (AwsRuntimeStores) instead of amending CLAUDE.md.
  Done and approved in review 3.
- Small review findings N1, N2, N3 are deferred, not fixed now. They are logged
  in docs/agent-runs/phase-10/follow-ups.md for Phase 11.

Still open (details in agent-report.md and follow-ups.md):
- R-10-1: query, job, and idempotency records are in memory per server, so
  more than one server needs a durable store (storage-engineer)
- R-10-2: no live Neptune connection yet, so AWS mode is never ready
  (storage-engineer, deploy-engineer)
- R-10-3 to R-10-8, N1 to N3, F6-R1 to F6-R5, F10, and the docs update
  are listed in follow-ups.md

Local run: not done (the guard blocks agents from running the app; I have
not run it myself yet)
Manual changes: none
Accepted commit: f78fb622cce39973cfb22070ae3809f266500f10
