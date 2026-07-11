# A12 Kernel — validation-language semantics

> **Provenance & role in this repo.** Owner-authored in the sibling `a12-kernel-demo` project; integrated here 2026-07-09 so this repo is self-contained — this copy is the one the docs, [SEMANTICS-MAP.md](SEMANTICS-MAP.md), and `SemanticsMapResolveTest` key on. It is the **canonical kernel-truth prose layer**: its 14 numbered sections are the `§n` taxonomy that [KERNEL-FINDINGS.md](KERNEL-FINDINGS.md) (our experiment-verified supplements), the operator-catalog facets, and the conformance corpus's `meta.sections` all mirror. The companion docs it mentions (`KERNEL-GLOSSARY.md`, `SHOWCASE.md`, `KERNEL-GRAMMAR.md`) remain in their origin projects.

A reference to how the A12 validation language *evaluates*, beyond what its syntax shows: its truth model, how it treats missing values, what the "required" property means, and the numeric, date, string, enumeration, repetition, path, and computation rules that determine when a rule fires. The behaviours below are documented in the kernel's *Validation-Language guide*; each entry cites the relevant chapter/section by title. Two claim classes share the page: those guide-documented behaviours, and — in the deepening subsections — behaviours **verified by experiment or source reading against kernel `30.8.1`**, each cited inline as *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §n / IFnn, locked by `SomeTest`)*; a claim without such a citation is the guide's.

Two facts frame everything else:

- **A rule states the *error* condition — and that polarity is fixed.** Its condition is written so that `true` means the data is invalid. `FieldNotFilled(CustomerName)` is the rule "the customer name is missing." No slot or configuration flips this: the rule's single condition slot is `errorCondition`, severity is message metadata ([§12](#12-the-validation-model)), and there is no assertion/requirement form — so a requirement must be authored as its *violation*, and a positively-written requirement is structurally valid but encodes the opposite rule, a mistake the kernel cannot catch *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) "Rule-condition polarity is fixed")*.
- **Rules and computations share one language**, so the same operators and treatments apply to computed fields.

**Scope:** this covers how the *validation language* evaluates. The developer/runtime side — the `DocumentV2` API, the `validateFull`/`validatePart` call surface, mapping, code-generation targets, and model include/expansion — is out of scope except where it changes evaluation (touched in [§12](#12-the-validation-model)). Authoring/grammar pitfalls (path syntax, keyword-name escaping, `Date()` operand formats) live in `KERNEL-GRAMMAR.md`.

For the vocabulary used here, see `KERNEL-GLOSSARY.md`; for runnable demonstrations, `SHOWCASE.md` (both in the origin `a12-kernel-demo` project).

---

## The field types at a glance

Which operators are legal on a field, and how a missing or mismatched value behaves, depends on the field's **type** — so the type model is worth holding in mind before the per-topic rules below. The kernel defines these field types (the interface names are the public `kernel-md-model-api` `fieldtypes/I*Type`):

| Field type | Variants / key config | `[Field]` yields | Special meaning for evaluation |
|---|---|---|---|
| **String** | `pattern`, min/max length | string | `Length` counts Unicode combining sequences as several characters; a `pattern` requires an accompanying error text; the basis for `Custom`. |
| **Number** | **scale** = max fractional digits; min/max value; `trait` = amount / percent / permille | number | Scale **gates `==`/`!=`** (mismatched scale is a *parse-time* error — round one side first); an unspecified number is substituted with **`0`** in comparisons (but *ignored* by min/max); orderable with `< > <= >=`. |
| **Boolean** | True / False / unset | boolean | Three states; an unspecified value leaves a comparison **not evaluated**; compares only to `True`/`False` or another Boolean; `[F] == True` is **not** the same as `FieldFilled(F)`. |
| **Confirm** | True / unset (a checkbox) | confirm | Distinct from Boolean: an unspecified value is treated as **`False`** in comparisons; compares only to another Confirm or the constant `True`. |
| **Enumeration** | `values`; *with* or *without* localized **texts**; **categories** | enum value | Compared by the **stored value**, not the display text (`[Country]=="F"`); a *text-bearing* enum cannot be compared with a plain string or a *textless* enum; `->` reads a value's **category** attribute. |
| **Date** | `format`; `datePrecision` = full / day- / month- / year-optional | date | Constants are `DD.MM.YYYY` (day-first); a **string literal matching that format parses as a date** (so it breaks string concatenation); precision gates `DayFromDate`/`MonthFromDate`/… and `ValueAsDate`. |
| **DateTime** *(extends Date)* | — | date-time | The required operand for `DifferenceIn{Hours,Minutes,Seconds}`, `Add{Hours,…}`, `*FromTime`, and `Now`. |
| **Time** *(extends Date)* | — | time | The operand for `HoursFromTime` / `MinutesFromTime` / `SecondsFromTime`. |
| **DateFragment** | `formatOfFragment` = `MM` / `yyyy` / `yyyy-MM` / `MM-dd` | partial date | A deliberately *imprecise* date; comparable only by the **year-presence rule** (or globally via a model **Base Year**); the fragment format decides which `Add*` apply. *(Editor: import-only.)* |
| **DateRange** | `format` (`MM` … `yyyy-MM-dd`); separator `/` | date-range | Supports only `==` / `!=` (**no ordering**); cannot be nested in another construct; `DateRangesOverlap` treats endpoints as inclusive. |
| **Custom** | `name`; min/max length | string-equivalent | Treated as a **string** in rule conditions; `Valid(F, "Name")` / `Invalid(...)` runs the project's registered validator for that custom type. A declared Custom field's **whole** formal check — length bounds included — is delegated to that validator (the bounds are hints); with none registered, a *filled* custom cell fails the entire validation call. |
| **TypeDefinition** | references a reusable type definition by id | the underlying type's value | **Delegates** to the referenced type — its legality *is* that underlying type's, and is known only after the model is **expanded** (includes/type-defs resolved). |
| **Unspecified** *(experimental)* | — | — | Documented as "not a data type" — an intermediary/decoration, not used as a rule operand. |

*Reference: Models → Field types; Constants and data types; Operators and language constructs → Comparison operations. Interfaces: `kernel-md-model-api` `fieldtypes/I*Type`. The per-type evaluation details are expanded in the sections below (numbers §5, dates §6, strings §7, enumerations §8).*

---

## 1. Truth values and logical evaluation

### Two-valued connectives over data that can be "unknown"

The logical operators `And` / `Or` and the predicates are two-valued: a condition is satisfied or not. However, the *data* a condition reads can be in a third state — **unknown** — when a field carries a formal error (see [§4](#4-the-required-property) and [§12](#12-the-validation-model)). A field in that state cannot be evaluated by any rule. The logic itself never produces a third value; "unknown" instead makes each operand that reads the field undecidable, and the ordinary `And`/`Or` algebra decides how far that reaches — branch-scoped, not rule-scoped ([§3](#3-formal-errors-and-the-unknown-state)) *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §3, locked by `FormalErrorScopeLawsTest`)*.

*Reference: Operators and language constructs → Logical operators; Appendix → Validation and formal validation → Formal errors.*

### `And` / `Or` require explicit parentheses when mixed

There is no implicit precedence between `And` and `Or`. A condition that mixes them without brackets is a parse error (`MVK_BRACKET_MISSING`), not a default grouping.

```
( FieldFilled(A) And [A] <= 0 ) Or FieldNotFilled(B)
```

The result differs depending on where the brackets go, so the language requires them rather than choosing for you.

*Reference: Operators and language constructs → Logical operators.*

### Boolean is a three-state field type

A `Boolean` field has three states: not filled, `True`, and `False`.

```
[Flag] == True       ;; satisfied only if Flag is filled AND its value is True
[Flag] == False      ;; satisfied only if Flag is filled AND its value is False
FieldFilled(Flag)    ;; satisfied if Flag has any value (True or False)
```

Consequences: `[Flag] == True` is not equivalent to `FieldFilled(Flag)`, and `[Flag] == False` does not mean "unchecked" — an unset Boolean satisfies neither `== True` nor `== False`.

*Reference: Models → Field types → Boolean.*

### `Confirm` and `Boolean` treat an unspecified value differently

For a `Confirm` field (a checkbox with the single value `True`), an unspecified value is treated as `False` in comparisons. For a `Boolean` field, an unspecified value leaves the comparison *not evaluated* (see [§2](#2-non-specified-empty-values)). The same comparison form therefore yields different results depending on the field type.

At the stored-value tier the split repeats: a boolean's value token is lowercase `true`/`false` — any other token is a formal error ([§3](#3-formal-errors-and-the-unknown-state)) — while a confirm accepts only `true` or unspecified; a stored `false` on a confirm is itself a formal error, consistent with "a checkbox whose single value is True". "Not confirmed" is written `[Confirm] != True` *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §1 + §2, locked by `BooleanConfirmLawsTest` + `EmptyValueLawsTest`)*.

*Reference: Models → Field types → Confirm; Models → Treatment of non-specified values.*

### There is no generic negation operator

The language has no `Not`. Each negative check is a dedicated predicate with its own evaluation logic, so that conditions stay simple and the language need not define negation over the "unknown" state. The positive/negative pairs are not always logical complements:

- `NotAllFieldsFilled(L)` is satisfied when **at least one** field is unspecified — including when **none** are filled.
- `FieldsNotCollectivelyFilled(L)` is satisfied when at least one **but not all** fields are filled (it excludes the all-empty case).

```
NotExactlyOneFieldFilled(A, B, C)   ;; satisfied for 0 filled AND for 2+ filled
```

Choosing the wrong member of a pair changes which documents the rule reports.

*Reference: Preface → Kernel Language; Predicate language constructs for Field lists.*

### How the fill quantifiers count — group scopes and the two iteration ranges

- **A group operand means "the fields inside the group", recursively.** A field-fill quantifier over a group expands to a field-list quantifier over every descendant field, through nested subgroups; a repeatable group in the operand carries its `*` onto each descendant, and a mixed group+field operand list is legal *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §1 / IF49, locked by `GroupScopedFieldFillDiffTest`)*. A **value aggregate** (`Sum`, `MinValue`, `MaxValue`, `NumberOfDifferentValues`) over a starred group expands the same way — every contained field's values, recursively *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §1 / IF99, locked by `GroupStarValueAggregateDiffTest`)*.
- **Two iteration ranges over a starred scope.** `AllFieldsFilled` and `NotAllFieldsFilled` walk the **full declared range** — up to the group's `repeatability`, an un-instantiated repetition reading *empty* — so `AllFieldsFilled(G*)` holds only when every *declared* repetition is filled. `AtLeastOneFieldFilled`, `NoFieldFilled`, `MoreThanOneFieldFilled`, and `NotExactlyOneFieldFilled` clamp to the **instantiated** rows. `FieldsNotCollectivelyFilled` mixes the two (≥ 1 filled over the instantiated rows AND ≥ 1 empty over the declared range). On a non-repeatable scope the ranges coincide *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §1 / IF49, locked by `GroupScopedFieldFillDiffTest`)*.
- **The group quantifiers admit a starred single group only for the count-zero / count-≥1 members.** `NoGroupFilled(G*)` and `AtLeastOneGroupFilled(G*)` count the group's **instantiated repetitions** — a created-but-empty row counts as a filled group (a row is content, [§9](#9-repetition-iteration-and-cross-array-evaluation)); a fired `NoGroupFilled(G*)` at zero rows types OMISSION, a fired `AtLeastOneGroupFilled(G*)` VALUE. The `AllGroupsFilled` / `NotAllGroupsFilled` / `GroupsNotCollectivelyFilled` family rejects the asterisk *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §1 / IF68 + IF46, locked by `StarredGroupQuantifierDiffTest` + `GroupQuantifierLawsTest`)*.
- **A formally-invalid cell counts in neither bucket.** A present-but-invalid cell ([§3](#3-formal-errors-and-the-unknown-state)) is neither filled nor empty in a fill tally, and any invalid element makes a value aggregate non-evaluable *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §1 + §3 / IF84, locked by `StarQuantifierInvalidCellDiffTest`)*.

---

## 2. Non-specified (empty) values

How an unspecified field affects a comparison depends on its **type**:

| Field type | Unspecified value in a comparison |
|---|---|
| number | a default of `0` is substituted |
| confirm | treated as `False` |
| string, date, boolean, enumeration, custom | the comparison is **not evaluated** (no error) |

```
[Amount] < 100      ;; number: fires on an EMPTY Amount, because 0 < 100
[Date] > "01.01.2022"   ;; date: does NOT fire on an empty Date (not evaluated)
```

To avoid reporting an error on an empty number field, guard it:

```
FieldFilled(Amount) And [Amount] < 100
```

Two further points:
- The `0` substitution does **not** apply to minimum/maximum calculations — there, unspecified fields are ignored.
- There are no empty strings: `[F] == ""` is never satisfied, even when `F` is unfilled — and neither is `!= ""`: a string comparison with an **empty operand on either side** (a literal `""`, an empty coercion result such as an out-of-range `RangeAsString`) is not evaluated, for `==` and `!=` alike. Use `FieldNotFilled(F)` to test for absence *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) "numeric coercions", locked by `RangeCoercionDiffTest`)*.

*Reference: Models → Treatment of non-specified values; Appendix → Validation and formal validation → Leading and trailing spaces.*

### Why the empty case decides whether a rule is right

A rule spends most of its life reading empty fields — a document is validated continuously during data entry, so "operand empty" is the *normal* state, not the edge case — and the table above means the same comparison shape silently changes meaning with the field's type: `[Amount] < 100` fires on an empty amount, `[Date] > "01.01.2022"` stays quiet on an empty date. Getting the empty column wrong is therefore the most common way a rule is wrong in practice, in both directions (a rule that flags every half-entered document, or one that never fires because its operand type gets the not-evaluated pass). The operator catalog carries these answers machine-readably — the `emptyOperand` facet, with this section's per-kind table as its header defaults — and records the class as the #1 recurring semantic failure ([CATALOG-SPEC.md](CATALOG-SPEC.md) §4).

### Where an operator overrides its kind's default

The per-kind table is only the default; several operators put an empty operand through a different door, so the same field can get the free pass in one position and be evaluated in the next:

| position | an empty operand… |
|---|---|
| string in a `+` **concatenation** | concatenates as `""`, so the enclosing comparison **is** evaluated — `[Full] != [First] + " " + [Last]` mis-fires on empty parts unless the operands are guarded *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §2, locked by `EmptyValueLawsTest`)* |
| `Length(F)` | reads as length **`0`**, evaluated — the string family's own empty-as-0 backdoor, needing the same `FieldFilled` guard as a number *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §2, locked by `EmptyValueLawsTest`)* |
| `PatternMatched` / `PatternViolated` | not evaluated — an empty string violates no pattern; absence needs `FieldNotFilled` *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §2, locked by `EmptyValueLawsTest`)* |
| the date/time **extractors** (`DayFromDate`, …) and the date **differences** | read as the fillable **`0`** — the enclosing comparison is evaluated and can fire *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §6 / IF73, locked by `EmptyOperandFiringDiffTest`)* |
| **value-list membership** | an empty subject makes membership UNKNOWN — *neither* the included nor the not-included form fires — and an empty numeric list *entry* never matches (the `0` substitution does not apply to entries) *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §2/§8, locked by `EmptyValueLawsTest` + `ValueListDiffTest`)* |
| the **count family** (`NumberOfFilledFields`, `NumberOfValueInFields`, …) | an empty cell is never counted — `NumberOfValueInFields(0 In …)` does **not** count an empty number as a match for `0` — but it keeps the count growable, typing a fired comparison OMISSION *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §2, locked by `NumberOfValueInFieldsDiffTest` + `FilledCountLawsTest`)* |
| operand-list `Min([A], [B])` / `Max(…)` | an empty NUMBER operand is substituted `0` and **competes in the fold** (`Min(0, B)` — the 0 can win); an empty DATE operand is **skipped** (the date-bearing side wins) *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §2 + §6 / IF58, locked by `EmptyValueLawsTest` + `DateAggregateDiffTest`)* |
| the field-list / starred aggregates (`Sum`, `MaxValue`, `MinValue`) | the empty cell is **dropped from the fold** — `MaxValue(-5, empty)` is `-5`, no `0` folded in; `Sum` reads it as `0`, which for a sum is the same thing *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §1 + §9, locked by `MultiFieldAggregateDiffTest` + `RepetitionLawsTest`)* |

