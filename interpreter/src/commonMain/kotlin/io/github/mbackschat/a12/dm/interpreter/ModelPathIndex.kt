package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.model.ModelDef

/**
 * Model-static path structure shared by validation passes that otherwise split and rebuild the same paths per
 * cell. The prepared model owns one instance, shared by every evaluator over it: ancestor propagation and over-repetition checks therefore perform only
 * indexed lookups while a document is validated (IF196).
 */
internal class ModelPathIndex(model: ModelDef) {
    enum class BoundKind {
        REPEATABLE,
        NON_REPEATABLE,
    }

    data class ExceededBound(val level: Int, val kind: BoundKind)

    private data class Bound(val level: Int, val maximum: Int, val kind: BoundKind)

    private data class PathInfo(
        val ancestors: List<String>,
        val through: List<String>,
        val bounds: List<Bound>,
    )

    private val byPath: Map<String, PathInfo> = model.entityPaths.associateWith { path ->
        val parts = pathParts(path)
        val through = parts.indices.map { level -> "/" + parts.take(level + 1).joinToString("/") }
        PathInfo(
            ancestors = through.dropLast(1),
            through = through,
            bounds = through.mapIndexedNotNull { level, prefix ->
                model.effectiveGroupRepetitionMaximums[prefix]?.let { maximum ->
                    val kind = if (prefix in model.repeatable) BoundKind.REPEATABLE else BoundKind.NON_REPEATABLE
                    Bound(level, maximum, kind)
                }
            },
        )
    }

    fun ancestorsOf(path: String): List<String> = info(path).ancestors

    fun groupsThrough(path: String): List<String> = info(path).through

    fun levelOf(path: String): Int = info(path).through.lastIndex

    /** The outermost GROUP level whose instance exceeds its effective maximum. */
    fun exceededBound(path: String, reps: List<Int>): ExceededBound? {
        for (bound in info(path).bounds) {
            if (bound.level < reps.size && reps[bound.level] > bound.maximum) {
                return ExceededBound(bound.level, bound.kind)
            }
        }
        return null
    }

    /** The level of [exceededBound], retained for consumers that need only suppression, not code selection. */
    fun overLimitLevel(path: String, reps: List<Int>): Int? {
        return exceededBound(path, reps)?.level
    }

    private fun info(path: String): PathInfo =
        checkNotNull(byPath[path]) { "document path is absent from the prepared model: $path" }
}
