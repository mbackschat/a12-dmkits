package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Every member A12 binds to text, held to the grid `rulekit.validate.laws.StringSlotContainerLawsTest` ran.
 *
 * A12's answer is uniform — an object or an array where it declares a string refuses its reader outright, on
 * all three carriers — so the checker states the law once and the rows here prove it reaches each carrier
 * rather than only the one it was written against. `UNREADABLE` there reads as one [CK_MODEL_NOT_BUILDABLE]
 * here, and every other shape reads as no finding from THIS family.
 *
 * **The admissions are load-bearing.** A non-string scalar is coerced into A12's string slot, so a numeric
 * `errorCondition` must draw nothing here; a family that refused it would reject a model A12 loads. Blank and
 * absent members belong to their owning families and are deliberately not this family's business, which the
 * rows below pin by expecting silence for them.
 */
class StringSlotBindingTest {

    private fun ruleSource(members: String) = ModelSource(
        """
        {"header":{"modelVersion":"28.4.0","id":"catalog","locales":[{"code":"en_US"}]},
         "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Price","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Rule","id":"r0","name":"PriceRule","Rule":{
              "errorCondition":"FieldFilled(Price)","errorEntityRelPath":"../Price",
              "errorCode":"PROBE","errorMessage":[{"locale":"en_US","text":"x"}]$members}}
          ]}}
         ]}}}
        """.trimIndent(),
    )

    private fun computationSource(alternative: String) = ModelSource(
        """
        {"header":{"modelVersion":"28.4.0","id":"catalog","locales":[{"code":"en_US"}]},
         "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Price","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Computation","id":"c0","name":"PriceCalc","Computation":{
              "computedFieldRelPath":"../Price",
              "computationAlternatives":[$alternative],
              "errorMessage":[{"locale":"en_US","text":"x"}]}}
          ]}}
         ]}}}
        """.trimIndent(),
    )

    private fun groupSource(members: String) = ModelSource(
        """
        {"header":{"modelVersion":"28.4.0","id":"catalog","locales":[{"code":"en_US"}]},
         "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
            {"type":"Group","id":"g1","name":"Lines","Group":{"repeatability":20,"elements":[
              {"type":"Field","id":"f1","name":"Sku","Field":{"fieldType":{"type":"StringType"}}}
            ]$members}}
          ]}}
         ]}}}
        """.trimIndent(),
    )

    private fun codesFor(source: ModelSource): List<String> =
        prepare(assertIs<Admission.Admitted>(admit(source))).tracked()
            .everyDeclaration(TrackedFactBase::stringSlotFindings)
            .map { it.code }

    private val containers = listOf(
        "an empty object" to "{}",
        "an object with content" to """{"value":"x"}""",
        "an empty list" to "[]",
        "a list holding a string" to """["x"]""",
    )

    @Test
    fun aRulesTextMembersAreCheckedForBINDINGAndNothingElse() {
        val actual = mutableMapOf<String, List<String>>()
        actual["the control — both members well formed"] = codesFor(ruleSource(""))
        for ((name, json) in containers) {
            actual["errorCondition — $name"] = codesFor(ruleSource(""","errorCondition":$json"""))
            actual["errorEntityRelPath — $name"] = codesFor(ruleSource(""","errorEntityRelPath":$json"""))
        }
        // Not this family's business: a scalar binds, and blank/absent belong to the owning family.
        actual["errorCondition — a number, which A12 coerces"] = codesFor(ruleSource(""","errorCondition":7"""))
        actual["errorCondition — blank"] = codesFor(ruleSource(""","errorCondition":"" """))

        val expected = mutableMapOf<String, List<String>>()
        expected["the control — both members well formed"] = emptyList()
        for ((name, _) in containers) {
            expected["errorCondition — $name"] = listOf(CK_MODEL_NOT_BUILDABLE)
            expected["errorEntityRelPath — $name"] = listOf(CK_MODEL_NOT_BUILDABLE)
        }
        expected["errorCondition — a number, which A12 coerces"] = emptyList()
        expected["errorCondition — blank"] = emptyList()
        assertEquals(expected, actual, "what this family says about a rule's text members")
    }

