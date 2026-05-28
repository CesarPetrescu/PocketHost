# Data flow

## Control operations

```text
Compose UI
  -> ServerCommands
      -> ServerForegroundService
          -> ProcessSupervisor
              -> ProcessBuilder
                  -> native daemon
```

Logs flow back:

```text
native daemon stdout/stderr
  -> ProcessSupervisor.streamLogs
      -> LogBus
          -> SecretRedactor
              -> SQLite logs table with retention prune
              -> Compose Logs screen

local daemon /health
  -> HealthMonitor
      -> ProcessSupervisor status map
          -> Compose Dashboard/Services degraded-state display
```

## File API flow

```text
Client
  -> Cloudflare Tunnel or localhost
      -> filed
          -> token check
              -> upload byte cap / method check
                  -> SafeJoin(root, requested_path)
                      -> SafeExistingPath for read/delete/download
                          -> symlink-component rejection
                              -> app-private public/files directory
```

## Tunnel flow

```text
Internet client
  -> Cloudflare edge
      -> cloudflared outbound tunnel
          -> localhost service
```

No inbound router/firewall opening is required for the tunnel path.

## Matrix flow

```text
Matrix client
  -> tunnel route or localhost
      -> matrixd
          -> app-private data/matrix
```

The selected Matrix homeserver owns its database format. PocketHost must not migrate or rewrite it without a tested migration tool.

## Web static flow

```text
Client
  -> tunnel/proxy/localhost
      -> webd
          -> explicit static handler
              -> SafeExistingPath
                  -> symlink-component rejection
                      -> serve file or reject directory listing
```

## DDNS flow

```text
Android supervisor / local caller
  -> ddnsd /api/update-now with admin token
      -> public IP endpoint
          -> public-routable IP validation
              -> A/AAAA record compatibility check
                  -> Cloudflare PATCH only if changed
```

## Diagnostics flow

```text
Settings screen
  -> Diagnostics.createBundle
      -> manifest + logs + SQLite database
          -> app-private diagnostics zip
```
