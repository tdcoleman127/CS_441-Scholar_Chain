#!/usr/bin/env bash
# run-tests.sh: dataset validation tests for every script and the validator. Owner: dataset-engineer.
#
# Usage: bash datasets/tests/run-tests.sh
# Fully offline: downloads use file:// URLs into a temporary directory. The real manifest and
# samples are only read; every mutation runs on a temporary copy.
# Output: "TEST PASS <name>" or "TEST FAIL <name>" per case, then "TESTS passed=N failed=M".
# Exit code 0 only if every case passes.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
scripts="$repo/datasets/scripts"
work="$(mktemp -d "${TMPDIR:-/tmp}/sc-dataset-tests.XXXXXX")"
trap 'rm -rf "$work"' EXIT

py=""
for cand in python3 python; do
  if command -v "$cand" >/dev/null 2>&1 && "$cand" -c 'import yaml' >/dev/null 2>&1; then
    py="$cand"
    break
  fi
done
[[ -n "$py" ]] || { echo "TESTS cannot run: Python with PyYAML not found"; exit 2; }

passed=0
failed=0
ok() { echo "TEST PASS $1"; passed=$((passed + 1)); }
bad() { echo "TEST FAIL $1${2:+ :: $2}"; failed=$((failed + 1)); }

# expect <name> <expected-exit> <grep-pattern-or-empty> -- command...
expect() {
  local name="$1" want="$2" pat="$3"
  shift 4
  local out rc=0
  out="$("$@" 2>&1)" || rc=$?
  if [[ "$rc" -ne "$want" ]]; then
    bad "$name" "exit $rc, wanted $want; last output: $(printf '%s' "$out" | tail -n 3 | tr '\n' '|')"
  elif [[ -n "$pat" ]] && ! grep -q -E -- "$pat" <<<"$out"; then
    bad "$name" "output lacks /$pat/; last output: $(printf '%s' "$out" | tail -n 3 | tr '\n' '|')"
  else
    ok "$name"
  fi
}

file_url() {
  # file:// URL for a local path; Git Bash on Windows needs the drive-letter form.
  if command -v cygpath >/dev/null 2>&1; then
    printf 'file:///%s' "$(cygpath -m "$1")"
  else
    printf 'file://%s' "$1"
  fi
}

sha() { sha256sum "$1" | awk '{print $1}'; }

fresh_copy() {
  # fresh_copy <dir>: a minimal repository root holding a copy of datasets/
  rm -rf "$1"
  mkdir -p "$1"
  cp -R "$repo/datasets" "$1/datasets"
}

# ---------------------------------------------------------------- validator: real repository
expect "validator passes on the committed datasets" 0 "RESULT PASS" -- \
  bash "$scripts/validate-datasets.sh"

