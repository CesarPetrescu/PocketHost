# Continuous integration

Five workflows, split by whether a red result should block a merge.

| Workflow | Trigger | Blocking? | Purpose |
|---|---|---|---|
| `ci.yml` | push to `main`, every PR | yes | Fast gates. Green on `main` today. |
| `android.yml` | push to `main`, every PR | yes | Builds the Kotlin app and its APKs. |
| `codeql.yml` | push, PR, weekly | yes | Static analysis, Go + Kotlin + Python + Rust + Actions. |
| `audit.yml` | nightly, manual | **no** | Checks that are red today by design. |
| `release.yml` | `v*` tag | n/a | Signed release build, SBOM, provenance, draft release. |

`ci.yml` triggers on `push` **only for `main`**. Without that filter every PR
commit runs the whole workflow twice — once for `push`, once for
`pull_request` — which is what the previous configuration did.

## What each surface is covered by

### Go daemons (`go/`, 2,947 lines)

| Surface | Where |
|---|---|
| `go build ./...` | `ci.yml` → `go` |
| `go vet ./...` (the full analyser set, not the `go test` subset) | `ci.yml` → `go` |
| `gofmt -s` | `ci.yml` → `go` |
| `go mod tidy` drift | `ci.yml` → `go` |
| Tests under `-race`, `-count=1` | `ci.yml` → `go` |
| Coverage floor (45%; actual 49.6%) | `ci.yml` → `go` |
| Reachable stdlib vulnerabilities | `ci.yml` → `go-vulncheck` |
| New lint findings only | `ci.yml` → `go-lint` |
| All lint findings | `audit.yml` → `go-lint-full` |
| gosec, into the Security tab | `audit.yml` → `go-gosec` |
| CodeQL `security-and-quality` | `codeql.yml` |
| `android/arm64` cross-compile | `ci.yml` → `go-cross-android` |
| `armeabi-v7a` / `x86` / `x86_64` cross-compile (needs the NDK) | `audit.yml` → `go-cross-cgo` |
| Live daemon behaviour, 15 assertions | `ci.yml` → `integration` |

CI builds with **Go 1.26.x**, not the `1.23.x` it used to pin. Go supports only
the two most recent minor releases; 1.23 is past end of life, and building with
it puts 28 reachable stdlib vulnerabilities into the output. `go.mod` still
declares `go 1.23` as the *language* floor — that is unrelated and stays.

### Android app (`android/`, 2,956 lines of Kotlin)

Nothing in CI had ever compiled this module. `android.yml` runs
`assembleDebug`, `lintDebug`, `testDebugUnitTest`, `assembleRelease`, and
uploads the debug APKs.

`app/build.gradle.kts` sets `lint { abortOnError = false }`, so the lint task
never fails a build. The workflow parses `lint-results-debug.xml` itself and
fails on any `severity="Error"`, which keeps local builds forgiving while CI
still gates.

There are **no test sources at all** — no `src/test`, no `src/androidTest` — so
`testDebugUnitTest` is `NO-SOURCE` and passes vacuously. The workflow emits a
warning saying exactly that rather than letting a green check imply otherwise.

No SDK install step is needed: `ubuntu-24.04` preinstalls SDK Platform
`android-36` and Build Tools `36.0.0`. Two constraints:

- Never move this job to an `*-arm` runner label. Those images ship no Android SDK.
- Keep the JDK pinned. Gradle 9.5.1 accepts Java 17–25; `ubuntu-26.04` already
  defaults to JDK 25.

`android/gradle/wrapper/gradle-wrapper.properties` previously had `retries=0`
and `networkTimeout=10000` — one 10-second attempt at a 140 MB download, which
is the single highest-probability flake in any Gradle CI. Now 3 retries and 60s.

### Rust (`rust/matrixd/`)

`cargo fmt --check`, `cargo clippy -D warnings`, `cargo build --locked`,
`cargo test --locked` in `ci.yml` → `rust`. `Cargo.lock` is committed so
`--locked` is meaningful.

Note that this crate is **not** what ships as `libmatrixd.so`; see below.

### Shell, PowerShell, Python

| Surface | Where |
|---|---|
| `shellcheck -S warning` over every `.sh` | `ci.yml` → `shell` |
| `shellcheck -S style` (all severities) | `audit.yml` → `shell-strict` |
| `bash -n` | `ci.yml` → `shell` |
| `ruff check` | `ci.yml` → `python` |
| `ruff format --check` | `audit.yml` → `python-format` |
| PSScriptAnalyzer over `scripts/*.ps1` | `audit.yml` → `powershell` |

shellcheck is preinstalled on the ubuntu images; the popular wrapper action has
not been updated since 2023.

### The workflows themselves

`actionlint` (syntax, expression and shell checking) gates in `ci.yml`.
`zizmor` (template injection, over-broad permissions, cache poisoning,
credential persistence) runs in `audit.yml` and uploads SARIF.

`.github/zizmor.yml` sets the pinning policy: third-party actions must be
hash-pinned, GitHub's own actions keep readable major tags. Dependabot keeps
both current.

### The repository itself

