# FLYWHEEL.md

How PocketHost ships and improves, turn by turn.

`AGENTS.md` says what to build. `SOUL.md` says who to be. `FLYWHEEL.md` says how a change moves from idea to verified improvement.

## The loop

Ship, verify, learn, improve. Each turn must leave the repo easier to change and safer to operate.

## Stage 1: Ship

Plan the approach, name the blast radius, build in reversible steps, review data flow and tests, then land the change.

Done when:

- the code/docs/config are changed in the repo
- the data flow is understood
- the rollback path is known
- tests appropriate to the touched layer pass locally

Gate `(human)` before:

- changing license terms
- deleting/migrating user data
- bundling third-party binaries
- changing public exposure defaults
- adding telemetry
- releasing an APK

## Stage 2: Verify

Prove it works where it matters. A passing test is useful; it is not enough for a server appliance.

Done when there is evidence for the touched layer:

- Go daemon: `go test ./...` plus a `/health` response from the daemon
- Android supervisor: APK installed, foreground notification visible, daemon launched from app UI, logs captured
- Matrix slot: selected homeserver version recorded, process starts with a clean data dir, Matrix client/server endpoint responds
- Cloudflare Tunnel: local health succeeds, tunnel connects, public hostname reaches only the intended local service
- Storage/data change: backup/migration path tested on a copy

## Stage 3: Learn

Capture cost, regressions, user friction, and surprises.

Done when:

- new constraints are written into `docs/`, `AGENTS.md`, tests, or code comments
- flaky steps are named
- missing observability is recorded
- the next turn knows more than this one did

Gate `(often)` when real-world device signal is required:

- battery drain
- thermal throttling
- OEM background killing
- long-running Matrix federation behavior
- Cloudflare Tunnel stability
- storage growth

## Stage 4: Improve

Fix causes, not symptoms. Raise the floor.

Done when:

- a bug gets a regression test or an operational check
- manual toil becomes a script, runbook, or UI affordance
- dangerous defaults get safer
- obsolete process is removed

## The bar

- Done means deployed and verified with evidence.
- Every iteration costs battery, time, bandwidth, and user trust.
- Know the data flow before exposing a port.
- Fix the cause, never the symptom.
- Leave a trail in the codebase.
- Audit this loop; delete steps that stop earning their place.

## Evidence log template

Use this in PRs, release notes, or `docs/runbooks/` notes:

```text
Change:
Blast radius:
Data flow:
Tests run:
Device tested:
Daemon health evidence:
Tunnel evidence:
Logs/screenshots:
Known gaps:
Rollback:
Next improvement:
```