# ---------------------------------------------------------------- validator: mutations
mut="$work/mut"
for case_ in \
  "drop-license|openalex: required field 'license'" \
  "literal-bucket|dblp: s3_prefixes are placeholder templates" \
  "too-few-enrichment|at least 3 selected enrichment datasets" \
  "wrong-malformed-count|crossref: sample .* 1 malformed \\(declared 5, 0\\)" \
  "http-license-url|arxiv: license has name, https url" \
  "missing-schema-file|opencitations: schema note exists" \
  "bad-upstream-digest|dblp: upstream checksum" \
  "unknown-source-type|arxiv: source_type is a known SourceType" \
  "excluded-without-evidence|excluded orcid: has source_type, reason, https evidence_urls" \
  "decision-wrong-recorded-as|human decision HD-03-01: recorded_as is 'human decision, phase 03 human gate, 2026-10-18'" \
  "closed-question-without-decision|human decision HD-03-01: closes Q-orcid-public-data-file" \
  "reopened-closed-question|human decision HD-03-03: closes Q-crossref-public-data-file" \
  "open-question-without-owner|open question Q-dblp-size: status 'open' is open \\(with owner\\)" \
  "missing-decision-ref|arxiv: decision_refs cites HD-03-02" \
  "unknown-decision-ref|dblp: decision_refs entry HD-03-99 is a recorded human decision" \
  "affiliation-not-dropped-before-neptune|openalex: affiliation_handling cites HD-03-02" \
  "missing-affiliation-privacy-note|crossref: privacy_notes record HD-03-02" \
  "missing-orcid-privacy-note|dblp: privacy_notes record HD-03-01" \
  "confirmation-run-without-evidence|dblp: confirmation_download cites HD-03-04" \
  "missing-confirmation-download|opencitations: confirmation_download cites HD-03-04" \
  "crossref-bulk-path-restored|crossref: only the REST api access path is active under HD-03-03" \
  "unknown-linkage-namespace|opencitations: linkage key omid namespace 'OMID'" \
  "pmid-key-wrong-namespace|openalex: linkage key pmid uses namespace pmid" \
  "none-namespace-without-note|dblp: linkage key dblp_person_id namespace 'none'" \
  "owner-fix-without-evidence|open question Q-line-endings: status 'closed'" \
  "owner-fix-without-resolver|open question Q-line-endings: status 'closed'" \
  "owner-fix-undated-evidence|open question Q-linkage-keys-without-core-namespace: status 'closed'" \
  "owner-fix-on-deferred|open question Q-line-endings: status 'deferred'" \
  "dblp-bound-question-reopened|human decision HD-03-06: closes Q-dblp-confirmation-bound" \
  "dblp-bound-below-estimate|dblp: confirmation_download.max_bytes 1029999999 covers the full-tier sources.tsv estimate total 1030000000" \
  "dblp-bound-missing|dblp: confirmation_download.max_bytes None covers" \
  "dblp-bound-agent-invoked|dblp: confirmation_download.invoked_by is human" \
  "dblp-bound-unapproved|dblp: confirmation_download.approved_by 'HD-03-99' is a recorded human decision" \
  "opencitations-chosen-file-mismatch|opencitations: confirmation_download.chosen_file '2026-09-01T15_0-11.zip' matches" \
  "opencitations-bound-below-chosen-file|opencitations: confirmation_download.max_bytes 180939783 covers"; do
  op="${case_%%|*}"
  pat="${case_#*|}"
  fresh_copy "$mut"
  "$py" -E "$here/mutate_manifest.py" "$mut/datasets/dataset-manifest.yaml" "$op"
  expect "validator rejects mutation $op" 1 "CHECK FAIL .*$pat" -- \
    "$py" -E "$scripts/validate_datasets.py" --root "$mut"
done

expect "validator reports linkage keys that have no core namespace" 0 "WARN linkage key without core namespace: opencitations.oci" -- \
  "$py" -E -B "$scripts/validate_datasets.py"

fresh_copy "$mut"
enums="$mut/modules/core/src/main/scala/edu/uic/cs441/scholarchain/core/model"
mkdir -p "$enums"
printf 'enum IdNamespace(val wire: String):\n  case Doi extends IdNamespace("doi")\n' >"$enums/Enums.scala"
expect "validator warns when a manifest namespace is missing from core IdNamespace" 0 "WARN linkage namespace omid is not yet an IdNamespace in modules/core" -- \
  "$py" -E -B "$scripts/validate_datasets.py" --root "$mut"

expect "validator accepts questions closed as owner-fix with dated evidence" 0 "CHECK PASS open question Q-line-endings: status 'closed'" -- \
  "$py" -E -B "$scripts/validate_datasets.py"
expect "validator checks the dblp bound against the sources.tsv full-tier estimate" 0 "CHECK PASS dblp: confirmation_download.max_bytes 1200000000 covers the full-tier sources.tsv estimate total 1030000000" -- \
  "$py" -E -B "$scripts/validate_datasets.py"

fresh_copy "$mut"
printf 'arxiv\tbulk\tfull\thttps://invalid.invalid/arxiv.tar\tnone\trecord-at-download\t5000\tarxiv.tar\n' >>"$mut/datasets/scripts/sources.tsv"
expect "validator rejects a full-tier sources.tsv row without a recorded human-approved bound" 1 "CHECK FAIL arxiv: confirmation_download.max_bytes None covers" -- \
  "$py" -E "$scripts/validate_datasets.py" --root "$mut"

fresh_copy "$mut"
printf ' ' >>"$mut/datasets/samples/openalex/openalex-works-sample.jsonl"
expect "validator rejects a sample whose bytes changed" 1 "CHECK FAIL openalex: sample .* sha256 matches manifest" -- \
  "$py" -E "$scripts/validate_datasets.py" --root "$mut"
expect "checksum list check rejects a sample whose bytes changed" 5 "action=fail" -- \
  bash "$scripts/record-sample-checksums.sh" --check --root "$mut"

