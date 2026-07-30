package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

/** JVM + Node lock for the admitted String and exact-`yyyy` Date component runtime in Date/Time constructors. */
class TemporalComponentFieldTest {

    private val definition = ModelDef(
        fields = listOf(
            FieldDef(DAY, Kind.STRING, maxLength = 2, pattern = "[0-9]+"),
            FieldDef(YEAR, Kind.DATE, format = "yyyy"),
            FieldDef(HOUR, Kind.STRING, maxLength = 2, pattern = "\\d+"),
            FieldDef(SECOND, Kind.STRING, maxLength = 2, pattern = "[0-9]{2}"),
            FieldDef(DATE_TARGET, Kind.DATE, format = "yyyy-MM-dd"),
            FieldDef(TIME_TARGET, Kind.TIME, format = "HH:mm:ss"),
        ),
        rules = listOf(
            RuleDef("$GROUP/$DATE_VALID", GROUP,
                "Valid(Date(Day, \"01\", Year))", DATE_VALID, "../Day"),
            RuleDef("$GROUP/$DATE_INVALID", GROUP,
                "Invalid(Date(Day, \"01\", Year))", DATE_INVALID, "../Day"),
            RuleDef("$GROUP/$TIME_EQUAL", GROUP,
                "[TimeTarget] == Time(Hour, \"30\", Second)", TIME_EQUAL, "../Hour"),
        ),
    )

    @Test
    fun stringAndYearDateComponentsPreserveRealUnrealEmptyAndFormalStates() {
        assertOwn(
            mapOf(DAY to "01", YEAR to "1963", DATE_TARGET to "1963-01-01"),
            listOf(DATE_VALID to MsgType.VALUE),
            relevant = DATE_CODES,
        )
        assertOwn(
            mapOf(DAY to "32", YEAR to "1963", DATE_TARGET to "1963-01-01"),
            listOf(DATE_INVALID to MsgType.VALUE),
            relevant = DATE_CODES,
        )
        assertOwn(
            mapOf(YEAR to "1963", DATE_TARGET to "1963-01-01"),
            listOf(DATE_INVALID to MsgType.OMISSION),
            empty = setOf(DAY),
            relevant = DATE_CODES,
        )
        assertOwn(
            mapOf(DAY to "xx", YEAR to "1963", DATE_TARGET to "1963-01-01"),
            emptyList(),
            relevant = DATE_CODES,
        )
    }

    @Test
    fun timeStringComponentsConvertLeadingZerosWithoutClamping() {
        assertOwn(
            mapOf(HOUR to "09", SECOND to "05", TIME_TARGET to "09:30:05"),
            listOf(TIME_EQUAL to MsgType.VALUE),
            relevant = TIME_CODES,
        )
        assertOwn(
            mapOf(HOUR to "24", SECOND to "05", TIME_TARGET to "00:30:05"),
            emptyList(),
            relevant = TIME_CODES,
        )
        assertOwn(
            mapOf(SECOND to "05", TIME_TARGET to "00:30:05"),
            emptyList(),
            empty = setOf(HOUR),
            relevant = TIME_CODES,
        )
    }

    private fun assertOwn(
        values: Map<String, String>,
        expected: List<Pair<String, MsgType>>,
        empty: Set<String> = emptySet(),
        relevant: Set<String>,
    ) {
        val document = definition.newDocument().group(GROUP, 1).also { doc ->
            values.forEach { (path, value) -> doc.field(path, value, 1, 1) }
            empty.forEach { path -> doc.emptyField(path, 1, 1) }
        }
        val actual = DmInterpreter(definition).validateFull(document)
            .filter { it.code in relevant }
            .map { it.code to it.type }
            .sortedBy { it.first }
        assertEquals(expected.sortedBy { it.first }, actual)
    }

    private companion object {
        const val GROUP = "/Parts"
        const val DAY = "$GROUP/Day"
        const val YEAR = "$GROUP/Year"
        const val HOUR = "$GROUP/Hour"
        const val SECOND = "$GROUP/Second"
        const val DATE_TARGET = "$GROUP/DateTarget"
        const val TIME_TARGET = "$GROUP/TimeTarget"
        const val DATE_VALID = "DATE_VALID"
        const val DATE_INVALID = "DATE_INVALID"
        const val TIME_EQUAL = "TIME_EQUAL"
        val DATE_CODES = setOf(DATE_VALID, DATE_INVALID)
        val TIME_CODES = setOf(TIME_EQUAL)
    }
}
