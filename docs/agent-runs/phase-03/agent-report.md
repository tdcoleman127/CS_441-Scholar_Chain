# Phase 03 agent reports

Work order: `docs/agent-runs/phase-03/prompt.md`, delegated as two separate work orders, run in parallel:

- dataset-engineer: `datasets/**`
- domain-engineer: `modules/core/**`

## Orchestrator verification (observed 2026-10-18)

- `sbt -batch check`: `[info] Passed: Total 121, Failed 0, Errors 0, Passed 121`, then `markTestRun: updated`, then `[success]`.
- `bash datasets/tests/run-tests.sh`: `TESTS passed=57 failed=0`. This run comes after dataset-engineer's last three edits, which closes the gap that agent reported.
- `git status`: new files exist only under `datasets/**`, `modules/core/src/**`, and `docs/agent-runs/phase-03/`. No other paths changed.
- Not rerun by the orchestrator: `datasets/scripts/validate-datasets.sh`. guard-dangerous blocks it (see the open routing items). The agent's last observed validator result was `RESULT PASS passed=220 failed=0 warnings=5`, from `python -E -B datasets/scripts/validate_datasets.py`.
- Cross-check: all five selected datasets (`dblp`, `openalex`, `crossref`, `opencitations`, `arxiv`) and the three excluded ones exist in the SourceType enum. Gap: IdNamespace has no `omid` or `pmid`, which the OpenCitations and OpenAlex linkage keys need.

## Open items for the human gate

1. **Graph-edge ID includes source evidence**, as the work order specifies. When evidence is added, the edge gets a new ID, which affects Neptune upserts. Decide now: keep it, or remove evidence from the formula (cheap now, a breaking version bump later).
2. **Guard collision** (owner: factory-engineer). guard-dangerous matches any path ending in `scripts/<name>.sh`, so it blocks `datasets/scripts/*.sh`. Running them after `cd datasets` was allowed, which is an inconsistency. This needs an amendment: either anchor the match at the repository root, or map the dataset scripts in docs/agents.md section 7.
3. **Line endings** (owner: build-engineer). `.gitattributes` does not pin `datasets/samples/**`, so `core.autocrlf=true` would break the sample checksums. Proposed fix: `-text` or `eol=lf`.
4. **IdNamespace** (owner: domain-engineer). Possibly add `omid` and `pmid`.
5. **Dataset decisions** (human): whether to ingest ORCID (privacy), Semantic Scholar terms, the cost of Crossref's requester-pays bulk file, whether affiliations reach the runtime stores, and approval of the bounded downloads needed to confirm the wire fields marked "unverified".
6. **Phase 02 carry-over** (owner: factory-engineer). Record `project/Versions.scala` as the dependency ledger in docs/agents.md section 4.

---

## Report 1: domain-engineer

```text
STATUS: COMPLETE
OWNER: domain-engineer
WORK ORDER: Phase 03 domain-engineer part. Worked only in modules/core/**.

FILES CREATED (modules/core/src/):
main/scala/edu/uic/cs441/scholarchain/core/: SchemaVersion.scala; ids/CanonicalHash.scala,
  ids/IdFormat.scala, ids/ResearcherPair.scala, ids/StableIds.scala; model/Enums.scala,
  model/Records.scala, model/RunManifest.scala, model/WireEnum.scala;
  validation/ValidationError.scala, validation/Validators.scala; json/Wire.scala;
  config/ConfigLoader.scala, config/ScholarChainConfig.scala
main/resources/scholarchain/core-reference.conf
test/scala/edu/uic/cs441/scholarchain/core/: Fixtures.scala, GoldenCatalog.scala,
  ids/StableIdsSuite.scala, validation/ValidationSuite.scala, json/WireContractSuite.scala,
  model/EnumWireSuite.scala, config/ConfigLoaderSuite.scala
test/resources/wire/*.json (20 golden fixtures, one per record type plus run-manifest)
test/resources/config/valid-v1.conf, unsupported-v99.conf, unversioned.conf, bad-values-v1.conf
FILES MODIFIED: none

COMMANDS RUN:
- sbt -batch core/compile: OK, no warnings
- temporary golden-generator suite writing to the scratchpad; output reviewed, copied, generator deleted
- independent sha256sum recomputation of the documented encoding: matched the Scala output and the FIPS "abc" vector
- sbt -batch core/test: the first run failed at test compile (munit Compare type error), fixed; the rerun had 121 tests with 0 failed
- mutation probe (disabled pair-order check, widened the confidence bound, disabled the year-window check): 6 of 121 failed; files restored, cmp identical
- sbt -batch fmt; sbt -batch check: exit 0
- Two Bash commands were blocked by guard-dangerous and rerun in allowed forms. testOnly is blocked, so core/test was used.

TEST RESULTS: CanonicalHash 6/6, EnumWire 13/13, IdFormat 3/3, ResearcherPair 4/4,
StableIds 16/16, ConfigLoader 19/19, Validation 27/27, WireContract 33/33.
"Passed: Total 121, Failed 0, Errors 0, Passed 121", zero [warn] lines.

VALIDATION RESULTS: each golden fixture equals Wire.encodePretty(fixture) byte for byte, decodes back
to an equal record, validates, and has schemaVersion == 1. Validation returns typed errors and never throws. It rejects:
missing or blank provenance and a non-run pipelineRunId; confidence <0, >1, NaN, or infinite; unsupported
schemaVersion (top level and nested); malformed IDs and IDs that do not match their formula; inverted pairs and self-pairs;
startYear > endYear (YearWindow, CoauthorEdge, ResearcherMetrics, GraphEdge, QueryPlan). Extra rules:
a claim with no evidence must be unsupported; claims may cite only evidence in the bundle; an overall "supported"
answer requires every claim to be supported; GraphEdge evidence IDs are non-empty, sorted, and distinct; CoauthorEdge
supporting works are sorted and distinct, and their count equals coauthoredWorkCount; a "match" outcome needs a candidate.
Import audit: only java.*, scala.*, upickle/ujson, com.typesafe.config, and core.
```

