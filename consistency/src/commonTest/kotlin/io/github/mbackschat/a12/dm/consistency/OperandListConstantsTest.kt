package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of the numeric operand-list constant budget.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.OpListMathConstantLawsTest`. The admitted rows carry the weight: a nested call and
 * an arithmetic operand each hold a literal that does NOT count against the outer call, so a reader counting
 * literals over the condition text would refuse two shapes A12 accepts.
 */
class OperandListConstantsTest {

    private val tooMany = "MVK_TOO_MANY_CONST_FOR_CALC_OP"

    @Test
    fun oneLiteralPerCallAndNoFlattening() {
        val actual = mutableMapOf<String, List<String>>()
        actual["one literal — the control"] = codes("Min([Quantity], 1) >= 0")
        actual["two literals"] = codes("Min([Quantity], 1, 2) >= 0")
        actual["two literals under Max"] = codes("Max([Quantity], 1, 2) >= 0")
        actual["a bracketed literal counts"] = codes("Min([Quantity], {1}, 2) >= 0")
        actual["one bracketed literal alone — the control"] = codes("Min([Quantity], {1}) >= 0")
        actual["a decimal literal beside another"] = codes("Min([Quantity], 1.5, 2) >= 0")
        actual["a negative literal beside another"] = codes("Min([Quantity], -1, 2) >= 0")
        actual["a literal inside arithmetic"] = codes("Min([Quantity] + 1, 2) >= 0")
        actual["a literal inside a NESTED call"] = codes("Min(Min([Quantity], 1), 2) >= 0")
        actual["…and across two selectors"] = codes("Min(Max([Quantity], 1), 2) >= 0")
        actual["a completed call in surrounding arithmetic"] = codes("Min([Quantity], 1) + 1 >= 0")
        actual["the AGGREGATE selector, which is a different operator"] =
            codes("MinValue(Lines*/Qty) >= 0")

        val expected = mutableMapOf<String, List<String>>()
        expected["one literal — the control"] = emptyList()
        expected["two literals"] = listOf(tooMany)
        expected["two literals under Max"] = listOf(tooMany)
        expected["a bracketed literal counts"] = listOf(tooMany)
        expected["one bracketed literal alone — the control"] = emptyList()
        expected["a decimal literal beside another"] = listOf(tooMany)
        expected["a negative literal beside another"] = listOf(tooMany)
        // The two rows a text-level literal count would get wrong, both admitted by A12.
        expected["a literal inside arithmetic"] = emptyList()
        expected["a literal inside a NESTED call"] = emptyList()
        expected["…and across two selectors"] = emptyList()
        expected["a completed call in surrounding arithmetic"] = emptyList()
        expected["the AGGREGATE selector, which is a different operator"] = emptyList()
        assertEquals(expected, actual, "the per-call constant budget")
    }

    /** Only this family's code, so another family's finding cannot make a row look right. */
    private fun codes(operation: String): List<String> =
        checkRead(ModelSource(model(operation))).findings
            .filter { it.code == tooMany }
            .map { it.code }

    private fun model(operation: String): String = """
        {"header":{"id":"order","locales":[{"code":"en_US"}]},
         "content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Quantity","Field":{"fieldType":{"type":"NumberType"}}},
            {"type":"Group","id":"g1","name":"Lines","Group":{"repeatability":3,"elements":[
              {"type":"Field","id":"f1","name":"Qty","Field":{"fieldType":{"type":"NumberType"}}}
            ]}},
            {"type":"Rule","id":"r0","name":"Probe","Rule":{
              "errorCondition":"FieldFilled(Quantity) And $operation",
              "errorEntityRelPath":"../Quantity",
              "errorCode":"C0","errorMessage":[{"locale":"en_US","text":"x"}]}}
          ]}}
         ]}}}
    """.trimIndent()
}
