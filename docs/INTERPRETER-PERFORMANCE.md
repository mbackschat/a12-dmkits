# Interpreter performance — method, benchmark catalog, regression gate

The `:interpreter` must be **fast on the hard cases**, and that is **measured and gated**, not hoped for. This doc is the durable method; the decision behind it is `CLAUDE.md` "Interpreter … Performance" (decided 2026-06-23). Measured baselines live in a dated results log (below), not here.

## The decision (three non-negotiables)

1. **REAL documents only — benchmarking a shortcut is cheating.** Measure the production evaluation path: the general `Document` (interpreter) and the kernel's `IDocument`, both built from **real DM-JSON models**. The spike's `Map<String,String>`-backed `RepDoc` (O(1) lookups, one-pass `star`) must **never** be the benchmarked object — it sidesteps the exact machinery (parallel index, common-prefix resolution, star aggregation) that is hard to make fast, so its numbers are a fiction. If a benchmark doesn't exercise real group resolution, it isn't a benchmark.
2. **Measure AGAINST the kernel, as a regression gate.** The perf benchmark is the **twin of the correctness differential** and lives in `:adapter` (the only module with the kernel). For each scenario: build one instance, run BOTH `kernel.validateFull`/`compute` and `DmInterpreter.validateFull`/`compute` over it, record both wall-clocks + the ratio, and assert (a) **correctness** (interp == kernel) and (b) a **time/ratio bound** that trips on an O(N²)-class regression. The interpreter should be within a sane constant factor of the kernel — ideally faster (no Groovy, no codegen).
   - **⭐ NEVER count Groovy codegen — in ANY benchmark, including startup (standing rule, re-affirmed 2026-06-24).** Kernel codegen is **always ahead of use in real-world projects**: a real deployment generates + compiles the validator at **model-load / deploy time** (cached in `IModelCodeCache`), so the kernel **never pays codegen per request** — its real cost is steady-state validation against already-generated code. Timing codegen therefore measures a cost production never incurs — a fiction that unfairly flatters any "interpreter is faster / starts faster" claim. **This is absolute and has no exception:**
     - **Comparison benchmarks** prime it out — the kernel generates on its FIRST `validateFull` (cached for the engine's life), and every timed region is preceded by a correctness `run()` + warmup, so the codegen call is always discarded. (This is also why the kernel's per-call time only JIT-warms downward over the first ~dozen calls — the Groovy validator warming, not recodegen.)
     - **Startup measurements too.** The interpreter's "fast startup" (goal #1) is measured **on its own terms** — its engine build + first-call index/parse cost in absolute terms — **NOT** as a race against the kernel's codegen. Do not put a "kernel cold start incl. codegen" number next to it; that's the forbidden comparison. The interpreter's no-codegen advantage is a **capability** point (it matters for native-image — the kernel's Groovy codegen can't go native at all — and for one-shot CLI / JS use where there's no ahead-of-time deploy step), stated as prose, never as a benchmark timing.
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

The interpreter is steered by **two goals that pull against each other** (owner direction):

1. **Fast startup** — minimal upfront work (index building, parsing) before the first answer.
2. **On-par with the kernel at *all* rule counts** — not just the few-rule case.

The tension: goal 2 wants the per-call rule work eliminated (cache parsed ASTs, precompute scopes), but doing that *eagerly* at construction is exactly the startup cost goal 1 forbids. The resolution is **lazy memoization** — do the work once, **on first use**, then reuse:

- **Prepare-once ASTs** (`DmInterpreter`): each rule's condition / computation's expression is parsed **lazily, exactly once** (a `lazy {}` per rule — SYNCHRONIZED, so the engine is thread-safe to reuse), then reused across every call and every row. The constructor only builds the wrappers (no parsing) → startup stays cheap, and a single-rule `testRule` forces only the one rule it touches. **This is the direct analog of the kernel's compile-once-at-model-load** — but lazy. *Caveat learned by measuring:* parsing turned out **not** to be the high-rule-count bottleneck (it didn't move the crossover), so this change is for startup + hygiene (it also removed a redundant double-parse), not the headline win.
- **Per-group row-context memo** (`Document`): `contextsFor`/`attachByDepth` did an **O(valued) attach-depth scan on every rule** (and every nested row). Since many rules share a handful of iteration scopes, and the document is immutable during a validation, this is memoized **per group inside the mutation-invalidated `Index`** — computed once per distinct scope, reused across all rules. **This *was* the lever**: ~24% faster across all rule counts, crossover pushed out ~2× (see the log). This mirrors the kernel computing the row structure once and reusing it across rules.

