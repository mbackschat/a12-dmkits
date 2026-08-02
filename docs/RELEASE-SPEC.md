# Release spec — `dmtool` CLI + the dmkits developer library (core / rulekit / modelkit / edit / schemakit)

The **durable** contract for releasing this project: the surfaces we ship, how each artifact is built, the release runbook, the verification gate, versioning, and the distribution/trust posture. This spec **survives and evolves** with every release. (Its transient companion — the per-release execution plan with iteration order, status, and one-time migrations — lives separately and is deleted when a release is done.)

**References the fixed contracts, never duplicates them:** [`CLI-SPEC.md`](CLI-SPEC.md) §10–11 (sizing, packaging phases B1/B2, licensing), [`EVAL-SPEC.md`](EVAL-SPEC.md) §2a (the eval's material boundary + JVM-launcher policy), [`API-GUIDE.md`](API-GUIDE.md) (the library/DSL surface), [`LICENSING.md`](LICENSING.md) (MIT source / EUPL distributable).

## 1. What we ship

**dmtool v1 ships ONE surface: the `dmtool` CLI native binary.** The dmkit developer-library surface (JARs, JavaDoc, the LLM API doc, a dev skill) is **deferred — not dmtool v1**; it is recorded for later in §11. The separately published, root-version-aligned interpreter is governed by [INTERPRETER-RELEASE-SPEC](INTERPRETER-RELEASE-SPEC.md).

| Surface | Audience | v1 artifacts | Channel |
|---|---|---|---|
| **`dmtool` CLI** | BAs + developers; **#1 use is inside a coding-agent session** | native binary per OS + `SHA256SUMS` + **agent plugins (Claude Code + Codex)** | binaries on the public mirror Release (§10); the Claude Code + Codex plugins (§6); later brew/scoop, `curl \| sh` |
| ~~dmkits library (five kernel-free kits + `dmkits` aggregator)~~ | Java developers | publish **wiring landed** (Stage 4); the Central **deploy** is **deferred → §11** | — |

The **command name is `dmtool`** everywhere it is publicly invoked. `:cli`'s `installDist` launcher (`applicationName = 'dmtool'`) and the native binary share that name; the eval exposes the JVM launcher as `dmtool` on PATH too (so the skill is one verbatim artifact — §6).

## 2. Versioning & naming — SemVer + compatibility metadata

**Scheme: SemVer** (`MAJOR.MINOR.PATCH`, tag `vMAJOR.MINOR.PATCH`). The project version is the project's **own** release identity; the upstream kernel is recorded as **compatibility metadata**, never folded into the version string. (CalVer-keyed-to-upstream was considered and rejected — it re-collapses the two identities and erases the library's break-signal; full rationale in the plan's D9, summarized in §13.)

- **0.x** while the public library API stabilizes; **`1.0.0`** when it does. MAJOR = breaking API change, MINOR = additive, PATCH = fixes.
- **The compatibility key is the kernel semver** (currently `30.8.1`; the A12 Tools distribution label `2025.06-ext5` is a secondary human tag). It is already encoded where consumers look:
  - CLI → baked into `version.properties` as `kernelBuiltAgainst`, surfaced by `--version` / `manifest.version`.
  - library → the kits are **kernel-free** (decoupling Stage 3), so their published POMs declare **no** kernel dependency; the kernel-compat key rides the CLI surface + the release title/notes, not a library POM coordinate.
  - → therefore **no separate `project-release.yaml`** (a third, drift-prone source), and **no `release/YYYY.MM` maintenance branches** (premature, and against the no-self-branching rule).
- **Tag-format gate** (CI, lands with the CI matrix): `^v[0-9]+\.[0-9]+\.[0-9]+(-(alpha|beta|rc)\.[0-9]+)?$`.
- **Release title carries compatibility:** `vX.Y.Z — kernel <kver> (A12 Tools <quarter>)` — e.g. `v0.1.0 — kernel 30.8.1 (A12 Tools 2025.06-ext5)`.
- **Release notes** include a **Compatibility** section (upstream kernel, supported range, API-compat note) plus Changes / Migration / Artifacts.
- **Pre-releases** use `-rc.N` / `-beta.N` suffixes; drop the suffix when stable.

## 3. Build procedures

### 3a. CLI native image (per OS)
- **Per-OS, no cross-compile:** native-image emits a binary for the OS+arch it runs on. macOS arm64 is built **locally** (cost: avoids the 10× CI multiplier; signing: local cert); linux-x64 / linux-arm64 / windows-x64 build on CI runners (§4). Linux portability: prefer a mostly-static binary (`-H:+StaticExecutableWithDynamicLibC` or musl).
- **v1 ships native-only** (decision — [`NATIVE-IMAGE-GRAALVM.md`](NATIVE-IMAGE-GRAALVM.md) §7). The shipped CLI is the GraalVM 25 native binary (~31 MB, ~37 ms start, reflection-robust, native gate GREEN). The **runtime-eval verbs** (`model eval`/`rule eval`/`model compute`) run on the kernel-free interpreter and are part of the shipped native surface; the bundled kernel remains the consistency oracle for authoring and check operations.
- **Native means Java-independent at execution.** [`native-no-java-smoke.sh`](../scripts/native-no-java-smoke.sh) runs the exact release artifact with an invalid `JAVA_HOME` and a `PATH` containing no `java`, requires its exact repository version, and carries a Java-launcher positive control. `native-gate.sh` runs it on macOS/Linux and the mirror workflow runs it on Windows.
- **The full build recipe, findings, sizing, Groovy exclusion, and full-verb native gate live in [`NATIVE-IMAGE-GRAALVM.md`](NATIVE-IMAGE-GRAALVM.md)** — native-image is a topic of its own.

### 3b. Developer library — DEFERRED (not v1)
The library jars/JavaDoc are out of v1 scope; the full build + consume + licensing record lives in **§11**.

## 4. Release runbook

**The publish glue is a committed script — [`scripts/publish-release.sh`](../scripts/publish-release.sh) — not prose** (the CLAUDE.md rule: deterministic side-effecting setup lives in a script). It does the mirror-content push, the draft + mac upload, the Linux/Windows CI dispatch, the `SHA256SUMS` ordering, and the final flip. Two manual things bracket it: the **version bump** (maintainer judgment, below) before tagging, and a **review of the draft** before `--go-live`.

**Published-release immutability policy:** after `--go-live`, the mirror's GitHub Release is immutable by project policy, even if GitHub technically allows asset edits. Adding a new platform, replacing a binary, changing `SHA256SUMS`, or changing the public workflow for that release requires a new patch release. Draft releases may be refreshed before publication; published releases are superseded, not mutated. `publish-release.sh` enforces this by refusing to stage over a published release and refusing `--go-live` unless the release is still a draft.

**Step 1 — version bump (before tagging).** Run **[`scripts/bump-version.sh X.Y.Z`](../scripts/bump-version.sh)** — it does the deterministic edits in the table below (the `build.gradle` version, both plugin manifests, the `cli-version.md` capture, and the plugin's **binary-download pin**), leaving only the CHANGELOG *content* and the build/verify/commit/tag to you. Most of these are caught by **no** guard (`VersionMentionFreshnessTest` guards only the *kernel* pin), so a missed file drifts the project version silently — which is exactly why the edits are scripted. The one exception is the download pin, now guarded by **`PluginInstallerVersionTest`**:

| File | What |
|---|---|
| `build.gradle` | `version = 'X.Y.Z'` — source of truth; baked into `--version` / `manifest.version` |
| `plugin/.claude-plugin/plugin.json` | `"version": "X.Y.Z"` |
| `plugin-codex/.codex-plugin/plugin.json` | `"version": "X.Y.Z"` |
| `examples/cli-version.md` | regen the captured `--version` + `manifest.version` (showboat-gated — drifts the demo otherwise) |
| `scripts/ensure-dmtool.sh` → both plugin copies | `VERSION="vX.Y.Z"` — the **release tag the on-demand installer downloads the binary from**. Set unconditionally, then propagated to both plugins' `skills/a12-dmtool/` by `sync-plugin-assets.sh`. **Guarded by `PluginInstallerVersionTest`** (it must equal the project version) — the pin lagging at `v0.1.0` shipped a long-fixed native crash to every install through v0.3.1, the incident this guard prevents. |
| `plugin/CHANGELOG.md` | flip `[Unreleased]` → `[X.Y.Z] — kernel <pin>`; **this section is the release `--notes`** (the script extracts it) |

Then rebuild + verify the demo, commit, tag, push:
```
./gradlew :cli:installDist && export PATH="$PWD/bin:$PATH" && uvx showboat@0.6.1 verify examples/cli-version.md
git commit -am "chore(release): vX.Y.Z — kernel <pin> (A12 Tools <quarter>)"
git tag -a vX.Y.Z -m "vX.Y.Z — kernel <pin> (A12 Tools <quarter>)" && git push origin main vX.Y.Z
```

**Step 2 — stage the release** (`./scripts/publish-release.sh vX.Y.Z`), which runs:
```
build mac native (§3a) + native-gate.sh (§5)
  └─ assemble-release-repo.sh ──► rsync --delete into a fresh clone of the mirror ──► commit + push   (mirror CONTENT)
       └─ gh release create --draft  ──► upload mac binary (dmtool-macos-arm64)                        (BEFORE the dispatch ↓)
            └─ gh workflow run build-native (source_ref+release_tag) ─► Linux/Windows nativeCompile+verify,
                 upload dmtool-linux-x64 / dmtool-linux-arm64 / dmtool-windows-x64.exe, then re-sum EVERY dmtool-* into SHA256SUMS
```
- **Ordering that bites (why the script enforces it):** the CI `publish` job re-sums *every* `dmtool-*` already attached to the release, so the **mac binary must be uploaded before the dispatch** — upload it late and `SHA256SUMS` silently won't cover it. (The earlier "CI creates the draft" diagram was wrong: the workflow only *uploads to* an existing release; the script creates the draft.)
- **Mirror CONTENT vs. Release ASSETS are two pushes:** the plugin/skill/examples/README/CHANGELOG go into the mirror *repo tree* (rsync `--delete`, so the tree must equal the assembled output exactly); the per-OS binaries + `SHA256SUMS` go to the mirror *GitHub Release*. The CI workflow ships to the mirror from `.github/mirror-workflows/build-native.yml` (assembled into the tree — §4 below).
- **Mirror commit identity is publisher-owned:** the fresh clone is configured through [`scripts/release-git-identity.sh`](../scripts/release-git-identity.sh) before every commit, so public history uses `Martin Backschat <mbackschat@mail.de>` independently of the release machine's global Git configuration. The interpreter mirror publisher reuses the same boundary.

**Step 3 — review the draft, then go live** (`./scripts/publish-release.sh vX.Y.Z --go-live`): verifies all four binaries + `SHA256SUMS` are present and that the checksums match, then `gh release edit --draft=false --latest`. A failed CI build never leaves a half-published release (it stays a draft).

- **`SHA256SUMS`** covers every binary — the integrity story that substitutes for code-signing (§7).
- **Asset names and platform mapping are single-sourced in [`scripts/release-assets.sh`](../scripts/release-assets.sh).** Most scripts source it directly; the static mirror workflow matrix is guarded by [`scripts/check-release-assets.sh`](../scripts/check-release-assets.sh) / `ReleaseAssetsManifestTest`, so adding a platform means updating the manifest + matrix together or CI fails.
- **Changelog:** [`plugin/CHANGELOG.md`](../plugin/CHANGELOG.md) (ships to the mirror root) is the public record of the released artifacts; it tracks only the public artifact (the CLI + plugins), never internal development.

**CI runs in the PUBLIC mirror repo — free Actions (incl. the 10× macOS / 2× Windows multipliers), no Team plan needed.** The mirror's build workflow **checks out the private source via a read-token secret**, bootstraps the kernel, builds Linux (x64 + arm64) and Windows native binaries, and publishes to the mirror's Releases. **Guardrail:** the workflow is **maintainer-triggered only** (`workflow_dispatch` / tag / `repository_dispatch` from the private repo) — **never `pull_request`** — so the private-source token is never exposed to a fork PR. The private source only lands transiently on the ephemeral runner; only the binaries become public. (mac stays local; on a Team plan linux+windows are already ~free within the 3000 included minutes, so this mainly buys "no Team needed" + headroom.)

### The mirror's `build-native` workflow (linux-x64 + linux-arm64 + windows-x64)

Single-sourced in the private repo at **`.github/mirror-workflows/build-native.yml`** (inert there — *not* under `.github/workflows/`, so it never runs in the private repo) and shipped to the mirror's `.github/workflows/` by `assemble-release-repo.sh`. It **must** be assembled, not hand-kept on the mirror: the release push rsyncs with `--delete`, so anything not in the assembled tree is wiped.

**One-time setup (before the first run):** add a fine-grained PAT with **`contents: read` on `a12-dmkits` only** as the mirror secret **`SOURCE_REPO_TOKEN`**. (The mirror's default `GITHUB_TOKEN` can read/write the *mirror's* own releases but cannot read the private source.) **The secret must hold a real value, not merely exist** — an empty/placeholder `SOURCE_REPO_TOKEN` fails both per-OS jobs at the private-source checkout with `Input required and not supplied: token` (the `token:` input resolves to empty, so `actions/checkout` rejects it). This silently kept **every release through v0.1.3 mac-only** (the all-OS CI had never run end-to-end); the token was first set for **v0.2.0**. A fine-grained PAT **expires**, so a later release can regress here — if the native jobs fail at *checkout*, re-issue the PAT and re-set the secret.

**Reproducible kernel bootstrap — two pins, and the drift failure mode.** The kernel is on no public Maven repo, so `bootstrap-kernel` rebuilds it from the public `mgm-tp` sources — which means the build is only reproducible if those sources can't move under it. Two pins guarantee that: (1) the job checks out `a12-kernel-bootstrap` at its **`release/2025.06` branch** (not `main`), mirroring mgm-tp's release-line naming, so a later change to the kit's `main` can't retroactively alter how this kernel line rebuilds; (2) that kit's `bootstrap.sh` clones each public sibling (`a12-kernel`, `a12-base`, `a12-kernel-mm`, `a12-devtools`) at an **exact release commit** (`A12_*_REF`, the tips of upstream's `release/2025.06`), not the moving default branch. **Failure mode:** if `bootstrap-kernel` fails with `a12-kernel-mm patch does not apply cleanly (upstream may have changed)`, an unpinned clone has drifted off the release line — the `a12-kernel-mm.patch` is generated against the pinned tree and won't apply to a newer one. The fix is to re-pin the offending source in `a12-kernel-bootstrap`, never to loosen the patch. This bit **v0.9.0**: a GitHub-Actions cache eviction (caches drop after ~7 days idle; the prior green run was 3 weeks earlier) forced a fresh bootstrap, which — then still cloning upstream `main` — had advanced past the release commit. The SHA pins + the branch pin are the fix.

**Run it:** the mirror → Actions → *build-native* → *Run workflow*, with `source_ref` (the `a12-dmkits` tag/branch to build, e.g. `v0.1.1`) and `release_tag` (the mirror release to attach to; blank = upload workflow artifacts only).

The three jobs and why they're shaped this way:

| Job | Runner | What | Why |
|---|---|---|---|
| `bootstrap-kernel` | ubuntu | rebuild the kernel from **pinned** public sources (`a12-kernel-bootstrap@release/2025.06`, siblings at exact release commits — see above), cache `~/.m2/.../com/mgmtp`, upload it as the `kernel-m2` artifact | the kernel is on no public Maven repo; the bootstrap is **bash**, so it runs on Linux *once* and the OS-independent JARs are handed to every native job — Windows never runs the bash bootstrap |
| `build-native` (matrix: linux-x64, linux-arm64, windows-x64) | ubuntu / windows | download `kernel-m2`; set up **JDK 21** (Gradle toolchain) + **GraalVM 25** (native-image via `GRAALVM_HOME`, since `toolchainDetection=false`); `:cli:nativeCompile`; verify | the toolchain is 21 but native-image needs GraalVM 25 — both must be present, with the 21 toolchain found via the matrix's architecture-specific `JAVA_HOME_21_*` env var |
| `publish` (only if `release_tag` set) | ubuntu | upload the three CI-built binaries to the release, then **regenerate `SHA256SUMS` over all assets** (incl. the locally-uploaded mac binary) | keeps the checksum file covering the full per-OS set |

**Per-OS verification asymmetry (deliberate):** Both Linux builds (x64 + arm64) run the full **`native-gate.sh`** (byte-for-byte showboat, §5). Windows runs a **lighter smoke** (`--version` / `manifest` / `model check` / `rule read`, CRLF-normalized) instead — the showboat demos are bash/`jq`/`python` heredocs that don't run cleanly on Windows, but the smoke still exercises the reflection metadata, which is the real cross-OS porting risk (it's captured on macOS; if Windows surfaces a `MissingReflection`/`MissingResource`, add a Windows `capture-native-config.sh` run).

