package io.github.mbackschat.a12.dm.consistency

/**
 * The order in which a `java.util.HashMap<String, …>` iterates its keys after `computeIfAbsent` was called with
 * each of [calls] in turn, a key already present included.
 *
 * A12 states an Enumeration's repeated values while iterating such a map, which `Java8Utils.findDuplicates` fills
 * by calling `computeIfAbsent` at every repeat occurrence (KERNEL-FINDINGS KF647), so this is the order a checker
 * must state them in. `computeIfAbsent` differs from `put` twice, and both differences are visible in that order:
 * it places a new key at the HEAD of its bucket, and it resizes BEFORE a call once the map already holds more than
 * three quarters of its capacity, a call finding its key included, rather than right after the insertion that
 * crossed it. The map starts with 16 buckets, doubles on each resize keeping each bucket's order, and places a key
 * by `String.hashCode` spread as `h ^ (h >>> 16)`.
 *
 * Exact while no bucket would hold eight keys at 64 buckets or more: Java resizes a smaller table at that point,
 * as here, but at 64 buckets it turns the bucket into a tree, whose order this does not reproduce. That needs
 * eight keys colliding in their low six hash bits among more than 24, and a key set reaching it is left in
 * first-call order.
 */
internal fun computeIfAbsentOrder(calls: List<String>): List<String> {
    var capacity = 0
    var size = 0
    var buckets = emptyList<MutableList<String>>()
    fun grow() {
        capacity = if (capacity == 0) INITIAL_CAPACITY else capacity * 2
        buckets = List(capacity) { mutableListOf<String>() }.also { grown ->
            buckets.flatten().forEach { grown[indexOf(it, capacity)] += it }
        }
    }
    for (key in calls) {
        if (capacity == 0 || size > capacity * 3 / 4) grow()
        val bucket = buckets[indexOf(key, capacity)]
        if (key in bucket) continue
        bucket.add(0, key)
        size++
        // `treeifyBin` runs once the bucket held seven keys before this one, and resizes a table under 64 instead.
        if (bucket.size >= TREEIFY_THRESHOLD) {
            if (capacity >= MIN_TREEIFY_CAPACITY) return calls.distinct()
            grow()
        }
    }
    return buckets.flatten()
}

private fun indexOf(key: String, capacity: Int): Int {
    var h = 0
    for (unit in key) h = 31 * h + unit.code
    return (h xor (h ushr 16)) and (capacity - 1)
}

private const val INITIAL_CAPACITY = 16

private const val TREEIFY_THRESHOLD = 8

private const val MIN_TREEIFY_CAPACITY = 64
