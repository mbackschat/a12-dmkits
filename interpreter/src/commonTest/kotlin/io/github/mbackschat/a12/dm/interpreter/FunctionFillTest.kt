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
 * Multiplatform (JVM + JS) lock for IF71 (graduated from IG45, the sibling of IF69): the `VkBigDecimal` fill
 * (`kannGroesserWerden`/`kannKleinerWerden`) propagates through the value FUNCTIONS too, so a fired comparison
 * over a function-wrapped fillable operand can be OMISSION. The functions carry fill differently:
 *
 *  - **Round** (`RoundDown/Up/Accounting[Value]`) — `setScale` PRESERVES the arg's fill exactly (identity), so a
 *    `RoundAccounting([A]+[B], n)` over an empty operand rides the arithmetic fill (IF69). The `*Value(field)`
 *    siblings gain the directional refinement (an UNSIGNED empty field can't shrink → `>= 0` is VALUE).
 *  - **Abs** (`Abs`/`AbsValue`) — `abs` TRANSFORMS the fill with the operand's value sign (`g' = g∨s`,
 *    `s' = (pos∧s)∨(neg∧g)`): `|x|` grows off either direction and shrinks only toward 0.
 *  - **Min/Max** (operand-list) — `min`/`max` carry directional fill (min shrinks via either operand, grows via
 *    the smaller; max mirrors).
 *
 * The kernel differential (both strategies, static-JAVA tri-check) is `adapter.laws.FunctionFillPolarityDiffTest`;
 * this locks the same behaviour on the pure engine, JVM + JS. Probe: compare against a filled field / literal so
 * `f < x` isolates "can it GROW" and `f > x` isolates "can it SHRINK".
 */
class FunctionFillTest {

    // /Order: a filled string anchor (keeps the row non-empty), a signed Qty, an UNSIGNED Stock, a signed Back.
    private fun model(cond: String) = ModelDef(
        fields = listOf(
            FieldDef("/Order/Anchor", Kind.STRING),
            FieldDef("/Order/Str", Kind.STRING),
            FieldDef("/Order/Qty", Kind.NUMBER),
            FieldDef("/Order/Stock", Kind.NUMBER, signed = false),
            FieldDef("/Order/Back", Kind.NUMBER),
        ),
        rules = listOf(RuleDef("/Order/R", "/Order", cond, CODE, "../Back")),
    )

    private fun sig(cond: String, filled: Map<String, String> = emptyMap(), empty: List<String> = emptyList()): String? {
        val m = model(cond)
        val doc: Document = m.newDocument().field("/Order/Anchor", "x", 1, 1)
        filled.forEach { (p, v) -> doc.field(p, v, 1, 1) }
        empty.forEach { p -> doc.emptyField(p, 1, 1) }
        return DmInterpreter(m).validateFull(doc).firstOrNull { it.code == CODE }
            ?.let { if (it.type == MsgType.OMISSION) "OMISSION" else "VALUE" }
    }

    private val Q = "/Order/Qty"; private val S = "/Order/Stock"; private val B = "/Order/Back"

    // ── Round: setScale PRESERVES the arg's fill (identity) ──────────────────────────────────────────────────
    // over an arithmetic arg — rides the IF69 arithmetic fill through the function wrapper.
    @Test fun roundArithBothEmptyGrows() = assertEquals("OMISSION", sig("RoundAccounting([Qty] + [Stock], 0) < 100", empty = listOf(Q, S)))
    @Test fun roundArithUnsignedCannotShrink() = assertEquals("VALUE", sig("RoundAccounting([Stock] + 5, 0) > -100", empty = listOf(S)))
    // *Value(field): the directional refinement — an unsigned empty field can grow but not shrink.
    @Test fun roundValueUnsignedGrows() = assertEquals("OMISSION", sig("RoundAccountingValue([Stock]) < 100", empty = listOf(S)))
    @Test fun roundValueUnsignedCannotShrink() = assertEquals("VALUE", sig("RoundAccountingValue([Stock]) > -100", empty = listOf(S)))
    @Test fun roundValueSignedCanShrink() = assertEquals("OMISSION", sig("RoundDownValue([Qty]) > -100", empty = listOf(Q)))

    // ── Abs: abs TRANSFORMS the fill (grows off either direction, shrinks only toward 0) ─────────────────────
    @Test fun absValueSignedEmptyGrows() = assertEquals("OMISSION", sig("AbsValue([Qty]) < 100", empty = listOf(Q)))
    // |x| ≥ 0 can never shrink below 0 → `> -100` is a VALUE error (was symmetric OMISSION before the fix).
    @Test fun absValueSignedEmptyCannotShrink() = assertEquals("VALUE", sig("AbsValue([Qty]) > -100", empty = listOf(Q)))
    @Test fun absArithGrows() = assertEquals("OMISSION", sig("Abs([Qty] - [Stock]) < 100", empty = listOf(Q, S)))
    @Test fun absArithCannotShrink() = assertEquals("VALUE", sig("Abs([Qty] - [Stock]) > -100", empty = listOf(Q, S)))

    // ── Min/Max: min/max carry directional fill ──────────────────────────────────────────────────────────────
    // Min(Stock_empty, 4): the running min is Stock (0) — the SMALLER — so the min's grow rides Stock (can grow),
    // shrink rides neither (both fixed/unsigned) → `< 100` is OMISSION, `> -100` is VALUE.
    @Test fun minSmallerUnsignedGrows() = assertEquals("OMISSION", sig("Min([Stock], [Back]) < 100", mapOf(B to "4"), listOf(S)))
    @Test fun minCannotShrink() = assertEquals("VALUE", sig("Min([Stock], [Back]) > -100", mapOf(B to "4"), listOf(S)))
    // Max(Qty_signed_empty, -4): the running max is Qty (0) — the LARGER — so max's shrink rides Qty (signed can
    // shrink) → `> -100` is OMISSION; grow rides either → `< 100` OMISSION.
    @Test fun maxSignedLargerShrinks() = assertEquals("OMISSION", sig("Max([Qty], [Back]) > -100", mapOf(B to "-4"), listOf(Q)))

    // ── RangeAsNumber: GROW-ONLY (≥ 0) — fires 0 on an empty string, growable, never shrinkable ──────────────
    @Test fun rangeAsNumberGrows() = assertEquals("OMISSION", sig("RangeAsNumber(Str, 1, 3) < 100", empty = listOf("/Order/Str")))
    @Test fun rangeAsNumberCannotShrink() = assertEquals("VALUE", sig("RangeAsNumber(Str, 1, 3) > -100", empty = listOf("/Order/Str")))

    private companion object {
        const val CODE = "FF"
    }
}
