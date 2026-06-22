# Interpreter spike — execution plan

Working plan for the §10 spike of [INTERPRETER-PROPOSAL.md](INTERPRETER-PROPOSAL.md). A **throwaway prototype** to de-risk the foundational loop, not production code — now executed (results in [INTERPRETER-SPIKE-FINDINGS.md](INTERPRETER-SPIKE-FINDINGS.md)) and merged into `main`.

## Goal & acceptance criterion

Prove that a **hand-written tree-walking interpreter** can reproduce the kernel's runtime rule evaluation for a few condition shapes — measured by the user's criterion: **the interpreter is correct iff it agrees with the kernel.**

Primary assertion, for a battery of `(condition, document)` cases:

```
interpreterFires(condition, doc)  ==  kernelFires(condition, doc)
```

Secondary (the message-fidelity probe): how close can the interpreter get to the kernel's `(code, errorField, MessageType)` triple — not just *whether* it fires, but the polarity typing.

## The differential loop (how every case is tested)

The existing law tests already do the **kernel** side. The spike adds the **interpreter** side over the *same* instance. Per case:

- **Kernel side** — `RuntimeLaws.withRule(model, group, ruleName, errorRelPath, code, conditionText)` → `firedCodes(model, groupPaths, emptyFields, fieldValues)` (and `messages(...)` for the MessageType).
- **Interpreter side** — hand-build the *same* condition as a spike AST, build the *same* instance as a `SpikeDoc`, evaluate → does the error condition hold?
- **Assert** the two agree.

To keep both sides feeding identical data, each case is a single spec (`fields`, `values`, `empties`, `types`) that drives *both* the `RuntimeLaws` call and the `SpikeDoc`. The condition exists twice — as **text** (kernel) and as a **hand-built AST** (interpreter); we eyeball their equivalence (no parser — that decision is deferred, §11).

## What to build (the interpreter, ~4 small files)

