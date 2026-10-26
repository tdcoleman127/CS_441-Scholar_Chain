#!/usr/bin/env bash
# record-sample-checksums.sh: (re)write or check datasets/samples/SHA256SUMS. Owner: dataset-engineer.
#
# Usage:
#   record-sample-checksums.sh [--root REPO_ROOT]           write SHA256SUMS if it changed
#   record-sample-checksums.sh --check [--root REPO_ROOT]   verify only; exit 5 on any mismatch
#
# SHA256SUMS lists every file under datasets/samples except itself, sorted by path, in the
# "sha256sum -c" format with paths relative to datasets/samples. Digests are over raw bytes, so
# the files must be checked out byte-exact (no CRLF conversion); see the manifest's limitations.
# Idempotent: an unchanged listing is not rewritten (action=skip).
set -euo pipefail

SC_TAG="sample-checksums"
# shellcheck source=datasets/scripts/lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

check=0
root="$(sc_repo_root)"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --check) check=1 ;;
    --root) root="${2:?}"; shift ;;
    *) sc_die 2 "unknown argument: $1" ;;
  esac
  shift
done
samples="$root/datasets/samples"
[[ -d "$samples" ]] || sc_die 2 "samples directory not found: $samples"
sums="$samples/SHA256SUMS"

tmp="$(mktemp "${TMPDIR:-/tmp}/sc-sums.XXXXXX")"
trap 'rm -f "$tmp"' EXIT
(
  cd "$samples"
  find . -type f ! -name SHA256SUMS ! -name '*.part' -print | sed 's|^\./||' | LC_ALL=C sort |
    while IFS= read -r f; do
      printf '%s  %s\n' "$(sc_hash sha256 "$f")" "$f"
    done
) >"$tmp"

if [[ $check -eq 1 ]]; then
  [[ -f "$sums" ]] || sc_die 5 "SHA256SUMS missing"
  if cmp -s "$tmp" "$sums"; then
    sc_step "action=check result=ok files=$(wc -l <"$tmp" | tr -d ' ')"
    exit 0
  fi
  diff "$sums" "$tmp" >&2 || true
  sc_die 5 "SHA256SUMS does not match the sample files (left: recorded, right: actual)"
fi

if [[ -f "$sums" ]] && cmp -s "$tmp" "$sums"; then
  sc_step "action=skip reason=unchanged files=$(wc -l <"$tmp" | tr -d ' ')"
  exit 0
fi
cp -f "$tmp" "$sums"
sc_step "action=written files=$(wc -l <"$sums" | tr -d ' ') sha256_of_list=$(sc_hash sha256 "$sums")"
