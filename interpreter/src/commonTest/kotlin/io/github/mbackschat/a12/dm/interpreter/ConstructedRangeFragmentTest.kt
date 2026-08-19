package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.intervalEndOfDeclaredFormat
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.DatePrecision
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM + JS) lock for SPEC-2026-08-19-02: a `DateRange(start, finish)` CONSTRUCTION completes its
 * finish at the interval END, so a construction over a day-omitting declaration profile equals the stored range
 * carrying the same components. Reading both operands as plain dates completed BOTH sides to the earliest date
 * and the equality never held. Kernel-verified by `adapter.laws.ConstructedRangeFragmentDiffTest`.
 */
class ConstructedRangeFragmentTest {

    private fun fires(profile: String, from: String, to: String, stored: String): Boolean {
        val fromPath = "/Policy/From"
        val toPath = "/Policy/To"
        val coverPath = "/Policy/Cover"
        val def = ModelDef(
            listOf(
                FieldDef(fromPath, Kind.DATE, format = profile, datePrecision = DatePrecision.FRAGMENT),
                FieldDef(toPath, Kind.DATE, format = profile, datePrecision = DatePrecision.FRAGMENT),
                FieldDef(coverPath, Kind.DATE_RANGE, format = profile, rangeSeparator = "/"),
            ),
            listOf(RuleDef("/Policy/EQ", "/Policy",
                "DateRange($fromPath, $toPath) == [$coverPath]", "EQ", "../Cover")),
        )
        val doc = def.newDocument().group("/Policy", 1)
            .field(fromPath, from, 1, 1)
            .field(toPath, to, 1, 1)
            .field(coverPath, stored, 1, 1)
        return DmInterpreter(def).validateFull(doc).any { it.code == "EQ" }
    }

    @Test fun aMonthOnlyConstructionEqualsItsStoredRange() =
        assertEquals(true, fires("MM", "06", "09", "06/09"))

    @Test fun aMonthOnlyConstructionDiffersOnAChangedFinish() =
        assertEquals(false, fires("MM", "06", "09", "06/10"))

    @Test fun aMonthOnlyConstructionDiffersOnAChangedStart() =
        assertEquals(false, fires("MM", "06", "09", "05/09"))

    @Test fun aYearOnlyConstructionEqualsItsStoredRange() =
        assertEquals(true, fires("yyyy", "2024", "2025", "2024/2025"))

    @Test fun aYearMonthConstructionEqualsItsStoredRange() =
        assertEquals(true, fires("yyyy-MM", "2024-02", "2024-02", "2024-02/2024-02"))

    /** Both sub-year components are declared, so the two interval rules coincide and nothing is widened. */
    @Test fun aMonthDayConstructionEqualsItsStoredRange() =
        assertEquals(true, fires("MM-dd", "06-01", "09-30", "06-01/09-30"))

    /**
     * The widening is a DATE rule and stops at an hour, exactly as the kernel's `addMonthAndDay` does. Asserted
     * on the helper directly because no model can route a time-bearing format into it today — which is why the
     * guard exists: the reachability argument is about a neighbouring map, not about this function.
     */
    @Test fun aTimeBearingFormatIsNeverWidened() {
        assertEquals("2024-06-01", intervalEndOfDeclaredFormat("2024-06-01", "yyyy-MM-dd'T'HH:mm:ss"))
        assertEquals("2024-06-01", intervalEndOfDeclaredFormat("2024-06-01", "HH:mm:ss"))
    }

    /** The date rule itself, per component the format omits — the positive control for the guard above. */
    @Test fun aDateFormatIsWidenedPerOmittedComponent() {
        assertEquals("2024-12-31", intervalEndOfDeclaredFormat("2024-01-01", "yyyy"))
        assertEquals("2024-02-29", intervalEndOfDeclaredFormat("2024-02-01", "yyyy-MM"))
        assertEquals("2024-06-30", intervalEndOfDeclaredFormat("2024-06-01", "MM"))
        assertEquals("2024-06-01", intervalEndOfDeclaredFormat("2024-06-01", "dd.MM.yyyy"))
    }
}