### Stable ID formulas

H(...) is SHA-256 (lowercase hex) over a length-prefixed encoding. Each field is NFC-normalized, UTF-8 encoded, and written as `<byteLen>:<bytes>`. The first field is a domain tag. A list is written as its count followed by its items. An absent optional is the empty string.

| ID | Formula |
|---|---|
| work | `work:<ns>:<normalized source id>`, ns ∈ doi, dblp, openalex, arxiv, s2, pwc; fallback `work:sc1:H("scholarchain:work:sc1", sourceDataset, sourceRecordId)`. DOI is lowercased with its prefix removed and must match `10.*/*`. arXiv has its `arXiv:` prefix removed and keeps its version. |
| researcher | `researcher:orcid:<ORCID>` when the ISO 7064 MOD 11-2 checksum passes; otherwise `researcher:sc<N>:H("scholarchain:researcher:sc<N>", anchorDataset, anchorRecordId)`, N = 1 |
| mention | `mention:v1:H(tag, workId, authorPosition, sourceDataset, sourceRecordId)` |
| coauthor edge | `coedge:v1:H(tag, low, high, startYear, endYear, policyVersion)` |
| chunk/profile | `chunk:v1:H(tag, entityId, sourceVersion, documentType, ordinal or "", textHash)`, textHash = `sha256:` + SHA-256 of the NFC UTF-8 text |
| graph edge | SUPERSEDED in the repair round (see below). Was `gedge:v1:H(tag, edgeType, from, to, policyVersion, list(sorted distinct evidenceIds))` |
| evidence | `evidence:v1:H(tag, evidenceType, subjectId, contentHash, sourceDataset, sourceRecordId)`; no run ID, so it is stable across reruns |
| match / bundle / plan / claim / answer | `match:v1`, `bundle:v1`, `plan:v1`, `claim:v1`, `answer:v1` (full table in the StableIds.scala Scaladoc) |
| run | `run:v1:H(tag, pipelineName, codeVersion, configFingerprint, count, sorted distinct (dataset, snapshotId, checksum)…, attempt)`; content-addressed with no timestamp, stored with the immutable RunManifest; a rerun increments `attempt` |

### Pair canonicalization rule

`ResearcherPair.of(a, b)` orders two well-formed researcher IDs by `String.compareTo`. The IDs are ASCII, so this is byte-wise lexicographic order. It requires `low < high` strictly, `of(a, b) == of(b, a)` holds, an equal pair is rejected as SelfPair, and a malformed ID as MalformedId. On the wire, records store `researcherLow` and `researcherHigh`, and an inverted pair is rejected as InvalidPairOrdering. COAUTHORED_WITH is the only undirected edge type, and `fromId < toId` is enforced for it.

### Configuration precedence

Highest wins:

1. **Environment variables:** an explicit table of 21 `SCHOLARCHAIN_*` names. An empty value counts as unset. Names not in the table are ignored.
2. **The versioned HOCON file:** named by `SCHOLARCHAIN_CONFIG_FILE` or passed explicitly. It must declare `scholarchain.config-schema-version` itself.
3. **The reference defaults:** the classpath resource `scholarchain/core-reference.conf`. It is not named `reference.conf`, so Typesafe never merges it implicitly.

The loader consults no system properties and no implicit `application.conf`. The environment cannot override the schema version. Loading returns `Either[List[ConfigError], ScholarChainConfig]`. The defaults are placeholders only, which a test enforces. Entity-resolution thresholds, the graph window and bridge parameters have no defaults, so loading fails closed when they are absent.

### Schema versions

- Wire records: current 1, supported {1}. Embedded value types carry no version.
- Config: config-schema-version 1.
- Researcher identity: sc1.
- Hashed ID formulas: v1.

### Enum wire values

- SourceType: dblp, openalex, opencitations, crossref, arxiv, orcid, semantic-scholar, papers-with-code, scholarchain-pipeline, scholarchain-runtime, human-curation
- MatchOutcome: match, non-match, needs-review, merge, split
- EvidenceType: graph-node, graph-edge, graph-path, source-record, text-chunk, metric
- CollaborationMetric: degree, weighted-degree, coauthored-work-count, new-coauthor-count, repeat-collaboration-rate, component-size, core-number, clustering-coefficient, betweenness-centrality, bridge-edge-count, pagerank
- QueryOperation: resolve-researcher, find-coauthors, collaboration-timeline, common-coauthors, shortest-path, researcher-metrics, find-bridges, community-members, vector-search, fetch-evidence
- VerificationStatus: supported, partially-supported, unsupported, contradicted, not-verified
- Also defined: MatchDecider, GraphNodeType, GraphEdgeType, VenueKind, DocumentType, IdNamespace

