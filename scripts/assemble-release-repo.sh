#!/usr/bin/env bash
# Assemble the PUBLIC a12-dmtool-releases repo tree for a release — the plugin + the EUPL-1.2 compliance
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
A12DIST="$(grep -oE "a12Distribution *= *'[^']+'" "$ROOT/build.gradle" | sed -E "s/.*'([^']+)'.*/\1/")"

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
# 1c. the worked-example walkthroughs (the CI-verified showboat demos) + their OWN sample models — the
#     public showcase, RUNNABLE: the demos reference examples/models/ (the demos' own fixtures, decoupled
#     from the JUnit test fixtures — a different purpose), so a user who clones the mirror runs them verbatim
#     from the repo root. The fixtures are our own original MIT models.
mkdir -p "$DEST/examples"
cp -f  "$ROOT"/examples/cli-*.md "$DEST/examples/"
cp -Rf "$ROOT/examples/models"   "$DEST/examples/"
cat > "$DEST/examples/README.md" <<'EOF'
# Worked examples — the dmtool CLI, verb by verb

Six end-to-end walkthroughs (command + real captured output + narration) covering **every verb**. They
are executable docs — kept in sync with the live CLI by a CI gate in the source repo.

| Demo | Shows |
|---|---|
| [`cli-tour`](cli-tour.md) | discover + read — `manifest` · `operators` · `patterns` · `diagnostics` · `schema` · `model describe`/`validate` · `rule read`/`explain` |
| [`cli-edit-loop`](cli-edit-loop.md) | author/modify rules & computations, `batch`, the re-express loop |
| [`cli-structure-edit`](cli-structure-edit.md) | fields/groups/typedefs/includes/config + the safe-delete gate + the refactors |
| [`cli-apply`](cli-apply.md) | the atomic multi-op session — rollback + correctives + in-session refactors |
| [`cli-runtime`](cli-runtime.md) | `model eval` / `rule test` / `model compute` against document instances **(JVM only — not in the native binary)** |
| [`cli-version`](cli-version.md) | `--version`, `manifest.version`, the model-version compatibility policy |

> **Try them:** clone this repo and run from its root with `dmtool` on PATH. The commands call the bare
> `dmtool` (what the plugin/raw install provides); discovery verbs (`manifest`, `operators`, `patterns`,
> `schema`, `--help`) need no model, and the model-based ones use the sample models shipped in
> [`models/`](models/). (`cli-runtime`'s verbs are JVM-only — not in the native binary.)
EOF
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

# 4. Release-time no-drift guard: the assembled public README + plugin manifest must state both pinned
#    versions (the JUnit VersionMentionFreshnessTest enforces the source files; this re-checks what we
#    actually assembled, in case the script is run without the test suite).
for f in "$DEST/README.md" "$DEST/.claude-plugin/plugin.json" "$DEST/CHANGELOG.md"; do
  grep -q "$PIN"     "$f" || { echo "assembled $f does not mention kernel pin $PIN — refusing" >&2; exit 1; }
  grep -q "$A12DIST" "$f" || { echo "assembled $f does not mention A12 distribution $A12DIST — refusing" >&2; exit 1; }
done

echo "assembled public release-repo tree → $DEST  (kernel pin $PIN, A12 Tools $A12DIST)"
echo "--- tree ---"; (cd "$DEST" && find . -type f | sort | sed 's/^/  /')
