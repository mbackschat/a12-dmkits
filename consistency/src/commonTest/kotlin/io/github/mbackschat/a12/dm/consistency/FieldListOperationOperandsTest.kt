package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of the field-list operations' operand-kind switch.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.FieldListOperandKindLawsTest`, except the extrema's numeric arm, whose rows come
 * from `rulekit.validate.laws.ExtremumOperandKindGridLawsTest`.
 *
 * The rows that carry the file are the BOOLEAN ones: a boolean declaration clears the confirm gate that the
 * code `MVK_NO_BOOLY_ALLOWED` appears to name, so a family reading the admitted set off the class name would
 * refuse a model A12 admits.
 */
class FieldListOperationOperandsTest {

    private fun codes(condition: String): List<String> = check(
        ModelSource(
            """
            {"header":{"id":"claim","locales":[{"code":"en_US"}]},
             "content":{"typeDefinitions":[{"id":"POLICY_NUMBER_1","name":"PolicyNumber",
               "fieldType":{"type":"StringType"}}],
              "modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Claim","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f1","name":"Notes","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f2","name":"Amount","Field":{"fieldType":{"type":"NumberType",
                  "NumberType":{"maxFractionalDigits":2}}}},
                {"type":"Field","id":"f3","name":"Excess","Field":{"fieldType":{"type":"NumberType",
                  "NumberType":{"maxFractionalDigits":2}}}},
                {"type":"Field","id":"f4","name":"Settled","Field":{"fieldType":{"type":"BooleanType"}}},
                {"type":"Field","id":"f5","name":"Settled2","Field":{"fieldType":{"type":"BooleanType"}}},
                {"type":"Field","id":"f6","name":"Acknowledged","Field":{"fieldType":{"type":"ConfirmType"}}},
                {"type":"Field","id":"f7","name":"ReportedOn","Field":{"fieldType":{"type":"DateType",
                  "DateType":{"format":"yyyy-MM-dd"}}}},
                {"type":"Field","id":"f8","name":"ClosedOn","Field":{"fieldType":{"type":"DateType",
                  "DateType":{"format":"yyyy-MM-dd"}}}},
                {"type":"Field","id":"f9","name":"PolicyRef","Field":{"fieldType":{"type":"TypeDefType",
                  "TypeDefType":{"typeDefinitionId":"POLICY_NUMBER_1"}}}},
                {"type":"Group","id":"g1","name":"Items","Group":{"repeatability":20,"elements":[
                  {"type":"Field","id":"f10","name":"ItemConfirmed","Field":{
                    "fieldType":{"type":"ConfirmType"}}},
                  {"type":"Field","id":"f11","name":"ItemAmount","Field":{"fieldType":{"type":"NumberType",
                    "NumberType":{"maxFractionalDigits":2}}}}
                ]}},
                {"type":"Group","id":"g2","name":"Parts","Group":{"repeatability":20,"elements":[
                  {"type":"Field","id":"f12","name":"PartAmount","Field":{"fieldType":{"type":"NumberType",
                    "NumberType":{"maxFractionalDigits":2}}}},
                  {"type":"Field","id":"f13","name":"PartFee","Field":{"fieldType":{"type":"NumberType",
                    "NumberType":{"maxFractionalDigits":2}}}}
                ]}},
                {"type":"Rule","id":"r0","name":"Probe","Rule":{
                  "errorCondition":"CONDITION_HERE","errorEntityRelPath":"../Reference",
                  "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
              ]}}
             ]}}}
            """.trimIndent().replace("CONDITION_HERE", condition),
        ),
    ).findings.map { it.code }

    private fun subject(condition: String): List<String> = codes("FieldFilled(Reference) And $condition")

    @Test
    fun firstFilledValueRefusesAConfirmAndLeavesTheBooleanSiblingAlone() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a confirm leading"] = subject("FirstFilledValue(Acknowledged, Notes) == [Reference]")
        actual["a confirm following"] = subject("FirstFilledValue(Notes, Acknowledged) == [Reference]")
        actual["a confirm inside a group expansion"] = subject("FirstFilledValue(Items*) == [Reference]")
        actual["two booleans"] = subject("FirstFilledValue(Settled, Settled2) == [Settled]")
        actual["two text fields"] = subject("FirstFilledValue(Notes, Reference) == [Reference]")
        actual["a mixed pair this family does not judge"] = subject("FirstFilledValue(Settled, Notes) == [Reference]")