### Sample serialized record

From `coauthor-pair-candidate.json` (the full set is in `modules/core/src/test/resources/wire/`):

```json
{
  "schemaVersion": 1,
  "researcherLow": "researcher:orcid:0000-0002-1825-0097",
  "researcherHigh": "researcher:sc1:2288d5bc36f4eebcf91c1e7399f902b26ee76295b5936b58f6203e1321341600",
  "workId": "work:dblp:conf/kdd/CarberrySmith24",
  "year": 2024,
  "provenance": {
    "sourceDataset": "dblp",
    "sourceRecordId": "conf/kdd/CarberrySmith24",
    "pipelineRunId": "run:v1:40bb9cfda64123cbe54be17ae30f7340ee0958ff3d7c1a4056926fbb08069875"
  }
}
```

Wire conventions:

- Field names are camelCase.
- Every field is always written.
- `None` is written as `null`, and a missing key is rejected.
- A whole-number Double is written without a fraction.
- Unknown enum values are rejected.

### Assumptions and risks (domain-engineer)

- Runtime records use sourceDataset `scholarchain-runtime`.
- RunManifest is the root of provenance, so it carries no provenance field.
- MatchDecision is a shared envelope only; the entity-resolution policy stays in its own module.
- All sample identities are fictional. The ORCIDs are ORCID's published test iDs.
- No consumers exist yet. Any field or formula change after other modules adopt these records is a breaking schema change.
- The config binding table is fixed at 21 settings. RAG and service parameters are not yet included.

---

## Report 2: dataset-engineer

```text
STATUS: COMPLETE (guard routing item open)
OWNER: dataset-engineer
WORK ORDER: Phase 03 dataset-engineer part. Wrote only under datasets/**.

FILES CREATED (datasets/):
dataset-manifest.yaml
schemas/dblp.md, openalex.md, crossref.md, opencitations.md, arxiv.md
scripts/lib.sh, fetch.sh, fetch-by-id.sh, sample-extract.sh, record-sample-checksums.sh,
  validate-datasets.sh, validate_datasets.py, sources.tsv
samples/dblp/dblp-sample.xml, dblp-malformed.xml, dblp.dtd; samples/openalex/openalex-works-sample.jsonl;
  samples/crossref/crossref-works-sample.jsonl; samples/opencitations/opencitations-index-sample.csv;
  samples/arxiv/arxiv-oai-sample.xml; samples/SHA256SUMS
tests/run-tests.sh, tests/mutate_manifest.py
FILES MODIFIED: none (datasets/.gitkeep unchanged)

COMMANDS RUN:
- About 40 WebFetch calls for license, snapshot, size, and format facts only. Failures: dblp.org FAQ, copyright, and search
  (Anubis bot protection); opencitations.net/licensing and two Crossref/OpenAlex doc paths (404);
  Figshare HTML and ORCID support pages (403).
- record-sample-checksums.sh: SHA256SUMS for 7 files
- validate-datasets.sh (before the guard block): RESULT PASS passed=220 failed=0 warnings=5
- run-tests.sh: first run 53 passed and 4 failed (GNU grep 3.0 on MSYS aborts on grep -i -F -f, plus one
  wrong expectation); after switching the matching to awk and fixing the test, 57 passed, 0 failed
- after three cosmetic edits: bash -n OK on all 7 scripts; validate_datasets.py RESULT PASS 220/0/5
- blocked by guard-dangerous: validate-datasets.sh and fetch.sh all --plan ("scripts/<name>.sh is
  not in the script execution map")
- no full downloads, no curl data downloads, no AWS calls

TEST RESULTS: 57 passed, 0 failed. Covers manifest mutations the validator must reject, changed,
undeclared, and oversized samples, script syntax, checksum idempotence, fetch.sh plan, bound, checksum, and repair behavior,
fetch-by-id.sh caps and rate limits, and sample-extract.sh caps and the 64 KiB limit.
VALIDATION RESULTS: 220 passed, 0 failed, 5 warnings. The warnings are facts deliberately recorded as unknown:
DBLP uncompressed size and record count, the OpenAlex snapshot version and date, and the arXiv harvest size.
```

### Selected datasets

| source_type | Role | License | Snapshot | Size | Linkage keys |
|---|---|---|---|---|---|
| dblp | primary | CC0-1.0 | dblp-2026-10-03, DOI 10.4230/dblp.xml.2026-10-03, MD5 cbd329100ea1bbb2873fa2f6d414ea00 | 1.03 GB gz | dblp key, DOI and arXiv ID from `ee`, `author/@orcid`, person ID |
| openalex | enrichment | CC0 | unknown; pinned at download (quarterly) | Works about 615 GB gz | DOI, OpenAlex IDs, ORCID, PMID |
| crossref | enrichment | facts / CC0; abstracts excluded | live REST API, bounded by DOI | at most 500 works per run | DOI, ORCID, reference DOIs |
| opencitations | enrichment | CC0 | Figshare v8, 2026-09-08 | 36 GB zip | DOI, OMID, PMID, OCI |
| arxiv | enrichment | metadata CC0; e-prints not stored | OAI-PMH harvest, pinned by window | unknown | arXiv ID, DOI |

