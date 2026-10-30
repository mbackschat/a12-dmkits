package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [standsAlone] against Java's `\X`, which A12's charset reader counts grapheme clusters with: any two such characters
 * are two clusters, so an entry of two or three of them is no combined grapheme and A12 refuses it.
 *
 * Every ordered pair is asked rather than each character alone, because a cluster boundary is a property of the pair:
 * CR is a cluster of its own and still joins a following LF.
 */
class SupportedCharacterEntryJvmTest {

    private val cluster = Regex("\\X")

    @Test
    fun everyPairOfStandAloneCharactersIsTwoClusters() {
        val alone = (Char.MIN_VALUE..Char.MAX_VALUE).filter { it.standsAlone() }
        val joined = alone.flatMap { first -> alone.map { "$first$it" } }.filter { cluster.findAll(it).count() != 2 }
        assertEquals(emptyList(), joined.take(20).map { pair -> pair.map { "U+%04X".format(it.code) } })
    }
}
