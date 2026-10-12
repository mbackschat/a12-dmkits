package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.ast.Val
import io.github.mbackschat.a12.dm.interpreter.eval.CascadeOverlay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class CascadeOverlayDisplayCacheTest {
    @Test
    fun returningToAnOldPrecisionRecomputesInsteadOfRetainingItsPreviousRendering() {
        val entry = CascadeOverlay.Entry.Overlaid(Val.Num(dec("123456789") * dec("7")))
        val first = entry.numberDisplay(2)
        entry.numberDisplay(4)
        val current = entry.numberDisplay(2)
        assertEquals(first, current)
        assertNotSame(first, current)
        assertSame(current, entry.numberDisplay(2))
    }
}
