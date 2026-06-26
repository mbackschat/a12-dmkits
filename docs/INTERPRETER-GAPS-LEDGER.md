# interpreter — gaps & open-issues ledger

> **What this is** — the interpreter's peer of [`DEV-API-GAPS-LEDGER.md`](DEV-API-GAPS-LEDGER.md) and [`FEATURE-GAPS-LEDGER.md`](FEATURE-GAPS-LEDGER.md): the single tracking surface for **open `:interpreter` gaps, deferred items, and to-fix issues**. It exists so open work is easy to **track, report, and pick up** — and so [`INTERPRETER-FINDINGS.md`](INTERPRETER-FINDINGS.md) can be what a findings doc should be: **settled, verified knowhow** you read to warm up and not re-investigate (like `KERNEL-FINDINGS.md`), never a backlog of dispositions ("open / to fix / deferred"). Per the CLAUDE.md *"rationale is timeless; a disposition is status"* rule — the findings hold the timeless fact, this ledger holds the live status.
>
> **The lifecycle (mimics the dmkit ledgers).** A gap is born here with an `IG<n>` code + a status. When it ships, its **rationale graduates to a numbered `IF<n>` finding** in [`INTERPRETER-FINDINGS.md`](INTERPRETER-FINDINGS.md) and the row here flips to **FIXED → IFn** (kept as the graduation record). A **deferred** gap stays here with its re-open trigger; the **technical detail** of each gap lives in the cited `IF<n>` finding (the knowhow), this ledger holds only *what's left to do and why*. (The already-shipped IF20-per-cell / IF21 / IF23 / IF24 are settled facts in findings — they predate this ledger; future fixes graduate through it.)
>
> **Scope.** `:interpreter` only — the kernel-free Kotlin evaluator (parser, evaluation semantics, model loader, the differential/perf harnesses' interpreter side). dmkit (rulekit/modelkit/edit) authoring gaps go to [`DEV-API-GAPS-LEDGER.md`](DEV-API-GAPS-LEDGER.md); kernel-coverage classification stays in the MVK / RT-semantics / grammar ledgers.

---

## 1. Open (to fix)

### IG1 — condition parser rejects `../` parent-relative field references (OPEN, surfaced 2026-06-26)

**A real correctness gap the dmkit migration exposed; worked around in the differential, not yet fixed.** The kernel's canonical condition render (its `FormatterVisitor`, fork #9 — what rulekit's `Rule.render()` reproduces) writes paths **relative to the rule's group**, so a cross-level UP reference — a `/Perf/Lines/Subs`-scoped rule reading the parent Line's `Qty` — renders **`[../Qty]`** (each `..` = one group level up). The interpreter's `parse/Parser.kt` `[…]` branch handles a leading `/` (absolute — IF24) and a bare-relative ident, but has **no case for `..` segments**: `readIdent()` on `../Qty` reads nothing and `expect(']')` throws `parser: expected ']' … [../Qty]`. Same for `[../Discount]` at depth.

- **Why it stayed hidden:** every pre-migration fixture hand-wrote cross-level refs **absolute** (`[/Perf/Lines/Qty]`), which IF24 made parseable; nothing fed the interpreter the kernel's *relative* form. It surfaced the moment `PerfCatalog` authored rules as typed `Rule`s and rendered them with `Rule.render()` (group-relative) — `PerfGateTest`, `InterpreterKernelFuzzTest`, `deepNestedHavingStaysSubQuadratic` all choked.
- **Implication beyond tests:** if real kernel-authored DM-JSON stores the canonical relative form (its formatter emits it), the interpreter **cannot load a model with a cross-level rule** — needs confirming against a real stored model, but the formatter evidence says it's likely.
- **Current handling (NOT a fix):** the differential bridge `adapter.laws.TypedRules` renders conditions **absolute** (`DslRenderer().render(rule.condition())`) — the lingua franca both engines parse (interp via IF24, kernel natively) and equivalent (both resolve self/cross refs via the common prefix). It sidesteps IG1, it does not resolve it.
- **Proper fix:** extend `parseAtom`'s `[` branch (and the bare-path argument case) to consume one-or-more `../` prefixes and resolve them by walking up the rule's group path into the absolute path the resolver already handles — needs the group context at resolution time + a differential lock that `../` resolves bit-identically to the kernel. **Top interpreter follow-up.**

