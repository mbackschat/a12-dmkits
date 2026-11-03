package io.github.mbackschat.a12.dm.consistency

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The portable `computeIfAbsent` order against `java.util.HashMap` itself, filled as A12's `findDuplicates` fills it:
 * one call at every occurrence of a value after its first.
 */
class JavaHashOrderJvmTest {

    @Test
    fun theOrderIsJavaHashMapsOwnForEverySizeUpToBeyondTwoResizes() {
        val random = Random(20261029)
        for (size in 0..240) {
            repeat(20) {
                // A pool smaller than the list repeats values, some three times or more.
                val pool = List(random.nextInt(1, size / 2 + 2)) { randomKey(random) }
                val values = List(size) { pool[random.nextInt(pool.size)] }
                assertEquals(javaOrder(values), computeIfAbsentOrder(repeatOccurrences(values)), "values $values")
            }
        }
    }

    /** A token-less value entry maps to a null key, which Java hashes to 0 (KERNEL-FINDINGS KF655). */
    @Test
    fun aNullKeyTakesTheBucketJavaGivesIt() {
        val random = Random(20261102)
        for (size in 0..120) {
            repeat(20) {
                val pool = List(random.nextInt(1, size / 2 + 2)) { if (it == 0) null else randomKey(random) }
                val values = List(size) { pool[random.nextInt(pool.size)] }
                assertEquals(javaOrder(values), computeIfAbsentOrder(repeatOccurrences(values)), "values $values")
            }
        }
    }

    @Test
    fun aBucketReachingEightKeysUnderSixtyFourBucketsResizesAsJavaDoes() {
        // Keys sharing their low four spread hash bits: eight of them in one of 16 buckets force `treeifyBin`'s resize.
        val colliding = generateSequence(0) { it + 1 }.map { "k$it" }
            .filter { key -> spread(key.hashCode()) and 15 == 3 }.take(12).toList()
        for (size in 1..colliding.size) {
            val values = colliding.take(size).let { it + it }
            assertEquals(javaOrder(values), computeIfAbsentOrder(repeatOccurrences(values)), "values $values")
        }
    }

    /**
     * The real map, filled by `computeIfAbsent` exactly where `Java8Utils.findDuplicates` calls it. `present` maps the
     * key to the ENTRY, which is never null, so a null key repeats as any other does.
     */
    private fun javaOrder(values: List<String?>): List<String?> {
        val present = java.util.HashMap<String?, Boolean>()
        val duplicates = java.util.HashMap<String?, Int>()
        for (value in values) if (present.put(value, true) != null) duplicates.computeIfAbsent(value) { 0 }
        return duplicates.keys.toList()
    }

    private fun repeatOccurrences(values: List<String?>): List<String?> {
        val seen = mutableSetOf<String?>()
        return values.filterNot { seen.add(it) }
    }

    private fun spread(h: Int) = h xor (h ushr 16)

    private fun randomKey(random: Random): String =
        String(CharArray(random.nextInt(1, 7)) { "ABCDEFGHIJKLMNOPQRSTUVWXYZ_0123456789äßé"[random.nextInt(40)] })
}
