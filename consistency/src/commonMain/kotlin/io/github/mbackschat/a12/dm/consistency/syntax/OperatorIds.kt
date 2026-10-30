package io.github.mbackschat.a12.dm.consistency.syntax

/*
 * The operator catalog's tables, keyed by [OperatorId] rather than by its string id.
 *
 * An id is the catalog's name for an operator, not its spelling in either language: `SemanticIndex` is written
 * `For` and `Fuer`, `Having` is written `Having` and `Mit`. Families read the model's own spelling through
 * [OPERATOR_SPELLINGS], so a misspelled id is a compile error where a string key would silently find nothing.
 * [CONDITION_OPERATOR_KEYWORDS] stays string-keyed because it is generated and public; these derive from it here
 * because that file is never hand-edited.
 */

/** Each language's spelling of every [OperatorId], built once from [CONDITION_OPERATOR_KEYWORDS]. */
internal val OPERATOR_SPELLINGS: Map<ConditionLanguage, Map<OperatorId, String>> =
    CONDITION_OPERATOR_KEYWORDS.mapValues { (_, spellings) ->
        spellings.entries.associate { (id, spelling) ->
            checkNotNull(OperatorId.of(id)) { "uncataloged id $id" } to spelling
        }
    }

/**
 * Each language's operator spellings back to their [OperatorId]s, the inverse of [OPERATOR_SPELLINGS].
 *
 * That table runs from id to spelling because only that direction is a function: where two ids share a
 * spelling (`Valid` for `Valid` and `ValidType`), the id listed later wins here.
 */
internal val CONDITION_OPERATOR_IDENTITIES: Map<ConditionLanguage, Map<String, OperatorId>> =
    OPERATOR_SPELLINGS.mapValues { (_, spellings) -> spellings.entries.associate { (id, spelling) -> spelling to id } }

/** A12's tolerance comparisons (`ComparisonOperation` `NE1`…`NE10`). */
internal val TOLERANCE_COMPARISONS = setOf(OperatorId.DIFFERS_WITH_TOLERANCE_RANGE_1,
    OperatorId.DIFFERS_WITH_TOLERANCE_RANGE_2, OperatorId.DIFFERS_WITH_TOLERANCE_RANGE_5, OperatorId.DIFFERS_WITH_TOLERANCE_RANGE_10)

/** A12's pattern comparisons (`NM`, `WM`). */
internal val PATTERN_COMPARISONS = setOf(OperatorId.PATTERN_MATCHED, OperatorId.PATTERN_VIOLATED)