**Windows-smoke authoring gotchas (learned v0.2.0 — the job had never run before the token was set, so all three were latent and not binary faults).** The smoke is bash under `set -e -o pipefail`:

| Gotcha | Symptom | Fix |
|---|---|---|
| **Grep a FILE, never a pipe** | `"$BIN" manifest \| … \| grep -q PAT` — `grep -q` exits on first match and **SIGPIPEs** the upstream producer (manifest is ~66 KB), so the step dies with **exit 141** under `pipefail` | write to a temp file, then `grep PAT file` (what the `--version` check already does via `tee`). `--version` only survived the pipe form because its output is a few lines |
| **Match Jackson's pretty spacing** | the CLI emits `"key" : value` (space *before* the colon); `grep '"key": value'` (compact) never matches → false failure | spacing-tolerant `grep -Eq '"key" *: *value'` |
| **Complete the verb invocation** | `rule read` bare exits 2 (`Missing required parameter: <rulePathInModel>`) | pass a real rule path (e.g. `/Order/DeliveryNotBeforeOrder`) |
| **POSIX-path args mangled by git-bash** *(Windows-only)* | `rule read /Order/X` → git-bash rewrites `/Order/X` to `C:/Program Files/Git/Order/X` (MSYS path conversion) → "not a rule", exit 2. **Not reproducible on mac/linux.** | prefix the command: `MSYS_NO_PATHCONV=1 "$BIN" … rule read /Order/X` |

