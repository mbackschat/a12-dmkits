# Probe task 03 — subscription billing computation (add, then re-express via modify)

**Source / gold:** structure inspired by the committed `subscription-computed.dm.json` (a subscription with a `Billing` section + a derived fee computation); the specific computation here is this task's own prose gold (own domain — SaaS billing; no tax/gov).
**Probes:** the **computation** surface — the least-probed in the prior runs. `computation add` (the value-expression `operation`, the computed-field-self-reference ban, message coverage) and **`computation modify`** (re-express an existing computation in place), plus an **aggregate over a repeatable group** (`Sum(Addons*/MonthlyFee)`). The `computation add`/`modify` paths recently gained the en_US-keyword default and the value-shape gate — this exercises both end-to-end.

Run as ONE session, prompts arriving in order — the agent must NOT look ahead or optimize across turns.

## The session (multi-prompt, in order)

**Turn 1**
> Create a document model for a software subscription. It has a tier, a billing section with a base monthly fee and an effective monthly fee, and a repeatable list of add-ons, each with a name and a monthly fee. The effective monthly fee is calculated as twice the base fee.

**Turn 2**
> Change the effective-fee calculation so it is twice the base fee plus the total of all the add-ons' monthly fees.

## Gold reference (what a correct end-state enforces — matched SEMANTICALLY, not literally)

After **turn 1**:
- a root `Subscription` group with a `Tier` (enum or string), a **`Billing`** sub-group with a `BaseFee` (NUMBER) and an `EffectiveFee` (NUMBER), and a **repeatable `Addons`** group with `Name` (STRING) + `MonthlyFee` (NUMBER);
- a **computation** on `EffectiveFee` whose operation is **`[BaseFee] * 2`** (or `2 * [BaseFee]` — any kernel-valid equivalent), with a message covering every declared locale;
- the computation does **not** reference its own computed field (`MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD` if it does);
- kernel-valid.

After **turn 2** (the re-express, via **`computation modify`** — not delete-and-re-add):
- the **same** computation now computes `EffectiveFee = [BaseFee] * 2 + Sum(Addons*/MonthlyFee)` (or any kernel-valid aggregate of the add-on fees — e.g. `Sum(Addons*/MonthlyFee)` added to the doubled base);
- still kernel-valid; the change is an in-place modify (the computation keeps its identity), not a drop-and-recreate.

*(Any field metadata / messages the agent set must actually be present — no false `applied`. A wrong-shaped spec value must be refused (`RK_INVALID_VALUE`), not silently coerced.)*

## Objective correctness check (vs gold)
Re-read the final model with the (real) CLI: confirm the computation's stored `operation` is `[BaseFee] * 2` after turn 1 and the base-doubled-plus-addon-sum form after turn 2 (read → re-render → compare), that it targets `EffectiveFee`, and that the model is kernel-valid. A claimed-but-absent change = a false success / silent drop finding.

## What to mine in the trajectory (from the objective shim log)
- **Correctness vs gold** (above) — the anchor; catches false successes / silent drops, especially on the **modify** path (did the re-expressed operation actually land?).
- **Computation-specific friction** — did `schema computation add` / `operators` make the `operation` expression + the aggregate-over-repeatable discoverable? Did the agent reach for `computation modify` or wrongly drop-and-re-add?
- **Efficiency & friction** — the quantified breakdown (purpose buckets + verb histogram + attribution + an efficient-run baseline); did it batch the known build into `apply`?
- **Misconceptions · Diagnostic quality · Self-describing-surface gaps** — as in `01`.
- **Concrete tool-defect findings** — ranked, actionable → root-cause fixes + guards.

## Report
Embed the produced model JSON verbatim for objective review; record the audit verdict + the digest; investigate every finding to its root cause.