fresh_copy "$mut"
echo "stray" >"$mut/datasets/samples/dblp/stray.txt"
expect "validator rejects an undeclared sample file" 1 "CHECK FAIL file datasets/samples/dblp/stray.txt" -- \
  "$py" -E "$scripts/validate_datasets.py" --root "$mut"

fresh_copy "$mut"
f="$mut/datasets/samples/crossref/crossref-works-sample.jsonl"
for _ in $(seq 1 20); do cat "$repo/datasets/samples/crossref/crossref-works-sample.jsonl"; done >"$f"
expect "validator rejects a sample larger than 65536 bytes" 1 "CHECK FAIL crossref: sample .* is at most 65536 bytes" -- \
  "$py" -E "$scripts/validate_datasets.py" --root "$mut"

fresh_copy "$mut"
printf 'x' >>"$mut/datasets/scripts/fetch.sh"
printf '\nif then\n' >>"$mut/datasets/scripts/fetch.sh"
expect "validate-datasets.sh fails when a script has a syntax error" 1 "action=syntax-fail file=datasets/scripts/fetch.sh" -- \
  bash "$mut/datasets/scripts/validate-datasets.sh" --root "$mut"

# ---------------------------------------------------------------- record-sample-checksums
fresh_copy "$mut"
expect "record-sample-checksums is idempotent on unchanged samples" 0 "action=skip reason=unchanged" -- \
  bash "$scripts/record-sample-checksums.sh" --root "$mut"
echo "new" >"$mut/datasets/samples/dblp/new.txt"
expect "record-sample-checksums rewrites when samples change" 0 "action=written files=8" -- \
  bash "$scripts/record-sample-checksums.sh" --root "$mut"

# ---------------------------------------------------------------- fetch.sh
up="$work/upstream"
mkdir -p "$up"
printf 'small upstream artifact\n' >"$up/a.txt"
head -c 4096 /dev/zero >"$up/big.bin"
good="$(sha "$up/a.txt")"
src="$work/sources.tsv"
{
  printf '# test sources\n'
  printf 'tst\tgood\tmetadata\t%s\tsha256\t%s\t100\ta.txt\n' "$(file_url "$up/a.txt")" "$good"
  printf 'tst\trecorded\tmetadata\t%s\tnone\trecord-at-download\t100\tb.txt\n' "$(file_url "$up/a.txt")"
  printf 'tstbad\tbad\tmetadata\t%s\tsha256\t%s\t100\ta.txt\n' "$(file_url "$up/a.txt")" "0000000000000000000000000000000000000000000000000000000000000000"
  printf 'tstbig\tbig\tmetadata\t%s\tnone\trecord-at-download\t100\tbig.bin\n' "$(file_url "$up/big.bin")"
  printf 'tstfull\tcorpus\tfull\thttps://invalid.invalid/corpus.gz\tmd5\t00000000000000000000000000000000\t5000\tcorpus.gz\n'
  printf 'tstunk\tcorpus\tfull\thttps://invalid.invalid/corpus.gz\tnone\trecord-at-download\tunknown\tcorpus.gz\n'
} >"$src"
dest="$work/dest"

expect "fetch.sh plan mode prints the plan" 0 "action=plan dataset=tst artifact=good tier=metadata" -- \
  bash "$scripts/fetch.sh" tst --plan --sources "$src"
[[ ! -e "$dest" ]] && ok "fetch.sh plan mode writes nothing" || bad "fetch.sh plan mode writes nothing"
expect "fetch.sh default mode is plan" 0 "action=plan" -- \
  bash "$scripts/fetch.sh" all --sources "$src"
expect "fetch.sh plans the real sources table" 0 "action=plan dataset=dblp artifact=release-xml tier=full" -- \
  bash "$scripts/fetch.sh" all --plan
expect "fetch.sh refuses download without a destination" 2 "no destination" -- \
  env -u SCHOLARCHAIN_DATA_DIR bash "$scripts/fetch.sh" tst --metadata --sources "$src"
expect "fetch.sh --full without --max-bytes is refused" 3 "requires an explicit --max-bytes" -- \
  bash "$scripts/fetch.sh" tstfull --full --dest "$dest" --sources "$src"
expect "fetch.sh --full over the bound is refused before any network" 3 "exceeds approved bound" -- \
  bash "$scripts/fetch.sh" tstfull --full --max-bytes 4999 --dest "$dest" --sources "$src"
