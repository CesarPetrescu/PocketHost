#!/usr/bin/env bash
set -euo pipefail

# Builds Tuwunel for Android and stages it as libmatrixd.so.
#
# Usage:
#   ./scripts/build-tuwunel-android.sh /path/to/tuwunel-source arm64-v8a x86_64
#
# Requirements:
# - Rust Android targets installed with rustup
# - Android NDK available under ANDROID_NDK_ROOT or ANDROID_SDK_ROOT/ndk
# - Tuwunel source checkout at v1.7.0

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JNI_DIR="$ROOT_DIR/android/app/src/main/jniLibs"
ANDROID_API="${ANDROID_API:-26}"
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
ANDROID_NDK_ROOT="${ANDROID_NDK_ROOT:-}"

host_tag() {
  case "$(uname -s 2>/dev/null || echo unknown)" in
    MINGW*|MSYS*|CYGWIN*) echo "windows-x86_64" ;;
    Darwin*) echo "darwin-x86_64" ;;
    *) echo "linux-x86_64" ;;
  esac
}

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 /path/to/tuwunel-source [arm64-v8a x86_64]" >&2
  exit 2
fi

SOURCE_DIR="$1"
shift

if [[ ! -f "$SOURCE_DIR/Cargo.toml" ]]; then
  echo "Tuwunel source checkout not found at: $SOURCE_DIR" >&2
  exit 2
fi

if [[ $# -eq 0 ]]; then
  ABIS=(arm64-v8a x86_64)
else
  ABIS=("$@")
fi

find_ndk_root() {
  local host
  host="$(host_tag)"
  if [[ -n "$ANDROID_NDK_ROOT" && -d "$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/$host/bin" ]]; then
    echo "$ANDROID_NDK_ROOT"
    return
  fi
  if [[ -d "$ANDROID_SDK_ROOT/ndk" ]]; then
    find "$ANDROID_SDK_ROOT/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1
  fi
}

ndk_bin() {
  local ndk
  ndk="$(find_ndk_root)"
  if [[ -z "$ndk" ]]; then
    echo "Android NDK not found. Set ANDROID_NDK_ROOT or install NDK under $ANDROID_SDK_ROOT/ndk." >&2
    exit 2
  fi
  echo "$ndk/toolchains/llvm/prebuilt/$(host_tag)/bin"
}

build_one() {
  local abi="$1"
  local target linker env_linker
  case "$abi" in
    arm64-v8a)
      target="aarch64-linux-android"
      linker="$(ndk_bin)/aarch64-linux-android${ANDROID_API}-clang"
      env_linker="CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER"
      ;;
    x86_64)
      target="x86_64-linux-android"
      linker="$(ndk_bin)/x86_64-linux-android${ANDROID_API}-clang"
      env_linker="CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER"
      ;;
    *)
      echo "Unsupported ABI for Tuwunel: $abi" >&2
      exit 2
      ;;
  esac

  if [[ ! -x "$linker" ]]; then
    if [[ -x "$linker.cmd" ]]; then
      linker="$linker.cmd"
    else
      echo "Android linker not found: $linker" >&2
      exit 2
    fi
  fi

  mkdir -p "$JNI_DIR/$abi"
  echo "Building Tuwunel for $abi ($target)"
  (
    cd "$SOURCE_DIR"
    env "$env_linker=$linker" cargo build --release --target "$target"
  )

  if [[ ! -f "$SOURCE_DIR/target/$target/release/tuwunel" ]]; then
    echo "Expected Tuwunel binary missing: $SOURCE_DIR/target/$target/release/tuwunel" >&2
    exit 2
  fi
  cp "$SOURCE_DIR/target/$target/release/tuwunel" "$JNI_DIR/$abi/libmatrixd.so"
  chmod 0755 "$JNI_DIR/$abi/libmatrixd.so"
  sha256sum "$JNI_DIR/$abi/libmatrixd.so"
}

for abi in "${ABIS[@]}"; do
  build_one "$abi"
done
