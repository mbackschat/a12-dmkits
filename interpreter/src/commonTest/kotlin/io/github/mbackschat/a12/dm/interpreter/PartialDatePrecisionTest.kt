package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.DatePrecision
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

/** JVM + Node lock for the full DateType partial-precision suffix and boundary-selection contract. */
class PartialDatePrecisionTest {

    private fun codes(precision: String, value: String?, condition: String): List<String> {
        val definition = ModelDef(
            fields = listOf(
                FieldDef(
                    APPROX,
                    Kind.DATE,
                    format = "dd.MM.yyyy",
                    datePrecision = enumValueOf<DatePrecision>(precision),
                ),
            ),
            rules = listOf(RuleDef("/Claim/PartialPrecision", "/Claim", condition, CODE, "../Approx")),
        )
        val document = definition.newDocument().group("/Claim", 1).also { doc ->
            if (value == null) doc.emptyField(APPROX, 1, 1) else doc.field(APPROX, value, 1, 1)
        }
        return DmInterpreter(definition).validateFull(document).map { it.code }.filter {
            it == CODE || it == "datumFormatFalsch" || it == "datumFalsch"
        }.sorted()
    }

    @Test
    fun unknownMonthSelectsTheMonthBeforeTheDay() {
        assertEquals(
            listOf(CODE),
            codes("MONTH_OPTIONAL", "00.00.2024", "ValueAsDate($APPROX, FirstDay) == \"01.01.2024\""),
        )
        assertEquals(
            listOf(CODE),
            codes("MONTH_OPTIONAL", "00.00.2024", "ValueAsDate($APPROX, LastDay) == \"31.12.2024\""),
        )
    }

    @Test
    fun widerModesRetainKnownYearDayOmissionAndFullValues() {
        for (precision in listOf("MONTH_OPTIONAL", "YEAR_OPTIONAL")) {
            assertEquals(
                listOf(CODE),
                codes(precision, "00.06.2024", "ValueAsDate($APPROX, LastDay) == \"30.06.2024\""),
            )
            assertEquals(
                listOf(CODE),
                codes(precision, "15.06.2024", "ValueAsDate($APPROX, FirstDay) == \"15.06.2024\""),
            )
        }
    }

    @Test
    fun unknownYearIsNeitherEmptyNorYear2000() {
        val separator =
            "FieldNotFilled($APPROX) Or ValueAsDate($APPROX, FirstDay) == \"01.01.2000\""
        assertEquals(emptyList(), codes("YEAR_OPTIONAL", "00.00.0000", separator))
        assertEquals(listOf(CODE), codes("YEAR_OPTIONAL", null, separator))
        assertEquals(
            emptyList(),
            codes("YEAR_OPTIONAL", "00.00.0000", "ValueAsDate($APPROX, FirstDay) == \"01.01.2000\""),
        )
    }

    @Test
    fun unknownComponentsMustBeASuffix() {
        assertEquals(
            listOf("datumFalsch"),
            codes("MONTH_OPTIONAL", "15.00.2024", "FieldFilled($APPROX)"),
        )
        assertEquals(
            listOf("datumFalsch"),
            codes("YEAR_OPTIONAL", "15.06.0000", "FieldFilled($APPROX)"),
        )
    }

    private companion object {
        const val APPROX = "/Claim/Approx"
        const val CODE = "PD"
    }
}
