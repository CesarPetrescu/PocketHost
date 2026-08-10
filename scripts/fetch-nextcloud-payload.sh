#!/usr/bin/env bash
set -euo pipefail

# Downloads a Nextcloud server release and stages it as the bundled asset
# <deps>/nextcloud-server-<version>.zip that NextcloudInstaller unpacks.
# The asset name + version must match NextcloudInstaller.kt (VERSION / ASSET_NAME).
#
# Usage:
#   ./scripts/fetch-nextcloud-payload.sh            # latest
#   ./scripts/fetch-nextcloud-payload.sh 33.0.5     # specific version
#
# NOTE: keep this in sync with NextcloudInstaller.VERSION. Nextcloud needs PHP
# >= its minimum; the bundled PHP 8.5 runtime satisfies Nextcloud 33.x.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEPS_DIR="${POCKETHOST_ASSETS_DIR:-$ROOT_DIR/android/deps/assets}"
WORK="${WORK:-/tmp/nc-fetch}"
VERSION="${1:-}"

mkdir -p "$WORK" "$DEPS_DIR"
cd "$WORK"

if [[ -n "$VERSION" ]]; then
  URL="https://download.nextcloud.com/server/releases/nextcloud-${VERSION}.zip"
else
  URL="https://download.nextcloud.com/server/releases/latest.zip"
fi
echo "Downloading $URL"
curl -fL --retry 2 -o nextcloud.zip "$URL"

rm -rf extracted && mkdir extracted
unzip -q nextcloud.zip -d extracted
VER="$(grep -oE "OC_VersionString = '[^']+'" extracted/nextcloud/version.php | sed "s/.*'\\([^']*\\)'.*/\\1/")"
echo "Nextcloud version: $VER"

OUT="$DEPS_DIR/nextcloud-server-${VER}.zip"
python3 - "$WORK/extracted/nextcloud" "$OUT" <<'PY'
import sys, os, zipfile
base, out = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED,compresslevel=1) as z:
    for root,_,files in os.walk(base):
        for f in files:
            fp=os.path.join(root,f)
            z.write(fp, os.path.join('nextcloud', os.path.relpath(fp, base)))
print("wrote", out, os.path.getsize(out), "bytes")
PY
echo "Staged $OUT"
echo "Ensure NextcloudInstaller.kt VERSION='$VER' (ASSET_NAME is derived from VERSION)."
