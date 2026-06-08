# a12-dmkits — CLI spec (the modeller/BA standalone front-end)

The component spec for the standalone CLI (N10). The proposal's [§14/§16](PROJECT-PROPOSAL.md) state intent; [`ARCHITECTURE.md`](ARCHITECTURE.md) places it among the subprojects; this spec holds the detail — the verb surface, the JSON I/O contract, the **dev/test workflow that keeps the inner loop fast**, the sizing, and the two packaging phases. Status flips on the [`API-POC-STUDY.md` §9](API-POC-STUDY.md) board. Verifiable, runnable demos of the verbs: [`CLI-TOUR-SPEC.md`](CLI-TOUR-SPEC.md) ↔ [`../examples/`](../examples/).

## 1. Purpose & persona

The CLI is the **modeller/BA's** interaction surface (N10): one engine (core + adapter), driven by an agent through **verbs with JSON in/out**, the JVM bundled and invisible. It is *not* the developer's surface — a developer uses rulekit as a library in their own project (no CLI). The CLI never shells out to `javac`; the agent authors a rule as **JSON**, which the CLI deserializes *through the typed builder*, so the BA gets the builder's pre-checks + the kernel `Diagnostic`s (F4a) at runtime — the runtime analogue of the developer's compile-time steering.

## 1a. Design principle — self-explaining & explorable (standing requirement, 2026-06-05)

**A cold agent, given only the binary, must be able to explore the tool.** Discoverability is a property of the *CLI*, not the N1 skill — the skill stays minimal (it teaches judgment: polarity, the empty-as-0 trap; not the verb/parameter catalog), so the tool has to carry its own description. Three layers, each a separate concern:

| Layer | Question it answers | Mechanism | State |
|---|---|---|---|
| **Verbs** | "what can this tool do?" | `--help` (root) + `manifest` (machine-readable verbs × summary) | ✅ |
| **Parameters** | "how do I call verb X, and what do the args mean?" | per-verb `--help` + the `manifest` `params:[{name, required, description}]` | ✅ |
| **I/O shape** | "what does verb X emit / expect?" | `schema <name>` — outputs (explain/describe/validate/read) + inputs (add/batch) | ✅ |
| **DSL vocabulary** | "what operators exist and what do they mean?" | `operators` (list + per-operator full semantics) | ✅ |

**Implementation: picocli** (§2) — one `@Command`/`@Option(description=…)` declaration per verb drives parsing, `--help`, *and* the `manifest`, so the help cannot drift from behavior (the most-stable option; the Java-standard, GraalVM-native-ready framework). Rules of thumb when touching the CLI: a new verb is a `@Command` class added to `RootCommand` (it gets `--help` + a manifest entry automatically) and ships an output schema in the same change; option descriptions are the single source of help text; nothing about the verb surface is discoverable *only* by reading the skill or the source. Drift is caught at build — `ManifestVerbTest` (verb-set discoverability), `SchemaVerbTest` (schema↔output key coverage) — the F11 discipline. Status of the layers is in §4 (verb table) and the §9 board.

## 2. Module shape

- **`:cli` Gradle subproject**, `application` plugin. Depends on **core + adapter** (the adapter, added 2026-06-05, powers the `read`/`explain` read-back verbs; it pulls the internal kernel read tier — fine for an *app*; N8 confines the internal tier to adapter/cli, never core).
- **picocli** drives the command tree (added 2026-06-05, §1a): `RootCommand` + one `@Command` class per verb, each a `Callable<Integer>` extending `BaseCommand` (which supplies the `out`/`err` writers from the seam). `picocli-codegen` (annotation processor) auto-emits the GraalVM native-image reflection config under `META-INF/native-image/`, so the B2 native build needs no hand-written config.
- **Testability split — the key to the fast loop:**
  - `Cli.run(String[] args, Appendable out, Appendable err) -> int` — **pure and testable**: no `System.exit`, no `System.out` capture, returns an exit code, writes to the passed sinks.
  - `CliMain.main(String[])` — the thin shell: `System.exit(Cli.run(args, System.out, System.err))`.
  - Each verb is a function producing a `String`/stream — so the BA behavior is unit-testable in-process, no packaging, no spawned process.

## 3. Dev/test workflow — keep packaging out of the inner loop

