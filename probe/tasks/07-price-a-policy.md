# Probe task 07 — price an insurance policy (computation depth · filter/Having · decimals/units · computed chaining)

**Start:** `clean` (from-scratch — the agent builds the policy model with `dmtool model new`).

Complements the suite where it is thinnest. `03` probes computations only *shallowly* (one computation, add/modify); `05` is structure/locale/roles; `06` is rules-over-repeats. **None** reach the **filter `$`/`Having`** operator, **`FirstFilledValue`** / multi-alternative computations with a `commonPrecondition`, **decimals/scale + number units**, or a **computation that reads another computed field** (chaining). This task is a calculation-heavy session that exercises exactly that cluster. Own neutral domain (insurance, no tax/gov; distinct from 03's subscription, 05's person, 06's order).

**What it probes (the cluster `03`/`05`/`06` miss):**

- **aggregate inside a computation** — a computed total = `Sum(Coverages*/Premium)` over a repeatable group.
- **`FirstFilledValue` / multi-alternative computation + `commonPrecondition`** — a derived value chosen from a priority list of conditional alternatives.
- **the filter operator / `Having`** — an aggregate folded over a *subset* of rows (`Sum(… Having [Type] == "LIABILITY")`), plus the error-field-referenced law (probe F05-1, re-tested in the wild).
- **decimals/scale + number units** — money as 2-decimal amounts, a percentage 0–100.
- **computed-field chaining** — a computation that references another computed field (does the kernel resolve a computed operand? a discovery either way).

## Anti-overfitting (shared with 05/06)

Discovery, not regression: known fixes are guarded deterministically elsewhere (the law tests, `CatalogExamplesAreKernelValidTest`, `DiagnosticEnricherTest`). Prompts are **intent-level** — the agent chooses the operators/verbs (`FirstFilledValue` vs nested alternatives, `Having` vs a separate filtered field, how to express a percentage), and that choice is where DX friction shows. A clean run is fine; the value is any **new** false-success / misconception / dead-end. If it goes green-and-boring, rotate the domain (e-commerce pricing, a loan schedule) or grow a turn toward an un-probed construct — don't deepen on covered ground.

## The session (multi-prompt, in order — do not look ahead)

**Turn 1**
> create a policy model with a policyholder and a repeatable list of coverages, each with a type and a premium amount. The total premium is the sum of all coverage premiums.

STOP — do not read below until this turn is complete and verified.

**Turn 2**
> the applied discount is the loyalty rate when the holder is a member, otherwise the seasonal promo rate, otherwise nothing.

STOP — do not read below until this turn is complete and verified.

**Turn 3**
> the total of the liability-type coverage premiums must not exceed the policy's liability cap.

STOP — do not read below until this turn is complete and verified.

**Turn 4**
> premiums are euro amounts with two decimals, and the discount is a percentage between 0 and 100.

STOP — do not read below until this turn is complete and verified.

**Turn 5**
> the net premium is the total premium minus the discount.

## Gold (matched SEMANTICALLY — the SHAPE, not exact text/operators)

- **T1** a repeatable `Coverages` group (`Type` enum, `Premium` number) and a non-repeatable computed `TotalPremium` whose operation aggregates `Sum(Coverages*/Premium)`. The agent should reach for a **computation** (not a rule) for a derived value.
- **T2** a derived discount via **`FirstFilledValue`** over alternatives (member→loyalty, promo-season→promo) with a fallback, **or** a multi-alternative computation each guarded by a `commonPrecondition`/per-alternative condition. The interesting signal: does the agent discover `FirstFilledValue` (or compose conditional alternatives), and does it get the precondition polarity right?
- **T3** a **rule** (not a computation) whose violation is `[LiabilityCap] < Sum(Coverages*/Premium Having [Coverages/Type] == "LIABILITY")` (or equivalent) — a **filtered aggregate**, with the **error field referenced** (the cap it compares against — F05-1: a model-level aggregate's error field must appear in the condition). A naked `Sum(...) > <cap-literal>` on an unreferenced error field is the F05-1 trap (a finding if it recurs).
- **T4** `Premium`/`TotalPremium` as **scale-2** numbers (optionally a currency **unit**); the discount as a number with **min 0 / max 100** (a percentage). Field properties, not rules (the skill's "is this even a rule?" gate).
- **T5** a computed `NetPremium` referencing the **computed** `TotalPremium` minus the discount — exercises whether a **computation can read another computed field**. If the kernel rejects a computed operand, that is itself a finding; if it resolves, note the ordering/dependency handling.

## What to mine

- **Correctness vs gold** — re-read the produced model; a claimed-but-absent computation/rule, a wrong locus (rule where a computation belongs, or vice versa), or a missing filter = a finding.
- **`FirstFilledValue` / alternatives (T2)** — discovered or fumbled? Did the precondition polarity (a computation alternative fires when its condition is TRUE, unlike a rule's violation) confuse the agent?
- **Filter / `Having` (T3)** — did the agent find `Having`? Did the error-field-referenced law (F05-1) bite, and did the now-generic `MVK_ERROR_FIELD_NOT_REFERENCED` / `MVK_NO_WILDCARD` guidance get it unstuck?
- **Decimals/units (T4)** — declared as field properties, or wrongly as rules? Any scale/`MVK_INVALID_COMPARE_DEC_PLACES` surprise.
- **Computed chaining (T5)** — does a computation referencing another computed field validate? Any dependency-order or self-validation surprise.
- **Internal tool errors** — the digest auto-RED-FLAGs an escaped exception OR a structured `RK_INTERNAL_ERROR` / `outcome:error` / exit 70.
