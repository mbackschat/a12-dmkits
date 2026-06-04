# The MVK ledger — every kernel rule-diagnostic, classified

Instrument 2 of the constraint-mining discipline (`CLAUDE.md`): every `MVK_*` code the kernel can report, classified (300 codes across four sources, kernel `30.8.1`). **`unclassified` is the proactive experiment queue.** `MvkLedgerTest` keeps each section in exact two-way parity with its source (the three enums reflectively — internal tier, test-only per N8; the metamodel section against the sibling `a12-kernel-mm` clone, skipped when absent).

**The fourth source is the deep one** (found 2026-06-04 while repairing this ledger’s coverage hole): `checkConsistency` validates the model *as a document against the MM_Common metamodel* — its generated validators (`mmvalidators-…-codegen` on the classpath) carry 105 structural laws authored as ordinary A12 rules, each with an `MVK_*` errorCode. Structural laws **are** metamodel rules.

Buckets: **erased** — unrepresentable in the typed API · **pre-checked** — caught at `build()`/handle creation with the fix named · **kernel-delegated** — deliberately left to F4 (with reason) · **pending** — its operator family is not built yet (corpus backlog) · **unclassified** — not yet analyzed (queue).

## `ParserErrorTypesEnum` (kernel-core-parser, reflective CI check)

