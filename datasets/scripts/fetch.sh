#!/usr/bin/env bash
# fetch.sh: bounded, idempotent download of upstream dataset artifacts. Owner: dataset-engineer.
#
# Usage:
#   fetch.sh <dataset|all> [--plan]                         (default; prints the plan, no network)
#   fetch.sh <dataset|all> --metadata --dest DIR [--max-bytes N] [--refresh]
#   fetch.sh <dataset|all> --full     --dest DIR  --max-bytes N  [--refresh]
#   Options: --sources FILE (default: datasets/scripts/sources.tsv)
#
# Bounds:
#   --metadata downloads only tier=metadata rows. Each row's estimate and actual size must be at
#     most --max-bytes (default 10485760 = 10 MiB; ceiling 52428800 = 50 MiB).
#   --full downloads only tier=full rows, and only with an explicit --max-bytes. It refuses when
#     the summed estimates exceed the bound or any estimate is unknown. A full download also needs a
#     human-approved size estimate (CLAUDE.md escalation policy); this script enforces the bound
#     but cannot record the approval, so the operator supplies the approved number as --max-bytes.
#     --full is human-invoked only (docs/agents.md section 7b); no agent may run it.
#   Time budget per file: SC_CURL_MAX_TIME if set, else max(600 s, max-bytes at 1 MiB/s).
# Idempotency: a file whose digest already matches is skipped. Downloads go to <file>.part and
#   are renamed only after verification. A digest mismatch deletes the partial file and fails.
# Destination: there is no default inside the repository. Use --dest or SCHOLARCHAIN_DATA_DIR.
# Exit codes: 0 ok, 2 usage, 3 bound refused, 4 download or size failure, 5 checksum mismatch.
set -euo pipefail

SC_TAG="fetch"
# shellcheck source=datasets/scripts/lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

readonly METADATA_DEFAULT_MAX=10485760
readonly METADATA_CEILING=52428800

usage() {
  sed -n '2,22p' "${BASH_SOURCE[0]}" >&2
  exit 2
}

dataset=""
mode="plan"
dest="${SCHOLARCHAIN_DATA_DIR:-}"
max_bytes=""
refresh=0
max_time=""
sources="$(dirname "${BASH_SOURCE[0]}")/sources.tsv"

[[ $# -ge 1 ]] || usage
dataset="$1"
shift
while [[ $# -gt 0 ]]; do
  case "$1" in
    --plan) mode="plan" ;;
    --metadata) mode="metadata" ;;
    --full) mode="full" ;;
    --dest) dest="${2:?--dest needs a directory}"; shift ;;
    --max-bytes) max_bytes="${2:?--max-bytes needs a number}"; shift ;;
    --sources) sources="${2:?--sources needs a file}"; shift ;;
    --refresh) refresh=1 ;;
    -h | --help) usage ;;
    *) sc_die 2 "unknown argument: $1" ;;
  esac
  shift
done

[[ -f "$sources" ]] || sc_die 2 "sources table not found: $sources"
[[ "$dataset" =~ ^[a-z0-9-]+$ ]] || sc_die 2 "invalid dataset name: $dataset"

case "$mode" in
  metadata)
    max_bytes="${max_bytes:-$METADATA_DEFAULT_MAX}"
    sc_is_uint "$max_bytes" || sc_die 2 "--max-bytes must be an unsigned integer"
    ((max_bytes <= METADATA_CEILING)) || sc_die 3 "metadata bound $max_bytes exceeds ceiling $METADATA_CEILING"
    ;;
  full)
    [[ -n "$max_bytes" ]] || sc_die 3 "--full requires an explicit --max-bytes (the human-approved size estimate)"
    sc_is_uint "$max_bytes" || sc_die 2 "--max-bytes must be an unsigned integer"
    ;;
esac
if [[ "$mode" != "plan" ]]; then
  [[ -n "$dest" ]] || sc_die 2 "no destination: pass --dest DIR or set SCHOLARCHAIN_DATA_DIR"
  sc_require_cmd curl awk
  max_time="$(sc_curl_time_budget "$max_bytes")"
