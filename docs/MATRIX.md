# Matrix integration plan

## Recommendation

Use Conduit as the selected first Matrix candidate. Conduit is a lightweight Matrix homeserver written in Rust, and the current upstream repository records Apache-2.0 licensing. Treat other Conduit-family forks as separate database owners unless upstream explicitly documents a safe migration. Treat Dendrite as a gated Go fallback only, because its current upstream status and license need explicit review before bundling or redistribution.

## Selected candidate

```text
Name: Conduit
Source: https://gitlab.com/famedly/conduit
License: Apache-2.0
Bundling status: not bundled yet
Android status: compatibility not verified yet
```

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
GET /_matrix/client/versions
```

`/health` is useful for custom adapters, but the Android supervisor probes `/_matrix/client/versions` so the selected homeserver proves Matrix-client compatibility rather than only process liveness.

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
- Do not enable one-click upgrades until backup/restore has been tested against a copy of the selected homeserver data directory.
