package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.model.EvalClock
import io.github.mbackschat.a12.dm.interpreter.model.ModelTimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform lock for [EvalClock.at]: an instant is read as wall time in the model's zone, as the kernel's
 * `RuntimeController` does with `currentDateForTest`, and a millisecond remainder survives as `.SSS`. The Berlin
 * rows straddle midnight and the 2026 spring-forward second, where a UTC reading lands on the wrong day or hour.
 */
class EvalClockAtTest {

    @Test
    fun anInstantIsReadInTheModelsZone() = assertEquals(
        listOf(
            EvalClock("2026-10-13", "2026-10-13T21:30:00"),
            EvalClock("2026-10-13", "2026-10-13T23:30:00"),
            EvalClock("2026-10-14", "2026-10-14T00:30:00"),
            EvalClock("2026-01-16", "2026-01-16T00:30:00.250"),
            EvalClock("2026-03-29", "2026-03-29T01:59:59.007"),
            EvalClock("2026-03-29", "2026-03-29T03:00:00"),
        ),
        listOf(
            EvalClock.at(1_791_927_000_000L, ModelTimeZone.UTC),
            EvalClock.at(1_791_927_000_000L, ModelTimeZone.EUROPE_BERLIN),
            EvalClock.at(1_791_930_600_000L, ModelTimeZone.EUROPE_BERLIN),
            EvalClock.at(1_768_519_800_250L, ModelTimeZone.EUROPE_BERLIN),
            EvalClock.at(1_774_745_999_007L, ModelTimeZone.EUROPE_BERLIN),
            EvalClock.at(1_774_746_000_000L, ModelTimeZone.EUROPE_BERLIN),
        ),
    )

    @Test
    fun anInstantBeforeTheEpochKeepsItsDayAndRemainder() =
        assertEquals(EvalClock("1969-12-31", "1969-12-31T23:59:59.500"), EvalClock.at(-500L, ModelTimeZone.UTC))
}
