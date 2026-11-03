package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.parseRangeToIso
import io.github.mbackschat.a12.dm.interpreter.model.ModelTimeZone.UTC
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pure-engine guard for **format-aware date-range parsing** — runs on JVM AND JS. Because parsing is driven
 * by the field's declared `format` (not a hardcoded pattern), the locale variants all canonicalize to the
 * same ISO endpoints: **de** `dd.MM.yyyy`, **en/US** `MM/dd/yyyy`, **ISO** `yyyy-MM-dd`. It also pins the
 * tricky case where the range separator ALSO appears inside the date format (anchored-regex disambiguation).
 */
class DateFormatTest {

    private val expected = "2021-06-01" to "2021-06-30"

    @Test fun deFormat() = assertEquals(expected, parseRangeToIso("01.06.2021-30.06.2021", "dd.MM.yyyy", "-", UTC))
    @Test fun enUsFormat() = assertEquals(expected, parseRangeToIso("06/01/2021-06/30/2021", "MM/dd/yyyy", "-", UTC))
    @Test fun isoFormatSlashSeparator() = assertEquals(expected, parseRangeToIso("2021-06-01/2021-06-30", "yyyy-MM-dd", "/", UTC))

    @Test fun separatorAlsoInsideFormat() =
        // sep "-" also separates the ISO date components — the anchored regex still splits the two endpoints.
        assertEquals(expected, parseRangeToIso("2021-06-01-2021-06-30", "yyyy-MM-dd", "-", UTC))

    @Test fun malformedReturnsNull() {
        assertNull(parseRangeToIso("01.06.2021 - 30.06.2021", "dd.MM.yyyy", "-", UTC), "spaces around the separator")
        assertNull(parseRangeToIso("2021-06-01/2021-06-30", "dd.MM.yyyy", "-", UTC), "wrong format")
        assertNull(parseRangeToIso("31.13.2021-01.01.2022", "dd.MM.yyyy", "-", UTC), "month 13 out of range")
    }
}
