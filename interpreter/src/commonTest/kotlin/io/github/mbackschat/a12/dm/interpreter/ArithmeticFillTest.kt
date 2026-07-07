package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM + JS) lock for IF69 (graduated from IG43): the `VkBigDecimal` fill (`kannGroesserWerden`/`kannKleinerWerden`)
 * propagates through arithmetic so a fired comparison over an arithmetic expression with a fillable (empty)
 * operand can be OMISSION. Each op propagates differently: `+` combines, `−` flips the subtrahend, `×`/`÷` are
 * SIGN-dependent (an unsigned-empty × a positive factor grows; × a negative factor shrinks), `^` follows the
 * base magnitude and exponent parity. The kernel differential (both strategies) is
 * `adapter.laws.ArithmeticFillPolarityDiffTest`; this locks the same behaviour on the pure engine, JVM + JS.
 *
 * Probe: compare the arithmetic against a filled field / literal (fill `(false,false)`), so `arith < x` isolates
 * "can it GROW" (LT is OMISSION iff the left can grow) and `arith > x` isolates "can it SHRINK".
 */
class ArithmeticFillTest {

    // /Order: a filled string anchor (keeps the row non-empty), a signed Qty, an UNSIGNED Stock, a signed Back.
    private fun model(cond: String) = ModelDef(
        fields = listOf(
            FieldDef("/Order/Anchor", Kind.STRING),
            FieldDef("/Order/Qty", Kind.NUMBER),
            FieldDef("/Order/Stock", Kind.NUMBER, signed = false),
            FieldDef("/Order/Back", Kind.NUMBER),
        ),
        rules = listOf(RuleDef("/Order/R", "/Order", cond, CODE, "../Back")),
    )

    /** Evaluate `cond` with `filled` field values + `empty` present-valueless fields; return the fired type. */
    private fun sig(cond: String, filled: Map<String, String> = emptyMap(), empty: List<String> = emptyList()): String? {
        val m = model(cond)
        val doc: Document = m.newDocument().field("/Order/Anchor", "x", 1, 1)
        filled.forEach { (p, v) -> doc.field(p, v, 1, 1) }
        empty.forEach { p -> doc.emptyField(p, 1, 1) }
        return DmInterpreter(m).validateFull(doc).firstOrNull { it.code == CODE }
            ?.let { if (it.type == MsgType.OMISSION) "OMISSION" else "VALUE" }
    }

    // ── + ──────────────────────────────────────────────────────────────────────────────────────────────────
    @Test fun addBothEmptyGrows() = assertEquals("OMISSION", sig("[Qty] + [Stock] < 100", empty = listOf("/Order/Qty", "/Order/Stock")))
    @Test fun addSignedEmptyShrinks() = assertEquals("OMISSION", sig("[Qty] + [Stock] > -100", empty = listOf("/Order/Qty", "/Order/Stock")))
    @Test fun addUnsignedCannotShrink() = assertEquals("VALUE", sig("[Stock] + 5 > -100", empty = listOf("/Order/Stock")))

    // ── − : the subtrahend's direction flips ─────────────────────────────────────────────────────────────────
    @Test fun subUnsignedSubtrahendShrinks() = assertEquals("OMISSION", sig("[Back] - [Stock] > -100", mapOf("/Order/Back" to "5"), listOf("/Order/Stock")))
    @Test fun subUnsignedSubtrahendCannotGrow() = assertEquals("VALUE", sig("[Back] - [Stock] < 100", mapOf("/Order/Back" to "5"), listOf("/Order/Stock")))

    // ── × : sign-dependent (same operand fill, direction flips with the other factor's sign) ─────────────────
    @Test fun mulByPositiveGrows() = assertEquals("OMISSION", sig("[Stock] * [Back] < 100", mapOf("/Order/Back" to "4"), listOf("/Order/Stock")))
    @Test fun mulByPositiveCannotShrink() = assertEquals("VALUE", sig("[Stock] * [Back] > -100", mapOf("/Order/Back" to "4"), listOf("/Order/Stock")))
    @Test fun mulByNegativeShrinks() = assertEquals("OMISSION", sig("[Stock] * [Back] > -100", mapOf("/Order/Back" to "-4"), listOf("/Order/Stock")))
    @Test fun mulByNegativeCannotGrow() = assertEquals("VALUE", sig("[Stock] * [Back] < 100", mapOf("/Order/Back" to "-4"), listOf("/Order/Stock")))

    // ── ÷ ──────────────────────────────────────────────────────────────────────────────────────────────────
    @Test fun divByPositiveGrows() = assertEquals("OMISSION", sig("[Stock] / [Back] < 100", mapOf("/Order/Back" to "4"), listOf("/Order/Stock")))
    @Test fun divByNegativeShrinks() = assertEquals("OMISSION", sig("[Stock] / [Back] > -100", mapOf("/Order/Back" to "-4"), listOf("/Order/Stock")))

    // ── ^ ──────────────────────────────────────────────────────────────────────────────────────────────────
    @Test fun powEvenExponentGrows() = assertEquals("OMISSION", sig("[Qty] ^ [Back] < 100", mapOf("/Order/Back" to "2"), listOf("/Order/Qty")))
    @Test fun powEvenExponentCannotShrink() = assertEquals("VALUE", sig("[Qty] ^ [Back] > -100", mapOf("/Order/Back" to "2"), listOf("/Order/Qty")))

    private companion object {
        const val CODE = "AF"
    }
}
