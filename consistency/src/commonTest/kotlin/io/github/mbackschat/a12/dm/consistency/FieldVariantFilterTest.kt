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
            {"type":"Field","id":"f0","name":"Tier","Field":{"fieldType":{"type":"StringType"}${filter?.let { ""","variantFilter":$it""" } ?: ""}}},
            {"type":"Field","id":"f1","name":"AutoRenew","Field":{"fieldType":{"type":"StringType"}}},
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
            {"type":"Field","id":"f0","name":"Tier","Field":{"fieldType":{"type":"StringType"}${filter?.let { ""","variantFilter":$it""" } ?: ""}}},
            {"type":"Rule","id":"r0","name":"TargetRule","Rule":{
              "errorCondition":"FieldNotFilled(Tier)","errorEntityRelPath":"../Tier",
              "errorCode":"PROBE","errorMessage":[{"locale":"en_US","text":"x"}]}}
          ]}}
         ]}}}
        """.trimIndent(),
    )

    /**
     * The same question asked with a PATH-shaped reference rather than a bare name.
     *
     * The two spellings take different resolvers — a bare name goes through the declaring-group-first tier
     * and its short-name fallback, a path through the step walker — so a row in one proves nothing about the
     * other. Written as `../Tier`, which walks up out of the rule's own group and back down.
     */
    private fun mentioningByPath(filter: String?) = ModelSource(
        """
        {"header":{"id":"catalog","locales":[{"code":"en_US"}]},
         "content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Tier","Field":{"fieldType":{"type":"StringType"}${filter?.let { ""","variantFilter":$it""" } ?: ""}}},
            {"type":"Field","id":"f1","name":"AutoRenew","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Group","id":"g1","name":"Inner","Group":{"elements":[
              {"type":"Rule","id":"r0","name":"PathRule","Rule":{
                "errorCondition":"FieldNotFilled(../AutoRenew) And FieldNotFilled(../Tier)",
                "errorEntityRelPath":"../../AutoRenew",
                "errorCode":"PROBE","errorMessage":[{"locale":"en_US","text":"x"}]}}
            ]}}
          ]}}
         ]}}}
        """.trimIndent(),
    )

    /**
     * The model-wide SHORT-NAME fallback, which is a third resolver again and only runs under the flag.
     *
     * A bare name is tried in the declaring group first and wins outright there, so the fallback is reached
     * only when the group does not have the field — here the rule sits in `Inner` and `Tier` lives in
     * `Catalog`. Without this fixture, reverting the fallback's scope to the unfiltered element list survives:
     * measured as a mutant, because every other row resolves before the fallback is consulted.
     */
    private fun mentioningByShortNameFallback(filter: String?) = ModelSource(
        """
        {"header":{"id":"catalog","locales":[{"code":"en_US"}]},
         "content":{"modelConfig":{"fieldRefByShortNameAllowed":true},
          "modelRoot":{"rootGroups":[
           {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
             {"type":"Field","id":"f0","name":"Tier","Field":{"fieldType":{"type":"StringType"}${filter?.let { ""","variantFilter":$it""" } ?: ""}}},
             {"type":"Group","id":"g1","name":"Inner","Group":{"elements":[
               {"type":"Field","id":"f1","name":"AutoRenew","Field":{"fieldType":{"type":"StringType"}}},
               {"type":"Rule","id":"r0","name":"FallbackRule","Rule":{
                 "errorCondition":"FieldNotFilled(AutoRenew) And FieldNotFilled(Tier)",
                 "errorEntityRelPath":"../AutoRenew",
                 "errorCode":"PROBE","errorMessage":[{"locale":"en_US","text":"x"}]}}
             ]}}
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
        // An unknown spelling is NOT `ONLY`, so the field stays VISIBLE — and the model is nonetheless
        // unloadable, which the binding family now says. Two different questions about one member: the
        // field is not deleted (no `MVK_INVALID_ENTITY`) and the source does not load.
        actual["an unknown filterType"] =
            codes(mentioning("""{"filterType":"SOMETIMES","variants":[]}"""))
        actual["no filterType at all"] = codes(mentioning("""{"variants":[]}"""))
        actual["a filterType that is not a string"] = codes(mentioning("""{"filterType":9}"""))
        actual["a variantFilter that is not an object"] = codes(mentioning(""""ONLY""""))
        // An explicit null is ADMITTED on this carrier as on the others — the accessor is an `Optional`.
        actual["an explicit null — the control that keeps the arm above honest"] = codes(mentioning("null"))

        val expected = mutableMapOf<String, List<String>>()
        expected["unfiltered — the control"] = emptyList()
        expected["ALL_EXCEPT — the control that accepts at the default variant"] = emptyList()
        expected["ONLY"] = listOf("MVK_INVALID_ENTITY")
        expected["ONLY naming the default variant"] = listOf("MVK_INVALID_ENTITY")
        expected["ONLY with an empty variants list"] = listOf("MVK_INVALID_ENTITY")
        expected["ONLY with no variants member"] = listOf("MVK_INVALID_ENTITY")
        expected["an unknown filterType"] = listOf(CK_MODEL_NOT_BUILDABLE)
        expected["no filterType at all"] = listOf(CK_MODEL_NOT_BUILDABLE)
        expected["a filterType that is not a string"] = listOf(CK_MODEL_NOT_BUILDABLE)
        expected["a variantFilter that is not an object"] = listOf(CK_MODEL_NOT_BUILDABLE)
        expected["an explicit null — the control that keeps the arm above honest"] = emptyList()
        assertEquals(expected, actual, "what the checker says about a condition naming a filtered field")
    }

    /**
     * The path-shaped resolver needs its own rows: it is a different function from the bare-name one.
     *
     * Without this, reverting the path walker alone to the unfiltered element list goes unnoticed — measured,
     * as a surviving mutant, because every row above spells its reference as a bare name.
     */
    @Test
    fun aPathShapedReferenceToAnOnlyFilteredFieldNamesNothingEither() {
        val actual = mutableMapOf<String, List<String>>()
        actual["unfiltered — the control"] = codes(mentioningByPath(null))
        actual["ALL_EXCEPT — the control"] = codes(mentioningByPath(allExcept))
        actual["ONLY"] = codes(mentioningByPath(only))

        val expected = mutableMapOf<String, List<String>>()
        expected["unfiltered — the control"] = emptyList()
        expected["ALL_EXCEPT — the control"] = emptyList()
        expected["ONLY"] = listOf("MVK_INVALID_ENTITY")
        assertEquals(expected, actual, "what the checker says about a path naming a filtered field")
    }

    /** The short-name fallback is a third resolver and needs its own rows; see the fixture's note. */
    @Test
    fun theShortNameFallbackDoesNotFindAnOnlyFilteredFieldEither() {
        val actual = mutableMapOf<String, List<String>>()
        actual["unfiltered — the control, so the fallback demonstrably reaches it"] =
            codes(mentioningByShortNameFallback(null))
        actual["ALL_EXCEPT — the control"] = codes(mentioningByShortNameFallback(allExcept))
        actual["ONLY"] = codes(mentioningByShortNameFallback(only))

        val expected = mutableMapOf<String, List<String>>()
        expected["unfiltered — the control, so the fallback demonstrably reaches it"] = emptyList()
        expected["ALL_EXCEPT — the control"] = emptyList()
        expected["ONLY"] = listOf("MVK_INVALID_ENTITY")
        assertEquals(expected, actual, "what the short-name fallback says about a filtered field")
    }

    /**
     * A filtered FIELD draws no suppression gap, because A12 does not suppress anything on this carrier.
     *
     * The gap text says A12 "excludes such a declaration from `checkConsistency` altogether" — true of a rule
     * and false of a field, where the field is DELETED and A12 goes on to say more about the model, not less.
     * Emitting it here would disclose the opposite of what happened.
     *
     * The rule row is the live control: the same spelling on a rule MUST still produce that gap, so a failure
     * here cannot be the gap machinery having stopped working. Measured through the report rather than the
     * family, because a scope that wrongly includes fields also SUPPRESSES findings at a field's path, and
     * that half is invisible today only because no finding happens to attach there.
     */
    @Test
    fun anOnlyFilteredFieldIsNotReportedAsASuppressedDeclaration() {
        val fieldGaps = check(mentioning(only)).gaps.filter { it.at != null }
        assertEquals(emptyList(), fieldGaps, "a deleted field is not an unexamined declaration")

        val ruleFiltered = ModelSource(
            """
            {"header":{"id":"catalog","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Tier","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Rule","id":"r0","name":"FilteredRule","Rule":{
                  "variantFilter":$only,
                  "errorCondition":"FieldNotFilled(Tier)","errorEntityRelPath":"../Tier",
                  "errorCode":"PROBE","errorMessage":[{"locale":"en_US","text":"x"}]}}
              ]}}
             ]}}}
            """.trimIndent(),
        )
        assertEquals(listOf("/Catalog/FilteredRule"), check(ruleFiltered).gaps.mapNotNull { it.at },
            "the control: the same spelling on a rule IS a suppression and must still say so")
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
        // The TARGET's code alone, though this rule's condition names the field too: A12 examines the error
        // target first and stops the rule there, so the entity code is withheld. `FieldVariantFilterLawsTest`
        // measured exactly this row and says so in its own comment — this mirror had asserted the union of
        // both roles and sat green, because nothing compares a mirror against its laws test.
        expected["ONLY"] = listOf("MVK_ERROR_FIELD_MISSING")
        assertEquals(expected, actual, "what the checker says about a rule targeting a filtered field")
    }
}
