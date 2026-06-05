# Kernel-behavior findings (complementary to KERNEL-SEMANTICS.md)

**This doc is the complement of `a12-kernel-demo`'s `KERNEL-SEMANTICS.md`** (the canonical kernel-truth reference; checked out here as [`../a12-kernel-demo/KERNEL-SEMANTICS.md`](../../a12-kernel-demo/KERNEL-SEMANTICS.md)). **KERNEL-SEMANTICS.md is read-only / consult-only — we never write to it** (owner policy); KERNEL-FINDINGS.md holds what it *lacks*. The two are meant to be read together: KERNEL-SEMANTICS for the canonical behavior, KERNEL-FINDINGS for the experiment-verified additions, section-numbered to mirror it so entries are easy to upstream if the owner ever chooses to.

Kernel-behavior facts **verified by experiment against kernel `30.8.1`** that are *not* (yet) covered by KERNEL-SEMANTICS.md — which we **consult before each increment** and never duplicate here. Each finding is locked by a test in this repo (named per entry); rulekit's *treatment* of each fact lives in [`KERNEL-INTEGRATION-NOTES.md`](./KERNEL-INTEGRATION-NOTES.md).

## §1 supplement — Boolean/Confirm comparability codes

The Boolean≠Confirm split is enforced at parse time with distinct codes: a Boolean compares only to `True`/`False` or another Boolean — anything else (e.g. a string) is **`MVK_INVALID_COMPARE_TO_YESNO`**; a Confirm compares only to `True` or another Confirm — `[Confirm] == False` and Boolean↔Confirm cross-comparisons are **`MVK_INVALID_COMPARE_TO_YES`**. "Not confirmed" is written `[Confirm] != True` (an unset confirm counts as not confirmed). *(Locked: `validate.laws.BooleanConfirmLawsTest`.)*

## §5 supplement — numeric literals are exempt from the scale check

The `==`/`!=` decimal-scale gate applies to **field/expression operands only**. Against a scale-2 field, `== 0`, `== 0.0`, `== 0.00`, `!= 0`, and `<= 0.000` are all accepted — only field-vs-field/expression comparisons of differing declared scale draw `MVK_INVALID_COMPARE_DEC_PLACES`. Consequence: scale-explicit literals (`0.00`) are a rendering-fidelity nicety, not a correctness requirement. *(Locked: `validate.laws.NumberLawsTest`.)*

**Field-vs-field comparison (finding 18, verified 2026-06-04).** Comparing two *fields* (not just field-vs-literal): **strings** compare with `==`/`!=` only (ordering → `MVK_INVALID_TYPE_FOR_COMPARISON`, "only for numbers, amounts and dates"); **numbers** — the scale gate is **`==`/`!=`-only**: equal-scale fields compare freely, different-scale fields draw `MVK_INVALID_COMPARE_DEC_PLACES` ("max fractional digits of lhs(0) and rhs(2) differ"), but **ordering (`< <= > >=`) is scale-exempt** (verified: `[Quantity(0)] < [UnitPrice(2)]` validates). String-vs-number → `MVK_INVALID_COMPARE_TO_ENUM_OR_STRING`. rulekit: `StringRef.eq/ne(StringRef)` and `NumberRef.{lt,le,gt,ge,eq,ne}(NumberRef)` — the number `eq`/`ne` **pre-check equal scale** (the D13 gate made cheap, since the index knows both scales), ordering does not. (Enum-vs-enum deferred — text-bearing-consistency rules + a two-enum fixture; KERNEL-SEMANTICS §8.) *(Locked: `validate.laws.NumberLawsTest.fieldVsFieldScaleGateAppliesToEqualityOnly`/`stringFieldVsFieldEqualityIsAccepted` + `RenderConditionTest`.)*

## §7 supplement — pattern-comparison slot rules and the portable-subset gate (2026-06-04 experiments)

`PatternMatched`/`PatternViolated` (DE: `WieMuster`/`NichtWieMuster`) are grammar-level *infix comparison operators* (`vglOp` alternatives like `==`), but their slot rules are far stricter than the grammar:

- **Left side: a bare string field value only.** A non-string field (number, enum, boolean) → **`MVK_INVALID_TYPE_FOR_PATTERN_COMPARISON`** ("only allowed for string values" — note: *enums are not pattern-comparable* even though they compare to strings with `==`). A string *expression* (e.g. concatenation) → **`MVK_INVALID_PARAMETER_FOR_PATTERN_COMPARISON`** ("only allowed with a field value as first parameter"). A constant → the general **`MVK_INVALID_COMPARE_TO_CONST`**.
- **Right side: a string constant only.** A field reference → **`MVK_MISSING_CONST_IN_PATTERN_COMP`**; a number constant → `MVK_INVALID_TYPE_FOR_PATTERN_COMPARISON`.
- **The regex gate is Java `Pattern.compile` *plus* a portable-subset check** (`PatternUtils.isPatternValid`; the extmp world uses `CommonUtils.isRegExValid` instead): lookbehind `(?<=a)b` is Java-valid yet rejected with **`MVK_INVALID_PATTERN`** — KERNEL-SEMANTICS §7's "portable subset" is *enforced*, not advisory. Consequence: a Java-side `Pattern.compile` pre-check is a necessary condition only; final validity is the kernel's call.
- **The empty pattern `""` is accepted** for document models (the non-extmp branch; extmp additionally requires non-empty).

*(Locked: `validate.laws.PatternLawsTest` + N7 `PatternMatched`/`PatternViolated` examples.)*

## §7 supplement — string-literal escaping is quote-doubling, not backslashes

The condition lexer's rule is `StringLiteral : '"' ( '""' | ~["] )* '"'` — an embedded quote is **doubled** (`"say ""hi"""`), and **backslash is a plain character** (no escape sequences; regex classes like `\d` are written as-is). Backslash-escaping a quote (`\"`) is a parse error (`MVK_UNEXPECTED_TOKEN`). Found while building the pattern operators: rulekit's renderer had C-style escaping and would have emitted wrong regexes for any pattern containing `\` — fixed and locked. *(Locked: `validate.laws.PatternLawsTest` + `RenderConditionTest.quotesStringLiteralsTheKernelWay`.)*

## §8 supplement — enum-category codes and the positional mapping

