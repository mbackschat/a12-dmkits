# Interpreter performance — method, benchmark catalog, regression gate

The `:interpreter` must be **fast on the hard cases**, and that is **measured and gated**, not hoped for. This doc is the durable method; the decision behind it is `CLAUDE.md` "Interpreter … Performance" (decided 2026-06-23). Measured baselines live in a dated results log (below), not here.

## The decision (three non-negotiables)

1. **REAL documents only — benchmarking a shortcut is cheating.** Measure the production evaluation path: the general `Document` (interpreter) and the kernel's `IDocument`, both built from **real DM-JSON models**. The spike's `Map<String,String>`-backed `RepDoc` (O(1) lookups, one-pass `star`) must **never** be the benchmarked object — it sidesteps the exact machinery (parallel index, common-prefix resolution, star aggregation) that is hard to make fast, so its numbers are a fiction. If a benchmark doesn't exercise real group resolution, it isn't a benchmark.
2. **Measure AGAINST the kernel, as a regression gate.** The perf benchmark is the **twin of the correctness differential** and lives in `:adapter` (the only module with the kernel). For each scenario: build one instance, run BOTH `kernel.validateFull`/`compute` and `DmInterpreter.validateFull`/`compute` over it, record both wall-clocks + the ratio, and assert (a) **correctness** (interp == kernel) and (b) a **time/ratio bound** that trips on an O(N²)-class regression. The interpreter should be within a sane constant factor of the kernel — ideally faster (no Groovy, no codegen).
3. **Target the non-obvious group machinery, at scale.** Not flat single-row rules — the cases that bite: **deeply nested repeatable groups**, **parallel iteration** / cross-level references, **starred aggregates** over large lists, and **many rows (up to 100 000)**. *Invest heavily in all things groups.*
4. **Run on the PURE engine, decoupled from the CLI — it must be CHEAP to run, very often.** Both the correctness differentials and the perf harness call the **`DmInterpreter` + `Document` API directly** (kernel via `RuntimeLaws`), NOT through `dmtool`/`InterpreterEngine`/`InterpreterModelLoader`. No process spawn, no `installDist`, no JSON envelope, no native build — just in-process JUnit (and `jsTest`). "Real document" means the real `Document` *data structure* with real nested-group shape, built **programmatically in-test**, not loaded through the CLI. The CLI's loader is a separate, thinner concern with its own small test; the engine's semantics + performance are measured at the engine, so the loop stays fast enough to run on every change.

## Why this matters (the cheating we caught)

The encouraging spike timing (`InterpreterMultiplatformTest.scalesToManyRepetitionRows`, ~30 ms / 20k rows) runs on `RepDoc`, whose field lookup is a `HashMap.get` and whose `star` is one pass — **O(N) overall**. The **production** path is the general `Document`, which stores placements in a `List` and does a linear `placements.firstOrNull{…}` scan on **every** `field`/`filled`/`displayValue`/`groupFilled` (and `star` is O(rows×P)) → **O(N²)** per-row evaluation. So the green spike number says nothing about the real path. That gap is exactly what this method closes: benchmark the real `Document`, against the kernel, at scale.

## The harness (invest here — it pays off)

Not ad-hoc `measureTime` calls sprinkled in tests — a **proper, reusable harness**, because it does double duty: it **gates** regressions *and* it's how we **find** the O(N²) hotspots in the group machinery. Design:

- **`PerfScenario`** — the unit: a real DM-JSON model + an **instance generator parameterized by scale `N`** (build 1k/10k/100k rows *programmatically*, never commit giant fixtures) + the operation (`validateFull` / `compute`) + the correctness oracle. One scenario, run by both engines.
- **Two engine adapters behind one interface** — `KernelEngine` (via `RuntimeLaws` → `IDocumentRtService`) and `InterpreterEngine` (`DmInterpreter`). The harness lives in `:adapter` (the only module with the kernel), mirroring the `laws/` differential layout, under `…/perf/`.
- **Measurement discipline** — per (scenario, N): **warm up** (discard JIT-warming iterations), then time `K` iterations; report **median** (robust) + min + p90, and the **ratio** `interp_median / kernel_median`. Never time a cold run.
- **Correctness first** — assert `interp == kernel` on the scenario *before* trusting any timing (a fast-but-wrong path is not a win; reuse the differential comparison).
- **Two run modes** — (1) **gate mode**: loose bounds, CI-friendly, fails on a catastrophic/relative regression; (2) **profile mode**: one scenario at high `N`, verbose per-phase stats, no assert — for hunting the hotspot (JFR/async-profiler-friendly loop on JVM).
- **JVM tool choice** — start with this **custom integration harness** (whole-document evaluation is the real unit; warmup+repeats+median+ratio is the right rigor for it). Reserve **JMH** for a *specific hot inner loop* (e.g. `repsFor`/`starRows`) that needs sub-method attribution — add it only when a profile points there, not upfront.
- **JS throughput** — a Node-runnable large-`N` check on `jsTest` (the same `Document`, `measureTime`, no kernel) so the native/JS reason-to-exist is perf-gated too.
- **Output + baselines** — print a `scenario × N × interp_ms × kernel_ms × ratio` table and append machine-tagged baselines to the results log (below). The spec holds the method; the log holds the numbers.

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
2. **(in progress)** B1/B2 (flat + single-aggregate): the **pure-engine** real-`Document` scaling is landed (`DocumentScaleTest`, JVM+JS, baselines above); the **against-kernel** comparison (build-once kernel pipeline in `:adapter`, larger N) is the next step.
3. Add B3/B4/B5 (the nested/cross-level/deep-star group cases) — the heart of "invest in groups."
4. B6/B7 (whole-model + compute at scale), then the JS throughput checks.
5. Wire the gate into CI once baselines are stable.

## Results log

*(dated baselines, newest first — populated as benchmarks land)*

**2026-06-23 — first slice: pure-engine real-`Document` scaling** (`interpreter/commonTest/DocumentScaleTest`, the honest counterpart of the RepDoc benchmark; kernel-free, JVM+JS). 3000 `/Perf/Lines` rows:

| scenario | JVM | JS (Node) |
|---|---|---|
| B1 per-row rule (`[Qty] > 50` iterating Lines) | ~65 ms | ~210 ms |
| B2 star aggregate (`Sum(Lines*/Amount) > 0`) | ~82 ms | ~210 ms |

Linear, both well under the JS 2s test-timeout — the regression guard for the #28 indexing (a quadratic `Document` blows the JS timeout at this N; that's exactly why N=20k JS failed and was dropped to 3k for the cross-platform test). **Not yet measured: against the kernel, and group-heavy/nested at 100k** — that's the JVM `:adapter` harness (next #29 increment), which has no 2s test cap.
