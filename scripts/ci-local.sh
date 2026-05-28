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
for script in scripts/*.sh; do
  bash -n "$script"
done

echo "== Repository checks =="
test -f LICENSE
test -f NOTICE
test -f AGENTS.md
test -f SOUL.md
test -f FLYWHEEL.md

echo "ok"