The last two rows are one design split seen from both sides — and they disambiguate the guide's min/max note above: "the `0` substitution does not apply to minimum/maximum calculations" is true of the *aggregates* `MaxValue`/`MinValue`, while the operand-list `Min`/`Max` are a separate family that does substitute. `0` is `Sum`'s neutral element but not min/max's, so the aggregates must drop empties where the sum may fold them in — which makes it easy to assume the drop extends to the *all-empty* case. It does not:

### What an all-empty selection folds to (the aggregate identities)

One operand empty is one question (above); the **whole selection** empty — every operand empty, or a starred group with zero filled cells — is another, and each aggregate has a defined identity for it:

- The NUMBER aggregates fold to the fillable **`0`**, not "undefined": `MaxValue(QTY, BQ) == 0` fires with both fields empty, and `MinValue(Items*/Count) == 0` fires on a group with no rows at all *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §1, locked by `MultiFieldAggregateDiffTest`)*. `Sum` over an all-empty list is likewise `0` *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §9, locked by `RepetitionLawsTest`)*.
- The DATE `Min`/`Max` fold to **no value**: a comparison against an all-empty date aggregate never fires, and a computation of it clears its target *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §6 / IF58, locked by `DateAggregateDiffTest`)*.
- `FirstFilledValue` follows its operand **kind**: an all-empty selection yields the fillable `0` for a NUMBER operand but no value (NOT-GIVEN) for a DATE or STRING operand *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §1/§3 / IF80 + IF83, locked by `FirstFilledValueKindDiffTest`)*.

The identity is treacherous because it hides under most thresholds: `0` and "not evaluated" produce the same non-fire for `MaxValue(…) > 3`, so a wrong mental model survives every such rule and surfaces only where `0` itself satisfies the comparison — `MinValue(…) < 10` is the shape that finally distinguishes them *(the latency observed in [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §1, locked by `MultiFieldAggregateDiffTest`)*. The catalog carries these identities per element kind as the `aggregateIdentity` facet ([CATALOG-SPEC.md](CATALOG-SPEC.md) §3c).

### The row gate — where the substitutions stop

All of the above happens inside a **content-bearing row**. Under full validation an entirely empty instance is evaluated only when the condition is one that *can* fire on emptiness — a structural property of the condition, true of the negative presence family (`NoFieldFilled`, `FieldNotFilled`, `NotAllFieldsFilled`, …) and composing through `And`/`Or`; a comparison never fires on a truly blank instance, so the empty-as-0 rows above presuppose some sibling content *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §2, locked by `EmptyRowGateDiffTest`)*. What counts as content has one wrinkle: an **instantiated repeatable row is itself content** — a created-but-blank repeat row *is* evaluated, so per-row empty-as-0 fires on every such row — while a non-repeatable group's bare structural presence is not *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §2 / IF53, locked by `RepeatableRowContentDiffTest`)*. Partial validation overrides the gate entirely: a *relevant* instance is always evaluated, empty or even phantom — see [§12](#12-the-validation-model) *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §2 / IF54, locked by `RepeatableRowContentDiffTest`)*.

Emptiness is also only one of a field's two non-value states: a **filled but formally invalid** cell behaves differently from an empty one in every position above — that third state is [§3](#3-formal-errors-and-the-unknown-state)'s subject.

---

## 3. Formal errors and the "unknown" state

A field whose value violates its *data-type configuration* (wrong type, pattern mismatch, too many decimal places, leading/trailing spaces, or a checked "required" box on an empty field) produces a **formal error**. While a formal error stands, the field is "unknown" to the kernel and **cannot be evaluated in any validation rule**.

A consequence at the logic layer: a condition that appears to be a tautology is not one.

```
FieldFilled(F) Or FieldNotFilled(F)
```

