# Nextcloud Experimental Validation Runbook

Nextcloud support is intentionally experimental/minimal and off by default.

## Current packaged state

- Nextcloud Server payload: `v32.0.11`
- Payload location for Gradle packaging: `D:\PocketHostDeps\nextcloud-v32.0.11\assets\nextcloud-server-32.0.11.zip`
- App install destination: app-private `data/nextcloud/server`
- Data directory: app-private `data/nextcloud/data`
- Bind address: `127.0.0.1:8092`
- Database mode: SQLite only

## PHP runtime requirement

Nextcloud cannot start until a real Android PHP runtime is staged as `libphp.so` for the target ABI.

Minimum extensions expected for the experimental SQLite runtime:

- sqlite3 / pdo_sqlite
- mbstring
- intl
- xml/xmlreader/xmlwriter
- zip
- curl
- gd
- fileinfo
- openssl
- sodium

Until those binaries exist, the Nextcloud tab should show a preflight failure instead of claiming the service is ready.

## First-run install

1. Open the Nextcloud tab.
2. Confirm the experimental warning is visible.
3. Press install packaged payload.
4. Confirm the app extracts the server into app-private storage.
5. Start Nextcloud only after PHP runtime preflight passes.

## Local health

The supervisor wrapper exposes:

```text
http://127.0.0.1:8092/health
```

Expected response after PHP and server payload are valid:

```json
{"ok":true,"service":"nextcloudd"}
```

## Public route

1. Keep Nextcloud local and working first.
2. Open Tunnel tab.
3. Select Nextcloud as target.
4. Start Quick Tunnel or named tunnel.

This is for short-lived testing only. Do not treat SQLite mode as a production Nextcloud deployment.
