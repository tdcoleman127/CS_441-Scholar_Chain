# DBLP XML: schema note (source_type `dblp`)

Owner: dataset-engineer. Verified 2026-10-18 against the sources listed under each fact.
"Verified" means read on the official page or file. "Unverified" means it comes from the
sample design or from general knowledge and must be confirmed on the real dump before a parser
depends on it (hadoop-engineer owns the parser).

## Files

| File | Notes | Evidence |
|---|---|---|
| `dblp-YYYY-MM-DD.xml.gz` (monthly release) | Pinned snapshot, persistent DOI `10.4230/dblp.xml.YYYY-MM-DD`, with an MD5 file next to it | https://drops.dagstuhl.de/entities/artifact/10.4230/dblp.xml.2026-10-03 |
| `dblp.xml.gz` (rolling) | Updated daily; its `.md5` changes daily. Not used as a pinned snapshot | https://dblp.org/xml/ |
| `dblp.dtd` | Needed to resolve symbolic character entities. Dated May 10, 2023 in its header. Committed as `datasets/samples/dblp/dblp.dtd` | https://dblp.org/xml/dblp.dtd |

## Encoding (verified, https://dblp.org/xml/docu/dblpxml.pdf)

- The header declares `encoding="ISO-8859-1"`, but the file contains only bytes below 128, that is,
  pure ASCII. Every non-ASCII character is a symbolic entity declared in the DTD (for example
  `&uuml;`) or a numeric reference (for example `&#321;` for U+0141).
- Parsers must load the DTD to resolve symbolic entities. The DTD declares 68 entities, covering
  Latin-1 plus `reg`, `micro`, and `times`. Anything outside that set is a numeric reference.
- Quirk: the Java SAX parser in the JDK has an entity-expansion limit that the full dump exceeds
  (the doc mentions "a limit for handling symbolic entities"). The fix is the hadoop-engineer's
  decision.

## Record elements (verified, dblp.dtd)

Root `<dblp>` children: `article | inproceedings | proceedings | book | incollection | phdthesis |
mastersthesis | www | person | data`.

- Attributes on every record: `key` (REQUIRED), `mdate`, `publtype`, `cdate` (implied). `article`
  also has `reviewid` and `rating`.
- Fields (any order, repeatable): `author editor title booktitle pages year address journal
  volume number month url ee cdrom cite publisher note crossref isbn series school chapter publnr
  stream rel`.
- `author` and `editor` attributes: `aux bibtex orcid label type` (`editor` has no `bibtex`). The
  `orcid` attribute dates from 2016-10-01 (CHANGES.txt).
- `title` may contain markup: `sub sup i tt ref`. The title text has to be built from mixed
  content, not read as a single text node.
- **There is no `doi` element.** DOIs appear as `ee` URLs (`https://doi.org/<doi>` in the sample).
  The DTD has no DOI field, which is verified. The exact `ee` URL forms in the live dump are
  unverified.

## Fields ScholarChain uses

| Field | Use |
|---|---|
| `@key` | DBLP record key. The authoritative work ID in namespace `dblp` |
| record element name, `@publtype` | Work type. `publtype="informal"` marks CoRR or preprint records (unverified convention; it appears in the sample) |
| `author` (ordered), `author/@orcid` | Author mentions with position. ORCID iD if present |
| `editor` | Editor mentions (not coauthorship) |
| `title`, `year`, `journal`, `booktitle`, `volume`, `number`, `pages` | Work attributes |
| `ee` | Links. DOI and arXiv extraction |
| `crossref` | Link from an inproceedings record to its proceedings record |
| `mdate`, `cdate` | Change and creation dates. Used for incremental-update detection |
| `www` with `key="homepages/..."` | Person records: all `author` children are names of one person (primary first, then aliases). A `www` holding only `crossref` marks a redundant person ID that is equivalent to another (verified, dblpxml.pdf) |

## Homonyms and person IDs (verified, dblpxml.pdf section "Homonyms and Synonyms")

