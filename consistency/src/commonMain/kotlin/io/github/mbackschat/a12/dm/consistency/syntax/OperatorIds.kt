package io.github.mbackschat.a12.dm.consistency.syntax

/*
 * The catalog ids more than one family looks up in [CONDITION_OPERATOR_KEYWORDS], declared once.
 *
 * An id is the catalog's name for an operator, not its spelling in either language: `SemanticIndex` is written
 * `For` and `Fuer`, `Having` is written `Having` and `Mit`. Families read the model's own spelling through the
 * table, which is generated from the catalog; these are only its keys, and they live here because that file is
 * never hand-edited.
 */

internal const val SEMANTIC_INDEX = "SemanticIndex"

internal const val HAVING = "Having"

internal const val REPETITION_NOT_UNIQUE = "RepetitionNotUnique"

internal const val FROM_SCOPE = "FromScope"

internal const val NUMBER_OF_VALUE_IN_FIELDS = "NumberOfValueInFields"

internal const val CUSTOM_CONDITION = "CustomCondition"

internal const val DATE_CONSTRUCTION = "Date"

internal const val TRUE_CONSTANT = "True"

internal const val FALSE_CONSTANT = "False"
