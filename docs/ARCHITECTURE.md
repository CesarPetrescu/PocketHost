# PocketHost Architecture

## Control plane vs data plane

PocketHost splits responsibilities:

```text
Kotlin Android app = control plane
Go/Rust/native daemons = data plane
SQLite = local config and service metadata
Cloudflare Tunnel = public ingress
```

The Kotlin app owns Android-specific concerns:

- UI
- foreground notification
- boot startup
- Android permissions
- process supervision
- service state
- logs
- local configuration

The daemons own server workloads:

- `webd`: static/local web server
- `filed`: MiniCloud-style file API
- `ddnsd`: optional DNS updater
- `proxyd`: local reverse proxy
- `hostd`: host status API
- `matrixd`: real Matrix server binary slot
- `cloudflared`: real tunnel binary slot

## Runtime flow

```text
MainActivity
  └─ starts ServerForegroundService
       └─ ProcessSupervisor
            ├─ locates /data/app/.../lib/arm64/libwebd.so
            ├─ starts process with service-specific args
            ├─ streams stdout/stderr into LogBus
            ├─ writes logs to files/logs/*.log
            └─ updates Compose state via StateFlow
```

## Why daemons are packaged as native libraries

Android apps targeting modern Android should not rely on executing binaries copied into writable app data. This project packages each daemon as a native library artifact, names it `lib<daemon>.so`, requests extraction with `android:extractNativeLibs="true"`, then launches from `applicationInfo.nativeLibraryDir`.

## Service ports

Default local-only bindings:

| Service | Port | Binding |
|---|---:|---|
| webd | 8080 | 127.0.0.1 |
| filed | 8090 | 127.0.0.1 |
| ddnsd | 8091 | 127.0.0.1 |
| proxyd | 8088 | 127.0.0.1 |
| hostd | 8099 | 127.0.0.1 |
| matrixd | 6167 | 127.0.0.1 |

Use Cloudflare Tunnel or another private tunnel for public routing.

## Matrix integration

The app already has a Matrix service slot. Replace the placeholder with one of:

- Rust Conduit/Tuwunel-style homeserver build, renamed to `libmatrixd.so`.
- Go Dendrite-style homeserver build, renamed to `libmatrixd.so`.

Expected contract:

```bash
libmatrixd.so --addr 127.0.0.1:6167 --data-dir /data/data/dev.pockethost/files/data/matrix
```

Recommended endpoints for app integration:

```text
GET /health
GET /_matrix/client/versions
```

## Cloudflare integration

The app expects:

```bash
libcloudflared.so tunnel --config <config> run
```

Store tunnel config under:

```text
/data/data/dev.pockethost/files/config/cloudflared.yml
```

Do not hard-code Cloudflare credentials into the APK.
