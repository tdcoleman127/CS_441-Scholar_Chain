# ER gold set v1: labeling guide (about 25 minutes)

Worksheet: `modules/entity-resolution/gold/v1/labeling-worksheet-v1.csv` (48 pairs).
You label it. The system being evaluated must not, so no score, decision, or suggested label appears
anywhere in the worksheet.

## The question for each row

Do mention A and mention B refer to the **same real person**?

Fill three columns per row:

| Column | Value |
|---|---|
| `label` | `same`, `different`, or `unsure` (case does not matter). Leave blank to skip. |
| `labeler` | Your name or initials. Or leave every cell blank and pass `--labeler NAME` when you run the evaluator. |
| `notes` | Optional. Why, especially for `unsure` or for a call you found hard. |

Do not edit any other cell. A test (`GoldWorksheetSuite`, run by `sbt check`) regenerates the
worksheet from the mention inventory and fails if any non-label cell has changed. That keeps every
label traceable to its source records.

## How to decide

`same` means you believe both mentions are the same real person, and `different` means you believe
they are two people. Label from the evidence in the row. The examples below are generic and are not
rows of the worksheet. Nothing in this guide tells you the answer for a specific row.

- Name form alone settles nothing either way. Initials vs full names (`M. Garcia` vs
  `Maria Garcia`), a missing middle initial, diacritics or transliteration (`José` vs `Jose`),
  source encodings (arXiv stores accents as TeX escapes, so `G\"odel` is `Gödel`), and name order
  (family name first or last) are all spellings. Neither "same" nor "different" follows from them.
- Two author positions on one paper are normally two people, even when the names look alike.
- The same paper in two sources (DBLP, Crossref, OpenAlex, arXiv) at the same author position is
  strong evidence of the same person.
- DBLP homonym numbers (`Name 0001`, `Name 0002`) are DBLP's own assertion that it has split
  homonymous people. Decide how far you trust it against the other evidence, and note your reasoning
  when the row's evidence conflicts with it.
- Identifiers (ORCID, OpenAlex author IDs) are strong evidence, but a source can attach the wrong ID
  or merge two people under one. If an identifier contradicts the rest of the row, say so in
  `notes`.
- Coauthors, venue, year, affiliation, and topic are supporting evidence. Sharing them makes "same"
  more likely, and not sharing them is weak evidence unless the name is very common.
- A DBLP person record (`role` = `person-record-name`) lists the name forms DBLP assigns to one
  person profile.

## When to use unsure

Use `unsure` when the evidence in the row does not settle it, for example a common name with no
shared coauthor, venue, affiliation, or identifier, or sources that contradict each other.
Do not guess. Unsure rows are counted and excluded from precision and recall, never silently
dropped. A short note on why helps later adjudication.

## Columns you will see (A side and B side)

`mention_id` (stable key: source, record, author position), `source_dataset`, `source_record_id`,
`role` (author, editor, person-record-name), `name` (as written in the source), `title`, `year`,
`venue`, `coauthors` (other names on the same record, `;`-separated), `researcher_ids` (ORCID,
OpenAlex author ID, as given in the source), `work_ids` (DOI or arXiv ID of the paper), and
`context` (affiliation, display name, raw name, and other source details).

Identifiers are as the source gives them. One can be wrong or malformed, and two sources can
disagree. Judge the person, not the identifier.

`stratum` names the observable property the pair was selected for (see
`selection-manifest-v1.txt`). It is not a hint about the answer.

## Editor notes

- Easiest: a plain-text editor or VS Code, typing values between the existing commas.
- Spreadsheet: import as UTF-8 with every column as Text, and save as **CSV UTF-8**. A legacy-encoding
  save corrupts names such as `Nguyễn`, and the integrity test then fails.

## After labeling

From the repository root (a human command; agents may not run `runMain`):

```
sbt "entityResolution/runMain edu.uic.cs441.scholarchain.er.WorksheetEvalMain modules/entity-resolution/gold/v1/labeling-worksheet-v1.csv --labeler <your-name>"
```

`--labeler` is needed only if you left the `labeler` cells blank. The report prints the worksheet
SHA-256, the counts (labeled, unlabeled, unsure, untraced, malformed), the confusion matrix and
precision, recall, and F1 with Wilson 95% intervals for each match path and overall, the threshold
sweep, and the confusion matrix for each selection stratum. Commit the labeled worksheet so the
gold labels stay in the record.

## Limits of this set

- All sample records are synthetic, written by dataset-engineer (CC0). They contain 41 usable
  mentions and only one valid ORCID (Josiah Carberry, ORCID's fictitious demonstration person).
- The selection deliberately oversamples hard cases (4 of 630 plainly different-name pairs were
  taken). Precision and recall on this set describe the hard strata, not population rates.
- With 48 pairs the confidence intervals will be wide. This set is enough to start calibration, not
  to finish it.
