# Screenshots

This directory contains screenshots used to explain the current PocketHost MVP.
They are intended for project documentation and release evidence, not for
marketing claims that the app is production-ready.

The current set was captured on a headless `x86_64` Android emulator using a
debug APK built from this repository.

| File | What it shows |
|---|---|
| `01-dashboard.png` | Dashboard on a fresh install with services stopped |
| `02-dashboard-running.png` | Dashboard after `Start all`, with the default services running |
| `03-services.png` | Service manager with status chips, uptime, binary state, and controls |
| `04-settings.png` | Settings with autostart, loopback exposure state, and admin token |
| `05-exposed-toggle.png` | LAN exposure toggle enabled with warning and restart action |
| `06-dashboard-exposed-banner.png` | Dashboard warning while services are configured for `0.0.0.0` |
| `07-webpanel-gate.png` | Host web control panel token gate in mobile Chrome |
| `08-webpanel-unlocked.png` | Authenticated host web panel |
| `09-webpanel-grid.png` | Host web panel live service status grid |

When replacing screenshots:

- Use a debug or locally staged build and record how it was produced.
- Do not include real tokens, hostnames, Cloudflare account data, tunnel
  credentials, user files, or private Matrix/Nextcloud data.
- Keep screenshots aligned with the safety model: loopback by default and
  explicit warnings for LAN exposure.
