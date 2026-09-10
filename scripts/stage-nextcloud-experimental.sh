#!/usr/bin/env bash
set -euo pipefail

# Stages a real Android PHP runtime into the app once the Termux-derived build
# has produced an executable plus runtime tree for the target ABI.
#
# Usage:
#   ./scripts/stage-nextcloud-experimental.sh arm64-v8a /path/to/php /path/to/php-runtime-root /path/to/nextcloud-<version>.zip
#   ./scripts/stage-nextcloud-experimental.sh x86_64 /path/to/php /path/to/php-runtime-root /path/to/nextcloud-<version>.zip

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JNI_DIR="$ROOT_DIR/android/app/src/main/jniLibs"
PHP_ASSETS_DIR="${PHP_ASSETS_DIR:-D:/PocketHostDeps/php-android/assets}"
# Single source of truth: whatever the app is compiled to open. Hardcoding a
# version here silently stages a payload the installer can never find.
NEXTCLOUD_VERSION="$(grep -oE 'const val VERSION = "[0-9.]+"' \
  "$ROOT_DIR/android/app/src/main/java/dev/pockethost/supervisor/NextcloudInstaller.kt" \
  | grep -oE '[0-9.]+')"
if [[ -z "$NEXTCLOUD_VERSION" ]]; then
  echo "could not read NextcloudInstaller.VERSION" >&2
  exit 2
fi
NEXTCLOUD_ASSETS_DIR="${NEXTCLOUD_ASSETS_DIR:-D:/PocketHostDeps/nextcloud-v$NEXTCLOUD_VERSION/assets}"

if [[ $# -ne 4 ]]; then
  echo "Usage: $0 <arm64-v8a|x86_64> /path/to/php /path/to/php-runtime-root /path/to/nextcloud-<version>.zip" >&2
  exit 2
fi

ABI="$1"
PHP_BIN="$2"
PHP_RUNTIME_ROOT="$3"
NEXTCLOUD_ZIP="$4"

case "$ABI" in
  arm64-v8a|x86_64) ;;
  *) echo "Unsupported Nextcloud ABI: $ABI" >&2; exit 2 ;;
esac

if [[ ! -f "$PHP_BIN" ]]; then
  echo "PHP executable not found: $PHP_BIN" >&2
  exit 2
fi
if [[ ! -d "$PHP_RUNTIME_ROOT" ]]; then
  echo "PHP runtime root not found: $PHP_RUNTIME_ROOT" >&2
  exit 2
fi
if [[ ! -f "$NEXTCLOUD_ZIP" ]]; then
  echo "Nextcloud ZIP not found: $NEXTCLOUD_ZIP" >&2
  exit 2
fi

mkdir -p "$JNI_DIR/$ABI" "$PHP_ASSETS_DIR" "$NEXTCLOUD_ASSETS_DIR"
cp "$PHP_BIN" "$JNI_DIR/$ABI/libphp.so"
chmod 0755 "$JNI_DIR/$ABI/libphp.so"
(cd "$PHP_RUNTIME_ROOT" && zip -qr "$PHP_ASSETS_DIR/php-runtime-$ABI.zip" .)
cp "$NEXTCLOUD_ZIP" "$NEXTCLOUD_ASSETS_DIR/nextcloud-server-$NEXTCLOUD_VERSION.zip"

sha256sum "$JNI_DIR/$ABI/libphp.so" "$PHP_ASSETS_DIR/php-runtime-$ABI.zip" "$NEXTCLOUD_ASSETS_DIR/nextcloud-server-$NEXTCLOUD_VERSION.zip"
