## Round 1: fast track (2026-11-01) — WO-00 dependency setup

Work order: docs/agent-runs/fast-track-phases-05-09.md, WO-00
Reviewed: git diff 766be11 -- build.sbt project/Versions.scala; sbt check PASS (246 passed, 0 failed); reviewer APPROVE
Decision: ACCEPTED WITH OPEN RISKS
- Fixed in session: none needed (0 CRITICAL, 0 MAJOR)
- Open: R-00-1, MINOR, services can import Bedrock/storage SDK classes via dependsOn(rag, storage); reviewer greps each phase, service-engineer/code-reviewer, Phase 09+
- Open: R-00-2, MINOR, url-connection-client only; streaming/async SDK calls need netty-nio-client, build-engineer on request
- Open: R-00-3, MINOR, AWS SDK releases daily; expect frequent bump proposals, dependency-updater
Deferred scope: Neptune client (neptunedata) and GraphFrames 0.12.3 available but not added by design
Manual changes: none
Accepted commit: 2b5768f73b2913235f0c40febde524e335fa8b8b


## Round 2: fast track (2026-11-02) — WO-05 entity resolution

Work order: docs/agent-runs/fast-track-phases-05-09.md, WO-05
Reviewed: untracked modules/entity-resolution/src/** vs 2b5768f; sbt check PASS (ER 19, core 145, contractTests 35, hadoop 66; 0 failed); reviewer REQUEST_CHANGES (F1, F2 MAJOR), repair-01, re-review APPROVE
Decision: ACCEPTED WITH OPEN RISKS
- Deviation from fast track: human chose to repair two MAJOR findings (plan repairs CRITICAL only)
- Fixed in session: F1 (work-level IDs counted as agreement for mention pairs; coauthors merged), F2 (deterministic accept on token-set proxy name; initials reject unreachable). Policy er-policy-v1 -> er-policy-v2, features er-features-v1 -> er-features-v2, no threshold values changed
- Open: R-05-1, MINOR, core MatchDecision cannot represent pair decisions, conflict outcome, or match path; ER emits local ResolutionRecord (erdecision:v1); domain-engineer, before Phase 09
- Open: R-05-2, MINOR, Hadoop Job 5 candidates carry no names or ID values (proxy name score, no initials, ORCID-only conflicts); hadoop-engineer per blocking spec, later phase
- Open: R-05-3, MINOR, token-set proxy name = 1.0 for permuted/repeated labels ("li li" vs "li") can be accepted on the probabilistic path; missing initials raise rather than lower confidence; entity-resolution-engineer, gold-set calibration
- Open: R-05-4, MINOR, policy UNCALIBRATED; no human-labeled pairs; metrics are synthetic-fixture arithmetic only; human labels ~50 pairs (Day 3), then evaluation
- Open: R-05-5, MINOR, ResolveMain reads one local file into one JVM; not an EMR-scale path; entity-resolution-engineer / spark-engineer, Phase 06
- Open: R-05-6, MINOR, ReviewItem lacks the ER run ID; policy file named er-policy-v1.conf holds er-policy-v2; entity-resolution-engineer, next ER change
Deferred scope: see WO-05 "Deferred" list
Manual changes: none
Accepted commit: 8e51f5306de84c513a0373f28de42fd72074ec8a
