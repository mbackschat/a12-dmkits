# Model corpora analysis — operator usage & transform backlog

A read-only **analysis** of two real A12 model corpora, used to prioritize what rulekit builds next and to *pattern-transform* (not copy) realistic rules into our own fixtures. This file records **facts about DSL usage** (operator names, frequencies, abstract skeletons) — **never** the corpora's domains, field names, or rules. The two corpora are complementary: **A12 Tools** (Corpus 1) shows *everyday application* rules; **`a12-kernel-mm`** (Corpus 2) shows *power-user / type-system* rules and is far denser in the advanced operators.

# Corpus 1 — A12 Tools application models

## Source & provenance (read-only; do not commit corpus content)

- **Source:** the `workspaces/` tree of an *A12 Tools* product distribution (proprietary sample/business models) — **not** the OSS kernel repos and **not** `a12-kernel-demo`. Path is machine-specific (a mounted volume).
- **Rule (from `CLAUDE.md`):** never copy domains/fields/rules from non-demo sources. We treat this strictly as a **read-only oracle**: mine *patterns and frequencies*, then author original fixtures in our own domains, kernel-validated via N7. The histogram and skeletons below are usage facts, not their content.
- **Version:** all 68 models are `modelVersion` **28.4.0** and the distribution is **2025.06-ext5 = kernel 30.8.1** — identical to rulekit's pinned version. No version tolerance needed.

## Corpus stats

- **68 document models**, **150 rule conditions**, **27 models reference other models** (`modelReferences` — i.e. they exercise the `expand` + resolver path we have not yet integration-tested).
- Rule-dense models reach ~29 rules each; conditions are frequently **multi-line** and contain `;;` **comments**, and keywords appear in **case variants** (`and` / `AND` as well as `And`).

## Operator / construct frequency (across the 150 conditions)

| Count | Construct | rulekit coverage |
|--:|---|---|
| 194 | `And`/`and`/`AND` | ✅ (renders `And`; case variants are read-only concern) |
| 87 / 83 | `FieldFilled` / `FieldNotFilled` | ✅ |
| 56 | `GroupFilled` | ✅ (`GroupRef` + group quantifier family) |
| 39 / 15 | `==` / `!=` | ✅ |
| **28** | `*` flatten | ❌ iteration |
| 21 | `AllFieldsFilled` | ✅ (quantifier family done) |
| **21** | `$` correlation | ❌ iteration |
| 19 / 11 / 11 / 7 | `<=` / `<` / `>` / `>=` | ✅ (symbols; catalog has `<=`) |
| 14 | `Or`/`or`/`OR` | ✅ |
| 13 / 10 / 8 / 6 / 5 | `NotExactlyOneFieldFilled` / `NotAllFieldsFilled` / `FieldsNotCollectivelyFilled` / `AtLeastOneFieldFilled` / `NoFieldFilled` | ✅ (quantifier family done) |
| **11** | `NumberOfFilledFields` (aggregate) | ❌ |
| 11 / 3 | `True` / `False` (boolean & confirm literals) | ✅ (`BoolRef.isTrue/isFalse`, `ConfirmRef.confirmed/notConfirmed`) |
| **8 / 8 / 8 / 6 / 4** | `CurrentRepetition` / `For` (semantic index) / `Today` / `Having` / `RepetitionNotUnique` | ❌ iteration + dates |
| **6 / 1 / 1 / 1** | `Date(...)` / `DifferenceInDays` / `DifferenceInYears` / `AddMonths` | ❌ date family |
| 5 | `->` (enum category) | ✅ (`EnumRef.category(...)`) |
| 4 / 4 / 3 / 1 / 1 | `RangeAsString` / (group: `AtLeastOneGroupFilled` ✅) / `PatternViolated` / `GroupNotFilled` ✅ / `Length` | partial (`Length` ✅, group predicates ✅) |
| ~2 | value-list membership (`IncludedInValueList`, `NoFieldValue…InValueList`) | ❌ |

## Abstract pattern inventory (generic skeletons — to transform into our own domains)