In DM-JSON, a category's `values` list pairs **positionally** with the enumeration's `values` (entry *i* categorizes enum value *i*); the comparison domain is the category's distinct value set. Codes: comparing to one of the *enum's own* values instead of a *category* value → **`MVK_INVALID_STRING_CONSTANT_FOR_ENUM_COMPARISON`**; an unknown category name → **`MVK_CATEGORY_UNKOWN`** (the kernel's spelling); `->` on a non-enumeration field → **`MVK_NO_ENUMERATION`**. Curiosity: `[Enum -> Category] == [Enum]` (category vs. plain enum field) is *accepted*. *(Locked: N7 `EnumCategory` examples + `dsl.operators.EnumConditionTest`.)*

## §6 supplement — the date family (corpus item 9 slice 9a, 2026-06-04 experiments)

`Today` (DE `Heute`), `DifferenceInDays|Months|Years` (DE `DifferenzIn{Tagen,Monaten,Jahren}`), `AddDays|Months|Years` (DE `Addiere{Tage,Monate,Jahre}`):

- **Date ordering needs no scale machinery** — dates have no scale (unlike the D13-deferred numeric field-vs-field), so `[A] < [B]` / `[A] == [B]` between two date fields validates directly. `[Date] >= Today` and date-vs-date `==`/ordering all work.
- **`Today` composes everywhere** — as a comparison operand (`[OrderDate] >= Today`) and as a function operand (`DifferenceInDays(OrderDate, Today)`).
- **A date compared to a non-date is rejected** — `[Quantity] >= Today` → **`MVK_INVALID_COMPARE_TO_DATE`** ("only allowed to compare dates with other fitting dates").
- **Bare vs bracketed by position** — a date field is bracketed `[OrderDate]` as a *value* (in comparisons) but **bare** `OrderDate` as an *entity argument* inside `DifferenceInDays(…)`/`AddMonths(…)`. (rulekit's renderer has a `renderArg` for argument position.)
- `DifferenceIn*` returns a **number** (comparable/arithmetic); `Add*` returns a **date** (comparable). Semantics caveats (kernel, §6): differences are a *floor* not a calendar count; `AddMonths` preserves the day while `AddYears` maps end-of-February — they disagree across leap years.

**Deferred to slice 9b — format-finicky / need special operand fields.** A **date constant literal** (`31.12.2020`) against a `yyyy-MM-dd` `DateType` field is rejected (`MVK_INVALID_COMPARE_TO_DATE`) — the comparable-fragment rules (§6: both formats include the year or both omit) make literal constants format-dependent. **`Date(d,m,y)` construction** needs bounded-integer / digit-patterned operand fields (a plain scale-0 number draws `MVK_WRONG_DATE_FORMAT_FOR_OP`), as do `Valid(...)`/`Invalid(...)` over constructed dates. `Now`, `DateRange`/`DateRangesOverlap`, `DateTime`/`Time` also deferred. Low marginal demand; build on need.

*(Locked: N7 examples of Today + all three Difference*/Add* operators + the `Today` number-vs-date invalid; `dsl.operators.DateConditionTest` for rendering + the non-date-field handle rejection. The order fixture gained `OrderDate`/`DeliveryDate` DateType fields.)*

## §14 supplement — CustomCondition's two forbidden contexts (corpus item 8, 2026-06-04 experiments)

`CustomCondition <Name>` (DE `ApplikationsBedingung`; grammar `applikationsBedingung: 'ApplikationsBedingung' nameOhnePunkt`) is a bare predicate — a host-registered name, no operands. KERNEL-SEMANTICS §14 names two bans; both verified and now **pre-checked** (the constructs are representable):

- **Inside a `Having` filter** → **`MVK_DISALLOWED_CONSTRUCT_IN_FILTER_CONDITION`** ("The language feature 'CustomCondition' is not supported for filter conditions"). *(Isolating it needs a filter that already references the iteration — otherwise `MVK_NO_ITERATION_FOR_WILDCARD` fires first.)*
- **In computation rules** (§14) — pre-checked in `Computation.build()` by walking preconditions for a `CustomConditionRef`.
- It does **not** satisfy the error-field rule on its own (a bare `CustomCondition X` → `MVK_ERROR_FIELD_NOT_REFERENCED`); pair it with a field reference — rulekit's generic `Rule.build()` error-field pre-check already covers this.

*(Locked: N7 `CustomCondition` valid; `dsl.operators.CustomConditionTest` for both bans + the bare-identifier name rule. DE keyword pairing verified from the lexer bundle (can't N7 against the en_US fixture — model condition-language gates parsing).)*

## §2 supplement — the filled-count aggregates (2026-06-04 experiments)

`NumberOfFilledFields` / `NumberOfFilledGroups` (DE: `AnzahlAngegebenerFelder`/`AnzahlAngegebenerKontexte`; the grammar's `feldListenAnzahlOp`, third sibling `NumberOfDifferentValues` deferred) are **numeric expressions**, freely comparable and usable in arithmetic (`NumberOfFilledFields(a, b) + 1 < [Q]` validates). Slot rules:

- **`NumberOfFilledFields` needs more than one field *or* at least one group** — a single field draws **`MVK_PARAMSIZE_INVALIDN`** ("There must be more than one field or at least one group"). The group form counts the filled fields *inside* the group(s); multiple groups are legal. Field kinds may be **mixed** (boolean, enum, number together — it counts presence, not values).
- **`NumberOfFilledGroups` needs more than one group** — a single group draws **`MVK_PARAMSIZE_INVALIDGN`**; a field argument draws the familiar type-direction code **`MVK_NO_FIELDS_ALLOWED`**.
- **Root groups are banned in both** — **`MVK_ROOT_GROUP_REFERENCED`** ("It is not allowed to reference a rootgroup").
- Comparing the count to a **decimal literal** (`<= 1.5`) is accepted (consistent with the §5 literal-scale exemption).

*(Locked: N7 examples of both operators incl. the param-size/type-direction invalids; root-group ban pre-checked in `CountAggregate` — `dsl.operators.AggregateConditionTest`.)*

## §8 supplement — the value-list membership family (2026-06-04 experiments)

Five operators in two grammar shapes, not covered by KERNEL-SEMANTICS. **Shape A** `FieldValueIncludedInValueList(field, v1, …)` / `FieldValueNotIncludedInValueList(…)` (DE: `FeldWertInWerteListe`/`FeldWertNichtInWerteListe`); **shape B** `AtLeastOne|No|NotAllFieldValue(s)IncludedInValueList(f1, f2 In v1, …)` (DE: `MindestensEin…`/`Kein…`/`NichtAlle…`; `In` is a language-independent grammar literal like the symbols). Slot rules:

- **Only string, enum, and number fields participate** — a boolean/confirm field draws **`MVK_ONLY_STRING_ENUM_NUMBER_ALLOWED`** ("only allowed to use string fields, enumerations or numbers (including amounts)").
- **Numeric values are integer literals only** — the grammar's value-list `number` is `'-'? digits`; a decimal (`10.50`) is a *parse error* (`MVK_UNEXPECTED_TOKEN`), even against a scale-2 field. Negative integers are fine.
- **Value types must match the field kind** — string field vs numbers or number field vs strings → **`MVK_INVALID_TYPES_FOR_COMPARISON`** (the plural-`TYPES` sibling of the comparison code).
- **Enum domains are enforced** — shape A: an out-of-domain value → **`MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION`**; shape B → **`MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION_OR_CATEGORY`**.
- **Shape B field lists must be type-homogeneous** — mixing a string and a number (or enum) field → **`MVK_VARYING_TYPES_NOT_ALLOWED`** ("All fields must be the same type"). A single-field list is legal.
- The field slot is a **bare path** (a bracketed `[Field]` is a parse error); the value list may also contain **field references** (`[OtherField]`) and may mix them with literals; shape B's right side accepts an entity list or strings (no numbers, per grammar).

*(Locked: `validate.laws.ValueListLawsTest` + N7 examples of all five operators. Field-valued lists and `*`-path field lists are deferred — the latter to the iteration cluster.)*

## §9 supplement — field/group predicates are type-directional

`GroupFilled(field)` → **`MVK_NO_FIELDS_ALLOWED`** ("Only groups or root groups are allowed"); `FieldFilled(group)` → **`MVK_NO_GROUPS_ALLOWED`**. Also re-confirmed experimentally: an enclosing group reference (`GroupFilled(G)`) satisfies the error-field-referenced rule for a field inside `G` — a bare `GroupFilled(ShippingAddress)` condition with error field `ShippingAddress/Street` validates. *(Locked: N7 `GroupFilled`/`FieldFilled` invalid examples + `RuleAuthoringTest.acceptsAnEnclosingGroupReferenceAsErrorFieldReference`.)*

## §9 supplement — repeatability interactions (2026-06-04 experiments)

- **`MVK_NEG_CONDITION_IN_ITERATION` triggers on iteration × unguarded negativity** — reproduced: with the error field under repeatable `Addons` (the rule iterates), both `GroupNotFilled(Addons)` and a bare `FieldNotFilled(Addons/MonthlyFee)` are rejected; the guarded form `GroupFilled(Addons) And FieldNotFilled(Addons/MonthlyFee)` passes. The trigger is the *rule's iteration* (error-field position) combined with a negative condition — not the predicate's group argument per se.
- **A repeatable group referenced from a *non-iterating* rule needs the asterisk** — `FieldFilled(Tier) And GroupNotFilled(Addons)` with error field `Tier` is rejected with **`MVK_NO_WILDCARD`** ("Missing specification of an asterisk for 'Addons'"). A previously unknown law; pre-checkable once the iteration model lands (rulekit knows both the error-field iteration and the group's repeatability).
- **Root groups work with both group predicates** — `GroupFilled(/Subscription)` and `GroupNotFilled(/Subscription)` (non-repeatable root, non-iterating rule) both validate; the kernel parses root-group references into a distinct AST node (`IRootGroupCondition`) but the surface syntax is uniform.

*(No locks yet — pre-checks deferred to the iteration cluster; the codes are classified kernel-delegated in `MVK-LEDGER.md`.)*

## §9 supplement — wildcard slot laws (slice 7a, 2026-06-04 experiments)

The `*` suffix is per path segment (grammar `idNameRef: idName '*'?`) and builds a **repetition list**; whether it is legal depends purely on the *slot*:

- **List slots accept starred paths**: the field-list quantifiers (`AtLeastOneFieldFilled(Addons*/Name)` …), shape-B value lists (`…(Addons*/Name In "x")`), and the count/`Sum` aggregates — iterating rule or not, and **mixing plain and starred operands in one predicate is legal**.
- **Scalar slots reject them** with **`MVK_NO_WILDCARDS_ALLOWED`** ("It is not allowed to use an asterisk"): `FieldFilled(Addons*/Name)`, a bracketed value `[Addons*/Fee]`, `GroupFilled(Addons*)`, and shape A's single-field slot.
- A star on a **non-repeatable** group is its own code: **`MVK_INVALID_WILDCARD`** ("Since 'X' is not repeatable…").
- **The negativity law (`MVK_NEG_CONDITION_IN_ITERATION`) is not an operator list** — the kernel's `IterationNotValidVisitor` (~800 lines) classifies each condition as positive/negative *in context*: the same operator flips depending on whether its operands reference the iteration (e.g. `FieldFilled(A)` is "negative" when `A` does *not* iterate but the rule does), and aggregate comparisons against constants are negativity-sensitive by comparison direction (`< / <= / != n`, `== 0`-style forms). rulekit deliberately does **not** replicate this — kernel-delegated to F4.
- **`MVK_NO_WILDCARD` (missing star) is not naively pre-checkable**: under *parallel iteration* (joined repeatable groups via same-name/type index fields) plain references to another repeatable group are legal — the pre-check waits for slice 7e's iteration model.

*(Locked: `validate.laws.IterationLawsTest`; rulekit treatment: `FieldOperand` = `FieldRef` | `FieldListRef`, list slots take the operand union, scalar slots take `FieldRef` — `BoundModel.flatten(starPath)` validates star placement.)*

## §9 supplement — `CurrentRepetition` and the deferred index-field cluster (slice 7e, 2026-06-04 experiments)

`CurrentRepetition` (DE `AktuelleWiederholung`; grammar `groupOp: 'AktuelleWiederholung' '(' entitySpec ')'`) is a **numeric expression** — the current iteration index of a group.

- **Takes a group, not a field** — a field argument → **`MVK_NO_FIELDS_ALLOWED`** ("Only groups or root groups are allowed"). Structural: `Dsl.currentRepetition(GroupRef)`.
- **The rule must iterate over that group** — from a non-iterating rule it draws **`MVK_NO_WILDCARD`** ("Missing specification of an asterisk"). Position-dependent → kernel-delegated (like the RNU position law).
- **A non-repeatable group is tolerated** (its index is constant) — `CurrentRepetition(/Subscription/Billing)` from an iterating rule validates.

**Slice 7f (2026-06-04, with an index-field fixture).** A repeatable group declares an index field via **`"indexFieldName": "<containedField>"`** (own-domain fixtures `logistics.dm.json` — `Lines`/`Picks` keyed on `LineRef`; `order.dm.json` `Items` keyed on `Sku`). With that:
- **Semantic index `[Field For "value"]`** (DE `Fuer`) selects the repetition whose index field equals `value` — **works for a string constant**; the field's group must declare an `indexFieldName` else **`MVK_NO_INDEX_FIELD`**. An *entity* index that itself iterates is rejected (**`MVK_SEMANTIC_INDEX_INVALID_ITER`**) — so rulekit supports the constant form (`NumberRef.forIndex(String)`); the entity/`$` forms stay kernel-delegated. (Enum/string `forIndex` deferred — only `NumberRef` for now.)
- **Parallel iteration needs no construct** — two repeatable groups sharing a same-name/type index field iterate jointly; a rule referencing both (e.g. `FieldsNotCollectivelyFilled(Lines/OrderedQty, Picks/PickedQty)`, or a field-vs-field cross-compare) validates with *existing* handles. rulekit supports it by *not interfering*.
- **`MVK_NO_WILDCARD` (missing asterisk) confirmed correctly kernel-delegated**: under parallel iteration a *plain* cross-group reference is **legal** (`FieldFilled(OrderedQty) And FieldFilled(/Shipment/Picks/PickedQty)` validates) — so a naive "repeatable group needs `*`" pre-check would false-positive. The pre-check would require full iteration-model analysis (does the rule parallel-iterate?); deliberately left to F4.

*(Locked: N7 `SemanticIndex` valid + `MVK_NO_INDEX_FIELD` invalid; `dsl.operators.IterationConditionTest` (render) + `validate.laws.IterationLawsTest.parallelIterationOverIndexKeyedGroupsIsSupportedNotBlocked`.)*

*(Locked: N7 `CurrentRepetition` valid (iterating) + invalid (non-iterating `MVK_NO_WILDCARD`); the iterating-position law in `validate.laws.IterationLawsTest`. Groups-only is structural.)*

## §9 supplement — the `Having` filter and `$` correlation (slice 7d, 2026-06-04 experiments)

`Having` (DE `Mit`; grammar: `'Mit' bedingung` on an `entitySpec`) filters a `*`-repetition list before an aggregate/quantifier consumes it; `$` (`DOLLAR`, language-independent) pins a reference to the rule's outer repetition inside a filter.

- **`Having` requires a starred (flattened) field** — on a plain reference it draws **`MVK_WILDCARDS_REQUIRED`** ("At least one asterisk needs to be specified for the filtered field"). So it lives on `FieldListRef`, not on plain `FieldRef`.
- **It attaches in any list slot** — verified inside `Sum`/aggregates *and* the field-list quantifiers (`AtLeastOneFieldFilled(… Having …)`).
- **Filter-condition exclusions** → **`MVK_DISALLOWED_CONSTRUCT_IN_FILTER_CONDITION`** ("The language feature 'X' is not supported for filter conditions"): verified for `RepetitionNotUnique` and a **nested `Having`**; KERNEL-SEMANTICS §9 also lists `CustomCondition`, a semantic index, and a parallel iteration. rulekit pre-checks the two representable ones (RNU + nested Having); the rest stay kernel-delegated.
- **`$` is filter-only** — outside a filter it draws **`MVK_INVALID_OUTER_ITERATION`** ("The symbol '$' to specify rule iteration is only allowed inside filter conditions"). A valid use correlates inner to outer: `Sum(/S/Addons*/MonthlyFee Having [/S/Addons/Name] == [$/S/Addons/Name]) > 0`. **`$` is deferred**: its only sensible use is a **field-to-field** comparison in the filter (`[inner] == [$outer]`), and rulekit has no field-vs-field comparison yet (D13) — so `$` rides that feature, not this slice. (`$` on the *filtered* side without a non-`$` inner ref → `MVK_NO_ITERATION_FOR_WILDCARD`.)

*(Locked: N7 `Having` valid + `MVK_DISALLOWED_CONSTRUCT_IN_FILTER_CONDITION` invalid; pre-checks in `FieldListRef.having` — `dsl.operators.IterationConditionTest`. `Having`'s starred-field law is structural (only `FieldListRef` offers it); `$`/`MVK_INVALID_OUTER_ITERATION`/`MVK_WILDCARDS_REQUIRED`/`MVK_NO_ITERATION_FOR_WILDCARD` recorded, pre-checks deferred with `$`.)*

## §9 supplement — list-aggregate slot laws (slice 7c, 2026-06-04 experiments)

`Sum`/`MaxValue`/`MinValue` (DE `Summe`/`MaxWert`/`MinWert`; grammar `feldListenOp`, siblings `SumOfProducts` and `FirstFilledValue` deferred) and `NumberOfDifferentValues` (DE `AnzahlVerschiedenerWerte`; third member of the count family):

- **Param law is shared with the count aggregates** — more than one field or a group (`MVK_PARAMSIZE_INVALIDN`); **a single flattened (`*`) operand satisfies it** (it expands to many fields), so `Sum(Items*/Count)` is the canonical shape. Mixed starred + plain operands and mixed scales are legal.
- **Duplicate operands are rejected** — `Sum(A, A)` draws **`MVK_DUPLICATE_PARAM1`** (fires before the type checks; applies to the whole entity-list family incl. `NumberOfFilledFields`).
- **Type laws**: `Sum` takes numbers only (**`MVK_NO_NUMBER`** — "Only numbers and amounts"); `MaxValue`/`MinValue` take numbers **or dates** (**`MVK_NOT_SORTABLE`** — "Only numbers, amounts or dates"); a **group argument** is expanded to its contained fields and type-checked the same way.
- **`NumberOfDifferentValues` is value-comparing**, so its operands must be type-homogeneous: string/enum together (**`MVK_STRING_ENUM_AND_NON_STRING_ENUM`**) or all numbers (**`MVK_NUMBER_AND_NON_NUMBER`**); booleans/confirms never (**`MVK_ONLY_STRING_ENUM_NUMBER_CMP_DATE_ALLOWED`**).
- Missing-row semantics (KERNEL-SEMANTICS §9): `Sum` substitutes 0 for unspecified fields; `MaxValue`/`MinValue` ignore them.

*(Locked: N7 examples of all four operators incl. three invalids; the pre-checks live in `ListAggregate`/`CountAggregate` — `dsl.operators.AggregateConditionTest`. The date variant of Max/Min rides the date family, item 9.)*

## §9 supplement — `RepetitionNotUnique` slot laws (slice 7b, 2026-06-04 experiments)

- **The rule sits at the parent**: every key field must have at least one repeatable group **between the rule group and the field** (or sit directly in a repeatable root group) — `RepetitionNotUnique(Addons/Name)` from *inside* `Addons` draws **`MVK_REPEATABLE_GROUP_MISSING`**; the same condition from the parent validates. Position-dependent → kernel-delegated, not pre-checked.
- **Keys are plain fields**: a group argument → `MVK_NO_GROUPS_ALLOWED`; a starred path → `MVK_NO_WILDCARDS_ALLOWED` (the iteration is implicit). **Composite keys** (several key fields) are legal. A key mixing in a non-repeatable field (e.g. `Tier`) → `MVK_REPEATABLE_GROUP_MISSING` for that field.
- **The rule must iterate** — with a non-iterating error field the plain key path draws the ordinary `MVK_NO_WILDCARD` before any RNU-specific check.
- **`@From`'s reference group must be repeatable** — `@From /Subscription` (root, non-repeating) draws **`MVK_GROUP_NOT_REPEATABLE`**. Note `@From`/`@Von` is a **localized keyword** (lexer-bundle token `VON`), unlike the language-independent `In` separator.
- **`FieldValuesNotUnique` (DE `FeldWerteNichtEindeutig`) is a separate, plain field-list predicate** (the grammar's `feldListenBedingung` family, not `wiederholungBedingung`): cross-field distinctness over plain or starred operands — both shapes kernel-verified.

*(Locked: `validate.laws.IterationLawsTest` + N7 `RepetitionNotUnique`/`FromScope`/`FieldValuesNotUnique` examples; `@From` repeatability pre-checked in `RepetitionNotUniqueCondition.from`.)*

## §11 supplement — computation alternatives and the calculated-field ban

- **Alternatives are all-or-nothing about preconditions**: either a single alternative *without* a precondition, or several that *all* carry one — mixing a precondition-less "otherwise" in is rejected with **`MVK_ERROR_EMPTY_PRECONDITION`**. The default branch must be an explicit complementary precondition (as the demo's `BonusComp` does with `[WeekPay] < 500`).
- The computed field may not be referenced in preconditions **nor in operations** — both directions draw the same **`MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD`** (KERNEL-SEMANTICS §11 states the precondition half).
- A wrong-kind operation (string expression into a number computed field) is **`MVK_INVALID_COMPARE_TO_ENUM_OR_STRING`**.
- Computations require error text like rules do (`MVK_ERROR_MESSAGE_MISSING`).

*(Locked: `ComputationAuthoringTest`.)*

## The pre-`checkConsistency` tiers — the write path's output contract (instrument 4, 2026-06-04)

Two structural gates fire *outside* `checkConsistency`, found while scoping the F6 DM-JSON write path:

- **Deserialize tier (Jackson binding).** `serializer.deserialize(reader)` is a Jackson read — malformed JSON, an unknown `"type"` discriminator, an unparseable number/enum, and the **locale-format check** (`en_us` → "Invalid locale format", before any consistency pass) fail here as `IOException`/binding errors, *not* `MVK_*` codes. Not enum-enumerable; documented, not locked.
- **Serialize/write gate — `BasicConsistencyCheck`.** `serialize(model, writer, …)` runs `BasicConsistencyCheck.checkModel` first and **aborts the write on any ERROR** — so this, not `checkConsistency`, is what an F6 `writeTo` must satisfy. Its `BasicDocumentModelConsistencyCategory` enum (16 categories) is the enumerable contract: `ID_EMPTY`, `NAME_EMPTY`, `ERROR_{ENTITY,CODE,CONDITION}_EMPTY`, `COMPUTED_FIELD_EMPTY`, `COMPUTATION_OPERATION_EMPTY`, `ENUM_TYPE_DUPLICATE_{VALUE,CATEGORIES}`, `LOCALIZED_TEXT_{ELEMENT,TYPEDEF}_NULL_PROBLEM`, `INDEX_FIELD_NAME_EMPTY`, `SORT_FIELD_NAME_EMPTY`, `TYPEDEF_{TYPE_REF,ID,NAME}_EMPTY`.

rulekit's authoring builders already satisfy the rule/computation subset (`ERROR_*`, `COMPUTED_FIELD/OPERATION`, `LOCALIZED_TEXT_ELEMENT`) by construction; the rest are model-structure concerns rulekit doesn't author (it only *adds rules/computations to an already-valid model*). *(Locked: `SerializerTierParityTest` — reflective parity over the category enum, so a kernel upgrade adding a category fails loudly.)*

## How `checkConsistency` actually works — structural laws ARE metamodel rules (2026-06-04)

Found while repairing the MVK ledger's coverage hole (`MVK_ERROR_MESSAGE_MISSING`/`MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING` appear at runtime but in no kernel enum, no source file, and no plain string in any jar): `checkConsistency`'s `CodeGenerationRule` runs `generateValidationCode`, and part of that pipeline **validates the model itself as a document against the `MM_Common` metamodel** — using validators *code-generated from the metamodel's rules* (the `mmvalidators-extmpinternal-mm_common-v2-codegen` jar, transitively on the classpath). The two "missing" codes are ordinary **rule `errorCode`s authored in MM_Common**, alongside **103 more** — 105 structural laws expressed as A12 rules over the metamodel.

Consequences:
- The owner's "kernel-mm is the source of truth about valid document model structures" is **literal**: the metamodel rules are executable law, not documentation.
- The MVK ledger gained MM_Common's 105 codes as its fourth source (`MvkLedgerTest` checks it opt-in against the sibling clone); the other text-bearing source found en route: `FehlertextErrorType` (kernel-core-service — error-text parameter checks; mostly non-`MVK_` codes like `RULE_HAS_NO_ERRORTEXT`, confirming the earlier "not every code is `MVK_*`" note).
- The kernel eats its own dogfood: the validation language validates the validation models. rulekit's catalog/ingest work on metamodel rules (already exercised by the harness) therefore covers the kernel's own usage of its language.

## Rule-condition polarity is fixed — `errorCondition` fires on TRUE, no configuration (2026-06-05 investigation)

A validation rule's condition has **one fixed polarity**: the sole condition slot is `errorCondition`, and the rule fires (= the document is invalid for the error field) exactly when the condition evaluates **TRUE**. So a *requirement* must be authored as its *violation* — to require "X is filled", the `errorCondition` is "X is **not** filled". Confirmed configurable in **no** way:

- **No per-rule polarity slot.** The Rule's slots (MM_Common metamodel + public `IRule` + serializer) are exactly `errorEntity`/`errorCode`/`errorCondition`/`severity`/`errorMessage`/`guiPatterns`/`variantFilter`/`variantErrorMessages` — none flips condition-true between error/ok. `severity` (INFO/WARNING/ERROR) is UI classification only ("does not play any role in the validation language"). The `customRefErrorEntities` pairs only refine *which* entities are blamed, not polarity.
- **No alternate condition form.** There is no `validCondition`/`assertion`/`requirement`/`applicabilityCondition`. `precondition` exists **only on computations** (gates which alternative runs), not on rules — a rule folds any applicability guard into the single `errorCondition` (e.g. `GroupFilled(K) And …`).
- **No model/rule config** reinterprets the condition.
- **No generic `Not` operator** (only `And`/`Or`). Instead the language ships **matched positive/negative predicate pairs** (`FieldFilled`/`FieldNotFilled`, `AllFieldsFilled`/`NoFieldFilled`, `AtLeastOneFieldFilled`/`NotExactlyOneFieldFilled`, `FieldsNotCollectivelyFilled`, …) so the author names the violation directly by predicate choice.

**Consequence (the silent trap):** writing the requirement *positively* (e.g. `FieldFilled(X)` as the errorCondition) is structurally valid and the kernel accepts it — but it encodes the **opposite** rule (fires when X *is* filled). The kernel cannot catch this (both polarities are valid conditions). rulekit's job: teach negation (skill) and flag likely inversion (`RK_INVERTED_REQUIREDNESS` lint — AST-based, so it needs a structured/ingested rule, not raw condition text). Source: `a12-kernel/documentation/ba/validation_language_guide_en.adoc` (§"A Rule always describes the error condition"; "no generic negation operator"; severity is UI-only); `IRule` (md `getErrorCondition`; tool `getErrorConditionText`); `RuleSerializer` slot set; `MM_Common_v2` Rules group; `IComputation.getPrecondition` (computation-only).

## `checkConsistency` runs runtime code generation — the AOT / native-image constraint (2026-06-05 investigation)

**Why this matters.** The standalone CLI (proposal N10, [`CLI-SPEC.md`](CLI-SPEC.md)) is a candidate for **GraalVM native-image** packaging (a tiny, instant-start single binary for the modeller/BA). native-image does **closed-world** AOT: anything that *parses/compiles/defines classes at runtime* is hostile to it. So whether the kernel does runtime codegen — and on which paths — decides whether native-image is possible at all. It also bounds any future AOT/offline scenario and is a third reason a TypeScript twin can't validate (kernel codegen is JVM-only).

**The discovery.** A native-image build of the CLI (which only does `deserialize → expand → checkConsistency`) builds clean but **fails at runtime**: `Error while instantiating template "templates/common/groovy/base/StringParameter"`. The JVM build never fails. That signature = runtime template machinery the closed world couldn't satisfy.

**Root cause — `checkConsistency` generates validation code as a side effect.** It is a *registered consistency rule*, `CodeGenerationRule`, that calls `generateValidationCode(model, new GroovyCodeGeneratorConfig(...), …)` — so **every `checkConsistency` runs full validation-code generation** (this is also the §"structural laws ARE metamodel rules" mechanism above, seen from the codegen side). Two corrections to earlier assumptions fell out:

- **The engine is ANTLR StringTemplate v4, not Groovy's template engine.** The `groovy` in the path is the *target language of the emitted source*, not the engine. The kernel renders source with `org.stringtemplate.v4.{ST,STGroup,STGroupFile}` (kernel `StringTemplateUtil`/`STParameterClassBase`; the error string is thrown by `StringTemplateUtil.getTemplate`). `StringParameter.st` is trivial — `StringParameter(t) ::= << "<t.value>" >>` — emitted for **every field's string-valued metadata** (name, labels, required-error text), so *any* model with ≥1 field triggers it.
- **It is NOT model-content-dependent.** The initial symptom (held-out `catalog` failed, traced models "passed") was an artifact of only testing `catalog` on the native binary. Re-tested: the native binary fails on **all** models (subscription/order/logistics/catalog) at the same template. (`storefront` fails earlier on an unrelated missing-resolver issue.)

**Experimental verification** (probes, JVM, `-verbose:class`; reproducible scripts under the gitignored `build/native-spike/`):

- **The trigger is `checkConsistency`, not `expand`.** A probe doing `expand` only loads ~7 engine-ish classes; `expand + checkConsistency` loads **497** — the entire StringTemplate v4 compiler pipeline (`STParser`, `STLexer`, `GroupParser`, `CodeGenerator`, `Compiler`, `CompiledST`) plus kernel `StringTemplateUtil`/`StringTemplateGroup`. So model *consistency checking* is where runtime codegen happens.
- **`checkConsistency` does NOT reach Groovy compilation.** No `groovy.lang.GroovyClassLoader`/`GroovyShell`, no `org.codehaus.groovy.control.*`, no runtime-defined classes (the `__JVM_LookupDefineClass__` entries are all JDK `LambdaForm`s). The only "groovy" class is `GroovyCodeGeneratorConfig` (a config object naming the target language). **`checkConsistency` emits Groovy *source* via StringTemplate and discards it — it never compiles it.** *(This corrects the `KERNEL-INTEGRATION-NOTES` "no code emitted, no compile" claim: code IS emitted via StringTemplate; only the Groovy compile step is absent here.)*

**The native-image verdict — feasible for the checkConsistency/parse path, with care.** StringTemplate v4 compiles templates to its *own interpreted form*, not JVM bytecode, so it is **native-compatible once its resources are bundled**. The failure was a **missing resource**: `base.stg` does `import "baseDir"`, loading `baseDir/*.st` (e.g. `StringParameter.st`) *by name*; the tracing agent records the `.stg` group files but **misses the import-indirected `.st` files**, so they weren't in the image. Rebuilding with `-H:IncludeResources='internal/templates/.*'` (+ `.*\.st`/`.*\.stg`) **fixed it**: the native binary then **passed on all four self-contained models, output byte-identical to the JVM**, **~37 ms startup** (vs ~600 ms JVM, ~16×), 36 MB. So native-image is viable for rulekit's verbs (all of which are `checkConsistency`/parse-based).

**The remaining hard ceiling — Groovy runtime compilation on the validation-*execution* path.** Actually *running* a model's validators against a document (the kernel's `getValidator()`/on-the-fly testing) compiles the emitted Groovy source at runtime via `GroovyClassLoader.parseClass` (`ModelGroovyCode`) / `GroovyShell` (`OnTheFlyTestingValidatorGenerator`) — genuine runtime JVM class generation, which native-image **cannot** do and resources can't fix. rulekit's CLI never executes validation (it authors/validates *model structure*, the kernel is the runtime), so this isn't a blocker — but it is a permanent boundary: **a native rulekit must never depend on the kernel's runtime validation execution.**

**Breadth / future risk.**
- Runtime codegen in the kernel is exactly two mechanisms, both reachable from the public model-service path: **(1) StringTemplate v4** (resource-fixable; reached by *every* `checkConsistency`) and **(2) Groovy runtime compilation** (not fixable; only on validation execution). No direct ASM/Javassist/CGLIB/`javax.tools` use exists elsewhere in the kernel/base/utils sources.
- **Tracing-agent metadata is not sufficient by itself** for this kernel: import-indirected StringTemplate resources are missed, so a native build needs *broad* resource inclusion (`internal/templates/**`) plus the closed-world discipline of testing **held-out** models (the agent-only build gave a false "works" on traced models and a false "categorically broken" on the held-out one — only the resource-bundling probe got it right).
- **Performance footnote:** `checkConsistency` pays the StringTemplate parse/compile cost per process (497 classes, the ST compiler) — relevant to CLI startup and the resident-mode trade-off.

**Source anchors** (kernel-internal, consult-only — from the `a12-kernel` checkout): `StringTemplateUtil.getTemplate` (the error string) + `getActCodeGroup` (`STGroupFile`); `STParameterClassBase` / `StringParameter` (`@StringTemplateGroup("/common/$programmingLanguage$/base")`); `CodeGenerationRule.checkModel` → `ExternalDocumentModelService.generateValidationCode` (the trigger); `ModelGroovyCode` (`GroovyClassLoader.parseClass`) and `OnTheFlyTestingValidatorGenerator` (`GroovyShell`) for the execution-only Groovy wall. Templates ship in `kernel-core-codegen-base` under `internal/templates/{common,validation}/{groovy,java,…}/`.

## `checkConsistency` notification granularity — `getSource()` is the `IRule` (2026-06-04 probe)

`RankedNotification` exposes `getMessage()`, `getSeverity()`, and **`getSource()` (returns `Object`)** — rulekit had been reading only the first two. Probed by injecting a kernel-rejected rule (`[CustomerName] < [ProductCode]` → `MVK_INVALID_TYPE_FOR_COMPARISON`) and inspecting the source:

- `getSource()` is the offending **`IRule`** element (concrete class `…internal.wrapper.RuleWrapper`, but it implements the **public** `IRule` — so consuming it is N8-safe). It carries `errorCondition`/`errorEntity`/`errorCode`, i.e. **rule granularity**.
- It does **not** point at the offending *operator/node* inside the condition, and there is no character offset or span — so it gives nothing finer than `where.rule`, which rulekit already fills. Useful instead for **rule attribution** when checking a model with many rules at once (which problem belongs to which rule); the F4a span-pointing operator-pin therefore stays the AST↔catalog set-intersection heuristic (`lint.SpanResolver`). **Scope correction (the gap is kernel→node, identical in both phases):** *character-precise* spans for rulekit-**authored** rules are achievable in **Phase 1** — the renderer already produces the text, so a node→`[start,end)` emit-map plus node-instance localization would highlight the exact substring without ingest. Only *foreign*-rule spans truly need ingest: no AST exists until the text is parsed, and the kernel's parser (unlike `checkConsistency`) may carry token positions for its own diagnostics. *(Documented, not locked — it concerns a notification field rulekit doesn't consume; precedent: the deserialize-tier entry.)*

## Runtime kernel-version detection — the jar manifest, via `Package` (F11 spike, 2026-06-04)

The kernel jars carry standard Maven manifest attributes, and the JVM exposes them at runtime with no kernel-specific API. Probed by reading `Package` off two loaded kernel classes (`IDocumentModel`, `DocumentModelServiceFactory`):

- `c.getPackage().getImplementationVersion()` → **`30.8.1`** (the full `Implementation-Version`); `getSpecificationVersion()` → **`30.8`** (`Specification-Version`, the minor line). `getImplementationTitle()` → the per-module artifact id (e.g. `'kernel-md-model-api'`).
- These come from the jar's `META-INF/MANIFEST.MF` main attributes — present on every kernel module checked (`kernel-core-parser`, `kernel-core-service`, `kernel-md-model`, …). So any class rulekit already imports works as the version source; no dependency on a kernel constant or service.
- **Treatment (F11):** rulekit reads this once at validation time and compares to the catalog's stamped `verifiedAgainst` floor; a mismatch is a single INFO/WARNING, never an error. The verification floor only rises (oldest supported = current), so the live concern is newer-than-floor skew.

## Parsetree construct-name enums — the kernel's machine-readable operator inventory (N3 reverse anchor, 2026-06-04)

The package `…core.parsetree.a12internal.api.types` holds ~18 construct-name enums (`kernel-core-parsetree-api`) — the kernel's own operator inventory, the second N3 parity anchor. Discovered by reflecting over each (`adapter.KernelConstructParityTest`):

- **`getRuntimeName()` returns the German keyword; the enum constant's `name()` is the English/canonical construct name.** E.g. `EntityListConditionName.AllFieldsFilled.getRuntimeName()` → `AlleFelderAngegeben`. So `name()` ≈ catalog `keywords.en`, `getRuntimeName()` ≈ catalog `keywords.de` — the matching keys for the reverse-parity test.
- **Not all enums have `getRuntimeName()`.** The symbolic families don't: `ComparisonOperation` (`GT/NE/LT/EQ/GE/LE`, tolerance `NE1/NE2/NE5/NE10`, pattern `WM/NM`), `CalcOp` (`ADD/MULTIPLY/SUBTRACT/DIVIDE/POWER`), `RootGroupConditionName` (German-only). These render as glyphs (`>`, `+`, …) or are out of scope, so they map to the catalog differently (by id) or not at all.
- **Some members are legacy German-only aliases** with no English name (`name()` == `getRuntimeName()`, both German): `EntityListConditionName.{ZeilenNichtAufsteigend, ZeilenLueckenlos, ZeileNichtEindeutig}` (the last superseded by `RepetitionNotUnique`), `EntityListOperationName.MaximalAngegebenerZeilenindex`, `FieldIdOperationName.AktuelleVordruckNummer`.
- **`RootGroupConditionName` is multi-form (Vordruck/USB) scope** — `MindestensEinVordruckAngegeben`, `UsbNichtFortlaufend`, … — outside rulekit's single-document-model scope.
- **The enums carry the full coverage backlog**, machine-readably: families rulekit hasn't modeled (date extraction, sub-day date add/diff, op-list math, tolerance comparisons, value coercions) appear as enum members with no catalog counterpart — exactly the operators a future increment (or a kernel upgrade) would add. `KernelConstructParityTest`'s exclusion list enumerates them with reasons.

## Parsing a condition to the AST — shapes for the F10 converter (2026-06-04 probe)

Verified by parsing rulekit-authored conditions via `IMVK_Service.parseBedingungNoExpand(IRule, pr)` (parses the rule's *own* stored `errorCondition`) and inspecting the `ICondition` tree:

- **`ICondition` carries no discriminator** — no `getName()`, no kind enum, only `traverse(ASTVisitor)`. Dispatch by `instanceof` against the concrete subtype interfaces.
- **Single-field presence is canonicalized into the field-list node.** `FieldFilled(x)` parses to an **`IEntityListCondition` named `AllFieldsFilled`** with a one-element entity list; `FieldNotFilled(x)` → name **`NoFieldFilled`**, one element. There is no dedicated single-field "filled" node and no `FieldFilled`/`FieldNotFilled` enum constant — the single-field and field-list forms collapse to the same node, distinguished only by entity-list arity. (So a 1-element `AllFieldsFilled`/`NoFieldFilled` round-trips to rulekit's `.filled()`/`.notFilled()`.)
- **`IComparisonCondition`**: `getOperation1()` (left) / `getOperation2()` (right) → `IOperation`; `getCmpOp()` → `ComparisonOperation` (`GT/LT/GE/LE/EQ/NE` + tolerance `NE1/2/5/10` + pattern `WM/NM`). A plain field operand is `IFieldIdOperation` with `getOperatorName() == FieldValue` (other names = `Length`/`Round…`/`AbsValue`/… wrappers); `getField().getFullName()` is the resolved absolute path. A literal is `IConstantOperation.getValue()` — **always a String**; its kind is `IOperation.getType()` (`OperationType {NUMBER, STRING, DATE, DATE_RANGE}`), not on the operand subtype. So a number comparison can be rebuilt with the kernel's literal string directly via rulekit's `gt(String)`/`eq(String)`/… overloads (no re-parsing).
- **`ICompositeCondition`**: `getLogOp()` → `LogOp {And, Or}`; `getConditionList()` → `List<ICondition>` (**n-ary**, not binary); `hasBrackets()` records source parenthesization.
- **`IEntityId` has no value-kind** and no `isGroup()` (derive from `!isField()`); the field kind comes from the model, not the path node — rulekit's converter reads it via `BoundModel.fieldAt(path).kind()`.
- **Aggregate operands** (`Sum`/`MaxValue`/`MinValue`/`NumberOfDifferentValues`/…) are `IEntityListOperation` (`getOperatorName()` → `EntityListOperationName`, `getEntityList()` → `IEntityIdList`), appearing as the left operand of an `IComparisonCondition`. For a wildcard scope like `Sum(Items*/Count)`, the single entity's `getFullName()` is `/Order/Items/Count` (**the `*` is dropped**) and `getIndices()` is `[1, 0, 1]` — the **`0` is `IParseTreeEntity.WILDCARD`**, marking which path segment was flattened (here `Items`). So the rulekit `*`-path is rebuilt by re-inserting `*` after each segment whose index is `WILDCARD`. A **`Having` filter** rides on that entity as `getFilterCondition()` → a nested `ICondition` (e.g. the `[Items/Sku] == "A"` comparison), which converts recursively and attaches via `FieldListRef.having(...)`.
- **Iteration operands/conditions.** `CurrentRepetition(group)` is an `IGroupOperation` (`getOperatorName()` → `GroupOperationName.CurrentRepetition`, `getGroup()` → the group `IEntityId`), a numeric operand of a comparison. `RepetitionNotUnique`/`FieldValuesNotUnique` are `IEntityListCondition`s (those names in `EntityListConditionName`): the key entities carry the **`ITERATION` (-1) index** at the repeatable level (e.g. `/Subscription/Addons/Name` → indices `[1, -1, 1]`), and a `… From <group>` scope, when present, rides on `getAdditionalGroupReference()` (absent → empty).

## Schema oracle — what the kernel-mm metamodels formalize (instrument 3, 2026-06-04)

Read-only mining of the sibling `a12-kernel-mm` canonical set (own-words summary; EUPL — nothing copied). Harness: `KernelMmIntegrationTest` (opt-in, skips when the clone is absent).

- **All 59 kernel-mm files are self-contained** — none carries `modelReferences`. The "multi-variant reference scheme needs a filesystem resolver" assumption was wrong: variants are file *naming* (`a12-`/`codegen-`/`none-` × current/`-previous`), not references. `ReferenceResolvers.selfContained()` suffices.
- **Canonical set = 15 models** (one per directory, preferring `a12-`, else `codegen-` — `MM_Common_v2` ships only `codegen-`), carrying **547 rules** and 6 computations.
- **Version tolerance verified by execution**: all 15 are `modelVersion 28.3.0` and load + expand + pass `checkConsistency` under our pinned **30.8.1** kernel.
- **Typedef hardening**: the metamodels are typedef-heavy (~310 `TypeDefType` field occurrences), and the model index classified **all 473 canonical fields to concrete kinds — zero UNKNOWN** (`IField.getEffectiveType()` resolves them after expansion, as designed).
- **`CT_MODEL_HEADER` formalizes the DM header itself**: `header{id, modelType, modelVersion, description, locales[]{code}, labels[]{locale,text}, annotations[]{name,value}, modelReferences[]{alias, modelType, purpose, reference}}` — i.e. the *reference scheme* Corpus-1 workspace models use (`alias`/`purpose`/`reference`) is documented here for the future resolver work, and `locales.code` is a **typedef** (the format constraint that fails at deserialize — KERNEL-FINDINGS §13). Its one rule: a model id must not start with `xml` (`[id] PatternMatched "[xX][mM][lL].*"` — `HEADER_ID_STARTS_WITH_XML`).
- **Constraint-rule topics** (per-operator occurrences across the 547 conditions): `FieldFilled` 293 / `FieldNotFilled` 246, `RepetitionNotUnique` 90, `CustomCondition` 53, `PatternViolated`/`PatternMatched` 55, `GroupFilled` 41, `Length` 7 — re-confirming the corpus backlog's priority of patterns, value-lists, `RepetitionNotUnique`, and `CustomCondition`.

## §10 supplement — the model-include (`modelAlias`) mechanism + the mount-suffix path (2026-06-04)

How a DM includes another (the workspace `purpose:"include"` scheme, adapted to our own domain in `storefront.dm.json` → `catalog.dm.json`):

- **Header**: `modelReferences: [{alias, modelType:"document", purpose:"include", reference:<includedModelId>}]`.
- **Content**: a placeholder **Group** with `"Group": {"repeatability": 1, "modelAlias": <alias>}` and **no inline `elements`** — `expand(model, resolver)` mounts the referenced model's content there. The `resolver` (`IDocumentModelReferenceResolver.getDocumentModel(reference)`) returns the included model; a directory resolver maps `reference` → `<reference>.json`.
- **The mount group *id* gets a `_` suffix on expand** (`Inventory_`), with the included root group `Catalog` nested under it — but **`id` ≠ name**. The kernel's `getId()` is an opaque/suffixed identifier; the **name-path** (the join of `getName()` segments, which is what a condition references via `getFullName()`) is the unsuffixed **`/Storefront/Inventory/ListPrice`** — and notably the included root group `Catalog` does **not** appear as a name segment (the mount surfaces the included fields directly under the mount group's name).
- **rulekit indexes by name-path (F-IDX, 2026-06-05), not by id** — so the handle path *is* the unsuffixed, condition-facing `/Storefront/Inventory/ListPrice`. (Before F-IDX the index keyed by `getId()`, exposing the suffixed `/Storefront/Inventory_/Catalog/ListPrice`; that id-path was never authorable in a condition — `MVK_INVALID_ENTITY` — which was the tell.) Whether a *written* rule referencing an included field round-trips through the kernel is still the deferred fork-9 path-policy follow-up.

rulekit treatment + the write-separation guarantee live in `KERNEL-INTEGRATION-NOTES.md`. *(Locked: `IncludesWritePathTest`.)*

## §13 supplement — the message-locale law

Header `locales` are **arbitrary** valid Java locale codes (`fr_FR` is fine; a malformed code like `en_us` fails at *deserialization* — "Invalid locale format" — before any consistency check). English/German are not special here; only the *condition keyword* language is limited to the EN/DE bundles.

**Language-only codes (`en`, `de`): they DESERIALIZE but the VALIDATION mode rejects them** (corpus check + experiment, 2026-06-05; locked by `dsl.BareLocaleTest`). A `Locale` needs only a language, so `en` parses, and the bare form **dominates the A12 Tools workspaces**: **268** models declare `[{"code":"en"},{"code":"de"}]` vs **107** with `en_US` (e.g. `advanced/models/CommonFieldDefinitions_TDM.json`); our own fixtures and `a12-kernel-mm` use the full `language_COUNTRY` form. **BUT** `checkConsistency` (the validation mode, run by `BoundModel.ofJson`) derives a validation language from the model's locale and **rejects the bare code**: `IllegalArgumentException "Die im ValidierungsModus übergebene Sprache en wird nicht unterstützt"` ("language en is not supported in validation mode"). The full `en_US` is supported. So:

| Tier | `en` (bare) | `en_US` (full) | `en_us` (malformed) |
|---|---|---|---|
| deserialize | ✅ parses | ✅ | ❌ "Invalid locale format" |
| **validation (`checkConsistency`)** | **❌ not supported** | ✅ | — |

**Consequence:** the validate/author path requires a **full** locale code; a bare-locale model (most of the workspaces) must have its locale normalized to a supported full form (`en`→`en_US`) before it can be validated through rulekit. (Corrects an earlier note that claimed bare codes "just work" — that held only for deserialize.) The message-coverage match is still exact-string: a message must carry the **same** code the header declares.

- A rule/computation must carry an error message for **every declared locale** — a missing one is **`MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING`**; none at all is `MVK_ERROR_MESSAGE_MISSING`.
- A message for an **undeclared** locale is **silently tolerated and ignored** — no diagnostic, so a typo'd locale code or dead text goes unnoticed (rulekit rejects it at build time for exactly that reason).

*(Locked: `RuleAuthoringTest.requiresAMessageForEveryDeclaredLocale` / `rejectsMessagesForUndeclaredLocales`.)*

## Structure-authoring consistency laws — field types & requiredness (modelkit M1/M2, 2026-06-05)

Surfaced while building modelkit (authoring a *whole model* from scratch and running it through `checkConsistency`). These are **model-structure** validity laws (the kind-B oracle), distinct from the rule-condition `MVK_*` codes in the ledger — they fire on the model, not on a rule. Each is now respected by the `structure` builders (made unrepresentable at the API, or enforced fail-fast before emit) and locked by `structure.DocumentModelDraftTest` / `structure.FieldConfigTest`.

- **An `UnspecifiedType` field is not consistency-valid** — `MVK_UNSPECIFIED_TYPE_NO_CODEGEN` ("Field 'X' does not specify a field type. Hence, code generation is not possible."). It's an authoring *placeholder*, never valid in a complete model. So modelkit's easy-kind set is **String / Number / Date / Boolean / Confirm** — `Unspecified` is deliberately excluded (corrects the betterbuilders/Q4 "easy types incl. Unspecified" read).
- **A string `pattern` requires a localized error text** — `MVK_ERROR_MESSAGE_AND_PATTERN_INVALID` ("The pattern '…' is specified, but the corresponding error text is not."). The text lives in the **`StringType.errorMessage`** array — `[{locale, text}]`, one entry **per declared model locale**. So in DM-JSON a patterned string is `"StringType": { "errorMessage": [{locale,text}…], "pattern": "…", "maxLength": … }`. modelkit makes pattern and message inseparable in the API (`pattern(regex, message)`), replicating the message across the model's locales.
- **Enumeration labels are all-or-nothing per declared locale** — `MVK_COUNT_OF_VALUES_INVALID` ("For the language 'en-US' not every XML value has a corresponding display value."). Once *any* value carries a label, *every* value needs a label for *every* declared locale. Bare (unlabelled) enums are fine. Shape: `"EnumerationType": { "values": [{ "value": "X", "label": [{locale,text}…] }, …] }` (label is **per value**, optional, but uniform when present). modelkit enforces completeness fail-fast at emit (it knows the model locales then).

Supporting structural shapes verified along the way (own-words; for the modelkit emitter and any future schema-oracle work):

| Slot | DM-JSON shape | Notes |
|---|---|---|
| **Requiredness** | `Field.requirednessConfig: { "mode": … }` (sibling of `fieldType`) | mode-only is valid — **no error message needed**. Modes: `notRequired` (or omit the config), `absoluteOrRelativeToNextRepAncestor` (plain required), `relativeToParent` ("only if parent group filled", §4). |
| **NumberType** | `{ "maxFractionalDigits": n, "minValue": x, "maxValue": y }` | `maxFractionalDigits` is the decimal scale (§5b); min/max optional. Valid with just a scale (M1 used `maxFractionalDigits: 0`). |
| **StringType** | `{ "minLength", "maxLength", "pattern", "errorMessage" }` | all optional; `pattern` ⇒ `errorMessage` (above). Bare `{ "type": "StringType" }` (no inner block) is valid. |
| **EnumerationType** | `{ "values": [{ "value", "label": [{locale,text}] }] }` | label per value, optional but all-or-nothing per locale (above). |
| **DateRangeType / DateFragmentType** | `{ "format", "rangeSeparator" }` / `{ "formatOfFragment" }` | both DateRange slots required; fragment needs its format (M3). |
| **TypeDef (reuse)** | declare: `content.typeDefinitions: [{ id, name, fieldType:{…} }]`; reference: a field's `fieldType: { "type":"TypeDefType", "TypeDefType": { "typeDefinitionId": "<id>" } }` | the id is **nested** under a `TypeDefType` block, not a sibling of `type` (a bare sibling → deserialize NPE "fieldType is null"). After expansion the field resolves to the typedef's underlying kind (M3). |
| **Field metadata (M4)** | label: `Field.label: [{locale,text}]` (inside the `Field` object, sibling of `fieldType`); annotations: **element-level** `annotations: [{name,value}]` (sibling of `name`, on fields *and* groups) | labels are per-locale; modelkit requires a label to cover every declared locale (uniform with enum labels). Field *descriptions* and *group labels* are rare in both corpora — not modeled yet. |
| **Header / config** | `header{id, modelType:"document", modelVersion, locales[{code}]}` + `content.modelConfig{decimalSeparator, timeZone, conditionLanguage{code}, fieldRefByShortNameAllowed}` + `modelInfo{name, variant, revision, immutable}` | the from-scratch skeleton; a full `language_COUNTRY` locale is required for the validation mode (§13). |

rulekit treatment: the `structure` package emits all of the above; modelkit's own checks (label completeness, min≤max, pattern⇔message, enum non-empty, repeatable≥2) fail fast *before* the kernel, turning these into clear authoring errors rather than cryptic `MVK_*`. *(Locked: `structure.DocumentModelDraftTest`, `structure.FieldConfigTest`.)*

## §paths supplement — relative path forms accepted in conditions (fork #9, 2026-06-04 probe)

Conditions accept paths **relative to the rule's group**, not only absolute — verified against the kernel: a bare segment for a field directly under the rule group (`FieldFilled(MonthlyFee)` at `/Subscription/Addons`), the bracketed value form (`[MonthlyFee]`), and `../` parent navigation to a sibling group's field (`FieldFilled(../Tier)` from `/Subscription/Addons`). All kernel-accepted (computation operations too). **Bare names also resolve *upward* (verified 2026-06-05 via `cli check`):** a bare segment not found directly under the rule group is looked up in **ancestor** groups — `[Tier]` from a rule scoped to `/Subscription/Addons` binds to the ancestor `/Subscription/Tier` (**no `../` needed**), exactly like `[/Subscription/Tier]`; a name found nowhere (`[Bogus]`) is `MVK_INVALID_ENTITY`. So a bare cross-(ancestor-)group reference works without `../`; `../` only matters to reach a different branch. *(This caught an eval judge out — it assumed bare = the rule group only and false-flagged correct candidates; RESULTS.md 2026-06-05 19:06.)* Descending *relatively* into a **repeatable** subgroup still hits the wildcard law (`MVK_NO_WILDCARD`) — that is the iteration rule, orthogonal to the path form. rulekit's renderer now renders rule/computation conditions relative to their group (the authoring norm — `DslRenderer` with a base group); standalone `DslRenderer.render` stays absolute, and absolute remains valid (KERNEL-INTEGRATION-NOTES). **Keyword-segment quoting (the other fork-9 half, verified 2026-06-04 probe):** a path segment whose name collides with a DSL keyword is a **parse error** unquoted — `FieldFilled(Length)` (and even absolute `FieldFilled(/Subscription/Length)`) → **`MVK_UNEXPECTED_TOKEN`** — and is escaped by **single-quoting that segment**: `FieldFilled('Length')` / `FieldFilled(/Subscription/'Length')` validate (grammar `NameOhnePunktMitQuotes : '\'' STR (STR|DIGIT)* '\''`). rulekit's renderer now single-quotes any segment matching a catalog operator keyword (per segment, `*` suffix preserved); the **full** lexer keyword set (beyond the operators rulekit catalogs) rides the N3 parity anchor. *(Locked: `dsl.PathPolicyTest`.)*