expect "fetch.sh --full with an unknown size estimate is refused" 3 "unknown size estimate" -- \
  bash "$scripts/fetch.sh" tstunk --full --max-bytes 999999999 --dest "$dest" --sources "$src"
expect "fetch.sh metadata bound above the ceiling is refused" 3 "exceeds ceiling" -- \
  bash "$scripts/fetch.sh" tst --metadata --max-bytes 99999999 --dest "$dest" --sources "$src"
expect "fetch.sh downloads and verifies a metadata artifact" 0 "action=verified dataset=tst artifact=good" -- \
  bash "$scripts/fetch.sh" tst --metadata --dest "$dest" --sources "$src"
expect "fetch.sh skips a verified artifact on rerun" 0 "action=skip dataset=tst artifact=good reason=checksum-match" -- \
  bash "$scripts/fetch.sh" tst --metadata --dest "$dest" --sources "$src"
[[ "$(cat "$dest/tst/b.txt.sha256")" == "$good" ]] && ok "fetch.sh records the sha256 sidecar for record-at-download artifacts" || bad "fetch.sh records the sha256 sidecar for record-at-download artifacts"
expect "fetch.sh skips a recorded artifact on rerun" 0 "artifact=recorded reason=recorded-sha256-match" -- \
  bash "$scripts/fetch.sh" tst --metadata --dest "$dest" --sources "$src"
expect "fetch.sh fails on checksum mismatch" 5 "reason=checksum-mismatch" -- \
  bash "$scripts/fetch.sh" tstbad --metadata --dest "$dest" --sources "$src"
[[ ! -e "$dest/tstbad/a.txt" && ! -e "$dest/tstbad/a.txt.part" ]] && ok "fetch.sh keeps no file after a mismatch" ||
  bad "fetch.sh keeps no file after a mismatch"
expect "fetch.sh enforces the byte bound on the actual download" 4 "" -- \
  bash "$scripts/fetch.sh" tstbig --metadata --max-bytes 1000 --dest "$dest" --sources "$src"
[[ ! -e "$dest/tstbig/big.bin" ]] && ok "fetch.sh keeps no oversized file" || bad "fetch.sh keeps no oversized file"
printf 'corrupted\n' >"$dest/tst/a.txt"
expect "fetch.sh re-downloads a corrupted local copy" 0 "action=stale dataset=tst artifact=good" -- \
  bash "$scripts/fetch.sh" tst --metadata --dest "$dest" --sources "$src"
[[ "$(sha "$dest/tst/a.txt")" == "$good" ]] && ok "fetch.sh repaired copy matches" || bad "fetch.sh repaired copy matches"

# Approved large bound (HD-03-06): a dblp-sized estimate under --max-bytes 1200000000 must pass the
# bound check, keep the byte cap, and get a time budget scaled to the bound (1145 s at 1 MiB/s),
# not the fixed 600 s that would refuse a 1.2 GB transfer on a link slower than about 2 MB/s.
amd5="$(md5sum "$up/a.txt" | awk '{print $1}')"
srcbig="$work/sources-big.tsv"
printf 'tstdblp\trelease\tfull\t%s\tmd5\t%s\t1030000000\trelease.gz\n' "$(file_url "$up/a.txt")" "$amd5" >"$srcbig"
expect "fetch.sh --full accepts the approved dblp-size bound" 0 "action=bound-ok dataset=tstdblp est_total=1030000000 max_bytes=1200000000" -- \
  env -u SC_CURL_MAX_TIME bash "$scripts/fetch.sh" tstdblp --full --max-bytes 1200000000 --dest "$dest" --sources "$srcbig"
[[ -f "$dest/tstdblp/release.gz" ]] && ok "fetch.sh --full kept the verified file under the large bound" ||
  bad "fetch.sh --full kept the verified file under the large bound"
expect "fetch.sh scales the time budget with a large bound" 0 "action=download dataset=tstdblp .*max_bytes=1200000000 max_time=1145" -- \
  env -u SC_CURL_MAX_TIME bash "$scripts/fetch.sh" tstdblp --full --max-bytes 1200000000 --dest "$dest" --sources "$srcbig" --refresh
expect "fetch.sh refuses a bound below the dblp-size estimate" 3 "estimated total 1030000000 bytes exceeds approved bound 1029999999" -- \
  bash "$scripts/fetch.sh" tstdblp --full --max-bytes 1029999999 --dest "$dest" --sources "$srcbig"
