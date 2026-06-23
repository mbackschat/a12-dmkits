# Interpreter proposal — a native-safe, cross-platform runtime evaluator

**Status:** in build-out. The §10 spike is **built, validated, and merged** (2026-06-21); the decision is now taken to **grow it into the full evaluator, in-repo** (the `:interpreter` module — *not* extracted to a sibling repo; see §8). Graduates to a spec / folds into [`ARCHITECTURE.md`](ARCHITECTURE.md) once the operator/validation coverage is broad enough to ship.

**One line:** an isolated runtime evaluator for conditions + computations that **interprets** the rule AST directly — instead of generating and executing code on the fly — so it runs cleanly in a GraalVM native image *and* in the browser, from one source.

**The committed goal — a full-blown interpreter serving two consumers:**

1. **dmtool's native image** — drive the runtime verbs (`model eval`, `model compute`, `rule test`) that the Groovy ceiling currently bars in a native image (§1). **Acceptance:** the [`examples/cli-runtime.md`](../examples/cli-runtime.md) showboat demo — which exercises exactly those verbs — runs **end-to-end on the native image** (today they are the `JVM_ONLY_VERBS`). That demo concretely bounds the minimum operator/feature coverage use-case 1 needs.
2. **A standalone Kotlin/JVM + Kotlin/JS library** — for any consumer running **computations and validations — both full *and* partial** — over a document model, with no kernel runtime dependency (§7). The API must also evaluate a **single rule or a single computation** against a document (the `rule test` shape — FIRED / PASSED / SUPPRESSED, and "what does this one computation yield"), not only whole-document validation — this is what dmtool's native-image `rule test` verb consumes (§1).

Correctness is the user's criterion throughout: **the interpreter is correct iff it agrees with the real kernel**, proven by the differential oracle (§9).

## 1. The problem

The kernel evaluates rules by **generating source code per model and executing it** (`ValidationCodeGenerator` emits one of six targets — `JAVA, GROOVY, JAVA_SCRIPT_VK, JAVA_SCRIPT_REDUCED, CPLUS, VBA`). On the JVM, dmtool's runtime path takes the Groovy target and compiles it at runtime in **one** place: `kernel-tool/kernel-core-ontheflytesting/.../OnTheFlyTestingValidatorGenerator.generateValidator()`, which calls `GroovyShell.parse(generatedCode)`.

That single step — compiling generated source to JVM bytecode at runtime — is what a GraalVM native image forbids. It is the "Groovy ceiling" recorded in [`NATIVE-IMAGE-GRAALVM.md`](NATIVE-IMAGE-GRAALVM.md), and it bars exactly three dmtool verbs (the `JVM_ONLY_VERBS` in `Cli.java`):

| verb | `KernelAdapter` entry | what it needs |
|---|---|---|
| `model eval` | `evaluate(…) → rt().validateFull` | run a model's rules over a document |
| `model compute` | `compute(…) → rt().compute` | run a model's computations |
| `rule test` | `testRule(…)` (wraps `evaluate`) | fire one rule, report FIRED/PASSED/SUPPRESSED |

Everything else dmtool does — structural `checkConsistency`, the bulk of the verbs — is **already native-safe** (no Groovy). So the gap is narrow and specific: *runtime rule/computation evaluation in a native image (and, as a bonus we want anyway, in the browser).*

## 2. Decision — interpret, don't generate-and-eval

Codegen is not wrong; it optimizes a case dmtool isn't and taxes a case dmtool is. It buys (a) **steady-state throughput** — generated code JITs and runs hot, which wins when a *fixed* model is validated against millions of documents; (b) **code export as a feature** — the C++/VBA/JS targets ship a validator into a foreign host; (c) one template set → many targets.

dmtool benefits from none of these. It loads an **arbitrary** model, validates a document or two, and exits — so it never reaches steady state, the per-model Groovy compile (hundreds of ms) is pure unamortized latency, and it exports nothing. A **tree-walking interpreter** is native-image-clean by construction (static code walking data — no `GroovyShell`, no runtime bytecode generation, no embedded JS VM), has ~zero startup, and can be cross-platform from a single source.