| Code | Bucket | rulekit treatment / lock |
|---|---|---|
| `MVK_BRACKET_MISSING` | erased | the renderer parenthesizes And/Or mixing structurally |
| `MVK_CATEGORY_NOT_ALLOWED` | unclassified | — (experiment queue) |
| `MVK_CATEGORY_REF_OP` | unclassified | — (experiment queue) |
| `MVK_CATEGORY_UNKOWN` | pre-checked | `EnumRef.category(name)` fails fast on undeclared categories |
| `MVK_CONDITION` | unclassified | — (experiment queue) |
| `MVK_CONDITION_WITHOUT_REPEATABLE_ROOTGROUP` | unclassified | — (experiment queue) |
| `MVK_CONDITION_WITH_WILDCARD` | unclassified | — (experiment queue) |
| `MVK_DATEFORMATS_NOT_COMPATIBLE` | unclassified | — (experiment queue) |
| `MVK_DATE_AND_NONDATE` | unclassified | — (experiment queue) |
| `MVK_DATE_OR_TIME_EXTRACT_OP_NOT_ALLOWED` | unclassified | — (experiment queue) |
| `MVK_DATE_WITH_AND_WITHOUT_YEAR` | unclassified | — (experiment queue) |
| `MVK_DEPENDENT_COLUMN_SIZE_MISMATCH` | unclassified | — (experiment queue) |
| `MVK_DIFFERENT_GROUPS` | unclassified | — (experiment queue) |
| `MVK_DIFFERENT_NO_VALUE` | unclassified | — (experiment queue) |
| `MVK_DIFFERENT_ROOT_GROUPS` | unclassified | — (experiment queue) |
| `MVK_DIFFERENT_YES_VALUE` | unclassified | — (experiment queue) |
| `MVK_DISALLOWED_CONSTRUCT_IN_FILTER_CONDITION` | unclassified | — (experiment queue) |
| `MVK_DUPLICATE_PARAM1` | unclassified | — (experiment queue) |
| `MVK_DUPLICATE_PARAM2` | unclassified | — (experiment queue) |
| `MVK_ENTITY_MOVE_IN_A12_MODEL_CONDITION` | unclassified | — (experiment queue) |
| `MVK_ENTITY_MOVE_IN_A12_MODEL_ERRORTEXT` | unclassified | — (experiment queue) |
| `MVK_ENUM_VALUES_NOT_IN_COLUMN` | unclassified | — (experiment queue) |
| `MVK_ERROR_FIELDGROUP_ITERATION4_INVALID` | unclassified | — (experiment queue) |
| `MVK_ERROR_FIELDGROUP_ITERATION5_INVALID` | unclassified | — (experiment queue) |
| `MVK_ERROR_FIELD_AND_ITERATION_INVALID` | unclassified | — (experiment queue) |
| `MVK_ERROR_FIELD_INVALID_INDEX` | unclassified | — (experiment queue) |
| `MVK_ERROR_FIELD_INVALID_INDEX_TYPE` | unclassified | — (experiment queue) |
| `MVK_ERROR_FIELD_NOT_IN_RULEGROUP` | unclassified | — (experiment queue) |
| `MVK_ERROR_FIELD_NOT_REFERENCED` | pre-checked | `Rule.build()` via `ConditionReferences.covers` (incl. enclosing-group rule) |
| `MVK_ERROR_FIELD_NOT_VALID` | unclassified | — (experiment queue) |
| `MVK_ERROR_FIELD_NO_INDEX` | unclassified | — (experiment queue) |
| `MVK_EXTRACT_OP_NOT_ALLOWED_4_DATE_CONSTRUCT_WITH_4_PARAMS` | unclassified | — (experiment queue) |
| `MVK_FIELDNAME_NOT_UNIQUE` | unclassified | — (experiment queue) |
| `MVK_FIELD_REF_OP` | unclassified | — (experiment queue) |
| `MVK_GROUP_IS_EMPTY` | unclassified | — (experiment queue) |
| `MVK_GROUP_NOT_REPEATABLE` | pending | iteration cluster (corpus item 7) |
| `MVK_INCONSISTENT_TYPES_COMPARED` | unclassified | — (experiment queue) |
| `MVK_INDEX_FIELD_INVALID` | unclassified | — (experiment queue) |
| `MVK_INDEX_TYPE_INCONSISTENT` | unclassified | — (experiment queue) |
| `MVK_INDEX_VALUE_INVALID` | unclassified | — (experiment queue) |
| `MVK_INVALID_COMBINATION_OF_REPETITON_NOT_UNIQUE` | unclassified | — (experiment queue) |
| `MVK_INVALID_COMPARE_DEC_PLACES` | kernel-delegated | by design (D13): result scales are the kernel’s; literals are exempt (KERNEL-FINDINGS §5) |
| `MVK_INVALID_COMPARE_ENUM_TO_ENUM` | unclassified | — (experiment queue) |
| `MVK_INVALID_COMPARE_ENUM_TO_STRING` | unclassified | — (experiment queue) |
| `MVK_INVALID_COMPARE_TO_CONST` | erased | handles always put a field value on one side; constant-vs-constant unrepresentable (verified via pattern experiments 2026-06-04) |
| `MVK_INVALID_COMPARE_TO_DATE` | unclassified | — (experiment queue) |
| `MVK_INVALID_COMPARE_TO_DATE_RANGE` | unclassified | — (experiment queue) |
| `MVK_INVALID_COMPARE_TO_ENUM_OR_STRING` | pre-checked | operation kind must match the computed field (`Calculation.kind()`) |
| `MVK_INVALID_COMPARE_TO_YES` | erased | `ConfirmRef` has no `isFalse()`; Boolean↔Confirm cross-compare unrepresentable |
| `MVK_INVALID_COMPARE_TO_YESNO` | erased | `BoolRef` compares only to `True`/`False` literals |
| `MVK_INVALID_CONST` | unclassified | — (experiment queue) |
| `MVK_INVALID_DATE_FOR_OP` | unclassified | — (experiment queue) |
| `MVK_INVALID_DATE_OR_TIME_EXTRACT_OP` | unclassified | — (experiment queue) |
| `MVK_INVALID_DATE_RANGE_FORMAT` | unclassified | — (experiment queue) |
| `MVK_INVALID_DATE_STRING` | unclassified | — (experiment queue) |
| `MVK_INVALID_DATE_TYPE` | unclassified | — (experiment queue) |
| `MVK_INVALID_DATE_TYPE_FOR_OP` | unclassified | — (experiment queue) |
| `MVK_INVALID_DIGIT_FOR_ROUNDING` | unclassified | — (experiment queue) |
| `MVK_INVALID_ENTITY` | unclassified | — (experiment queue) |
| `MVK_INVALID_INTERVAL` | unclassified | — (experiment queue) |
| `MVK_INVALID_ITERATION_IN_FILTER_CONDITION` | unclassified | — (experiment queue) |
| `MVK_INVALID_LENGTH_OF_RAW_TYPE` | unclassified | — (experiment queue) |
| `MVK_INVALID_NUMBER_FOR_EXP` | unclassified | — (experiment queue) |
| `MVK_INVALID_OUTER_ITERATION` | unclassified | — (experiment queue) |
| `MVK_INVALID_PARAMETER_FOR_DATE_RANGE_COMPARISON` | unclassified | — (experiment queue) |
| `MVK_INVALID_PARAMETER_FOR_PATTERN_COMPARISON` | erased | `PatternCondition` left side is always a bare string field value — expressions unrepresentable (KERNEL-FINDINGS §7) |
| `MVK_INVALID_PARAMS_FOR_OP` | unclassified | — (experiment queue) |
| `MVK_INVALID_PATTERN` | pre-checked | `StringRef.matches/violates` fail fast via `Pattern.compile` (necessary condition only — the portable-subset gate stays kernel-delegated; locked: `RuleValidatorTest.locksPatternComparisonSlotRules`) |
| `MVK_INVALID_PREDEFINED_TYPE` | unclassified | — (experiment queue) |
| `MVK_INVALID_RAW_TYPE` | unclassified | — (experiment queue) |
| `MVK_INVALID_ROOT_GROUP` | unclassified | — (experiment queue) |
| `MVK_INVALID_ROOT_GROUP_CONSTR` | unclassified | — (experiment queue) |
| `MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION` | pre-checked | `EnumRef.in/notIn` check every value against the enum domain (locked: `RenderConditionTest.enumValueListsPreCheckTheDomain`) |
| `MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION_CATEGORY` | unclassified | — (experiment queue) |
| `MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION_OR_CATEGORY` | pre-checked | the `Dsl` enum value-list quantifiers check every value against every listed field's domain |
| `MVK_INVALID_STRING_CONSTANT_FOR_ENUM_COMPARISON` | pre-checked | `EnumRef.is`/`CategoryRef.is` validate against the model domain |
| `MVK_INVALID_SUPPRESSED_WARNING` | pending | no `@SuppressWarning` surface yet (only MVK_INVALID_COMPARE_DEC_PLACES is suppressible) |
| `MVK_INVALID_TABLE_ENUM` | unclassified | — (experiment queue) |
| `MVK_INVALID_TIME` | unclassified | — (experiment queue) |
| `MVK_INVALID_TYPE` | unclassified | — (experiment queue) |
| `MVK_INVALID_TYPES_FOR_COMPARISON` | erased | value-list handles bind the value type to the field kind (`StringRef.in(String…)`, `NumberRef.in(long…)`; locked: N7 `FieldValueNotIncludedInValueList` invalidExample) |
| `MVK_INVALID_TYPE_FOR_COMPARISON` | erased | no ordering methods on non-orderable handles (`StringRef` has no `le`); N7 invalid examples |
| `MVK_INVALID_TYPE_FOR_PATTERN_COMPARISON` | erased | only `StringRef` offers `matches`/`violates` (enums/numbers/booleans excluded by type; locked: N7 `PatternMatched` invalidExample) |
| `MVK_INVALID_TYPE_FOR_TOLERANCE_OP` | unclassified | — (experiment queue) |
| `MVK_INVALID_UNSPECIFIED_TYPE` | unclassified | — (experiment queue) |
| `MVK_INVALID_WILDCARD` | pre-checked | `BoundModel.flatten` rejects a star on a non-repeatable group with the code named (locked: `RuleValidatorTest.locksWildcardSlotRules`) |
| `MVK_INVALID_WILDCARD_FOR_RULEGROUP` | unclassified | — (experiment queue) |
| `MVK_INVALID_WILDCARD_REL` | pending | fork-9 path policy: `..`+`*` forces absolute paths |
| `MVK_ITERATION_RESTRICTION_IN_A12_MODEL` | unclassified | — (experiment queue) |
| `MVK_MAX_GROUP_RESTRICTION_TO_ONE` | unclassified | — (experiment queue) |
| `MVK_MAX_LFDNR_RESTRICTION_TO_ONE` | unclassified | — (experiment queue) |
| `MVK_MISSING_CONST_IN_PATTERN_COMP` | erased | the regex parameter is a Java `String` — a field reference on the right is unrepresentable (locked: `RuleValidatorTest.locksPatternComparisonSlotRules`) |
| `MVK_MISSING_ERROR_FIELD` | unclassified | — (experiment queue) |
| `MVK_MISSING_YEAR` | unclassified | — (experiment queue) |
| `MVK_MULTIPLE_INDEX_FIELDS` | unclassified | — (experiment queue) |
| `MVK_MULTIPLE_INDEX_FIELDS2` | unclassified | — (experiment queue) |
| `MVK_NEG_CONDITION_IN_ITERATION` | kernel-delegated | by design (decided 2026-06-04 in slice 7a): the law is a full truth-propagation algorithm (`IterationNotValidVisitor`, ~800 lines, context-dependent positivity) — not replicated; F4 is the gate |
| `MVK_NON_NUMERIC` | unclassified | — (experiment queue) |
| `MVK_NOT_IN_GROUP` | unclassified | — (experiment queue) |
| `MVK_NOT_IN_RULEGROUP` | unclassified | — (experiment queue) |
| `MVK_NOT_IN_SAME_PATH` | unclassified | — (experiment queue) |
| `MVK_NOT_ON_ERRORFIELD_PATH` | unclassified | — (experiment queue) |
| `MVK_NOT_SORTABLE` | unclassified | — (experiment queue) |
| `MVK_NO_BASE_YEAR` | unclassified | — (experiment queue) |
| `MVK_NO_BOOLY_ALLOWED` | unclassified | — (experiment queue) |
| `MVK_NO_CONST` | unclassified | — (experiment queue) |
| `MVK_NO_CONVERT_IN_NUMBER` | unclassified | — (experiment queue) |
| `MVK_NO_DATE` | unclassified | — (experiment queue) |
| `MVK_NO_DATE_CONSTANT` | unclassified | — (experiment queue) |
| `MVK_NO_DATE_RANGE` | unclassified | — (experiment queue) |
| `MVK_NO_DIGIT_PATTERN` | unclassified | — (experiment queue) |
| `MVK_NO_EMPTY` | unclassified | — (experiment queue) |
| `MVK_NO_ENUMERATION` | erased | only `EnumRef` offers `category()` |
| `MVK_NO_FIELDS_ALLOWED` | erased | `GroupRef` ≠ `FieldRef` — group predicates take groups only |
| `MVK_NO_GROUPS_ALLOWED` | erased | field predicates take `FieldRef` only |
| `MVK_NO_INDEX_FIELD` | unclassified | — (experiment queue) |
| `MVK_NO_ITERATION_FOR_WILDCARD` | unclassified | — (experiment queue) |
| `MVK_NO_NUMBER` | unclassified | — (experiment queue) |
| `MVK_NO_NUMBER_FOR_CALC` | unclassified | — (experiment queue) |
| `MVK_NO_STRING` | unclassified | — (experiment queue) |
| `MVK_NO_STRING_OR_ENUM` | unclassified | — (experiment queue) |
| `MVK_NO_STRING_OR_ENUM_OR_EXT_ENUM` | unclassified | — (experiment queue) |
| `MVK_NO_STRING_OR_NUMBER_FOR_ADD` | unclassified | — (experiment queue) |
| `MVK_NO_TYPEYESNO` | unclassified | — (experiment queue) |
| `MVK_NO_TYPE_YES_OR_YESNO` | unclassified | — (experiment queue) |
| `MVK_NO_WILDCARD` | kernel-delegated | repeatable group without `*` in a non-iterating rule; pre-check deferred to slice 7e — parallel iteration (index fields) makes plain refs legal, a naive check would false-positive |
| `MVK_NO_WILDCARDS_ALLOWED` | erased | scalar slots take `FieldRef` only; starred lists are `FieldListRef`, accepted only by list slots (locked: `RuleValidatorTest.locksWildcardSlotRules`) |
| `MVK_NO_WILDCARDS_G_ALLOWED` | unclassified | — (experiment queue) |
| `MVK_NUMBER_AND_NON_NUMBER` | unclassified | — (experiment queue) |
| `MVK_ONLY_CONSTANTS_USED` | unclassified | — (experiment queue) |
| `MVK_ONLY_ENUMS_ALLOWED` | unclassified | — (experiment queue) |
| `MVK_ONLY_STRING_ENUM_NUMBER_ALLOWED` | erased | `BoolRef`/`ConfirmRef` offer no `in`/`notIn` (locked: `RuleValidatorTest.locksValueListSlotRules` + N7 `FieldValueIncludedInValueList` invalidExample) |
| `MVK_ONLY_STRING_ENUM_NUMBER_CMP_DATE_ALLOWED` | unclassified | — (experiment queue) |
| `MVK_ONLY_STRING_ENUM_NUMBER_CMP_DATE_ALLOWED_NO_FIELD` | unclassified | — (experiment queue) |
| `MVK_ONLY_STRING_ENUM_NUMBER_DATE_ALLOWED` | unclassified | — (experiment queue) |
| `MVK_PARALLEL_ITERATION_FOR_FILTERED_ENTITY_NOT_ALLOWED` | unclassified | — (experiment queue) |
| `MVK_PARALLEL_ITERATION_INVALID` | unclassified | — (experiment queue) |
| `MVK_PARALLEL_ITERATION_NOT_ALLOWED` | unclassified | — (experiment queue) |
| `MVK_PARAMSIZE_INVALID1` | unclassified | — (experiment queue) |
| `MVK_PARAMSIZE_INVALID2` | unclassified | — (experiment queue) |
| `MVK_PARAMSIZE_INVALIDFN` | unclassified | — (experiment queue) |
| `MVK_PARAMSIZE_INVALIDGN` | erased | `Dsl.numberOfFilledGroups(first, second, …)` requires ≥2 groups by signature (locked: N7 invalidExample) |
| `MVK_PARAMSIZE_INVALIDN` | erased | `Dsl.numberOfFilledFields` signatures require ≥2 fields or ≥1 group (locked: N7 invalidExample) |
| `MVK_PARAMSIZE_INVALID_2` | unclassified | — (experiment queue) |
| `MVK_PARTIAL_DATE_NOT_ALLOWED` | unclassified | — (experiment queue) |
| `MVK_POINT_IN_A12_MODEL` | unclassified | — (experiment queue) |
| `MVK_REF_ERROR_ENTITY_IF_FILLED_NOT_REFERENCED` | unclassified | — (experiment queue) |
| `MVK_REF_ERROR_ENTITY_IF_NOT_FILLED_NOT_REFERENCED` | unclassified | — (experiment queue) |
| `MVK_REPEATABLE_GROUP_MISSING` | unclassified | — (experiment queue) |
| `MVK_REPEATABLE_LEVEL_REQUIRED` | unclassified | — (experiment queue) |
| `MVK_ROOT_GROUP_IS_EMPTY` | unclassified | — (experiment queue) |
| `MVK_ROOT_GROUP_REFERENCED` | pre-checked | `CountAggregate` rejects root-group refs with the code named (locked: `RenderConditionTest.filledCountAggregatesRejectRootGroups`) |
| `MVK_ROOT_GROUP_WITH_OTHER_PARAMETERS` | unclassified | — (experiment queue) |
| `MVK_RULE_GROUP_IS_EMPTY_OR_INVALID` | unclassified | — (experiment queue) |
| `MVK_SEMANTIC_INDEX_AND_WILDCARD` | unclassified | — (experiment queue) |
| `MVK_SEMANTIC_INDEX_BELOW_WILDCARD` | unclassified | — (experiment queue) |
| `MVK_SEMANTIC_INDEX_CONTAINED_IN_INDEX` | unclassified | — (experiment queue) |
| `MVK_SEMANTIC_INDEX_INVALID_ITER` | unclassified | — (experiment queue) |
| `MVK_SEMANTIC_INDEX_NOT_ALLOWED` | unclassified | — (experiment queue) |
| `MVK_SEMANTIC_INDEX_NO_FIELD` | unclassified | — (experiment queue) |
| `MVK_SEMANTIC_INDEX_REF_OP` | unclassified | — (experiment queue) |
| `MVK_SEMANTIC_INDEX_WITH_CATEGORY` | unclassified | — (experiment queue) |
| `MVK_SEMANTIC_INDEX_WITH_SEMANTIC_INDEX` | unclassified | — (experiment queue) |
| `MVK_SEMANTIC_INDEX_WITH_WILDCARD` | unclassified | — (experiment queue) |
| `MVK_STRING_ENUM_AND_NON_STRING_ENUM` | unclassified | — (experiment queue) |
| `MVK_STRING_VALUE_NOT_IN_COLUMN` | unclassified | — (experiment queue) |
| `MVK_TABLE_ENUM_EXPECTED` | unclassified | — (experiment queue) |
| `MVK_TOO_MANY_CONST_FOR_CALC_OP` | unclassified | — (experiment queue) |
| `MVK_TOO_MANY_DIV_FOR_CALC` | unclassified | — (experiment queue) |
| `MVK_TOO_MANY_PARENT_GROUPS` | unclassified | — (experiment queue) |
| `MVK_TOO_MANY_POW_FOR_CALC` | unclassified | — (experiment queue) |
| `MVK_VARYING_TYPES_NOT_ALLOWED` | erased | the `Dsl` value-list quantifiers take all-`StringRef` or all-`EnumRef` lists (locked: `RuleValidatorTest.locksValueListSlotRules` + N7 `AtLeastOneFieldValueIncludedInValueList` invalidExample) |
| `MVK_WILDCARDS_REQUIRED` | unclassified | — (experiment queue) |
| `MVK_WILDCARD_AT_LOWEST_LEVEL_REQUIRED` | unclassified | — (experiment queue) |
| `MVK_WILDCARD_ONLY_AT_LOWEST_LEVEL_ALLOWED` | unclassified | — (experiment queue) |
| `MVK_WRONG_DATE_FORMAT_FOR_OP` | unclassified | — (experiment queue) |

