#!/usr/bin/env bash
# sample-extract.sh: bounded excerpt extraction from a locally downloaded upstream file.
# Owner: dataset-engineer. It never touches the network.
#
# Usage:
#   sample-extract.sh dblp-xml --input dblp.xml[.gz] --keys KEYS  --out FILE [--max-records N]
#   sample-extract.sh jsonl    --input F.jsonl[.gz]  --dois DOIS  --out FILE [--max-records N]
#   sample-extract.sh csv      --input F.csv[.gz]    --dois DOIS  --out FILE [--max-records N]
#
# dblp-xml keeps the records whose key attribute is listed in KEYS (one key per line) and wraps
#   them in the dump's own XML header, DOCTYPE, and <dblp> root. It assumes that each record's
#   start tag begins a line, as in the sample; confirm this on the real dump before relying on it.
# jsonl keeps lines that contain any DOI from DOIS anywhere in the line (case-insensitive fixed
#   string), so a work that only cites the DOI is kept too. Exact filtering is the parser's job.
# csv keeps the header line plus matching lines.
# Bounds: --max-records defaults to 100, hard cap 1000. If --out resolves (missing directories,
#   '..', and case included) inside datasets/samples, the result must be at most 65536 bytes.
#   The file written is exactly that resolved path. A symlink --out is refused, and so is a
#   backslash in --out except on MSYS/Cygwin, where it is a separator (SC_TEST_PATH_STYLE: tests).
# Idempotency: an existing --out with identical content is left untouched (action=skip).
# After writing, the sha256 is printed. Record it with record-sample-checksums.sh and in the
# manifest. License check is manual: only extract from sources whose license permits
# redistribution (see the manifest), and record the excerpt's provenance there.
set -euo pipefail

SC_TAG="sample-extract"
# shellcheck source=datasets/scripts/lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

readonly MAX_RECORDS_CAP=1000
readonly SAMPLE_MAX_BYTES=65536

[[ $# -ge 1 ]] || { sed -n '2,23p' "${BASH_SOURCE[0]}" >&2; exit 2; }
mode="$1"
shift
input="" keys="" out="" max=100
while [[ $# -gt 0 ]]; do
  case "$1" in
    --input) input="${2:?}"; shift ;;
    --keys | --dois) keys="${2:?}"; shift ;;
    --out) out="${2:?}"; shift ;;
    --max-records) max="${2:?}"; shift ;;
    *) sc_die 2 "unknown argument: $1" ;;
  esac
  shift
done
[[ -f "$input" ]] || sc_die 2 "--input file not found: $input"
[[ -f "$keys" ]] || sc_die 2 "--keys/--dois file not found: $keys"
[[ -n "$out" ]] || sc_die 2 "--out is required"
sc_is_uint "$max" || sc_die 2 "--max-records must be an unsigned integer"
((max <= MAX_RECORDS_CAP)) || sc_die 3 "--max-records $max exceeds cap $MAX_RECORDS_CAP"

# Path style decides what a backslash in --out means. windows (MSYS, MinGW, Cygwin, Git Bash): a
# separator, normalized to '/' once. posix (Linux, CI, WSL, macOS): a literal filename character,
# so an --out containing one is refused, because the cap decision and the file name would differ.
# SC_TEST_PATH_STYLE=posix|windows forces the branch; it exists for run-tests.sh only. Forcing it
# cannot skip the cap: the script always writes exactly the path it resolved.
path_style="${SC_TEST_PATH_STYLE:-}"
if [[ -z "$path_style" ]]; then
  case "$(uname -s 2>/dev/null || echo unknown)" in
    MINGW* | MSYS* | CYGWIN*) path_style=windows ;;
    *) path_style=posix ;;
  esac
fi
case "$path_style" in
  windows) out="${out//\\//}" ;;
  posix) [[ "$out" != *\\* ]] || sc_die 2 "--out contains a backslash, which is a literal filename character on this host; use '/' separators: $out" ;;
  *) sc_die 2 "SC_TEST_PATH_STYLE must be posix or windows, not: $path_style" ;;
esac

reader() {
  case "$input" in
    *.gz) gzip -dc -- "$input" ;;
    *) cat -- "$input" ;;
  esac
}

tmp="$(mktemp "${TMPDIR:-/tmp}/sc-extract.XXXXXX")"
trap 'rm -f "$tmp" "$tmp.keys"' EXIT
tr -d '\r' <"$keys" | sed '/^[[:space:]]*$/d;/^#/d' >"$tmp.keys"

