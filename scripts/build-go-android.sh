#!/usr/bin/env bash
set -euo pipefail

# Usage:
#   ./scripts/build-go-android.sh arm64-v8a
#   ./scripts/build-go-android.sh arm64-v8a x86_64
#   ./scripts/build-go-android.sh all

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GO_DIR="$ROOT_DIR/go"
JNI_DIR="$ROOT_DIR/android/app/src/main/jniLibs"

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
  local goarch goarm
  case "$abi" in
    arm64-v8a) goarch="arm64"; goarm="" ;;
    armeabi-v7a) goarch="arm"; goarm="7" ;;
    x86) goarch="386"; goarm="" ;;
    x86_64) goarch="amd64"; goarm="" ;;
    *) echo "Unsupported ABI: $abi" >&2; exit 2 ;;
  esac

  mkdir -p "$JNI_DIR/$abi"
  for cmd in "${cmds[@]}"; do
    echo "Building $cmd for $abi"
    if [[ -n "$goarm" ]]; then
      (cd "$GO_DIR" && CGO_ENABLED=0 GOOS=android GOARCH="$goarch" GOARM="$goarm" go build -trimpath -ldflags="-s -w" -o "$JNI_DIR/$abi/lib${cmd}.so" "./cmd/$cmd")
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
