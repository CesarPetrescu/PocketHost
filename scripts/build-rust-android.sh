#!/usr/bin/env bash
set -euo pipefail

# Placeholder build script for the Matrix daemon adapter.
# Requires Rust, cargo, cargo-ndk, and Android NDK.
#
# Install:
#   rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android
#   cargo install cargo-ndk
#
# Build:
#   ./scripts/build-rust-android.sh arm64-v8a

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUST_DIR="$ROOT_DIR/rust/matrixd"
JNI_DIR="$ROOT_DIR/android/app/src/main/jniLibs"
ABI="${1:-arm64-v8a}"

case "$ABI" in
  arm64-v8a) TARGET="aarch64-linux-android" ;;
  armeabi-v7a) TARGET="armv7-linux-androideabi" ;;
  x86) TARGET="i686-linux-android" ;;
  x86_64) TARGET="x86_64-linux-android" ;;
  *) echo "Unsupported ABI: $ABI" >&2; exit 2 ;;
esac

mkdir -p "$JNI_DIR/$ABI"
(cd "$RUST_DIR" && cargo ndk -t "$ABI" build --release)
cp "$RUST_DIR/target/$TARGET/release/matrixd" "$JNI_DIR/$ABI/libmatrixd.so"
chmod 0755 "$JNI_DIR/$ABI/libmatrixd.so"

echo "Built $JNI_DIR/$ABI/libmatrixd.so"
