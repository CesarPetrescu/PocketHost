#!/usr/bin/env bash
# Repository consistency checks that pass today and are cheap enough to gate
# every pull request. Each check is independent; all of them run and the
# script exits non-zero if any failed, so one run reports every problem.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT" || exit 1

FAILED=0
fail() { printf 'FAIL %s\n' "$*" >&2; FAILED=1; }
ok()   { printf 'ok   %s\n' "$*"; }

# --- internal markdown links and image paths resolve -------------------------
check_links() {
  local bad=0 md target dir
  while IFS= read -r md; do
    dir="$(dirname "$md")"
    # ](path) where path is not a URL, anchor, or mailto
    while IFS= read -r target; do
      [[ -z "$target" ]] && continue
      target="${target%%#*}"
      [[ -z "$target" ]] && continue
      if [[ "$target" = /* ]]; then
        [[ -e "${ROOT}${target}" ]] || { fail "link $md -> $target"; bad=1; }
      else
        [[ -e "$dir/$target" ]] || { fail "link $md -> $target"; bad=1; }
      fi
    done < <(grep -oE '\]\([^)]+\)' "$md" \
             | sed -E 's/^\]\(//; s/\)$//; s/ .*$//' \
             | grep -vE '^(https?:|mailto:|#|tel:)')
  done < <(git ls-files '*.md')
  (( bad == 0 )) && ok "all internal markdown links resolve"
}

# --- every scripts/ path named in docs or the Makefile exists ----------------
check_script_refs() {
  local bad=0 ref
  while IFS= read -r ref; do
    [[ -e "$ref" ]] || { fail "referenced script missing: $ref"; bad=1; }
  done < <(git ls-files '*.md' Makefile \
           | xargs grep -ohE '(\./)?scripts/[A-Za-z0-9_.-]+\.(sh|ps1|py)' 2>/dev/null \
           | sed 's|^\./||' | sort -u)
  (( bad == 0 )) && ok "every scripts/ path referenced in docs and the Makefile exists"
}

# --- hostd control panel: every API path app.js calls exists in Go -----------
check_hostd_routes() {
  local bad=0 path
  while IFS= read -r path; do
    grep -qF "HandleFunc(\"$path\"" go/cmd/hostd/*.go \
      || { fail "hostd web panel calls $path but no Go handler registers it"; bad=1; }
  done < <(grep -oE '/api/[A-Za-z0-9_/-]+' go/cmd/hostd/web/app.js | sort -u)
  (( bad == 0 )) && ok "every /api path in the hostd web panel has a Go handler"
}

# --- the embedded panel is valid JavaScript ---------------------------------
check_hostd_js() {
  if command -v node >/dev/null 2>&1; then
    node --check go/cmd/hostd/web/app.js \
      && ok "go/cmd/hostd/web/app.js parses" \
      || fail "go/cmd/hostd/web/app.js is not valid JavaScript"
  else
    ok "node not available, skipping app.js syntax check"
  fi
}

# --- executable bit matches shebang -----------------------------------------
check_exec_bits() {
  local bad=0 f mode
  while IFS= read -r f; do
    head -c2 "$f" | grep -q '#!' || continue
    mode="$(git ls-files -s -- "$f" | awk '{print $1}')"
    [[ "$mode" == "100755" ]] || { fail "$f has a shebang but is committed mode $mode"; bad=1; }
  done < <(git ls-files 'scripts/*.sh' 'scripts/*.py')
  (( bad == 0 )) && ok "every shebanged script under scripts/ is committed executable"
}

# --- no new large files sneak in ---------------------------------------------
# jniLibs is the one place large binaries are expected; everything else is
# source and must stay small.
check_large_files() {
  local bad=0 f sha size
  # Sizes come from the index, not the worktree, so this is correct under a
  # sparse checkout where most files are not materialised on disk.
  while read -r _ sha _ f; do
    [[ "$f" == android/app/src/main/jniLibs/* ]] && continue
    [[ "$f" == android/gradle/wrapper/gradle-wrapper.jar ]] && continue
    size="$(git cat-file -s "$sha" 2>/dev/null || echo 0)"
    if (( size > 2000000 )); then
      fail "large file outside jniLibs: $f ($((size/1024)) KiB)"
      bad=1
    fi
  done < <(git ls-files -s)
  (( bad == 0 )) && ok "no tracked file over 2 MiB outside jniLibs"
}

# --- working tree is clean ---------------------------------------------------
check_clean_tree() {
  local dirty
  dirty="$(git status --porcelain)"
  if [[ -n "$dirty" ]]; then
    printf '%s\n' "$dirty" >&2
    fail "working tree is dirty (a build step wrote into the repo)"
  else
    ok "working tree is clean"
  fi
}

# --- governance files the project requires to exist -------------------------
check_required_files() {
  local bad=0 f
  for f in LICENSE NOTICE AGENTS.md SOUL.md FLYWHEEL.md README.md CHANGELOG.md; do
    [[ -f "$f" ]] || { fail "required file missing: $f"; bad=1; }
  done
  (( bad == 0 )) && ok "all required governance files present"
}

run_target() {
  case "$1" in
    links)    check_links ;;
    refs)     check_script_refs ;;
    routes)   check_hostd_routes ;;
    js)       check_hostd_js ;;
    execbits) check_exec_bits ;;
    large)    check_large_files ;;
    tree)     check_clean_tree ;;
    required) check_required_files ;;
    # Everything that does not depend on an untouched working tree, so it is
    # usable from a developer machine mid-change.
    local)    check_required_files; check_links; check_script_refs
              check_hostd_routes; check_hostd_js; check_exec_bits; check_large_files ;;
    all)      run_target local; check_clean_tree ;;
    *) echo "usage: $0 [links|refs|routes|js|execbits|large|tree|required|local|all]..." >&2; exit 2 ;;
  esac
}

if [[ $# -eq 0 ]]; then
  run_target all
else
  for target in "$@"; do run_target "$target"; done
fi

exit "$FAILED"
