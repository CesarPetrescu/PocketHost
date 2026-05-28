#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GO_ROOT="$ROOT/go"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/pockethost-verify.XXXXXX")"
PIDS=()
TOKEN="verify-token"

cleanup() {
  for pid in "${PIDS[@]:-}"; do
    if kill -0 "$pid" >/dev/null 2>&1; then
      kill "$pid" >/dev/null 2>&1 || true
    fi
  done
  wait >/dev/null 2>&1 || true
  rm -rf "$TMP"
}
trap cleanup EXIT

mkdir -p "$TMP/bin" "$TMP/www/dir" "$TMP/files" "$TMP/logs"
echo "private" > "$TMP/www/dir/private.txt"

cd "$GO_ROOT"
for cmd in hostd webd filed proxyd ddnsd; do
  go build -o "$TMP/bin/$cmd" "./cmd/$cmd"
done

if "$TMP/bin/webd" --addr 0.0.0.0:18180 --data-dir "$TMP/www" >"$TMP/logs/public-bind.log" 2>&1; then
  echo "webd unexpectedly allowed public bind without override" >&2
  exit 1
fi
echo "ok public bind guard rejects 0.0.0.0 by default"

POCKETHOST_TOKEN="$TOKEN" "$TMP/bin/hostd" --addr 127.0.0.1:18099 >"$TMP/logs/hostd.log" 2>&1 & PIDS+=("$!")
"$TMP/bin/webd" --addr 127.0.0.1:18081 --data-dir "$TMP/www" >"$TMP/logs/webd.log" 2>&1 & PIDS+=("$!")
POCKETHOST_TOKEN="$TOKEN" "$TMP/bin/filed" --addr 127.0.0.1:18090 --data-dir "$TMP/files" >"$TMP/logs/filed.log" 2>&1 & PIDS+=("$!")
"$TMP/bin/proxyd" --addr 127.0.0.1:18088 --routes "web.local=http://127.0.0.1:18081,files.local=http://127.0.0.1:18090" >"$TMP/logs/proxyd.log" 2>&1 & PIDS+=("$!")
POCKETHOST_TOKEN="$TOKEN" "$TMP/bin/ddnsd" --addr 127.0.0.1:18091 --interval 1h >"$TMP/logs/ddnsd.log" 2>&1 & PIDS+=("$!")

POCKETHOST_VERIFY_TOKEN="$TOKEN" python3 - <<'PY'
import json
import os
import sys
import time
import urllib.error
import urllib.request

TOKEN = os.environ["POCKETHOST_VERIFY_TOKEN"]
checks = [
    ("hostd", "http://127.0.0.1:18099/health"),
    ("webd", "http://127.0.0.1:18081/health"),
    ("filed", "http://127.0.0.1:18090/health"),
    ("proxyd", "http://127.0.0.1:18088/health"),
    ("ddnsd", "http://127.0.0.1:18091/health"),
]

def fetch_any(url, headers=None):
    req = urllib.request.Request(url, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=2) as resp:
            body = resp.read().decode("utf-8", "replace")
            return resp.status, body, dict(resp.headers)
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", "replace")
        return exc.code, body, dict(exc.headers)

def fetch(url, headers=None):
    status, body, headers = fetch_any(url, headers)
    if status >= 400:
        raise RuntimeError(f"status={status} body={body[:160]}")
    return status, body, headers

def wait_json(name, url):
    last = None
    for _ in range(60):
        try:
            status, body, headers = fetch(url)
            data = json.loads(body)
            if status == 200 and data.get("status") == "ok":
                if "nosniff" not in headers.get("X-Content-Type-Options", ""):
                    raise RuntimeError("missing security header")
                print(f"ok {name} {url}")
                return
            last = f"status={status} body={body[:160]}"
        except Exception as exc:  # noqa: BLE001 - diagnostic script
            last = repr(exc)
        time.sleep(0.25)
    raise SystemExit(f"{name} did not become healthy: {last}")

for name, url in checks:
    wait_json(name, url)

status, body, _ = fetch("http://127.0.0.1:18088/", {"Host": "web.local"})
if status != 200 or "PocketHost webd" not in body:
    raise SystemExit(f"proxy route failed: status={status} body={body[:160]}")
print("ok proxyd host route web.local -> webd")

status, body, _ = fetch_any("http://127.0.0.1:18081/dir/")
if status != 403:
    raise SystemExit(f"webd directory listing should be blocked: status={status} body={body[:160]}")
print("ok webd directory listing disabled")

status, body, _ = fetch_any("http://127.0.0.1:18090/api/files")
if status != 401:
    raise SystemExit(f"filed unauthenticated list should fail: status={status} body={body[:160]}")
print("ok filed token required")

status, body, _ = fetch("http://127.0.0.1:18090/api/files", {"X-PocketHost-Token": TOKEN})
if status != 200 or '"items"' not in body:
    raise SystemExit(f"filed list failed: status={status} body={body[:160]}")
print("ok filed list API with token")

status, body, _ = fetch_any("http://127.0.0.1:18099/api/status")
if status != 401:
    raise SystemExit(f"hostd unauthenticated status should fail: status={status} body={body[:160]}")
status, body, _ = fetch("http://127.0.0.1:18099/api/status", {"Authorization": f"Bearer {TOKEN}"})
if '"runtime"' not in body:
    raise SystemExit(f"hostd status missing runtime: status={status} body={body[:160]}")
print("ok hostd status token gate")
PY
