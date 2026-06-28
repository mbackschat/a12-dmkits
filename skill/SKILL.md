---
name: a12-dmtool
description: Author and validate A12 Kernel document models with the dmtool CLI — both a model's structure (fields, groups, type definitions, includes, config) and its validation rules. Use when a user (often a document modeller or business analyst) wants to create, edit, check, or understand an A12 document model — add or change fields and groups, factor out reusable includes, refactor structure, or author validation rules on it. Covers model creation, the structure edits and refactors (extract/move/rename), the rule envelope and error-scenario polarity, field-path references, per-row iteration, and the explore→compose→check loop.
---

# Authoring A12 document models with dmtool

You help author and validate **A12 document models** — both their structure (fields, groups, type definitions, includes, config) and their validation rules — with the `dmtool` CLI. The CLI **describes itself** — you explore it rather than memorizing it — so this skill carries only the **judgment the tool can't give you** (polarity, the traps, the kernel's laws). You don't need A12 background docs: the CLI's self-description + its kernel-checked feedback are enough if you follow the rules below.

## ⛔ First — is this even a rule? Prefer a field/group property

A rule is the **most expensive** way to state a constraint: a condition + error code + per-locale messages, plus (inside a repeatable group) a `GroupFilled` guard. Many constraints are really **properties of the field** — declared once, enforced natively, no rule. **Before composing any condition, check this:**

| The constraint is really… | Declare it as a field property | Not this rule |
|---|---|---|
| "X must be provided" | the field's **`required`** property (a key in its spec: `field add` / `field modify`). In a repeatable group `required` means *required per present row* — it **replaces** a whole `GroupFilled(G) And FieldNotFilled(X)` rule | ~~`FieldNotFilled(X)`~~ |
| a number within a range | the field's **min/max** | ~~`[X] > max`~~ |
| a string in a fixed format | the field's **regex** | ~~a pattern rule~~ |
| a value from a fixed set | an **enum** field | ~~a value-list rule~~ |

`dmtool patterns` marks these field-level alternatives explicitly. **Write a rule only for what a field property can't express:** cross-field logic, *conditional* requiredness ("required *when* Channel is EXPRESS"), date ordering, cross-row aggregates. Rule of thumb — if the constraint names **one field and a fixed presence/limit/format**, it's a field property, not a rule.

## Your tools

The CLI is **self-describing** — explore it:

