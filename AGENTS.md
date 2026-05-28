# AGENTS.md

What to do in this repository.

Follow `./FLYWHEEL.md` for every change. Do not call a stage done without the evidence it requires. Stop at any gate marked `(human)`.

## Product goal

PocketHost turns an Android phone or tablet into a user-visible, supervised mini-server host.

The app is the control plane:

- Kotlin Android app
- Jetpack Compose UI
- foreground supervisor service
- boot receiver
- SQLite config/log database
- process lifecycle and diagnostics

The daemons are the data plane:

- Go for custom server daemons: web, files, DDNS, local proxy, host status
- Rust or Go for Matrix, preferably a Rust implementation such as Tuwunel/Conduit when Android-compatible
- official `cloudflared` for Cloudflare Tunnel when included by the operator
- no native Nextcloud rewrite; Nextcloud belongs in a later Linux-userland/VM module

## Non-negotiable architecture rules

1. Services bind to `127.0.0.1` by default.
2. Public exposure goes through a tunnel or explicit user configuration, never by accidental LAN/WAN binding.
3. Android starts/stops daemons only through the foreground supervisor.
4. Native daemon artifacts are packaged as `jniLibs/<abi>/lib<name>.so` and launched from `applicationInfo.nativeLibraryDir`.
5. Do not place secrets in the APK, sample configs, logs, screenshots, or committed test fixtures.
6. SQLite is the default storage layer. Use one DB per boundary: app DB, file metadata DB, Matrix DB, etc.
7. Matrix integration must be replaceable. The Android app supervises a binary and probes health; it must not hard-code one homeserver fork deeply into the UI.
8. Cloudflare integration supervises `cloudflared`; it does not reimplement the tunnel protocol.
9. Nextcloud remains optional and isolated because it requires a Linux-style PHP/web/database/runtime stack.

## Coding rules

### Kotlin / Android

- Keep Android policy logic in `supervisor/`.
- Keep UI side effects minimal; call `ServerCommands`, not `ProcessSupervisor`, from UI where possible.
- Add user-visible failure states. Missing binary, permission failure, port conflict, and process crash are different states.
- Do not introduce background work that bypasses the persistent notification.
- Prefer small screens and observable state over hidden global behavior.

### Go daemons

- Use only loopback binding unless explicitly configured otherwise.
- Every daemon must expose `/health`.
- Every daemon must support deterministic startup args and environment variables.
- Validate paths with `pocket.SafeJoin` or equivalent.
- Bound request sizes and timeouts.
- Add unit tests for parsing, path safety, auth, and health behavior.

### Rust / Matrix

- Treat Matrix as an adapter contract first: binary, config, data dir, health probe.
- Do not assume that different Conduit-family forks can share a database safely.
- Treat Dendrite as a gated fallback; do not bundle it without explicit license and maintenance review.
- Include migration and backup instructions before enabling one-click upgrades.

## Human gates

Stop for human sign-off before:

- changing license terms
- bundling `cloudflared` or a Matrix homeserver binary
- enabling public network binding
- changing default tunnel routing
- deleting or migrating user data
- adding telemetry
- preparing public release artifacts
- making Google Play distribution claims

## Evidence required before marking work done

Minimum local evidence:

```bash
go test ./...
```

For Android changes:

- build result from Android Studio or CI
- installed APK on a real or emulated Android device
- screenshot or logs showing the foreground supervisor notification
- at least one daemon started from the app
- successful `/health` request to the daemon

For tunnel changes:

- local service health before tunnel
- `cloudflared` process logs
- public route response or Cloudflare dashboard evidence
- proof no raw inbound port was required

For Matrix changes:

- selected homeserver version and license recorded
- fresh data directory smoke test
- `/health` or `/_matrix/client/versions` response
- backup/restore path documented before upgrade support
