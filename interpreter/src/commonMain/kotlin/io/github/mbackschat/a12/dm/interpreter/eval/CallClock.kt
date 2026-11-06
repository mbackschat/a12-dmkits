package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.model.EvalClock
import io.github.mbackschat.a12.dm.interpreter.model.ModelTimeZone

/**
 * One call's evaluation clock: its instant is fixed when the call starts, and it is read as wall time only when a
 * rule reads `Today` or `Now`.
 *
 * The wall-time reading is the expensive half. On Kotlin/JS the instant's Long arithmetic is emulated and the zone
 * tables initialize on first use, and the support audit used to read it as well. Together that cost about 1.1 ms
 * of the browser first answer's 9.6 ms interpreter construction on a model with no clock constant (interleaved
 * jitless profile, 2026-11-06). The audit asks only whether a clock with a date-time exists, so [hasNow] answers it
 * without a reading. `CallClockReadingTest` locks both halves.
 */
internal class CallClock(val hasNow: Boolean, read: () -> EvalClock) {
    /** The wall-time reading, computed at most once per clock. */
    val reading: EvalClock by lazy(read)

    companion object {
        /** A clock that already holds its reading, as a directly supplied [EvalClock] does. */
        fun of(clock: EvalClock): CallClock = CallClock(clock.now != null) { clock }

        /** [epochMillis] read in [timeZone] on first use, which always yields a date-time ([EvalClock.at]). */
        fun at(epochMillis: Long, timeZone: ModelTimeZone): CallClock =
            CallClock(hasNow = true) { EvalClock.at(epochMillis, timeZone) }
    }
}