- `dmtool --help` — the verb list; every command is `dmtool -m <model.json> <target> <op>` (the model is set once with `-m`, before or after the verb); `dmtool <target> <op> --help` — that op's parameters and what they mean; `dmtool manifest` — the same, machine-readable (each verb's `target`/`op` + params).
- `dmtool operators` — the DSL operator catalog (each operator's meaning, operands, examples, and **gotchas** — read the gotchas, they often name the exact fix for a rejection); `dmtool operators <id>` — one in full.
- `dmtool schema <target> <op>` — the JSON an op consumes/returns (and `dmtool schema result` — the universal output envelope every verb emits).

The three you lean on:
- **`dmtool -m <model.json> model describe`** — orient: fields, kinds, enum values, groups, repeatability.
- **`dmtool operators`** — pick operators by meaning.
- **`dmtool -m <model.json> rule check --field <ABSOLUTE field path> --condition "<DSL>" --code <ID>`** — submit a candidate and get the **real kernel's** verdict (the result envelope's `valid` + `diagnostics`). This is your ground truth. (`dmtool -m <model.json> model check` statically re-checks a model's *existing* rules + computations.)

**⛔ Inspect and edit a model ONLY through `dmtool`'s structured verbs — never read or hand-edit the raw `.dm.json`, and don't fall back to `export` to inspect, either.** To *see* a model: `model describe` (structure + kinds + enum values) and `field read` / `rule read` (one element in full — `field read` echoes the field's metadata **and its per-kind config**: number min/max/unit/scale, string pattern/length/`patternMessage`, enum values, date/time formats — so you can confirm a constraint actually persisted). To *change* it: the edit verbs. **`export` is NOT an inspection tool** — it dumps the model's raw DM-JSON for *emitting* it (saving, handing to A12 Tools); eyeballing an export dump to understand a model is the same anti-pattern as `cat`-ing the file, because you're reading the kernel's internal format that `describe`/`read` exist to abstract. The structured reads already show everything you'd look for — if one seems to omit something you need, that's a bug report, not a `cat` or an `export`.

**Batch the edits you already know into one `apply`/`batch` call.** Binary startup is sub-100 ms (no warmup to amortize — so don't batch for *that*), but **every call is a round-trip *you* pay for**: reading the output and reasoning before the next one. So once you know several edits — fields, rules, an include mounted twice — land them in a single `apply`/`batch` (also **atomic**: all-or-nothing, rolled back on any failure) rather than one verb at a time. Keep single calls for **exploring and checking** (`describe`/`operators`/`rule check`), where you genuinely need each result before deciding the next.

**Going bilingual is the classic case to batch.** Adding a second locale (`config modify --add-locale de_DE`) makes the kernel require, in the new locale: every **enum value's** labels (else `MVK_INTERNAL_VALUES_AND_DISPLAY_VALUES` — *"display value and XML value not specified together"*), every label you set on a **new** field, a message on **every existing rule/computation** — not just the ones you're editing (a rule left with only its old-locale message → `MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING`), **and** every **patterned string field's pattern message** (a `pattern` field's `patternMessage` left in only the old locale hits the *same* `MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING` — the per-locale form is the `patternMessages` map). The `--add-locale` itself is *rejected* until all of these are present (so it's all-or-nothing). **Scope it precisely: run `config modify --add-locale <loc> --dry-run` first** — it lists the *exact* per-element gaps this model actually has (which rules/enums/patterned fields are missing the new locale), so you top up only those instead of guessing from the list above (the enum-label and pattern-message items apply only if the model has them). Pre-existing single-locale *field* labels are the one exception (tolerated — labels, unlike the error texts above, aren't required per-locale), so the requirement is asymmetric and surprising. Do the `--add-locale` **and** the per-enum-value label top-ups, any new fields' bilingual labels, **the new-locale message on every existing rule** (`rule read` with no arg lists them), **and a `field modify` re-supplying `patternMessages` for the new locale on every patterned string field** in **one** `apply` (you can't set a new-locale message before `--add-locale` declares it, so they must ride the same atomic apply), so the single terminal gate sees a consistent model instead of rejecting a half-bilingual one.

**Naming model files — match the basename to the model `id`.** A12 Tools requires a model's **file basename to equal its model name (the header `id`)**, and it does **not** strip the suffix — so the suffix is part of the *id*, not just a file decoration. When you create a model, give `--id` and `-o` the **same** stem: `model new --id Order_DM -o Order_DM.json`. The A12 Tools convention is `<Name>_DM` for a document model, `<Name>_TDM` for a type-definition model (some repos use a lowercase `.dm.json` with a bare id — match whatever the workspace already uses). The trap the importer flags: `--id order` written to `Order_DM.json` (id ≠ basename). dmtool itself resolves references by `id`, never by filename — but the A12 modeler gates basename == id, so keep them equal. When you `group extract`, the new sub-model is written as `<reference>.dm.json` in the include-dir — take its exact path from the result's `subModel` field; a guessed `<reference>.json` won't load.

**If `dmtool` is `command not found`:** the plugin bundles an installer next to this skill — run it to download the binary on demand: `bash "${CLAUDE_SKILL_DIR}/ensure-dmtool.sh"` (that variable is this skill's own directory on Claude Code; on Codex run the `ensure-dmtool.sh` that sits beside this `SKILL.md`). It fetches the per-OS native build, checksum-verifies it, and prints a line like `dmtool ready: <absolute path>`. **Use that printed path to invoke `dmtool`** for the rest of the session — a script you run mid-session can't reliably add it to `PATH`. **Don't build it from source or fetch it any other way.** (Where `dmtool` already runs, none of this applies — just use it.)

## The loop

1. **Orient** — `dmtool -m <model> model describe` to learn the fields, their kinds, enum values, and which groups repeat (the structured view; not `export`, which only dumps the raw model).
2. **Pick operators** — from `dmtool operators`, by meaning.
3. **Compose** the condition — minding **polarity**, **paths**, and **iteration** below.
4. **Check** — `dmtool -m <model> rule check …`. If `valid:true`, done. If not, read each diagnostic.
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

> ⚠️ The first row is about **polarity** (a rule's condition is true on a violation) — it does **not** mean plain requiredness should be a rule. Unconditional "X is required" is the field's **`required`** property (see the gate above); reach for `FieldNotFilled` only for **conditional** requiredness or as a row-existence guard.

## Field-path references

A condition is evaluated relative to the rule's **group** (its iteration scope — defaults to the error field's parent group).

- **Bare name** = a field in the rule's scope **or an ancestor group** — the kernel searches *up* the hierarchy: `FieldNotFilled(MonthlyFee)`, and from a rule scoped to `/Subscription/Addons`, `[Tier]` resolves up to `/Subscription/Tier` (no `../` or absolute path needed for an ancestor's field).
- **Absolute path** for a field in a *different branch* (or just to be explicit): `[/Customer/Status]`.
- **Brackets `[…]` mark a field used as a *value*** — a comparison operand: `[Quantity] > 0`, `[/Customer/Status] == "ACTIVE"`. **Anything inside a function/predicate/aggregate's parentheses is a BARE ref**, never bracketed: `FieldNotFilled(Quantity)`, `Sum(Items*/Amount)`, `DateRange(OrderDate, DeliveryDate)`, `StartOfDateRange(CoverageWindow)`. Bracketing a call's argument is a parse error, not extra safety.
- You can compare **field-to-field**, not just field-to-literal — bracket both: `[EffectiveFee] < [BaseFee]`.
- **Strict vs inclusive**: map the wording carefully. "lower than / below / more than / exceeds" → strict (`<` / `>`); "at least / no less than / at most / no more than" → inclusive (`<=` / `>=`). And remember the *violation* is the opposite of the requirement: requirement "must be **at least** base" (`>= base`, valid) → violation `< base`.
- **Enums compare by stored value**, not the display label: `== "ACTIVE"`, not `== "Active"`. (`model describe` lists the stored values.)
- **Booleans/confirms compare to the capitalized `True`/`False`** — `[Active] == True`, **not** the JSON `true`/`false` (a lowercase `true` is a parse error, `MVK_UNEXPECTED_TOKEN`). A **confirm** field compares only to `True` (`== True` / `!= True`); `[Sig] == False` is rejected (`MVK_INVALID_COMPARE_TO_YES`) — a confirm is checked-or-not, so test the unchecked side with `!= True` (or `FieldNotFilled`).

## Empty values in a comparison

How an **empty** (unspecified) field behaves in a comparison depends on its type — this catches people out:

| Field type | Empty value in a comparison |
|---|---|
| **number** | substituted with **`0`** — so `[Amount] < 100` **fires** on an empty Amount (0 < 100) |
| **confirm** | treated as **`False`** |
| string · date · boolean · enum | the comparison is **not evaluated** (it doesn't fire, no error) |

So **guard a number comparison** when an empty value shouldn't trip it — `FieldFilled(Amount) And [Amount] < 100`. (`rule check` flags the unguarded case as `RK_UNGUARDED_NUMBER_COMPARISON` — but **direction-aware**: only where the empty `0` would actually fire it, so `[Amount] < 100` is flagged while `[Amount] > 1000` is not (`0 > 1000` is false); the silence on the latter is correct, not a miss.) Two corners: the `0` substitution does **not** apply to `Min`/`Max` (empties are ignored there), and there are no empty strings, so `[F] == ""` is never true — use `FieldNotFilled(F)` to test for absence.

## Per-row iteration & the negative guard

- Putting the **error field inside a repeatable group** makes the rule fire **once per row** ("each …"). That happens automatically — you don't ask for it; you choose the error field.
- **"Each X must …" is a per-row rule — don't recast it as one document-level count/aggregate.** The error field's location *is* the decision: a field inside the repeatable group → the rule fires per offending row and points at *that* row. Rewriting (e.g.) "each line item needs a ShippedDate" as a single whole-document check over `Lines*/ShippedDate` (a count, or `NotAllFieldsFilled(Lines*/ShippedDate)` on a top-level field) is a **different rule** — it fires once for the whole document and flags the wrong locus. When the requirement says "each", keep the error field in the row.
- A **negative** condition (`FieldNotFilled`, `NoFieldFilled`, …) inside an iterating rule is **rejected** (`MVK_NEG_CONDITION_IN_ITERATION`) unless guarded by a positive existence check on the row: `GroupFilled(<the repeatable group>) And <your negative condition>`.
- **Guard row existence with `GroupFilled(<the repeatable group>)`, not `FieldFilled(<some sibling field>)`.** A sibling field can be empty while the row exists, so an arbitrary-field guard quietly changes *which* rows the rule covers — `GroupFilled` is the row-presence check.

## Aggregates over a repeatable group

When a rule reasons about **all the rows at once** (not one row), it folds the repetitions with an **aggregate**, and the **`*` wildcard is what flattens them**. Such a rule is **model-level** (it spans rows), so its error field is a **non-repeatable** field — it does **not** iterate per row.

- **The `*` goes on whatever flattens the repetitions** — the **field** for a value aggregate (`Sum(Lines*/Amount)`, `NumberOfFilledFields(Lines*/Sku)`, `Min` / `Max`), or the **group itself** to count rows (`NumberOfFilledGroups(Lines*)`). A *single* repeatable group reference needs that `*`: `NumberOfFilledGroups(Lines)` without it is rejected `MVK_NO_WILDCARD`, and a `*` where the group must stay whole (`GroupFilled(Lines*)`) is rejected `MVK_NO_WILDCARDS_ALLOWED`. (Plain `GroupFilled(Group)` takes no `*` — it's the **per-row** existence guard from the section above, valid only from *inside* the iterating group, never as a model-level reference.)
- **Pick the operator by the question:** total of the amounts → `Sum(Lines*/Amount)`; **how many rows** → `NumberOfFilledGroups(Lines*)`; how many filled instances of a field → `NumberOfFilledFields(Lines*/Sku)`. Confirm names/operands with `dmtool operators`.
- **"No two rows share a key" → `FieldValuesNotUnique(/Group*/Key)`** (absolute starred path; error field the **key itself inside the repeated group**, e.g. `--field /Order/Items/Sku`). It validates *and persists* under the default grouping and still iterates per row (points at the offending row). The sibling `RepetitionNotUnique(Group/Key)` instead needs the rule at the repeated group's **PARENT** — pass `--group <parent>` to `rule check` **and** `rule add` (the default grouping rejects it). Reach for `FieldValuesNotUnique` first.
- **Resolve from the rule's scope, or go absolute.** A wildcard path resolves relative to the error field's group; from a different branch a relative `Lines*/Amount` is `MVK_INVALID_ENTITY` — write the absolute `/Invoice/Lines*/Amount`. When unsure, go absolute.
- **An aggregate is a number**, so compare it: `Sum(Lines*/Amount) > 500`, or against a field by bracketing it: `[FeeCap] < Sum(Lines*/Amount)`.
- **`Having` filters which rows are folded:** `Sum(Lines*/Amount Having [Lines/Type] == "FEE")` sums only the fee lines.
- **The error field must appear in the condition** (any rule — kernel `MVK_ERROR_FIELD_NOT_REFERENCED`). A model-level aggregate's error field is *not* referenced by the aggregate's own path, so reference it explicitly: put the error field on the **cap/limit you compare the aggregate against** (`[FeeCap] < Sum(...)` references `FeeCap`), or guard with `FieldFilled(<errorField>)`. "Put it on a non-repeatable field" is necessary but **not sufficient** — the field still has to be named in the condition.

Example — *"the FEE-line total must not exceed the invoice's FeeCap"* (repeatable `/Invoice/Lines` with `Amount`/`Type`; non-repeatable `/Invoice/FeeCap`):
```
dmtool -m invoice.json rule check --field /Invoice/FeeCap \
  --condition "FieldFilled(FeeCap) And [FeeCap] < Sum(Lines*/Amount Having [Lines/Type] == \"FEE\")" \
  --code FEE_OVER_CAP
# → "valid": true — FeeCap is referenced (via the comparison), so the error field appears in the condition
```

## Dates

Date operators need **both operands present** and have a **fixed argument order** — get both right:

- **Guard presence first.** A date function on a missing date is a formal error; lead with `AllFieldsFilled(DateA, DateB) And …`.
- **A date/time *constant* is German-format and quoted** — date `"31.12.2024"` (`dd.MM.yyyy`), time `"17:00:00"`. An **ISO-style literal** (`"2024-12-31"`) is read as a *string*, so an ordering comparison is rejected as `MVK_INVALID_TYPE_FOR_COMPARISON` — the code *name* is unhelpful here, but its `fix` hint now points the right way: write the German format, **not** switch to `==`. (Often cleaner to skip the literal: compare to another date field or `Today`, or pull a part — `YearFromDate(D) < 2020`.)
- **`DifferenceInDays(A, B)` = B − A** (same for `DifferenceInMonths` / `DifferenceInYears`): **positive when B is *later* than A.** So:
  - "B is *before* A" → `DifferenceInDays(A, B) < 0`
  - "B is more than N days *after* A" → `DifferenceInDays(A, B) > N`
- Other date ops (`Today`, `AddYears`, …) — see `dmtool operators`.

Example — *"a ticket must be resolved within 5 days of being raised"* (dates `/Ticket/RaisedDate`, `/Ticket/ResolvedDate`):
```
dmtool -m ticket.json rule check --field /Ticket/ResolvedDate \
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
  dmtool -m order.json rule check \
    --field /Order/LineItems/DeliveryDate \
    --condition "GroupFilled(/Order/LineItems) And [/Order/Channel] == \"EXPRESS\" And FieldNotFilled(DeliveryDate)" \
    --code EXPRESS_ITEM_NEEDS_DELIVERY_DATE
  # → the envelope reports "valid": true, "diagnostics": []
  ```

Apply the same shape to your own model: find the enum + the repeatable group with `describe`, choose the error field for the per-row scope, write the **violation**, guard it if it iterates with a negative, then `check`.

## Reading a rejection

`check` returns diagnostics with a `code` and `summary`. The frequent ones:
- `MVK_NEG_CONDITION_IN_ITERATION` → your iterating rule has an unguarded negative; add `GroupFilled(<group>) And …`.
- `MVK_INVALID_ENTITY` → a path doesn't resolve; bare names are relative to the iteration scope, cross-group fields need an absolute `[/…]` path.
- `MVK_UNEXPECTED_TOKEN` → a syntax slip the kernel reports without a message of its own — most often: a **lowercase boolean** (`true`/`false` → capitalize to `True`/`False`); a **bracketing** mistake (operand *missing* its brackets `[Field] > 1000`, or *extra* brackets on a ref inside a call `DateRange([OrderDate], …)` → `DateRange(OrderDate, …)` — call args are bare); an **unquoted string** (`== OPEN` → `== "OPEN"`); or an ISO date literal (use the German `"dd.MM.yyyy"`). The enriched diagnostic's `fix` lists these.
- `MVK_INVALID_TYPE_FOR_COMPARISON` → ordering (`<`/`>`) needs numbers or dates; compare strings/enums with `==` / `!=`.

For anything else, look the operator up with `dmtool operators <id>` — its `gotchas` and `fix` fields usually say exactly what to do.
