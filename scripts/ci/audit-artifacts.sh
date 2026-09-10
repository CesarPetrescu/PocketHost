#!/usr/bin/env bash
# Provenance and attribution audit of the committed native payload.
#
# Unlike scripts/ci/check-repo.sh this is NOT expected to pass today: it exists
# to keep the gap between what NOTICE claims and what jniLibs actually contains
# visible and measurable. It runs on a schedule, not as a pull-request gate.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT" || exit 1

JNI="android/app/src/main/jniLibs"
DAEMONS=(hostd webd filed ddnsd proxyd nextcloudd)
ABIS=(arm64-v8a armeabi-v7a x86 x86_64)
# Minimum Go toolchain the shipped daemons must be built with. Go supports the
# two most recent minor releases; anything older gets no security backports.
MIN_GO_MINOR=26

FAILED=0
fail() { printf 'FAIL %s\n' "$*" >&2; FAILED=1; }
ok()   { printf 'ok   %s\n' "$*"; }
note() { printf 'note %s\n' "$*"; }

goarch_for_abi() {
  case "$1" in
    arm64-v8a) echo arm64 ;; armeabi-v7a) echo arm ;;
    x86) echo 386 ;; x86_64) echo amd64 ;; *) echo "" ;;
  esac
}

# --- every jniLibs entry is an ELF for the ABI directory it sits in ----------
check_elf() {
  local bad=0 f abi want got
  while IFS= read -r f; do
    abi="$(basename "$(dirname "$f")")"
    file -b "$f" | grep -q '^ELF' || { fail "$f is not an ELF binary"; bad=1; continue; }
    case "$abi" in
      arm64-v8a)   want="ARM aarch64" ;;
      armeabi-v7a) want="ARM," ;;
      x86)         want="Intel 80386" ;;
      x86_64)      want="x86-64" ;;
      *)           fail "unexpected ABI directory: $abi"; bad=1; continue ;;
    esac
    got="$(file -b "$f")"
    [[ "$got" == *"$want"* ]] || { fail "$f: expected $want, file(1) says: $got"; bad=1; }
  done < <(git ls-files "$JNI/*.so")
  (( bad == 0 )) && ok "every jniLibs binary is an ELF matching its ABI directory"
}

# --- first-party daemons: module, arch, toolchain, clean-tree stamp ----------
check_go_provenance() {
  command -v go >/dev/null 2>&1 || { note "go not installed, skipping provenance"; return; }
  local bad=0 abi d f meta ver arch minor
  for abi in "${ABIS[@]}"; do
    for d in "${DAEMONS[@]}"; do
      f="$JNI/$abi/lib$d.so"
      [[ -f "$f" ]] || continue
      meta="$(go version -m "$f" 2>/dev/null)" || { fail "$f: not a Go binary"; bad=1; continue; }

      grep -q 'mod[[:space:]]*dev.pockethost/daemons' <<<"$meta" \
        || { fail "$f: not built from dev.pockethost/daemons"; bad=1; }

      arch="$(grep -oP 'build\s+GOARCH=\K\S+' <<<"$meta" | head -1)"
      [[ "$arch" == "$(goarch_for_abi "$abi")" ]] \
        || { fail "$f: GOARCH=$arch but it sits in $abi/"; bad=1; }

      ver="$(head -1 <<<"$meta" | grep -oP 'go1\.\K[0-9]+' | head -1)"
      minor="${ver:-0}"
      (( minor >= MIN_GO_MINOR )) \
        || { fail "$f: built with go1.$minor, below the supported floor go1.$MIN_GO_MINOR"; bad=1; }

      grep -q 'vcs.modified=true' <<<"$meta" \
        && { fail "$f: built from a modified working tree (vcs.modified=true)"; bad=1; }
    done
  done
  (( bad == 0 )) && ok "every committed first-party daemon has sound build provenance"
}

