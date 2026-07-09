# A12 Kernel — validation-language semantics

> **Provenance & role in this repo.** Owner-authored in the sibling `a12-kernel-demo` project; integrated here 2026-07-09 so this repo is self-contained — this copy is the one the docs, [SEMANTICS-MAP.md](SEMANTICS-MAP.md), and `SemanticsMapResolveTest` key on. It is the **canonical kernel-truth prose layer**: its 14 numbered sections are the `§n` taxonomy that [KERNEL-FINDINGS.md](KERNEL-FINDINGS.md) (our experiment-verified supplements), the operator-catalog facets, and the conformance corpus's `meta.sections` all mirror. The companion docs it mentions (`KERNEL-GLOSSARY.md`, `SHOWCASE.md`, `KERNEL-GRAMMAR.md`) remain in their origin projects.

A reference to how the A12 validation language *evaluates*, beyond what its syntax shows: its truth model, how it treats missing values, what the "required" property means, and the numeric, date, string, enumeration, repetition, path, and computation rules that determine when a rule fires. The behaviours below are documented in the kernel's *Validation-Language guide*; each entry cites the relevant chapter/section by title. Two claim classes share the page: those guide-documented behaviours, and — in the deepening subsections — behaviours **verified by experiment or source reading against kernel `30.8.1`**, each cited inline as *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §n / IFnn, locked by `SomeTest`)*; a claim without such a citation is the guide's.

Two facts frame everything else:

- **A rule states the *error* condition.** Its condition is written so that `true` means the data is invalid. `FieldNotFilled(CustomerName)` is the rule "the customer name is missing."
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
| **Boolean** | True / False / unset | boolean | Three states; an unspecified value leaves a comparison **not evaluated**; `[F] == True` is **not** the same as `FieldFilled(F)`. |
| **Confirm** | True / unset (a checkbox) | confirm | Distinct from Boolean: an unspecified value is treated as **`False`** in comparisons; compares only to another Confirm or the constant `True`. |
| **Enumeration** | `values`; *with* or *without* localized **texts**; **categories** | enum value | Compared by the **stored value**, not the display text (`[Country]=="F"`); a *text-bearing* enum cannot be compared with a plain string or a *textless* enum; `->` reads a value's **category** attribute. |
| **Date** | `format`; `datePrecision` = full / day- / month- / year-optional | date | Constants are `DD.MM.YYYY` (day-first); a **string literal matching that format parses as a date** (so it breaks string concatenation); precision gates `DayFromDate`/`MonthFromDate`/… and `ValueAsDate`. |
| **DateTime** *(extends Date)* | — | date-time | The required operand for `DifferenceIn{Hours,Minutes,Seconds}`, `Add{Hours,…}`, `*FromTime`, and `Now`. |
| **Time** *(extends Date)* | — | time | The operand for `HoursFromTime` / `MinutesFromTime` / `SecondsFromTime`. |
| **DateFragment** | `formatOfFragment` = `MM` / `yyyy` / `yyyy-MM` / `MM-dd` | partial date | A deliberately *imprecise* date; comparable only by the **year-presence rule** (or globally via a model **Base Year**); the fragment format decides which `Add*` apply. *(Editor: import-only.)* |
| **DateRange** | `format` (`MM` … `yyyy-MM-dd`); separator `/` | date-range | Supports only `==` / `!=` (**no ordering**); cannot be nested in another construct; `DateRangesOverlap` treats endpoints as inclusive. |
| **Custom** | `name`; min/max length | string-equivalent | Treated as a **string** in rule conditions; `Valid(F, "Name")` / `Invalid(...)` runs the project's registered validator for that custom type. |
| **TypeDefinition** | references a reusable type definition by id | the underlying type's value | **Delegates** to the referenced type — its legality *is* that underlying type's, and is known only after the model is **expanded** (includes/type-defs resolved). |
| **Unspecified** *(experimental)* | — | — | Documented as "not a data type" — an intermediary/decoration, not used as a rule operand. |

