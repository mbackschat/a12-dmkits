# a12-dmkits — eval task catalog (living)

The per-task catalog for the N6 self-sufficiency eval. **The method, scoring, cost model, and run protocol live in [`../docs/EVAL-SPEC.md`](../docs/EVAL-SPEC.md)** (the stable contract); the **schema, gold shapes, and how-to-add-a-task** are in [`README.md`](README.md); **measured results** (pass@k, lifts) are in [`RESULTS.md`](RESULTS.md). This file is the *what each task is and what it exercises* index — it grows as coverage grows.

> **Keep this synced (no drift guard — it's hand-maintained).** The **authoritative, always-current task list is the corpus itself — `eval/tasks/<kit>/*.json`.** When you **add / rename / remove / change** a task, update the matching row here **in the same commit** (and `README.md` if the schema changed). The `T<N>` labels are legacy handles for the rulekit tasks → they map to `rulekit/<NN>-<slug>` (e.g. `T1` → `rulekit/01-pro-addon-fee-required`); new tasks are referenced by `<kit>/<NN>-<slug>`, no new `T<N>`.

**Current corpus (2026-08-02): 35 tasks, 16 families** — `rulekit/` 01–24, `modelkit/` 01–09, `mixed/` 01–02.

## rulekit — rules, computations, and their comprehension (the operator-coverage catalog)

The **gold** (the exact condition / field / code, or the rules + rubric for comprehension) is authoritative in each task's JSON — `eval/tasks/rulekit/<NN>-*.json` — **not duplicated here**; this catalog is *coverage-only* (what each task probes). Every gold is kernel-verified by `EvalGoldOracleTest`.

| # | Family | Task | What it exercises (coverage) |
|---|---|---|---|
| T1 | Authoring | "When Tier is PRO, each add-on's MonthlyFee must be provided" (subscription) | The iteration/negative-guard idiom (a bare `FieldNotFilled` in the repeatable Addons scope trips `MVK_NEG_CONDITION_IN_ITERATION` unless guarded) + an absolute cross-group ref. |
| T2 | Authoring | "A subscription's effective fee must never be lower than its base fee" (subscription) | Non-iterating field-vs-field comparison (both operands bracketed); the polarity flip `>=` → `<`; no guard needed. |
| T3 | Authoring | "An order's product code, when present, must be `XX-9999`" (order) — pattern | Pattern polarity (`PatternViolated`, not `PatternMatched` — matching is the *good* case) + presence guard. **Unambiguous** format (replaced an over-specified email-regex version, RESULTS 2026-06-05). |
| T4 | Authoring | "Quantity must be ≥ the total quantity across RUSH line items" (order) — aggregate + Having | A filtered aggregate over a repeatable group vs a non-repeatable field; model-level; error field covered. Also **reads cleanly** (field-vs-aggregate round-trips since 2026-06-05). |
| T5 | Authoring | "Delivery date must not fall before the order date" (order) — date | Date-diff argument order/sign (`DifferenceInDays(A,B)` = B−A) + a multi-field presence guard. |
| T6 | Authoring | "An auto-renewing subscription must be on a paid tier" (subscription) — value-list + boolean | The *negative* value-list predicate, the boolean literal `True` (not the string), and enum-by-stored-code. |
| T7 | Comprehension | "Read two rules on this order model and explain each violation + the field it guards" (`order-ruled` — date + pattern) | Both rules read to `Converted`. Read-back: polarity + violated-not-matched pattern semantics + presence guard. |
| T8 | Comprehension | "Read EligibilityCheck and explain the violation, guarded field, and what it delegates" (`order-ruled` — custom condition) | Reads to `Converted`. Recognizing a **host-delegated** condition and *not hallucinating* its meaning from the name. |
| T9 | Authoring | "An order placed before the year 2020 is invalid" (order) — date extraction | **date→number extraction**: `YearFromDate` returns a number; reach for it over an ISO date literal (lexed as a string → rejected for ordering) + presence guard. |
| T10 | Authoring | "Express shipping ⇒ the shipping address must be complete" (order) — group-scope quantifier | **group-scope** field-fill quantifier `NotAllFieldsFilled(<group>)` (vs the field-list form) + polarity + boolean `True`. |
| T11 | Authoring | "Full name must read as first + space + last" (`person`) — string concatenation | **String concatenation** — a string expression on the right of a comparison; `+` is the intrinsic concat; `!=` polarity for "must equal". |
| T12 | Authoring | "Tax number, when provided, must be a valid German tax number" (`person`) — custom-field-type validity | **`ValidType`/`InvalidType`**: `Invalid(field, "Type")` delegates to a registered verifier; no regex (a `PatternViolated` candidate is genuinely non-equivalent). The type name isn't model-checked. |
| T13 | Authoring-computation | "Coverage window = the date range from order date to delivery date" (order) — `DateRange` | The computation form of the **`DateRange` constructor**. **Gold is UNGUARDED, intentionally** — an empty operand computes nothing (`adapter.laws.DateRangeComputationLawsTest`), so a guard is redundant; the judge prompt carries this fact. The constructor is also legal in `==`/`!=` conditions, which this task does not ask for. |
| T14 | Authoring-computation | "Effective fee is twice the base fee" (subscription) — numeric computation | The baseline computation **write path** — discovering the computation-spec shape (`schema computation add`) + an unconditional value expression. |
| T15 | Authoring (multi-file) | "Every storefront must have a store name" (`storefront`, multi-file) | **CLI-over-includes (F-RES):** `storefront` `include`s a `catalog` in a sibling `multifile/lib/` tree → needs `-w <lib>`, which the agent must discover from the CLI error. Gold on a main-model field. |
| T16 | Modify-computation | "Effective fee = base fee PLUS the total of all add-on monthly fees" (`subscription-computed`) — `modify --dry-run` | The agent discovers the existing `EffectiveFeeComp`, KEEPS the computed field, re-expresses. Backbone: replacing the computation re-binds. |
| T17 | Modify-rule | "Tighten the delivery rule: delivery ≥ 2 days after the order date" (`order-ruled`) — `modify --rule` | The agent discovers `/Order/DeliveryNotBeforeOrder`, KEEPS the rule + error field, re-expresses the **condition** — polarity ("≥ 2 days after" → violation `< 2`). |
| T18 | Comprehension | "Read three value-operator rules and explain each violation" (`order-aggregates`) | All three read to `Converted`. The operand-list operators that read awkwardly as authoring intents — operand-list `Max` (vs a row-aggregate), `RoundDownValue` (truncation), `FirstFilledValue` (first-non-empty vs sum) — + the violation polarity. |
| T19 | Self-correction | "Delivery date must be on or before 31 December 2024" (order) — the ISO-vs-German-date trap | **Rides the authoring path** (§3): the agent hits the seeded date-format error, reads the diagnostic's `fix`, and retries until kernel-valid — a CLI/catalog diagnostic-quality signal. |
| `rulekit/20` | Computation-comprehension | "Read the banded shipping computation and explain, in plain business terms, which field it computes, the condition under which it computes, and each band" (`order-tiered`) | **The comprehension twin for computations.** `computation explain` surfaces the `commonPrecondition` (the WHEN guard) + the `computationAlternatives` (the bands — each `precondition → operation`). Read-back must get the computed field, the precondition guard, and each band's direction right (no inversion). Managed non-determinism: deterministic backbone (`EvalGoldOracleTest` reads to `Converted` + asserts the precondition/alternatives surface) + an LLM rubric judge. |
| `rulekit/21` | Runtime | "Run the model's computations over one document and report the rows in human-readable form" (`order-tiered`) | Bare-tool discovery of `model compute`, its `--instance` input, and `--text`; deterministic oracle locks the exact computed row and summary. |
| `rulekit/22` | Runtime | "Evaluate one document against the whole model and report fired and unsupported rules in human-readable form" (`order-ruled`) | Bare-tool discovery of `model eval`, its `--instance` input, and `--text`; deterministic oracle locks both fired codes and the host-delegated unsupported rule. |
| `rulekit/23` | Runtime | "Evaluate one named rule against one document and report its verdict in human-readable form" (`order-ruled`) | Bare-tool discovery of `rule eval`, its `--instance` input, and `--text`; deterministic oracle locks the focused fired verdict and summary. |
| `rulekit/24` | Computation-preflight | "Test whether this proposed computation would be accepted without modifying the model" (`subscription`) | The intent withholds the option name. The agent must discover the read-only `computation add` preview; the deterministic oracle locks acceptance and byte-identical input. |

## modelkit + mixed — structure, metadata, refactor, templates, access-control, sessions

Each is **deterministically scored** (no LLM judge) **except `workspace-roles`** (LLM-judged like comprehension). Gold shapes + the deterministic post-conditions are in [`README.md`](README.md); backbones in `EVAL-SPEC §8d`.

| Task | Family (kit) | Intent | What it probes (coverage) |
|---|---|---|---|
| `modelkit/01-add-field-and-group` | structure | add an optional whole-number `LoyaltyPoints` field to the order | basic structure authoring — adding a field/group |
| `modelkit/02-safe-delete-referenced-field` | delete-safety | remove the delivery-date field + any rules depending on it | the safe-delete cascade — removing a field that rules reference |
| `modelkit/03-rename-field` | refactor | rename the delivery-date field to `PromisedDate` | reference-rewriting rename (valid→valid) |
| `modelkit/04-describe-field` | metadata | give the customer-name field an internal (maintainer) description | the element-descriptions surface |
| `modelkit/05-annotate-rule` | metadata | tag the delivery-date rule with `owner = orders-team` | the name/value annotations surface |
| `modelkit/06-model-comment` | metadata | set a model-level comment | the model-comment surface |
| `modelkit/07-multiselect-template` | template | add a multi-select `Conditions` group | the multi-select group template (`usageType`) |
| `modelkit/08-attachment-template` | template | add a file-attachment `Invoice` group | the attachment group template |
| `modelkit/09-workspace-roles-audit` | workspace-roles | audit a workspace's access control (undefined role/authority) | **discoverability** of `workspace roles` from the bare CLI + reporting the right cross-file findings (R4; the only **LLM-judged** modelkit family) |
| `mixed/01-apply-add-field-and-rule` | session | add a `Discount` number field, then a rule that it must not be negative | an `apply` session touching both kits |
| `mixed/02-fix-rejected-batch` | session | add a `PromisedDate` field, then a delivery-on-or-before rule | the read→edit→write session with a recovery trap (a rejected batch) |
