package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.EvalConfig
import io.github.mbackschat.a12.dm.interpreter.eval.Interpreter
import io.github.mbackschat.a12.dm.interpreter.eval.SingleRowDoc
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.EvalClock
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.ModelTimeZone
import io.github.mbackschat.a12.dm.interpreter.parse.parseCondition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A hand-built clock whose `now` names a wall time the model's zone skips reads as the wall time one gap later,
 * the way kernel 31.1.1 resolves every gap label (KERNEL-FINDINGS KF665), and it does so wherever it is read: as a
 * temporal operand and as a computed value alike. A12 cannot meet this input, because its clock is an instant
 * ([EvalClock.at] builds the same), so this locks our own consistency contract, not a kernel row.
 */
class ClockReadingZoneTest {

    @Test
    fun aClockInABerlinGapReadsOneGapLaterOnEveryReadOfNow() {
        for (condition in listOf("[ScheduledAt] < Now", "[ScheduledAt] < AddSeconds(Now, 1)")) {
            assertTrue(fires(ModelTimeZone.EUROPE_BERLIN, condition), condition)
        }
    }

    /** The control: in UTC the same wall time is its own instant, so 03:15 lies after it. */
    @Test
    fun theSameWallTimeIsAnOrdinaryReadingInUtc() {
        assertFalse(fires(ModelTimeZone.UTC, "[ScheduledAt] < Now"))
        assertFalse(fires(ModelTimeZone.UTC, "[ScheduledAt] < AddSeconds(Now, 1)"))
    }

    /** A computed `Now` stores the label its comparisons read: one gap later in Berlin, the clock's own in UTC. */
    @Test
    fun aComputedNowStoresTheResolvedReading() {
        assertEquals("VALUE|2026-03-29T03:30:00", computedNow(ModelTimeZone.EUROPE_BERLIN))
        assertEquals("VALUE|2026-03-29T02:30:00", computedNow(ModelTimeZone.UTC))
    }

    private fun computedNow(zone: ModelTimeZone): String {
        val def = ModelDef(
            fields = listOf(FieldDef(FIELD, Kind.DATE_TIME, format = "yyyy-MM-dd'T'HH:mm:ss")),
            rules = emptyList(),
            computations = listOf(ComputationDef(FIELD, ROOT, "Now")),
            groupPaths = setOf(ROOT),
            timeZone = zone,
        )
        val computed = DmInterpreter(def, GAP_CLOCK).compute(def.newDocument().group(ROOT, 1)).single()
        return "${computed.outcome.name}|${computed.value}"
    }

    private fun fires(zone: ModelTimeZone, condition: String): Boolean =
        Interpreter(
            SingleRowDoc(ROOT, mapOf(FIELD to "2026-03-29T03:15:00"), mapOf(FIELD to Kind.DATE_TIME)),
            EvalConfig(clock = GAP_CLOCK, timeZone = zone),
        ).fires(parseCondition(condition, ROOT))

    private companion object {
        const val ROOT = "/Schedule"
        const val FIELD = "/Schedule/ScheduledAt"

        /** Europe/Berlin moves 02:00 straight to 03:00 on 2026-03-29. */
        val GAP_CLOCK = EvalClock(today = "2026-03-29", now = "2026-03-29T02:30:00")
    }
}
