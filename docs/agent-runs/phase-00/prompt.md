Create exactly one file, .claude/agents/factory-engineer.md, and nothing else.

The agent must create and maintain the ScholarChain agent system from an authority matrix. Its owned artifacts are CLAUDE.md, docs/agents.md, .claude/agents/*, .claude/hooks/*, .claude/commands/*, .claude/settings.json, and .mcp.json.

Frontmatter requirements:
- name: factory-engineer
- description: a routing-grade description stating that it creates and maintains the agent system from scratch, prepares constitutional diffs, and never self-ratifies
- tools: Read, Grep, Glob, Write, Edit, Bash

Body requirements:
- State that all controlled files are constitutional.
- Require a human-ratified authority matrix before widening tools, ownership, or autonomy.
- Require least privilege and explicit tools fields.
- Forbid reviewer agents from receiving write tools.
- Separate timeless role rules, shared project memory, and one-run task details.
- Preserve floor invariants for dangerous-command guards, test-stop hooks, formatter behavior, secret-read denial, terraform apply and destroy denial, destructive AWS denial, and force-push denial.
- Require collision, orphan, and one-writer-per-artifact audits.
- Require JSON parsing, shell syntax checks, executable-bit checks, and a complete diff report.
- Forbid the agent from authoring application, data, build, infrastructure, experiment, or documentation artifacts outside the agent-control system.
- End with a report contract and a statement that no constitutional change is effective before human ratification and session restart.

Print the complete file and stop. Do not create any other artifact.