package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.EvalContext
import io.github.mbackschat.a12.dm.interpreter.eval.GroupRelevance
import io.github.mbackschat.a12.dm.interpreter.eval.GroupPresenceState
import io.github.mbackschat.a12.dm.interpreter.eval.UNIQUE_INDEX
import io.github.mbackschat.a12.dm.interpreter.model.CustomConditionFieldPointer
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef

/**
 * The validation-stage equivalent of the kernel's group-fill cache. It contains only data admitted by the
 * formal conversion stage: valid valued fields, duplicate-index values (admitted before uniqueness is checked),
 * and instantiated repeatable rows. A malformed/custom-invalid/over-limit VALUE is not content, while creating
 * its repeatable row remains structural content independently (live-kernel discriminator, IF193).
 *
 * Construction is call-local and linear in placements × path depth. Every rule-row lookup is O(1).
 */
internal class AdmittedGroupContentIndex private constructor(
    private val filledByPath: Map<String, Set<List<Int>>>,
) {
    fun isFilled(ctx: EvalContext, groupPath: String): Boolean =
        isFilled(groupPath, ctx.repsOf(groupPath))

    fun isFilled(groupPath: String, reps: List<Int>): Boolean = reps in (filledByPath[groupPath] ?: emptySet())

    companion object {
        fun build(
            model: ModelDef,
            doc: Document,
            isRelevant: Relevance,
            groupRelevance: (String, List<Int>) -> GroupRelevance,
            formalResults: FormalResultCache,
            paths: ModelPathIndex,
        ): AdmittedGroupContentIndex {
            val filled = HashMap<String, HashSet<List<Int>>>()

            fun addAncestors(path: String, reps: List<Int>, includeSelf: Boolean) {
                val groups = if (includeSelf) paths.groupsThrough(path) else paths.ancestorsOf(path)
                for ((level, groupPath) in groups.withIndex()) {
                    val depth = level + 1
                    val prefix = if (depth == reps.size) reps else reps.subList(0, depth)
                    filled.getOrPut(groupPath) { HashSet() } += prefix
                }
            }

            for (field in doc.valuedFields()) {
                if (!isRelevant(field.path, field.reps)) continue
                val admitted = when (val result = formalResults.resultAt(field.path, field.reps, field.raw)) {
                    FormalResult.Valid, is FormalResult.Unsupported -> true
                    is FormalResult.Invalid ->
                        result.failure is FormalFailure.Generated && result.failure.code == UNIQUE_INDEX
                }
                if (admitted) addAncestors(field.path, field.reps, includeSelf = false)
            }

            // Creating a repeatable row is content in itself, even when every field in it is empty.
            for (groupPath in model.repeatable) {
                for (reps in doc.instantiatedRows(groupPath)) {
                    if (groupRelevance(groupPath, reps) == GroupRelevance.NONE) continue
                    addAncestors(groupPath, reps, includeSelf = true)
                }
            }
            return AdmittedGroupContentIndex(filled)
        }
    }
}

/**
 * Adds the validation cache's independent erroneous-group dimension to [AdmittedGroupContentIndex]. This is
 * deliberately not a Boolean: a group can contain admitted data and still be erroneous, or have only rejected
 * malformed data. Positive presence reads the content bit once any descendant is relevant; negative presence is
 * known only when the error bit is clear and the group is fully relevant. That preserves the kernel's asymmetric
 * `GroupFilled` / `GroupNotFilled` three-valued semantics in full and partial validation.
 */
internal class GroupPresenceIndex(
    private val content: AdmittedGroupContentIndex,
    formallyIncorrect: Set<CustomConditionFieldPointer>,
    private val groupRelevance: (String, List<Int>) -> GroupRelevance,
) {
    private val erroneousByPath = HashMap<String, HashSet<List<Int>>>()

    init {
        for (field in formallyIncorrect) {
            val parts = pathParts(field.path)
            for (depth in 1 until parts.size) {
                val groupPath = "/" + parts.take(depth).joinToString("/")
                erroneousByPath.getOrPut(groupPath) { HashSet() } += field.indices.take(depth)
            }
        }
    }

    fun state(groupPath: String, reps: List<Int>): GroupPresenceState =
        GroupPresenceState(
            filled = content.isFilled(groupPath, reps),
            erroneous = reps in (erroneousByPath[groupPath] ?: emptySet()),
            relevance = groupRelevance(groupPath, reps),
        )
}
