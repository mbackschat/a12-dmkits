# Release PLAN — v1 execution checklist (transient)

**Transient. Delete this file once v1 ships.** It tracks only *what's left to ship v1*. Everything durable — the surfaces, build recipe, runbook, versioning, distribution topology, licensing, the deferred developer wave, and all rationale — lives in **[`RELEASE-SPEC.md`](RELEASE-SPEC.md)** (and native specifics in **[`NATIVE-IMAGE-GRAALVM.md`](NATIVE-IMAGE-GRAALVM.md)**). Decisions are made; this is just the to-do.

## v1 scope (decided)

**Ship the `dmtool` native CLI binary only.** Native-only (GraalVM 25); macOS arm64 first, built locally. Source repo stays **private** (early testers); a separate **public release-mirror repo** is the distribution point. The whole developer-library wave (JARs/JavaDoc/`llms.txt`/dev skill) is **deferred** → RELEASE-SPEC §11.

## Done

- ✅ `cli`→`dmtool` rename (launcher, skill, eval, shim) — committed, verified.
- ✅ Native build infra: GraalVM 25 plugin, `-Os`, native-only Groovy exclusion, comprehensive reflection capture (`scripts/capture-native-config.sh`), the verb-hiding gate (`Cli.JVM_ONLY_VERBS` + `NativeImageVerbHidingTest`), the native gate (`scripts/native-gate.sh`) — committed, native gate GREEN (5/6 + 1 skip).
- ✅ All release decisions (RELEASE-SPEC + its §13 rationale).

## Remaining steps — iteration 1 (local mac release)

1. [ ] **Version:** bump `0.1.0-SNAPSHOT` → `0.1.0` (root `build.gradle`).
2. [ ] **Build:** mac arm64 native binary (GraalVM 25, `GRAALVM_HOME` set) → name it `dmtool-macos-arm64`; generate `SHA256SUMS`. Run `scripts/native-gate.sh` → GREEN.
3. [ ] **Public mirror repo:** create it (decide the name) — it will hold the downloadable binaries (EUPL-1.2 + kernel LICENSE/NOTICE/THIRD_PARTY + source pointer; README states per-artifact licensing). *(Outward action — confirm name before creating.)*
4. [ ] **Release:** tag `v0.1.0` on this (source) repo; publish the mac binary + `SHA256SUMS` to the **mirror's** Release `v0.1.0` (title `v0.1.0 — kernel 30.8.1 (A12 Tools 2025.06-ext5)`; notes with a Compatibility section — RELEASE-SPEC §2).
5. [ ] **README:** add an "Install (macOS)" section — download, `xattr -d com.apple.quarantine`, `chmod +x`, `sha256sum -c`.

## Later iterations

- **Iteration 2 — CI matrix** (in the public mirror, free Actions): linux-x64 + windows-x64 native binaries; checkout private source via token (maintainer-triggered only); draft-release + mac-local upload. Decide Linux mostly-static then.
- **Iteration 3 — agent plugins (Claude Code + Codex): SCAFFOLDED** in [`plugin/`](../plugin/) + [`plugin-codex/`](../plugin-codex/) (per-agent manifest + marketplace + `SessionStart` hook; shared skill + agent-agnostic downloader copy-synced by `scripts/sync-plugin-assets.sh`, CI freshness-locked). Hook *logic* verified locally under both Claude and Codex env profiles. **Remaining:** test in a real session each (`claude --plugin-dir ./plugin`; `codex plugin marketplace add` + install) — SessionStart fires, PATH injection, install flow; then at release, `assemble-release-repo.sh` pushes both to the mirror (Claude at root, Codex under `codex/`). Detail: RELEASE-SPEC §6 / §12.

## Still-open (resolve in-flight)

- The **public mirror repo name** (step 3).
- **Signing:** none for v1 (escape hatch in the README); revisit on friction.
- **Going public later** retires the mirror (releases move to this repo).
