# Release spec — `dmtool` CLI + rulekit developer library

The **durable** contract for releasing this project: the surfaces we ship, how each artifact is built, the release runbook, the verification gate, versioning, and the distribution/trust posture. This spec **survives and evolves** with every release. (Its transient companion — the per-release execution plan with iteration order, status, and one-time migrations — lives separately and is deleted when a release is done.)

**References the fixed contracts, never duplicates them:** [`CLI-SPEC.md`](CLI-SPEC.md) §10–11 (sizing, packaging phases B1/B2, licensing), [`EVAL-SPEC.md`](EVAL-SPEC.md) §2a (the eval's material boundary + JVM-launcher policy), [`API-POC-STUDY.md`](API-POC-STUDY.md) (the library/DSL surface), [`LICENSING.md`](LICENSING.md) (MIT source / EUPL distributable).

## 1. What we ship — two surfaces, one GitHub Release

A single GitHub Release per version (`vX.Y.Z`) carries **both** surfaces as different asset types. One release process, not two.

| Surface | Audience | Artifacts | v1 channel | Later channels |
|---|---|---|---|---|
| **`dmtool` CLI** | BAs + developers; **#1 use is inside a Claude Code session** | native binary per OS + `SHA256SUMS` | raw binaries on the Release | Claude Code **plugin** (binary + skill); brew/scoop; `curl \| sh` |
| **rulekit library** | Java developers (typed builder / DSL API) | `rulekit-X.Y.Z.jar` + `-sources.jar` + `-javadoc.jar` + POM | same Release **+ `publishToMavenLocal`** | static Maven repo; GitHub Pages JavaDoc |

The **command name is `dmtool`** everywhere it is publicly invoked. `:cli`'s `installDist` launcher (`applicationName = 'dmtool'`) and the native binary share that name; the eval exposes the JVM launcher as `dmtool` on PATH too (so the skill is one verbatim artifact — §6).

## 2. Versioning & naming — SemVer + compatibility metadata

**Scheme: SemVer** (`MAJOR.MINOR.PATCH`, tag `vMAJOR.MINOR.PATCH`). The project version is the project's **own** release identity; the upstream kernel is recorded as **compatibility metadata**, never folded into the version string. (CalVer-keyed-to-upstream was considered and rejected — it re-collapses the two identities and erases the library's break-signal; full rationale in the plan's D9, summarized in §11.)

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
- **Toolchain:** GraalVM for JDK 21, `native-image`. Build via the **`org.graalvm.buildtools.native`** Gradle plugin on `:cli` → `./gradlew :cli:nativeCompile` (run with `JAVA_HOME` pointing at the GraalVM; set `graalvmNative.toolchainDetection = false` so the plugin uses it directly).
- **Per-OS, no cross-compile:** native-image emits a binary for the OS+arch it runs on. macOS arm64 is built **locally** (cost: avoids the 10× CI multiplier; signing: local cert). linux-x64 / windows-x64 are built on CI runners (§4). Linux portability: prefer a mostly-static binary (`-H:+StaticExecutableWithDynamicLibC` or musl) so it doesn't break on older glibc.

**Verified build recipe + findings (2026-06-14, GraalVM 21.0.11, Gradle 9.0.0, native-build-tools plugin `1.1.1`).** Three things native-image needs beyond the defaults — the first two are solved, the third is the **remaining blocker**:

1. **Drop Groovy** — `configurations.all { exclude group: 'org.codehaus.groovy' }`. Groovy (a kernel transitive, `org.codehaus.groovy:groovy:3.0.25`) uses `invokedynamic` mutable call sites; native-image analysis fails hard with *"`MethodHandleNatives.setCallSiteTargetNormal` is reachable"* while parsing `MutableCallSite.setTarget`. rulekit never executes the kernel's runtime validators (the §11 hard ceiling), so Groovy is dead weight — dropping it both fixes the analysis **and** is the biggest size win ([`CLI-SPEC.md`](CLI-SPEC.md) §10). ⚠️ *The exclusion is currently global to `:cli` (affects installDist/eval too); its safety on the JVM path (`:cli:test` + showboat green without Groovy) is **not yet verified** — verify, or scope the exclusion to the native classpath only.*
2. **Bundle resources** (closed-world drops anything read via `getResourceAsStream`) — verified working with `-H:IncludeResources=` for `internal/templates/.*` (StringTemplate, used by `checkConsistency`), `catalog/.*\.json` (the operator catalog), `schema/.*\.json` (the CLI schema contracts), `verb-examples\.json`, and `.*version\.properties`. (Confirmed: the binary *reads* `catalog/operators.json` at runtime — resources are in the image.)
3. **Jackson reflection — partly solved, but reflection coverage is the deep problem.** `picocli-codegen` registers *picocli's* reflection, not the Jackson POJOs. Bundling a **tracing-agent** capture (`-agentlib:native-image-agent=config-merge-dir=…` run over all six showboat demos → `reflect-config.json` with **1480 classes**, + resource/serialization/proxy/jni configs, dropped under `src/main/resources/META-INF/native-image/`) **fixed startup** (`--version`/manifest work). **But it is necessary, not sufficient:** `model validate` then fails with `MissingReflectionRegistrationError` — *the kernel reflects far more widely than the demo runs exercised.* Closing this needs either a much more exhaustive agent sweep (every verb × varied models/operators/diagnostics), hand-authored reflect-config for the kernel's reflective hotspots, or `-H:+UnlockExperimentalVMOptions` with broader auto-registration. **This is iterative, multi-cycle work — not a single fix.**

4. **The runtime-eval verbs can't go native at all** (the §11 hard ceiling, now concretely breached). `model eval` / `rule test` / `model compute` execute the kernel's *runtime validation* against a document instance — which **generates and loads Groovy classes at runtime** (the agent captured `Com_mgmtp_a12_kernel_generated_*…CalculationController.class` from the `cli-runtime.md` demo). So Groovy is genuinely **required** for those three verbs — on the JVM **and** in native. Consequences: the Groovy exclusion (finding #1) **cannot be global** (it would break those verbs on the JVM/eval too — it must be native-classpath-only); and even then, **the native binary cannot support `model eval`/`rule test`/`model compute`** (no Groovy → no runtime validator codegen). A native build must drop those verbs or make them error cleanly; the authoring/checking/structure/read surface (the #1 use case) is unaffected (`rule check`/`model validate` are structural — StringTemplate, native-OK).

- **`typeReached` warning:** GraalVM 21.0.11 warns *"Found typeReached condition … not supported by this version … considered always true; consider upgrading."* A newer GraalVM clears it (owner has offered one — worth taking: it also unlocks **`-Os`** size optimization, rejected by 21.0.11 as *"Invalid value 's' for option Optimize"*).
- **Sizing:** measured **52 MB** (no reflect-config) → **61 MB** (with the 1480-class reflect-config; the metadata costs ~9 MB) using `--gc=serial`. [`CLI-SPEC.md`](CLI-SPEC.md) §10's ~36 MB looks optimistic for the *current* CLI; levers are `-Os` (newer GraalVM), `--strip-debug`, dropping more unused kernel jars, and tighter reflect-config. See §3a-bis below.
- **Verification gate (§5):** all six showboat demos currently **FAIL** against the native binary (reflection gaps + the runtime-eval verbs). The gate is doing its job — it will not let an incomplete native binary ship.

**Strategic note — B2 (native) is NOT a quick win for the current CLI; revisit B1.** [`CLI-SPEC.md`](CLI-SPEC.md) §11's "proven feasible spike" predates the catalog Jackson loading and the runtime-eval verbs; the CLI has outgrown it. Getting native fully green is real, iterative work (reflection coverage) **and** forces dropping three verbs (Groovy). **jpackage (B1) — a trimmed JVM + launcher — has none of these problems** (full reflection, Groovy works, all verbs work) at the cost of size/startup. So the original "B1 ships first, B2 is the upgrade" sequencing (which D1 reversed) may have been right. **Open decision for the owner — see RELEASE-PLAN §8.**

### 3a-bis. Native build status (2026-06-14)
✅ plugin `1.1.1` on Gradle 9 · ✅ resources bundle · ✅ startup (Jackson) via tracing-agent reflect-config · ❌ full verb reflection coverage (`model validate` still `MissingReflectionRegistrationError`) · ❌ runtime-eval verbs (need Groovy) · ❌ showboat gate (0/6). **Not shippable yet.** The working config (reverted from the tree, re-applicable): plugin + `toolchainDetection=false` + `--gc=serial` + native-only Groovy exclusion + the bundled tracing-agent config.

### 3b. Developer library
- **Plugins:** `java-library` + `maven-publish`. `java { withJavadocJar(); withSourcesJar() }` → `-javadoc.jar` + `-sources.jar`. A `MavenPublication` `from components.java` stamps a correct POM (so transitive deps resolve for consumers — §9).
- **Scope:** publish the **root `:` jar only** (rulekit + folded-in modelkit). `:adapter` is the CLI-internal kernel read tier — **not** a published developer artifact.
- **Outputs:** `publishToMavenLocal` (own machine + sibling kits) **and** the jars + POM attached to the GitHub Release.

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

## 5. The verification gate — native↔JVM byte-identity

Every freshly built **native** binary is re-verified against the `examples/` showboat demos before it ships:

```
DMTOOL_CLI="$PWD/<native-binary>" uvx showboat@0.6.1 verify examples/cli-*.md
```

This is **not optional polish** — it is the bridge that *licenses the eval to run on the JVM launcher only* (the eval never builds native; [`EVAL-SPEC.md`](EVAL-SPEC.md) §2a). The chain:

> eval measures JVM `dmtool` → this gate proves native `dmtool` == JVM `dmtool` (byte-for-byte) → the N6 self-sufficiency number transfers to the shipped artifact.

showboat compares output byte-for-byte, so a closed-world resource drop (§3a) makes a demo diverge and the gate fails — on the exact OS where it would have hurt a user. Windows note: native stdout may be CRLF; normalize (`sed 's/\r$//'`) or scope Windows to hand-picked invocations if the full suite won't match.

## 6. Agent distribution — the Claude Code plugin

The CLI is **LLM-agent-first**; its primary delivery is a **Claude Code plugin** that bundles the skill *and* puts the binary on PATH.

- **Vehicle = plugin** (not a bare skill — can't deliver a binary; not MCP — wrong model for a local offline CLI, and it would undercut the self-describing-CLI thesis).
- **Binary delivery = a `SessionStart` hook** that downloads the right per-OS binary from the GitHub Release, verifies its `sha256`, caches it in `CLAUDE_PLUGIN_DATA`, and adds it to PATH. (Not committed into the plugin's `bin/` — a ~36 MB per-OS binary would bloat/rot the repo.) Bonus: the hook fetches via `curl`, which sets **no quarantine xattr** → Gatekeeper never fires for the agent path (§7).
- **The skill is single-source:** ship [`skill/SKILL.md`](../skill/SKILL.md) **verbatim** — it *is* the eval's production-mode material ([`EVAL-SPEC.md`](EVAL-SPEC.md) §2a). **Never clone, inherit, or bloat it** with a verb catalog (the CLI self-describes verbs; the skill teaches judgment). Before declaring it release-good: (1) the name must read `dmtool` (§1); (2) run the production-mode benchmark once for a measured lift (currently only n=1 probes).
- *Caveats to verify when built:* `bin/`-on-PATH needs Claude Code ≥ v2.1.91; no documented per-OS `bin/` naming convention (the hook-download is the inferred idiom) — prototype with a throwaway plugin first.

## 7. Distribution & trust

`dmtool` is a **CLI run from a shell**, not a double-clicked GUI app — so "double-click works" is the wrong target. **No code-signing for v1.** The audience is terminal-capable, and **how the binary arrives decides whether the OS guards even fire:**

| Arrival path | macOS Gatekeeper | Windows SmartScreen |
|---|---|---|
| **browser** download of zip/dmg | fires (quarantine xattr) | fires (Mark-of-the-Web) |
| **terminal** `curl`/pkg-manager/**plugin hook** | does **not** fire | does **not** fire |

So the primary (plugin/terminal) paths are clean; only a browser download trips a guard, covered by a three-line README escape hatch (mac `xattr -d com.apple.quarantine`, Windows "More info → Run anyway", linux `chmod +x`). `SHA256SUMS` is the integrity guarantee. Revisit signing only on real friction reports. Licensing: source **MIT**; the kernel-embedding CLI distributable ships **EUPL-1.2** ([`LICENSING.md`](LICENSING.md)); the library jar stays MIT (kernel is a separate dependency jar).

## 8. Library consume recipe (local, no Maven Central)

Maven Central is **blocked** — rulekit's `api` dependency `kernel-md-facade:30.8.1` is not on Central (it is bootstrapped into `~/.m2` from public sources by the `a12-kernel-bootstrap` kit). So local-only is the honest, forced choice. A consuming developer:

1. **Bootstraps the kernel** into their own `~/.m2` (the `a12-kernel-bootstrap` kit) — unavoidable and dominant in every distribution option.
2. **Installs the rulekit jar *with its POM*** into `~/.m2` (not a bare flatDir dep — the POM is what lets the kernel resolve from their `.m2` and Jackson from Central).
3. Declares `implementation 'io.github.mbackschat.a12.dm:rulekit:X.Y.Z'`.

A static Maven repo (one-line `maven { url }` consume) is a later upgrade; it does not remove step 1, so its v1 marginal value is small.

## 9. Removing a release

**Supersede, don't delete** — ship `vX.Y.(Z+1)` and roll any package-manager pointers forward; reserve hard `gh release delete` for a leaked secret or a broken artifact. Everything lives in repos the owner controls (no Central gatekeeper), so removal is easy — but **deleting an asset a brew formula / scoop manifest points at breaks installs**, so always **update the pointer before deleting the asset**. Removal stops *new* installs only; it cannot recall copies already on machines.

## 10. Channels — current & planned

| Channel | Status | Guards tripped? | Maintenance |
|---|---|---|---|
| raw binaries on the GitHub Release | **v1** | browser-download only (escape hatch) | none |
| `publishToMavenLocal` + jars on Release | **v1** | n/a | none |
| Claude Code **plugin** (skill + hook) | planned | none (curl fetch) | a plugin repo + per-release hook target |
| **Homebrew** formula (mac+linux) / **Scoop** manifest (windows) | planned | none | a tap + a bucket repo, per-release pointer bump |
| `curl \| sh` installer | planned | none | one script (shares the tarball+checksum artifacts) |
| static Maven repo / GitHub Pages JavaDoc | planned | n/a | a published repo dir / Pages |

## 11. Rationale (the durable *why*)

- **Native over jlink/jpackage** — single self-contained binary, ~16× faster start, no JVM to provision; the closed-world risk is bounded by §5's verification gate and the §3a hard ceiling.
- **CI matrix, mac built locally** — native-image can't cross-compile (so "all OSes at once" = parallel CI jobs); macOS bills at a 10× multiplier (the only expensive line) and the signing cert lives locally, so mac stays off CI.
- **No signing at v1** — the audience is terminal-capable and the primary delivery paths (plugin hook, package managers, `curl`) don't trip the OS guards at all; checksums + an escape hatch cover the rest. Signing is pure cost until friction is observed.
- **Local-only library, SemVer** — Central is structurally blocked by the un-published kernel dependency; SemVer (not CalVer) keeps the project's release identity independent of the upstream calendar and preserves the API break-signal library consumers need.
- **Plugin, single-source skill** — one `/plugin install` gives an agent both `dmtool` and the judgment layer; keeping the skill identical to the eval's material is what keeps the N6 number honest.