*Reference: Models → Field types; Constants and data types; Operators and language constructs → Comparison operations. Interfaces: `kernel-md-model-api` `fieldtypes/I*Type`. The per-type evaluation details are expanded in the sections below (numbers §5, dates §6, strings §7, enumerations §8).*

---

## 1. Truth values and logical evaluation

### Two-valued connectives over data that can be "unknown"

The logical operators `And` / `Or` and the predicates are two-valued: a condition is satisfied or not. However, the *data* a condition reads can be in a third state — **unknown** — when a field carries a formal error (see [§4](#4-the-required-property) and [§12](#12-the-validation-model)). A field in that state cannot be evaluated by any rule. The logic itself never produces a third value; "unknown" instead removes a field (and the rules over it) from evaluation.

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
- There are no empty strings: `[F] == ""` is never satisfied, even when `F` is unfilled. Use `FieldNotFilled(F)` to test for absence.

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
| **value-list membership** | an empty subject is never in any list, and an empty numeric list *entry* never matches — the `0` substitution does not apply to entries *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §2/§8, locked by `EmptyValueLawsTest`)* |
| operand-list `Min([A], [B])` / `Max(…)` | an empty NUMBER operand is substituted `0` and **competes in the fold** (`Min(0, B)` — the 0 can win); an empty DATE operand is **skipped** (the date-bearing side wins) *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §2 + §6 / IF58, locked by `EmptyValueLawsTest` + `DateAggregateDiffTest`)* |
| the field-list / starred aggregates (`Sum`, `MaxValue`, `MinValue`) | the empty cell is **dropped from the fold** — `MaxValue(-5, empty)` is `-5`, no `0` folded in; `Sum` reads it as `0`, which for a sum is the same thing *(verified: [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §1 + §9, locked by `MultiFieldAggregateDiffTest` + `RepetitionLawsTest`)* |

The last two rows are one design split seen from both sides: `0` is `Sum`'s neutral element but not `Min`/`Max`'s, so the aggregates must drop empties where the sum may fold them in — which makes it easy to assume the drop extends to the *all-empty* case. It does not:

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
- Tolerance comparisons exist only at fixed thresholds (`DiffersWithToleranceRange1/2/5/10`) and are satisfied when the difference is **outside** the tolerance.

*Reference: Arithmetic operations → Accuracy of arithmetic operations; Operators and language constructs → Comparison operations.*

---

## 6. Dates and time

### Constant format and string/date ambiguity

Date constants are written `DD.MM.YYYY` (day first) regardless of locale, and the decimal separator is always `.`. A string literal that matches the date format is interpreted as a **date** constant, which prevents using it in string concatenation:

```
"30.11." + [YearString]      ;; invalid: "30.11." is read as a date
"30." + "11." + [YearString] ;; valid
```

*Reference: Constants and data types.*

### Difference and addition are not symmetric

`DifferenceInMonths` / `DifferenceInYears` return the largest number of months/years that can be added to the first date without passing the second — a floor, not a calendar count:

```
DifferenceInMonths("31.01.2010", "30.03.2010")   ;; returns 1, not 2
```

`AddYears` maps the last day of February to the last day of February (`AddYears("28.02.2023", 1)` → `29.02.2024`), whereas `AddMonths` preserves the day number (`AddMonths("28.02.2011", 12)` → `28.02.2012`); the two therefore disagree across a leap-year boundary, and a naive age check built on them is wrong around leap days. Fractional offsets are truncated, not rounded.

*Reference: Date and Time language constructs → DifferenceInMonths / AddMonths / AddYears.*

### Constructing dates, and checking validity

`Date(Day, Month, Year)` builds a date from numeric parts (there are also a two-argument form that supplies the year from the model **Base Year**, and a four-argument century form); `Time(...)` and `DateTime(...)` construct the corresponding values. A constructed date is **invalid** if any referenced part is unfilled, or the parts don't form a real calendar date — `Date(31, 11, 2000)` (November has 30 days) or 30 February. `Valid(...)` / `Invalid(...)` test exactly that:

```
Invalid(Date(Day, Month, Year))   ;; fires on 31.11, 30.02, an unfilled part, …
```

`Valid(Field, "CustomTypeName")` is the other form: it runs the project's registered validator for that custom type against the field. (Operand-format requirements for the `Date(...)` parts — bounded integer fields, or digit-patterned strings — are an authoring concern documented in `KERNEL-GRAMMAR.md`.)

*Reference: Date and Time language constructs → Date and time constructs → Date (Date with three parameters / Valid or invalid dates); Miscellaneous → Valid and Invalid.*

### Other date behaviours

- Dates before 16 October 1583 are invalid.
- `DifferenceInHours/Minutes/Seconds` across a daylight-saving transition can depend on the target language of the generated code.
- Date *fragments* (partial dates) are comparable only if both formats include the year or both omit it; a missing component defaults to `01`. A model-level **Base Year** changes these rules globally.
- `Today` ignores the time of day; `Now` is not recommended with `==` and is forbidden in computations.
- Date ranges support only `==` / `!=` (not ordering), and `DateRange(...)` cannot be nested inside other constructs; `DateRangesOverlap` treats endpoints as inclusive.

*Reference: Date and Time language constructs → Date fragments / Today / Now / Date Ranges.*

---

## 7. Strings and patterns

- String length counts Unicode combining sequences as multiple characters, so one visible glyph can occupy several characters of a length limit.
- `PatternMatched` / `PatternViolated` evaluate the **whole** value (anchored), use only the regular-expression subset portable across the target languages, and can be a performance/security risk if written to backtrack (e.g. `(a+)+`).
- `+` is overloaded — numeric **addition** between Number operands, string **concatenation** between strings. The guide documents no general operand-dispatch rule beyond one pitfall: a string literal shaped like a date parses as a *date constant* and so cannot be concatenated (see [§6](#6-dates-and-time)).

*Reference: Models → Field types → String; Operators and language constructs → String operations; Miscellaneous → PatternMatched and PatternViolated.*

---

## 8. Enumerations

- Rule conditions compare the stored enumeration **value**, not the displayed text: write `[Country] == "F"`, not `[Country] == "France"`.
- An enumeration that defines localized texts cannot be compared with a plain string or with an enumeration that has no texts; comparing two text-bearing enumerations also requires their values and texts to be mutually consistent.
- Enumeration values can carry named category attributes, read with the `->` operator: `[Country -> AdministrationArea] != "EU"`.

*Reference: Operators and language constructs → Comparison operations → Comparing strings and enumerations; The Field value operator.*

---

## 9. Repetition, iteration, and cross-array evaluation

The repetition model is the kernel's most distinctive — and most easily mis-authored — semantics. A flat rule fires zero or one time; a rule over repeatable data fires *per row*, and several constructs turn it into what is effectively a small query language over arrays.

### When a rule iterates, and where its error lands

A rule **iterates when its *error field* is repeatably referenced** — i.e. the error field's absolute path passes through one or more repeatable groups. It is then evaluated **once per repetition present in the document** (always at least once), and each resulting message attaches to **that specific row**. Iteration is driven by the **error field's position**, not by which fields the condition happens to read. For example, with rule group `Casino`, error field `Round` inside a repeatable `Poker` group, `FieldFilled(Poker/Round) And [Poker/Win_Loss] >= 0` is evaluated once per existing round and reports on the offending round.

The error field is also subject to a structural rule: it **must be referenced by the condition**, directly or indirectly (an enclosing `GroupFilled(Group)` counts), or the model is rejected with `MVK_ERROR_FIELD_NOT_REFERENCED`. So the error field both *selects* the iteration and *must appear* in the logic.

*Reference: Rules → Rules and repeatability → Iteration; Rules → Error Field.*

### Parallel iteration — joining two repeatable groups by key

When a rule references two repeatable groups where neither is nested in the other, they are iterated **jointly, keyed by an Index Field** that must exist on each group and **share the same name and type**. This is an *outer join* over the index values: a group with no row for a given index value counts as "not specified" for that iteration.

```
FieldsNotCollectivelyFilled(Demand/Units, Capacity/Units)   ;; iterated per shared Warehouse index value
```

Constraints (from the guide): at most **one** index field on the error field's path and on each parallel-iterated group; parallel iteration is **not allowed over rules using `RepetitionNotUnique`**; and **negative conditions are not allowed in a parallel iteration** except alongside a positive condition.

*Reference: Rules → Rules and repeatability → Parallel Iteration.*

### The filter operator `Having`, the `$` correlation, and aggregation

`Having` filters a repetition list (a `*`-path) down to the rows where a condition holds, before an aggregate consumes it:

```
Sum(Products*/Quantity Having [Products/ProductName] == "N33") > 100
```

The **`$` operator** — usable **only inside a filter condition** — pins a reference to the **current outer repetition** of the iterating rule, turning the filter into a *correlated subquery* (e.g. "holidays whose date equals the current stop's ETA"). Not every reference in a filter may be `$`-marked.

Aggregation handles missing rows **per function**: **`Sum` substitutes `0`** for an unspecified field, whereas **`MaxValue` / `MinValue` ignore** unspecified fields (a Number list with none specified yields `0`; a Date list yields an empty value). The filter condition may **not** contain `CustomCondition`, `RepetitionNotUnique`, a semantic index, a parallel iteration, or a nested filter.

*Reference: Rules → Rules and repeatability → Filter operator (and → Iteration in filter condition); Arithmetic language constructs → Sum / Maximum and Minimum.*

### Other repetition rules

- `GroupFilled(G)` is satisfied for an **empty repeat row that has been created** — "filled" here means "the row exists," not "the row has data."
- Negative conditions (those that hold when nothing is specified) are not permitted in combination with repeatable groups; the parser rejects them (`MVK_NEG_CONDITION_IN_ITERATION`) unless guarded, e.g. `GroupFilled(K) And AtLeastOneFieldFilled(K/F)`.
- List predicates over a repeatable group with `*` consider **all possible** repetitions, including unspecified ones, not only those that hold data.
- `RepetitionNotUnique(... @From Group)` checks uniqueness within the chosen reference group; omitting or changing `@From` changes the scope (e.g. unique per order vs. unique across all orders).

*Reference: Predicate language constructs for Groups → GroupFilled and GroupNotFilled; Predicate language constructs for Field lists → RepetitionNotUnique.*

---

## 10. Paths and references

- A field or group named like a keyword (`And`, `Date`, `Today`, …) must be single-quoted in a path: `FieldFilled(Order/'Date')`.
- Paths are **absolute** (`/Order/Quantity`) or **relative** (`../Other`). The guide favours relative paths within a rule's group — they are shorter and survive the group being moved. One combination *requires* absolute, though: a relative `..` up-navigation may not be combined with the `*` asterisk (`MVK_INVALID_WILDCARD_REL` — "use the absolute path notation"), so reaching upward *and* across all repetitions must be written absolutely (e.g. `/A*/F`) — which is why `CrossArrayDemo` uses absolute paths. A field may also be referenced by a bare **short name** (e.g. `[Quantity]`) when the model sets `fieldRefByShortNameAllowed`, as this demo does.
- The asterisk `*` flattens a repeatable group into the list of its rows; if an outer repeatable group uses `*`, every repeatable group below it in the path must also use `*`.
- The `$` operator (a correlated reference to the current row) may be used only inside a filter condition, and not every reference in the filter may be `$`-marked.
- `CurrentRepetition(G) == n` cannot stand alone; it must be combined with a condition that ensures the repetition is filled.
- The semantic index (`Group For "value"`) is unsupported in several combinations (multiple repetition layers, several index fields, under an asterisk, and with certain predicates).

*Reference: Rule condition; Models → Paths; Repeatable Groups and Fields → Asterisk operator; Filter operator; Detecting the current repetition; Parser messages → Paths (`MVK_INVALID_WILDCARD_REL`).*

---

## 11. Computations

- **Every computation rule also acts as a validation rule**: if the computed field is filled, its value must equal what the computation produces, or an error is reported. Assigning a time-dependent value such as `Now` to a computed field therefore reports an error as soon as time passes.
- The computed field's declared decimal scale must equal the calculation's scale; otherwise the model is rejected (wrap the calculation in a rounding construct to match).
- A computation table's preconditions must be mutually exclusive, and the computed field may not appear in any precondition.
- A non-repeatable computed field is calculated even when all its inputs are empty (yielding `0` or an empty/space string); add an `AllFieldsFilled(...)` precondition to suppress that. Indirect calculation cycles among computed fields are possible and are not always easy to detect.

*Reference: Computation Rules → Computation Rules as Validation Rules / Number of decimal places / Preconditions / Calculation cycles.*

---

## 12. The validation model

### Formal validation vs. rule validation

Formal validation is the set of field-type checks the kernel performs automatically (type, pattern, decimal places, required-by-checkbox). These carry the rule path `formalePruefung` (one of a handful of kernel-internal German identifiers) and make a field "unknown" while they stand. Author-written rules are separate and never block a field from evaluation. See [§3](#3-formal-errors-and-the-unknown-state).

### Severity is metadata: ERROR vs. WARNING vs. INFO

A rule's severity (`ERROR` / `WARNING` / `INFO`) is **metadata on the resulting message**, not part of evaluation — the condition fires identically whatever the severity. Only `ERROR`-severity messages make the document invalid: `IDocumentValidationResult.noErrorOccurred()` returns `true` even when `WARNING`s and `INFO`s are present (it considers only `ERROR`s). So a firing `WARNING` surfaces a message to the user without failing validation — useful for "are you sure?" advisories.

*Reference: Appendix → Validation and formal validation → Errors arising from validation rules; runtime API `IDocumentValidationResult.noErrorOccurred()` (`kernel-md-runtime-api`).*

### Full validation vs. partial validation

`validateFull` checks the whole document. `validatePart` checks a "relevant" subset (e.g. the current wizard page) and guarantees only one direction: it never reports an error that could only be fixed outside that subset. It does **not** guarantee a complete check of the relevant fields themselves — some checks may be skipped for performance, and which ones is an implementation detail that may change. A document that passes a partial validation may still fail a full validation on the same fields. A field referenced by a rule but living on another screen is excluded from the relevant set unless it has the `Global` flag set.

*Reference: Appendix → Full Validation and Partial Validation; Appendix → Validation and formal validation → Relevant fields.*

---

## 13. Error-message interpolation

- `$Field$` interpolates the field's **name/label**; `$Field.value$` interpolates its **value**. A literal `$` is written `$$`.
- For a **field** error text (as opposed to a rule error text) the tokens are literally `Field` and `Field.value`, independent of the actual field name.
- `$Field.value$` may be used only if the field is referenced in the condition at least once without an asterisk, and error-text paths may not contain asterisks.
- For an unspecified value, the interpolation yields `0` for numbers and an empty string for other types.

*Reference: Error texts for Rules; Models → Field types → Error texts for Fields.*

---

## 14. CustomCondition — the escape hatch

When the language cannot express a check, `CustomCondition <Name>` delegates the decision to host code that the consuming system registers; the kernel parses and places the rule like any other but calls out to that implementation to decide whether it fires.

```
FieldFilled(id) And CustomCondition NotReverse
```

Two constraints: a `CustomCondition` is **forbidden in computation rules** and **inside filter (`Having`) conditions**. And, like any predicate, it doesn't exempt the rule from the error-field rule ([§9](#9-repetition-iteration-and-cross-array-evaluation)) — here the `FieldFilled(id)` conjunct references the error field.

*Reference: CustomCondition; Computation Rules (intro); Rules → Rules and repeatability → Filter operator.*
