#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_DIR="$ROOT/android"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/root/Android/Sdk}}"
ADB="$SDK_ROOT/platform-tools/adb"
EMULATOR="$SDK_ROOT/emulator/emulator"
AVD="${POCKETHOST_AVD:-medium_phone}"
PACKAGE="${POCKETHOST_PACKAGE:-dev.pockethost.debug}"
APK="$ANDROID_DIR/app/build/outputs/apk/debug/app-x86_64-debug.apk"

if [[ ! -x "$ADB" ]]; then
  echo "adb not found at $ADB. Set ANDROID_SDK_ROOT." >&2
  exit 2
fi

if [[ ! -x "$EMULATOR" ]]; then
  echo "emulator not found at $EMULATOR. Set ANDROID_SDK_ROOT." >&2
  exit 2
fi

emulator_pid=""
cleanup() {
  if [[ -n "$emulator_pid" ]]; then
    "$ADB" emu kill >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

wait_for_boot() {
  "$ADB" wait-for-device
  timeout 240 bash -c '
    until [[ "$("$0" shell getprop sys.boot_completed 2>/dev/null | tr -d "\r")" == "1" ]]; do
      sleep 2
    done
  ' "$ADB"
}

if ! "$ADB" devices | awk 'NR > 1 && $2 == "device" { found = 1 } END { exit(found ? 0 : 1) }'; then
  echo "Starting AVD $AVD headlessly"
  ANDROID_SDK_ROOT="$SDK_ROOT" "$EMULATOR" "@$AVD" \
    -no-window \
    -no-audio \
    -no-boot-anim \
    -gpu swiftshader_indirect \
    -no-snapshot \
    -wipe-data >/tmp/pockethost-emulator.log 2>&1 &
  emulator_pid="$!"
fi

wait_for_boot

abi="$("$ADB" shell getprop ro.product.cpu.abi | tr -d '\r')"
if [[ "$abi" != "x86_64" ]]; then
  echo "This smoke script expects an x86_64 emulator; got $abi." >&2
  exit 1
fi

echo "== Build debug APK =="
(cd "$ANDROID_DIR" && ./gradlew :app:assembleDebug)

echo "== Install APK =="
"$ADB" install -r "$APK"
"$ADB" shell pm grant "$PACKAGE" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true

echo "== Launch app and start default services =="
"$ADB" shell am start -n "$PACKAGE/dev.pockethost.MainActivity" >/dev/null
sleep 2
# medium_phone is 1080x2400; this taps the visible Dashboard "Start all" button.
"$ADB" shell input tap 242 674
sleep 12

echo "== Foreground notification =="
"$ADB" shell dumpsys notification --noredact | grep -q "PocketHost supervisor running"
echo "ok PocketHost supervisor notification"

echo "== Daemon processes =="
for name in libhostd.so libwebd.so libfiled.so libproxyd.so; do
  "$ADB" shell "ps -A | grep -q '$name'"
  echo "ok $name process"
done

health() {
  local port="$1"
  local service="$2"
  local response
  response="$("$ADB" shell "printf 'GET /health HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n' | nc -w 3 127.0.0.1 $port" | tr -d '\r')"
  if [[ "$response" != *"HTTP/1.1 200 OK"* || "$response" != *"\"service\": \"$service\""* || "$response" != *"\"status\": \"ok\""* ]]; then
    echo "$response" >&2
    echo "health failed for $service on port $port" >&2
    exit 1
  fi
  echo "ok $service /health on 127.0.0.1:$port"
}

echo "== Local health probes from Android =="
health 8099 hostd
health 8080 webd
health 8090 filed
health 8088 proxyd

echo "ok"
