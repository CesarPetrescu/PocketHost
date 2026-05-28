#!/usr/bin/env bash
set -euo pipefail

# Builds the official cloudflared source for Android and writes executable
# artifacts into jniLibs so the Android supervisor can launch them.
#
# Usage:
#   ./scripts/build-cloudflared-android.sh /path/to/cloudflared-source
#   ./scripts/build-cloudflared-android.sh /path/to/cloudflared-source arm64-v8a x86_64
#
# The source checkout should be the tag/commit recorded in NOTICE.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JNI_DIR="$ROOT_DIR/android/app/src/main/jniLibs"
ANDROID_API="${ANDROID_API:-26}"
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
ANDROID_NDK_ROOT="${ANDROID_NDK_ROOT:-}"
CLOUDFLARED_VERSION="${CLOUDFLARED_VERSION:-2026.5.2}"
CLOUDFLARED_BUILD_TIME="${CLOUDFLARED_BUILD_TIME:-2026-05-28T00:00 UTC}"

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 /path/to/cloudflared-source [abi ...]" >&2
  exit 2
fi

SOURCE_DIR="$1"
shift

if [[ ! -f "$SOURCE_DIR/go.mod" || ! -d "$SOURCE_DIR/cmd/cloudflared" ]]; then
  echo "cloudflared source checkout not found at: $SOURCE_DIR" >&2
  exit 2
fi

find_ndk_root() {
  if [[ -n "$ANDROID_NDK_ROOT" && -d "$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin" ]]; then
    echo "$ANDROID_NDK_ROOT"
    return
  fi
  if [[ -d "$ANDROID_SDK_ROOT/ndk" ]]; then
    find "$ANDROID_SDK_ROOT/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1
  fi
}

ndk_clang() {
  local target="$1"
  local ndk
  ndk="$(find_ndk_root)"
  if [[ -z "$ndk" ]]; then
    echo "Android NDK not found. Set ANDROID_NDK_ROOT or install NDK under $ANDROID_SDK_ROOT/ndk." >&2
    exit 2
  fi
  local cc="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/${target}${ANDROID_API}-clang"
  if [[ ! -x "$cc" ]]; then
    echo "Android NDK clang not found: $cc" >&2
    exit 2
  fi
  echo "$cc"
}

if [[ $# -eq 0 ]]; then
  ABIS=(arm64-v8a x86 x86_64)
elif [[ "$1" == "all" ]]; then
  ABIS=(arm64-v8a x86 x86_64)
else
  ABIS=("$@")
fi

build_one() {
  local abi="$1"
  local goarch cc_target cgo_enabled

  case "$abi" in
    arm64-v8a) goarch="arm64"; cc_target=""; cgo_enabled="0" ;;
    x86) goarch="386"; cc_target="i686-linux-android"; cgo_enabled="1" ;;
    x86_64) goarch="amd64"; cc_target="x86_64-linux-android"; cgo_enabled="1" ;;
    *) echo "Unsupported ABI: $abi" >&2; exit 2 ;;
  esac

  mkdir -p "$JNI_DIR/$abi"
  echo "Building cloudflared for $abi"
  if [[ -n "$cc_target" ]]; then
    local cc
    cc="$(ndk_clang "$cc_target")"
    (
      cd "$SOURCE_DIR"
      CC="$cc" CGO_ENABLED="$cgo_enabled" GOOS=android GOARCH="$goarch" \
        go build -mod=vendor -trimpath \
          -ldflags="-s -w -X \"main.Version=$CLOUDFLARED_VERSION\" -X \"main.BuildTime=$CLOUDFLARED_BUILD_TIME\"" \
          -o "$JNI_DIR/$abi/libcloudflared.so" \
          github.com/cloudflare/cloudflared/cmd/cloudflared
    )
  else
    (
      cd "$SOURCE_DIR"
      CGO_ENABLED="$cgo_enabled" GOOS=android GOARCH="$goarch" \
        go build -mod=vendor -trimpath \
          -ldflags="-s -w -X \"main.Version=$CLOUDFLARED_VERSION\" -X \"main.BuildTime=$CLOUDFLARED_BUILD_TIME\"" \
          -o "$JNI_DIR/$abi/libcloudflared.so" \
          github.com/cloudflare/cloudflared/cmd/cloudflared
    )
  fi
  chmod 0755 "$JNI_DIR/$abi/libcloudflared.so"
}

OUTPUTS=()
for abi in "${ABIS[@]}"; do
  build_one "$abi"
  OUTPUTS+=("$JNI_DIR/$abi/libcloudflared.so")
done

sha256sum "${OUTPUTS[@]}"
