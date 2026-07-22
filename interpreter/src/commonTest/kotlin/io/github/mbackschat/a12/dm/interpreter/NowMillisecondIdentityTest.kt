package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.EvalConfig
import io.github.mbackschat.a12.dm.interpreter.eval.Interpreter
import io.github.mbackschat.a12.dm.interpreter.eval.SingleRowDoc
import io.github.mbackschat.a12.dm.interpreter.model.EvalClock
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.parse.parseCondition
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Portable lock for the exact millisecond identity carried only by the injected `Now` clock value. */
class NowMillisecondIdentityTest {

    private val clock = EvalClock(today = "2025-06-23", now = "2025-06-23T12:00:00.999")

    @Test
    fun nowDoesNotEqualAStoredValueRenderedInTheSameSecond() {
        assertFalse(fires("2025-06-23T12:00:00", "[ScheduledAt] == Now"))
        assertTrue(fires("2025-06-23T12:00:00", "[ScheduledAt] < Now"))
    }

    @Test
    fun subSecondDifferencesTruncateTowardZeroInBothDirections() {
        assertTrue(fires("2025-06-23T12:00:00", "DifferenceInSeconds(ScheduledAt, Now) == 0"))
        assertTrue(fires("2025-06-23T12:00:00", "DifferenceInSeconds(Now, ScheduledAt) == 0"))
    }

    @Test
    fun subDayShiftPreservesTheMillisecondRemainder() {
        assertTrue(fires("2025-06-23T12:00:01", "DifferenceInSeconds(Now, AddSeconds(Now, 1)) == 1"))
        assertFalse(fires("2025-06-23T12:00:01", "[ScheduledAt] == AddSeconds(Now, 1)"))
        assertTrue(fires("2025-06-23T12:00:01", "[ScheduledAt] < AddSeconds(Now, 1)"))
    }

    private fun fires(value: String, condition: String): Boolean =
        Interpreter(
            SingleRowDoc(ROOT, mapOf(FIELD to value), mapOf(FIELD to Kind.DATE_TIME)),
            EvalConfig(clock = clock),
        ).fires(parseCondition(condition, ROOT))

    private companion object {
        const val ROOT = "/Schedule"
        const val FIELD = "/Schedule/ScheduledAt"
    }
}