The line that matters for native-image and for "no on-the-fly codegen": **the interpreter may generate an internal *data structure* (its own AST/IR); it must never generate host-language *source* that gets compiled or `eval`'d.**

> Footnote — when codegen *would* win: a server with a **fixed** model catalog and a real throughput need should AOT-generate Java validators at *build* time (the kernel's `JAVA` target), which is throughput-optimal and also native-safe. That's a different product than dmtool; this proposal is for the arbitrary-model-at-runtime case (dmtool, and the browser).

## 3. The honest tradeoffs

An interpreter is slower per operation and puts semantic fidelity on us. Both costs are unusually cheap here:

| You give up | Why it barely hurts |
|---|---|
| **Per-operator JIT dispatch speed** | The repetition/scale optimizations live in the kernel's **runtime library, not the generated code** — `EbenenIterator` (the `STERN_OPTIMIERUNGS_SCHWELLE = 100` `*`-iteration prune), and the `ValidationCache`/`GroupFillCache`/`IndexFieldCache` family. They are *algorithmic* and interpreter-agnostic; we reimplement the same strategies. Only a **constant factor** of node dispatch is lost, not the algorithmic wins. |
| **Byte-identical operator semantics for free** | We now implement operators — but against an **almost-complete executable spec we already mined**: ~70 [`KERNEL-FINDINGS`](KERNEL-FINDINGS.md), 109 N7-verified operators in the catalog, 300 classified [`MVK-LEDGER`](MVK-LEDGER.md) codes, all 28 runtime-core classes drained in [`RT-SEMANTICS-LEDGER`](RT-SEMANTICS-LEDGER.md), and 119 classified [`GRAMMAR-LEDGER`](GRAMMAR-LEDGER.md) productions. Implementing to a known spec, not reverse-engineering in the dark. |
| **Byte-identical localized message text** | The fiddliest part (4-locale, German-keyed resource bundles, param substitution). It's a *scoping decision* (see §11), not a blocker. |

## 4. Two interpreter flavors

"Interpreter" spans a spectrum; the architectural boundary (data, not host source) holds across all of it:

- **Tree-walking interpreter** — walk the AST, evaluate node by node. Simplest, most portable, slowest dispatch. **Start here** (get the laws green).
- **Compile-to-internal-IR VM** — per model, lower the AST to a compact internal instruction list (still just data → native-safe, no eval), then interpret that in a tight loop. Better cache locality, no per-node virtual dispatch. The **escape hatch** for throughput on huge nested-repetition documents — adopt only if measurement demands it.

(A Truffle self-optimizing interpreter would recover near-codegen speed but ties to the GraalVM optimizing runtime and complicates the browser; out of scope unless throughput forces it.)

## 5. Language — Kotlin Multiplatform

Once it's an interpreter, **Kotlin Multiplatform (KMP)** is the strongest fit: one interpreter codebase, both targets, and it collapses the kernel's *two hand-maintained* runtimes (`kernel-core-runtime` Java + `kernel-core-runtime-ts` TypeScript — file-by-file ports, no transpiler) into **one common engine + thin shims**.

```
                ONE interpreter  (Kotlin common code: AST, operators, polarity,
                                   empty/null logic, iteration, caching)
                ├── Kotlin/JVM  → static bytecode → native-image-clean, NO GraalJS,
                │                  native kernel-Java interop for the test oracle
                └── Kotlin/JS   → plain JS → browser, NO Wasm boundary

   platform-specific (expect/actual) — exactly the line the kernel already draws:
        decimals :  java.math.BigDecimal      ⟷   big.js
        dates    :  java.time                 ⟷   luxon
```

- **Why not TypeScript:** TS is native in the browser but would force a **GraalJS embed** on the JVM (the very JS VM an interpreter exists to avoid). Kotlin/JVM is static — it just runs in the native image.
- **Why not "host the kernel's JS on GraalJS":** viable and zero-reimplementation, but it ships a JS VM (size, perf caveats), keeps full kernel coupling, and trusts the kernel's TS runtime over its Java one. Kept as a fallback if the interpreter's semantic-fidelity cost proves too high; not the plan.
- **Why not the Wasm boundary:** a Wasm-compiled interpreter called from JS marshals the whole document across the JS↔Wasm boundary per validation — expensive for deep repetition docs. Kotlin/JS compiles to plain JS, so the document is read in place. (Confirmed concern; avoided by target choice.)

## 6. What's owned vs delegated

The interpreter **owns the runtime evaluation path** (the part that needed Groovy) and nothing else. Two things stay delegated:

1. **Structural `checkConsistency`** — already native-safe; dmtool keeps calling it for model validity. The interpreter does *not* re-implement structural validation.
2. **The parser → AST** — conditions live as *text* on disk, so something must parse them (see the open decision in §11: kernel parse-tree-api vs. an ANTLR parser generated from the kernel `.g4`).

The boundary to dmtool is a **clean data contract, not a shared object model**:

```
(condition/computation text  +  document JSON  +  locale)  →  diagnostics
```

The interpreter has its **own multiplatform AST** and does **not** import `dm.core`/`BoundModel` — which falls out of KMP anyway (common code can't see JVM-only Java types). dmtool *adapts to* the interpreter; the interpreter never depends on dmtool.

## 7. Standalone = dependency hygiene (not location)

"Standalone library" is a property of the artifact's dependencies and API, not of where the source sits. Four leaf rules make it standalone in either home:

1. **No dependency on `cli`/dmtool** — they consume it, never the reverse.
2. **No *runtime* dependency on the kernel** — the whole point. The kernel appears only as a **test-only** conformance oracle.
3. **A clean data contract, not a shared object model** (§6) — owns its AST.
4. **Independently publishable** — Maven (JVM) + npm (JS), its own coordinates (package `io.github.mbackschat.a12.dm.interpreter`).

Hold these from day one and extraction (§8) stays cheap.

## 8. Placement — built out in-repo; separation by convention + linting, not by a repo wall

**Decision (2026-06-23): the interpreter is built out *in this repo*, and dmtool's consumption of it evolves alongside it here.** The earlier plan ("spike in-repo, extract to a sibling `a12-dm-interpreter` before promoting") is **retired** — it solved a problem §7 already dissolves: *standalone is a property of the module's dependencies and API, not of where the source sits.* Co-locating dmtool + interpreter is the tighter loop for use-case 1 (the native-image verbs), and the differential oracle (§9) lives here too. The cross-repo tax bought only an independent release cadence we don't need yet.

```
   a12-dmkits (this repo, one build)
   ├── :interpreter  (KMP — commonMain engine, jvm/js shims)   ← the publishable standalone artifact
   │      ▲ no kernel, no dmtool — enforced by the purity lint below
   ├── :adapter (Java) ── testImplementation :interpreter       ← the kernel DIFFERENTIAL oracle lives here,
   │                                                              keeping :interpreter kernel-free even in test
   └── :cli / dmtool ──── (consumes :interpreter for the native-image runtime verbs)
```

**Strong separation is kept by *convention + linting*, not a repo boundary** — the four hygiene rules of §7 are enforced mechanically, so the coupling can't silently erode:

- A **purity lint** (`:interpreter`'s `verifyInterpreterPurity`, wired into `check`) fails the build if `commonMain` imports `java.*` / `com.mgmtp.*` / `groovy.*`, or if `jvmMain`/`jsMain` import `com.mgmtp.*`/`groovy.*` (the JVM shim may use `java.*`; the JS shim may use its externals). No kernel/dmtool type can reach the engine.
- The kernel **differential tests stay in `:adapter`** (not `:interpreter`'s test source) — so `:interpreter` carries **zero** kernel dependency even in test scope, which is exactly what keeps it cleanly publishable.
- `:interpreter` never depends on `:` (rulekit core), `:adapter`, or `:cli` — they consume it, never the reverse.

**Standalone publishing (use-case 2) is a module property, satisfied here:** `:interpreter` publishes to Maven (JVM) + npm (JS) under its own coordinates (package `io.github.mbackschat.a12.dm.interpreter`) regardless of living in this repo.

**Extraction to a sibling repo remains a *future option*, no longer a prerequisite.** Do it **only if** the interpreter earns an independent life — a non-dmtool consumer needs its own release cadence, or it ships as standalone OSS with its own issue tracker. Because §7's hygiene is lint-enforced from day one, that extraction stays a *lift* (move the module + its tests, point dmtool at the published dep), not a rewrite — so deferring it costs nothing.

## 9. Verification — the differential oracle we already have

The acceptance criterion is the user's own: **the interpreter is correct iff it agrees with the kernel.** The harness already exists:

| tier | location | oracle | scale |
|---|---|---|---|
| runtime fires/does-not-fire | `adapter/.../laws/` | `RuntimeLaws` over `IDocumentRtService.validateFull` + `compute` | ~105 assertions, 29 classes |
| structural accept/reject | `src/.../validate/laws/` | `KernelLaws` over `RuleValidator`/`checkConsistency` | ~65 assertions, 19 classes |

The build measure: run each law's document through **both** the real kernel and the interpreter; assert identical `(code, errorField, MessageType)` triples and identical `compute()` outcomes (value / cleared / errored). A divergence is a finding — including any place the kernel's own Java and TS runtimes disagree (they are hand-maintained parallel copies). This is the proactive root-cause discipline applied to a new engine.

## 10. The spike plan (the de-risking first step)

Before committing to scope, interpret **3–4 condition shapes end-to-end in Kotlin/JVM** and run them through `RuntimeLaws` against the real kernel, in a native-image-clean build:

1. a **comparison** (`<`/`=` with three-valued logic),
2. an **empty-as-0** case (the type-asymmetry table — number fires as 0, confirm as False, others not evaluated),
3. an **aggregate** (`Sum`/`NumberOfDifferentValues` — omission typing until repetition exhausted),
4. a **nested-repetition `*`-iteration** (exercises the metadata/iteration bridge and the `STERN_OPTIMIERUNGS_SCHWELLE` strategy).

Success: green laws + confirmed native-image-clean + the parser/AST bridge proven. This surfaces the message-fidelity question (§11) concretely.

**Build-out progress (2026-06-23, in-repo per §8).** Past the spike, the engine grew a real **public API** — `DmInterpreter` over a structured `ModelDef` + a general `Document`: **`validateFull`** (whole-document), **`testRule`/`evalCandidate`** (single-rule eval — the FIRED/PASSED/**SUPPRESSED** verdict, incl. a formal-error gate that suppresses a rule referencing a malformed field), and **`compute`** (computations → value/cleared, empty-as-0). A general `Document` (indexed placements, common-prefix field resolution, star aggregation) replaced the spike's shape-specific docs, and a rule's iteration scope is derived from its referenced repeatable fields (KERNEL-FINDINGS §9). Operator coverage grew to ~23 constructs (added `PatternMatched`/`PatternViolated` and arithmetic `+ - *`). Each increment is locked by a `…DiffTest` differential against the real kernel. **The CLI wiring then landed:** a DM-JSON→`ModelDef` loader (`InterpreterModelLoader`) + `InterpreterEngine` route `model eval`/`compute` and `rule test` through the interpreter **by default**, with **`--kernel`** selecting the kernel (JVM only; refused under the native profile). `examples/cli-runtime.md` now runs on the interpreter and verifies green. **What remains for use-case-1 acceptance is purely the native toolchain:** take the three verbs out of `JVM_ONLY_VERBS` and make the `--kernel`/`KernelAdapter.evaluate` path unreachable in the native build (the Groovy ceiling), then native build + the showboat on the binary. Live state + the prioritized order: [`INTERPRETER-BUILDOUT.md`](INTERPRETER-BUILDOUT.md).

**Spike result (2026-06-21, branch `spike-interpreter`).** Executed, and **all four shapes match the real kernel** — 25 differential cases via the `RuntimeLaws` oracle (JVM): comparison + three-valued logic; the empty-value type asymmetry (number→0, confirm→False, boolean/string/empty-row not-evaluated) with the `FieldFilled` guard; operand-list + starred aggregates (`Sum` substitutes 0, `MaxValue` ignores empties, chronological dates); and per-row `*`-iteration firing on exactly the offending rows. Beyond the plan, the interpreter was lifted into a real **Kotlin Multiplatform `:interpreter` module**, and the **same `commonMain` engine runs on Kotlin/JS (Node)** — proving the §5 cross-platform claim, with the only platform-specific code an `expect/actual` decimal shim (BigDecimal on JVM, Double in the POC). **Native-safe confirmed:** `commonMain` has zero `java.*` / `com.mgmtp.*` / groovy imports. Both the Kotlin-JVM and the KMP/JS toolchains resolve in the isolated, public-repos-only Gradle home. (Working notes: [`INTERPRETER-SPIKE-PLAN.md`](INTERPRETER-SPIKE-PLAN.md) / [`INTERPRETER-SPIKE-FINDINGS.md`](INTERPRETER-SPIKE-FINDINGS.md).) A **follow-up then closed the gaps**: arbitrary-precision JS decimal (**big.js** — `0.1 + 0.2 == 0.3` on both platforms); the **`MessageType`/polarity** typing (VALUE/OMISSION; And = omission wins, Or = value wins, **and** the omitted-tail aggregate case — vs the kernel); a real **`commonMain` parser** (condition text → AST, matched against the kernel); **throughput** (20k rows interpret in ~25–39 ms on the JVM, with a regression-guard bound); and **localized message text** (`$field.value$` interpolation matching `getErrorText` in en_US **and** de_DE). **Narrow residue** for a real build: the `$field$` display-name + formal-error message tiers, the full parser grammar (kernel-parse-tree-api vs ANTLR — §11), and a proper perf-regression harness (JMH/baselines vs. the spike's in-test bound).

## 11. Open decisions

Narrow, and each is measurable from the spike:

- **Throughput bar** — is there a real big-document/server scenario, or is dmtool's short-lived process the target? Decides tree-walk (§4) vs. investing early in the IR-VM.
- **Message-fidelity bar** — structural `Diagnostic` equality (code + where + severity + message-type), or **byte-identical localized prose**? The latter means reusing the kernel's message bundles + rendering.
- **Parser source** — reuse the kernel parse-tree-api (a kernel dep on each platform) vs. generate an ANTLR parser from the kernel `.g4` (self-contained, but see §13). Lean: ANTLR-from-grammar for a clean standalone component.

## 12. Non-goals / scope

- **Not** a replacement for structural validation — `checkConsistency` stays with the kernel (native-safe).
- **Not** a code generator, and **not** a kernel replacement — it reproduces *runtime evaluation* behavior, verified against the kernel.
- **In scope: both full *and* partial validation** (`validateFull` *and* `validatePart`) plus `compute()` — the standalone-consumer goal asks for partial, so its three-value-logic suppression contract is full-kernel-parity work we take on (verified against the kernel like everything else), not deferred.

## 13. Licensing

The project decision is settled in [`LICENSING.md`](LICENSING.md): a12-dmkits **source is MIT**; the kernel-bundling CLI distributable is **EUPL-1.2** (the kernel is dual `EUPL-1.2 OR commercial`). The interpreter aligns cleanly: built **clean-room from the mined behavioral spec** (the findings/laws/catalog — own-words, experiment-derived), it is MIT like the rest of the source, and — crucially — it carries **no kernel runtime dependency**, so a distributable that ships *only* the interpreter need not be EUPL. The one derivative-risk to settle: generating a parser from the kernel's EUPL `.g4` grammar (§11) would touch EUPL-licensed source. mgm-tp owns the kernel, so this is likely moot internally, but it matters if the interpreter ships as independent OSS — settle it before the Phase-2 extraction publishes.

---

**Cross-references:** [`NATIVE-IMAGE-GRAALVM.md`](NATIVE-IMAGE-GRAALVM.md) (the Groovy ceiling this removes) · [`RT-SEMANTICS-LEDGER.md`](RT-SEMANTICS-LEDGER.md) / [`KERNEL-FINDINGS.md`](KERNEL-FINDINGS.md) (the semantics to reproduce) · [`TESTING-SPEC.md`](TESTING-SPEC.md) (the law tiers / oracle) · [`PROJECT-DESIGN.md`](PROJECT-DESIGN.md) (the N8/D15 kernel boundary) · [`LICENSING.md`](LICENSING.md).
