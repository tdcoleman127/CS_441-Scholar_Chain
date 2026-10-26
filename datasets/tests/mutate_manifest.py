"""Test helper: apply one named mutation to a copy of dataset-manifest.yaml. Owner: dataset-engineer.

Usage: python -E mutate_manifest.py MANIFEST OPERATION
Only used by datasets/tests/run-tests.sh on temporary copies, never on the real manifest.
"""

import sys

import yaml


def by_id(m, did):
    return next(d for d in m["datasets"] if d["id"] == did)


def by_hd(m, hid):
    return next(d for d in m["human_decisions"] if d["id"] == hid)


def by_q(m, qid):
    return next(q for q in m["open_questions"] if q["id"] == qid)


def main(path: str, op: str) -> int:
    with open(path, encoding="utf-8") as f:
        m = yaml.safe_load(f)
    if op == "drop-license":
        del by_id(m, "openalex")["license"]
    elif op == "literal-bucket":
        by_id(m, "dblp")["s3_prefixes"]["raw"] = "s3://my-real-bucket/raw/dblp/"
    elif op == "too-few-enrichment":
        m["datasets"] = [d for d in m["datasets"] if d["id"] in ("dblp", "openalex", "crossref")]
    elif op == "wrong-malformed-count":
        by_id(m, "crossref")["samples"][0]["malformed_records"] = 0
    elif op == "http-license-url":
        by_id(m, "arxiv")["license"]["url"] = "http://creativecommons.org/publicdomain/zero/1.0/"
    elif op == "missing-schema-file":
        by_id(m, "opencitations")["schema"] = "datasets/schemas/does-not-exist.md"
    elif op == "bad-upstream-digest":
        by_id(m, "dblp")["checksums"]["upstream"][0]["value"] = "not-a-digest"
    elif op == "unknown-source-type":
        by_id(m, "arxiv")["source_type"] = "arxiv-v2"
    elif op == "excluded-without-evidence":
        m["excluded_candidates"][0]["evidence_urls"] = []
    elif op == "decision-wrong-recorded-as":
        by_hd(m, "HD-03-01")["recorded_as"] = "decided by the orchestrator"
    elif op == "closed-question-without-decision":
        del by_q(m, "Q-orcid-public-data-file")["resolution"]
    elif op == "reopened-closed-question":
        by_q(m, "Q-crossref-public-data-file")["status"] = "open"
    elif op == "open-question-without-owner":
        del by_q(m, "Q-dblp-size")["owner"]
    elif op == "missing-decision-ref":
        by_id(m, "arxiv")["decision_refs"].remove("HD-03-02")
    elif op == "unknown-decision-ref":
        by_id(m, "dblp")["decision_refs"].append("HD-03-99")
    elif op == "affiliation-not-dropped-before-neptune":
        by_id(m, "openalex")["affiliation_handling"]["drop_before"].remove("neptune")
    elif op == "missing-affiliation-privacy-note":
        d = by_id(m, "crossref")
        d["privacy_notes"] = [n for n in d["privacy_notes"] if "HD-03-02" not in n]
    elif op == "missing-orcid-privacy-note":
        d = by_id(m, "dblp")
        d["privacy_notes"] = [n for n in d["privacy_notes"] if "HD-03-01" not in n]
    elif op == "confirmation-run-without-evidence":
        by_id(m, "dblp")["confirmation_download"]["status"] = "run"
    elif op == "missing-confirmation-download":
        del by_id(m, "opencitations")["confirmation_download"]
    elif op == "crossref-bulk-path-restored":
        by_id(m, "crossref")["source"]["download_urls"]["public_data_file"] = "requester-pays bucket"
    elif op == "unknown-linkage-namespace":
        next(k for k in by_id(m, "opencitations")["linkage_keys"] if k["key"] == "omid")["namespace"] = "OMID"
    elif op == "pmid-key-wrong-namespace":
        next(k for k in by_id(m, "openalex")["linkage_keys"] if k["key"] == "pmid")["namespace"] = "pubmed"
    elif op == "none-namespace-without-note":
        del next(k for k in by_id(m, "dblp")["linkage_keys"] if k["key"] == "dblp_person_id")["namespace_note"]
    elif op == "owner-fix-without-evidence":
        del by_q(m, "Q-line-endings")["evidence"]
    elif op == "owner-fix-without-resolver":
        del by_q(m, "Q-line-endings")["resolved_by"]
    elif op == "owner-fix-undated-evidence":
        by_q(m, "Q-linkage-keys-without-core-namespace")["evidence"][0]["observed_on"] = "today"
    elif op == "owner-fix-on-deferred":
        by_q(m, "Q-line-endings")["status"] = "deferred"
    elif op == "dblp-bound-question-reopened":
        by_q(m, "Q-dblp-confirmation-bound")["status"] = "open"
    elif op == "dblp-bound-below-estimate":
        by_id(m, "dblp")["confirmation_download"]["max_bytes"] = 1029999999
    elif op == "dblp-bound-missing":
        del by_id(m, "dblp")["confirmation_download"]["max_bytes"]
    elif op == "dblp-bound-agent-invoked":
        by_id(m, "dblp")["confirmation_download"]["invoked_by"] = "dataset-engineer"
    elif op == "dblp-bound-unapproved":
        by_id(m, "dblp")["confirmation_download"]["approved_by"] = "HD-03-99"
    elif op == "opencitations-chosen-file-mismatch":
        by_id(m, "opencitations")["confirmation_download"]["chosen_file"]["listed_bytes"] = 180939785
    elif op == "opencitations-bound-below-chosen-file":
        by_id(m, "opencitations")["confirmation_download"]["max_bytes"] = 180939783
    else:
        print(f"unknown operation {op}")
        return 2
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        yaml.safe_dump(m, f, sort_keys=False, allow_unicode=True)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1], sys.argv[2]))
