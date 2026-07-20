package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.ast.Val

/**
 * Closed-interval overlap of two resolved ISO date ranges (lexicographic compare = chronological on ISO).
 *
 * Stored inverted ranges normally fail the earlier formal check and never reach evaluation. This defensive
 * runtime boundary remains independent: either inverted operand returns false even when a caller supplies a
 * resolved range directly. Otherwise overlap is `end1 ≥ start2 ∧ end2 ≥ start1`, so touching endpoints overlap.
 */
internal fun rangesOverlap(x: Val.Range, y: Val.Range): Boolean =
    x.start <= x.end && y.start <= y.end && x.end >= y.start && y.end >= x.start
