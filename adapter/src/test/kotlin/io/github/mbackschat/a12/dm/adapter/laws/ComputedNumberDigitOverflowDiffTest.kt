package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.GroupBuilder
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.function.Consumer

/**
 * Differential + QUAD-check for the computed-NUMBER STORE path at the >15-digit and scale-no-fit edges (the
 * IG44 "R1 arithmetic residual" — which turned out to be no arithmetic divergence at all). The kernel's store
 * formatter for a computed number is `CalculationController.handleBerechnetenWert(VkBigDecimal, id)` — shared
 * by BOTH strategies (the codegen templates emit calls to the same controller):
 *
 *  1. store-time pre-round to scale 19 HALF_UP (`VkBigDecimal.DEFAULT_SCALE`);
 *  2. **fit** (natural scale ≤ the target's `maxFractionalDigits`): render at
 *     `max(naturalScale, minFractionalDigits)` with **NO length cap** — a > 15-digit stored string then trips
 *     `stelligkeitZuLang` in the formal check and the cell is ERRORED with the FULL value stored;
 *  3. **no-fit** (natural scale > `maxFractionalDigits`): render length-bounded to 16 significant digits
 *     (`BigDecimalUtils.toStringWertLaengenBeschraenkt(v19, 16)`) and mark "fehlerhaft berechnet" — ERRORED
 *     always, value stored.
 *
 * The interpreter previously rendered via the V2 `NumberConversion.determineFractionalDigits` 15-digit CAP — a
 * formatter NEITHER kernel compute strategy uses at this edge — which silently RE-ROUNDED a 15+-integer-digit
 * value to a smaller scale, so it passed the digit check and reported VALUE where the kernel stores the full
 * string and reports ERRORED (the R1 sweep atom `kernel=ERRORED,interp=VALUE`; both engines' pre-render
 * NUMERIC value is identical — the divergence is purely the stored form).
 *
 * The sig here carries the ERRORED stored value too (`ERRORED|<value>` — the kernel exposes it via
 * `getComputedFieldInstancesWithErrors`), pinning the value-drift half of the class that a kind-only compare
 * hides. Own-domain (e-commerce): an `Order` projecting a bulk value from unit factors.
 */
class ComputedNumberDigitOverflowDiffTest {

    private val UNIT = "/Order/UnitFactor"
    private val BULK = "/Order/BulkFactor"
    private val PROJECTED = "/Order/Projected"

    /** An order with two scale-2 factor inputs and a `Projected` target at the given (minFrac, maxFrac). */
    private fun base(minFrac: Int, maxFrac: Int) = DocumentModelDraft.named("order").locale("en_US")
        .rootGroup("Order", Consumer { g: GroupBuilder ->
            g.numberField("UnitFactor", Consumer { it.fractionDigits(2) })
                .numberField("BulkFactor", Consumer { it.fractionDigits(2) })
                .numberField("Projected", Consumer { if (minFrac > 0) it.fractionDigits(minFrac, maxFrac) else it.fractionDigits(maxFrac) })
        }).build().toJson()

    private val bound = BoundModel.ofJson(base(0, 2))
    private val unit = bound.num(UNIT).asNumber()
    private val bulk = bound.num(BULK).asNumber()

    /** `Projected` computed by [op] with the given target `(minFrac, maxFrac)`. [suppressScaleGate] declares
     *  `errorCodesToSuppress: [MVK_INVALID_COMPARE_DEC_PLACES]` — the kernel's own escape hatch for the static
     *  assignment-scale gate, the ONLY way a scale-mismatched operation (the runtime no-fit arm) can load. */
    private fun model(op: NumberExpr, minFrac: Int, maxFrac: Int, suppressScaleGate: Boolean = false): String {
        val rendered = RuntimeLaws.render("/Order", op.expression())
        val suppress = if (suppressScaleGate) listOf("MVK_INVALID_COMPARE_DEC_PLACES") else listOf()
        return RuntimeLaws.withComputation(base(minFrac, maxFrac), "/Order", "ProjectedComp", "../Projected", rendered, suppress)
    }

    /** Outcome sig CARRYING the stored value for ERRORED too (the value-drift half of the class). */
    private fun sig(c: RuntimeLaws.Computed?): String = when {
        c == null -> "NONE"
        c.cleared() -> "CLEARED"
        c.errored() -> "ERRORED|${c.value().orElse("")}"
        else -> "VALUE|${c.value().orElse("")}"
    }

    private fun kdoc(unitVal: String?, bulkVal: String?): RuntimeLaws.Doc {
        val d = RuntimeLaws.doc().group("/Order", 1)
        unitVal?.let { d.field(UNIT, it, 1, 1) }
        bulkVal?.let { d.field(BULK, it, 1, 1) }
        return d
    }

    private fun iOutcome(model: String, unitVal: String?, bulkVal: String?): String {
        val def = ModelLoader.load(model)
        val doc = def.newDocument().group("/Order", 1)
        unitVal?.let { doc.field(UNIT, it, 1, 1) }
        bulkVal?.let { doc.field(BULK, it, 1, 1) }
        val c = DmInterpreter(def).compute(doc).firstOrNull { it.path == PROJECTED }
        return when (c?.outcome) {
            null -> "NONE"; ComputeOutcome.CLEARED -> "CLEARED"
            ComputeOutcome.ERRORED -> "ERRORED|${c.value}"
            ComputeOutcome.VALUE -> "VALUE|${c.value}"
        }
    }