All under `adapter/src/test/kotlin/io/github/mbackschat/a12/dm/interpreter/spike/` (TEST scope — it must not ship in adapter's main jar):

1. **`Ast.kt`** — a sealed condition AST + a typed three-valued result.
   - `sealed interface Cond` → `Comparison(op, Expr, Expr)`, `And`, `Or`, `Not`, `Call(name, args)` (for `FieldFilled`, `AllFieldsFilled`, `Length`, `Min`, `Sum`, `DifferenceInDays`, `PatternViolated`, value-list membership…).
   - `sealed interface Expr` → `FieldRef(path)`, `NumLit`, `StrLit`, `BoolLit`, …
   - `enum Tri { TRUE, FALSE, UNKNOWN }` — three-valued logic. `UNKNOWN` = "not evaluated" → does **not** fire. `And`/`Or` follow Kleene logic.
2. **`SpikeDoc.kt`** — the plain-data document the interpreter walks (NO kernel types).
   - Field instances keyed by `(path, repIndices)` → value, with the three states **value / present-but-empty / absent**.
   - Group rows (present repetition instances).
   - A tiny **field-type map** `path → SpikeType { NUMBER, STRING, BOOLEAN, CONFIRM, DATE }` — the miniature "metadata bridge" (the empty-handling asymmetry is type-driven, so the interpreter must know types).
   - A "row is filled" helper (≥1 sibling value in the row) — needed for the empty-as-0 precondition.
3. **`Interpreter.kt`** — the tree-walking evaluator.
   - Walk `Cond`, resolve `FieldRef` against `SpikeDoc` in the current row context, apply operators with three-valued + type-driven empty semantics, return `Tri`. A rule **fires** when its error condition evaluates `TRUE`.
   - Model decimals with `java.math.BigDecimal` (the JVM half of the eventual `expect/actual` shim — see proposal §5).
4. **`InterpreterDiffTest.kt`** (or `.java`) — in package `io.github.mbackschat.a12.dm.adapter.laws` so it can reach package-private `RuntimeLaws`. The differential battery (below).

## The four shapes (concrete, all on `models/order.dm.json`)

| # | shape | condition (kernel text) | the cases | what the interpreter must reproduce |
|---|---|---|---|---|
| 1 | **comparison** | `[Quantity] < 100` | filled `200` → no fire; filled `50` → fire | numeric compare + three-valued logic |
| 2 | **empty-as-0 asymmetry** | `[Quantity] < 100`; `[TermsConfirmed] != True`; `[ExpressShipping] == True`; `[ProductCode] == "ABC"` | each with the field **empty in a filled row** (sibling `CustomerName="Acme"`), and the **entirely-empty row** control | number empty → 0 (fires); confirm empty → False (fires); boolean/string empty → UNKNOWN (no fire); entirely-empty row → not evaluated. This is the heart of the spike. |
| 3 | **aggregate** | `Min([Quantity], [BackorderQuantity]) == 0` (operand-list) and/or a starred `Sum`/`MinValue` over `/Order/Items/Count` | empty operand vs filled | operand-list substitutes empty→0; starred aggregate **skips** empty rows (the families differ — see `AggregateUncertaintyLawsTest`) |
| 4 | **nested-repetition `*`** | a per-row rule / aggregate over `/Order/Items` rows | 2–3 `Items` rows with mixed `Count` values | iterate repetition rows, resolve `FieldRef` per row, attribute the fire to the right row. **Hardest — partial is acceptable**; document exactly what the iteration/metadata bridge needs. |

Reference templates already in the repo: `EmptyValueLawsTest` (shapes 1–2, verbatim conditions + `RuntimeLaws` usage), `AggregateUncertaintyLawsTest` (shape 3), `RepetitionLawsTest` (shape 4).

## Discipline (non-negotiable)

- **The interpreter (`Ast`/`SpikeDoc`/`Interpreter`) imports ZERO `com.mgmtp.*` and ZERO `groovy.*`** — pure static code over plain data. Only the diff *test* touches the kernel (via `RuntimeLaws`). Verify at the end with a grep over the spike sources. This is what keeps the native-safety claim honest.
- **Hand-roll the AST. No parser.**
- **Spike quality** — minimal, no infrastructure, no gold-plating. Learning is the deliverable.

## Build & run

- Build ONLY via `./build.sh <args>` (isolated Gradle home + JDK 21). Never bare `./gradlew`.
- Run the battery: `./build.sh :adapter:test --tests '*InterpreterDiff*'`.

## Step-by-step checklist

0. **(Verify the build is green here first)** `./build.sh :adapter:test --tests '*EmptyValueLawsTest*'` — confirm the worktree builds + the oracle runs before adding anything.
1. **Kotlin toolchain probe** — add `id 'org.jetbrains.kotlin.jvm' version '<match>'` to `adapter/build.gradle`, drop in a trivial `src/test/kotlin` file, `./build.sh :adapter:compileTestKotlin`. **If the plugin won't resolve in the isolated home → fall back to writing the interpreter in Java** and record the toolchain blocker as a key finding (it affects Phase-1). Decide this *before* investing.
2. **`Ast.kt` + `SpikeDoc.kt`** — the AST, three-valued `Tri`, the plain doc + field-type map.
3. **`Interpreter.kt`** — shapes 1–2 first (comparison + the empty asymmetry). Get the type-driven empty handling + filled-row precondition right.
4. **`InterpreterDiffTest`** — wire the case spec that feeds both sides; battery for shapes 1–2; run; reach agreement with the kernel.
5. **Shape 3 (aggregate)** — operand-list vs starred.
6. **Shape 4 (`*`-iteration)** — attempt full; if partial, document the gap precisely.
7. **MessageType probe** — for the passing cases, also compare `messages(...)`'s `MessageType`; note how close polarity gets (message-fidelity finding).
8. **Native-safety grep** — confirm no kernel/groovy imports in the interpreter sources.
9. **Write `SPIKE-FINDINGS.md`** (skeleton below); **commit** (`spike(interpreter): differential interpreter prototype over the kernel oracle`).

## `SPIKE-FINDINGS.md` to produce

- **Per-shape outcome** — MATCH / DIVERGENCE (with the diff) / PARTIAL, with the actual kernel-vs-interpreter values.
- **The AST/metadata/iteration bridge** — what evaluation concretely required; what `*`-iteration needs.
- **The three §11 open decisions, informed by the spike** — (a) throughput signal; (b) message-fidelity reality (how close `(code, errorField, MessageType)` got, what byte-identical localized text would take); (c) parser observations.
- **Native-safety verdict** (the grep result).
- **Kotlin toolchain outcome** (resolved? or Java fallback + why).
- **Recommended scope adjustments** for the real Phase-1 build.

## Known risks & fallbacks

- **Kotlin Gradle plugin resolution in the isolated, public-repos-only Gradle home** — the main risk. Probed in Step 1; Java fallback ready.
- **`*`-iteration** is the deep end — accept partial, the *gap description* is the value.
- **Condition text ↔ hand-built AST equivalence** is eyeballed (no parser) — fine for a spike; a real divergence there is a test bug, not a finding.
