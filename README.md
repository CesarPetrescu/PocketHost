# PocketHost

PocketHost turns an Android phone or tablet into a small, supervised personal
server.

It is built for spare devices: a phone on a charger, an old tablet on a shelf,
or an emulator used as a lab box. The Android app is the control panel. Native
daemons do the actual serving work.

PocketHost is currently a developer-sideload MVP, not a production release. It
can build, run the core local daemons, package split APKs, and expose selected
services through Cloudflare Tunnel, but release signing, public distribution,
automated device CI, and full Matrix/Nextcloud validation are still in progress.

## What It Does

PocketHost provides:

- Android dashboard for starting, stopping, restarting, and inspecting services.
- Foreground supervisor service with a persistent notification while daemons run.
- Local web server for static files.
- Token-protected file API for browse, upload, download, and delete.
- Host web control panel served by `hostd`.
- Local reverse proxy for service routing.
- Optional DDNS updater.
- Optional Cloudflare Tunnel supervisor slot.
- Optional Matrix homeserver slot.
- Experimental Nextcloud wrapper slot.
- SQLite-backed log persistence and diagnostics bundle export.
- Loopback-first security defaults with explicit LAN exposure toggle.

## Current Status

The useful core works today:

- Android app builds with Kotlin, Jetpack Compose, and AGP.
- Default Go daemons build and pass local tests.
- Local daemon verification passes.
- Debug APKs build for `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`, and
  `universal`.
- Native daemon artifacts are already staged under `android/app/src/main/jniLibs`.

Still not public-release ready:

- Release builds are debug-signed.
- Google Play distribution is not implemented.
- Android device and emulator tests are not automated in CI.
- Matrix direction needs cleanup across docs and runtime artifacts.
- Nextcloud is experimental and depends on PHP runtime assets.
- Cloudflare public route verification still needs device evidence.
- Production update/signature handling for daemon bundles is not implemented.

## System Design

PocketHost is deliberately split into a control plane and a data plane.

```text
Android app, Kotlin + Jetpack Compose
  -> ServerCommands
  -> ServerForegroundService
  -> ProcessSupervisor
  -> native daemon executables from applicationInfo.nativeLibraryDir
  -> local HTTP services on 127.0.0.1
  -> optional Cloudflare Tunnel for public ingress
```

### Control Plane

The Android app owns device-specific responsibilities:

- UI and service controls.
- Foreground service lifecycle.
- Persistent notification and stop surface.
- Boot receiver for optional autostart.
- Service configuration and preflight checks.
- Health polling.
- Log collection, redaction, retention, and display.
- Diagnostics bundle creation.

Key files:

- `android/app/src/main/java/dev/pockethost/ui/PocketHostApp.kt`
- `android/app/src/main/java/dev/pockethost/supervisor/ServerForegroundService.kt`
- `android/app/src/main/java/dev/pockethost/supervisor/ProcessSupervisor.kt`
- `android/app/src/main/java/dev/pockethost/supervisor/ServiceRegistry.kt`
- `android/app/src/main/java/dev/pockethost/supervisor/ServicePreferences.kt`

### Data Plane

Native daemons own the service workloads:

| Service | Binary | Default | Port | Purpose |
|---|---|---:|---:|---|
| Host API | `libhostd.so` | on | 8099 | Host health, web panel, daemon status aggregation |
| Web Server | `libwebd.so` | on | 8080 | Static/local web hosting |
| MiniCloud Files | `libfiled.so` | on | 8090 | Token-protected file API |
| Local Reverse Proxy | `libproxyd.so` | on | 8088 | Host-based local reverse proxy |
| DDNS Updater | `libddnsd.so` | off | 8091 | Optional Cloudflare DNS updater |
| Matrix Server | `libmatrixd.so` | off | 6167 | Matrix homeserver slot |
| Nextcloud Experimental | `libnextcloudd.so` | off | 8092 | Experimental PHP/Nextcloud wrapper |
| Cloudflare Tunnel | `libcloudflared.so` | off | n/a | Optional public tunnel client |

The Go daemons live under `go/cmd/*`. Shared daemon safety code lives in
`go/internal/pocket`.

### Android Native Packaging

The daemon files are packaged as native library artifacts:

```text
android/app/src/main/jniLibs/arm64-v8a/libhostd.so
android/app/src/main/jniLibs/arm64-v8a/libwebd.so
android/app/src/main/jniLibs/arm64-v8a/libfiled.so
android/app/src/main/jniLibs/arm64-v8a/libproxyd.so
android/app/src/main/jniLibs/arm64-v8a/libddnsd.so
android/app/src/main/jniLibs/arm64-v8a/libmatrixd.so
android/app/src/main/jniLibs/arm64-v8a/libnextcloudd.so
android/app/src/main/jniLibs/arm64-v8a/libcloudflared.so
android/app/src/main/jniLibs/arm64-v8a/libphp.so
```

The `.so` suffix is an Android packaging mechanism. PocketHost launches these
files as executable child processes from `applicationInfo.nativeLibraryDir`.

### Runtime Flow

```text
User taps Start all
  -> Compose calls ServerCommands
  -> Android starts ServerForegroundService
  -> ProcessSupervisor resolves each ServiceSpec
  -> NativeBinaryLocator finds lib<name>.so
  -> ProcessBuilder starts the daemon
  -> stdout/stderr stream into LogBus
  -> LogBus redacts and stores logs in SQLite
  -> HealthMonitor probes local HTTP endpoints
  -> Compose state updates through StateFlow
```

