The factory-engineer has finished Phase 1. Nothing is committed, and the changes wait on your ratification.

I re-ran its main checks myself:

bash .claude/hooks/validate-agent-files.sh prints RESULT: PASS with exit code 0.
There are 18 agent files and factory-engineer.md is unchanged.
All 6 hooks are staged with the executable bit set (100755), and nothing else is staged.
The temporary hook exception it used to write docs/agent-runs/README.md has been removed.
What it created: 33 new files. These are CLAUDE.md, docs/agents.md (the copied ownership table plus 8 refinements for this repo), 17 agent files, 5 commands, settings.json, .mcp.json, 6 hooks and docs/agent-runs/README.md. It found the course spec at Downloads/ScholarChain-Readme-Desc-Demo.md. The work order didn't say where the spec was.

Check before you ratify:

The new rules are already being enforced, before you ratified them. Claude Code picked up .claude/settings.json as soon as it was written. The new hooks and permissions now apply to this session too, which goes against "nothing takes effect before ratification".

It added restrictions the work order didn't ask for:

The main session may only write to docs/agent-runs/**.
Built-in subagents are read-only.
Writes outside the repo are blocked, which also turns off Claude's memory files.
disableBypassPermissionsMode stops you from skipping permission prompts, even when you choose to.
Your Phase 0 rule says any narrowing needs your approval, so these are listed as ratification items. Look at them closely.

Line endings could break every hook. Git is set to core.autocrlf=true, so the hooks can get Windows line endings on checkout and then fail in bash. It suggests running git config core.autocrlf input now, and having build-engineer add a .gitattributes with *.sh text eol=lf in Phase 2.

The full diff is in a file, not in the report. It's 2,428 lines, saved at scratchpad\full.diff (sha256 d81f370e…). That breaks its own report contract, and it says so.

Gaps it reported in the guards:

The dangerous-command guard matches command text, so an encoded command such as base64 … | bash can slip past it.
The ownership hook only covers the Edit and Write tools, not Bash.
The ownership table exists in two places, and only the owner names are checked against each other.
Setup you'll need for the MCP servers:

Install uvx (it's missing).
Set SCHOLARCHAIN_GITHUB_READONLY_PAT.
Create an AWS profile named scholarchain-readonly.
It asks for 9 decisions: the 8 refinements, every tool grant, the narrowings, dev as the only approved environment, you applying Terraform yourself, the pinned MCP servers, the staged hooks, the line-ending fix, and the diff being delivered as a file.

Probes to run after you ratify and restart:

Probe 1: feature-implementer doesn't exist in this roster, so it uses service-engineer editing build.sbt instead. This is still waiting on your question to the instructor.
Probe 5: can wait until after Phase 2, or you can run it now with a throwaway Probe.scala file.
Probe 6: confirm all 18 agents are listed and the validator passes.
Extra probes it added: editing CLAUDE.md from the main session, aws s3 rb, and git push --force.
I haven't written report.md or human-gate.md, since you save those yourself. The agent's full report is the hand-back message above.