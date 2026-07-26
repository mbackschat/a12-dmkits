# Seed generator & differential fuzz net — spec

Two coupled pieces that share one engine:

1. **The model-driven seed generator** (`SeedGenerator`, package `…dm.interpreter.gen`) — given a document model, it produces a **best-effort, model-derived sample candidate** document instance. Requested row counts are structurally bounded by each declared repeatability, but local value generation does not prove whole-model acceptance. Kernel-free and multiplatform, so the same code serves three consumers.
2. **The differential fuzz net** (`adapter.perf.InterpreterKernelFuzzTest`) — built on the generator: it generates many random documents and asserts the **interpreter agrees with the real kernel** on every one. This is the strongest gate for the interpreter's #1 rule — *match the kernel exactly* ([INTERPRETER-FINDINGS](INTERPRETER-FINDINGS.md) IF0).

Around these sit a few **companion harnesses** that make the exact-match loop fast: a **kernel probe battery** (learn the kernel's behavior *before* implementing), **fuzz divergence localization** (pinpoint *which* row/code diverged), and a **catalog-parity meter** (guard against unhandled operators). They're documented in "[Companion harnesses](#companion-harnesses--the-probe-first-loop)" below.

This doc is the **stable method**. The generator's *consumers* have their own homes — [INTERPRETER-PERFORMANCE.md](INTERPRETER-PERFORMANCE.md) for the perf harness, the `dmtool model seed` verb ([CLI-SPEC.md](CLI-SPEC.md) §5). Which operators the interpreter handles is the **catalog-parity meter** (`adapter.laws.CatalogParityTest`, build-checked — 109/109), not a hand-maintained map here.

## Why a generator at all

Hand-writing document fixtures to test the interpreter is the obvious approach — and it is exactly what the per-operator `*DiffTest`s do (one pinned case each). But hand-picked cases only catch the divergences you *think* to write. The exact-match bar needs the opposite: **random combinations a human would not pick** — odd row counts across nested groups, mixed fill/empty/malformed patterns, colliding index keys, an aggregate over a list that happens to be empty. To generate those *and feed the same document to both engines*, you need one generator that builds a document once and can target either backing — that is the generator's reason to exist.

It is **best-effort, model-derived** on purpose: the structure comes from the model (placements are addressable and row counts stay within declared repeatability) and the default per-field value is in-format for the field's kind — but this is **not** a whole-model validity guarantee because `ValidValues` is a per-field local heuristic. A test that wants value invalidity (the fuzz net does) layers it on top via a value strategy.

## The data flow

```
                       ModelDef (the model: kinds, repeatable groups, scales, formats, index fields)
                          │
                          ▼
   ValueStrategy ───►  SeedGenerator.populate(sink, seed, rowsOf, value)
   (value per field)      │   walks the group tree; per repeatable group asks rowsOf(group, parentReps, rnd)
   RowsFn ────────────►   │   plans + checks every row count before the first sink mutation
   (rows per group)       │
   Exact leaf quota ───►  SeedGenerator.populateExactLeafRows(sink, leafGroup, totalLeafRows, …)
                          │   distributes one exact scale over the target's repeatable ancestor chain
                          ▼
                    PlacementSink                 ← the seam: one build, any backing
                    ├─ Document.asSink()          → the interpreter's general Document      (:interpreter)
                    ├─ KernelSink(RuntimeLaws.Doc)→ a kernel IDocument                       (:adapter, has the kernel)
                    └─ JsonInstanceSink           → a DM-JSON sample candidate              (dmtool model seed, :cli)
```

Each placement carries **one 1-based repetition index per path part** (group *and* field) — the exact addressing the interpreter's `Document` and the kernel's `IDocument` both use, so the sink is a thin pass-through on each side.

## The seed generator

`SeedGenerator(model: ModelDef)` precomputes the group tree (child groups, fields-per-group, roots) from the field paths + declared repeatable groups. Then:

```kotlin
gen.populate(
    sink,                       // a PlacementSink (interpreter Document / kernel Doc / JSON)
    seed = 0L,                  // RNG seed → fully deterministic output
    rowsOf = { group, parentReps, rnd -> … },   // rows to instantiate per repeatable group
    value  = ValidValues,       // a ValueStrategy: the value for each field (null = leave empty)
)

gen.populateExactLeafRows(
    sink,
    leafGroup = "/Orders/Lines/Parts",
    totalLeafRows = 100_000,
    unrelatedRows = 1,
)
```

| Concept | Contract |
|---|---|
| **`PlacementSink`** | `group(path, *reps)` · `field(path, value, *reps)` · `emptyField(path, *reps)`. The only thing the generator emits into. |
| **`RowsFn`** = `(group, parentReps, rnd) -> Int` | How many rows to instantiate for a repeatable group under a given parent row. A non-repeatable group always gets exactly one row. Counts may vary per parent and per run (the RNG is passed in), must be non-negative, and may not exceed a present `declaredReps` cap; an absent cap is unbounded. |
| **Exact leaf-row scale** | `populateExactLeafRows` produces exactly the requested number of instances of one selected repeatable group. It uses the target's complete repeatable ancestor chain, chooses the minimum legal row count at each level, and splits each parent's remaining quota by balanced quotient/remainder. Repeatables outside the chain use the configurable `unrelatedRows` default (normally `1`). |
| **`ValueStrategy`** = `(field, reps, rnd) -> String?` | The value for one field instance; **`null` leaves it empty** (present-but-unfilled). |
| **`ValidValues`** (the default strategy) | A **best-effort, constraint-aware** value for each `Kind` (a per-field local heuristic, **not** a whole-document validity guarantee): NUMBER respects its declared **`min/maxFractionalDigits` interval**, sign (never a signed zero — that is a formal error), and **`min/maxValue` range** (draws a declared bound, exercising both ends across seeds), STRING respects its declared **`min/maxLength`**, dates/times/ranges render in the field's format, a CONFIRM is `true` or empty only. A STRING **`pattern`** is cheaply satisfied (always verified against the real regex) or the field is **left empty** (the sanctioned leave-empty fallback) — the one case `ValidValues` returns `null`. Staying inside a field's declared constraints keeps the kernel's per-cell formal checks from firing and *suppressing the rules a differential means to exercise* (WI-7 / S2). Otherwise never empties a field — compose a wrapper for that. |

**Structural refusal is atomic at the sink boundary.** `populate` first plans the complete variable-count topology, rejects a negative count or any count above that group's declared repeatability, and only then emits placements. This includes a late sibling and a nested group's second parent row: neither can leave an earlier valid branch in the sink. A missing `declaredReps` entry means that repeatable group is unbounded. The fixed Java/CLI overload validates every explicit/default count up front and rejects an explicit path that is not a declared repeatable group.

The exact-scale overload applies the same atomic boundary before emission. It rejects an unknown or non-repeatable target, negative quota/default, an unrelated default above its own cap, and a target quota above the product capacity of its repeatable chain. It computes capacities with saturation rather than materializing the future tree, then the ordinary complete topology plan and existing value/emission machinery do the work. This makes `N` an exact legal workload even when no single group allows `N` rows per parent.

**Determinism is the contract that makes the dual-engine differential work.** `populate` walks in a fixed order and owns independent deterministic row-topology and value streams derived from the same seed, so two calls with the same generator instance and seed produce the **identical** document without speculatively generating values during structural preflight. The fuzz net relies on this: it materializes seed *s* into the interpreter and into the kernel from the same generator, and any divergence is therefore a real semantic difference, not a generation artefact — and a failing seed is exactly reproducible.

**Kernel-free + multiplatform.** `…interpreter.gen` imports no kernel/Groovy/`java.*` (the `verifyInterpreterPurity` lint enforces it), so the generator rides the native image and the JS artifact. That is what lets the *same* generator back the shipped `dmtool model seed` verb on the native binary — the kernel is touched only by the fuzz net's oracle, which lives in `:adapter`.

## The differential fuzz net

`InterpreterKernelFuzzTest.randomValidDocumentsAgreeWithKernel` is the gate:

```
for seed in 0 until 100:
    doc_s = generate(PerfCatalog.fuzz, seed)            # one document, two materializations
    assert interpreter.firedCodes(doc_s) == kernel.firedCodes(doc_s)   # identical sorted multiset
```

It collects **all** divergences across the 100 seeds (not fail-fast) and reports them together, each as `seed=… kernel-only=… interp-only=…`, so one run triages every distinct mismatch. The comparison is the **sorted fired-code multiset** (same codes *and* per-row firing counts) from `DmInterpreter.validateFull` vs the kernel's `IDocumentRtService.validateFull` (via `RuntimeLaws.kernelEngine`).

### The fuzz model is deliberately diverse

The fuzz uses its **own** model (`fuzz.dm.json`, an own-domain e-commerce cart) — a shape the perf model lacks, chosen to stress the dimensions a flat model can't:

- **Every value kind**: STRING, NUMBER (scale 0 *and* scale 2), an **ISO date**, a **non-ISO `dd.MM.yyyy` date** (locale-variant canonicalization), BOOLEAN.
- **Two nesting levels**: `/Cart/Lines` → `/Cart/Lines/Parts`, both repeatable, both with an **index field** (`ItemId`, `PartId`).
- **A broad rule set** — one rule per construct, so a single generated document exercises many operators at once: a per-row comparison, date extractors over both date fields, an aggregate (`Sum`), `FirstFilledValue`, `NumberOfValueInFields`, division/power, `Abs`/`Length`, the `Round*` family, a nested per-part rule, and a cross-level (common-prefix) reference.

### The value strategy is where the fuzzing happens

`PerfCatalog.fuzz()`'s `ValueStrategy`, keyed off the seed:

| Draw | Outcome | Stresses |
|---|---|---|
| 30% | **empty** (null) | requiredness (`mandatoryField` on empty index fields), empty-as-0, skip-empty aggregates |
| of the filled, 15% | **deliberately malformed** for the formal-checked kinds (NUMBER: `abc` / `-0` / `+5` / ` 5 `; DATE: garbage or the *wrong* locale format) | formal-error detection + rule suppression, in both engines |
| index fields | drawn from a **small pool** (`K0`..`K2`) | value **collisions** → the auto `uniqueIndex` check + its branch-scoped suppression |
| otherwise | a valid value (`ValidValues`) | the normal path |

Row counts are random per group (`Lines` 0–6, `Parts` 0–4), so list lengths — and whether an aggregate sees an empty list — vary across seeds.

### Why it is the exactness multiplier

A hand-written `*DiffTest` proves one operator behaves on one input. The fuzz net proves **the operators compose correctly under inputs no one curated** — and it has paid for itself repeatedly. Track record (each found by the net, root-caused, fixed clean-room, and locked with a dedicated `*DiffTest`):

| # | Divergence the fuzz surfaced | Fix |
|---|---|---|
| 1 | NUMBER sign formals: **signed-zero `-0`** / **leading-plus `+5`** treated as valid | `FormalError` + check-ordering (`NumberSignFormalDiffTest`) |
| 2 | **plain-date locale**: a `dd.MM.yyyy` value parsed as ISO | format-aware `DateMath.parseDateToIso` (`PlainDateDiffTest`) |
| 3 | a malformed value in a numeric combiner **crashed** the interpreter | total parsing (`decOrNull`) + combiner Unknown-propagation (`AggMalformedDiffTest`) |
| 4 | **`-0` parses but is a formal error** → an aggregate folded it as 0 and fired where the kernel suppresses | `Document.resolveValue` gates on `formalError`, not parseability (`UniquenessDiffTest`/`74052407`) |
| 5 | a **presence check** (`FieldFilled`) on a present-but-malformed field returned TRUE and fired, but the kernel's `isWertAngegeben` is UNKNOWN for a not-check-relevant field | the 3-valued `Interpreter.filledTri` + the quantifier `(t,f)` tally (`CurrentRepetitionDiffTest`/`QuantifierMalformedDiffTest`) |

(The colliding-key pool additionally drove index-field `uniqueIndex` + its suppression to parity.)

## Extending the net — the per-operator loop

Adding an operator follows one loop (the standing per-operator build method):

> **probe the kernel → implement clean-room → pin a `*DiffTest` → add a rule to the fuzz model → commit**

The fuzz step is one line: add a `FuzzRule(group, code, errorRel, condition)` to the list in `PerfCatalog.fuzz()` using only supported operators and a **referenced** error field, and the 100-seed sweep starts exercising it in combination with every other rule. Guard any rule that would otherwise fire on the empty-as-0 row with `FieldFilled(...)` (the kernel rejects a rule that can fire on a wholly-empty row). Keep the fuzz model's own-domain fixture realistic (the license-safe own-domain rule applies).

## How to run

```
# the fuzz net (the exactness gate) — fast, ~seconds
./build.sh :adapter:test --tests "io.github.mbackschat.a12.dm.adapter.perf.InterpreterKernelFuzzTest"

# the seed generator's own proof — the focused JVM run (the JS side rides the unfiltered :interpreter:check)
./build.sh :interpreter:jvmTest --tests "io.github.mbackschat.a12.dm.interpreter.SeedGeneratorTest"
```

A failing fuzz seed prints `seed=N kernel-only=… interp-only=…`; reproduce it in isolation by pinning that seed, then write the minimal `*DiffTest` that captures the divergence before fixing (red/green).

## Relationship to the perf harness and the seed verb

- **Perf harness** ([INTERPRETER-PERFORMANCE.md](INTERPRETER-PERFORMANCE.md)) — the *same* `SeedGenerator` + `PlacementSink` build the perf scenarios' instances into both engines; the fuzz net is the **correctness twin** of the perf differential (one asserts equality of outputs, the other measures time, both over generated real documents). The deterministic strategies there drive a known firing fraction; the fuzz strategy here is random.
- **`dmtool model seed` verb** — shipped. The generator is placed kernel-free so the verb wires portable `ModelWorkspace` collection/assembly → strict expanded-input loading → `SeedGenerator.populate(JsonInstanceSink)` → serialization on the native image: workspace includes are mounted, imported typedefs become effective field types, ids are normalized to name paths, and a residual unmapped reference is refused rather than silently degraded. It then produces a best-effort model-derived sample candidate for the prepared model. The `PlacementSink` seam is the extension point — a third backing, no generator change.

## Companion harnesses — the probe-first loop

The generator + fuzz are **reactive** (catch a divergence *after* you implement). Around them sit harnesses that make the exact-match loop faster and tighter. The loop, end to end:

```
   ┌─ A. probe the kernel ──► learn the semantics (empty/malformed/boundary/sparse) BEFORE coding
   │
   ├─ implement clean-room  ──► the operator + its *DiffTest (the pinned cases)
   │
   ├─ B. fuzz + localize ───► random docs catch what you didn't pin; localize the failing row/code
   │
   └─ C. parity meter ──────► a guard that fails when a catalog operator is unhandled / un-fuzzed
```

| Harness | What it does | Status |
|---|---|---|
| **A · Kernel probe battery** (`adapter.laws.KernelProbe`) | Inject a candidate condition + run the REAL kernel over a battery of edge documents; report per-scenario *fires? / message type / all fired codes*. Learn the semantics **before** implementing (the `CurrentRepetition` miss — shipped wrong because I guessed instead of probing). | **Built** |
| **B · Fuzz divergence localization** (`adapter.laws.FuzzDiagnostics`) | On a failing seed, diff the **(code, pointer)** pairs (not just codes) so the output names the *row* + code that diverged; + a delta-debug **shrink** to the minimal failing doc. | **Built** |
| **C · Catalog-parity meter** (`adapter.laws.CatalogParityTest`) | A test that **fails** when an `operators.json` construct is unhandled by the interpreter (or un-fuzzed) — turns "what's left" into a derived checklist + a silent-gap guard. | **Built** |
| **D · Full-message differential** (`adapter.laws.FullMessageFuzzDiffTest`, via `FuzzDiagnostics.signaturesAgree`) | Deepen the fuzz beyond firing: assert the full signature **code\|type\|pointer** agrees (NOT text — we diverge on text by design, [INTERPRETER-FINDINGS](INTERPRETER-FINDINGS.md) IF3), so a wrong **polarity** or **row** trips it. Caught the `CurrentRepetition`/`FirstFilledValue` mis-typed OMISSION (117/200 seeds — KERNEL-FINDINGS §1/§3 supplement). | **Built** |
| **E · Model fuzzing** (`adapter.perf.ModelFuzz` + `ModelFuzzDiffTest`) | Vary the **model**: random-depth (2..4) nested repeatable models, valid-by-construction via modelkit `DocumentModelDraft.toJson()` + a matching `ModelDef`, then fuzz docs and assert the full signature `code\|type\|pointer` agrees — the **deep-nesting** space the fixed 2-level fuzz model can't reach. Surfaced the structural over-repetition check (`zuGrosseZeile`/`zuGrosseKontextnummer`) as an interpreter scope boundary (KERNEL-FINDINGS). | **Built** |
| **F · Computation-cascade fuzz** (`adapter.perf.ComputationFuzz` + `ComputationFuzzDiffTest`, via `FuzzDiagnostics.computeOutcomesAgree`) | Fuzz the **compute** path (every other net fuzzes *validation* only): random chains of scalar computations where each `C{i}` references `C{i-1}` (a guaranteed cascade at random depth) over a non-repeatable `/Calc` group, asserting the computed-field outcome multiset agrees with the kernel. Closes the blind spot that let a silent cascade bug (a dependent reading its dependency's stored-empty operand, not its computed value) survive when one hand-picked case was the *only* compute test. | **Built** |
| **G · Sibling-group fuzz** (`adapter.perf.SiblingGroupFuzz` + `SiblingGroupFuzzDiffTest`) | Produce **two sibling repeatable groups** (`A` ⊥ `B` under a common root) — the shape no other net generates (they do nested *chains* or one fixed model), and the shape the silent parallel-iteration bug hid in. Two regimes: **supported** rules (single-group per-row, or cross-sibling **aggregates** that collapse the sibling) must agree with the kernel at the full-message level (multi-sibling coverage — incl. the aggregate-error-on-a-repeatable-field pointer, previously untested); **parallel-iteration** rules (a per-row reference into BOTH siblings — the index-keyed outer join) must **fail loud** (interp reports the rule unsupported, emits no message) while the kernel fires — hardening the loud-not-wrong contract beyond one hand-picked case, and the acceptance gate for the join when it lands. | **Built** |

### A — the kernel probe battery

`KernelProbe` (in `:adapter`, kernel-only) answers *"what does the kernel actually do for this condition, across the edge cases?"* in one call — so probe-first is the path of least resistance, not a chore. The recurring shape (a per-row / aggregate / iteration operator over a repeatable field) has a ready battery:

```kotlin
val scenarios = KernelProbe.rowPatterns(valid = "5", malformed = "abc").map { (name, rows) ->
    name to KernelProbe.rowsDoc("/Cart", "/Cart/Lines", itemId, qty, rows)   // 0/1/2/3 rows × empty/valid/malformed
}
println(KernelProbe.report(model, "/Cart", "FF", "../Lines/Qty", "FirstFilledValue(Lines*/Qty) > 3", scenarios))
```

prints a table whose columns are *scenario · fires · message-type · all fired codes* — so you read, in one shot, that empties are skipped, a malformed first-filled **suppresses** (the rule goes quiet while a `zahl…` formal appears), and a leading empty types the firing OMISSION while a trailing empty types it VALUE. `KernelProbeTest` exercises the harness on `FirstFilledValue` (CI-green, anti-rot) and is the copyable usage example; **when probing a new operator, clone its shape, read the report, then write the interpreter + its `*DiffTest`.** It is a *tool*, not a gate — the gates stay the `*DiffTest`s + the fuzz.

**The battery is also a differential over EXISTING operators** (`OperatorBatteryDiffTest`): the same row matrix run through BOTH engines, asserting agreement per scenario. The hand-picked `*DiffTest`s pin curated cases and the fuzz hits the matrix randomly — this sweeps it *systematically*, so an edge gap can't hide between the curated cases. It is the deterministic counterpart that would have caught the `CurrentRepetition` malformed-operand gap (the `malformed, valid` row is in the battery). Reach for it to re-verify an operator whenever its semantics are touched, and as a cheap "are there gaps?" sweep over the implemented set.

### B — fuzz divergence localization + shrink

When the fuzz catches a seed, `FuzzDiagnostics` turns the codes-only verdict into the row-level "why". `localize(scenario, seed)` re-runs the seed and diffs the **(code, pointer)** pairs (both engines render the pointer the same `formatPointer` way), so the output reads `kernel-only=[DV@/Cart/Lines[1, 3, 1]] interp-only=[]` — the exact row and code, not a guess. `shrink(scenario, seed)` then **delta-debugs** the generated document — greedily dropping repeatable-group rows that aren't needed to keep the divergence (its predicate is the *same* sorted-code multiset the fuzz uses) — leaving a minimal failing instance to read. Both are wired into `InterpreterKernelFuzzTest`'s failure message, so a future divergence is self-describing. `FuzzDiagnosticsTest` exercises them on a deliberately-divergent scenario (anti-rot). This is what replaces the "reproduce the failing seed by hand to find the row" step that the `CurrentRepetition` divergence cost.

### C — the catalog-parity meter

A and B make the *implement* loop fast; C guards the *what's-left* — it turns operator coverage from a hand-maintained checklist into a **derived build guard**. `CatalogParityTest` reads three always-current sources — the catalog (`OperatorCatalog`/`operators.json`, the universe of kernel constructs), what the interpreter **handles** (the live `PredicateOp`/`ConstantOp`/`FunctionOp` enums + a small explicit set of AST-level constructs the parser dispatches directly: `And`/`Or`, the comparisons, the arithmetic umbrella, the pattern tests, the boolean literals), and what the **fuzz net** exercises (`PerfCatalog.fuzzConditions()`) — and asserts:

- **every catalog construct is classified exactly once** — *handled*, *deferred* (the `DEFERRED` map, which IS the operator backlog, each entry reasoned), or *excluded* (`CustomCondition` — host-delegated, not a pure-engine op). An **unclassified** id fails the build: the kernel added an operator, or one shipped unrecorded. A construct in *two* buckets fails too — a handled op still tagged deferred is the **stale-classification** rot the owner rules warn about.
- **every name we classify is a real catalog id** — catches a typo or a kernel-renamed/removed op still referenced.
- **every handled callable is in the fuzz net or explicitly fuzz-pending** — so a newly-handled op can't ship without random-combination coverage *or* a recorded reason (`fuzzPending`, which shrinks as fuzz rules are added). A pending entry that is now fuzzed fails as stale, and a fuzz rule referencing an unhandled construct fails (it would crash at eval).

The match is on `name(` with a **left word-boundary**, so neither a prefix (`Sum(` ∉ `SumOfProducts(`) nor a suffix (`Date(` ∉ `YearFromDate(`) false-matches. `coverageMeter` prints the snapshot — `N constructs: X handled, Y deferred, Z excluded` + the fuzz fraction. Same "mine constraints proactively, CI-locked" discipline as the MVK / RT-semantics / grammar ledgers: source-of-truth proposes (the catalog grows, an enum gains a member), the guard confirms. The `DEFERRED` map replaces the prose "what's left" list — it can't go stale because the build checks it.
