---
name: a12-rules
description: Author and validate A12 Kernel validation rules with the rulekit CLI. Use when a user (often a document modeller or business analyst) wants to add, check, or understand a validation rule on an A12 document model. Covers the rule envelope, the error-scenario polarity, field-path references, per-row iteration, and the explore→compose→check loop.
---

# Authoring A12 validation rules with rulekit

You help author **A12 validation rules** on a document model, using the `rulekit` CLI and its bundled operator catalog. You don't need A12 background docs — the catalog + the CLI's kernel-checked feedback are enough if you follow the rules below.

## Your tools

- **The CLI** — `cli <verb> …` (run `cli` with no args for the verb list). The verbs you'll use:
  - `cli describe <model.json>` — the model card (fields, kinds, enum values, groups, repeatability).
  - `cli dump <model.json> fields|groups|rules` — greppable JSONL; `cli dump <model.json> card` — the skeleton.
  - `cli check <model.json> --field <ABSOLUTE field path> --condition "<DSL>" --code <ID>` — submit a candidate rule and get the **real kernel's** verdict: `{"valid": true|false, "diagnostics": [...]}`. This is your ground truth.
  - `cli validate <model.json>` — re-check a model's *existing* rules.
- **The operator catalog** (`operators.json`) — the reference for every operator: meaning, operand types, examples, and **gotchas** (read these — they often name the exact fix for a rejection).

## The loop

1. **Orient** — `cli describe` / `cli dump` to learn the fields, their kinds, enum values, and which groups repeat.
2. **Pick operators** — from the catalog, by meaning.
3. **Compose** the condition — minding **polarity**, **paths**, and **iteration** below.
4. **Check** — `cli check …`. If `valid:true`, done. If not, read each diagnostic.
5. **Iterate** — the diagnostic `code`+`summary` name the problem; look the operator up in the catalog for the fix; adjust and re-check.

## ⚠️ Polarity — the single most important thing

**A rule's condition is TRUE when the document is INVALID.** It describes the *error scenario* (the violation), **not** the requirement. There is **no `Not` operator** — instead, pick the *negative-form* predicate.

So to enforce a requirement, write its **violation**:

| Requirement | ✅ condition (the violation) | ❌ common mistake (the opposite rule) |
|---|---|---|
| "X must be provided" | `FieldNotFilled(X)` | `FieldFilled(X)` |
| "amount must be ≤ 1000" | `[X] > 1000` | `[X] <= 1000` |
| "at least one of A/B set" | `NoFieldFilled(A, B)` | `AtLeastOneFieldFilled(A, B)` |

**The kernel accepts both polarities** (both are valid conditions), so `check` returning `valid:true` does **not** mean your polarity is right — only that the syntax/types are. Always re-read your condition as "this is true exactly when the document is *wrong*."

## Field-path references

A condition is evaluated relative to the rule's **group** (its iteration scope — defaults to the error field's parent group).

- **Bare name** = a field in that scope: `FieldNotFilled(MonthlyFee)`.
- **Absolute path** for a field in another group: `[/Customer/Status]`.
- **Comparisons** bracket the value reference: `[Quantity] > 0`, `[/Customer/Status] == "ACTIVE"`. **Predicates** take bare refs: `FieldNotFilled(Quantity)`.
- You can compare **field-to-field**, not just field-to-literal — bracket both: `[EffectiveFee] < [BaseFee]`.
- **Strict vs inclusive**: map the wording carefully. "lower than / below / more than / exceeds" → strict (`<` / `>`); "at least / no less than / at most / no more than" → inclusive (`<=` / `>=`). And remember the *violation* is the opposite of the requirement: requirement "must be **at least** base" (`>= base`, valid) → violation `< base`.
- **Enums compare by stored value**, not the display label: `== "ACTIVE"`, not `== "Active"`. (`cli describe` lists the stored values.)

## Per-row iteration & the negative guard

- Putting the **error field inside a repeatable group** makes the rule fire **once per row** ("each …"). That happens automatically — you don't ask for it; you choose the error field.
- A **negative** condition (`FieldNotFilled`, `NoFieldFilled`, …) inside an iterating rule is **rejected** (`MVK_NEG_CONDITION_IN_ITERATION`) unless guarded by a positive existence check on the row: `GroupFilled(<the repeatable group>) And <your negative condition>`.

## Worked example (a *different* model, to show the pattern)

Requirement: *"When an order's Channel is EXPRESS, each line item's DeliveryDate must be provided."*
Model has enum `/Order/Channel` (values `STANDARD, EXPRESS`) and a **repeatable** group `/Order/LineItems` with field `DeliveryDate`.

- Error field (drives per-row iteration): `/Order/LineItems/DeliveryDate`.
- Violation = the row exists **and** channel is EXPRESS **and** the date is missing — guarded because it iterates and uses a negative:
  ```
  GroupFilled(/Order/LineItems) And [/Order/Channel] == "EXPRESS" And FieldNotFilled(DeliveryDate)
  ```
- Confirm:
  ```
  cli check order.json \
    --field /Order/LineItems/DeliveryDate \
    --condition "GroupFilled(/Order/LineItems) And [/Order/Channel] == \"EXPRESS\" And FieldNotFilled(DeliveryDate)" \
    --code EXPRESS_ITEM_NEEDS_DELIVERY_DATE
  # → {"valid": true, "diagnostics": []}
  ```

Apply the same shape to your own model: find the enum + the repeatable group with `describe`, choose the error field for the per-row scope, write the **violation**, guard it if it iterates with a negative, then `check`.

## Reading a rejection

`check` returns diagnostics with a `code` and `summary`. The frequent ones:
- `MVK_NEG_CONDITION_IN_ITERATION` → your iterating rule has an unguarded negative; add `GroupFilled(<group>) And …`.
- `MVK_INVALID_ENTITY` → a path doesn't resolve; bare names are relative to the iteration scope, cross-group fields need an absolute `[/…]` path.
- `MVK_UNEXPECTED_TOKEN` → usually a comparison missing brackets (`[Field] > 1000`) or a malformed expression.
- `MVK_INVALID_TYPE_FOR_COMPARISON` → ordering (`<`/`>`) needs numbers or dates; compare strings/enums with `==` / `!=`.

For anything else, look the operator up in the catalog — its `gotchas` and `fix` fields usually say exactly what to do.
