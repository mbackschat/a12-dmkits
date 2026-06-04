# a12-rulekit — CLI spec (the modeller/BA standalone front-end)

The component spec for the standalone CLI (N10). The proposal's [§14/§16](PROJECT-PROPOSAL.md) state intent; [`ARCHITECTURE.md`](ARCHITECTURE.md) places it among the subprojects; this spec holds the detail — the verb surface, the JSON I/O contract, the **dev/test workflow that keeps the inner loop fast**, the sizing, and the two packaging phases. Status flips on the [`API-POC-STUDY.md` §9](API-POC-STUDY.md) board.

## 1. Purpose & persona

The CLI is the **modeller/BA's** interaction surface (N10): one engine (core + adapter), driven by an agent through **verbs with JSON in/out**, the JVM bundled and invisible. It is *not* the developer's surface — a developer uses rulekit as a library in their own project (no CLI). The CLI never shells out to `javac`; the agent authors a rule as **JSON**, which the CLI deserializes *through the typed builder*, so the BA gets the builder's pre-checks + the kernel `Diagnostic`s (F4a) at runtime — the runtime analogue of the developer's compile-time steering.

## 2. Module shape

- **`:cli` Gradle subproject**, `application` plugin. Depends on **core** now; **+ adapter** joins when the ingest/explain/whereUsed verbs land (the adapter pulls the internal kernel read tier — fine for an *app*; N8 confines the internal tier to adapter/cli, never core).
- **Testability split — the key to the fast loop:**
  - `Cli.run(String[] args, Appendable out, Appendable err) -> int` — **pure and testable**: no `System.exit`, no `System.out` capture, returns an exit code, writes to the passed sinks.
  - `CliMain.main(String[])` — the thin shell: `System.exit(Cli.run(args, System.out, System.err))`.
  - Each verb is a function producing a `String`/stream — so the BA behavior is unit-testable in-process, no packaging, no spawned process.

## 3. Dev/test workflow — keep packaging out of the inner loop

The self-contained binary (jlink/jpackage) only *provisions a JVM*; it adds **zero behavior** and takes ~a minute to build. So it is **never** in the code-build-test cycle. Three speeds, by what's changing:

| Changing | Command | Speed |
|---|---|---|
| **CLI/verb code** | `./gradlew :cli:test` — JUnit verb tests over `Cli.run(...)` | sub-second (warm daemon); the primary loop |
| code, want a real process | `./gradlew -q :cli:run --args="dump model.json fields"` | seconds (incremental; skips packaging) |
| **only inputs** (model/rule JSON) | `./gradlew :cli:installDist` once, then `build/install/cli/bin/cli …` | ~1s JVM startup, no rebuild |

Rules of the loop: never `clean`; never `installDist`/`shadowJar`/jlink/jpackage to test *behavior* (only to produce a distributable). Point an agent dry-run at the one-time `installDist` launcher so it re-invokes with no rebuild cost.

*Future option (only if multi-call latency bites):* a **resident/stdin mode** — the CLI stays up and reads commands on stdin, paying JVM startup once across many agent calls (the "stdin → run" shape of study finding 6).

## 4. Verb surface

JSON/JSONL out on stdout (pipeable, `jq`/`grep`-able); non-zero exit on error with a `Diagnostic` JSON on stderr. Initial + planned:

| Verb | In | Out | Status | Engine |
|---|---|---|---|---|
| **`dump`** | model.json [+ artifact] | JSONL / markdown (the finding-13 artifacts) | ✅ 2026-06-05 | core |
| **`describe`** | model.json | model card JSON (F9a) | ✅ 2026-06-05 | core |
| **`validate`** | model.json | `{valid, diagnostics[]}` JSON (model-level; rule-level with the rule-spec) | ✅ 2026-06-05 | core |
| **`check`** | model.json + `--field`/`--condition`/`--code` [`--group`] | `{valid, diagnostics[]}` JSON (authoring feedback, no persist) | ✅ 2026-06-05 | core |
| `batch` | a list of ops (stdin/file) | results array | **planned — build with the benchmark runner** (amortizes JVM startup + kernel warm-up across many ops; the StringTemplate group cache is per-process — see KERNEL-FINDINGS) | core |
| `ingest` | model.json + rule id | rulekit AST (JSON) / `Opaque` | planned | adapter |
| `explain` | model.json + rule/condition | `RuleExplanation` JSON | planned | core |
| `add` | model.json + rule-spec JSON | validated DM-JSON | planned | core |
| `diff` | model.json + rule id + modified spec | `RenderedDiff` JSON | planned | adapter |
| `write-back` | model.json + rule id + spec | rewritten DM-JSON | planned | core |

## 5. The `dump` verb (first)

