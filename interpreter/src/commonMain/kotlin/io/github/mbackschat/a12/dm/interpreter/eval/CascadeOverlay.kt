package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.ast.Val

/**
 * What a computation cascade has done to each cell, answered in **one** lookup.
 *
 * This used to be three collections — `overlay: Map<Pair<String, List<Int>>, Val>`, `cleared` and `poisoned` as
 * `Set<Pair<String, List<Int>>>` — and every read of [OverlayContext] consulted all three under the same key. A
 * `Pair<String, List<Int>>` is hashed by walking the repetition list, so one logical read walked it three times,
 * and `starCells` walked it three times **per row**. Measured on the compute-overlay correlation profile,
 * `stateOf` was present in 29.67 % of samples with `ArrayList.hashCodeRange` the third-hottest self frame
 * (INTERPRETER-PERFORMANCE-RESULTS "Checkpoint 2026-08-12 — the list-in-key sweep"; the sweep is [IF285]).
 *
 * **Nested `path → reps → entry`, which is the same fix and the same reason as [Document.Index.valueByKey]'s.**
 * The outer `String` lookup is cheap and its hash is cached by the platform, so a path the cascade never touched
 * — the common case — costs one map probe and never hashes a list at all. It also skips `repsOf(path)`, itself a
 * map lookup, which the flat form had to call before it could even build the key.
 *
 * **Poison is ABSORBING, and that is what preserves the old semantics rather than changing them.** The three-
 * collection form applied precedence at READ time (poisoned beats cleared beats overlaid) and never removed a
 * cell from `overlay` when poisoning it, so a later overlay write could not un-poison a cell — the read simply
 * kept answering POISONED. Collapsing to one entry per cell makes the last write win, so the precedence has to
 * move to the write. Enforcing it here is deliberately stronger than arguing it cannot happen: a poisoned cell is
 * also marked `decided`, which the cascade skips, but that is an invariant in another file and this way the
 * guarantee holds locally. Cause-blindness is unchanged (SPEC-2026-07-26-04): [Entry.Poisoned] carries identity
 * only, never the failed payload, subtype, message, or producer cause.
 *
 * **A value also fills the groups above it, for the rest of the call.** The kernel records every non-empty computed
 * value as filling its ancestor groups and never un-fills one (`CalculationCache.addBerechnetenFeldWert` →
 * `setFieldFilled`), so a later computation's `NoGroupFilled(G)` sees it. [repeatableGroups] names the groups whose
 * presence is their row's existence instead: a computation writes only into rows that exist, so a value adds
 * nothing to theirs.
 */
internal class CascadeOverlay(private val repeatableGroups: Set<String>) {

    /** What the cascade says about one cell. A closed set, so every reader dispatches exhaustively. */
    internal sealed interface Entry {
        /** The cascade computed a value for this cell; dependents read it instead of the stored one. */
        data class Overlaid(val value: Val) : Entry {
            // IF391: the 2026-10-12 compute-overlay allocation profile repeats numeric rendering across formal,
            // value, and scan reads. Entry replacement invalidates this memo; one precision bounds its storage.
            private var cachedNumericDisplay: String? = null
            private var cachedMinFractionalDigits: Int = 0

            fun numberDisplay(minFractionalDigits: Int): String {
                val cached = cachedNumericDisplay
                if (cached != null && cachedMinFractionalDigits == minFractionalDigits) return cached
                return (value as Val.Num).v.display(minFractionalDigits).also {
                    cachedMinFractionalDigits = minFractionalDigits
                    cachedNumericDisplay = it
                }
            }
        }

        /** A prior computation CLEARED this cell — dependents must read EMPTY, never the stale stored seed. */
        data object Cleared : Entry

        /** Invalid for the rest of this cascade; every read poisons. Identity only, by contract. */
        data object Poisoned : Entry
    }

    private val byPath = HashMap<String, HashMap<List<Int>, Entry>>()

    /**
     * Per path, the repetition prefix at every depth of each cell the cascade gave a value, so a group-presence read
     * is one lookup per computed path rather than a scan of its rows. Grow-only, as the kernel's fill propagation is.
     */
    private val valuedPrefixes = HashMap<String, HashMap<Int, HashSet<List<Int>>>>()

    /** No cell has been touched, so a caller can skip wrapping its context at all. */
    val isEmpty: Boolean get() = byPath.isEmpty()

    /**
     * The cascade's entries for one path, or `null` when it has none — the fast exit that keeps a repetition list
     * from being hashed for a column the cascade never wrote. Read-only to callers; the map is live, so a wrapper
     * handed out early in a step still sees the cascade's latest answer.
     */
    fun rowsAt(path: String): Map<List<Int>, Entry>? = byPath[path]

    fun overlay(path: String, reps: List<Int>, value: Val) {
        put(path, reps, Entry.Overlaid(value))
        // Read back rather than assumed: an overlay onto a poisoned cell is restored to the poison, which fills nothing.
        if (value is Val.Unknown || byPath[path]?.get(reps) !is Entry.Overlaid) return
        val byDepth = valuedPrefixes.getOrPut(path) { HashMap() }
        for (depth in 0..reps.size) byDepth.getOrPut(depth) { HashSet() }.add(reps.take(depth))
    }

    /**
     * Whether a value this cascade computed fills the non-repeatable group [groupPath] at the instance a reading
     * context selects. [scope] answers, for a computed path, the repetition prefix that context binds for it.
     */
    fun fillsGroup(groupPath: String, scope: (String) -> List<Int>): Boolean {
        if (valuedPrefixes.isEmpty() || groupPath in repeatableGroups) return false
        val below = "$groupPath/"
        for ((path, byDepth) in valuedPrefixes) {
            if (!path.startsWith(below)) continue
            val bound = scope(path)
            if (byDepth[bound.size]?.contains(bound) == true) return true
        }
        return false
    }

    fun clear(path: String, reps: List<Int>) = put(path, reps, Entry.Cleared)

    fun poison(path: String, reps: List<Int>) = put(path, reps, Entry.Poisoned)

    private fun put(path: String, reps: List<Int>, entry: Entry) {
        val rows = byPath.getOrPut(path) { HashMap() }
        // ONE probe on the path every write takes. Reading before writing would hash the repetition list twice in
        // a class whose whole purpose is not doing that; `put` returns the displaced entry, so the absorbing rule
        // becomes a restore that only fires on a write after a poison.
        val displaced = rows.put(reps, entry)
        if (displaced == Entry.Poisoned && entry != Entry.Poisoned) rows[reps] = displaced
    }
}
