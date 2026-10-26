# Crossref works: schema note (source_type `crossref`)

Owner: dataset-engineer. Verified 2026-10-18 unless marked unverified.

## Access paths

- **REST API, bounded (the phase-03 default):** `https://api.crossref.org/works/<doi>` returns
  `{"message": {...work...}}`. Verified by fetching `10.5555/12345678`, a record from the
  Crossref test publisher ("Test accounts", member 7822, prefix 10.5555). Polite pool: send a
  `mailto` contact (https://www.crossref.org/documentation/retrieve-metadata/rest-api/).
  `fetch-by-id.sh crossref` stores the raw response, envelope included.
- **Public data file: not used** (HD-03-03, human decision, phase 03 human gate, 2026-10-18). For the record: the 2025 file is
  "approximately 200 GB", a tar with JSONL, on Academic Torrents and in the requester-pays S3
  bucket `api-snapshots-reqpays-crossref`, with no published checksums
  (https://www.crossref.org/documentation/retrieve-metadata/bulk-downloads/). The bounded REST
  API by DOI is the only Crossref access path.

The sample stores one work `message` object per line, the shape of the REST `message` object.

## Work fields (verified, https://github.com/CrossRef/rest-api-doc/blob/master/api_format.md)

| Field | Type | Use |
|---|---|---|
| `DOI` | String, required | DOI linkage. Case varies between records, so compare in lowercase |
| `title` | Array of String, required | Title (first element) |
| `author[]` | Array of Contributor, optional | Author mentions. Can be absent |
| `author[].family` | String, required in Contributor | |
| `author[].given` | String, optional | |
| `author[].ORCID` | URL-form ORCID, optional | ORCID linkage. Accept both `http://` and `https://` resolver forms (the scheme in practice is unverified) |
| `author[].authenticated-orcid` | Boolean, optional | ORCID provenance strength (match policy: entity-resolution-engineer) |
| `author[].affiliation[].name` | String | **Batch only** (HD-03-02): entity-resolution features; dropped before Neptune, DynamoDB, S3 Vectors, and runtime exports. No resolved institution ID was verified in Crossref affiliations |
| `author[].sequence` | `first` / `additional` | Seen on the live test record. Not in api_format.md |
| `issued` | Partial Date, required: earliest of `published-print` and `published-online`; only year required | Year |
| `container-title` | Array of String | Venue |
| `type` | Type ID from `https://api.crossref.org/v1/types` | Work type |
| `reference[]` | Array of Reference | Citation evidence. Entries may carry `DOI` or only `unstructured` text |
| `abstract` | Present in some records | **Not ingested.** Abstracts "retain the license under which they were published" |

## Human decisions (human decision, phase 03 human gate, 2026-10-18)

- **HD-03-01:** ORCID iDs only as Crossref carries them (`ORCID`, `authenticated-orcid`). No ORCID
  profile data.
- **HD-03-02:** affiliation names are batch only (see the table).
- **HD-03-03:** bounded REST API by DOI only; no bulk data file.
- **HD-03-04:** confirmation download approved in principle, not run: a few hundred DOIs through
  `fetch-by-id.sh crossref` (cap 500) to confirm the ORCID URL scheme, `sequence`, the
  affiliation object shape, and the `[[null]]` date quirk.

## Linkage keys

`DOI` (namespace `doi`), `author[].ORCID` (namespace `orcid`), `reference[].DOI` (namespace `doi`).

## Licensing of fields (verified, https://www.crossref.org/documentation/retrieve-metadata/)

Bibliographic metadata, including references, consists of facts and is not subject to
copyright. Crossref-generated data is CC0. Abstracts are under publisher or author copyright.

## Quirks the sample exercises (`datasets/samples/crossref/crossref-works-sample.jsonl`)

- Mixed-case DOI (`10.5555/SC441.0002`) against lowercase elsewhere.
- An invalid ORCID check digit (`0000-0002-1825-0098`) that must be rejected, not linked.
- A 24-author work, matching the DBLP large-team record.
- A work with no `author` and `issued.date-parts` of `[[null]]` (unverified quirk modeled on
  practice).
- A reference with only `unstructured` text.
- A truncated, malformed final line.