## `CalculationErrorType` (kernel-core-calc, reflective CI check)

| Code | Bucket | rulekit treatment / lock |
|---|---|---|
| `MVK_CALCULATION_FIELD_AND_NO_CALCULATION_RULE` | unclassified | — (experiment queue) |
| `MVK_CALC_INVALID_ACTION_TYPE` | unclassified | — (experiment queue) |
| `MVK_ERROR_CALC_CYCLE` | unclassified | — (experiment queue) |
| `MVK_ERROR_COMPARISON_WITH_OR` | unclassified | — (experiment queue) |
| `MVK_ERROR_EMPTY_PRECONDITION` | pre-checked | `Computation.build()` rejects mixed alternatives; no `.otherwise()` API exists |
| `MVK_ERROR_INDEX_AS_CALCULATED_FIELD` | unclassified | — (experiment queue) |
| `MVK_ERROR_INVALID_COMPARISON` | unclassified | — (experiment queue) |
| `MVK_ERROR_INVALID_ENUM_CALCULATION` | unclassified | — (experiment queue) |
| `MVK_ERROR_INVALID_FIELDNAME` | unclassified | — (experiment queue) |
| `MVK_ERROR_INVALID_FIELD_GATE` | unclassified | — (experiment queue) |
| `MVK_ERROR_INVALID_LANGUAGE_FEATURE` | unclassified | — (experiment queue) |
| `MVK_ERROR_ITERATION_UNCHECKED` | unclassified | — (experiment queue) |
| `MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD` | pre-checked | `Computation.build()` checks preconditions and operations |
| `MVK_ERROR_RESTRICTED_CALCULATION_RULE` | unclassified | — (experiment queue) |
| `MVK_ERROR_SEMANTIC_INDEX_OR_CATEGORY_FOR_ERRORFIELD` | unclassified | — (experiment queue) |
| `MVK_ERROR_WRONG_CALCULATED_FIELD` | unclassified | — (experiment queue) |
| `MVK_ERROR_WRONG_ROUNDING_MODE` | unclassified | — (experiment queue) |
| `MVK_GROUP_AS_CALCULATED_FIELD` | unclassified | — (experiment queue) |

