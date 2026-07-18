# Incremental (Excel-style) evaluation — feasibility & deferral

> **Status: *(feasible, DEFERRED — trigger-gated, 2026-07-18)*.** A design exploration of Excel-style **incremental recomputation** for the interpreter — recompute only the dependents of a changed cell instead of the whole document on each call. The verdict is **feasible but currently unmotivated**: no in-process consumer re-evaluates a mutating document against this engine today, and the mature kernel itself deliberately chose recompute-all-per-run + within-run caches over cross-edit dirty propagation. Kept for the record + the re-open trigger. The live substrate this would sit on is [`../INTERPRETER-PERFORMANCE.md`](../INTERPRETER-PERFORMANCE.md) "Architecture direction" (the *prepared model*, compile-once/run-many).

## 1. The question

Spreadsheet engines keep a precedents/dependents graph + a calculation chain; when a cell changes they dirty-flag the dependent closure and recalc only that, in topological order. Could the interpreter do the same — given a change (or a diff of two inputs), re-evaluate only the rules/computations that transitively depend on the changed cells, instead of re-running the whole document?

## 2. How the interpreter evaluates today: recompute-all-per-call

Every `validateFull` / `compute` call re-runs the whole thing:

- **`Document` is immutable during a validation, with a *mutation-invalidated* index.** A cell change produces a **new** `Document` (functional / copy-on-write: `add(...)`, `withComputed(overrides)`) and drops the entire lookup index (`cachedIndex = null`) — **invalidate-and-rebuild, not incremental patch**.
- **Within one call there's memoization** — the `Document.Index` (`(path,reps)→placement`, `groupPath→rows`, per-group row-context memo) and the computation `overlay`. All of it is **per-run** and discarded when the call returns.
- **There's already a computation dependency graph — but per-call and computations-only.** `ComputationEngine.computationsInDependencyOrder()` is a fresh DFS topological sort over the "this computation reads another computed field" relation (with cycle detection), rebuilt every call. Rules and input fields are **not** in a graph.
- **Dependencies are dynamic (read-driven), not static.** The engine is explicit that poison propagation is *"read-driven … there is no static dependency pre-skip"* — short-circuit (`And`/`Or`) means which fields a rule actually reads depends on the values.

So today: parse (lazily, once) → build index → compute all computations in dependency order into an overlay → evaluate all rules → report. No state survives between calls; dmtool runs one shot per process.

## 3. What "Excel-style incremental" would map to

| Excel | This engine |
|---|---|
| cell | input field cell `(path, reps)` |
| formula cell | a **computation** (computed field) or a **validation rule** |
| precedents/dependents graph | field → {computations, rules that read it}; computed → computed edges |
| dirty flag + calc chain | mark dependents dirty on edit; re-eval the dirty transitive closure, reuse cached outcomes for the rest |
| volatile functions / conditional refs | short-circuit + read-driven deps (the precedent set is value-dependent) |

## 4. Feasibility: structurally yes — but the cost is *exactness*

**Structurally feasible, and some substrate exists** — the computation DAG, the side-effect-free `overlay` (computed→computed edges), read-driven read tracking, and the **IF117 eval-state seam** (which already separates the reusable engine from per-call state). It would be a build on existing bones, not a rewrite.

**But the hard parts are domain-specific and dominate the effort:**

1. **Exactness is the bar, not "close enough."** Interpreter rule #1 is bit-for-bit kernel parity; an incremental result must be *identical* to a full re-eval. The project's own template for this class of work is **IG59** (the deferred overlay-column cache): flagged as *"a perf feature with a real correctness surface (a stale column → wrong results, a #1-rule violation), not a behavior-neutral extraction … pick it up with its perf case + a compute differential, not as a drive-by."* Incremental recalc is that, at much larger scale.
2. **Read-driven / short-circuit dependencies.** A precedent set changes with values (a flipped `And` branch reads different fields). You must capture *actual* reads per eval and conservatively re-run when any captured precedent changes — or re-validate the dependency itself. (Excel solves this; it is known-hard.)
3. **Aggregate fan-out.** `Sum*` / `Count` / `Min` / `Max` over a repetition list depend on **every row** → any single-row edit dirties the aggregate and everything downstream. On aggregate-heavy models the "dirty closure" is most of the model, so the savings collapse.
4. **Granularity must be per-`(rule, repetition-index)`.** To actually save work, dirty-tracking has to be per *row instance*, not per rule — finer-grained than Excel's flat grid, over nested repeatable groups.
5. **Poison / cascade + 3VL suppression** are transitive and read-driven; a change can flip a poison state that cascades — more state the incremental engine must track exactly.
6. **Row add/remove** (vs value-only edits) is strictly harder — already the open question in [`../INTERPRETER-PERFORMANCE.md`](../INTERPRETER-PERFORMANCE.md) "Architecture direction" (values-only ⇒ the structure index is reusable; rows change ⇒ rebuild the value store, reuse only the prepared model).

## 5. The decisive point — who benefits, and the precedent

- **dmtool CLI: zero benefit.** Stateless, one-shot, ~37 ms native. This is the **same** reason `dmtool serve` was rejected ([`../FEATURE-GAPS.md`](../FEATURE-GAPS.md) §4) — incremental recalc needs a long-lived, mutating in-process document, which the CLI never has.
- **Standalone library: the only candidate** — a **form runtime / interactive editor** holding one document and re-validating on each edit. That's a real A12 shape, but: **(a)** no such consumer is adopting *this* interpreter today; **(b)** full re-eval is already **linear and competitive-to-faster than the kernel** ([`../INTERPRETER-PERFORMANCE.md`](../INTERPRETER-PERFORMANCE.md)), and `validatePart` (relevance-scoped) already cuts work; **(c) the kernel itself — the incumbent, years-optimized form engine — does *not* do cross-edit incremental.** Its architecture is *compile-once + **per-run** caches* (`ValidationCache` / `CalculationCache` / `IndexFieldCache` / `GroupFillCache`) + short-circuit — i.e. recompute-all-per-run with within-run memoization, plus relevance-scoping. So unlike the SMT case, there is **no incumbent-parity pressure**: the mature engine deliberately chose *not* to.

## 6. Decision & sequencing

**DEFER.** The right next step — already on the roadmap and the necessary substrate — is *not* dirty-propagation:

1. **Prepared model (compile-once / run-many)** — [`../INTERPRETER-PERFORMANCE.md`](../INTERPRETER-PERFORMANCE.md) "Architecture direction" already names the exact use case (*"user edits a value → validate/compute → feedback → repeat — the model is stable while values change"*): `val engine = DmInterpreter(model)` once, loop `engine.validate(values)`. This amortizes parse + iteration-scope precompute across full evals — the 80% win for the form loop, **low correctness risk**, and it is the layer any incremental scheme sits on. Profile-gated.
2. **True incremental (dirty-cell propagation) rides on top of that**, and stays deferred until **both**: (i) a stateful consumer (a form runtime) actually adopts the library, **and** (ii) profiling shows full-re-eval-per-edit is the bottleneck *despite* the prepared model + `validatePart`. When both fire, budget the bulk of the work as **exactness** (per-row granularity, aggregate fan-out, poison cascade), locked by a validate/compute differential exactly as IG59 demands.

**Re-open trigger (explicit):** a stateful interactive consumer adopts the standalone interpreter library AND profiling shows per-edit full-re-eval (with the prepared model + relevance-scoping already in place) is the measured bottleneck. Until then this is a performance feature for a consumer that does not exist against this engine, and one the mature kernel itself skipped.
