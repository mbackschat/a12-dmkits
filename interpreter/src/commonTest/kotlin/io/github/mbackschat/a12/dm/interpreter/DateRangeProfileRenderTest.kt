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
 * Multiplatform (JVM + Node) peer for SPEC-2026-08-19-03/-05: what a DATE_RANGE computation STORES, across all
 * eight `(format, separator)` declaration pairs kernel 30.8.1 admitted (KERNEL-FINDINGS KF81); 31.1.1 adds
 * their empty-separator twins, which read as `/` (KF658). The kernel side is
 * `adapter.laws.DateRangeFirstFilledProfileDiffTest` and
 * `ConstructedRangeTargetDiffTest`; those run only on the JVM, and the ledger entries ask for the JVM/Node
 * route as well.
 *
 * The load-bearing rows are the yearless ones. The evaluator carries a range as two completed ISO instants, so
 * it *does* invent a base year internally on an `MM` value; the target renderer then writes through the
 * target's declared format, which has no year run, so the invented year cannot reach the document. The last
 * row is the discriminator for that mechanism: with source and target in DIFFERENT lexical formats of the same
 * component set, a source-keyed renderer would store the source's spelling.
 */
class DateRangeProfileRenderTest {

    private val SOURCE = "/Cover/Source"
    private val TARGET = "/Cover/Target"

    /** One `FirstFilledValue(Source)` computation from a range field into a range target. */
    private fun stored(
        sourceFormat: String,
        sourceSeparator: String,
        targetFormat: String,
        targetSeparator: String,
        value: String,
    ): String {
        val def = ModelDef(
            listOf(
                FieldDef(SOURCE, Kind.DATE_RANGE, format = sourceFormat, rangeSeparator = sourceSeparator),
                FieldDef(TARGET, Kind.DATE_RANGE, format = targetFormat, rangeSeparator = targetSeparator),
            ),
            emptyList(),
            computations = listOf(ComputationDef(TARGET, "/Cover", "FirstFilledValue($SOURCE)")),
        )
        val doc = def.newDocument().group("/Cover", 1).field(SOURCE, value, 1, 1)
        val computed = DmInterpreter(def).compute(doc).firstOrNull { it.path == TARGET }
        return when (computed?.outcome) {
            null -> "NONE"
            ComputeOutcome.CLEARED -> "CLEARED"
            ComputeOutcome.ERRORED -> "ERRORED|${computed.value}"
            ComputeOutcome.VALUE -> "VALUE|${computed.value}"
        }
    }

    private fun sameProfile(format: String, separator: String, value: String) =
        stored(format, separator, format, separator, value)

    @Test fun anExactIsoWindowRetainsItsEndpoints() =
        assertEquals("VALUE|2024-06-01/2024-06-30",
            sameProfile("yyyy-MM-dd", "/", "2024-06-01/2024-06-30"))

    @Test fun anExactEuropeanWindowRetainsItsEndpoints() =
        assertEquals("VALUE|01.06.2024-30.06.2024",
            sameProfile("dd.MM.yyyy", "-", "01.06.2024-30.06.2024"))

    @Test fun aYearWindowRetainsItsYears() =
        assertEquals("VALUE|2024/2025", sameProfile("yyyy", "/", "2024/2025"))

    @Test fun aYearMonthWindowRetainsItsMonths() =
        assertEquals("VALUE|2024-02/2024-03", sameProfile("yyyy-MM", "/", "2024-02/2024-03"))

    /** No base year is declared, and none appears in the stored result. */
    @Test fun aYearlessMonthWindowRetainsItsComponentPair() =
        assertEquals("VALUE|06/09", sameProfile("MM", "/", "06/09"))

    @Test fun aYearlessMonthDayWindowRetainsItsComponentPair() =
        assertEquals("VALUE|06-01/09-30", sameProfile("MM-dd", "/", "06-01/09-30"))

    /** `MM` with an empty separator, whose stored value is halved. */
    @Test fun aHalvedMonthPairRetainsItsComponentPair() =
        assertEquals("VALUE|0609", sameProfile("MM", "", "0609"))

    /** The eighth pair, `dd.MM`+`-`: day and month on both sides, and again no invented year. */
    @Test fun aYearlessDayMonthWindowRetainsItsComponentPair() =
        assertEquals("VALUE|01.06-30.09", sameProfile("dd.MM", "-", "01.06-30.09"))

    /** The discriminator: same component set, different spelling — the TARGET's format wins. */
    @Test fun theTargetFormatDecidesTheStoredForm() =
        assertEquals("VALUE|2024-06-01/2024-06-30",
            stored("dd.MM.yyyy", "-", "yyyy-MM-dd", "/", "01.06.2024-30.06.2024"))
}
