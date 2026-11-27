#!/bin/bash
set -euo pipefail
base=${SCAN_BASE:-}
head=${SCAN_HEAD:-HEAD}
head=$(git rev-parse --verify --end-of-options "$head^{commit}")
forced_push() {
  [[ ${GITHUB_EVENT_NAME:-} == push && -f ${GITHUB_EVENT_PATH:-} ]] || return 1
  python3 - "$GITHUB_EVENT_PATH" "$base" "$head" <<'EVENT'
import json
import sys

try:
    with open(sys.argv[1], encoding="utf-8") as payload:
        event = json.load(payload)
except (OSError, ValueError):
    sys.exit(1)
matched = (isinstance(event, dict) and event.get("forced") is True
           and event.get("before") == sys.argv[2] and event.get("after") == sys.argv[3])
sys.exit(0 if matched else 1)
EVENT
}

# A new branch (including a repository's initial commit) has no before SHA.
# Scan its reachable history; using origin/main as the base would skip the
# initial tree when origin/main already points to this same commit.
range=$head
if [[ "$base" =~ ^[0-9a-f]{40}$ ]] && [[ "$base" != 0000000000000000000000000000000000000000 ]]; then
  if git cat-file -e "$base^{commit}" 2>/dev/null; then
    range="$base..$head"
  elif forced_push; then
    # The previous tip can be unreachable after a history rewrite. Scanning
    # all history reachable from the new tip still covers every introduced commit.
    printf '%s\n' 'Previous tip unavailable after force push. Scanning reachable history.' >&2
  else
    printf 'Secret scan base commit is unavailable: %s\n' "$base" >&2
    exit 1
  fi
fi
gitleaks git . --no-banner --redact=100 --log-opts="$range"
