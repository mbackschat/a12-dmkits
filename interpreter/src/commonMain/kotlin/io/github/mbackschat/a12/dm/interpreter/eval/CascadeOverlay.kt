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
 * (INTERPRETER-PERFORMANCE "Checkpoint 2026-08-12 — the list-in-key sweep"; the sweep is [IF285]).
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
 */
internal class CascadeOverlay {

    /** What the cascade says about one cell. A closed set, so every reader dispatches exhaustively. */
    internal sealed interface Entry {
        /** The cascade computed a value for this cell; dependents read it instead of the stored one. */
        data class Overlaid(val value: Val) : Entry

        /** A prior computation CLEARED this cell — dependents must read EMPTY, never the stale stored seed. */
        data object Cleared : Entry

        /** Invalid for the rest of this cascade; every read poisons. Identity only, by contract. */
        data object Poisoned : Entry
    }

    private val byPath = HashMap<String, HashMap<List<Int>, Entry>>()

    /** No cell has been touched, so a caller can skip wrapping its context at all. */
    val isEmpty: Boolean get() = byPath.isEmpty()

    /**
     * The cascade's entries for one path, or `null` when it has none — the fast exit that keeps a repetition list
     * from being hashed for a column the cascade never wrote. Read-only to callers; the map is live, so a wrapper
     * handed out early in a step still sees the cascade's latest answer.
     */
    fun rowsAt(path: String): Map<List<Int>, Entry>? = byPath[path]

    fun overlay(path: String, reps: List<Int>, value: Val) = put(path, reps, Entry.Overlaid(value))

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
