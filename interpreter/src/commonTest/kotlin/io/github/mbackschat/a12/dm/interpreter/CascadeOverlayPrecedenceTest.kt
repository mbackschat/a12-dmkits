package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.ast.Val
import io.github.mbackschat.a12.dm.interpreter.eval.CascadeOverlay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The precedence [CascadeOverlay] enforces on WRITE, which the three-collection form applied on every READ.
 *
 * This is the invariant that moved, so it is the one that needs its own lock: collapsing `overlay`/`cleared`/
 * `poisoned` into one entry per cell makes the last write win, and read-time precedence (poisoned beats cleared
 * beats overlaid) only survives because poison is absorbing. Nothing in the cascade's own ordering is relied on
 * here — the point is that this holds locally, whatever order a caller writes in.
 */
class CascadeOverlayPrecedenceTest {

    private val path = "/Order/Total"
    private val reps = listOf(1, 1)

    @Test
    fun anOverlayWriteCannotUnpoisonACell() {
        val cascade = CascadeOverlay()
        cascade.poison(path, reps)

        cascade.overlay(path, reps, Val.Txt("late"))
        cascade.clear(path, reps)

        assertEquals(CascadeOverlay.Entry.Poisoned, cascade.rowsAt(path)?.get(reps),
            "the flat form kept answering POISONED because precedence was applied at read time and a poisoned " +
                "cell was never removed from the overlay; one entry per cell must reproduce that")
    }

    @Test
    fun poisonStillWinsWhenItArrivesLast() {
        val cascade = CascadeOverlay()
        cascade.overlay(path, reps, Val.Txt("early"))

        cascade.poison(path, reps)

        assertEquals(CascadeOverlay.Entry.Poisoned, cascade.rowsAt(path)?.get(reps))
    }

    @Test
    fun clearingAndOverlayingReplaceEachOtherInWriteOrder() {
        val cascade = CascadeOverlay()

        cascade.clear(path, reps)
        assertEquals(CascadeOverlay.Entry.Cleared, cascade.rowsAt(path)?.get(reps))

        // The flat form paired every overlay write with `cleared -= key`, so the later write won here too.
        cascade.overlay(path, reps, Val.Txt("v"))
        assertEquals(CascadeOverlay.Entry.Overlaid(Val.Txt("v")), cascade.rowsAt(path)?.get(reps))

        cascade.clear(path, reps)
        assertEquals(CascadeOverlay.Entry.Cleared, cascade.rowsAt(path)?.get(reps))
    }

    @Test
    fun cellsAreIndependentAcrossRowsAndPaths() {
        val cascade = CascadeOverlay()
        val other = listOf(1, 2)

        cascade.poison(path, reps)
        cascade.overlay(path, other, Val.Txt("row2"))
        cascade.clear("/Order/Net", reps)

        assertEquals(CascadeOverlay.Entry.Poisoned, cascade.rowsAt(path)?.get(reps))
        assertEquals(CascadeOverlay.Entry.Overlaid(Val.Txt("row2")), cascade.rowsAt(path)?.get(other))
        assertEquals(CascadeOverlay.Entry.Cleared, cascade.rowsAt("/Order/Net")?.get(reps))
    }

    /** The fast exit the whole change rests on: an untouched path must not even reach a repetition-list hash. */
    @Test
    fun anUntouchedPathHasNoRowsAtAll() {
        val cascade = CascadeOverlay()

        assertNull(cascade.rowsAt(path), "an empty cascade must answer without a reps lookup")

        cascade.clear("/Order/Net", reps)

        assertNull(cascade.rowsAt(path), "a path the cascade never wrote must stay absent once it is non-empty")
        assertNull(cascade.rowsAt("/Order/Net")?.get(listOf(9, 9)), "an untouched ROW of a written path is absent")
    }
}
