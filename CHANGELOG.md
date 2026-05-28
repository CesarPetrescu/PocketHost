# Changelog

## 0.1.2 - Flywheel turns 006-015

### Added

- Shared Go HTTP middleware for security headers, request IDs, structured access logging, JSON errors, and constant-time token checks.
- Loopback-only daemon bind guard with explicit `POCKETHOST_ALLOW_PUBLIC_BIND=true` override.
- Safe `webd` static handler with traversal/symlink protection and directory listing disabled.
- Configurable `filed` upload limit via `--max-upload-bytes` / `POCKETHOST_FILED_MAX_UPLOAD_BYTES`.
- Atomic MiniCloud upload commits through temporary files and rename.
- DDNS public-IP validation, A/AAAA record validation, configurable IP/API endpoints, and unchanged-IP skip logic.
- Token protection for `hostd` `/api/status` and `ddnsd` `/api/update-now`; `/health` remains open for local probes.
- `proxyd` precompiled routes, bounded upstream transport, explicit 502 errors, dropped-route logging, and upstream evidence header.
- Android `SecretRedactor`, SQLite log retention, and diagnostics zip creation from Settings.
- CI checks for Go formatting plus stronger live daemon verification of bind guard, token gates, headers, proxying, and disabled directory listing.
- Flywheel evidence log at `docs/flywheel/TURNS_006_015.md`.

### Changed

- `pocket.Version` is now `0.1.2`.
- Rebuilt Android ARM64 daemon artifacts in `android/app/src/main/jniLibs/arm64-v8a/`.
- `ServiceRegistry` now passes the Android admin token to `hostd`, `filed`, and `ddnsd`.

### Fixed

- Prevented accidental LAN/public daemon exposure by default.
- Prevented static web serving from following symlinks outside the web root.
- Prevented unbounded file uploads from consuming storage/memory unexpectedly.
- Prevented unauthenticated local admin calls to host/DDNS APIs when an admin token exists.

### Not verified here

- Android APK build.
- Android install/foreground-service/device lifecycle behavior.
- Diagnostics bundle creation on a real Android device.
- Cloudflare Tunnel connection with real credentials.
- Matrix homeserver runtime behavior.

## 0.1.1 - Flywheel turns 001-005

### Added

- Graceful HTTP daemon shutdown through context/signal-aware server helpers.
- MiniCloud symlink/path escape protections for list, download, upload, and delete flows.
- Explicit file download handler with directory listing disabled on `/files/`.
- Reverse proxy route normalization and target URL validation.
- Android `HealthMonitor` and `Degraded` service state.
- Local daemon verification script that starts all Go daemons and checks live `/health` endpoints.
- Makefile `verify-daemons` target.
- Flywheel evidence log at `docs/flywheel/TURNS_001_005.md`.

### Changed

- `pocket.Version` is now `0.1.1`.
- `ci-local.sh` now verifies local daemon health, not just Go compilation.
- Android supervisor status no longer treats process start as the only health signal.
- Rebuilt Android ARM64 daemon artifacts in `android/app/src/main/jniLibs/arm64-v8a/`.

### Fixed

- Prevented `filed` from serving files through symlink escape paths.
- Prevented delete operations from using symlink parents to affect files outside the root.
- Prevented uploads from overwriting symlink targets.
- Prevented malformed proxy routes from entering the routing table.

### Not verified here

- Android APK build.
- Android install/foreground-service/device lifecycle behavior.
- Cloudflare Tunnel connection with real credentials.
- Matrix homeserver runtime behavior.

## 0.1.0 - Final architecture baseline

- Kotlin Android app scaffold with Compose UI.
- Foreground supervisor and boot receiver.
- SQLite app storage scaffold.
- Go daemons for host status, web, files, proxy, and DDNS.
- Rust Matrix placeholder.
- Cloudflare Tunnel binary slot.
- Apache-2.0 license, NOTICE, AGENTS/SOUL/FLYWHEEL process docs, threat model, and runbooks.
