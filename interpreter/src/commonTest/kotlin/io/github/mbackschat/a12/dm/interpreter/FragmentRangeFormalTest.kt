package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM + JS) lock for IF112 — the formal check of a DATE_RANGE whose declared format is a date
 * FRAGMENT (`yyyy`, a year-range like `2024/2025`): each side parses BY THE DECLARED FORMAT, completing the
 * missing components exactly like a fragment DATE field, so a year-range is well-formed — not
 * `datumBereichFormatFalsch`. Kernel-verified by the tri-check differential
 * `adapter.laws.FragmentRangeFormalDiffTest`.
 */
class FragmentRangeFormalTest {

    private val SEASON = "/Trip/Season"

    private val def = ModelDef(
        listOf(FieldDef(SEASON, Kind.DATE_RANGE, format = "yyyy", rangeSeparator = "/")),
        emptyList(),
    )

    private fun codes(value: String): List<String> {
        val doc = def.newDocument().group("/Trip", 1).field(SEASON, value, 1, 1)
        return DmInterpreter(def).validateFull(doc).map { it.code }.sorted()
    }

    @Test fun aYearRangeIsWellFormed() = assertEquals(emptyList(), codes("2024/2024"))

    @Test fun anOrdinaryYearRangeIsWellFormed() = assertEquals(emptyList(), codes("2024/2025"))

    @Test fun startAfterEndFlags() = assertEquals(listOf("datumBereichNichtGueltig"), codes("2025/2024"))

    @Test fun aNonYearSideFlags() = assertEquals(listOf("datumBereichFormatFalsch"), codes("20x4/2024"))

    @Test fun aMissingSeparatorFlags() = assertEquals(listOf("datumBereichTrennerFehlt"), codes("2024"))

    @Test fun aPreGregorianYearRangeFlags() = assertEquals(listOf("datumBereichDatumFalsch"), codes("1500/1600"))
}
