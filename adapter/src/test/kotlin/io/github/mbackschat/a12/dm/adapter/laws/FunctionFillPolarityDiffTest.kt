package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — IF71 (graduated from IG45, the sibling of IF69): the kernel's `VkBigDecimal` fill
 * (`kannGroesserWerden`/`kannKleinerWerden`) propagates through the value FUNCTIONS, so a fired comparison over a
 * function-wrapped fillable (empty) operand can be an OMISSION error. `Interpreter.funcFill` routed every such
 * function through the generic `else → leafOmission`, which does NOT recurse into an arithmetic arg (so a
 * `RoundAccounting([A]+[B], n)` lost the arith fill → always VALUE) and is symmetric (so a `RoundAccountingValue`
 * / `AbsValue` over an UNSIGNED empty field wrongly typed OMISSION). Each function carries fill clean-room from
 * `VkBigDecimal`:
 *
 *  - **Round** (`setScale`) — PRESERVES `kannGroesser/Kleiner` exactly, so a `Round*(expr, n)` / `Round*Value(f)`
 *    propagates its rounded operand's directional fill unchanged.
 *  - **Abs** (`abs`) — TRANSFORMS it with the operand's value sign (`g' = g∨s`, `s' = (pos∧s)∨(neg∧g)`): `|x|`
 *    grows off either direction and shrinks only toward 0 (so `|x| ≥ 0` never shrinks → `> -100` is VALUE).
 *  - **Min/Max** (`min`/`max`) — carry directional fill: min shrinks via either operand and grows via the
 *    SMALLER; max mirrors.
 *
 * This is the DOMINANT corpus shape (money computations round), so it surfaces most through the per-computation
 * implicit validation `[F] != RoundAccounting(<operation>, n)` (IF65). Own-domain (order desk): `Quantity`/
 * `BackorderQuantity`/`UnitPrice` are signed, `StockOnHand` is unsigned (`positivesOnly`). Conditions authored
 * with the rulekit typed DSL. Both kernel strategies emit calls to the SAME runtime `VkBigDecimal`
 * (`setScale`/`abs`/`min`/`max`) ⟹ codegen-parity (static-JAVA tri-checked).
 *
 * Probe: compare the function against a FILLED field / literal (fill `(false,false)`), so `f < x` fires ⟺ tests
 * whether the value can GROW and `f > x` tests whether it can SHRINK — isolating the function's directional fill.
 */
class FunctionFillPolarityDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val m = BoundModel.ofJson(order)
    private fun render(c: Condition): String = RuntimeLaws.render(c)
    private fun n(path: String): NumberExpr = m.num(path).asNumber()
    private fun nr(path: String): NumberRef = m.num(path)

    private class Scenario(val kModel: String, val kDoc: RuntimeLaws.Doc, val interp: DmInterpreter, val iDoc: Document)

    private fun scenario(cond: String, errorRel: String, filled: Map<String, String>, empty: List<String>): Scenario {
        val kModel = RuntimeLaws.withRule(order, "/Order", "R", errorRel, CODE, cond)
        val def = ModelLoader.load(kModel)
        val kDoc = RuntimeLaws.doc().group("/Order", 1).field(ANCHOR, "x", 1, 1)
        val iDoc = def.newDocument().apply { group("/Order", 1); field(ANCHOR, "x", 1, 1) }
        filled.forEach { (p, v) -> kDoc.field(p, v, 1, 1); iDoc.field(p, v, 1, 1) }
        empty.forEach { p -> kDoc.emptyField(p, 1, 1); iDoc.emptyField(p, 1, 1) }
        return Scenario(kModel, kDoc, DmInterpreter(def), iDoc)
    }

    private fun kType(s: Scenario, code: String = CODE): String? =
        RuntimeLaws.messages(s.kModel, s.kDoc).firstOrNull { it.code() == code }?.type()?.name
    private fun iType(s: Scenario, code: String = CODE): String? = s.interp.validateFull(s.iDoc).firstOrNull { it.code == code }
        ?.let { if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR" }

    private fun agree(s: Scenario, expected: String, msg: String) {
        val k = kType(s); val i = iType(s)
        assertEquals(expected, k, "$msg — scenario must FIRE $expected on the KERNEL [kernel=$k]")
        assertEquals(k, i, "$msg [kernel=$k interp=$i]")
    }

    /** As [scenario], but injects a COMPUTATION (`../BackorderQuantity = operation`) + a stored value so the
     *  implicit `[Back] != operation` validation (IF65) fires — the path through which the function-wrapped fill
     *  polarity (IF71) surfaces most widely. */
    private fun compScenario(operation: String, stored: String, filled: Map<String, String>, empty: List<String>): Scenario {
        val kModel = RuntimeLaws.withComputation(order, "/Order", COMP, "../BackorderQuantity", operation)
        val def = ModelLoader.load(kModel)
        val kDoc = RuntimeLaws.doc().group("/Order", 1).field(BQ, stored, 1, 1)
        val iDoc = def.newDocument().apply { group("/Order", 1); field(BQ, stored, 1, 1) }
        filled.forEach { (p, v) -> kDoc.field(p, v, 1, 1); iDoc.field(p, v, 1, 1) }
        empty.forEach { p -> kDoc.emptyField(p, 1, 1); iDoc.emptyField(p, 1, 1) }
        return Scenario(kModel, kDoc, DmInterpreter(def), iDoc)
    }

    private fun compAgree(s: Scenario, expected: String, msg: String) {
        val k = kType(s, COMP); val i = iType(s, COMP)
        assertEquals(expected, k, "$msg — implicit validation must FIRE $expected on the KERNEL [kernel=$k]")
        assertEquals(k, i, "$msg [kernel=$k interp=$i]")
    }

    private companion object {
        const val CODE = "FF"
        const val BQ = "/Order/BackorderQuantity"   // signed, scale 0
        const val Q = "/Order/Quantity"             // signed, scale 0
        const val SOH = "/Order/StockOnHand"        // UNSIGNED, scale 0
        const val UP = "/Order/UnitPrice"           // signed, scale 2
        const val PC = "/Order/ProductCode"         // free string (for RangeAsNumber over an empty string)
        const val ANCHOR = "/Order/CustomerName"    // filled string, makes the row non-empty (unreferenced)
        const val COMP = "FFComp"
    }

    // ── Round — setScale PRESERVES the arg's fill exactly ────────────────────────────────────────────────────
    // over an arithmetic arg: rides the IF69 arithmetic fill through the function wrapper (the dominant shape).
    @Test fun roundArithBothEmptyGrows() =
        agree(scenario(render(Dsl.roundAccounting(n(Q).plus(n(SOH)), 0).lt(100)), "../Quantity", mapOf(), listOf(Q, SOH)),
            "OMISSION_ERROR", "RoundAccounting(Q+SOH,0) < 100 (both empty): filling grows it → OMISSION")
    @Test fun roundArithUnsignedCannotShrink() =
        agree(scenario(render(Dsl.roundUp(n(SOH).plus(5L), 0).gt(-100)), "../StockOnHand", mapOf(), listOf(SOH)),
            "VALUE_ERROR", "RoundUp(SOH+5,0) > -100: an unsigned empty can't shrink it → VALUE")
    // *Value(field): the directional refinement — an UNSIGNED empty field grows but can't shrink.
    @Test fun roundValueUnsignedGrows() =
        agree(scenario(render(Dsl.roundAccountingValue(nr(SOH)).lt(100)), "../StockOnHand", mapOf(), listOf(SOH)),
            "OMISSION_ERROR", "RoundAccountingValue(SOH) < 100: filling grows it → OMISSION")
    @Test fun roundValueUnsignedCannotShrink() =
        agree(scenario(render(Dsl.roundAccountingValue(nr(SOH)).gt(-100)), "../StockOnHand", mapOf(), listOf(SOH)),
            "VALUE_ERROR", "RoundAccountingValue(SOH) > -100: an unsigned empty can't shrink → VALUE")
    @Test fun roundValueSignedCanShrink() =
        agree(scenario(render(Dsl.roundDownValue(nr(Q)).gt(-100)), "../Quantity", mapOf(), listOf(Q)),
            "OMISSION_ERROR", "RoundDownValue(Q) > -100: a signed empty can shrink below → OMISSION")

    // ── Abs — abs TRANSFORMS the fill (grows off either direction, shrinks only toward 0) ────────────────────
    @Test fun absValueSignedEmptyGrows() =
        agree(scenario(render(Dsl.absValue(nr(Q)).lt(100)), "../Quantity", mapOf(), listOf(Q)),
            "OMISSION_ERROR", "AbsValue(Q) < 100: filling grows |Q| → OMISSION")
    // |x| ≥ 0 can never shrink below 0 → `> -100` is a VALUE error (was symmetric OMISSION before the fix).
    @Test fun absValueSignedEmptyCannotShrink() =
        agree(scenario(render(Dsl.absValue(nr(Q)).gt(-100)), "../Quantity", mapOf(), listOf(Q)),
            "VALUE_ERROR", "AbsValue(Q) > -100: |Q| ≥ 0 can't shrink below → VALUE")
    @Test fun absArithGrows() =
        agree(scenario(render(Dsl.abs(n(Q).minus(n(SOH))).lt(100)), "../Quantity", mapOf(), listOf(Q, SOH)),
            "OMISSION_ERROR", "Abs(Q−SOH) < 100 (both empty): |diff| can grow → OMISSION")
    @Test fun absArithCannotShrink() =
        agree(scenario(render(Dsl.abs(n(Q).minus(n(SOH))).gt(-100)), "../Quantity", mapOf(), listOf(Q, SOH)),
            "VALUE_ERROR", "Abs(Q−SOH) > -100: |diff| ≥ 0 → VALUE")

    // ── Min/Max — min/max carry directional fill ─────────────────────────────────────────────────────────────
    // Min(SOH_empty, 4): the running min is SOH (0), the SMALLER — grow rides SOH (can grow), shrink rides neither.
    @Test fun minSmallerUnsignedGrows() =
        agree(scenario(render(Dsl.min(n(SOH), n(BQ)).lt(100)), "../StockOnHand", mapOf(BQ to "4"), listOf(SOH)),
            "OMISSION_ERROR", "Min(SOH,4) < 100: the min rides SOH which can grow → OMISSION")
    @Test fun minCannotShrink() =
        agree(scenario(render(Dsl.min(n(SOH), n(BQ)).gt(-100)), "../StockOnHand", mapOf(BQ to "4"), listOf(SOH)),
            "VALUE_ERROR", "Min(SOH,4) > -100: neither operand can shrink it → VALUE")
    // Max(Q_signed_empty, -4): the running max is Q (0), the LARGER — shrink rides Q (signed can shrink).
    @Test fun maxSignedLargerShrinks() =
        agree(scenario(render(Dsl.max(n(Q), n(BQ)).gt(-100)), "../Quantity", mapOf(BQ to "-4"), listOf(Q)),
            "OMISSION_ERROR", "Max(Q,-4) > -100: the max rides signed Q which can shrink → OMISSION")

    // ── RangeAsNumber — GROW-ONLY (a number ≥ 0; `bereichAlsZahl` sets kannKleiner=false) ────────────────────
    // over an empty STRING field: still yields 0 and FIRES, growable by filling the string, never shrinkable.
    @Test fun rangeAsNumberGrows() =
        agree(scenario(render(Dsl.rangeAsNumber(m.field(PC), 1, 3).lt(100)), "../ProductCode", mapOf(), listOf(PC)),
            "OMISSION_ERROR", "RangeAsNumber(PC,1,3) < 100 (empty): filling the string grows it → OMISSION")
    @Test fun rangeAsNumberCannotShrink() =
        agree(scenario(render(Dsl.rangeAsNumber(m.field(PC), 1, 3).gt(-100)), "../ProductCode", mapOf(), listOf(PC)),
            "VALUE_ERROR", "RangeAsNumber(PC,1,3) > -100: a value ≥ 0 can't shrink below → VALUE")

    // ── nested: fill flows through arithmetic INSIDE a function INSIDE arithmetic ────────────────────────────
    @Test fun roundInsideArith() =
        agree(scenario(render(Dsl.roundAccounting(n(Q).plus(n(SOH)), 0).times(n(BQ)).lt(100)), "../Quantity", mapOf(BQ to "3"), listOf(Q, SOH)),
            "OMISSION_ERROR", "(RoundAccounting(Q+SOH,0)×3) < 100: fill flows through the wrapper → OMISSION")

    // ── the per-computation implicit validation ([F] != RoundAccounting(<operation>, n)) — IF71's dominant path ─
    @Test fun computationImplicitValidationRidesRoundFill() =
        compAgree(compScenario("RoundAccounting([Quantity] + [StockOnHand], 0)", stored = "10", filled = mapOf(Q to "3"), empty = listOf(SOH)),
            "OMISSION_ERROR", "computed Back=RoundAccounting(Q+SOH,0): stored 10 ≠ 3, RHS can grow → OMISSION")
    @Test fun computationImplicitValidationValueControl() =
        compAgree(compScenario("RoundAccounting([Quantity] + [StockOnHand], 0)", stored = "1", filled = mapOf(Q to "3"), empty = listOf(SOH)),
            "VALUE_ERROR", "computed Back: stored 1 < 3 and the unsigned RHS can't shrink → VALUE")

    // ── both strategies: setScale/abs/min/max are runtime-shared; static-JAVA codegen must agree ─────────────
    @Test fun codegenPropagatesFunctionFillIdentically() {
        val s = scenario(render(Dsl.roundAccountingValue(nr(SOH)).lt(100)), "../StockOnHand", mapOf(), listOf(SOH))
        val groovy = kType(s)
        val engine = RuntimeLaws.kernelJavaEngine(s.kModel)
        val javaStatic = engine.messages(engine.instance(s.kDoc)).firstOrNull { it.code() == CODE }?.type()?.name
        assertEquals(groovy, iType(s), "codegen (interp≠groovy) groovy=$groovy interp=${iType(s)}")
        assertEquals(groovy, javaStatic, "codegen (java-static≠groovy) groovy=$groovy java=$javaStatic")
    }
}
