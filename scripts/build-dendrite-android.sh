#!/usr/bin/env bash
set -euo pipefail

# Builds the Dendrite Matrix homeserver (Go) for Android and stages it as
# libmatrixd.so. Dendrite replaces the earlier Tuwunel slot because the Tuwunel
# build panics on Android (it needs a JVM/JNI "android context" that a forked
# daemon process started by ProcessSupervisor cannot provide). Dendrite is pure
# Go with a pure-Go SQLite driver, so it runs fine as a supervised process.
#
# Usage:
#   ./scripts/build-dendrite-android.sh                 # arm64-v8a + x86_64
#   ./scripts/build-dendrite-android.sh arm64-v8a
#   DENDRITE_SRC=/path/to/dendrite ./scripts/build-dendrite-android.sh
#
# Requires: Go, and the Android NDK (for the cgo-linked 64-bit-Intel target).

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JNI_DIR="$ROOT_DIR/android/app/src/main/jniLibs"
ANDROID_API="${ANDROID_API:-26}"
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
ANDROID_NDK_ROOT="${ANDROID_NDK_ROOT:-}"
DENDRITE_SRC="${DENDRITE_SRC:-/tmp/dendrite}"
DENDRITE_REPO="${DENDRITE_REPO:-https://github.com/matrix-org/dendrite.git}"

host_tag() { case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) echo windows-x86_64;; Darwin*) echo darwin-x86_64;; *) echo linux-x86_64;; esac; }

find_ndk() {
  if [[ -n "$ANDROID_NDK_ROOT" && -d "$ANDROID_NDK_ROOT" ]]; then echo "$ANDROID_NDK_ROOT"; return; fi
  [[ -d "$ANDROID_SDK_ROOT/ndk" ]] && find "$ANDROID_SDK_ROOT/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n1
}

if [[ $# -gt 0 ]]; then ABIS=("$@"); else ABIS=(arm64-v8a x86_64); fi

if [[ ! -d "$DENDRITE_SRC/.git" ]]; then
  echo "Cloning Dendrite into $DENDRITE_SRC"
  git clone --depth 1 "$DENDRITE_REPO" "$DENDRITE_SRC"
fi

NDK="$(find_ndk || true)"
NDKBIN="${NDK:+$NDK/toolchains/llvm/prebuilt/$(host_tag)/bin}"

build_one() {
  local abi="$1" goarch cc out
  out="$JNI_DIR/$abi/libmatrixd.so"
  mkdir -p "$JNI_DIR/$abi"
  case "$abi" in
    # arm64 links statically without cgo; Intel targets need NDK external linking.
    arm64-v8a) goarch=arm64; cc="" ;;   # static, no NDK needed
    x86_64)
      goarch=amd64
      [[ -n "$NDK" ]] || { echo "Android NDK not found (required for the x86_64 cgo target). Set ANDROID_NDK_ROOT or install NDK under $ANDROID_SDK_ROOT/ndk." >&2; exit 2; }
      cc="$NDKBIN/x86_64-linux-android${ANDROID_API}-clang"
      [[ -x "$cc" ]] || cc="$cc.cmd"   # NDK clang wrapper is .cmd on Windows hosts
      ;;
    *) echo "Unsupported ABI for Dendrite: $abi (Matrix is 64-bit only)" >&2; exit 2 ;;
  esac
  echo "Building Dendrite for $abi"
  if [[ -n "$cc" ]]; then
    [[ -x "$cc" ]] || { echo "NDK clang not found: $cc" >&2; exit 2; }
    (cd "$DENDRITE_SRC" && env CC="$cc" CGO_ENABLED=1 GOOS=android GOARCH="$goarch" \
      go build -trimpath -ldflags="-s -w" -o "$out" ./cmd/dendrite)
  else
    (cd "$DENDRITE_SRC" && env CGO_ENABLED=0 GOOS=android GOARCH="$goarch" \
      go build -trimpath -ldflags="-s -w" -o "$out" ./cmd/dendrite)
  fi
  chmod 0755 "$out"
  echo "  wrote $out ($(stat -c%s "$out" 2>/dev/null || stat -f%z "$out") bytes)"
}

for abi in "${ABIS[@]}"; do build_one "$abi"; done
echo "Done. Dendrite staged as libmatrixd.so for: ${ABIS[*]}"
