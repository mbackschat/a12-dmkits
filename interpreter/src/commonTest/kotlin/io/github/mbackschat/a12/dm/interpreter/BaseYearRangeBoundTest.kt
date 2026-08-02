package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

/** Portable endpoint lock for the one point-in-time admitted as a date-range source. */
class BaseYearRangeBoundTest {

    @Test
    fun startAndEndOfBaseYearAreTheFirstAndLastCalendarDays() {
        val model = ModelDef(
            fields = listOf(FieldDef(DATE, Kind.DATE)),
            rules = listOf(
                RuleDef("$ROOT/Start", ROOT, "[Anchor] == StartOfDateRange(BaseYear)", "START", "../Anchor"),
                RuleDef("$ROOT/End", ROOT, "[Anchor] == EndOfDateRange(BaseYear)", "END", "../Anchor"),
            ),
            baseYear = 2020,
        )
        fun codes(date: String) = DmInterpreter(model).validateFull(
            model.newDocument().group(ROOT, 1).field(DATE, date, 1, 1),
        ).map { it.code }.toSet()

        assertEquals(setOf("START"), codes("2020-01-01"))
        assertEquals(setOf("END"), codes("2020-12-31"))
        assertEquals(emptySet(), codes("2020-06-15"))
    }

    private companion object {
        const val ROOT = "/Forecast"
        const val DATE = "/Forecast/Anchor"
    }
}
