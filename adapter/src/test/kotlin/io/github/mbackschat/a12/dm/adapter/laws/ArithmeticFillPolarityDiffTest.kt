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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — IF69 (graduated from IG43): the kernel's `VkBigDecimal` fill (`kannGroesserWerden`/`kannKleinerWerden`) propagates
 * through ARITHMETIC, so a fired comparison over an arithmetic expression with a fillable (empty) operand can be
 * an OMISSION error. The interpreter had no `Expr.Arith` case in `fill`, so every arithmetic operand read as a
 * fixed value → always VALUE, diverging from the kernel wherever filling an operand could still break (or clear)
 * the firing. Each op propagates fill differently (clean-room from `VkBigDecimal`):
 *
 *  - `+` : each direction fillable iff either operand is fillable in that direction.
 *  - `−` : the subtrahend's direction FLIPS (`a − b` grows if a grows or b shrinks).
 *  - `×` : SIGN-DEPENDENT — an unsigned-empty × a positive factor can only grow; × a negative factor can only
 *          shrink (`produktKannGroesser/KleinerWerden`).
 *  - `÷` : `a ÷ b = a × (1/b)`; the reciprocal carries b's sign and flipped fillability.
 *  - `^` : per `powCanChange` — a square (`base^2`) of a signed-empty base can only grow (result ≥ 0), etc.
 *
 * Probe trick: compare the arithmetic expression against a FILLED field / literal (fill `(false,false)`), so
 * `arith < x` fires ⟺ tests whether the arith can GROW (LT is OMISSION iff the left can grow) and `arith > x`
 * tests whether it can SHRINK — isolating the arithmetic operand's directional fill.
 *
 * Own-domain (order desk), the order fixture: `Quantity`/`BackorderQuantity`/`UnitPrice` are signed, `StockOnHand`
 * is unsigned (`positivesOnly`). Conditions authored with the rulekit typed DSL and rendered to the stored form.
 * Both kernel strategies share the runtime `VkBigDecimal` arithmetic ⟹ codegen-parity (static-JAVA tri-checked).
 */
class ArithmeticFillPolarityDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val m = BoundModel.ofJson(order)
    private fun render(c: Condition): String = RuntimeLaws.render(c)
    private fun n(path: String): NumberExpr = m.num(path).asNumber()

    private class Scenario(val kModel: String, val kDoc: RuntimeLaws.Doc, val interp: DmInterpreter, val iDoc: Document)

    /** Inject `cond` as a rule on /Order (error at `errorRel`), place `filled` values + `empty` present-valueless
     *  fields, on BOTH engines from the SAME injected model JSON. */
    private fun scenario(cond: String, errorRel: String, filled: Map<String, String>, empty: List<String>): Scenario {
        val kModel = RuntimeLaws.withRule(order, "/Order", "R", errorRel, CODE, cond)
        val def = ModelLoader.load(kModel)
        // A filled (unreferenced) anchor makes the /Order row non-empty so the rule is EVALUATED — an all-empty
        // row is skipped by both engines (the empty-row gate), which would hide the arithmetic-fill polarity.
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

    /** Assert the interpreter and kernel agree on the fired message TYPE, and that the rule actually fired. */
    private fun agree(s: Scenario, expected: String, msg: String) {
        val k = kType(s); val i = iType(s)
        assertEquals(expected, k, "$msg — scenario must FIRE $expected on the KERNEL [kernel=$k]")
        assertEquals(k, i, "$msg [kernel=$k interp=$i]")
    }

    private fun agreeBothStrategies(s: Scenario, expected: String, msg: String) {
        agree(s, expected, msg)
        val engine = RuntimeLaws.kernelJavaEngine(s.kModel)
        val javaStatic = engine.messages(engine.instance(s.kDoc)).firstOrNull { it.code() == CODE }?.type()?.name
        assertEquals(expected, javaStatic, "$msg [java-static=$javaStatic]")
    }

    /** As [scenario], but injects a COMPUTATION (`../BackorderQuantity = operation`) instead of a rule, plus a
     *  stored `BackorderQuantity` value so the implicit `[Back] != operation` validation (IF65) fires — the path
     *  through which the arithmetic-empty polarity (IF69) surfaces widely. */
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
        const val CODE = "AF"
        const val BQ = "/Order/BackorderQuantity"   // signed, scale 0
        const val Q = "/Order/Quantity"             // signed, scale 0
        const val SOH = "/Order/StockOnHand"        // UNSIGNED, scale 0
        const val UP = "/Order/UnitPrice"           // signed, scale 2
        const val ANCHOR = "/Order/CustomerName"     // filled string, makes the row non-empty (unreferenced)
        const val COMP = "AFComp"                    // the injected computation's name = its implicit-validation code
    }

    // ── + : combines both operands' directions ──────────────────────────────────────────────────────────────
    // (Q signed-empty + SOH unsigned-empty) = (grow,shrink) → both directions fillable.
    @Test fun addBothEmptyCanGrow() =
        agree(scenario(render(n(Q).plus(n(SOH)).lt(100)), "../Quantity", mapOf(), listOf(Q, SOH)),
            "OMISSION_ERROR", "(Q+SOH) < 100 (both empty): filling grows it toward 100 → OMISSION")
    @Test fun addBothEmptyCanShrink() =
        agree(scenario(render(n(Q).plus(n(SOH)).gt(-100)), "../Quantity", mapOf(), listOf(Q, SOH)),
            "OMISSION_ERROR", "(Q+SOH) > -100: the SIGNED empty can push it below -100 → OMISSION")
    // + of an UNSIGNED-empty alone: can grow, cannot shrink.
    @Test fun addUnsignedEmptyCanGrow() =
        agree(scenario(render(n(SOH).plus(5L).lt(100)), "../StockOnHand", mapOf(), listOf(SOH)),
            "OMISSION_ERROR", "(SOH+5) < 100: filling SOH grows it → OMISSION")
    @Test fun addUnsignedEmptyCannotShrink() =
        agree(scenario(render(n(SOH).plus(5L).gt(-100)), "../StockOnHand", mapOf(), listOf(SOH)),
            "VALUE_ERROR", "(SOH+5) > -100: an unsigned empty can't shrink it → VALUE")

    // ── − : the subtrahend's direction FLIPS ────────────────────────────────────────────────────────────────
    // (BQ filled − SOH unsigned-empty): filling SOH can only SHRINK the difference.
    @Test fun subUnsignedSubtrahendCanShrink() =
        agree(scenario(render(n(BQ).minus(n(SOH)).gt(-100)), "../BackorderQuantity", mapOf(BQ to "5"), listOf(SOH)),
            "OMISSION_ERROR", "(BQ−SOH) > -100: filling SOH shrinks it below -100 → OMISSION")
    @Test fun subUnsignedSubtrahendCannotGrow() =
        agree(scenario(render(n(BQ).minus(n(SOH)).lt(100)), "../BackorderQuantity", mapOf(BQ to "5"), listOf(SOH)),
            "VALUE_ERROR", "(BQ−SOH) < 100: subtracting an unsigned empty can't grow it → VALUE")

    // ── × : SIGN-DEPENDENT (the decisive test: same operand fill, direction flips with the other factor's sign) ─
    @Test fun mulUnsignedEmptyByPositiveCanGrow() =
        agree(scenario(render(n(SOH).times(n(BQ)).lt(100)), "../StockOnHand", mapOf(BQ to "4"), listOf(SOH)),
            "OMISSION_ERROR", "(SOH×4) < 100: filling SOH grows the product → OMISSION")
    @Test fun mulUnsignedEmptyByPositiveCannotShrink() =
        agree(scenario(render(n(SOH).times(n(BQ)).gt(-100)), "../StockOnHand", mapOf(BQ to "4"), listOf(SOH)),
            "VALUE_ERROR", "(SOH×4) > -100: ×positive can't shrink → VALUE")
    @Test fun mulUnsignedEmptyByNegativeCanShrink() =
        agree(scenario(render(n(SOH).times(n(BQ)).gt(-100)), "../StockOnHand", mapOf(BQ to "-4"), listOf(SOH)),
            "OMISSION_ERROR", "(SOH×-4) > -100: filling SOH pushes the product negative → OMISSION")
    @Test fun mulUnsignedEmptyByNegativeCannotGrow() =
        agree(scenario(render(n(SOH).times(n(BQ)).lt(100)), "../StockOnHand", mapOf(BQ to "-4"), listOf(SOH)),
            "VALUE_ERROR", "(SOH×-4) < 100: ×negative can't grow → VALUE")

    // ── ÷ : a ÷ b = a × (1/b) ───────────────────────────────────────────────────────────────────────────────
    @Test fun divUnsignedEmptyByPositiveCanGrow() =
        agree(scenario(render(n(SOH).dividedBy(n(BQ)).lt(100)), "../StockOnHand", mapOf(BQ to "4"), listOf(SOH)),
            "OMISSION_ERROR", "(SOH÷4) < 100: filling SOH grows it → OMISSION")
    @Test fun divUnsignedEmptyByNegativeCanShrink() =
        agree(scenario(render(n(SOH).dividedBy(n(BQ)).gt(-100)), "../StockOnHand", mapOf(BQ to "-4"), listOf(SOH)),
            "OMISSION_ERROR", "(SOH÷-4) > -100: filling SOH pushes it negative → OMISSION")

    // ── ^ : powCanChange ────────────────────────────────────────────────────────────────────────────────────
    // signed-empty base ^ 2 (fixed even exponent): result ≥ 0, can grow, cannot shrink below 0.
    @Test fun powEvenExponentSignedEmptyBaseCanGrow() =
        agree(scenario(render(n(Q).toThePowerOf(n(BQ)).lt(100)), "../Quantity", mapOf(BQ to "2"), listOf(Q)),
            "OMISSION_ERROR", "(Q^2) < 100: filling Q grows the square → OMISSION")
    @Test fun powEvenExponentSignedEmptyBaseCannotShrink() =
        agree(scenario(render(n(Q).toThePowerOf(n(BQ)).gt(-100)), "../Quantity", mapOf(BQ to "2"), listOf(Q)),
            "VALUE_ERROR", "(Q^2) > -100: a square is ≥ 0, can't shrink below → VALUE")
    // base > 1 (fixed) ^ empty-signed exponent (variable): monotone rising in the exponent → grow & shrink.
    @Test fun powFixedBaseGtOneVariableExponent() =
        agree(scenario(render(n(BQ).toThePowerOf(n(Q)).lt(100)), "../BackorderQuantity", mapOf(BQ to "5"), listOf(Q)),
            "OMISSION_ERROR", "(5^Q) < 100: raising the empty exponent grows it → OMISSION")
    // ODD fixed exponent over a signed-empty base: an odd power keeps the base's sign, so it CAN go negative →
    // `> -100` is OMISSION (contrast the even square, which is ≥ 0 → VALUE) — the powCanChangeWithFixedExponent
    // parity branch.
    @Test fun powOddExponentSignedEmptyBaseCanShrink() =
        agree(scenario(render(n(Q).toThePowerOf(n(BQ)).gt(-100)), "../Quantity", mapOf(BQ to "3"), listOf(Q)),
            "OMISSION_ERROR", "(Q^3) > -100: an odd power of a signed-empty base can go negative → OMISSION")
    // fixed NEGATIVE base ^ variable (empty) exponent — powCanChangeWithFixedNegativeBase.
    @Test fun powFixedNegativeBaseVariableExponent() =
        agree(scenario(render(n(BQ).toThePowerOf(n(Q)).lt(100)), "../BackorderQuantity", mapOf(BQ to "-2"), listOf(Q)),
            "OMISSION_ERROR", "(-2^Q) < 100: the empty exponent makes it fillable → OMISSION")
    // fixed base 0<a<1 ^ variable (empty) exponent — powCanChangeWithFixedBase, monotone-falling branch.
    @Test fun powFixedFractionBaseVariableExponent() =
        agree(scenario(render(n(UP).toThePowerOf(n(Q)).lt(100)), "../UnitPrice", mapOf(UP to "0.50"), listOf(Q)),
            "OMISSION_ERROR", "(0.50^Q) < 100: fillable via the empty exponent → OMISSION")
    // everything-variable: both base and exponent empty — powCanChangeWithEverythingVariable.
    @Test fun powEverythingVariable() =
        agree(scenario(render(n(Q).toThePowerOf(n(SOH)).lt(100)), "../Quantity", mapOf(), listOf(Q, SOH)),
            "OMISSION_ERROR", "(Q^SOH) < 100 (both empty): fillable → OMISSION")

    // 0^empty reads 0^0=1 in both cases, but the conservative fill bits intentionally split by exponent sign.
    @Test fun powZeroToEmptyUnsignedExponentIsOmission() =
        agreeBothStrategies(
            scenario(render(Dsl.number(0).toThePowerOf(n(SOH)).gt(0)), "../StockOnHand", mapOf(), listOf(SOH)),
            "OMISSION_ERROR",
            "0^empty-unsigned > 0: exponent is grow-only, so the zero-base branch is shrink-only",
        )

    @Test fun powZeroToEmptySignedExponentIsValue() =
        agreeBothStrategies(
            scenario(render(Dsl.number(0).toThePowerOf(n(Q)).gt(0)), "../Quantity", mapOf(), listOf(Q)),
            "VALUE_ERROR",
            "0^empty-signed > 0: a shrinkable exponent selects grow-only, which cannot clear >",
        )

    // ── nested arithmetic: fill propagates through the tree ─────────────────────────────────────────────────
    @Test fun nestedAddTimesCanGrow() =
        agree(scenario(render(n(Q).plus(n(SOH)).times(n(BQ)).lt(100)), "../Quantity", mapOf(BQ to "3"), listOf(Q, SOH)),
            "OMISSION_ERROR", "((Q+SOH)×3) < 100: fill flows through nesting → OMISSION")

    // ── the per-computation implicit validation ([F] != <operation>) — IF69 surfaces here widely (IF65) ─────
    @Test fun computationImplicitValidationOmissionRidesArithFill() =
        compAgree(compScenario("[Quantity] + [StockOnHand]", stored = "10", filled = mapOf(Q to "3"), empty = listOf(SOH)),
            "OMISSION_ERROR", "computed Back=[Quantity]+[StockOnHand]: stored 10 ≠ 3, RHS can grow → OMISSION")
    @Test fun computationImplicitValidationValueControl() =
        compAgree(compScenario("[Quantity] + [StockOnHand]", stored = "1", filled = mapOf(Q to "3"), empty = listOf(SOH)),
            "VALUE_ERROR", "computed Back: stored 1 < 3 and the unsigned RHS can't shrink → VALUE")

    // ── both strategies: the arithmetic is runtime-shared; static-JAVA codegen must agree ───────────────────
    @Test fun codegenPropagatesArithmeticFillIdentically() {
        val s = scenario(render(n(SOH).times(n(BQ)).lt(100)), "../StockOnHand", mapOf(BQ to "4"), listOf(SOH))
        val groovy = kType(s)
        val engine = RuntimeLaws.kernelJavaEngine(s.kModel)
        val javaStatic = engine.messages(engine.instance(s.kDoc)).firstOrNull { it.code() == CODE }?.type()?.name
        assertEquals(groovy, iType(s), "codegen (interp≠groovy) groovy=$groovy interp=${iType(s)}")
        assertEquals(groovy, javaStatic, "codegen (java-static≠groovy) groovy=$groovy java=$javaStatic")
    }
}
