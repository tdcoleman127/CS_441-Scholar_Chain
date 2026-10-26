# OpenCitations Index: schema note (source_type `opencitations`)

Owner: dataset-engineer. Verified 2026-10-18 unless marked unverified.

## Bulk dump (verified)

- "OpenCitations Index CSV dataset of all the citation data", Figshare article 24356626 version 8,
  published 2026-09-08T09:33:59Z, license CC0
  (https://api.figshare.com/v2/articles/24356626/versions/8).
- 94 zip files named like `2026-09-01T15_0-11.zip`. Figshare publishes a `computed_md5` and the
  byte size for each file. The download page lists the CSV dump at 36 GB zipped and 250 GB
  unzipped (https://download.opencitations.net/).
- **Discrepancy:** the download page says the Index dump was "Released on 2026-07-28", while
  Figshare v8 was published on 2026-09-08 with files dated 2026-09-01 to 2026-09-05. The snapshot
  is pinned to Figshare v8, the citable artifact, and the question is recorded in the manifest.

## Citation record fields (verified field names, https://api.opencitations.net/index/v2)

`oci, citing, cited, creation, timespan, journal_sc, author_sc`

| Field | Use |
|---|---|
| `oci` | Open Citation Identifier: two numbers joined by a hyphen, for example `06101801781-06180334099` (the API takes it without an `oci:` prefix) |
| `citing`, `cited` | Space-separated identifiers with prefixes, for example `omid:br/06101801781 doi:10.7717/peerj-cs.421 pmid:33817056` |
| `creation` | Citation creation date (the citing work's publication date). May be partial (`2025-02`) |
| `timespan` | Duration between cited and citing publication. ISO 8601 duration form in the sample; may be empty |
| `journal_sc`, `author_sc` | Journal and author self-citation flags (`yes` / `no`) |

Unverified: that the bulk CSV columns appear in exactly the API's field order with a header row.
Verify on one zip from v8 before parsing. That download is about 180 MB or more, so it needs approval.

## Linkage keys

| Key | Core namespace | Token |
|---|---|---|
| DOI | `doi` | `doi:<doi>`, compared in lowercase |
| OMID | `omid` | `omid:br/<n>`, the OpenCitations internal ID |
| PMID | `pmid` | `pmid:<n>` |
| OCI | none | `oci`. Identifies the citation itself; kept as the source record ID |

The `omid` and `pmid` namespaces exist in `IdNamespace` (domain-engineer, phase 03), and the
work ID kind allows them. The OCI deliberately has no namespace: `IdFormat.normalizeOci` pins it
as the `sourceRecordId` of a CITES edge's evidence. ScholarChain uses citations only as evidence links between works that already have a DOI, not
as coauthorship.

## Human decisions (human decision, phase 03 human gate, 2026-10-18)

- **HD-03-04:** confirmation download approved in principle, not run: one zip from Figshare v8,
  which dataset-engineer reads as "one shard" (94 zips of 180939784 to 543078628 bytes). The
  chosen file is the smallest listed one, `2026-09-01T15_0-11.zip`: 180939784 bytes, md5
  `642bf5f9f06c925cfdd298af9f8484ef`, `https://ndownloader.figshare.com/files/68274988`. It
  confirms the bulk CSV column order and header row. The download is human-invoked only:
  `bash datasets/scripts/fetch.sh opencitations --full --max-bytes 180939784 --dest "$SCHOLARCHAIN_DATA_DIR"`.
- **HD-03-05:** the snapshot date question stays open (`Q-opencitations-snapshot-date`).

## Quirks the sample exercises (`datasets/samples/opencitations/opencitations-index-sample.csv`)

- Several IDs in one cell (`omid` plus `doi` plus `pmid`).
- Mixed-case DOI (`doi:10.5555/SC441.0002`).
- A cited entity with only an OMID (no DOI).
- An empty `timespan` and a partial `creation` date.
- A short row with 2 of 7 columns (malformed).