sc_step "action=extract mode=$mode input=$(basename "$input") max_records=$max"
# Early exit (head, awk exit) closes the pipe on purpose; SIGPIPE in the reader is expected,
# so pipefail is off for the extraction only. Input integrity is the fetch checksum's job.
set +o pipefail
case "$mode" in
  dblp-xml)
    {
      printf '<?xml version="1.0" encoding="ISO-8859-1"?>\n<!DOCTYPE dblp SYSTEM "dblp.dtd">\n<dblp>\n'
      reader | awk -v max="$max" -v keyfile="$tmp.keys" '
        BEGIN {
          while ((getline k < keyfile) > 0) want[k] = 1
          types = "^<(article|inproceedings|proceedings|book|incollection|phdthesis|mastersthesis|www|person|data)[ >]"
        }
        !inrec && $0 ~ types {
          t = $0; sub(/^</, "", t); sub(/[ >].*$/, "", t)
          k = ""
          if (match($0, /key="[^"]*"/)) k = substr($0, RSTART + 5, RLENGTH - 6)
          inrec = 1; keep = (k in want) && n < max; buf = ""
        }
        inrec {
          if (keep) buf = buf $0 "\n"
          if (index($0, "</" t ">")) {
            if (keep) { printf "%s", buf; n++ }
            inrec = 0
            if (n >= max) exit
          }
        }
      '
      printf '</dblp>\n'
    } >"$tmp"
    ;;
  jsonl | csv)
    # Case-insensitive fixed-string match in awk (GNU grep 3.0 on MSYS aborts on grep -i -F -f).
    reader | awk -v max="$max" -v keyfile="$tmp.keys" -v header="$([[ $mode == csv ]] && echo 1 || echo 0)" '
      BEGIN { while ((getline k < keyfile) > 0) want[++nk] = tolower(k) }
      header == 1 && NR == 1 { print; next }
      {
        l = tolower($0)
        for (i = 1; i <= nk; i++) if (index(l, want[i])) { print; if (++n >= max) exit; break }
      }
    ' >"$tmp"
    ;;
  *) sc_die 2 "unknown mode: $mode (dblp-xml|jsonl|csv)" ;;
esac
set -o pipefail

# resolve_out PATH: print the absolute, normalized form of PATH without requiring it to exist.
# The nearest existing ancestor is resolved physically (cd + pwd -P); the missing remainder is
# normalized lexically ('.' dropped, '..' pops). Backslashes were handled above. Returns 1 on failure.
resolve_out() {
  local p="$1" d rest="" base part
  local -a parts stack
  d="$(dirname -- "$p")"
  rest="$(basename -- "$p")"
  while [[ ! -d "$d" ]]; do
    [[ "$d" == "." || "$d" == "/" ]] && return 1
    rest="$(basename -- "$d")/$rest"
    d="$(dirname -- "$d")"
  done
  base="$(cd -- "$d" 2>/dev/null && pwd -P)" || return 1
  [[ "$base" == /* ]] || return 1
  IFS=/ read -r -a parts <<<"${base#/}/$rest"
  stack=()
  for part in "${parts[@]}"; do
    case "$part" in
      "" | .) ;;
      ..) ((${#stack[@]} > 0)) && unset 'stack[${#stack[@]}-1]' ;;
      *) stack+=("$part") ;;
    esac
  done
  local IFS=/
  printf '/%s\n' "${stack[*]}"
}

[[ -d "$out" ]] && sc_die 2 "--out is a directory: $out (name the output file)"
case "$(basename -- "$out")" in
  . | .. | "") sc_die 2 "--out must name a file: $out" ;;
esac
bytes="$(sc_file_bytes "$tmp")"
# Fail closed: an --out that cannot be resolved is refused, not treated as outside the samples.
resolved="$(resolve_out "$out")" || sc_die 2 "cannot resolve --out path: $out"
[[ -n "$resolved" && "$resolved" != "/" ]] || sc_die 2 "cannot resolve --out path: $out"
# An existing symlink at --out (dangling or not) is refused: cp -f would write through it to a
# target the cap decision never saw. Ancestors are already resolved physically above.
[[ -L "$resolved" ]] && sc_die 2 "--out is a symlink; name a regular file: $out"
[[ -d "$resolved" ]] && sc_die 2 "--out is a directory: $out (name the output file)"
# Case-insensitive on every platform: Windows and MSYS paths are case-insensitive, and on a
# case-sensitive file system the extra matches only make the cap stricter.
case "$(printf '%s' "$resolved" | tr '[:upper:]' '[:lower:]')" in
  */datasets/samples/*)
    ((bytes <= SAMPLE_MAX_BYTES)) || sc_die 3 "excerpt is $bytes bytes; repository samples are limited to $SAMPLE_MAX_BYTES (out resolves to $resolved)"
    ;;
esac

# From here on only the resolved path is used, so the file written is the file the cap judged.
if [[ -f "$resolved" ]] && cmp -s "$tmp" "$resolved"; then
  sc_step "action=skip out=$resolved reason=identical-content sha256=$(sc_hash sha256 "$resolved")"
  exit 0
fi
mkdir -p -- "$(dirname -- "$resolved")"
cp -f -- "$tmp" "$resolved"
sc_step "action=written out=$resolved bytes=$bytes sha256=$(sc_hash sha256 "$resolved")"