If `F` has a formal error, neither branch is satisfied — the field is unknown, so even "filled or not filled" cannot be said to be true.

Formal errors differ from rule errors: they use a fixed, non-customizable message, are shown on focus-out, and block the field from participating in other rules. Rule errors are authored in the model, can be error/warning/info, and never make a field unevaluable.

*Reference: Appendix → Validation and formal validation → Formal errors.*

### House ↔ kernel vocabulary — "cell" is our coinage (declared here, once)

"Cell" is a **house term**, not kernel vocabulary — the kernel has no `Zelle`/`cell` noun (source-verified; [KERNEL-FINDINGS](KERNEL-FINDINGS.md) "Terminology"). It is a compact row×column shorthand we use in the semantics/interpreter layer; this table is its **single point of declaration**, so every surviving use maps to the kernel's own wording. Agent-facing surfaces (the operator catalog, published schema, diagnostics, the skill, API-GUIDE) do **not** use the house noun — they use the kernel wording in the right-hand column.

| house term | denotes | kernel's wording / nearest construct | why coined |
|---|---|---|---|
| **cell** | one field value at a given repetition (a repeated field's value in one row) | *field value* (`FeldWert`) at a *repetition* (`Kontext`/`Kontextnummer`); the kernel has **no single noun** for the pair | a terse row×column shorthand for "a repetition's field value" |
| **cell state** | a value's three-way evaluability: empty · filled · not-check-relevant | the kernel's separate **predicates** — `isWertAngegeben` (specified/filled), not-specified (empty), `nichtPruefungsrelevant` (not check-relevant); **not** a named kernel type | names the 3-way taxonomy the kernel leaves as loose predicates (`CellState`) |
| **(index) column** | a field's values across all repetitions of its group | "the field's values across the repetitions"; **no single kernel noun** (the uniqueness/aggregate scans operate over it) | the scan unit for uniqueness + starred aggregates (`IndexColumn`) |

Everything below uses "cell" per this declaration.

### The three cell states — the third one is not "empty"

A field's cell (a repetition's field value — see the vocabulary table above) has three states, not two: **empty**, **filled**, and **not-check-relevant** (the kernel's `nichtPruefungsrelevant`) — present but formally invalid. [§2](#2-non-specified-empty-values)'s substitutions are the *empty* column only; an invalid cell answers every question with "unknown", which lands differently in every position:

| what asks | an empty cell | a not-check-relevant (filled-but-invalid) cell |
|---|---|---|
| a comparison reading the value | the per-kind substitution ([§2](#2-non-specified-empty-values)) | UNKNOWN — the branch cannot decide *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §3, locked by `FormalErrorLawsTest`)* |
| `FieldFilled` / `FieldNotFilled` / the fill quantifiers | a definite FALSE / TRUE — the cell counts as empty | UNKNOWN — counted as **neither filled nor empty**, so e.g. `NoFieldFilled` does *not* fire over invalid cells (invalid ≠ empty) *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §3 + §1 / IF84, locked by `QuantifierMalformedDiffTest` + `StarQuantifierInvalidCellDiffTest`)* |
| a value aggregate or count over a selection containing it | the empty cell is dropped or substituted ([§2](#2-non-specified-empty-values)) | the **whole aggregate is non-evaluable** — the enclosing rule is suppressed *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §2, locked by `AggMalformedDiffTest`)* |
| a computation reading it | the per-kind compute behaviour ([§11](#11-computations)) | the read **aborts the computing instance** — compute's poison, [§11](#11-computations) *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §11 / IF85, locked by `ComputePoisonReadDiffTest`)* |

### What puts a cell in the third state

More than a type-format mismatch. The same mechanism — same sentinel path, same suppression — is triggered by:

- **a malformed value** — the base case above, plus the cross-kind baseline checks that run before any type format: legal charset and leading/trailing blanks. The legal charset is the model-config `supportedCharacters` when the model declares one (runtime-enforced), else the full BMP (`0x0000–0xFFFF`, so any supplementary-plane character is illegal); a per-field charset is not declarable, and a *project/codegen*-level restricted charset (e.g. DIN 91379) exists outside the DM model *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §3 / IF43 + "`modelConfig.supportedCharacters`", locked by `BaselineValueFormalDiffTest` + `SupportedCharactersLawsTest`)*;
- **a declared-constraint violation** — range, length, pattern, digit counts, enum domain: a parseable but out-of-bounds value is exactly as invalid as an unparseable one, and a value violating several checks reports the *first* in the kernel's fixed precedence *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §3 / IF20 + IF70, locked by `FieldConstraintFormalDiffTest`)*;
- **the "required" checkbox on an empty field** ([§4](#4-the-required-property)) — for validation the required-and-empty cell is unknown like any other formal error; compute, by contrast, reads it as plainly empty *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §9 (the uniqueness-cache exclusion) + §11 (compute's view), locked by `RepetitionNotUniqueFormalKeyDiffTest` + `ComputeInvalidOperandDiffTest`)*;
- **a duplicate index value** — the auto `uniqueIndex` check marks every participating cell, field-locally *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §4 / IF86, locked by `UniqSuppressionDiffTest` + `IndexAggregateDiffTest`)*;
- **an over-repetition row** — an instance beyond the group's declared repeatability, which also suppresses that row's ordinary checks *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §9 / IF27, locked by `OverRepetitionDiffTest`)*.

### Suppression is branch-scoped — and it reaches presence checks

A formal error does not remove the whole rule; it makes the affected operand UNKNOWN and the ordinary `And`/`Or` algebra decides the rest: `healthyTrue Or [broken] > 0` still fires via its healthy branch, while `healthyTrue And [broken] > 0` is absorbed — the single-branch rule is simply the case where branch- and rule-scoped suppression look identical *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §3, locked by `FormalErrorScopeLawsTest`)*. The unknown reaches even operators that read no value: `FieldFilled(malformed)` decides nothing *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §3, locked by `QuantifierMalformedDiffTest` + `CurrentRepetitionDiffTest`)*. And the suppression is silent from the author's viewpoint: the kernel emits its own formal-error code, attributed to the sentinel rule path `formalePruefung`, while the author's rule simply produces nothing — in a validation report the discriminator is that sentinel vs the rule's slash-path *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §3, locked by `FormalErrorLawsTest`)*.

### Validation excludes; compute poisons

The two operations treat the third state with opposite temperament, and the difference matters wherever computed fields chain. **Validation excludes**: an invalid cell drops out — tallies count it in neither bucket, aggregates over it go non-evaluable, referencing branches go UNKNOWN — and nothing propagates beyond the rules that reference it. **Compute poisons**: during `compute()` the invalid cells are converted so that any *read* of one throws; the reading computation instance aborts, produces no value, and the invalid mark cascades to its own dependents' reads in turn — order-dependently, because only cells an evaluation actually reads can poison it *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §11 / IF85, locked by `ComputePoisonReadDiffTest`)*. The compute side is unpacked in [§11](#11-computations).

---

## 4. The "required" property

The "required" checkbox in the field editor is more than a single flag; it expands into a generated rule, manifests as a formal error, and is reflected by a separate "mandatory" indicator.

### The "Only if the Parent Group is filled" option

The checkbox has a secondary option that changes the generated condition:

| Field (location) | Parent-filled = **no** | Parent-filled = **yes** |
|---|---|---|
| `F1` (in group `A`) | `FieldNotFilled(F1)` | `GroupFilled(A) And FieldNotFilled(F1)` |
| `F5` (in group `C`, nested under `K`) | `GroupFilled(C) And FieldNotFilled(F5)` | `GroupFilled(K) And FieldNotFilled(F5)` |

With "yes," the field is required only once its parent group is filled (a reference to the parent). For a nested field the *referenced* group also differs between the two modes — the immediate parent under "no," a higher group under "yes."

### It manifests as a formal error

A field that is required-by-checkbox and unfilled produces a **formal error**, so the field becomes "unknown" (see [§3](#3-formal-errors-and-the-unknown-state)) — a stronger effect than the equivalent author-written rule `FieldNotFilled(F)`, which leaves the field evaluable. Tabbing through an empty required field does not trigger the error on its own, because no value changes.

### The "mandatory" star is a heuristic, not ground truth

Some controls show a star indicating the field is mandatory. The star is computed by a defined "star method" that inspects a fixed set of constructs. Starred fields are always mandatory, but **not every mandatory field is starred**: deciding mandatoriness in general is not effectively decidable (the guide's example shows a case where it would depend on whether a particular large number is prime). With "Parent-filled = yes," a field may be starred, mandatory-without-a-star, or not mandatory. A missing star therefore does not imply the field is optional.

### Interaction with repeatable groups

Because "yes" emits `GroupFilled(parent)`, and `GroupFilled` is satisfied for an empty, freshly-added repeat row (see [§9](#9-repetition-iteration-and-cross-array-evaluation)), a conditionally-required field can report an error on a row the user added but left blank.

### The generated rule, precisely — and what "filled" means here

The checkbox expands into an **autogenerated rule** (name `vk_vk<field>_req`, error code `mandatoryField`, severity ERROR, type OMISSION), whose condition is decided by the field's lowest repeatable ancestor: `FieldNotFilled(field)` — **unconditional**, firing even on an un-instantiated document — for an absolutely-required field with no repeatable ancestor; `GroupFilled(R) And FieldNotFilled(field)` per instantiated row under a repeatable ancestor `R`; `GroupFilled(parent) And FieldNotFilled(field)` under "Parent-filled = yes" *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §4, locked by `RequirednessDiffTest`)*.

"The parent is filled" resolves by the parent's repeatability, with the same **content** notion as `GroupFilled` ([§9](#9-repetition-iteration-and-cross-array-evaluation)): a repeatable parent counts a created-but-empty row; a non-repeatable parent counts a filled descendant — **including an instantiated repeatable descendant row**, not only a valued field *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §4, locked by `RequirednessDiffTest`)*. The required-and-empty "unknown" is branch-scoped like any formal error ([§3](#3-formal-errors-and-the-unknown-state)): `[ReqNum] < 100` is suppressed, but `[ReqNum] < 100 Or <true>` still fires.

### The index field's auto-generated checks — mandatory and unique

A repeatable group's `indexFieldName` arms two automatic checks with no authored rule ([§9](#9-repetition-iteration-and-cross-array-evaluation) covers the join it keys): the index field is **auto-required** with parent-filled semantics, and its values are auto-checked for **uniqueness** — the kernel emits `uniqueIndex` on **every** row whose non-empty index value duplicates another's (not all-but-first), scoped **per parent row**, as a VALUE error; an empty index value is excluded from the uniqueness check and draws `mandatoryField` instead *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §4, locked by `UniquenessDiffTest` + `RequirednessDiffTest`)*. A duplicate-flagged cell enters the third state ([§3](#3-formal-errors-and-the-unknown-state)) **field-locally**: rules reading *that* field are suppressed (branch-scoped) while a sibling field's rules on the same rows still fire, and an aggregate or count over a starred index column carrying any duplicated or mandatory-empty cell is non-evaluable *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §4 / IF86, locked by `UniqSuppressionDiffTest` + `IndexAggregateDiffTest`)*. Because the kernel generates both checks, a model must not also carry the equivalent authored rules — they would double up *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) "A group's index field auto-generates its uniqueness + mandatory rules", locked by `IndexFieldLawsTest`)*.

*Reference: Appendix → Validation and formal validation → Required by Checkbox; Appendix → Mandatory fields have an asterisk in their label → Required checkbox / The star method for validation rules.*

---

## 5. Numbers and decimals

### `==` / `!=` require equal decimal scale (checked at parse time)

By default `==` and `!=` are not permitted between two numbers whose maximum decimal places differ; the rule is rejected when it is parsed, based on the model-declared scales (not the runtime values).

```
[Product] == [Factor1] * [Factor2]                  ;; rejected if scales differ
[Product] == RoundAccounting([Factor1] * [Factor2], 3)   ;; permitted
```

The maximum scale can grow during multiplication (e.g. two 3-decimal factors yield a 6-decimal product), which is what makes the first form fail. A documented rewrite for an exact comparison is `[F] <= [G] And [G] <= [F]`.

Three verified boundaries of the gate: it applies to **field/expression operands only** — a numeric **literal** is scale-exempt (`== 0`, `== 0.00`, `<= 0.000` are all accepted against any scale), so scale-explicit literals are a rendering nicety, not a correctness requirement; **ordering** (`<`, `<=`, `>`, `>=`) is scale-exempt even field-vs-field; and an expression's *derived* scale is what the gate sees (division and power derive an unknown scale, the `?` in the message), so wrap in a rounding or suppress. **Strings** compare field-vs-field with `==`/`!=` only — ordering draws `MVK_INVALID_TYPE_FOR_COMPARISON` *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §5, locked by `NumberLawsTest`)*.

The scale check is the one diagnostic you can waive inline: begin the rule condition with `@SuppressWarning(MVK_INVALID_COMPARE_DEC_PLACES)` to allow an `==`/`!=` across differing scales (e.g. comparing a checksum). It is the **only** suppressible code — naming any other (`@SuppressWarning(MVK_…)`) raises `MVK_INVALID_SUPPRESSED_WARNING`.

*Reference: Operators and language constructs → Comparison operations → Comparing numbers; Appendix → Parser messages → Arithmetic.*

### Rounding modes

There are three rounding families, each with a value-construct variant (`…Value(Field)`, which takes a `Number` field; `RoundAccounting([F])` and `RoundAccountingValue(F)` are equivalent). The optional `DecimalPlaces` argument is an integer from 1 to 14.

- **`RoundDown` / `RoundDownValue`** — always toward **−∞** (the value is never increased).
- **`RoundUp` / `RoundUpValue`** — always toward **+∞** (the value is never decreased).
- **`RoundAccounting` / `RoundAccountingValue`** — to the **nearest** value; when the two neighbours are equidistant, **away from zero**. This is *not* banker's rounding (round-half-to-even): for an equidistant value such as `2.5`, `RoundAccounting` gives `3` (and `-2.5` gives `-3`), whereas banker's rounding would give `2` and `-2`.

Worked values (no `DecimalPlaces` argument), from the guide:

| input | `RoundDown` | `RoundUp` | `RoundAccounting` |
|---|---|---|---|
| `-1.5` | `-2` | `-1` | `-2` |
| `1.777` | `1` | `2` | `2` |
| `-1.777` | `-2` | `-1` | `-2` |
| `2/3` | `0` | `1` | `1` |
| `1.5` | `1` | `2` | `2` |

Note that for negatives, "down"/"up" follow the number line, not magnitude: `RoundUp(-1.777) = -1` (closer to zero), `RoundDown(-1.777) = -2`.

*Reference: Arithmetic language constructs → Rounding.*

### Division by zero and power-operator edge cases evaluate quietly

A division by zero is not executed; the comparison containing it evaluates to `false` (never an error). Similarly, the power operator is not evaluated for some inputs (e.g. `0` to a negative power, or an exponent outside ±1000), so a rule containing it may simply not fire — indistinguishable from a satisfied condition unless tested for explicitly.

*Reference: Arithmetic operations → Treatment of division by zero; Arithmetic operations → Examples for using the Power Operator.*

### Other numeric constraints

- At most one division per calculation without grouping braces: `{ [G] / [F] } / 2`. Powers cannot be nested without brackets.
- Input numbers may have at most 15 digits (checked before each validation); internal arithmetic uses higher precision.
- A Number field's scale is **always bounded** — there is no unbounded-scale decimal: a model that omits `maxFractionalDigits` gets scale `0`, never unbounded, never rejected *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §5, locked by `modelkit.NumberScaleKernelLawTest`)*.
- Tolerance comparisons exist only at fixed thresholds (`DiffersWithToleranceRange1/2/5/10`) and are satisfied when the difference is **strictly outside** the tolerance — `|a − b| > N`, a difference of exactly `N` does not fire. An empty numeric operand reads `0` ([§2](#2-non-specified-empty-values)); a fired tolerance comparison types like `==` (either operand's fill can close the band) *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §5, locked by `ToleranceComparisonLawsTest` + `ToleranceDiffTest`)*.

### Internal precision — the scale-19 comparison rescale and the 50-digit arithmetic cap

- Every NUMBER **comparison** (ordering, equality, tolerance) rescales both operands to **scale 19 `HALF_UP`** before comparing; `min`/`max` *selection* stays full-precision *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §5, locked by `PrecisionDiffTest`)*.
- Arithmetic `×`/`÷`/`^` rounds to a **50-digit `MathContext`** (`+`/`−` stay exact) — reachable through high-scale multiplication, and what makes `÷` terminate at all *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §5, locked by `ArithmeticDiffTest` + `DivPowDiffTest`)*.
- A scale-19 `HALF_UP` pre-round precedes every target-scale rounding: `RoundDown([q]/3*3, 0) == [q]` fires for `q = 1` because the internal `0.99…9` pre-rounds to `1` before the floor *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §5, locked by `RoundingLawsTest`)*.

*Reference: Arithmetic operations → Accuracy of arithmetic operations; Operators and language constructs → Comparison operations.*

---

## 6. Dates and time

### Constant format and string/date ambiguity

Date constants are written `DD.MM.YYYY` (day first) regardless of locale, and the decimal separator is always `.`. A string literal that matches the date format is interpreted as a **date** constant, which prevents using it in string concatenation:

```
"30.11." + [YearString]      ;; invalid: "30.11." is read as a date
"30." + "11." + [YearString] ;; valid
```

The literal is typed **by its own syntax, not by context**: `[StringField] == "15.06.2024"` is rejected (string vs a date constant), a `dd.MM.yyyy` literal compares chronologically to a DATE field *whatever the field's stored format*, and it rides in argument position (`DifferenceInDays(OrderDate, "01.01.2022")`) — while inside a value *list* a date-looking entry stays a plain string. An **ISO-format literal** (`"2022-01-01"`) is lexed as a string, never a date. The **omitted-year form** (`"13.07."`, trailing dot) is a reference-year feature: the kernel injects the model's **Base Year** (not a floor), and a model without one rejects it *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §6 / IF52, locked by `DateLiteralDiffTest` + `OmittedYearDateDiffTest`)*.

*Reference: Constants and data types.*

### Difference and addition are not symmetric

`DifferenceInMonths` / `DifferenceInYears` return the largest number of months/years that can be added to the first date without passing the second — a floor, not a calendar count:

```
DifferenceInMonths("31.01.2010", "30.03.2010")   ;; returns 1, not 2
```

`AddYears` maps the last day of February to the last day of February (`AddYears("28.02.2023", 1)` → `29.02.2024`), whereas `AddMonths` preserves the day number (`AddMonths("28.02.2011", 12)` → `28.02.2012`); the two therefore disagree across a leap-year boundary, and a naive age check built on them is wrong around leap days. Fractional offsets are truncated, not rounded.

The exact calendar corrections, pinned: `AddYears` corrects the Feb-28 boundary *into* a leap year (`28.02.1999 + 1 year = 29.02.2000`, not the plain calendar-add `28.02.2000`), and `AddMonths` clamps end-of-month overflow to the shorter month's last day (`31.01.2008 + 1 month = 29.02.2008`), never carrying into the next month *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §6, locked by `DateArithmeticLawsTest`)*. The **sub-day** family reduces to integer seconds since epoch: `AddHours`/`AddMinutes`/`AddSeconds` carry across the day boundary, and `DifferenceIn{Hours,Minutes,Seconds}(a, b)` is `(epoch(b) − epoch(a)) / unit` **truncated toward zero** — a reverse difference of −5 h 30 m is `-5`, not `-6`; the argument order is `b − a`, like `DifferenceInDays` *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §6, locked by `DateTimeArithmeticDiffTest`)*.

*Reference: Date and Time language constructs → DifferenceInMonths / AddMonths / AddYears.*

### Constructing dates, and checking validity

`Date(Day, Month, Year)` builds a date from numeric parts (there are also a two-argument form that supplies the year from the model **Base Year**, and a four-argument century form); `Time(...)` and `DateTime(...)` construct the corresponding values. A constructed date is **invalid** if any referenced part is unfilled, or the parts don't form a real calendar date — `Date(31, 11, 2000)` (November has 30 days) or 30 February. `Valid(...)` / `Invalid(...)` test exactly that:

```
Invalid(Date(Day, Month, Year))   ;; fires on 31.11, 30.02, an unfilled part, …
```

`Valid(Field, "CustomTypeName")` is the other form: it runs the project's registered validator for that custom type against the field. (Operand-format requirements for the `Date(...)` parts — bounded integer fields, or digit-patterned strings — are an authoring concern documented in `KERNEL-GRAMMAR.md`.)

The two predicates are **not complements**, and the construction has a defined non-value: an unreal `Date(...)` is **non-evaluable** — it does not roll over and does not keep an out-of-range value, so an extractor over it yields nothing. `Valid(Date(...))` = all parts present ∧ well-formed ∧ the date is real, fired VALUE; `Invalid(Date(...))` = parts well-formed ∧ (not real ∨ some part empty) — its polarity is directional (an empty fillable part → OMISSION, a present-but-unreal date → VALUE), and it even fires on an all-empty row. A **malformed** part suppresses *both* predicates (each goes UNKNOWN; the formal error fires instead) — they are complementary only when no part is malformed. To fire `Invalid` only on a complete-but-impossible date, prefix `AllFieldsFilled(...)` *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §6, locked by `DateConstructionDiffTest` + `DateValidityLawsTest`)*.

*Reference: Date and Time language constructs → Date and time constructs → Date (Date with three parameters / Valid or invalid dates); Miscellaneous → Valid and Invalid.*

### Other date behaviours

- **Dates before 16 October 1583 are invalid — as stored or computed *values*.** The floor is always-on and exact (strict *before*: 1583-10-15 errors, 1583-10-16 is valid; a day-optional value completes its omitted day to `01` *before* the check): a stored value below it is the formal error `datumFalsch` (suppressing referencing rules, [§3](#3-formal-errors-and-the-unknown-state)), a computed one is ERRORED, and a date range floors its start the same way. It is distinct from the *opt-in* "younger than 1900" additional check *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §3 / IF88, locked by `ComputedDateGregorianFloorDiffTest`)*. The floor is **not** a runtime criterion for the `Date(...)` *construction*: `Invalid(Date(1,1,1500))` does not fire in 30.8.1 *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §6, probe-evidence)*.
- **A date value is stored and formal-checked in its field's declared `format`, not a canonical wire format** — the same calendar date is a well-formed value in one field and `datumFormatFalsch` in another; a consumer must read each field's `format` to parse its dates *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §3, locked by `PlainDateDiffTest`)*.
- `DifferenceInHours/Minutes/Seconds` across a daylight-saving transition can depend on the target language of the generated code.
- Date *fragments* (partial dates) are comparable only if both formats include the year or both omit it; a missing component defaults to `01`. A model-level **Base Year** changes these rules globally. At evaluation a fragment field's value **completes to the earliest concrete date** — missing month → `01`, missing day → `01`, missing year → the declared base year — and then compares, shifts, or extracts as a plain date; the completion is shared by both kernel strategies *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §6 / IF57, locked by `DateFragmentComparisonDiffTest`)*. A **fragment-format date range** completes its two endpoints **asymmetrically** — the start to the interval's earliest date, the end to its latest (missing month → `12`, missing day → the month's last day, leap-aware) — so a `yyyy` range `2024/2025` denotes `2024-01-01 .. 2025-12-31` *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §6 / IF118, locked by `FragmentRangeEvalDiffTest`)*.
- A `DateType` with `datePrecision: DAY_OPTIONAL` omits the day as a literal `00` in the day position (`00.06.2024` — the only well-formed omission shape). Such a value is not a single concrete date; `ValueAsDate(field, FirstDay|LastDay)` resolves it (day `01` / the month's last day; a full value resolves to itself), and `ValueAsDate` is valid only as a direct comparison operand *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §6, locked by `ValueAsDateDiffTest`)*.
- `Today` ignores the time of day; `Now` is not recommended with `==` and is forbidden in computations. `Now` compares only to an operand whose format carries a time component — against a plain date field the rejection fires at **code generation**, not at the parse/consistency check *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §6, locked by `NowDiffTest`; the codegen-tier evidence is probe-recorded)*.
- **`BaseYear` is polymorphic by the other operand**: against a date it is the base-year *start* (`2020-01-01`); against a number it is the year *number* (`2020`); as a range source, `StartOfDateRange(BaseYear)` / `EndOfDateRange(BaseYear)` are `01.01` / `31.12` of the reference year. It is model config, not the clock; VALUE polarity *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §6, locked by `BaseYearDiffTest` + `adapter.PointInTimeSourceReadTest`)*.
- Date ranges support only `==` / `!=` (not ordering), and `DateRange(...)` cannot be nested inside other constructs; `DateRangesOverlap` treats endpoints as inclusive.
- **The overlap predicates differ in shape**: `DateRangesOverlap(op0, op1, …)` is **any-pair** among *all* operands' kept, filled ranges (one growing set — a list-internal pair fires even when the scalar operand is disjoint, and a same-cell self-pair via scalar + star fires every filled row), while `AtLeastOneDateRangeOverlaps(scalar In list…)` is genuinely **scalar-vs-list** (two list cells overlapping each other do not fire). Intervals are **closed** — back-to-back periods sharing a boundary day overlap; an inverted range (start after end) never overlaps; empty/malformed cells are skipped *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §6 / IF72, locked by `DateRangeOverlapSemanticsDiffTest` + `DateRangeOverlapLawsTest`)*.
- **DATE_TIME rides the date format definition**: the date extractors (`DayFromDate`, …) and the date differences accept a datetime operand and see its DATE part (times ignored); the date *arithmetic* family (`AddYears`, …) over a datetime is rejected at code generation *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §6 / IF95, locked by `DateFunctionsOverDateTimeDiffTest`)*.

*Reference: Date and Time language constructs → Date fragments / Today / Now / Date Ranges.*

---

## 7. Strings and patterns

- String length counts Unicode combining sequences as multiple characters, so one visible glyph can occupy several characters of a length limit.
- `PatternMatched` / `PatternViolated` evaluate the **whole** value (anchored), use only the regular-expression subset portable across the target languages, and can be a performance/security risk if written to backtrack (e.g. `(a+)+`). The portable subset is **enforced, not advisory**: a Java-valid lookbehind is rejected with `MVK_INVALID_PATTERN`, so a Java-side `Pattern.compile` pre-check is a necessary condition only *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §7, locked by `PatternLawsTest`)*. A fired pattern comparison is always a VALUE error (there is no fillable branch), and an empty string violates no pattern ([§2](#2-non-specified-empty-values)) *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §2, locked by `EmptyValueLawsTest`)*.
- `+` is overloaded — numeric **addition** between Number operands, string **concatenation** between strings. The guide documents no general operand-dispatch rule beyond one pitfall: a string literal shaped like a date parses as a *date constant* and so cannot be concatenated (see [§6](#6-dates-and-time)).

*Reference: Models → Field types → String; Operators and language constructs → String operations; Miscellaneous → PatternMatched and PatternViolated.*

---

## 8. Enumerations

- Rule conditions compare the stored enumeration **value**, not the displayed text: write `[Country] == "F"`, not `[Country] == "France"`.
- An enumeration that defines localized texts cannot be compared with a plain string or with an enumeration that has no texts; comparing two text-bearing enumerations also requires their values and texts to be mutually consistent. A plain string ↔ *texts-less* enum comparison **is** allowed — the presence of display texts, not the stored value, governs comparability *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §8, locked by `EnumComparabilityLawsTest`)*.
- Enumeration values can carry named category attributes, read with the `->` operator: `[Country -> AdministrationArea] != "EU"`. The category mapping is **positional** (`values[i]` categorizes enum value *i*) and many-to-one — the comparison fires for *every* value sharing the category value; an empty enum rides the not-evaluated track, category hop or not *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §8, locked by `EnumCategoryLawsTest` + `EnumCategoryDiffTest`)*.

### The value-list quantifiers — per-cell classification, and the `No` poison asymmetry

`AtLeastOne` / `No` / `NotAll` `…FieldValue(s)IncludedInValueList(f1, f2 In v1, …)` expand **both** sides per cell (a starred entry contributes one cell per instantiated row) and classify each by the three-way state — filled / empty / not-check-relevant (UNKNOWN, [§3](#3-formal-errors-and-the-unknown-state)):

- **`AtLeastOne`** fires iff some filled cell's value is in the set; empty and UNKNOWN cells and members are skipped outright; an empty value *set* → no fire. Fired polarity VALUE.
- **`No`** fires iff no filled cell's value is in the set — including when nothing is filled at all (the sole member that can fire empty); an UNKNOWN fields-cell **or** values member **poisons** it (no fire). Fired polarity OMISSION on any empty fields-cell, un-instantiated declared tail, or empty values member — else VALUE.
- **`NotAll`** needs a filled fields-cell and fires iff some filled cell's value is *not* in the set; an UNKNOWN values member poisons, an UNKNOWN fields-cell is merely skipped — the `No`-vs-`NotAll` asymmetry; OMISSION only on the values side's account.
- An empty values **member** contributes nothing to the set (never a substituted `0`) but flags a fired `No`/`NotAll` OMISSION. A `Having` filter is accepted on either side, drops rows **before** the per-cell classification, and escalates a fired result to OMISSION unconditionally ([§12](#12-the-validation-model)).

*(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §8 / IF60 + IF74, locked by `ValueListQuantifierDiffTest` + `ValueListQuantifierStarDiffTest` + `EntityValueListDiffTest` + `ValueListQuantifierHavingDiffTest`)*

*Reference: Operators and language constructs → Comparison operations → Comparing strings and enumerations; The Field value operator.*

---

## 9. Repetition, iteration, and cross-array evaluation

The repetition model is the kernel's most distinctive — and most easily mis-authored — semantics. A flat rule fires zero or one time; a rule over repeatable data fires *per row*, and several constructs turn it into what is effectively a small query language over arrays.

### When a rule iterates, and where its error lands

A rule **iterates when it references repeatable fields**: its iteration scope is **the set of repeatable fields the condition and error field reference**, never the rule node's placement — and the error field must *share* that scope (a genuine cross-cardinality split is rejected, `MVK_ERROR_FIELD_NOT_IN_RULEGROUP`) *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §9, locked by `IterationLawsTest`)*. The rule is then evaluated **once per repetition present in the document** (always at least once), and each resulting message attaches to **that specific row**. For example, with rule group `Casino`, error field `Round` inside a repeatable `Poker` group, `FieldFilled(Poker/Round) And [Poker/Win_Loss] >= 0` is evaluated once per existing round and reports on the offending round.

Two verified boundary rules sharpen this: the **error entity alone is not an anchor** — a rule whose error field sits deeper-repeatable than its condition references keeps the references' scope *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §9 / IF106, locked by `CollectivelyFilledGroupScopeDiffTest`)* — and a rule with **no repeatable (non-starred) reference evaluates exactly once**, anchored at its error entity, regardless of its declaring container's instance content; the declaring group matters only for resolving relative references *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §9 / IF103, locked by `CrossRootRuleScopeDiffTest`)*.

The error field is also subject to a structural rule: it **must be referenced by the condition**, directly or indirectly (an enclosing `GroupFilled(Group)` counts), or the model is rejected with `MVK_ERROR_FIELD_NOT_REFERENCED`. So the error field both *selects* the iteration and *must appear* in the logic.

*Reference: Rules → Rules and repeatability → Iteration; Rules → Error Field.*

### Parallel iteration — joining two repeatable groups by key

When a rule references two repeatable groups where neither is nested in the other, they are iterated **jointly, keyed by an Index Field** that must exist on each group and **share the same name and type**. This is an *outer join* over the index values: a group with no row for a given index value counts as "not specified" for that iteration.

```
FieldsNotCollectivelyFilled(Demand/Units, Capacity/Units)   ;; iterated per shared Warehouse index value
```

Constraints (from the guide): at most **one** index field on the error field's path and on each parallel-iterated group; parallel iteration is **not allowed over rules using `RepetitionNotUnique`**; and **negative conditions are not allowed in a parallel iteration** except alongside a positive condition.

The join's mechanics, verified: the iteration value-set is the **union** of the joined groups' index values; an unmatched side reads "not specified", its pointer carrying the fixed sentinel repetition index `-5` (`IIdentifier.UNKNOWN`). An **invalid** index value — duplicated or empty — marks that group's index invalid for the parent, so references into it read three-valued UNKNOWN for unmatched values, which *suppresses* a negative operator like `FieldsNotCollectivelyFilled`. Non-repeatable intermediate groups are transparent to the join; a repeatable non-indexed *frame* is legal above exactly one joined group *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §9 / IF19, locked by `ParallelIterationDiffTest`)*.

*Reference: Rules → Rules and repeatability → Parallel Iteration.*

### The filter operator `Having`, the `$` correlation, and aggregation

`Having` filters a repetition list (a `*`-path) down to the rows where a condition holds, before an aggregate consumes it:

```
Sum(Products*/Quantity Having [Products/ProductName] == "N33") > 100
```

The **`$` operator** — usable **only inside a filter condition** — pins a reference to the **current outer repetition** of the iterating rule, turning the filter into a *correlated subquery* (e.g. "holidays whose date equals the current stop's ETA"). Not every reference in a filter may be `$`-marked. `$` is not optional sugar: a filter may only iterate the repetition levels the filtered field stars, so `$` is the **sole** way a filter references an out-of-scope (outer) field *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §9, locked by `CorrelationLawsTest`)*.

### Where a star binds — the anchor rules

- A starred operand read from an iterating row binds the enclosing indices only for the repeatable levels **strictly above the (first) `*`** — the starred level itself always iterates its full extent. A **same-group star** therefore spans *all* rows, not the current one (only `$` correlates; a bare star never does), and a multi-star operand `A*/B*/F` binds at the levels above `A` *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §9 / IF93 + IF94, locked by `ComputeStarAggregatePoisonDiffTest` + `MultiStarFilteredCountDiffTest`)*.
- A **cross-subtree** star binds only along the **shared named ancestry** — an enclosing row's index is never applied positionally to an unrelated chain, so a cross-subtree count or presence reads identically from every iterating row *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §9 / IF97, locked by `CrossSubtreeGroupStarDiffTest`)*.
- A bare (unstarred) reference to an **index-keyed** repeatable group in a group-scope quantifier is an iteration anchor of its own — ancestor-or-self, per row, judging the *current* row's cells; a non-indexed repeatable group needs the `*` *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §9 / IF106, locked by `CollectivelyFilledGroupScopeDiffTest`)*.

Aggregation handles missing rows **per function**: **`Sum` substitutes `0`** for an unspecified field, whereas **`MaxValue` / `MinValue` ignore** unspecified fields (a Number list with none specified yields `0`; a Date list yields an empty value). The filter condition may **not** contain `CustomCondition`, `RepetitionNotUnique`, a semantic index, a parallel iteration, or a nested filter.

*Reference: Rules → Rules and repeatability → Filter operator (and → Iteration in filter condition); Arithmetic language constructs → Sum / Maximum and Minimum.*

### Other repetition rules

- `GroupFilled(G)` is satisfied for an **empty repeat row that has been created** — for a *repeatable* `G`, "filled" means "the row exists," not "the row has data." For a **non-repeatable** `G`, "filled" means *content*: a filled descendant field **or an instantiated repeatable descendant row**; the group's own structural presence is not content *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §9 / IF53, locked by `GroupFilledDiffTest` + `NonRepGroupFilledContentDiffTest`)*.
- Negative conditions (those that hold when nothing is specified) are not permitted in combination with repeatable groups; the parser rejects them (`MVK_NEG_CONDITION_IN_ITERATION`) unless guarded, e.g. `GroupFilled(K) And AtLeastOneFieldFilled(K/F)`.
- List predicates over a repeatable group with `*` consider **all possible** repetitions, including unspecified ones, not only those that hold data.
- An instance **beyond the group's declared repeatability** draws the structural errors `zuGrosseZeile` / `zuGrosseKontextnummer` (VALUE), and the over-limit rows' ordinary checks are suppressed ([§3](#3-formal-errors-and-the-unknown-state)) *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §9 / IF27, locked by `OverRepetitionDiffTest`)*.
- **"At least N rows" is expressible** via the starred single-group count: `NumberOfFilledGroups(G*)` counts one repeatable group's instantiated rows *including zero*, so `NumberOfFilledGroups(Items*) < N` fires at 0…N−1 rows (the aggregate-consumed star does not put the rule into per-row iteration); "at most N" stays structural (`repeatability`) *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §9 / IF46, locked by `NumberOfFilledGroupsStarDiffTest` + `RootGroupCountLawsTest`)*.
- `SumOfProducts(A*, B*)` zips its two same-group fields' rows and sums the per-row products; an empty cell reads `0` (its product is `0`), a malformed cell suppresses *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §9, locked by `SumOfProductsDiffTest`)*.
- `FieldValuesNotUnique(f1, f2, …)` — or the starred `FieldValuesNotUnique(G*/f)` over a repetition's rows — fires when two of the operands' **present** values are equal: empties are skipped (two empties are not a duplicate), equality is the **typed** comparison (a scale-0 `5` equals a scale-2 `5.00`), and a formally-invalid operand suppresses the rule *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §4, locked by `FieldValuesNotUniqueDiffTest`)*.

### `RepetitionNotUnique`, precisely

`RepetitionNotUnique(... @From Group)` checks uniqueness within the chosen reference group; omitting or changing `@From` changes the scope (e.g. unique per order vs. unique across all orders). The verified mechanics:

- It fires **per row whose (composite) key duplicates another row's** within the scope — a composite key compares as the tuple. An **all-empty** key row is skipped, but among participants (≥ 1 key field filled) **empties match empties**. Polarity: all-filled duplicates are VALUE; a duplicate with an empty (fillable) key component is OMISSION *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §9, locked by `RepetitionNotUniqueDiffTest`)*.
- It is a per-row **3VL predicate**: a guard conjunct gates *firing*, but the duplicate cache is built over the full scope **independent of the guard** — a guard-false duplicate does not fire yet still makes its partner a duplicate *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §9 / IF62, locked by `RepetitionNotUniqueGuardedDiffTest`)*.
- A **formally-invalid key cell drops its row from the cache**, per key cell: malformed, duplicate-index, over-repetition, and **required-empty** cells all count as unknown — the distinguishing property is *requiredness, not emptiness* (an optional-empty cell is a valid null participant); a formal error on a *non-key* field of the same row does not drop it *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §9 / IF64, locked by `RepetitionNotUniqueFormalKeyDiffTest`)*.
- The **default reference group** (no `@From`) is the *first repeatable group below the rule group* on the key path, and uniqueness is checked per that group's **parent row** — a key two repeatable levels deep is checked across all inner rows of the whole outer scope, not per intermediate group; `@From` overrides the reference group *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §9, locked by `RepetitionNotUniqueNestedDiffTest`)*.

*Reference: Predicate language constructs for Groups → GroupFilled and GroupNotFilled; Predicate language constructs for Field lists → RepetitionNotUnique.*

---

## 10. Paths and references

- A field or group named like a keyword (`And`, `Date`, `Today`, …) must be single-quoted in a path: `FieldFilled(Order/'Date')`.
- Paths are **absolute** (`/Order/Quantity`) or **relative** (`../Other`). The guide favours relative paths within a rule's group — they are shorter and survive the group being moved. One combination *requires* absolute, though: a relative `..` up-navigation may not be combined with the `*` asterisk (`MVK_INVALID_WILDCARD_REL` — "use the absolute path notation"), so reaching upward *and* across all repetitions must be written absolutely (e.g. `/A*/F`) — which is why `CrossArrayDemo` uses absolute paths. A field may also be referenced by a bare **short name** (e.g. `[Quantity]`) when the model sets `fieldRefByShortNameAllowed`, as this demo does.
- **A bare segment resolves declaring-group-first, then upward, then (flag-gated) model-wide.** A name not found directly under the rule group is looked up in **ancestor** groups — `[Tier]` from a rule at `/Subscription/Addons` binds the ancestor's field with no `../` *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §10, locked by `dsl.PathPolicyTest`)*. Only on a miss, for a relative reference with `fieldRefByShortNameAllowed` on, a model-wide **unique field short-name** lookup runs: > 1 match is `MVK_FIELDNAME_NOT_UNIQUE` (never a silent pick), 0 falls through to `MVK_INVALID_ENTITY`; the serialized condition retains the short name *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §10 / IF119, locked by `ShortNameRefDiffTest` + `PathLawsTest`)*.
- In a **`..Name/rest`** path the up-count comes only from the `..` tokens; the trailing name merely *names* (and validates) the ancestor landed on — a matching `..Name/rest` resolves exactly like `../rest` *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §10 / IF55, locked by `NamedAncestorPathDiffTest`)*.
- The asterisk `*` flattens a repeatable group into the list of its rows; if an outer repeatable group uses `*`, every repeatable group below it in the path must also use `*`.
- The `$` operator (a correlated reference to the current row) may be used only inside a filter condition, and not every reference in the filter may be `$`-marked.
- `CurrentRepetition(G) == n` cannot stand alone; it must be combined with a condition that ensures the repetition is filled.
- **A row is addressed by semantic key, never by position**: the language has no `[Field At n]`, no neighbour offset — `CurrentRepetition` *yields* an index to compare (or `$`-correlate), never one to select with; the only row-addressing form is the semantic index `[Field For key]` *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §9, grammar-verified; the construct inventory is CI-locked by `adapter.KernelConstructParityTest`)*.
- The semantic index (`Group For "value"`) is unsupported in several combinations (multiple repetition layers, several index fields, under an asterisk, and with certain predicates).
- **What a semantic-index read yields**: a matched, filled cell is its value; **no matching row and a matched-but-empty cell both read as the kind's empty value** ([§2](#2-non-specified-empty-values) — a number reads the fillable `0`, a fired `== 0` types OMISSION), never "unknown". As a **presence** operand, an absent row is judged like an empty cell (FALSE — `FieldNotFilled(X For "k")` fires when no row carries the key); a malformed matched cell or a formally-invalid key field is UNKNOWN *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §9 / IF59, locked by `SemanticIndexDiffTest` + `SemanticIndexPresenceDiffTest`)*. Validation-side indexed reads are judged **per lookup** (a malformed key cell elsewhere in the column does not suppress a clean match; an unresolvable no-match over a column with any not-check-relevant cell suppresses the referencing rule) — the compute side is stricter, per column ([§11](#11-computations)) *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §11 / IF115, locked by `IndexedReadSuppressionDiffTest`)*.

*Reference: Rule condition; Models → Paths; Repeatable Groups and Fields → Asterisk operator; Filter operator; Detecting the current repetition; Parser messages → Paths (`MVK_INVALID_WILDCARD_REL`).*

---

## 11. Computations

- **Every computation rule also acts as a validation rule**: if the computed field is filled, its value must equal what the computation produces, or an error is reported. Assigning a time-dependent value such as `Now` to a computed field therefore reports an error as soon as time passes.
- The computed field's declared decimal scale must equal the calculation's scale; otherwise the model is rejected (wrap the calculation in a rounding construct to match).
- A computation table's preconditions must be mutually exclusive, and the computed field may not appear in any precondition.
- A non-repeatable computed field is calculated even when all its inputs are empty (yielding `0` or an empty/space string); add an `AllFieldsFilled(...)` precondition to suppress that. Indirect calculation cycles among computed fields are possible and are not always easy to detect.

*Reference: Computation Rules → Computation Rules as Validation Rules / Number of decimal places / Preconditions / Calculation cycles.*

### One operation, three outcomes — and the operand states that decide them

Validation and computation are **separate operations**: `validateFull` never computes first, and the consumer composes the form-engine flow compute → apply → validate itself, where the apply step writes back only clean values and *empties* errored and cleared instances *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §11/§12 / IF45, locked by `EvalDocSeamLawsTest` + `ComputeApplyDiffTest`)*. Per computed cell the outcome is a **VALUE**, **CLEARED** (no value — a stale stored value is wiped, never left standing), or **ERRORED** (a value the target cannot legally hold, returned but flagged) — the vocabulary [CONFORMANCE-CORPUS-SPEC.md](CONFORMANCE-CORPUS-SPEC.md) §7 fixes. Which outcome an operand's state produces follows [§2](#2-non-specified-empty-values)'s per-kind split, with compute's own twists:

| an operand that is… | NUMBER | DATE | STRING in a concatenation |
|---|---|---|---|
| **empty** | reads `0` — the computation produces a VALUE *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §11, locked by `ComputeDocVerbTest`)* | the operation is not evaluated — CLEARED *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §2, locked by `DateRangeComputationLawsTest`)* | concatenates as `""` — produces a VALUE *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §11 / IF81, locked by `ConcatInvalidOperandDiffTest`)* |
| **required and empty** | still `0` — the mandatory error is validation's concern, not compute's *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §11, locked by `ComputeInvalidOperandDiffTest`)* | as empty | still `""` *(verified: IF81, locked by `ConcatInvalidOperandDiffTest`)* |
| **filled but formally invalid** | CLEARED — and the target is poisoned for dependents (below) *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §11 / IF76 + IF78, locked by `ComputeInvalidOperandDiffTest`)* | CLEARED + poison — the rule is type-agnostic *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §11 / IF78)* | CLEARED + poison *(verified: IF81, locked by `ConcatInvalidOperandDiffTest`)* |

