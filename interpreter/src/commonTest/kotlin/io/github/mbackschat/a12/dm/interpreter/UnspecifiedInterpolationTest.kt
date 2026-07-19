package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.SingleRowDoc
import io.github.mbackschat.a12.dm.interpreter.eval.renderMessage
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * IF121 — interpolating an UNSPECIFIED `$field.value$`: `0` for a NUMBER, empty string for other kinds
 * (matching the kernel's `getValueForDisplay`; the JVM-vs-kernel proof is `adapter.laws.UnspecifiedValue
 * InterpolationDiffTest`, this pins the same rendering on BOTH KMP targets). A FILLED value renders verbatim.
 */
class UnspecifiedInterpolationTest {

    private fun ctx(kinds: Map<String, Kind>, values: Map<String, String> = emptyMap()) =
        SingleRowDoc("/G", values, kinds)

    @Test
    fun unspecifiedNumberRendersZero() {
        assertEquals("x=0.", renderMessage("x=\$N.value\$.", "/G", ctx(mapOf("/G/N" to Kind.NUMBER))))
    }

    @Test
    fun unspecifiedNonNumberRendersEmpty() {
        assertEquals("s=[].", renderMessage("s=[\$S.value\$].", "/G", ctx(mapOf("/G/S" to Kind.STRING))))
        assertEquals("d=[].", renderMessage("d=[\$D.value\$].", "/G", ctx(mapOf("/G/D" to Kind.DATE))))
    }

    @Test
    fun filledValueRendersVerbatim() {
        val c = ctx(mapOf("/G/N" to Kind.NUMBER), mapOf("/G/N" to "42"))
        assertEquals("x=42.", renderMessage("x=\$N.value\$.", "/G", c))
    }
}
