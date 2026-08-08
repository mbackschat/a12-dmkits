package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.model.Kind

/**
 * The canonical **index key** of a stored index-field [value] — the kernel's `IndexFieldCache.normalizeValue`
 * (SPEC-2026-07-23-08). A **Number** index field is keyed by NUMERIC value, so the legal spellings `5`, `5.0`
 * and `5.00` are ONE key; a value that does not parse as a number falls back to its raw text (the kernel's own
 * raw fallback), and every other [Kind] keeps its exact stored text.
 *
 * **Every index-keyed lookup and every index-uniqueness test goes through this ONE function** ([IF272]): the
 * semantic index (`[F For "v"]`), the parallel join's value→row map, the auto `uniqueIndex` check, and the
 * computation engine's invalid-index detection. Keying any of them by raw text splits one kernel key into two
 * — a join that must match does not, and a collision that must invalidate the column is not seen.
 */
internal fun indexKeyOf(value: String, kind: Kind?): String =
    if (kind == Kind.NUMBER) decOrNull(value)?.display() ?: value else value
