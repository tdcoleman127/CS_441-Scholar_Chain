# ScholarChain architecture

Draft for Phase 17, written from repository state at commit `0c82710`. Every box below names the
source file or Terraform module it comes from. Measured behavior (latency, scaling, counts) is not
part of this document: PENDING (Phase 15) and PENDING (Phase 16).

## 1. Module graph (build.sbt)

The sbt projects are defined in `build.sbt` (lines 149-269 at `0c82710`). Allowed dependencies are
the table in `CLAUDE.md` ("Module dependency boundaries").

```mermaid
flowchart LR
  core["core<br/>modules/core"]
  hadoop["hadoop<br/>modules/hadoop"]
  er["entityResolution<br/>modules/entity-resolution"]
  spark["sparkAnalytics<br/>modules/spark"]
  storage["storage<br/>modules/storage"]
  rag["rag<br/>modules/rag"]
  services["services<br/>modules/services"]
  contracts["contractTests<br/>tests/contracts"]
  hadoop --> core
  er --> core
  spark --> core
  spark --> er
  storage --> core
  rag --> core
  rag --> storage
  services --> core
  services --> storage
  services --> rag
  contracts -.-> hadoop
  contracts -.-> er
  contracts -.-> spark
  contracts -.-> storage
  contracts -.-> rag
  contracts -.-> services
```

Dashed edges: the cross-module test project may depend on any module; nothing depends on it.

## 2. Build-time architecture: the agentic factory

How the code itself was produced (docs/agents.md sections 1, 4, 5, 10).

```mermaid
flowchart TB
  human["Human architect<br/>work orders, ratification,<br/>terraform apply, human-gate.md, commits"]
  orch["Orchestrator<br/>(main Claude Code session)"]
  subgraph agents[".claude/agents/*.md (18 subagents)"]
    impl["Implementers<br/>build, domain, dataset, hadoop, ER,<br/>spark, storage, rag, service, infra"]
    test["test-engineer<br/>tests/**"]
    rev["code-reviewer<br/>read-only"]
    ops["deploy-engineer, experiment-engineer,<br/>incident-responder"]
    doc["documentation-engineer"]
    fac["factory-engineer<br/>constitution"]
    dep["dependency-updater"]
  end
  hooks["Hooks (.claude/settings.json)<br/>guard-dangerous.sh (Bash, AWS MCP)<br/>guard-ownership.sh (Write/Edit)<br/>format-scala.sh, validate-agent-files.sh --hook<br/>verify-tests-ran.sh (Stop)"]
  evidence["docs/agent-runs/phase-NN/<br/>prompt, reports, reviews, repairs,<br/>human-gate.md, commit.txt"]
  human --> orch
  orch --> impl
  orch --> test
  orch --> rev
  orch --> ops
  orch --> doc
  orch --> fac
  orch --> dep
  agents -. every tool call .-> hooks
  impl --> orch
  test --> orch
  rev --> orch
  orch --> evidence
  human --> evidence
```

Subagents never invoke one another (no agent is granted the Agent or Task tool; the validator rejects
it). Findings go back through the orchestrator to the owning agent.

## 3. Build-time architecture: the batch data pipeline

Sources: `infra/terraform/modules/workflow` (Step Functions state machine
`scholarchain-<env>-pipeline`, SQS jobs queue and DLQ), `scripts/run-bounded-pipeline.sh` (stages),
`modules/hadoop` (Jobs 1-7, `DblpPipelineMain`, `SourcePipelineMain`), `modules/entity-resolution`,
`modules/spark` (`PartA`, `PartB`, `PartBMain`), `modules/storage` (loaders), and
`infra/terraform/modules/{data_lake,dynamodb,vectors,neptune}`.

Solid edges are automated by `scripts/run-bounded-pipeline.sh` and the state machine. Dashed edges
are steps that exist only as library code or as an optional Terraform state, with no automated
entrypoint at `0c82710` (details below the diagram).

