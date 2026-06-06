---
name: a12-rules
description: Author and validate A12 Kernel validation rules with the rulekit CLI. Use when a user (often a document modeller or business analyst) wants to add, check, or understand a validation rule on an A12 document model. Covers the rule envelope, the error-scenario polarity, field-path references, per-row iteration, and the explore→compose→check loop.
---

# Authoring A12 validation rules with rulekit

You help author **A12 validation rules** on a document model with the `rulekit` CLI. The CLI **describes itself** — you explore it rather than memorizing it — so this skill carries only the **judgment the tool can't give you** (polarity, the traps, the kernel's laws). You don't need A12 background docs: the CLI's self-description + its kernel-checked feedback are enough if you follow the rules below.

## Your tools

The CLI is **self-describing** — explore it:

- `cli --help` — the verb list; `cli <verb> --help` — a verb's parameters and what they mean; `cli manifest` — the same, machine-readable.
- `cli operators` — the DSL operator catalog (each operator's meaning, operands, examples, and **gotchas** — read the gotchas, they often name the exact fix for a rejection); `cli operators <id>` — one in full.
- `cli schema <verb>` — the JSON a verb emits or expects.

The three you lean on:
- **`cli describe <model.json>`** — orient: fields, kinds, enum values, groups, repeatability.
- **`cli operators`** — pick operators by meaning.
- **`cli check <model.json> --field <ABSOLUTE field path> --condition "<DSL>" --code <ID>`** — submit a candidate and get the **real kernel's** verdict `{"valid": …, "diagnostics": […]}`. This is your ground truth. (`cli validate <model.json>` re-checks a model's *existing* rules.)

## The loop

1. **Orient** — `cli describe` / `cli dump` to learn the fields, their kinds, enum values, and which groups repeat.
2. **Pick operators** — from `cli operators`, by meaning.
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

- **Bare name** = a field in the rule's scope **or an ancestor group** — the kernel searches *up* the hierarchy: `FieldNotFilled(MonthlyFee)`, and from a rule scoped to `/Subscription/Addons`, `[Tier]` resolves up to `/Subscription/Tier` (no `../` or absolute path needed for an ancestor's field).
- **Absolute path** for a field in a *different branch* (or just to be explicit): `[/Customer/Status]`.
- **Comparisons** bracket the value reference: `[Quantity] > 0`, `[/Customer/Status] == "ACTIVE"`. **Predicates** take bare refs: `FieldNotFilled(Quantity)`.
- You can compare **field-to-field**, not just field-to-literal — bracket both: `[EffectiveFee] < [BaseFee]`.
- **Strict vs inclusive**: map the wording carefully. "lower than / below / more than / exceeds" → strict (`<` / `>`); "at least / no less than / at most / no more than" → inclusive (`<=` / `>=`). And remember the *violation* is the opposite of the requirement: requirement "must be **at least** base" (`>= base`, valid) → violation `< base`.
- **Enums compare by stored value**, not the display label: `== "ACTIVE"`, not `== "Active"`. (`cli describe` lists the stored values.)

## Empty values in a comparison

How an **empty** (unspecified) field behaves in a comparison depends on its type — this catches people out:

