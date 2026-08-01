package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.workspace.immutableListSnapshot

/**
 * One parsed **exact** A12 `DocumentPointer`: the path part names and their repetitions, positionally paired.
 * Repetition `0` is legal only on the last part, where it addresses A12's `RepetitionsV2` slot.
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
 * `[0]` on the LAST part addresses `RepetitionsV2`; on an earlier part it is rejected, exactly as
 * `DocumentPointerImpl`'s constructor rejects it ("All repetition indexes must be >= 1, the last repetition can
 * also be 0").
 *
 * <b>This type models the EXACT pointer only, and the distinction is load-bearing.</b> A12 has a second
 * addressing type, `DocumentMultiPointer`, where `0` genuinely IS a wildcard meaning "all repetitions" — and there
 * it is legal at **every** part (the kernel's own example is `a[0]/b[2]`, wildcard on the first). This file used to
 * call `0` "the wildcard" while enforcing the exact pointer's position rule, i.e. borrow one type's vocabulary for
 * the other type's constraint. That cost real time: a canonical findings entry read the mismatch as an
 * over-rejection and declared a correct, test-locked refusal a defect. The refusal is right; the NAME was wrong.
 * A multipointer parser, should one ever be needed, is a separate type with the wildcard-at-every-part rule — not
 * a relaxation of this one. Note that on BOTH types an omitted bracket means repetition **1**, never "all".
 */
object A12PointerFormat {

    /**
     * The repetition value A12 permits only on an exact pointer's LAST part, where it addresses `RepetitionsV2`.
     *
     * Deliberately NOT named `WILDCARD`: that is `DocumentMultiPointer`'s meaning for the same digit, and conflating
     * the two is what made this type's constraint look like a bug.
     */
    const val LAST_PART_ZERO: Int = 0

    /**
     * A12's **unknown** repetition — `IIdentifier.UNKNOWN`, source-verified as `-5` in kernel 30.8.1
     * (`kernel-core-runtime-api/.../IIdentifier.java:118`). It is not an index and not a wildcard: it marks a level
     * the engine could not resolve to a row, which is what a parallel iteration produces when one joined group has
     * no row carrying the current index value. The kernel renders such a pointer as `…/Demand[-5]/Headcount`, so a
     * consumer meets it in real output and a renderer that cannot express it would have to invent something.
     *
     * Accepted at **any** part, unlike [LAST_PART_ZERO]: "no row here" can occur at any joined level, whereas the
     * last-part `0` addresses a specific slot. The two are different concepts that happen to share a bracket.
     */
    const val UNKNOWN_REPETITION: Int = -5

    // The closing bracket MUST stay escaped: Kotlin/JS compiles a Regex in unicode (`u`) mode, where a lone
    // `]` is a SyntaxError — the pattern would fail to construct and every use would see an uninitialized
    // object. Harmless on the JVM, required on JS (the multiplatform `:interpreter:check` is what catches it).
    // The optional `-` admits UNKNOWN_REPETITION only; every other negative is rejected below.
    private val PART = Regex("([\\w_-]+)(\\[(-?\\d+)?\\])?")

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
        for (i in repetitions.indices) {
            val rep = repetitions[i]
            val lastPartZero = rep == LAST_PART_ZERO && i == repetitions.size - 1
            require(rep >= 1 || rep == UNKNOWN_REPETITION || lastPartZero) {
                "all repetition indexes must be >= 1; only the last may be $LAST_PART_ZERO, and " +
                    "$UNKNOWN_REPETITION (A12's unknown repetition) may appear at any part, in pointer " +
                    "'$pointer' (an exact DocumentPointer — a multipointer wildcard is a different type)"
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
