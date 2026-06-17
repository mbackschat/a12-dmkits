# dmcode — the code-mode delivery (proposal)

**Status: experiment.** This proposal captures the design of a second, experimental delivery tier — `dmcode` — alongside the shipped native `dmtool` CLI. It records requirements, the measured findings behind each choice, the settled decisions, the open questions, a transient plan, and the release approach. When the experiment graduates, its design extracts into a [`CLI-SPEC.md`](CLI-SPEC.md) section (or a `DMCODE-SPEC.md`) and the release detail into [`RELEASE-SPEC.md`](RELEASE-SPEC.md); this proposal then shrinks to intent + a pointer.

## What dmcode is

Where the native `dmtool` binary gives a coding agent a **fixed verb set**, `dmcode` lets the agent **author/modify/validate document models by writing and executing typed Java** against the modelkit/rulekit library. The human layer is unchanged: the BA still converses in natural language; only the agent's *action language* changes (verbs → code). This is the "code-as-action" pattern — code is a superset of any verb vocabulary, it plays to a coding agent's native competency, and it uses the project's strongest surface (the typed builder, which `API-GUIDE.md` already argues beats fragile condition strings).

The two tiers are complementary, not competing:

| | native `dmtool` (shipped) | `dmcode` (this proposal) |
|---|---|---|
| Audience | BA on a laptop, common case | power/bulk/bespoke authoring; runtime evaluation |
| Surface | fixed verbs (+`apply`/`batch`) | unbounded — loops, conditionals, data-driven, multi-model |
| Feedback oracle | runtime diagnostics | **the compiler** + validate + lint |
| Provisioning | one 33 MB binary, zero deps | a bundled JVM + kits (~80 MB) |
| Delivery | native binary, eager hook | JVM bundle, lazy hook |
| Runtime evaluation | **can't** (`model eval`/`rule test` need Groovy → hidden) | **can** (full JVM has Groovy) |

This proposal is the code-mode side of the broader agent-first thesis. It is sequenced **after** the developer-library path (the "(B)" path: shipping the kits as a Maven artifact for developers already in a JVM project — [`RELEASE-SPEC.md`](RELEASE-SPEC.md) §11), whose agent resources `dmcode` reuses wholesale.

## 1. Requirements