```mermaid
flowchart LR
  ds["datasets/samples and snapshots<br/>DBLP, OpenAlex, Crossref,<br/>arXiv, OpenCitations"]
  raw[("S3 raw and samples buckets<br/>modules/data_lake")]
  sfn["Step Functions<br/>scholarchain-env-pipeline<br/>modules/workflow"]
  subgraph emrh["Transient EMR cluster: stage hadoop"]
    h1["Hadoop Job 1<br/>DBLP extraction"]
    h6["Hadoop Job 6<br/>publication teams, coauthor pairs"]
    h2["Hadoop Job 2<br/>source normalization"]
    h3["Hadoop Job 3<br/>exact identifiers"]
    h45["Hadoop Jobs 4+5<br/>blocking, candidate pairs"]
    h7["Hadoop Job 7<br/>graph export preparation"]
  end
  er["Entity resolution: ResolveMain<br/>OFF-CLUSTER, not a pipeline stage<br/>(writes the ER decisions directory)"]
  subgraph emra["Transient EMR cluster: stage spark-part-a"]
    pa["Spark Part A<br/>canonical researchers, graph,<br/>metrics, components"]
  end
  subgraph emrb["Transient EMR cluster: stage spark-part-b"]
    pb["Spark Part B<br/>paths, communities, bridge,<br/>k-core, export + manifests"]
  end
  cur[("S3 curated bucket<br/>run=run-v1 export")]
  ddb[("DynamoDB<br/>scholarchain-env-*")]
  vec[("S3 Vectors<br/>bucket + index")]
  nep[("Neptune<br/>optional: enable_neptune")]
  ds --> raw
  sfn --> emrh
  sfn --> emra
  sfn --> emrb
  raw --> h1 --> h6
  raw --> h2 --> h3
  h1 --> h3
  h2 --> h45
  h1 --> h45
  h45 --> h7
  h45 -. "Job 5 candidates, run by hand" .-> er
  h6 --> pa
  er -. "decisions directory passed as a stage input" .-> pa
  pa --> pb
  pb --> cur
  cur -. "storage loaders: library only, no main()" .-> ddb
  cur -. "embedding ingestion: NOT AUTOMATED<br/>(runEmbeddingIngestion always false)" .-> vec
  cur -. "NeptuneBulkLoad files: library only, no main()" .-> nep
```

Stage order as encoded in `scripts/run-bounded-pipeline.sh` (header lines 19-27): `hadoop` (step 1
`DblpPipelineMain`, Job 1 then Job 6; step 2 `SourcePipelineMain`, Jobs 2, 3, 4+5, 7), then
`spark-part-a` (`PartAMain`), then `spark-part-b` (`PartBMain`). These three are the only stages.
One stage runs per Step Functions execution, on its own transient cluster.

What is not automated at `0c82710` (verified in source):

| Step | State | Evidence |
|---|---|---|
| Entity resolution | Not a pipeline stage. `ResolveMain` (`modules/entity-resolution`) runs off-cluster on the Job 5 candidates; its decisions directory is then passed to the `spark-part-a` stage as an input | `scripts/run-bounded-pipeline.sh` lines 25-27 |
| Embedding ingestion into S3 Vectors | Not automated. The state machine has an optional `RunEmbeddingIngestion` state, but the script always sends `"runEmbeddingIngestion": false`, and the ingestion command is empty | `scripts/run-bounded-pipeline.sh` lines 37 and 625; `infra/terraform/modules/workflow/main.tf` line 21; `infra/terraform/envs/dev/dev.tfvars.example` line 108 (`embedding_ingestion_command = []`, "empty until an ingestion entrypoint exists in code") |
| DynamoDB loads and Neptune bulk-load files | Not automated. The loaders and `NeptuneBulkLoad` exist as library code; `modules/storage/src/main` has no `main` method, `extends App`, or `@main` entrypoint, and no pipeline stage calls them | search of `modules/storage/src/main` for `def main`, `extends App`, `@main` (no matches); `storage/graph/NeptuneBulkLoad.scala`, `storage/vectors/EmbeddingIngestion.scala` |

