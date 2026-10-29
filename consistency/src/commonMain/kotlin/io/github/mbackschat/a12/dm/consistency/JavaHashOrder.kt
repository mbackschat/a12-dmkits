package io.github.mbackschat.a12.dm.consistency

/**
 * The order in which a `java.util.HashMap<String, …>` iterates [keys] inserted in this order.
 *
 * A12 states some sentences while iterating such a map (`Java8Utils.findDuplicates`, KERNEL-FINDINGS KF647), so
 * this is the order a checker must state them in. The map starts with 16 buckets, doubles once it holds more than
 * three quarters of its capacity, and places a key by `String.hashCode` spread as `h ^ (h >>> 16)`; within a
 * bucket it keeps insertion order, which a resize preserves.
 *
 * Exact while no bucket would hold nine keys: Java resizes a table smaller than 64 at that point, as here, but at
 * 64 buckets or more it turns the bucket into a tree, whose order this does not reproduce. That needs nine keys
 * colliding in their low six hash bits among more than 24, and a key set reaching it is left in insertion order.
 */
internal fun javaHashMapOrder(keys: List<String>): List<String> {
    var capacity = 16
    var buckets = List(capacity) { mutableListOf<String>() }
    fun grow() {
        capacity *= 2
        buckets = List(capacity) { mutableListOf<String>() }.also { grown ->
            buckets.flatten().forEach { grown[indexOf(it, capacity)] += it }
        }
    }
    val distinct = keys.distinct()
    for ((count, key) in distinct.withIndex()) {
        val bucket = buckets[indexOf(key, capacity)]
        bucket += key
        // `putVal` treeifies a bin on appending its ninth node, and `treeifyBin` resizes a table under 64 instead,
        // before the size check below can resize again.
        if (bucket.size > TREEIFY_THRESHOLD) {
            if (capacity >= MIN_TREEIFY_CAPACITY) return distinct
            grow()
        }
        if (count + 1 > capacity * 3 / 4) grow()
    }
    return buckets.flatten()
}

private fun indexOf(key: String, capacity: Int): Int {
    var h = 0
    for (unit in key) h = 31 * h + unit.code
    return (h xor (h ushr 16)) and (capacity - 1)
}

private const val TREEIFY_THRESHOLD = 8

private const val MIN_TREEIFY_CAPACITY = 64
