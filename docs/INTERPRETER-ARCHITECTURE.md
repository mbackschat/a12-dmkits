# Interpreter architecture — implementation, code design & special topics

How the `:interpreter` module is built. The **design decision + contract** (why interpret instead of generate-and-eval, the goals, the boundaries) lives in [INTERPRETER-SPEC.md](INTERPRETER-SPEC.md); the **numbered design findings** (the *why* behind specific choices) in [INTERPRETER-FINDINGS.md](INTERPRETER-FINDINGS.md); the **performance method + benchmark catalog** in [INTERPRETER-PERFORMANCE.md](INTERPRETER-PERFORMANCE.md). This doc is the *how* — the code structure, the data structures, the techniques, and the special topics. It describes the system **as it is today**; update it when the structure moves (same reflex as [ARCHITECTURE.md](ARCHITECTURE.md)).

`:interpreter` is a **Kotlin Multiplatform**, **kernel-free** runtime evaluator (`package io.github.mbackschat.a12.dm.interpreter`)—the publication-eligible module dmtool's native image runs for `model eval`/`compute`, `rule eval`, and `model seed`. Maven/npm distribution is not wired yet (IG85). Correctness means agreement with the real A12 kernel; the differential verifies the exercised surface and the live exceptions stay in [INTERPRETER-GAPS.md](INTERPRETER-GAPS.md).

## 1. Kotlin Multiplatform & the two targets (JVM + JS)

One engine, two compile targets, from a single `commonMain` source set:

```
:interpreter (KMP)
  commonMain/   the whole engine — pure Kotlin, ZERO java.* / com.mgmtp.* / groovy.*
  jvmMain/      Dec.jvm.kt   actual = java.math.BigDecimal
  jsMain/       Dec.js.kt    actual = big.js   (@JsModule, an npm dependency)
  commonTest/   runs on JVM AND JS (jsNodeTest) — no kernel
```

- **The decimal primitive is platform-specific, and pattern compilation has platform actuals.** `Dec` is an `expect class` in `commonMain` with `java.math.BigDecimal` on the JVM and **big.js** on JS; `CompiledPattern` targets Java `Pattern` vs an ES2018 translation executed by JS `RegExp`. Sharing the evaluator does not by itself equalize host-library semantics; IF163 and IF164 close those two host-sensitive contracts with common JVM/Node locks and real-kernel differentials at the model-observable loci.
- **`commonMain` is kernel-free by construction**, enforced by the **`verifyInterpreterPurity`** lint (wired into `check`): a forbidden import (`java.*`/`com.mgmtp.*`/`groovy.*` in `commonMain`; `com.mgmtp.*`/`groovy.*` in `jvmMain`/`jsMain`) fails the build. This makes the module native-image-safe and JS-compilable. The shared suite runs under Node, the supported JS runtime; browser packaging/host behavior is a deferred capability (IF138).
- **Host exactness is explicit, never inferred from shared code:** IF163 locks decimal parity and IF164 locks the admitted Java-pattern dialect across JVM/Node, with the JVM serving the real-kernel differential host. (`expect/actual class` also emits a harmless beta warning, suppressible with `-Xexpect-actual-classes`.)

## 2. Package layering

`commonMain` is layered so dependencies flow one way and the operator semantics never know which evaluation strategy sits on top:

```
        model   ←   ast   ←   parse              (data contract ← AST ← text→AST)
          ↑          ↑
          └────  eval  ────┘                     (the engine over the AST + a document)
                  ↑
              DmInterpreter (root façade)
```

