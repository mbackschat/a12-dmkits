package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A FIELD under an `ONLY` variant filter is gone, so anything naming it names nothing.
 *
 * The rows mirror `rulekit.validate.laws.FieldVariantFilterLawsTest` ([KERNEL-FINDINGS] KF305). The member's
 * other two carriers SUPPRESS — A12 says nothing about a filtered rule, so this checker says nothing either —
 * and a field does the opposite: `ValidationModelConverter` drops it, so A12 says MORE about the model, not
 * less. Reading one carrier's law onto the other would have been wrong in both directions.
 *
 * **`ALL_EXCEPT` is the control on every row.** At the default variant it accepts, so a model differing only
 * in the filter type must be clean — which is what separates "the filter deleted the field" from "the
 * reference was bad anyway".
 *
 * **The role decides the code, and both roles are here.** A condition naming a deleted field draws
 * `MVK_INVALID_ENTITY`; a rule TARGETING one draws `MVK_ERROR_FIELD_MISSING`. The checker already maps roles
 * to those codes, so this family adds no code of its own — which is the argument for making the field
 * invisible at resolution rather than reporting from a family that would have to re-derive the mapping.
 */
class FieldVariantFilterTest {

    private val only = """{"filterType":"ONLY","variants":["premium"]}"""
    private val allExcept = """{"filterType":"ALL_EXCEPT","variants":["premium"]}"""

    /** A model whose `Tier` field carries [filter], with a rule that mentions it but targets `AutoRenew`. */
    private fun mentioning(filter: String?) = ModelSource(
        """
        {"header":{"id":"catalog","locales":[{"code":"en_US"}]},
         "content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Tier","Field":{${filter?.let { """"variantFilter":$it""" } ?: ""}}},
            {"type":"Field","id":"f1","name":"AutoRenew","Field":{}},
            {"type":"Rule","id":"r0","name":"MentionRule","Rule":{
              "errorCondition":"FieldNotFilled(AutoRenew) And FieldNotFilled(Tier)",
              "errorEntityRelPath":"../AutoRenew",
              "errorCode":"PROBE","errorMessage":[{"locale":"en_US","text":"x"}]}}
          ]}}
         ]}}}
        """.trimIndent(),
    )

    /** The same, with the rule TARGETING the filtered field instead of merely mentioning it. */
    private fun targeting(filter: String?) = ModelSource(
        """
        {"header":{"id":"catalog","locales":[{"code":"en_US"}]},
         "content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Tier","Field":{${filter?.let { """"variantFilter":$it""" } ?: ""}}},
            {"type":"Rule","id":"r0","name":"TargetRule","Rule":{
              "errorCondition":"FieldNotFilled(Tier)","errorEntityRelPath":"../Tier",
              "errorCode":"PROBE","errorMessage":[{"locale":"en_US","text":"x"}]}}
          ]}}
         ]}}}
        """.trimIndent(),
    )

    private fun codes(source: ModelSource): List<String> = check(source).findings.map { it.code }.distinct()

    @Test
    fun aConditionNamingAnOnlyFilteredFieldNamesNothing() {
        val actual = mutableMapOf<String, List<String>>()
        actual["unfiltered — the control"] = codes(mentioning(null))
        actual["ALL_EXCEPT — the control that accepts at the default variant"] = codes(mentioning(allExcept))
        actual["ONLY"] = codes(mentioning(only))
        // The filter TYPE alone decides; no variants list rescues the field. Measured on this carrier rather
        // than inherited from the rule carrier, because it is a different reader.
        actual["ONLY naming the default variant"] =
            codes(mentioning("""{"filterType":"ONLY","variants":[""]}"""))
        actual["ONLY with an empty variants list"] =
            codes(mentioning("""{"filterType":"ONLY","variants":[]}"""))
        actual["ONLY with no variants member"] = codes(mentioning("""{"filterType":"ONLY"}"""))
        // An unknown spelling is NOT `ONLY`, so the field stays visible and this checker under-reports a
        // model A12 cannot build at all. Silence is the safe direction and this row pins it deliberately.
        actual["an unknown filterType"] =
            codes(mentioning("""{"filterType":"SOMETIMES","variants":[]}"""))

        val expected = mutableMapOf<String, List<String>>()
        expected["unfiltered — the control"] = emptyList()
        expected["ALL_EXCEPT — the control that accepts at the default variant"] = emptyList()
        expected["ONLY"] = listOf("MVK_INVALID_ENTITY")
        expected["ONLY naming the default variant"] = listOf("MVK_INVALID_ENTITY")
        expected["ONLY with an empty variants list"] = listOf("MVK_INVALID_ENTITY")
        expected["ONLY with no variants member"] = listOf("MVK_INVALID_ENTITY")
        expected["an unknown filterType"] = emptyList()
        assertEquals(expected, actual, "what the checker says about a condition naming a filtered field")
    }

    @Test
    fun aRuleTargetingAnOnlyFilteredFieldDrawsTheTargetsOwnCode() {
        val actual = mutableMapOf<String, List<String>>()
        actual["unfiltered — the control"] = codes(targeting(null))
        actual["ALL_EXCEPT — the control"] = codes(targeting(allExcept))
        actual["ONLY"] = codes(targeting(only)).sorted()

        val expected = mutableMapOf<String, List<String>>()
        expected["unfiltered — the control"] = emptyList()
        expected["ALL_EXCEPT — the control"] = emptyList()
        // Both roles fire here, because this rule's condition names the field too — which is what A12 does,
        // and the reason the mentioning grid above keeps the target elsewhere.
        expected["ONLY"] = listOf("MVK_ERROR_FIELD_MISSING", "MVK_INVALID_ENTITY").sorted()
        assertEquals(expected, actual, "what the checker says about a rule targeting a filtered field")
    }
}
