package io.github.mbackschat.a12.dm.interpreter.property

import io.github.mbackschat.a12.dm.interpreter.IndexIdentityCandidate
import io.github.mbackschat.a12.dm.interpreter.resolveIndexIdentity
import kotlin.test.Test
import kotlin.test.assertEquals

/** Exhaustive kernel-free check of the identity stage after owner semantics are anchored by adapter laws. */
class IndexIdentityStagePropertyTest {

    @Test
    fun everySequenceOfUpToThreeCandidatesMatchesTheFiniteReference() {
        val atoms = listOf(
            Atom("excluded", key = null, compromises = false),
            Atom("empty", key = null, compromises = true),
            Atom("inadmissible", key = null, compromises = true),
            Atom("non-relevant", key = null, compromises = true),
            Atom("A", key = "A", compromises = false),
            Atom("B", key = "B", compromises = false),
            Atom("compromised-key", key = "A", compromises = true),
        )

        for (size in 0..3) {
            for (sequence in sequences(atoms, size)) {
                val candidates = sequence.mapIndexed { index, atom ->
                    IndexIdentityCandidate(
                        repetitions = listOf(index + 1),
                        key = atom.key,
                        compromises = atom.compromises,
                    )
                }
                val actual = resolveIndexIdentity(candidates)
                val participants = candidates.filter { it.key != null }.groupBy { it.key!! }
                val duplicates = participants.filterValues { it.size > 1 }
                val expectedUnique = participants.filterValues { it.size == 1 }
                    .mapValues { (_, cells) -> cells.single().repetitions }
                val expectedDuplicateRows = duplicates.values.flatten().mapTo(linkedSetOf()) { it.repetitions }

                assertEquals(expectedUnique, actual.uniqueRowsByKey, sequence.label("unique rows"))
                assertEquals(duplicates.keys, actual.duplicatedKeys, sequence.label("duplicated keys"))
                assertEquals(expectedDuplicateRows, actual.duplicateRows, sequence.label("duplicate rows"))
                assertEquals(
                    candidates.any { it.compromises } || duplicates.isNotEmpty(),
                    actual.compromised,
                    sequence.label("column state"),
                )
            }
        }
    }

    @Test
    fun inadmissibleRowsNeverMaskTheCollisionPolicyOfAdmittedPeers() {
        val invalid = candidate(1, key = null, compromises = true)
        val first = candidate(2, key = "5", compromises = false)
        val second = candidate(3, key = "5", compromises = false)

        assertEquals(mapOf("5" to listOf(2)), resolveIndexIdentity(listOf(invalid, first)).uniqueRowsByKey)
        assertEquals(
            emptyMap(),
            resolveIndexIdentity(listOf(invalid, first, second)).uniqueRowsByKey,
            "the inadmissible row stays outside identity, while the two admitted peers still collide",
        )
    }

    private fun candidate(repetition: Int, key: String?, compromises: Boolean) =
        IndexIdentityCandidate(listOf(repetition), key, compromises)

    private fun <T> sequences(atoms: List<T>, size: Int): List<List<T>> = when (size) {
        0 -> listOf(emptyList())
        else -> sequences(atoms, size - 1).flatMap { prefix -> atoms.map { prefix + it } }
    }

    private fun List<Atom>.label(observable: String): String =
        "$observable for [${joinToString { it.name }}]"

    private data class Atom(
        val name: String,
        val key: String?,
        val compromises: Boolean,
    )
}
