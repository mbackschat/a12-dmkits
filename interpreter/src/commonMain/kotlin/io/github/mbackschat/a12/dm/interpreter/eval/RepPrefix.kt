package io.github.mbackschat.a12.dm.interpreter.eval

/**
 * The first [length] indices of [reps] as a map key, hashed once and compared index by index.
 *
 * A `List<Int>` — a `subList` view or a `take` copy — keys the same maps correctly, but on Kotlin/JS its `hashCode`
 * and `equals` walk an iterator, and a view re-checks its range on every step. The per-document indexes that key by
 * a repetition prefix pay that on every insert and probe. Keying the content trie by this type alone made the
 * 200-row browser first answer 4–5 % faster (`b1` model on Node, interleaved A/B against the list-keyed build,
 * 2026-11-06; INTERPRETER-PERFORMANCE-RESULTS).
 *
 * Holds [reps] by reference: every caller keys by a placement's or a stored row's indices, which live as long as
 * the document does.
 */
internal class RepPrefix(private val reps: List<Int>, val length: Int) {
    private val hash: Int = run {
        var h = 1
        for (i in 0 until length) h = 31 * h + reps[i]
        h
    }

    /** Whether this prefix begins with every index of [prefix]. */
    fun startsWith(prefix: List<Int>): Boolean {
        if (prefix.size > length) return false
        for (i in prefix.indices) if (reps[i] != prefix[i]) return false
        return true
    }

    override fun hashCode(): Int = hash

    override fun equals(other: Any?): Boolean {
        if (other !is RepPrefix || other.hash != hash || other.length != length) return false
        for (i in 0 until length) if (reps[i] != other.reps[i]) return false
        return true
    }
}
