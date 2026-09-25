package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of "a comparison inside a `Having` filter body is judged as one outside it".
 *
 * Mirrors `rulekit.validate.laws.FilterBodyComparisonLawsTest` row for row: each comparison family's defining
 * refusal, written over fields of the filtered repetition so the body reads only the row it filters.
 */
class FilterBodyComparisonTest {

    @Test
    fun eachComparisonFamilysRefusalIsDrawnInsideAFilterBody() {
        val rows = linkedMapOf(
            "[Claims/Units] == 1" to ADMITTED,
            "[Claims/Notes] == [Claims/Units]" to "MVK_INVALID_COMPARE_TO_ENUM_OR_STRING",
            "[Claims/Cause] == \"SMOKE\"" to "MVK_INVALID_STRING_CONSTANT_FOR_ENUM_COMPARISON",
            "[Claims/Notes] == [Claims/Reported]" to "MVK_INVALID_COMPARE_TO_DATE",
            "[Claims/Cause] == [Claims/Urgent]" to "MVK_INVALID_COMPARE_TO_YESNO",
            "[Claims/Notes] < [Claims/Units]" to "MVK_INVALID_TYPE_FOR_COMPARISON",
            "\"a\" == 1" to "MVK_INVALID_COMPARE_TO_CONST",
            "[Claims/Tier] == [Claims/Notes]" to "MVK_INVALID_COMPARE_ENUM_TO_STRING",
            "[Claims/Tier] == [Claims/TierConflict]" to "MVK_INVALID_COMPARE_ENUM_TO_ENUM",
            "[Claims/Units] DiffersWithToleranceRange1 True" to "MVK_INVALID_TYPE_FOR_TOLERANCE_OP",
            "[Claims/Units] == True" to "MVK_INCONSISTENT_TYPES_COMPARED",
            "[Claims/Units] == [Claims/Paid]" to "MVK_INVALID_COMPARE_DEC_PLACES",
            "[Claims/Settled] == False" to "MVK_INVALID_COMPARE_TO_YES",
            "[Claims/Reported] == [Claims/Anniversary]" to "MVK_INVALID_COMPARE_TO_DATE",
            "[Claims/Window] == [Claims/MonthWindow]" to "MVK_INVALID_COMPARE_TO_DATE_RANGE",
            "Length(Claims/Notes) PatternMatched \"a+\"" to "MVK_INVALID_PARAMETER_FOR_PATTERN_COMPARISON",
            "[Claims/Units] PatternMatched \"a+\"" to "MVK_INVALID_TYPE_FOR_PATTERN_COMPARISON",
            "[Claims/Notes] PatternMatched [Claims/ClaimNo]" to "MVK_MISSING_CONST_IN_PATTERN_COMP",
            "[Claims/Notes] PatternMatched \"a\\\\*+\"" to "MVK_INVALID_PATTERN",
        )
        val actual = rows.keys.associateWith { firstCode("Sum(Claims*/Paid Having $it) > 0") }
        assertEquals(rows, actual, "each comparison family's refusal, inside a filter body")
    }

    /** The class the checker names first, which is the one A12 raises. */
    private fun firstCode(probe: String): String = check(ModelSource(model(json("FieldFilled(Reference) And $probe"))))
        .findings.firstOrNull { it.category == FindingCategory.A12_CONSTRAINT && it.at == "/Policy/Probe" }
        ?.code ?: ADMITTED

    /** A condition as the model text carries it. */
    private fun json(text: String) = text.replace("\\", "\\\\").replace("\"", "\\\"")

    /** The claims policy of the laws test. */
    private fun model(condition: String): String = """
        {"header":{"id":"claims-policy","locales":[{"code":"en_US"}]},
         "content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Group","id":"g1","name":"Claims","Group":{"repeatability":5,"elements":[
              {"type":"Field","id":"f1","name":"ClaimNo","Field":{"fieldType":{"type":"StringType"}}},
              {"type":"Field","id":"f2","name":"Notes","Field":{"fieldType":{"type":"StringType"}}},
              {"type":"Field","id":"f3","name":"Units","Field":{"fieldType":{"type":"NumberType",
                "NumberType":{"maxFractionalDigits":0}}}},
              {"type":"Field","id":"f4","name":"Paid","Field":{"fieldType":{"type":"NumberType",
                "NumberType":{"maxFractionalDigits":2}}}},
              {"type":"Field","id":"f5","name":"Cause","Field":{"fieldType":{"type":"EnumerationType",
                "EnumerationType":{"values":[{"value":"FIRE"},{"value":"WATER"}]}}}},
              {"type":"Field","id":"f6","name":"Tier","Field":{"fieldType":{"type":"EnumerationType",
                "EnumerationType":{"values":[
                  {"value":"BASIC","label":[{"locale":"en_US","text":"Basic cover"}]},
                  {"value":"PLUS","label":[{"locale":"en_US","text":"Plus cover"}]}]}}}},
              {"type":"Field","id":"f7","name":"TierConflict","Field":{"fieldType":{"type":"EnumerationType",
                "EnumerationType":{"values":[
                  {"value":"BASIC","label":[{"locale":"en_US","text":"Budget cover"}]},
                  {"value":"PLUS","label":[{"locale":"en_US","text":"Plus cover"}]}]}}}},
              {"type":"Field","id":"f8","name":"Urgent","Field":{"fieldType":{"type":"BooleanType"}}},
              {"type":"Field","id":"f9","name":"Settled","Field":{"fieldType":{"type":"ConfirmType"}}},
              {"type":"Field","id":"f10","name":"Reported","Field":{"fieldType":{"type":"DateType",
                "DateType":{"format":"dd.MM.yyyy"}}}},
              {"type":"Field","id":"f11","name":"Anniversary","Field":{"fieldType":{"type":"DateType",
                "DateType":{"format":"MM-dd"}}}},
              {"type":"Field","id":"f12","name":"Window","Field":{"fieldType":{"type":"DateRangeType",
                "DateRangeType":{"format":"dd.MM.yyyy","rangeSeparator":"-"}}}},
              {"type":"Field","id":"f13","name":"MonthWindow","Field":{"fieldType":{"type":"DateRangeType",
                "DateRangeType":{"format":"MM","rangeSeparator":"/"}}}}
            ]}},
            {"type":"Rule","id":"r0","name":"Probe","Rule":{
              "errorCondition":"$condition","errorEntityRelPath":"../Reference",
              "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"see it"}]}}
          ]}}
         ]}}}
    """.trimIndent()

    private companion object {
        const val ADMITTED = "ADMITTED"
    }
}
