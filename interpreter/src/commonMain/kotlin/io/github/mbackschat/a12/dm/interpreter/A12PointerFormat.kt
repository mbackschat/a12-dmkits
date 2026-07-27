package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.workspace.immutableListSnapshot

/**
 * One parsed A12 pointer: the path part names and their repetitions, positionally paired. Repetition `0` is
 * A12's wildcard ("all repetitions") and is legal only on the last part.
 */
data class A12Pointer(val names: List<String>, val repetitions: List<Int>) {
    init {
        require(names.size == repetitions.size) {
            "names and repetitions have different length: ${names.size} vs ${repetitions.size}"
        }
    }
}

/**
 * A12's canonical `DocumentPointer` string — the project's single addressing form (CLAUDE.md "NEVER invent a
 * shape A12 already defines"). Grammar, per kernel 30.8.1 `DocumentPointerImpl`/`PathPartImpl` and recorded in
 * KERNEL-FINDINGS: parts joined by `/`, each part `name` optionally followed by `[n]`. An absent bracket means
 * repetition 1 and the canonical form *omits* `[1]`, so `Recipe/Ingredients[3]/Name` is canonical and a field
 * part carries no bracket. A leading `/` is accepted and stripped as redundant; a trailing `/` is rejected.
 * `[0]` is the wildcard and is legal only on the last part.
 */
object A12PointerFormat {

    /** A12's wildcard repetition — "all repetitions", legal only on the last path part. */
    const val WILDCARD: Int = 0

    private val PART = Regex("([\\w_-]+)(\\[(\\d+)?])?")

    /**
     * Render [path] (with or without a leading `/`) and its positional [repetitions] as the canonical string.
     */
    fun format(path: String, repetitions: List<Int>): String {
        val names = splitNames(path)
        require(names.size == repetitions.size) {
            "path '$path' has ${names.size} parts but ${repetitions.size} repetitions"
        }
        return names.asSequence()
            .mapIndexed { i, name -> if (repetitions[i] == 1) name else "$name[${repetitions[i]}]" }
            .joinToString("/")
    }

    /** Parse a canonical (or leading-slash) pointer string into its names and repetitions. */
    fun parse(pointer: String): A12Pointer {
        val names = ArrayList<String>()
        val repetitions = ArrayList<Int>()
        for (part in splitNames(pointer)) {
            val match = PART.matchEntire(part)
                ?: throw IllegalArgumentException("not a valid path part: '$part' in pointer '$pointer'")
            names.add(match.groupValues[1])
            repetitions.add(match.groupValues[3].takeIf { it.isNotEmpty() }?.toInt() ?: 1)
        }
        for (i in 0 until repetitions.size - 1) {
            require(repetitions[i] >= 1) {
                "all repetitions must be >= 1, only the last may be the ${WILDCARD} wildcard, in pointer '$pointer'"
            }
        }
        return A12Pointer(immutableListSnapshot(names), immutableListSnapshot(repetitions))
    }

    /** Split a path on `/`, stripping the redundant leading slash and rejecting a trailing one. */
    private fun splitNames(path: String): List<String> {
        require(path != "/" && !path.endsWith("/")) { "path must not end with a slash but was '$path'" }
        val body = path.removePrefix("/")
        return if (body.isEmpty()) emptyList() else body.split("/")
    }
}
