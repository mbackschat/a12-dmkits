package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of "a comparison inside a `Having` filter body is judged as one outside it".
 *
 * Mirrors `rulekit.validate.laws.FilterBodyComparisonLawsTest` row for row: each comparison family's defining
 * refusal, written over fields of the filtered repetition so the body reads only the row it filters. The
 * marked-read rows mirror `adapter.laws.CheckerFilterBodyAgreementTest`, which measures them on the kernel. A row's
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
            // A nested filter is walked with the body and refused by the construct gate after it; an unstarred
            // nested operand is read once its own filter has cleared A12's one filter flag, as the rule's read.
            "Sum(Claims*/Paid Having Sum(Claims*/Units Having [Claims/Units] > 5) > 0) > 0" to
                "MVK_DISALLOWED_CONSTRUCT_IN_SET_EXPRESSION",
            "Sum(Claims*/Paid Having Sum(Claims*/Units Having [Claims/Notes] < [Claims/Units]) > 0) > 0" to
                "MVK_INVALID_TYPE_FOR_COMPARISON",
            "Sum(Claims*/Paid Having Sum(Claims*/Units Having [Reference] > 5) > 0) > 0" to
                "MVK_INVALID_TYPE_FOR_COMPARISON",
            "Sum(Claims*/Paid Having Sum(Claims/Units Having [Claims/Units] > 5) > 0) > 0" to "MVK_NO_WILDCARD",
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
            "NumberOfFilledGroups(Claims* Having [Claims/Units] > 5) > 0" to NO_GROUPS,
            "NumberOfFilledGroups(Claims* Having [Claims/Notes] < [Claims/Units]) > 0" to "MVK_INVALID_TYPE_FOR_COMPARISON",
            "NumberOfFilledGroups(Claims* Having [NoSuchField] == \"x\") > 0" to "MVK_INVALID_ENTITY",
            "NumberOfFilledGroups(Claims* Having [Claims/Units] + ) > 0" to "MVK_UNEXPECTED_TOKEN",
            "Sum(Claims*/Paid For Insured Having [Insured/Name] == \"x\") > 0" to NO_GROUPS,
            "Sum(Claims*/Paid For Insured Having [Insured/Name] < [Insured/Count]) > 0" to "MVK_INVALID_TYPE_FOR_COMPARISON",
            "Sum(Claims*/Paid Having [Parties/Name] == \"x\") > 0" to "MVK_INVALID_ITERATION_IN_SET_EXPRESSION",
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
                "MVK_INVALID_ITERATION_IN_SET_EXPRESSION",
            "Sum(Orders*/Units For Parties*/Name Having [Orders/Note] < [Orders/Units]) > 0" to
                "MVK_INVALID_TYPE_FOR_COMPARISON",
            "Sum(Orders*/Units For Reference Having [Orders/Note] == \"x\") > 0" to "MVK_WILDCARDS_REQUIRED",
            "Sum(Orders*/Units For Reference Having [Orders/Note] < [Orders/Units]) > 0" to
                "MVK_INVALID_TYPE_FOR_COMPARISON",
            "Sum(Orders*/Lines*/Amount Having Sum(Orders*/Units Having $LINE_COMPARISON) > 0) > 0" to
                "MVK_INVALID_TYPE_FOR_COMPARISON",
            "Sum(Orders*/Units Having Sum(Orders*/Lines*/Amount Having $LINE_COMPARISON) > 0) > 0" to
                "MVK_INVALID_TYPE_FOR_COMPARISON",
            // An aggregate in a body is one more spec the walk reads; a starred one binds no level.
            "Sum(Claims*/Paid Having Sum(Claims*/Units) > 0) > 0" to "MVK_NO_ITERATION_FOR_WILDCARD",
        )
        val actual = rows.keys.associateWith { classes(it) }
        assertEquals(rows, actual, "where the filter chain is raised around its body")
    }

    @Test
    fun aMarkedReadInABodyIsTheRulesOwnRead() {
        val fromTheRoot = linkedMapOf(
            "Sum(Claims*/Paid Having [Claims/Units] > [\$Claims/Units]) > 0" to "MVK_NO_WILDCARD",
            "Sum(Claims*/Paid Having Sum(Claims*/Units Having [Claims/Units] > [\$Claims/Units]) > 0) > 0" to
                "MVK_NO_WILDCARD",
            "Sum(Claims*/Paid Having [\$Reference] == \"x\" And Sum(Claims*/Units Having [Claims/Units] > 5) > 0) > 0" to
                "MVK_DISALLOWED_CONSTRUCT_IN_SET_EXPRESSION",
        )
        assertEquals(fromTheRoot, fromTheRoot.keys.associateWith { classes(it) }, "a marked read, from the root")

        // Reporting inside `Claims`, so the rule iterates it and a marked read of it is at the rule's coordinates.
        val insideAClaim = linkedMapOf(
            "Sum(Parties*/Count Having [\$Claims/Notes] == \"x\") > 0" to "MVK_NO_ITERATION_FOR_WILDCARD",
            "Sum(Parties*/Count Having [Claims/Notes] == \"x\" And [\$Claims/Notes] == \"x\") > 0" to
                "MVK_INVALID_ITERATION_IN_SET_EXPRESSION",
            "Sum(Parties*/Count Having [Parties/Name] == [\$Orders/Note]) > 0" to "MVK_ERROR_FIELD_NO_INDEX",
            "Sum(Parties*/Count Having [Orders/Note] == \"y\" And [\$Orders/Note For \"A\"] == \"x\") > 0" to
                "MVK_INVALID_ITERATION_IN_SET_EXPRESSION",
            "Sum(Parties*/Count Having Sum(Parties*/Count Having [Parties/Name] == [\$Claims/Notes]) > 0) > 0" to
                "MVK_DISALLOWED_CONSTRUCT_IN_SET_EXPRESSION",
        )
        assertEquals(insideAClaim, insideAClaim.keys.associateWith { classes(it, "Claims/Notes") },
            "a marked read, from a rule iterating Claims")
    }

    /** The model's complete A12 class set, sorted, or [ADMITTED], for a rule reporting on [target]. */
    private fun classes(probe: String, target: String = "Reference"): String =
        checkRead(ModelSource(model(json("FieldFilled($target) And $probe"), target)))
            .findings.filter { it.category == FindingCategory.A12_CONSTRAINT }
            .map { it.code }.distinct().sorted().joinToString(",").ifEmpty { ADMITTED }

    /** A condition as the model text carries it. */
    private fun json(text: String) = text.replace("\\", "\\\\").replace("\"", "\\\"")

    /** The claims policy of the laws test. */
    private fun model(condition: String, target: String): String = """
        {"header":{"modelVersion":"29.4.0","id":"claims-policy","locales":[{"code":"en_US"}]},
         "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
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
              "errorCondition":"$condition","errorEntityRelPath":"../$target",
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
