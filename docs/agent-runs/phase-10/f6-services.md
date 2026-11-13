# Phase 10 F6, step 2: service-engineer switch to AwsRuntimeStores

Assembled by the orchestrator from the service-engineer report, 2026-11-12.

Orchestrator spot check (observed): a grep for `software.amazon.awssdk` in modules/services found 0 matches. `git diff --stat HEAD` is empty (no tracked file changed). `.claude/.last-test-run` was updated 2026-11-12 23:44:10 -0600, and no module source is newer.

STATUS: COMPLETE (service-engineer)

## Changes (modules/services only)

- Created test/.../AwsCompositionSuite.scala (6 tests).
- Main.scala:
  - Removed the S3ObjectStore, DynamoDbKeyValueStore, and AwsClients imports, and the three `software.amazon.awssdk` imports.
  - `AwsComposition.openAwsStores = cfg => AwsRuntimeStores.fromConfig(cfg).map(s => RuntimeStores(s.objects, s.keyValue, s))`.
  - `dependencies(env, open = openAwsStores)`. The `open` seam exists because the AwsRuntimeStores constructor is private. Production always passes storage's factory.
  - When the exports fail to load, the handle is closed and only fixed text is reported (`release exports failed to load for SCHOLARCHAIN_RELEASE_RUN_ID`). The reader's error string, which could quote SDK exception text, is no longer echoed.
- ServicesApp.scala:
  - `Dependencies` gains `resources: List[AutoCloseable] = Nil`, and the `ServicesApp` constructor gains `resources`.
  - `ServicesApp.close()` closes the scheduler and pools, then each resource. A throwing close does not stop the others.
  - The shutdown hook runs `server.stop(5)` and then `app.close()`.
  - Fixture mode has `resources = Nil`.
- TestKit.scala and OpsAndLoggingSuite.scala: `ThrowingWorkflow` has no constructor argument, which clears an unused-parameter warning. Behaviour is unchanged.
- New type: `RuntimeStores(objects: ObjectStore, keyValue: KeyValueStore, handle: AutoCloseable)`.

## Evidence (implementer-observed)

- `sbt -batch fmt services/test`: 61/61 passed (one unused-parameter warning, then fixed).
- `sbt -batch check`: exit 0, all steps [success], no [warn] or [error], markTestRun updated.
- services: 61 total, 61 passed (was 55). The AwsCompositionSuite tests cover:
  - the handle is released on app close, even if an earlier resource throws;
  - the handle is closed when the exports fail to load, with no detail text leaked;
  - a factory failure gives storage's typed message;
  - the production opener validates before building any client;
  - a placeholder config never reaches the factory;
  - fixture mode carries no resources.
- Full build: 488 total, 486 passed, 0 failed, 2 skipped (storage live-AWS suite).
- Law-2 note: "fails without the change" is reasoned, not observed against the old code. The old code had no `open` seam or `resources`, never closed its clients, and echoed the reader's error text.

## Risks

- The export-load error no longer carries reader detail. If operators need it, a sanitized error type from storage or rag would be required.
- If the server fails to bind after the stores open, the process exits without an explicit `close()`; the JVM exit frees the clients.
- R-10-2 (no live Neptune adapter; AWS mode not ready), F10, and R-10-1 through R-10-8 are unchanged.

NEXT GATE: code-reviewer reviews the F6 storage and services diffs together (review-03).
