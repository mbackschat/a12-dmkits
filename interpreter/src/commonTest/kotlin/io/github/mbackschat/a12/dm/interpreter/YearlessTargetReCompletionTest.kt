package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.DatePrecision
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Portable lock for KERNEL-FINDINGS KF666: a computed `02-29` stored into a day-month target is re-completed in the
 * Base Year, and under a common year that re-completion fails twice on one pointer, so the whole computation fails
 * result assembly. The same model under a leap Base Year, differing in that member alone, stores the value; a
 * year-carrying target never re-completes. The kernel rows are `adapter.laws.CalendarTargetCompletionDiffTest`.
 */
class YearlessTargetReCompletionTest {

    @Test
    fun aLeapDayIntoEveryDayMonthTargetFailsAssemblyUnderACommonBaseYear() {
        for ((format, precision) in DAY_MONTH_TARGETS) {
            val failure = assertFailsWith<IllegalStateException>("$format $precision") {
                compute(format, precision, baseYear = 2023)
            }
            assertTrue(failure.message!!.startsWith("Duplicate computation diagnostic"), "$format $precision: ${failure.message}")
        }
    }

    @Test
    fun aLeapBaseYearStoresIt() {
        assertEquals(ComputeOutcome.VALUE to "02-29", compute("MM-dd", DatePrecision.FULL, baseYear = 2024))
        assertEquals(ComputeOutcome.VALUE to "02-29", compute("MM-dd", DatePrecision.FRAGMENT, baseYear = 2024))
    }

    @Test
    fun aYearCarryingTargetDoesNotReComplete() {
        assertEquals(ComputeOutcome.VALUE to "2024-02-29", compute("yyyy-MM-dd", DatePrecision.FULL, baseYear = 2023))
    }

    private fun compute(format: String, precision: DatePrecision, baseYear: Int): Pair<ComputeOutcome, String?> {
        val model = ModelDef(
            fields = listOf(FieldDef(CYCLE, Kind.DATE, format = format, datePrecision = precision)),
            rules = emptyList(),
            computations = listOf(ComputationDef(CYCLE, ROOT, "\"29.02.2024\"")),
            baseYear = baseYear,
        )
        val computed = DmInterpreter(model).compute(model.newDocument().group(ROOT, 1)).single { it.path == CYCLE }
        return computed.outcome to computed.value
    }

    private companion object {
        const val ROOT = "/Plan"
        const val CYCLE = "/Plan/Cycle"
        val DAY_MONTH_TARGETS = listOf("MM-dd", "MMdd", "dd.MM.", "ddMM").map { it to DatePrecision.FULL } +
            listOf("MM-dd", "MMdd", "dd.MM.", "ddMM").map { it to DatePrecision.FRAGMENT }
    }
}
