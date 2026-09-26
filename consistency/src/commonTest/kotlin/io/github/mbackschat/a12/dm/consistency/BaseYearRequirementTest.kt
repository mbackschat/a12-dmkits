package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of the two laws that need a model base year.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.BaseYearRequirementLawsTest`. The rows that carry the file are the three carriers
 * of the constant — the refusal is the constant's, not any carrier's — and the two-part `Time`
 * construction, which has no year to omit and is admitted by the same shape that refuses a `Date`.
 */
class BaseYearRequirementTest {

    private val noBaseYear = listOf("MVK_NO_BASE_YEAR")
    private val missingYear = listOf("MVK_MISSING_YEAR")

    private fun codes(condition: String, baseYear: Boolean): List<String> = checkRead(
        ModelSource(
            """
            {"header":{"id":"order-base-year","locales":[{"code":"en_US"}]},
             "content":{"modelInfo":{BASE_YEAR"name":"order"},"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f1","name":"Day","Field":{"fieldType":{"type":"NumberType",
                  "NumberType":{"minValue":"0","maxLength":2}}}},
                {"type":"Field","id":"f2","name":"OrderedOn","Field":{"fieldType":{"type":"DateType",
                  "DateType":{"format":"dd.MM.yyyy"}}}},
                {"type":"Field","id":"f3","name":"PickupTime","Field":{"fieldType":{"type":"TimeType",
                  "TimeType":{"format":"HH:mm:ss"}}}},
                {"type":"Rule","id":"r0","name":"Probe","Rule":{
                  "errorCondition":"CONDITION_HERE","errorEntityRelPath":"../Reference",
                  "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
              ]}}
             ]}}}
            """.trimIndent()
                .replace("BASE_YEAR", if (baseYear) "\"baseYear\":\"2024\"," else "")
                .replace("CONDITION_HERE", condition),
        ),
    ).findings.map { it.code }

    /** The probe also READS the rule's error target, so the unreferenced-target family stays out of the row. */
    private fun without(probe: String): List<String> =
        codes("FieldFilled(Reference) And $probe", baseYear = false)
            .filter { it != "MVK_ERROR_FIELD_NOT_REFERENCED" }

    private fun with(probe: String): List<String> =
        codes("FieldFilled(Reference) And $probe", baseYear = true)
            .filter { it != "MVK_ERROR_FIELD_NOT_REFERENCED" }

    @Test
    fun whereTheBaseYearConstantNeedsTheDeclaration() {
        val actual = mutableMapOf<String, List<String>>()
        actual["compared against a date, declared"] = with("[OrderedOn] > BaseYear")
        actual["compared against a date, undeclared"] = without("[OrderedOn] > BaseYear")
        actual["inside an extraction, declared"] = with("YearFromDate(BaseYear) > 2020")
        actual["inside an extraction, undeclared"] = without("YearFromDate(BaseYear) > 2020")
        actual["shifted, undeclared"] = without("[OrderedOn] > AddDays(BaseYear, 7)")

        val expected = mutableMapOf<String, List<String>>()
        expected["compared against a date, declared"] = emptyList()
        expected["compared against a date, undeclared"] = noBaseYear
        expected["inside an extraction, declared"] = emptyList()
        // The constant's own checker runs wherever it stands, so all three carriers answer the same — which
        // is why this family looks for the TOKEN rather than for a carrier that admits the constant.
        expected["inside an extraction, undeclared"] = noBaseYear
        expected["shifted, undeclared"] = noBaseYear
        assertEquals(expected, actual, "where the base-year constant needs the declaration")
    }

    @Test
    fun whereAYearLessDateConstructionNeedsTheDeclaration() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a complete construction, undeclared — the control"] =
            without("[OrderedOn] == Date(\\\"01\\\", \\\"02\\\", \\\"2024\\\")")
        actual["a year-less construction, undeclared"] = without("[OrderedOn] == Date(\\\"01\\\", \\\"02\\\")")
        actual["a year-less construction, declared"] = with("[OrderedOn] == Date(\\\"01\\\", \\\"02\\\")")
        actual["a year-less construction naming a field, undeclared"] =
            without("[OrderedOn] == Date(Day, \\\"02\\\")")
        actual["a year-less construction whose DAY is out of range, undeclared"] =
            without("[OrderedOn] == Date(\\\"32\\\", \\\"02\\\")")
        actual["a two-part TIME construction, undeclared"] =
            without("[PickupTime] == Time(\\\"10\\\", \\\"30\\\")")

        val expected = mutableMapOf<String, List<String>>()
        expected["a complete construction, undeclared — the control"] = emptyList()
        expected["a year-less construction, undeclared"] = missingYear
        expected["a year-less construction, declared"] = emptyList()
        expected["a year-less construction naming a field, undeclared"] = missingYear
        // The day parameter is checked before the year one, so this family declines and the slot family
        // answers — read through that family's own walk rather than restated here.
        expected["a year-less construction whose DAY is out of range, undeclared"] =
            listOf("MVK_WRONG_DATE_FORMAT_FOR_OP")
        expected["a two-part TIME construction, undeclared"] = emptyList()
        assertEquals(expected, actual, "where a year-less date construction needs the declaration")
    }
}
