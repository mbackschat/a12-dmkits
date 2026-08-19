package io.github.mbackschat.a12.dm.interpreter

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
}
