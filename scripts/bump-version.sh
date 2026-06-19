#!/usr/bin/env bash
# Bump the PROJECT version across every surface RELEASE-SPEC §4 step 1 lists. These are the deterministic
# edits that NO guard catches — VersionMentionFreshnessTest pins only the *kernel* version, so a missed file
# drifts the project version silently. This script does the mechanical version-string replacement; the two
# parts that need judgment stay manual (and are printed as next steps): the CHANGELOG *content* and the
# build/verify/commit/tag. (The release glue downstream is scripts/publish-release.sh.)
#
#   ./scripts/bump-version.sh 0.3.1        # or v0.3.1
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

NEW="${1:?usage: bump-version.sh X.Y.Z}"; NEW="${NEW#v}"
[[ "$NEW" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-](alpha|beta|rc)\.[0-9]+)?$ ]] \
  || { echo "version must be X.Y.Z (optionally -rc.N): $NEW" >&2; exit 2; }

OLD="$(grep -oE "^version = '[^']+'" build.gradle | sed -E "s/.*'([^']+)'.*/\1/")"
[[ -n "$OLD" ]]      || { echo "could not read current version from build.gradle" >&2; exit 1; }
[[ "$OLD" != "$NEW" ]] || { echo "version is already $NEW — nothing to do" >&2; exit 1; }
echo "bumping project version $OLD -> $NEW"

export OLD NEW
# Each edit is line-scoped (the `if /…/` guard) so the literal old version is only swapped where it IS the
# version — never, e.g., a coincidental match. \Q…\E quotes the dots so they aren't regex metacharacters.
perl -i -pe 's/\Q$ENV{OLD}\E/$ENV{NEW}/ if /^version = /'                    build.gradle                                          # 1. source of truth (--version / manifest.version)
perl -i -pe 's/\Q$ENV{OLD}\E/$ENV{NEW}/ if /"version"\s*:/'                  plugin/.claude-plugin/plugin.json plugin-codex/.codex-plugin/plugin.json  # 2+3. the agent-plugin manifests
perl -i -pe 's/\Q$ENV{OLD}\E/$ENV{NEW}/ if /^a12-dmkits / || /"rulekit"\s*:/' examples/cli-version.md                              # 4. the showboat-gated capture (project-version lines only)
# 5. the plugin's binary-download pin (the SessionStart hook fetches the v$NEW release). Set UNCONDITIONALLY
# (not a swap of $OLD) so it self-heals even if it ever drifted, then propagate to both plugin copies. The
# pin lagging the project version is exactly what shipped the stale v0.1.0 binary; PluginHookVersionTest now
# fails the build if this step is skipped.
perl -i -pe 's/^VERSION="v[^"]+"/VERSION="v$ENV{NEW}"/'                       scripts/ensure-dmtool.sh
./scripts/sync-plugin-assets.sh >/dev/null

FILES=(build.gradle plugin/.claude-plugin/plugin.json plugin-codex/.codex-plugin/plugin.json examples/cli-version.md \
       scripts/ensure-dmtool.sh plugin/hooks/ensure-dmtool.sh plugin-codex/hooks/ensure-dmtool.sh)
for f in build.gradle plugin/.claude-plugin/plugin.json plugin-codex/.codex-plugin/plugin.json \
         scripts/ensure-dmtool.sh plugin/hooks/ensure-dmtool.sh plugin-codex/hooks/ensure-dmtool.sh; do
  grep -q "$NEW" "$f" || { echo "FAILED to set $NEW in $f — left as-is, check manually" >&2; exit 1; }
done
echo "edited:"; git --no-pager diff --stat -- "${FILES[@]}"

cat <<EOF

The MECHANICAL edits are done. Remaining (judgment + verify), per RELEASE-SPEC §4 step 1:
  1. plugin/CHANGELOG.md — flip [Unreleased] -> [$NEW] — kernel <pin> (write the actual user-facing changes)
  2. ./build.sh :cli:installDist && PATH="\$PWD/bin:\$PATH" uvx showboat@0.6.1 verify examples/cli-version.md
  3. git commit -am "chore(release): v$NEW — kernel <pin> (A12 Tools <quarter>)"
  4. git tag -a v$NEW -m "v$NEW — …"   then   ./scripts/publish-release.sh v$NEW
EOF