- **Boolean-gated requiredness:** `[Bool] == True And FieldNotFilled(G)` / `[Bool] == True And NotAllFieldsFilled(a, b, c)`
- **Guarded cross-field comparison:** `AllFieldsFilled(F1, F2) And [F1] <op> [F2]`
- **Cross-group address equality:** `[GroupA/X] == [GroupB/X] And …`
- **Uniqueness over a repeating group:** `GroupFilled(G) And RepetitionNotUnique(G/Key)`
- **Enum-category navigation:** `[Field -> Category] == "X"`
- **Date thresholds:** `AllFieldsFilled(Start, End) And DifferenceInDays(Start, End) > N`; `[Date] >= Today`
- **Aggregate-driven:** `NumberOfFilledFields(a, b, c) <op> N`
- **Filtered aggregate / correlation:** `Sum(G*/X) Having (…)`, `[$/Other/Field]` correlated refs

## Parser-relevant findings (for the future text→AST round-trip, #3)

Real conditions include `;;` line comments, `\r\n` line breaks, and case-variant keywords (`and`/`AND`). A future parser must tolerate all three; the catalog's keyword set must include the case variants (distinct lexer tokens `UND1`/`UND2`/`UND3`).

# Corpus 2 — `a12-kernel-mm` metamodels (advanced operators)

## Source & provenance (read-only; do not commit)

