package io.github.mbackschat.a12.dm.adapter.laws

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BoundedLruCacheTest {

    @Test
    fun accessOrderEvictsTheLeastRecentlyUsedEntryAtTheHardBound() {
        val cache = BoundedLruCache<Int, String>(2)
        cache.getOrCompute(1) { "one" }
        cache.getOrCompute(2) { "two" }
        cache.getOrCompute(1) { error("entry 1 must be retained") }
        cache.getOrCompute(3) { "three" }

        var rebuilt = false
        assertEquals("two-again", cache.getOrCompute(2) { rebuilt = true; "two-again" })
        assertEquals(true, rebuilt)
        assertEquals(2, cache.size())
    }
}
