#!/usr/bin/env bash
# Behavioral guard for the plugin's on-demand installer (scripts/ensure-dmtool.sh): the binary cache MUST be
# VERSION-AWARE, so bumping the pinned VERSION re-downloads the matching binary instead of serving a stale
# cached one forever. That stale-cache bug (a version-less fixed path + "download only if absent") shipped a
# fixed binary that never reached upgraders — see docs + the finding it closes.
#
# Mechanism: stub `curl` to a fake release server that serves a per-version binary (a tiny script that
# self-reports its version) + a matching SHA256SUMS, then run a copy of the installer pinned to two different
# versions and assert the binary it resolves actually tracks the pin. Self-contained; needs only
# bash + shasum + awk (mac/linux). Run by EnsureDmtoolCacheTest (so it rides :cli? no — :test) and CI.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
INSTALLER="$ROOT/scripts/ensure-dmtool.sh"
# shellcheck source=release-assets.sh
. "$ROOT/scripts/release-assets.sh"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
export RK_SELFTEST_BINARY_ASSETS="${RK_BINARY_ASSETS[*]}"

# --- a fake `curl` release server -------------------------------------------------------------------
# Serves, for any /releases/download/vX.Y.Z/ URL: the asset = a shell script printing "a12-dmkits X.Y.Z",
# and SHA256SUMS = the matching checksum for every known asset name. Logs each binary fetch to $FETCH_LOG.
mkdir -p "$WORK/bin"
cat > "$WORK/bin/curl" <<'STUB'
#!/usr/bin/env bash
out=""; url=""
while [[ $# -gt 0 ]]; do
  case "$1" in -o) out="$2"; shift 2;; http*) url="$1"; shift;; *) shift;; esac
done
ver="$(printf '%s' "$url" | sed -nE 's#.*/download/(v[0-9.]+)/.*#\1#p')"
[[ -n "$ver" ]] || exit 22
content() { printf '#!/bin/sh\necho "a12-dmkits %s"\n' "${ver#v}"; }
case "$url" in
  *SHA256SUMS)
    [[ "${FAKE_CHECKSUM_MODE:-ok}" != download-fails ]] || exit 22
    : > "$out"
    if [[ "${FAKE_CHECKSUM_MODE:-ok}" == missing-entry ]]; then
      printf '%064d  unrelated-asset\n' 0 >> "$out"
      exit 0
    fi
    for a in $RK_SELFTEST_BINARY_ASSETS; do
      tmp="$(mktemp)"; content > "$tmp"
      if [[ "${FAKE_BAD_CHECKSUM:-0}" == 1 ]]; then
        printf '%064d  %s\n' 0 "$a" >> "$out"
      else
        printf '%s  %s\n' "$(shasum -a 256 "$tmp" | awk '{print $1}')" "$a" >> "$out"
      fi
      rm -f "$tmp"
    done ;;
  *dmtool-*) content > "$out"; echo "$ver ${url##*/}" >> "$FETCH_LOG" ;;
  *) exit 22 ;;
esac
STUB
chmod +x "$WORK/bin/curl"
cat > "$WORK/bin/uname" <<'STUB'
#!/usr/bin/env bash
case "${1:-}" in
  -s) printf '%s\n' "${FAKE_UNAME_S:-$(/usr/bin/uname -s)}" ;;
  -m) printf '%s\n' "${FAKE_UNAME_M:-$(/usr/bin/uname -m)}" ;;
  *) /usr/bin/uname "$@" ;;
esac
STUB
chmod +x "$WORK/bin/uname"
export FETCH_LOG="$WORK/fetches.log"; : > "$FETCH_LOG"
export PATH="$WORK/bin:$PATH"
export PLUGIN_DATA="$WORK/data" CLAUDE_PLUGIN_DATA="$WORK/data"
unset RK_DMTOOL_LOCAL CLAUDE_PLUGIN_ROOT PLUGIN_ROOT 2>/dev/null || true

fail() { echo "SELFTEST FAIL: $*" >&2; exit 1; }

# Run a copy of the installer pinned to $1; echo the bin dir it exported onto PATH (via its env-file).
resolved_dir=""
run_at() {
  local ver="$1"; local copy="$WORK/installer-$ver.sh" envf="$WORK/env-$ver"; : > "$envf"
  sed -E "s/^VERSION=\"v[0-9.]+\"/VERSION=\"$ver\"/" "$INSTALLER" > "$copy"
  cp "$ROOT/scripts/release-assets.sh" "$WORK/release-assets.sh"
  CLAUDE_ENV_FILE="$envf" bash "$copy" || fail "installer exited non-zero at $ver"
  resolved_dir="$(sed -nE 's#^export PATH="([^:]+):.*#\1#p' "$envf" | tail -1)"
  [[ -n "$resolved_dir" ]] || fail "installer put nothing on PATH at $ver"
}
reports() { "$resolved_dir/dmtool" --version 2>/dev/null | awk 'NR==1{print $2}'; }

# 1. first version: downloads + the resolved binary reports it
run_at v9.9.1
[[ "$(reports)" == 9.9.1 ]] || fail "v9.9.1: resolved binary reports '$(reports)', want 9.9.1"
[[ "$(grep -c v9.9.1 "$FETCH_LOG")" -ge 1 ]] || fail "v9.9.1 was not downloaded"