| # | Requirement |
|---|---|
| **R1** | A coding agent (Claude Code + Codex) writes **and executes** Java against modelkit/rulekit; the BA's NL conversation is unchanged. |
| **R2** | **No assumed JDK** on the user's machine — the runtime is bundled. |
| **R3** | The agent authors **typed Java**, not Groovy (the compiler is the correctness oracle, and Java has far more LLM training mass). Groovy stays the kernel's *internal* dependency, never the authoring language. |
| **R4** | **Diagnostics are the #1 priority** — structured, code-first, English, *learnable*. Compile, runtime, validation, and lint feedback land as one consistent envelope so the agent can understand and self-correct. |
| **R5** | **Safety via the host's OS sandbox**, not a bespoke in-JVM one. |
| **R6** | A **separate, standalone, experimental plugin** for Claude Code + Codex; the heavy bundle is **lazily downloaded** on first use. |
| **R7** | Agent-facing resources are **derived / CI-locked** (the project's no-drift discipline). |
| **R8** | **Licensing compliance** for an artifact that bundles both the kernel and a JDK. |

## 2. Findings & background

Everything below was measured or verified during the design session (2026-06-16/17), not estimated.

| # | Finding |
|---|---|
| **F1 — substrate** | The **source-code launcher** (`java App.java`) is the right substrate (not a REPL, not a build tool). **JDK 25 is worthwhile**, for two *proven* wins: **JEP 512** compact source files + instance `main` + `java.base` auto-import (ran with no `--enable-preview`, collapsing boilerplate the LLM can't fumble), and the **AOT cache** (`-XX:AOTCacheOutput`, JEP 514). |
| **F2 — runtime size** | jlink, stock OpenJDK 21, trimmed: `java.base` = **28 MB**, the 7-module set = **45 MB**, `+jdk.compiler` = **58 MB**, `+jdk.jshell` = 60 MB. The **same set from GraalVM nearly doubles it** (75–99 MB). **Therefore: jlink from stock OpenJDK (Temurin), never GraalVM.** |
| **F3 — bundle** | ≈ **80 MB** = ~60 MB runtime (OpenJDK 25 + `jdk.compiler`) + ~20 MB kits jars (55 jars; Groovy is 7.7 MB of it). The runtime is **per-OS**; the jars are OS-independent. **jlink cross-compiles** all three OS runtimes from one host (unlike native-image). |
| **F4 — diagnostics lever** | The in-process Compiler API (`ToolProvider.getSystemJavaCompiler()` + `DiagnosticCollector`) yields **structured `{code, line, col}`** objects (proven). But the message *locale* is bound by the JVM default — the API's per-call/file-manager locale args **do not** override it (proven: English requested, German returned under a German platform locale). So **`-Duser.language=en` is the only lever** for English prose, and the **`code` is locale-invariant** (`compiler.err.prob.found.req` in any locale) → **lead with the code**, treat prose as secondary. |
| **F5 — reuse** | The structured diagnostic vocabulary already exists at the **kit level** — `RkCode`, `Diagnostic`, `Problem`, `Span`, `DiagnosticSource`, `DiagnosticEnricher`, `ValidationResult` (in `rulekit/validate/`), `Suggest` (in `core/`); the `ResultEnvelope` shape is in cli. The Runner reuses these rather than inventing a new layer. |
| **F6 — operator mapping** | `Dsl.java`'s javadoc **already maps each builder method to its kernel operator** (`sum(...)` → `Sum(…)`), so `api.txt` (derived from that javadoc) carries the operator mapping for free. `operators.json`'s *unique* code-mode value is therefore the **gotchas + invalid→fix** pairs, not the mapping. |
| **F7 — dev-path probe** | The developer-path eval (n=1, 2026-06-05) showed code-against-API **erases the entire *syntactic* error class** (types/paths/shape caught at compile), but the **semantic traps survive** (polarity, iteration guard, empty-as-0) — navigably, via one javadoc sentence + the catalog gotchas. Its conclusion: "a full dev skill looks unnecessary." |
| **F8 — runtime-eval upside** | The JVM bundle contains Groovy + the kernel runtime, so it **can run `model eval`/`rule test`/`model compute`** — exactly what the native CLI structurally cannot do. Potentially `dmcode`'s strongest differentiator (see OQ2). |
| **F9 — safety norm** | Anthropic ([*How We Contain Claude*](https://www.anthropic.com/engineering/how-we-contain-claude), Claude Code Seatbelt/bubblewrap, the [`sandbox-runtime`](https://github.com/anthropic-experimental/sandbox-runtime) library) and the industry (Firecracker/gVisor microVMs; OpenAI Codex gVisor) **converge on OS/VM-boundary isolation, never custom in-process** — *"the software you build yourself is often the weakest component."* `SecurityManager` is removed in JDK 24, so in-JVM self-sandboxing isn't available anyway. |
| **F10 — catalog size** | `operators.json` = **109 operators / ~90 KB** → too large to pre-load; serve it **raw, on-demand** (the agent queries it), plus a small **derived gotchas trap-sheet** for the always-relevant-but-unprompted traps. |
| **F11 — landed support** | Two recent commits move toward this design: `ca85a78` (native reflection metadata now captured from the **JUnit suite**, not a curated script) and `13d96cd` (a **self-correction eval family** wired into the Layer-2 runner — a natural harness for OQ1). |

## 3. Design decisions

| # | Decision |
|---|---|
| **D1** | **Two tiers, separate plugins** — native `dmtool` (light, verbs) + `dmcode` (heavy JVM, code). Separate because it's an experiment: faster to iterate/release, clean positioning, a one-step kill switch, and it keeps the N6 eval's material boundary (the CLI skill) untouched. |
| **D2** | **Name = `dmcode`** (plugin + command). Chosen over `dmrun`: it names the modality (clean `dmtool` vs `dmcode` tiering), reads as an interpreter (`dmcode App.java`, like `python script.py`), avoids the "run a model = evaluate" collision, and is extensible to subcommands. |
| **D3** | **Java + source-launcher + stock OpenJDK 25** (F1, F2). |
| **D4** | **Bundle = minimal-JDK (incl. `jdk.compiler`) + kits jars**; per-OS; **lazy-downloaded on first `dmcode`** (with first-run AOT-cache generation). |
| **D5** | **A managed Runner**, not bare `java App.java`: compile **in-process** via the Compiler API (structured diagnostics, English via `-Duser.language=en`, **code-first**, cap/dedupe cascades, render the source line+caret) → run **in-process** (no fork — see D6) → **auto-surface `validate()` + the lint** (fixing the probe's discoverability gap) → emit **one `ResultEnvelope`-shaped JSON** with a `phase` discriminator. Learning aids: a `fix` per diagnostic (reuse the catalog `invalidExample.fix`), did-you-mean (`Suggest`), a **compile-code → API-guidance map**, and pointers to the skill/catalog for recurring traps. |
| **D6** | **Safety = inherit the host OS sandbox** (Claude Code Seatbelt/bubblewrap, Codex gVisor) + **self-defend at the app layer** (input validation, the typed builder, the promoted refactor gate, fail-closed). The classpath is naturally restricted (only the kits ship). **No custom in-JVM sandbox or scratch-CWD fork** (F9). A container is the option only for untrusted *non-agent* deployments. |
| **D7** | **Resources** (the discoverability layer that replaces `manifest`/`schema`): `api.txt` (doclet-derived, scoped to ~40 authoring types, signatures + **javadoc prose** + spliced locked snippets) · `samples/` (authoring regions extracted from **passing tests** via the existing `DocSnippetFreshnessTest` lock engine; operator-family parity; trap-correct) · `operators.json` (raw on-demand + a derived gotchas sheet) · an `App.java` template · the **skill** (judgment + the loop, minimal). All **derived/CI-locked**; built via a scoped doclet + the snippet-lock; assembled via `sync-plugin-assets.sh`; gated lightly (samples compile + signatures resolve + snippet-lock + the dev-path probe — *not* the CLI conformance matrix). |
| **D8** | **Licensing = an aggregate**: EUPL-1.2 (the embedded kernel) + **GPLv2-with-Classpath-Exception** (the bundled OpenJDK). The CPE shields our code → **not GPL-infected**; it is **not MIT** (already true once the kernel is embedded). Use a clean Temurin build; carry the OpenJDK license + source-pointer. Record in [`LICENSING.md`](LICENSING.md). |
| **D9** | **Sequence (B) before (A)** — build the developer-library resources first (provisioning-free, already probed); `dmcode` reuses `api.txt`/samples/skill wholesale. |

## 4. Open questions

| # | Question | Lean |
|---|---|---|
| **OQ1** | Measure `dmcode` head-to-head with the CLI at pass@k, with explicit **kill/graduate criteria**. | The self-correction eval family (`13d96cd`) is the harness. *Graduate* if it beats the CLI on composition tasks **and** ties on simple ones; *park* if it only ties at higher token/latency cost. **The central unmeasured question — resolve before building the full plugin.** |
| **OQ2** | Is **runtime-eval** (which native can't ship) the headline differentiator rather than authoring expressiveness? | Likely yes — explore showcasing it in samples/skill. |
| **OQ3** | Ship the **full `dmtool` verb surface on the bundle** too (incl. runtime-eval as verbs)? | Deferred — weigh identity-drift + conformance re-coupling. |
| **OQ4** | Compact-source (JEP 512) vs regular-class for the Compiler-API path. | Wrap the compact body (with a line-offset back-map), or accept regular classes for v0. |
| **OQ5** | `api.txt` token budget; always-loaded spine vs fetched-on-demand. | Apply the prompted/unprompted rule (as for `operators.json`). |
| **OQ6** | The `samples/` manifest — which tasks, operator-family parity, size. | — |
| **OQ7** | **Promote the refactor safety gate `cli/` → kits** so raw `EditSession` code can't unsafely delete/rename. | Required for safe code-mode authoring; the where-used primitive is already reusable in `adapter/`. |
| **OQ8** | First-run AOT-gen UX + Codex's unconfirmed PATH/env contract. | Deterministic `$PLUGIN_DATA/bin/dmcode` fallback. |
| **OQ9** | Release mirror choice. | **Resolved: a separate mirror (Option B) at graduation; the experiment publishes no marketplace** (see §6). |

## 5. Plan / sequencing (transient — delete on graduation)

1. **(B) developer-library resources first** — `api.txt`, `samples/`, the `llms.txt` API reference, the dev skill. Reused by `dmcode`.
2. **Spike the doclet** — the only genuinely new code; confirm a JDK-21 `jdk.javadoc.doclet` emits the scoped flat `api.txt`.
3. **The managed Runner** (D5) — Compiler-API compile + in-process run + auto-validate/lint + the envelope + the learning aids.
4. **The bundle** (D3/D4) — cross-jlink stock OpenJDK 25 (+`jdk.compiler`) + the jars + the Runner + the `dmcode` shim; first-run AOT-gen.
5. **The `dmcode` plugin** — Claude + Codex trees; `ensure-bundle.sh` (lazy); the skill + resources.
6. **Promote the refactor safety gate into the kits** (OQ7).
7. **Measure (A)** at pass@k vs the CLI with kill/graduate criteria (OQ1).
8. **Release prerequisite** — parameterize the release machinery and verify dmtool still ships unchanged (§6).
9. **Graduate** → stand up the separate mirror (§6).

## 6. Release & distribution

The shipped model is unchanged in shape from the native CLI: **plugin + skill + resources in the repo tree; the heavy bundle as a lazily-downloaded GitHub Release asset; licenses at the root.** Two phases:

### Phase 1 — experiment (no marketplace)
Publish nothing to a marketplace. Regular users see only `dmtool` (today's flow, untouched). Testers install `dmcode` **from local source** (the project already supports verified local-source plugin install on both hosts); its hook pulls the bundle from a Release tagged `dmcode-v0.0.x`. **Zero install-confusion, zero new release infra.** Keep the bundles as Release *assets* (never under the mirror's `--delete` tree control).

### Phase 2 — graduation (a separate mirror, Option B)
On graduation, stand up a **separate public mirror `a12-dmcode-releases`**. Options considered:

| | A — same mirror (`a12-dmtool-releases`) | **B — new mirror (`a12-dmcode-releases`)** |
|---|---|---|
| Setup | low (reuse) | higher (a repo + secret + config) |
| Ongoing ops | **higher/riskier** | **lower** |
| `--delete` rsync push | a two-product tree → a bad assemble can wipe the stable plugin | each mirror touches only its own tree |
| Layout | asymmetric (dmtool at root, dmcode in a subdir, doubled Codex) | symmetric clone (dmcode plugin at root) |
| Install UX | one marketplace lists *both* — possible but muddy, mixed Release tags | **marketplace = product**; add the one you want |
| Kill | surgical removal | **delete the repo** |

**A is rejected**: each of its problems (the `--delete` footgun, the asymmetry, the muddy install) is *fixable*, but every fix recreates the isolation B gives for free — so A's lower setup is a false economy. **B is the clean end state.** Install UX:

```
# B — each marketplace is one product
/plugin marketplace add mbackschat/a12-dmtool-releases   →  /plugin install dmtool
/plugin marketplace add mbackschat/a12-dmcode-releases   →  /plugin install dmcode
```

The B mirror tree (a symmetric clone of the dmtool mirror):

```
a12-dmcode-releases/
├── .claude-plugin/{marketplace.json (lists "dmcode", source "."), plugin.json}
├── hooks/{ensure-bundle.sh, hooks.json}          ← LAZY: fetch the bundle on first `dmcode`
├── skills/a12-dmcode/SKILL.md
├── resources/{api.txt, samples/, operators.json, App.java, gotchas.md}   ← small, in-tree
├── codex/{.codex-plugin/, hooks/, skills/, AGENTS.md}
├── .agents/plugins/marketplace.json              → ./codex
├── .github/workflows/build-dmcode-bundle.yml     ← cross-jlink all 3 OS from ONE runner
├── README.md   CHANGELOG.md
├── LICENSE(EUPL-1.2)  KERNEL-LICENSE  OPENJDK-LICENSE(GPLv2+CPE)  THIRD_PARTY_NOTICES  NOTICE
└── licenses/…
  └─ Releases:  dmcode-macos-arm64.tar.gz · dmcode-linux-x64.tar.gz · dmcode-windows-x64.zip · SHA256SUMS
```

### The prerequisite — parameterize before doubling
Standing up B by **copy-pasting** the release scripts would create drifting near-duplicates: `assemble-release-repo.sh` and `publish-release.sh` are hardcoded single-product, and `build-native.yml`'s `bootstrap-kernel` job would be duplicated. (Only `sync-plugin-assets.sh` is already parameterized — via its `PLUGINS` list — and is the model the others should match.) So **before B**:

1. Lift the common release machinery (`publish-release.sh`'s preconditions, stage/`--go-live`, the rsync-into-fresh-clone push, draft create, notes-from-CHANGELOG, the SHA256SUMS ordering trap, the `--latest` flip) into a **parameterized core + per-product config** (mirror, artifact list, build command, workflow name).
2. Parameterize `assemble-release-repo.sh`'s tree layout.
3. Extract `bootstrap-kernel` into a **reusable workflow** shared by both build jobs (the build jobs themselves differ — GraalVM native vs jlink bundle — and stay separate).
4. **Verify dmtool still releases unchanged** through the parameterized scripts (red/green, as its own step) *before* adding `dmcode` as a second config.

Then **B = a second config + a second mirror, not a fork of the scripts.**

### Build, versioning, size, licensing
- **Build:** reuse `bootstrap-kernel` (OS-independent jars), then **cross-jlink all three bundles from one Linux runner** — no mac-local step, no per-OS matrix (easier than the native binary).
- **Delivery:** the small plugin ships `ensure-bundle.sh`, which on first `dmcode` downloads the per-OS bundle, checksum-verifies, unpacks to `PLUGIN_DATA`, and generates the AOT cache. The AOT cache is **not** shipped.
- **Versioning:** an independent experimental line (`0.0.x`).
- **Size:** GitHub Releases handle it (3 × ~40 MB compressed).
- **Licensing:** the only new release task vs the native binary is carrying the **OpenJDK GPLv2+CPE license + source-pointer** alongside the existing EUPL/kernel bundle (D8).

## Related docs

- [`CLI-SPEC.md`](CLI-SPEC.md) — the verb tier and the refactor safety gate (the `cli/`-only gate OQ7 would promote).
- [`RELEASE-SPEC.md`](RELEASE-SPEC.md) — the plugin pattern (§6) and the developer-library wave (§11) `dmcode` sequences after.
- [`NATIVE-IMAGE-GRAALVM.md`](NATIVE-IMAGE-GRAALVM.md) — why the native CLI can't run code or runtime-eval (the constraint that motivates this tier).
- [`LICENSING.md`](LICENSING.md) — the home for the EUPL + GPLv2+CPE aggregate (D8).
- [`EVAL-SPEC.md`](EVAL-SPEC.md) — the developer-path harness (OQ1).
- [`API-GUIDE.md`](API-GUIDE.md) — the library surface `api.txt`/samples derive from.
