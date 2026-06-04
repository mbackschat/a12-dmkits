# Kernel-behavior findings (supplement to KERNEL-SEMANTICS.md)

Kernel-behavior facts **verified by experiment against kernel `30.8.1`** that are *not* (yet) covered by the canonical semantics reference [`../a12-kernel-demo/KERNEL-SEMANTICS.md`](../../a12-kernel-demo/KERNEL-SEMANTICS.md) — which we **consult before each increment** and never duplicate here. Topic numbering follows that doc's sections so entries are easy to upstream if the owner ever chooses to. Each finding is locked by a test in this repo (named per entry); rulekit's *treatment* of each fact lives in [`KERNEL-INTEGRATION-NOTES.md`](./KERNEL-INTEGRATION-NOTES.md).

## §1 supplement — Boolean/Confirm comparability codes

The Boolean≠Confirm split is enforced at parse time with distinct codes: a Boolean compares only to `True`/`False` or another Boolean — anything else (e.g. a string) is **`MVK_INVALID_COMPARE_TO_YESNO`**; a Confirm compares only to `True` or another Confirm — `[Confirm] == False` and Boolean↔Confirm cross-comparisons are **`MVK_INVALID_COMPARE_TO_YES`**. "Not confirmed" is written `[Confirm] != True` (an unset confirm counts as not confirmed). *(Locked: `RuleValidatorTest.locksConfirmAndBooleanComparisonRules`.)*

## §5 supplement — numeric literals are exempt from the scale check

The `==`/`!=` decimal-scale gate applies to **field/expression operands only**. Against a scale-2 field, `== 0`, `== 0.0`, `== 0.00`, `!= 0`, and `<= 0.000` are all accepted — only field-vs-field/expression comparisons of differing declared scale draw `MVK_INVALID_COMPARE_DEC_PLACES`. Consequence: scale-explicit literals (`0.00`) are a rendering-fidelity nicety, not a correctness requirement. *(Locked: `RuleValidatorTest.acceptsScaleFreeNumericLiteralsOnEquality`.)*

## §7 supplement — pattern-comparison slot rules and the portable-subset gate (2026-06-04 experiments)

`PatternMatched`/`PatternViolated` (DE: `WieMuster`/`NichtWieMuster`) are grammar-level *infix comparison operators* (`vglOp` alternatives like `==`), but their slot rules are far stricter than the grammar:

- **Left side: a bare string field value only.** A non-string field (number, enum, boolean) → **`MVK_INVALID_TYPE_FOR_PATTERN_COMPARISON`** ("only allowed for string values" — note: *enums are not pattern-comparable* even though they compare to strings with `==`). A string *expression* (e.g. concatenation) → **`MVK_INVALID_PARAMETER_FOR_PATTERN_COMPARISON`** ("only allowed with a field value as first parameter"). A constant → the general **`MVK_INVALID_COMPARE_TO_CONST`**.
- **Right side: a string constant only.** A field reference → **`MVK_MISSING_CONST_IN_PATTERN_COMP`**; a number constant → `MVK_INVALID_TYPE_FOR_PATTERN_COMPARISON`.
- **The regex gate is Java `Pattern.compile` *plus* a portable-subset check** (`PatternUtils.isPatternValid`; the extmp world uses `CommonUtils.isRegExValid` instead): lookbehind `(?<=a)b` is Java-valid yet rejected with **`MVK_INVALID_PATTERN`** — KERNEL-SEMANTICS §7's "portable subset" is *enforced*, not advisory. Consequence: a Java-side `Pattern.compile` pre-check is a necessary condition only; final validity is the kernel's call.
- **The empty pattern `""` is accepted** for document models (the non-extmp branch; extmp additionally requires non-empty).

*(Locked: `RuleValidatorTest.locksPatternComparisonSlotRules` + N7 `PatternMatched`/`PatternViolated` examples.)*

## §7 supplement — string-literal escaping is quote-doubling, not backslashes

The condition lexer's rule is `StringLiteral : '"' ( '""' | ~["] )* '"'` — an embedded quote is **doubled** (`"say ""hi"""`), and **backslash is a plain character** (no escape sequences; regex classes like `\d` are written as-is). Backslash-escaping a quote (`\"`) is a parse error (`MVK_UNEXPECTED_TOKEN`). Found while building the pattern operators: rulekit's renderer had C-style escaping and would have emitted wrong regexes for any pattern containing `\` — fixed and locked. *(Locked: `RuleValidatorTest.locksKernelStringLiteralEscaping` + `RenderConditionTest.quotesStringLiteralsTheKernelWay`.)*

## §8 supplement — enum-category codes and the positional mapping

In DM-JSON, a category's `values` list pairs **positionally** with the enumeration's `values` (entry *i* categorizes enum value *i*); the comparison domain is the category's distinct value set. Codes: comparing to one of the *enum's own* values instead of a *category* value → **`MVK_INVALID_STRING_CONSTANT_FOR_ENUM_COMPARISON`**; an unknown category name → **`MVK_CATEGORY_UNKOWN`** (the kernel's spelling); `->` on a non-enumeration field → **`MVK_NO_ENUMERATION`**. Curiosity: `[Enum -> Category] == [Enum]` (category vs. plain enum field) is *accepted*. *(Locked: N7 `EnumCategory` examples + `RenderConditionTest.categoryHandleErasesTheVerifiedCategoryMistakes`.)*

## §9 supplement — field/group predicates are type-directional

`GroupFilled(field)` → **`MVK_NO_FIELDS_ALLOWED`** ("Only groups or root groups are allowed"); `FieldFilled(group)` → **`MVK_NO_GROUPS_ALLOWED`**. Also re-confirmed experimentally: an enclosing group reference (`GroupFilled(G)`) satisfies the error-field-referenced rule for a field inside `G` — a bare `GroupFilled(ShippingAddress)` condition with error field `ShippingAddress/Street` validates. *(Locked: N7 `GroupFilled`/`FieldFilled` invalid examples + `RuleAuthoringTest.acceptsAnEnclosingGroupReferenceAsErrorFieldReference`.)*

## §9 supplement — repeatability interactions (2026-06-04 experiments)

- **`MVK_NEG_CONDITION_IN_ITERATION` triggers on iteration × unguarded negativity** — reproduced: with the error field under repeatable `Addons` (the rule iterates), both `GroupNotFilled(Addons)` and a bare `FieldNotFilled(Addons/MonthlyFee)` are rejected; the guarded form `GroupFilled(Addons) And FieldNotFilled(Addons/MonthlyFee)` passes. The trigger is the *rule's iteration* (error-field position) combined with a negative condition — not the predicate's group argument per se.
- **A repeatable group referenced from a *non-iterating* rule needs the asterisk** — `FieldFilled(Tier) And GroupNotFilled(Addons)` with error field `Tier` is rejected with **`MVK_NO_WILDCARD`** ("Missing specification of an asterisk for 'Addons'"). A previously unknown law; pre-checkable once the iteration model lands (rulekit knows both the error-field iteration and the group's repeatability).
- **Root groups work with both group predicates** — `GroupFilled(/Subscription)` and `GroupNotFilled(/Subscription)` (non-repeatable root, non-iterating rule) both validate; the kernel parses root-group references into a distinct AST node (`IRootGroupCondition`) but the surface syntax is uniform.

*(No locks yet — pre-checks deferred to the iteration cluster; the codes are classified kernel-delegated in `MVK-LEDGER.md`.)*

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
