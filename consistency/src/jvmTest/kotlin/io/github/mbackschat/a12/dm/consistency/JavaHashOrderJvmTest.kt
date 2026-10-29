package io.github.mbackschat.a12.dm.consistency

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** The portable `HashMap` order against `java.util.HashMap` itself, over every resize and the pre-tree resize. */
class JavaHashOrderJvmTest {

    @Test
    fun theOrderIsJavaHashMapsOwnForEverySizeUpToBeyondTwoResizes() {
        val random = Random(20261029)
        for (size in 0..120) {
            repeat(20) {
                val keys = List(size) { randomKey(random) }
                assertEquals(javaOrder(keys), javaHashMapOrder(keys), "keys $keys")
            }
        }
    }

    @Test
    fun aBucketReachingNineKeysUnderSixtyFourBucketsResizesAsJavaDoes() {
        // Keys sharing their low four spread hash bits: nine of them in one of 16 buckets force `treeifyBin`'s resize.
        val colliding = generateSequence(0) { it + 1 }.map { "k$it" }
            .filter { key -> spread(key.hashCode()) and 15 == 3 }.take(12).toList()
        for (size in 1..colliding.size) {
            val keys = colliding.take(size)
            assertEquals(javaOrder(keys), javaHashMapOrder(keys), "keys $keys")
        }
    }

    private fun javaOrder(keys: List<String>): List<String> {
        val map = java.util.HashMap<String, Int>()
        keys.forEach { map.putIfAbsent(it, 0) }
        return map.keys.toList()
    }

    private fun spread(h: Int) = h xor (h ushr 16)

    private fun randomKey(random: Random): String =
        String(CharArray(random.nextInt(1, 7)) { "ABCDEFGHIJKLMNOPQRSTUVWXYZ_0123456789äßé"[random.nextInt(40)] })
}
