Purpose:

Attack the implementation before the cloud bill and grading process do it for you.

Why:

Implementation agents optimize for satisfying their work orders. An independent test agent and a read-only reviewer are needed to search for assumptions, missing edge cases, ownership violations, security failures, and untested behavior.

Use this work order for testing:

```text
Use the test-engineer agent on the complete ScholarChain repository produced through Phase 10.

Add adversarial tests without modifying production code.

Required categories:
- Malformed and truncated DBLP XML.
- Unicode normalization and confusable characters.
- Duplicate and conflicting DOI records.
- Common author names and reordered author lists.
- Very large blocking groups.
- Coauthor edges with unresolved or conflicting researcher endpoints.
- Empty and disconnected graphs.
- Distinct-degree, component, shortest-path, bridge, and k-core known-graph cases.
- Large-team pair expansion and common-name researcher-resolution cases.
- Missing topic, institution, abstract, or researcher-profile metadata.
- Duplicate vector keys and stale model versions.
- Prompt injection in title, abstract, and full-text chunks.
- LLM malformed JSON and fabricated identifiers.
- Unsupported answer claims.
- Neptune timeout.
- DynamoDB conditional-write conflict.
- S3 Vectors throttling or transient failure.
- Replayed workflow and idempotency.
- API authentication, authorization, malformed bodies, and timeouts.
- Logging redaction.
- Configuration omissions.

Run all tests that do not require live AWS. Run bounded AWS integration tests only under the approved profile.
Leave any production defect exposed by a failing test. Report it to the owning agent instead of changing production code.
Report exact test counts, failures, coverage by requirement, and residual risks.
```

Use this work order for review:

```text
Use the code-reviewer agent to perform an adversarial read-only review of the complete repository and the full current diff.

Review in this order:
1. Correctness and data loss.
2. Contract and schema drift.
3. Artifact ownership violations.
4. Security, secrets, IAM assumptions, and injection risks.
5. Hadoop and Spark distribution mistakes.
6. Entity-resolution over-merging and unreported uncertainty.
7. Storage idempotency and provenance.
8. RAG grounding and unsupported claims.
9. API behavior and failure handling.
10. Tests that would pass even if the behavior were broken.
11. Documentation claims not supported by implementation.
12. Cost and cleanup risks.

Verify every candidate finding against the actual files before reporting it.
For each finding provide severity, file and line, concrete failure input, expected impact, and owning agent.
Return APPROVE or REQUEST_CHANGES. Do not edit any file.
```

Repair loop:

```text
1. Orchestrator groups findings by owner.
2. Each owning agent receives only its findings plus necessary context.
3. Agent fixes its owned artifacts and runs its gate.
4. Test engineer reruns relevant adversarial tests.
5. Code reviewer rereads the complete diff.
6. Repeat until APPROVE or human escalation.
```

Gate:

- No critical or major verified finding remains.
- Every production behavior has a relevant test.
- All tests pass under their declared profiles.
- No reviewer wrote production code.
- Repair records identify owners and rerun evidence.