The guard consequence cuts both ways: over DATE operands an `AllFieldsFilled` precondition is *redundant* — a false precondition clears exactly as the unevaluated operation does *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §2, locked by `DateRangeComputationLawsTest`)* — while over a NUMBER operand the same guard *changes behaviour*: guarded clears where unguarded computes `0` *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §11, locked by `ComputeDocVerbTest`)*.

The STRING column above is the *concatenation* position's. A **bare string copy** (`X = [S]`, one operand, no `+`) of an empty operand computes nothing — CLEARED, never a stored `""` *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §2, locked by `StringCopyComputeDiffTest`)*. The `FieldValueAsString` coercion of a not-given read follows the same per-context split — a standalone copy CLEARS (the store path treats an empty string value as "no calculation was done"), a concatenation gets `""`; the coercion's operand must be a NUMBER/AMOUNT field (a string copies bare) *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §11 / IF109, locked by `FieldValueAsStringComputeDiffTest`)*.

### Two kinds of "no value" — the empty cascade vs the poison

A CLEARED cell is not one thing; *why* it cleared decides what a downstream computation sees:

- **A precondition-clear cascades as EMPTY.** A field its own computation cleared silently — false `commonPrecondition`, no matching alternative, an unevaluated operation — reads as plainly *empty* in a dependent: `FieldFilled(dep)` is false, a number operand reads empty-as-0. The mechanism is that a calculation run strips every calculated field's *input* value from its working data, so an un-computed calculated field reads empty regardless of what the document stored *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §11 / IF77, locked by `CascadeClearedDependencyDiffTest`)*.
- **An invalidity-clear POISONS.** Reading a formally-invalid cell (an invalid operand's target, an ERRORED computed value, [§3](#3-formal-errors-and-the-unknown-state)'s third state generally) throws inside the computing instance: it aborts, skips its remaining alternatives, produces no value, and is itself marked invalid — so its dependents' reads poison in turn. The poison is **read-driven and therefore order-dependent**: compute's `And`/`Or` collapse each conjunct two-valued and short-circuit (a false *or unknown* left side skips the right entirely), and the quantifier scans stop at their deciding cell — an invalid cell beyond the stop is never read and never poisons *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §11 / IF85 + IF40, locked by `ComputePoisonReadDiffTest` + `ComputeCascadeErroredDiffTest`)*.

