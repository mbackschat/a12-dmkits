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
 * Multiplatform (JVM + JS) lock for IF78: a computation over a FILLED operand that is "not check-relevant"
 * (formally invalid — ANY formal-error class, not just the value-domain subset IF76 first gated) is CLEARED,
 * matching the kernel, which treats any formally-invalid operand as `nichtPruefungsrelevant`. An EMPTY operand
 * — even required — stays empty-as-0 (computes). The kernel differential (both strategies) is
 * `adapter.laws.ComputeInvalidOperandDiffTest`; this pins the interpreter's compute outcome on both platforms.
 */
class ComputeOperandInvalidityTest {

    private val P = "/Order/Price"      // computed target, scale 2
    private val N = "/Order/N"          // number operand

    private fun computeNumber(operand: FieldDef, value: String?): ComputeOutcome {
        val m = ModelDef(
            listOf(FieldDef(P, Kind.NUMBER, scale = 2), operand),
            emptyList(),
            computations = listOf(ComputationDef(P, "/Order", "RoundDown([N], 2)")),
        )
        val doc = m.newDocument().group("/Order", 1).field(P, "0.01", 1, 1)
        if (value != null) doc.field(N, value, 1, 1)
        return DmInterpreter(m).compute(doc).first { it.path == P }.outcome
    }

    // ── valid + empty controls ──
    @Test fun validIntegerComputes() =
        assertEquals(ComputeOutcome.VALUE, computeNumber(FieldDef(N, Kind.NUMBER, scale = 0), "5"), "5 valid → VALUE")
    @Test fun emptyOperandComputesEmptyAsZero() =
        assertEquals(ComputeOutcome.VALUE, computeNumber(FieldDef(N, Kind.NUMBER, scale = 0), null), "empty → empty-as-0 → VALUE")

    // ── the codes IF76's narrow gate MISSED (now cleared, IF78) ──
    @Test fun decimalInIntegerClears() =   // scale-0 field, decimal value → zahlHatDezimalTrenner
        assertEquals(ComputeOutcome.CLEARED, computeNumber(FieldDef(N, Kind.NUMBER, scale = 0), "5.0"), "decimal-in-integer → CLEARED")
    @Test fun integerCapClears() =         // maxIntegerDigits 3, 4-digit value → zahlZuvieleVorkommastellen
        assertEquals(ComputeOutcome.CLEARED, computeNumber(FieldDef(N, Kind.NUMBER, scale = 0, maxIntegerDigits = 3), "1234"), "integer-cap → CLEARED")
    @Test fun zeroNotAllowedClears() =     // zahlIstNull
        assertEquals(ComputeOutcome.CLEARED, computeNumber(FieldDef(N, Kind.NUMBER, scale = 0, zeroNotAllowed = true), "0"), "zero-not-allowed → CLEARED")
    @Test fun numberTooLongClears() =      // numberMaxLength 2 → zahlZuLang
        assertEquals(ComputeOutcome.CLEARED, computeNumber(FieldDef(N, Kind.NUMBER, scale = 0, numberMaxLength = 2), "555"), "number-too-long → CLEARED")

    // ── value-domain codes IF76 already cleared (regression guard) ──
    @Test fun belowMinValueClears() =
        assertEquals(ComputeOutcome.CLEARED, computeNumber(FieldDef(N, Kind.NUMBER, scale = 0, minValue = "10", maxValue = "20"), "5"), "below-min → CLEARED")
}