- **Source:** `a12-kernel-mm/documentModels/` — the kernel's own **metamodels** (MM_Common, MM_Computation, MM_Typedef, MM_CombinationModel, SelectionModel, CT_* custom-type attribute models). Internal type-system models, not application domains. A **kernel OSS repo → do-not-copy** (`CLAUDE.md`); read-only, transform *shapes* only.
- **Volume:** 59 JSON files = the same logical models in render variants (`a12-`/`codegen-`/`none-`) × current/`-previous`. Deduped to **15 canonical models** (one per directory, preferring `a12-`, else `codegen-` — `MM_Common_v2` ships only `codegen-`) carrying **547 rule conditions** — far rule-denser than Corpus 1. *(Corrected 2026-06-04 by the shipped harness; the earlier 13/355 figures used a selection that missed codegen-only directories.)*
- **Version:** header `modelVersion` **28.3.0** (one minor below Corpus 1's 28.4.0). Within the 28.x line the kernel reads; expected to load tolerantly, **not yet verified through `ModelLoader`** — a good check for the resolver/expand integration test.

## Operator frequency (canonical set, 355 conditions)

| Count | Construct | rulekit coverage |
|--:|---|---|
| 500 / 29 | `And` / `Or` | ✅ |
| 233 / 54 | `==` / `!=` | ✅ |
| 202 / 105 | `FieldFilled` / `FieldNotFilled` | ✅ |
| **137** | `*` flatten | ❌ iteration |
| 70 | `Length` | ✅ |
| **57** | `RepetitionNotUnique` | ❌ |
| 54 | `->` enum-category | ✅ |
| 53 | `AllFieldsFilled` (quantifier family) | ✅ |
| 46 | `GroupFilled` | ✅ |
| **45** | `CustomCondition` | ❌ (the app-defined escape hatch) |
| 23 | `True` / `False` literals | ✅ |
| **23** | value-list membership (`…InValueList`) | ❌ |
| **14 / 13** | `PatternMatched` / `PatternViolated` | ❌ |
| 7 | `NumberOfFilledFields` | ❌ |

**Insight:** the metamodels are the heavy users of the *advanced* cluster (`*`, `->`, `RepetitionNotUnique`, `Pattern*`, `CustomCondition`, value-lists) that Corpus 1 barely touches. The kernel team's own rules are the strongest evidence of which advanced operators matter — and they pervasively use `;;` comments and multi-line conditions (reinforcing the #3 parser findings above).

# Combined transform backlog (both corpora, frequency × effort)

Counts shown as C1 (app) + C2 (metamodel). Order balances combined frequency against effort.

1. ~~**Boolean (+ confirm) literals & comparison**~~ — ✅ **done 2026-06-03** (`BoolLiteral` + `BoolRef`/`ConfirmRef`; confirm-vs-False made unrepresentable — `MVK_INVALID_COMPARE_TO_YES`/`_YESNO` verified and N7-locked).
2. ~~**Group predicates**~~ — ✅ **done 2026-06-04** (all seven: `GroupFilled`/`GroupNotFilled` + five group-list quantifiers; `MVK_NO_FIELDS_ALLOWED`/`MVK_NO_GROUPS_ALLOWED` made unrepresentable; enclosing-group-counts-as-error-field-reference verified).
3. ~~**Enum-category navigation**~~ — ✅ **done 2026-06-04** (`EnumRef.category(...)` → validated `CategoryRef`; `MVK_CATEGORY_UNKOWN`/`MVK_NO_ENUMERATION`/`MVK_INVALID_STRING_CONSTANT_FOR_ENUM_COMPARISON` made unrepresentable or fail-fast).
4. **Pattern operators** — `PatternMatched` / `PatternViolated` (0 + 27). *Med; string field + regex constant.*
5. **Value-list membership** — `…IncludedInValueList` family (≈2 + 23). *Med.*
6. **`NumberOfFilledFields` aggregate** (11 + 7) → comparable numeric expression. *Med.*
7. **Iteration / structural cluster** — `*` flatten (28 + 137), `RepetitionNotUnique` (4 + 57), `$` correlation (21 + 0), `CurrentRepetition` (8), `For` semantic-index (8), `Having` (6). *High effort, highest value — model-awareness (§5a) pays off here. Its own multi-step phase; Corpus 2 shows it's even more important than Corpus 1 alone suggested.*
8. **`CustomCondition`** (0 + 45) — the app-defined boolean escape hatch (Zod `.refine` analogue). *Med; emit an opaque named condition the kernel resolves.*
9. **Date family** — `Today`, `Date(d,m,y)`, `DifferenceIn*`, `Add*` (~17 + ~0). *Med; app-only — date kinds + year-presence comparability (§5b).*

## How this drives the work

Each backlog item is built as a normal increment: catalog entries (verified EN/DE keywords) + AST node(s) + typed handles + renderer, with examples authored in **our own** fixtures and **kernel-validated by N7**. The corpora only tell us *what to build and in what order*; correctness comes from the kernel, originality from our own domains.

**Decided sourcing policy:**
- **Corpus 1 (A12 Tools, proprietary):** read-only analysis only. Transform via **one-time pattern-inspired original authoring** — our own coherent fixtures exercising the patterns; never commit or structurally migrate their content.
- **Corpus 2 (`a12-kernel-mm`, EUPL-1.2 OSS):** *referenced*, not vendored. The bootstrap already clones it as a sibling, so an **opt-in integration test** reads `../a12-kernel-mm/documentModels` (env-overridable, skipped if absent). Not committed — EUPL copyleft would conflict with rulekit's intended permissive license (§19), and referencing ≠ copying.

~~That opt-in test … needs a filesystem resolver for kernel-mm's multi-variant reference scheme~~ — **shipped 2026-06-04** (`KernelMmIntegrationTest`, opt-in, skips when the sibling clone is absent): all 15 canonical metamodels load, expand, and pass `checkConsistency` under the pinned 30.8.1 kernel (28.3.0 tolerance **verified**), and every field classifies to a concrete kind (typedef-heavy — zero UNKNOWN). The resolver assumption was wrong: **none of the 59 files carries `modelReferences`** — variants are file naming, not references; `selfContained()` suffices. Schema-oracle findings in `KERNEL-FINDINGS.md`.

## The corpora as the ingest round-trip oracle (Phase 2 — noted 2026-06-03)

With the text→AST read path now core scope (proposal v2, F10/§12), the corpora gain a second job beyond prioritization: **a ready-made, real-world coverage harness for the converter.** An opt-in test sweeps every readable corpus condition through *kernel parse → converter → rulekit AST → re-render → kernel `formatConditionText` comparison* and reports, per condition: converted cleanly / fail-fast construct gap (with the missing construct named) / canonical-form mismatch. 505 real conditions (150 app + 355 metamodel) measure converter coverage against actual usage instead of synthetic fixtures — and the fail-fast gap report *is* the converter backlog, ordered by real frequency. Sourcing boundaries unchanged: `a12-kernel-mm` via the sibling clone (env-overridable, skipped if absent), the *A12 Tools* workspaces via the machine-specific mount (local-only, never CI, never committed); nothing of either corpus enters the repo — the harness reads, converts, compares, and reports counts.
