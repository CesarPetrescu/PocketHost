#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

echo "== Go tests =="
(cd go && go test ./...)

echo "== Go formatting =="
unformatted="$(gofmt -l go)"
if [[ -n "$unformatted" ]]; then
  echo "$unformatted"
  exit 1
fi

echo "== Local daemon verification =="
./scripts/verify-daemons-local.sh

echo "== Shell syntax =="
for script in scripts/*.sh scripts/ci/*.sh; do
  bash -n "$script"
done

echo "== Shell lint =="
if command -v shellcheck >/dev/null 2>&1; then
  shellcheck -S warning scripts/*.sh scripts/ci/*.sh
else
  echo "shellcheck not installed, skipping (CI runs it)"
fi

echo "== Repository checks =="
./scripts/ci/check-repo.sh local

echo "ok"
