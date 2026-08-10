# Building PocketHost from source

This guide builds the whole app from a clean checkout, **including the large
native payloads that are deliberately kept out of git** (the PHP-for-Android
runtime and the Nextcloud server payload). It also covers how each daemon binary
is produced and how to verify everything on an emulator.

> TL;DR for a full build:
> ```bash
> ./scripts/build-go-android.sh all                 # Go daemons, 4 ABIs
> ./scripts/build-cloudflared-android.sh <src> all  # cloudflared (see notes)
> ./scripts/build-dendrite-android.sh               # Matrix (Dendrite), arm64+x86_64
> ./scripts/build-php-android-runtime.sh arm64-v8a x86_64   # PHP runtime + assets
> ./scripts/fetch-nextcloud-payload.sh 33.0.5       # Nextcloud asset
> cd android && ./gradlew :app:assembleDebug        # APK with everything bundled
> ```

---

## 1. What is in git vs what you must build

The repo commits the native binaries under `android/app/src/main/jniLibs/<abi>/`,
but **coverage is per-ABI** — Matrix, the PHP binary, and cloudflared are 64-bit /
selective (see §9). It does **not** commit the bulky asset payloads, which are
`.gitignore`d under `android/deps/`:

| Artifact | In git? | How to (re)build |
|---|---|---|
| Go daemons (`lib{hostd,webd,filed,proxyd,ddnsd,nextcloudd}.so`) | yes — all 4 ABIs | `scripts/build-go-android.sh` |
| `libcloudflared.so` | yes — arm64-v8a, x86, x86_64 (not armeabi-v7a) | `scripts/build-cloudflared-android.sh` |
| `libmatrixd.so` (Dendrite) | yes — arm64-v8a, x86_64 only | `scripts/build-dendrite-android.sh` |
| `libphp.so` | yes — arm64-v8a, x86_64 only | `scripts/build-php-android-runtime.sh` |
| `php-runtime-<abi>.zip` (PHP libs + extensions) | **no — build it** | `scripts/build-php-android-runtime.sh` |
| `nextcloud-server-<ver>.zip` | **no — build it** | `scripts/fetch-nextcloud-payload.sh` |

The two `.zip`s land in `android/deps/assets/` and are bundled into the APK as
assets at build time (see §6). The committed `libmatrixd.so` / `libphp.so` can lag
upstream — re-run the matching script (e.g. `./scripts/build-dendrite-android.sh`)
to refresh them.

---

## 2. Prerequisites

| Tool | Used for | Notes |
|---|---|---|
| JDK 17+ | Gradle / Android build | bundled with Android Studio |
| Android SDK + **NDK** | APK + native cross-compile | NDK 26 or 27 works; install under `$ANDROID_SDK_ROOT/ndk` |
| Go 1.23+ | Go daemons, cloudflared, Dendrite | |
| Docker | PHP-for-Android runtime (Termux builder) | needed only for `libphp.so` / the runtime zip |
| Python 3 | zip assembly in the runtime/payload scripts | |
| `unzip`, `ar`, `tar`, `curl` | payload extraction/download | |
| adb (Android Platform-Tools) | APK install + port-forward (§7–8) | bundled with the SDK |
| Android emulator or device | running/verifying the APK | x86_64 image recommended on desktop; arm64 needs an ARM device/emulator (§9) |

Set once:
```bash
export ANDROID_SDK_ROOT=$HOME/Android/Sdk      # or your SDK path
export ANDROID_HOME=$ANDROID_SDK_ROOT
```
The build scripts auto-discover the newest NDK under `$ANDROID_SDK_ROOT/ndk`, or
honor `ANDROID_NDK_ROOT`. **Set these before running any build script below** — they
are used to locate the NDK and SDK tools.

---

## 3. Go daemons (all ABIs)

```bash
./scripts/build-go-android.sh all          # arm64-v8a armeabi-v7a x86 x86_64
# or selected: ./scripts/build-go-android.sh x86_64 arm64-v8a
```
Builds `hostd webd filed proxyd ddnsd nextcloudd` into each `jniLibs/<abi>/`.
arm64 links statically (`CGO_ENABLED=0`); the 32-bit + x86_64 targets use NDK
clang with cgo. Run `cd go && go test ./...` first for the unit-test baseline.

---

## 4. cloudflared

Build the official source for the ABIs you ship:
```bash
git clone --depth 1 --branch 2026.5.2 https://github.com/cloudflare/cloudflared.git /tmp/cloudflared
./scripts/build-cloudflared-android.sh /tmp/cloudflared arm64-v8a x86 x86_64
```
**Note:** `build-cloudflared-android.sh` currently has cases for arm64-v8a / x86 /
x86_64 only; `armeabi-v7a` is not produced (32-bit ARM is not a supported tier —
see §8).

---

## 5. Matrix homeserver — Dendrite (replaces Tuwunel)

```bash
./scripts/build-dendrite-android.sh           # arm64-v8a + x86_64
```
We ship **Dendrite** (Go) rather than Tuwunel. The Tuwunel build panics on
Android: it pulls the `ndk-context` crate, which needs a JVM/Android context that
a daemon launched as a forked `ProcessBuilder` process cannot provide (it also
hits an unimplemented `RUSAGE_THREAD`). Dendrite is pure Go (pure-Go SQLite) and
runs cleanly as a supervised process.