expect "fetch.sh keeps the 600 s time budget for small bounds" 0 "action=download dataset=tst .*max_time=600" -- \
  env -u SC_CURL_MAX_TIME bash "$scripts/fetch.sh" tst --metadata --dest "$dest" --sources "$src" --refresh
expect "fetch.sh honors an SC_CURL_MAX_TIME override" 0 "max_time=42" -- \
  env SC_CURL_MAX_TIME=42 bash "$scripts/fetch.sh" tst --metadata --dest "$dest" --sources "$src" --refresh
expect "fetch.sh rejects a non-numeric SC_CURL_MAX_TIME" 2 "SC_CURL_MAX_TIME is not an unsigned integer" -- \
  env SC_CURL_MAX_TIME=soon bash "$scripts/fetch.sh" tst --metadata --dest "$dest" --sources "$src" --refresh
expect "fetch.sh plans the OpenCitations confirmation shard as a full-tier row" 0 "action=plan dataset=opencitations artifact=index-csv-v8-shard-2026-09-01T15 tier=full est_bytes=180939784 algo=md5" -- \
  bash "$scripts/fetch.sh" opencitations --plan

# ---------------------------------------------------------------- fetch-by-id.sh
ids="$work/dois.txt"
printf '# comment\n10.5555/sc441.0001\nnot-a-doi\n10.5555/SC441.0002\r\n10.5555/sc441.0003\n' >"$ids"
expect "fetch-by-id plan rejects invalid DOIs" 0 "action=reject source=crossref id=not-a-doi" -- \
  bash "$scripts/fetch-by-id.sh" crossref --ids "$ids" --plan --max-ids 2
expect "fetch-by-id plan stops at --max-ids" 0 "action=stop source=crossref reason=max-ids-reached max_ids=2" -- \
  bash "$scripts/fetch-by-id.sh" crossref --ids "$ids" --plan --max-ids 2
expect "fetch-by-id plan lowercases DOIs into the verified Crossref path" 0 "url=https://api.crossref.org/works/10.5555/sc441.0002" -- \
  bash "$scripts/fetch-by-id.sh" crossref --ids "$ids" --plan
expect "fetch-by-id plan uses both OpenCitations operations" 0 "url=https://api.opencitations.net/index/v2/references/doi:10.5555/sc441.0001" -- \
  bash "$scripts/fetch-by-id.sh" opencitations --ids "$ids" --plan
expect "fetch-by-id refuses --max-ids above the cap" 3 "exceeds cap 500" -- \
  bash "$scripts/fetch-by-id.sh" openalex --ids "$ids" --plan --max-ids 501
expect "fetch-by-id refuses arXiv delay below 3 s" 3 "at least 3 s" -- \
  bash "$scripts/fetch-by-id.sh" arxiv --from 2024-01-01 --until 2024-01-02 --delay 1 --plan
expect "fetch-by-id refuses arXiv pages above the cap" 3 "exceeds cap 5" -- \
  bash "$scripts/fetch-by-id.sh" arxiv --from 2024-01-01 --until 2024-01-02 --max-pages 6 --plan
expect "fetch-by-id arXiv plan uses the verified OAI base URL" 0 "url=https://oaipmh.arxiv.org/oai\\?verb=ListRecords&metadataPrefix=arXiv&set=cs" -- \
  bash "$scripts/fetch-by-id.sh" arxiv --from 2024-01-01 --until 2024-01-02 --plan
expect "fetch-by-id rejects unsupported sources" 2 "unsupported source" -- \
  bash "$scripts/fetch-by-id.sh" semantic-scholar --ids "$ids" --plan

api="$work/api"
mkdir -p "$api/works/10.5555"
printf '{"message":{"DOI":"10.5555/sc441.0001"}}\n' >"$api/works/10.5555/sc441.0001"
one="$work/one.txt"
printf '10.5555/sc441.0001\n' >"$one"
expect "fetch-by-id downloads and records a response" 0 "action=recorded source=crossref out=10.5555_sc441.0001.json" -- \
  bash "$scripts/fetch-by-id.sh" crossref --ids "$one" --dest "$dest" --delay 0 --base-url "$(file_url "$api")"
expect "fetch-by-id skips an already recorded response" 0 "action=skip source=crossref out=10.5555_sc441.0001.json" -- \
  bash "$scripts/fetch-by-id.sh" crossref --ids "$one" --dest "$dest" --delay 0 --base-url "$(file_url "$api")"