### Network Model

PocketHost binds services to loopback by default:

```text
127.0.0.1:8099 hostd
127.0.0.1:8080 webd
127.0.0.1:8090 filed
127.0.0.1:8088 proxyd
127.0.0.1:8091 ddnsd
127.0.0.1:6167 matrixd
127.0.0.1:8092 nextcloudd
```

The Settings screen has an explicit LAN exposure toggle. When enabled, the app
passes `0.0.0.0:<port>` and sets `POCKETHOST_ALLOW_PUBLIC_BIND=true`. Without
that environment variable, the Go daemons refuse non-loopback bind addresses.

Public internet access should go through Cloudflare Tunnel or another deliberate
tunnel path, not accidental raw port exposure.

## Security Defaults

- Services bind to `127.0.0.1` by default.
- LAN binding is opt-in and visibly warned in the UI.
- Daemons reject public bind addresses unless explicitly allowed.
- Admin APIs support `X-PocketHost-Token` and `Authorization: Bearer`.
- Token comparison uses a constant-time helper.
- `/health` stays unauthenticated for local supervision.
- File and web paths reject traversal and symlink escape.
- Directory listing is disabled by default.
- Uploads have a configurable byte cap and atomic commit behavior.
- Cloudflare credentials are not committed and are copied into app-private
  storage when imported.
- Logs are redacted before UI and SQLite storage.
- SQLite log retention is bounded.

## Repository Layout

```text
PocketHost/
|- android/                 Android app, Compose UI, foreground supervisor
|- go/                      Go daemon source and tests
|- rust/matrixd/            Matrix placeholder adapter source
|- configs/examples/        Safe sample configs without secrets
|- docs/                    Architecture, product, threat model, runbooks
|- releases/                Local APK staging area
|- scripts/                 Build, package, and verification scripts
|- AGENTS.md                Agent/developer rules for this repo
|- FLYWHEEL.md              Change and evidence process
|- SOUL.md                  Product and engineering taste notes
|- LICENSE                  Apache-2.0
`- NOTICE                   Third-party integration notes
```

## Build Requirements

Recommended local toolchain:

- JDK 17+
- Android SDK platform 36
- Android build tools 36.0.0
- Android NDK 27+
- Go 1.23+
- Gradle wrapper from `android/gradlew`
- Kotlin is resolved by the Android Gradle plugin; standalone `kotlinc` is useful
  but not required for the Android build.

Set:

```bash
export ANDROID_SDK_ROOT=$HOME/Android/Sdk
export ANDROID_HOME=$ANDROID_SDK_ROOT
export ANDROID_NDK_ROOT=$ANDROID_SDK_ROOT/ndk/27.2.12479018
```

## Build And Test

Run the local repository baseline:

```bash
./scripts/ci-local.sh
```

That runs:

- Go unit tests.
- Go formatting check.
- Local live daemon verification.
- Shell syntax checks.
- Basic repository file checks.

Run Go tests directly:

```bash
cd go
go test ./...
```

Build Android Go daemons:

```bash
./scripts/build-go-android.sh all
```

Build the Android debug APKs:

```bash
cd android
./gradlew :app:assembleDebug
```

Stage local split APK artifacts:

```bash
./scripts/package-android.sh release
```

The current `release` build type is debug-signed and is for sideload testing
only.

## First Device Smoke Test

1. Install the debug APK on an Android 10+ device.
2. Grant notification permission.
3. Tap `Start all`.
4. Confirm the persistent PocketHost notification appears.
5. Confirm default services show as running.
6. Probe health endpoints:

```bash
adb shell 'toybox wget -qO- http://127.0.0.1:8099/health || true'
adb shell 'toybox wget -qO- http://127.0.0.1:8080/health || true'
adb shell 'toybox wget -qO- http://127.0.0.1:8090/health || true'
adb shell 'toybox wget -qO- http://127.0.0.1:8088/health || true'
```

7. Open Logs and confirm daemon output appears.
8. Tap `Stop all` and confirm services stop.

See `docs/runbooks/VERIFY_ANDROID_DEVICE.md` for the fuller checklist.

## Definition Of Done

For ordinary code changes:

- `./scripts/ci-local.sh` passes.
- No secrets are added to code, docs, configs, screenshots, logs, or fixtures.
- Network behavior remains loopback-first unless a human-approved change says
  otherwise.
- Service failures show useful states instead of crashing the app.
- Docs are updated when behavior, commands, ports, or release status changes.

For Android behavior changes, also collect:

- APK build result.
- Install evidence on an emulator or physical device.
- Foreground notification evidence.
- At least one daemon started from the app.
- Successful local `/health` probe.
- Logs visible in the app.

For tunnel, Matrix, Nextcloud, release, or data-migration changes, follow the
gates in `AGENTS.md` and `FLYWHEEL.md`.

## Roadmap

Near-term:

- Clean up Matrix direction and docs around the selected runtime.
- Add Android instrumented smoke tests to CI.
- Collect repeatable emulator/device evidence for APK changes.
- Improve Cloudflare route verification and named tunnel evidence.
- Keep Nextcloud isolated and experimental until PHP/runtime validation is solid.

Later:

- Production signing.
- Signed daemon bundle/update model.
- Backup and restore flows for Matrix and Nextcloud data.
- Public release process and release notes.
- Optional enterprise/device-owner deployment mode.

## License

PocketHost code and docs are licensed under Apache-2.0. See `LICENSE`, `NOTICE`,
and `docs/LICENSE_DECISION.md`.
