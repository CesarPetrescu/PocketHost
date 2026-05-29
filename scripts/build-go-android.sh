#!/usr/bin/env bash
set -euo pipefail

# Usage:
#   ./scripts/build-go-android.sh arm64-v8a
#   ./scripts/build-go-android.sh arm64-v8a x86_64
#   ./scripts/build-go-android.sh all

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GO_DIR="$ROOT_DIR/go"
JNI_DIR="$ROOT_DIR/android/app/src/main/jniLibs"
ANDROID_API="${ANDROID_API:-26}"
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
NDK_ROOT="${ANDROID_NDK_ROOT:-}"

find_ndk_root() {
  if [[ -n "$NDK_ROOT" && -d "$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin" ]]; then
    echo "$NDK_ROOT"
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
  ABIS=(arm64-v8a)
elif [[ "$1" == "all" ]]; then
  ABIS=(arm64-v8a armeabi-v7a x86 x86_64)
else
  ABIS=("$@")
fi

cmds=(hostd webd filed ddnsd proxyd)

build_one() {
  local abi="$1"
  local goarch goarm cc_target
  # android/arm (32-bit) requires external cgo linking, so armeabi-v7a must be
  # built with the NDK clang just like the x86 targets. arm64 links statically.
  case "$abi" in
    arm64-v8a) goarch="arm64"; goarm=""; cc_target="" ;;
    armeabi-v7a) goarch="arm"; goarm="7"; cc_target="armv7a-linux-androideabi" ;;
    x86) goarch="386"; goarm=""; cc_target="i686-linux-android" ;;
    x86_64) goarch="amd64"; goarm=""; cc_target="x86_64-linux-android" ;;
    *) echo "Unsupported ABI: $abi" >&2; exit 2 ;;
  esac

  mkdir -p "$JNI_DIR/$abi"
  for cmd in "${cmds[@]}"; do
    echo "Building $cmd for $abi"
    if [[ -n "$cc_target" ]]; then
      local cc
      cc="$(ndk_clang "$cc_target")"
      (cd "$GO_DIR" && env CC="$cc" CGO_ENABLED=1 GOOS=android GOARCH="$goarch" ${goarm:+GOARM="$goarm"} go build -trimpath -ldflags="-s -w" -o "$JNI_DIR/$abi/lib${cmd}.so" "./cmd/$cmd")
    else
      (cd "$GO_DIR" && CGO_ENABLED=0 GOOS=android GOARCH="$goarch" go build -trimpath -ldflags="-s -w" -o "$JNI_DIR/$abi/lib${cmd}.so" "./cmd/$cmd")
    fi
    chmod 0755 "$JNI_DIR/$abi/lib${cmd}.so"
  done
}

for abi in "${ABIS[@]}"; do
  build_one "$abi"
done

echo "Done. Binaries written to $JNI_DIR"
