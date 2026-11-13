Use the service-engineer agent to implement ScholarChain RESTful microservices in Scala 3.

Required services:

1. Query API
- POST /v1/query
- Validate request.
- Create query ID and idempotency record.
- Start synchronous or asynchronous Graph-RAG workflow.
- Return answer or job reference.

2. Jobs API
- GET /v1/jobs/{jobId}
- GET /v1/jobs/{jobId}/result
- Report state, timestamps, stage, errors, and result link.

3. Evidence API
- GET /v1/evidence/{evidenceId}
- Return provenance, source record, source URI, graph path or text pointer, and compact supporting content.

4. Graph exploration API
- GET /v1/graph/nodes/{nodeId}
- GET /v1/graph/nodes/{nodeId}/neighbors
- Bound depth, edge types, result count, and timeout.

5. Explain and audit API
- GET /v1/explain/{queryId}
- Return QueryPlan, stages executed, prompt versions, model versions, evidence IDs, score components, counters, and verification status.
- Do not return hidden chain-of-thought.

6. Operations endpoints
- GET /healthz
- GET /readyz
- GET /metrics when the deployment model permits it

Required HTTP behavior:
- JSON request and response contracts.
- Path-based API versioning.
- Request IDs and trace IDs.
- Idempotency-Key support for POST operations.
- Pagination or cursoring for graph neighborhoods.
- Timeouts, bounded retries, and circuit breakers.
- Typed error model.
- Authentication and authorization appropriate to the course deployment.
- No secrets or raw credentials in responses or logs.
- Thin routes; application logic belongs in services and ports.

Required tests:
- Successful synchronous query.
- Asynchronous query path.
- Invalid JSON.
- Unsupported query constraint.
- Missing evidence.
- Idempotent repeated request.
- Timeout and downstream failure.
- Unauthorized request.
- Health and readiness distinction.
- Full query-to-evidence lifecycle with fakes.

Run sbt check.
Report route table, status-code mapping, timeout policy, dependency graph, authentication method, and exact test counts.