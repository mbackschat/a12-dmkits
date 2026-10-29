package io.github.mbackschat.a12.dm.consistency.syntax

/*
 * The catalog ids more than one family looks up in [CONDITION_OPERATOR_KEYWORDS], declared once.
 *
 * An id is the catalog's name for an operator, not its spelling in either language: `SemanticIndex` is written
 * `For` and `Fuer`, `Having` is written `Having` and `Mit`. Families read the model's own spelling through the
 * table, which is generated from the catalog; these are its keys and the inverse of the table, and they live here
 * because that file is never hand-edited.
 */

/**
 * Each language's operator spellings back to their [OperatorId]s, built once from [CONDITION_OPERATOR_KEYWORDS].
 *
 * That table runs from id to spelling because only that direction is a function: where two ids share a
 * spelling (`Valid` for `Valid` and `ValidType`), the id listed later wins here.
 */
internal val CONDITION_OPERATOR_IDENTITIES: Map<ConditionLanguage, Map<String, OperatorId>> =
    CONDITION_OPERATOR_KEYWORDS.mapValues { (_, spellings) ->
        spellings.entries.associate { (id, spelling) -> spelling to checkNotNull(OperatorId.of(id)) { "uncataloged id $id" } }
    }

/** A12's tolerance comparisons (`ComparisonOperation` `NE1`…`NE10`). */
internal val TOLERANCE_COMPARISONS = setOf(OperatorId.DIFFERS_WITH_TOLERANCE_RANGE_1,
    OperatorId.DIFFERS_WITH_TOLERANCE_RANGE_2, OperatorId.DIFFERS_WITH_TOLERANCE_RANGE_5, OperatorId.DIFFERS_WITH_TOLERANCE_RANGE_10)

/** A12's pattern comparisons (`NM`, `WM`). */
internal val PATTERN_COMPARISONS = setOf(OperatorId.PATTERN_MATCHED, OperatorId.PATTERN_VIOLATED)

internal const val SEMANTIC_INDEX = "SemanticIndex"

internal const val HAVING = "Having"

internal const val REPETITION_NOT_UNIQUE = "RepetitionNotUnique"

internal const val FROM_SCOPE = "FromScope"

internal const val NUMBER_OF_VALUE_IN_FIELDS = "NumberOfValueInFields"

internal const val CUSTOM_CONDITION = "CustomCondition"

internal const val DATE_CONSTRUCTION = "Date"

internal const val TRUE_CONSTANT = "True"

internal const val FALSE_CONSTANT = "False"

internal const val TODAY_CONSTANT = "Today"

internal const val NOW_CONSTANT = "Now"
