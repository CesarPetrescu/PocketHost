# Cloudflare Tunnel Validation Runbook

## Quick Tunnel

1. Start the Web service.
2. Open the Tunnel tab.
3. Select `Quick Tunnel`.
4. Select target service, for example Web or Nextcloud.
5. Start Cloudflare Tunnel.
6. Wait for the first `trycloudflare.com` URL to appear.
7. Use `Copy` beside the public URL and open it externally.

Expected behavior:

- The tunnel URL is extracted from cloudflared logs and shown on-screen.
- The active target is visible.
- Only the selected target is proxied.
- No Cloudflare account is required for Quick Tunnel.

## Named Tunnel

1. Export Cloudflare tunnel credentials JSON from Cloudflare.
2. Import it with the Android file picker.
3. Confirm the UI shows the app-private credentials path.
4. Fill tunnel UUID/name and hostname.
5. Start Cloudflare Tunnel.

Credential JSON must contain `AccountTag`, `TunnelID`, and `TunnelSecret`. Contents are not printed in logs.
