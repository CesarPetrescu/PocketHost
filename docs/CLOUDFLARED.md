# Cloudflare Tunnel contract

PocketHost supervises the official `cloudflared` client. It does not reimplement the tunnel protocol and does not bundle credentials.

## Android service slot

Binary:

```text
android/app/src/main/jniLibs/arm64-v8a/libcloudflared.so
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

## Operator route examples

```text
web.example.com       -> http://127.0.0.1:8080
files.example.com     -> http://127.0.0.1:8090
matrix.example.com    -> http://127.0.0.1:6167
nextcloud.example.com -> http://127.0.0.1:8081
```

Do not change default tunnel routing without a human gate. Do not commit credentials, tunnel tokens, generated certs, or dashboard screenshots that expose secrets.