- dblp "optionally append[s] a space character and a four digit number to names" to separate
  homonymous persons, for example `Chen Li 0002`. The number is part of the name string inside
  `<author>`. **Treat `Ada Sample`, `Ada Sample 0001`, and `Ada Sample 0002` as different dblp
  name identities.** The unsuffixed name may still hide several people (dblp: "In many cases
  homonyms remain undetected").
- Person records: `www key="homepages/<x>/<y>"` with title `Home Page` (the 2009 doc writes
  "HomePage"). Secondary `author` names are aliases. The person ID is the key without the
  `homepages/` prefix.
- Matching policy (how much to trust suffixes and aliases) belongs to entity-resolution-engineer.

## Linkage keys

| Key | Core namespace | Where | Normalization note (policy owner: entity-resolution-engineer) |
|---|---|---|---|
| DBLP key | `dblp` | `@key` | Case-sensitive, used as-is |
| DOI | `doi` | `ee` with `doi.org/` prefix | Strip the resolver prefix. DOIs are case-insensitive, so compare in lowercase |
| arXiv ID | `arxiv` | `ee` with `arxiv.org/abs/`; key pattern `journals/corr/abs-YYMM-NNNNN` | Unverified pattern on the live dump |
| ORCID iD | `orcid` | `author/@orcid` | Bare form `0000-0000-0000-000X`. Validate the ISO 7064 11,2 check digit. Used only as dblp carries it (HD-03-01) |
| DBLP person ID | none | `www/@key` | From `homepages/...`. No core namespace: core researcher IDs are hashed. Kept as a source record ID |

## Human decisions (human decision, phase 03 human gate, 2026-10-18)

- **HD-03-01, ORCID Public Data File excluded.** ORCID iDs come only from `author/@orcid` and
  `editor/@orcid` as dblp carries them. No ORCID profile data is fetched.
- **HD-03-02, affiliations institution-level only at runtime.** Person records can carry
  `<note type="affiliation">` (the DTD gives `note` an implied `type` attribute). These raw
  strings may be used in batch only, for example as entity-resolution features, and are dropped
  before Neptune, DynamoDB, S3 Vectors, and any runtime-facing export. dblp has no resolved
  institution IDs, so nothing affiliation-related reaches runtime from dblp.
- **HD-03-04, confirmation download approved in principle, not run.** It will confirm the `ee` URL
  forms, the CoRR key pattern and `publtype="informal"`, and the uncompressed size and record
  count. dblp's only verified artifact is the 1.03 GB release file.
- **HD-03-06, dblp confirmation-download limit raised for now** (phase 03 human gate, Round 2:
  "DBLP test download limit can be increased for now"). Bound: 1200000000 bytes, which covers
  1.03 GB read as 1.035 GiB at worst (1111322788 bytes) with about 8 percent headroom. It closes
  `Q-dblp-confirmation-bound`. The download is human-invoked only, because docs/agents.md
  section 7b blocks `fetch.sh --full` for every agent:
  `bash datasets/scripts/fetch.sh dblp --full --max-bytes 1200000000 --dest "$SCHOLARCHAIN_DATA_DIR"`.
- **HD-03-05.** DBLP's uncompressed size and record count stay open (`Q-dblp-size`).

## Quirks the samples exercise

- `datasets/samples/dblp/dblp-sample.xml` has 10 root records: the same work as a conference
  version and a CoRR version, a 24-author article, a record missing `year` and `ee`, a record
  missing the REQUIRED `key` with a non-numeric year `20x4` (well-formed, but invalid against the
  DTD), homonym names, symbolic and numeric entities (`&#7877;`), title markup, person records
  with aliases and a crossref alias.
- `datasets/samples/dblp/dblp-malformed.xml` is not well-formed: an undeclared entity
  (`&notdeclared;`) and a truncated final record. Note: expat skips undeclared entities when an
  external DTD is in use, so only the truncation makes it fail in Python. Xerces in Java reports
  the undeclared entity as fatal.
