#!/usr/bin/env bash
# Guard that every release surface agrees with scripts/release-assets.sh.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=release-assets.sh
. "$ROOT/scripts/release-assets.sh"

fail() { echo "release-assets check failed: $*" >&2; exit 1; }

expect_asset() {
  local actual="$1" expected="$2" label="$3"
  [[ "$actual" == "$expected" ]] || fail "$label maps to '$actual', expected '$expected'"
}

expect_asset "$(rk_release_asset_for_platform Darwin arm64)" "$RK_MACOS_ARM64_ASSET" "Darwin/arm64"
expect_asset "$(rk_release_asset_for_platform Linux x86_64)" "$RK_LINUX_X64_ASSET" "Linux/x86_64"
expect_asset "$(rk_release_asset_for_platform Linux aarch64)" "$RK_LINUX_ARM64_ASSET" "Linux/aarch64"
expect_asset "$(rk_release_asset_for_platform Linux arm64)" "$RK_LINUX_ARM64_ASSET" "Linux/arm64"
if rk_release_asset_for_platform Linux riscv64 >/dev/null; then
  fail "Linux/riscv64 unexpectedly maps to a release asset"
fi

workflow="$ROOT/.github/mirror-workflows/build-native.yml"
[[ -f "$workflow" ]] || fail "mirror workflow missing: $workflow"

expected="$(mktemp)"
actual="$(mktemp)"
trap 'rm -f "$expected" "$actual"' EXIT
printf '%s\n' "${RK_CI_NATIVE_ASSETS[@]}" | sort > "$expected"
sed -nE 's/^[[:space:]]+asset: (dmtool[^[:space:]]+)$/\1/p' "$workflow" | sort > "$actual"

if ! diff -u "$expected" "$actual"; then
  fail "the build-native workflow asset matrix drifted from scripts/release-assets.sh"
fi

echo "release-assets check: OK"
