#!/usr/bin/env bash
set -euo pipefail

# Fetches Termux's PHP runtime packages and stages a relocatable Android PHP
# runtime asset for PocketHost. This is a faster alternative to the Docker
# source build when the bundled libphp.so was built from the same Termux PHP
# package version.
#
# Usage:
#   ./scripts/fetch-termux-php-runtime.sh x86_64
#   ./scripts/fetch-termux-php-runtime.sh arm64-v8a

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TERMUX_BASE="${TERMUX_BASE:-https://termux.net}"
DEPS_DIR="${POCKETHOST_ASSETS_DIR:-$ROOT_DIR/android/deps/assets}"
WORK="${WORK:-/tmp/pockethost-termux-php-runtime}"
ROOT_PACKAGES=(php php-gd php-sodium)
REQUIRED_EXTS=(sqlite3 pdo_sqlite mbstring intl xml xmlreader xmlwriter simplexml dom zip curl gd fileinfo openssl sodium ctype session zlib posix)

abi_to_termux() {
  case "$1" in
    arm64-v8a) echo "aarch64" ;;
    x86_64) echo "x86_64" ;;
    *) echo "" ;;
  esac
}

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <arm64-v8a|x86_64>" >&2
  exit 2
fi

ABI="$1"
TERMUX_ARCH="$(abi_to_termux "$ABI")"
if [[ -z "$TERMUX_ARCH" ]]; then
  echo "Unsupported PHP runtime ABI: $ABI" >&2
  exit 2
fi

command -v curl >/dev/null || { echo "curl is required" >&2; exit 2; }
command -v python3 >/dev/null || { echo "python3 is required" >&2; exit 2; }
command -v unzip >/dev/null || true

RUN_DIR="$WORK/$ABI"
INDEX_XZ="$RUN_DIR/Packages.xz"
INDEX="$RUN_DIR/Packages"
PREFIX="$RUN_DIR/prefix"
RUNTIME="$RUN_DIR/runtime"
mkdir -p "$RUN_DIR/debs" "$PREFIX" "$RUNTIME/lib" "$RUNTIME/extensions" "$DEPS_DIR"

curl -fL --retry 2 --max-time 60 \
  -o "$INDEX_XZ" \
  "$TERMUX_BASE/dists/stable/main/binary-$TERMUX_ARCH/Packages.xz"
xz -dc "$INDEX_XZ" > "$INDEX"

python3 - "$INDEX" "${ROOT_PACKAGES[@]}" > "$RUN_DIR/manifest.tsv" <<'PY'
import re
import sys

index = sys.argv[1]
roots = sys.argv[2:]
packages = {}
current = {}
last = None

with open(index, encoding="utf-8", errors="replace") as handle:
    for raw in handle:
        line = raw.rstrip("\n")
        if not line:
            if current.get("Package"):
                packages[current["Package"]] = current
            current = {}
            last = None
            continue
        if line.startswith(" ") and last:
            current[last] += " " + line.strip()
            continue
        if ":" in line:
            key, value = line.split(":", 1)
            current[key] = value.strip()
            last = key
if current.get("Package"):
    packages[current["Package"]] = current

seen = []
queue = list(roots)
while queue:
    name = queue.pop(0)
    if name in seen or name not in packages:
        continue
    seen.append(name)
    for part in packages[name].get("Depends", "").split(","):
        dep = part.strip().split("|")[0].strip()
        dep = re.sub(r"\s*\(.*?\)", "", dep).strip()
        if dep and dep not in seen and dep in packages:
            queue.append(dep)

for name in seen:
    p = packages[name]
    print("\t".join([name, p.get("Version", ""), p.get("Filename", ""), p.get("SHA256", "")]))
PY

while IFS=$'\t' read -r _name _version filename sha; do
  out="$RUN_DIR/debs/${filename##*/}"
  if [[ ! -f "$out" ]]; then
    curl -fL --retry 2 --max-time 120 -o "$out" "$TERMUX_BASE/$filename"
  fi
  if [[ -n "$sha" ]]; then
    echo "$sha  $out" | sha256sum -c - >/dev/null
  fi
done < "$RUN_DIR/manifest.tsv"

rm -rf "$PREFIX" "$RUNTIME"
mkdir -p "$PREFIX" "$RUNTIME/lib" "$RUNTIME/extensions"
(
  cd "$PREFIX"
  for deb in "$RUN_DIR"/debs/*.deb; do
    ar p "$deb" data.tar.xz 2>/dev/null | tar -xJ 2>/dev/null ||
      ar p "$deb" data.tar.zst 2>/dev/null | tar --zstd -x 2>/dev/null ||
      ar p "$deb" data.tar.gz 2>/dev/null | tar -xz 2>/dev/null ||
      true
  done
)

TERMUX_PREFIX="$PREFIX/data/data/com.termux/files/usr"
cp -Lf "$TERMUX_PREFIX"/lib/*.so* "$RUNTIME/lib/" 2>/dev/null || true
cp -Lf "$TERMUX_PREFIX"/lib/php/*.so "$RUNTIME/extensions/" 2>/dev/null || true
printf '%s\n' "${REQUIRED_EXTS[@]}" > "$RUNTIME/extensions.txt"

python3 - "$RUNTIME" "$DEPS_DIR/php-runtime-$ABI.zip" <<'PY'
import os
import sys
import zipfile

runtime, out = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=1) as archive:
    for arc in ("lib", "extensions"):
        base = os.path.join(runtime, arc)
        for root, _dirs, files in os.walk(base):
            for name in files:
                path = os.path.join(root, name)
                archive.write(path, os.path.join(arc, os.path.relpath(path, base)))
    archive.write(os.path.join(runtime, "extensions.txt"), "extensions.txt")
print(out, os.path.getsize(out))
PY

echo "Staged $DEPS_DIR/php-runtime-$ABI.zip"
