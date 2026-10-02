package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.MessageParameterProbes.kernel
import io.github.mbackschat.a12.dm.adapter.laws.PremiumProbes.premium
import io.github.mbackschat.a12.dm.adapter.laws.PremiumProbes.rule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * SPEC-2026-10-01-05 on our own premium carrier: which class `BaseYear` and its range endpoints draw under `==`
 * opposite each field kind, in either position.
 *
 * The two partner readings are what the grid separates: `BaseYear` alone meets the comparison as a Number, so a
 * String draws the text class and a DateRange its own, while `StartOfDateRange(BaseYear)` and
 * `EndOfDateRange(BaseYear)` meet it as a date, so the same two kinds draw the date class. A Date and a scale-0
 * Number are the admitted controls, and a model without a base year is the control that the year was read at all.
 */
class BaseYearComparisonClassAgreementTest {
    data class Row(val id: String, val model: String, val classes: List<String>)

    private enum class Kind(val field: String, val yearClass: String?, val dateClass: String?) {
        STRING("Note", TEXT, DATE), DATE_RANGE("Term", "MVK_INVALID_COMPARE_TO_DATE_RANGE", DATE),
        TIME("Clock", DATE, DATE), DATE_TIME("Stamp", DATE, null), BOOLEAN("Active", "MVK_INVALID_COMPARE_TO_YESNO", "MVK_INVALID_COMPARE_TO_YESNO"),
        CONFIRM("Agreed", "MVK_INVALID_COMPARE_TO_YES", "MVK_INVALID_COMPARE_TO_YES"),
        SCALE_TWO_NUMBER("Ratio", "MVK_INVALID_COMPARE_DEC_PLACES", DATE), ENUMERATION("Tier", TEXT, DATE),
        LABELLED_ENUMERATION("Band", TEXT, DATE), DATE_KIND("Start", null, null), SCALE_ZERO_NUMBER("Units", null, DATE)
    }

    fun rows(): List<Row> = buildList {
        val withYear = premium(baseYear = 2024)
        fun row(id: String, base: String, comparison: String, vararg classes: String) =
            add(Row(id, rule(base, "Note2", "FieldFilled(Note2) And $comparison"), classes.toList()))
        for (kind in Kind.entries) {
            val field = "[${kind.field}]"
            val name = kind.name.lowercase()
            row("year-right-$name", withYear, "$field == BaseYear", *listOfNotNull(kind.yearClass).toTypedArray())
            row("year-left-$name", withYear, "BaseYear == $field", *listOfNotNull(kind.yearClass).toTypedArray())
            if (kind == Kind.DATE_TIME) continue
            for (endpoint in listOf("StartOfDateRange", "EndOfDateRange")) {
                val point = "$endpoint(BaseYear)"
                val classes = listOfNotNull(kind.dateClass).toTypedArray()
                row("${endpoint.lowercase()}-right-$name", withYear, "$field == $point", *classes)
                row("${endpoint.lowercase()}-left-$name", withYear, "$point == $field", *classes)
            }
        }
        val withoutYear = premium()
        row("no-base-year-right", withoutYear, "[Units] == BaseYear", "MVK_NO_BASE_YEAR")
        row("no-base-year-left", withoutYear, "BaseYear == [Units]", "MVK_NO_BASE_YEAR")
    }

    @Test
    fun baseYearMeetsTheComparisonAsANumberAndItsEndpointsAsADate() {
        val rows = rows()
        assertEquals(64, rows.size, "frozen witness rows")
        assertEquals(rows.size, rows.map { it.id }.toSet().size, "observations retain independent identities")
        assertEquals(rows.map { it.id to it.classes }, rows.map { it.id to kernel(it.model) }, "kernel classes")
    }

    private companion object {
        const val TEXT = "MVK_INVALID_COMPARE_TO_ENUM_OR_STRING"
        const val DATE = "MVK_INVALID_COMPARE_TO_DATE"
    }
}
