package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.callsIn
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * That `RepetitionNotUnique` does not sit in a condition that PARALLEL-iterates.
 *
 * Two index-keyed repeatable groups iterate jointly with no construct to say so, and A12 refuses a
 * uniqueness check inside such a condition with `MVK_PARALLEL_ITERATION_NOT_ALLOWED`
 * ([KERNEL-FINDINGS KF353], `rulekit.validate.laws.ParallelIterationGateLawsTest`). It is the first of the
 * four whole-RULE gates A12 runs over a parallel iteration, so it also pre-empts the negative-condition
 * class — measured, on a condition violating both.
 *
 * **Two conditions bound what this family may call a parallel iteration, and each was measured because
 * getting it wrong is an over-rejection.** A STARRED read of the second group is NOT one: the star puts a
 * wildcard at that level rather than the joint-iteration marker, and the condition is ADMITTED. And index
 * fields of DIFFERENT declared types draw `MVK_ERROR_FIELD_INVALID_INDEX_TYPE` instead, ahead of this gate —
 * so a family keying on the index field's NAME alone would name this class where A12 names that one.
 *
 * **The star has to be tested at the JOINT level, not anywhere in the reference.** A star marks the level it
 * sits on and nothing above it, so an aggregate over `Picks` then a starred `Checks` beneath it still
 * iterates `Picks` jointly and A12 refuses the rule — measured 2026-09-14
 * (`rulekit.validate.laws.FilteredEntityParallelIterationLawsTest`),
 * after this family had read "carries a star" as "does not iterate" and stayed silent on it.
 *
 * **It asks for the same NAME and the same declared TYPE, which is the intersection rather than the law.**
 * A12's own test is `matchBaseType` over the two index fields; whether a differing NAME alone stops the
 * joint iteration is unmeasured, and two different declared types can in principle share a base type. Both
 * unknowns are resolved towards silence, so this under-reports rather than inventing a rejection.
 *
 * **Rules only**, like every family here that reads `RepetitionNotUnique`: A12's calculation module refuses
 * the construct in a computation outright ([KERNEL-FINDINGS KF333]).
 */
fun PreparedModel.parallelIterationUniquenessFindings(): List<Finding> {
    val keywords = CONDITION_OPERATOR_KEYWORDS[conditionLanguage] ?: return emptyList()
    val spelling = keywords[REPETITION_NOT_UNIQUE_CALL] ?: return emptyList()
    val referencesByElement = conditionReferences().groupBy { it.declaredAt }
    return elements.filter { it.kind == "Rule" }.mapNotNull { element ->
        val body = element.body ?: return@mapNotNull null
        val callsUniqueness = conditionTexts(element.kind, body).any { (member, text) ->
            callsIn(text, conditionLanguage, syntaxEntryOf(member)).any { it.operator == spelling }
        }
        if (!callsUniqueness) {
            return@mapNotNull null
        }
        val declaringGroup = element.path.substringBeforeLast('/', missingDelimiterValue = "")
        val keyed = referencesByElement[element.path].orEmpty()
            .mapNotNull { reference ->
                val resolved = (reference.target as? Reference.Resolved)?.path ?: return@mapNotNull null
                val starred = starredLevelsOf(reference.written, declaringGroup, element)
                    ?: return@mapNotNull null
                keyedGroupOver(resolved)?.takeIf { it.path !in starred }
            }
            .distinct()
        val pair = jointlyIteratingPair(keyed) ?: return@mapNotNull null
        Finding(
            FindingCategory.A12_CONSTRAINT,
            FindingSeverity.ERROR,
            "MVK_PARALLEL_ITERATION_NOT_ALLOWED",
            "this rule reads `${pair.first.path}` and `${pair.second.path}`, which share the index field " +
                "`${pair.first.indexField}` and therefore iterate JOINTLY — `$spelling` may not sit in a " +
                "condition that parallel-iterates; star one of the reads, or split the rule in two",
            element.path,
        )
    }
}

private const val REPETITION_NOT_UNIQUE_CALL = "RepetitionNotUnique"

/** A repeatable group that declares an index field, with the field's short name and declared type. */
private data class KeyedGroup(val path: String, val indexField: String, val indexType: String)

/** The nearest index-keyed repeatable group at or above [path], or null when nothing above it is keyed. */
private fun PreparedModel.keyedGroupOver(path: String): KeyedGroup? = elements
    .filter {
        it.declaredKind == DeclaredKind.GROUP && it.isRepeatable &&
            (it.path == path || path.startsWith("${it.path}/"))
    }
    .sortedByDescending { it.path.length }
    .firstNotNullOfOrNull { group ->
        val name = (group.body?.get("indexFieldName") as? JsonPrimitive)?.contentOrNull
            ?: return@firstNotNullOfOrNull null
        val type = declaredTypeOf("${group.path}/$name") ?: return@firstNotNullOfOrNull null
        KeyedGroup(group.path, name, type)
    }

/** The `fieldType.type` a field declares, or null when it declares none this reader can see. */
private fun PreparedModel.declaredTypeOf(path: String): String? = elements
    .firstOrNull { it.path == path && it.declaredKind == DeclaredKind.FIELD }
    ?.body
    ?.let { (it["fieldType"] as? JsonObject)?.get("type") as? JsonPrimitive }
    ?.contentOrNull

/**
 * The first two groups that A12 would iterate jointly, or null.
 *
 * Neither may contain the other: nesting is ordinary iteration, where the inner group's rows belong to an
 * outer row rather than being matched against them by key.
 */
private fun jointlyIteratingPair(keyed: List<KeyedGroup>): Pair<KeyedGroup, KeyedGroup>? {
    keyed.indices.forEach { left ->
        (left + 1 until keyed.size).forEach { right ->
            val one = keyed[left]
            val other = keyed[right]
            val nested = one.path.startsWith("${other.path}/") || other.path.startsWith("${one.path}/")
            if (!nested && one.indexField == other.indexField && one.indexType == other.indexType) {
                return one to other
            }
        }
    }
    return null
}