| Field type | Empty value in a comparison |
|---|---|
| **number** | substituted with **`0`** — so `[Amount] < 100` **fires** on an empty Amount (0 < 100) |
| **confirm** | treated as **`False`** |
| string · date · boolean · enum | the comparison is **not evaluated** (it doesn't fire, no error) |

So **guard a number comparison** when an empty value shouldn't trip it — `FieldFilled(Amount) And [Amount] < 100`. (`cli check` flags the unguarded case for you as `RK_UNGUARDED_NUMBER_COMPARISON`.) Two corners: the `0` substitution does **not** apply to `Min`/`Max` (empties are ignored there), and there are no empty strings, so `[F] == ""` is never true — use `FieldNotFilled(F)` to test for absence.

## Per-row iteration & the negative guard

- Putting the **error field inside a repeatable group** makes the rule fire **once per row** ("each …"). That happens automatically — you don't ask for it; you choose the error field.
- **"Each X must …" is a per-row rule — don't recast it as one document-level count/aggregate.** The error field's location *is* the decision: a field inside the repeatable group → the rule fires per offending row and points at *that* row. Rewriting (e.g.) "each line item needs a ShippedDate" as a single whole-document check over `Lines*/ShippedDate` (a count, or `NotAllFieldsFilled(Lines*/ShippedDate)` on a top-level field) is a **different rule** — it fires once for the whole document and flags the wrong locus. When the requirement says "each", keep the error field in the row.
- A **negative** condition (`FieldNotFilled`, `NoFieldFilled`, …) inside an iterating rule is **rejected** (`MVK_NEG_CONDITION_IN_ITERATION`) unless guarded by a positive existence check on the row: `GroupFilled(<the repeatable group>) And <your negative condition>`.
- **Guard row existence with `GroupFilled(<the repeatable group>)`, not `FieldFilled(<some sibling field>)`.** A sibling field can be empty while the row exists, so an arbitrary-field guard quietly changes *which* rows the rule covers — `GroupFilled` is the row-presence check.

## Aggregates over a repeatable group

When a rule reasons about **all the rows at once** (not one row), use an **aggregate** over the flattened group — `Group*/Field` (the `*` takes every row's value):

- **`Sum(Lines*/Amount)` sums the field's *values*.** This is **not** the same as *counting* rows — `Sum` adds the numbers; to count how many fields are filled use `NumberOfFilledFields(...)`. Mixing these up is a common slip: "total of the amounts" is `Sum`, "how many items" is a count.
- `Min` / `Max` / count-style aggregates exist too — look up the exact names/operands with `cli operators`.
- An aggregate **is a number**, so compare it like one: `Sum(Lines*/Amount) > 500`. To compare against another field instead of a literal, bracket the field: `[Cap] < Sum(Lines*/Amount)`.
- **`Having` filters which rows are folded:** `Sum(Lines*/Amount Having [Lines/Type] == "FEE")` sums only the fee lines.
- An aggregate rule is **model-level** (it spans rows), so put its error field on a **non-repeatable** field — it does **not** iterate per row.

Example — *"the total of all FEE lines must not exceed 500"* (an invoice with repeatable `/Invoice/Lines`):
```
cli check invoice.json --field /Invoice/Total \
  --condition "Sum(Lines*/Amount Having [Lines/Type] == \"FEE\") > 500" --code FEE_TOTAL_TOO_HIGH
```

## Dates

Date operators need **both operands present** and have a **fixed argument order** — get both right:

- **Guard presence first.** A date function on a missing date is a formal error; lead with `AllFieldsFilled(DateA, DateB) And …`.
- **`DifferenceInDays(A, B)` = B − A** (same for `DifferenceInMonths` / `DifferenceInYears`): **positive when B is *later* than A.** So:
  - "B is *before* A" → `DifferenceInDays(A, B) < 0`
  - "B is more than N days *after* A" → `DifferenceInDays(A, B) > N`
- Other date ops (`Today`, `AddYears`, …) — see `cli operators`.

Example — *"a ticket must be resolved within 5 days of being raised"* (dates `/Ticket/RaisedDate`, `/Ticket/ResolvedDate`):
```
cli check ticket.json --field /Ticket/ResolvedDate \
  --condition "AllFieldsFilled(RaisedDate, ResolvedDate) And DifferenceInDays(RaisedDate, ResolvedDate) > 5" \
  --code RESOLVED_TOO_LATE
```

## Custom conditions (host-delegated)

`CustomCondition <Name>` is an **escape hatch**: the named check runs in the host application's code, **not** in the rule language — its logic is *not visible in the model*. Two rules:

- **Don't guess what it decides.** Reading a rule that uses one, name it as a host-delegated check ("delegates to the app-defined `CreditApproved` check") and stop — inventing its meaning from the name is wrong.
- **Polarity is unchanged** (see above): like any condition it is part of the *violation*, so the rule fires (document **invalid**) when the whole `errorCondition` is **true** — not when it's false. `CustomCondition` references no field, so pair it with one to cover the error field: `FieldFilled(Applicant) And CustomCondition CreditApproved`.

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

For anything else, look the operator up with `cli operators <id>` — its `gotchas` and `fix` fields usually say exactly what to do.