**Measured (2026-06-24, the log below):** the interpreter's startup is **small in absolute terms** — engine build ~1.5 ms (lazy — just the AST wrappers), first `validateFull` ~57 ms on a 48-rule model (one-time lazy index build + first parse), then steady-state. And a single document stays **linear to 300 000 rows** (~2.5 µs/row). The harness for these two goals is `PerfScalingTest` (opt-in `-Dperf.scaling=true`). **We do NOT compare this to the kernel's cold codegen** (non-negotiable #2 — the kernel pre-generates ahead of use; counting it would be the forbidden measurement). The interpreter's *not needing* codegen at all is a **capability** advantage — decisive for the native image (Groovy codegen can't go native) and one-shot CLI / JS use — not a startup-timing race. The residual at *extreme* rule counts is addressed next.

## Where the kernel wins (the crossover — the kernel team's pride is justified, 2026-06-24)

The kernel's documented architecture is **compile-once codegen + per-run caches** (`IndexFieldCache`, `ValidationCache`, `CalculationCache`, `GroupFillCache`, `RepetitionNotUniqueErrorCache` — `kernel-core-runtime/_30_8/internal/core/`), plus short-circuit optimizations (BA doc: skip comparison rules when the root group is empty; cancel quantifiers early). So its edge is the **many-rules** regime: generated straight-line code beats a tree-walker's per-node dispatch + `Val` allocation, and that per-(rule,row) overhead accumulates with rule count.

This is **measured, not assumed** — the rule-count sweep (`KernelEdgeTest`, opt-in `-Dperf.edge=true`) finds the crossover where interp/kernel passes 1.0. After the two optimizations above it sits at **~96 rules vs Groovy** (single-doc and per-eval batch), widening to ~1.2× at 192 rules. The residual is the inherent **interpreter-vs-generated-code** per-operation overhead — the honest motivation for a future **generator** (the "structure for a future generator" standing requirement): emit code per model, no tree-walk. Until then the interpreter is the right tool for the common case (a handful of rules, any document size, instant startup) and the kernel for very-rule-dense models in a hot reuse loop.

### The kernel's per-run caches → where the interpreter is naive (the optimization map, 2026-06-24)

The crossover is the *constant-factor* gap. The **steeper** danger is an **O(N²) cliff** where the kernel has a cache and the interpreter does a per-call scan. Each kernel cache (`…core.rt._30_8.internal.core`) names a pattern; the discipline is to check the interpreter's analog and *measure*, not assume. Read the kernel source **for the mechanism, not the expression** (clean-room): the *idea* — index by key, memoize group-fill — is reproduced in original Kotlin.

| kernel cache | pattern it rescues | naive cost | interpreter's analog | verdict |
|---|---|---|---|---|
| **RepetitionNotUniqueErrorCache** | index/field uniqueness over a repetition list | O(N²) pairwise | `duplicateIndexCells` uses **hash grouping** (`groupingBy`/`eachCount`) | already O(N) ✓ |
| **IndexFieldCache** | semantic-index `[F For v]` resolved per row | O(rows²) | `starRows` did an **O(all-rows) `.filter` per call** → **O(rows²)** — **measured quadratic** (growth ×3.5/doubling, the interp lead collapsing 0.17→0.66) | **was the cliff → FIXED**: `Document` now indexes rows by prefix (the clean-room `IndexFieldCache` analog); linear again, 98 ms→6.8 ms @4k rows. Guarded by `PerfGateTest.semanticIndexStaysSubQuadratic` |
| **GroupFillCache** | `GroupFilled(G)` / requiredness over a big doc | O(rows × valued) | `groupFilled`'s non-repeatable branch scans `index().valued` **O(valued) per call** | **latent cliff** for GroupFilled-heavy / many-empty-required models — same prefix/scan issue; not yet stressed-and-fixed (hint) |
| **CalculationCache** | a computed field reused by many rules | O(uses × recompute) | the interpreter computes per `compute()` call (no cross-rule reuse within a run) | minor today (compute is a separate pass); revisit if computed-field fan-out grows |
| **ValidationCache** | repeated field reads / format reconversion | per-op constant | values resolved on demand; the AST + row-context memos cover the repeated-work axis | constant-factor, not a cliff |
| **USBIndexCache** | HDV/USB code translation | O(lookups) | the interpreter does not model USB/HDV | out of scope |

Plus non-cache kernel optimizations worth noting as hints: **iterator pruning** (skip empty repetitions above a threshold) and **end-index pruning** (iterate to the max *filled* row, not the declared max) — the interpreter sidesteps both because it only materializes *instantiated* rows (no empty-row iteration), so sparsity isn't its problem.

**The honest lesson (the owner's skepticism, confirmed):** it is **not** easy to beat the kernel. Its caches each kill a real O(N²) the interpreter hits the moment a model uses that construct at scale — the semantic-index cliff was real and quadratic until the row-prefix index closed it, and at least one sibling (GroupFilled) is still latent. The interpreter wins the common case and startup, but staying on-par at scale means **systematically mirroring the kernel's runtime indexing** (clean-room), one verified cliff at a time — not assuming a tree-walker is inherently faster. Also found: the interpreter does not yet support the kernel's **field-keyed** semantic index (`[F For <field>]`, only string-literal) — a capability gap, not just a perf one.

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
- **Results & discussion.** Removes the latent requiredness O(N²). **Residual (documented hint):** the *non-repeatable* `GroupFilled` branch still scans valued fields O(valued) per call — its scoping depth varies with the rule group, so it needs a `(groupPath, group)`-keyed prefix index, deferred until a model stresses it.

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
