package io.github.mbackschat.a12.dm.consistency

import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [isA12TimeZone] against what A12 asks, `TimeZone.getTimeZone(id).getID()` other than its `GMT` fallback, on the JDK
 * running this test.
 *
 * The checker carries the pinned JDK's registry because it runs without a JVM, so the registry and the custom-offset
 * grammar are both spelled out, and this is where a JDK whose time-zone data or offset parser differs is caught.
 */
class A12TimeZonesJvmTest {

    private fun jdkAccepts(id: String) = id == "GMT" || TimeZone.getTimeZone(id).id != "GMT"

    @Test
    fun theRegistryIsTheJdksOwn() {
        assertEquals(TimeZone.getAvailableIDs().toSet(), JDK_ZONE_IDS)
    }

    /**
     * Every id of the registry in five near-miss spellings, and every tail of up to six characters over the digits, the
     * colon and both signs, after `GMT` and four other prefixes: about 1.8 million ids, the grid that settled the custom
     * grammar on OpenJDK 21.0.13 (2026-10-30).
     */
    @Test
    fun everyNearMissIsJudgedAsTheJdkJudgesIt() {
        val nearMisses = JDK_ZONE_IDS.flatMap { listOf(it.lowercase(), it.uppercase(), "$it ", " $it", "$it/x", it.replace('/', '-')) }
        var tails = listOf("")
        repeat(6) { tails = tails + tails.filter { it.length == tails.last().length }.flatMap { tail -> "0159:+-x".map { tail + it } } }
        val offsets = listOf("GMT", "gmt", "UTC", "GMT0", "Etc/GMT", "").flatMap { prefix -> tails.map { prefix + it } }
        val disagreeing = (nearMisses + offsets).filter { isA12TimeZone(it) != jdkAccepts(it) }
        assertEquals(emptyList(), disagreeing.take(20), "ids where the checker and the JDK differ")
    }
}
