# Release PLAN (transient) — `dmtool` CLI + rulekit developer library

> **Status: PLAN, not yet executed** (drafted 2026-06-14). This is the followable plan for the first public release, written to be picked up in a later Claude Code session. It records the **options** weighed, the **decisions** taken, the **owner requirements** that constrained them, and the **rationale** for each — so the executing session can act without re-deriving the reasoning.
>
> **Companion specs (the stable contracts this plan executes against):** [`CLI-SPEC.md`](CLI-SPEC.md) §10–11 (sizing, packaging phases B1/B2, licensing), [`EVAL-SPEC.md`](EVAL-SPEC.md) §2a (the eval's material boundary + JVM-launcher policy), [`API-POC-STUDY.md`](API-POC-STUDY.md) (the developer/library surface). This doc is the *time-bound plan*; those are the *fixed method* — keep churny progress here, not in them.

## 0. Lifecycle — this PLAN (transient) vs `RELEASE-SPEC.md` (durable)

**This document is transient and will be DELETED once the release work is finished.** Its job is to drive the *first* release to completion. The durable artifact is **`RELEASE-SPEC.md`**, which **this plan creates as a deliverable** (see §5/§7) and which **survives and evolves** with every subsequent release — exactly the project's "specs hold the stable method" convention (CLAUDE.md; cf. `CLI-SPEC`/`EVAL-SPEC`).

Author `RELEASE-SPEC.md` *during* execution (so it reflects what was actually built), then delete this plan. The content split:

| Migrates into `RELEASE-SPEC.md` (durable contract) | Stays in this PLAN (deleted when done) |
|---|---|
| surfaces · artifacts · channels (the *what we release*) | owner-requirements snapshot (§2) |
| build procedure per artifact — native `buildArgs`+resource list; jars+POM via `maven-publish`; the per-OS matrix | iteration **order** + current status (§5) |
| the release **runbook** (tag → build → verify → publish; draft + mac-local upload) | one-time **migrations** (the `cli`→`dmtool` rename, §4) |
| the **verification gate** + native↔JVM byte-identity contract (§4) | open questions (§8) |
| versioning/tagging convention (D9); **remove-a-release** policy (D10) | this release's doc-update intentions (§7) |
| distribution & **trust contract** — signing posture, escape hatch, checksums, channel→guard table (D4) | the D1–D10 **decision log** *as a planning record* |
| **plugin/skill** distribution mechanics (D5/D6/D7); **library consume** recipe (D8) | |
| the **durable rationale** — why native (D1), why local-only (D8), why no-signing (D4) | |

The durable *why* lives in the spec (specs carry rationale); the plan keeps the decision log only until deletion. The spec **references**, never duplicates, the fixed contracts in `CLI-SPEC`/`EVAL-SPEC`.

## 1. What we release — two surfaces, one GitHub Release

A single GitHub Release per version (`v0.1.0`) carries **both** surfaces as different asset types. There is one release process, not two.

| Surface | Audience | Artifact(s) | Channel (v1) | Channel (later) |
|---|---|---|---|---|
| **`dmtool` CLI** | BAs + developers; **#1 use is inside a Claude Code session** | native binary per OS + `SHA256SUMS` | raw binaries on the GitHub Release | a Claude Code **plugin** (binary + skill); brew/scoop |
| **rulekit library** | Java developers consuming the typed builder / DSL API | `rulekit-<v>.jar` + `-sources.jar` + `-javadoc.jar` + POM | same GitHub Release, **+ `publishToMavenLocal`** | static Maven repo / GitHub Pages JavaDoc |

## 2. Owner requirements (the constraints this plan obeys)

1. **Release soon**, native-image CLI for **mac, linux, windows** — but **iterate**: the *very first* iteration is **local mac build only**, no Windows, no GitHub CI. Plan the rest; don't build it yet.
2. **Linux = x64 only** for now (linux-arm64 parked).
3. **macOS = arm64, built locally** on the owner's own machine (not in CI).
4. **GitHub is private**; willing to move to **Team ($4/user/mo)** for private Actions later.
5. The CLI is **LLM-agent-first**: it must ship in a form a Claude Code session can use — a **skill and/or plugin**, ideally with the binary delivered alongside.
6. Developer **JARs + JavaDoc**, but **NOT on Maven Central** — **local only**, yet **consumable by other developers**.
7. README will eventually explain the plugin/skill and the JARs/JavaDoc surfaces — **recorded here as intent**, to be written at release time, not now.

## 3. Decisions, with options and rationale

### D1 — Native-image, multi-OS: how to build "all at once"
- **Options:** (a) one machine cross-compiles all OSes; (b) a CI matrix, one job per OS; (c) jlink/jpackage instead of native.
- **Decision:** **CI matrix, one native-image job per OS** (deferred to a later iteration; see §5). Native-image **cannot cross-compile** — a binary is emitted for the OS+arch it runs on — so "at once" is necessarily *parallel CI jobs*, never a single cross-build.
- **Rationale:** the per-OS constraint is inherent to native-image (and to jlink/jpackage). GitHub-hosted runners cover every target natively. Native (B2) is chosen over jpackage (B1) per the owner's standing intent; this **reverses** [`CLI-SPEC.md`](CLI-SPEC.md) §11's documented "B1 ships first" sequencing → that §11 "Decision" line must be updated to record native-first (see §10).

### D2 — macOS: build locally, not in CI
- **Options:** (a) `macos-14` hosted runner (also arm64); (b) build locally on the owner's Mac and upload.
- **Decision:** **build mac arm64 locally.**
- **Rationale:** two wins. **Cost** — macOS runners bill at a **10× multiplier**, the only multiplier that can make the matrix expensive (one mac job ≈ 80 Linux-equivalent minutes); building locally sidesteps it entirely. **Signing** — the owner's Apple Developer cert (if/when signing happens) already lives in the local keychain. Hosted `macos-14` saves nothing here and costs the 10×.

### D3 — CI cost model (when CI lands)
- **Decision:** move to **Team ($4/user/mo)**, build linux-x64 + windows-x64 in CI, keep mac local.
- **Rationale:** Team includes **3,000 Linux-equivalent minutes/mo**. Non-mac jobs are cheap (Linux 1×, Windows 2×); a full release ≈ ~24–32 quota minutes → **~90 releases/mo before any overage**, i.e. effectively **$0 over the seat**. The 10× mac multiplier — the only expensive line — is avoided by D2. (Public-repo Actions are free, but the repo is private, hence Team.)

### D4 — Code signing / launch UX (Gatekeeper, SmartScreen, "double-click")
- **Options:** (a) unsigned + documented escape hatch; (b) sign mac + windows; (c) both.
- **Decision:** **no code-signing for v1.**
- **Rationale:** `dmtool` is a **CLI run from a shell**, not a double-clicked GUI app — so "double-click works" is the wrong target. The audience is **terminal-capable** (developers + BAs who can follow terminal instructions). Crucially, **how the binary arrives decides whether the guards even fire**: terminal `curl`/package-manager/plugin-hook fetches do **not** set the macOS quarantine xattr or Windows Mark-of-the-Web, so Gatekeeper/SmartScreen **never trigger** on those paths. Only a *browser* download trips them — covered by a three-line escape hatch in the README (mac `xattr -d com.apple.quarantine`, Windows "More info → Run anyway", linux `chmod +x`). `SHA256SUMS` is the integrity story that substitutes for signatures. Revisit signing only if real friction is reported. (For the #1 use case — the plugin's hook fetch via `curl` — Gatekeeper is dodged automatically; see D6.)

### D5 — LLM-agent distribution vehicle: plugin, bare skill, or MCP?
- **Options:** (a) bare Agent Skill; (b) **Claude Code plugin**; (c) MCP server.
- **Decision:** **a Claude Code plugin** that bundles the skill **and** delivers the binary.
- **Rationale:** a bare skill can't put a binary on PATH; a plugin can (its `bin/` on PATH since CC ≥ v2.1.91, or via a hook — see D6) **and** bundles the skill — so one `/plugin install` gives the agent both `dmtool` and the judgment layer. **MCP is the wrong fit**: it's for remote/stateful services, and wrapping a local offline CLI in MCP would *undercut the self-describing-CLI thesis* the whole project is built on ([`CLI-SPEC.md`](CLI-SPEC.md) "self-explaining surface").

### D6 — How the plugin delivers the binary
- **Options:** (a) commit per-OS binaries into the plugin's `bin/`; (b) a `SessionStart` hook that downloads the right binary from the GitHub Release.
- **Decision:** **`SessionStart` hook downloads** per-OS, checksum-verified, into `CLAUDE_PLUGIN_DATA`, added to PATH.
- **Rationale:** committing a ~36 MB per-OS native binary to git bloats the repo and rots; the hook keeps the plugin tiny and **reuses the exact release artifacts** D1 produces. Bonus: the hook fetches via `curl` → **no quarantine/MotW → Gatekeeper/SmartScreen never fire** for the primary (Claude Code) audience, so D4's "no signing" has zero UX cost on the path that matters most. *Caveats to verify when building:* the `bin/`-on-PATH feature needs CC ≥ v2.1.91; there's no officially documented per-OS `bin/` naming convention (the download-on-first-use hook is the inferred idiom) — prototype with a throwaway plugin first.

### D7 — The skill: ship as-is, clone, extend, or inherit?
- **Options:** (a) ship [`skill/SKILL.md`](../skill/SKILL.md) verbatim (single source); (b) clone a release variant; (c) extend it; (d) inherit/overlay.
- **Decision:** **single source — ship the exact skill the eval tests.** No clone, no inherit, no catalog-bloat.
- **Rationale:** the skill **is** the production artifact by design — [`EVAL-SPEC.md`](EVAL-SPEC.md) §2a defines "production mode" as *self-describing CLI + `skill/SKILL.md` + model file*, pointing at this exact path. **Cloning would silently break N6** (benchmark one skill, ship another). **Inheritance isn't a CC mechanism.** **Extending with a verb catalog** would violate the standing "skill teaches judgment, the CLI self-describes verbs" rule. Two things must happen before calling it "release-good": **(1)** reconcile the command name (see §4); **(2)** run the **production-mode benchmark once** (wired but opt-in) — its lift is currently **unmeasured** beyond n=1 probes; every wired pass@k run in [`../eval/RESULTS.md`](../eval/RESULTS.md) is bare-tool.

### D8 — Developer library: Maven Central or local?
- **Options:** (a) Maven Central; (b) local only (`publishToMavenLocal` + jars on the Release); (c) static Maven repo / JitPack.
- **Decision:** **local only**, **consumable by other developers** via jars-on-the-Release + an install-with-POM recipe.
- **Rationale:** **Central is blocked anyway** — rulekit's `api` dependency `com.mgmtp.a12.kernel:kernel-md-facade:30.8.1` is **not on Central** (it's bootstrapped into `~/.m2` from public sources by the `a12-kernel-bootstrap` kit). A Central publish would emit a POM referencing a kernel artifact no Central user can resolve → broken. So local-only is the **honest, forced** choice, and it spares the Central ceremony (PGP, OSSRH namespace, strict POM gates). **The consumer must bootstrap the kernel into their own `.m2` regardless** — that step is unavoidable and dominant in every option, which is *why* a hand-install of rulekit to `.m2` is right-sized (the consumer is already in "local `.m2`" mode). A static Maven repo (smoother one-line consume) is a clean **later** upgrade; it doesn't remove the kernel-bootstrap step, so its v1 marginal value is small.
- **Critical detail:** consumers install the jar **with its POM** (not bare flatDir), so transitive deps resolve — kernel from their `.m2`, Jackson from Central. This is *why* `maven-publish` is needed: it stamps the correct POM.
- **Scope (confirmed):** publish the **root `:` jar only** (rulekit + the folded-in modelkit). `:adapter` is the CLI-internal kernel read tier — **not** a published developer artifact.

### D9 — Versioning (scheme = **SemVer + compatibility metadata**, "Option A")
- **Options:** **A — SemVer** (`MAJOR.MINOR.PATCH`) + upstream compatibility recorded as *metadata*; **B — CalVer** keyed to the upstream quarter (`vYYYY.MM.PATCH`, the external "Versioning and Release Naming Policy" proposal).
- **Decision:** **A.** Drop `-SNAPSHOT` → **`0.1.0`**, tag **`v0.1.0`**; advance to **`v1.0.0`** when the public library API stabilizes. **CalVer (B) rejected.**
- **Rationale:**
  - **B re-collapses the identities it claims to separate** — baking the upstream `YYYY.MM` *into* the project version makes the version string partly an upstream identifier, the opposite of "independent release identity," and it forces a fake bump for a project release that doesn't track a new upstream quarter.
  - **We ship a versioned library API**, so consumers need SemVer's MAJOR/MINOR/PATCH break-signal; CalVer gives none. CalVer also hides feature-vs-fix within a line.
  - **The compatibility key that matters is the kernel semver `30.8.1`, not the A12 quarter.** We already encode it twice: the CLI bakes `kernelBuiltAgainst` into `version.properties` (via `--version`/`manifest.version`), and the library POM declares `kernel-md-facade:30.8.1`. So **no `project-release.yaml`** (a third, drift-prone source) and **no `release/YYYY.MM` maintenance branches** (premature at 0.x; would collide with the no-self-branching rule).
- **Kept from the proposal (→ durable spec):** (1) the **release title carries upstream compatibility** — `v0.1.0 — kernel 30.8.1 (A12 Tools 2025.06-ext5)`; (2) release **notes get a Compatibility section**; (3) a **tag-validation regex**, SemVer-shaped: `^v[0-9]+\.[0-9]+\.[0-9]+(-(alpha|beta|rc)\.[0-9]+)?$` (the CI step lands with the CI matrix, iteration 3 — local mac has no CI to run it).

### D10 — Removing a release (operational policy, mainly for brew/scoop later)
- **Decision:** **supersede, don't delete** — ship `v0.1.1` and roll pointers forward; reserve hard `gh release delete` for secret-leak / broken-artifact.
- **Rationale:** everything lives in repos the owner controls (no Central gatekeeper), so removal is easy — but deleting an asset a brew formula / scoop manifest points at **breaks installs**; always **update the pointer before deleting the asset**. Removal stops *new* installs only — it cannot recall copies already on machines.

## 4. Cross-cutting prerequisite — the `cli` → `dmtool` rename

The shipped binary is **`dmtool`** (README, [`bin/dmtool`](../bin/dmtool), `examples/`, [`CLI-SPEC.md`](CLI-SPEC.md) §8), but today the build, the skill, and the eval still say **`cli`**:
- `cli/build.gradle` has **no `applicationName`** → `installDist` emits a launcher literally named `cli` (`cli/build/install/cli/bin/cli`).
- [`skill/SKILL.md`](../skill/SKILL.md) uses `cli` ~22×.
- the eval harness hardcodes the path: `benchmark.workflow.js` (`const CLI = '… cli/build/install/cli/bin/cli'`) and `gap-finder.workflow.js` (`const BIN`), whose prompt already hedges *"it calls itself `dmtool`"*.

**This rename is a prerequisite for the whole release** (the binary, the skill, and the eval must all agree on `dmtool`). Do it as **one coherent commit**, and prefer the cleaner approach over scattering literal renames:

> **Make both environments expose `dmtool` on PATH.** The skill speaks the *release* name (`dmtool`); **production** = the plugin puts `dmtool` on PATH (D6); **eval** = [`eval/prep-sandbox.sh`](../eval/prep-sandbox.sh) exposes the JVM `installDist` launcher as `dmtool` on PATH (deterministic setup belongs in that committed script per the testing-strategy rule). Then the skill is **one verbatim, environment-agnostic artifact** and N6 stays honest.

**What changes:** `applicationName = 'dmtool'`; the two workflow constants + prompts; the skill's `cli`→`dmtool`; the `bin/dmtool` shim fallback path. **What does NOT change:** the eval's tasks, golds, scoring, material boundary, and the bare-tool vs production split — this is a rename of the invocation token only.

**The native↔JVM equivalence is load-bearing.** The eval deliberately runs on the **JVM `installDist` launcher**, never native (native-image's ~1.5-min build has no place in the per-task loop — [`EVAL-SPEC.md`](EVAL-SPEC.md) §2a). That's valid *only because* native output is byte-identical to JVM ([`CLI-SPEC.md`](CLI-SPEC.md) §11). So the **release-time showboat-verify-against-the-native-binary** check (§5) is not optional polish — it is the bridge that *licenses the eval to skip native*: eval blesses JVM → release proves native == JVM → the N6 number transfers to the shipped artifact. native-image's closed-world analysis can silently drop a resource; the per-OS showboat check is what catches that divergence.

## 5. Iteration plan

Ordered by dependency. **Iteration 1 is the only one to execute first**; the rest are planned, not yet built.

| # | Iteration | Builds | CI? | Depends on |
|---|---|---|---|---|
| **1** | **Local mac CLI release** *(execute first)* | mac arm64 native binary → GitHub Release + `SHA256SUMS` | **no** | §4 rename, GraalVM local, repeatable native task |
| **2** | **Developer JARs** | root `:` jar + sources + javadoc + POM → same Release; `publishToMavenLocal` | no (local build) | D8, D9 |
| **3** | **GitHub CI matrix** | linux-x64 + windows-x64 native binaries; draft-release flow + mac-local upload | **yes** | Team plan (D3), iteration 1 proven |
| **4** | **Claude Code plugin** | plugin bundling the skill + `SessionStart` hook that downloads `dmtool` from the Release | no | a published Release to download from; D5/D6/D7 |
| **—** | **Backlog** (§9) | brew/scoop, code-signing, linux-arm64, mac-x64, static Maven repo, GitHub Pages JavaDoc, `curl \| sh` installer, production-mode eval as a gate | — | — |

### Iteration 1 — concrete steps (local mac only)

> **STATUS (2026-06-14):** rename ✅ (committed `0b8c307`, verified — all 6 showboat demos + `:cli:test` green on the renamed JVM launcher); spec authored ✅; native build **investigated, one blocker remaining** — see step 5. GraalVM 21.0.11 (with `native-image`) is **installed locally** at `/Library/Java/JavaVirtualMachines/graalvm-jdk-21.0.11+9.1/` (off PATH); `gh` authed; kernel `30.8.1` in `mavenLocal`.

1. ~~**Prereq:** GraalVM 21~~ — **DONE**: GraalVM 21.0.11 present locally (path above); run `:cli:nativeCompile` with `JAVA_HOME` set to it.
2. ~~**Do the §4 rename**~~ — **DONE + verified + committed** (`0b8c307`). Eval routes through the `bin/dmtool` shim.
3. **Native plugin + config** — recipe **proven** (was applied + reverted; re-apply from [`RELEASE-SPEC.md`](RELEASE-SPEC.md) §3a): plugin `org.graalvm.buildtools.native:1.1.1` (resolves on Gradle 9), `toolchainDetection=false`, the `-H:IncludeResources=` set (verified bundling), **and `configurations.all { exclude group: 'org.codehaus.groovy' }`** (Groovy's mutable call sites break analysis). ⚠️ verify the Groovy exclusion is safe on the JVM path too, or scope it native-only.
4. **Version:** drop `-SNAPSHOT` → `0.1.0` **at the release/tag step** (not during dev).
5. **Build + smoke-test — BLOCKER:** `:cli:nativeCompile` **builds** (52 MB, ~1m53s) but the binary **crashes at startup**: Jackson can't reflectively construct the catalog POJOs (`OperatorEntry` &c.). **Next step:** add a `reflect-config.json` (or run the native-image tracing agent) for the Jackson-deserialized types, then re-run and pass the showboat byte-identity gate (§5). Full diagnosis + fix path: [`RELEASE-SPEC.md`](RELEASE-SPEC.md) §3a finding #3.
6. **Release:** `gh release create v0.1.0` with the mac binary + `SHA256SUMS`.
7. **Author the durable spec:** create **`docs/RELEASE-SPEC.md`** from this plan's §0 left-column content (the runbook + contracts as actually built), and add its index line to `docs/README.md`. README "Install (macOS)" stanza with the escape hatch (per §7).
8. **On completion of all iterations:** delete this plan (`docs/RELEASE.md`); the spec carries forward.

## 6. Backlog — later iterations (planned, not scheduled)

- **brew/scoop** — a Homebrew **formula** (mac+linux, in a `homebrew-tap` repo) and a Scoop **manifest** (windows, in a `scoop-bucket` repo): each points at a release asset (tarball/zip) + pins its `sha256`; the package manager fetches+verifies and puts `dmtool` on PATH **without tripping Gatekeeper/SmartScreen**. Cost = two small repos + a per-release pointer bump (automatable). Removal policy = D10.
- **Code-signing** — Apple Developer ID + notarization (mac), OV/EV cert (windows). Only if friction reported.
- **linux-arm64**, **mac-x64** — extra matrix jobs when demand appears.
- **Static Maven repo / GitHub Pages JavaDoc** — smoother one-line library consume + browsable API docs.
- **`curl \| sh` installer** — shares the same tarball+checksum artifacts as brew/scoop.
- **Production-mode eval as a release gate** — wire the production benchmark (D7) into the release checklist once it has a measured baseline.

## 7. Documentation to update (intentions — do at execution time, not now)

Per the project doc-discipline ("docs travel inside the feature commit"):

- **NEW `docs/RELEASE-SPEC.md`** — the **durable** release spec (the runbook + contracts in the §0 left column). **Authored during execution; survives and evolves.** This is the deliverable that outlives the plan.
- **`docs/RELEASE.md`** — *this plan; **DELETE** once the first release is finished* (§0).
- **`docs/README.md` index** — add a one-line pointer to **`RELEASE-SPEC.md`** (not this plan) *(deferred this turn to avoid colliding with the parallel agent on `main`)*.
- **`CLI-SPEC.md` §11** — flip the "B1 ships first" Decision line to record **native-first** (D1).
- **README.md** *(owner-flagged intent)* — at release time, add: an **Install** section (download + escape hatch + `sha256sum -c`), a **"Use as a library"** teaser (must use the **typed builder**, not a raw condition string, mirroring [`API-POC-STUDY.md`](API-POC-STUDY.md) §5b), and a **Claude Code plugin/skill** section. README samples are hand-maintained + not CI-verified → update in the same commit as the change.

## 8. Open questions to resolve before/at execution

1. ~~**GraalVM 21 installed locally?**~~ — **RESOLVED**: GraalVM 21.0.11 present locally.
2. ~~**Confirm `v0.1.0`** as the first tag~~ — **RESOLVED**: Option A confirmed, `v0.1.0` (D9).
3. **🚩 NATIVE (B2) vs jpackage (B1) — the decision D1 reversed now needs re-deciding.** The native build is a **substantial, multi-cycle effort** (reflection coverage — `model validate` still throws `MissingReflectionRegistrationError` even after a 1480-class tracing-agent capture) **and forces dropping three verbs** (`model eval`/`rule test`/`model compute` need Groovy runtime codegen, not native-compilable). jpackage (B1) avoids both (all verbs work, no reflection grind) at the cost of size/startup. **Decide: invest in completing native (accepting the verb loss + grind), or ship B1 first as §11 originally sequenced.** Full findings: [`RELEASE-SPEC.md`](RELEASE-SPEC.md) §3a.
4. **If pursuing native: newer GraalVM.** A GraalVM for JDK 25 brings a newer native-image engine (better reflection auto-handling → less manual config; `-Os` size optimization; `typeReached` support). It does **not** fix the Groovy/runtime-eval wall (#3). Owner offered to download JDK 25 or JDK 21 GraalVM.
5. **Native binary's runtime-eval verbs:** if native ships, decide how `model eval`/`rule test`/`model compute` behave there — omitted from the command tree, or present but erroring with a clear "JVM-only verb" message.
6. **🚩 Private repo → public release downloads** (affects D6 plugin hook + raw-download + brew/scoop, all of which fetch anonymously): release assets on a **private** repo require auth — **not** anonymously downloadable. Options: make the repo public; or a dedicated **public releases-mirror repo** (source stays private, CI publishes binaries there); or external public hosting. Decide before the plugin/brew/scoop channels.
7. **Linux portability:** when CI lands (iteration 3), decide whether to build a **mostly-static** Linux binary (`-H:+StaticExecutableWithDynamicLibC` or musl) so it doesn't fail on older glibc.
8. **Groovy exclusion scope:** must be **native-classpath-only** (global breaks the JVM runtime-eval verbs + the CI showboat gate + the eval). Not yet implemented as native-only.