        val expected = mutableMapOf<String, List<String>>()
        expected["a confirm leading"] = listOf("MVK_NO_BOOLY_ALLOWED")
        expected["a confirm following"] = listOf("MVK_NO_BOOLY_ALLOWED")
        expected["a confirm inside a group expansion"] = listOf("MVK_NO_BOOLY_ALLOWED")
        // `FieldUtils.isYes` tests `IYesType`, and `IYesNoType` is a sibling rather than a subtype.
        expected["two booleans"] = emptyList()
        expected["two text fields"] = emptyList()
        // A12 answers `MVK_VARYING_TYPES_NOT_ALLOWED` here, which this family deliberately leaves unreported.
        expected["a mixed pair this family does not judge"] = emptyList()
        assertEquals(expected, actual, "one confirm anywhere refuses the list; a boolean never does")
    }

    @Test
    fun sumDemandsANumberOfEveryFieldAndTheExtremaOnlyBehindANonTemporalLeader() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a text field in a Sum"] = subject("Sum(Notes, Amount) > 0")
        actual["a date in a Sum"] = subject("Sum(ReportedOn, Amount) > 0")
        actual["an all-number Sum"] = subject("Sum(Amount, Excess) > 0")
        actual["an all-number group expansion"] = subject("Sum(Parts*) > 0")
        actual["a mixed group expansion"] = subject("Sum(Items*) > 0")
        actual["a text field leading an extremum"] = subject("MaxValue(Notes, Amount) == [Notes]")
        actual["a date following a number leader"] = subject("MaxValue(Amount, ReportedOn) > 0")
        actual["a temporal leader, which the component gate types"] =
            subject("MaxValue(ReportedOn, ClosedOn) == [ReportedOn]")
        actual["a typedef, whose base this reader does not resolve"] = subject("Sum(PolicyRef, Amount) > 0")

        val expected = mutableMapOf<String, List<String>>()
        expected["a text field in a Sum"] = listOf("MVK_NO_NUMBER")
        expected["a date in a Sum"] = listOf("MVK_NO_NUMBER")
        expected["an all-number Sum"] = emptyList()
        expected["an all-number group expansion"] = emptyList()
        expected["a mixed group expansion"] = listOf("MVK_NO_NUMBER")
        expected["a text field leading an extremum"] = listOf("MVK_NOT_SORTABLE")
        expected["a date following a number leader"] = listOf("MVK_NOT_SORTABLE")
        // The component-set gate owns a temporal leader, and it agrees here, so the row is silent for the
        // reason the handover names rather than because nothing looked.
        expected["a temporal leader, which the component gate types"] = emptyList()
        // A number-based typedef is admitted by both arms, so refusing one would be an over-rejection.
        expected["a typedef, whose base this reader does not resolve"] = emptyList()
        assertEquals(expected, actual, "the numeric arms, and where the leader hands the list on")
    }

    @Test
    fun theGatesAheadOfTheSwitchKeepTheirOwnClasses() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a lone unstarred confirm, which the arity gate answers"] =
            subject("FirstFilledValue(Acknowledged) == [Reference]")
        actual["a confirm named twice, which the duplicate gate answers"] =
            subject("FirstFilledValue(Acknowledged, Acknowledged) == [Reference]")
        actual["the root group, which the root-group gate answers"] = subject("Sum(/Claim) > 0")

        val expected = mutableMapOf<String, List<String>>()
        expected["a lone unstarred confirm, which the arity gate answers"] = listOf("MVK_PARAMSIZE_INVALIDN")
        expected["a confirm named twice, which the duplicate gate answers"] = listOf("MVK_DUPLICATE_PARAM1")
        expected["the root group, which the root-group gate answers"] = listOf("MVK_ROOT_GROUP_REFERENCED")
        assertEquals(expected, actual, "a gate ahead of the switch keeps its class, and this family is quiet")
    }
}