What `compute` *reports* is a delta against the input, not a report-all: a VALUE only on typed change, CLEARED only when the input cell was filled, ERRORED unconditionally *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §11, locked by `ComputeGranularityTest` + `CorpusCaptureTest`)*.

### The stored form — a computed value lands as a string in the target's declared shape

A computed value is stored in the target field's own declared format: a DATE in that field's `format`, not ISO *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §3 / IF33, locked by `ComputeTargetFormatDiffTest`)*; a NUMBER through one store path shared by both kernel strategies — a scale-19 pre-round, then a render **padded to the target's `minFractionalDigits`** (`10` into a two-decimal field stores `10.00`) with *no* length cap, so an over-long result stores in full and is ERRORED by the ordinary digit checks rather than re-rounded to fit *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §11 / IF82 + IF92, locked by `ComputedNumberScaleDiffTest` + `ComputedNumberDigitOverflowDiffTest`)*. The stored form is what downstream sees: a dependent computation reads the padded string back *(verified: IF90, locked by `CascadeComputedOperandScaleDiffTest`)*, and the change-equality above is scale-sensitive — a computed `7.00` over a stored `7` counts as a change.

### Where a computation runs — the target's scope, and the parallel join

- A computation iterates over its **computed field's** repetition scope — where it *writes*, not where its node is declared — and an all-non-repeatable target computes exactly one instance even on an empty document, because a non-repeatable group is implicitly present *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §11 / IF89, locked by `CrossGroupConstantComputeScopeDiffTest`)*. The general law, shared with rules: the iteration scope is the set of referenced repeatable fields, never the node's placement *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §9, locked by `IterationLawsTest`)*.
- Alternatives evaluate top-to-bottom and the **first whose precondition holds** supplies the value; none holding → CLEARED. The mutual-exclusivity requirement above is authoring discipline the runtime does not enforce — overlapping preconditions resolve to the first declared *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §11 / IF21, locked by `ComputationPreconditionDiffTest` + `ComputationEvaluationLawsTest`)*. Model composition can hand one field several computations; they run in document order and the first non-empty outcome wins *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §11 / IF87, locked by `MultiComputationFieldDiffTest`)*.
- Operands reaching into sibling indexed groups run [§9](#9-repetition-iteration-and-cross-array-evaluation)'s parallel iteration: rows join by the shared **index-field value**, never by physical position; an unmatched side reads empty; the calculation never *creates* a repetition (a key present only on the side group computes nothing); and an invalid index cell in *any* joined group clears **every** instance of the computation — deliberately broader than strictly necessary *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §9/§11 / IF96, locked by `ComputeParallelIterationDiffTest`)*.
- A starred operand read from an iterating row binds the enclosing row only for levels *strictly above* the (first) star — a same-group star spans **all** rows, which is also how one poisoned sibling cell aborts every row's dependent *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §9 / IF93 + IF94, locked by `ComputeStarAggregatePoisonDiffTest` + `MultiStarFilteredCountDiffTest`)*.

### The implicit validation rule, precisely

The "also acts as a validation rule" bullet above has an exact generated shape: a field gate `FieldFilled(F) And`, the optional common precondition, then per alternative `(precondition And [F] != operation)`, `Or`-joined — so it fires only when the field is *filled* and the tier that would compute disagrees with the stored value, with the computation's own name as the fired error code. A cleared-vs-stored mismatch is deliberately not flagged (no tier's comparison holds), and the tier selection is the *same* logic the compute path runs, so the two operations cannot disagree about which alternative applies *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §11/§12 / IF65, locked by `ComputationValidationLawsTest` + `ComputationGuardedValidationDiffTest`)*.

