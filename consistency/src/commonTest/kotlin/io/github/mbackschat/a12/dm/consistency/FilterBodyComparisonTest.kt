package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of "a comparison inside a `Having` filter body is judged as one outside it".
 *
 * Mirrors `rulekit.validate.laws.FilterBodyComparisonLawsTest` row for row: each comparison family's defining
 * refusal, written over fields of the filtered repetition so the body reads only the row it filters. A row's
 * verdict is the model's COMPLETE class set, as the laws half's is, so a class the checker adds beside the one
 * A12 names fails here too.
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
        val actual = rows.keys.associateWith { classes("Sum(Claims*/Paid Having $it) > 0") }
        assertEquals(rows, actual, "each comparison family's refusal, inside a filter body")
    }

    @Test
    fun aFilterBodysComparisonOutranksTheFilteredSpecsOwnClass() {
        val rows = linkedMapOf(
            "Sum(Claims/Paid Having [Claims/Units] > 5) > 0" to "MVK_NO_WILDCARD",
            "Sum(Claims/Paid Having [Claims/Notes] < [Claims/Units]) > 0" to "MVK_INVALID_TYPE_FOR_COMPARISON",
            // Divergence locks, CONSISTENCY-GAPS DG90: A12 refuses a nested filter by the construct gate, and the
            // nested operand by its own chain; the checker's filter walk declines a nested filter outright.
            "Sum(Claims*/Paid Having Sum(Claims*/Units Having [Claims/Units] > 5) > 0) > 0" to ADMITTED,
            "Sum(Claims*/Paid Having Sum(Claims*/Units Having [Claims/Notes] < [Claims/Units]) > 0) > 0" to
                "MVK_INVALID_TYPE_FOR_COMPARISON",
            "Sum(Claims*/Paid Having Sum(Claims*/Units Having [Reference] > 5) > 0) > 0" to
                "MVK_INVALID_TYPE_FOR_COMPARISON",
            "Sum(Claims*/Paid Having Sum(Claims/Units Having [Claims/Units] > 5) > 0) > 0" to ADMITTED,
            "Sum(Claims*/Paid Having Sum(Claims/Units Having [Claims/Notes] < [Claims/Units]) > 0) > 0" to
                "MVK_INVALID_TYPE_FOR_COMPARISON",
            "Sum(Claims*/Paid* Having [Claims/Units] > 5) > 0" to "MVK_INVALID_WILDCARD",
            "Sum(Claims*/Paid* Having [Claims/Notes] < [Claims/Units]) > 0" to "MVK_INVALID_WILDCARD",
        )
        val actual = rows.keys.associateWith { classes(it) }
        assertEquals(rows, actual, "a filter body's comparison beside the filtered spec's own class")
    }

    @Test
    fun whereTheFilterChainRaisesAroundItsBody() {
        val rows = linkedMapOf(
            "NumberOfFilledGroups(Claims* Having [Claims/Notes] < [Claims/Units]) > 0" to NO_GROUPS,
            "NumberOfFilledGroups(Claims* Having [NoSuchField] == \"x\") > 0" to NO_GROUPS,
            "NumberOfFilledGroups(Claims* Having [Claims/Units] + ) > 0" to NO_GROUPS,
            "Sum(Claims*/Paid For Insured Having [Insured/Name] < [Insured/Count]) > 0" to NO_GROUPS,
            "Sum(Claims*/Paid Having [Parties/Name] == \"x\") > 0" to "MVK_INVALID_ITERATION_IN_FILTER_CONDITION",
            "Sum(Claims*/Paid Having [Parties/Name] < [Parties/Count]) > 0" to "MVK_INVALID_TYPE_FOR_COMPARISON",
            "Sum(Claims*/Paid Having [Parties/Name] == [Parties/Count]) > 0" to "MVK_INVALID_COMPARE_TO_ENUM_OR_STRING",
            "Sum(Claims*/Paid Having [Parties/Count] == [Claims/Paid]) > 0" to "MVK_INVALID_COMPARE_DEC_PLACES",
            "Sum(Orders*/Units For Parties*/Name Having [Parties/Name] == \"x\") > 0" to
                "MVK_SEMANTIC_INDEX_WITH_WILDCARD",
            "Sum(Orders*/Units For Parties*/Name Having [Parties/Name] < [Parties/Count]) > 0" to
                "MVK_INVALID_TYPE_FOR_COMPARISON",
            // A filter an index key owns is walked against the key, while the key is parsed and so ahead of
            // the key's own star class.
            "Sum(Orders*/Units For Parties*/Name Having [Orders/Note] == \"x\") > 0" to
                "MVK_INVALID_ITERATION_IN_FILTER_CONDITION",
            "Sum(Orders*/Units For Parties*/Name Having [Orders/Note] < [Orders/Units]) > 0" to
                "MVK_INVALID_TYPE_FOR_COMPARISON",
            "Sum(Orders*/Units For Reference Having [Orders/Note] == \"x\") > 0" to "MVK_WILDCARDS_REQUIRED",
            "Sum(Orders*/Units For Reference Having [Orders/Note] < [Orders/Units]) > 0" to
                "MVK_INVALID_TYPE_FOR_COMPARISON",
            "Sum(Orders*/Lines*/Amount Having Sum(Orders*/Units Having $LINE_COMPARISON) > 0) > 0" to
                "MVK_INVALID_TYPE_FOR_COMPARISON",
            "Sum(Orders*/Units Having Sum(Orders*/Lines*/Amount Having $LINE_COMPARISON) > 0) > 0" to
                "MVK_INVALID_TYPE_FOR_COMPARISON",
            // Divergence lock, CONSISTENCY-GAPS DG90: A12 refuses an aggregate in a body by its own chain; the
            // checker's filter walk declines an aggregate there.
            "Sum(Claims*/Paid Having Sum(Claims*/Units) > 0) > 0" to ADMITTED,
        )
        val actual = rows.keys.associateWith { classes(it) }
        assertEquals(rows, actual, "where the filter chain is raised around its body")
    }

    /** The model's complete A12 class set, sorted, or [ADMITTED]. */
    private fun classes(probe: String): String = check(ModelSource(model(json("FieldFilled(Reference) And $probe"))))
        .findings.filter { it.category == FindingCategory.A12_CONSTRAINT }
        .map { it.code }.distinct().sorted().joinToString(",").ifEmpty { ADMITTED }

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
            {"type":"Group","id":"g2","name":"Insured","Group":{"elements":[
              {"type":"Field","id":"f14","name":"Name","Field":{"fieldType":{"type":"StringType"}}},
              {"type":"Field","id":"f15","name":"Count","Field":{"fieldType":{"type":"NumberType",
                "NumberType":{"maxFractionalDigits":0}}}}
            ]}},
            {"type":"Group","id":"g3","name":"Parties","Group":{"repeatability":4,"elements":[
              {"type":"Field","id":"f16","name":"Name","Field":{"fieldType":{"type":"StringType"}}},
              {"type":"Field","id":"f17","name":"Count","Field":{"fieldType":{"type":"NumberType",
                "NumberType":{"maxFractionalDigits":0}}}}
            ]}},
            {"type":"Group","id":"g4","name":"Orders","Group":{"repeatability":3,"indexFieldName":"OrderNo","elements":[
              {"type":"Field","id":"f18","name":"OrderNo","Field":{"fieldType":{"type":"StringType"}}},
              {"type":"Field","id":"f19","name":"Note","Field":{"fieldType":{"type":"StringType"}}},
              {"type":"Field","id":"f20","name":"Units","Field":{"fieldType":{"type":"NumberType",
                "NumberType":{"maxFractionalDigits":0}}}},
              {"type":"Group","id":"g5","name":"Lines","Group":{"repeatability":3,"elements":[
                {"type":"Field","id":"f21","name":"Amount","Field":{"fieldType":{"type":"NumberType",
                  "NumberType":{"maxFractionalDigits":2}}}},
                {"type":"Field","id":"f22","name":"Memo","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f23","name":"Qty","Field":{"fieldType":{"type":"NumberType",
                  "NumberType":{"maxFractionalDigits":0}}}}
              ]}}
            ]}},
            {"type":"Rule","id":"r0","name":"Probe","Rule":{
              "errorCondition":"$condition","errorEntityRelPath":"../Reference",
              "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"see it"}]}}
          ]}}
         ]}}}
    """.trimIndent()

    private companion object {
        const val ADMITTED = "ADMITTED"
        const val NO_GROUPS = "MVK_NO_GROUPS_ALLOWED"
        const val LINE_COMPARISON = "[Orders/Lines/Memo] < [Orders/Lines/Qty]"
    }
}
