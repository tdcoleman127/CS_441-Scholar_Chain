## Round N: <short title, e.g. "repair-02: read-only fence brace/glob fix"> (YYYY-MM-DD)

Work order: docs/agent-runs/phase-NN/<prompt file>
Agents run: <owner agent(s)>, then code-reviewer, then test-engineer (if any)
Snapshot reviewed: <patch, checkpoint, or diff range, e.g. repair-02-wip-cp04.patch or main..HEAD>

Reviewed:
- Files or diff: <paths>
- Reports: <report file names under docs/agent-runs/phase-NN/>
- Reviewer verdict: APPROVE | REQUEST_CHANGES (<report file>)
- Checks run and results (observed output only):
  - bash .claude/hooks/validate-agent-files.sh: <PASS/FAIL, counts>
  - bash -n <scripts>: <result>
  - sbt check: <PASS/FAIL, tests passed/failed>
  - Probes or other checks: <IDs and results, including negative results>

Decision: ACCEPTED | ACCEPTED WITH OPEN RISKS | REJECTED
Reasons:
- <why this decision>
Findings status:
- Closed: <IDs>
- Partially closed (documented): <IDs, where documented>
- Open: <IDs>

Approvals (only if they apply):
- Environment: <dev>
- Terraform plan checksum: <sha256>
- Cost bound: <$ amount / duration>
- Other exact values: <e.g. size bound, EMR capacity>

Manual changes:
- <change made by hand>, <exact command>, <why>
- (none) if there were none

Remaining risk:
- <ID> <CRITICAL|MAJOR|MINOR>: <one-line description>. Owner: <agent>. Fixed in: <phase/repair>. Interim mitigation: <if any>

Ratification statement (constitutional changes only):
I ratify the changes to <files> as of snapshot <name>, accepting the deviations and
remaining risks above, with <IDs> to be fixed in <phase/repair> before <next work>.

Next step: <next round, next phase, or restart required: yes/no>

Accepted commit: <40-character hash>  (filled in by the follow-up commit)
