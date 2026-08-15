# Interpreter performance — the optimization design (SUPERSEDED — archived research record)

**Status: SUPERSEDED (2026-06-25), archived for the cited SOTA research + decision trail.** This was the *proposal* for the perf investment — a layered **eval-strategy** plan (Layer 0 CSE/DAG → Layer 1 sealed compiled-node tree → Layer 2 vectorized batch). **Measurement overtook its central thesis:** Layer 1 (sealed nodes), built and A/B'd, measured ~**7% end-to-end** and was reverted — *the eval is not the `validateFull` bottleneck*. The real per-operation levers turned out to be **resolution caching** (the per-row `placeCache`/`valCache`, the `formalErrorAt` memo — the kernel's `ValidationCache`/`IndexFieldCache` analogs), **not** an eval-strategy ladder. The live, current story is in **[`INTERPRETER-PERFORMANCE-RESULTS.md`](INTERPRETER-PERFORMANCE-RESULTS.md)** ("Kernel optimizations from SOURCE" + the results log).

**What's still worth reading here:** §3 the **SOTA research (cited)** — partial-eval/closures, bytecode dispatch, vectorization/CSE, GraalVM-native + Kotlin/JS facts — and §6 the **rejected approaches + why** (Truffle = warmup; threaded dispatch = not expressible in Kotlin; runtime codegen = breaks native/JS). These still inform future decisions (e.g. revisiting vectorization for the rows axis, or the two-phase after per-op alloc is collapsed). **What's obsolete:** §5 the layered design, §8 the build order, §8a the Layer-1 blueprint — the path the measurements actually took is in `INTERPRETER-PERFORMANCE.md`, not here.

---

## 1. Why now — the high-rule regime is real, not synthetic

The investment is justified by a corrected corpus measurement (2026-06-25). Earlier reasoning leaned on the bundled demo workspaces (≤~2 rules/model) and wrongly concluded the high-rule regime was synthetic. The real proxy is **`a12-kernel-mm`** (the kernel team's own metamodels), counting **conditions + computations** ("rules" collectively) and classifying by the `modelType=document` header:

| Corpus | Largest document model | Median | Reaches the crossover? |
|---|---|---|---|
| Demo workspaces (examples) | ~2 rules | ~1 | No — but not real-world |
| `a12-kernel-mm`, source models | **90 rules** (`MM_Field_1`) | 13 | At the tree-walk crossover (~96) |
| `a12-kernel-mm`, composed/expanded | **192 rules** (`MM_Common_v2`) | — | **Yes — the benchmark's high point** |

And these run in a **hot reuse loop**: the metamodels validate *every* document model a user authors — same model, many instances. The clincher: the interpreter's headline native-image job (`dmtool` validating a user's model) **is** evaluating 90–192-rule models in that loop. The regime where the tree-walk loses to the kernel (~96+ rules) is the *main* case for the metamodel workload, not a corner. Closing it has a confirmed consumer.

---

## 2. The constraints that filter every technique

Every candidate is judged against four hard constraints. A technique that violates one is **disqualified** (or at best a JVM-only optional extra, never load-bearing).

| Constraint | What it forbids |
|---|---|
| **Kotlin Multiplatform → GraalVM native-image (AOT, NO runtime JIT)** | runtime code generation; reliance on speculative runtime devirtualization; any technique needing a live JIT (Truffle PE) |
| **Kotlin/JS (V8)** | `Long` on the hot path (object-pair/`BigInt` emulation); megamorphic dispatch sites; unstable object shapes; `eval`/`new Function` |
| **Lowest startup / warmup — NO hiccup, NO prep phase, NO profiling ramp** | profile-guided runtime self-specialization (warmup ramp); blocking AOT compile at load; "first N calls are slow" |
| **Correctness is #1 (kernel differential, bit-for-bit)** | any fast path not proven equal to the tree-walk on the full fuzz corpus before it's trusted |