`scripts/ci/check-repo.sh` — all green today, gates on every PR:

- every internal markdown link and image path resolves
- every `scripts/…` path named in the docs or the Makefile exists
- every `/api/…` path the hostd web panel calls has a Go handler
- `go/cmd/hostd/web/app.js` is valid JavaScript
- every shebanged script is committed executable
- no tracked file over 2 MiB outside `jniLibs`
- the working tree is clean after a build step ran
- the governance files exist

The hostd control panel (`go/cmd/hostd/web/`, 606 lines) is `//go:embed`ed into
every `libhostd.so` and is the only UI for the file API — it had no automated
check of any kind before this.

Run the same set locally with `make check`, or the whole PR-gate suite with
`make ci`.

### The shipped native payload

`scripts/ci/audit-artifacts.sh`, run nightly by `audit.yml`. **This fails
today.** That is the point — it measures the distance between what `NOTICE`
claims and what `android/app/src/main/jniLibs/` actually contains:

- every binary is an ELF matching its ABI directory — passes
- first-party daemons: correct module, `GOARCH` matches the ABI directory,
  toolchain at or above the supported floor, `vcs.modified=false` —
  **24 failures**: all built with go1.23.5 from a modified working tree
- every declared ABI split ships all six daemons — passes
- `NOTICE` records a SHA256 for every committed binary — **28 failures**:
  only cloudflared's three hashes match anything on disk
- every bundled third-party Go binary is named in `NOTICE` — **2 failures**:
  `libmatrixd.so` is `github.com/matrix-org/dendrite`, which `NOTICE` describes
  as Tuwunel v1.7.0 and separately forbids bundling
- known vulnerabilities in the binaries users actually run — **fails**:
  50 stdlib vulnerabilities per arm64 daemon
- Nextcloud version constants agree — **fails**: the app opens 33.0.5, the
  staging script stages 32.0.11

Promote each of these into `ci.yml` as it is fixed.

## What is deliberately not covered

- **Emulator / instrumentation tests.** KVM is available on GitHub's Linux x64
  runners, but `android-emulator-runner` costs 8–15 minutes and flakes on boot
  timeouts and `adb` dropouts, and an x86_64 emulator can only exercise the
  x86_64 split — not the `arm64-v8a` one that real devices use. The cheap
  substitutes (cross-compile, ELF assertions, live daemon checks) are all in
  place instead. `scripts/verify-android-emulator.sh` also needs three fixes
  before it could run anywhere: it drives the UI by a hardcoded pixel tap,
  hardcodes an AVD name, and defaults `SDK_ROOT` to `/root/Android/Sdk`.
- **Rebuild-and-byte-compare against the committed `.so` files.** Impossible as
  the artifacts stand: `build-go-android.sh` does not pass `-buildvcs=false`, so
  a CI rebuild stamps a different `vcs.revision`, and the committed binaries
  carry `vcs.modified=true` from a dirty tree. The `go version -m` assertions
  give the same staleness signal for free. Byte-reproducibility needs
  `-buildvcs=false`, a regeneration of all 24 daemons, and an exactly pinned
  `GOTOOLCHAIN` first.
- **Bash under CodeQL.** There is no shell extractor. shellcheck and actionlint
  are the only coverage the scripts get.
- **Network-dependent build scripts** (`fetch-nextcloud-payload.sh`,
  `fetch-termux-php-runtime.sh`, `build-dendrite-android.sh`,
  `build-php-android-runtime.sh`). They download unpinned, unverified content
  into the shipped APK. Running them in CI would validate the wrong thing —
  they need checksum and signature verification first.

## Cost

Source-only jobs use `sparse-checkout` plus `filter: blob:none`. A full checkout
of this repository materialises ~457 MB, of which 346 MB is `jniLibs` that most
jobs never read; excluding it turns a ~110 MB fetch into a few MB. The
`android`, `native-artifacts` and `sbom` jobs check out in full because they
genuinely need the payload.

## Making the gates binding

None of this is enforced while the default branch is unprotected: anyone with
write access can push past a red check. `audit.yml` → `branch-protection`
reports the state, but `GITHUB_TOKEN` cannot be granted `administration:read`,
so supply a fine-grained PAT as `BRANCH_PROTECTION_TOKEN` to make that
assertion binding.

Suggested required checks on `main`: every job in `ci.yml`, plus
`android / assemble, lint, unit tests`, plus the `codeql.yml` analyses.

## Release signing

`release.yml` refuses to publish an APK signed with the Android debug key, and
refuses one carrying only a v1 JAR signature (Android 11+ will not install an
APK targeting API 30+ without a v2/v3 block). `app/build.gradle.kts` reads a
real keystore from the environment when one is present and falls back to the
debug key otherwise, so local builds are unchanged.

To make releases publishable, set these repository secrets:

| Secret | Contents |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | keystore password |
| `ANDROID_KEY_ALIAS` | key alias |
| `ANDROID_KEY_PASSWORD` | key password |

The workflow also asserts the tag matches `versionName` and that the ABI split
APKs carry distinct `versionCode`s — they are all `1` today, so no two can be
published to the same track.