Excluded (recorded in the manifest):

- `orcid`: privacy decision needed.
- `semantic-scholar`: non-sublicensable API license.
- `papers-with-code`: the site redirects to Hugging Face, so no license could be verified.

### Assumptions and risks (dataset-engineer)

- The samples are synthetic, CC0, and written in each source's wire format. `dblp.dtd` is a verbatim CC0 file.
- Identities are fictional: Josiah Carberry and the 10.5555 test DOI prefix.
- Fields marked "unverified" in the schema notes need confirmation on bounded downloads.
- The validator needs Python 3.8+ with PyYAML. It runs with `-E`, not `-I`, because `-I` hides the user site-packages where PyYAML lives.
- Risks:
  - the guard collision
  - CRLF breaking the sample checksums
  - dblp.org bot protection may block fetch.sh
  - Crossref's requester-pays bulk file costs the project money

### Known unresolved dataset questions

1. OpenAlex: which quarterly release is current? Are keyless API calls still allowed? A key would have to come from Secrets Manager.
2. OpenCitations: the download page says "released 2026-07-28", but Figshare v8 is dated 2026-09-08. Which is the citable snapshot?
3. Crossref public data file: is requester-pays S3 or a torrent download acceptable?
4. Should the ORCID Public Data File be ingested at all? This is a privacy decision.
5. Should the Semantic Scholar terms get a legal review?
6. The unverified wire fields need confirmation on approved bounded downloads.
7. DBLP's uncompressed size and record count are unpublished.
8. Should affiliations reach the runtime stores? This is a privacy decision. (Closed by HD-03-02.)

---

# Repair round (2026-10-18)

The human answered the open items in the orchestrator session. These decisions still need recording in `human-gate.md`, which only the human writes:

| ID (manifest label) | Decision |
|---|---|
| (core) | Graph-edge ID: remove evidence from the hash |
| HD-03-01 | ORCID Public Data File excluded; ORCID iDs only as carried by other sources |
| HD-03-02 | Affiliations: institution level only at runtime (resolved ROR or OpenAlex ID plus year). Raw strings are batch only and dropped before Neptune, DynamoDB, S3 Vectors, and runtime exports |
| HD-03-03 | Crossref: REST by DOI only; no requester-pays bulk file |
| HD-03-04 | Confirmation downloads approved in principle for a later phase: one small bounded fetch per source. Not run |
| HD-03-05 | Semantic Scholar stays excluded; its legal review is deferred. OpenAlex pin, OpenCitations date, and DBLP size stay open |

## Orchestrator verification (observed)

- `sbt -batch check`: `[info] Passed: Total 137, Failed 0, Errors 0, Passed 137`, then `[success]`. The temporary `TmpGoldenGenSuite.scala` has been removed.
- `bash datasets/tests/run-tests.sh`: `TESTS passed=74 failed=0`.
- `git status` on CLAUDE.md, docs/agents.md, .mcp.json, and .claude: no changes (the amendment is not applied).
- `git apply --check docs/agent-runs/phase-03/evidence/phase-03-factory-amendment.patch`: OK.
- `docs/agent-runs/phase-03/evidence/probe-proposal.txt`: `PROPOSAL: 0 unexpected`.
- The orchestrator copied the evidence from the session scratchpad to `docs/agent-runs/phase-03/evidence/`: the amendment patch, `probe-baseline.txt`, and `probe-proposal.txt`.

## build-engineer (item 3): COMPLETE

- `.gitattributes`: added `*.py`, `*.tsv`, and `*.md` as `text eol=lf`. Added `datasets/samples/** -text -eol` as the last rule (last match wins), so sample bytes are never converted.
- `git check-attr` resolves as intended. `(cd datasets/samples && sha256sum -c SHA256SUMS)`: 7 of 7 OK. The CR-byte scan found no CR bytes.
- Manual action: commit `.gitattributes` with the samples, or before them.

## dataset-engineer (item 5): COMPLETE

- The manifest has a `human_decisions` block (HD-03-01 to 05), `decision_refs` on every dataset, per-dataset `affiliation_handling` with `drop_before`, `confirmation_download` marked `approved-in-principle-not-run`, a `namespace` on every linkage key, and Crossref made REST-only.
- Schema notes now have dated "Human decisions" sections.
- The validator and tests now check that the manifest follows these decisions: `RESULT PASS passed=344 failed=0 warnings=8`, and the tests went from 57 to 74 (74 passed).
- Stale after the other repairs: `Q-line-endings` (fixed by build-engineer) and `Q-linkage-keys-without-core-namespace` (settled by domain-engineer). Both still read "open" in the manifest. (Closed in Round 2; see below.)
- New human question: a byte bound for the DBLP confirmation download (`Q-dblp-confirmation-bound`). Also confirm that one OpenCitations shard (about 180 to 545 MB) counts as "one shard".

## domain-engineer (items 2 and 4): COMPLETE

