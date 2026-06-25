# Interpreter performance — method, benchmark catalog, regression gate

The `:interpreter` must be **fast on the hard cases**, and that is **measured and gated**, not hoped for. This doc is the durable method; the decision behind it is `CLAUDE.md` "Interpreter … Performance" (decided 2026-06-23). Measured baselines live in a dated results log (below), not here.

## The decision (three non-negotiables)

1. **REAL documents only — benchmarking a shortcut is cheating.** Measure the production evaluation path: the general `Document` (interpreter) and the kernel's `IDocument`, both built from **real DM-JSON models**. The spike's `Map<String,String>`-backed `RepDoc` (O(1) lookups, one-pass `star`) must **never** be the benchmarked object — it sidesteps the exact machinery (parallel index, common-prefix resolution, star aggregation) that is hard to make fast, so its numbers are a fiction. If a benchmark doesn't exercise real group resolution, it isn't a benchmark.
2. **Measure AGAINST the kernel, as a regression gate.** The perf benchmark is the **twin of the correctness differential** and lives in `:adapter` (the only module with the kernel). For each scenario: build one instance, run BOTH `kernel.validateFull`/`compute` and `DmInterpreter.validateFull`/`compute` over it, record both wall-clocks + the ratio, and assert (a) **correctness** (interp == kernel) and (b) a **time/ratio bound** that trips on an O(N²)-class regression. The interpreter should be within a sane constant factor of the kernel — ideally faster (no Groovy, no codegen).
   - **⭐ NEVER count Groovy codegen — in ANY benchmark, including startup (standing rule, re-affirmed 2026-06-24).** Kernel codegen is **always ahead of use in real-world projects**: a real deployment generates + compiles the validator at **model-load / deploy time** (cached in `IModelCodeCache`), so the kernel **never pays codegen per request** — its real cost is steady-state validation against already-generated code. Timing codegen therefore measures a cost production never incurs — a fiction that unfairly flatters any "interpreter is faster / starts faster" claim. **This is absolute and has no exception:**
     - **Comparison benchmarks** prime it out — the kernel generates on its FIRST `validateFull` (cached for the engine's life), and every timed region is preceded by a correctness `run()` + warmup, so the codegen call is always discarded. (This is also why the kernel's per-call time only JIT-warms downward over the first ~dozen calls — the Groovy validator warming, not recodegen.)
     - **Startup measurements too.** The interpreter's "fast startup" (goal #1) is measured **on its own terms** — its engine build + first-call index/parse cost in absolute terms — **NOT** as a race against the kernel's codegen. Do not put a "kernel cold start incl. codegen" number next to it; that's the forbidden comparison. The interpreter's no-codegen advantage is a **capability** point (it matters for native-image — the kernel's Groovy codegen can't go native at all — and for one-shot CLI / JS use where there's no ahead-of-time deploy step), stated as prose, never as a benchmark timing.
     - **Codegen+compile is ONCE PER DEPLOYMENT/RELEASE — it is not a per-call, setup, OR startup cost, so it belongs in NO column of ANY comparison.** It runs at model-load / deploy time (cached in `IModelCodeCache`) and is paid **zero** times per request. Consequence: in any steady-state / hot-reuse comparison BOTH engines are already *past* setup — the kernel's code generated at deploy, the interpreter's first-parse amortized — so the only thing left to compare is **per-operation evaluation cost** (tree-walk vs already-generated code). Two traps that break this even while *saying* "paid once": **(a)** listing codegen as the kernel's *per-call setup* in a comparison table — it is not a per-call cost at all; **(b)** framing a **cold / one-shot** run as "the kernel must codegen first" — in production the model is already deployed, so the kernel does **not** codegen on the call. The interpreter's *not needing* codegen is a **capability** (it runs where Groovy codegen can't — native image, JS — and where there is no deploy step), **never** a "starts faster / faster per call than the kernel" timing claim.
3. **Target the non-obvious group machinery, at scale.** Not flat single-row rules — the cases that bite: **deeply nested repeatable groups**, **parallel iteration** / cross-level references, **starred aggregates** over large lists, and **many rows (up to 100 000)**. *Invest heavily in all things groups.*
4. **Run on the PURE engine, decoupled from the CLI — it must be CHEAP to run, very often.** Both the correctness differentials and the perf harness call the **`DmInterpreter` + `Document` API directly** (kernel via `RuntimeLaws`), NOT through `dmtool`/`InterpreterEngine`/`InterpreterModelLoader`. No process spawn, no `installDist`, no JSON envelope, no native build — just in-process JUnit (and `jsTest`). "Real document" means the real `Document` *data structure* with real nested-group shape, built **programmatically in-test**, not loaded through the CLI. The CLI's loader is a separate, thinner concern with its own small test; the engine's semantics + performance are measured at the engine, so the loop stays fast enough to run on every change.

## Why this matters (the cheating we caught)

The encouraging spike timing (`InterpreterMultiplatformTest.scalesToManyRepetitionRows`, ~30 ms / 20k rows) runs on `RepDoc`, whose field lookup is a `HashMap.get` and whose `star` is one pass — **O(N) overall**. The **production** path is the general `Document`, which stores placements in a `List` and does a linear `placements.firstOrNull{…}` scan on **every** `field`/`filled`/`displayValue`/`groupFilled` (and `star` is O(rows×P)) → **O(N²)** per-row evaluation. So the green spike number says nothing about the real path. That gap is exactly what this method closes: benchmark the real `Document`, against the kernel, at scale.

## The kernel baseline: Groovy vs Java (the fairness question, 2026-06-24)

The kernel's dynamic `validateFull` runs **Groovy-generated, Groovy-compiled** validators (the codegen architecture is in [`KERNEL-INTEGRATION-NOTES.md`](KERNEL-INTEGRATION-NOTES.md) "Code generation targets"). Compiled Groovy executes as JVM bytecode, but carries a **per-operation dynamic-dispatch / metaclass tax** over equivalent Java. The kernel team themselves treat this as real — they disable Groovy's Parrot parser (`groovy.antlr4=false`) specifically because it *"slows down the groovy compilation"*. So a naive "interp vs kernel" ratio conflates two things: the interpreter's algorithm **and** the fact that the kernel pays a Groovy tax the interpreter doesn't.

Two consequences, and what we do about each:

- **Scaling is tax-immune — trust it.** A constant factor (Groovy-vs-Java per-op overhead) does not bend the complexity curve. So the linearity / O(N²)-detection analysis (the "large docs" story) is unaffected by which language the kernel ran; it is the robust deliverable.
- **The absolute ratio is tax-inflated — so we also measure a Java baseline.** To get a *Groovy-tax-free* absolute comparison, we run the kernel's **own generated validator compiled as Java**, via the supported **static-model runtime path** (generate Java source through `generateValidationCode(…, JAVA)`, compile in-memory with `javax.tools.JavaCompiler`, run it through `DocumentStaticRtService` — same `validateFull` scaffolding, Java underneath). Each scenario then reports **interp × kernel-Groovy × kernel-Java**, and the honest "how much faster is the interpreter's *algorithm*" question is read against **kernel-Java**, not kernel-Groovy. This is the kernel's own logic, just stripped of the Groovy execution overhead — so it isolates the algorithmic comparison from the language tax.

**Why this matters beyond fairness — the generator signal.** Our tree-walker being *competitive* with compiled Groovy is already encouraging; the kernel-Java number tells us how much of that edge is real algorithm vs Groovy tax. It also informs the **future generator** (the "generator-ready" layering): if a JVM-bytecode/Java target is what beats Groovy, that's the perf opportunity a generator would chase.

**The JS generalization (forward-looking).** TypeScript/JS is the kernel's *other* documented codegen target, so this exact harness — *generate code in language X → compile/load → run through the rt path → diff against the interpreter* — generalizes to a **kernel-JS vs interpreter-JS** comparison later (the interpreter's other reason-to-exist). The baseline engine is therefore built **language-parameterized** (Java now; JS-capable later), not hardwired.

## The interpreter's two goals: fast startup + on-par at all rule counts (the tradeoff, 2026-06-24)

The interpreter is steered by **two CO-EQUAL goals that pull against each other** (owner direction — neither is secondary):

1. **Low cold / startup cost** — the time from *(model, document)* to the **first** answer must be small: minimal upfront work, no codegen, a fast first `validateFull`. This is the realistic latency for a one-shot CLI invocation, a native-image run, a JS page, or a freshly-built engine — **not** a warm steady state.
2. **On-par with the kernel at *all* rule counts** — the warm steady state, not just the few-rule case.

These are **co-equal and gated separately**, because they measure opposite regimes and a win in one can hide a loss in the other — the index-build O(N²) (#7) was invisible to the *warm* gate for exactly this reason. So: the warm path is gated by `PerfGateTest` (against-kernel ratios + the sub-quadratic shape checks), and the **cold path is gated by `PerfGateTest.coldStartStaysSubQuadratic`** — a fresh engine + fresh document + ONE `validateFull`, no warmup, asserting sub-quadratic growth so a reintroduced cold O(N²) trips even though the warm gate can't see it. Measured cold growth is **~4× per 4× rows (linear)**.

The tension: goal 2 wants the per-call rule work eliminated (cache parsed ASTs, precompute scopes), but doing that *eagerly* at construction is exactly the cold cost goal 1 forbids. The resolution is **lazy memoization** — do the work once, **on first use**, then reuse (so the *constructor* is trivial and the first call is O(N), never O(N²)):

- **Prepare-once ASTs** (`DmInterpreter`): each rule's condition / computation's expression is parsed **lazily, exactly once** (a `lazy {}` per rule — SYNCHRONIZED, so the engine is thread-safe to reuse), then reused across every call and every row. The constructor only builds the wrappers (no parsing) → startup stays cheap, and a single-rule `testRule` forces only the one rule it touches. **This is the direct analog of the kernel's compile-once-at-model-load** — but lazy. *Caveat learned by measuring:* parsing turned out **not** to be the high-rule-count bottleneck (it didn't move the crossover), so this change is for startup + hygiene (it also removed a redundant double-parse), not the headline win.
- **Per-group row-context memo** (`Document`): `contextsFor`/`attachByDepth` did an **O(valued) attach-depth scan on every rule** (and every nested row). Since many rules share a handful of iteration scopes, and the document is immutable during a validation, this is memoized **per group inside the mutation-invalidated `Index`** — computed once per distinct scope, reused across all rules. **This *was* the lever**: ~24% faster across all rule counts, crossover pushed out ~2× (see the log). This mirrors the kernel computing the row structure once and reusing it across rules.

**Measured (2026-06-24, the log below):** the interpreter's startup is **small in absolute terms** — engine build ~1.5 ms (lazy — just the AST wrappers), first `validateFull` ~57 ms on a 48-rule model (one-time lazy index build + first parse), then steady-state. And a single document stays **linear to 300 000 rows** (~2.5 µs/row). The harness for these two goals is `PerfScalingTest` (opt-in `-Dperf.scaling=true`).

**Cold-startup spectrum (2026-06-25, `coldStartupSpectrum`) — acceptable across the board.** The TRUE worst case (fresh engine + fresh document + exactly ONE `validateFull`, no JIT warmup): **37 ms** (6 rules) → **62 ms** (48) → **126 ms** (192) on a ~500-row document, and **85 ms** (10 000 rows) → **495 ms** (100 000 rows) at one rule. Sub-second everywhere, scaling ~linearly on both axes (no cold O(N²) — `coldStartStaysSubQuadratic` gates it), with **no codegen step at all**. Both axes (rule count, document size) stay comfortably interactive for one-shot CLI / native-image / JS use. **We do NOT compare this to the kernel's cold codegen** (non-negotiable #2 — the kernel pre-generates ahead of use; counting it would be the forbidden measurement). The interpreter's *not needing* codegen at all is a **capability** advantage — decisive for the native image (Groovy codegen can't go native) and one-shot CLI / JS use — not a startup-timing race. The residual at *extreme* rule counts is addressed next.

## Where the kernel wins (the crossover — the kernel team's pride is justified, 2026-06-24)

The kernel's documented architecture is **compile-once codegen + per-run caches** (`IndexFieldCache`, `ValidationCache`, `CalculationCache`, `GroupFillCache`, `RepetitionNotUniqueErrorCache` — `kernel-core-runtime/_30_8/internal/core/`), plus short-circuit optimizations (BA doc: skip comparison rules when the root group is empty; cancel quantifiers early). So its edge is the **many-rules** regime: generated straight-line code beats a tree-walker's per-node dispatch + `Val` allocation, and that per-(rule,row) overhead accumulates with rule count.

This is **measured, not assumed** — the rule-count sweep (`KernelEdgeTest`, opt-in `-Dperf.edge=true`) finds the crossover where interp/kernel passes 1.0. The original crossover was **~96 rules vs Groovy**; the per-operation levers below (resolution caching + reduced allocation, NOT an eval-strategy change — see the next section) have since pushed the interpreter past it across the whole sweep — after the `repsOf` memo (results log) interp/kernel-**Java** is **~0.3–0.5 through 192 rules in both firing regimes** (the interpreter is the faster one).

**A codegen ceiling exists, but only for pathologically CONCENTRATED rules (2026-06-25, corrected).** The kernel's codegen splits its validator into methods **by error-field count** (10 fields/method Groovy, 50 Java), each holding all rules of its fields. So when **many rules pile onto few error fields**, they collapse into one method that can exceed the JVM 64 KB bytecode limit (`MethodTooLargeException`) — `manyRules` (rules on ~3 fields) trips this at ~512–768. But that is a *benchmark artifact*: with rules **distributed** across many fields (the realistic shape), the kernel fans out across many small methods and **compiles 2000+ rules fine** (results log, `manyRulesDistributed`). So this is not a general per-DM ceiling and not a reason to avoid a generator on its own; it's a reminder that **a generator's method-size management is real work** the interpreter (no codegen) simply never does. The interpreter scales **linearly** to 2000+ rules at a flat ~0.54 ms/rule regardless of distribution.

### Eval-strategy was NOT the lever — the per-operation cost was (measured, 2026-06-25)

An early hypothesis was an **eval-strategy ladder** (compile-to-closures → partial-eval → bytecode VM → vectorize → cross-rule CSE → runtime JIT), the Futamura idea of specializing the interpreter to the model. A closure spike measured ~2.5× **on the firing-eval in isolation**, which looked decisive. **It wasn't:** productionized as a sealed compiled-node engine ("Layer 1"), it measured ~**7% end-to-end** and was reverted — `firedType` is only ~7% of `validateFull`, so an eval-strategy change can't move the dominant cost. The real per-operation levers were **resolution caching** (per-row `placeCache`/`valCache`, the `formalErrorAt` memo — the kernel's `ValidationCache`/`IndexFieldCache` analogs) and reduced allocation, which **beat kernel-Java at 192 high-firing** — see "Kernel optimizations from SOURCE" below + the results log. The full SOTA research that informed the ladder (closures-vs-bytecode, Truffle warmup, vectorization, AOT/JS facts) and the rejected approaches are archived in [`archived/INTERPRETER-PERFORMANCE-DESIGN.md`](archived/INTERPRETER-PERFORMANCE-DESIGN.md). The two-phase short-circuit (the kernel's guard) is the one ladder idea still open — re-measure it once per-op allocation is collapsed (it's blocked on cheap per-op eval, not on a strategy change).

### The kernel's per-run caches → where the interpreter is naive (the optimization map, 2026-06-24)

The crossover is the *constant-factor* gap. An **O(N²) cliff** (the kernel has a cache, the interpreter does a per-call scan) is the *steeper* danger — but **do not dismiss a constant factor as "not a cliff"**: a per-(rule,row) redundancy is a **~rules× constant** that, at 192 rules, dominates (the `ValidationCache` "repeated field reads" row below was mis-triaged exactly this way — parked as "not a cliff," it turned out the largest remaining gap). The discipline for **both**: check the interpreter's analog and **measure at real rule counts**, not assume. Read the kernel source **for the mechanism, not the expression** (clean-room): the *idea* — index by key, memoize group-fill, **resolve a field once per row** — is reproduced in original Kotlin.

| kernel cache | pattern it rescues | naive cost | interpreter's analog | verdict |
|---|---|---|---|---|
| **RepetitionNotUniqueErrorCache** | index/field uniqueness over a repetition list | O(N²) pairwise | `duplicateIndexCells` uses **hash grouping** (`groupingBy`/`eachCount`) | already O(N) ✓ |
| **IndexFieldCache** (semantic index) | semantic-index `[F For v]` resolved per row | O(rows²) | `starRows` did an **O(all-rows) `.filter` per call** → **O(rows²)** — **measured quadratic** (growth ×3.5/doubling, the interp lead collapsing 0.17→0.66) | **was the cliff → FIXED**: `Document` now indexes rows by prefix (the clean-room `IndexFieldCache` analog); linear again, 98 ms→6.8 ms @4k rows. Guarded by `PerfGateTest.semanticIndexStaysSubQuadratic` |
| **IndexFieldCache** (parallel iteration) | two NON-nested repeatable groups joined by a shared index field — an outer join (the kernel's steepest O(N²) avoidance) | O(A×B) nested loops without the index | `Document.parallelContexts` builds a value→row map per group (O(1) lookups via the row-prefix index) and iterates the union of index values — the clean-room `IndexFieldCache` analog; incl. the invalid-index 3VL corner | **was silently wrong → FIXED, exact + linear**: the index-keyed outer join is implemented (matched / synthetic `-5` / polarity / empty-as-0 / invalid-index 3VL), O(rows) not O(rows²). `ParallelIterationDiffTest` + `SiblingGroupFuzzDiffTest` + `PerfGateTest.parallelIteration*` |
| **GroupFillCache** | `GroupFilled(G)` / requiredness over a big doc | O(rows × valued) | both branches indexed: repeatable-group fill is O(1) (row-prefix index); the non-repeatable branch is O(1) via a per-`(groupPath, group)` attach-depth index | **FIXED** (#6 + #8) |
| **CalculationCache** | the cascade — a computed field referencing ANOTHER computed field, incl. through a star aggregate | wrong value (stale operand) without forward results | `compute()` orders computations by dependency + reads prior results via a **side-effect-free overlay**; the overlay now also applies to a **starred** read (`Sum(Group* then Computed)`) via `EvalContext.starCells`, so an aggregate-over-computed sums the computed values | **was silently wrong → FIXED**: direct cascade (verified 11 not 1) AND cascade-through-star (Total sums computed Doubled). `ComputeCascadeDiffTest`, `CascadeThroughStarDiffTest` |
| **ValidationCache** | repeated field reads / format reconversion | **~rules× constant** (every rule re-resolves the same field on the same row) | a per-row-context `placeCache` resolves each `(path, reps)` ONCE, reused across `field`/`filled`/`displayValue` **and across all rules** (the kernel resolves once per row); `formalErrorAt` likewise memoized per `(path, reps)` | **was MIS-TRIAGED as "constant-factor, not a cliff" → FIXED.** The earlier verdict ("the row-context memos cover the repeated-work axis") was wrong twice: the #3 row-context memo caches *contexts*, NOT field resolution; and the constant factor is ~rules× (large at 192 rules — `formalErrorAt` was 56% of `validateFull`; the `placeCache` moved ratioJava 1.25→0.84). The cliff-hunting bias parked a large constant-factor win. `placeCache` + the `formalErrorAt` memo (results log) |
| **USBIndexCache** | HDV/USB code translation | O(lookups) | the interpreter does not model USB/HDV | out of scope |

Plus non-cache kernel optimizations worth noting as hints: **iterator pruning** (skip empty repetitions above a threshold) and **end-index pruning** (iterate to the max *filled* row, not the declared max) — the interpreter sidesteps both because it only materializes *instantiated* rows (no empty-row iteration), so sparsity isn't its problem.

**The honest lesson (the owner's skepticism, confirmed — and it kept paying out):** it is **not** easy to beat the kernel. A second investigation pass (the aggregation / parallel-iteration / calculation-cascade machinery, beyond the six named caches) found **two more silent bugs** the differential fuzz net hadn't generated:
- **Parallel iteration** (two non-nested repeatable groups, the kernel's index-keyed outer join — its *steepest* O(N²) avoidance): the interpreter was **silently wrong** (under-fired, no unsupported report). It went *loud* first (declined), then was **implemented exactly for every kernel-valid shape** — the clean-room index-keyed outer join (`Document.parallelContexts`): sibling / wrapped / asymmetric-depth groups, a repeatable **frame** above one joined group (`/Plan/X/A ⋈ B`, iterated per frame row), matched / synthetic `-5` / polarity / empty-as-0, AND the invalid-index 3VL corner (a duplicate/empty index makes a group's unmatched references read UNKNOWN), at O(rows). Reverse-engineered from the source (`ValidationCache.getIterationValuesForGroups`, `IndexFieldCache`, `IIdentifier.UNKNOWN = -5`) + targeted probes. The shapes still declined-loud were all confirmed by probe to be **kernel-invalid models** (`MVK_NO_WILDCARD` for a multi-frame, `MVK_MULTIPLE_INDEX_FIELDS` for an indexed frame, `MVK_NEG_CONDITION_IN_ITERATION` for a negative-over-frame) — so a valid model is never declined.
- **The computation cascade** (a computed field referencing another): the interpreter was **silently wrong** (read the stale operand → wrong value). Now **fixed** — `compute()` orders by dependency and reads prior results via a side-effect-free overlay, matching the kernel.

So across the passes we **mirrored every remaining kernel-cache analog clean-room** (semantic-index #4, repeatable group-fill #6, cold-path build #7, non-repeatable group-fill #8, the cascade, the parallel-iteration outer join) — leaving **no known O(N²) cliff on any common path** and **no known silent disagreement**. Parallel iteration is now exact for every shape the kernel will *accept* (the declined shapes are kernel-invalid models). The lesson: a tree-walker isn't inherently fast OR inherently correct at the edges — you earn parity by indexing/ordering exactly where the kernel does, one verified case at a time, reverse-engineering the intricate corners from the source (and *probing which shapes are even valid*, so a "gap" isn't really an unreachable one), and **failing loud on what you haven't built yet**.

## How the kernel's GENERATED code evaluates a rule — and what the interpreter can mimic (2026-06-25)

A deep read of the kernel's **generated Java** validator (dumped for the `manyRules` shapes + an `Or` and a 3-way `And`, via `generateValidationCode(…, JAVA)`) shows *exactly* where its per-operation edge comes from — and confirms the owner's intuition that it's **mimickable with tree-walk techniques, no codegen needed.** (Mechanism described in our own words + stylized pseudocode; the verbatim generated source is not reproduced — license-safe rule.)

**The generated shape — one rule = one method, a two-phase body.** Each rule compiles to a method that iterates its scope and, per row, does:

```
for (row in iter) {                                  // the rule's iteration scope (cached repetitions)
  if ( fieldsGiven(Qty).isKnownAndTrue()             // PHASE 1: short-circuit BOOLEAN guard —
       && compare(GT, num(Qty), num(Amount))         //   Java && / || ; reads only .isKnownAndTrue();
            .isKnownAndTrue() ) {                     //   later operands skipped once firing is decided
    var tb0 = fieldsGiven(Qty);                       // PHASE 2 (only inside the if = only when it FIRES):
    var tb1 = compare(GT, num(Qty), num(Amount));     //   RE-evaluate operands, build the typed results,
    var v   = tb0.combineUND(tb1);                    //   combine (UND for And, ODER for Or) to get polarity
    addMessage(errorField, row, code, v.fehlerType());
  }
}
```

**The two structural differences from our tree-walk** (both confirmed across `And`, `Or`, `Cmp`, `FieldFilled`, `Sum` — the pattern is uniform; only the combine differs per operator: `combineUND` ↔ our `And` type rule, `combineODER` ↔ our `orType`):

| | kernel generated code | interpreter (`Interpreter.firedType` → `evalTyped`) |
|---|---|---|
| **firing decision** | **short-circuits** — `a.isKnownAndTrue() && b…` / `|| ` skips later operands (and *their* whole evaluation) once the result is forced | **no short-circuit** — `Kleene.and(evalCond(l), evalCond(r))` evaluates **both** sides, always |
| **polarity (VALUE/OMISSION)** | computed **only when the rule fires** (the `combineUND/ODER → fehlerType()` is inside the `if`) | computed **every row** — `evalTyped` calls `leafType`/`fill` (→ `ctx.filled`/`ctx.signed`) for every operand whether or not it fires |
| **index/reps** | primitive `long[]` (no boxing) | `List<Int>` (boxed `Integer`s) — the research-flagged Kotlin/JS cliff |
| **dispatch** | generated straight-line calls (no per-node `when`) | AST re-walk per node — **NOT the lever** (a JFR profile, #64, put the whole eval at ~3–4%; the compiled-node engine that targets this stays unbuilt, twice-rejected) |

**Why this matters for the profiler hotspot.** The profiler put **eval-proper at ~181 ms** (low-firing, the residual after the `formalErrorAt` memo): `ctx.field` + `Val` allocation + the walk **+ the polarity (`leafType`/`fill`)**. The kernel pays the polarity **only on the rare firing rows** and **skips whole operands** via short-circuit; the interpreter *used to* pay polarity on **every** row. In the realistic **low-firing** regime (valid documents — most rules pass) this was pure waste: e.g. `FieldFilled(Qty) And [Qty] > 50` with a small `Qty` evaluates `FieldFilled` (true) then the comparison (false → no fire), and the interpreter *also* computed the comparison's `leafType`/`fill` polarity that is then discarded. **Closed 2026-06-25 (#62)** by the **lazy-polarity single walk** (`evalTypedLazy` — polarity only on TRUE nodes); the *short-circuit* half (skip whole operands) remains the kernel's, but for this benchmark's `FieldFilled(true) And …` shape there is nothing to short-circuit, so the polarity-skip was the lever.

**Per-operand primitive cost is ~equal — it is NOT the lever.** `controller.vergleiche(…)` builds a `ValidierungsErgebnis` (truth **+** `fehlerTyp`, eager — set in its constructor) and `feldWertAlsZahl` returns an allocated decimal — the same order of work as our `evalCmp` + `leafType` + `Val.Num`. So polarity is eager in *both*; the kernel's win is **not** a cheaper leaf, it is **(a) not running the leaf at all when short-circuited, and (b) not combining/typing on non-firing rows.**

**The mimic that was tried — a two-phase tree-walk — REVERTED TWICE, then beaten by a lazy single walk (measured 2026-06-25).** The natural mimic is two-phase: Phase 1 a truth-only `evalCond` (no polarity); Phase 2 the typed `evalTyped` **only when fired**. Measured both pre- and post-`placeCache`/`(C)` (bias #5): a noise-free in-run A/B re-rejected it **both** times — pre: +1.16× low-firing / −1.5× high-firing; post-(C): +4% low-firing / −6.5% high-firing. The reason is the **second walk**: the kernel affords its two-phase because Phase-2 (`combineUND`) is cheap *straight-line generated code* over cached leaves; the interpreter's Phase-2 re-**traverses** the AST and re-**evals** the leaves (and would double-invoke a `CustomCondition`), costing more on firing rows than the polarity-skip saves. **The fix is to keep ONE walk but make the polarity LAZY** — `evalTypedLazy` computes a node's type only when its truth is TRUE — which gets the non-firing skip with **no** second walk. Measured faster in **both** regimes; adopted (#62 in the results log). **Lesson:** the kernel's *two-phase structure* isn't the win — a tree-walk can't make the second walk cheap; the extractable win is "don't *type* what doesn't fire," and that fits in a single lazy walk. The *short-circuit* (skip whole operands) does remain inseparable from cheap straight-line dispatch — the interpreter can't bolt that on.

**What this means for the real lever.** The against-kernel low-firing sweep (`KernelEdgeTest.ruleCountCrossoverLowFiring`) shows the kernel's per-operation edge is **largest in the realistic valid-document regime** (192 rules: interp/java **≈1.70** low-firing vs ≈1.16 high-firing — the kernel drops far more when rules pass, via short-circuit + no message-building + straight-line). So closing the gap requires **cheaper per-operation eval**, not an eval *restructure*: the candidates are (a) **compiled/straight-line dispatch** (the Layer-1 sealed-node engine — measured ~7% alone, but it's the prerequisite that would *also* let a two-phase pay off), (b) **unboxed `IntArray` reps** (kill `Integer` boxing on `repsOf`/`FieldPointer`/`Index` keys — research-flagged), and (c) **vectorized columnar eval** for the rows axis. The structural mimic alone is not enough.

**Conclusion (refining the owner's assumption).** The interpreter *can* mimic the generated code's structure — but the measurement shows the structure (short-circuit + two-phase) is **only worth it on top of cheap dispatch**. The kernel's advantage is the *combination* (straight-line + short-circuit + unboxed), and the dispatch piece is load-bearing: bolting the two-phase onto a tree-walk regresses the firing-heavy case. The honest path to parity is the cheaper-per-op work (compiled dispatch / unboxing / vectorization), with the two-phase reconsidered *after* dispatch is cheap.

## Kernel optimizations from SOURCE — mechanism, interpreter analog, and the biases to re-check (2026-06-25)

After the `ValidationCache` mis-triage (I had it in my own table, dismissed it on a wrong verdict, and only the profiler forced the revisit), I stopped reasoning about the kernel's optimizations from memory and **read the runtime + codegen source** (four parallel deep reads: the per-run caches, the short-circuits/pruning, the codegen rule structure + eval primitives, the index/value representation). This records *exactly why and how* each kernel technique works, the interpreter's **true** status (not the assumed one), and — the durable part — **the biases that hid the gaps**. This is iterative: re-read the source and re-run this checklist whenever a new perf problem is learned.

### The technique map (source-grounded)

| Kernel technique | Exactly how/why (mechanism) | Interpreter status |
|---|---|---|
| **`ValidationCache.datenCache`** (value store) | one O(1) `HashMap<id → value>` built once at load; every operand read is a probe, never a scan. The win is a **large constant ×(rules·rows·ops)**, plus *resolve-once-per-row* | **DONE** — `Index.valueByKey` + the per-row `placeCache` (the placeCache was the missing "resolve once per row, reused across rules" half) |
| **`IndexFieldCache`** (value→row) | nested `group → parentReps → value → row` map, so `[F For v]` / parallel-join is O(1), not an O(rows) scan per ref → O(rows²)→O(rows) | **PARTIAL — gap found.** Parallel iteration has it (`indexMap`); the **semantic-index `indexedCell` still does a per-call scan** with no value→row memo → latent O(refs·rows). Fix: memoize `(group,parentReps)→(value→row)`, reuse for `indexedCell` |
| **`GroupFillCache`** | per-instance filled-children nodes, up-propagated; `GroupFilled`/requiredness O(1), not an O(fields) scan → O(rows²)→O(1)/O(rows) | **DONE** — `groupFilled` (repeatable=row-instantiated, O(1) via prefix index) + `nonRepGroupFilledDepths` |
| **`CalculationCache`** (computed overlay) | `id → computed value` layered over the value store; a dependent reads the computed value, not recomputes the producer's tree (avoids re-expanding a DAG) | **DONE** — `compute()`'s `overlay` + `OverlayContext` (incl. through-star); *verify dependency order is topological* |
| **`RepetitionNotUniqueErrorCache`** | **lazy** all-pairs sweep on first touch → O(rows²) once, then O(1) lookups (vs O(rows³) naive) | **DONE & FASTER** — hash group-by, O(rows). **Don't "match the kernel" by reintroducing the all-pairs sweep** — match the *result*, not the algorithm |
| **`USBIndexCache`** | bidirectional USB-number maps; H-DV/legacy models only (`assert hasUniqueEntities()`) | **n/a** — legacy USB world out of scope; **zero analog is correct** |
| **Two-phase guard** (`if (a.isKnownAndTrue() && …)` then typed `combineUND/ODER` on fire) | the firing decision runs on native `&&`/`||` over primitive booleans (real short-circuit; later operands' field reads skipped); the *typed* combine (allocating, type-discriminating) runs **only on fired rows** | **NOT adopted** — `Kleene.and(eval(l),eval(r))` evaluates both; `evalTyped` computes polarity every row. A naive two-phase was measured net-loss **pre-`placeCache`** → **RE-MEASURE** (the second walk's re-reads are now cached) |
| **Short-circuit `∧`/`∨`** | skip the right operand once the left forces the result (`F∧x=F`, `T∨x=T` — monotone, semantics-preserving) | **NOT adopted** — `Kleene.and/or` are strict. Cheap, safe; helps when an early operand decides |
| **Quantifier early-cancel** | `AtLeastOne` returns on the first witness, `All` on the first counter-example; aggregates stop via `Combiner.canChange()` (e.g. `FirstFilledValue` at the first filled cell) | **GAP** — `quantTally` does a **full scan**, no early-exit; for monotone quantifiers (`Exists`/`All`/`No`) over large stars that's O(rows) where the kernel is O(1)/O(few). (Not for count/`NotAll`/different-values.) Audit `starVals` laziness too |
| **Iterator pruning / end-bounding** | iterate **set** repetitions (`GesetzteWiederholungen`), bound to max-*filled* not declared-max, skip empty rows; star-opt threshold 100 | **DONE** — instantiated-row index (`rowsByGroup`/`contextsFor`) is O(filledRows), cleaner. Risk: any star/parallel path iterating **declared** reps; audit |
| **Interned result + packed polarity** | a comparison returns an **interned enum** (truth+`fehlerTyp`, 0 alloc); fillability rides as **booleans on `VkBigDecimal`** (no separate object); polarity eager (a cheap flag), operator-aware | **DONE (C+#62)** — `Res`/`Fill` are interned (0 alloc, like the kernel's enum); and where the kernel pays the eager polarity *cheaply* on every reached node, the interpreter's `leafType`/`fill` is *not* cheap, so it instead computes polarity **only on firing nodes** (the `evalTypedLazy` walk) — same observable result, the non-firing waste removed |
| **Unboxed `long[]` indices, carried in-place** | a row's index path is one primitive `long[]`, mutated while advancing, `clone()`d only on emit; `Arrays.equals/hashCode` keys; sentinels packed as negative slots | **GAP (representational)** — reps are boxed `List<Int>`, re-allocated by `repsFor` per lookup. → `IntArray` reps, snapshot only on escape (research-flagged) |
| **Decimal (`VkBigDecimal`, MathContext(50), scale-19 compare)** | exact decimal; `BigDecimal` arithmetic already runs on an internal compact `long` (`intCompact`), `BigInteger` only on overflow — so a "scaled-`Long`" *rewrite* buys ~nothing on arithmetic (the kernel has the same fast path) | **PARTLY HARVESTED (DONE 2026-06-25)** — the win was *eliminating* allocation we didn't need, not a decimal rewrite: **(A)** skip the two `setScale(19)` allocs per compare (`compareScaled19`, value-preserving at ≤19 digits), **(B)** cache literals' `Val.Num`. Bit-identical, differential-gated. **Residual** decimal cost is the `Sum` fold's intermediate `Dec` objects — a long-accumulator fast path *there* (overflow→bignum) is the remaining decimal lever, scoped to the aggregate fold |

**Correction to a prior mental model (a bias, below): the kernel's value store is itself a `HashMap`, not array-indexed storage.** Its per-row cheapness comes from **memoization (the caches) + iteration pruning**, NOT contiguous arrays. The interpreter's `Map`-keyed `Index` is already kernel-aligned — *don't chase a column-store rewrite*; port the caches/pruning (and the `IntArray` index representation), which is where the kernel actually avoids O(N²).

### The biases to re-check (the durable lesson)

Each one already caused, or nearly caused, a mis-triage. Run this list when a new perf problem appears:

1. **Cliff-hunting** — "an O(N²) cliff is the danger; a constant factor isn't." A per-(rule,row) redundancy is a **~rules× constant** that dominates at high rule counts. *(Mis-triaged `ValidationCache`.)* → measure constant factors at real rule counts, not just complexity class.
2. **One-cache-one-analog** — "six kernel caches ⇒ six interpreter analogs." `USBIndexCache` → zero (legacy); `RepetitionNotUnique` → a *better* algorithm. → **match the result/behavior, not the data structure.**
3. **Map-vs-array** — "the kernel is fast because it stores values in arrays." It uses a `HashMap` too; speed = memoization + pruning. → don't rewrite the store; port the caches.
4. **Elegant-single-walk** — the one `evalTyped` walk (truth+polarity per node) is *tidy*, which hides that it does the kernel's expensive phase-2 work on every non-firing row. → the kernel's uglier guard/combine split is the perf design; tidiness ≠ speed.
5. **Measured-once-settled** — a rejected optimization can become viable after a *related* change. The two-phase was net-loss **before** the `placeCache` made re-resolution cheap; its calculus has changed. → **re-measure rejected optimizations when the cost structure shifts.**
6. **Cross-run-noise** — a 20% kernel-Java shift between runs hid/faked wins (Layer 1 looked like noise; the placeCache magnitude was confounded). → use the **within-run ratio** (interp/kernel-Java in the same run) or an **in-run A/B toggle**, never cross-build absolutes.
7. **High-firing-benchmark** — the only against-kernel sweep was high-firing (`perfValues`), which hid that the kernel's edge is *largest in the realistic valid-document (low-firing) regime*. → always measure both regimes.
8. **Resolved-on-demand-is-fine** — "lazy per-call resolution is cheap." It's cheap *once*; re-done per accessor per operand per rule per row it's the dominant cost (`placeCache`). → measure the *redundancy*, not the single call.
9. **Allocation-blindness** — counting lookups/dispatch but not the per-op object churn (`Val`+`Fill`+`Res`, boxed reps). The kernel interns results + packs polarity. → count allocations on the hot path.

### The work-list this produced (measured, iterative)

Ordered by confidence, each gated by the full differential + both firing sweeps: **(reps-memo — DONE, the biggest win)** memoize `repsOf` per RowContext — found by the #64 JFR profile, ~halved ratioJava everywhere (results log); **(D)** `IntArray` reps — **revalidated** by the same profile (boxed `List<Int>` reps as `Index`/`Pair` map keys still cost ~16%, now the largest remaining cluster after the reps-memo), the remaining by-representation lever; **(C — DONE)** collapse `Val`/`Fill`/`Res` → interned truth-with-type + packed fill bits; **(A — DONE)** memoize `indexedCell`'s value→row; **(B)** quantifier early-cancel for monotone quantifiers + audit `starVals` laziness; **(F/F2 — DONE)** two-phase short-circuit re-measured twice and re-rejected; the polarity-on-non-firing-rows waste it targeted is closed by the **lazy-polarity single walk** (`evalTypedLazy`); **(compiled-node engine — REJECTED, #64)** the JFR profile put the eval at ~3–4% — a compiler subsystem would target ~3%; unbuilt; **(decimal — DONE, reframed)** the two allocation cuts on the compare/literal path, not a scaled-`Long` rewrite; **(Sum-fold — DONE, not worth it)** aggregates already beat the kernel 10–50×. Remaining lever: **(D)** `IntArray` reps. None changes observable semantics — all are representational/structural/algorithmic, clean-room.

## Optimization catalog (rationale · implementation · example · results)

Every optimization here is in **`commonMain`**, so it benefits **JVM and JS identically** (one code path; JS is verified by `DocumentScaleTest`/`InterpreterMultiplatformTest` on Node). The governing constraint is **fast startup**: each is **lazy** (built on first use inside the mutation-invalidated `Index`, or a per-rule `lazy {}`) — never eager work in the constructor. Listed in evaluation-pipeline order.

### 1. Non-quadratic `Document` (the foundation, #28)

- **Rationale.** The general `Document` stored placements in a `List`; every `field`/`filled`/`displayValue`/`star`/`groupFilled` did a linear `firstOrNull{…}` scan, so per-row evaluation was **O(N²)** — 100k rows could not even run (a quadratic JS document blew the 2 s test timeout at 20k).
- **Implementation.** A lazily-built, **mutation-invalidated `Index`** maps `(path, reps) → placement` and `groupPath → rows`; lookups become O(1)/O(rows). Each row's `evaluated` flag is computed in **one amortized pass** over the valued fields; `autoGroup` dedups via a set (was O(P²)).
- **Example.** B1 — a per-row `[Qty] > 50` over N `/Perf/Lines`.
- **Results & discussion.** Linear: **300 000 rows at ~2.5 µs/row**. This is the prerequisite every later optimization builds on — without an O(1) value store, nothing else matters.

### 2. Prepare-once ASTs (lazy parse cache)

- **Rationale.** Every `validateFull` re-parsed each rule's condition — in fact **twice** (`repetitionNotUnique` + `rowOutcomes`). The hypothesis (#31) was that this per-call re-parse caused the many-rules slowdown.
- **Implementation.** A per-rule `lazy {}` (`SYNCHRONIZED`, so the engine is thread-safe to reuse) AST wrapper, built in the constructor **without parsing** (→ fast startup); the first use parses and caches, every later call/row reuses. A single-rule `testRule` forces only the rule it touches.
- **Example.** B6 / `manyRules(N)` in repeated-eval (engine reused over many documents).
- **Results & discussion.** **Honest negative result:** parsing was *not* the bottleneck — the crossover did not move. The value is **startup + hygiene** (it also removed the redundant double-parse). The lesson — *measure before assuming* — is why the next optimization was found by profiling, not guessing.

### 3. Per-group row-context memo

- **Rationale.** `contextsFor`/`attachByDepth` did an **O(valued) attach-depth scan on every rule** (and every nested row). Many rules share a handful of iteration scopes, so this was O(rules × valued) of pure recomputation.
- **Implementation.** Memoize `contextsFor` and `attachByDepth` **per group** inside the `Index` — computed once per distinct scope, reused across all rules. The clean-room analog of the kernel computing the document's row structure once and reusing it.
- **Example.** B6 — 12 rules over ~3 distinct scopes (`/Perf`, `/Perf/Lines`, `…/Subs`).
- **Results & discussion.** **~24 % faster across all rule counts**; crossover vs Groovy moved **~48 → ~96 rules**. This was the first real lever (vs #2's null result).

### 4. Row-prefix index (the semantic-index / star cliff — the steepest)

- **Rationale.** `starRows` did an **O(all-rows) `.filter` per call**; inside per-row iteration (a semantic index `[F For "v"]`, or a cross-level star aggregate) that is **O(rows²)**. Measured genuinely quadratic (growth ×3.5 per doubling, the interpreter's lead collapsing 0.17→0.66 over 500→4000 rows) while the kernel's `IndexFieldCache` stayed linear and **won at scale** — the proof that the kernel's optimization is well-founded.
- **Implementation.** The `Index` indexes each group's rows by **every prefix of their rep tuple** (built once, dropped on mutation); `starRows` is then an O(1) lookup. The clean-room `IndexFieldCache`/row-index analog — the *idea*, in original Kotlin.
- **Example.** A per-row rule `FieldFilled(Amount) And [/Perf/Lines/Amount For "NOMATCH"] > 0` (`PerfCatalog.semanticIndexPerRow`).
- **Results & discussion.** **98 ms → 6.8 ms at 4000 rows**; linear again (growth ~×2/doubling), pulling *ahead* of the kernel (ratioG 0.66→0.04). Guarded against regression by `PerfGateTest.semanticIndexStaysSubQuadratic`. Also fixes any cross-level star aggregate evaluated per row (e.g. a per-Line `Sum(Subs* / Cost)` at scale).

### 5. Single typed walk per row (no double AST walk)

- **Rationale.** `rowOutcomes` called `fires()` (a Tri-only walk via `eval`) **and then** `firedType()` (a typed walk via `evalTyped`) — **two full AST walks on every firing row**. But `evalTyped` already yields the truth value, so the second walk is redundant.
- **Implementation.** Call `firedType()` **once**: non-null ⟺ the rule fires (`truth == TRUE`), and the value *is* the polarity (VALUE/OMISSION). A firing row now costs one walk, not two.
- **Example.** B6 / `manyRules(96)` — 45 480 firing rows, each previously walked twice.
- **Results & discussion.** Crossover vs **kernel-Java reached parity at ~96 rules (ratioJava 0.99)**; the 192-rule gap narrowed **1.30 → 1.19**. The win scales with the firing ratio (no help for all-passing documents — those already walked once).

### 6. O(1) repeatable-group fill

- **Rationale.** `groupFilled`'s repeatable-group branch did `reps in rowsOf(group)` — an **O(rows) `List` membership** — so requiredness over a **sparse-required** document (many empty required fields, each triggering a `GroupFilled` check per row) was **O(rows²)**. The `GroupFillCache` analog (the repeatable half).
- **Implementation.** O(1) membership via the row-prefix index (`rowsWithPrefix(group, reps).isNotEmpty()`).
- **Example.** A form with many empty required fields across N rows (every empty field triggers the per-row group-fill check).
- **Results & discussion.** Removes the latent requiredness O(N²). (The *non-repeatable* `GroupFilled` branch was the sibling — now fixed too, #8.)

### 8. O(1) non-repeatable group fill

- **Rationale.** `groupFilled`'s non-repeatable-group branch scanned `index().valued` and tested each descendant's scope — **O(valued) per call** → O(rows × valued) when a model has `GroupFilled(nonRepeatableGroup)` checks per row. The other half of the `GroupFillCache` analog (#6 did the repeatable half). It looked hard because the scope depth varies with the rule's iteration group.
- **Implementation.** A per-`(groupPath, group)` **attach-depth index** over the valued *descendants* of `groupPath` — exactly the `attachByDepth`/`contextsFor` shape (group descendants by their `commonPrefixLen(p.path, group)` depth, store the prefix set), memoized in the `Index`. `groupFilled` then checks `rowReps.take(d)` against the set at each depth — O(1) per call after a once-per-`(groupPath, group)` build. It mirrors the old per-descendant scope check exactly, so behavior is unchanged (the dead `inScope` helper was removed).
- **Example.** A per-row rule `GroupFilled(SomeNonRepeatableGroup) And …` over a large document.
- **Results & discussion.** The non-repeatable `GroupFilled` path is now O(1)/call — no O(N²) left in either `groupFilled` branch. Full `:adapter` differential suite green (`GroupFilledDiffTest` + the requiredness/index-field law tests confirm behavior unchanged). With this, **no known O(N²) cliff remains on any common path** (the row-prefix index #4, the repeatable-fill #6, and this close out the kernel-cache analogs the interpreter was missing).

### 7. Linear index build (the cold-path / startup / JS cliff the warm harness never saw)

- **Rationale.** The `Index` build deduped group rows with `if (p.reps !in it) it.add(p.reps)` — an **O(rows) `List` membership per group placement → O(N²) to build the index**. The against-kernel harness *never saw it* (it measures **warm**, after the index is built once outside the timed region), so it hid behind that methodology — but **every COLD first `validateFull` pays it**: startup, the CLI one-shot path, and a single JS pass. It was the real reason a JS run blew up at scale (cold JVM @20k = 1.75 s; **JS @20k = 14 s**, super-linear).
- **Implementation.** Drop the check entirely: every group placement is **already unique** by `(path, reps)` because both `group()` and `autoGroup()` gate on the `instantiatedGroups` set before adding — so the `!in` scan was redundant *and* quadratic. A plain `add` makes the build O(N).
- **Example.** Any cold/first evaluation at scale — `DmInterpreter(model).validateFull(bigDoc)` on a fresh document; the CLI evaluating one large document; the JS `DocumentScaleTest`.
- **Results & discussion.** Cold build is **linear**: JVM @30k = 152–220 ms (was ~1.75 s at *20k*); **JS @10k = ~60–106 ms (was >2 s — it timed out)**. This is the **biggest win for startup and for JS**, both of which only ever run the cold path. The JS scaling guard (`DocumentScaleTest`) rose from 3k → **10k** (a scale that was *impossible* on JS before). A worked lesson in benchmark methodology: a warm against-kernel harness is right for steady-state but **blind to one-shot/startup cost** — measure the cold path separately (that is what `PerfScalingTest.startupCost` and the JS `DocumentScaleTest` now do).

### JS performance — one code path, so every optimization carries (2026-06-24)

All seven optimizations live in **`commonMain`**, so **JVM and JS run the identical algorithm** — there is no JS-specific slow path, and there is none to maintain. JS is **linear** on the real `Document` (`DocumentScaleTest`, Node): ~60–106 ms at 10k rows. The residual **JVM↔JS gap is a constant factor** (the Kotlin/JS runtime: no JIT on a single pass, slower numerics/collections) — *not* a complexity difference, and not something to chase with JS-specific code (which would fork the engine and break the clean-room single-source). The lever that mattered for JS was the **cold-path O(N²) (#7)**: because JS has no JIT warmup, a quadratic build that the JVM shrugs off (warm) made JS time out — fixing it at the shared root made JS scale. Startup stays fast on JS for the same reason it does on the JVM: every index/memo is **lazy**, never eager constructor work. (A future *kernel-JS vs interpreter-JS* comparison — the kernel's TypeScript codegen target — is the JS analog of the Groovy-vs-Java baseline; the harness is built language-parameterized for it.)

**Net effect (2026-06-24):** the interpreter is faster-and-linear for the common case on **both JVM and JS**, **starts fast with no codegen** (cold first-call now linear too, #7), has **no remaining *known* O(N²) cliff on the common paths**, and now **matches kernel-Java at ~96 rules / beats Groovy through ~150**. The last gap (1.19 at 192 rules) is the irreducible interpreter-vs-generated-code per-node cost — closing it past parity at extreme rule density is the **generator's** job, not more tree-walk tuning.

## The harness (invest here — it pays off)

Not ad-hoc `measureTime` calls sprinkled in tests — a **proper, reusable harness**, because it does double duty: it **gates** regressions *and* it's how we **find** the O(N²) hotspots in the group machinery. Design:

- **`PerfScenario`** — the unit: a real DM-JSON model + an **instance generator parameterized by scale `N`** (build 1k/10k/100k rows *programmatically*, never commit giant fixtures) + the operation (`validateFull` / `compute`) + the correctness oracle. One scenario, run by both engines.
- **Two engine adapters behind one interface** — `KernelEngine` (via `RuntimeLaws` → `IDocumentRtService`) and `InterpreterEngine` (`DmInterpreter`). The harness lives in `:adapter` (the only module with the kernel), mirroring the `laws/` differential layout, under `…/perf/`.
- **Measurement discipline** — per (scenario, N): **warm up** (discard JIT-warming iterations), then time `K` iterations; report **median** (robust) + min + p90, and the **ratio** `interp_median / kernel_median`. Never time a cold run.
- **Correctness first** — assert `interp == kernel` on the scenario *before* trusting any timing (a fast-but-wrong path is not a win; reuse the differential comparison). The comparison is the fired-code **multiset** (a sorted `List`, not a `Set`), so a per-row firing-count mismatch is caught, not just a missing code.
- **Three run modes** — (1) **gate mode**: single document, loose bounds, CI-friendly, fails on a catastrophic/relative regression; (2) **repeated-eval mode**: the engine built once, M slightly-different documents looped (form-input / batch) — the reuse pattern the kernel's codegen + per-run caches are tuned for, reported per-eval; (3) **profile mode**: one scenario at high `N`, interpreter-only, verbose per-iteration stats, no assert — for hunting the hotspot (JFR/async-profiler-friendly loop on JVM).
- **JVM tool choice** — start with this **custom integration harness** (whole-document evaluation is the real unit; warmup+repeats+median+ratio is the right rigor for it). Reserve **JMH** for a *specific hot inner loop* (e.g. `repsFor`/`starRows`) that needs sub-method attribution — add it only when a profile points there, not upfront.
- **JS throughput** — a Node-runnable large-`N` check on `jsTest` (the same `Document`, `measureTime`, no kernel) so the native/JS reason-to-exist is perf-gated too.
- **Output + baselines** — print a `scenario × N × interp_ms × kernel_ms × ratio` table and append machine-tagged baselines to the results log (below). The spec holds the method; the log holds the numbers.

**Built (2026-06-23, extended 2026-06-24):** `adapter/src/test/.../perf/` — `PerfHarness` (the `PlacementSink`-driven scenario + a `PerfOp{VALIDATE,COMPUTE}` + the three engine adapters `Interpreter`/`Kernel`(Groovy)/`KernelJava` behind `PerfEngine`/`ReusableEngine` + `measure`/`runScenario`/`runRepeated`/`timeInterpreter`), `PerfCatalog` (single-sourced `perf.dm.json` + **B1–B7** + a parameterized `manyRules(count)` + a fuzz scenario), `PerfGateTest` (the in-suite gate — **Groovy-only for speed** — + repeated-eval + sub-quadratic + compute), `KernelEdgeTest` (opt-in `-Dperf.edge=true` — the rule-count crossover + batch), `PerfScalingTest` (opt-in `-Dperf.scaling=true` — startup cost + row scaling), `PerfProfileTest` (opt-in `-Dperf.profile=true`). `RuntimeLaws` is the shared kernel oracle; its build-once `KernelEngine` (Groovy) **and** `JavaKernelEngine` (the kernel's own validator compiled as Java — the Groovy-tax-free baseline) both expose `firedCodesSorted`/`computeOutcomesSorted`. `KernelJavaBaselineDiffTest` guards that the Java baseline fires identically to Groovy. Codegen is primed out of every timed region (non-negotiable #2).

**Model-driven seed generator + differential fuzz net — built (2026-06-23).** The instances are built by a kernel-free **`SeedGenerator`** (`…interpreter.gen`) through the `PlacementSink` seam, so the perf harness, the **differential fuzz net** (`InterpreterKernelFuzzTest` — the correctness twin of this perf differential), and a future `dmtool seed` verb all share one generator. **Both pieces have their own spec — [SEED-GENERATOR-FUZZ-SPEC.md](SEED-GENERATOR-FUZZ-SPEC.md)** (the generator's contract + the fuzz method, diverse model, value strategy, bug track record, and per-operator extension loop); this doc only consumes the generator for the perf scenarios.

## Architecture direction (owner hint, 2026-06-23 — keep in mind)

A structural optimization to design toward (not yet committed work):

- **Decouple eval from a hierarchy — a flat, indexed instance is enough.** The validate/compute API only ever asks "the value for `(path, repetition-indices)`", so the instance backing store can be a **flat `(path, reps) → value` map**, not a tree (this *is* the task-#28 index, promoted to the primary representation). Group membership and iteration scopes come from the **model** (stable), not from walking an instance tree.
- **Prepare the model once; reuse across runs.** Today `DmInterpreter` **re-parses every rule/computation on every call** (`parseCondition`/`parseExpression` inside `rowOutcomes`/`compute`). The use-case is a form-input loop — *user edits a value → validate/compute → feedback → repeat* — so the **model is stable while values change**. Parse the rules/computations to ASTs, precompute iteration scopes + the field-metadata index **once** (a prepared/compiled model), and run many value-sets through it. Shape: `val engine = DmInterpreter(model)` built once → loop `engine.validate(values)` / `engine.compute(values)` with a fresh flat value store each iteration. This mirrors the kernel's own compile-once + per-run-cache architecture (`ValidationCache`/`CalculationCache`/`IndexFieldCache`/`GroupFillCache`).
- **The caching boundary (the owner's caveat):** cache only **value-independent** artifacts (parsed ASTs, scopes, field metadata, possibly stable structure). **Never memoize results across runs** — every follow-up run has different values by definition (it's the edit loop).
- **Open question (deferred):** between runs, do only *values* change, or also the set of repetition *rows* (add/remove)? If values-only, the structure index is reusable too; if rows change, rebuild the value store per run and reuse only the prepared model (the safe default). Decide when this is picked up — profile first.
- **Study the kernel's data representation + caches for INSPIRATION (not a copy — the license boundary holds: read for comprehension, write original).** The kernel devs spent *years* optimizing; its instance representation and its caches — `IndexFieldCache`, `ValidationCache`, `CalculationCache`, `GroupFillCache`, `RepetitionNotUniqueErrorCache` — encode hard-won structure (index-by-field, memoized group-fill, cached calculation results within a run). **Do not assume a naive fresh impl will be anywhere near kernel performance.** Get informed by what they cache and how they index, reproduce the *idea* in original Kotlin, and let the against-kernel benchmark (#29) tell the truth. Humility + measurement, not confidence.

## Prerequisite — make the real `Document` non-quadratic (task #28 — DONE 2026-06-23, `434cdcdc`)

A naive O(N²) `Document` cannot even run 100k rows, so the first step was structural. **Done:**

- **`valueAt`/`rowsOf` indexed** — a lazily-built, mutation-invalidated `Index` maps `(path, reps) → placement` and `groupPath → rows`, so `field`/`filled`/`displayValue`/`star` are O(1)/O(rows), not O(P).
- **`evaluated` amortized** — `contextsFor(G)` computes each row's `evaluated` in ONE pass over the valued fields (recording each field's index-prefix at its attach-depth), instead of an O(P) scan per row.
- **`autoGroup` deduped via a set** — group auto-instantiation was an O(P²) `placements.none{…}` build scan; now O(1) per add.
- All `*DiffTest` stay green (behavior unchanged — a perf change, correctness already locked).
- **Residual (noted):** `groupFilled` still scans the valued list per call (O(valued)); only group-predicate-heavy models hit it — the #29 benchmark will say whether it needs indexing.

The large-N benchmarks below are now runnable (task #29).

## Benchmark catalog (the hard cases — grow this list)

Each scenario is a real DM-JSON model + an instance built at parameterized scale `N`, evaluated by **both** engines. Start with these group-stressing shapes:

| # | shape | what it stresses | scale knob |
|---|---|---|---|
| B1 | flat group, per-row comparison rule | baseline row iteration | N rows: 1k / 10k / 100k |
| B2 | starred aggregate (`Sum`/`MaxValue`/`NumberOfDifferentValues`) over one repetition list | `star` collection + aggregate fold | N rows |
| B3 | **nested repeatable groups, 3–4 levels** (e.g. Order → Items → Sublines → Charges), per-innermost-row rule | parallel index + iteration-scope resolution at depth | rows per level (e.g. 100×100×10) |
| B4 | **cross-level reference** — an inner-row rule referencing an outer-group field | common-prefix resolution walk per row | depth × N |
| B5 | aggregate over a **deep** list (`Sum(A*/B*/C)`) | nested-star collection | rows per level |
| B6 | many rules × many rows (a realistic model: dozens of rules over 10k–100k rows) | whole-document throughput | rules × rows |
| B7 | computations at scale (`compute` over nested groups) | the compute path, not just validate | rows |

The catalog must favor **non-obvious** combinations (nesting × aggregation × cross-level), not just "more rows of the flat case." Use own-domain fixtures (the license-safe rule); a dedicated `models/perf-*.dm.json` set is fine.

## The regression gate (mechanics)

- **Home:** `adapter/src/test/.../perf/` (has the kernel; mirrors the `laws/` differential layout). Not in `:interpreter` (kernel-free) and not in the cold-agent surfaces.
- **Per scenario:** build the instance once; warm up (JIT); time `K` iterations of each engine; report `interp_ms`, `kernel_ms`, `ratio`. Assert correctness first, then the bound.
- **Bounds, two kinds:** (a) an **absolute** generous ceiling per scale (catches catastrophic O(N²) — e.g. "100k rows under X s"), tuned with headroom so CI/JS variance doesn't flake; (b) a **ratio** bound vs the kernel ("interp ≤ c × kernel") that catches a relative regression even as hardware changes. Prefer the ratio bound as the primary signal; keep the absolute as a backstop.
- **Cross-platform:** the same `Document` runs on JS (Node) — add a JS throughput check for the large-N shapes (the interpreter's reason-to-exist is native+JS, so JS perf is in scope), without the kernel (JS has no kernel; correctness there is pinned by `InterpreterMultiplatformTest`).
- **Results log:** record dated baselines (machine, N, interp_ms, kernel_ms, ratio) in a results section/log, newest first — the spec holds the method, the log holds the numbers (mirrors the EVAL-SPEC ↔ eval/RESULTS.md split).

## Staging

1. ✅ Index the `Document` (task #28) + re-green the diff tests — DONE (`434cdcdc`).
2. ✅ **The reusable harness is built** (`adapter.perf` — `PerfHarness`/`PerfCatalog`/`PerfGateTest`/`PerfProfileTest`): the `PerfScenario` + two engine adapters behind `PerfEngine`/`ReusableEngine` + the three run modes (gate / repeated-eval / profile), with codegen primed out of every timed region. **B1** (per-row), **B2** (star aggregate), **B3** (nested per-sub), **B4** (cross-level ref) land against the kernel; the **repeated-eval** (form-input/batch) and **sub-quadratic scaling** guards land too. The ad-hoc `AgainstKernelPerfTest` was superseded and removed.
3. ✅ **B5** (deep nested-star `Sum(Lines* / Subs* / Cost)`), **B6** (many rules) and **100k+ rows** (interp-only, `PerfScalingTest`) — DONE (2026-06-24). B6 surfaced the rule-count crossover; the row-scaling confirms linearity to 300k.
4. ✅ **B7** (`compute` at scale) — DONE; both kernel engines got a build-once `computeOutcomesSorted`. **Java baseline** (`JavaKernelEngine`, Groovy-tax-free), **rule-count crossover** (`KernelEdgeTest`), and **startup + row-scaling** (`PerfScalingTest`) all landed.
5. **Next:** the residual many-rules gap → a future **generator** (no tree-walk); JS throughput beyond `DocumentScaleTest`; wire a gate into CI once baselines are stable.

## Results log

*(dated baselines, newest first — populated as benchmarks land)*

**2026-06-25 — (rejected) a cached-hashCode reps wrapper (`RepsList`) — the delegation cost beat the caching.** Follow-on to the `repsOf` memo: wrap the memoized reps in a `List<Int> by backing` that caches its hashCode (to kill the profile's `hashCodeRange`/`Pair.hashCode`). Differential-green but a measured **net LOSS** (same-machine A/B: low-firing 192 0.47→0.69, high-firing 0.32→0.43). Reason: `by`-delegation adds an indirection on **every** reps access (`get`/`take`/`joinToString`/iteration — all hot) plus a wrapper allocation, which dwarfs the once-per-instance hashCode saving. Reverted. **Lesson:** a cache that adds per-access indirection can lose to the recompute it replaces — measure it, don't assume. (The bigger map-key cost — the `Pair<String, List<Int>>` composite keys' `getNode`/`areEqual` — would need a *structural* change, e.g. splitting `valueByKey`/FormalCache by path to drop the `Pair`; deferred.)

**2026-06-25 — the kernel's codegen ceiling is a rule-CONCENTRATION artifact, NOT a per-DM ceiling (corrected; rule distribution also moves perf).** An earlier run of this log claimed "the kernel STRUCTURALLY CANNOT run past ~512 rules" after `manyRules` hit `MethodTooLargeException` at 768. **That was an artifact of the benchmark, not a kernel limit** — chased down via the kernel codegen source + a distributed re-test:
- **Mechanism (source).** The kernel's validator codegen (`CodeGenTeilValidierung`) groups rule-calls by their **error field** and splits them into `fuehreAus_N` methods **by FIELD count — 10 fields/method for Groovy, 50 for Java**. Each method holds **all rules of its fields**. So a method's size is driven by *rules-per-field-batch*, not total rules.
- **Why `manyRules` hit the wall.** Its rules pile onto only ~3 error fields (Qty/Amount/Cost), so ALL of them collapse into one `fuehreAus_0` → that method blew 64 KB at ~512–768. Manufactured concentration, not a realistic shape.
- **Distributed re-test.** `manyRulesDistributed` (one rule per distinct field — the realistic "wide form") spreads rules across many fields → many small methods. The kernel now **compiles and runs 2000 rules with no error** (`distributedRuleScaling`, N=100): interp/kernel-Java **0.43 → 0.31** across 192 → 2000 (the interpreter ~2.5–3× faster, ratio improving with scale). So the kernel handles large *realistic* models fine; only a pathological many-rules-on-few-fields concentration trips the method limit.
- **Interpreter:** unaffected by rule distribution (no codegen), dead-linear either way — `interpScalingPastKernelCeiling` shows ~0.54 ms/rule (low-firing) / ~0.80 ms/rule (firing) flat from 192 to 2000 rules.
- **Lesson (the user caught this):** an "ill-shaped" benchmark distribution distorts BOTH the codegen-limit finding AND the perf ratio. Benchmark realistic distributions (rules spread across fields/groups), not a manufactured pile. (Knobs: `KernelEdgeTest`, `perf.edge.counts`, `PerfCatalog.manyRulesDistributed`.)

**2026-06-25 — (#64) compiled-node engine REJECTED again by a JFR profile — which instead found the real lever: memoize `repsOf` → ratioJava ~HALVED, low-firing 192 1.03→0.48.** Bias #5 said to re-measure the Layer-1 compiled-node engine now that `(C)`/#62 shifted the cost structure. A JFR sampling profile of low-firing-192 `validateFull` (the new `EvalProfileTest` + the `-Dperf.jfr` hook) settled it: the **eval dispatch is only ~3–4%** (`evalTypedLazy`+`evalCmp`+`evalPred`+`evalExpr`+`firedType` ≈ 80–90 of ~3000 samples), so a compiled-node engine — which optimizes exactly that — **can't move the needle** (Amdahl). The Layer-1 finding still holds; the cost did NOT shift to the eval. It shifted to the **reps/path machinery**: `commonPrefixLen` (270) + `repsFor` (180) ≈ 15%, boxed `List<Int>` reps as map keys (`ArrayList.equals`/`hashCode`/`add` + `Pair` hash/equals) ≈ 16%, `HashMap` lookups ≈ 13%. The profile's gift: **`repsOf(path)` was uncached** and called ~5× per row (the error path, each referenced field, the FormalCache `Pair` key, the suppressor scan, `formatPointer`), each call re-doing `partsOf`×2 + `commonPrefixLen` + a `List<Int>` alloc. **Memoize it per RowContext** (the `placeCache`/`valCache` pattern — `repsCache`, stable since the document is immutable; bonus: the FormalCache `Pair` key now reuses the same list instance, cutting the `Pair`/`ArrayList` hash cost). Behavior-preserving, full kernel differential green. **Same-machine A/B, two confirming runs: ratioJava roughly HALVED everywhere** — low-firing **192 1.03→0.48**, **96 0.73→0.34**, **48 0.45→0.25**; high-firing **192 0.57→0.34**. The interpreter now beats kernel-Java by ~2–3× across the whole sweep, including the previously-losing low-firing-192 extreme. **Lesson (bias #5 + the profiler):** "re-measure when the cost structure shifts" paid — but the shift was *away* from the eval; the win was found by *profiling*, not by assuming the named lever. The compiled-node subsystem stays unbuilt (it would target ~3% — a compiler for nothing).

**2026-06-25 — (#63) Sum-fold long-accumulator: NOT worth it — measured the aggregates already beat the kernel 10–50×.** Before investing in a long-accumulator fold (avoid the intermediate `Dec` objects), measured where the `Sum` fold actually dominates: the new `aggregateSweep` (B2 star-sum, B5 deep-nested-star, B7 per-Line compute), interp/kernel-Java. The interpreter is **already 10–50× faster** there: B2 **ratioJava 0.09→0.03** (N=1000→3000), B5 **0.11→0.07**, B7 **0.03→0.02** — a 3000-element fold runs in ~2–3 ms vs the kernel's 28–92 ms. (In `manyRules` the `Sum` rule is once-per-`validateFull`, ~1–2 ms — also negligible.) So the fold is **not a bottleneck anywhere**; a long-accumulator would optimize an already-winning path while adding overflow/scale-alignment correctness risk. **Premise deflated by measurement** (like #61's scaled-`Long`). Kept the `aggregateSweep` as a permanent guard that aggregates stay fast; closed the lever. The low-firing 192 residual is the **tree-walk dispatch**, not arithmetic — next is the compiled-node engine re-measure (#64).

**2026-06-25 — (#62) the polarity-on-non-firing-rows waste, closed by a LAZY-polarity single walk (faster in BOTH regimes); the naive two-phase re-confirmed a net loss.** Bias #5 said to re-measure the kernel's two-phase guard now that (C)+A+B made per-op eval cheap. Did so with an **in-run A/B** (both strategies interleaved in one JVM — bias #6: separate `cleanTest` sweeps had ~13% run-to-run variance, which *faked* a 0.98 then flattened to 1.11 for the *same* code). Two findings:
- **The naive `evalCond`-then-`evalTyped` two-phase is still a net loss** — interleaved A/B: low-firing **~4% faster**, high-firing **~6.5% slower** (penalty > gain). The reason *changed* post-`placeCache`: the polarity it skips (`leafType` → `ctx.filled`/`ctx.signed`) is now *cheap*, so little is saved, while the double-walk re-traverses the AST + re-evals leaves (and would double-invoke a `CustomCondition`) on every firing row. So bias #5's "re-measure" correctly *re-rejected* it — for a new reason.
- **A lazy-polarity SINGLE walk wins instead** (`evalTypedLazy`): compute a node's VALUE/OMISSION polarity **only when its truth is TRUE** (a non-firing node's type is a placeholder, provably never read — sound by induction on `And`/`Or`/`orType`'s read sites; `Not`, parser-unreachable, falls its subtree back to full typing). This is the kernel's "type only firing rows" with **no** double-walk, **no** leaf re-eval, **no** double custom-invoke. Interleaved A/B (lazy/single, two runs): low-firing **0.955–0.972**, high-firing **0.965–0.996** — **faster in both regimes, regression nowhere** (firing rows also skip the polarity of FALSE sub-branches). Adopted as the only typed walk; the transient `twoPhase` toggle + A/B scaffold removed. Correctness #1 by the full kernel differential (the rendered polarity must match). Net effect on the against-kernel ratioJava: low-firing 192 ≈ 1.06 (narrowed, still just above kernel — residual is the `Sum` fold + tree-walk dispatch), high-firing 192 ≈ 0.64 (still beats kernel).

**2026-06-25 — decimal compare/literal allocation cut (the reframed #61) → low-firing 192 1.21→1.07 same-machine.** #61 was scoped as a "scaled-`Long` fast path." Investigating the hot path **deflated that framing**: Java's `BigDecimal` already does its arithmetic on an internal **compact `long`** (`intCompact`, falling back to `BigInteger` only on overflow) — so does the kernel's `VkBigDecimal`. A scaled-`Long` *rewrite* wouldn't make arithmetic faster, only avoid the *result-object* allocation, which we re-incur wrapping the result in `Dec`+`Val.Num` anyway. The real waste in the low-firing (compare-heavy) regime was **allocation we simply didn't need**, two surgical cuts:
- **(A) scale19 elision.** `vglCompare`/`eq` did `l.scale19().compareTo(r.scale19())` — **two** `setScale(19)` `BigDecimal` allocations per NUMBER comparison. New `Dec.compareScaled19`: at ≤19 fractional digits, `setScale(19, HALF_UP)` only pads trailing zeros (never rounds away a digit) and `compareTo` ignores scale, so the rounding can't change the outcome and is skipped; beyond 19 digits it falls back to the explicit form. **Bit-identical** (locked by `CompareScaled19Test`, JVM+JS, incl. the >19-digit rounds-to-equal / rounds-apart cases). The JVM actual carries the fast path; JS stays the exact round-both (not the differential target).
- **(B) literal `Val.Num` caching.** `evalExpr(NumLit)` allocated a fresh `Val.Num(e.v)` per (rule, row); now `Expr.NumLit`/`StrLit`/`BoolLit` carry an eager `asVal` built once at parse (the AST is cached per rule, #31) — the kernel's constant is likewise a field of the generated code. Zero per-eval allocation for literals.

Both are pure allocation cuts (no observable-semantics change), full kernel differential green (correctness #1), `commonMain` (JVM+JS). **Same-machine before/after** (stash the change, re-run the baseline now — controls bias #6, since the prior session's 1.38 was a noisier run): low-firing **192 1.21→1.07**, **96 0.86→0.74** (now beats kernel-Java), **48 0.56→0.49**; high-firing **192 0.71→0.65**, **48 0.51→0.45**. Two fresh A+B runs agreed (192 low-firing = 1.07 both). The residual low-firing 192 (1.07, still just above the kernel) is no longer the *compare* — that path is now ~0-alloc — it is the **`Sum(Lines*/Amount)` fold's intermediate `Dec` objects** (~48k adds at count 192) + the per-row **polarity computed on non-firing rows** (the documented structural waste: the kernel types only firing rows). Those are the next levers, not a decimal rewrite.

**2026-06-25 — (C) cut per-operation allocation (intern `Res`/`Fill`, cache the resolved `Val` per row) → low-firing closes further.** The kernel returns an **interned** result enum (0 alloc) and packs fillability as booleans on the value; the interpreter allocated `Val` + `Fill` + `Res` per `Cmp`. Three changes: `Res` interned (3×2=6), `Fill` interned (2×2=4), and `field()`'s resolved `Val` cached per row context (`valCache`, the kernel re-wraps but we needn't re-allocate across the rules referencing the same field). Behavior-preserving (pure interning/memo), differential green. Effect on the realistic **low-firing** ratioJava (noise-controlled, vs the placeCache-only run): 192 **1.49→1.38**, 96 **1.05→0.98** (now beats kernel-Java), 48 **0.73→0.60**; high-firing 192 holds ~0.84. The remaining low-firing gap (1.38) is `BigDecimal`/`Dec` allocation + dispatch — the scaled-`Long` fast path is the open lever.

**2026-06-25 — (A) `indexedCell` value→row memo; (F) two-phase RE-MEASURED post-`placeCache` → still net-loss, blocked on (C).** Two findings actioned:
- **(A)** Closed the semantic-index scan: `indexedCell` now builds a per-context `indexValue → row` memo once (the `IndexFieldCache` analog), reused across references and rules, instead of an O(rows) scan **per reference** (latent O(refs·rows)). Behavior-preserving (first-match kept), differential green.
- **(F)** Bias #5 said the two-phase short-circuit might pay now that `placeCache` makes the 2nd walk's re-reads cheap. Re-measured (in-run A/B, both regimes): **low-firing 1.06–1.34× faster, high-firing 0.58–0.73× (still ~1.5× SLOWER)** at 192. The `placeCache` removed the re-*resolution* cost, but the double-walk's re-**dispatch** (AST walk) + Val/Fill/Res re-**allocation** on firing rows remain — so still a net loss. **The two-phase is blocked on (C)** (interned result + packed polarity = cheap per-op eval); only when each walk is cheap (as the kernel's straight-line code is) does the two-phase pay. Reverted again; will re-measure after (C).

**2026-06-25 — per-row field-resolution cache: the interpreter now BEATS kernel-Java at 192 high-firing (the session's biggest win).** The kernel-codegen analysis pointed at the real lever: the kernel resolves a referenced field ONCE per row (its `vergleiche`/`feldWertAlsZahl`, cached per row in `IndexFieldCache`), while the interpreter's `field`/`filled`/`displayValue` each re-resolved the same `(path, reps)` independently (`repsFor` list-alloc + `valueAt` map lookup) — and worse, **every rule re-resolved the same field on the same row**. Since the `Document` row contexts are **reused across all rules** iterating a scope, a tiny per-context `placeCache` (path → placement) collapses ~`rules × fields` resolutions per row to ~`fields` (e.g. 192 rules × ~2 fields → ~3 distinct). Measured (interp/kernel-**Java**, the noise-controlled within-run ratio):

| regime, 192 rules | ratioJava post-memo | **+placeCache** |
|---|:--:|:--:|
| high-firing | ~1.25 | **0.84** (interp faster than kernel-Java) |
| low-firing | 1.70 | **1.49** |
| 96 low-firing | 1.28 | **1.05** |
| 48 low-firing | 0.93 | **0.73** |

This is the **kernel-mimic that worked** — not the two-phase eval *structure* (which a tree-walk can't make cheap), but the kernel's **per-row resolution caching** (`IndexFieldCache` analog). Behavior-preserving (pure memo, the document is immutable during a validation; correct for full + partial — full `:adapter` differential green), `commonMain` (JVM+JS). The remaining low-firing gap (1.49) is the next target: the per-call `Val`/`Fill`/`Res` allocations + boxed `List<Int>` reps (the resolution is now cached, the *allocation* is not).

**2026-06-25 — the kernel's per-op edge is LARGEST in low-firing; the two-phase mimic measured a net loss (reverted).** Added a **low-firing** against-kernel sweep (`KernelEdgeTest.ruleCountCrossoverLowFiring` — valid docs, ~0 fires, the realistic regime). It exposed what the high-firing crossover hides:

| regime (192 rules) | interp | kernel-Java | interp/java |
|---|--:|--:|:--:|
| high-firing (`manyRules`, 87.5k fires) | 586 ms | 505 ms | 1.16 |
| **low-firing** (valid doc, 0 fires) | 451 ms | **265 ms** | **1.70** |

The kernel nearly halves (505→265 ms) when rules pass — short-circuit guard + no message-building + straight-line — while the interpreter drops far less. So the kernel's edge is **biggest in the realistic valid-document case**, not the firing-heavy one.

Then the **two-phase short-circuit mimic** (short-circuit `fires` guard + `firedType` polarity only on fire — mirroring the generated code) was A/B'd noise-free in one JVM run (toggle, single-walk vs two-phase):

| regime (192 rules) | single walk | two-phase | speedup |
|---|--:|--:|:--:|
| low-firing | 373 ms | 321 ms | **1.16×** |
| high-firing | 475 ms | **724 ms** | **0.66× (1.5× slower)** |

Net **loss**: the +1.16× low-firing gain is outweighed by the −1.5× high-firing loss, because the interpreter's Phase-2 (`firedType`) is a full tree-walk (the kernel's is cheap straight-line). **Reverted.** Correct throughout (full differential green). The low-firing benchmark is **kept** (it gates the realistic regime). Conclusion: the real lever is **cheaper per-operation eval** (compiled/straight-line dispatch, unboxed `IntArray` reps, vectorization) — not an eval restructure; see "How the kernel's GENERATED code evaluates a rule".

**2026-06-25 — profiled `validateFull`: `formalErrorAt` was 56% (redundant per-operand recompute) → memoized.** Profiling the low-firing 192-rule/1000×5 probe (0 fires, the realistic *valid-document* regime) attributed the cost:

| phase | before memo | after memo |
|---|--:|--:|
| `eval` (alloc + firedType) | 372 ms | 326 ms |
| └ `invalid` (`formalErrorAt`) | **200 ms** | 145 ms |
| suppressor (`formalErrorAt`) | **80 ms** | 39 ms |
| iterationContexts | 0.8 ms | — |
| formal/mandatory/unique pre-pass | 0.6 ms | — |
| **total** | **498 ms** | **395 ms** (instrumentation-inclusive) |

`formalErrorAt` (the field-instance's format/mandatory/duplicate-index status) was being **recomputed per operand occurrence per row and per suppressor ref** — ~56% of `validateFull` — even though the document is immutable during a validation. **Fix: memoize it by `(path, reps)` per `validateFull`** (`FormalCache`, behavior-preserving — kept relevance-independent so partial validation reuses it; thread-safe — created per call). This is the direct analog of the kernel's `ValidationCache`. Effect is **regime-dependent**: ~20% on the **low-firing valid-document case** (the common runtime case — most rules pass); modest on the high-firing `manyRules` crossover (543→529 ms at 192, ratioJava 1.31→1.25) where message-rendering dominates instead. Correct (full `:adapter` differential green). **Residual headroom** (next): the memo's residual `invalid` cost is now the cache *key* (`ctx.repsOf(path)` computed twice per call — once for the key, once for `isRelevant` — plus the map lookup), and the eval-proper cost is `ctx.field`/`Val` allocation; neither is the formal check anymore.

**2026-06-25 — Layer 1 (sealed compiled-node eval) measured ~7% end-to-end, NOT the 2.5× the spike implied — the eval is not the `validateFull` bottleneck (reverted).** A faithful sealed-node compiler for the `manyRules` subset (`And`/`Or`/`Cmp`/`FieldFilled`/`Sum`) was wired behind a whole-rule compile-or-fallback seam, passed the full `:adapter` differential (bit-for-bit correct), and was A/B'd against the tree-walk fallback **in one JVM run** (the noise-free comparison the spike lacked):

| regime | rules | fires | fallback | compiled | speedup |
|---|--:|--:|--:|--:|:--:|
| high-firing (all fire) | 192 | 156 729 | 568 ms | 528 ms | 1.08× |
| **low-firing (mostly pass)** | 48 | 0 | 125 ms | 117 ms | **1.08×** |
| **low-firing (mostly pass)** | 96 | 0 | 241 ms | 227 ms | **1.06×** |
| **low-firing (mostly pass)** | 192 | 0 | 479 ms | 448 ms | **1.07×** |

The spike's **~2.5×** was the firing-only eval *in isolation* (per-row `Interpreter` alloc + AST re-walk); at the `validateFull` level that eval is **only ~7% of the cost**, even in the 0-fire regime where it should dominate most. The rest — `iterationContexts` construction, the formal/mandatory/uniqueness pre-passes, the **per-row suppressor scan** (`firstSuppressor` → `formalErrorAt` per ref, per non-firing row), the `EvalContext` Document lookups, and (when rules fire) message rendering — is **shared by both paths**, so an eval-only optimization can't move it. **Conclusion: the eval is the wrong lever.** Layer 1 was reverted (a ~7% win does not justify a new compiler subsystem — "minimum code, nothing speculative"); the next step is to **profile `validateFull`** and target the dominant *shared* cost (likely the per-row suppressor scan + `iterationContexts`). The measure-to-guide discipline did its job: it stopped a complex, low-value addition before it shipped.

**2026-06-25 — pre-optimization baseline** (`KernelEdgeTest.ruleCountCrossover`, N=1000 Lines × 5 Subs, median of 8 after 12 warmups; codegen excluded; `ratioJava` = the fair algorithmic ratio). The yardstick the later optimizations are read against:

| rules | interp | kernel-Groovy | kernel-Java | interp/java |
|--:|--:|--:|--:|:--:|
| 6 | 28.0 ms | 118.2 ms | 110.3 ms | **0.25** |
| 12 | 41.9 ms | 128.8 ms | 118.5 ms | 0.35 |
| 24 | 78.4 ms | 152.0 ms | 148.9 ms | 0.53 |
| 48 | 140.6 ms | 201.4 ms | 175.2 ms | 0.80 |
| 96 | 272.1 ms | 284.7 ms | 274.4 ms | **0.99 ← crossover** |
| 192 | 543.2 ms | 502.9 ms | 414.2 ms | **1.31** |

Confirms the regime the optimization targets: the interpreter wins decisively below ~50 rules, reaches parity with kernel-Java at ~96, and loses (1.31×) at 192 — exactly the high-rule hot-reuse case the `a12-kernel-mm` metamodels hit (90 source / 192 composed). Layer 1's measured ~2.5× per-(rule×row) win is aimed here; the target is to push the crossover to ~240 and turn 192 into a win.

**2026-06-24 — full spectrum + the Java baseline + the rule-count crossover + startup & row scaling** (`adapter.perf.*`; build-once engines, median of 8 after 12 warmups unless noted; JVM, single-threaded; correctness — interp == kernel fired-code/compute **multiset** — asserted before timing; **codegen excluded** from every timed region). Three baselines: **interp** (`DmInterpreter`), **groovy** (the kernel's dynamic path — the routine shortcut), **java** (the kernel's OWN validator compiled as Java — the *Groovy-tax-free* baseline). `ratioJava` is the fair algorithmic comparison.

*The spectrum* (single document, 3000 rows; B3–B5 are 600 Lines × 5 Subs):

| # | shape | interp | groovy | java | interp/java | Groovy tax |
|---|---|---|---|---|---|---|
| B1 | per-row `[Qty] > 50` | ~12 ms | ~126 ms | ~97 ms | **0.12** | 1.30× |
| B2 | star aggregate `Sum(Lines* / Amount)` | ~6 ms | ~113 ms | ~91 ms | **0.07** | 1.25× |
| B3 | nested per-sub `[Cost] > 50` | ~14 ms | ~59 ms | ~51 ms | **0.28** | 1.16× |
| B4 | cross-level `[Cost] > [Lines/Qty]` | ~14 ms | ~61 ms | ~53 ms | **0.26** | 1.15× |
| B5 | deep nested-star `Sum(Lines* / Subs* / Cost)` | ~7 ms | ~53 ms | ~48 ms | **0.14** | 1.11× |
| B6 | 12 mixed rules | ~41 ms | ~76 ms | ~66 ms | **0.63** | 1.16× |
| B7 | compute `Amount := Sum(Subs* / Cost)` | ~14 ms | ~91 ms | ~75 ms | **0.18** | 1.22× |

*The Groovy tax* is **small (~1.1–1.3×)** — compiled Groovy is JVM bytecode; the dynamic-dispatch/metaclass overhead over equivalent Java is real but modest. So the interpreter's edge is largely **algorithmic**, not a Groovy artifact: even against kernel-**Java** it is ~4–14× faster on few-rule scenarios. (Residual caveat: the kernel still validates enum/value-list + the explicit `FieldValuesNotUnique` the interpreter doesn't model yet — "interp faster" is partly "interp does less".)

*The rule-count crossover* — **where the kernel wins** (single document, N=1000 Lines × 5 Subs; the lever for the interpreter's #2 goal):

| rules | interp (before any opt) | interp (after all opts) | kernel-Java | interp/java now |
|---|---|---|---|---|
| 6 | 41 ms | **32 ms** | ~97 ms | 0.32 |
| 24 | 124 ms | **88 ms** | ~143 ms | 0.62 |
| 48 | 199 ms | **146 ms** | ~185 ms | 0.79 |
| 96 | 350 ms | **266 ms** | ~269 ms | **0.99 ← parity with Java** |
| 192 | 721 ms | **539 ms** | ~452 ms | 1.19 |

The four 2026-06-24 interpreter optimizations (see **the Optimization catalog**: prepare-once ASTs, per-group context memo, row-prefix index, single typed walk + O(1) group-fill) cut every rule count and pushed the crossover vs **kernel-Java to parity at ~96 rules** (was ~48) — and the interpreter beats **Groovy** (the routine baseline) through ~150 rules. The 192-rule gap narrowed **1.67 → 1.19**. Past ~96 rules the kernel's compile-once generated code still wins at the limit (the irreducible tree-walk-vs-codegen per-node cost) — the **generator's** territory, not more tree-walk tuning. **Batch** (repeated-eval, engine reused, 20 varying docs, per-eval) crosses similarly.

*Startup* (goal #1 — 48-rule model, interpreter measured **on its own terms**; the kernel's cold codegen is **deliberately NOT shown** — it is ahead-of-use in real projects, so comparing it is the forbidden measurement, non-negotiable #2):

| interp startup | cost |
|---|---|
| engine build (lazy AST wrappers, no parse) | ~1.5 ms |
| first `validateFull` (one-time lazy index build + first parse) | ~57 ms |
| steady-state per call thereafter | (the spectrum/scaling numbers above) |

So the interpreter's whole startup is **~58 ms and pays no codegen at all** — which is what makes it viable for the **native image** (the kernel's Groovy codegen can't go native) and one-shot CLI / JS use, where there is no ahead-of-time deploy step to amortize a codegen pass. That is a **capability** statement, not a "faster than the kernel" timing.

*Row scaling* (goal #2 — B1 per-row, interpreter-only, no kernel):

| rows | interp | µs/row |
|---|---|---|
| 30 000 | 75 ms | 2.34 |
| 100 000 | 250 ms | 2.43 |
| 300 000 | 881 ms | 2.65 |

**Flat µs/row ⇒ linear to 300k rows** (the #28 indexing + per-group context memo hold). Against Groovy the interpreter pulls *further* ahead as N grows (ratioG 0.10@1k → 0.01@30k) — and the **kernel-Groovy went super-linear past 10k** (755 ms@10k → 14 190 ms@30k) for this shape; flagged as *observed, Groovy-only, unconfirmed against Java* (likely an O(N²) component or a Groovy-at-scale artifact), not asserted.

**Honest read:** the interpreter is **faster and linear for the common case** (a handful of rules, any document size) and **needs no codegen step at all** (startup ~58 ms, and the only path that can go native); the **kernel wins past ~96 rules** in a hot reuse loop (compile-once + caches). The verdict is not "Nx faster" flatly — it depends on rule density, and the kernel validates more dimensions. We never count the kernel's codegen (it's ahead-of-use in real projects). Both engines agree bit-for-bit on every benchmarked instance.

**2026-06-23 — the serious against-kernel harness** (`adapter.perf.*`; build-once `KernelEngine` vs `DmInterpreter`, median of 8 after 12 warmups; JVM). Correctness (interp == kernel fired-code **multiset**) asserted first. **Codegen excluded** — the kernel generates code once ahead of validation (cached in `IModelCodeCache`); warmup primes it out, so the kernel cost is *steady-state validation against already-generated code*.

Single document:

| scenario | interp | kernel | ratio |
|---|---|---|---|
| B1 per-row, 3000 Lines | ~7.7 ms | ~122 ms | ~0.06 |
| B2 star-aggregate `Sum(Amount)`, 3000 Lines | ~5.3 ms | ~101 ms | ~0.05 |
| B3 nested per-sub, 600×5 = 3000 Sub rows | ~8.7 ms | ~61 ms | ~0.14 |
| B4 cross-level (Sub refs parent Line's Qty), 600×5 | ~8.8 ms | ~61 ms | ~0.15 |

Repeated-eval — engine built ONCE, 20 slightly-different documents looped (form-input / batch; **per-eval**, median of 5 loops after 3 warmup loops):

| scenario | interp/eval | kernel/eval | ratio |
|---|---|---|---|
| B1 per-row, 1500 Lines × 20 docs | ~2.3 ms | ~35 ms | ~0.06 |
| B3 nested per-sub, 300×5 × 20 docs | ~2.8 ms | ~23 ms | ~0.12 |

Sub-quadratic scaling (B1 interp-only, N vs 4N): 9.7 ms@5000 → 41 ms@20000 = **4.24×** (linear ≈ 4, quadratic ≈ 16) — the #28 indexing holds.

**Findings:**
1. **Codegen is one-and-done, never timed.** Confirmed via the stack trace (`generateCode` fires inside the FIRST `validateFull`, then cached) + the monotonic warmup decrease (JIT warming the Groovy *validator*, not re-codegen). The correctness `run()` + warmup discard it, so the kernel's measured cost is its realistic per-document steady state.
2. **Repeated eval — the kernel's tuned pattern.** Both engines speed up per-eval under engine-reuse + JIT warmth; the **ratio is preserved** (~0.06), so the interpreter stays competitive-to-faster even in the reuse pattern the kernel's codegen+caches are built for. With ONE simple rule the interpreter's per-call rule re-parse is negligible — the prepare-once optimization (#31) only bites at **many-rules** scale (a B6 scenario, next).
3. **Not apples-to-apples (the residual caveat):** the interpreter matches the kernel on requiredness (`mandatoryField` — the catalog fills index fields for parity), formal-checking, rich messages, and (since the fuzz net forced it) **index-field uniqueness** (`uniqueIndex`); the kernel still validates dimensions the interpreter does not model yet (**enum/value-list**, the explicit `FieldValuesNotUnique` constraint). So "interp faster" partly means "interp does less".

**Honest read:** **competitive-to-faster** on these scenarios (plausible — direct Kotlin vs Groovy validator execution), **not** "Nx faster" as a speed verdict; a true verdict needs full feature parity. 100k rows (profile mode), deep nested-star (B5), and many-rules (B6) still to add.

**2026-06-23 — first slice: pure-engine real-`Document` scaling** (`interpreter/commonTest/DocumentScaleTest`, the honest counterpart of the RepDoc benchmark; kernel-free, JVM+JS). 3000 `/Perf/Lines` rows:

| scenario | JVM | JS (Node) |
|---|---|---|
| B1 per-row rule (`[Qty] > 50` iterating Lines) | ~65 ms | ~210 ms |
| B2 star aggregate (`Sum(Lines*/Amount) > 0`) | ~82 ms | ~210 ms |

Linear, both well under the JS 2s test-timeout — the regression guard for the #28 indexing (a quadratic `Document` blows the JS timeout at this N; that's exactly why N=20k JS failed and was dropped to 3k for the cross-platform test). **Not yet measured: against the kernel, and group-heavy/nested at 100k** — that's the JVM `:adapter` harness (next #29 increment), which has no 2s test cap.
