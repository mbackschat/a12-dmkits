# Probe task 06 — evolve an EXISTING model's rules (repeats · cross-row correlation · flag→rule promotion)

**Start:** `fixture cli/src/test/resources/models/order-ruled.dm.json` — a maintain/extend session on a model that **already has** structure, a repeatable group, and rules. (The from-scratch sessions `01`/`05` can't reach this surface: there are no pre-existing references to rewrite, no repeatable group to add per-row rules to, no flag to promote.)

The seeded model (discover it with `model describe` — never assume): a non-repeatable `/Order` with `ShippingAddress`/`BillingAddress`, a **repeatable `/Order/Items` (max 50)** carrying `Sku` (STRING) + `Count` (NUMBER), an `ExpressShipping` (BOOLEAN) flag, a `Priority` enum (`STANDARD|EXPRESS|OVERNIGHT`), `BackorderQuantity`, `TermsConfirmed` (CONFIRM), and three existing rules.

**What it probes (the hardest rule surface — none of it reachable from a clean start):**

- **rules over repeats** — a per-row iterating rule on `/Order/Items` (and the **empty-number-reads-as-0** trap on a per-row number comparison).
- **cross-row correlation / parallel indices** — "no two rows share a value" (`RepetitionNotUnique` / `$`-correlation), which the agent must *discover* and get the scope right on.
- **promotion flag → rule** — a constraint that starts as a field **property** (`required` / a flag) but gains a **dependency**, so it must become a **conditional rule** (the skill's "is this even a rule?" gate, in reverse: unconditional → property, conditional → rule).
- **reference integrity on freshly-authored rules** — a rename that must **rewrite** rules the agent just wrote.

## Anti-overfitting (shared with task 05)

Discovery, not regression: the known crashes/findings are guarded deterministically elsewhere (native-battery, the law tests) — this task hunts **new** rule-authoring friction. Prompts are **intent-level** (the agent picks operators/verbs; that choice is where friction shows). A clean run is fine; the value is any new false-success/misconception/dead-end. If it goes green-and-boring, rotate the fixture (`subscription-computed`, `order-aggregates`) or grow a turn toward an un-probed construct — don't deepen on covered ground.

## The session (multi-prompt, in order — do not look ahead; discover the model first)

**Turn 1**
> each line item must have a count greater than zero.

STOP — do not read below until this turn is complete and verified.

**Turn 2**
> no two line items may have the same SKU.

STOP — do not read below until this turn is complete and verified.

**Turn 3**
> the backorder quantity should be required. — *(then, as a follow-up once turn 3a is done:)* actually, only require it when express shipping is on.

STOP — do not read below until this turn is complete and verified.

**Turn 4**
> when the order priority is EXPRESS or OVERNIGHT, every line item must have a SKU.

STOP — do not read below until this turn is complete and verified.

**Turn 5**
> rename the line-item SKU field to ProductSku everywhere.

## Gold (matched SEMANTICALLY — the rule SHAPE, not exact text/field names)

- **T1** a **per-row** rule scoped inside `/Order/Items` (error field in the row) whose violation is `Count <= 0` — and, because an empty number reads as 0, **presence-guarded** (`FieldFilled(Count) And [Count] <= 0`), not a naked comparison that also fires on empty rows. A whole-document aggregate instead of a per-row rule is the **wrong locus** (a finding).
- **T2** a **cross-row uniqueness** constraint over `/Order/Items/Sku` (the kernel's repetition-uniqueness / `$`-correlation), scoped to the repetition list — not a single-row check.
- **T3** the requiredness ends up **conditional → a rule**, not the field's `required` property: violation `[ExpressShipping] == true And FieldNotFilled(BackorderQuantity)` (or equivalent). The interesting signal is whether the agent first set the plain `required` property (correct for "should be required") and then **promoted** it to a rule when the dependency arrived — or wrongly kept it a property (which can't express the condition).
- **T4** a rule that is **both conditional and per-row** — guarded for iteration: `GroupFilled(/Order/Items) And [/Order/Priority] In ("EXPRESS","OVERNIGHT") And FieldNotFilled(Sku)` (enum compared by stored value; the negative guarded by the row-existence check).
- **T5** the `Sku` field renamed to `ProductSku`, with the **T2 and T4 rules rewritten** to the new path (reference rewrite), and the model still kernel-valid.

## What to mine

- **Correctness vs gold** — re-read the produced model; a claimed-but-absent rule, a wrong locus (aggregate vs per-row), or a missing presence-guard = a finding.
- **The promotion judgment (T3)** — did the agent reach for `required` first then promote, or fumble between property and rule? Did the skill's "is this a rule?" gate help or mislead?
- **Iteration + correlation friction** — did `MVK_NEG_CONDITION_IN_ITERATION` / the guard requirement surface clearly? Did the agent discover the uniqueness operator, or guess? Did empty-as-0 bite on T1?
- **Rename integrity (T5)** — did the rename rewrite the freshly-authored rules, or dangle/refuse? Any reference left stale.
- **Internal tool errors** — the digest auto-RED-FLAGs an escaped exception OR a structured `RK_INTERNAL_ERROR`/`outcome:error`/exit 70.
