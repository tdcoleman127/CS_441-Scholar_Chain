# OpenAlex works: schema note (source_type `openalex`)

Owner: dataset-engineer. Verified 2026-10-18 unless marked unverified.

## Snapshot layout (verified, https://help.openalex.org/download-all-data/openalex-snapshot)

- Bucket `s3://openalex/` (public, `--no-sign-request`), data under `data/`. The license and
  release notes are at the bucket root (`LICENSE.txt`: CC0 1.0 text).
- Two complete formats: gzip JSON Lines under `/data/jsonl/` and snappy Parquet under
  `/data/parquet/`. Downloading both doubles the transfer, so ScholarChain uses JSON Lines only.
- Each entity folder has `updated_date=YYYY-MM-DD/` partitions with `part_0000.*`,
  `part_0001.*`, and so on, up to 400,000 records each. A partition holds the records that last
  changed on that date. The works folder also has a `deleted_ids.csv.gz` deletion log.
- Manifests: a combined `manifest.json` per format and one per entity, with `record_count` and
  `content_length`. **No checksums are documented.** The manifest is written last, so a present
  manifest means the format is complete. The exact manifest paths are unverified. List the
  bucket at download time.

## Work fields

| Field | Status | Use |
|---|---|---|
| `id` | verified: URL form `https://openalex.org/W...` | Work ID, namespace `openalex`. Store the short form `W...` |
| `doi` | verified: "the canonical external ID for works"; URL form seen in practice | DOI linkage. Strip `https://doi.org/`, lowercase |
| `ids` | verified: "all known external identifiers ... as URIs where possible; keys for unknown IDs are omitted" | Extra IDs (`openalex`, `doi`, `mag`, `pmid` per the API record for 10.7717/peerj-cs.421) |
| `title`, `display_name` | verified (identical; nullable) | Title |
| `publication_year` | verified: integer | Year |
| `type` | verified: e.g. article, preprint, dataset, paratext | Work type |
| `authorships[]` | verified: "Authorship objects, each pairing an author with their institution(s) and role" | Author mentions |
| `authorships[].author_position` | unverified name; values first/middle/last observed through the API summary | Position |
| `authorships[].author.{id, display_name, orcid}` | unverified subfield names | Author ID (namespace `openalex`, `A...`), name, ORCID URL |
| `authorships[].raw_author_name` | unverified | Name as printed. May differ from `display_name` (Unicode normalization, initials) |
| `authorships[].institutions[].id`, `.ror` | unverified subfield names | Resolved institution links (namespaces `openalex`, `ror`). **Runtime-allowed with the work's `publication_year`** (HD-03-02) |
| `authorships[].institutions[].display_name`, `raw_affiliation_strings` | unverified | **Batch only** (HD-03-02): entity-resolution features; dropped before runtime stores |
| `authorships[].is_corresponding` | verified indirectly (`corresponding_author_ids` lists authors with `is_corresponding: true`) | Not used for graph edges |
| `referenced_works` | verified: OpenAlex IDs this work cites | Citation evidence |
| `updated_date` | verified: ISO 8601 UTC timestamp of the last change | Incremental updates |

Before hadoop-engineer writes the parser, verify the unverified subfield names against one
`part_0000.gz` from the pinned snapshot. That is a bounded download, so it needs approval.

## Linkage keys

| Key | Core namespace | Field |
|---|---|---|
| DOI | `doi` | `doi` (to DBLP `ee`, Crossref `DOI`, OpenCitations `doi:`) |
| OpenAlex work ID | `openalex` | `id` |
| OpenAlex author ID | `openalex` (but core researcher IDs take no namespace) | `authorships[].author.id` |
| ORCID | `orcid` | `authorships[].author.orcid`, used only as carried (HD-03-01) |
| PMID | `pmid` | `ids.pmid` |
| OpenAlex institution ID | `openalex` | `authorships[].institutions[].id` |
| ROR | `ror` | `authorships[].institutions[].ror` |

MAG appears in `ids` but has no core namespace and is not used.

## Human decisions (human decision, phase 03 human gate, 2026-10-18)

- **HD-03-01:** ORCID iDs only as OpenAlex carries them. No ORCID profile data.
- **HD-03-02:** only resolved institution links (OpenAlex institution ID or ROR) with a year may
  reach runtime stores. `raw_affiliation_strings` and institution display names are batch only and
  are dropped before Neptune, DynamoDB, S3 Vectors, and any runtime-facing export.
- **HD-03-04:** confirmation download approved in principle, not run: a few hundred works by DOI
  through `fetch-by-id.sh openalex` (cap 500) to confirm the unverified subfields above.
- **HD-03-05:** the snapshot pin stays open (`Q-openalex-snapshot-pin`).

## Quirks the sample exercises (`datasets/samples/openalex/openalex-works-sample.jsonl`)

- The DOI in uppercase (`10.5555/SC441.0001`) while DBLP and Crossref use lowercase. Comparison
  must ignore case.
- `raw_author_name` in Unicode NFD (`Jürgen`) while `display_name` is NFC (`Jürgen`).
- An ASCII-folded raw name (`Zoe Ibanez`) next to the accented display name.
- `doi: null`, `title: null`, and empty `authorships`.
- Title with HTML markup (`H<sub>2</sub>`).
- One truncated, malformed line (the 4th line).
