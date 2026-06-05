# Matrix Validation Runbook

PocketHost packages Tuwunel `v1.7.0` as `libmatrixd.so` for ARM64 and x86_64.

## Local setup

1. Open the Matrix tab.
2. Set `serverName` to a local/test name, for example `localhost` or a domain you control.
3. Keep federation off unless public routing and `.well-known` delegation are configured.
4. Choose whether registration is enabled.
5. If registration is enabled, set and copy the registration token.
6. Save configuration.
7. Start Matrix.

The app writes `tuwunel.toml` into app-private config storage. The default bind address is `127.0.0.1:6167`.

## Health check

The UI health check targets:

```text
http://127.0.0.1:6167/_matrix/client/versions
```

Expected behavior:

- Health becomes OK after Tuwunel starts.
- Matrix logs appear in the app log stream.
- Registration is controlled by the saved registration settings.

## Public client-server API through Cloudflare

1. Configure Matrix locally first.
2. Open Tunnel tab.
3. Select Matrix as the tunnel target.
4. Start Quick Tunnel or a named tunnel.
5. Configure a Matrix client against the tunnel URL.

Federation remains off by default. Enable federation only when the server name, DNS, TLS route, and Matrix `.well-known` behavior are intentionally configured.