---

## 2. Deferred (recorded, with a re-open trigger; detail in [`INTERPRETER-FINDINGS.md`](INTERPRETER-FINDINGS.md))

| code | item | status / re-open trigger | detail in |
|---|---|---|---|
| **IG2** | `uniqueValue` per-field constraint | deferred — probe-confirmed **not DM-JSON-deserializable** (`Field.unique`/`StringType.unique` don't load; `IField` has no `unique` accessor); it rides the USB/declared-unique machinery, not a field property. No O(N²) risk outstanding (the one risk is unauthorable). Re-open if a real model authors it. | IF20 |
| **IG3** | `mandatoryGroup` (empty repeatable + `requirednessConfig{ABSOLUTE}`) | deferred — did not fire the obvious way (returned empty); not reachable via group requiredness as tried; needs deeper (likely USB/multi-form) probing. Low priority. | IF20 |
| **IG4** | `Dec` number literal compares by **identity** (no value `equals`/`hashCode`) | deferred — whole-AST equality is unreliable wherever a number literal appears (tests use field-comparison forms to avoid it). A cross-platform + BigDecimal scale-sensitivity decision; a dedicated follow-up. | IF24 |
| **IG5** | runtime-semantics extraction (generator-readiness) | tracked refactor, **not** a prerequisite — migrate the comparison/3VL/aggregate logic still inside `Interpreter` into the pure runtime layer **as touched**; `DateMath`/`Kleene`/`quantifierFires`/the op registries already moved. | IF10 |
| **IG6** | formal-error message **text** = interpreter's own wording (Option A) | deferred — the byte-identical-kernel-text path (Option B) is not built (German-only kernel text, IF3 license boundary); the *code*/pointer/severity already match the kernel. Re-open only if a consumer needs verbatim kernel message text. | IF (formal errors) / KERNEL-INTEGRATION-NOTES |
| **IG7** | custom field-type loader limitation | recorded — `InterpreterModelLoader.kindOf` maps `CustomFieldType` → `Kind.STRING`, dropping the declared `minLength`/`maxLength` + type *name* at load; validity comes only via explicit `ValidType`/`InvalidType` rules against the consumer's `PredefinedTypeRegistry`. Re-open if a consumer registers a `CustomFieldTypeService` whose bound-checking the native runtime must mirror. | IF22 |
| **IG8** | custom-ref error-entity attribution (`IRule.getCustomRefErrorEntitiesIfFilled/IfNotFilled`) | deferred boundary — refines WHICH field(s) a `CustomCondition` rule blames by filled-state (rich-message attribution only, not fire/no-fire); the interpreter attributes to the rule's errorEntity. Recorded in `InterpreterModelElementParityTest`. | IF22 |

---

## 3. Out of scope (won't build unless the trigger flips; recorded so it's not re-investigated)

- **string charset** (`zeichenNichtImZeichensatz`) — **not DM-JSON-declarable** (no serialized key) and does not fire by default (probe: tab / non-ASCII / control chars on a plain string are all clean). Detail: IF20/IF22.
- **`maxNumberOfDigitsBeforeDecimalPlace`** (`zahlZuVieleVorkommastellen`) — not DM-JSON-declarable (no serialized key). Detail: IF20.
- **over-repetition structural bound** (a row count beyond a group's declared `repeatability`) — a structural/USB concern, not a per-eval check. Detail: IF20.
- **`getRoundingMode`** on a computation — inert in the runtime (rounding is explicit via the round operators). Detail: IF22.
