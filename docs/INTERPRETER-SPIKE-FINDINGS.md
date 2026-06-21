# Interpreter spike — findings

Result of the [INTERPRETER-SPIKE-PLAN.md](INTERPRETER-SPIKE-PLAN.md) spike for [INTERPRETER-PROPOSAL.md](INTERPRETER-PROPOSAL.md) §10. Prototype, merged into `main`; the extract-to-sibling-repo plan is in the proposal §8.

## Verdict

**The thesis holds on both axes.** A hand-rolled tree-walking interpreter (1) reproduces the kernel's runtime rule evaluation for all four target shapes — verified against the *real kernel* via the existing `RuntimeLaws` oracle on the JVM — and (2) the **same `commonMain` engine compiles to Kotlin/JS and runs in Node**, with the only platform-specific code being a tiny `expect/actual` decimal shim. Both the native-image-clean property and the cross-platform property are demonstrated, not asserted.

## Update — gaps closed (2026-06-21 follow-up)

Three of the originally-listed gaps were closed:

| gap | how it was closed |
|---|---|
| **Arbitrary-precision JS decimal** | The JS `Dec` actual was swapped from `Double` to **big.js** (proposal §5's named choice) via `@JsModule`/`@JsNonModule` + an npm dependency. Proof: `0.1 + 0.2 == 0.3` *exactly* on **both** JVM and JS (a Double-backed actual gives 0.30000…04). Also proves the npm-binding path resolves in the isolated home. |
| **MessageType / polarity** | A typed eval pass reproduces VALUE-vs-OMISSION (comparison over filled → VALUE, over empty-substituted → OMISSION; **And = omission wins, Or = value wins**; `FieldNotFilled` → OMISSION). **7 cases match the kernel** (`PolarityDiffTest`, mirrors `ErrorTypeLawsTest`); the typing also runs on JS. |
| **Real parser** | A minimal recursive-descent parser in `commonMain` turns condition **text → AST** (resolving relative paths against the rule's group). Proof: `ParserDiffTest` drives the interpreter from parsed text and matches the kernel across single-row, starred-aggregate, and per-row shapes; the parser also runs on JS. (Full grammar / kernel-parser-vs-ANTLR choice stays open — §11.) |

## Update 2 — performance + the last gaps (2026-06-21)

### Performance

A `commonTest` benchmark interprets a per-row rule **plus** a starred `Sum` aggregate over **20,000 repetition rows**:

| platform | 20,000 rows (per-row + aggregate) | per row-evaluation |
|---|---|---|
| JVM | **~25–39 ms** | ≈ 1–2 µs |
| JS (Node) | green (same correctness; timing in the JS test report) | — |

So the tree-walk is comfortably fast for large single-level repetition — the original "huge nested datasets" worry doesn't bite at this scale, and nested groups compose the *same* per-row `EvalContext` primitive (nested iteration, no new mechanism). The benchmark now carries a **loose upper-bound assertion as a regression guard** — it trips on a gross O(n²)-type slowdown but has enough headroom (≈150×) not to flake on JS or CI variance.

**On benchmarks as regression guards (your point — agreed):** an in-test time bound is the *floor*, not the real thing. A Phase-1 build should track **recorded baselines** (JMH micro-benchmarks, or a dedicated perf CI job that stores per-commit numbers and fails on a percentage regression) rather than a hand-picked `< 5s`. The in-test guard here is a cheap stand-in that catches the catastrophic case immediately; the proper harness is itself a Phase-1 item.

### Omitted-tail aggregate polarity (closed)

Threading a declared-repetition count through `RepDoc` (+ a `starOmission` context method), the interpreter types a starred aggregate **OMISSION while it can still grow** — an empty operand OR an un-instantiated tail below the declared reps — and **VALUE only once exhausted**. Matches the kernel on the `AggregateUncertaintyLawsTest` cases (empty-operand → OMISSION; 3-of-50 → OMISSION; all-50-filled → VALUE).

### Localized message text (closed, scoped)

The interpreter renders a fired rule's per-locale `errorMessage` with `$field.value$` substituted, and the rendering matches the kernel's `getErrorText` in **both en_US and de_DE** (`LocalizedMessageDiffTest`) — proving value-interpolation **and** locale-selection from one engine; the `$field.value$` renderer is `commonMain` (runs on JS too). Confirmed placeholder syntax: `$field.value$` = value (`getValueForDisplay`), `$field$` = display name (`identifierToString`); **same placeholder across locales**, only the surrounding text is localized. Also learned: a model must *declare* a locale (order.dm.json ships only en_US) for the kernel to render it — the test adds de_DE to the model's `locales`. **Scope boundary (still open):** the `$field$` display-name placeholder needs the field's label metadata, and the formal-error (type-error) message bundles are a separate, German-keyed tier.

## What was built

A real Kotlin Multiplatform module — one interpreter, two targets, consumed two ways:

```
:interpreter (KMP)
  commonMain/…/spike/   Ast, EvalContext, Interpreter, SpikeDoc, RepDoc, Dec(expect)   ← the engine (pure)
  jvmMain/…/spike/      Dec.jvm  (java.math.BigDecimal)        ┐ the ONE platform-specific
  jsMain/…/spike/       Dec.js   (Double, POC)                 ┘ primitive — the §5 "thin shim"
  commonTest/…/spike/   InterpreterMultiplatformTest           → runs on JVM + JS (Node), no kernel

:adapter (JVM)  testImplementation project(':interpreter')
  …/adapter/laws/SpikeInterpreterDiffTest   shapes 1-3  ┐ differential vs the REAL kernel
  …/adapter/laws/RepetitionDiffTest         shape 4     ┘ (RuntimeLaws oracle), JVM only
```

## Per-shape outcome (JVM, differential vs kernel)

| # | shape | cases | result |
|---|---|---|---|
| 1 | comparison + three-valued logic | 2 | **MATCH** |
| 2 | empty-value **type asymmetry** (number→0 fires, confirm→False fires, boolean/string not-evaluated, entirely-empty row not-evaluated) + `FieldFilled` guard + operand-list `Min(∅→0)` | 12 | **MATCH** |
| 3 | starred aggregates `Sum`/`MaxValue`/`NumberOfDifferentValues` (Sum substitutes 0, Max ignores empties) + chronological date `MaxValue` | 8 | **MATCH** |
| 4 | per-row `*`-iteration (fires on exactly the offending rows) | 3 | **MATCH** |

Every case asserts `interpreterFires == kernelFires` over the *same* document instance. All green.

## JS POC outcome

`:interpreter:allTests` runs `InterpreterMultiplatformTest` on **JVM and JS (jsNodeTest)** — same comparison / empty-asymmetry / guard / `Sum` / per-row-iteration cases, identical results on both platforms.

## The metadata / iteration bridge (what evaluation actually needed)

- The "metadata bridge" the proposal worried about was, for these shapes, **just a `path → Kind` map** (number/string/boolean/confirm/date) — the empty-value asymmetry is entirely type-driven, so the interpreter needs field *kinds* but little else.
- An `EvalContext` seam (`field` / `filled` / `star` / `evaluated`) lets the **one** evaluator serve both a single row and a repeatable group's per-row + outer contexts — **no duplicated operator logic** for iteration. Per-row iteration = one context per row; starred aggregates = the outer context's `star(path)` collecting filled values across rows.
- The "entirely-empty row is not evaluated" rule maps cleanly to a per-context `evaluated` gate.

## The three §11 open decisions, informed

| decision | what the spike showed |
|---|---|
| **Throughput** | Not stressed (correctness spike). But the kernel's scale optimizations are algorithmic (caches, `*`-prune) and would be reimplemented in the same `EvalContext`/iteration layer — the interpreter shape doesn't preclude them. Defer the IR-VM until a measured need. |
| **Message fidelity** | The spike matched **firing** (and row attribution for per-row rules). It did **not** yet reproduce the `MessageType` polarity (OMISSION vs VALUE) or localized text — that's the harder fidelity tier (`AggregateUncertaintyLawsTest` shows the omitted-tail subtlety). A real build needs it; scope it explicitly. |
| **Parser** | Deferred as planned — the AST was hand-built. No parser was needed to prove evaluation. Still the open choice (kernel parse-tree-api vs ANTLR-from-grammar). |

## Toolchain findings (both risks cleared)

- **Kotlin/JVM 2.2.0** and **Kotlin Multiplatform 2.2.0** both resolve from the Gradle Plugin Portal inside the **isolated, public-repos-only Gradle home**, on Gradle 9.0 / JDK 21. No Java fallback was needed.
- **Kotlin/JS works in the isolated home**: the plugin downloaded Node, ran the npm/yarn install, compiled to JS, and executed `jsNodeTest` — all offline-of-the-private-registry. This was the main unknown for the JS POC; it's cleared. (Implication: CI for the JS target needs network to nodejs.org + the npm registry the first time.)
- **Native-safety: clean.** `commonMain` has zero `java.*` and zero `com.mgmtp.*`/groovy imports; the only JDK type is `BigDecimal` in the JVM `Dec` actual.

## Caveats / still not proven

After the follow-ups above (big.js, polarity, parser, throughput, omitted-tail polarity), what remains:

- **Localized message text — display-name + formal-error tiers** — value-interpolation + locale-selection are *done* (Update 2); still open: the `$field$` display-name placeholder (needs field label metadata) and the formal-error (type-error) message bundles (a separate German-keyed tier).
- **Parser is a subset** — covers the shapes exercised; the full grammar (and the kernel-parse-tree-api-vs-ANTLR choice) is still §11's open decision.
- **Benchmark is an in-test bound, not a tracked baseline** — fine as a gross-regression guard; a real perf-regression harness (JMH / recorded baselines) is Phase-1 work.
- `expect/actual class` emits a beta warning (harmless; suppressible with `-Xexpect-actual-classes`).

## Recommended scope for Phase 1

1. Keep the **`EvalContext` seam** and the **single `commonMain` engine consumed by adapter (JVM, kernel-diff) + a JS test** — that structure worked and is the proper Phase-1 shape.
2. Swap the JS `Dec` actual to a real decimal (big.js / multiplatform bignum) early, before the operator set grows.
3. Add the **`MessageType`/polarity** dimension next — it's the largest remaining fidelity gap and has real subtleties (the omitted-tail typing).
4. Decide the **parser** (kernel parse-tree-api vs ANTLR-from-grammar) — the first thing that forces real condition *text* through the engine.
5. Drive everything with the `RuntimeLaws`/`KernelLaws` differential oracle, expanding shape coverage.

## Resuming this work — state & how to continue (on `main`, post-merge)

> The "Recommended scope" list just above predates the follow-ups: items 2–4 (big.js, MessageType/polarity, parser) are **done** (see Updates 1–2). This section is the live state.

**Where it is.** The spike is **merged into `main`** (merge commit `56a622f`). No branch/worktree remains.

**Module layout (on `main`):**
- **`:interpreter`** — the engine, a Kotlin Multiplatform module, **kernel-free** (the publishable, native-safe artifact). `commonMain` = `Ast` · `Dec` (expect) · `EvalContext` · `Interpreter` · `Message` · `Parser` · `RepDoc` · `SpikeDoc`; `jvmMain`/`jsMain` = the `Dec` actuals (BigDecimal / big.js); `commonTest` = `InterpreterMultiplatformTest` (runs on JVM **and** JS, no kernel).
- **`:adapter`** (test scope) — the kernel-**differential** tests (`…/adapter/laws/*DiffTest.kt`) that compare the interpreter to the real kernel via the `RuntimeLaws` oracle; they consume `:interpreter`.

**Build / verify (always via `./build.sh` — isolated Gradle home + JDK 21, never bare `./gradlew`):**
- Everything: `./build.sh :interpreter:allTests :adapter:test`
- JS only: `./build.sh :interpreter:jsNodeTest` · JVM engine tests: `./build.sh :interpreter:jvmTest`

**Open decisions / residue:**
1. **Test placement** — the differential tests + a Kotlin test toolchain currently live in `:adapter` (otherwise Java-only). Options: leave it (spike pragmatism) · rewrite them in Java to keep adapter Java-only · or move them to `:interpreter`'s `jvmTest` with a *test-only* kernel dep (the proposal §8 Phase-2 shape, which also needs the `RuntimeLaws` oracle ported/shared since adapter's own law tests still need it).
2. `$field$` display-name + formal-error message tiers (localized-text residue).
3. Full parser grammar + the kernel-parse-tree-api-vs-ANTLR choice (§11).
4. A real perf-regression harness (JMH / tracked baselines) vs. the in-test bound.
5. **Phase-2 extraction** (proposal §8): lift `:interpreter` into a sibling repo `a12-dm-interpreter` (its own conformance suite, test-only kernel dep) — removes the KMP toolchain from this repo's build.

**Design record:** [INTERPRETER-PROPOSAL.md](INTERPRETER-PROPOSAL.md) (intent + §10 spike result; extraction plan in §8) · this file.
