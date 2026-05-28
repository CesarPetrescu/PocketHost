# Cloudflare Tunnel contract

PocketHost supervises the official `cloudflared` client. It does not reimplement the tunnel protocol and does not bundle credentials.

## Bundled source build

PocketHost currently packages Android builds of upstream `cloudflared`:

```text
Version: 2026.5.2
Source: https://github.com/cloudflare/cloudflared
Tag: 2026.5.2
Commit: 0e84636de9450d9e73c1e28932ed2bd62cb33e10
License: Apache-2.0
Build script: scripts/build-cloudflared-android.sh
```

The release page records `2026.5.2` as the latest upstream release on 2026-05-27. Upstream does not publish Android APK-ready `jniLibs`; these artifacts are cross-compiled from the official source checkout and recorded in `NOTICE`.

## Android service slot

Binary:

```text
android/app/src/main/jniLibs/arm64-v8a/libcloudflared.so
android/app/src/main/jniLibs/x86/libcloudflared.so
android/app/src/main/jniLibs/x86_64/libcloudflared.so
```

Launch contract:

```bash
libcloudflared.so tunnel --config "$APP_FILES/config/cloudflared.yml" run
```

Required config location:

```text
/data/data/dev.pockethost/files/config/cloudflared.yml
```

The Android supervisor preflights that config path before launch. Missing config is a configuration failure, while missing `libcloudflared.so` is a missing binary failure.

Build command used for the bundled artifacts:

```bash
./scripts/build-cloudflared-android.sh /tmp/cloudflared-2026.5.2 all
```

The APK does not include tunnel credentials, tokens, certs, hostnames, or Cloudflare account identifiers.

## Operator route examples

```text
web.example.com       -> http://127.0.0.1:8080
files.example.com     -> http://127.0.0.1:8090
matrix.example.com    -> http://127.0.0.1:6167
```

Do not change default tunnel routing without a human gate. Do not commit credentials, tunnel tokens, generated certs, or dashboard screenshots that expose secrets.
