# PocketHost

PocketHost is an Android mini-server control plane for repurposed phones and tablets.

It runs an Android-native supervisor app and launches small native daemons for web, files, DDNS, proxying, host status, Matrix, and Cloudflare Tunnel integration.


## Current project status

PocketHost is an early, developer-sideload MVP. The repository contains a working Android control-plane shell, a foreground supervisor, Go daemon sources with local health/security tests, prebuilt daemon artifacts in `jniLibs/` for the configured Android ABIs, a Rust Matrix placeholder, and a Cloudflare Tunnel binary slot. It is **not** release-ready for public distribution: release signing, automated Android device validation, Cloudflare credential import, public tunnel verification, Matrix homeserver replacement, and Google Play policy work remain pending.

Use `./scripts/ci-local.sh` for the local Go/test verification baseline and `./scripts/package-android.sh release` to create local split APKs under `releases/apk/`. The generated release variant is currently debug-signed for sideload testing only.

## Final stack

| Layer | Choice |
|---|---|
| Android app | Kotlin + Jetpack Compose |
| Long-running control | ForegroundService with persistent notification |
| App database | SQLite |
| Custom daemons | Go |
| Matrix slot | Rust-first; Go fallback only after license/maintenance review |
| Public ingress | official `cloudflared` supervised by the app |
| Nextcloud | out of scope for PocketHost core |
| License | Apache-2.0 |

## Repository layout

```text
PocketHost/
├─ android/                 Android/Kotlin app
├─ go/                      Go daemon sources
├─ rust/matrixd/            Matrix adapter placeholder
├─ scripts/                 build, packaging, local CI scripts
├─ configs/examples/        sample configs without secrets
├─ docs/                    architecture, product, threat model, runbooks
├─ AGENTS.md                what agents should build
├─ SOUL.md                  engineering identity and taste
├─ FLYWHEEL.md              how changes ship and get verified
├─ LICENSE                  Apache-2.0
└─ NOTICE                   notices and third-party integration rules
```

## What is implemented

- Compose dashboard/services/network/storage/logs/settings UI with Material 3 theming, status chips, and per-service uptime
- `hostd` web control panel: aggregated live daemon status plus per-daemon controls (DDNS refresh, file browse/upload/download/delete), token-gated over loopback
- operator network-exposure toggle (loopback `127.0.0.1` ⇄ `0.0.0.0`), off by default and wired through the daemon bind guard
- foreground service supervisor
- boot receiver
- SQLite log persistence
- native daemon launcher
- Android-side daemon health probing with degraded-state reporting
- Go daemons:
  - `hostd`
  - `webd`
  - `filed`
  - `proxyd`
  - `ddnsd`
- Android daemon packaging paths for `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`
- Matrix binary slot with a Rust placeholder implementation
- bundled `cloudflared` binary slot for ARM64, x86, and x86_64 Android builds
- local CI script, Go unit tests, Go formatting checks, and live daemon health/security verification
- Flywheel process docs and release evidence rules
- Android diagnostics bundle creation from Settings
- Android log redaction and bounded log retention

## What is intentionally not implemented yet

- full Matrix homeserver source inside this repo
- Cloudflare tunnel credential import and public route verification
- native Nextcloud
- Google Play release pipeline
- production update/signature system for daemon bundles

## Android packaging model

Daemon executables are packaged as native-library artifacts:

```text
android/app/src/main/jniLibs/arm64-v8a/libwebd.so
android/app/src/main/jniLibs/arm64-v8a/libfiled.so
android/app/src/main/jniLibs/arm64-v8a/libddnsd.so
android/app/src/main/jniLibs/arm64-v8a/libproxyd.so
android/app/src/main/jniLibs/arm64-v8a/libhostd.so
android/app/src/main/jniLibs/arm64-v8a/libmatrixd.so       optional
android/app/src/main/jniLibs/arm64-v8a/libcloudflared.so
```

The `.so` suffix is an Android packaging mechanism. These files are normal executable daemons launched from `applicationInfo.nativeLibraryDir`.

## Build and test

Run local repository checks, Go formatting checks, unit tests, and live daemon health/security verification:

```bash
./scripts/ci-local.sh
```

Run live daemon verification only:

```bash
./scripts/verify-daemons-local.sh
```

Run Go tests directly:

```bash
cd go
go test ./...
```

Build Android Go daemons for every configured APK split:

```bash
./scripts/build-go-android.sh all
```

You can also build selected ABIs, for example:

```bash
./scripts/build-go-android.sh arm64-v8a
./scripts/build-go-android.sh x86_64 x86
```

The `armeabi-v7a`, `x86`, and `x86_64` ABIs require Android NDK clang wrappers. Install the NDK under `$ANDROID_SDK_ROOT/ndk` or set `ANDROID_NDK_ROOT`.

Build the bundled Android `cloudflared` artifacts from an official upstream checkout:

```bash
git clone --depth 1 --branch 2026.5.2 https://github.com/cloudflare/cloudflared.git /tmp/cloudflared-2026.5.2
./scripts/build-cloudflared-android.sh /tmp/cloudflared-2026.5.2 all
```

Build the Android app from Android Studio by opening `android/`, or from the repository root with the wrapper-backed packaging script:

```bash
./scripts/package-android.sh release
```

The packaging script runs Gradle and copies split APKs for `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`, and `universal` into `releases/apk/`. The current release build type is debug-signed for sideload testing; it is not a public release-signing pipeline.

## First Android smoke test

1. Install debug APK on an ARM64 Android device.
2. Grant notification permission.
3. Tap **Start all**.
4. Confirm the persistent notification appears.
5. Confirm default services are running.
6. Probe health endpoints:

```bash
adb shell 'toybox wget -qO- http://127.0.0.1:8099/health || true'
adb shell 'toybox wget -qO- http://127.0.0.1:8080/health || true'
adb shell 'toybox wget -qO- http://127.0.0.1:8090/health || true'
adb shell 'toybox wget -qO- http://127.0.0.1:8088/health || true'
```

7. Open Logs screen and confirm daemon output appears.
8. Tap **Stop all** and confirm services stop.

See `docs/runbooks/VERIFY_ANDROID_DEVICE.md`.

## Security defaults

- services bind to `127.0.0.1` by default
- public exposure is opt-in through a tunnel
- no Cloudflare credentials in the APK or repo
- file/host/DDNS admin APIs support token auth through `X-PocketHost-Token` or `Authorization: Bearer`
- token checks use a constant-time comparison helper
- daemons refuse non-loopback binds unless `POCKETHOST_ALLOW_PUBLIC_BIND=true` is explicitly set
- file and web servers reject traversal and symlink escape paths
- web and file directory listing are disabled by default
- uploads have configurable byte caps and atomic commit behavior
- raw network SQL is not part of the MVP
- Matrix database ownership belongs to the selected Matrix binary
- Nextcloud is not part of the core app
- Android logs redact bearer tokens and common secret assignment patterns

## License

PocketHost code and docs are licensed under Apache-2.0. See `LICENSE`, `NOTICE`, and `docs/LICENSE_DECISION.md`.

## Flywheel evidence

The first five implementation turns are recorded in `docs/flywheel/TURNS_001_005.md`. Turns 006-015 are recorded in `docs/flywheel/TURNS_006_015.md`. Turns 016-020 and later are recorded under `docs/flywheel/`.