    /** legacy-compute == modern-computeModern == java-static == interpreter, and all equal [expected]. */
    private fun quad(op: NumberExpr, minFrac: Int, maxFrac: Int, unitVal: String?, bulkVal: String?,
                     expected: String, msg: String, suppressScaleGate: Boolean = false) {
        val m = model(op, minFrac, maxFrac, suppressScaleGate)
        val legacy = sig(RuntimeLaws.compute(m, kdoc(unitVal, bulkVal)).firstOrNull { it.path() == PROJECTED })
        val modern = sig(RuntimeLaws.computeModern(m, kdoc(unitVal, bulkVal), null).firstOrNull { it.path() == PROJECTED })
        val engine = RuntimeLaws.kernelJavaEngine(m)
        val javaStatic = sig(engine.compute(engine.instance(kdoc(unitVal, bulkVal))).firstOrNull { it.path() == PROJECTED })
        val interp = iOutcome(m, unitVal, bulkVal)
        assertEquals(legacy, modern, "$msg — legacy compute vs modern computeModern")
        assertEquals(legacy, javaStatic, "$msg — groovy vs java-static")
        assertEquals(expected, legacy, "$msg — kernel stored form")
        assertEquals(legacy, interp, "$msg — kernel vs interpreter")
    }

    /** The product-of-many R1 shape: `[UnitFactor]` to the 5th power as products. */
    private fun unitToTheFifth(): NumberExpr = unit.times(unit).times(unit).times(unit).times(unit)

    // ── the FIT arm: > 15 stored digits is ERRORED with the FULL value (no re-rounding cap) ─────────────

    @Test fun productOverflowsFifteenDigitsIsErroredWithFullValue() =
        quad(Dsl.roundAccounting(unitToTheFifth(), 2), 0, 2, "812.47", null,
            "ERRORED|354027231847478.01",
            "RoundAccounting(F^5, 2), F=812.47 → 17 stored digits: ERRORED, full value kept (the R1 atom)")

    @Test fun secondInstanceCapToOneArm() =
        quad(Dsl.roundAccounting(unit.times(bulk), 3), 0, 3, "9812345.67", "9912345.89",
            "ERRORED|97263364273283.796",
            "RoundAccounting(A*B, 3) at 14 integer digits → 17 stored digits: ERRORED, full scale-3 value (the predicted second instance — the old cap clipped to scale 1)")

    @Test fun sixteenIntegerDigitsErroredValueDrift() =
        quad(Dsl.roundAccounting(unit.times(bulk), 2), 2, 2, "98123456.78", "99123456.89",
            "ERRORED|9726336238030108.21",
            "RoundAccounting(A*B, 2) at 16 integer digits, min=2 → 18 stored digits: ERRORED both, but the stored VALUE must be the kernel's full form (the old cap stored the re-rounded whole)")

    @Test fun negativeSignExcludedFromDigitCount() =
        quad(Dsl.roundAccounting(unitToTheFifth(), 2), 0, 2, "-812.47", null,
            "ERRORED|-354027231847478.01",
            "RoundAccounting((-F)^5, 2) → 17 digits + sign: the sign is not a digit; ERRORED with the full negative value")

    @Test fun fifteenDigitBoundaryStaysValue() =
        quad(Dsl.roundAccounting(unit.times(bulk), 2), 0, 2, "9812345.67", "991234.58",
            "VALUE|9726336339017.27",
            "RoundAccounting(A*B, 2) at exactly 15 digits → VALUE, identical stored form (the fix must not over-ERRORED)")

    // ── the NO-FIT arm: natural scale > maxFractionalDigits renders length-bounded(16) and is ALWAYS ERRORED.
    // Reachable on a legal model ONLY via the computation's `errorCodesToSuppress` scale-gate suppression —
    // without it the codegen REJECTS the scale-mismatched assignment (MVK_INVALID_COMPARE_DEC_PLACES, an ERROR
    // for computations; the withoutSuppression control pins that) ─────────────────────────────────────────────

    @Test fun noFitSmallProductStoresPlainAndErrors() =
        quad(unit.times(bulk), 0, 2, "1.23", "2.01",
            "ERRORED|2.4723",
            "[A]*[B] (scale-4 product) into a maxFrac-2 target → no-fit: stored plain (precision ≤ 16), ERRORED always",
            suppressScaleGate = true)

    @Test fun noFitLargeProductStoresLengthBounded16() =
        quad(unit.times(bulk), 0, 2, "98123456.78", "9912345.89",
            "ERRORED|972633643525825.6",
            "[A]*[B] (scale-4, 19 significant digits) into a maxFrac-2 target → no-fit: length-bounded to 16 significant digits HALF_UP",
            suppressScaleGate = true)

    @Test fun noFitQuotientPreRoundsToScale19() =
        quad(unit.dividedBy(bulk), 0, 2, "10.00", "3.00",
            "ERRORED|3.333333333333333",
            "[A]/[B] = 3.3̅ (MathContext-50 quotient, scale 49) into a maxFrac-2 target → store pre-rounds to scale 19 FIRST, then length-bounds to 16 significant digits",
            suppressScaleGate = true)

    /** The reachability control: WITHOUT the suppression the codegen REJECTS the scale-mismatched assignment —
     *  `MVK_INVALID_COMPARE_DEC_PLACES` is an ERROR for a computation (the implicit `[target] != <op>` check),
     *  so the no-fit arm is unreachable on an unsuppressed model. */
    @Test fun scaleMismatchedAssignmentRejectedWithoutSuppression() {
        val m = model(unit.times(bulk), 0, 2)
        val thrown = Assertions.assertThrows(Exception::class.java) {
            RuntimeLaws.compute(m, kdoc("1.23", "2.01"))
        }
        Assertions.assertTrue(
            thrown.message.orEmpty().contains("MVK_INVALID_COMPARE_DEC_PLACES"),
            "expected the static scale gate, got: ${thrown.message}")
    }
}