The agent-files-as-API surface (finding 13). Artifacts: three JSONL (one self-contained JSON object per line — `grep` and `jq` both work, only matching lines enter an agent's context) plus one read-whole markdown skeleton.

```
cli dump <model.json> fields              # one artifact → stdout (pipe: | grep -i fee)
cli dump <model.json> groups|rules|card
cli dump <model.json> --output ctx/       # all four artifacts → files in ctx/
cli dump <model.json>                      # no artifact, no --output → model-card to stdout
```

Line shapes (JSONL stays pure data — the model id/version stamp lives in `model-card.md`, the read-whole artifact):

- **fields.jsonl** — `{"path","name","kind","group","repeatsUnder",("scale"),("values")}` (`scale` for NUMBER, `values` for ENUMERATION; `repeatsUnder` = nearest repeatable ancestor or null).
- **groups.jsonl** — `{"path","name","repeatable","repeatsUnder"}`.
- **rules.jsonl** — `{"rule","group","slot":"errorCondition","code","errorField","severity","condition","referencedFields":null,"resolution":"heuristic"}`. The core cannot resolve refs from raw condition text (that needs the adapter's parse), so `referencedFields` is honestly `null` / `resolution:"heuristic"` until the adapter lands.
- **model-card.md** — markdown, stamped with model id + version + counts + the group skeleton (repeatability) + enum domains.

Implementation: fields/groups/card from core (`BoundModel.ofJson` → `fields()`/`groups()`/`nearestRepeatableAncestor()`); rules from a read-only walk of the authored DM-JSON (Jackson), since core does not enumerate existing rules. Serialization lives in `:cli`, **not** core (core exposes the programmatic projections; the CLI renders them to files).

## 6. Sizing (measured 2026-06-05)

- **Application jars: ~18.5 MB** (51 jars — kernel subset + Jackson + Apache commons + antlr4-runtime + Groovy; Groovy alone ~7–8 MB).
- **jlink-trimmed JRE: ~45–70 MB**; jpackage launcher +1–3 MB.
- **Self-contained app-image: ~65–90 MB / OS; compressed download ~30–50 MB** — unremarkable for a "download one thing" tool (smaller than a typical Electron app).
- **Shrink levers:** drop kernel jars the verbs never exercise (verify with `jdeps`/coverage — Groovy + `kernel-core-runtime`/codegen may be unused by the verb paths; biggest win); jlink `--compress=zip-9 --strip-debug --no-man-pages --no-header-files`; ship per-OS only. With the Groovy/runtime trim the download could approach ~25–35 MB.

## 7. Packaging phases & licensing

- **Phase A — dev/test:** `application` plugin → `run` / `installDist` / `distZip`. Uses the dev's JDK + the kernel from `mavenLocal`. All CLI + LLM-support work happens here. CI-testable.
- **Phase B1 — distribution, default: jlink + jpackage app-image.** A self-contained, signed app-image. Guaranteed to work (real JVM — no closed-world issues). Measured JDK-module footprint (`jdeps --print-module-deps`, 2026-06-05): `java.base, java.compiler, java.desktop, java.management, java.prefs, java.scripting, java.sql` → `jlink --add-modules …` builds a ~50–65 MB runtime; bundle the 53 classpath jars alongside (no app modularization needed). macOS notarization / Windows signing apply. Adds no behavior; only provisions the JVM.
- **Phase B2 — GraalVM native-image: spiked 2026-06-05 → FEASIBLE for rulekit's checkConsistency/parse verbs, with care.** Full root-cause investigation in [`KERNEL-FINDINGS.md`](KERNEL-FINDINGS.md) "`checkConsistency` runs runtime code generation". Result (GraalVM 21.0.11, tracing agent + `--no-fallback`):
  - **Build: ✅** real binary, **36 MB**, ~1m28s. **Startup: ✅ ~37 ms vs ~600 ms JVM (~16×)** — the payoff (an agent invoking the CLI many times).
  - **Correctness: ✅ once template resources are bundled.** `checkConsistency` runs `CodeGenerationRule` → emits Groovy *source* via **ANTLR StringTemplate v4** (not Groovy's engine; "groovy" = target language) — StringTemplate compiles to its own *interpreted* form, so it's native-compatible. The first build crashed (`Error while instantiating template "…/StringParameter"`) on a **missing resource**: `base.stg`'s `import "baseDir"` loads `baseDir/*.st` *by name*, which the tracing agent misses. Rebuilding with `-H:IncludeResources='internal/templates/.*'` → the binary **passes on all self-contained models, output byte-identical to the JVM**. (The earlier "not viable / content-dependent" reading was wrong — an artifact of only testing one model on native.)
  - **The one hard ceiling:** `checkConsistency` never compiles Groovy, but *executing* a model's validators (`getValidator`/on-the-fly testing) would — `GroovyClassLoader.parseClass`, which native-image can't do. rulekit never executes validation, so it's clear; but **a native rulekit must never depend on the kernel's runtime validation execution.**
  - **Build requirements** (for productionizing B2): broad resource inclusion (`internal/templates/**`, not agent-only — the agent misses import-indirected `.st` files), agent-captured reflect/proxy config + the reachability-metadata repo, `--no-fallback`, and **held-out-model testing** (closed-world risk).
  - **Decision:** **B1 (jlink) ships first** (zero AOT risk); **B2 (native-image) is a proven-feasible upgrade** for the startup win, gated on the resource-inclusion + held-out-test discipline above (and never crossing into validation execution). The resident/stdin mode (§3) remains the fallback if B2 is deferred. *(Spike artifacts/scripts live under the gitignored `build/native-spike/`; not committed — they hardcode a local GraalVM path.)*

**Java Modules (JPMS) verdict (2026-06-05):** use **jlink** (the module *tooling* for runtime trimming, B1) — yes; **full app modularization** (`module-info` everywhere) — no: 53 non-modular jars (kernel + Groovy + commons + antlr) with near-certain split packages across the many `kernel-*` jars block JPMS, for ~zero payoff while everything below is non-modular. jlink runs fine in classpath/automatic-module mode.

**Licensing (settled — [`LICENSING.md`](LICENSING.md)):** rulekit source is **MIT**; the bundled distributable (Phase B) embeds the kernel and so ships under **EUPL-1.2** (redistribution is granted by the kernel LICENSE), carrying the kernel `LICENSE`/`NOTICE`/`THIRD_PARTY_NOTICES`/`licenses/` + a source pointer, and no "mgm A12" branding. Phase A (run/installDist on a dev box) redistributes nothing, so it carries no EUPL obligation.