- **New graph-edge formula:** `gedge:v1:H("scholarchain:graph-edge:v1", edgeType, a, b, policyVersion, startYear or "", endYear or "")`, with endpoints in canonical order for COAUTHORED_WITH. Evidence IDs are an attribute only. Test: edges that differ only in evidenceIds share an edgeId. The tag stays `v1` because the old formula was never released. The human should confirm this, or ask for v2.
- **New namespaces:**
  - `omid`: `work:omid:br/<digits>`
  - `pmid`: `work:pmid:<n>`, leading zeros stripped
  - `oci` deliberately has no namespace (a citation is a CITES edge). `normalizeOci` pins its sourceRecordId.
  - DBLP person and OpenAlex author IDs map through the sc1 researcher anchor.
- **Affiliation enforcement:**
  - AFFILIATED_WITH needs a resolved institution and a window.
  - Institution nodes must be resolved.
  - No "affiliation" node properties.
  - `CanonicalResearcher.institutionIds` must be resolved.
  - `AuthorMention.affiliationText` is batch only (Scaladoc contract).
  - New AffiliationPolicySuite, 10 tests.
- **Golden fixtures:** 4 of 20 changed, IDs and hashes only (graph-edge, evidence-bundle, answer-claim, answer-response).
- **Mutation probe:** 11 of 137 tests failed with the changes reverted. Files restored byte-identical.
- **Risks:**
  - `institutionIds` has no years.
  - `EvidenceItem.excerpt` has a free-text affiliation ban that is a Scaladoc contract only.
  - A work can have multiple namespaces; choosing the canonical one is entity-resolution or hadoop policy.
  - arXiv URL forms must be stripped by the parsers.

## factory-engineer (items 1 and 6): PROPOSAL STAGED, NOT APPLIED

The agent wrote a patch instead of editing in place. Hooks take effect on the next tool call, so in-place edits would have been live before ratification. Evidence: `docs/agent-runs/phase-03/evidence/`.

- **Amendment A:**
  - Script paths are resolved and anchored: repo-root `scripts/` keeps the section 7 map. A new section 7b map covers `datasets/scripts/`:
    - `validate-datasets.sh` and `record-sample-checksums.sh --check`: any agent, no prompt.
    - Checksum write mode and `sample-extract.sh`: dataset-engineer only.
    - `fetch.sh` and `fetch-by-id.sh`: dataset-engineer, ask (hook-level and settings).
    - `fetch.sh --full`: blocked for every caller.
  - Every cd, pushd, or `env -C` form that runs a script is blocked.
  - **Live-config finding:** with a persistent cwd of `datasets/scripts`, the current guard lets any caller run `fetch.sh`, including `--full` (baseline probes D20 and D22). This stays open until the patch is applied and Claude Code restarts.
- **Amendment B:**
  - `project/Versions.scala` is recorded as the ledger. dependency-updater may only Edit the version literals in it.
  - The hook checks this by blanking every literal and requiring the rest of the file to be byte-identical. New literals must be exact, with no `latest`, `+`, `SNAPSHOT`, or ranges.
  - Bash writes and value correctness rest on instructions plus code review.
  - The sbt, scalafmt, and image-tag bumps go to build-engineer as reports.
- **One-writer audit:** FAIL unless the human explicitly ratifies the split of `project/Versions.scala` (build-engineer owns structure, dependency-updater owns values). The alternative is that build-engineer moves the values to a separate file.
- **Checks:**
  - Validator: PASS on the mirror and on the live repo.
  - `bash -n`: OK on every hook.
  - JSON: parses.
  - Probes: 75 of 75 as expected. Mutation: 2 of 2 detected.
- **Ratification asks:**
  1. Staging the change as a patch.
  2. Amendment A items (a) to (e).
  3. The ledger split exception.
  4. Mirror-value reporting.
  5. Residual risks: `validate_datasets.py` and `run-tests.sh` are not guarded, and nested script calls are invisible to the guard.
- **After ratification (human):**
  1. `git apply docs/agent-runs/phase-03/evidence/phase-03-factory-amendment.patch`
  2. `bash .claude/hooks/validate-agent-files.sh`
  3. Commit and restart.
  4. Fresh code-reviewer pass and a probe rerun against the applied files.

---

# Round 2 (2026-10-18, after the human applied the amendment and restarted)

Work order (human): dataset-engineer closes the two stale manifest questions and records the DBLP limit and OpenCitations shard decision ("Keep the v1 version of the Graph-edge ID tag"; "DBLP test download limit can be increased for now"); factory-engineer reruns the probes against the applied files; code-reviewer reviews the whole phase 03 change set; the orchestrator runs sbt check.

## Orchestrator verification (observed)

- `sbt -batch check`: `[info] Passed: Total 137, Failed 0, Errors 0, Passed 137`, `markTestRun: updated`, `[success]`.
- After dataset-engineer finished: `bash datasets/scripts/validate-datasets.sh` → `RESULT PASS passed=361 failed=0 warnings=7`; `bash datasets/scripts/record-sample-checksums.sh --check` → `result=ok files=7`; `bash datasets/tests/run-tests.sh` → `TESTS passed=96 failed=0`.
- Copied factory-engineer's scratchpad output into `evidence/`: `probe-applied-live.txt` (75 `ok` lines, `APPLIED-LIVE: 0 unexpected`), `probe-mutation-1.txt` (1 unexpected), `probe-mutation-2.txt` (4 unexpected), `probe-mutation-3.txt` (1 unexpected), `probe-residual.txt`.

## dataset-engineer: COMPLETE

