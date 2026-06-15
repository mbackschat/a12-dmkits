# Release PLAN — v1 execution checklist (transient)

**Transient. Delete this file once v1 ships.** It tracks only *what's left to ship v1*. Everything durable — the surfaces, build recipe, runbook, versioning, distribution topology, licensing, the deferred developer wave, and all rationale — lives in **[`RELEASE-SPEC.md`](RELEASE-SPEC.md)** (and native specifics in **[`NATIVE-IMAGE-GRAALVM.md`](NATIVE-IMAGE-GRAALVM.md)**). Decisions are made; this is just the to-do.

## v1 scope (decided)

**Ship the `dmtool` native CLI binary + the agent plugins (Claude Code + Codex).** Native-only (GraalVM 25); macOS arm64 first, built locally. Source repo stays **private** (early testers); the public **`dmtool-releases`** mirror is the distribution point. The developer-library wave (JARs/JavaDoc/`llms.txt`/dev skill) is **deferred** → RELEASE-SPEC §11.

## Done

- ✅ `cli`→`dmtool` rename; version `0.1.0` (root `build.gradle`).
- ✅ Native build infra + binary: GraalVM 25, `-Os`, native-only Groovy exclusion, comprehensive reflection capture, the verb-hiding gate (`Cli.JVM_ONLY_VERBS` + `NativeImageVerbHidingTest`), the native gate. **`build/release/dmtool-macos-arm64` + `SHA256SUMS` staged; native gate GREEN** (5/6 + 1 skip).
- ✅ **Agent plugins (Claude Code + Codex)** in [`plugin/`](../plugin/) + [`plugin-codex/`](../plugin-codex/): per-agent manifest + marketplace + `SessionStart` hook; shared skill + agent-agnostic downloader copy-synced (`scripts/sync-plugin-assets.sh`, CI freshness-locked). **Codex validated against the installed CLI 0.139.0** (manifest parses, plugin resolves, local-source install bundles manifest + hooks + skill).
- ✅ Self-describing-surface hardening: stale-command guard + rename-proof command-ref guard (`CommandReferencesResolveTest`), tool name single-sourced (`Cli.TOOL_NAME`).
- ✅ Public **CHANGELOG** ([`plugin/CHANGELOG.md`](../plugin/CHANGELOG.md), ships to the mirror root; empty until post-v1).
- ✅ Mirror assembly (`scripts/assemble-release-repo.sh`): both plugins (Claude at root, Codex under `codex/`) + the EUPL bundle from the pinned kernel.
- ✅ All release decisions (RELEASE-SPEC + its §13 rationale); mirror name = **`dmtool-releases`**.

## Remaining to ship v1 — pre-flight → publish (awaiting "go")

1. [ ] **Create the public mirror repo** `dmtool-releases` (public). *(Outward, irreversible — needs explicit go-ahead.)*
2. [ ] **Push the mirror tree** — `scripts/assemble-release-repo.sh` → push the assembled tree (both plugins + EUPL bundle + README + CHANGELOG) to the mirror.
3. [ ] **Tag + release** — tag `v0.1.0` on this (source) repo; publish the mac binary + `SHA256SUMS` to the mirror's Release `v0.1.0` (title `v0.1.0 — kernel 30.8.1 (A12 Tools 2025.06-ext5)`; notes per RELEASE-SPEC §2; flip the CHANGELOG entry's date).
_(The top-README raw-binary "Install (macOS)" section is done — committed.)_

## Still-open (resolve in-flight)

- **Tag-push:** push the source-repo `v0.1.0` tag to `origin` (private), or keep it local?
- **Signing:** none for v1 (escape hatch in the README); revisit on friction.
- **Going public later** retires the mirror (releases move to this repo).

## After v1 (not blocking the ship)

- **Real-session plugin verification:** Claude `/plugin install` + Codex live session — confirm `SessionStart` fires and PATH injection lands (the `$PLUGIN_DATA/bin/dmtool` fallback covers a host that can't inject PATH).
- **Production-mode eval run** for a measured skill lift before declaring the skill "release-good" (currently n=1 probes).
- **Iteration 2 — CI matrix** (in the mirror, free Actions): linux-x64 + windows-x64 native binaries; checkout private source via token (maintainer-triggered only); draft-release + mac-local upload. Decide Linux mostly-static then.
