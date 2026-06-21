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
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

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
    : > "$out"
    for a in dmtool-macos-arm64 dmtool-linux-x64 dmtool-windows-x64.exe; do
      tmp="$(mktemp)"; content > "$tmp"
      printf '%s  %s\n' "$(shasum -a 256 "$tmp" | awk '{print $1}')" "$a" >> "$out"; rm -f "$tmp"
    done ;;
  *dmtool-*) content > "$out"; echo "$ver" >> "$FETCH_LOG" ;;
  *) exit 22 ;;
esac
STUB
chmod +x "$WORK/bin/curl"
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

echo "SELFTEST OK: version-aware cache — downloads on bump, reuses on same version"
