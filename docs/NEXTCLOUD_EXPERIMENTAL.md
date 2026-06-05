# Experimental Nextcloud module

PocketHost treats Nextcloud as an isolated experimental module, not a core service. Nextcloud requires a PHP runtime, PHP extensions, web runtime behavior, background jobs, storage, and upgrade/backup discipline that do not fit the core Go-daemon model.

## Selected target

```text
Nextcloud Server: v32.0.11
Runtime: PHP 8.4 Android build supplied by operator/build pipeline
Database: SQLite only
PocketHost service: nextcloudd on 127.0.0.1:8092
PHP backend port: 127.0.0.1:8093
Status: experimental, minimal/testing only
```

## Current implementation

- Android exposes a Nextcloud screen with explicit experimental warnings.
- `nextcloud` is registered as an off-by-default service and is never included in default Start All.
- `nextcloudd` preflights `libphp.so`, the Nextcloud server directory, and `index.php`.
- `nextcloudd` exposes `/health` and proxies traffic to PHP's local server.
- The Tunnel screen can publish Nextcloud through Quick Tunnel or named Cloudflare routes.
- `scripts/stage-nextcloud-experimental.sh` stages an already-built PHP runtime and a Nextcloud server payload.

## Required runtime files

```text
android/app/src/main/jniLibs/arm64-v8a/libnextcloudd.so
android/app/src/main/jniLibs/arm64-v8a/libphp.so
android/app/src/main/jniLibs/x86_64/libnextcloudd.so
android/app/src/main/jniLibs/x86_64/libphp.so
/data/data/dev.pockethost/files/data/nextcloud/server/index.php
/data/data/dev.pockethost/files/data/nextcloud/data/
```

## Limitations

- SQLite mode is for minimal/testing use, not a production Nextcloud deployment.
- PHP cross-compilation and extension selection must be verified before any release claim.
- Background jobs, app store installation, upgrades, and backups are not automated yet.
- The module must not be exposed publicly until login, upload/download, restart persistence, and backup/restore are verified.

## Rollback

Stop the `nextcloud` service, remove `libphp.so`, remove `libnextcloudd.so` if desired, and delete or archive app-private `data/nextcloud` after backing up user files.

## Local integration status, 2026-06-04

Implemented in the Android app:

- Experimental Nextcloud tab with explicit testing/minimal warning.
- App-private first-run installer for `nextcloud-server-32.0.11.zip`.
- D:-backed Gradle asset source: `D:\PocketHostDeps\nextcloud-v32.0.11\assets`.
- Go supervisor wrapper `nextcloudd` packaged as `libnextcloudd.so` for `arm64-v8a` and `x86_64`.
- Service registry entry for `nextcloud` on `127.0.0.1:8092`, off by default and excluded from Start All.
- Cloudflare tunnel route option for `http://127.0.0.1:8092`.

Still blocked:

- `libphp.so` is not packaged until a real Android PHP 8.4 runtime exists for ARM64 and x86_64 with the required SQLite/minimal Nextcloud extensions.
- The app intentionally reports a Nextcloud preflight failure while `libphp.so` is missing.

## PHP runtime build attempt, 2026-06-04

Chosen route: Termux-derived PHP runtime rebuilt from source.

Local source staging completed:

- Termux packages source: `D:\PocketHostDeps\termux-packages`
- PocketHost PHP asset staging path: `D:\PocketHostDeps\php-android\assets`
- Stage script: `scripts\stage-php-runtime.ps1`
- Bootstrap script: `scripts\build-php-termux-runtime.ps1`

Current host blocker:

- Docker is not installed.
- WSL launcher exists, but no usable Linux distro is available.
- Termux PHP builds require a Linux builder environment; native PowerShell cannot run the Termux package build pipeline.

Next action to unblock:

1. Install Docker Desktop or a WSL Linux distro.
2. Rerun `scripts\build-php-termux-runtime.ps1 -Abi arm64-v8a` and `scripts\build-php-termux-runtime.ps1 -Abi x86_64`.
3. Stage each real PHP executable/runtime tree with `scripts\stage-php-runtime.ps1`.
4. Rebuild APKs and run the in-app PHP module self-check.