---

## 12. The validation model

### Formal validation vs. rule validation

Formal validation is the set of field-type checks the kernel performs automatically (type, pattern, decimal places, required-by-checkbox). These carry the rule path `formalePruefung` (one of a handful of kernel-internal German identifiers) and make a field "unknown" while they stand. Author-written rules are separate and never block a field from evaluation. See [§3](#3-formal-errors-and-the-unknown-state).

### Severity is metadata: ERROR vs. WARNING vs. INFO

A rule's severity (`ERROR` / `WARNING` / `INFO`) is **metadata on the resulting message**, not part of evaluation — the condition fires identically whatever the severity. Only `ERROR`-severity messages make the document invalid: `IDocumentValidationResult.noErrorOccurred()` returns `true` even when `WARNING`s and `INFO`s are present (it considers only `ERROR`s). So a firing `WARNING` surfaces a message to the user without failing validation — useful for "are you sure?" advisories.

*Reference: Appendix → Validation and formal validation → Errors arising from validation rules; runtime API `IDocumentValidationResult.noErrorOccurred()` (`kernel-md-runtime-api`).*

### Every fired message is typed — VALUE vs OMISSION, "could filling something clear this?"

Beyond severity, every message `validateFull` raises carries a **message type** (`IMessage.getMessageType()`), a dimension the two-valued presentation elsewhere doesn't show: **OMISSION** — filling one or more currently-empty fields could satisfy the rule ("something is missing") — vs **VALUE** — no fill can; only changing a value helps ("what you entered is wrong"). Because empty operands participate in so many firings ([§2](#2-non-specified-empty-values)), the type is what tells a user *which* repair to attempt. Internally the evaluation result is three-state — fired-as-value / fired-as-omission / not-fired — with its own `And`/`Or` algebra and no negation combinator (the language has no `Not`, [§1](#1-truth-values-and-logical-evaluation)): under `And` **omission wins** (filling could still rescue the whole rule), under `Or` **value wins** (the value branch alone convicts) *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §12, locked by `ErrorTypeLawsTest`)*.

### The directional-fill machinery behind the typing

The type is not a per-operator constant — it is computed from **directional fillability**: every operand carries "could this result still grow / still shrink if something were filled", and the enclosing comparison's direction decides whether a fillable direction could clear the firing. The verified load-bearing pieces:

- **An empty number's fillability is asymmetric and sign-aware**: it can always grow, but can shrink only if the field is *signed* — and the trigger is `positivesOnly`, not `minValue`. So `[Unsigned] >= 0` fired on the empty substitution is a VALUE error (no fill breaks `>= 0` from above), while the signed twin is OMISSION *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §1/§3 / IF9, locked by `DirectionalPolarityDiffTest`)*.
- **Fill propagates through arithmetic and the value functions**: `+` combines both directions, `−` flips the subtrahend's, `×`/`÷` are sign-dependent, `^` follows base/exponent parity; `Round*` preserves the flags, `Abs` transforms them with the value's sign, operand-list `Min`/`Max` combine them directionally, `RangeAsNumber` is grow-only. So `[F] != [A] + [B]` with an empty `B` types OMISSION — the right side can still move *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §1/§3 / IF69 + IF71, locked by `ArithmeticFillPolarityDiffTest` + `FunctionFillPolarityDiffTest`)*.
- **Each aggregate combiner has its own directions**: `Sum` grows always and shrinks only when signed, `MaxValue` is grow-only, `MinValue` shrink-only *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §1/§3, locked by `AggDirectionalPolarityDiffTest`)*; and a starred aggregate's un-instantiated declared tail counts as fillable, keeping a firing OMISSION until the repetition is exhausted *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §9, locked by `AggregateUncertaintyLawsTest`)*.
- **The counts can only grow** (`NumberOfFilledFields` and family): a fired `count >= n` is VALUE — no fill can lower a count — while `count < n` stays OMISSION *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §1, locked by `MultiFieldAggregateDiffTest` + `NumberOfFilledDiffTest`)*.
- **Dates ride a symmetric combiner**: a fired date comparison types OMISSION iff either side is not-given, regardless of the comparison's direction *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §6 / IF58, locked by `DateAggregateDiffTest`)*.

