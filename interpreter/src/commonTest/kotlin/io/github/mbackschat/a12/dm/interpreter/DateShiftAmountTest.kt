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
 * Multiplatform (JVM + JS) lock for SPEC-2026-07-27-04 — a Date/DateTime `Add*` amount is converted through
 * Java `BigDecimal.intValue()`: **truncate toward zero**, then **retain the low signed 32 bits**. Large amounts
 * are neither rejected nor saturated, so `2³² + 7` shifts by exactly 7 — a boundary observable inside the Date
 * domain. Kernel-verified by `adapter.laws.DateShiftAmountDiffTest`; the narrowing itself is oracled against
 * `java.math.BigDecimal.intValue` in `A12ShiftAmountNarrowingTest`.
 */
class DateShiftAmountTest {

    private val BASIS = "/Schedule/Basis"
    private val LANDED = "/Schedule/Landed"

    private fun landed(amount: String): String? {
        val m = ModelDef(
            fields = listOf(
                FieldDef(BASIS, Kind.DATE, format = "yyyy-MM-dd"),
                FieldDef(LANDED, Kind.DATE, format = "yyyy-MM-dd"),
            ),
            rules = emptyList(),
            computations = listOf(ComputationDef(LANDED, "/Schedule", "AddDays(/Schedule/Basis, $amount)")),
        )
        val d = m.newDocument().group("/Schedule", 1).field(BASIS, "2024-06-01", 1, 1)
        val c = DmInterpreter(m).compute(d).firstOrNull { it.path == LANDED }
        return if (c?.outcome == ComputeOutcome.VALUE) c.value else null
    }

    @Test fun aWholeAmountShifts() = assertEquals("2024-06-08", landed("7"))

    @Test fun aFractionalAmountTruncatesTowardZero() {
        assertEquals("2024-06-02", landed("1.9"), "1.9 → +1, not +2 and not no-value")
        assertEquals("2024-05-31", landed("-1.9"), "-1.9 → -1, truncation is toward zero")
        assertEquals("2024-06-01", landed("0.9"), "0.9 → 0")
    }

    @Test fun anAmountBeyondThirtyTwoBitsWraps() {
        assertEquals("2024-06-08", landed("4294967303"), "2^32 + 7 narrows to +7")
        assertEquals("2024-06-08", landed("-4294967289"), "-(2^32 - 7) narrows to +7")
        assertEquals("2024-06-01", landed("4294967296"), "2^32 narrows to 0")
    }
}
