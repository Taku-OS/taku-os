#!/bin/sh
set -eu
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
rustc --edition 2024 --test "$here/host_tests.rs" -o "$tmp/plugin-transaction-tests"
"$tmp/plugin-transaction-tests" --test-threads=1
