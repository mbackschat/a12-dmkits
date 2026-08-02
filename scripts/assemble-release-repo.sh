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

# The public mirror receives plugin/README.md. Refuse stale generated source statistics before touching DEST.
"$ROOT/scripts/update-code-statistics.sh" --check --artifact dmtool

# Guard: the kernel checkout must match our pin, or its notices are for the wrong version.
[[ -d "$KERNEL" ]] || { echo "kernel checkout not found at $KERNEL" >&2; exit 2; }
if ! git -C "$KERNEL" log -1 --format=%s 2>/dev/null | grep -q "$PIN"; then
  echo "kernel checkout HEAD does not mention the pin $PIN — refusing (notices would be the wrong version)" >&2
  git -C "$KERNEL" log -1 --format='  HEAD: %s' >&2; exit 1
fi

# Full rebuild: wipe any previous assembly first. `cp -Rf` only OVERWRITES — it never removes a file that no
# longer exists in source, so a structural deletion (e.g. dropping the SessionStart `hooks/` dir) would
# silently survive in a stale DEST and get rsync'd back onto the mirror. DEST is always a derived build tree.
rm -rf "$DEST"
mkdir -p "$DEST/licenses"
# 1a. the CLAUDE CODE plugin at the mirror ROOT + the public-repo README/CLAUDE.md (already EUPL-aware:
#     per-artifact licensing + source pointer + disclaimer). Claude's marketplace.json has source "." → the
#     repo root. The mirror's AGENTS.md is a GENERATED tiny redirect to the mirror's own (public) CLAUDE.md —
#     never copied from the source repo, where AGENTS.md is a SYMLINK to the internal CLAUDE.md (dual-runtime,
#     2026-07-09): a dereferencing `cp` of that symlink would ship the internal instructions into the mirror.
cp -Rf "$ROOT/plugin/." "$DEST/"
cat > "$DEST/AGENTS.md" << 'AGENTS_EOF'
# AGENTS.md

This project's agent/contributor guidance lives in **[CLAUDE.md](./CLAUDE.md)** — see it for everything, including the hard rule against leaking machine-specific paths, usernames, and other local/secret data into committed files.
AGENTS_EOF
# 1b. the CODEX plugin under codex/ (its manifest .codex-plugin/plugin.json + AGENTS.md differ from Claude's,
#     so it can't share the root), with its marketplace manifest at the Codex-standard location pointing back
#     at ./codex (git-subdir). The on-demand installer rides inside skills/ (no hooks dir — there is no hook).
mkdir -p "$DEST/codex" "$DEST/.agents/plugins"
cp -Rf "$ROOT/plugin-codex/.codex-plugin"    "$DEST/codex/"
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
# Ship the REAL examples/README.md — the single, maintained demo index — NOT a hand-written copy. A
# heredoc here used to duplicate the index and silently rotted (it froze at 6 demos + a stale "cli-runtime
# is JVM-only" note while the source README tracked all of them). One source of truth = no drift.
cp -f "$ROOT/examples/README.md" "$DEST/examples/README.md"
# 1c-bis. the multi-step SESSION catalogue — PUBLIC-MIRROR-ONLY, generated FRESH here from the internal
#     probe/tasks/ (the source of truth). It is NOT committed in the source repo, so it can never go
#     stale; each release regenerates it. The generator scrapes ONLY the scenario titles + the ordered
#     turn prompts — no gold, no probe internals (see scripts/gen-scenarios.sh).
"$ROOT/scripts/gen-scenarios.sh" > "$DEST/SCENARIOS.md"
# 1d. the mirror's own GitHub Actions workflow(s) — single-sourced here (inert under .github/mirror-workflows/,
#     i.e. NOT a workflow in this private repo) and shipped to the mirror's .github/workflows/ so the public
#     repo can build the per-OS native binaries (RELEASE-SPEC §4). Must be assembled (not hand-kept on the
#     mirror): the release push rsyncs with --delete, so anything not in this tree is wiped.
mkdir -p "$DEST/.github/workflows"
cp -f "$ROOT"/.github/mirror-workflows/*.yml "$DEST/.github/workflows/"

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

# Local Finder metadata is noise in a generated public mirror and must never be published.
find "$DEST" -type f -name .DS_Store -delete

# 4. Release-time no-drift guard: the assembled public README + plugin manifest must state both pinned
#    versions (the JUnit VersionMentionFreshnessTest enforces the source files; this re-checks what we
#    actually assembled, in case the script is run without the test suite).
for f in "$DEST/README.md" "$DEST/.claude-plugin/plugin.json" "$DEST/CHANGELOG.md"; do
  grep -q "$PIN"     "$f" || { echo "assembled $f does not mention kernel pin $PIN — refusing" >&2; exit 1; }
  grep -q "$A12DIST" "$f" || { echo "assembled $f does not mention A12 distribution $A12DIST — refusing" >&2; exit 1; }
done

echo "assembled public release-repo tree → $DEST  (kernel pin $PIN, A12 Tools $A12DIST)"
echo "--- tree ---"; (cd "$DEST" && find . -type f | sort | sed 's/^/  /')