### The same firing rule can carry either type

Because the type is computed from the data, one rule legitimately fires OMISSION on one document and VALUE on another. `NotExactlyOneFieldFilled(A, B)` fired at zero filled is OMISSION (filling one field reaches exactly one) but at two filled is VALUE (no fill gets back to one) *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §1, locked by `FieldListQuantifierDiffTest`)*. `FirstFilledValue` types OMISSION only when no row is filled or an instantiated empty row *precedes* the first filled one — empties after it are irrelevant, the first value is fixed *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §1/§3, locked by `FirstFilledValueDiffTest` + `FullMessageFuzzDiffTest`)*. And `CurrentRepetition` is a structural row index no fill can change — a fired comparison against it is always VALUE *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §1/§3, locked by `CurrentRepetitionDiffTest`)*. The scalar per-operator claims are carried machine-readably as the catalog's `polarity` facet; the compositional cases (arithmetic, the transforming functions, the sign-dependent aggregates) are truth tables over the operands and stay represented by their differential locks ([CATALOG-SPEC.md](CATALOG-SPEC.md) §3d).

### Full validation vs. partial validation

`validateFull` checks the whole document. `validatePart` checks a "relevant" subset (e.g. the current wizard page) and guarantees only one direction: it never reports an error that could only be fixed outside that subset. It does **not** guarantee a complete check of the relevant fields themselves — some checks may be skipped for performance, and which ones is an implementation detail that may change. A document that passes a partial validation may still fail a full validation on the same fields. A field referenced by a rule but living on another screen is excluded from the relevant set unless it has the `Global` flag set.

