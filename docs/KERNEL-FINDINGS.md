# Kernel-behavior findings (supplement to KERNEL-SEMANTICS.md)

Kernel-behavior facts **verified by experiment against kernel `30.8.1`** that are *not* (yet) covered by the canonical semantics reference [`../a12-kernel-demo/KERNEL-SEMANTICS.md`](../../a12-kernel-demo/KERNEL-SEMANTICS.md) — which we **consult before each increment** and never duplicate here. Topic numbering follows that doc's sections so entries are easy to upstream if the owner ever chooses to. Each finding is locked by a test in this repo (named per entry); rulekit's *treatment* of each fact lives in [`KERNEL-INTEGRATION-NOTES.md`](./KERNEL-INTEGRATION-NOTES.md).

## §1 supplement — Boolean/Confirm comparability codes

The Boolean≠Confirm split is enforced at parse time with distinct codes: a Boolean compares only to `True`/`False` or another Boolean — anything else (e.g. a string) is **`MVK_INVALID_COMPARE_TO_YESNO`**; a Confirm compares only to `True` or another Confirm — `[Confirm] == False` and Boolean↔Confirm cross-comparisons are **`MVK_INVALID_COMPARE_TO_YES`**. "Not confirmed" is written `[Confirm] != True` (an unset confirm counts as not confirmed). *(Locked: `validate.laws.BooleanConfirmLawsTest`.)*

## §5 supplement — numeric literals are exempt from the scale check

The `==`/`!=` decimal-scale gate applies to **field/expression operands only**. Against a scale-2 field, `== 0`, `== 0.0`, `== 0.00`, `!= 0`, and `<= 0.000` are all accepted — only field-vs-field/expression comparisons of differing declared scale draw `MVK_INVALID_COMPARE_DEC_PLACES`. Consequence: scale-explicit literals (`0.00`) are a rendering-fidelity nicety, not a correctness requirement. *(Locked: `validate.laws.NumberLawsTest`.)*

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

**Deferred to slice 7f — needs index-field fixtures.** Both the **semantic index** (`Fuer`/`For`; grammar `'Fuer' (entitySpec | StringLiteral)`) and **parallel iteration** require a repeatable group to declare an **index field**, which our fixtures lack: `[Field Fuer "1"]` → **`MVK_NO_INDEX_FIELD`** ("there is no repeatable group with index field"), and `Fuer "const"` is even a parse error in that position. The semantic-index code family (`MVK_SEMANTIC_INDEX_*`, `MVK_INDEX_*`) and the still-deferred pre-checks (`MVK_NO_WILDCARD` missing-star, RNU position) all hinge on an explicit **iteration model** over index-field-bearing groups — its own slice once a fixture grows the metadata. Low demand (corpus: semantic index 8 / 0, `For` parallel iteration negligible).

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

## How `checkConsistency` actually works — structural laws ARE metamodel rules (2026-06-04)

Found while repairing the MVK ledger's coverage hole (`MVK_ERROR_MESSAGE_MISSING`/`MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING` appear at runtime but in no kernel enum, no source file, and no plain string in any jar): `checkConsistency`'s `CodeGenerationRule` runs `generateValidationCode`, and part of that pipeline **validates the model itself as a document against the `MM_Common` metamodel** — using validators *code-generated from the metamodel's rules* (the `mmvalidators-extmpinternal-mm_common-v2-codegen` jar, transitively on the classpath). The two "missing" codes are ordinary **rule `errorCode`s authored in MM_Common**, alongside **103 more** — 105 structural laws expressed as A12 rules over the metamodel.

Consequences:
- The owner's "kernel-mm is the source of truth about valid document model structures" is **literal**: the metamodel rules are executable law, not documentation.
- The MVK ledger gained MM_Common's 105 codes as its fourth source (`MvkLedgerTest` checks it opt-in against the sibling clone); the other text-bearing source found en route: `FehlertextErrorType` (kernel-core-service — error-text parameter checks; mostly non-`MVK_` codes like `RULE_HAS_NO_ERRORTEXT`, confirming the earlier "not every code is `MVK_*`" note).
- The kernel eats its own dogfood: the validation language validates the validation models. rulekit's catalog/ingest work on metamodel rules (already exercised by the harness) therefore covers the kernel's own usage of its language.

## Schema oracle — what the kernel-mm metamodels formalize (instrument 3, 2026-06-04)

Read-only mining of the sibling `a12-kernel-mm` canonical set (own-words summary; EUPL — nothing copied). Harness: `KernelMmIntegrationTest` (opt-in, skips when the clone is absent).

- **All 59 kernel-mm files are self-contained** — none carries `modelReferences`. The "multi-variant reference scheme needs a filesystem resolver" assumption was wrong: variants are file *naming* (`a12-`/`codegen-`/`none-` × current/`-previous`), not references. `ReferenceResolvers.selfContained()` suffices.
- **Canonical set = 15 models** (one per directory, preferring `a12-`, else `codegen-` — `MM_Common_v2` ships only `codegen-`), carrying **547 rules** and 6 computations.
- **Version tolerance verified by execution**: all 15 are `modelVersion 28.3.0` and load + expand + pass `checkConsistency` under our pinned **30.8.1** kernel.
- **Typedef hardening**: the metamodels are typedef-heavy (~310 `TypeDefType` field occurrences), and the model index classified **all 473 canonical fields to concrete kinds — zero UNKNOWN** (`IField.getEffectiveType()` resolves them after expansion, as designed).
- **`CT_MODEL_HEADER` formalizes the DM header itself**: `header{id, modelType, modelVersion, description, locales[]{code}, labels[]{locale,text}, annotations[]{name,value}, modelReferences[]{alias, modelType, purpose, reference}}` — i.e. the *reference scheme* Corpus-1 workspace models use (`alias`/`purpose`/`reference`) is documented here for the future resolver work, and `locales.code` is a **typedef** (the format constraint that fails at deserialize — KERNEL-FINDINGS §13). Its one rule: a model id must not start with `xml` (`[id] PatternMatched "[xX][mM][lL].*"` — `HEADER_ID_STARTS_WITH_XML`).
- **Constraint-rule topics** (per-operator occurrences across the 547 conditions): `FieldFilled` 293 / `FieldNotFilled` 246, `RepetitionNotUnique` 90, `CustomCondition` 53, `PatternViolated`/`PatternMatched` 55, `GroupFilled` 41, `Length` 7 — re-confirming the corpus backlog's priority of patterns, value-lists, `RepetitionNotUnique`, and `CustomCondition`.

## §13 supplement — the message-locale law

Header `locales` are **arbitrary** valid Java locale codes (`fr_FR` is fine; a malformed code like `en_us` fails at *deserialization* — "Invalid locale format" — before any consistency check). English/German are not special here; only the *condition keyword* language is limited to the EN/DE bundles.

- A rule/computation must carry an error message for **every declared locale** — a missing one is **`MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING`**; none at all is `MVK_ERROR_MESSAGE_MISSING`.
- A message for an **undeclared** locale is **silently tolerated and ignored** — no diagnostic, so a typo'd locale code or dead text goes unnoticed (rulekit rejects it at build time for exactly that reason).

*(Locked: `RuleAuthoringTest.requiresAMessageForEveryDeclaredLocale` / `rejectsMessagesForUndeclaredLocales`.)*