fi

# Select rows: dataset match (or all) and, outside plan mode, the tier that matches the mode.
rows=()
while IFS= read -r line; do
  rows+=("$line")
done < <(awk -F '\t' -v ds="$dataset" -v mode="$mode" '
  /^#/ || NF == 0 { next }
  NF != 8 { printf "BADROW %d\n", NR; next }
  (ds == "all" || $1 == ds) && (mode == "plan" || $3 == mode) { print }
' "$sources")

for r in "${rows[@]+"${rows[@]}"}"; do
  [[ "$r" == BADROW* ]] && sc_die 2 "malformed sources row (need 8 tab-separated columns): ${r#BADROW }"
done

if [[ ${#rows[@]} -eq 0 ]]; then
  sc_step "action=none dataset=$dataset mode=$mode reason=no-matching-artifacts"
  exit 0
fi

# Bound check for --full before any network activity.
if [[ "$mode" == "full" ]]; then
  total=0
  for r in "${rows[@]}"; do
    IFS=$'\t' read -r ds art tier url algo sum est fname <<<"$r"
    sc_is_uint "$est" || sc_die 3 "artifact $ds/$art has unknown size estimate; full download refused"
    total=$((total + est))
  done
  ((total <= max_bytes)) || sc_die 3 "estimated total $total bytes exceeds approved bound $max_bytes"
  sc_step "action=bound-ok dataset=$dataset est_total=$total max_bytes=$max_bytes"
fi

fail=0
for r in "${rows[@]}"; do
  IFS=$'\t' read -r ds art tier url algo sum est fname <<<"$r"
  if [[ "$mode" == "plan" ]]; then
    sc_step "action=plan dataset=$ds artifact=$art tier=$tier est_bytes=$est algo=$algo url=$url"
    continue
  fi
  [[ "$fname" =~ ^[A-Za-z0-9._-]+$ ]] || sc_die 2 "unsafe filename in sources: $fname"
  if [[ "$mode" == "metadata" ]] && sc_is_uint "$est" && ((est > max_bytes)); then
    sc_die 3 "artifact $ds/$art estimate $est exceeds metadata bound $max_bytes"
  fi
  outdir="$dest/$ds"
  mkdir -p "$outdir"
  out="$outdir/$fname"
  side="$out.sha256"

  # Idempotency check.
  if [[ -f "$out" && $refresh -eq 0 ]]; then
    if [[ "$algo" != "none" ]]; then
      if [[ "$(sc_hash "$algo" "$out")" == "$sum" ]]; then
        sc_step "action=skip dataset=$ds artifact=$art reason=checksum-match algo=$algo"
        continue
      fi
      sc_step "action=stale dataset=$ds artifact=$art reason=checksum-mismatch-redownload"
    elif [[ -f "$side" && "$(sc_hash sha256 "$out")" == "$(cat "$side")" ]]; then
      sc_step "action=skip dataset=$ds artifact=$art reason=recorded-sha256-match"
      continue
    fi
  fi

  sc_step "action=download dataset=$ds artifact=$art url=$url max_bytes=$max_bytes max_time=$max_time"
  rm -f "$out.part"
  sc_curl_bounded "$url" "$out.part" "$max_bytes"
  if [[ "$algo" != "none" ]]; then
    got="$(sc_hash "$algo" "$out.part")"
    if [[ "$got" != "$sum" ]]; then
      rm -f "$out.part"
      sc_step "action=fail dataset=$ds artifact=$art reason=checksum-mismatch algo=$algo expected=$sum got=$got"
      fail=5
      continue
    fi
    mv -f "$out.part" "$out"
    sc_step "action=verified dataset=$ds artifact=$art algo=$algo digest=$got bytes=$(sc_file_bytes "$out")"
  else
    mv -f "$out.part" "$out"
    sha="$(sc_hash sha256 "$out")"
    printf '%s' "$sha" >"$side"
    sc_step "action=recorded dataset=$ds artifact=$art sha256=$sha bytes=$(sc_file_bytes "$out") note=record-in-manifest"
  fi
done

exit "$fail"
