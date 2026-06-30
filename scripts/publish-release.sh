#!/usr/bin/env bash
# Publish a release to the PUBLIC a12-dmtool-releases mirror — the SCRIPTED, mechanical end-to-end that
# RELEASE-SPEC §4 previously described only in prose. It closes the glue gaps a cold follower hit:
#   1. the version-bump surface is a checklist (below) — do it BEFORE tagging;
#   2. the mirror CONTENT push (rsync --delete into a fresh clone of the mirror) — was undocumented;
#   3. the draft-release create + mac-binary upload;
#   4. the ORDERING trap: the mac binary must be on the release BEFORE the CI dispatch, because the
#      workflow's publish job re-sums EVERY `dmtool-*` already attached (build-native.yml step "Regenerate
#      SHA256SUMS") — upload mac late and the checksum file silently won't cover it;
#   5. the linux/windows CI dispatch via `gh workflow run` (not a click-path);
#   6. the final --latest flip, kept as a SEPARATE explicit step (the runbook's draft-review seam).
#
# PREREQ — the release commit + annotated tag must exist on main AND be PUSHED TO ORIGIN (the mirror's
# build-native CI builds the binary from the private source at `ref: $TAG`, so a tag that's only local makes
# CI fail; §0 below now enforces this — `git push origin main vX.Y.Z` before running this). The version bump is maintainer
# judgment (the number) + this exact file set (NONE of these is caught by VersionMentionFreshnessTest,
# which guards only the KERNEL pin — the project version drifts silently if you miss one). Run
# `./scripts/bump-version.sh X.Y.Z` to make the first four edits below mechanically; flip the CHANGELOG by hand:
#   • build.gradle                        `version = 'X.Y.Z'`   (source of truth → --version/manifest.version)
#   • plugin/.claude-plugin/plugin.json   `"version": "X.Y.Z"`
#   • plugin-codex/.codex-plugin/plugin.json  `"version": "X.Y.Z"`
#   • examples/cli-version.md             regen the captured `--version` + `manifest.version` (showboat-gated)
#   • plugin/CHANGELOG.md                 flip [Unreleased] → [X.Y.Z] — kernel <pin>; this section IS the notes
# then: ./gradlew :cli:installDist && export PATH="$PWD/bin:$PATH" && uvx showboat@0.6.1 verify examples/cli-version.md
#       git commit -am "chore(release): vX.Y.Z — kernel <pin> (A12 Tools <quarter>)" && git tag -a vX.Y.Z -m … && git push origin main vX.Y.Z
#
# Usage:
#   ./scripts/publish-release.sh vX.Y.Z             # STAGE: build+gate mac, assemble, push mirror content,
#                                                   #   create draft, upload mac, dispatch linux/windows CI
#   ./scripts/publish-release.sh vX.Y.Z --go-live   # after CI is GREEN and you reviewed the draft:
#                                                   #   verify all 3 binaries + SHA256SUMS, then flip --latest
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

MIRROR="mbackschat/a12-dmtool-releases"
NATIVE="$ROOT/cli/build/native/nativeCompile/dmtool"   # mac arm64 (per-OS, no cross-compile — §3a)
MAC_ASSET="dmtool-macos-arm64"
CLONE="$ROOT/build/mirror-clone"
DEST="$ROOT/build/release-repo"

