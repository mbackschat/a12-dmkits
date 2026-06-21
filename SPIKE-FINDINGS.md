# Interpreter spike — findings

Result of the [`SPIKE-PLAN.md`](SPIKE-PLAN.md) spike for [`docs/INTERPRETER-PROPOSAL.md`](docs/INTERPRETER-PROPOSAL.md) §10. On branch `spike-interpreter` (throwaway prototype).

## Verdict

**The thesis holds on both axes.** A hand-rolled tree-walking interpreter (1) reproduces the kernel's runtime rule evaluation for all four target shapes — verified against the *real kernel* via the existing `RuntimeLaws` oracle on the JVM — and (2) the **same `commonMain` engine compiles to Kotlin/JS and runs in Node**, with the only platform-specific code being a tiny `expect/actual` decimal shim. Both the native-image-clean property and the cross-platform property are demonstrated, not asserted.

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

## Caveats / not yet proven

- **JS decimal is `Double` for the POC** — exact for the integer/simple-decimal fixtures, but not arbitrary-precision. Production swaps the JS `Dec` actual for big.js or a multiplatform bignum; the swap is localized to `Dec.js.kt` and the engine never changes.
- **No `MessageType`/polarity, no localized message text, no formal-error model** — only fire/no-fire (+ per-row attribution).
- **Throughput unmeasured.**
- **No parser.** A handful of operators only (the four shapes' worth).
- `expect/actual class` emits a beta warning (harmless; suppressible with `-Xexpect-actual-classes`).

## Recommended scope for Phase 1

1. Keep the **`EvalContext` seam** and the **single `commonMain` engine consumed by adapter (JVM, kernel-diff) + a JS test** — that structure worked and is the proper Phase-1 shape.
2. Swap the JS `Dec` actual to a real decimal (big.js / multiplatform bignum) early, before the operator set grows.
3. Add the **`MessageType`/polarity** dimension next — it's the largest remaining fidelity gap and has real subtleties (the omitted-tail typing).
4. Decide the **parser** (kernel parse-tree-api vs ANTLR-from-grammar) — the first thing that forces real condition *text* through the engine.
5. Drive everything with the `RuntimeLaws`/`KernelLaws` differential oracle, expanding shape coverage.