# ---------------------------------------------------------------- sample-extract.sh
keys="$work/keys.txt"
printf 'conf/scsample/CarberryMS24\nhomepages/99/990001\n' >"$keys"
ex="$work/extract"
mkdir -p "$ex"
cp "$repo/datasets/samples/dblp/dblp.dtd" "$ex/dblp.dtd"
expect "sample-extract dblp-xml writes the selected records" 0 "action=written" -- \
  bash "$scripts/sample-extract.sh" dblp-xml --input "$repo/datasets/samples/dblp/dblp-sample.xml" --keys "$keys" --out "$ex/subset.xml"
cat >"$work/count.py" <<'EOF'
import sys
sys.path.insert(0, sys.argv[1])
import validate_datasets as v
wf, n, err = v.count_xml(sys.argv[2], "*")
print(f"wf={wf} records={n} {err}")
EOF
expect "sample-extract dblp-xml output is well-formed with 2 records" 0 "wf=True records=2" -- \
  "$py" -E -B "$work/count.py" "$scripts" "$ex/subset.xml"
expect "sample-extract dblp-xml is idempotent" 0 "action=skip" -- \
  bash "$scripts/sample-extract.sh" dblp-xml --input "$repo/datasets/samples/dblp/dblp-sample.xml" --keys "$keys" --out "$ex/subset.xml"
gzip -c "$repo/datasets/samples/crossref/crossref-works-sample.jsonl" >"$work/crossref.jsonl.gz"
printf '10.5555/sc441.0002\n' >"$work/doi2.txt"
expect "sample-extract jsonl reads gzip and matches DOIs case-insensitively" 0 "action=written" -- \
  bash "$scripts/sample-extract.sh" jsonl --input "$work/crossref.jsonl.gz" --dois "$work/doi2.txt" --out "$ex/cr.jsonl"
# The DOI is the record's own DOI on line 2 (uppercase) and a reference DOI on lines 1 and 3.
[[ "$(wc -l <"$ex/cr.jsonl" | tr -d ' ')" == "3" ]] && grep -q '"DOI":"10.5555/SC441.0002"' "$ex/cr.jsonl" &&
  ok "sample-extract jsonl kept the 3 lines mentioning the DOI, uppercase record included" ||
  bad "sample-extract jsonl kept the 3 lines mentioning the DOI, uppercase record included"
expect "sample-extract csv keeps the header" 0 "action=written" -- \
  bash "$scripts/sample-extract.sh" csv --input "$repo/datasets/samples/opencitations/opencitations-index-sample.csv" --dois "$work/doi2.txt" --out "$ex/oc.csv"
[[ "$(head -n 1 "$ex/oc.csv")" == "oci,citing,cited,creation,timespan,journal_sc,author_sc" && "$(wc -l <"$ex/oc.csv" | tr -d ' ')" == "3" ]] &&
  ok "sample-extract csv output is header plus 2 matching rows" || bad "sample-extract csv output is header plus 2 matching rows"
expect "sample-extract refuses --max-records above the cap" 3 "exceeds cap 1000" -- \
  bash "$scripts/sample-extract.sh" jsonl --input "$work/crossref.jsonl.gz" --dois "$work/doi2.txt" --out "$ex/x.jsonl" --max-records 1001
big="$work/big.jsonl"
for _ in $(seq 1 400); do printf '{"DOI":"10.5555/sc441.0002","pad":"%0200d"}\n' 0; done >"$big"
mkdir -p "$work/fake/datasets/samples/x"
expect "sample-extract refuses an oversized excerpt inside datasets/samples" 3 "limited to 65536" -- \
  bash "$scripts/sample-extract.sh" jsonl --input "$big" --dois "$work/doi2.txt" --out "$work/fake/datasets/samples/x/big.jsonl" --max-records 400
[[ ! -e "$work/fake/datasets/samples/x/big.jsonl" ]] && ok "sample-extract wrote no oversized sample" || bad "sample-extract wrote no oversized sample"