The self-contained binary (jlink/jpackage) only *provisions a JVM*; it adds **zero behavior** and takes ~a minute to build. So it is **never** in the code-build-test cycle. Three speeds, by what's changing:

| Changing | Command | Speed |
|---|---|---|
| **CLI/verb code** | `./gradlew :cli:test` — JUnit verb tests over `Cli.run(...)` | sub-second (warm daemon); the primary loop |
| code, want a real process | `./gradlew -q :cli:run --args="export model.json fields"` | seconds (incremental; skips packaging) |
| **only inputs** (model/rule JSON) | `./gradlew :cli:installDist` once, then `build/install/cli/bin/cli …` | ~1s JVM startup, no rebuild |

Rules of the loop: never `clean`; never `installDist`/`shadowJar`/jlink/jpackage to test *behavior* (only to produce a distributable). Point an agent dry-run at the one-time `installDist` launcher so it re-invokes with no rebuild cost.

*Future option (only if multi-call latency bites):* a **resident/stdin mode** — the CLI stays up and reads commands on stdin, paying JVM startup once across many agent calls (the "stdin → run" shape of study finding 6).

## 4. Verb surface

JSON/JSONL out on stdout (pipeable, `jq`/`grep`-able); non-zero exit on error with a `Diagnostic` JSON on stderr. Initial + planned:

| Verb | In | Out | Status | Engine |
|---|---|---|---|---|
| **`export`** | model.json [+ artifact] [`--out-dir`] | JSONL / markdown (the finding-13 artifacts); one to stdout or all four to `--out-dir`. **Renamed from `dump` 2026-06-08** | ✅ 2026-06-05 | core |
| **`describe`** | model.json | model card JSON (F9a) | ✅ 2026-06-05 | core |
| **`validate`** | model.json | `{valid, diagnostics[]}` JSON (model-level; rule-level with the rule-spec) | ✅ 2026-06-05 | core |
| **`check`** | model.json + `--field`/`--condition`/`--code` [`--group`] | `{valid, diagnostics[]}` JSON (authoring feedback, no persist) | ✅ 2026-06-05 | core |
| **`eval-doc`** | model.json `--doc <document.json>` [`--rule` \| `--condition`+`--field`] [`--include-dir`] | `{fired[], messages[], rule?}` — the **runtime** verdict: which rules **fire on a document instance** (`validateFull`), optionally narrowed to one existing rule or an injected candidate. The peer of `check` (does it *fire*?) vs (does the kernel *accept* it?). Computations are NOT run (a computed field reads as the doc supplies it) | ✅ 2026-06-09 | adapter |
| **`compute-doc`** | model.json `--doc <document.json>` [`--include-dir`] | `{computed[]}` — what each computed field **becomes** on a document instance (`compute`): value / cleared / error. Read-only (never writes `--doc`). The computation peer of `eval-doc` | ✅ 2026-06-09 | adapter |
| `batch` | a JSON array of ops (file) | results array | ✅ (2026-06-05) — runs many ops in one process, amortizing JVM startup + the kernel's per-process StringTemplate warm-up (see KERNEL-FINDINGS); each result is self-describing for attribution | core |
| **`add`** | model.json + spec JSON [`--output`] | the persisted DM-JSON (stdout) | ✅ 2026-06-05; **computation-spec ✅ 2026-06-08** (persists a computation when the spec has `computedField`) | core (`write`) |
| **`read`** | model.json (`--rule <rulePathInModel>` \| `--all`) | one rule: `{converted, condition}` / `{converted:false, …}`; `--all`: `{rules{…}, computations{…}, summary}` — the unified whole-model read (validation rules + computations, disjoint). The F10 read-back, surfaced as a read verb. **Renamed from `ingest` 2026-06-08** (CLI verb + the `KernelAdapter.read`/`ReadResult` API alike — one vocabulary) | ✅ 2026-06-05; `--all` ✅ 2026-06-08 | adapter |
| **`explain`** | model.json (`--rule <rulePathInModel>` \| `--computation <computationPathInModel>`) | rule: `RuleExplanation` JSON (structural `tree` + flat operators with meaning/gotchas, refs, iteration, gloss); computation: `ComputationExplanation` JSON (computed field+kind, common precondition + per-alternative precondition/operation, messages, gloss) | ✅ 2026-06-05; `--computation` ✅ 2026-06-08 | adapter + core |
| **`where-used`** | model.json `--entity <entityPath>` | `{entity, rules[], computations[], count}` — rules **and** computations whose condition directly references the field/group (F9a exact, `getDirectlyReferencedEntities`); kept in separate arrays since `getAllRules()` ⊇ computations | ✅ 2026-06-07; computations ✅ 2026-06-08 | adapter |
| **`schema`** | `[<name>]` (explain/describe/validate/read outputs; add/batch inputs) | the JSON Schema of that verb's I/O, **on demand** (lists names when bare); documents the explain `tree.operator → operators[].id` backlink | ✅ 2026-06-05 | cli (resources) |
| **`operators`** | `[<id>] [--kind] [--keyword]` | the DSL **operator catalog** — a filterable list, or one operator's full semantics (keywords EN/DE, operands, meaning, constraints, gotchas, valid/invalid example). The proactive complement to `explain` | ✅ 2026-06-05 | core catalog |
| **`manifest`** | — | machine-readable map of **every verb + its parameters** (name, required, meaning), derived from the picocli model so it can't drift | ✅ 2026-06-05 | cli (picocli) |
| `modify` | model.json + (`--rule` + `--condition` \| `--computation` + `--spec`) [`-o`] [`--dry-run`] | `RenderedDiff` JSON + **writes the modified model** | ✅ 2026-06-08 — the F8 re-express loop, and the **sole** re-express verb: validates then writes **in place** by default (`-o <file>` redirects); **`--dry-run`** previews read-only (writes nothing). A separate read-only `diff` verb existed briefly (2026-06-08) but was retired the same day — redundant with `modify --dry-run` | adapter |

