package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of everything `CheckOperationImpl.check` asks of an arithmetic expression.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.CalculationOperatorArityLawsTest` and
 * `rulekit.validate.laws.ArithmeticOperandKindLawsTest`. The two ORDERED rows are what the arity half costs:
 * A12 checks each operation context as it parses, so an expression violating both gates draws whichever
 * violation is written first — a reader asking the two questions in a fixed order is wrong in one order.
 *
 * The PRE-EMPTION row is what holds the two halves together: a kind defect inside one context silences that
 * context's arity gates, so a checker asking both would report a class A12 never reaches.
 */
class ArithmeticOperationGatesTest {

    private val invalidExponent = "MVK_INVALID_NUMBER_FOR_EXP"
    private val tooManyPow = "MVK_TOO_MANY_POW_FOR_CALC"
    private val tooManyDiv = "MVK_TOO_MANY_DIV_FOR_CALC"
    private val notANumber = "MVK_NO_NUMBER_FOR_CALC"
    private val mixedAdd = "MVK_NO_STRING_OR_NUMBER_FOR_ADD"

    /** A billing model whose `BaseFee` declares two fractional digits and `Loose` declares none at all. */
    private fun codes(declaration: String): List<String> = checkRead(
        ModelSource(
            """
            {"header":{"id":"billing","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Billing","Group":{"elements":[
                {"type":"Field","id":"f0","name":"BaseFee","Field":{"fieldType":{"type":"NumberType",
                  "NumberType":{"maxFractionalDigits":2}}}},
                {"type":"Field","id":"f1","name":"Loose","Field":{"fieldType":{"type":"NumberType"}}},
                {"type":"Field","id":"f2","name":"EffectiveFee","Field":{"fieldType":{"type":"NumberType",
                  "NumberType":{"maxFractionalDigits":2}}}},
                {"type":"Field","id":"f3","name":"Notes","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f4","name":"Serial","Field":{"fieldType":{"type":"CustomFieldType",
                  "CustomFieldType":{"name":"BillingSerial"}}}},
                {"type":"Field","id":"f5","name":"IssuedOn","Field":{"fieldType":{"type":"DateType",
                  "DateType":{"format":"yyyy-MM-dd"}}}},
                {"type":"Field","id":"f6","name":"PlainChannel","Field":{"fieldType":{
                  "type":"EnumerationType","EnumerationType":{"values":[{"value":"WEB"},{"value":"STORE"}]}}}},
                {"type":"Field","id":"f7","name":"LabelledChannel","Field":{"fieldType":{
                  "type":"EnumerationType","EnumerationType":{"values":[
                    {"value":"WEB","label":[{"locale":"en_US","text":"Web shop"}]},
                    {"value":"STORE","label":[{"locale":"en_US","text":"In store"}]}]}}}},
                $declaration
              ]}}
             ]}}}
            """.trimIndent(),
        ),
    ).findings.map { it.code }

    private fun computed(operation: String) = codes(
        """{"type":"Computation","id":"c0","name":"FeeCalc","Computation":{
              "computedFieldRelPath":"../EffectiveFee",
              "computationAlternatives":[{"operation":"${operation.replace("\"", "\\\"")}"}],
              "errorMessage":[{"locale":"en_US","text":"x"}]}}""",
    )

    /**
     * A condition that also READS the rule's error target, so the unreferenced-target family stays out of the
     * row — every condition whose arithmetic does not happen to name `BaseFee` needs it.
     */
    private fun guarded(condition: String) = ruled("FieldFilled(BaseFee) And $condition")

    private fun ruled(condition: String) = codes(
        """{"type":"Rule","id":"r0","name":"Probe","Rule":{
              "errorCondition":"${condition.replace("\"", "\\\"")}",
              "errorEntityRelPath":"../BaseFee","errorCode":"C1",
              "errorMessage":[{"locale":"en_US","text":"x"}]}}""",
    )

    @Test
    fun howManyDivisionsAndPowersOneExpressionMayCarry() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a product — the control"] = computed("RoundDown([BaseFee] * 2, 2)")
        actual["a whole-number literal exponent"] = computed("RoundDown([BaseFee] ^ 2, 2)")
        actual["a DECIMAL literal exponent"] = computed("RoundDown([BaseFee] ^ 2.5, 2)")
        actual["a FIELD exponent declaring 2 fractional digits"] =
            computed("RoundDown([BaseFee] ^ [BaseFee], 2)")
        actual["a FIELD exponent declaring no maxFractionalDigits"] =
            computed("RoundDown([BaseFee] ^ [Loose], 2)")
        actual["a nested power"] = computed("RoundDown([BaseFee] ^ 2 ^ 2, 2)")
        actual["a bracketed nested power"] = computed("RoundDown({[BaseFee] ^ 2} ^ 2, 2)")
        actual["a nested power whose own exponent is decimal"] =
            computed("RoundDown([BaseFee] ^ 2 ^ 2.5, 2)")
        actual["two divisions in one chain"] = computed("RoundDown([BaseFee] / 2 / 4, 2)")
        actual["two divisions separated by a plus"] =
            computed("RoundDown([BaseFee] / 2 + [BaseFee] / 4, 2)")
        actual["two divisions, the inner bracketed"] =
            computed("RoundDown([BaseFee] / {[BaseFee] / 4}, 2)")
        actual["a division then a multiplication"] = computed("RoundDown([BaseFee] / 2 * 4, 2)")
        actual["a nested power beside two divisions"] =
            computed("RoundDown([BaseFee] ^ 2 ^ 2 + [BaseFee] / 2 / 4, 2)")
        actual["the same two, the divisions written FIRST"] =
            computed("RoundDown([BaseFee] / 2 / 4 + [BaseFee] ^ 2 ^ 2, 2)")

        val expected = mutableMapOf<String, List<String>>()
        expected["a product — the control"] = emptyList()
        expected["a whole-number literal exponent"] = emptyList()
        expected["a DECIMAL literal exponent"] = listOf(invalidExponent)
        expected["a FIELD exponent declaring 2 fractional digits"] = listOf(invalidExponent)
        // Silent, and the kernel side is a SOURCE READ rather than a measurement: `checkNoFractionalDigits
        // InExponent` refuses a null scale as well as a positive one, and the estate's fixtures cannot
        // author a number field without the member. Acting on an unmeasured reading would be the one
        // direction this checker may not take, so the shape is left to A12.
        expected["a FIELD exponent declaring no maxFractionalDigits"] = emptyList()
        expected["a nested power"] = listOf(tooManyPow)
        expected["a bracketed nested power"] = emptyList()
        expected["a nested power whose own exponent is decimal"] = listOf(invalidExponent)
        expected["two divisions in one chain"] = listOf(tooManyDiv)
        expected["two divisions separated by a plus"] = emptyList()
        expected["two divisions, the inner bracketed"] = emptyList()
        expected["a division then a multiplication"] = emptyList()
        expected["a nested power beside two divisions"] = listOf(tooManyPow)
        expected["the same two, the divisions written FIRST"] = listOf(tooManyDiv)
        assertEquals(expected, actual, "how many divisions and powers one expression may carry")
    }

    /** The RULE carrier: the same gates read the same way out of a condition's arithmetic. */
    @Test
    fun theSameGatesAnswerInsideARuleCondition() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a product — the control"] = ruled("[BaseFee] * 2 > 0")
        actual["two divisions in one chain"] = ruled("[BaseFee] / 2 / 4 > 0")
        actual["a nested power"] = ruled("[BaseFee] ^ 2 ^ 2 > 0")
        actual["a DECIMAL literal exponent"] = ruled("[BaseFee] ^ 2.5 > 0")

        val expected = mutableMapOf<String, List<String>>()
        expected["a product — the control"] = emptyList()
        expected["two divisions in one chain"] = listOf(tooManyDiv)
        expected["a nested power"] = listOf(tooManyPow)
        expected["a DECIMAL literal exponent"] = listOf(invalidExponent)
        assertEquals(expected, actual, "the same gates answer inside a rule condition")
    }

    @Test
    fun whatEachOperatorAdmitsAsAnOperand() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a text under a product"] = guarded("[BaseFee] * [Notes] > 0")
        actual["a date under a subtraction"] = guarded("[BaseFee] - [IssuedOn] > 0")
        actual["a custom type under a division"] = guarded("[BaseFee] / [Serial] > 0")
        actual["two numbers added"] = guarded("{[BaseFee] + [Loose]} > 0")
        actual["two texts joined"] = guarded("{[Notes] + [Serial]} == \"x\"")
        actual["a text joined to a labelless enumeration"] = guarded("{[Notes] + [PlainChannel]} == \"x\"")
        actual["a text joined to a LABELLED enumeration"] = guarded("{[Notes] + [LabelledChannel]} == \"x\"")
        actual["a number added to a text"] = guarded("{[BaseFee] + [Notes]} > 0")
        actual["a concatenation subtracted from"] = guarded("{{[Notes] + [Serial]} - [BaseFee]} > 0")
        actual["a product added to a number"] = guarded("{{[BaseFee] * [Loose]} + [BaseFee]} > 0")

        val expected = mutableMapOf<String, List<String>>()
        expected["a text under a product"] = listOf(notANumber)
        expected["a date under a subtraction"] = listOf(notANumber)
        expected["a custom type under a division"] = listOf(notANumber)
        expected["two numbers added"] = emptyList()
        expected["two texts joined"] = emptyList()
        expected["a text joined to a labelless enumeration"] = emptyList()
        // The only difference between this row and the one above it is that the enumeration carries labels.
        expected["a text joined to a LABELLED enumeration"] = listOf(mixedAdd)
        expected["a number added to a text"] = listOf(mixedAdd)
        expected["a concatenation subtracted from"] = listOf(notANumber)
        expected["a product added to a number"] = emptyList()
        assertEquals(expected, actual, "what each arithmetic operator admits, and what `+` does instead")
    }

    /** The handover: a kind defect inside one context silences that context's arity gates. */
    @Test
    fun theKindWalkSilencesThisContextsArityGates() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a text in a two-division chain"] = guarded("[Notes] / 2 / 4 > 0")
        actual["the same chain, all numbers"] = guarded("[BaseFee] / 2 / 4 > 0")
        actual["a text in a nested power"] = guarded("[Notes] ^ 2 ^ 2 > 0")
        actual["the same power, all numbers"] = guarded("[BaseFee] ^ 2 ^ 2 > 0")

        val expected = mutableMapOf<String, List<String>>()
        expected["a text in a two-division chain"] = listOf(notANumber)
        expected["the same chain, all numbers"] = listOf(tooManyDiv)
        expected["a text in a nested power"] = listOf(notANumber)
        expected["the same power, all numbers"] = listOf(tooManyPow)
        assertEquals(expected, actual, "the kind class answers alone where an arity law is also broken")
    }
}
