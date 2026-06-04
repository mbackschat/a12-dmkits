# Kernel-behavior findings (supplement to KERNEL-SEMANTICS.md)

Kernel-behavior facts **verified by experiment against kernel `30.8.1`** that are *not* (yet) covered by the canonical semantics reference [`../a12-kernel-demo/KERNEL-SEMANTICS.md`](../../a12-kernel-demo/KERNEL-SEMANTICS.md) — which we **consult before each increment** and never duplicate here. Topic numbering follows that doc's sections so entries are easy to upstream if the owner ever chooses to. Each finding is locked by a test in this repo (named per entry); rulekit's *treatment* of each fact lives in [`KERNEL-INTEGRATION-NOTES.md`](./KERNEL-INTEGRATION-NOTES.md).

## §1 supplement — Boolean/Confirm comparability codes

The Boolean≠Confirm split is enforced at parse time with distinct codes: a Boolean compares only to `True`/`False` or another Boolean — anything else (e.g. a string) is **`MVK_INVALID_COMPARE_TO_YESNO`**; a Confirm compares only to `True` or another Confirm — `[Confirm] == False` and Boolean↔Confirm cross-comparisons are **`MVK_INVALID_COMPARE_TO_YES`**. "Not confirmed" is written `[Confirm] != True` (an unset confirm counts as not confirmed). *(Locked: `RuleValidatorTest.locksConfirmAndBooleanComparisonRules`.)*

## §5 supplement — numeric literals are exempt from the scale check

The `==`/`!=` decimal-scale gate applies to **field/expression operands only**. Against a scale-2 field, `== 0`, `== 0.0`, `== 0.00`, `!= 0`, and `<= 0.000` are all accepted — only field-vs-field/expression comparisons of differing declared scale draw `MVK_INVALID_COMPARE_DEC_PLACES`. Consequence: scale-explicit literals (`0.00`) are a rendering-fidelity nicety, not a correctness requirement. *(Locked: `RuleValidatorTest.acceptsScaleFreeNumericLiteralsOnEquality`.)*

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

## §13 supplement — the message-locale law

Header `locales` are **arbitrary** valid Java locale codes (`fr_FR` is fine; a malformed code like `en_us` fails at *deserialization* — "Invalid locale format" — before any consistency check). English/German are not special here; only the *condition keyword* language is limited to the EN/DE bundles.

- A rule/computation must carry an error message for **every declared locale** — a missing one is **`MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING`**; none at all is `MVK_ERROR_MESSAGE_MISSING`.
- A message for an **undeclared** locale is **silently tolerated and ignored** — no diagnostic, so a typo'd locale code or dead text goes unnoticed (rulekit rejects it at build time for exactly that reason).

*(Locked: `RuleAuthoringTest.requiresAMessageForEveryDeclaredLocale` / `rejectsMessagesForUndeclaredLocales`.)*
