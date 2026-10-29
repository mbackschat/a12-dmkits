package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of `SumOfProducts`' operand-pair laws.
 *
 * Every row mirrors one measured against the real kernel by `rulekit.validate.laws.SumOfProductsLawsTest`.
 * The rows that matter are the ones another gate answers first: a plainly missing star, an identical
 * starless pair, a group operand and a starred filter are all refused by A12 with a class this family does
 * not own, so reporting there would name the wrong repair on a correctly rejected model.
 */
class SumOfProductsOperandsTest {

    @Test
    fun theFourClassesInTheOrderA12DecidesThem() {
        val actual = mutableMapOf<String, List<String>>()
        actual["two parallel lists — the control"] = codes("Lines*/Qty, Lines*/Qty")
        actual["different owning groups"] = codes("Lines*/Qty, Vouchers*/Amount")
        actual["different owning groups by DEPTH"] = codes("Lines*/Qty, Lines*/Packaging/Weight")
        actual["one operand repeatable, one not"] = codes("Lines*/Qty, Fees/Handling")
        actual["no repeatable level, same owning group"] = codes("Total, Other")
        actual["no repeatable level, DIFFERENT owning groups"] = codes("Total, Fees/Handling")
        actual["two starred levels"] = codes("Lines*/Rows*/Cell, Lines*/Rows*/Cell")
        actual["a filtered operand"] = codes("""Lines*/Qty Having [Lines/Qty] > 0, Lines*/Qty""")
        actual["a filtered operand that also stars two levels"] =
            codes("""Lines*/Rows*/Cell Having [Lines/Qty] > 0, Lines*/Rows*/Cell""")
        actual["the filter on the right, after a left operand starring two levels"] =
            codes("""Lines*/Rows*/Cell, Lines*/Rows*/Cell Having [Lines/Qty] > 0""")
        actual["a filtered operand whose owning group also differs"] =
            codes("""Lines*/Qty Having [Lines/Qty] > 0, Fees/Handling""")

        val expected = mutableMapOf<String, List<String>>()
        expected["two parallel lists — the control"] = emptyList()
        expected["different owning groups"] = listOf("MVK_DIFFERENT_GROUPS")
        expected["different owning groups by DEPTH"] = listOf("MVK_DIFFERENT_GROUPS")
        expected["one operand repeatable, one not"] = listOf("MVK_DIFFERENT_GROUPS")
        expected["no repeatable level, same owning group"] = listOf("MVK_REPEATABLE_LEVEL_REQUIRED")
        // The owning-group gate runs first, so this pair never reaches the repeatable-level one.
        expected["no repeatable level, DIFFERENT owning groups"] = listOf("MVK_DIFFERENT_GROUPS")
        expected["two starred levels"] = listOf("MVK_WILDCARD_ONLY_AT_LOWEST_LEVEL_ALLOWED")
        expected["a filtered operand"] = listOf("MVK_WILDCARD_AT_LOWEST_LEVEL_REQUIRED")
        // The filter beats the multi-star, because the restriction is read at the LOWEST level first.
        expected["a filtered operand that also stars two levels"] =
            listOf("MVK_WILDCARD_AT_LOWEST_LEVEL_REQUIRED")
        // …but the operands are asked in order, so a left operand's two stars answer before a right one's filter
        // (`rulekit.validate.laws.SumOfProductsLawsTest`, cold review 2026-10-21).
        expected["the filter on the right, after a left operand starring two levels"] =
            listOf("MVK_WILDCARD_ONLY_AT_LOWEST_LEVEL_ALLOWED")
        expected["a filtered operand whose owning group also differs"] = listOf("MVK_DIFFERENT_GROUPS")
        assertEquals(expected, actual, "SumOfProducts operand-pair classes")
    }

    @Test
    fun theShapesAnotherGateAnswersFirst() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a plainly missing star"] = codes("Lines*/Qty, Lines/Packaging/Weight")
        actual["a star above the lowest repeatable level"] = codes("Lines*/Rows/Cell, Lines*/Rows/Cell")
        actual["the same starless field twice"] = codes("Total, Total")
        actual["a GROUP operand"] = codes("Lines*, Lines*/Qty")
        actual["a starred reference inside the filter"] =
            codes("""Lines*/Qty Having [Lines*/Qty] > 0, Lines*/Qty""")
        actual["one operand only"] = codes("Lines*/Qty")
        actual["an operand naming nothing declared"] = codes("Lines*/Nowhere, Lines*/Qty")
        actual["a star on the field, not its level, AND different owning groups"] =
            codes("Lines/Qty*, Vouchers*/Amount")
        actual["a star on a fixed group, one operand repeatable"] = codes("Fees*/Handling, Vouchers*/Amount")

