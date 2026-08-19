package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — a `DateRange(start, finish)` CONSTRUCTION whose endpoints are date FRAGMENTS completes its two
 * sides asymmetrically, exactly as a stored fragment range does (SPEC-2026-08-19-02).
 *
 * The kernel's `RuntimeController.constructDateRange` does not read its operands as plain dates; its own comment
 * says so ("we have to use the last day possible to correspond with feldWertAlsDateRange"). It pairs each raw
 * operand with that FIELD's declared format and hands the pair to the very same
 * `datesAndFormatsToDateRange` the stored path uses — `INTERVAL_START` for the start, `INTERVAL_END` for the
 * finish. So a missing MONTH fills to `01` / `12` and a missing DAY to `01` / the completed month's last day,
 * per side. The interpreter evaluated both operands as ordinary fragment DATE values, which is
 * `INTERVAL_START` on BOTH sides, so a construction never equalled the stored range it was built to match
 * wherever the declared profile omits the day: `MM`, `yyyy`, and `yyyy-MM` all diverged, while `MM-dd` and a
 * full `dd.MM.yyyy` agreed because there is nothing left to complete.
 *
 * Own domain (seasonal insurance cover). Conditions are authored with the rulekit typed DSL.
 */
class ConstructedRangeFragmentDiffTest {

    private val base = DocumentModelDraft.named("seasonal-cover").locale("en_US")
        .rootGroup("Policy") { policy ->
            policy.dateFragmentField("FromMonth") { it.fragmentFormat("MM") }
            policy.dateFragmentField("ToMonth") { it.fragmentFormat("MM") }
            policy.dateRangeField("CoverMonths") { it.format("MM").separator("/") }
            policy.dateFragmentField("FromYear") { it.fragmentFormat("yyyy") }
            policy.dateFragmentField("ToYear") { it.fragmentFormat("yyyy") }
            policy.dateRangeField("CoverYears") { it.format("yyyy").separator("/") }
            policy.dateFragmentField("FromPeriod") { it.fragmentFormat("yyyy-MM") }
            policy.dateFragmentField("ToPeriod") { it.fragmentFormat("yyyy-MM") }
            policy.dateRangeField("CoverPeriods") { it.format("yyyy-MM").separator("/") }
            policy.dateFragmentField("FromDay") { it.fragmentFormat("MM-dd") }
            policy.dateFragmentField("ToDay") { it.fragmentFormat("MM-dd") }
            policy.dateRangeField("CoverDays") { it.format("MM-dd").separator("/") }
        }.build().toJson()

    private val model = BoundModel.ofJson(base)

    private val modelJson = listOf(
        equality("MonthsEqual", "FromMonth", "ToMonth", "CoverMonths"),
        equality("YearsEqual", "FromYear", "ToYear", "CoverYears"),
        equality("PeriodsEqual", "FromPeriod", "ToPeriod", "CoverPeriods"),
        equality("DaysEqual", "FromDay", "ToDay", "CoverDays"),
    ).fold(base, TypedRules::inject)

    private val java by lazy { RuntimeLaws.kernelJavaEngine(modelJson) }
    private val definition by lazy { ModelLoader.load(modelJson) }

    private fun equality(code: String, from: String, to: String, window: String): Rule {
        val constructed = Dsl.dateRange(
            model.dateFragment("$ROOT/$from"), model.dateFragment("$ROOT/$to"))
        return model.rule(model.group(ROOT))
            .name(code)
            .errorField(model.dateRange("$ROOT/$window").field())
            .`when`(constructed.eq(model.dateRange("$ROOT/$window")))
            .code(code)
            .severity(Severity.ERROR)
            .message(Dsl.en("Constructed range equals the stored cover window."))
            .build()
    }

    /** The three engines must agree on which equality codes fire for the given cells — and on the SET, so a
     *  construction that stops matching its own stored twin shows up as a missing code, not as parity noise. */
    private fun agree(expected: Set<String>, vararg cells: Pair<String, String>) {
        val kernelDocument = RuntimeLaws.doc().group(ROOT, 1)
        val document = definition.newDocument().group(ROOT, 1)
        cells.forEach { (path, value) ->
            kernelDocument.field(path, value, 1, 1)
            document.field(path, value, 1, 1)
        }
        val dynamic = RuntimeLaws.messages(modelJson, kernelDocument).map { it.code() }.toSet()
        val generated = java.messages(java.instance(kernelDocument)).map { it.code() }.toSet()
        val interpreted = DmInterpreter(definition).validateFull(document).map { it.code }.toSet()

        assertEquals(expected, dynamic, "normative dynamic Groovy")
        assertEquals(dynamic, generated, "generated Java")
        assertEquals(dynamic, interpreted, "interpreter")
    }

    @Test
    fun aMonthOnlyConstructionEqualsItsStoredMonthRange() =
        agree(setOf("MonthsEqual"),
            "$ROOT/FromMonth" to "06", "$ROOT/ToMonth" to "09", "$ROOT/CoverMonths" to "06/09")

    @Test
    fun aMonthOnlyConstructionDiffersOnAChangedFinish() =
        agree(emptySet(),
            "$ROOT/FromMonth" to "06", "$ROOT/ToMonth" to "09", "$ROOT/CoverMonths" to "06/10")

    @Test
    fun aMonthOnlyConstructionDiffersOnAChangedStart() =
        agree(emptySet(),
            "$ROOT/FromMonth" to "06", "$ROOT/ToMonth" to "09", "$ROOT/CoverMonths" to "05/09")

    @Test
    fun aYearOnlyConstructionEqualsItsStoredYearRange() =
        agree(setOf("YearsEqual"),
            "$ROOT/FromYear" to "2024", "$ROOT/ToYear" to "2025", "$ROOT/CoverYears" to "2024/2025")

    @Test
    fun aYearMonthConstructionEqualsItsStoredPeriodRange() =
        agree(setOf("PeriodsEqual"),
            "$ROOT/FromPeriod" to "2024-02", "$ROOT/ToPeriod" to "2024-02",
            "$ROOT/CoverPeriods" to "2024-02/2024-02")

    /** The control that was already agreeing: `MM-dd` carries both sub-year components, so neither side has
     *  anything to complete and the two interval rules coincide. It must stay that way. */
    @Test
    fun aMonthDayConstructionEqualsItsStoredDayRange() =
        agree(setOf("DaysEqual"),
            "$ROOT/FromDay" to "06-01", "$ROOT/ToDay" to "09-30", "$ROOT/CoverDays" to "06-01/09-30")

    private companion object {
        const val ROOT = "/Policy"
    }
}