## `FehlertextErrorType` (kernel-core-service, reflective CI check; its non-`MVK_` members like `RULE_HAS_NO_ERRORTEXT` are out of ledger scope)

| Code | Bucket | rulekit treatment / lock |
|---|---|---|
| `MVK_INDEX_FOR_ERROR_TEXT_INVALID` | unclassified | — (experiment queue) |

## `MM_Common` metamodel rules (a12-kernel-mm; opt-in CI check against the sibling clone)

| Code | Bucket | rulekit treatment / lock |
|---|---|---|
| `MVK_ADDITIONAL_CHECK_INVALID` | unclassified | — (experiment queue) |
| `MVK_AMOUNT_INT_PART_MISSING` | unclassified | — (experiment queue) |
| `MVK_ARITY_TOO_BIG` | unclassified | — (experiment queue) |
| `MVK_CATEGORY_NAME_DUPLICATE` | unclassified | — (experiment queue) |
| `MVK_CATEGORY_VALUES_NOT_ALLOWED` | unclassified | — (experiment queue) |
| `MVK_CATEGORY_VALUE_DUPLICATE` | unclassified | — (experiment queue) |
| `MVK_CATEGORY_VALUE_INVALID` | unclassified | — (experiment queue) |
| `MVK_CONDITION_MISSING` | unclassified | — (experiment queue) |
| `MVK_COUNT_OF_VALUES_INVALID` | unclassified | — (experiment queue) |
| `MVK_DATE_RANGE_FORMAT_INVALID_CODEGEN` | unclassified | — (experiment queue) |
| `MVK_DEPENDENT_COLUMNS_DUPLICATE` | unclassified | — (experiment queue) |
| `MVK_DEPENDENT_COLUMNS_EQUALS_COLUMN` | unclassified | — (experiment queue) |
| `MVK_DEPENDENT_COLUMNS_NOT_ALLOWED` | unclassified | — (experiment queue) |
| `MVK_DEPENDENT_COLUMN_NAME_MISSING` | unclassified | — (experiment queue) |
| `MVK_DISPLAY_VALUES_DUPLICATE` | unclassified | — (experiment queue) |
| `MVK_DISPLAY_VALUES_MISSING` | unclassified | — (experiment queue) |
| `MVK_DISPLAY_VALUES_NOT_ALLOWED` | unclassified | — (experiment queue) |
| `MVK_DISPLAY_VALUE_INVALID` | unclassified | — (experiment queue) |
| `MVK_DISPLAY_VALUE_TOO_LONG` | unclassified | — (experiment queue) |
| `MVK_ERROR_CODE_MISSING` | unclassified | — (experiment queue) |
| `MVK_ERROR_FIELD_MISSING` | unclassified | — (experiment queue) |
| `MVK_ERROR_MESSAGE_AND_NO_PATTERN` | unclassified | — (experiment queue) |
| `MVK_ERROR_MESSAGE_AND_PATTERN_INVALID` | unclassified | — (experiment queue) |
| `MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING` | pre-checked | builders require a message per declared locale (KERNEL-FINDINGS §13) — a **metamodel rule** (MM_Common) |
| `MVK_ERROR_MESSAGE_MISSING` | pre-checked | builders require ≥1 message — a **metamodel rule** (MM_Common) |
| `MVK_FORMAT_DATE_RANGE_MISSING` | unclassified | — (experiment queue) |
| `MVK_FORMAT_MISSING` | unclassified | — (experiment queue) |
| `MVK_FRACT_DIGITS_INVALID` | unclassified | — (experiment queue) |
| `MVK_FRACT_DIGITS_MISSING` | unclassified | — (experiment queue) |
| `MVK_INDEX_FIELD_DUPLICATE` | unclassified | — (experiment queue) |
| `MVK_INTERNAL_VALUES_AND_DISPLAY_VALUES` | unclassified | — (experiment queue) |
| `MVK_INTERNAL_VALUES_DUPLICATE` | unclassified | — (experiment queue) |
| `MVK_INTERNAL_VALUES_MISSING` | unclassified | — (experiment queue) |
| `MVK_INTERNAL_VALUES_NOT_ALLOWED` | unclassified | — (experiment queue) |
| `MVK_INTERNAL_VALUE_INVALID` | unclassified | — (experiment queue) |
| `MVK_INTERNAL_VALUE_MISSING` | unclassified | — (experiment queue) |
| `MVK_INVALID_SORTINFO` | unclassified | — (experiment queue) |
| `MVK_INVALID_SORT_PRIO` | unclassified | — (experiment queue) |
| `MVK_LANGUAGES_DUPLICATE` | unclassified | — (experiment queue) |
| `MVK_LANGUAGE_DUPLICATE` | unclassified | — (experiment queue) |
| `MVK_LANGUAGE_FOR_VALUES_MISSING` | unclassified | — (experiment queue) |
| `MVK_LANGUAGE_INVALID` | unclassified | — (experiment queue) |
| `MVK_LANGUAGE_MISSING` | unclassified | — (experiment queue) |
| `MVK_LINE_BREAK_AND_MAX_LENGTH_1_INVALID` | unclassified | — (experiment queue) |
| `MVK_MAX_DIGITS_MISSING` | unclassified | — (experiment queue) |
| `MVK_MAX_LENGTH_TOO_BIG` | unclassified | — (experiment queue) |
| `MVK_MAX_LENGTH_TOO_SMALL` | unclassified | — (experiment queue) |
| `MVK_MAX_MISSING` | unclassified | — (experiment queue) |
| `MVK_MAX_TOO_BIG` | unclassified | — (experiment queue) |
| `MVK_MESSAGE_FOR_LANGUAGE_MISSING` | unclassified | — (experiment queue) |
| `MVK_MIN_LENGTH_BIGGER_MAX_LENGTH` | unclassified | — (experiment queue) |
| `MVK_MIN_LENGTH_TOO_BIG` | unclassified | — (experiment queue) |
| `MVK_MIN_VALUE_BIGGER_THAN_MAX_VALUE` | unclassified | — (experiment queue) |
| `MVK_MISSING_CATEGORY_VALUE` | unclassified | — (experiment queue) |
| `MVK_MISSING_INTERNAL_VALUE` | unclassified | — (experiment queue) |
| `MVK_MISSING_VALUE_FOR_CATEGORY` | unclassified | — (experiment queue) |
| `MVK_NAME_ENDS_WITH_COLON_H_DV` | unclassified | — (experiment queue) |
| `MVK_NAME_FIELDS_DUPLICATE` | unclassified | — (experiment queue) |
| `MVK_NAME_GROUP_DUPLICATE` | unclassified | — (experiment queue) |
| `MVK_NAME_INVALID_A12_DV` | unclassified | — (experiment queue) |
| `MVK_NAME_INVALID_DOT_COLON_A12_DV` | unclassified | — (experiment queue) |
| `MVK_NAME_INVALID_DOT_COLON_H_DV` | unclassified | — (experiment queue) |
| `MVK_NAME_INVALID_H_DV` | unclassified | — (experiment queue) |
| `MVK_NAME_MISSING` | unclassified | — (experiment queue) |
| `MVK_NAME_ROOT_GROUP_DUPLICATE` | unclassified | — (experiment queue) |
| `MVK_NAME_RULES_DUPLICATE` | unclassified | — (experiment queue) |
| `MVK_NAME_STARTS_WITH_DIGIT` | unclassified | — (experiment queue) |
| `MVK_NAME_STARTS_WITH_DIGIT_A12_DV` | unclassified | — (experiment queue) |
| `MVK_NAME_STARTS_WITH_DIGIT_H_DV` | unclassified | — (experiment queue) |
| `MVK_NAME_STARTS_WITH_XML` | unclassified | — (experiment queue) |
| `MVK_NAME_STARTS_WITH_XML_A12_DV` | unclassified | — (experiment queue) |
| `MVK_NO_VALUE_MISSING` | unclassified | — (experiment queue) |
| `MVK_NO_VAL_AND_HINT_LISTS` | unclassified | — (experiment queue) |
| `MVK_OPTIONAL_DATE_TYPE_INVALID` | unclassified | — (experiment queue) |
| `MVK_PATTERN_INVALID` | unclassified | — (experiment queue) |
| `MVK_PROGRAMMING_LANGUAGE_MISSING` | unclassified | — (experiment queue) |
| `MVK_ROOT_GROUP_NAME_INVALID` | unclassified | — (experiment queue) |
| `MVK_ROW_INVALID` | unclassified | — (experiment queue) |
| `MVK_ROW_TOO_BIG` | unclassified | — (experiment queue) |
| `MVK_RULE_NAME_INVALID` | unclassified | — (experiment queue) |
| `MVK_SEVERITY_MISSING` | unclassified | — (experiment queue) |
| `MVK_SORT_FIELD_DUPLICATE` | unclassified | — (experiment queue) |
| `MVK_SORT_INFO_MISSING` | unclassified | — (experiment queue) |
| `MVK_SORT_PRIO_DUPLICATE` | unclassified | — (experiment queue) |
| `MVK_SORT_PRIO_VALUE_MISSING` | unclassified | — (experiment queue) |
| `MVK_STRING_TYPE_NO_VAL_AND_NO_LINE_BREAK` | unclassified | — (experiment queue) |
| `MVK_STRING_TYPE_PARAMS_INCONSISTENT` | unclassified | — (experiment queue) |
| `MVK_SUPP_LANGUAGES_DUPLICATE` | unclassified | — (experiment queue) |
| `MVK_SUPP_LANGUAGES_MISSING` | unclassified | — (experiment queue) |
| `MVK_S_VALUE_INVALID` | unclassified | — (experiment queue) |
| `MVK_S_VALUE_NOT_ALLOWED` | unclassified | — (experiment queue) |
| `MVK_TABLE_AND_COLUMN_NAME` | unclassified | — (experiment queue) |
| `MVK_TOO_MANY_INDEX_FIELDS` | unclassified | — (experiment queue) |
| `MVK_TYPE_MISSING` | unclassified | — (experiment queue) |
| `MVK_UNSPECIFIED_TYPE_NO_CODEGEN` | unclassified | — (experiment queue) |
| `MVK_VALUES_DUPLICATE` | unclassified | — (experiment queue) |
| `MVK_VALUES_FOR_LANGUAGE_MISSING` | unclassified | — (experiment queue) |
| `MVK_VALUE_INVALID` | unclassified | — (experiment queue) |
| `MVK_VALUE_LANGUAGES_DUPLICATE` | unclassified | — (experiment queue) |
| `MVK_VALUE_LANGUAGE_INVALID` | unclassified | — (experiment queue) |
| `MVK_VALUE_TOO_LONG` | unclassified | — (experiment queue) |
| `MVK_VALUE_TOO_SHORT` | unclassified | — (experiment queue) |
| `MVK_YES_NO_INVALID` | unclassified | — (experiment queue) |
| `MVK_YES_NO_INVALID_2` | unclassified | — (experiment queue) |
| `MVK_YES_VALUE_MISSING` | unclassified | — (experiment queue) |

*Classified: 36 of 300 (codes may appear in more than one source); the rest queue up as their operator families land or via dedicated experiments.*
