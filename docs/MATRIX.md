# Matrix integration plan

## Recommendation

Use Tuwunel first when an Android-compatible build is available. Use Conduit for lighter/beta experiments. Treat Dendrite as a gated Go fallback only, because its current upstream status and license need explicit review before bundling or redistribution.

## Contract expected by Android app

Binary:

```text
android/app/src/main/jniLibs/arm64-v8a/libmatrixd.so
```

Launch contract:

```bash
libmatrixd.so --addr 127.0.0.1:6167 --data-dir "$APP_FILES/data/matrix"
```

Health contract:

```text
GET /health
```

or:

```text
GET /_matrix/client/versions
```

## Configuration fields the UI should eventually manage

- server name
- listen address
- data directory
- registration enabled
- registration token
- federation enabled
- media retention limit
- log level
- backup/export
- database implementation/version

## Hard rules

- Do not switch between Conduit-family forks against the same database unless the upstream project explicitly supports that migration. Treat Matrix DB ownership as part of the selected binary.
- Do not bundle Dendrite or any Matrix binary until `NOTICE` records version, source, license, build target, SHA256, and modifications.
- Do not expose federation publicly until local-only client login and backup/restore have been verified.