TAG="${1:?usage: publish-release.sh vX.Y.Z [--go-live]}"
[[ "$TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+([.-](alpha|beta|rc)\.[0-9]+)?$ ]] || { echo "tag must be vMAJOR.MINOR.PATCH: $TAG" >&2; exit 2; }
GO_LIVE=0; [[ "${2:-}" == "--go-live" ]] && GO_LIVE=1
VER="${TAG#v}"

KVER="$(grep -oE "kernelVersion *= *'[^']+'" build.gradle | sed -E "s/.*'([^']+)'.*/\1/")"
A12="$(grep -oE "a12Distribution *= *'[^']+'" build.gradle | sed -E "s/.*'([^']+)'.*/\1/")"
TITLE="$TAG — kernel $KVER (A12 Tools $A12)"

say() { printf '\n\033[1m== %s\033[0m\n' "$*"; }

# ---- the release notes = the matching CHANGELOG section (§4: "that section doubles as the release notes") --
notes_file() {
  local out="$ROOT/build/release-notes-$VER.md"
  awk -v v="$VER" '
    $0 ~ "^## \\[" v "\\]" {grab=1; next}
    grab && /^## \[/ {exit}
    grab {print}
  ' plugin/CHANGELOG.md > "$out"
  [[ -s "$out" ]] || { echo "no CHANGELOG section for [$VER] — flip [Unreleased] first" >&2; exit 1; }
  echo "$out"
}

# ============================ --go-live : the final, separate publish ============================
if [[ "$GO_LIVE" == 1 ]]; then
  say "Verify all assets present before going live"
  assets="$(gh release view "$TAG" --repo "$MIRROR" --json assets -q '.assets[].name' | sort)"
  echo "$assets"
  for a in "$MAC_ASSET" dmtool-linux-x64 dmtool-windows-x64.exe SHA256SUMS; do
    grep -qx "$a" <<<"$assets" || { echo "MISSING asset: $a — is the CI run finished?" >&2; exit 1; }
  done
  say "Verify SHA256SUMS covers every binary"
  tmp="$(mktemp -d)"; ( cd "$tmp"
    gh release download "$TAG" --repo "$MIRROR" --pattern 'dmtool-*'
    gh release download "$TAG" --repo "$MIRROR" --pattern 'SHA256SUMS'
    shasum -a 256 -c SHA256SUMS )
  rm -rf "$tmp"
  say "Flip the draft to the latest published release"
  gh release edit "$TAG" --repo "$MIRROR" --draft=false --latest
  echo "PUBLISHED: $TITLE  → https://github.com/$MIRROR/releases/tag/$TAG"
  exit 0
fi

# ============================ stage : everything up to the reviewable draft ============================
say "0. Preconditions"
[[ -z "$(git status --porcelain)" ]] || { echo "working tree not clean — commit/stash first" >&2; exit 1; }
git rev-parse "$TAG" >/dev/null 2>&1 || { echo "tag $TAG does not exist — bump+commit+tag first (see header)" >&2; exit 1; }
# The tag MUST be on origin and point at the same commit as the local tag: step 6 dispatches the mirror's
# build-native CI, which checks out the PRIVATE source at `ref: $TAG` (see .github/mirror-workflows/
# build-native.yml). If the tag isn't pushed (or origin's is stale), that checkout fails — a confusing
# mid-flight CI failure AFTER we've already pushed the mirror + created a draft. Catch it here, fail fast
# with the exact push. (We don't push for you — pushing source is the maintainer's call.)
local_tag_commit="$(git rev-parse "$TAG^{}")"
remote_tag_commit="$(git ls-remote origin "refs/tags/$TAG^{}" 2>/dev/null | awk '{print $1}')"
[[ -n "$remote_tag_commit" ]] || remote_tag_commit="$(git ls-remote origin "refs/tags/$TAG" 2>/dev/null | awk '{print $1}')"
if [[ -z "$remote_tag_commit" ]]; then
  echo "tag $TAG is not on origin — the mirror's build-native CI builds the binary from the private source at $TAG." >&2
  echo "  push it first:  git push origin main $TAG" >&2; exit 1
fi
if [[ "$remote_tag_commit" != "$local_tag_commit" ]]; then
  echo "origin's $TAG ($remote_tag_commit) differs from local ($local_tag_commit) — CI would build the wrong commit." >&2
  echo "  push the current tag:  git push --force-with-lease origin $TAG" >&2; exit 1
fi
# The tag marks the version-bump commit; tooling/doc commits may legitimately follow it. The real invariant
# is that the SHIPPED content (build.gradle version, the working tree the binary+mirror are built from) is
# this release — so we check the version, not HEAD==tag identity.
[[ "$(grep -oE "version = '[^']+'" build.gradle | sed -E "s/.*'([^']+)'.*/\1/")" == "$VER" ]] || { echo "build.gradle version != $VER" >&2; exit 1; }
gh auth status >/dev/null 2>&1 || { echo "gh not authenticated" >&2; exit 1; }
gh repo view "$MIRROR" >/dev/null 2>&1 || { echo "cannot reach mirror repo $MIRROR" >&2; exit 1; }
./scripts/sync-plugin-assets.sh --check >/dev/null || { echo "plugin assets stale — run ./scripts/sync-plugin-assets.sh" >&2; exit 1; }
echo "ok — $TITLE"

say "1. mac arm64 native binary + verification gate (§3a/§5)"
: "${GRAALVM_HOME:?set GRAALVM_HOME to a GraalVM 25 JDK}"
# ALWAYS (re)build — Gradle's nativeCompile is incremental: UP-TO-DATE & fast when the source is unchanged,
# a full rebuild when it isn't. DO NOT guard on `[[ -x "$NATIVE" ]]`: reusing a pre-existing artifact ships a
# STALE binary — it bit v0.8.1, where a leftover 0.8.0 nativeCompile output (predating the release source)
# was reused, and the gate caught a 0.8.0 `--version` + a missing report-wrap under the 0.8.1 tag. Existence
# is not freshness. Route through ./build.sh — it pins the isolated, init.d-free GRADLE_USER_HOME + JDK 21 so
# a bare ./gradlew can't resolve native-image/Kotlin plugins from the private registry; GRAALVM_HOME
# feeds the native-image toolchain detection.
./build.sh :cli:nativeCompile -Porg.gradle.java.installations.fromEnv=GRAALVM_HOME
# Guard the whole STALE-BINARY class (not just the reused-artifact path above): the binary we are about to
# gate, upload, and flip to `latest` MUST report THIS release's version. Fail loud here — never ship a binary
# whose `--version` disagrees with the tag.
built_ver="$("$NATIVE" --version 2>/dev/null | awk 'NR==1{print $2}')"
[[ "$built_ver" == "$VER" ]] || { echo "native binary reports '$built_ver', expected '$VER' — STALE/wrong build; delete $NATIVE and re-run." >&2; exit 1; }
echo "native binary version ok: $built_ver"
./scripts/native-gate.sh "$NATIVE"

say "2. Assemble the public mirror tree"
./scripts/assemble-release-repo.sh "$DEST"

say "3. Push the mirror CONTENT (rsync --delete into a fresh clone)"
rm -rf "$CLONE"
gh repo clone "$MIRROR" "$CLONE" -- --depth 1
rsync -a --delete --exclude='.git' "$DEST"/ "$CLONE"/
if [[ -n "$(git -C "$CLONE" status --porcelain)" ]]; then
  git -C "$CLONE" add -A
  git -C "$CLONE" commit -m "release $TAG — kernel $KVER (A12 Tools $A12)"
  git -C "$CLONE" push
  echo "mirror content pushed"
else
  echo "mirror content already up to date"
fi

say "4. Ensure a DRAFT release exists"
NOTES="$(notes_file)"
if gh release view "$TAG" --repo "$MIRROR" >/dev/null 2>&1; then
  echo "release $TAG already exists — leaving as is"
else
  gh release create "$TAG" --repo "$MIRROR" --draft --title "$TITLE" --notes-file "$NOTES"
fi

say "5. Upload the mac binary (BEFORE the CI dispatch — so the CI SHA256SUMS regen covers it, gap #4)"
stage="$(mktemp -d)"; cp "$NATIVE" "$stage/$MAC_ASSET"
gh release upload "$TAG" "$stage/$MAC_ASSET" --clobber --repo "$MIRROR"
rm -rf "$stage"

say "6. Dispatch the linux/windows native build (it uploads them + re-sums ALL dmtool-*)"
gh workflow run build-native --repo "$MIRROR" -f source_ref="$TAG" -f release_tag="$TAG"
echo "dispatched. Watch:  gh run watch \$(gh run list --repo $MIRROR --workflow build-native -L1 --json databaseId -q '.[0].databaseId') --repo $MIRROR"

cat <<EOF

STAGED. The draft release has the mac binary; linux/windows + SHA256SUMS arrive when CI finishes.
Next (after CI is GREEN and you've reviewed the draft):
  ./scripts/publish-release.sh $TAG --go-live
EOF
