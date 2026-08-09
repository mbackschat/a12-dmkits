package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.model.Kind

/**
 * The canonical **index key** projection, equivalent to the kernel's `IndexFieldCache.normalizeValue`
 * (SPEC-2026-07-23-08). An admitted **Number** index value is keyed numerically, so the legal spellings `5`,
 * `5.0` and `5.00` are one key; every other [Kind] keeps its exact stored text. A non-parsing Number token is
 * returned unchanged by this pure projection, but a stored cell must pass formal admission before a caller may
 * project it. The fallback therefore supports query projection and never turns a malformed stored cell into an
 * identity.
 *
 * Every identity relation that starts from raw text uses this function ([IF272]): semantic-index lookup, the
 * parallel join, the auto `uniqueIndex` check, and compute-side invalid-index detection. The typed
 * [io.github.mbackschat.a12.dm.interpreter.eval.IndexColumn] compares [io.github.mbackschat.a12.dm.interpreter.ast.Val]
 * directly, whose decimal equality already has the same value semantics.
 */
internal fun indexKeyOf(value: String, kind: Kind?): String =
    if (kind == Kind.NUMBER) decOrNull(value)?.display() ?: value else value

/** The index field of [targetPath]'s nearest indexed ancestor group, or null when no indexed group owns it. */
internal fun nearestIndexFieldOf(indexFields: Collection<String>, targetPath: String): String? =
    indexFields.asSequence()
        .filter { indexField ->
            val group = indexField.substringBeforeLast('/')
            targetPath == group || targetPath.startsWith("$group/")
        }
        .maxByOrNull { indexField -> indexField.substringBeforeLast('/').count { it == '/' } }
