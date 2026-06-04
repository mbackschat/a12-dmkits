# The MVK ledger — every kernel rule-diagnostic, classified

Instrument 2 of the constraint-mining discipline (`CLAUDE.md`): every `MVK_*` code of the kernel’s rule diagnostics, classified. Sources (kernel `30.8.1`): `ParserErrorTypesEnum` (176 codes) and `CalculationErrorType` (18 codes) — both internal-tier, read reflectively by `MvkLedgerTest` (test-only, N8). **`unclassified` is the proactive experiment queue** — the anti-whack-a-mole instrument.

Buckets: **erased** — unrepresentable in the typed API · **pre-checked** — caught at `build()`/handle creation with the fix named · **kernel-delegated** — deliberately left to F4 (with reason) · **pending** — its operator family is not built yet (corpus backlog) · **unclassified** — not yet analyzed (queue).

## `ParserErrorTypesEnum`

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
| `MVK_INVALID_COMPARE_TO_CONST` | unclassified | — (experiment queue) |
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
| `MVK_INVALID_PARAMETER_FOR_PATTERN_COMPARISON` | unclassified | — (experiment queue) |
| `MVK_INVALID_PARAMS_FOR_OP` | unclassified | — (experiment queue) |
| `MVK_INVALID_PATTERN` | unclassified | — (experiment queue) |
| `MVK_INVALID_PREDEFINED_TYPE` | unclassified | — (experiment queue) |
| `MVK_INVALID_RAW_TYPE` | unclassified | — (experiment queue) |
| `MVK_INVALID_ROOT_GROUP` | unclassified | — (experiment queue) |
| `MVK_INVALID_ROOT_GROUP_CONSTR` | unclassified | — (experiment queue) |
| `MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION` | unclassified | — (experiment queue) |
| `MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION_CATEGORY` | unclassified | — (experiment queue) |
| `MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION_OR_CATEGORY` | unclassified | — (experiment queue) |
| `MVK_INVALID_STRING_CONSTANT_FOR_ENUM_COMPARISON` | pre-checked | `EnumRef.is`/`CategoryRef.is` validate against the model domain |
| `MVK_INVALID_SUPPRESSED_WARNING` | pending | no `@SuppressWarning` surface yet (only MVK_INVALID_COMPARE_DEC_PLACES is suppressible) |
| `MVK_INVALID_TABLE_ENUM` | unclassified | — (experiment queue) |
| `MVK_INVALID_TIME` | unclassified | — (experiment queue) |
| `MVK_INVALID_TYPE` | unclassified | — (experiment queue) |
| `MVK_INVALID_TYPES_FOR_COMPARISON` | unclassified | — (experiment queue) |
| `MVK_INVALID_TYPE_FOR_COMPARISON` | erased | no ordering methods on non-orderable handles (`StringRef` has no `le`); N7 invalid examples |
| `MVK_INVALID_TYPE_FOR_PATTERN_COMPARISON` | unclassified | — (experiment queue) |
| `MVK_INVALID_TYPE_FOR_TOLERANCE_OP` | unclassified | — (experiment queue) |
| `MVK_INVALID_UNSPECIFIED_TYPE` | unclassified | — (experiment queue) |
| `MVK_INVALID_WILDCARD` | unclassified | — (experiment queue) |
| `MVK_INVALID_WILDCARD_FOR_RULEGROUP` | unclassified | — (experiment queue) |
| `MVK_INVALID_WILDCARD_REL` | pending | fork-9 path policy: `..`+`*` forces absolute paths |
| `MVK_ITERATION_RESTRICTION_IN_A12_MODEL` | unclassified | — (experiment queue) |
| `MVK_MAX_GROUP_RESTRICTION_TO_ONE` | unclassified | — (experiment queue) |
| `MVK_MAX_LFDNR_RESTRICTION_TO_ONE` | unclassified | — (experiment queue) |
| `MVK_MISSING_CONST_IN_PATTERN_COMP` | unclassified | — (experiment queue) |
| `MVK_MISSING_ERROR_FIELD` | unclassified | — (experiment queue) |
| `MVK_MISSING_YEAR` | unclassified | — (experiment queue) |
| `MVK_MULTIPLE_INDEX_FIELDS` | unclassified | — (experiment queue) |
| `MVK_MULTIPLE_INDEX_FIELDS2` | unclassified | — (experiment queue) |
| `MVK_NEG_CONDITION_IN_ITERATION` | kernel-delegated | verified 2026-06-04 (KERNEL-FINDINGS §9): iterating rule × unguarded negative condition; pre-check candidate with the iteration model |
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
| `MVK_NO_WILDCARD` | kernel-delegated | verified 2026-06-04: repeatable group without `*` in a non-iterating rule; iteration cluster pending |
| `MVK_NO_WILDCARDS_ALLOWED` | unclassified | — (experiment queue) |
| `MVK_NO_WILDCARDS_G_ALLOWED` | unclassified | — (experiment queue) |
| `MVK_NUMBER_AND_NON_NUMBER` | unclassified | — (experiment queue) |
| `MVK_ONLY_CONSTANTS_USED` | unclassified | — (experiment queue) |
| `MVK_ONLY_ENUMS_ALLOWED` | unclassified | — (experiment queue) |
| `MVK_ONLY_STRING_ENUM_NUMBER_ALLOWED` | unclassified | — (experiment queue) |
| `MVK_ONLY_STRING_ENUM_NUMBER_CMP_DATE_ALLOWED` | unclassified | — (experiment queue) |
| `MVK_ONLY_STRING_ENUM_NUMBER_CMP_DATE_ALLOWED_NO_FIELD` | unclassified | — (experiment queue) |
| `MVK_ONLY_STRING_ENUM_NUMBER_DATE_ALLOWED` | unclassified | — (experiment queue) |
| `MVK_PARALLEL_ITERATION_FOR_FILTERED_ENTITY_NOT_ALLOWED` | unclassified | — (experiment queue) |
| `MVK_PARALLEL_ITERATION_INVALID` | unclassified | — (experiment queue) |
| `MVK_PARALLEL_ITERATION_NOT_ALLOWED` | unclassified | — (experiment queue) |
| `MVK_PARAMSIZE_INVALID1` | unclassified | — (experiment queue) |
| `MVK_PARAMSIZE_INVALID2` | unclassified | — (experiment queue) |
| `MVK_PARAMSIZE_INVALIDFN` | unclassified | — (experiment queue) |
| `MVK_PARAMSIZE_INVALIDGN` | unclassified | — (experiment queue) |
| `MVK_PARAMSIZE_INVALIDN` | unclassified | — (experiment queue) |
| `MVK_PARAMSIZE_INVALID_2` | unclassified | — (experiment queue) |
| `MVK_PARTIAL_DATE_NOT_ALLOWED` | unclassified | — (experiment queue) |
| `MVK_POINT_IN_A12_MODEL` | unclassified | — (experiment queue) |
| `MVK_REF_ERROR_ENTITY_IF_FILLED_NOT_REFERENCED` | unclassified | — (experiment queue) |
| `MVK_REF_ERROR_ENTITY_IF_NOT_FILLED_NOT_REFERENCED` | unclassified | — (experiment queue) |
| `MVK_REPEATABLE_GROUP_MISSING` | unclassified | — (experiment queue) |
| `MVK_REPEATABLE_LEVEL_REQUIRED` | unclassified | — (experiment queue) |
| `MVK_ROOT_GROUP_IS_EMPTY` | unclassified | — (experiment queue) |
| `MVK_ROOT_GROUP_REFERENCED` | unclassified | — (experiment queue) |
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
| `MVK_VARYING_TYPES_NOT_ALLOWED` | unclassified | — (experiment queue) |
| `MVK_WILDCARDS_REQUIRED` | unclassified | — (experiment queue) |
| `MVK_WILDCARD_AT_LOWEST_LEVEL_REQUIRED` | unclassified | — (experiment queue) |
| `MVK_WILDCARD_ONLY_AT_LOWEST_LEVEL_ALLOWED` | unclassified | — (experiment queue) |
| `MVK_WRONG_DATE_FORMAT_FOR_OP` | unclassified | — (experiment queue) |

## `CalculationErrorType`

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

## Observed at runtime, source not yet located

These codes were reported by `checkConsistency` in experiments but are not declared in either enum above (likely the md-tier rule checks; locate when classifying):

| Code | Bucket | rulekit treatment / lock |
|---|---|---|
| `MVK_ERROR_MESSAGE_MISSING` | pre-checked | builders require ≥1 message |
| `MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING` | pre-checked | builders require a message per declared locale (KERNEL-FINDINGS §13) |

*Classified: 21 of 196; the rest queue up as their operator families land (corpus backlog) or via dedicated experiments.*
