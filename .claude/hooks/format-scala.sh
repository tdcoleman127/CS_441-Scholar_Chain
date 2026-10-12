#!/usr/bin/env bash
# PostToolUse hook (matcher: Edit|Write|MultiEdit). Formats the one Scala or sbt file that was
# just written, using the repository's .scalafmt.conf. A convenience never blocks: this hook
# always exits 0 and exits harmlessly when no Scala file changed or no formatter is available.
# Formatter preference: scalafmt CLI (fast), then the sbt thin client (scalafmtOnly / scalafmtSbt).
# Owner: factory-engineer (constitutional artifact). Formatter configuration (.scalafmt.conf) is
# owned by build-engineer.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
. "$HERE/common.sh"
SC_HOOK="format-scala"

SC_INPUT="$(cat)"
export SC_INPUT
SC_PY="$(sc_python)" || { echo "format-scala: no Python 3 found; skipping formatting." >&2; exit 0; }
file="$(sc_field tool_input.file_path)" || exit 0

case "$file" in
  *.scala|*.sbt|*.sc) ;;
  *) exit 0 ;;
esac
[ -f "$file" ] || exit 0

root="$(sc_repo_root)"
if [ ! -f "$root/.scalafmt.conf" ]; then
  echo "format-scala: $root/.scalafmt.conf does not exist yet (build-engineer creates it); skipping." >&2
  exit 0
fi

if command -v scalafmt >/dev/null 2>&1; then
  scalafmt --quiet --config "$root/.scalafmt.conf" "$file" >/dev/null 2>&1 \
    || echo "format-scala: scalafmt reported a problem with $file; 'sbt check' will surface it." >&2
elif command -v sbt >/dev/null 2>&1 && [ -f "$root/build.sbt" ]; then
  cls="$(sc_classify_path "$file")"
  rel="${cls#* }"
  if [ "${cls%% *}" = "REL" ]; then
    case "$file" in
      *.sbt) task="scalafmtSbt" ;;
      *)     task="scalafmtOnly $rel" ;;
    esac
    (cd "$root" && sbt --client "$task") >/dev/null 2>&1 \
      || echo "format-scala: 'sbt --client $task' failed; 'sbt check' will surface formatting drift." >&2
  fi
else
  echo "format-scala: neither scalafmt nor an sbt build is available; skipping $file." >&2
fi
exit 0
