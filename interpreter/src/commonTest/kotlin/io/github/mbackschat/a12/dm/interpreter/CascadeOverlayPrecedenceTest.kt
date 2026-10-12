package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.ast.Val
import io.github.mbackschat.a12.dm.interpreter.ast.emptyValue
import io.github.mbackschat.a12.dm.interpreter.eval.CascadeOverlay
import io.github.mbackschat.a12.dm.interpreter.eval.CellState
import io.github.mbackschat.a12.dm.interpreter.eval.OverlayContext
import io.github.mbackschat.a12.dm.interpreter.eval.OverlayViews
import io.github.mbackschat.a12.dm.interpreter.eval.PoisonedReadException
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

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
        val cascade = CascadeOverlay(repeatableGroups = emptySet())
        cascade.poison(path, reps)

        cascade.overlay(path, reps, Val.Txt("late"))
        cascade.clear(path, reps)

        assertEquals(CascadeOverlay.Entry.Poisoned, cascade.rowsAt(path)?.get(reps),
            "the flat form kept answering POISONED because precedence was applied at read time and a poisoned " +
                "cell was never removed from the overlay; one entry per cell must reproduce that")
    }

    @Test
    fun poisonStillWinsWhenItArrivesLast() {
        val cascade = CascadeOverlay(repeatableGroups = emptySet())
        cascade.overlay(path, reps, Val.Txt("early"))

        cascade.poison(path, reps)

        assertEquals(CascadeOverlay.Entry.Poisoned, cascade.rowsAt(path)?.get(reps))
    }

    @Test
    fun clearingAndOverlayingReplaceEachOtherInWriteOrder() {
        val cascade = CascadeOverlay(repeatableGroups = emptySet())

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
        val cascade = CascadeOverlay(repeatableGroups = emptySet())
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
        val cascade = CascadeOverlay(repeatableGroups = emptySet())

        assertNull(cascade.rowsAt(path), "an empty cascade must answer without a reps lookup")

        cascade.clear("/Order/Net", reps)

        assertNull(cascade.rowsAt(path), "a path the cascade never wrote must stay absent once it is non-empty")
        assertNull(cascade.rowsAt("/Order/Net")?.get(listOf(9, 9)), "an untouched ROW of a written path is absent")
    }

    @Test
    fun numericDisplayReusesTheCurrentPrecisionAcrossDirectAndScanReads() {
        val cascade = CascadeOverlay(repeatableGroups = emptySet())
        cascade.overlay(path, reps, Val.Num(dec("123456789") * dec("7")))
        var minimumFractionalDigits = 2
        val overlay = numericOverlay(cascade) { minimumFractionalDigits }

        val first = overlay.displayValue(path)
        assertEquals("864197523.00", first)
        assertSame(first, overlay.displayValue(path))
        assertSame(first, overlay.containedFieldCells("/Order", declaredRange = false).single().raw)

        minimumFractionalDigits = 4
        val moreDigits = overlay.displayValue(path)
        assertEquals("864197523.0000", moreDigits)
        assertSame(moreDigits, overlay.displayValue(path))
        assertSame(moreDigits, overlay.containedFieldCells("/Order", declaredRange = false).single().raw)

        minimumFractionalDigits = 2
        val fewerDigits = overlay.containedFieldCells("/Order", declaredRange = false).single().raw
        assertEquals("864197523.00", fewerDigits)
        assertSame(fewerDigits, overlay.displayValue(path))
    }

    @Test
    fun replacingClearingAndPoisoningTheEntryCannotReuseAnOldNumericDisplay() {
        val cascade = CascadeOverlay(repeatableGroups = emptySet())
        val overlay = numericOverlay(cascade) { 2 }
        cascade.overlay(path, reps, Val.Num(dec("123456789") * dec("7")))
        assertEquals("864197523.00", overlay.displayValue(path))

        cascade.overlay(path, reps, Val.Num(dec("123456789") * dec("6")))
        val replacement = overlay.displayValue(path)
        assertEquals("740740734.00", replacement)
        assertSame(replacement, overlay.containedFieldCells("/Order", declaredRange = false).single().raw)

        cascade.clear(path, reps)
        assertNull(overlay.displayValue(path))
        assertNull(overlay.containedFieldCells("/Order", declaredRange = false).single().raw)

        cascade.overlay(path, reps, Val.Num(dec("123456789") * dec("5")))
        assertEquals("617283945.00", overlay.displayValue(path))
        cascade.poison(path, reps)
        assertFailsWith<PoisonedReadException> { overlay.displayValue(path) }
        assertEquals(CellState.INVALID, overlay.containedFieldCells("/Order", declaredRange = false).single().baseState)

        cascade.overlay(path, reps, Val.Num(dec("123456789") * dec("4")))
        assertFailsWith<PoisonedReadException> { overlay.displayValue(path) }
    }

    private fun numericOverlay(cascade: CascadeOverlay, minFrac: () -> Int): OverlayContext {
        val model = ModelDef(
            fields = listOf(FieldDef(path, Kind.NUMBER, scale = 4, minFractionalDigits = 2)),
            rules = emptyList(),
        )
        val context = model.newDocument().field(path, "1.00", 1, 1).computeContexts("/Order").single()
        return OverlayContext(context, cascade, { emptyValue(model.kinds.getValue(it)) }, { minFrac() }, OverlayViews(), path)
    }
}