**Meta-lesson — there are TWO bug classes, and only one is locally catchable.** **(a) Logic bugs** (the colon spacing, the SIGPIPE, the missing arg) reproduce instantly against the local native binary — e.g. `bash -c 'set -o pipefail; ./dmtool manifest | sed "s/\r$//" | grep -Eq PAT; echo $?'` prints `141` (piped) vs `0` (file form) — so **always drive the smoke locally first** to flush these before a ~10-min CI round-trip. **(b) Windows/git-bash quirks** (MSYS path conversion, CRLF) are **invisible on mac/linux** and surface only on the actual Windows runner (or a local git-bash). Expect a Windows-only round even after the logic is locally green; budget for it, and keep `/`-prefixed args out of git-bash or guard them with `MSYS_NO_PATHCONV=1`.

## 5. The verification gate — native↔JVM byte-identity

Every freshly built **native** binary is re-verified against the `examples/` showboat demos before it ships:

```
DMTOOL_CLI="$PWD/<native-binary>" uvx showboat@0.6.1 verify examples/cli-*.md
```

The same gate first proves that the artifact runs with every Java discovery route disabled, then runs the byte-identity demos and native no-crash battery. These are distinct claims: demo parity cannot prove that the delivered executable is not a JVM launcher.

This is **not optional polish** — it is the bridge that *licenses the eval to run on the JVM launcher only* (the eval never builds native; [`EVAL-SPEC.md`](EVAL-SPEC.md) §2a). The chain:

> eval measures JVM `dmtool` → this gate proves native `dmtool` == JVM `dmtool` (byte-for-byte) → the N6 self-sufficiency number transfers to the shipped artifact.

showboat compares output byte-for-byte, so a closed-world resource drop (§3a) makes a demo diverge and the gate fails — on the exact OS where it would have hurt a user. Windows note: native stdout may be CRLF; normalize (`sed 's/\r$//'`) or scope Windows to hand-picked invocations if the full suite won't match.

## 6. Agent distribution — the plugins (Claude Code + Codex, both v1)

The CLI is **LLM-agent-first**; its primary delivery is an **agent plugin** that bundles the skill *and* the on-demand installer that fetches the binary. **v1 ships two plugins — Claude Code and OpenAI Codex** — because the two ecosystems converged on a near-identical model (a `SKILL.md` of the same format, a plugin manifest + marketplace). **Plugin source lives in this repo** — [`plugin/`](../plugin/) (Claude Code) and [`plugin-codex/`](../plugin-codex/) (Codex) — single source of truth, locally testable. The **public `a12-dmtool-releases` repo is a release-only push target**.

- **Vehicle = plugin** (not a bare skill — can't bundle a binary installer; not MCP — wrong model for a local offline CLI, and it would undercut the self-describing-CLI thesis). This holds for both agents.
- **Single-source shared assets, COPY-synced** (the proven cross-agent pattern — symlinks don't survive the mirror push). Four assets are shared **verbatim** by both plugins:
  - `skill/SKILL.md` → `skills/a12-dmtool/` — the authoring skill, canonical (also the eval's material, [`EVAL-SPEC.md`](EVAL-SPEC.md) §2a, exact path baked into its boundary audit);
  - `scripts/ensure-dmtool.sh` → `skills/a12-dmtool/` — the **agent-agnostic** on-demand installer (uses a host data directory when one reaches the skill command, otherwise a sandbox-writable temporary cache; accepts either host's env-file hint). It sits beside `SKILL.md` so the skill can run it via the skill's directory;
  - `scripts/release-assets.sh` → `skills/a12-dmtool/` — the binary asset names + platform mapping used by the installer and release checks;
  - `skill-bug-report/SKILL.md` → `skills/a12-dmtool-bug-report/` — the **bug-and-findings report** skill (`/a12-dmtool-bug-report`): it helps a user file a crisp, minimal-repro report and saves it to a folder they pick. The **same skill the probe uses** for its report pass (PROBE-SPEC), so the report rigor has one source.

  `scripts/sync-plugin-assets.sh` copies all shared assets into every plugin tree (`plugin/`, `plugin-codex/`); a **CI freshness check** (`--check`) fails on any drift. Never hand-edit a plugin copy; add a plugin to the script's `PLUGINS` list when a new agent is supported, or a row to its `ASSETS` for a new shared asset.
- **Per-agent files (the irreducible delta), maintained in place:** each plugin's manifest (`.claude-plugin/plugin.json` + `marketplace.json` · `.codex-plugin/plugin.json` + a git-subdir `marketplace.json`) and Codex's `AGENTS.md` (a usage/context template).
- **Binary delivery = on-demand, skill-triggered (NO session hook).** The skill instructs the agent: when `dmtool` is `command not found`, run the bundled `ensure-dmtool.sh` from the skill's own directory. The installer downloads the per-OS binary from the mirror Release, checksum-verifies it, caches it by version, and **prints its absolute path only after both the versioned destination and stable symlink are executable**. A skill-launched Codex shell is not a plugin hook and does not receive the hook-only `PLUGIN_DATA`; its fallback is `${TMPDIR}/dmtool-plugin-<uid>`, not `HOME`, because the workspace sandbox need not admit the user's cache directory. Claude's host data directory remains preferred when present. A script run mid-session cannot reliably inject PATH, so the skill invokes the printed path; an env-file export is harmless best effort. Fetches via `curl` → no quarantine/MotW (§7). **Version-aware cache (must stay so):** each resolved data directory holds `bin/<version>/dmtool`, with a self-correcting `bin/dmtool` symlink and old version dirs pruned. A version-less fixed path once served a stale binary after plugin upgrade. `EnsureDmtoolCacheTest` now locks the bump/cache behavior, the Codex sandbox fallback, and the no-false-ready failure path in the input-declared `deliveryGuardTest`; `PluginInstallerVersionTest` separately locks the pin. **Dev fallback** (verified, both agents): `RK_DMTOOL_LOCAL` or a sibling source-repo native build is symlinked, so source packaging can be qualified before publication. Delivery failure warns and leaves the session alive; it never claims readiness.
- **Mirror layout (assembled by `scripts/assemble-release-repo.sh`):** the Claude plugin at the **mirror root** (`.claude-plugin/`, `skills/`); the Codex plugin under **`codex/`**; the Codex marketplace manifest at `.agents/plugins/marketplace.json` (git-subdir → `./codex`); the **worked-example walkthroughs + their sample models** under **`examples/`** (+ `examples/models/`) — the public showcase, runnable as-is. All alongside the binaries (Releases) + the shared EUPL bundle + the public `README.md` (covers both install paths + per-artifact licensing + disclaimer) + `CLAUDE.md` with the generated-repo/release immutability guardrails. The mirror root `AGENTS.md` is the same tiny redirect pattern as this repo: it points agents to `CLAUDE.md` instead of duplicating instructions.
- **Install:** `/plugin marketplace add mbackschat/a12-dmtool-releases` + `/plugin install dmtool` (Claude Code) · `codex plugin marketplace add mbackschat/a12-dmtool-releases` + `codex plugin add dmtool` (Codex). Claude can use its on-demand skill in the install session; Codex loads bundled skills in a **new CLI session after installation**, per the current host contract.
- **Activation is an executable RELEASE GATE.** [`codex-plugin-activation-smoke.sh`](../scripts/codex-plugin-activation-smoke.sh) creates a fresh isolated Codex home, installs the plugin, starts a separate neutral “check this model” session, and parses the event stream rather than trusting the final prose: the installed `a12-dmtool` skill file must be selected, its bundled installer must print a truthful ready path, the agent must execute `model check`, and the output must carry `KERNEL_CONFIRMED`, `valid:true`, `written:false`. `--local` qualifies current source with the current native image; default mode installs the documented public marketplace and qualifies the released artifact. Run local mode before publication and public mode after publication, and rerun whenever plugin metadata, installer behavior, activation text, or the Codex contract changes.
- **Measured 2026-08-02:** Codex 0.146.0 installed public plugin 0.10.1 as enabled and selected `dmtool:a12-dmtool` from the neutral task, but the run exposed the pre-fix installer defect: absent hook-only `PLUGIN_DATA` sent the cache to sandbox-forbidden `HOME`, failed three writes, and still printed `dmtool ready`. The current source fix then passed the complete local-source smoke with `gpt-5.6-sol` / `xhigh` and `KERNEL_CONFIRMED`. Public 0.10.1 is therefore the real red artifact, not release-good evidence; public mode must pass after the fix is published.
- *Still to verify in a real agent session:* Claude Code's install-session skill-directory and invoke-by-printed-path flow. Codex's skill discovery, skill-directory resolution, installer invocation, sandbox fallback, printed-path use, and kernel-confirmed command have now all been observed. Before “release-good,” also run the production-mode benchmark once for a measured skill lift (currently only n=1 probes).

## 7. Distribution & trust

`dmtool` is a **CLI run from a shell**, not a double-clicked GUI app — so "double-click works" is the wrong target. **No code-signing for v1.** The audience is terminal-capable, and **how the binary arrives decides whether the OS guards even fire:**

| Arrival path | macOS Gatekeeper | Windows SmartScreen |
|---|---|---|
| **browser** download of zip/dmg | fires (quarantine xattr) | fires (Mark-of-the-Web) |
| **terminal** `curl`/pkg-manager/**plugin installer** | does **not** fire | does **not** fire |

So the primary (plugin/terminal) paths are clean; only a browser download trips a guard, covered by a three-line README escape hatch (mac `xattr -d com.apple.quarantine`, Windows "More info → Run anyway", linux `chmod +x`). `SHA256SUMS` is the integrity guarantee. Revisit signing only on real friction reports. Licensing: source **MIT**; the kernel-embedding CLI distributable ships **EUPL-1.2** ([`LICENSING.md`](LICENSING.md)); the library jar stays MIT (kernel is a separate dependency jar).

## 8. Library consume recipe — DEFERRED (not v1)

The consume recipe (install the kernel-free kit jar(s) with their POMs → declare the dependency) is recorded with the rest of the deferred developer wave in **§11**. Maven Central is **no longer structurally blocked** (Stage 3 removed the kernel `api` dep and Stage 4 wired the per-kit publish); only the actual deploy + signing remain deferred.

The five kits' **public API is now surface-locked** (Set S; §11) — a golden-diff *visibility* guard freezes each kit's public/protected surface to a committed `api/dmkits-<kit>.api`, so any signature change is deliberate and reviewed **before first publish freezes consumer expectations**. This is part of publish-readiness, not a no-break promise (0.x still allows breaks).

A static Maven repo (one-line `maven { url }` consume) is a later upgrade; it does not remove step 1, so its v1 marginal value is small.

## 9. Removing a release

**Supersede, don't delete** — ship `vX.Y.(Z+1)` and roll any package-manager pointers forward; reserve hard `gh release delete` for a leaked secret or a broken artifact. Everything lives in repos the owner controls (no Central gatekeeper), so removal is easy — but **deleting an asset a brew formula / scoop manifest points at breaks installs**, so always **update the pointer before deleting the asset**. Removal stops *new* installs only; it cannot recall copies already on machines.

## 10. Distribution topology & channels

**Source repo PRIVATE (early testers) + a separate PUBLIC release-mirror repo as the single public distribution point for dmtool and the dmkits described by this spec.** The release step publishes every downloadable artifact to the mirror's GitHub Releases (and its Pages), so anonymous consumers and agents fetch from the mirror while the source stays private. Going public later retires the mirror (releases move to the source repo). The standalone interpreter shares the main repository version and has its own public mirror under [INTERPRETER-RELEASE-SPEC](INTERPRETER-RELEASE-SPEC.md). The dmtool mirror holds:

- the **CLI native binaries** per-OS + `SHA256SUMS`;
- the **developer JARs** — five per-kit artifacts (`dmkits-core`/`-rulekit`/`-modelkit`/`-edit`/`-schemakit`) + the `dmkits` aggregator POM (kernel-decoupling Stage 4); `-javadoc.jar`/`-sources.jar` ride publishing-prep (§3b);
- the **JavaDoc HTML** (browsable) on the mirror's **GitHub Pages**;
- an **LLM-facing API doc** (`llms.txt`-style flat API reference) for coding agents — planned (§11).

| Channel | Status | Notes |
|---|---|---|
| CLI binaries on the mirror Release | **v1** | anonymous `curl` (public mirror) — no auth, no quarantine/MotW |
| developer jars + POM on the mirror Release | **v1** | + `publishToMavenLocal` for the maintainer/siblings; consume = install-with-POM (§8) |
| **agent plugins** (Claude Code + Codex; skill + on-demand installer) | **v1** | both push to the mirror (Claude at root, Codex under `codex/`); the skill's on-demand installer plain-`curl`s the CLI binary from the mirror (§6) |
| **JavaDoc HTML** + **LLM API doc** on the mirror Pages | planned | public URLs for human + agent API consumption (§11) |
| **Homebrew** formula / **Scoop** manifest | planned | point at the public mirror assets — now unblocked (anonymous) |
| `curl \| sh` installer | planned | fetches from the mirror |

**Licensing on the mirror (per [`LICENSING.md`](LICENSING.md) / CLI-SPEC §11) — mixed, per-artifact:**
- **CLI native binaries → EUPL-1.2.** They embed the kernel, so each binary/release carries the kernel **LICENSE/NOTICE/THIRD_PARTY** + a source pointer (and **no "mgm A12" branding** — the `dmtool` name respects that).
- **Developer JARs → MIT.** The five kit jars (`dmkits-core`/`-rulekit`/`-modelkit`/`-edit`/`-schemakit`) + the `dmkits` aggregator POM are **kernel-free** (decoupling Stage 3 — no kernel type, no kernel dep in their POMs), so they stay MIT with no EUPL rider. The broad kernel backend (`:adapter`) is **not** published (owner ruling).
- **Interpreter Kernel drop-in → EUPL-1.2 by owner policy.** This is a narrow, bytecode-free `IDocumentRtService` compatibility artifact published after the MIT interpreter core, not a publication of `:adapter`. Its deferred API contract and re-open trigger live in [INTERPRETER-API-GAPS](INTERPRETER-API-GAPS.md#iag2), while its artifact gate lives in [INTERPRETER-RELEASE-GAPS](INTERPRETER-RELEASE-GAPS.md#irg1).
- The mirror repo's README states the per-artifact licensing explicitly so a downloader isn't confused by the mix.

## 11. dmkit developer surface — DEFERRED; interpreter publication active separately

**The dmkit Java-library surface remains out of scope for dmtool v1** (v1 = the CLI native binary, §1). The standalone interpreter is specified separately in [INTERPRETER-RELEASE-SPEC](INTERPRETER-RELEASE-SPEC.md): a GitHub Release-hosted npm-compatible tarball, a GitHub Pages Maven repository, versioned Kotlin/TypeScript API docs, portable workspace preparation, and a browser showcase in its interpreter release mirror. This section remains the durable record of the later **dmkit developer-library wave**.

1. **Library JARs — publish WIRING LANDED (kernel-decoupling Stage 4, Package B); the Central or mirror DEPLOY is what's still deferred.** `java-library` + `maven-publish` on the **five kernel-free kits** → `io.github.mbackschat.a12.dm:dmkits-{core,rulekit,modelkit,edit,schemakit}:X.Y.Z` (each `from components.java`, correct inter-kit POM deps) + the POM-only **`dmkits` aggregator** depending on all five (the coordinate this spec documents, §8). **MIT** — the kits carry **no kernel type and no kernel dep in their POMs** (Stage 3), and `:adapter` is unpublished (owner ruling), so **Maven Central is no longer structurally blocked**: the old blocker — a `kernel-md-facade` `api` dep on the published artifact — is gone. Verified by `publishToMavenLocal` (POMs inspected: right coordinates, right inter-kit deps, no `com.mgmtp`/no `:adapter`). **The five kits' public API is now surface-locked (Set S).** A golden-diff *visibility* guard — `PublicApiGuardTest` over a reflection dump (`PublicApiDumper`), one committed `api/dmkits-<kit>.api` per kit — fails the build on any change to a public/protected type or member of `dmkits-{core,rulekit,modelkit,edit,schemakit}`, with a "regenerate via `./gradlew dumpKitApi` and review the diff" message. This is deliberately **not** japicmp-style "fail on any incompatibility": at 0.x a break is allowed, so the guard only makes every surface change *deliberate and reviewed* before first publish freezes consumer expectations (the project's golden-guard pattern — [`docs/TESTING-SPEC.md`](TESTING-SPEC.md) "structural-law guardians"). **One HARD rule rides the same walk (Set W): no third-party type on any public/protected kit signature** — `PublicApiGuardTest.noThirdPartyTypeOnAnyPublishedSignature` fails the build if any published signature (supertypes, bounds, fields, params, returns, exceptions, through generics) references a type outside `java.*`/`javax.*`/`io.github.mbackschat.*`, so an `implementation`-scoped dep (Jackson) can never force itself onto a consumer's compile classpath. **Still deferred to a publishing-prep follow-on:** the actual Central (or mirror) *deploy* + signing and the `-javadoc.jar`/`-sources.jar` (no `sources.jar` while the source repo is private). **Consume (local today):** `implementation 'io.github.mbackschat.a12.dm:dmkits:X.Y.Z'` (the aggregator) or any single kit; a consumer who wants the kernel oracle provides a backend (`:adapter` built from source) on the runtime classpath.
2. **JavaDoc** — the `-javadoc.jar` (downloadable) **and** browsable **HTML on the mirror's GitHub Pages** (a stable public URL).
3. **LLM API doc (`llms.txt`-style)** — a flat, single-file API reference for coding agents (public types/signatures + typed-builder idioms + DSL entry points + short usage), distinct from the human JavaDoc. **Must be derived/CI-locked** against the real API (the no-drift discipline — assembled from the CI-locked typed-builder snippets + signatures), not hand-maintained. Public URL an agent can fetch.
4. **Developer skill** — the library-persona analog of [`skill/SKILL.md`](../skill/SKILL.md): teaches *judgment* for authoring via the typed builder / `EditSession` / DSL (polarity, paths, iteration — same kernel semantics), **not** the API catalog (JavaDoc + `llms.txt`'s job — keep it minimal, mirroring "self-describing surface > skill"). Complements the EVAL-SPEC §2 "Developer path."

## 12. Multi-agent packaging — Claude Code + Codex shipped (v1); Gemini next

The tool is **not Claude-specific**. The CLI is a self-describing binary any coding agent can shell out to, and the judgment layer is plain markdown — so portability is mostly **reuse**, with a thin per-agent wrapper as the only real delta. **v1 ships Claude Code and Codex** (§6); **Gemini is the next candidate**, recorded here.

**Reused as-is (zero per-agent work):**
- **The binary** — `dmtool` is agent-agnostic; the §6 self-describing surface (`manifest` / `schema` / `--help` / diagnostics) is exactly what lets *any* cold agent discover the verbs and their I/O without host-specific glue. No changes.
- **The judgment content + the installer + release-asset manifest** — [`skill/SKILL.md`](../skill/SKILL.md), `scripts/ensure-dmtool.sh`, and `scripts/release-assets.sh` are canonical and single-source; the §6 copy-sync (`sync-plugin-assets.sh --check`) materializes them into each plugin's skill dir. Adding an agent = a new row in that script's `PLUGINS` list + the per-agent wrapper files.

**The per-agent delta — a thin wrapper:**

| Agent | Distribution vehicle | Context file | Binary delivery | Status |
|---|---|---|---|---|
| **Claude Code** | plugin ([`plugin/`](../plugin/)) | the bundled skill | on-demand installer (skill-run) → mirror (§6) | **v1** |
| **OpenAI Codex** | plugin ([`plugin-codex/`](../plugin-codex/), git-subdir marketplace) | the skill + `AGENTS.md` template | on-demand installer (skill-run) → mirror (§6) | **v1** |
| **Gemini** | a Gemini CLI extension | `GEMINI.md` | same mirror binary, same on-demand installer pattern | next |

- **The Claude↔Codex convergence made Codex near-free:** same `SKILL.md` format, same plugin/marketplace model — the delta was one manifest, an `AGENTS.md`, and generalizing the installer's env-var reads. Gemini is expected to be similar (extension + `GEMINI.md`).
- **MCP is the write-once alternative** — one MCP server reachable by all agents instead of N native wrappers. Rejected as the *primary* path (same reasoning as §6's vehicle choice): it adds a running process and undercuts the offline self-describing-CLI thesis. Keep it on the table only if maintaining several native wrappers becomes the actual friction.

## 13. Rationale (the durable *why*)

- **Native over jlink/jpackage** — single self-contained binary, no JVM to provision; **measured 2026-06-15** (not estimated): native **33 MB / ~90 ms** vs B1 **119–147 MB / ~600 ms** (~4× size, ~7× start), and both pass the runtime-eval demos. The closed-world risk is bounded by **rule-based reflection registration + the no-crash battery** ([`NATIVE-IMAGE-GRAALVM.md`](NATIVE-IMAGE-GRAALVM.md) §3.4/§3.6), which closed the v0.1.0/v0.1.1 capture-bound defects. **B1 is the measured escape hatch** (§10 there) only if a bundled kernel runtime outside the current consistency surface becomes necessary or native-image robustness becomes untenable.
- **CI matrix, mac built locally** — native-image can't cross-compile (so "all OSes at once" = parallel CI jobs); macOS bills at a 10× multiplier (the only expensive line) and the signing cert lives locally, so mac stays off CI.
- **No signing at v1** — the audience is terminal-capable and the primary delivery paths (plugin hook, package managers, `curl`) don't trip the OS guards at all; checksums + an escape hatch cover the rest. Signing is pure cost until friction is observed.
- **Library SemVer; Central now unblocked** — the kits are kernel-free (Stage 3) with per-kit publish wiring (Stage 4), so the old structural blocker (an un-published kernel `api` dep) is gone; only the deploy + signing remain deferred. SemVer (not CalVer) keeps the project's release identity independent of the upstream calendar and preserves the API break-signal library consumers need.
- **Plugin, single-source skill** — one `/plugin install` gives an agent both `dmtool` and the judgment layer; keeping the skill identical to the eval's material is what keeps the N6 number honest.
