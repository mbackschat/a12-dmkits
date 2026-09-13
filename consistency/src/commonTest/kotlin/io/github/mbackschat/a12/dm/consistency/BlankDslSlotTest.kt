package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A DSL slot that is PRESENT and blank, which A12 parses rather than treats as unset.
 *
 * Three different codes across four members, so the interesting assertion is that they are NOT alike: a
 * checker with one rule for "a blank does not parse" is right about one member and wrong about the rest
 * (`rulekit.validate.laws.ComputationAlternativeSlotLawsTest.aBlankSlotAnswersDifferentlyOnEachMemberOfThisCarrier`).
 *
 * Every row carries a FILLED control of the same shape, so a code below is about the blank and not about the
 * fixture that had to be built to carry it.
 */
class BlankDslSlotTest {

    private fun computation(alternatives: String, common: String? = null) = ModelSource(
        """
        {"header":{"id":"catalog","locales":[{"code":"en_US"}]},
         "content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Price","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Computation","id":"c0","name":"Calc","Computation":{
              "computedFieldRelPath":"../Price",
              "errorMessage":[{"locale":"en_US","text":"x"}],
              ${common?.let { """"commonPrecondition":"$it",""" } ?: ""}
              "computationAlternatives":$alternatives}}
          ]}}
         ]}}}
        """.trimIndent(),
    )

    private fun codes(source: ModelSource) = check(source).findings.map { it.code }.distinct().sorted()

    private val guarded = """{"precondition":"FieldFilled(Price)","operation":"42.00"}"""

    @Test
    fun aBlankSlotDrawsTheCodeItsOwnMemberDraws() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a filled precondition — the control"] = codes(computation("[$guarded,$guarded]"))
        actual["a BLANK alternative precondition"] = codes(
            computation("""[$guarded,{"precondition":"","operation":"42.00"}]"""),
        )
        actual["a filled common precondition — the control"] =
            codes(computation("[$guarded]", "FieldFilled(Price)"))
        actual["a BLANK common precondition"] = codes(computation("[$guarded]", ""))
        actual["a filled operation — the control"] = codes(computation("[$guarded]"))
        actual["a BLANK operation"] = codes(
            computation("""[{"precondition":"FieldFilled(Price)","operation":""}]"""),
        )

        val expected = mutableMapOf<String, List<String>>()
        expected["a filled precondition — the control"] = emptyList()
        expected["a BLANK alternative precondition"] = listOf("MVK_UNEXPECTED_TOKEN")
        expected["a filled common precondition — the control"] = emptyList()
        expected["a BLANK common precondition"] = listOf("MVK_UNEXPECTED_TOKEN")
        expected["a filled operation — the control"] = emptyList()
        // The row that makes the per-member rule necessary: the SAME blank, a different code.
        expected["a BLANK operation"] = listOf("MVK_INCOMPLETE_INPUT")
        assertEquals(expected, actual, "what the checker says about a blank DSL slot")
    }

    /**
     * A blank `errorCondition` keeps ONE finding, though A12 gives it two codes.
     *
     * Its own family reports `MVK_CONDITION_MISSING` for the same declaration, so emitting the second code
     * here would hand a consumer two findings for one defect. The defect is surfaced either way; this locks
     * the choice so a later reading of "be faithful to A12" does not silently make the report noisier.
     */
    @Test
    fun aBlankErrorConditionIsNotReportedTwice() {
        val codes = check(
            ModelSource(
                """
                {"header":{"id":"catalog","locales":[{"code":"en_US"}]},
                 "content":{"modelRoot":{"rootGroups":[
                  {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
                    {"type":"Field","id":"f0","name":"Price","Field":{"fieldType":{"type":"StringType"}}},
                    {"type":"Rule","id":"r0","name":"PriceRule","Rule":{
                      "errorCondition":"","errorEntityRelPath":"../Price",
                      "errorCode":"P","errorMessage":[{"locale":"en_US","text":"x"}]}}
                  ]}}
                 ]}}}
                """.trimIndent(),
            ),
        ).findings.map { it.code }
        // A12 gives BOTH codes here and the owning family already reproduces the pair, which is why the
        // blank-slot rule excludes this member: without the exclusion it would add a THIRD finding
        // duplicating the second code. The pair is the measured answer, not a redundancy.
        assertEquals(listOf("MVK_CONDITION_MISSING", "MVK_INCOMPLETE_INPUT"), codes,
            "the owning family speaks for a blank condition, and the blank-slot rule must not echo it")
    }
}
