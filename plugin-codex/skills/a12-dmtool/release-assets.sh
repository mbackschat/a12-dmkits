#!/usr/bin/env bash
# Canonical release asset names for the public a12-dmtool-releases repo.
#
# This file is sourced by release scripts and copied beside the plugin installer. Keep asset names here, not
# retyped into shell scripts. The mirror workflow matrix is static YAML, so scripts/check-release-assets.sh
# verifies that its asset rows match this manifest.

RK_MACOS_ARM64_ASSET="dmtool-macos-arm64"
RK_LINUX_X64_ASSET="dmtool-linux-x64"
RK_LINUX_ARM64_ASSET="dmtool-linux-arm64"
RK_WINDOWS_X64_ASSET="dmtool-windows-x64.exe"
RK_SHA256_ASSET="SHA256SUMS"

RK_BINARY_ASSETS=(
  "$RK_MACOS_ARM64_ASSET"
  "$RK_LINUX_X64_ASSET"
  "$RK_LINUX_ARM64_ASSET"
  "$RK_WINDOWS_X64_ASSET"
)

RK_CI_NATIVE_ASSETS=(
  "$RK_LINUX_X64_ASSET"
  "$RK_LINUX_ARM64_ASSET"
  "$RK_WINDOWS_X64_ASSET"
)

RK_RELEASE_ASSETS=(
  "${RK_BINARY_ASSETS[@]}"
  "$RK_SHA256_ASSET"
)

rk_release_asset_for_platform() {
  local os="${1:-$(uname -s)}"
  local arch="${2:-$(uname -m)}"
  case "$os/$arch" in
    Darwin/arm64)              printf '%s\n' "$RK_MACOS_ARM64_ASSET" ;;
    Linux/x86_64)              printf '%s\n' "$RK_LINUX_X64_ASSET" ;;
    Linux/aarch64|Linux/arm64) printf '%s\n' "$RK_LINUX_ARM64_ASSET" ;;
    *) return 1 ;;
  esac
}
