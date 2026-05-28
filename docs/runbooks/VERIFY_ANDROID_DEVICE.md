# Verify on Android device

Use this runbook before calling an Android change done.

## Preconditions

- ARM64 Android phone/tablet
- APK installed
- notification permission granted
- battery optimization exception considered for long tests

## Steps

1. Open PocketHost.
2. Tap **Start all**.
3. Confirm persistent notification appears.
4. Confirm hostd, webd, filed, and proxyd are running.
5. Run local probes:

```bash
adb shell 'toybox wget -qO- http://127.0.0.1:8099/health || true'
adb shell 'toybox wget -qO- http://127.0.0.1:8080/health || true'
adb shell 'toybox wget -qO- http://127.0.0.1:8090/health || true'
adb shell 'toybox wget -qO- http://127.0.0.1:8088/health || true'
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
toybox wget -qO- http://127.0.0.1:8099/health || true
```

4. Verify token-gated endpoints reject unauthenticated calls. Example:

```bash
toybox wget -qO- http://127.0.0.1:8099/api/status || true
```

Expected: unauthorized JSON response unless the request includes the Android admin token.

5. Confirm `webd` does not list directories and `filed` rejects unauthenticated file API calls when the admin token is configured.
