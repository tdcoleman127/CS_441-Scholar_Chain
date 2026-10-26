#!/usr/bin/env bash
# ScholarChain hook library. Sourced by the hook scripts in this directory; it is never
# registered as a hook itself. Owner: factory-engineer (constitutional artifact).
#
# Exit-code protocol used by every hook: 0 = proceed, 2 = block (stderr goes back to the
# model as a course correction), anything else = non-blocking error.

SC_HOOK="${SC_HOOK:-scholarchain-hook}"

# Block with an actionable message and exit 2.
sc_block() {
  printf 'BLOCKED by %s: %s\n' "$SC_HOOK" "$*" >&2
  exit 2
}

# Print the first Python 3 interpreter that actually runs. A shim on PATH that fails to
# start (for example a broken pyenv shim) is skipped.
sc_python() {
  local c
  for c in python3 python py; do
    if command -v "$c" >/dev/null 2>&1 && "$c" -c 'import json, os, sys' >/dev/null 2>&1; then
      printf '%s\n' "$c"
      return 0
    fi
  done
  return 1
}

# sc_field <dotted.path>: print one field of the hook JSON held in $SC_INPUT.
# Booleans print as true/false; lists print one item per line; a missing field prints nothing.
# Returns 3 when the hook input is not valid JSON.
sc_field() {
  SC_FIELD="$1" PYTHONIOENCODING=utf-8 PYTHONUTF8=1 "$SC_PY" -c '
import json, os, sys
try:
    cur = json.loads(os.environ.get("SC_INPUT") or "{}")
except Exception:
    sys.exit(3)
for part in os.environ["SC_FIELD"].split("."):
    cur = cur.get(part) if isinstance(cur, dict) else None
if cur is None:
    sys.exit(0)
if isinstance(cur, bool):
    print("true" if cur else "false")
elif isinstance(cur, list):
    print("\n".join(str(x) for x in cur))
else:
    print(cur)
'
}

# Repository root: CLAUDE_PROJECT_DIR when Claude Code provides it, else git, else cwd.
sc_repo_root() {
  if [ -n "${CLAUDE_PROJECT_DIR:-}" ]; then
    printf '%s\n' "$CLAUDE_PROJECT_DIR"
  else
    git rev-parse --show-toplevel 2>/dev/null || pwd
  fi
}

# sc_classify_path <path>: print exactly one line:
#   REL <repo-relative path, forward slashes>   the path is inside the repository
#   TEMP <absolute path>                        the path is in the session scratchpad or a temp dir
#   OUTSIDE <absolute path>                     anything else
# MSYS-style paths (/c/Users/...) are understood on Windows.
sc_classify_path() {
  SC_PATH="$1" SC_ROOT="$(sc_repo_root)" SC_CWD="$(sc_field cwd)" \
  SC_SCRATCH="$(sc_field scratchpad_dir)" PYTHONIOENCODING=utf-8 PYTHONUTF8=1 "$SC_PY" -c '
import os, re
def norm(p):
    p = (p or "").replace("\\", "/")
    if os.name == "nt":
        m = re.match(r"^/(?:cygdrive/|mnt/)?([a-zA-Z])(?:/|$)(.*)$", p)
        if m:
            p = m.group(1) + ":/" + m.group(2)
    return p
def absn(p, base):
    p = norm(p)
    if not os.path.isabs(p):
        p = os.path.join(base, p)
    return os.path.normcase(os.path.normpath(os.path.abspath(p)))
def inside(child, parent):
    try:
        rel = os.path.relpath(child, parent)
    except ValueError:
        return None
    if rel == os.pardir or rel.startswith(os.pardir + os.sep):
        return None
    return rel
root = absn(os.environ["SC_ROOT"], os.getcwd())
cwd = absn(os.environ.get("SC_CWD") or os.environ["SC_ROOT"], root)
target = absn(os.environ["SC_PATH"], cwd)
rel = inside(target, root)
if rel is not None:
    print("REL " + ("." if rel == os.curdir else rel.replace(os.sep, "/")))
else:
    temps = [os.environ.get(k) for k in ("SC_SCRATCH", "TMPDIR", "TEMP", "TMP")] + ["/tmp"]
    for t in temps:
        if t and inside(target, absn(t, root)) is not None:
            print("TEMP " + target.replace(os.sep, "/"))
            break
    else:
        print("OUTSIDE " + target.replace(os.sep, "/"))
'
}