# --- ABI splits declared in Gradle must ship the same daemon set -------------
check_abi_matrix() {
  local bad=0 abi d missing
  for abi in "${ABIS[@]}"; do
    missing=()
    for d in "${DAEMONS[@]}"; do
      [[ -f "$JNI/$abi/lib$d.so" ]] || missing+=("$d")
    done
    if (( ${#missing[@]} )); then
      fail "$abi is missing daemons: ${missing[*]}"
      bad=1
    fi
  done
  # Third-party payloads are allowed to be ABI-specific, but report the shape so
  # a split that silently loses a feature is visible.
  for abi in "${ABIS[@]}"; do
    for d in cloudflared matrixd php; do
      [[ -f "$JNI/$abi/lib$d.so" ]] || note "$abi ships no lib$d.so (that split has no $d feature)"
    done
  done
  (( bad == 0 )) && ok "every declared ABI split ships all six first-party daemons"
}

# --- NOTICE must record a SHA256 for every committed binary ------------------
check_notice_hashes() {
  local bad=0 f sum recorded
  recorded="$(grep -oE '\b[0-9a-f]{64}\b' NOTICE | sort -u)"
  while IFS= read -r f; do
    sum="$(sha256sum "$f" | cut -d' ' -f1)"
    grep -qxF "$sum" <<<"$recorded" \
      || { fail "no SHA256 in NOTICE matches $f ($sum)"; bad=1; }
  done < <(git ls-files "$JNI/*.so")
  # And every hash NOTICE records must describe a file that still exists.
  local h
  while IFS= read -r h; do
    [[ -z "$h" ]] && continue
    grep -qxF "$h" <(git ls-files "$JNI/*.so" | xargs -r sha256sum | cut -d' ' -f1) \
      || fail "NOTICE records SHA256 $h but no committed binary has it"
  done <<<"$recorded"
  (( bad == 0 )) && ok "NOTICE records a matching SHA256 for every committed binary"
}

# --- third-party Go binaries must be attributed by module path --------------
check_thirdparty_identity() {
  command -v go >/dev/null 2>&1 || { note "go not installed, skipping identity"; return; }
  local bad=0 f mod
  while IFS= read -r f; do
    case "$(basename "$f")" in
      libhostd.so|libwebd.so|libfiled.so|libddnsd.so|libproxyd.so|libnextcloudd.so) continue ;;
    esac
    mod="$(go version -m "$f" 2>/dev/null | grep -oP '^\s*mod\s+\K\S+' | head -1)"
    [[ -z "$mod" ]] && { note "$f is not a Go binary; identity must be recorded by hand"; continue; }
    grep -qF "$mod" NOTICE \
      || { fail "$f is $mod but NOTICE never names that module"; bad=1; }
  done < <(git ls-files "$JNI/*.so")
  (( bad == 0 )) && ok "every bundled third-party Go binary is named in NOTICE"
}

# --- known vulnerabilities in the binaries users actually run ---------------
check_binary_vulns() {
  command -v govulncheck >/dev/null 2>&1 || { note "govulncheck not installed, skipping"; return; }
  local bad=0 f out
  while IFS= read -r f; do
    out="$(govulncheck -mode=binary "$f" 2>&1)"
    if grep -q 'No vulnerabilities found' <<<"$out"; then
      ok "no known vulnerabilities in $f"
    else
      fail "$f: $(grep -oE 'affected by [0-9]+ vulnerabilit\w+' <<<"$out" | head -1)"
      bad=1
    fi
  done < <(git ls-files "$JNI/arm64-v8a/*.so")
}

# --- version constants agree across Go, Kotlin, shell and docs --------------
check_version_drift() {
  local bad=0 kotlin staged
  kotlin="$(grep -oP 'VERSION\s*=\s*"\K[0-9.]+' \
            android/app/src/main/java/dev/pockethost/supervisor/NextcloudInstaller.kt | head -1)"
  staged="$(grep -oP 'nextcloud-server-\K[0-9.]+(?=\.zip)' \
            scripts/stage-nextcloud-experimental.sh | head -1)"
  if [[ -n "$kotlin" && -n "$staged" && "$kotlin" != "$staged" ]]; then
    fail "Nextcloud version drift: the app opens $kotlin, stage-nextcloud-experimental.sh stages $staged"
    bad=1
  fi
  if [[ -n "$kotlin" ]] && ! grep -rqF "$kotlin" NOTICE docs/NEXTCLOUD_EXPERIMENTAL.md 2>/dev/null; then
    fail "Nextcloud $kotlin is what ships but neither NOTICE nor docs/NEXTCLOUD_EXPERIMENTAL.md mentions it"
    bad=1
  fi
  (( bad == 0 )) && ok "Nextcloud version constants agree across code, scripts and docs"
}

case "${1:-all}" in
  elf)          check_elf ;;
  provenance)   check_go_provenance ;;
  abi)          check_abi_matrix ;;
  notice)       check_notice_hashes ;;
  identity)     check_thirdparty_identity ;;
  vulns)        check_binary_vulns ;;
  versions)     check_version_drift ;;
  all)
    check_elf
    check_go_provenance
    check_abi_matrix
    check_notice_hashes
    check_thirdparty_identity
    check_binary_vulns
    check_version_drift
    ;;
  *) echo "usage: $0 [elf|provenance|abi|notice|identity|vulns|versions|all]" >&2; exit 2 ;;
esac

exit "$FAILED"
