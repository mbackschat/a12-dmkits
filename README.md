# a12-dmkits

Author, read, and safely edit the **rules** and **structure** of A12 Kernel document models — through a self-describing command-line tool (**`dmtool`**), or as typed Java code. What you author is confirmed by the **real A12 kernel**, so it is *accepted*, not merely plausible; what you run — which rules fire on a document — is evaluated by a clean-room, kernel-exact **interpreter** (JVM + JS). Models also import from and export to **JSON Schema / OpenAPI**. Rich specs and result envelopes are JSON; other inputs use flags, and artifact/catalog modes explicitly declare JSON, text, or empty stdout.

> **Not an official A12 artifact.** A personal exploration, not affiliated with or endorsed by mgm — currently a private project, built using GPT 5.5. Shipped code binds only to the kernel's **public** OSS surface — no internal systems. (An opt-in, Java-only adapter module may read the kernel's internal AST tier under strict guardrails.)

**What's in the box** — one line each; the sections below carry the detail:

- **rulekit** — validation rules & computations as typed, model-bound Java code: author, read back, re-express
- **modelkit** — the model's **structure**, the same way: fields, groups, type definitions, includes, document config
- **dm.edit** — `EditSession`: structure + rule edits on one in-memory model, behind a single kernel gate
- **schemakit** — JSON Schema / OpenAPI ⇄ document model, import and export
- **the interpreter** — **[PUBLIC RELEASE REPOSITORY →](https://github.com/mbackschat/a12-interpreter-releases)** — kernel-free runtime evaluation (validate / compute); one engine for the JVM, native binary, Node.js, and browser, with a production browser JavaScript entry of about 290 kB gzip
- **the portable checker** (`a12-consistency`) — **[PUBLIC RELEASE REPOSITORY →](https://github.com/mbackschat/a12-consistency-releases)** — kernel-free *consistency* checking: would A12 reject this model? One Kotlin Multiplatform implementation for the JVM, Node and the browser, answering `INVALID` or `INCONCLUSIVE` — and, deliberately, never `VALID`
- **`dmtool`** — **[PUBLIC RELEASE REPOSITORY →](https://github.com/mbackschat/a12-dmtool-releases)** — the CLI over all of it: self-describing, machine-readable by default, explicitly profiled artifacts, agent-first

<p align="center">
  <a href="assets/infographics/a12-dmkits-toolchain.avif">
    <img src="assets/infographics/a12-dmkits-toolchain.avif" alt="A12 dmkits toolchain from typed authoring through Kernel acceptance to runtime evaluation" width="720">
  </a>
</p>
<p align="center"><sub>Click the infographic to open the full-resolution image.</sub></p>

---

<!-- shared:dmtool-story — canonical HERE; mirrored verbatim into plugin/README.md (guarded by SharedReadmeRegionTest). Keep it link-light: NO docs/ links (the public mirror has no docs/ tree). -->
## Why `dmtool`

An A12 document model carries **validation rules** and **computations** whose logic is a condition in the kernel's expression language — *"a delivery date must not be before the order date"* becomes a string like:

```
AllFieldsFilled(OrderDate, DeliveryDate) And DifferenceInDays(OrderDate, DeliveryDate) < 0
```

Writing these by hand is fragile: a mistyped path, a wrong operator or decimal scale, or — most insidious — **inverted polarity** (the condition true on the *requirement* instead of the *violation*) slips through until the kernel runs, if it's caught at all.

**`dmtool` closes that gap from the command line** — one self-describing native binary, machine-readable by default with explicitly profiled JSON/text artifacts, no Java toolchain. It authors, reads, and safely edits a model's rules *and* structure — **every edit is confirmed by the embedded real A12 kernel before it is written**, so *valid* means the engine accepts it, not that it merely looks right — and it runs rules against real documents on a **clean-room interpreter** held kernel-exact by a differential test estate and a tri-verified conformance corpus.

It is built **agent-first** — the #1 use is inside a coding-agent session (Claude Code, Codex):

- **Self-describing — a cold agent needs no external docs.** `manifest` lists every verb × parameter with a worked example; `operators` / `patterns` / `diagnostics` / `schema` expose the DSL vocabulary, the validation idioms, each diagnostic code, and the exact I/O shape — all from the binary itself.
- **Judgment where `--help` can't reach.** A bundled skill teaches what the catalog can't — condition *polarity*, error-field paths, iteration scope — so a scaffolded rule has the right shape before the kernel ever sees it.
- **Safe by construction.** Envelope-producing operations share one standard result shape, while raw artifact/catalog and text-view modes are explicit in the operational profile; deletes and structural refactors are **gated** (a referenced field won't silently vanish); a multi-op `apply` session is **atomic** (rollback on any failure).

<p align="center">
  <a href="assets/infographics/dmtool-model-workbench.avif">
    <img src="assets/infographics/dmtool-model-workbench.avif" alt="dmtool DocumentModel workbench for discovery, schema exchange, understanding, authoring, review, and runtime automation" width="720">
  </a>
</p>
<p align="center"><sub>Click the infographic to open the full-resolution image.</sub></p>

### A session, in natural language

You don't type `dmtool` — your agent does. You state the rule in plain language; the agent resolves the model, reasons about **polarity** and **operator semantics**, and the **real kernel** confirms the authored rule before anything is written:

**You —** *"In the order model, a delivery date must never be before the order date."*

**Agent —** *finds the fields, then checks the operator's sign convention:*

```sh
dmtool -m order.dm.json model describe | jq -c '.data.fields[] | select(.kind=="DATE") | .path'
# → "/Order/OrderDate"   "/Order/DeliveryDate"
dmtool operators DifferenceInDays          # its meaning, sign convention, gotchas, and OPPOSITE
```

*An A12 rule fires on the **violation**, so the condition must be true when delivery is before order — and that gets validated against the real kernel before anything is written:*

```sh
dmtool -m order.dm.json rule check --field /Order/DeliveryDate --code DELIVERY_BEFORE_ORDER \
  --condition 'AllFieldsFilled(OrderDate, DeliveryDate) And DifferenceInDays(OrderDate, DeliveryDate) < 0'
# → { "valid": true, "diagnostics": [] }   ✓ accepted — polarity lint clean, error field legal & in scope
dmtool -m order.dm.json rule add delivery-rule.json     # persist (kernel-checked again on write)
```

**Done** — the rule is written and re-validated: true *exactly* when delivery precedes the order (the violation), with a referenced, in-scope error field. The agent didn't guess: had it written the condition true on the *requirement*, the built-in **polarity lint** would have flagged it; had it invented a field path or misread the operator, the kernel would have rejected it. That is the point — **deep rule + operator semantics, gated by the engine itself**, reachable from one English sentence.

### The skill & the plugin

Two pieces make an agent this good with `dmtool`:

- **The plugin** delivers the binary — a bundled installer the skill runs **on demand** (the first time `dmtool` is needed, in that same session) fetches the right per-OS native build (anonymous, checksum-verified). One install line (below); the same plugin model serves Claude Code and Codex.
- **The skill** is a small `SKILL.md` of *judgment* — the traps `--help` can't teach: rule **polarity**, valid **error fields**, **iteration** scope, the date/number gotchas. The binary stays self-describing (`manifest` / `operators` / `schema`); the skill teaches *when* and *why*, and the same canonical skill backs both agents.

### 🔌 The same tool over MCP — for a host that cannot run a binary

Some callers can't spawn a subprocess at all: a web client, a hosted agent platform, a sandboxed IDE integration. For those, **the same binary is a Model Context Protocol server** (revision `2026-07-28`) — two transports, one implementation:

```sh
dmtool mcp                                       # stdin/stdout, for a host that launches the binary
dmtool mcp --http --host 0.0.0.0 --port 8080     # a bound port — upload it, start it, hand out the URL
```

The whole verb surface arrives as tools, **derived from the same command tree the CLI describes itself from** — so the two surfaces can never describe the tool differently, and a verb added to the CLI appears here by itself. Each tool carries the facts a host can act on: a read tool is annotated read-only, so allowlisting `dm.rule.read` cannot reach `rule remove` — a guarantee granting shell access can never give you.

**Every call carries its own content.** Send the model's DM-JSON, get the edited DM-JSON back. The server accepts **no filesystem path**, holds no model between calls, and deletes everything a call creates before it answers — so two callers cannot reach each other's work, by construction rather than by policy.

> **Where you *can* run the binary, run it.** The CLI edits models in place; over MCP you pay a content round trip in each direction. MCP is the answer to "my host cannot execute anything", not a local convenience — and the verb's own `--help` says so.
<!-- /shared:dmtool-story -->

Prefer typed Java in your own JVM project? The same engine is a library — see *The idea* below.

---

## Three ways to use it

| You are… | Use | Why |
|---|---|---|
| a **modeller / BA**, or an **LLM agent** | **`dmtool`**, the CLI | verbs + profiled JSON/text I/O, single native binary, self-describing — no Java toolchain to install |
| a **developer**, editing models in your own JVM project | **rulekit / modelkit** as a library | typed builders, kernel-checked, in your own code & compiler |
| a **host that cannot execute a binary** — a web client, a hosted agent platform, a sandboxed integration | **`dmtool mcp`**, the MCP server ([contract](docs/MCP-SPEC.md)) | the same verbs as tools derived from the same command tree; every call carries its content both ways and the server keeps nothing |
| a **TypeScript / Node / browser developer** who needs model *checking* with no kernel, server or licence | **`a12-consistency`**, the portable checker ([contract](docs/CONSISTENCY-API.md)) | one Kotlin Multiplatform implementation, strings in and out, nothing thrown; it reports `INVALID` or `INCONCLUSIVE` with its coverage evidence and never claims `VALID` |

*(The kits — rulekit, modelkit, dm.edit, schemakit — are a **Java** library. The **interpreter** and the **portable checker** are Kotlin Multiplatform, and both packed libraries are tested on JVM, Node.js, and Chrome.)*

## Why Kotlin, GraalVM, and the A12 Kernel

The three technologies have separate jobs. Kotlin Multiplatform carries one portable runtime-evaluation engine across JVM and JavaScript. GraalVM turns the JVM application stack into the self-contained per-OS `dmtool` executable. The real A12 Kernel remains the authority for model acceptance, canonical read-back, safe rewrites, and the differential evidence that keeps the clean-room Interpreter aligned.

<p align="center">
  <a href="assets/infographics/kotlin-graalvm-kernel-roles.avif">
    <img src="assets/infographics/kotlin-graalvm-kernel-roles.avif" alt="How Kotlin Multiplatform, GraalVM Native Image, and the A12 Kernel divide responsibilities across a12-dmkits" width="720">
  </a>
</p>
<p align="center"><sub>Click the infographic to open the full-resolution image.</sub></p>

---

## `dmtool` — the CLI

Same engine, no Java required. Model-bound operations use `dmtool -m <model> <target> <op> [args]`, and the tool is **self-describing**: a cold agent (or you) can discover the whole surface from the binary alone. Each operation's manifest profile declares whether its stdout is the standard JSON **result envelope** (`{ok, valid, outcome, data, diagnostics[], …}`), raw JSON/text, or empty after an artifact write.

**Install** from the public release repo, **[`a12-dmtool-releases`](https://github.com/mbackschat/a12-dmtool-releases)**, the single source of install truth. It documents the agent plugin for Claude Code and Codex and the raw per-OS binary; the [Homebrew tap](https://github.com/mbackschat/homebrew-tap#dmtool) and [Scoop bucket](https://github.com/mbackschat/scoop-bucket#dmtool) install the same binaries. The plugin sources live here under [`plugin/`](plugin/) and [`plugin-codex/`](plugin-codex/).

A first taste:

```sh
dmtool manifest                                  # every verb × parameter, with a worked example each
dmtool -m order.dm.json model describe           # the model card: fields, kinds, enum domains, groups
dmtool -m order.dm.json rule check --field /Order/DeliveryDate --code DELIVERY_BEFORE_ORDER \
  --condition 'AllFieldsFilled(OrderDate, DeliveryDate) And DifferenceInDays(OrderDate, DeliveryDate) < 0'
dmtool -m order.dm.json model eval --instance order-instance.json   # which rules FIRE on a real document
```

**The tour.** The [`dmtool` guide](docs/DMTOOL-GUIDE.md) walks every verb family with copyable calls:

| You want to… | Verbs | Guide section |
|---|---|---|
| install it, or serve it to a host over MCP | `mcp` | [install](docs/DMTOOL-GUIDE.md#install--see-the-release-repo-single-source-of-truth) · [MCP](docs/DMTOOL-GUIDE.md#serve-it-over-mcp) |
| discover the tool from the binary | `manifest` · `operators` · `patterns` · `diagnostics` · `schema` | [discover](docs/DMTOOL-GUIDE.md#discover-the-tool-itself) |
| start a model, or exchange it with JSON Schema / OpenAPI | `model new` · `model import-jsonschema` · `model export-jsonschema` | [start](docs/DMTOOL-GUIDE.md#start-a-model-from-scratch) · [schemas](docs/DMTOOL-GUIDE.md#json-schema--openapi) |
| read and understand a model | `model info`/`describe`/`check`/`report` · `rule explain` · `where-used` · `model usage` | [read](docs/DMTOOL-GUIDE.md#read--understand-a-model) |
| review what an edit changed | `model diff` (impact-ranked, catches polarity flips) · `model normalize` · `model expand` | [diff](docs/DMTOOL-GUIDE.md#review-what-changed--a-semantic-diff) |
| work across a folder of models | `workspace list`/`graph`/`check`/`roles` · `-w` | [workspaces](docs/DMTOOL-GUIDE.md#explore-a-whole-folder-of-models) |
| author rules and computations, kernel-confirmed | `patterns <idiom>` · `rule check`/`add` · `computation add` · `batch` | [patterns](docs/DMTOOL-GUIDE.md#scaffold-from-a-validation-pattern--polarity--error-field-baked-in) · [author](docs/DMTOOL-GUIDE.md#author--check--confirmed-by-the-kernel) |
| re-express a rule with a preview diff | `rule modify --dry-run` | [edit](docs/DMTOOL-GUIDE.md#edit-with-a-preview-diff) |
| run rules and computations on real documents | `model eval` · `rule eval` · `model compute` · `model seed` | [run](docs/DMTOOL-GUIDE.md#run-the-rules-against-real-data-not-just-static-checks) |
| calibrate with an identifier-free workload profile | `profile extract`/`synthesize`/`compare` | [profiles](docs/DMTOOL-GUIDE.md#calibrate-with-performance-profiles) |
| edit structure and refactor safely | `field`/`group`/`typedef`/`include`/`criterion`/`config` · rename · move · extract · gated `remove` | [structure](docs/DMTOOL-GUIDE.md#edit-structure--with-a-safe-delete-gate) |
| apply many edits atomically | `apply` (one gate, one write, rollback) | [`apply`](docs/DMTOOL-GUIDE.md#both-kits-at-once-atomically--the-apply-session) |

This build targets **A12 Kernel 31.1.1** (A12 Tools distribution **2026.06-ext1**); `dmtool --version` reports it, and the [compatibility section](docs/DMTOOL-GUIDE.md#version--compatibility--tolerant-but-explicit) covers how other model versions load. Every verb has a runnable, CI-verified walkthrough in [`examples/`](examples/README.md), and the full contract is the [CLI specification](docs/CLI-SPEC.md).

---

## The idea — typed, model-bound, kernel-confirmed

The same fragile-by-hand condition (see *Why `dmtool`* above) has a **typed** answer for developers. **rulekit** builds the condition as typed code bound to your model, so a wrong path or a number operator on a date fails before the kernel is ever asked:

```java
BoundModel m = BoundModel.ofJson(Files.readString(modelPath));   // bind a model to the real kernel

GroupRef  addons = m.group("/Subscription/Addons");              // resolve each element ONCE …
NumberRef fee    = m.num("/Subscription/Addons/MonthlyFee");     // … then reuse the typed ref — no repeated paths

Rule rule = m.rule(addons)
    .name("FeeCapRule")
    .errorField(fee)                                            // fields come OFF the model …
    .when(fee.gt(1000))                                         // … a wrong path can't compile
    .code("FEE_CAP").severity(Severity.ERROR)
    .message(en("Monthly fee is too high."), de("Monatsgebühr ist zu hoch."))
    .build();

String dmJson = m.add(rule).toJson();   // re-validated through the kernel; ready to write back
```

It renders to the exact DSL the kernel expects (English by default, **German** on demand), the kernel confirms the result, and it runs in reverse: an existing condition string reads back into the same typed form. **modelkit** applies the same idea to the model's **structure**, and **`EditSession`** puts structure and rule edits on one model behind a single kernel gate.

| Library | Tour |
|---|---|
| **rulekit** — rules and computations | [refs by path or editor, computations, interpolated messages, what you can express, read-back, explain/find, German rendering, comments, the pattern catalogue](rulekit/README.md#in-practice) |
| **modelkit** — structure | [build from scratch, configured field kinds, group templates, type definitions & includes, document config](modelkit/README.md#in-practice) |
| **`EditSession`** — both kits, one gate | [the editor idiom, refs as currency, reference-preserving refactors](edit/README.md#in-practice) |
| **schemakit** — JSON Schema / OpenAPI | [module front page](schemakit/README.md) · [mapping contract](docs/SCHEMAKIT-SPEC.md) |

Runnable, test-locked snippets for all of them are in the [API guide](docs/API-GUIDE.md).

---

## The interpreter — kernel-free runtime evaluation

Runtime evaluation (*which rules fire on this document, what does each computed field evaluate to*) runs on a **clean-room, kernel-free interpreter** written in Kotlin Multiplatform. It is the sole runtime eval engine, the one behind `model eval` / `rule eval` / `model compute` / `model seed`. It is also why those verbs work in the native binary: the interpreter needs **no codegen step at all**, where the kernel's Groovy codegen can't go native.

Those verbs and `model expand` share the interpreter's portable workspace boundary for includes and imported typedefs; the JVM/Jackson model reader remains a metadata/index implementation, not a second runtime resolver.

- **Kernel-exact, by verification.** Its results are checked rule-for-rule against the real A12 kernel by a **differential test suite** plus a model-driven fuzzer, and it replays a **conformance corpus** of kernel-verified expected outcomes — captured tri-verified against the kernel's two evaluation strategies — on both JVM and JS.
- **A standalone library, not just the CLI's engine.** One common engine serves **JVM, Node.js, and browser** applications. The public TypeScript, Kotlin, and Java API prepares finite or provider-backed workspaces, resolves includes and imported type definitions, reads Documents, reuses a prepared interpreter across form changes, runs full or partial validation, computes derived values, and hosts project field/condition callbacks.
- **A developer-facing public release.** Start with the [public interpreter repository](https://github.com/mbackschat/a12-interpreter-releases), [try the live browser showcase](https://mbackschat.github.io/a12-interpreter-releases/showcase/), or browse the [version-matched API guide and references](https://mbackschat.github.io/a12-interpreter-releases/api/latest/). The packed-artifact-tested [source guide](docs/INTERPRETER-API-GUIDE.md) and [showcase source](interpreter/consumer-tests/browser/) remain here for contributors.

<p align="center">
  <a href="assets/infographics/a12-interpreter-runtime.avif">
    <img src="assets/infographics/a12-interpreter-runtime.avif" alt="A12 Interpreter preparation, reusable request lifecycle, outputs, and Kernel conformance evidence" width="720">
  </a>
</p>
<p align="center"><sub>Click the infographic to open the full-resolution image.</sub></p>

### Performance and footprint

The same engine runs on the JVM, Node.js and in the browser, and the figures below come from two runtimes — latency measured in system Chrome, cross-engine comparison and memory measured on the JVM.

Small enough to ship to a browser — the production JavaScript entry is **290 kB gzip**, with no A12 Kernel bytecode — and quick enough to re-run on every keystroke.

<p align="center">
  <a href="assets/infographics/interpreter-vs-kernel.avif">
    <img src="assets/infographics/interpreter-vs-kernel.avif" alt="Measured A12 Interpreter performance and resident memory compared with the A12 Kernel" width="720">
  </a>
</p>
<p align="center"><sub>Click the infographic to open the full-resolution image.</sub></p>

Measured in system Chrome against the packed npm package, the same artifact consumers install, over the two Documents the collector defines. Each is timed **two ways**, because an application experiences both: the **first answer** a user waits for when a form opens, which includes building the interpreter, and the **warm** cost of revalidating after every keystroke, which does not.

| Document | Findings | First answer *(includes building the interpreter)* | Warm *(validation only)* |
|---|---|---|---|
| 200 rows — representative | — | **11.55 ms median** | **1.1 ms** |
| 10,000 rows — stress | 4,900 | **166.1 ms median** | **53.3 ms** |

The stress Document carries 50× the rows and costs 14× the time.

The comparison baseline is deliberately the toughest one available — the A12 Kernel's own **generated validator compiled as Java**, with code generation outside the clock. Each figure is how many times faster the interpreter finished the same work:

| Default inventory, 15 cases | Repeated documents | Huge document, 20,000 rows | Hardest case, 384 low-firing rules |
|---|---|---|---|
| **2.8× – 17.1×** | **12.2× – 16.8×** | **≈152×** | **1.73×** |

The spread does not track model size: the widest margins were measured on computation-heavy and repeated-document scenarios, the narrowest on 384 concentrated rules that almost never fire. Resident memory on the JVM goes the same way — a prepared model costs **4.2× – 8.1×** less than the Kernel's generated Java and **18.5× – 37.9×** less than its dynamic Groovy.

**Not one eligible comparison went the other way.** Across the default, repeated, rule-count and edge suites, neither kernel strategy won a single case — the hardest case above is the narrowest win, not a loss. Browser support is measured directly: the packed tarball is installed into a real consumer and exercised in system Chrome, never inferred from Node.js. These are bounded same-machine measurements after exact result preflight, not universal promises.

<sub>Measured 2026-08-17 · A12 Kernel 30.8.1 · Apple M1 · Chrome 151 · JDK 21 · no discarded samples · [method, timing boundaries and complete tables](docs/INTERPRETER-PERFORMANCE.md)</sub>

---

## The portable checker — kernel-free consistency, without the kernel

<p align="center">
  <a href="assets/infographics/a12-consistency-checker.avif">
    <img src="assets/infographics/a12-consistency-checker.avif" alt="A12 Consistency Checker application inputs, checking operations, reports, snapshot reuse, and assurance boundaries" width="720">
  </a>
</p>
<p align="center"><sub>Click the infographic to open the full-resolution image.</sub></p>

The interpreter answers *what happens when you run this model*. The **portable checker** (`a12-consistency`) answers the question before it: *would A12 reject this model at all?* — and it answers it with no server, no kernel and no licence, on the JVM, in Node and in the browser, from one Kotlin Multiplatform implementation.

**It never says `VALID`, and that is the design rather than a shortfall.** "Would A12 accept this?" is a claim about every rule A12 has, and the checker implements a finite subset (103 named check families today). So it reports `INVALID` or `INCONCLUSIVE` and publishes the coverage evidence (`checksCompleted`, `gaps`) that says how much weight the answer bears.

What it *can* decide is its own scope, and that does have a pass: `outcome: ALL_CHECKS_PASSED` means every family this build implements ran and none rejected the model. Reading `INCONCLUSIVE` as "valid" is the one way to misuse it, which is why the status is a word rather than a boolean.

```ts
import { checkEditJson } from 'a12-consistency';

// Gate a save by what the EDIT introduced, not by the state of the model it lands in.
const edit = JSON.parse(checkEditJson(baselineJson, editedJson));
edit.introduced;
```

- **Strings in, strings out, and nothing throws.** An unreadable source comes back as a report whose `diagnostics` explain the failure, so there is no `catch` branch to write. Beyond a whole-model check it answers an unsaved **draft**, the **impact** of a candidate change (which declarations it reaches, not which it broke), and holds one analysis across many questions through a **snapshot** — the editor path, where a keystroke costs one check rather than a full re-admission.
- **The same answer from the CLI.** `dmtool model check --engine portable` runs exactly this checker; `--engine kernel` runs the real kernel. The engine you ask for is the engine that answers — neither ever falls back to the other, and the receipt names which one spoke.
- **Worked, executable consumers.** A plain [Node project](consistency/consumer-tests/node) including a CI gate that cannot manufacture a pass, and a [browser consumer](consistency/consumer-tests/browser) replaying the same shared cases in real Chrome. Both install the packed tarball and import by package name.
- **Standalone Maven distribution.** The [checker release repository](https://github.com/mbackschat/a12-consistency-releases) provides `dm-consistency` for Gradle platform selection and `dm-consistency-jvm` for Java/Maven, with the predicate dependency closure. [Installation](consistency/README.md#getting-it) uses the dedicated Pages Maven repository. npm registry publication remains deferred; build and install its tarball locally.
- **Read next:** the module front page [`consistency/README.md`](consistency/README.md) and the full contract [`docs/CONSISTENCY-API.md`](docs/CONSISTENCY-API.md). Two JVM façades sit on top for callers who already hold one of the other products — `PortableConsistencyGate` returns the verdict as a rulekit `ValidationResult`, and `ConsistencyPreflight` answers an interpreter consumer's static precondition.

---

## Repository layout

**A top-level directory is one of five things, and never two.** The rule is what the entry *produces*, not what it is about — so the checker's module, the checker's demos and the checker's docs live in three different categories on purpose.

**1. Modules that produce a published artifact.** Each is a Gradle module with its own `build.gradle`, its own purity lint, and a committed public-API golden under [`api/`](api).

| module | artifact | what it is |
|---|---|---|
| [`core`](core) | `dmkits-core` | the shared types and the SPI ports the kits reach the kernel through |
| [`rulekit`](rulekit) | `dmkits-rulekit` | validation rules and computations as typed, model-bound Java |
| [`modelkit`](modelkit) | `dmkits-modelkit` | the model's structure — fields, groups, type definitions, includes, config |
| [`edit`](edit) | `dmkits-edit` | `EditSession`: structure + rule edits on one model behind a single kernel gate |
| [`schemakit`](schemakit) | `dmkits-schemakit` | JSON Schema / OpenAPI ⇄ document model |
| [`interpreter`](interpreter) | `dm-interpreter` | the kernel-free runtime evaluator (Kotlin Multiplatform: JVM + JS) |
| [`consistency`](consistency) | `dm-consistency` / `a12-consistency` | the kernel-free consistency checker (Kotlin Multiplatform: JVM + JS) |
| [`predicates`](predicates) | `dm-predicates` | the dependency-free leaf holding A12 laws the Java kits and the checker would otherwise each restate |
| [`interpreter-bridge`](interpreter-bridge) | `dmkits-interpreter-bridge` | condition read-back with no kernel present — the one module depending on both `rulekit` and `interpreter` |
| [`consistency-gate`](consistency-gate) | `dmkits-consistency-gate` | the two JVM façades over the checker, for a dmkits caller and for an interpreter consumer |

**2. Modules that produce something other than a library.** [`cli`](cli) is the `dmtool` application, shipped as a native binary rather than a jar. [`adapter`](adapter) is the kernel backend — the only module that touches the kernel's internal read tier, deliberately unpublished so that tier can never reach a consumer. [`src`](src) is the root project's own source, and it is **test-only**: root has no main source at all, and hosts the cross-kit integration suite plus the public-API lock.

**3. What ships to a coding agent.** [`skill`](skill) holds the two canonical skills — the authoring skill (the material the self-sufficiency eval measures) and the bug-report skill. [`plugin`](plugin) and [`plugin-codex`](plugin-codex) are the Claude Code and Codex plugin trees; their skill copies are generated from `skill/` by [`scripts/sync-plugin-assets.sh`](scripts/sync-plugin-assets.sh), never hand-edited.

**4. How the claims get proved.** [`corpus`](corpus) is the kernel-verified conformance corpus the interpreter replays. [`eval`](eval) is the self-sufficiency benchmark harness that measures whether a cold agent can drive the tool. [`probe`](probe) drives a multi-prompt session against the *shipped* native binary and captures every friction. [`perf-results`](perf-results) holds the committed performance records, per platform. [`api`](api) holds the public-API goldens, one per published artifact.

**5. What a reader or user meets.** [`docs`](docs) — every design document, spec, findings record and gap ledger, indexed by [`docs/README.md`](docs/README.md). [`examples`](examples) — the runnable `dmtool` walkthroughs, with their own sample models. [`assets`](assets) — the infographics this README embeds.

**And the build itself:** [`gradle`](gradle) (the wrapper, the shared build fragments, and the Kotlin/JS yarn lockfile), [`scripts`](scripts) (every repository tool, indexed by [`scripts/README.md`](scripts/README.md)), and [`bin`](bin) — one launcher shim so a doc, demo or script writes `dmtool …` without naming a packaging artifact.

---

## Documentation

Start at the 📚 **[documentation index →](docs/README.md)**; the rest of the map:

| To find… | See |
|---|---|
| 🧰 Every `dmtool` verb, with copyable calls | **[`dmtool` guide →](docs/DMTOOL-GUIDE.md)** · **[runnable walkthroughs →](examples/README.md)** |
| ☕ The Java libraries in use | **[rulekit →](rulekit/README.md)** · **[modelkit →](modelkit/README.md)** · **[`EditSession` →](edit/README.md)** · **[API guide →](docs/API-GUIDE.md)** |
| 🚀 Live interpreter | **[Browser showcase →](https://mbackschat.github.io/a12-interpreter-releases/showcase/)** · **[API guide and references →](https://mbackschat.github.io/a12-interpreter-releases/api/latest/)** |
| 🧭 Vision, requirements, the decision log | **[Project design →](docs/PROJECT-DESIGN.md)** |
| 🧩 The API design findings | **[API findings →](docs/API-FINDINGS.md)** |
| 🛠️ The CLI contract | **[CLI specification →](docs/CLI-SPEC.md)** |
| 🔬 Verified Kernel facts | **[Kernel findings →](docs/KERNEL-FINDINGS.md)** · **[Kernel integration notes →](docs/KERNEL-INTEGRATION-NOTES.md)** |
| 🔧 Building, testing, and the commit hooks | **[Developing →](docs/DEVELOPING.md)** · **[Build environment →](docs/BUILD-ENVIRONMENT.md)** · run **[`scripts/doctor.sh`](scripts/doctor.sh)** |
| 📏 How large each part is | **[Source statistics →](docs/SOURCE-STATISTICS.md)** |

## Build & test

The A12 Kernel comes from your local Maven repository: publish kernel `31.1.1` once with the sibling [`a12-kernel-bootstrap`](https://github.com/mbackschat/a12-kernel-bootstrap) kit (`./bootstrap.sh`). You also need JDK 21, Node >=24.12.0, and the latest [locmetry](https://github.com/mbackschat/locmetry). Then:

```sh
scripts/doctor.sh                    # check the toolchain, Gradle home, hooks and local Kernel
./build.sh test                      # isolated Gradle home + JDK 21, then Gradle
brew install actionlint              # the pre-push hook validates workflows with it
git config core.hooksPath .githooks  # enable the commit and push hooks once per clone
```

[Developing](docs/DEVELOPING.md) explains why the build uses an isolated Gradle home, how the build cache works, the useful Gradle tasks, what each hook enforces, and the optional maintainer agent setup.

## License

**MIT** — see [`LICENSE`](LICENSE).

- **Source: MIT.** The kits' own original work, shipping no kernel binary, so they carry no copyleft entanglement — consistent with the sibling `a12-kernel-demo` / `a12-kernel-bootstrap` (both MIT).
- **The kernel: dual `EUPL-1.2 OR commercial`** (upstream, not ours).
- **The shipped standalone CLI distributable: EUPL-1.2**, because it *bundles* the kernel — the source stays MIT.