*Reference: Appendix → Full Validation and Partial Validation; Appendix → Validation and formal validation → Relevant fields.*

---

## 13. Error-message interpolation

- `$Field$` interpolates the field's **name/label**; `$Field.value$` interpolates its **value**. A literal `$` is written `$$`.
- For a **field** error text (as opposed to a rule error text) the tokens are literally `Field` and `Field.value`, independent of the actual field name.
- `$Field.value$` may be used only if the field is referenced in the condition at least once without an asterisk, and error-text paths may not contain asterisks.
- For an unspecified value, the interpolation yields `0` for numbers and an empty string for other types.

### What a fired message carries, and where the name comes from

- A fired message's `getErrorText` is the **interpolated end-user text** — the tokens already resolved against the evaluated document (the authored template stays in the model); only the runtime can produce it, because it depends on the data *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §3, locked by `adapter.MessageInterpolationTest`)*.
- `$Field$` resolves through a **caller-supplied `ILabelProvider`**, not a direct model-label read: a faithful provider returns the field's label for the locale, falling back to the name; with **no** provider configured the kernel emits a path+index debug string (`fullName/idx.idx`). Surveyed shipped models leave field labels empty, so in practice the token resolves to the field **name** *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §3, locked by `DisplayNameDiffTest`)*.
- A number's **unit trait** (amount / percent / permille) does **not** render into `$Field.value$` — the interpolation yields the bare number; the unit is a UI concern *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) "The number `trait`/unit slot")*.

*Reference: Error texts for Rules; Models → Field types → Error texts for Fields.*

---

## 14. CustomCondition — the escape hatch

When the language cannot express a check, `CustomCondition <Name>` delegates the decision to host code that the consuming system registers; the kernel parses and places the rule like any other but calls out to that implementation to decide whether it fires.

```
FieldFilled(id) And CustomCondition NotReverse
```

Two constraints: a `CustomCondition` is **forbidden in computation rules** and **inside filter (`Having`) conditions**. And, like any predicate, it doesn't exempt the rule from the error-field rule ([§9](#9-repetition-iteration-and-cross-array-evaluation)) — here the `FieldFilled(id)` conjunct references the error field.

At runtime, `CustomCondition X` fires iff the registered implementation returns `true`, on an evaluated row only ([§2](#2-non-specified-empty-values)'s row gate — it does not fire on an all-empty row), with VALUE polarity; a formal error on the fields the rule references suppresses it like any operator ([§3](#3-formal-errors-and-the-unknown-state)), and a bare `CustomCondition X` referencing no field is rejected (`MVK_ERROR_FIELD_NOT_REFERENCED`) *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) "CustomCondition — a runtime CLIENT SPI", locked by `CustomConditionDiffTest`)*.

Custom **conditions** and custom **field types** are the kernel's only custom hooks — there is no custom *computation* or function: a computation's operation vocabulary is closed, and `CustomCondition` (the one hook that could reach a computation) is barred there *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) "Custom-extensibility surface")*.

*Reference: CustomCondition; Computation Rules (intro); Rules → Rules and repeatability → Filter operator.*
