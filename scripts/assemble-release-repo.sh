#!/usr/bin/env bash
# Assemble the PUBLIC dmtool-releases repo tree for a release — the plugin + the EUPL-1.2 compliance
# bundle for the kernel-embedding native binary (LICENSING.md §33). Kernel notices are copied FRESH from
# the pinned kernel checkout (../a12-kernel), not committed into this MIT source repo; re-run on a kernel
# bump (KERNEL-UPGRADE.md). The native binaries + SHA256SUMS are uploaded to the Release separately.
#
#   ./scripts/assemble-release-repo.sh [dest]      # default dest: build/release-repo
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KERNEL="$ROOT/../a12-kernel"
DEST="${1:-$ROOT/build/release-repo}"
PIN="$(grep -oE "kernelVersion *= *'[^']+'" "$ROOT/build.gradle" | grep -oE "[0-9][0-9.]+")"

# Guard: the kernel checkout must match our pin, or its notices are for the wrong version.
[[ -d "$KERNEL" ]] || { echo "kernel checkout not found at $KERNEL" >&2; exit 2; }
if ! git -C "$KERNEL" log -1 --format=%s 2>/dev/null | grep -q "$PIN"; then
  echo "kernel checkout HEAD does not mention the pin $PIN — refusing (notices would be the wrong version)" >&2
  git -C "$KERNEL" log -1 --format='  HEAD: %s' >&2; exit 1
fi

mkdir -p "$DEST/licenses"
# 1a. the CLAUDE CODE plugin at the mirror ROOT + the public-repo README (already EUPL-aware: per-artifact
#     licensing + source pointer + disclaimer). Claude's marketplace.json has source "." → the repo root.
cp -Rf "$ROOT/plugin/." "$DEST/"
# 1b. the CODEX plugin under codex/ (its hooks.json differs from Claude's, so it can't share the root),
#     with its marketplace manifest at the Codex-standard location pointing back at ./codex (git-subdir).
mkdir -p "$DEST/codex" "$DEST/.agents/plugins"
cp -Rf "$ROOT/plugin-codex/.codex-plugin"    "$DEST/codex/"
cp -Rf "$ROOT/plugin-codex/hooks"            "$DEST/codex/"
cp -Rf "$ROOT/plugin-codex/skills"           "$DEST/codex/"
cp -f  "$ROOT/plugin-codex/AGENTS.md"        "$DEST/codex/"
cp -f  "$ROOT/plugin-codex/marketplace.json" "$DEST/.agents/plugins/marketplace.json"
# 2. EUPL-1.2 compliance bundle, from the pinned kernel
cp -f  "$KERNEL/licenses/EUPL-1.2.txt"  "$DEST/LICENSE"           # the artifact is offered under EUPL-1.2
cp -f  "$KERNEL/LICENSE"                "$DEST/KERNEL-LICENSE"    # the embedded kernel's own (dual) licence
cp -f  "$KERNEL/THIRD_PARTY_NOTICES"    "$DEST/THIRD_PARTY_NOTICES"
cp -Rf "$KERNEL/licenses/."             "$DEST/licenses/"         # EUPL-1.2, Apache-2.0, BSD-3-Clause, MIT
# 3. NOTICE = our attribution header + the kernel's NOTICE preserved
{
  cat <<'EOF'
dmtool — A12 rule-authoring CLI
Copyright (c) 2026 Martin Backschat. Plugin/CLI source: MIT.

This distributed native binary EMBEDS the A12 Kernel and is therefore offered under the
European Union Public Licence v1.2 (see LICENSE). Kernel source (EUPL-1.2), satisfying
EUPL Art. 5: https://github.com/mgm-tp

Not an official mgm / A12 artifact.

----------------------------------------------------------------------
Embedded component — A12 Kernel — NOTICE:
----------------------------------------------------------------------
EOF
  cat "$KERNEL/NOTICE"
} > "$DEST/NOTICE"

echo "assembled public release-repo tree → $DEST  (kernel pin $PIN)"
echo "--- tree ---"; (cd "$DEST" && find . -type f | sort | sed 's/^/  /')
