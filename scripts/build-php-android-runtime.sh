#!/usr/bin/env bash
set -euo pipefail
shopt -s nullglob   # unmatched deb globs expand to nothing, not literal strings

# Builds a relocatable PHP 8.5 runtime for Android (via the Termux package
# builder in Docker) and stages it for PocketHost / nextcloudd:
#   - android/app/src/main/jniLibs/<abi>/libphp.so          (the php binary)
#   - <deps>/php-runtime-<abi>.zip  (lib/ + extensions/ + extensions.txt)
#
# 17 of the 19 Nextcloud-required extensions are compiled into libphp.so;
# gd and sodium are shared modules placed in extensions/ (and loaded via the
# two `extension=` lines that PhpRuntimeInstaller.writePhpIni emits). The app's
# php.ini also disables opcache: Termux's opcache hardcodes a /data/data/com.termux
# tmp path that does not exist in PocketHost's sandbox and crashes `php -S`.
#
# Usage:
#   ./scripts/build-php-android-runtime.sh arm64-v8a
#   ./scripts/build-php-android-runtime.sh x86_64
#   ./scripts/build-php-android-runtime.sh arm64-v8a x86_64
#
# Requires: Docker. Each ABI is a long (~30-40 min) from-source build.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JNI_DIR="$ROOT_DIR/android/app/src/main/jniLibs"
DEPS_DIR="${POCKETHOST_ASSETS_DIR:-$ROOT_DIR/android/deps/assets}"
TERMUX_SRC="${TERMUX_SRC:-/tmp/termux-packages}"
TERMUX_REPO="${TERMUX_REPO:-https://github.com/termux/termux-packages.git}"
WORK="${WORK:-/tmp/php-stage}"

REQUIRED_EXTS="sqlite3 pdo_sqlite mbstring intl xml xmlreader xmlwriter simplexml dom zip curl gd fileinfo openssl sodium ctype session zlib posix"

abi_to_termux() { case "$1" in arm64-v8a) echo aarch64;; x86_64) echo x86_64;; *) echo "";; esac; }

[[ $# -gt 0 ]] || { echo "Usage: $0 <arm64-v8a|x86_64> [...]" >&2; exit 2; }
command -v docker >/dev/null || { echo "Docker is required." >&2; exit 2; }

if [[ ! -d "$TERMUX_SRC/.git" ]]; then
  echo "Cloning termux-packages into $TERMUX_SRC"
  git clone --depth 1 "$TERMUX_REPO" "$TERMUX_SRC"
fi

build_one() {
  local abi="$1" tarch deb prefix rt
  tarch="$(abi_to_termux "$abi")"
  [[ -n "$tarch" ]] || { echo "PHP runtime only supports arm64-v8a / x86_64, not $abi" >&2; exit 2; }

  echo "==> [$abi] building php (Termux/Docker, long)…"
  # The container builds as uid 1000; it must be able to write output/ + build dirs.
  chown -R 1000:1000 "$TERMUX_SRC" 2>/dev/null || chmod -R a+rwX "$TERMUX_SRC"
  mkdir -p "$TERMUX_SRC/output"
  ( cd "$TERMUX_SRC" && ./scripts/run-docker.sh ./build-package.sh -a "$tarch" php )
  chmod -R a+rX "$TERMUX_SRC/output" 2>/dev/null || true   # output is uid 1000; make host-readable

  echo "==> [$abi] assembling runtime"
  prefix="$WORK/$abi/prefix"; rt="$WORK/$abi/runtime"
  rm -rf "$WORK/$abi"; mkdir -p "$prefix" "$rt/lib" "$rt/extensions"
  ( cd "$prefix"
    for deb in "$TERMUX_SRC"/output/*_${tarch}.deb "$TERMUX_SRC"/output/*_all.deb; do
      case "$deb" in *-static_*|*binutils*|*-cross_*|*doxygen*|*apache2*|*python*|*tk_*|*postgresql*|*coreutils*|*gawk*|*diffutils*|*findutils*|*sed_*|*grep_*|*dialog*|*procps*|*psmisc*|*tar_*|*less_*|*gzip*|*dash_*|*attr_*|*fdisk*|*blk-utils*|*util-linux*) continue;; esac
      ar p "$deb" data.tar.xz 2>/dev/null | tar -xJ 2>/dev/null || true
    done )
  local P="$prefix/data/data/com.termux/files/usr"
  [[ -x "$P/bin/php" ]] || { echo "Termux build produced no php binary for $tarch — check $TERMUX_SRC/output for .deb files" >&2; exit 2; }
  cp "$P/bin/php" "$rt/libphp.so"; chmod 0755 "$rt/libphp.so"
  cp -Lf "$P"/lib/*.so* "$rt/lib/" 2>/dev/null || true
  cp -Lf "$P"/lib/php/gd.so "$P"/lib/php/sodium.so "$rt/extensions/" 2>/dev/null || true
  for ext in gd sodium; do
    [[ -f "$rt/extensions/$ext.so" ]] || echo "WARNING: $ext.so missing from the Termux build for $abi — runtime will fail preflight" >&2
  done
  printf '%s\n' $REQUIRED_EXTS > "$rt/extensions.txt"

  echo "==> [$abi] staging into jniLibs + $DEPS_DIR"
  mkdir -p "$JNI_DIR/$abi" "$DEPS_DIR"
  cp "$rt/libphp.so" "$JNI_DIR/$abi/libphp.so"; chmod 0755 "$JNI_DIR/$abi/libphp.so"
  python3 - "$rt" "$DEPS_DIR/php-runtime-$abi.zip" <<'PY'
import sys, os, zipfile
rt, out = sys.argv[1], sys.argv[2]
def addtree(z, base, arc):
    for root,_,files in os.walk(base):
        for f in files: z.write(os.path.join(root,f), os.path.join(arc, os.path.relpath(os.path.join(root,f), base)))
with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED,compresslevel=1) as z:
    addtree(z, rt+'/lib','lib'); addtree(z, rt+'/extensions','extensions'); z.write(rt+'/extensions.txt','extensions.txt')
print("  wrote", out, os.path.getsize(out), "bytes")
PY
  echo "  libphp.so + php-runtime-$abi.zip staged for $abi"
}

for abi in "$@"; do build_one "$abi"; done
echo "Done. PHP runtime built for: $*"
echo "Verify on a device: php -m must list: $REQUIRED_EXTS"
