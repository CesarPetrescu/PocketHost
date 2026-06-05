# PocketHost Completion TODO

## Critical blockers

- Build real Android PHP 8.4 runtime for `arm64-v8a` and `x86_64`.
- Install Docker Desktop or a real WSL Linux distro so the Termux-derived PHP build can run.
- Rebuild PHP from open-source Termux/php-src sources, not random third-party binaries.
- Stage PHP artifacts with `scripts/stage-php-runtime.ps1`:
  - `android/app/src/main/jniLibs/arm64-v8a/libphp.so`
  - `android/app/src/main/jniLibs/x86_64/libphp.so`
  - `D:\PocketHostDeps\php-android\assets\php-runtime-arm64-v8a.zip`
  - `D:\PocketHostDeps\php-android\assets\php-runtime-x86_64.zip`
- Confirm PHP has required Nextcloud extensions: `sqlite3`, `pdo_sqlite`, `mbstring`, `intl`, `xml`, `xmlreader`, `xmlwriter`, `simplexml`, `dom`, `zip`, `curl`, `gd`, `fileinfo`, `openssl`, `sodium`, `ctype`, `session`, `zlib`, `posix`.

## Nextcloud completion

- Run the in-app PHP runtime installer on x86_64 emulator and ARM64 phone.
- Run the in-app PHP module self-check and fix missing extensions/config paths.
- Install the packaged Nextcloud `v32.0.11` payload from the app.
- Start Nextcloud on `127.0.0.1:8092`.
- Verify `/health` returns OK.
- Complete SQLite setup through the web UI.
- Verify admin login, upload/download, restart persistence, and data directory persistence.
- Keep Nextcloud marked experimental/minimal and off by default.

## Matrix completion

- Run Tuwunel on ARM64 phone and x86_64 emulator.
- Verify `/_matrix/client/versions` locally.
- Register/login with a Matrix client using the configured registration token.
- Test Matrix through Cloudflare Quick Tunnel.
- Keep federation off unless DNS, server name, and `.well-known` routing are intentionally configured.

## Cloudflare completion

- Run Quick Tunnel on-device.
- Confirm the UI extracts and displays the `trycloudflare.com` URL.
- Confirm copy buttons work for tunnel URL and admin token.
- Test named tunnel credential import with Android file picker.
- Confirm named tunnel config generation works and credentials stay app-private.
- Verify tunnel routes only expose the selected service.

## APK/device validation

- Rebuild release split APKs after PHP artifacts are staged.
- Confirm ARM64 and x86_64 APKs contain `libcloudflared.so`, `libmatrixd.so`, `libnextcloudd.so`, `libphp.so`, PHP runtime asset zip, and Nextcloud payload zip.
- Install/test ARM64 APK on a physical Android phone.
- Install/test x86_64 APK on the API 36 emulator under `D:\AndroidBuild\avd`.
- Run foreground notification, service start/stop/restart, log stream, and settings persistence smoke tests.

## Production hardening later

- Add production signing instead of debug-signed release APKs.
- Add backup/export flow for Matrix and Nextcloud data.
- Add upgrade/rollback flow for Nextcloud payload and PHP runtime.
- Add stronger diagnostics bundle redaction for any future secrets.
- Decide whether old `armeabi-v7a` and `x86` splits should be removed or kept without Matrix/Nextcloud support.
- Clean up Android SDK duplicate command-line-tools warning under `D:\AndroidSDK`.
