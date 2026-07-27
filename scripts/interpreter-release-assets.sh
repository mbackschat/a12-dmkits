#!/usr/bin/env bash

# Canonical asset names for the public a12-interpreter-releases repository. The interpreter differs from
# dmtool because it publishes one library package, a cumulative Maven repository, versioned API docs, and
# one static showcase rather than per-platform binaries.

interpreter_release_asset_names() {
  local version="${1:?interpreter artifact version is required}"
  printf '%s\n' \
    "a12-interpreter-${version}.tgz" \
    "dm-interpreter-${version}-maven-repository.zip" \
    "dm-interpreter-${version}-api-docs.zip" \
    "dm-interpreter-${version}-showcase.zip"
}