# The cap must not depend on the output directory already existing (Round 3 finding). None of
# the directories below is pre-created.
bigx() { bash "$scripts/sample-extract.sh" jsonl --input "$big" --dois "$work/doi2.txt" --out "$1" --max-records 400; }
# 1. A new subdirectory of the real repository samples. The trap removes it even if the script
#    wrongly writes there, so no stray file is left under datasets/samples.
newsrc="$repo/datasets/samples/sc-test-newsrc-$$"
trap 'rm -rf "$work" "$newsrc"' EXIT
expect "sample-extract caps an excerpt in a new subdirectory of the repository samples" 3 "limited to 65536" -- \
  bigx "$newsrc/big.jsonl"
[[ ! -e "$newsrc" ]] && ok "sample-extract created nothing under the repository samples" ||
  bad "sample-extract created nothing under the repository samples"
rm -rf "$newsrc"
# 2. A samples tree whose root does not exist at all.
expect "sample-extract caps an excerpt when no part of the samples path exists" 3 "limited to 65536" -- \
  bigx "$work/fresh/datasets/samples/newsrc/big.jsonl"
[[ ! -e "$work/fresh" ]] && ok "sample-extract created no directories for a refused excerpt" ||
  bad "sample-extract created no directories for a refused excerpt"
# 3. A '..'-laden path through a missing directory that resolves into datasets/samples.
expect "sample-extract caps a '..' path that resolves into datasets/samples" 3 "limited to 65536" -- \
  bigx "$work/dots/datasets/missing/../samples/./new2/big.jsonl"
expect "sample-extract caps a '..' path through an existing directory into the repository samples" 3 "limited to 65536" -- \
  bigx "$repo/datasets/scripts/../samples/sc-test-newsrc-$$/big.jsonl"
[[ ! -e "$newsrc" ]] && ok "sample-extract created nothing for the '..' repository path" ||
  bad "sample-extract created nothing for the '..' repository path"
rm -rf "$newsrc"
# 4. Case variants (Windows and MSYS file systems are case-insensitive), matched fail-closed everywhere.
expect "sample-extract caps a case-variant samples path" 3 "limited to 65536" -- \
  bigx "$work/case/Datasets/SAMPLES/new3/big.jsonl"
# 5. Outside datasets/samples the excerpt is not capped, even with '..' and missing directories.
expect "sample-extract does not cap an excerpt outside datasets/samples" 0 "action=written" -- \
  bigx "$work/outside/datasets/samples/../../elsewhere/new4/big.jsonl"
[[ -f "$work/outside/elsewhere/new4/big.jsonl" && "$(wc -c <"$work/outside/elsewhere/new4/big.jsonl" | tr -d ' ')" -gt 65536 ]] &&
  ok "sample-extract wrote the uncapped excerpt outside datasets/samples" ||
  bad "sample-extract wrote the uncapped excerpt outside datasets/samples"
# 6. --out naming a directory would make cp write a file the caller did not name; refuse it.
mkdir -p "$work/adir"
expect "sample-extract refuses an --out that is a directory" 2 "is a directory" -- \
  bigx "$work/adir"
[[ -z "$(ls -A "$work/adir")" ]] && ok "sample-extract wrote nothing into a directory --out" ||
  bad "sample-extract wrote nothing into a directory --out"

# Round 4 (F6): the cap decision and the written path must agree on every platform.
# SC_TEST_PATH_STYLE forces the path branch so both are testable on any host:
#   posix   = Linux, CI, WSL, macOS: a backslash is a literal filename character, so an --out
#             containing one is refused (exit 2) before anything is resolved or written.
#   windows = MSYS, Cygwin, Git Bash: a backslash is a separator and is normalized once.
# On this MSYS host, the posix cases run the exact code path that a Linux host takes by default.
small="$work/doi1.txt"
printf '10.5555/sc441.0001\n' >"$small"
smallx() { bash "$scripts/sample-extract.sh" jsonl --input "$repo/datasets/samples/crossref/crossref-works-sample.jsonl" --dois "$small" --out "$1"; }
# styled <style> <big|small> <out>: run sample-extract with SC_TEST_PATH_STYLE forced to <style>.
styled() {
  local style="$1" size="$2" o="$3" in="$big" ids="$work/doi2.txt" mr=400
  if [[ "$size" == small ]]; then
    in="$repo/datasets/samples/crossref/crossref-works-sample.jsonl" ids="$small" mr=100
  fi
  SC_TEST_PATH_STYLE="$style" bash "$scripts/sample-extract.sh" jsonl --input "$in" --dois "$ids" --out "$o" --max-records "$mr"
}
bs='\'
mkdir -p "$work/bs/datasets/samples/x"
# The reviewer's failure input: on Linux this name resolves outside the samples (cap skipped) but
# is written inside samples/x/ under a backslash name.
expect "sample-extract (posix paths) refuses an --out containing a backslash" 2 "backslash" -- \
  styled posix big "$work/bs/datasets/samples/x${bs}..${bs}..${bs}big.jsonl"
