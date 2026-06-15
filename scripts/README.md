# `scripts/` — release & build tooling

Maintainer scripts for building, packaging, and releasing `dmtool`. None are needed for the everyday dev inner loop (that's `./gradlew test` — see the top-level [`README.md`](../README.md)); these drive the **native image**, the **agent plugins**, and the **public release mirror**.

| Script | What it does | When to run |
|---|---|---|
| [`sync-plugin-assets.sh`](sync-plugin-assets.sh) | Copy the **single-source** plugin assets — `skill/SKILL.md` and `ensure-dmtool.sh` — into every agent plugin tree (`plugin/`, `plugin-codex/`). `--check` verifies the copies match and fails on drift (CI guard). | After editing the canonical skill or downloader; `--check` runs in CI. |
| [`ensure-dmtool.sh`](ensure-dmtool.sh) | **Canonical** `SessionStart` hook (agent-agnostic): downloads the per-OS native binary from the release mirror, checksum-verifies it, caches it, and puts `dmtool` on PATH. Never runs from here — it is synced into each plugin's `hooks/` and runs there. | Don't run directly; edit here, then `sync-plugin-assets.sh`. |
| [`capture-native-config.sh`](capture-native-config.sh) | Drive the GraalVM tracing agent over all fixtures + operators + demos to (re)capture the reflection metadata the native build needs. | On a verb/catalog/kernel/Jackson change — see [`../docs/NATIVE-IMAGE-GRAALVM.md`](../docs/NATIVE-IMAGE-GRAALVM.md) §5.1. |
| [`native-gate.sh`](native-gate.sh) | Verify the built native binary against the showboat demos (the two-gate verification); skips the JVM-only demos with reasons. | Before shipping a native binary. |
| [`assemble-release-repo.sh`](assemble-release-repo.sh) | Assemble the public `a12-dmtool-releases` tree: both plugins (Claude at root, Codex under `codex/`) + the EUPL-1.2 compliance bundle copied fresh from the pinned kernel checkout. | At release, and on a kernel bump (notices change). |

## The single-source pattern

The skill and the binary-downloader are shared **verbatim** by both agent plugins, so they live in exactly one place and are **copy-synced** into each plugin (symlinks don't survive the release-mirror push, and the two `hooks.json` genuinely differ per agent). The per-agent files — each plugin's manifest, `hooks.json`, and Codex's `AGENTS.md` — are maintained in place, never synced. `sync-plugin-assets.sh --check` is the no-drift guard; add a new plugin tree to its `PLUGINS` list when another agent is supported.

Rationale and the full release procedure: [`../docs/RELEASE-SPEC.md`](../docs/RELEASE-SPEC.md). Native-image specifics: [`../docs/NATIVE-IMAGE-GRAALVM.md`](../docs/NATIVE-IMAGE-GRAALVM.md).
