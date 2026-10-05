The biggest differerence I see in between the prompt and the report

description: a routing-grade description stating that it creates and maintains the agent system from scratch, prepares 

description: Use to create the ScholarChain agent system from scratch and to maintain it from a human-ratified authority matrix. It owns CLAUDE.md, docs/agents.md, .claude/agents/*, .claude/hooks/*, .claude/commands/*, .claude/settings.json, and .mcp.json, and prepares constitutional diffs to them. It never self-ratifies. Do not use it for application, data, build, infrastructure, experiment, or documentation work.


#2 - the agent should not be allow to narrow the matrix without human approval and ratification here
- You may narrow privileges without a matrix change. Report every narrowing in the diff report.

8. **Ratification request:** the exact decisions the human must approve.

## Effectiveness

No constitutional change takes effect until a human has ratified it **and** the session has been restarted. Until both have happened, the previous ratified configuration stays in force, and you must act as if your proposed changes do not exist.