[[ ! -e "$work/bs/datasets/big.jsonl" && -z "$(ls -A "$work/bs/datasets/samples/x")" && "$(ls -A "$work/bs/datasets/samples")" == "x" ]] &&
  ok "sample-extract (posix paths) wrote nothing for a backslash --out" ||
  bad "sample-extract (posix paths) wrote nothing for a backslash --out"
expect "sample-extract (posix paths) refuses a backslash --out even outside datasets/samples" 2 "backslash" -- \
  styled posix small "$work/bs${bs}plain.jsonl"
[[ -z "$(find "$work/bs" -name '*plain.jsonl')" ]] && ok "sample-extract (posix paths) wrote no backslash-named file" ||
  bad "sample-extract (posix paths) wrote no backslash-named file"
expect "sample-extract (windows paths) caps a backslash path that resolves into datasets/samples" 3 "limited to 65536" -- \
  styled windows big "$work/bs/datasets/x${bs}..${bs}samples${bs}y${bs}big.jsonl"
[[ ! -e "$work/bs/datasets/samples/y" && ! -e "$work/bs/datasets/x" ]] &&
  ok "sample-extract (windows paths) created nothing for a refused backslash path" ||
  bad "sample-extract (windows paths) created nothing for a refused backslash path"
expect "sample-extract (windows paths) writes a backslash path at its resolved location" 0 "action=written out=.*/bs/winout/w.jsonl " -- \
  styled windows small "$work/bs${bs}winout${bs}w.jsonl"
[[ -f "$work/bs/winout/w.jsonl" ]] && ok "sample-extract (windows paths) file exists at the resolved path" ||
  bad "sample-extract (windows paths) file exists at the resolved path"
expect "sample-extract rejects an unknown SC_TEST_PATH_STYLE" 2 "SC_TEST_PATH_STYLE" -- \
  styled dos small "$work/bs/u.jsonl"
[[ ! -e "$work/bs/u.jsonl" ]] && ok "sample-extract wrote nothing under an unknown SC_TEST_PATH_STYLE" ||
  bad "sample-extract wrote nothing under an unknown SC_TEST_PATH_STYLE"
# The written file is exactly the path the cap was decided on: a '..' through a missing directory
# writes the normalized file and creates no stray directory.
expect "sample-extract writes the resolved path for a '..' --out" 0 "action=written out=.*/res/out.jsonl " -- \
  smallx "$work/res/missing/../out.jsonl"
[[ -f "$work/res/out.jsonl" && ! -e "$work/res/missing" ]] &&
  ok "sample-extract created no stray directory for a '..' --out" ||
  bad "sample-extract created no stray directory for a '..' --out"
# An existing symlink at --out is refused: cp -f would follow it and write the link target, a file
# the cap decision never saw. MSYS needs native symlinks for ln -s to make a real link.
mkdir -p "$work/ln"
printf 'target\n' >"$work/ln/target.txt"
MSYS=winsymlinks:nativestrict ln -s "$work/ln/target.txt" "$work/ln/link.jsonl" 2>/dev/null || true
if [[ -L "$work/ln/link.jsonl" ]]; then
  expect "sample-extract refuses an --out that is an existing symlink" 2 "symlink" -- \
    smallx "$work/ln/link.jsonl"
  [[ "$(cat "$work/ln/target.txt")" == "target" ]] && ok "sample-extract left the symlink target untouched" ||
    bad "sample-extract left the symlink target untouched"
  MSYS=winsymlinks:nativestrict ln -s "$work/ln/nowhere.txt" "$work/ln/dangling.jsonl" 2>/dev/null || true
  expect "sample-extract refuses an --out that is a dangling symlink" 2 "symlink" -- \
    smallx "$work/ln/dangling.jsonl"
  [[ ! -e "$work/ln/nowhere.txt" ]] && ok "sample-extract created no dangling-symlink target" ||
    bad "sample-extract created no dangling-symlink target"
else
  bad "symlink tests could not create a symlink on this host"
fi

echo "TESTS passed=$passed failed=$failed"
[[ "$failed" -eq 0 ]]
