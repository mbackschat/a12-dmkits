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

    private fun codes(condition: String): List<String> = checkRead(
        ModelSource(
            """
            {"header":{"modelVersion":"28.4.0","id":"claim","locales":[{"code":"en_US"}]},
             "content":{"modelInfo":{"name":"model"},"typeDefinitions":[{"id":"POLICY_NUMBER_1","name":"PolicyNumber",
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
                {"type":"Field","id":"f14","name":"Severity","Field":{"fieldType":{"type":"EnumerationType",
                  "EnumerationType":{"values":[{"value":"LOW"},{"value":"HIGH"}],
                  "categories":[{"name":"Band","values":["A","B"]}]}}}},
                {"type":"Field","id":"f15","name":"Adjuster","Field":{"fieldType":{"type":"CustomFieldType",
                  "CustomFieldType":{"name":"AdjusterCode"}}}},
                {"type":"Field","id":"f16","name":"CoverWindow","Field":{"fieldType":{"type":"DateRangeType",
                  "DateRangeType":{"format":"dd.MM.yyyy","rangeSeparator":"-"}}}},
                {"type":"Field","id":"f17","name":"FreeNote","Field":{"fieldType":{"type":"StringType",
                  "StringType":{"lineBreaksPermitted":true,"noValueValidation":true}}}},
                {"type":"Group","id":"g3","name":"Ledger","Group":{"elements":[
                  {"type":"Field","id":"f18","name":"LedgerAmount","Field":{"fieldType":{"type":"NumberType",
                    "NumberType":{"maxFractionalDigits":2}}}},
                  {"type":"Field","id":"f19","name":"LedgerFee","Field":{"fieldType":{"type":"NumberType",
                    "NumberType":{"maxFractionalDigits":2}}}}
                ]}},
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
        actual["a Boolean beside text"] = subject("FirstFilledValue(Settled, Notes) == [Reference]")

        val expected = mutableMapOf<String, List<String>>()
        expected["a confirm leading"] = listOf("MVK_NO_BOOLY_ALLOWED")
        expected["a confirm following"] = listOf("MVK_NO_BOOLY_ALLOWED")
        expected["a confirm inside a group expansion"] = listOf("MVK_NO_BOOLY_ALLOWED")
        // `FieldUtils.isYes` tests `IYesType`, and `IYesNoType` is a sibling rather than a subtype.
        expected["two booleans"] = emptyList()
        expected["two text fields"] = emptyList()
        // A Boolean clears the confirm gate and then meets the type gate, which a text field beside it fails.
        expected["a Boolean beside text"] = listOf("MVK_VARYING_TYPES_NOT_ALLOWED")
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
        actual["a typedef, read as the string it defines"] = subject("Sum(PolicyRef, Amount) > 0")

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
        // A12 reads a type-defined field as the type its definition declares, here a string
        // (`rulekit.validate.laws.TypedefBaseKindGateLawsTest`, KERNEL-FINDINGS KF602).
        expected["a typedef, read as the string it defines"] = listOf("MVK_NO_NUMBER")
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

    /**
     * The fifth carrier, whose LEADER picks between three arms and a class of its own.
     *
     * Every row mirrors one measured by `rulekit.validate.laws.DistinctValueOperandKindLawsTest`. The rows
     * that carry the method are the three UNCLAIMED leaders: A12 answers them with a class of their own
     * rather than with the numeric arm's, which is what a reader folding "not string, not number, not date"
     * into the number branch would get wrong.
     */
    @Test
    fun theDistinctCountTypesItsListFromTheLeaderAndEachArmRefusesDifferently() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a text leader over a text list"] = subject("NumberOfDifferentValues(Notes, Reference) > 0")
        actual["a text leader over an enumeration"] = subject("NumberOfDifferentValues(Notes, Severity) > 0")
        actual["a text leader over a custom declaration"] =
            subject("NumberOfDifferentValues(Notes, Adjuster) > 0")
        actual["a text leader over a number"] = subject("NumberOfDifferentValues(Notes, Amount) > 0")
        actual["a text leader over a date"] = subject("NumberOfDifferentValues(Notes, ReportedOn) > 0")
        actual["a text leader over a date range"] =
            subject("NumberOfDifferentValues(Notes, CoverWindow) > 0")
        actual["an ENUM leader over a number"] = subject("NumberOfDifferentValues(Severity, Amount) > 0")
        actual["a CUSTOM leader over a number"] = subject("NumberOfDifferentValues(Adjuster, Amount) > 0")
        actual["a text leader over a field waiving value validation"] =
            subject("NumberOfDifferentValues(Notes, FreeNote) > 0")
        actual["a kind defect BEYOND the waiving field"] =
            subject("NumberOfDifferentValues(Notes, Amount, FreeNote) > 0")
        actual["a number leader over numbers"] = subject("NumberOfDifferentValues(Amount, Excess) > 0")
        actual["a number leader over a text"] = subject("NumberOfDifferentValues(Amount, Notes) > 0")
        actual["a BOOLEAN leader"] = subject("NumberOfDifferentValues(Settled, Notes) > 0")
        actual["a CONFIRM leader"] = subject("NumberOfDifferentValues(Acknowledged, Notes) > 0")
        actual["a DATE-RANGE leader"] = subject("NumberOfDifferentValues(CoverWindow, Notes) > 0")
        actual["a TEMPORAL leader, which the component gate owns"] =
            subject("NumberOfDifferentValues(ReportedOn, Notes) > 0")
        actual["a TYPEDEF leader, read as the string it defines"] =
            subject("NumberOfDifferentValues(PolicyRef, Amount) > 0")
        actual["a GROUP leader, read from its first EXPANDED field"] =
            subject("NumberOfDifferentValues(Ledger, Notes) > 0")
        actual["a lone unstarred field, which the arity gate answers"] =
            subject("NumberOfDifferentValues(Settled) > 0")
        actual["a category access, which the list's category gate answers"] =
            subject("NumberOfDifferentValues(Severity -> Band, Notes, Notes) > 0")

        val expected = mutableMapOf<String, List<String>>()
        // The string arm FOLDS custom and enumeration in with text, where the extrema refuse both.
        expected["a text leader over a text list"] = emptyList()
        expected["a text leader over an enumeration"] = emptyList()
        expected["a text leader over a custom declaration"] = emptyList()
        expected["a text leader over a number"] = listOf("MVK_STRING_ENUM_AND_NON_STRING_ENUM")
        expected["a text leader over a date"] = listOf("MVK_STRING_ENUM_AND_NON_STRING_ENUM")
        expected["a text leader over a date range"] = listOf("MVK_STRING_ENUM_AND_NON_STRING_ENUM")
        expected["an ENUM leader over a number"] = listOf("MVK_STRING_ENUM_AND_NON_STRING_ENUM")
        expected["a CUSTOM leader over a number"] = listOf("MVK_STRING_ENUM_AND_NON_STRING_ENUM")
        expected["a text leader over a field waiving value validation"] = listOf("MVK_INVALID_RAW_TYPE")
        // The kind walk finishes before the value-validation one starts, so the later defect answers.
        expected["a kind defect BEYOND the waiving field"] = listOf("MVK_STRING_ENUM_AND_NON_STRING_ENUM")
        expected["a number leader over numbers"] = emptyList()
        expected["a number leader over a text"] = listOf("MVK_NUMBER_AND_NON_NUMBER")
        // No arm claims these three, and the else branch is a class of its own.
        expected["a BOOLEAN leader"] = listOf("MVK_ONLY_STRING_ENUM_NUMBER_CMP_DATE_ALLOWED")
        expected["a CONFIRM leader"] = listOf("MVK_ONLY_STRING_ENUM_NUMBER_CMP_DATE_ALLOWED")
        expected["a DATE-RANGE leader"] = listOf("MVK_ONLY_STRING_ENUM_NUMBER_CMP_DATE_ALLOWED")
        // The handover, working: this family declines a temporal leader and `TEMPORAL_AGGREGATE_OPERANDS`
        // reports A12's own class for it on the same call — so the arm is covered, by the other family.
        expected["a TEMPORAL leader, which the component gate owns"] = listOf("MVK_DATE_AND_NONDATE")
        expected["a TYPEDEF leader, read as the string it defines"] = listOf("MVK_STRING_ENUM_AND_NON_STRING_ENUM")
        expected["a GROUP leader, read from its first EXPANDED field"] = listOf("MVK_NUMBER_AND_NON_NUMBER")
        expected["a lone unstarred field, which the arity gate answers"] = listOf("MVK_PARAMSIZE_INVALIDN")
        // A12 answers `MVK_CATEGORY_NOT_ALLOWED` — from the FIRST statement of the method, ahead even of
        // the duplicate this row also carries. The list category gate's family names it; this one never sees
        // the call, the shared operand reducer declining a categorized operand.
        expected["a category access, which the list's category gate answers"] = listOf("MVK_CATEGORY_NOT_ALLOWED")
        assertEquals(expected, actual, "the distinct count's four arms, and what outruns the leader")
    }
}
