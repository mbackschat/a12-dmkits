package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM + JS) lock for the DATE_RANGE **split** discriminators, which are two checks and not one:
 * the kernel's `FormatDefinitionDatumBereich.formalePruefung` first asks whether the stored token CONTAINS its
 * declared separator, and only a token that does not draws `datumBereichTrennerFehlt`. Once the separator is
 * present, the split's SHAPE is a format question — `splitStringBySeparator` preserves empty tokens, and any
 * length other than two, or either endpoint empty, is `datumBereichFormatFalsch`, decided before endpoint
 * parsing, order, and the Gregorian floor. Kernel-verified by `adapter.laws.DateRangeFormalDiffTest`.
 */
class DateRangeSplitFormalTest {

    private val WINDOW = "/Policy/CoverageWindow"

    private val def = ModelDef(
        listOf(FieldDef(WINDOW, Kind.DATE_RANGE, format = "dd.MM.yyyy", rangeSeparator = "-")),
        emptyList(),
    )

    private fun codes(value: String): List<String> {
        val doc = def.newDocument().group("/Policy", 1).field(WINDOW, value, 1, 1)
        return DmInterpreter(def).validateFull(doc).map { it.code }.sorted()
    }

    @Test fun aTwoPartSplitIsWellFormed() = assertEquals(emptyList(), codes("01.06.2024-30.06.2024"))

    @Test fun anAbsentSeparatorIsTheOnlyMissingSeparator() =
        assertEquals(listOf("datumBereichTrennerFehlt"), codes("01.06.2024"))

    @Test fun anExtraSeparatorIsAShapeError() =
        assertEquals(listOf("datumBereichFormatFalsch"), codes("01.06.2024-30.06.2024-31.07.2024"))

    @Test fun aLeadingEmptyEndpointIsAShapeError() =
        assertEquals(listOf("datumBereichFormatFalsch"), codes("-30.06.2024"))

    @Test fun aTrailingEmptyEndpointIsAShapeError() =
        assertEquals(listOf("datumBereichFormatFalsch"), codes("01.06.2024-"))

    @Test fun aBareSeparatorIsAShapeError() = assertEquals(listOf("datumBereichFormatFalsch"), codes("-"))
}
