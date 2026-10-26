# arXiv metadata (OAI-PMH): schema note (source_type `arxiv`)

Owner: dataset-engineer. Verified 2026-10-18 unless marked unverified.

## Access (verified)

- OAI-PMH 2.0 base URL `https://oaipmh.arxiv.org/oai` (it replaced `export.arxiv.org/oai2` in March
  2025). Identify reports `earliestDatestamp` 2005-09-16, `deletedRecord` persistent, and
  granularity `YYYY-MM-DD` (https://oaipmh.arxiv.org/oai?verb=Identify).
- Formats (`ListMetadataFormats`): `oai_dc`, `arXiv` (https://oaipmh.arxiv.org/OAI/arXiv.xsd,
  namespace `http://arxiv.org/OAI/arXiv/`), `arXivOld`, and `arXivRaw`. ScholarChain uses `arXiv`,
  which separates author name parts.
- Terms of Use: "no more than one request every three seconds" over a single connection, for all
  of a user's machines together. Do not store or serve e-prints (PDF or source) unless the license
  allows it. **ScholarChain harvests metadata only.**
  (https://info.arxiv.org/help/api/tou.html)
- Full harvests: make requests without a datestamp range. Incremental harvests: set `from` to
  the last response date and leave `until` unset. Resumption tokens expire daily
  (https://info.arxiv.org/help/oa/index.html).

## `arXiv` metadata elements (verified, arXiv.xsd)

Required `id`. Optional `created updated authors title msc-class acm-class report-no journal-ref
comments abstract categories doi proxy license`. `authors` holds one or more `author`, each with
a required `keyname` and optional `forenames`, `suffix`, and repeatable `affiliation`.

| Field | Use |
|---|---|
| `id` | arXiv ID, namespace `arxiv`. New style `YYMM.NNNNN`, old style `archive/YYMMNNN` |
| `authors/author` (ordered) | Author mentions with position. `keyname` = family name |
| `title` | Title. Can contain line breaks and indentation, so collapse whitespace |
| `doi` | DOI of the published version, when the submitter supplied one |
| `categories` | Space-separated subject classes |
| `license` | License URL of the **e-print**. Metadata itself is CC0 |
| `created`, `updated` | Version dates |
| `abstract` | CC0 as metadata. Used only as embedding text if rag-engineer chooses to |
| header `@status="deleted"` | Deleted record. No metadata element (OAI-PMH) |

Unverified: the OAI identifier prefix `oai:arXiv.org:` used in the sample headers, because the
Identify response carried no oai-identifier description. Also unverified: whether author names
can contain TeX accent escapes such as `M\"uller`. The sample includes one so that the parser
decides on a policy explicitly.

## Linkage keys

arXiv ID (namespace `arxiv`; to DBLP CoRR `ee` URLs and keys), and DOI (namespace `doi`; to DBLP
`ee`, Crossref, OpenAlex). No ORCID in the `arXiv` format.

## Human decisions (human decision, phase 03 human gate, 2026-10-18)

- **HD-03-02:** `authors/author/affiliation` strings are batch only, for example as
  entity-resolution features, and are dropped before Neptune, DynamoDB, S3 Vectors, and any
  runtime-facing export. arXiv has no resolved institution IDs.
- **HD-03-04:** confirmation download approved in principle, not run: one ListRecords page
  (`fetch-by-id.sh arxiv --max-pages 1`) to confirm the OAI identifier prefix and TeX escapes.

## Quirks the sample exercises (`datasets/samples/arxiv/arxiv-oai-sample.xml`)

- A TeX-escaped name (`M\"uller`) where other sources have `Müller`.
- A multi-line title.
- An old-style ID with `suffix` and no DOI or license.
- A deleted record with a header only.
- The same work as the DBLP CoRR record and the conference DOI.
