package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM + JS) lock for IF92 — the computed-NUMBER store path at the >15-digit and scale-no-fit
 * edges (the kernel's `CalculationController.handleBerechnetenWert` rule): a stored form is NEVER re-rounded to
 * fit the 15-digit check — the fit arm renders `max(naturalScale, minFractionalDigits)` uncapped (a >15-digit
 * string is ERRORED with the FULL value), the no-fit arm (natural scale > the target's maxFractionalDigits —
 * authorable only via the `errorCodesToSuppress` scale-gate suppression) renders length-bounded to 16
 * significant digits after the scale-19 store pre-round and is ERRORED unconditionally. Every expectation here
 * is kernel-verified by the quad-differential `adapter.laws.ComputedNumberDigitOverflowDiffTest`
 * (groovy-legacy == groovy-modern == java-static == interpreter).
 */
class ComputedNumberDigitOverflowTest {

    private fun model(op: String, minFrac: Int, maxFrac: Int) = ModelDef(
        fields = listOf(
            FieldDef("/Order/UnitFactor", Kind.NUMBER, scale = 2),
            FieldDef("/Order/BulkFactor", Kind.NUMBER, scale = 2),
            FieldDef("/Order/Projected", Kind.NUMBER, scale = maxFrac, minFractionalDigits = minFrac),
        ),
        rules = emptyList(),
        computations = listOf(ComputationDef("/Order/Projected", "/Order", op)),
    )

    private fun projected(op: String, minFrac: Int, maxFrac: Int, unit: String?, bulk: String?): Pair<ComputeOutcome?, String?> {
        val m = model(op, minFrac, maxFrac)
        val doc = m.newDocument().group("/Order", 1)
        unit?.let { doc.field("/Order/UnitFactor", it, 1, 1) }
        bulk?.let { doc.field("/Order/BulkFactor", it, 1, 1) }
        val c = DmInterpreter(m).compute(doc).firstOrNull { it.path == "/Order/Projected" }
        return c?.outcome to c?.value
    }

    private val fifth = "RoundAccounting([UnitFactor] * [UnitFactor] * [UnitFactor] * [UnitFactor] * [UnitFactor], 2)"

    // ── the FIT arm: > 15 stored digits is ERRORED with the FULL value (no re-rounding cap) ─────────────

    @Test fun productOverflowingFifteenDigitsIsErroredWithFullValue() =
        assertEquals(ComputeOutcome.ERRORED to "354027231847478.01",
            projected(fifth, 0, 2, "812.47", null), "17 stored digits: ERRORED, full value kept (the R1 atom)")

    @Test fun seventeenDigitScaleThreeKeepsItsScale() =
        assertEquals(ComputeOutcome.ERRORED to "97263364273283.796",
            projected("RoundAccounting([UnitFactor] * [BulkFactor], 3)", 0, 3, "9812345.67", "9912345.89"),
            "the second-instance arm: the old cap clipped to scale 1 and reported VALUE")

    @Test fun sixteenIntegerDigitsKeepTheMinPad() =
        assertEquals(ComputeOutcome.ERRORED to "9726336238030108.21",
            projected("RoundAccounting([UnitFactor] * [BulkFactor], 2)", 2, 2, "98123456.78", "99123456.89"),
            "18 stored digits (min-2 pad kept): ERRORED with the kernel's full form")

    @Test fun negativeSignIsNotADigit() =
        assertEquals(ComputeOutcome.ERRORED to "-354027231847478.01",
            projected(fifth, 0, 2, "-812.47", null), "17 digits + sign: ERRORED with the full negative value")

    @Test fun fifteenDigitBoundaryStaysValue() =
        assertEquals(ComputeOutcome.VALUE to "9726336339017.27",
            projected("RoundAccounting([UnitFactor] * [BulkFactor], 2)", 0, 2, "9812345.67", "991234.58"),
            "exactly 15 digits: VALUE — the uncapped render must not over-ERRORED")

    // ── the NO-FIT arm: natural scale > maxFractionalDigits → length-bounded(16) render, ERRORED always ──

    @Test fun noFitSmallProductStoresPlain() =
        assertEquals(ComputeOutcome.ERRORED to "2.4723",
            projected("[UnitFactor] * [BulkFactor]", 0, 2, "1.23", "2.01"),
            "scale-4 product into maxFrac-2: stored canonical (≤ 16 significant digits), ERRORED always")

    @Test fun noFitLargeProductLengthBoundsToSixteenSignificantDigits() =
        assertEquals(ComputeOutcome.ERRORED to "972633643525825.6",
            projected("[UnitFactor] * [BulkFactor]", 0, 2, "98123456.78", "9912345.89"),
            "19 significant digits: bounded to 16, HALF_UP")

    @Test fun noFitQuotientPreRoundsToScaleNineteen() =
        assertEquals(ComputeOutcome.ERRORED to "3.333333333333333",
            projected("[UnitFactor] / [BulkFactor]", 0, 2, "10.00", "3.00"),
            "the store pre-rounds the long quotient to scale 19 FIRST, then bounds to 16 significant digits")
}
