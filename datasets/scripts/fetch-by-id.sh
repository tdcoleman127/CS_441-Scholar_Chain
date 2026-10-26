#!/usr/bin/env bash
# fetch-by-id.sh: bounded enrichment lookups by identifier, for a DBLP-derived subset.
# Owner: dataset-engineer.
#
# Usage:
#   fetch-by-id.sh crossref      --ids FILE --dest DIR [--max-ids N] [--delay S] [--base-url URL]
#   fetch-by-id.sh openalex      --ids FILE --dest DIR [--max-ids N] [--delay S] [--base-url URL]
#   fetch-by-id.sh opencitations --ids FILE --dest DIR [--max-ids N] [--delay S] [--base-url URL]
#   fetch-by-id.sh arxiv --from YYYY-MM-DD --until YYYY-MM-DD --dest DIR [--set SPEC]
#                        [--max-pages N] [--delay S] [--base-url URL]
#   Add --plan to print the request plan without any network access.
#
# IDS FILE: one DOI per line (bare form, for example 10.5555/sc441.0001). Blank lines and lines
#   starting with # are ignored. Invalid DOIs are reported and skipped.
# Bounds: --max-ids defaults to 25, hard cap 500. arXiv --max-pages defaults to 1, hard cap 5.
#   Each response is capped at 5 MiB. Delay between requests defaults to 1 s (3 s minimum and
#   default for arXiv, per the arXiv API Terms of Use: one request every three seconds).
# Endpoints (verified 2026-10-18, cited in datasets/dataset-manifest.yaml):
#   crossref       https://api.crossref.org/works/<doi>     (polite pool: set
#                  SCHOLARCHAIN_CROSSREF_MAILTO to a contact address; it is sent as mailto=)
#   openalex       https://api.openalex.org/works/doi:<doi>
#   opencitations  https://api.opencitations.net/index/v2/citations/doi:<doi>   (incoming) and
#                  .../references/doi:<doi>                                    (outgoing)
#   arxiv          https://oaipmh.arxiv.org/oai?verb=ListRecords&metadataPrefix=arXiv&...
# Idempotency: an existing non-empty response whose sha256 sidecar matches is skipped.
# No credentials are read or sent. Exit codes: 0 ok, 2 usage, 3 bound refused, 4 fetch failure.
set -euo pipefail

SC_TAG="fetch-by-id"
# shellcheck source=datasets/scripts/lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

readonly MAX_IDS_CAP=500
readonly MAX_PAGES_CAP=5
readonly RESPONSE_MAX=5242880

usage() {
  sed -n '2,26p' "${BASH_SOURCE[0]}" >&2
  exit 2
}

[[ $# -ge 1 ]] || usage
src="$1"
shift
ids="" dest="${SCHOLARCHAIN_DATA_DIR:-}" max_ids=25 delay="" base="" plan=0
from="" until="" set_spec="cs" max_pages=1
while [[ $# -gt 0 ]]; do
  case "$1" in
    --ids) ids="${2:?}"; shift ;;
    --dest) dest="${2:?}"; shift ;;
    --max-ids) max_ids="${2:?}"; shift ;;
    --delay) delay="${2:?}"; shift ;;
    --base-url) base="${2:?}"; shift ;;
    --from) from="${2:?}"; shift ;;
    --until) until="${2:?}"; shift ;;
    --set) set_spec="${2:?}"; shift ;;
    --max-pages) max_pages="${2:?}"; shift ;;
    --plan) plan=1 ;;
    -h | --help) usage ;;
    *) sc_die 2 "unknown argument: $1" ;;
  esac
  shift
done

case "$src" in
  crossref) base="${base:-https://api.crossref.org}"; delay="${delay:-1}" ;;
  openalex) base="${base:-https://api.openalex.org}"; delay="${delay:-1}" ;;
  opencitations) base="${base:-https://api.opencitations.net/index/v2}"; delay="${delay:-1}" ;;
  arxiv) base="${base:-https://oaipmh.arxiv.org/oai}"; delay="${delay:-3}" ;;
  *) sc_die 2 "unsupported source: $src (crossref|openalex|opencitations|arxiv)" ;;
