package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — **what the distinct count and the extrema do with a COMPONENT-OMITTING temporal operand at
 * runtime**. [KF253]'s fold measurement carries complete-date spellings only; the admission side
 * (`TemporalAggregateComponentSetLawsTest`) proves a `yyyy-MM` or a yearless `MM` list is legal, so the
 * runtime rows below are constructible and were simply never run.
 *
 * <p><b>The answer is the shared declared component set, at both families.</b> A `yyyy-MM` pair folds
 * `2024-03` with `2024-03` as one value and separates it from `2024-04`; a yearless `MM` pair does the same,
 * and orders, with **no Base Year declared** — so nothing here needs a completion anchor to be well defined.
 *
 * <p><b>The reversed document is the separator, and without it these are four conditions that happen to
 * hold.</b> Every pair is swapped and no ordering rule fires at all — which no constant-true reading can
 * produce. The ascending row alone would be consistent with an extremum that ignores its operands.
 *
 * <p><b>Supplementation is by the DECLARED Base Year, not by the sibling operand's year</b>, and the
 * other-year row is what separates those: under `baseYear 2024`, a yearless `03` beside `2023-03` counts as
 * **two** values and the extremum puts the 2023 operand first. A sibling-supplied year would make them equal
 * and count one.
 *
 * <p>Reported by a12-kernel-lean as `SPEC-2026-09-06-08` (the distinct count) and `SPEC-2026-09-06-10` (the
 * extrema); measured here on our own fixture, on the kernel and the interpreter.
 *
 * <p>Own domain (an insurance renewal window).
 */
class ComponentOmittingTemporalFoldDiffTest {

    private companion object {
        const val GROUP = "/Cover"
        const val HOLDER = "/Cover/Holder"
        const val PERIOD_A = "/Cover/PeriodA"
        const val PERIOD_B = "/Cover/PeriodB"
        const val MONTH_A = "/Cover/MonthA"
        const val MONTH_B = "/Cover/MonthB"
        const val FULL_A = "/Cover/FullA"
        const val FULL_B = "/Cover/FullB"
    }

    /**
     * `yyyy-MM` and yearless `MM` pairs beside a complete-date control. The Base-Year variant is the SAME
     * draft with one extra builder call, so the two models differ in exactly that.
     */
    private fun cover(baseYear: Int?): String {
        val builder = DocumentModelDraft.named("renewal-window").locale("en_US")
        if (baseYear != null) {
            builder.baseYear(baseYear)
        }
        return builder.rootGroup("Cover") { g ->
            g.stringField("Holder") { }
            g.dateField("PeriodA") { d -> d.format("yyyy-MM") }
            g.dateField("PeriodB") { d -> d.format("yyyy-MM") }
            g.dateField("MonthA") { d -> d.format("MM") }
            g.dateField("MonthB") { d -> d.format("MM") }
            g.dateField("FullA") { d -> d.format("yyyy-MM-dd") }
            g.dateField("FullB") { d -> d.format("yyyy-MM-dd") }
        }.build().toJson()
    }

    private val noBaseYear: String = cover(null)
    private val withBaseYear: String = cover(2024)

    private fun withRule(model: String, code: String, condition: String): String =
        RuntimeLaws.withRule(model, GROUP, "R$code", "../Holder", code,
            "FieldFilled(Holder) And $condition")

    private class Cells(val values: Map<String, String>)

    private fun kernelFires(model: String, code: String, condition: String, cells: Cells): Boolean {
        val doc = RuntimeLaws.doc().group(GROUP, 1)
        doc.field(HOLDER, "Northwind Freight", 1, 1)
        cells.values.forEach { (path, value) -> doc.field(path, value, 1, 1) }
        return code in RuntimeLaws.firedCodes(withRule(model, code, condition), doc)
    }

    private fun interpreterFires(model: String, code: String, condition: String, cells: Cells): Boolean {
        val def = ModelLoader.load(withRule(model, code, condition))
        val doc = def.newDocument().group(GROUP, 1)
        doc.field(HOLDER, "Northwind Freight", 1, 1)
        cells.values.forEach { (path, value) -> doc.field(path, value, 1, 1) }
        return code in DmInterpreter(def).validate(doc).messages.map { it.code }.toSet()
    }

    /** Fires on BOTH engines, or on neither; a split is the finding and is never merged away. */
    private fun bothFire(model: String, code: String, condition: String, cells: Cells): Boolean {
        val kernel = kernelFires(model, code, condition, cells)
        val interpreter = interpreterFires(model, code, condition, cells)
        assertEquals(kernel, interpreter, "engines disagree on '$condition' over ${cells.values}")
        return kernel
    }