| package | holds | role |
|---|---|---|
| `.model` | `ModelDef`/`FieldDef`/`RuleDef`/`ComputationDef` · `Kind`/`MsgType`/`Severity`/`Requiredness` · `Message`/`RuleResult`/`ValidationOutcome`/`Computed` · `RelevantEntity` · the `CustomCondition` SPI · `CustomFieldType`/`CustomFieldTypeDeclaration`/`CustomFieldValidationContext`/`CustomTypeRejection` · `PredefinedType` registry | the data contract consumers build/read |
| `.load` | `ModelLoader` — prepared, self-contained, name-path-keyed DM-JSON → `ModelDef` (kotlinx tree API, WI-2); rejects unprepared residue loud (IF145) | build a `ModelDef` after upstream assembly; the standalone commonMain assembler is IG68 |
| `.ast` | `Cond` / `Expr` (sealed) · `Val` / `Tri` · `typedValue`/`emptyValue` | the parsed rule, as data |
| `.parse` | `parseCondition` / `parseExpression` — recursive descent (§3) | text → AST |
| `.eval` | `Interpreter` (the tree-walk strategy = the per-row eval-state seam, IF117) + its collaborator extension files `ExprEval`/`PolarityWalk`/`ValueListQuantEval` + the per-call `EvalConfig` bundle · `EvalContext` + `Document` (+ the `OverlayContext` cascade decorator, and the `SingleRowDoc`/`RepDoc` test doubles) · the operator registries (`PredicateOp`/`FunctionOp`/`ConstantOp`) · `Kleene` · `DateMath` · `ValCompare` · `Aggregates` · `Polarity` · `CorrelationJoin` (the pure `$`-join recognizer) · `FormalError` · `Pointer` · the message renderer | the engine |
| root | `DmInterpreter` (the public façade) + its prepared `PreparedSupportAudit` and I2 seam collaborators (`ParsedModel` · `AutoChecks` · `IterationScopes` · `RepetitionNotUniqueEvaluator` · `ComputationEngine` · the per-call `ValidationPass` · the pure `Paths`/`AstRefs` helpers — IF117), the `Dec` `expect`, `UnsupportedConstructException` | API surface |

**Dependency direction:** `model ← ast ← parse`, `model,ast ← eval`, `model ← load`, all ← root. `:interpreter` depends on **one** third-party runtime lib — kotlinx-serialization-json, common, tree API only, for `.load` (whitelisted in the purity lint per proposal §11 F1) — and on no kernel/dmtool; `:`/`:adapter`/`:cli` consume *it*, never the reverse.

**The `.eval` strategy is organized around the eval-state seam** ([INTERPRETER-FINDINGS.md](INTERPRETER-FINDINGS.md) IF117). An `Interpreter` instance is one immutable per-row eval-state — `(ctx, config, customErrorField, invalid, outer, outerUnbound)` — and the resolution-coupled subsystems are collaborator **extension families** on it, one file each, sharing that state with no per-row allocation beyond the one object: `ExprEval.kt` (value-expression resolution — `evalExpr`/`evalFunc` + the value helpers, the semantic-index and date/time machinery), `PolarityWalk.kt` (the typed truth+polarity walks + the fill-resolution machinery), `ValueListQuantEval.kt` (VLQ truth + polarity). `Interpreter.kt` keeps the strategy core — the public API, the row gates, the truth walk (`evalCond`/`evalPred`/`evalCmp`/…), the quantifier tallies/scans, and the overlap predicates — plus the **seam contract**: the `internal` state reads and cross-cluster dispatch the collaborators consume; everything else is class- or file-private. Chosen shape + rejected alternatives: IF117.