### 4a. Verb & option naming — decisions (2026-06-08 audit)

A pass over every verb/option for intuition + CLI convention. **Changed** (the intuition-breakers):

| Was | Now | Why |
|---|---|---|
| `ingest` | **`read`** | "ingest" means *import data in* — but the verb *reads/renders* a stored rule (the opposite). Renamed at the CLI **and** the adapter API (`KernelAdapter.read`, the `ReadResult` type) — one vocabulary, no porcelain/plumbing split. |
| `dump` | **`export`** | "dump" read as a raw blob; the verb's job is to *export* the model's artifacts. |
| `export --output <dir>` | **`--out-dir <dir>`** | the flag is a **directory** (all four artifacts); `add`/`modify` keep `--output`/`-o` for a **file**, so the same flag never means two target kinds. |
| one re-express verb, write-default | **`modify`** (`--dry-run` previews) | a brief read-only `diff` verb (2026-06-08) was retired the same day — identical code path + output to `modify --dry-run`, so it was pure redundancy; see §5c. |

**Reviewed, kept** (defensible — recorded so it isn't re-litigated): `check` vs `validate` (candidate vs existing — the names read alike, but `--help` carries the distinction); `add`/`modify` taking a computation as a `--spec` *file* but a rule *inline* (`--condition`) — a computation is multi-part + needs messages; `where-used --entity` vs `--field`/`--group`/`--rule` elsewhere (`--entity` is the field-*or*-group generalization that verb needs); noun-query verbs `schema`/`operators`/`manifest` mixed with action verbs (widely accepted — git/docker do it).

## 5. The `export` verb (first)

The agent-files-as-API surface (finding 13). Artifacts: three JSONL (one self-contained JSON object per line — `grep` and `jq` both work, only matching lines enter an agent's context) plus one read-whole markdown skeleton.

```
cli export <model.json> fields              # one artifact → stdout (pipe: | grep -i fee)
cli export <model.json> groups|rules|card
cli export <model.json> --out-dir ctx/      # all four artifacts → files in ctx/
cli export <model.json>                     # no artifact, no --out-dir → model-card to stdout
```

Line shapes (JSONL stays pure data — the model id/version stamp lives in `model-card.md`, the read-whole artifact):

- **fields.jsonl** — `{"path","name","kind","group","repeatsUnder",("scale"),("values")}` (`scale` for NUMBER, `values` for ENUMERATION; `repeatsUnder` = nearest repeatable ancestor or null).
- **groups.jsonl** — `{"path","name","repeatable","repeatsUnder"}`.
- **rules.jsonl** — `{"rule","group","slot":"errorCondition","code","errorField","severity","condition","referencedFields":null,"resolution":"heuristic"}`. The core cannot resolve refs from raw condition text (that needs the adapter's parse), so `referencedFields` is honestly `null` / `resolution:"heuristic"` until the adapter lands.
- **model-card.md** — markdown, stamped with model id + version + counts + the group skeleton (repeatability) + enum domains.

Implementation: fields/groups/card from core (`BoundModel.ofJson` → `fields()`/`groups()`/`nearestRepeatableAncestor()`); rules from a read-only walk of the authored DM-JSON (Jackson), since core does not enumerate existing rules. Serialization lives in `:cli`, **not** core (core exposes the programmatic projections; the CLI renders them to files).

## 5b. The `check` and `add` verbs (the authoring loop)

- **`check`** — submit a candidate rule, get the real kernel's verdict, persist nothing: `check <model.json> --field <fieldPathInModel> --condition "<DSL>" --code <errorCode> [--group <groupPathInModel>] [--severity] [--name]`. Wraps core `RuleValidator.check`; `--field` is the error field's path **inside the model** (e.g. `/Group/Field`) — a model entity path, **not** a file path (the model file is the positional `<model.json>`). The kernel's `../`-relative form and `--group` (the iteration scope, default = field's parent) are computed for you. **Help-term clarity (2026-06-06):** the metavars name the entity kind (`<fieldPathInModel>`/`<groupPathInModel>`/`<errorCode>`, and `--rule <rulePathInModel>` on `explain`/`read`/`modify`) rather than a generic `<path>`/`<id>`, and each model-entity option's description contrasts it with the model file — so an agent can't conflate the two (an N6 run had an agent pass the model file path into `--field`; the old `<absolutePath>` metavar read like a filesystem path, and the `startsWith("/")` guard let a Unix file path through). A pre-flight resolves `--field`/`--group`/`--rule` against the loaded model (`ModelPaths`) and fails fast when a path doesn't exist — catching the file-path slip and typos before the kernel's generic error. A rule/field is referenced by its **full name = the name-path** (`getFullName()`), **not** its `id` (an opaque hash in real models — see [`KERNEL-INTEGRATION-NOTES.md`](KERNEL-INTEGRATION-NOTES.md) §"getId is opaque" / F-IDX).
- **`add`** — persist a rule from a JSON **rule-spec**, completing the loop (check → add → saved model): `add <model> <spec.json> [--output <file>]`. Spec carries the full envelope:
  ```json
  { "field": "/Subscription/Billing/EffectiveFee", "condition": "[EffectiveFee] < [BaseFee]",
    "code": "EFFECTIVE_FEE_BELOW_BASE", "severity": "ERROR", "name": "…",
    "messages": [ {"locale":"en_US","text":"…"}, {"locale":"de_DE","text":"…"} ] }
  ```
  Flow: kernel-validate the condition → if rejected, emit `{persisted:false,diagnostics}` to **stderr**, exit 1, persist nothing → else inject with the **real** messages and **re-validate the persisted model** (which catches message/locale-coverage that the condition-check masks with placeholders) → emit the new DM-JSON to **stdout** (or write `--output`), exit 0.
- **`add` a computation — ✅ (2026-06-08).** When the spec carries a `computedField` (vs a rule-spec's `field`/`condition`), `add` persists a **computation** instead — same flow (kernel-validate → persist → re-validate). The computation-spec is the calculated field + `alternatives` (≥1 `{precondition?, operation}`, DSL text) + `messages`, optional `commonPrecondition`/`name`: `{ "computedField": "/Order/Total", "alternatives": [ {"operation":"[Subtotal] + [Tax]"} ], "messages": [ … ] }`. Parsed by `cli.ComputationSpec` into a `DraftComputation`, validated via `RuleValidator.check(…, DraftComputation, resolver)`, persisted via the slot-based `ModelWriter.addComputation(json, draft, messages)`. Both shapes are in `schema add` (a `oneOf`).
- **Write path:** `add` builds on the generalized, **dsl-free `write.DmJson`** primitives (`insertIntoGroup`/`findElement`) — the shared model-core write surgery the future modelkit reuses (fork #8); the `Rule`/`Computation`-node shapes stay in `write.ModelWriter` (the computation node is built from a `DraftComputation`, so the typed and slot-based writers share it).
- **Inverted-requiredness lint backstop — ✅ on `add` and `check` (2026-06-05).** The shared `LintBackstop` helper reads a rule's condition (F10) → AST → `Linter.lint(model, errorField, condition)` and surfaces any model-context findings (chiefly **`RK_INVERTED_REQUIREDNESS`** — the automated backstop for the skill's #1 polarity teaching; the kernel accepts both polarities silently). `add` runs it on the just-persisted rule and emits findings as **warnings on stderr**; `check`, which persists nothing, runs `LintBackstop.lintCandidate` — a **temporary in-memory injection** (persist with placeholder messages, load, read) — and merges the findings into its `diagnostics` array as WARNINGs, so `valid` never flips. Best-effort throughout: an `Opaque` (unmodeled) condition or any hiccup is silent — a kernel-valid rule is never blocked.
- **`modify` — ✅ (2026-06-08).** The F8 modify-by-re-express loop over the CLI, and the **sole** re-express verb (rules + computations). `modify <model.json> --rule <rulePathInModel> --condition "<new DSL>"` re-expresses a rule's condition; `modify <model.json> --computation <name> --spec <computation-spec.json>` re-expresses a whole computation (the `--spec` is the same computation-spec `add` reads; it must re-express the *same* computation — its `computedField`/`name` derive `<name>`). It applies the change (`ModelWriter.replaceCondition`/`replaceComputation`), **validates** by loading the modified model (a kernel-rejected re-expression exits 1 with `{valid:false}`, nothing on stdout — use `check` for diagnostics), then emits a **`RenderedDiff`** (`before`/`after`/`changed`/`lossiness` — structured render-vs-render when `Converted`, kernel-canonical textual when `Opaque`, via the adapter's `diff`/`diffComputation`) and **writes the modified model back** — in place by default, to `-o <file>` if given, or — with **`--dry-run`** — not at all (a read-only preview). Exactly one mode is required. (A separate read-only `diff` verb existed briefly on 2026-06-08 — same `RenderedDiff`, it just never wrote — and was retired the same day as redundant with `modify --dry-run`.)
### 5c. Multi-file models — `--include-dir` (F-RES, ✅ 2026-06-08)

Every model-loading verb (`export`, `describe`, `validate`, `check`, `add`, `read`, `explain`, `where-used`, `modify`) accepts **`--include-dir <dir>`** (repeatable) — the directory tree(s) holding the document models a multi-file application `include`s / imports type-definitions from. A reference resolver indexes every document model under the tree(s) by `header.id` and serves it to the kernel's expand step.

- **Default = the directory of `<model.json>`** — sibling models resolve with no flag (the common workspace layout). `--include-dir` points elsewhere when the included models live in a separate tree.
- **Strictly safer than the old self-contained default** it replaces: a directory resolver is simply never called when the model has no references, so single-file models behave exactly as before; a multi-file model that used to fail to load (`"model references a separate model …"`) now resolves.
- Wired via the `IncludeDirOption` picocli mixin (so it appears only on model-loading verbs, not `operators`/`manifest`/`schema`); `check`/`add` thread the resolver into `RuleValidator.check(modelJson, draft, resolver)` and the `LintBackstop`. `batch` inherits it per-op (each op is a full `Cli.run`, so pass `--include-dir` in the op's `args`). Locked by `cli.IncludeResolverVerbTest`.

- **`batch` — ✅ (2026-06-05).** `batch <ops.json>` runs a JSON array of ops, each `{"verb","args":[…],"id"?}`, through the same `Cli.run` seam in **one process** — so the kernel's one-time StringTemplate warm-up (~497 classes on the first `checkConsistency`) and JVM startup are paid once, not once per spawned CLI. The enabler for the N6 benchmark runner (`eval/benchmark.workflow.js`, which batches the per-candidate re-checks). Emits a JSON array of per-op results; one op's failure never aborts the rest. **Exit:** batch-level errors (missing/malformed file, not an array) exit 2; otherwise exit 0 with each op's own exit in the array. **Attribution (the failure mode tested for):** each result is **self-describing** — an explicit `op` index, the caller's optional `id`, the `verb`, and the **echoed `args`** sit alongside that op's `result`/`stderr`, so a diagnostic is never mis-attributed even when results are reordered or diagnostics flattened. Diagnostics are scoped per op (each op runs against a fresh output buffer — no cross-op leakage). One residue: a verb's *own* diagnostics may still carry a synthetic rule path (e.g. `check`'s default `CheckRule`), so to disambiguate *inside* the diagnostic too, pass that op's `--name`.

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
