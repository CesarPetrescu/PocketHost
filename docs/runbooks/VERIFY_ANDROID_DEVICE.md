# Verify on Android device

Use this runbook before calling an Android change done.

## Preconditions

- Android SDK path configured with `ANDROID_HOME`, `ANDROID_SDK_ROOT`, or `android/local.properties` containing `sdk.dir=/path/to/android/sdk`.
- SDK platform `android-36` installed to match the app `compileSdk`.
- Android Build Tools installed; use Build Tools 36.x when available for this project.
- Android NDK installed if rebuilding Go daemon artifacts for `armeabi-v7a`, `x86`, or `x86_64`.
- ARM64 Android phone/tablet
- APK installed
- notification permission granted
- battery optimization exception considered for long tests

For an x86_64 emulator smoke test, use:

```bash
./scripts/verify-android-emulator.sh
```

The script builds the debug APK, installs the x86_64 split on the connected
emulator, taps **Start all**, checks the foreground notification, confirms the
default daemon processes, and probes `/health` from inside Android.

## Steps

### Build and stage split APKs

From the repository root:

```bash
./scripts/package-android.sh release
```

Use the APK in `releases/apk/` matching the device ABI, or use the universal APK
for broad sideload compatibility. Expected staged developer APKs are:

- `pockethost-release-arm64-v8a-release.apk`
- `pockethost-release-armeabi-v7a-release.apk`
- `pockethost-release-x86-release.apk`
- `pockethost-release-x86_64-release.apk`
- `pockethost-release-universal-release.apk`

These APKs are debug-signed sideload/developer artifacts only. Do not treat
them as public release artifacts without a separate release-signing review.

### Device smoke test

1. Open PocketHost.
2. Tap **Start all**.
3. Confirm persistent notification appears.
4. Confirm hostd, webd, filed, and proxyd are running.
5. Run local probes:

```bash
adb shell "printf 'GET /health HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n' | nc -w 3 127.0.0.1 8099"
adb shell "printf 'GET /health HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n' | nc -w 3 127.0.0.1 8080"
adb shell "printf 'GET /health HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n' | nc -w 3 127.0.0.1 8090"
adb shell "printf 'GET /health HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n' | nc -w 3 127.0.0.1 8088"
```

6. Open Logs screen and confirm lines are visible.
7. Tap **Stop all**.
8. Confirm the notification disappears and health endpoints stop responding.

## Evidence to save

- Android version and device model
- APK version
- screenshots of Dashboard and notification
- health response output
- relevant log excerpt


## Additional checks after Flywheel 006-015

1. Open Settings and create a diagnostics bundle. Record the displayed path.
2. Confirm Logs does not show raw bearer tokens or admin tokens after starting services.
3. From `adb shell`, verify local health still works without a token:

```bash
printf 'GET /health HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n' | nc -w 3 127.0.0.1 8099
```

4. Verify token-gated endpoints reject unauthenticated calls. Example:

```bash
toybox wget -qO- http://127.0.0.1:8099/api/status || true
```

Expected: unauthorized JSON response unless the request includes the Android admin token.

5. Confirm `webd` does not list directories and `filed` rejects unauthenticated file API calls when the admin token is configured.