**The root façade is organized by state LIFETIME (the seam's I2 half, IF117/IF152).** `DmInterpreter` wires call-invariant collaborators once per engine — `ParsedModel` (the parse-once AST caches + the implicit computation-validation rules), `AutoChecks` (the auto-generated checks + uncached formal oracle), `IterationScopes` (scope resolution + the parallel outer join), `RepetitionNotUniqueEvaluator`, and `ComputationEngine` (the cascade — overlay/poison/parallel; its per-call state stays local, reified as `OverlayContext`) — a DAG with no callbacks into the façade. `ValidationPass` owns the validation call's document, locale, relevance, duplicate-index cells, once-per-call `EvalConfig`, and complete `FormalResultCache`; it checks relevance before custom validation and shares one result across automatic emission, rule/candidate suppression, RNU, and per-row evaluation. Computation owns the same cache shape for its call, keyed by effective raw value so cascade overlays cannot reuse a stale result. No formal/custom cache crosses a call or snapshot. Pure string/AST helpers live top-level in `Paths.kt`/`AstRefs.kt`; the façade retains orchestration, the row-outcome loop, and relevance builders.

## 3. Parsing, the AST & "lowering"

**Parsing.** `parseCondition(text, ruleGroup)` is a small **recursive-descent** parser (`parse/Parser.kt`) — no ANTLR, no generated tables, so it compiles to JS and stays dependency-free (parser-source decision resolved in WI-P: hand-written completion, INTERPRETER-SPEC §11 / IF55). It turns the kernel's condition DSL (`FieldFilled(Qty) And [Qty] > 50`, `Sum(Lines*/Amount) > 0`, `[field -> Category]`, `CustomCondition X`, …) into the sealed `Cond`/`Expr` AST, handling operator precedence (`^` > `*`/`/` > `+`/`-`; comparisons; `And`/`Or`), `{ }` grouping, the `[...]` field-reference / semantic-index / enum-category atoms, and the bracket/quote literal forms. It accepts the full stored grammar the kernel emits — keyword **case variants** (`And`/`and`/`AND`, …), `;;` **comments** (incl. multi-line), `..Name` **turning-point** paths, and the `For` semantic-index suffix on **bare** args — verified 100% over the catalog/fuzz + real corpora (`adapter.laws.ParserCorpusSweepTest`).

**The AST.** `Cond` (sealed): `And`/`Or`/`Not`, `Cmp` (a comparison), `Pred` (a named predicate — `FieldFilled`, the quantifiers, …), `Pattern`, `Tolerance`, `ValueListQuant`, `Custom` (a `CustomCondition`). `Expr` (sealed): `Field`, `Star` (a `*` aggregate operand), `Func` (a value function), `Arith`, `SemanticIndex`, `EnumCategory`, and the literals. Values during evaluation are `Val` (`Num`/`Txt`/`Bool`/`Range`/`Unknown`) and truth is the three-valued `Tri` (`TRUE`/`FALSE`/`UNKNOWN`).

**On "lowering".** There is **no separate lowering IR** — the `Interpreter` tree-walks the parsed AST directly (the kernel-free thesis: interpret the AST, don't lower it to generated code). The normalization that *does* happen is at **parse time**: relative field paths are resolved against the rule's group to absolute paths; an `@From <ref>` reference-group marker is carried as a trailing `StrLit` on a `RepetitionNotUnique` so the evaluator can read it without a new node type; precedence/associativity are resolved into the tree shape. Everything past the parser is a faithful walk of that tree — the simplicity that keeps the engine portable and the differential honest.

**The document data structures** (`eval/Document.kt`) — see §5 for the performance properties:
- The **general `Document`** is the production instance: a flat list of *placements* (group rows + field values), each addressed by **one 1-based repetition index per path part** (`/Order/Items/Count` at `[1,2,1]`), behind a lazily-built `Index`.
- **`EvalContext`** is the seam the `Interpreter` evaluates against (`field`/`filled`/`signed`/`star`/`groupFilled`/`displayValue`/`repsOf`/…). One `Document` yields many `EvalContext`s — one per row of an iteration scope — so the *same* operator logic serves a single row, a per-row iteration, and an outer aggregate context, with no duplicated code.
- **`SingleRowDoc`/`RepDoc`** are lightweight `Map`-backed `EvalContext` doubles for *isolated Interpreter unit tests* (not production models, never benchmarked — see their KDocs and [INTERPRETER-FINDINGS.md](INTERPRETER-FINDINGS.md)).

## 4. The runtime-semantics layering (generator-ready)

The engine is layered so an interpreter (tree-walk, today) and a future **generator** (emit code per model, the kernel's approach) could share the *same* runtime operator semantics — both are evaluation *strategies* over one runtime ([INTERPRETER-FINDINGS.md](INTERPRETER-FINDINGS.md) IF10):

```
   strategy        the Interpreter tree-walk (today)   |   a generator (future) — NOT built
   ───────────────────────────────────────────────────────────────────────────────────────
   runtime         pure functions over RESOLVED values, AST-walk-agnostic:
   semantics         · Kleene 3VL (and/or/not)         · quantifierFires (list quantifiers)
                     · PredicateOp/FunctionOp/ConstantOp registries   · DateMath
                     · ValCompare (value order)   · Aggregates (Sum/Min/Max/distinct/SumOfProducts)
                     · Polarity (comparison-fillability rules + the Fill value object)
   ───────────────────────────────────────────────────────────────────────────────────────
   data access     EvalContext / Document — field / filled / signed / star / reps
```

- **The truth model is Kleene's strong three-valued logic** (`eval/ThreeValued.kt`, `object Kleene`). A condition is `TRUE` (fires), `FALSE`, or `UNKNOWN` ("can't decide"); a rule fires **only on `TRUE`**. This one tiny truth table is the *single* mechanism behind formal-error suppression, empty-row gating, and partial validation — every suppression path just sends an operand to `UNKNOWN`, and `T ∨ U = T` / `T ∧ U = U` does the rest. It is verified bit-for-bit against the kernel's `combineUND`/`combineODER` (`KleeneDiffTest`; full treatment in [INTERPRETER-FINDINGS.md](INTERPRETER-FINDINGS.md) IF17).
- **Operator semantics are extracted** as pure, strategy-agnostic functions (`Kleene`, `quantifierFires`, `DateMath`, the operator registries, `ValCompare` (value order), the `Aggregates` folds — `Sum`/`Min`/`Max`/`NumberOfDifferentValues`/`SumOfProducts`, and `Polarity` — the comparison-fillability rules + the `Fill` value object); the IG5 extraction **completed 2026-06-27**. What stays in the strategy — the `Interpreter` eval-state plus its IF117 collaborator extension files — is **operand resolution**, the data-access half that belongs there (resolve a field/aggregate operand to its `Fill`, the star walks, `evalExpr`, the trivial resolution-coupled value-list count / pattern regex). The strategy resolves + dispatches; the runtime layer applies the operator — so a generator emits the same runtime calls over its own resolution. We do **not** build the generator now — this only keeps adding one later a new top layer, not a rewrite.

## 5. Performance and relative-footprint techniques

The hard cases are **groups at scale** — deeply nested repeatable groups, cross-level references, starred aggregates over large lists, many rows (up to 100 000). Method + the benchmark catalog + the regression gate: [INTERPRETER-PERFORMANCE.md](INTERPRETER-PERFORMANCE.md). The implemented techniques:

| technique | what it does | complexity |
|---|---|---|
| **Indexed placements** (`Document.Index`, task #28) | a lazily-built, mutation-invalidated index maps `(path, reps) → placement` and `groupPath → rows`, so `field`/`filled`/`displayValue`/`star` are O(1)/O(rows), not an O(P) linear scan of the placement list | per-lookup O(1); per-row eval linear, not quadratic |
| **Amortized `evaluated`** | `contextsFor(group)` computes every row's "is this row evaluated" in ONE pass over the valued fields (recording each field's index-prefix at its attach-depth), not an O(P) scan per row | O(P) once per scope, not per row |
| **Common-prefix (cross-level) resolution** | a per-row rule that references an *ancestor*-group field resolves it by the shared repetition-index prefix of the two paths (`starRows` filters rows whose common-prefix indices match the current row) — so an inner row reads the right outer-group value | O(rows) per scope |
| **Star aggregation** | `Sum`/`Min`/`MaxValue`/… collect a starred field's values across a repeatable group's rows in row order via the index; `FirstFilledValue` reads the first filled in that order | O(rows) |
| **Indexed `$` correlation join** | recognized equality filters use bind-depth-scoped hash buckets; a single comparable field-to-outer-field comparator uses a scope-cached ordered prefix tally; both avoid rescanning the inner repetition for every outer row | equality: O(rows) build + O(1) lookup; ordered comparator: O(rows log rows) build + O(log rows) lookup; compound predicates, unsupported kinds, and multi-star multi-row self-exclusion retain the explicit general fallback (IF144/IF159) |
| **Nested / parallel iteration** | the deepest repeatable group an unstarred reference touches drives the iteration scope; nested groups compose the *same* per-row `EvalContext` primitive (one context per row), so deeper nesting is more contexts, **no new mechanism** | linear in instantiated rows |

**Low footprint is relative, not cache avoidance.** The data structures above deliberately spend bounded memory on indices, hash maps, parsed ASTs, row-context memos, and resolution caches because that is how the engine avoids repeated scans and quadratic work. These caches are the expected architecture, not debt to remove for a smaller heap number. The constraint is ownership and growth: model-invariant caches live on `DmInterpreter`, document-derived caches belong behind the mutation-invalidated `Document.Index`, call state in `ValidationPass`, and row state on one `Interpreter`; no global/unbounded cache and no document-wide state duplicated per rule/row. A cache must plateau for a stable owner, preserve invalidation correctness, and have its footprint delta reported beside the performance win. **Fixed (IF142):** `rowContextsCache` and `correlationBucketCache` live on `Document` but are cleared with `cachedIndex` by one invalidation method (`invalidateDerivedCaches`) on every mutation — the invariant holds without moving them into `Document.Index`; locked by `DocumentMutationCacheInvalidationTest`. The normative tradeoff and relative growth/closure bars are NFR-F1/F2 in [INTERPRETER-SPEC.md](INTERPRETER-SPEC.md).

**Regression gates:** the interpreter-only growth guards exercise the real `Document` at `N` and `4N`; comparative canaries/benchmarks first assert the interpreter and kernel agree, then measure a time/ratio bound. The historical gate is a catastrophic-regression floor; the startup/throughput/relative-footprint certification contract is in [INTERPRETER-SPEC.md](INTERPRETER-SPEC.md) and its method/status in [INTERPRETER-PERFORMANCE.md](INTERPRETER-PERFORMANCE.md).

**Prepared model — measured and incrementally realized.** `ParsedModel` parses each stored rule/computation lazily once per `DmInterpreter`; `PreparedModel` reuses the corresponding rule/computation plans; IF164's engine-owned registry compiles each distinct model-static condition/declared/predefined/computation pattern once; and `ComputationEngine` caches the whole-model dependency order and each requested field's transitive closure. Exact JVM+Node preparation counters prove those classes remain model-static across documents and repeated calls (IF171). Date-format preparation remains owned by its semantic feature work rather than being inferred from this structural counter. Value-dependent results remain document/call scoped and are never reused across changed documents. Compute-overlay index columns, equality-correlation buckets, and ordered-correlation tallies are cached per computation step (IF143/IF159), under the same `(column, scope)` lifetime as the raw document-level memos.

**Humility:** the kernel team spent years optimizing (its `IndexFieldCache`/`ValidationCache`/`CalculationCache`/`GroupFillCache`). We read those for the *idea* (index-by-field, memoized group-fill, cached calculations within a run), reproduce the mechanism in original Kotlin (the license boundary holds — §6), and let the against-kernel benchmark tell the truth.

## 6. The kernel-as-oracle approach (correctness)

Correctness is defined as **agreement with the real kernel**, and proven by a **differential oracle**, not by re-deriving the spec:

- **The oracle** (`adapter/…/laws/RuntimeLaws`, `:adapter` — the only module with the kernel) runs the kernel's real `IDocumentRtService.validateFull`/`validatePart`/`compute` over a constructed instance and reports what fired (code, polarity type, pointer). Each interpreter increment is locked by a `…DiffTest` that builds the *same* instance two ways and asserts `interpreter == kernel`.
- **The differential-test recipe:** add a `…DiffTest.kt` in `adapter/.../laws/`; load a fixture model (`RuntimeLaws.model("models/…")`); inject the rule (`RuntimeLaws.withRule(...)` / `.withComputation(...)`); build the instance two ways from one spec — `RuntimeLaws.doc()…` for the kernel, the general `Document`/a test double for the interpreter; assert equality on fired codes + polarity + the rich-message channels. Drive the interpreter from **parsed condition text**, not a hand-built AST.
- **The A–E harness series** ([SEED-GENERATOR-FUZZ-SPEC.md](SEED-GENERATOR-FUZZ-SPEC.md)) layers coverage on top of the per-operator DiffTests: **A** a kernel probe battery (learn semantics before implementing), **B** fuzz-divergence localization + shrink, **C** the catalog-parity meter (an unhandled operator fails the build — currently 110/110 handled), **D** the full-message fuzz (`code|type|pointer` over random documents — it caught the `CurrentRepetition`/`FirstFilledValue` polarity bug), **E** model fuzzing (random-depth nested models). Plus the two law tiers (accept/reject in `validate/laws`, fires/does-not-fire in `adapter/laws`).
- **Clean-room (the license boundary).** The kernel is EUPL-1.2 / commercial; the whole point of `:interpreter` is a kernel-FREE engine. So we **reimplement behaviour, never delegate or transliterate**: read the kernel source for *understanding* (to learn a surprising special case), then write original Kotlin and lock it with the differential. No `com.mgmtp.*` call from the interpreter; the purity lint enforces the no-link half. ([INTERPRETER-FINDINGS.md](INTERPRETER-FINDINGS.md) IF0.)

## 7. Special topics

- **The Groovy ceiling, lifted.** The kernel's runtime validation generates + loads Groovy at runtime (`GroovyClassLoader.parseClass`) — not native-compilable. Because the interpreter reproduces those semantics with **no codegen**, the runtime verbs run in the native image on the interpreter — the sole runtime eval engine; the opt-in `--kernel` eval engine that hit this ceiling was retired (Set V — [NATIVE-IMAGE-GRAALVM.md](NATIVE-IMAGE-GRAALVM.md) §4).
- **Formal errors are operand-level, branch-scoped.** A malformed / required-empty / duplicate-index field resolves to `Val.Unknown`, so a rule referencing it is suppressed only where Kleene logic says so (`A ∧ [broken]` suppresses; `A ∨ [broken]` with a true `A` still fires) — not a whole-rule skip ([INTERPRETER-FINDINGS.md](INTERPRETER-FINDINGS.md) IF11/IF17).
- **Polarity (VALUE vs OMISSION)** rides alongside truth: a fired error is OMISSION when filling a currently-empty operand could clear it, else VALUE — directional and sign-aware for comparisons (`[Unsigned] >= 0` empty → VALUE; signed → OMISSION). The kernel folds polarity into its truth states; the interpreter carries it as a separate `MsgType` and combines it the same way (And: omission wins; Or: value wins).
- **Partial validation** (`validatePart`) is full validation with a *relevance* gate: a rule runs only if its error-field instance is relevant, and a non-relevant referenced field is `Val.Unknown` — so the existing 3VL produces the kernel's exact suppression with no special-casing ([INTERPRETER-FINDINGS.md](INTERPRETER-FINDINGS.md), KERNEL-FINDINGS "Partial validation").
- **Custom conditions** are a cross-platform SPI (`CustomCondition` over `CustomConditionData`/`FieldPointer`) mirroring the kernel's `IData`-based `ICustomCondition` — identical in shape on the JVM core *and* in TypeScript, so existing impls plug in. A JVM consumer adapts an existing kernel `ICustomCondition` via `:adapter`'s `KernelCustomConditions.fromCore` (it supplies its own `IData`/`IIdentifier`, constructs no kernel `…Impl`).
- **The seed generator** (`gen.SeedGenerator` + `PlacementSink`/`ValueStrategy`) is a kernel-free, model-driven instance builder: it powers the perf harness, the differential fuzz net, and the `dmtool model seed` verb (generate a best-effort model-derived sample candidate) — one generator, three consumers.
