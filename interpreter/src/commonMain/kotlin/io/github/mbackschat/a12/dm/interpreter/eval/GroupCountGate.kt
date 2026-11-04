package io.github.mbackschat.a12.dm.interpreter.eval

/**
 * Partial validation's precondition on one `NumberOfFilledGroups` operand: whether the count can be decided at
 * all before its instances are tallied (KERNEL-FINDINGS KF464, IF339).
 *
 * A12 iterates the counted GROUP itself rather than the fields it contains, and it asks three things the
 * instances the document holds cannot answer: that the group's relevant identifiers establish the starred
 * extent, that the FIRST declared instance is fully relevant and free of errors, and that every declared
 * instance the document lacks is fully relevant, since an absent row still takes part in the count. Only then
 * does each held instance contribute its [GroupPresenceState.fillProduct].
 */
interface GroupCountGate {
    /**
     * Whether the group at [groupPath] is countable from the row [rowReps] stands on, [bindDepth] splitting the
     * levels that row binds from the ones the operand spans as on [io.github.mbackschat.a12.dm.interpreter.ast.Expr.Star].
     * [present] holds the coordinates of the instances the document carries in that span.
     */
    fun admits(groupPath: String, bindDepth: Int, rowReps: List<Int>, present: Set<List<Int>>): Boolean

    /**
     * The coordinates of [rowReps] that [admits] decides by: every row sharing them gets the same answer, so a count
     * keyed by them as well as by its scope may be shared across those rows (INTERPRETER-FINDINGS IF411).
     */
    fun decidingPrefix(groupPath: String, bindDepth: Int, rowReps: List<Int>): List<Int>
}