        val expected = mutableMapOf<String, List<String>>()
        // MVK_NO_WILDCARD, from the reference reader, ahead of every gate this family owns.
        expected["a plainly missing star"] = emptyList()
        expected["a star above the lowest repeatable level"] = emptyList()
        // MVK_DUPLICATE_PARAM1 — and the identical pair WITH stars is admitted, so this is not a shape rule.
        expected["the same starless field twice"] = emptyList()
        // MVK_DUPLICATE_PARAM2: a field named beside the group that contains it, ahead of the kind gate.
        expected["a GROUP operand"] = emptyList()
        // MVK_NO_WILDCARDS_ALLOWED, the field-read gate, which `FIELD_VALUE_READ_OPERAND` already reports.
        expected["a starred reference inside the filter"] = emptyList()
        // MVK_PARAMSIZE_INVALID_2, the arity gate.
        expected["one operand only"] = emptyList()
        expected["an operand naming nothing declared"] = emptyList()
        // MVK_INVALID_WILDCARD, the resolver's, ahead of the pair comparison.
        expected["a star on the field, not its level, AND different owning groups"] = emptyList()
        expected["a star on a fixed group, one operand repeatable"] = emptyList()
        assertEquals(expected, actual, "the shapes another gate answers first")
    }

    /** Only this family's four codes, so another family's finding cannot make a row look right. */
    private fun codes(operands: String): List<String> =
        checkRead(ModelSource(model(operands))).findings
            .filter { it.code in OWNED }
            .map { it.code }

    /**
     * A basket with one repeatable line list, a nested fixed group, a nested REPEATABLE group, and two
     * plain fields — every shape the four laws separate, on one model so no row moves a second variable.
     */
    private fun model(operands: String): String = """
        {"header":{"modelVersion":"28.4.0","id":"basket","locales":[{"code":"en_US"}]},
         "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Basket","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Total","Field":{"fieldType":{"type":"NumberType"}}},
            {"type":"Field","id":"f5","name":"Other","Field":{"fieldType":{"type":"NumberType"}}},
            {"type":"Group","id":"g1","name":"Lines","Group":{"repeatability":3,"elements":[
              {"type":"Field","id":"f1","name":"Qty","Field":{"fieldType":{"type":"NumberType"}}},
              {"type":"Group","id":"g2","name":"Packaging","Group":{"elements":[
                {"type":"Field","id":"f2","name":"Weight","Field":{"fieldType":{"type":"NumberType"}}}
              ]}},
              {"type":"Group","id":"g5","name":"Rows","Group":{"repeatability":4,"elements":[
                {"type":"Field","id":"f6","name":"Cell","Field":{"fieldType":{"type":"NumberType"}}}
              ]}}
            ]}},
            {"type":"Group","id":"g3","name":"Vouchers","Group":{"repeatability":3,"elements":[
              {"type":"Field","id":"f3","name":"Amount","Field":{"fieldType":{"type":"NumberType"}}}
            ]}},
            {"type":"Group","id":"g4","name":"Fees","Group":{"elements":[
              {"type":"Field","id":"f4","name":"Handling","Field":{"fieldType":{"type":"NumberType"}}}
            ]}},
            {"type":"Rule","id":"r0","name":"Probe","Rule":{
              "errorCondition":"FieldFilled(Total) And SumOfProducts($operands) > 0",
              "errorEntityRelPath":"../Total",
              "errorCode":"C0","errorMessage":[{"locale":"en_US","text":"x"}]}}
          ]}}
         ]}}}
    """.trimIndent()

    private companion object {
        val OWNED = setOf(
            "MVK_DIFFERENT_GROUPS",
            "MVK_WILDCARD_AT_LOWEST_LEVEL_REQUIRED",
            "MVK_WILDCARD_ONLY_AT_LOWEST_LEVEL_ALLOWED",
            "MVK_REPEATABLE_LEVEL_REQUIRED",
        )
    }
}
