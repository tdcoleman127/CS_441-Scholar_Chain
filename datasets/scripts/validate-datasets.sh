#!/usr/bin/env bash
# validate-datasets.sh: the dataset validation command. Owner: dataset-engineer.
#
# Usage: bash datasets/scripts/validate-datasets.sh [--root REPO_ROOT]
#
# Steps (each prints a STEP marker; the run fails if any step fails):
#   1. bash -n on every datasets/scripts/*.sh and datasets/tests/*.sh
#   2. sources.tsv rows have exactly 8 tab-separated columns
#   3. record-sample-checksums.sh --check (SHA256SUMS matches the sample bytes)
#   4. validate_datasets.py (manifest fields, licenses, S3 templates, schemas, sample checksums,
#      sizes, record counts, malformed-record counts, excluded candidates, human decisions,
#      open-question status, affiliation handling, confirmation downloads, linkage namespaces)
# Needs bash 4+, awk, sha256sum or shasum, and Python 3.8+ with PyYAML. No network access.
set -euo pipefail

SC_TAG="validate-datasets"
# shellcheck source=datasets/scripts/lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

root="$(sc_repo_root)"
if [[ $# -eq 2 && "$1" == "--root" ]]; then
  root="$(cd "$2" && pwd)"
elif [[ $# -ne 0 ]]; then
  sc_die 2 "usage: validate-datasets.sh [--root REPO_ROOT]"
fi
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
failures=0

sc_step "action=syntax root=$root"
for f in "$root"/datasets/scripts/*.sh "$root"/datasets/tests/*.sh; do
  [[ -f "$f" ]] || continue
  if bash -n "$f"; then
    sc_step "action=syntax-ok file=${f#"$root"/}"
  else
    sc_step "action=syntax-fail file=${f#"$root"/}"
    failures=$((failures + 1))
  fi
done

sc_step "action=sources-table"
bad_rows="$(awk -F '\t' '!/^#/ && NF > 0 && NF != 8 { print NR }' "$root/datasets/scripts/sources.tsv")"
if [[ -n "$bad_rows" ]]; then
  sc_step "action=sources-table-fail rows=$(echo "$bad_rows" | tr '\n' ',')"
  failures=$((failures + 1))
fi

sc_step "action=sample-checksums"
if ! bash "$here/record-sample-checksums.sh" --check --root "$root"; then
  failures=$((failures + 1))
fi

py=""
for cand in python3 python; do
  if command -v "$cand" >/dev/null 2>&1 && "$cand" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 8) else 1)' >/dev/null 2>&1; then
    py="$cand"
    break
  fi
done
[[ -n "$py" ]] || sc_die 2 "Python 3.8+ not found (needed for the manifest validator)"

sc_step "action=manifest python=$py"
if ! "$py" -E "$here/validate_datasets.py" --root "$root"; then
  failures=$((failures + 1))
fi

if ((failures > 0)); then
  sc_step "action=result result=FAIL failed_steps=$failures"
  exit 1
fi
sc_step "action=result result=PASS"
