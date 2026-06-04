# Roadmap

## 0.1 Scaffold

- Kotlin Compose shell
- foreground supervisor
- Go daemons
- native binary packaging
- local logs
- SQLite log persistence

## 0.2 Hardened MVP

- root license and notices
- AGENTS/SOUL/FLYWHEEL process docs
- Go unit tests
- threat model
- acceptance criteria
- local CI script

## 0.3 Android validation

- Gradle wrapper ✅
- multi-ABI APK splits (arm64-v8a, armeabi-v7a, x86, x86_64) + universal ✅
- debug-signed release build type ✅
- emulator smoke evidence (install, start daemon, /health, screenshots) ✅
- Android instrumented smoke tests (automated, in CI) — pending
- APK build in CI — pending
- local split APK staging script ✅
- real-device runbook ✅
- battery/thermal observation notes

## 0.4 Cloudflare module

- bundled Android cloudflared source build with version/hash record
- import tunnel config
- route editor
- tunnel health card
- public route verification runbook

## 0.5 MiniCloud

- SQLite metadata
- resumable upload later
- share links
- scoped tokens
- storage quotas

## 0.6 Matrix lab

- Conduit Android-compatible build notes
- first-run setup
- admin user flow
- backup/restore flow

## Later

- device-owner/enterprise mode
- fleet management
- update channel and signed daemon bundles

## Current release blockers

- Production signing and release artifact review are not implemented.
- Android instrumented smoke tests are not automated in CI.
- Device/emulator verification must still collect foreground notification, daemon start, logs, and `/health` evidence before an Android change is considered fully verified.
- Cloudflare public-route evidence and Matrix replacement/backup documentation remain gated future work.
