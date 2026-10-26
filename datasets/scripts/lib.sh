#!/usr/bin/env bash
# Shared helpers for ScholarChain dataset scripts. Owner: dataset-engineer.
# Sourced, never executed. Every script that sources it already runs in strict mode.
#
# Step markers: every observable action prints one line to stderr that starts with "STEP ",
# followed by the script tag and key=value pairs, for example
#   STEP fetch dataset=dblp artifact=dtd action=skip reason=checksum-match
# so logs can be grepped with: grep '^STEP ' log | grep 'action=fail'

SC_TAG="${SC_TAG:-datasets}"

sc_step() {
  # sc_step <action-name> key=value ...
  printf 'STEP %s %s\n' "$SC_TAG" "$*" >&2
}

sc_die() {
  # sc_die <exit-code> <message>
  local code="$1"
  shift
  printf 'STEP %s action=fail reason=%s\n' "$SC_TAG" "$(printf '%s' "$*" | tr ' ' '_')" >&2
  printf 'ERROR: %s\n' "$*" >&2
  exit "$code"
}

sc_require_cmd() {
  local c
  for c in "$@"; do
    command -v "$c" >/dev/null 2>&1 || sc_die 2 "required command not found: $c"
  done
}

sc_is_uint() {
  [[ "${1:-}" =~ ^[0-9]+$ ]]
}

sc_file_bytes() {
  # Portable byte count (GNU stat and BSD stat disagree; wc -c is everywhere).
  wc -c <"$1" | tr -d ' '
}

sc_hash() {
  # sc_hash <sha256|md5> <file>  -> prints the lowercase hex digest
  local algo="$1" file="$2"
  case "$algo" in
    sha256)
      if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$file" | awk '{print $1}'
      else
        shasum -a 256 "$file" | awk '{print $1}'
      fi
      ;;
    md5)
      if command -v md5sum >/dev/null 2>&1; then
        md5sum "$file" | awk '{print $1}'
      else
        md5 -q "$file"
      fi
      ;;
    *) sc_die 2 "unsupported hash algorithm: $algo" ;;
  esac
}

sc_repo_root() {
  # The repository root, derived from this file's location (datasets/scripts/lib.sh).
  local here
  here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  (cd "$here/../.." && pwd)
}

readonly SC_CURL_TIME_FLOOR=600
readonly SC_CURL_MIN_RATE=1048576

sc_curl_time_budget() {
  # sc_curl_time_budget <max-bytes> -> seconds allowed for one bounded transfer.
  # SC_CURL_MAX_TIME wins when set. Otherwise the larger of 600 s and the time to move
  # <max-bytes> at 1 MiB/s, so an approved large bound (for example the 1.2 GB dblp release
  # bound) is not silently refused by a fixed 600 s timeout. The byte cap is unaffected.
  local max="$1" t
  sc_is_uint "$max" || sc_die 2 "max bytes is not an unsigned integer: $max"
  if [[ -n "${SC_CURL_MAX_TIME:-}" ]]; then
    sc_is_uint "$SC_CURL_MAX_TIME" || sc_die 2 "SC_CURL_MAX_TIME is not an unsigned integer: $SC_CURL_MAX_TIME"
    printf '%s' "$SC_CURL_MAX_TIME"
    return 0
  fi
  t=$(((max + SC_CURL_MIN_RATE - 1) / SC_CURL_MIN_RATE))
  ((t > SC_CURL_TIME_FLOOR)) || t=$SC_CURL_TIME_FLOOR
  printf '%s' "$t"
}

sc_curl_bounded() {
  # sc_curl_bounded <url> <out-file> <max-bytes>
  # Downloads to <out-file> with a hard size cap. Only https and file URLs are allowed
  # (file URLs exist for the offline test suite). No retries that could multiply transfer.
  local url="$1" out="$2" max="$3" max_time
  sc_is_uint "$max" || sc_die 2 "max bytes is not an unsigned integer: $max"
  max_time="$(sc_curl_time_budget "$max")" || return 2
  if ! curl --fail --silent --show-error --location \
    --proto '=https,file' --proto-redir '=https' \
    --max-filesize "$max" --max-time "$max_time" \
    --user-agent "ScholarChain-dataset-scripts/1 (bounded; CS441 course project)" \
    --output "$out" "$url"; then
    rm -f "$out"
    printf 'STEP %s action=fail reason=curl-error url=%s\n' "$SC_TAG" "$url" >&2
    return 4
  fi
  local got
  got="$(sc_file_bytes "$out")"
  if ((got > max)); then
    rm -f "$out"
    sc_die 4 "download exceeded bound: $got > $max bytes from $url"
  fi
}
