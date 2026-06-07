#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_DIR="$ROOT_DIR/android"
RELEASE_DIR="$ROOT_DIR/releases/apk"
VARIANT="${1:-release}"

case "$VARIANT" in
  debug|release) ;;
  *)
    echo "Usage: $0 [debug|release]" >&2
    exit 2
    ;;
esac

if [[ -z "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" && ! -f "$ANDROID_DIR/local.properties" ]]; then
  echo "Android SDK location not found. Set ANDROID_HOME/ANDROID_SDK_ROOT or create android/local.properties with sdk.dir before packaging APKs." >&2
  exit 2
fi

cd "$ANDROID_DIR"
TASK="assemble${VARIANT^}"
if [[ -x ./gradlew ]]; then
  ./gradlew ":app:$TASK"
else
  gradle ":app:$TASK"
fi

mkdir -p "$RELEASE_DIR"
rm -f "$RELEASE_DIR"/*.apk

OUTPUT_DIR="$ANDROID_DIR/app/build/outputs/apk/$VARIANT"
if [[ ! -d "$OUTPUT_DIR" ]]; then
  echo "Gradle output directory not found: $OUTPUT_DIR" >&2
  exit 1
fi

# Copy every split Gradle produced. The Android project enables arm64-v8a,
# armeabi-v7a, x86, x86_64, and universal APK outputs.
found=0
while IFS= read -r -d '' apk; do
  name="$(basename "$apk")"
  cp "$apk" "$RELEASE_DIR/pockethost-$VARIANT-${name#app-}"
  found=1
done < <(find "$OUTPUT_DIR" -maxdepth 1 -type f -name '*.apk' -print0 | sort -z)

if [[ "$found" -ne 1 ]]; then
  echo "No APKs found in $OUTPUT_DIR" >&2
  exit 1
fi

cat > "$RELEASE_DIR/README.md" <<EOF_README
# PocketHost APK Artifacts

This directory receives locally generated APKs from:

\`\`\`bash
./scripts/package-android.sh $VARIANT
\`\`\`

Expected split outputs:

- \`pockethost-$VARIANT-arm64-v8a-$VARIANT.apk\`
- \`pockethost-$VARIANT-armeabi-v7a-$VARIANT.apk\`
- \`pockethost-$VARIANT-x86-$VARIANT.apk\`
- \`pockethost-$VARIANT-x86_64-$VARIANT.apk\`
- \`pockethost-$VARIANT-universal-$VARIANT.apk\`

These APKs are for developer sideload testing. The current \`release\` build type
uses the debug signing configuration and is not suitable for public app-store
distribution.

Before treating any APK here as a candidate release, collect:

- \`./scripts/ci-local.sh\` output.
- Android build output.
- Install evidence on a real device or emulator.
- Foreground notification evidence.
- Daemon start/stop evidence.
- Successful local \`/health\` probes.
- Confirmation that no secrets are bundled in configs, assets, logs, or
  screenshots.
EOF_README

printf 'APKs copied to %s:\n' "$RELEASE_DIR"
find "$RELEASE_DIR" -maxdepth 1 -type f -name '*.apk' -print | sort
