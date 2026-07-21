package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.model.ModelDef

/**
 * Model-static path structure shared by validation passes that otherwise split and rebuild the same paths per
 * cell. The engine owns one instance: ancestor propagation and over-repetition checks therefore perform only
 * indexed lookups while a document is validated (IF196).
 */
internal class ModelPathIndex(model: ModelDef) {
    private data class Bound(val level: Int, val maximum: Int)

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
                model.declaredReps[prefix]?.let { Bound(level, it) }
            },
        )
    }

    fun ancestorsOf(path: String): List<String> = info(path).ancestors

    fun groupsThrough(path: String): List<String> = info(path).through

    fun levelOf(path: String): Int = info(path).through.lastIndex

    /** The outermost repeatable level whose instance exceeds its model-declared maximum. */
    fun overLimitLevel(path: String, reps: List<Int>): Int? {
        for (bound in info(path).bounds) {
            if (bound.level < reps.size && reps[bound.level] > bound.maximum) return bound.level
        }
        return null
    }

    private fun info(path: String): PathInfo =
        checkNotNull(byPath[path]) { "document path is absent from the prepared model: $path" }
}
