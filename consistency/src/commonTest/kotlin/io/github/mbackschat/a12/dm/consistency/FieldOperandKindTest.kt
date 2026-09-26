package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of A12's `checkIsField` gate.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.FieldOperandKindLawsTest`. The eight ADMITTED carriers are the load-bearing rows:
 * they are what a family named after its operators would have refused.
 */
class FieldOperandKindTest {

    private val noGroups = "MVK_NO_GROUPS_ALLOWED"

    /** A policy model whose one rule carries [condition]; `Insured` neither repeats nor is the root. */
    private fun codes(condition: String): List<String> = checkRead(
        ModelSource(
            """
            {"header":{"id":"policy-operand-kind","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Group","id":"g1","name":"Insured","Group":{"elements":[
                  {"type":"Field","id":"f1","name":"InsuredName","Field":{"fieldType":{"type":"StringType"}}},
                  {"type":"Field","id":"f2","name":"InsuredCity","Field":{"fieldType":{"type":"StringType"}}}
                ]}},
                {"type":"Group","id":"g2","name":"Claims","Group":{"repeatability":20,"elements":[
                  {"type":"Field","id":"f3","name":"ClaimRef","Field":{"fieldType":{"type":"StringType"}}},
                  {"type":"Group","id":"g3","name":"Assessor","Group":{"elements":[
                    {"type":"Field","id":"f4","name":"AssessorName","Field":{"fieldType":{"type":"StringType"}}}
                  ]}}
                ]}},
                {"type":"Rule","id":"r0","name":"Probe","Rule":{
                  "errorCondition":"CONDITION_HERE","errorEntityRelPath":"../Reference",
                  "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
              ]}}
             ]}}}
            """.trimIndent().replace("CONDITION_HERE", condition),
        ),
    ).findings.map { it.code }

    @Test
    fun onlyTheTwoScalarCarriersRefuseAGroup() {
        val actual = mutableMapOf<String, Boolean>()
        actual["FieldFilled"] = noGroups in codes("FieldFilled(Reference) And FieldFilled(Insured)")
        actual["FieldNotFilled"] = noGroups in codes("FieldFilled(Reference) And FieldNotFilled(Insured)")
        actual["AllFieldsFilled"] = noGroups in codes("AllFieldsFilled(Reference, Insured)")
        actual["AtLeastOneFieldFilled"] = noGroups in codes("AtLeastOneFieldFilled(Reference, Insured)")
        actual["NumberOfFilledFields"] = noGroups in codes("NumberOfFilledFields(Reference, Insured) > 0")

        assertEquals(
            mapOf("FieldFilled" to true, "FieldNotFilled" to true, "AllFieldsFilled" to false,
                "AtLeastOneFieldFilled" to false, "NumberOfFilledFields" to false),
            actual,
        )
    }

    /**
     * The shapes it leaves alone, and why each silence is a WRONG CLASS avoided rather than a gap.
     *
     * A12 answers each of these with a different code — the two wildcard classes and the root class — and
     * naming the kind there would hand the author a repair for a gate that is not the one that fired.
     */
    @Test
    fun theShapesItLeavesAlone() {
        val actual = mutableMapOf<String, Boolean>()
        actual["a FIELD operand — the control"] =
            noGroups in codes("FieldFilled(Reference) And FieldFilled(Insured/InsuredName)")
        actual["a repeatable group read WITHOUT a star"] =
            noGroups in codes("FieldFilled(Reference) And FieldFilled(Claims)")
        actual["a star on a group that does not repeat"] =
            noGroups in codes("FieldFilled(Reference) And FieldFilled(Insured*)")
        actual["the ROOT group"] = noGroups in codes("FieldFilled(Reference) And FieldFilled(/Policy)")

        assertEquals(
            mapOf("a FIELD operand — the control" to false, "a repeatable group read WITHOUT a star" to false,
                "a star on a group that does not repeat" to false, "the ROOT group" to false),
            actual,
        )
    }

    /** …and the two shapes where the starring IS right, which is what makes the silences above bounded. */
    @Test
    fun aCorrectlyStarredGroupIsStillAGroup() {
        val actual = mutableMapOf<String, Boolean>()
        actual["a starred repeatable group"] =
            noGroups in codes("FieldFilled(Reference) And FieldFilled(Claims*)")
        actual["a nested group below a correctly starred level"] =
            noGroups in codes("FieldFilled(Reference) And FieldFilled(Claims*/Assessor)")

        assertEquals(
            mapOf("a starred repeatable group" to true,
                "a nested group below a correctly starred level" to true),
            actual,
        )
    }
}
