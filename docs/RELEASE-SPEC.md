# Release spec — `dmtool` CLI + rulekit developer library

The **durable** contract for releasing this project: the surfaces we ship, how each artifact is built, the release runbook, the verification gate, versioning, and the distribution/trust posture. This spec **survives and evolves** with every release. (Its transient companion — the per-release execution plan with iteration order, status, and one-time migrations — lives separately and is deleted when a release is done.)

**References the fixed contracts, never duplicates them:** [`CLI-SPEC.md`](CLI-SPEC.md) §10–11 (sizing, packaging phases B1/B2, licensing), [`EVAL-SPEC.md`](EVAL-SPEC.md) §2a (the eval's material boundary + JVM-launcher policy), [`API-POC-STUDY.md`](API-POC-STUDY.md) (the library/DSL surface), [`LICENSING.md`](LICENSING.md) (MIT source / EUPL distributable).

## 1. What we ship

**v1 ships ONE surface: the `dmtool` CLI native binary.** The developer-library surface (JARs, JavaDoc, the LLM API doc, a dev skill) is **deferred — not v1**; it is recorded for later in §11. Focus is the binary.

| Surface | Audience | v1 artifacts | Channel |
|---|---|---|---|
| **`dmtool` CLI** | BAs + developers; **#1 use is inside a coding-agent session** | native binary per OS + `SHA256SUMS` + **agent plugins (Claude Code + Codex)** | binaries on the public mirror Release (§10); the Claude Code + Codex plugins (§6); later brew/scoop, `curl \| sh` |
| ~~rulekit library~~ | Java developers | **deferred → §11** | — |

The **command name is `dmtool`** everywhere it is publicly invoked. `:cli`'s `installDist` launcher (`applicationName = 'dmtool'`) and the native binary share that name; the eval exposes the JVM launcher as `dmtool` on PATH too (so the skill is one verbatim artifact — §6).

## 2. Versioning & naming — SemVer + compatibility metadata

**Scheme: SemVer** (`MAJOR.MINOR.PATCH`, tag `vMAJOR.MINOR.PATCH`). The project version is the project's **own** release identity; the upstream kernel is recorded as **compatibility metadata**, never folded into the version string. (CalVer-keyed-to-upstream was considered and rejected — it re-collapses the two identities and erases the library's break-signal; full rationale in the plan's D9, summarized in §13.)

- **0.x** while the public library API stabilizes; **`1.0.0`** when it does. MAJOR = breaking API change, MINOR = additive, PATCH = fixes.
- **The compatibility key is the kernel semver** (currently `30.8.1`; the A12 Tools distribution label `2025.06-ext5` is a secondary human tag). It is already encoded where consumers look:
  - CLI → baked into `version.properties` as `kernelBuiltAgainst`, surfaced by `--version` / `manifest.version`.
  - library → the published **POM** declares `com.mgmtp.a12.kernel:kernel-md-facade:30.8.1`.
  - → therefore **no separate `project-release.yaml`** (a third, drift-prone source), and **no `release/YYYY.MM` maintenance branches** (premature, and against the no-self-branching rule).
- **Tag-format gate** (CI, lands with the CI matrix): `^v[0-9]+\.[0-9]+\.[0-9]+(-(alpha|beta|rc)\.[0-9]+)?$`.
- **Release title carries compatibility:** `vX.Y.Z — kernel <kver> (A12 Tools <quarter>)` — e.g. `v0.1.0 — kernel 30.8.1 (A12 Tools 2025.06-ext5)`.
- **Release notes** include a **Compatibility** section (upstream kernel, supported range, API-compat note) plus Changes / Migration / Artifacts.
- **Pre-releases** use `-rc.N` / `-beta.N` suffixes; drop the suffix when stable.

## 3. Build procedures

### 3a. CLI native image (per OS)
- **Per-OS, no cross-compile:** native-image emits a binary for the OS+arch it runs on. macOS arm64 is built **locally** (cost: avoids the 10× CI multiplier; signing: local cert); linux-x64 / windows-x64 on CI runners (§4). Linux portability: prefer a mostly-static binary (`-H:+StaticExecutableWithDynamicLibC` or musl).
- **v1 ships native-only** (decision — [`NATIVE-IMAGE-GRAALVM.md`](NATIVE-IMAGE-GRAALVM.md) §7). The shipped CLI is the GraalVM 25 native binary (~31 MB, ~37 ms start, reflection-robust, native gate GREEN). The **runtime-eval verbs** (`model eval`/`rule test`/`model compute`) need Groovy → not native-compilable → **hidden from the shipped binary**; they remain the JVM dev/eval vehicle only, not a shipped artifact.
- **The full build recipe, findings, sizing, the hard ceiling, and the verb-hiding gate live in [`NATIVE-IMAGE-GRAALVM.md`](NATIVE-IMAGE-GRAALVM.md)** — native-image is a topic of its own.

### 3b. Developer library — DEFERRED (not v1)
The library jars/JavaDoc are out of v1 scope; the full build + consume + licensing record lives in **§11**.

## 4. Release runbook

```
git tag vX.Y.Z ──► (CI, when present)
                     ├─ job linux-x64   → nativeCompile → verify (§5) → upload
                     ├─ job windows-x64 → nativeCompile → verify (§5) → upload
                     └─ job release      → gather + SHA256SUMS → create DRAFT release
                                                                          │
   locally (mac):  nativeCompile → verify (§5) → gh release upload  ──────┤
   locally:        :cli/library jars (3b) ──────────────────────────────► (same Release)
                                                                          │
                                              review the draft ──► publish (--latest)
```

- A **draft** release is the seam for the local-mac step: CI assembles linux+windows into a draft; the maintainer adds the locally-built mac binary (+ its checksum) and the library jars, reviews, then publishes. A failed mac build never leaves a half-published release.
- **`SHA256SUMS`** covers every binary + jar — the integrity story that substitutes for code-signing (§7).
- Tag command form: `git tag -a "vX.Y.Z" -m "…"; gh release create "vX.Y.Z" --title "vX.Y.Z — kernel <kver> (A12 Tools <quarter>)" --notes-file … --latest`.
- **Changelog:** [`plugin/CHANGELOG.md`](../plugin/CHANGELOG.md) (ships to the mirror root) is the public record of the released artifacts. Before tagging, flip the new version's `— unreleased` to the date and confirm the entries; that section doubles as the release `--notes`. It tracks only the public artifact (the CLI + plugins), never internal development.

**CI runs in the PUBLIC mirror repo — free Actions (incl. the 10× macOS / 2× Windows multipliers), no Team plan needed.** The mirror's build workflow **checks out the private source via a read-token secret**, bootstraps the kernel, builds linux/windows native, and publishes to the mirror's Releases. **Guardrail:** the workflow is **maintainer-triggered only** (`workflow_dispatch` / tag / `repository_dispatch` from the private repo) — **never `pull_request`** — so the private-source token is never exposed to a fork PR. The private source only lands transiently on the ephemeral runner; only the binaries become public. (mac stays local; on a Team plan linux+windows are already ~free within the 3000 included minutes, so this mainly buys "no Team needed" + headroom. Iteration-3 concern — no CI until the matrix lands.)

## 5. The verification gate — native↔JVM byte-identity

Every freshly built **native** binary is re-verified against the `examples/` showboat demos before it ships:

```
DMTOOL_CLI="$PWD/<native-binary>" uvx showboat@0.6.1 verify examples/cli-*.md
```

This is **not optional polish** — it is the bridge that *licenses the eval to run on the JVM launcher only* (the eval never builds native; [`EVAL-SPEC.md`](EVAL-SPEC.md) §2a). The chain:

> eval measures JVM `dmtool` → this gate proves native `dmtool` == JVM `dmtool` (byte-for-byte) → the N6 self-sufficiency number transfers to the shipped artifact.

showboat compares output byte-for-byte, so a closed-world resource drop (§3a) makes a demo diverge and the gate fails — on the exact OS where it would have hurt a user. Windows note: native stdout may be CRLF; normalize (`sed 's/\r$//'`) or scope Windows to hand-picked invocations if the full suite won't match.

## 6. Agent distribution — the plugins (Claude Code + Codex, both v1)

The CLI is **LLM-agent-first**; its primary delivery is an **agent plugin** that bundles the skill *and* puts the binary on PATH. **v1 ships two plugins — Claude Code and OpenAI Codex** — because the two ecosystems converged on a near-identical model (a `SKILL.md` of the same format, a `SessionStart` hook, a plugin manifest + marketplace). **Plugin source lives in this repo** — [`plugin/`](../plugin/) (Claude Code) and [`plugin-codex/`](../plugin-codex/) (Codex) — single source of truth, locally testable. The **public `dmtool-releases` repo is a release-only push target**.

- **Vehicle = plugin** (not a bare skill — can't deliver a binary; not MCP — wrong model for a local offline CLI, and it would undercut the self-describing-CLI thesis). This holds for both agents.
- **Single-source shared assets, COPY-synced** (the proven cross-agent pattern — symlinks don't survive the mirror push, and the two `hooks.json` genuinely differ). Two assets are shared **verbatim** by both plugins:
  - `skill/SKILL.md` — canonical (also the eval's material, [`EVAL-SPEC.md`](EVAL-SPEC.md) §2a, exact path baked into its boundary audit);
  - `scripts/ensure-dmtool.sh` — the **agent-agnostic** binary downloader (reads `CLAUDE_*` or `PLUGIN_*`/`CODEX_ENV_FILE`, so one script serves both hosts).

  `scripts/sync-plugin-assets.sh` copies both into every plugin tree (`plugin/`, `plugin-codex/`); a **CI freshness check** (`--check`) fails on any drift. Never hand-edit a plugin copy; add a plugin to the script's `PLUGINS` list when a new agent is supported.
- **Per-agent files (the irreducible delta), maintained in place:** each plugin's manifest (`.claude-plugin/plugin.json` + `marketplace.json` · `.codex-plugin/plugin.json` + a git-subdir `marketplace.json`), its `hooks/hooks.json` (different formats), and Codex's `AGENTS.md` (a usage/context template).
- **Binary delivery = a `SessionStart` hook** (`ensure-dmtool.sh`): downloads the per-OS binary from the mirror Release, checksum-verifies it, caches it (`CLAUDE_PLUGIN_DATA`/`PLUGIN_DATA`), and injects PATH via the host env-file (`CLAUDE_ENV_FILE`/`CODEX_ENV_FILE`). Fetches via `curl` → no quarantine/MotW (§7). If a host can't inject PATH, the binary is at a deterministic `$PLUGIN_DATA/bin/dmtool` (documented in `AGENTS.md`). **Dev fallback** (verified, both agents): `RK_DMTOOL_LOCAL` or a sibling source-repo native build is symlinked — so a plugin is testable from a checkout before any release exists. Never hard-fails the session.
- **Mirror layout (assembled by `scripts/assemble-release-repo.sh`):** the Claude plugin at the **mirror root** (`.claude-plugin/`, `hooks/`, `skills/`); the Codex plugin under **`codex/`**; the Codex marketplace manifest at `.agents/plugins/marketplace.json` (git-subdir → `./codex`). Both alongside the binaries (Releases) + the shared EUPL bundle + the public `README.md` (covers both install paths + per-artifact licensing + disclaimer).
- **Install:** `/plugin marketplace add mbackschat/dmtool-releases` + `/plugin install dmtool` (Claude Code) · `codex plugin marketplace add mbackschat/dmtool-releases` + `codex plugin add dmtool` (Codex). **Verified against Codex 0.139.0** — the marketplace manifest parses, the plugin resolves via the git-subdir source, and a local-source install bundles the manifest + hooks + skill (installed/enabled).
- **Verified against Codex 0.139.0** (the installed CLI): the marketplace manifest parses, the `dmtool` plugin resolves via its git-subdir source, and a **local-source install** bundles the manifest + `hooks.json` + the hook script + the skill + `AGENTS.md` (status *installed, enabled*). This caught two doc-vs-reality fixes now applied: the install verb is `codex plugin add` (not `install`), and the marketplace entry's `policy` block is **required** (`authentication` ∈ `ON_INSTALL`|`ON_USE`, no `NONE`).
- *Still to verify in real agent sessions* (can't be checked headless): on **each host**, that `SessionStart` actually fires and **PATH injection** lands (Codex's exact env-file var name for PATH is unconfirmed — the deterministic `$PLUGIN_DATA/bin/dmtool` fallback in `AGENTS.md` covers it regardless); and the Claude `/plugin install` flow. Hook *logic* is unit-verified under both Claude and Codex env-var profiles (resolves the build, writes the PATH export, runs the verb-hidden binary). Before "release-good": run the production-mode benchmark once for a measured skill lift (currently only n=1 probes).

## 7. Distribution & trust

`dmtool` is a **CLI run from a shell**, not a double-clicked GUI app — so "double-click works" is the wrong target. **No code-signing for v1.** The audience is terminal-capable, and **how the binary arrives decides whether the OS guards even fire:**

| Arrival path | macOS Gatekeeper | Windows SmartScreen |
|---|---|---|
| **browser** download of zip/dmg | fires (quarantine xattr) | fires (Mark-of-the-Web) |
| **terminal** `curl`/pkg-manager/**plugin hook** | does **not** fire | does **not** fire |

So the primary (plugin/terminal) paths are clean; only a browser download trips a guard, covered by a three-line README escape hatch (mac `xattr -d com.apple.quarantine`, Windows "More info → Run anyway", linux `chmod +x`). `SHA256SUMS` is the integrity guarantee. Revisit signing only on real friction reports. Licensing: source **MIT**; the kernel-embedding CLI distributable ships **EUPL-1.2** ([`LICENSING.md`](LICENSING.md)); the library jar stays MIT (kernel is a separate dependency jar).

## 8. Library consume recipe — DEFERRED (not v1)

The consume recipe (bootstrap kernel → install jar with POM → declare the dependency; Maven Central is structurally blocked by the un-published kernel dep) is recorded with the rest of the deferred developer wave in **§11**.

A static Maven repo (one-line `maven { url }` consume) is a later upgrade; it does not remove step 1, so its v1 marginal value is small.

## 9. Removing a release

**Supersede, don't delete** — ship `vX.Y.(Z+1)` and roll any package-manager pointers forward; reserve hard `gh release delete` for a leaked secret or a broken artifact. Everything lives in repos the owner controls (no Central gatekeeper), so removal is easy — but **deleting an asset a brew formula / scoop manifest points at breaks installs**, so always **update the pointer before deleting the asset**. Removal stops *new* installs only; it cannot recall copies already on machines.

## 10. Distribution topology & channels

**Source repo PRIVATE (early testers) + a separate PUBLIC release-mirror repo as the single public distribution point.** The release step publishes every downloadable artifact to the mirror's GitHub Releases (and its Pages), so anonymous consumers and agents fetch from the mirror while the source stays private. Going public later retires the mirror (releases move to the source repo). The mirror holds:

- the **CLI native binaries** per-OS + `SHA256SUMS`;
- the **developer JARs** — `rulekit-X.Y.Z.jar` + `-sources.jar` + `-javadoc.jar` + POM (§3b);
- the **JavaDoc HTML** (browsable) on the mirror's **GitHub Pages**;
- an **LLM-facing API doc** (`llms.txt`-style flat API reference) for coding agents — planned (§11).

| Channel | Status | Notes |
|---|---|---|
| CLI binaries on the mirror Release | **v1** | anonymous `curl` (public mirror) — no auth, no quarantine/MotW |
| developer jars + POM on the mirror Release | **v1** | + `publishToMavenLocal` for the maintainer/siblings; consume = install-with-POM (§8) |
| **agent plugins** (Claude Code + Codex; skill + hook) | **v1** | both push to the mirror (Claude at root, Codex under `codex/`); the `SessionStart` hook plain-`curl`s the CLI binary from the mirror (§6) |
| **JavaDoc HTML** + **LLM API doc** on the mirror Pages | planned | public URLs for human + agent API consumption (§11) |
| **Homebrew** formula / **Scoop** manifest | planned | point at the public mirror assets — now unblocked (anonymous) |
| `curl \| sh` installer | planned | fetches from the mirror |

**Licensing on the mirror (per [`LICENSING.md`](LICENSING.md) / CLI-SPEC §11) — mixed, per-artifact:**
- **CLI native binaries → EUPL-1.2.** They embed the kernel, so each binary/release carries the kernel **LICENSE/NOTICE/THIRD_PARTY** + a source pointer (and **no "mgm A12" branding** — the `dmtool` name respects that).
- **Developer JARs → MIT.** `rulekit-X.Y.Z.jar` does **not** embed the kernel (it's a separate `api` dependency the consumer bootstraps), so rulekit's own jar + sources + javadoc stay MIT.
- The mirror repo's README states the per-artifact licensing explicitly so a downloader isn't confused by the mix.

## 11. Developer surface — DEFERRED (recorded for later, NOT v1)

**Out of scope for v1** (v1 = the CLI native binary, §1). This section is the durable record of the whole **developer-library wave** so it isn't lost when the transient release *plan* is deleted. The agent-first thesis extends from the CLI to the **library** — just as the CLI pairs a *self-describing surface* (`manifest`/`schema`/`--help`) with a *judgment skill*, the Java API would pair an **agent-readable API reference** with a **developer skill**. All would publish on the public mirror.

1. **Library JARs** — `java-library` + `maven-publish`; `java { withJavadocJar() }` → consumer `rulekit-X.Y.Z.jar` + `-javadoc.jar` + a correct POM (`from components.java`). **No `sources.jar` while the source repo is private** (it would expose the private source on the public mirror; javadoc is fine — it's the public API, not impl). Root `:` jar only (rulekit + modelkit); not `:adapter`. **MIT** (doesn't embed the kernel). Outputs: `publishToMavenLocal` + the jar/javadoc/POM on the mirror. **Consume:** download from the mirror → bootstrap the kernel into `~/.m2` → install jar *with its POM* → `implementation 'io.github.mbackschat.a12.dm:rulekit:X.Y.Z'`. (Maven Central is structurally blocked — the kernel `api` dep isn't on Central.)
2. **JavaDoc** — the `-javadoc.jar` (downloadable) **and** browsable **HTML on the mirror's GitHub Pages** (a stable public URL).
3. **LLM API doc (`llms.txt`-style)** — a flat, single-file API reference for coding agents (public types/signatures + typed-builder idioms + DSL entry points + short usage), distinct from the human JavaDoc. **Must be derived/CI-locked** against the real API (the no-drift discipline — assembled from the CI-locked typed-builder snippets + signatures), not hand-maintained. Public URL an agent can fetch.
4. **Developer skill** — the library-persona analog of [`skill/SKILL.md`](../skill/SKILL.md): teaches *judgment* for authoring via the typed builder / `EditSession` / DSL (polarity, paths, iteration — same kernel semantics), **not** the API catalog (JavaDoc + `llms.txt`'s job — keep it minimal, mirroring "self-describing surface > skill"). Complements the EVAL-SPEC §2 "Developer path."

## 12. Multi-agent packaging — Claude Code + Codex shipped (v1); Gemini next

The tool is **not Claude-specific**. The CLI is a self-describing binary any coding agent can shell out to, and the judgment layer is plain markdown — so portability is mostly **reuse**, with a thin per-agent wrapper as the only real delta. **v1 ships Claude Code and Codex** (§6); **Gemini is the next candidate**, recorded here.

**Reused as-is (zero per-agent work):**
- **The binary** — `dmtool` is agent-agnostic; the §6 self-describing surface (`manifest` / `schema` / `--help` / diagnostics) is exactly what lets *any* cold agent discover the verbs and their I/O without host-specific glue. No changes.
- **The judgment content + the downloader** — [`skill/SKILL.md`](../skill/SKILL.md) and `scripts/ensure-dmtool.sh` are canonical and single-source; the §6 copy-sync (`sync-plugin-assets.sh --check`) materializes them into each plugin. Adding an agent = a new row in that script's `PLUGINS` list + the per-agent wrapper files.

**The per-agent delta — a thin wrapper:**

| Agent | Distribution vehicle | Context file | Binary delivery | Status |
|---|---|---|---|---|
| **Claude Code** | plugin ([`plugin/`](../plugin/)) | the bundled skill | `SessionStart` hook → mirror (§6) | **v1** |
| **OpenAI Codex** | plugin ([`plugin-codex/`](../plugin-codex/), git-subdir marketplace) | the skill + `AGENTS.md` template | `SessionStart` hook → mirror (§6) | **v1** |
| **Gemini** | a Gemini CLI extension | `GEMINI.md` | same mirror binary, same hook pattern | next |

- **The Claude↔Codex convergence made Codex near-free:** same `SKILL.md` format, same `SessionStart`/plugin/marketplace model — the delta was one manifest, one `hooks.json`, an `AGENTS.md`, and generalizing the downloader's env-var reads. Gemini is expected to be similar (extension + `GEMINI.md`).
- **MCP is the write-once alternative** — one MCP server reachable by all agents instead of N native wrappers. Rejected as the *primary* path (same reasoning as §6's vehicle choice): it adds a running process and undercuts the offline self-describing-CLI thesis. Keep it on the table only if maintaining several native wrappers becomes the actual friction.

## 13. Rationale (the durable *why*)

- **Native over jlink/jpackage** — single self-contained binary, ~16× faster start, no JVM to provision; the closed-world risk is bounded by §5's verification gate and the §3a hard ceiling.
- **CI matrix, mac built locally** — native-image can't cross-compile (so "all OSes at once" = parallel CI jobs); macOS bills at a 10× multiplier (the only expensive line) and the signing cert lives locally, so mac stays off CI.
- **No signing at v1** — the audience is terminal-capable and the primary delivery paths (plugin hook, package managers, `curl`) don't trip the OS guards at all; checksums + an escape hatch cover the rest. Signing is pure cost until friction is observed.
- **Local-only library, SemVer** — Central is structurally blocked by the un-published kernel dependency; SemVer (not CalVer) keeps the project's release identity independent of the upstream calendar and preserves the API break-signal library consumers need.
- **Plugin, single-source skill** — one `/plugin install` gives an agent both `dmtool` and the judgment layer; keeping the skill identical to the eval's material is what keeps the N6 number honest.