    private fun ascending() = Cells(
        mapOf(
            PERIOD_A to "2024-03", PERIOD_B to "2024-04",
            MONTH_A to "03", MONTH_B to "04",
            FULL_A to "2024-03-05", FULL_B to "2024-04-05",
        ),
    )

    private fun reversed() = Cells(
        mapOf(
            PERIOD_A to "2024-04", PERIOD_B to "2024-03",
            MONTH_A to "04", MONTH_B to "03",
            FULL_A to "2024-04-05", FULL_B to "2024-03-05",
        ),
    )

    private fun equal() = Cells(
        mapOf(
            PERIOD_A to "2024-03", PERIOD_B to "2024-03",
            MONTH_A to "03", MONTH_B to "03",
            FULL_A to "2024-03-05", FULL_B to "2024-03-05",
        ),
    )

    /** The distinct count folds at the shared declared component set, year-bearing and yearless alike. */
    @Test
    fun theDistinctCountFoldsAtTheDeclaredPrecision() {
        val actual = linkedMapOf(
            "yyyy-MM pair, equal cells, == 1" to
                bothFire(noBaseYear, "YM1EQ", "NumberOfDifferentValues(PeriodA, PeriodB) == 1", equal()),
            "yyyy-MM pair, differing cells, == 2" to
                bothFire(noBaseYear, "YM2NE", "NumberOfDifferentValues(PeriodA, PeriodB) == 2", ascending()),
            "yearless MM pair, equal cells, == 1" to
                bothFire(noBaseYear, "MO1EQ", "NumberOfDifferentValues(MonthA, MonthB) == 1", equal()),
            "yearless MM pair, differing cells, == 2" to
                bothFire(noBaseYear, "MO2NE", "NumberOfDifferentValues(MonthA, MonthB) == 2", ascending()),
            "yyyy-MM pair, differing cells, == 1 — must NOT fire" to
                bothFire(noBaseYear, "YM1NE", "NumberOfDifferentValues(PeriodA, PeriodB) == 1", ascending()),
        )
        val expected = linkedMapOf(
            "yyyy-MM pair, equal cells, == 1" to true,
            "yyyy-MM pair, differing cells, == 2" to true,
            "yearless MM pair, equal cells, == 1" to true,
            "yearless MM pair, differing cells, == 2" to true,
            "yyyy-MM pair, differing cells, == 1 — must NOT fire" to false,
        )
        assertEquals(expected, actual, "the count compares at the list's own precision, not at full calendar precision")
    }

    /**
     * The extrema order a component-omitting operand, and the REVERSED document is what makes that a
     * measurement: every ordering rule that fires on the ascending cells fires on none of the reversed ones.
     */
    @Test
    fun theExtremaOrderAComponentOmittingOperandAndTheReversedDocumentFiresNothing() {
        val min = "MinValue(PeriodA, PeriodB) == [PeriodA]"
        val max = "MaxValue(PeriodA, PeriodB) == [PeriodB]"
        val yearlessMin = "MinValue(MonthA, MonthB) == [MonthA]"
        val yearlessMax = "MaxValue(MonthA, MonthB) == [MonthB]"
        val completeControl = "MinValue(FullA, FullB) == [FullA]"

        val actual = linkedMapOf(
            "yyyy-MM min, ascending" to bothFire(noBaseYear, "YMMINA", min, ascending()),
            "yyyy-MM max, ascending" to bothFire(noBaseYear, "YMMAXB", max, ascending()),
            "yearless MM min, ascending" to bothFire(noBaseYear, "MOMINA", yearlessMin, ascending()),
            "yearless MM max, ascending" to bothFire(noBaseYear, "MOMAXB", yearlessMax, ascending()),
            "complete-date min, ascending — control" to
                bothFire(noBaseYear, "FULLMINA", completeControl, ascending()),
            "yyyy-MM min, REVERSED" to bothFire(noBaseYear, "YMMINA", min, reversed()),
            "yyyy-MM max, REVERSED" to bothFire(noBaseYear, "YMMAXB", max, reversed()),
            "yearless MM min, REVERSED" to bothFire(noBaseYear, "MOMINA", yearlessMin, reversed()),
            "yearless MM max, REVERSED" to bothFire(noBaseYear, "MOMAXB", yearlessMax, reversed()),
            "complete-date min, REVERSED — control" to
                bothFire(noBaseYear, "FULLMINA", completeControl, reversed()),
        )
        val expected = linkedMapOf(
            "yyyy-MM min, ascending" to true,
            "yyyy-MM max, ascending" to true,
            "yearless MM min, ascending" to true,
            "yearless MM max, ascending" to true,
            "complete-date min, ascending — control" to true,
            "yyyy-MM min, REVERSED" to false,
            "yyyy-MM max, REVERSED" to false,
            "yearless MM min, REVERSED" to false,
            "yearless MM max, REVERSED" to false,
            "complete-date min, REVERSED — control" to false,
        )
        assertEquals(expected, actual, "the fold orders at the shared set's precision, yearless included")
    }

