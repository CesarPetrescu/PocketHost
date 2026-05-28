# PocketHost product specification

## One-sentence product

PocketHost turns a spare Android phone/tablet into a small, supervised, tunnel-exposed personal server.

## Target users

- developers who want a pocket lab server
- home users who want a small low-power always-on endpoint
- small teams that want a test Matrix/web/file node
- students learning networking, Android systems, and service operations

## MVP user journey

1. Install APK on an ARM64 Android phone/tablet.
2. Grant notification permission.
3. Open dashboard.
4. Start default services.
5. See host/web/files/proxy marked running.
6. Open local `/health` endpoint.
7. Optionally import Cloudflare Tunnel config.
8. Enable public routes one by one.
9. View logs and storage usage.
10. Export diagnostics before reporting issues.

## Screens

### Dashboard

- device name and app version
- services running count
- failed/missing binary count
- storage free
- Start all / Stop all
- default service cards

### Services

- card per service
- state: stopped/starting/running/stopping/failed/missing binary
- port and binding
- start/stop/restart
- autostart flag later
- last message

### Network

- local service endpoints
- tunnel status
- route mapping
- warning for non-loopback bindings

### Storage

- app data directory
- config directory
- logs directory
- web root
- file root
- Matrix data root
- backup/export action later

### Logs

- recent log lines
- service filter later
- export diagnostics later

### Settings

- autostart
- admin token preview/rotation
- binary recheck
- backup/export later

## Acceptance criteria for MVP

- APK installs on ARM64 Android 10+ test device.
- Foreground notification appears when services start.
- Default Go daemons launch from `nativeLibraryDir`.
- `/health` responds for hostd, webd, filed, proxyd.
- Missing matrix/cloudflared binaries show `Missing binary`, not a crash.
- Logs appear in UI and persist in SQLite.
- Stop all terminates child processes.
- Services bind to loopback by default.
