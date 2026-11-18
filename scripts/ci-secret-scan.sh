#!/bin/bash
set -euo pipefail
base=${SCAN_BASE:-}
head=${SCAN_HEAD:-HEAD}
head=$(git rev-parse --verify --end-of-options "$head^{commit}")
# A new branch (including a repository's initial commit) has no before SHA.
# Scan its reachable history; using origin/main as the base would skip the
# initial tree when origin/main already points to this same commit.
range=$head
if [[ "$base" =~ ^[0-9a-f]{40}$ ]] && [[ "$base" != 0000000000000000000000000000000000000000 ]]; then
  git cat-file -e "$base^{commit}"
  range="$base..$head"
fi
gitleaks git . --no-banner --redact=100 --log-opts="$range"
