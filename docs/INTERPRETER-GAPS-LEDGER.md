# interpreter — gaps & open-issues ledger

> **What this is** — the interpreter's peer of [`DEV-API-GAPS-LEDGER.md`](DEV-API-GAPS-LEDGER.md) and [`FEATURE-GAPS-LEDGER.md`](FEATURE-GAPS-LEDGER.md): the single tracking surface for **open `:interpreter` gaps, deferred items, and to-fix issues**. It exists so open work is easy to **track, report, and pick up** — and so [`INTERPRETER-FINDINGS.md`](INTERPRETER-FINDINGS.md) can be what a findings doc should be: **settled, verified knowhow** you read to warm up and not re-investigate (like `KERNEL-FINDINGS.md`), never a backlog of dispositions ("open / to fix / deferred"). Per the CLAUDE.md *"rationale is timeless; a disposition is status"* rule — the findings hold the timeless fact, this ledger holds the live status.
>
> **The lifecycle (mimics the dmkit ledgers).** A gap is born here with an `IG<n>` code + a status, under the section that matches its status. When it ships, its **rationale graduates to a numbered `IF<n>` finding** in [`INTERPRETER-FINDINGS.md`](INTERPRETER-FINDINGS.md), and its row **moves out of §1 Open into §4 Shipped** as a one-line `IGn → IFn` pointer (kept so `IGn` code refs still resolve and the graduation is traceable). **A FIXED item never stays under "§1 Open (to fix)" — moving it is part of shipping the fix.** A **deferred** gap stays in §2 with its re-open trigger; the **technical detail** of each gap lives in the cited `IF<n>` finding (the knowhow), this ledger holds only *what's left to do and why*. (The already-shipped IF20-per-cell / IF21 / IF23 / IF24 are settled facts in findings — they predate this ledger; future fixes graduate through §4.)
>
> **Section invariant (the placement rule):** each numbered section holds exactly one status — **§1 = open, §2 = deferred, §3 = out-of-scope, §4 = shipped**. A code appears in exactly one. So §1 must read "(none)" whenever no gap is actually open; a FIXED/graduated entry under §1 is a bug in *this doc*, not a record.
>
> **Scope.** `:interpreter` only — the kernel-free Kotlin evaluator (parser, evaluation semantics, model loader, the differential/perf harnesses' interpreter side). dmkit (rulekit/modelkit/edit) authoring gaps go to [`DEV-API-GAPS-LEDGER.md`](DEV-API-GAPS-LEDGER.md); kernel-coverage classification stays in the MVK / RT-semantics / grammar ledgers.

---

## 1. Open (to fix)

*(none currently open.)* Shipped gaps move to §4; deferred ones live in §2.

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

---

## 4. Shipped (graduated to findings)

> Kept as one-line graduation records so `IG<n>` code refs (in comments/tests) still resolve and the fix stays traceable; the full rationale lives in the cited `IF<n>` finding. A row lands here the moment its fix ships — it is **not** left under §1.

| code | item | shipped → finding |
|---|---|---|
| **IG1** | condition parser rejected `../` parent-relative cross-level field refs | **FIXED 2026-06-26 → [IF25](INTERPRETER-FINDINGS.md)** — `readRef`/`readParentUps`/`walkUp` in `parse/Parser.kt`; parse-time resolution to the identical absolute AST (no eval/perf change); locked by `interpreter.ParentRelativePathParseTest` + the canonical-relative-render differential (`adapter.laws.TypedRules`) |