The pivotal consequence: **the one-time, deterministic "compile-at-model-load" step is allowed** (it's data-structure construction, O(model), not codegen), and that is where all the specialization must happen — because no runtime will fix a bad dispatch shape or boxing for us on native/JS. This is a hand-written **first Futamura projection**: specialize the interpreter to the fixed model (static input) so per-row evaluation of the document (dynamic input) is near-generated-code.

---

## 3. SOTA research synthesis (cited)

Four research strands, each filtered by §2. Full reports are archived in the session; the load-bearing findings:

### 3a. Partial evaluation & closure-compilation (the primary technique)
- **Closure compilation** (Feeley & Lapalme, *Using closures for code generation*, 1987 — [ACM](https://dl.acm.org/doi/10.1016/0096-0551(87)90012-9)): walk the AST **once at load**, emit per node a function capturing its children's compiled functions; per-evaluation there is no node-kind decode and no path re-resolution. Measured ~2× over a tree-walk on a validator workload ([pl-rants](https://pl-rants.net/posts/compile-to-closures/)). This is the first Futamura projection by hand ([Futamura projections](https://en.wikipedia.org/wiki/Partial_evaluation)).
- **The biggest single win is path → slot-index binding**: resolving a field path against the schema is hoisted out of the O(rules×rows) hot loop into load-time captured state ("hash lookups → array references"). Unanimous across reports.
- **Truffle/Graal self-specialization is DISQUALIFIED as a runtime mechanism**: it needs a live JIT to partial-evaluate and has a steep warmup ramp (SOMPE ~16× slower than Java, reaching ~2.4× only after ~1000 iterations — [Marr & Ducasse, OOPSLA 2015](https://stefan-marr.de/papers/oopsla-marr-ducasse-meta-tracing-vs-partial-evaluation/)). What *survives* is its kernel idea — "specialize the node to what you know" — done at **load** from model facts, not from runtime profiles.
- **AST interpreters with eagerly-specialized nodes are on par with or faster than bytecode in pure interpretation, no JIT, no warmup difference** ([Larose, Kaleba, Marr, *AST vs. Bytecode*, OOPSLA 2023](https://stefan-marr.de/2023/10/ast-vs-bytecode-interpreters/)).

### 3b. Bytecode VM vs closures, on AOT and JS specifically
- The historical bytecode-VM win came from **threaded dispatch** (computed-goto/`musttail`), which is **not expressible in Kotlin** (no labels-as-values, no mutual-tail-call; `tailrec` is self-recursion only) — so on KMP a VM is stuck with the *switch* form ([Ertl & Gregg, JILP 2003](https://jilp.org/vol5/v5paper12.pdf); [Nelhage, Python 3.14 tail-call analysis, 2025](https://blog.nelhage.com/post/cpython-tail-call/)).
- The branch-prediction folklore that favored threaded code is **largely obsolete on the modern cores native/JS run on** (ITTAGE predictors — [Rohou, Swamy, Seznec, CGO 2015](https://inria.hal.science/hal-01100647v1)).
- **Direct AOT evidence**: in Rust (AOT, no JIT — the best proxy for GraalVM-native), **closures beat both bytecode forms** (1.4s vs 1.5s, AST-walk 2.1s — [Neil Mitchell, *Writing a Fast Interpreter*, 2020](http://neilmitchell.blogspot.com/2020/04/writing-fast-interpreter.html)).
- The bytecode VM's one decisive advantage — a JIT inlining the dispatch loop — **is exactly what AOT/JS lack**. Its other advantages (compact serializable program, O(1) stack slots) **don't apply** to our one-in-memory-model, hot-reuse, no-serialization workload.

### 3c. Vectorized/columnar evaluation + CSE (the orthogonal axes)
- **CSE / hash-consed expression DAG across rules** + **per-scope aggregate memoization**: when 90–192 rules share field reads or `Sum(Group*/x)` aggregates, compute each once. Attacks the **rules axis** algorithmically; fully portable, no warmup; cheap. DuckDB ships this as `COMMON_SUBEXPRESSIONS`; it's also the Rete *node-sharing* idea (not Rete's incremental state — wrong for one-shot batch) ([CSE](https://en.wikipedia.org/wiki/Common_subexpression_elimination); [DuckDB optimizer](https://www.alibabacloud.com/blog/duckdb-internals---part-4-optimizer-overview_602677)).
- **Columnar SoA + batch-at-a-time evaluation** (MonetDB/X100, DuckDB): evaluate one rule's expression column-wise over a cache-sized batch (~1024–2048 rows), with **vectorized 3VL** (parallel value/valid bitsets, Kleene tables as word-parallel bit ops) and **selection vectors** for short-circuit AND/OR. Attacks the **rows axis** (up to 100k). The wins that **survive no-JIT/JS** are dispatch-amortization + cache locality + branch-freedom (NOT hardware SIMD, which is experimental on native and absent on Kotlin/JS) ([X100, CIDR 2005](https://www.cidrdb.org/cidr2005/papers/P19.pdf); [Kersten et al., VLDB 2018 — vectorization has "no warmup"](https://www.vldb.org/pvldb/vol11/p2209-kersten.pdf); [DuckDB vectors](https://duckdb.org/docs/current/internals/vector.html)).
- **Critical caveat: vectorization LOSES below a row-count threshold** — its fixed per-batch overhead doesn't pay off on tiny groups, and our **median is 13 rows**. A **row-at-a-time fallback** keyed on row count is non-negotiable.

### 3d. Runtime facts (native AOT + Kotlin/JS) — the source-shape rules
- GraalVM native: "**Delivers peak performance immediately, with no warmup**" — confirms the premise. But *no runtime profile* ⇒ inlining is guess-based and speculative devirtualization is gone; the AOT compiler "sees each branch as equally likely." **A megamorphic virtual call site stays an indirect call** ([GraalVM Native Image](https://www.graalvm.org/latest/reference-manual/native-image/); [PGO](https://medium.com/graalvm/profile-guided-optimization-for-native-image-f015f853f9a8)). PGO exists but is **Oracle-edition only** — design as if unavailable.
- Kotlin/Native devirtualizes **sealed/closed hierarchies at IR level** before LLVM — a `when` over a sealed type is the compiler-friendliest dispatch ([Kotlin/Native LLVM passes](https://kotlinlang.org/docs/native-llvm-passes.html)).
- Kotlin/JS V8: one generic `evaluate(node)` virtual call fed every node type is **megamorphic by construction** (>4 shapes → V8 abandons optimization). A `when(opcode: Int)` (jump table) and a sealed-type check are **stable**; raw `Function` lambdas through one applicator site are the risk. **`Long` is a perf cliff** (avoid on hot path); boxing of `Int?`/`List<Int>`/generics is real with no JIT to undo it ([V8 deopts](https://medium.com/@ThinkingLoop/v8-deopts-9-silent-hot-path-killers-df40aabd0209); [Kotlin/JS Long](https://kotlinlang.org/docs/whatsnew2220.html)).
- **No `BigDecimal` in common** → use a **scaled-`Long` fast path** for small values, falling back to a MP bignum (`ionspin/kotlin-multiplatform-bignum`) only when out of range/scale; never route every op through a big-decimal object (allocates, no escape analysis on native/JS). Mind the JS `Double` ±2⁵³ bound when sizing the scaled integer.

---

## 4. The one contradiction — and how it's resolved

The research is unanimous except on **one decision**, where two reports point opposite ways:

- **Closure-compilation report & bytecode report:** *closures* are primary — they beat bytecode on AOT (Mitchell), and a closure tree is "many monomorphic sites" while a bytecode loop is "one megamorphic site" (bad on V8).
- **Runtime-facts report:** *closures-as-operators should be AVOIDED on the hot path* — a lambda through a shared higher-order applicator is "effectively megamorphic and stays an indirect call" under AOT; it prefers a **sealed-AST + exhaustive `when`** or a **closed-set instruction interpreter over primitive arrays**.

**Resolution — they're arguing about three different things under one word "closures," and the disagreement dissolves once separated:**

| Representation | Dispatch site shape | Native AOT | Kotlin/JS | Verdict |
|---|---|---|---|---|
| **Raw `(Ctx)->Val` lambdas** | every `child(ctx)` invoke sees all lambda impls → **megamorphic** | weak (indirect call, no speculation) | weak/risky (poly→megamorphic) | **Avoid** — this is the form the runtime-facts report rightly rejects, and the closure report itself flags as a trap |
| **Sealed compiled-node hierarchy** (`sealed CompiledCond { fun eval(ctx): Tri }`, one subtype/operator, children captured) | `child.eval(ctx)` over a **closed** set | **strong** — Kotlin/Native devirtualizes closed hierarchies at IR level | good — stable-shape type check | **✅ primary** — this is what *all three* reports actually recommend in their impl notes |
| **Flat bytecode int-switch over primitive columns** | one `when(opcode: Int)` jump table (NOT megamorphic — it's an int switch, not a virtual call) | strong | strong | **✅ the substrate for the vectorized batch path** (§5 Layer 2), not the primary scalar form |

So the "closures vs bytecode" fight was a false binary caused by conflating *raw lambdas* (bad on AOT/JS) with the *sealed-node tree* (good). The decision: **the primary scalar representation is a sealed compiled-node tree** (devirtualizable, stable-shape, the form Mitchell's closures and Report-4's sealed-`when` both reduce to), and **bytecode-over-columns is reserved for the vectorized batch path** where its flat int-switch loop is the right tool. We will still **measure** the sealed-node tree against a raw-lambda variant on the native image and JS to confirm the AOT/JS devirtualization holds in practice — the contradiction is resolved on paper, but correctness-#1 discipline says prove it on the real targets.

---

## 5. The proposed layered design

A single **compile-at-model-load pipeline** feeding **two execution strategies over one shared runtime-semantics layer**. This honors the standing "generator-readiness" requirement (CLAUDE.md / [`INTERPRETER-FINDINGS.md`](INTERPRETER-FINDINGS.md) IF10): data-access → runtime operator semantics → strategy; the two strategies below are strategies #1 and #2, and a future generator would be strategy #3 — all consuming the same operator semantics.

```
                          COMPILE AT MODEL-LOAD  (once per model, O(model), no codegen)
  rule/computation ASTs ──▶ canonicalize (fold consts, normalize commutative order)
                            └▶ hash-cons into a SHARED EXPRESSION DAG        ← Layer 0 (CSE)
                               └▶ resolve every path → slot accessor          ← Layer 0 (indexing, exists)
                                  └▶ lower DAG → sealed CompiledNode tree      ← Layer 1
                                     (operand types monomorphized, literals &
                                      slot indices captured, invariant guards hoisted)

                          EVALUATE  (hot loop, peak from call #1, no warmup)
   per (document instance):
     aggregates memoized per scope (Sum(Group*/x) once)                       ← Layer 0
     for each rule:
        rows ≥ threshold ?  ──▶ STRATEGY 2: vectorized batch over columns      ← Layer 2
                                (SoA, vectorized 3VL bitsets, selection vectors)
        rows <  threshold ?  ─▶ STRATEGY 1: sealed-node walk, row at a time    ← Layer 1
                                                                               
   shared by both strategies:  RUNTIME OPERATOR SEMANTICS                      (3VL, compare,
   DateMath, aggregates, polarity, value carrier: unboxed Val / scaled-Long)   generator-ready)
```

**Layer 0 — Foundation (cheapest, highest-leverage, fully portable, do first).**
- *O(1) data access* — already built (the mutation-invalidated `Index`; [`INTERPRETER-PERFORMANCE.md`](INTERPRETER-PERFORMANCE.md) §1). Prerequisite for everything.
- *CSE / shared expression DAG* — hash-cons all rules' ASTs so shared reads/subexpressions/aggregates collapse to one node. Attacks the **rules axis** (the 90–192-rule regime) algorithmically. New.
- *Per-scope aggregate memoization* — partly present (the compute overlay + `starCells`); generalize to a keyed memo across rules.

**Layer 1 — Compile to a sealed compiled-node tree (the primary scalar engine; refines ladder rung A+B+the surviving half of C).**
- First Futamura projection by hand: lower the DAG to a `sealed CompiledNode` tree with abstract `eval(ctx)`; capture slot accessors, monomorphize the operand op by field type, **unbox** the numeric path, hoist invariant guards. Built lazily per rule (same instant-start profile as today's lazy parse).
- Value carrier: unboxed `Val`/3VL enum returned directly; **scaled-`Long` decimal fast path** with bignum fallback. No boxing through generics on the hot path.
- This is the production form of the measured spike (the rung-A `RuleCompiler` showed ~2.5×); the spike was raw lambdas — the production form is sealed nodes (§4).

**Layer 2 — Vectorized batch path for large repeatable groups (the rows axis; ladder rung E, with rung D's flat dispatch as its substrate).**
- Columnar SoA document layout (primitive arrays + validity bitsets); evaluate a rule's DAG column-wise over a cache-sized batch; vectorized 3VL (parallel value/valid bitsets); selection vectors for short-circuit AND/OR.
- **Row-at-a-time fallback below a tuned row-count threshold** (median 13 rows ⇒ most scopes use Layer 1). Mandatory.

**Layer 3 — Optional, JVM-only, never load-bearing (ladder rung G + SIMD + PGO).**
- True runtime JIT (ASM/`MethodHandle`), hardware SIMD, GraalVM PGO (Oracle edition). All explicitly **not** depended on; the AOT/JS hot path must be fast under "all branches equally likely." Reached only if a measured workload justifies a JVM-distribution-only extra.

---

## 6. Rejected, with reasons (so we don't relitigate)

| Technique | Why rejected for the portable engine |
|---|---|
| Truffle/Graal runtime self-specialization + PE | warmup ramp (~1000 iters) + needs a live JIT — violates no-warmup *and* native/JS |
| Raw `(Ctx)->Val` lambda closures as the representation | megamorphic invoke site on AOT/JS — use the sealed-node tree instead (§4) |
| Bytecode VM as the *primary* scalar engine | not faster than sealed-node closures on AOT (Mitchell); threaded dispatch not expressible in KMP; serialization advantage doesn't apply. (Bytecode-over-columns IS used in Layer 2.) |
| Threaded / computed-goto / `musttail` dispatch | not expressible in Kotlin (no labels-as-values, no mutual tail calls) |
| Runtime codegen (bytecode-spinning, JS `eval`, MethodHandle on the shared path) | banned on native (closed-world); off the portable path |
| Hardware SIMD as a foundation | absent on Kotlin/JS, experimental + flagged on native — opportunistic JVM bonus only |
| Eager compile-everything at construction | violates the cold-start goal — compile lazily, per rule, on first use |

---

## 7. Measurement plan — measure against the kernel at every step

Per the owner's direction ("always measure against the kernel to guide you") and the existing against-kernel gate ([`INTERPRETER-PERFORMANCE.md`](INTERPRETER-PERFORMANCE.md) non-negotiable #2). The harness already exists (`PerfCatalog`, `PerfGateTest`, `KernelEdgeTest`, the differential); it gets extended, not replaced:

- **Real-scale matrix.** Rule counts **{13, 48, 90, 192}** (the mm-derived reality) × rows **{1, 13, 100, 1k, 10k, 100k}**, on the **real `Document`** (never the `Map`-backed shortcut — non-negotiable #1).
- **Three baselines per cell** — interp × kernel-Groovy × kernel-Java (the Groovy-tax-free algorithmic comparison; [`INTERPRETER-PERFORMANCE.md`](INTERPRETER-PERFORMANCE.md) "kernel baseline"). **Never count codegen** (non-negotiable #2).
- **Per-layer delta + correctness gate.** After each layer: assert (a) **interp == kernel** bit-for-bit across the full fuzz corpus (correctness #1 — a fast path is not trusted until the differential is green), and (b) the against-kernel ratio improved without a complexity regression (`PerfGateTest` sub-quadratic shape checks).
- **Target cadence (owner direction, 2026-06-25).** **JVM first** — the kernel differential + perf harness run in-process there, so it's the fast inner loop. **JS often** — re-check on Kotlin/JS *frequently*, not just at the end (the `:interpreter` `jsTest` scale/multiplatform tests run on Node and are cheap), so a JS-only regression (boxing, `Long` cliff, shape churn) is caught early, not at the finish. **Native at a few important checkpoints only** — it is the CLI tool's target and expensive to build, so validate the §4 AOT devirtualization/no-boxing claims at layer boundaries, not every iteration.
- **Profile mode** to locate the hotspot before building the next layer (measure to guide, don't speculate).

---

## 8. Build order — pivoted 2026-06-25 by measurement: the eval is NOT the bottleneck

The plan was Layer 1 (sealed-node eval) first, as the "measured ~2.5× win." **An A/B measurement falsified that for `validateFull`.** Layer 1 was built (faithful sealed-node compiler for the `manyRules` subset, full `:adapter` differential green) and A/B'd against the tree-walk in one JVM run: **~7% end-to-end, even in the 0-fire regime** where eval should dominate ([`INTERPRETER-PERFORMANCE-RESULTS.md`](INTERPRETER-PERFORMANCE-RESULTS.md) results log, 2026-06-25). The spike's 2.5× was the eval *in isolation*; at the `validateFull` level the firedType eval is only ~7% of the cost. **Layer 1 was reverted** — a 7% win does not justify a new compiler subsystem (the project rule: minimum code, nothing speculative).

**The dominant `validateFull` cost is the SHARED machinery**, untouched by any eval strategy: `iterationContexts` construction, the formal/mandatory/uniqueness pre-passes, the **per-row suppressor scan** (`firstSuppressor` → `formalErrorAt` per ref, per non-firing row), the `EvalContext` Document lookups, and message rendering (per firing). So the layered-eval plan (Layer 1 sealed nodes → Layer 2 vectorized) is **deprioritized**: it optimizes ~7% of the cost.

**New order:**

1. **Profile `validateFull`** (the design's "decision point: read the matrix" — and it just spoke). Attribute the per-row/per-rule cost across: iterationContexts · formal/mandatory/uniqueness pre-passes · the suppressor scan · Document lookups · message rendering. Use the low-firing 192-rule/1000×5 scenario (479 ms, 0 fires) as the probe. **Target the dominant shared cost**, not the eval.
2. **Optimize the measured hotspot**, red/green + against-kernel gated. (Hypothesis from the A/B: the per-row suppressor scan and `iterationContexts` lead — but profile, don't assume.)
3. **Re-decide eval strategies (Layers 1/2) only if** a *profiled* workload shows eval actually dominates (a different shape than `manyRules` — e.g. deeply-nested-aggregate-heavy with low firing). The reverted Layer-1 approach (§8a) is the blueprint to resurrect *then*, not now.

Each step: against-kernel differential first (correctness), then the perf gate, then graduate the "applied + rationale" detail into [`INTERPRETER-PERFORMANCE.md`](INTERPRETER-PERFORMANCE.md)'s optimization catalog.

### 8a. The reverted Layer 1 approach (blueprint, if eval is ever shown to dominate)

Kept as a record — the seam and strategy were sound; the *lever* was wrong (eval is ~7%). To resurrect:

- **The seam.** `DmInterpreter.rowOutcomes` allocates an `Interpreter(ctx, …, errorField) { invalid }` **per row** and calls `firedType(ast)`. The compiled path builds, **once per rule (lazy)**, a `CompiledCond`; per row it calls `compiled.firedType(rowEval)` bundling the per-row `(ctx, errorField, invalid)`. Engine config captured at compile time.
- **Compile-or-fallback, whole-rule.** `compileOrNull(cond)` returns `null` if any node is off-subset; that rule keeps the exact `Interpreter` path — purely additive, byte-for-byte unchanged, differential trivially green for uncompiled rules (which is exactly why the A/B, not the differential, is the gate that exposed the no-win).
- **Subset that compiled:** `And`/`Or`/`Not`, ordered numeric/text `Cmp`, `FieldFilled`, `Sum(group* )`; operands `Field`/`NumLit`/`Sum`. Semantics mirrored `Interpreter` exactly (same `Kleene`, scale-19 compare, directional `fill`/`leafType`, hoisted `canFireEmpty`).
- **Lesson recorded:** isolate a candidate optimization's end-to-end effect with an **in-run A/B** (toggle the path, same JVM) *before* investing — a differential that passes when zero rules take the new path, and a cross-build timing within noise, will both hide a no-op. The A/B is the only honest gate for "did it help."
