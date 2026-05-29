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

## Network exposure toggle

By default every daemon binds `127.0.0.1` (architecture rules 1 & 2). The Android
app exposes a single, explicit, off-by-default operator toggle in
**Settings → Expose services on the local network**:

```text
ServicePreferences.exposeOnLan = false  -> bind host 127.0.0.1   (loopback only)
ServicePreferences.exposeOnLan = true   -> bind host 0.0.0.0     (LAN/WAN reachable)
```

`ServiceRegistry` threads the chosen bind host into each daemon's `--addr` and,
when LAN exposure is on, sets `POCKETHOST_ALLOW_PUBLIC_BIND=true` so
`pocket.ValidateListenAddr` permits the non-loopback bind. The guard is never
weakened: a non-loopback bind without that explicit override is still refused.
Changing the toggle requires restarting daemons; the app offers a
**Restart running services to apply** action (`ACTION_RESTART_ALL`). The web
panel and the in-app dashboard both surface a banner whenever any daemon is
bound to `0.0.0.0`. Public internet routing should still prefer Cloudflare
Tunnel over a raw open port.

## Host web control panel

`hostd` serves a loopback web control panel in addition to its status API:

```text
GET  /                     embedded single-page panel (public; asks for token)
GET  /health               public
GET  /api/status           token-gated runtime info
GET  /api/services         token-gated; concurrent /health aggregation of the fleet
POST /api/ddns/update-now  token-gated; proxied to ddnsd
GET  /api/files            token-gated; proxied directory listing from filed
GET  /api/files/download   token-gated; proxied file download from filed
POST /api/files/upload     token-gated; proxied multipart upload to filed
POST /api/files/delete     token-gated; proxied delete to filed
```

The panel is a static SPA (no build step). The browser holds the admin token in
`sessionStorage` and sends it as `X-PocketHost-Token`; `hostd` validates it and,
for control actions, re-authenticates outbound to sibling daemons with the same
token held server-side, so the token reaches only loopback. Process start/stop
is deliberately **not** exposed here — only the Android foreground supervisor may
launch or kill daemons (rule 3). The panel follows the same bind host as every
other daemon, so enabling LAN exposure makes it reachable from the network too;
its `/api/*` routes stay token-gated.

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
