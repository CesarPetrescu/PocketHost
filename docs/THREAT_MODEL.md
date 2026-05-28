# Threat model

## Assets

- files stored in MiniCloud
- Matrix database and media
- tunnel credentials
- admin token
- service logs
- Android app-private storage
- device battery/thermals/network budget

## Trust boundaries

```text
User UI
  -> Kotlin supervisor
      -> native daemon process
          -> localhost HTTP endpoint
              -> Cloudflare Tunnel / LAN / client
```

Primary boundary: only the Kotlin supervisor should know which services are enabled and how they are launched. Daemons should not be able to silently expose themselves publicly.

## Default security controls

- loopback-only binding
- no hard-coded Cloudflare credentials
- token auth for file, host admin, and DDNS admin APIs when token is set
- constant-time token comparison helper
- app-private storage
- persistent notification while services run
- logs visible to user, redacted before SQLite/UI storage
- bounded SQLite log retention
- no raw SQL network port in MVP

## Main risks

| Risk | Mitigation |
|---|---|
| Accidental public exposure | loopback default, daemon bind guard, explicit public-bind override, tunnel routes explicit |
| Secret leakage in logs | `SecretRedactor`, token/bearer/assignment redaction, avoid printing env secrets |
| Path traversal/symlink escape in web/file server | `SafeJoin`, `SafeExistingPath`, explicit handlers, disabled directory listing, and regression tests |
| Long-running battery drain | foreground service, charger warnings later |
| OEM background killing | foreground service plus user-visible diagnostics |
| Matrix DB corruption | no fork swapping without documented migration |
| Tunnel credential compromise | app-private config, no sample secrets |
| Supply-chain risk from bundled binaries | record version/hash/license in `NOTICE` before release |
| Health signal spoofing/misrouting | bind to loopback by default; Android probes local `/health`; tunnel routes are explicit |

## Release blockers

- no admin token in committed files
- no non-loopback binding by default and no public bind override in release config
- no unbounded upload defaults
- no Matrix binary bundled without license and migration notes
- no `cloudflared` binary bundled without upstream version/hash/license notes
- no destructive data operation without backup path
