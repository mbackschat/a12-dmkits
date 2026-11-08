---
name: release
description: Release a12-dmkits — one version to all four repositories (the source, plus the dmtool, interpreter and consistency-checker mirrors). Use whenever the owner says "release", "cut X.Y.Z", "publish", "go live", or asks to resume an interrupted release. A release is never one artifact alone.
---

# Releasing a12-dmkits

**"Release" means all four repositories, one version, one source commit.** The contract and the step table are [RELEASE-SPEC §4a](../../../docs/RELEASE-SPEC.md#four-repositories); the orchestrator is [`scripts/typescript/release.ts`](../../../scripts/typescript/release.ts). Below, `R` stands for `scripts/run-typescript.sh release scripts/typescript/release.ts`.

## Before you start

1. `R status X.Y.Z`. Resume from whatever it shows; every step is re-runnable.
2. Pick the version. A breaking interpreter or checker API change is a minor bump before 1.0.

## Prepare (one commit, pushed)

1. `scripts/bump-version.sh X.Y.Z`. Write the plugin changelog's `[X.Y.Z]` entries and the two library changelogs' `[@VERSION@]` entries. Headline topics only.
2. Re-capture the native reflection metadata: `GRAALVM_HOME=<graalvm-25> ./scripts/capture-native-config.sh`. Its stamp hashes `build.gradle`, so it comes after the bump; the traced suite outlasts one harness process, so run it in slices ([NATIVE-IMAGE-GRAALVM §5.2](../../../docs/NATIVE-IMAGE-GRAALVM.md#capture-slices)). Commit the metadata and `cli/native-capture-stamp.txt`.
3. Run `scripts/release-perf-gate.sh --fresh` on a near-idle machine (`uptime` first, nothing else running). It writes a `perf-results/` record; commit that too.
4. Run `scripts/update-code-statistics.sh --artifact all`.
5. Commit, then push `main`, but only once the owner has asked for the release, since a push burns CI minutes.

## Publish

| Run | Then |
|---|---|
| `R preflight X.Y.Z` | Fix everything it lists in one commit; re-run. |
| `R tag X.Y.Z` | From here until go-live, commit nothing: the publishers require HEAD at their tags. |
| `R stage X.Y.Z` | This takes long: `:interpreter:check` and a native build. Launch it through the harness's background mechanism with output to a file, and wait for its own exit. |
| review | Wait for the dmtool mirror's `build-native` run and the interpreter's public build to go green, then read the three drafts. |
| `R go-live X.Y.Z` | Libraries first; dmtool goes last and refuses otherwise. |
| `R freeze X.Y.Z` | Commit `chore(release): open the post-X.Y.Z changelog sections` and push. |

## When something fails

- **A publisher refusal names its own remedy.** Fix it at the root. Never hand-edit a mirror or a published release, because published releases are immutable (RELEASE-SPEC §4).
- **A fix that needs a commit after tagging** means moving all three source tags to the new commit. Moving a pushed tag needs the owner's explicit go-ahead. Lease each tag on its old object id: `git push --force-with-lease=refs/tags/T:<old sha> origin T`.
- **Stop and report** when a gate fails for a reason the release cannot fix: a performance breach, a red lane or an open gap.