- Closed `Q-line-endings` and `Q-linkage-keys-without-core-namespace` as `owner-fix`, citing `git check-attr` output, the checksum check, and what it read in modules/core. It corrected the work order: core has no DBLP-person-specific or OpenAlex-author-specific code; those IDs become researcher IDs only if entity resolution chooses them as the sc1 anchor.
- New decision HD-03-06 quotes the human gate. DBLP confirmation limit: 1,200,000,000 bytes (worst-case 1.035 GiB = 1,111,322,788 bytes plus about 8% headroom). The download stays human-invoked only: `bash datasets/scripts/fetch.sh dblp --full --max-bytes 1200000000 --dest "$SCHOLARCHAIN_DATA_DIR"`. `Q-dblp-confirmation-bound` closed.
- The curl time limit was a fixed 600 s, which would cut off a 1.2 GB download. It is now the larger of 600 s and the max-bytes transfer time at 1 MiB/s (1145 s here), still overridable with `SC_CURL_MAX_TIME`. The byte cap and the default plan mode are unchanged.
- OpenCitations: one Figshare v8 zip counts as one shard (the agent's interpretation of HD-03-04). Chosen file: `2026-09-01T15_0-11.zip`, 180,939,784 bytes, md5 `642bf5f9f06c925cfdd298af9f8484ef`, added as a full-tier row in `sources.tsv`. Before this row existed, the manifest's `--full` command for OpenCitations would have downloaded nothing. `Q-opencitations-snapshot-date` stays open.
- 22 new tests and 11 new manifest mutations. The agent's test that reverts the changes on a scratch copy was denied by the permission system and not run. code-reviewer judged that most of the new tests would fail on the old code and about three are regression checks only.
- Still open: Q-openalex-snapshot-pin, Q-openalex-api-key, Q-opencitations-snapshot-date, Q-dblp-size, Q-unverified-wire-details; Q-semantic-scholar-license-review deferred.

## factory-engineer: COMPLETE (verification only, no edits)

- `git apply --reverse --check` passes for all 8 patched files. The normalized blob hashes equal HEAD plus the patch, so the applied files have not drifted from it.
- `bash .claude/hooks/validate-agent-files.sh` → `RESULT: PASS`. `bash -n` passes on all hooks; settings.json and .mcp.json parse as JSON.
- Probes against the live hooks: 75 of 75 as expected. D20 and D22 (`fetch.sh` started from cwd `datasets/scripts`) are now BLOCK, so that live-config finding is closed.
- Mutations: 3 of 3 caught. Correction: Round 1 did not record its second mutation, so only M1 reproduces a Round 1 check; M2 and M3 are new.
- New residual risk: copying or renaming `fetch.sh` outside a `scripts/` path gets around section 7b (`probe-residual.txt` R6 to R8). validate_datasets.py, run-tests.sh, and wrapper calls (such as `make`) remain unguarded.

## code-reviewer: REQUEST_CHANGES

1. MAJOR (factory-engineer): `guard-dangerous.sh` `dataset_script_check` (about lines 934 to 944) tests only literal words of the current segment. Three inputs get past the checks: `F=--full; bash datasets/scripts/fetch.sh dblp $F ...`, `xargs datasets/scripts/fetch.sh dblp < args.txt`, and a protected `--dest` passed through a variable. The ratified rule that `--full` is blocked for every agent falls back to the ask prompt. Verified by reading the code, not by running the hook.
2. MAJOR (domain-engineer): `Validators.scala:420-424` accepts `overallStatus = Supported` with an empty claims list and an empty evidence bundle, because `forall` is true for an empty list. No test covers this case.
3. MINOR (dataset-engineer): `sample-extract.sh:109` skips the 64 KiB cap when the `--out` directory does not exist yet. The test at `run-tests.sh:310-312` creates the directory first, so it misses this.
4. MINOR (orchestrator): this report was stale. Fixed by this section.

The reviewer ran `sbt -batch test` → 137/0. Its read-only fence did not allow it to run the dataset scripts.

---

# Round 3 repairs (2026-10-18/19)

## Orchestrator verification (observed)

- After the domain-engineer and dataset-engineer repairs, `sbt -batch check`: `[info] Passed: Total 143, Failed 0, Errors 0, Passed 143`, `[success]`.
- `validate-datasets.sh` → `RESULT PASS passed=361 failed=0 warnings=7`. Sample checksums → `ok files=7`. `run-tests.sh` → `TESTS passed=108 failed=0`. `datasets/samples` holds only the declared files.
- `git apply --check -v evidence/round3/phase-03-round3.patch`: checks guard-dangerous.sh and docs/agents.md with no error. The live constitutional diff is unchanged (8 files, +202/-21).
- Copied factory-engineer's Round 3 evidence to `evidence/round3/`: the patch, the probe files, validate-mirror.txt, mut/ (mutated hook copies, inert, never to be copied back), and probe3.py.

## domain-engineer (finding 2): COMPLETE

`Validators.overallStatusErrors`:
- zero claims: the answer may only be unsupported or not-verified;
- supported: non-empty, and every claim supported;
- partially-supported: at least one supported or partially-supported claim;
- contradicted: at least one contradicted claim.

Before the fix, 5 of the 6 new tests failed. Wire fixtures unchanged.

## dataset-engineer (finding 3): COMPLETE

- `sample-extract.sh` `resolve_out` resolves the nearest existing ancestor; unresolvable paths fail closed; the cap match ignores case; a directory `--out` is refused (a second defect it found).
- Before the fix the tests were 98 passed, 10 failed; after, 108 passed, 0 failed.

## factory-engineer (finding 1 plus copy-and-rename residual): PROPOSAL STAGED, NOT APPLIED

- Patch: `evidence/round3/phase-03-round3.patch`.
- Probes: live baseline 40 unexpected of 141; proposal 0 unexpected of 141. Mutations: 14 of 14 detected.
- Ratification asks are listed in its report: rule groups, the copy fence, global narrowings CMDWORD and LAUNCHWORD, and residual risks.

## code-reviewer re-review: REQUEST_CHANGES

Findings 2 and 3 are verified FIXED (`sbt test` 143/0). Finding 1: the reviewer's three Round 2 inputs are now blocked, judged by reading the code. New findings:

- **F1 CRITICAL (factory-engineer; human escalation):** the dataset-script protected-path check is a raw regex with no path normalization, in both the live hook (guard-dangerous.sh:934) and the patch (R3-PROTWHOLE). Example: `bash datasets/scripts/sample-extract.sh csv ... --out .claude/./hooks/common.sh`. settings.json auto-allows sample-extract.sh, so this would overwrite a hook file with no prompt. Overwriting common.sh would make guard-dangerous fail open. Worked out by reading the code; not executed. **This affects the live configuration, not only the patch.**
- **F2 MAJOR (factory-engineer):** `fetch.sh --full` still runs when a launcher starts a wrapper program: `find ... -exec env {} dblp --full`, `xargs -I % %`, or `xargs -a args.txt env`.
- **F3 MAJOR (factory-engineer):** `coproc`, `trap '...' exit`, and `flock` are not modelled by the section 10 parser, so `fetch.sh --full` falls back to a prompt. `Fetch.sh` with a capital F also gets past the case-sensitive settings deny rule.
- **F4 MINOR (factory-engineer):** R3-CMDWORD blocks `case ... *) ... esac` one-liners for every caller.
- **F5 MINOR (factory-engineer):** the docs/agents.md 7b text claims OUTFULL blocks more than it does: extensionless copies get past it.
- **F6 MINOR (dataset-engineer):** on Linux, a backslash in `--out` resolves differently from the path actually written, so the cap can be skipped.

Repair loop count since Round 1: 2 with no APPROVE (Round 2 review, Round 3 re-review). A third would trigger the "repeated gate failure" escalation (docs/agents.md section 8).

---

# Round 4 checkpoint (2026-10-25, in progress)

Human instruction: start the repair round; each part takes no longer than 30 minutes.

Human Step 0: the `sample-extract.sh` auto-allow rules are no longer in `.claude/settings.json` (orchestrator grep found no match). This is option (a) from next-session.md, and it closes the live F1 auto-allow path. It is not yet recorded under "Manual changes" in human-gate.md.

## dataset-engineer (F6): COMPLETE

- In sample-extract.sh, the path style comes from `uname -s`, with a test-only override `SC_TEST_PATH_STYLE`. On POSIX hosts an `--out` containing a backslash is refused (exit 2). The script writes only the resolved path that the cap checked. An `--out` that is an existing or dangling symlink is refused.
- Before the fix the tests were 112 passed, 12 failed (all 12 new cases, including the reviewer's exact input). After: 124 passed, 0 failed.
- Orchestrator re-run: `TESTS passed=124 failed=0`; `RESULT PASS passed=361 failed=0 warnings=7`; checksums `ok files=7`; `datasets/samples` holds only the declared files.

## factory-engineer (F1 to F5): RUNNING, time-boxed

- Work order: revise the Round 3 patch into a Round 4 patch, staged only, against the current settings.json, and probe every Round 3 reviewer input.
- The 30-minute time box was sent mid-run. The agent is to stage what it has, prioritize F1, and report each of F1 to F5 as fixed, partial, or not started.

## Remaining in Round 4

1. Copy factory-engineer's evidence to `evidence/round4/`, run `git apply --check`, run `sbt check`, and rerun the dataset checks.
2. Fresh code-reviewer pass on the whole phase-03 change set, including the Round 4 patch.
3. Human ratification. If the verdict is REQUEST_CHANGES again, docs/agents.md section 8 "repeated gate failure" applies (third loop).

## factory-engineer (F1 to F5): BLOCKED-ON its own evidence run (time box reached)

- It reports F1 to F5 all fixed in a staged patch (`scratchpad/r4/phase-03-round4.patch`, not yet copied). Rules: R4-PROTNORM, R4-LAUNCHSEL/REPL/OPTS, R4-COPROC/TRAP/ALIAS, R4-FULLANY, R4-ASKALL, R4-CASEPAT, R4-OUTFULLX. `git apply --check` is clean according to the agent.
- New findings:
  - NF1: Windows 8.3 short names (`CLAUDE~1/hooks/...`) get past the live section 9 protected-path writes. Present since Phase 01. The patch adds R4-SHORTNAME as a separate ratification item.
  - NF2: output options of programs the guard does not model (`curl -o .claude/hooks/x`) remain unfenced. Documented as a residual; not fixed.
- Live baseline: 109 unexpected of 256 probes, plus 10 unexpected in the side probe.
- Not yet observed on the final patch: the full probe set and the 17 mutation runs.
- Orchestrator check at the checkpoint: `probe-proposal.txt` has 77 lines with 0 UNEXPECTED so far, and the full set is about 280 cases. No `mut/*.txt` result files exist yet. The run is still in progress.
- Hook latency rises: about 4.7 s instead of 3.3 s for dataset commands. The hook timeout is 20 s.
- The agent ran about 82 minutes in total, past the 30-minute box.

## Human decision (2026-10-25): option 2, 30-minute limit per part

The guard patch is narrowed to F1 and NF1. The remaining bypass classes are recorded as named residual risks, to be handled individually later. The Round 3 patch and the large Round 4 patch will not be applied.

## factory-engineer, narrowed patch (about 26 minutes): staged, not applied

The agent's written report did not reach the orchestrator. Everything below was read from the evidence files.

- Patch: `evidence/round4/phase-03-round4-narrow.patch`, touching guard-dangerous.sh and docs/agents.md. LF line endings.
- Rules:
  - R4-PROTNORM (F1): when a command runs a dataset script, every word and every `=value` is resolved and normalized before the protected-path check.
  - R4-SHORTNAME (NF1): blocks `~<digit>` 8.3 short names.
- docs/agents.md names residuals RR-01 to RR-09.
- Evidence observed by the orchestrator:
  - `NARROW-PROPOSAL: 0 unexpected`;
  - `NARROW-REGRESSION: 0 unexpected`;
  - validator `RESULT: PASS`;
  - `MUT-R4-PROTNORM: 23 unexpected` and `MUT-R4-SHORTNAME: 4 unexpected`, so both mutations were detected;
  - `git apply --check` clean on both files;
  - the live constitutional diff is unchanged apart from the human's settings.json edit (8 files, +199/-21).
- Orchestrator `sbt -batch check`: `Passed: Total 143, Failed 0, Errors 0, Passed 143`, `[success]`.
- code-reviewer started on the narrowed patch plus F6, with a 30-minute limit.

## code-reviewer, Round 4 (narrowed patch plus F6): REQUEST_CHANGES

Judged against the option-2 decision. All guard conclusions come from reading the code.

- F6 (dataset-engineer): verified FIXED; no findings.
- RR-01 to RR-09 honestly cover the Round 2 and Round 3 bypass classes. The sample-extract auto-allow is gone, and the patched docs match it.
- R4-SHORTNAME blocks the 8.3 forms; `HEAD~1` git forms still pass.
- **F1 MAJOR (factory-engineer):** R4-PROTNORM splits words at quote characters and treats `\` as a path separator, whereas bash joins quoted parts and drops the backslash. Bypass inputs:
  - `--out .cl'aude'/hooks/common.sh`
  - `--out .cl\aude/hooks/common.sh`
  - `--out docs/agent''-runs/phase-03/x.csv`
  - `SCHOLARCHAIN_DATA_DIR=docs/agent''-runs ...`, which contradicts the RR-03 text
  - the glob `.claud?/hooks/...`
  
  These are not covered by any RR. They are MAJOR, not CRITICAL, only because the human is now always prompted for sample-extract and fetch. Proposed fix: POSIX shlex tokenizing that fails closed on a parse error, testing each token with its quotes removed, and failing closed on glob characters.
- **F2 MINOR (factory-engineer):** the R4-SHORTNAME docs text overstates in one place (it blocks only operands that could reach protected paths) and understates in another (the inline check scans the whole command, so `git diff HEAD~1 | python -c ...` is now blocked for every caller).

Repair loops without an APPROVE: 3 (Round 2, Round 3, Round 4). docs/agents.md section 8 "repeated gate failure" now applies: the human decides. No further repair round was started.

## Human decision (2026-10-26): option 2, the quoting bypass accepted as RR-10

## factory-engineer, final patch (text only, about 3.5 minutes): staged, not applied

- Patch: `evidence/round4/phase-03-round4-final.patch`. It supersedes the Round 3 patch, the full-scope Round 4 patch, and the narrow patch.
- It adds RR-10 (quote, backslash, and glob spellings get past R4-PROTNORM). It corrects the R4-PROTNORM paragraph, RR-03, the enforcement-limits paragraph, and the R4-SHORTNAME section 4 text and comments.
- Orchestrator check:
  - Diffing the narrow and final patches shows the only non-comment changes are docs/agents.md text.
  - `git apply --check` is clean on both files.
  - `validate-final.txt`: `RESULT: PASS`.
  - The narrow-round probe and mutation evidence stands, because no code line changed.

## code-reviewer, final check: APPROVE

- No guard code changed: every changed guard-dangerous.sh line is a comment.
- RR-10 covers all five Round 4 F1 inputs. The R4-PROTNORM, RR-03, and R4-SHORTNAME text is accurate for the named cases.
- One non-blocking MINOR, owned by factory-engineer: the R4-SHORTNAME docs and comment understate two over-blocking effects.
  - `cp a.txt /tmp/x~1/b` is blocked, because nonliteral() treats `~` as non-literal.
  - Any variable or glob write operand or redirect target in a command that mentions `HEAD~1` is blocked for every caller, for example `git diff HEAD~1 > "$OUT"`.
  - No security impact; fold the wording into a later docs pass.

Phase 03 is ready for human ratification of the final patch.
