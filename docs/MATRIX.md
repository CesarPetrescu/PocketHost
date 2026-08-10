# Matrix integration plan

## Recommendation

Use Tuwunel as the selected first Matrix candidate. Tuwunel is the successor path for the Conduit/conduwuit family and keeps the Rust-first Matrix slot aligned with PocketHost's architecture. Treat every Conduit-family database as owned by the exact selected binary unless upstream explicitly documents a safe migration. Treat Dendrite as a gated Go fallback only, because its current upstream status and license need explicit review before bundling or redistribution.

## Selected candidate

```text
Name: Tuwunel
Version: v1.7.0
Source: https://github.com/matrix-construct/tuwunel
Docs: https://matrix-construct.github.io/tuwunel/
License: Apache-2.0 family; verify upstream release license before bundling
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
libmatrixd.so --config "$APP_FILES/config/tuwunel.toml"
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

## Current Android app behavior

- The Matrix screen writes `config/tuwunel.toml` under app-private storage.
- The supervisor launches `libmatrixd.so --config <tuwunel.toml>`.
- The health probe remains `GET /_matrix/client/versions`.
- Missing `libmatrixd.so` is surfaced as `Missing binary`; missing server-name configuration is a preflight failure.
- Build staging is handled by `scripts/build-tuwunel-android.sh` for `arm64-v8a` and `x86_64`.

## Hard rules

- Do not switch between Conduit-family forks against the same database unless the upstream project explicitly supports that migration. Treat Matrix DB ownership as part of the selected binary.
- Do not bundle Dendrite or any Matrix binary until `NOTICE` records version, source, license, build target, SHA256, and modifications.
- Do not expose federation publicly until local-only client login and backup/restore have been verified.
- Do not enable one-click upgrades until backup/restore has been tested against a copy of the selected homeserver data directory.