esac
sc_is_uint "$delay" || sc_die 2 "--delay must be whole seconds"
if [[ "$src" == "arxiv" && "${base}" == https://oaipmh.arxiv.org/* ]] && ((delay < 3)); then
  sc_die 3 "arXiv requires at least 3 s between requests"
fi
[[ $plan -eq 1 || -n "$dest" ]] || sc_die 2 "no destination: pass --dest DIR or set SCHOLARCHAIN_DATA_DIR"

pause() { [[ "$delay" -gt 0 ]] && sleep "$delay" || true; }

# fetch_one <url> <out>: idempotent single request.
fetch_one() {
  local url="$1" out="$2" side="$2.sha256"
  if [[ -s "$out" && -f "$side" && "$(sc_hash sha256 "$out")" == "$(cat "$side")" ]]; then
    sc_step "action=skip source=$src out=$(basename "$out") reason=recorded-sha256-match"
    return 0
  fi
  sc_step "action=request source=$src url=$url"
  rm -f "$out.part"
  if ! sc_curl_bounded "$url" "$out.part" "$RESPONSE_MAX"; then
    rm -f "$out.part"
    sc_step "action=fail source=$src url=$url"
    return 4
  fi
  mv -f "$out.part" "$out"
  sc_hash sha256 "$out" >"$side"
  sc_step "action=recorded source=$src out=$(basename "$out") sha256=$(cat "$side") bytes=$(sc_file_bytes "$out")"
  pause
}

if [[ "$src" == "arxiv" ]]; then
  [[ "$from" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ && "$until" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] ||
    sc_die 2 "arxiv needs --from and --until as YYYY-MM-DD"
  [[ "$set_spec" =~ ^[A-Za-z0-9:._-]+$ ]] || sc_die 2 "invalid --set"
  sc_is_uint "$max_pages" || sc_die 2 "--max-pages must be an unsigned integer"
  ((max_pages <= MAX_PAGES_CAP)) || sc_die 3 "--max-pages $max_pages exceeds cap $MAX_PAGES_CAP"
  url="${base}?verb=ListRecords&metadataPrefix=arXiv&set=${set_spec}&from=${from}&until=${until}"
  if [[ $plan -eq 1 ]]; then
    sc_step "action=plan source=arxiv url=$url max_pages=$max_pages delay=$delay"
    exit 0
  fi
  mkdir -p "$dest/arxiv"
  page=1
  while ((page <= max_pages)); do
    out="$dest/arxiv/listrecords-${set_spec//[:]/_}-${from}-${until}-p${page}.xml"
    fetch_one "$url" "$out" || exit 4
    token="$(grep -o '<resumptionToken[^>]*>[^<]*</resumptionToken>' "$out" | sed 's/<[^>]*>//g' || true)"
    if [[ -z "$token" ]]; then
      sc_step "action=done source=arxiv pages=$page reason=no-resumption-token"
      exit 0
    fi
    url="${base}?verb=ListRecords&resumptionToken=${token}"
    page=$((page + 1))
  done
  sc_step "action=stop source=arxiv pages=$max_pages reason=max-pages-reached"
  exit 0
fi

[[ -n "$ids" && -f "$ids" ]] || sc_die 2 "--ids FILE is required for $src"
sc_is_uint "$max_ids" || sc_die 2 "--max-ids must be an unsigned integer"
((max_ids <= MAX_IDS_CAP)) || sc_die 3 "--max-ids $max_ids exceeds cap $MAX_IDS_CAP"

count=0 rc=0
[[ $plan -eq 1 ]] || mkdir -p "$dest/$src"
while IFS= read -r raw || [[ -n "$raw" ]]; do
  doi="$(printf '%s' "$raw" | tr -d '\r' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
  [[ -z "$doi" || "$doi" == \#* ]] && continue
  if ! [[ "$doi" =~ ^10\.[0-9]{4,9}/[^[:space:]]+$ ]]; then
    sc_step "action=reject source=$src id=$doi reason=not-a-bare-doi"
    continue
  fi
  if ((count >= max_ids)); then
    sc_step "action=stop source=$src reason=max-ids-reached max_ids=$max_ids"
    break
  fi
  count=$((count + 1))
  lc="$(printf '%s' "$doi" | tr '[:upper:]' '[:lower:]')"
  safe="$(printf '%s' "$lc" | tr -c 'a-z0-9._-' '_')"
  enc="$(printf '%s' "$lc" | sed 's/%/%25/g; s/#/%23/g; s/?/%3F/g; s/&/%26/g; s/ /%20/g')"
  case "$src" in
    crossref)
      urls=("${base}/works/${enc}${SCHOLARCHAIN_CROSSREF_MAILTO:+?mailto=${SCHOLARCHAIN_CROSSREF_MAILTO}}")
      outs=("$dest/$src/${safe}.json")
      ;;
    openalex)
      urls=("${base}/works/doi:${enc}")
      outs=("$dest/$src/${safe}.json")
      ;;
    opencitations)
      urls=("${base}/citations/doi:${enc}" "${base}/references/doi:${enc}")
      outs=("$dest/$src/${safe}.citations.json" "$dest/$src/${safe}.references.json")
      ;;
  esac
  for i in "${!urls[@]}"; do
    if [[ $plan -eq 1 ]]; then
      sc_step "action=plan source=$src id=$lc url=${urls[$i]}"
    else
      fetch_one "${urls[$i]}" "${outs[$i]}" || rc=4
    fi
  done
done <"$ids"
sc_step "action=done source=$src ids=$count"
exit "$rc"
