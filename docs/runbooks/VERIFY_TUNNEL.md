# Verify Cloudflare Tunnel route

Use this only after a user has imported a real tunnel config.

## Steps

1. Start the target local service.
2. Confirm local health:

```bash
curl http://127.0.0.1:8080/health
```

3. Start `cloudflared` from PocketHost.
4. Confirm tunnel logs show a connection.
5. Confirm the public hostname reaches the intended service:

```bash
curl -I https://web.example.com/
```

6. Confirm no service is bound to `0.0.0.0` unless explicitly configured.

## Evidence to save

- local health response
- tunnel log excerpt without credentials
- public hostname response headers
- route mapping screenshot

```text
web.example.com       -> http://127.0.0.1:8080
files.example.com     -> http://127.0.0.1:8090
matrix.example.com    -> http://127.0.0.1:6167
```