    /**
     * The cell the 2×2 was missing: a UNIFORMLY yearless list on a model that DECLARES a Base Year. The rows
     * above take the no-Base-Year arm and the supplementation rows below take a mixed-precision list, so
     * "a declared Base Year does not disturb a list that needs no supplementation" was the one corner neither
     * covered — and a12-kernel-lean's `SPEC-2026-09-07-02` names it unmeasured on both sides.
     *
     * The two models differ in exactly the `baseYear` builder call, so a verdict change here could only be
     * that flag; the reversed document is the separator, as above.
     */
    @Test
    fun aUniformlyYearlessListOrdersUnderADeclaredBaseYearToo() {
        val yearlessMin = "MinValue(MonthA, MonthB) == [MonthA]"
        val yearlessMax = "MaxValue(MonthA, MonthB) == [MonthB]"

        val actual = linkedMapOf(
            "yearless MM min, ascending, baseYear 2024" to
                bothFire(withBaseYear, "MOMINA", yearlessMin, ascending()),
            "yearless MM max, ascending, baseYear 2024" to
                bothFire(withBaseYear, "MOMAXB", yearlessMax, ascending()),
            "yearless MM min, REVERSED, baseYear 2024" to
                bothFire(withBaseYear, "MOMINA", yearlessMin, reversed()),
            "yearless MM max, REVERSED, baseYear 2024" to
                bothFire(withBaseYear, "MOMAXB", yearlessMax, reversed()),
            "yearless MM min, EQUAL values, baseYear 2024" to
                bothFire(withBaseYear, "MOMINA", yearlessMin, equal()),
        )
        val expected = linkedMapOf(
            "yearless MM min, ascending, baseYear 2024" to true,
            "yearless MM max, ascending, baseYear 2024" to true,
            "yearless MM min, REVERSED, baseYear 2024" to false,
            "yearless MM max, REVERSED, baseYear 2024" to false,
            // Both operands decode to one month, so the minimum equals either — the row says the Base Year
            // does not silently shift one operand and not the other.
            "yearless MM min, EQUAL values, baseYear 2024" to true,
        )
        assertEquals(expected, actual, "a declared Base Year neither enables nor disturbs a uniformly yearless fold")
    }

    /**
     * A yearless operand beside a year-bearing one, admitted only under a declared Base Year. The
     * other-year row separates "the Base Year supplements" from "the sibling's year is borrowed".
     */
    @Test
    fun aYearlessOperandIsSupplementedByTheDeclaredBaseYearAndNotByItsSibling() {
        val count1 = "NumberOfDifferentValues(MonthA, PeriodA) == 1"
        val count2 = "NumberOfDifferentValues(MonthA, PeriodA) == 2"
        val minIsPeriod = "MinValue(MonthA, PeriodA) == [PeriodA]"

        fun cells(period: String) = Cells(mapOf(MONTH_A to "03", PERIOD_A to period))

        val actual = linkedMapOf(
            "03 beside 2024-03, == 1" to bothFire(withBaseYear, "SUP1EQ", count1, cells("2024-03")),
            "03 beside 2024-04, == 2" to bothFire(withBaseYear, "SUP2NE", count2, cells("2024-04")),
            "03 beside 2023-03, == 2" to bothFire(withBaseYear, "SUP2NE", count2, cells("2023-03")),
            "03 beside 2023-03, the 2023 operand is the minimum" to
                bothFire(withBaseYear, "SUPMIN", minIsPeriod, cells("2023-03")),
            "03 beside 2024-04, the 2024-04 operand is NOT the minimum" to
                bothFire(withBaseYear, "SUPMIN", minIsPeriod, cells("2024-04")),
        )
        val expected = linkedMapOf(
            "03 beside 2024-03, == 1" to true,
            "03 beside 2024-04, == 2" to true,
            "03 beside 2023-03, == 2" to true,
            "03 beside 2023-03, the 2023 operand is the minimum" to true,
            "03 beside 2024-04, the 2024-04 operand is NOT the minimum" to false,
        )
        assertEquals(expected, actual, "the identity is the SUPPLEMENTED set's, anchored on the declared Base Year")
    }
}
