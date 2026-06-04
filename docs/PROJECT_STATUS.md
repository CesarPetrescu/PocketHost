# Project status

Last updated: 2026-06-04.

## Summary

PocketHost is a developer-sideload MVP for turning an Android phone or tablet
into a supervised local mini-server host. The repository currently includes the
Android control plane, Go daemon data plane, a Rust Matrix placeholder, local
verification scripts, and documentation/runbooks for the safety model.

It is not yet a public release product. The current Android `release` build type
is debug-signed for install testing, and the staged APKs in `releases/apk/` are
local developer artifacts only.

## Implemented

- Kotlin/Jetpack Compose Android app with dashboard, service management,
  network, storage, logs, and settings screens.
- Foreground supervisor service, boot receiver, health probing, diagnostics
  bundle generation, redacted logs, and SQLite-backed log persistence.
- Native daemon packaging through `jniLibs/<abi>/lib<name>.so`, launched from
  `applicationInfo.nativeLibraryDir`.
- Go daemons for `hostd`, `webd`, `filed`, `proxyd`, and `ddnsd`.
- Host web control panel served by `hostd` with token-gated API routes.
- Loopback-first network policy with explicit operator LAN exposure override.
- Rust `matrixd` placeholder with `/health` and `/_matrix/client/versions`.
- Cloudflare Tunnel supervisor slot for `libcloudflared.so` without committing
  tunnel credentials.
- Local Go unit tests plus live daemon health/security verification script.
- Gradle ABI splits for `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`, and a
  universal APK.

## Not implemented / not complete

- Real Matrix homeserver bundling and migration/backup support.
- Cloudflare tunnel credential import, route editor, dashboard evidence, and
  public route verification.
- Android instrumented tests in CI.
- Real-device battery, thermal, and OEM background-kill evidence.
- Release signing, Play distribution workflow, update channels, and production
  daemon-bundle signatures.
- Native Nextcloud support; it remains out of core scope.

## Build artifact policy

Run this command to stage local APK artifacts:

```bash
./scripts/package-android.sh release
```

The script copies Gradle split APK outputs to `releases/apk/`. These files are
for local sideload testing only unless a future, human-reviewed release process
adds production signing and release notes.

## Verification baseline

Minimum local evidence for code changes remains:

```bash
./scripts/ci-local.sh
```

For Android changes, the Flywheel still requires device or emulator evidence:
installed APK, foreground notification, daemon started from the app, logs, and a
successful daemon `/health` probe.
