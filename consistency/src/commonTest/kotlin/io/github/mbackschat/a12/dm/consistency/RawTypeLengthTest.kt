package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of "what `Length` over a raw-type field may look like".
 *
 * Every row mirrors one measured against the real Kernel by `rulekit.validate.laws.NvvRawTypeLawsTest`,
 * including its complement grid — the layouts an author writes that the accepted set does not reach. The
 * parenthesised row is the one that decides the implementation: A12 admits it, so a checker matching the
 * accepted shape against the raw token stream would refuse a legal model.
 */
class RawTypeLengthTest {

    private val badShape = "MVK_INVALID_LENGTH_OF_RAW_TYPE"
    private val badConstant = "MVK_INTERNAL_ERROR"

    private fun ruleSaying(condition: String, target: String = "../IncidentNote"): List<String> =
        checkRead(ModelSource(model(condition = condition, errorField = target))).findings.map { it.code }

    @Test
    fun onlyTheWholeConditionMaxLengthDeclarationIsLeftAlone() {
        val actual = mutableMapOf<String, List<String>>()
        actual["`Length(f) > c` — the accepted shape"] = ruleSaying("Length(IncidentNote) > 5")
        actual["mirrored `c < Length(f)`"] = ruleSaying("-1 < Length(IncidentNote)")
        actual["parenthesised"] = ruleSaying("(Length(IncidentNote) > 5)")
        actual["inside an And"] = ruleSaying("Length(IncidentNote) > 5 And FieldFilled(Other)")
        actual["inside an Or"] = ruleSaying("Length(IncidentNote) > 5 Or FieldFilled(Other)")
        actual["a non-strict comparator"] = ruleSaying("Length(IncidentNote) >= 5")
        actual["the comparison reversed"] = ruleSaying("Length(IncidentNote) < 5")
        actual["mirrored, reversed"] = ruleSaying("5 > Length(IncidentNote)")
        actual["an ORDINARY field, inside an Or — the control"] =
            ruleSaying("Length(Other) > 5 Or FieldFilled(Other)", target = "../Other")
        actual["an ORDINARY field, non-strict — the control"] =
            ruleSaying("Length(Other) >= 5", target = "../Other")
        actual["a presence check on the raw field — the control"] =
            ruleSaying("FieldNotFilled(IncidentNote)")

        val expected = mutableMapOf<String, List<String>>()
        expected["`Length(f) > c` — the accepted shape"] = emptyList()
        expected["mirrored `c < Length(f)`"] = emptyList()
        // A12 admits redundant parentheses, so this row is what stops the family matching raw tokens.
        expected["parenthesised"] = emptyList()
        expected["inside an And"] = listOf(badShape)
        expected["inside an Or"] = listOf(badShape)
        expected["a non-strict comparator"] = listOf(badShape)
        expected["the comparison reversed"] = listOf(badShape)
        expected["mirrored, reversed"] = listOf(badShape)
        // The restriction is the FIELD's, not the layout's — an ordinary string carries neither gate.
        expected["an ORDINARY field, inside an Or — the control"] = emptyList()
        expected["an ORDINARY field, non-strict — the control"] = emptyList()
        expected["a presence check on the raw field — the control"] = emptyList()
        assertEquals(expected, actual, "what Length over a raw-type field may look like")
    }

    /** The constant gate, which the shape gate pre-empts — measured in that precedence, not assumed. */
    @Test
    fun theConstantMustBeADecimalPointFreeSignedThirtyTwoBitInteger() {
        val actual = mutableMapOf<String, List<String>>()
        actual["both endpoints — the control"] = ruleSaying("Length(IncidentNote) > -2147483648")
        actual["a decimal point"] = ruleSaying("Length(IncidentNote) > 5.0")
        actual["a decimal point, mirrored"] = ruleSaying("0.5 < Length(IncidentNote)")
        actual["one step over the cap"] = ruleSaying("Length(IncidentNote) > 2147483648")
        actual["one step under the floor, mirrored"] = ruleSaying("-2147483649 < Length(IncidentNote)")
        actual["a bad constant AND a bad shape"] = ruleSaying("Length(IncidentNote) >= 5.0")

        val expected = mutableMapOf<String, List<String>>()
        expected["both endpoints — the control"] = emptyList()
        expected["a decimal point"] = listOf(badConstant)
        expected["a decimal point, mirrored"] = listOf(badConstant)
        expected["one step over the cap"] = listOf(badConstant)
        expected["one step under the floor, mirrored"] = listOf(badConstant)
        // The SHAPE gate runs first in A12, so reporting the constant here would name a code A12 never reached.
        expected["a bad constant AND a bad shape"] = listOf(badShape)
        assertEquals(expected, actual, "the raw-type Length constant domain")
    }