# 2. THE GUARD: bumping the pin must resolve the NEW version, not reuse the cached v9.9.1
run_at v9.9.2
[[ "$(grep -c v9.9.2 "$FETCH_LOG")" -ge 1 ]] || fail "bump v9.9.1->v9.9.2 did not re-download (stale-cache bug)"
[[ "$(reports)" == 9.9.2 ]] || fail "after bump the installer still serves '$(reports)' (stale-cache bug — version-less cache)"
# the stable deterministic path ($DATA/bin/dmtool, used by hosts that can't inject PATH) must self-correct too
[[ "$("$WORK/data/bin/dmtool" --version 2>/dev/null | awk '{print $2}')" == 9.9.2 ]] \
  || fail "the stable path \$DATA/bin/dmtool did not self-correct to v9.9.2"
[[ -e "$WORK/data/bin/v9.9.1" ]] && fail "old version dir v9.9.1 was not pruned"

# 3. same version again: cache hit, no extra download
before="$(grep -c v9.9.2 "$FETCH_LOG")"
run_at v9.9.2
[[ "$(grep -c v9.9.2 "$FETCH_LOG")" == "$before" ]] || fail "re-running the same version re-downloaded (cache miss)"

# 4. platform mapping: Linux arm64/aarch64 must fetch the arm64 release asset, not fall through to manual install
rm -rf "$WORK/data"; : > "$FETCH_LOG"
FAKE_UNAME_S=Linux FAKE_UNAME_M=aarch64 run_at v9.9.3
[[ "$(reports)" == 9.9.3 ]] || fail "linux arm64: resolved binary reports '$(reports)', want 9.9.3"
grep -q "v9.9.3 $RK_LINUX_ARM64_ASSET" "$FETCH_LOG" \
  || fail "linux arm64 did not download $RK_LINUX_ARM64_ASSET"

# 5. A skill-launched command does not inherit the plugin-hook-only PLUGIN_DATA variables on Codex. The
#    installer must therefore choose a sandbox-writable temporary cache rather than assuming HOME is writable.
CODEX_TMP="$WORK/codex-tmp"; mkdir -p "$CODEX_TMP"
DEV_BINARY="$WORK/dev-dmtool"; printf '#!/bin/sh\necho "a12-dmkits 9.9.3"\n' > "$DEV_BINARY"; chmod +x "$DEV_BINARY"
unset PLUGIN_DATA CLAUDE_PLUGIN_DATA
fallback_out="$(HOME=/dev/null TMPDIR="$CODEX_TMP" RK_DMTOOL_LOCAL="$DEV_BINARY" bash "$INSTALLER" 2>&1)"
fallback_path="$CODEX_TMP/dmtool-plugin-${UID:-user}/bin/dmtool"
[[ "$fallback_out" == *"dmtool ready: $fallback_path"* ]] \
  || fail "missing plugin-data variables did not select the writable temporary cache: $fallback_out"
[[ -x "$fallback_path" ]] || fail "temporary-cache fallback did not produce an executable stable path"

# 6. The installer deliberately leaves a session alive on delivery failure, but it must never print a false
#    ready path. /dev/null is a deterministic non-directory on every supported POSIX release host.
unwritable_out="$(PLUGIN_DATA=/dev/null/dmtool CLAUDE_PLUGIN_DATA= RK_DMTOOL_LOCAL="$DEV_BINARY" bash "$INSTALLER" 2>&1)"
[[ "$unwritable_out" != *"dmtool ready:"* ]] \
  || fail "an unusable cache printed a false ready path: $unwritable_out"
[[ "$unwritable_out" == *"dmtool plugin:"* ]] \
  || fail "an unusable cache did not emit the required warning: $unwritable_out"

# 7. Integrity refusal is a delivery failure, not a reason to terminate the agent's session. It must remove
#    the rejected binary, warn, return success, and never claim readiness.
set +e
checksum_out="$(PLUGIN_DATA="$WORK/bad-checksum" CLAUDE_PLUGIN_DATA= FAKE_BAD_CHECKSUM=1 bash "$INSTALLER" 2>&1)"
checksum_status=$?
set -e
[[ $checksum_status -eq 0 ]] \
  || fail "a checksum mismatch hard-failed the calling session with exit $checksum_status: $checksum_out"
[[ "$checksum_out" == *"checksum mismatch"* && "$checksum_out" != *"dmtool ready:"* ]] \
  || fail "a checksum mismatch did not warn without claiming readiness: $checksum_out"

# 8. Verification is fail-closed even though the caller's session stays alive: a missing checksum file and
#    a checksum file without the selected asset are both unverified downloads, never installable binaries.
for checksum_mode in download-fails missing-entry; do
  checksum_data="$WORK/checksum-$checksum_mode"
  set +e
  checksum_out="$(PLUGIN_DATA="$checksum_data" CLAUDE_PLUGIN_DATA= FAKE_CHECKSUM_MODE="$checksum_mode" bash "$INSTALLER" 2>&1)"
  checksum_status=$?
  set -e
  [[ $checksum_status -eq 0 ]] \
    || fail "$checksum_mode hard-failed the calling session with exit $checksum_status: $checksum_out"
  [[ "$checksum_out" == *"checksum"* && "$checksum_out" != *"dmtool ready:"* ]] \
    || fail "$checksum_mode did not warn without claiming readiness: $checksum_out"
  [[ ! -e "$checksum_data/bin/dmtool" ]] \
    || fail "$checksum_mode left a stable path to an unverified binary"
done

echo "SELFTEST OK: version-aware cache, sandbox fallback, and truthful readiness"