The related hand-off gaps (export layout, Neptune not queried at runtime, semantic context not
composed) are listed in [data-lineage.md](data-lineage.md#gaps-observed-while-tracing-not-resolved-by-this-draft).

## 4. Runtime architecture

Sources: `infra/terraform/modules/runtime` (ECR, ECS cluster, task definitions `api`, `worker`,
`embedding_ingestion`, ALB, HTTP listener, ECS services `api` and `worker`),
`infra/terraform/modules/network` (VPC, public, app, and data subnets, S3 and DynamoDB gateway
endpoints, optional interface endpoints), `infra/terraform/modules/observability` (alarms and
dashboard), `modules/services` (`Main`, `ServicesApp`, `Router`), and
`modules/rag/src/main/scala/edu/uic/cs441/scholarchain/rag/runtime/`.

```mermaid
flowchart LR
  client["Client<br/>Authorization: Bearer key"]
  alb["ALB (HTTP listener)<br/>modules/runtime"]
  subgraph ecs["ECS Fargate, app subnets"]
    api["api service<br/>services image @ git SHA"]
    worker["worker service<br/>same image"]
  end
  subgraph app["modules/services (inside the api task)"]
    router["Router<br/>auth, scopes, rate limit,<br/>validation, error model"]
    qs["QueryService<br/>idempotency, sync or async jobs<br/>(in-memory records)"]
    reads["EvidenceService, GraphService"]
  end
  subgraph ragp["modules/rag GraphRagPipeline"]
    plan["QueryPlanner"]
    resolve["ResearcherResolver"]
    retr["Retrieval<br/>graph facts, then semantic"]
    bundle["Evidence bundle"]
    synth["TemplateSynthesizer<br/>typed claims"]
    verify["ClaimVerifier V1-V6"]
  end
  s3[("S3 curated<br/>release exports")]
  ddb[("DynamoDB<br/>evidence bundles")]
  sm["Secrets Manager<br/>api-key digests (by ARN)"]
  cw["CloudWatch logs,<br/>alarms, dashboard"]
  client --> alb --> api
  api --> router --> qs --> plan --> resolve --> retr --> bundle --> synth --> verify
  router --> reads
  retr --> s3
  bundle --> ddb
  reads --> ddb
  sm -. injected as SCHOLARCHAIN_API_KEYS .-> api
  api --> cw
  worker --> cw
```

What is and is not wired at `0c82710` (verified in source):

| Component | State | Evidence |
|---|---|---|
| Graph facts from release exports (S3) | wired in `aws` mode | `Main.scala`, `AwsComposition.dependencies` calls `GraphExportReader.load` |
| Evidence bundles in DynamoDB | wired in `aws` mode | `AwsComposition.dependencies` passes `Some(stores.keyValue)` |
| Live Neptune queries | not wired; `UnavailableGraphStore` fails every call and readiness reports it | `Main.scala`, `object UnavailableGraphStore` |
| S3 Vectors semantic retrieval | code exists in `modules/rag` and `modules/storage`; not passed by `AwsComposition` (`semantic = None`) | `ServicesApp.scala` `Dependencies` defaults |
| Model-based synthesis | not wired; default `TemplateSynthesizer` | `ServicesApp.scala` `Dependencies` defaults; prompt files in `prompts/answer-synthesis/v1/` |
| Fixture mode | `SCHOLARCHAIN_RUNTIME_MODE=fixture` serves the synthetic `LocalFixture` release | `Main.scala`; Terraform `runtime_mode` (`infra/terraform/envs/dev/variables.tf`) |
| SQS consumer in services | none found in `modules/services/src/main` (no SQS reference); async jobs run in the in-process `workflow` worker pool | `ServicesApp.scala` (`WorkerPool("workflow", ...)`) |
| Optional OpenSearch tier | not implemented | no OpenSearch reference under `modules/` or `infra/` |

## 5. Deployment path

```mermaid
sequenceDiagram
  participant H as Human
  participant IE as infra-engineer
  participant CR as code-reviewer
  participant DE as deploy-engineer
  participant AWS as AWS (dev)
  IE->>AWS: scripts/plan.sh dev (read-only profile, saved plan, SHA-256)
  IE->>CR: plan summary
  CR-->>H: APPROVE or REQUEST_CHANGES
  H->>H: human-gate.md: APPROVED-DEPLOY-PLAN-SHA256 line, commit
  H->>AWS: terraform apply (outside Claude Code)
  DE->>AWS: scripts/deploy.sh dev (ask prompt): image at git SHA, new task revisions, roll services
  DE->>AWS: scripts/smoke-test.sh dev
  alt smoke fails
    DE->>AWS: scripts/rollback.sh dev (earlier SHA or revision)
  end
  DE-->>H: reports/deployments/... record
```

Phase 14 deviation (recorded in `docs/agent-runs/phase-14/human-gate.md`): the human ran `deploy.sh`
and `smoke-test.sh` personally, and deploy-engineer verifies read-only and writes the report. That
report is [reports/deployments/2026-11-24-dev-phase-14.md](../reports/deployments/2026-11-24-dev-phase-14.md)
(written after `0c82710`, not yet committed).

## 6. Threats and limits

See [threat-model.md](threat-model.md). Verified service limits and Region availability:
`docs/service-limits.md`, PENDING (not written in this draft).