- arm64-v8a builds with `CGO_ENABLED=0`; x86_64 needs NDK clang + cgo.
- The app generates `dendrite.yaml` at runtime from `res/raw/dendrite_template.yaml`
  and generates the ed25519 signing key with `SecureRandom`
  (`ServicePreferences.writeDendriteConfig` / `ensureMatrixSigningKey`).
- Matrix is **64-bit only**.

---

## 6. PHP runtime + Nextcloud payload (the gitignored assets)

### 6a. PHP-for-Android runtime
```bash
./scripts/build-php-android-runtime.sh arm64-v8a x86_64
```
For each ABI this runs the **Termux package builder in Docker** to compile PHP
8.5 + all its native deps, then assembles a relocatable runtime:

- `jniLibs/<abi>/libphp.so` — the `php` binary (17 of the 19 required extensions
  are compiled in: `intl`/ICU, `mbstring`, `xml`/`dom`/`simplexml`/`xmlreader`/
  `xmlwriter`, `zip`, `curl`, `openssl`, `zlib`, `sqlite3`/`pdo_sqlite`,
  `fileinfo`, `ctype`, `session`, `posix`).
- `deps/assets/php-runtime-<abi>.zip` containing:
  - `lib/` — ~238 shared libs the php binary needs (libicu, libxml2, libcurl,
    libsqlite, libzip, libpng, libsodium, …); `nextcloudd` adds this to
    `LD_LIBRARY_PATH`.
  - `extensions/` — `gd.so` + `sodium.so` (the two shared extensions).
  - `extensions.txt` — manifest of all 19 so the app's static preflight passes
    for the built-in ones.

**Gotchas already handled in code** (`PhpRuntimeInstaller.writePhpIni`):
`extension=gd` + `extension=sodium` are emitted, and **opcache is disabled** —
Termux's opcache hardcodes `/data/data/com.termux/.../tmp`, which does not exist
in PocketHost's sandbox and crashes `php -S`.

Each ABI is a long (~30–40 min) from-source build.

### 6b. Nextcloud payload
```bash
./scripts/fetch-nextcloud-payload.sh 33.0.5
```
Downloads Nextcloud and writes `deps/assets/nextcloud-server-<ver>.zip`. Keep the
version in sync with `NextcloudInstaller.kt` (`VERSION` / `ASSET_NAME`).

### 6c. Where the assets live
Gradle reads extra asset dirs from (first match wins):
1. `-PpocketHostAssetsDir=/abs/path`
2. `POCKETHOST_ASSETS_DIR` env var
3. default `android/deps/assets` (gitignored)

So the bulky zips never enter git but are packaged into the APK.

---

## 7. Build & install the APK

```bash
cd android
./gradlew :app:assembleDebug
```
Outputs per-ABI + universal APKs under
`android/app/build/outputs/apk/debug/`. With both runtime zips + the Nextcloud
zip bundled, each 64-bit split is ~630 MB (assets are shared across splits).

Install + first run:
```bash
adb install -r -g app/build/outputs/apk/debug/app-x86_64-debug.apk
```
1. Open the app, grant the notification permission.
2. **Nextcloud tab** → *Install packaged PHP runtime* **first**, then *Install
   packaged Nextcloud payload* (these unpack the bundled assets into app-private
   storage). The order matters: `ServicePreferences.nextcloudPreflight` checks the
   PHP runtime first, so Nextcloud won't start without it.
3. Set a server name on the **Matrix tab** (Save and start) if you want Matrix.
4. Enable + start services from the **Services** tab or *Start all* on the
   Dashboard. Nextcloud first-run setup completes in the browser via the served
   setup wizard (SQLite).

> The `release` build type is currently **debug-signed** (sideload/testing only).

---

## 8. Verifying it works (per component)

With services started, probe each daemon (health endpoints are public; forward a
port with `adb forward tcp:1<port> tcp:<port>` first):

| Component | Check | Expected |
|---|---|---|
| hostd | `GET :8099/health` | `200 {"service":"hostd"…}` |
| webd | `GET :8080/health` | `200` |
| filed | `GET :8090/health` | `200` |
| proxyd | `GET :8088/health` | `200` |
| ddnsd | `GET :8091/health` | `200` |
| hostd web panel | `GET :8099/` | `200`, "PocketHost Control Panel" |
| token gate | `GET :8099/api/services` (no token) | `401` |
| Matrix (Dendrite) | `GET :6167/_matrix/client/versions` | `200` versions list |
| Cloudflare | start quick tunnel; the `trycloudflare.com` URL appears in Logs | URL returns `200` publicly |
| Nextcloud | `GET :8092/health`; `GET :8092/status.php` | `200`; `installed:true` |

`scripts/verify-android-emulator.sh` automates the default-daemon smoke test;
`scripts/ci-local.sh` runs the Go unit tests + local daemon security checks.

---

## 9. Known limitations / tiers

- **64-bit only for Matrix, Nextcloud, and the PHP runtime.** `x86` and
  `armeabi-v7a` get the core Go daemons but not `matrixd`/`nextcloudd`/`php`
  (and `armeabi-v7a` also lacks `cloudflared`). Prefer shipping `arm64-v8a` +
  `x86_64` only.
- **arm64 is built but cannot be run-tested without an ARM emulator/device.**
  The x86_64 path is fully verified on the emulator; arm64 uses byte-identical
  packaging.
- **Release signing, Play distribution, and CI APK/device tests are not set up.**
- Nextcloud uses **SQLite** and is **experimental / off by default**.