    /** A computation may not reach a raw-type field through `Length` at all, in any of its members. */
    @Test
    fun aComputationMayNotTakeTheLengthOfARawTypeFieldInAnyMember() {
        val actual = mutableMapOf<String, List<String>>()
        actual["as the operation"] = computing(operation = "Length(IncidentNote)")
        actual["as an alternative's precondition"] =
            computing(operation = "1", precondition = "Length(IncidentNote) > 5")
        actual["as the common precondition"] =
            computing(operation = "1", commonPrecondition = "Length(IncidentNote) > 5")
        actual["an ORDINARY field's length — the control"] = computing(operation = "Length(Other)")

        val expected = mutableMapOf<String, List<String>>()
        // Even the shape a RULE admits is refused here: the max-length declaration is a rule-only reading.
        expected["as the operation"] = listOf(badShape)
        expected["as an alternative's precondition"] = listOf(badShape)
        expected["as the common precondition"] = listOf(badShape)
        expected["an ORDINARY field's length — the control"] = emptyList()
        assertEquals(expected, actual, "a computation may not take the length of a raw-type field")
    }

    /** The finding points at the declaration and names the member, since the fix is to rewrite that text. */
    @Test
    fun theFindingNamesTheMemberAndTheRawField() {
        val finding = checkRead(ModelSource(model(condition = "Length(IncidentNote) >= 5")))
            .findings.single()
        assertEquals("/Claim/Probe", finding.at)
        assertEquals(true, finding.explain.contains("errorCondition"), finding.explain)
        assertEquals(true, finding.explain.contains("/Claim/IncidentNote"), finding.explain)
    }

    private fun computing(
        operation: String,
        precondition: String? = null,
        commonPrecondition: String? = null,
    ): List<String> = checkRead(ModelSource(model(operation = operation, precondition = precondition,
        commonPrecondition = commonPrecondition))).findings.map { it.code }

    /**
     * A claims model carrying one raw-type string, one ordinary string, and either a rule or a computation.
     *
     * One builder for both carriers because the rule/computation split IS the comparison: A12 admits the
     * max-length declaration in a rule and refuses the same text in a computation.
     */
    private fun model(
        condition: String? = null,
        errorField: String = "../IncidentNote",
        operation: String? = null,
        precondition: String? = null,
        commonPrecondition: String? = null,
    ): String {
        val declaration = if (condition != null) {
            """{"type":"Rule","id":"r0","name":"Probe","Rule":{
                 "errorCondition":"$condition","errorEntityRelPath":"$errorField",
                 "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}"""
        } else {
            val common = commonPrecondition?.let { """"commonPrecondition":"$it",""" } ?: ""
            val guard = precondition?.let { """"precondition":"$it",""" } ?: ""
            """{"type":"Computation","id":"c0","name":"Probe","Computation":{
                 "computedFieldRelPath":"../Len",$common
                 "computationAlternatives":[{$guard"operation":"$operation"}],
                 "errorMessage":[{"locale":"en_US","text":"x"}]}}"""
        }
        return """
            {"header":{"id":"claim","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Claim","Group":{"elements":[
                {"type":"Field","id":"f0","name":"IncidentNote","Field":{"fieldType":{"type":"StringType",
                  "StringType":{"noValueValidation":true,"lineBreaksPermitted":true}}}},
                {"type":"Field","id":"f1","name":"Other","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f2","name":"Len","Field":{"fieldType":{"type":"NumberType"}}},
                $declaration
              ]}}
             ]}}}
        """.trimIndent()
    }
}
