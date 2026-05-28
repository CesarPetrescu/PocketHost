# PocketHost Android solution

## Final product shape

PocketHost is an Android server supervisor. It does not try to make Android behave like a full Linux distribution. It gives Android-native lifecycle control, diagnostics, and safety around small native daemons.

```text
Android app, Kotlin/Compose
  ├─ foreground supervisor service
  ├─ persistent notification
  ├─ boot receiver
  ├─ SQLite app database
  ├─ service cards, logs, storage, network screens
  └─ daemon launcher

Native daemon layer
  ├─ hostd      Go status API
  ├─ webd       Go static web server
  ├─ filed      Go MiniCloud file API
  ├─ proxyd     Go local reverse proxy
  ├─ ddnsd      Go optional DNS updater
  ├─ matrixd    Rust/Go Matrix homeserver slot
  └─ cloudflared optional official tunnel binary
```

## Why this is the correct Android shape

Android is an application OS with strict background execution rules. A server app must therefore be explicit and user-visible: it runs as a foreground service, exposes controls through the UI, and uses a persistent notification as the user's always-visible stop surface.

Native daemons are packaged under `jniLibs/<abi>/` as `lib<name>.so` files and launched from `applicationInfo.nativeLibraryDir`. They are executable artifacts, not JNI libraries. The suffix exists because Android packaging reliably extracts native-library files.

## Default service policy

| Service | Default | Binding | Reason |
|---|---:|---|---|
| hostd | on | 127.0.0.1:8099 | local health/status |
| webd | on | 127.0.0.1:8080 | simple web hosting |
| filed | on | 127.0.0.1:8090 | MiniCloud file API |
| proxyd | on | 127.0.0.1:8088 | local service routing |
| ddnsd | off | 127.0.0.1:8091 | usually unnecessary with tunnels |
| matrixd | off | 127.0.0.1:6167 | heavier, selected by operator |
| nextcloudd | off | 127.0.0.1:8081 | isolated Linux-userland launcher only |
| cloudflared | off | outbound | requires user Cloudflare setup |

## Matrix decision

Use a Rust Matrix homeserver first, with Go as fallback:

1. Tuwunel as the preferred production-shaped candidate when an Android-compatible binary exists.
2. Conduit for lighter/beta experiments.
3. Dendrite only after human/legal review, because current upstream status and license markers make it a non-default option.
4. Synapse is out of scope for native Android; it belongs in a Linux-userland module.

The Android app must treat Matrix as a replaceable binary contract:

```bash
libmatrixd.so --addr 127.0.0.1:6167 --data-dir <app-files>/data/matrix
```

Minimum probe:

```text
GET http://127.0.0.1:6167/_matrix/client/versions
```

## Cloudflare decision

Use official `cloudflared`. PocketHost supervises it and renders routes; it does not reimplement tunnel networking.

Recommended route model:

```text
web.example.com    -> http://127.0.0.1:8080
files.example.com  -> http://127.0.0.1:8090
matrix.example.com -> http://127.0.0.1:6167
nextcloud.example.com -> http://127.0.0.1:8081
```

Do not commit tunnel credentials. Store config in app-private storage and import it from the UI or debug tooling.

## Nextcloud decision

Do not implement Nextcloud natively. Nextcloud requires a Linux-style PHP/web/database/background-job stack. For the core app, build MiniCloud instead:

- upload/download/list/delete
- SQLite metadata later
- token auth
- share links later
- optional tunnel exposure

A future `nextcloudd` module can supervise a Termux/proot/VM-style environment, but it must be isolated from the core app and store its runtime under `files/data/nextcloud`.

## Distribution modes

### Developer/sideload mode

Best for the MVP. Full control, fewer store-policy constraints, explicit user consent.

### Google Play mode

Possible only if the foreground-service use case is clearly declared, the notification is persistent, risky features are opt-in, and the reviewer can understand why the app is a user-visible server supervisor.

### Enterprise/device-owner mode

Best long-term mode for fleets or repurposed tablets. Allows clearer power/network policy and device management.

## Health model

The Android supervisor must not treat `Process.start()` as proof that a service is usable. For every service with a local port, the supervisor polls `http://127.0.0.1:<port>/health`. After repeated probe failures, the service enters `Degraded` instead of `Running`.

## Security and diagnostics model

Default daemons bind to loopback. The Go daemon layer refuses public bind addresses unless `POCKETHOST_ALLOW_PUBLIC_BIND=true` is explicitly set. Admin APIs use the Android-generated `POCKETHOST_TOKEN`; `/health` stays unauthenticated so the local health monitor can function.

Android logs are redacted before storage/display. The SQLite log table is bounded to the newest 5,000 rows. Settings includes a diagnostics bundle action that writes a zip containing a manifest, logs, and the app database into app-private storage.

This is still local evidence. A release also needs real Android evidence: foreground notification visible, Start/Stop actions working, logs visible, boot behavior tested, diagnostics bundle creation verified, and battery/thermal behavior observed on target hardware.