    /**
     * One defect, one finding — measured through the WHOLE report rather than this family alone.
     *
     * The families that own these members read them too, and the failure this guards against is not that one
     * of them reports the wrong thing but that two of them report the same thing: an unbindable envelope once
     * produced two near-identical `CK_MODEL_NOT_BUILDABLE` findings at one path, and only counting caught it.
     * Calling `stringSlotFindings` per declaration cannot see that, which is why this row goes through `check`.
     */
    @Test
    fun anUnbindableSlotIsReportedOnceAcrossTheWholeReport() {
        val actual = mutableMapOf<String, Int>()
        actual["errorCondition as an object"] =
            checkRead(ruleSource(""","errorCondition":{"value":"x"}""")).findings.count { it.at == "/Catalog/PriceRule" }
        actual["errorEntityRelPath as a list"] =
            checkRead(ruleSource(""","errorEntityRelPath":["../Price"]""")).findings.count { it.at == "/Catalog/PriceRule" }
        actual["the control — nothing wrong"] =
            checkRead(ruleSource("")).findings.count { it.at == "/Catalog/PriceRule" }

        val expected = mapOf(
            "errorCondition as an object" to 1,
            "errorEntityRelPath as a list" to 1,
            "the control — nothing wrong" to 0,
        )
        assertEquals(expected, actual, "findings at the rule's path, counted across every family")
    }

    @Test
    fun aComputationAlternativesTextMembersAreCheckedOnTheirOwnCarrier() {
        val actual = mutableMapOf<String, List<String>>()
        actual["the control — a well formed alternative"] = codesFor(computationSource("""{"operation":"42.00"}"""))
        for ((name, json) in containers) {
            actual["operation — $name"] = codesFor(computationSource("""{"operation":$json}"""))
            actual["precondition — $name"] =
                codesFor(computationSource("""{"operation":"42.00","precondition":$json}"""))
        }

        val expected = mutableMapOf<String, List<String>>()
        expected["the control — a well formed alternative"] = emptyList()
        for ((name, _) in containers) {
            expected["operation — $name"] = listOf(CK_MODEL_NOT_BUILDABLE)
            expected["precondition — $name"] = listOf(CK_MODEL_NOT_BUILDABLE)
        }
        assertEquals(expected, actual, "what this family says about a computation alternative's text members")
    }

    @Test
    fun aGroupsOrderingMembersAreCheckedOnTheirOwnCarrierToo() {
        val actual = mutableMapOf<String, List<String>>()
        actual["the control — a well formed index field"] = codesFor(groupSource(""","indexFieldName":"Sku""""))
        for ((name, json) in containers) {
            actual["indexFieldName — $name"] = codesFor(groupSource(""","indexFieldName":$json"""))
        }
        // `sortFields` is declared as a LIST, so the shapes that ask about its binding are the non-list ones.
        actual["sortFields — an object instead of a list"] = codesFor(groupSource(""","sortFields":{}"""))
        actual["sortFields — a string instead of a list"] = codesFor(groupSource(""","sortFields":"Sku""""))
        actual["sortFields — a number instead of a list"] = codesFor(groupSource(""","sortFields":7"""))
        actual["sortFields — a well formed list, the control"] =
            codesFor(groupSource(""","sortFields":[{"name":"Sku"}]"""))

        val expected = mutableMapOf<String, List<String>>()
        expected["the control — a well formed index field"] = emptyList()
        for ((name, _) in containers) {
            expected["indexFieldName — $name"] = listOf(CK_MODEL_NOT_BUILDABLE)
        }
        expected["sortFields — an object instead of a list"] = listOf(CK_MODEL_NOT_BUILDABLE)
        expected["sortFields — a string instead of a list"] = listOf(CK_MODEL_NOT_BUILDABLE)
        expected["sortFields — a number instead of a list"] = listOf(CK_MODEL_NOT_BUILDABLE)
        expected["sortFields — a well formed list, the control"] = emptyList()
        assertEquals(expected, actual, "what this family says about a group's ordering members")
    }
}
