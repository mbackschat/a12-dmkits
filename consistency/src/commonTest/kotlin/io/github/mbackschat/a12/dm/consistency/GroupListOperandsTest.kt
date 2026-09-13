package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of the group-list duplicate and overlap gates.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.GroupListDuplicateReachLawsTest`. The ADMITTED rows carry the risk and each is a
 * different mistake: two groups whose names share a prefix (a text comparison), one repeatable group named
 * twice with its wildcard (comparing every operand), and one group named once in each of two calls (reading
 * the condition's names instead of each call's operand list).
 */
class GroupListOperandsTest {

    private val exact = "MVK_DUPLICATE_PARAM1"
    private val overlap = "MVK_DUPLICATE_PARAM2"

    /**
     * A motor-policy model whose one rule carries [condition].
     *
     * `Garage` and `GarageBay` are siblings whose names share a prefix, `Coverage` ▸ `Vehicle` ▸ `Driver` is
     * a three-deep nest, and `Claims` is repeatable. Every condition is conjoined with a reference to the
     * rule's own error field, so no row also draws the unreferenced-error-field finding.
     */
    private fun codes(condition: String): List<String> = check(
        ModelSource(
            """
            {"header":{"id":"policy","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
                {"type":"Field","id":"f0","name":"PolicyNumber","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Group","id":"g1","name":"Coverage","Group":{"elements":[
                  {"type":"Field","id":"f1","name":"Tier","Field":{"fieldType":{"type":"StringType"}}},
                  {"type":"Group","id":"g2","name":"Vehicle","Group":{"elements":[
                    {"type":"Field","id":"f2","name":"Plate","Field":{"fieldType":{"type":"StringType"}}},
                    {"type":"Group","id":"g3","name":"Driver","Group":{"elements":[
                      {"type":"Field","id":"f3","name":"Licence","Field":{"fieldType":{"type":"StringType"}}}
                    ]}}
                  ]}}
                ]}},
                {"type":"Group","id":"g4","name":"Garage","Group":{"elements":[
                  {"type":"Field","id":"f4","name":"GarageName","Field":{"fieldType":{"type":"StringType"}}}
                ]}},
                {"type":"Group","id":"g5","name":"GarageBay","Group":{"elements":[
                  {"type":"Field","id":"f5","name":"BayNumber","Field":{"fieldType":{"type":"StringType"}}}
                ]}},
                {"type":"Group","id":"g6","name":"Claims","Group":{"repeatable":true,"maxRepetitions":12,
                  "elements":[
                  {"type":"Field","id":"f6","name":"ClaimAmount","Field":{"fieldType":{"type":"NumberType"}}}
                ]}},
                {"type":"Rule","id":"r0","name":"Probe","Rule":{
                  "errorCondition":"FieldFilled(PolicyNumber) And ${'$'}CONDITION",
                  "errorEntityRelPath":"../PolicyNumber",
                  "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
              ]}}
             ]}}}
            """.trimIndent().replace("\$CONDITION", condition),
        ),
    ).findings.map { it.code }

    @Test
    fun whichOperandPairsCountAsTheSameGroupTwice() {
        val actual = mutableMapOf<String, List<String>>()
        actual["the same group twice, both relative — the control"] = codes("AllGroupsFilled(Garage, Garage)")
        actual["two distinct groups — the control"] = codes("AllGroupsFilled(Garage, Coverage)")
        actual["a relative path beside the ABSOLUTE spelling of the same group"] =
            codes("AllGroupsFilled(Garage, /Policy/Garage)")
        actual["an ancestor and its CHILD"] = codes("AllGroupsFilled(Coverage, Coverage/Vehicle)")
        actual["an ancestor and its GRANDCHILD"] = codes("AllGroupsFilled(Coverage, Coverage/Vehicle/Driver)")
        actual["the descendant named FIRST"] = codes("AllGroupsFilled(Coverage/Vehicle, Coverage)")
        actual["two groups whose NAMES share a prefix"] = codes("AllGroupsFilled(Garage, GarageBay)")

        val expected = mutableMapOf<String, List<String>>()
        expected["the same group twice, both relative — the control"] = listOf(exact)
        expected["two distinct groups — the control"] = emptyList()
        // Identity is the RESOLVED element: comparing the authored text would miss this one.
        expected["a relative path beside the ABSOLUTE spelling of the same group"] = listOf(exact)
        expected["an ancestor and its CHILD"] = listOf(overlap)
        expected["an ancestor and its GRANDCHILD"] = listOf(overlap)
        expected["the descendant named FIRST"] = listOf(overlap)
        // …and containment is by path STEP: `Garage` does not contain `GarageBay`, and A12 admits the pair.
        expected["two groups whose NAMES share a prefix"] = emptyList()
        assertEquals(expected, actual, "which operand pairs count as the same group twice")
    }

    /**
     * The shapes this family stays SILENT on, each because A12 answers them with something else.
     *
     * The starred row is the over-rejection guard: A12 ADMITS one repeatable group named twice with its
     * wildcard, so an implementation comparing every operand refuses a legal model.
     */
    @Test
    fun theShapesItDeliberatelyDoesNotReport() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a starred repeatable named twice"] = codes("NumberOfFilledGroups(Claims*, Claims*) > 0")
        actual["a starred repeatable beside its UNSTARRED spelling"] =
            codes("NumberOfFilledGroups(Claims*, Claims) > 0")
        actual["an UNRESOLVABLE operand beside an exact duplicate"] =
            codes("AllGroupsFilled(Nowhere, Garage, Garage)")
        actual["the same group once in EACH of two calls"] =
            codes("AllGroupsFilled(Garage, Coverage) And AllGroupsFilled(Garage, GarageBay)")

        val expected = mutableMapOf<String, List<String>>()
        expected["a starred repeatable named twice"] = emptyList()
        // After the starred operand is skipped one operand is left, so there is no pair — which is also why
        // the mixed row stays silent, and A12 answers it with its wildcard gate rather than a duplicate.
        expected["a starred repeatable beside its UNSTARRED spelling"] = emptyList()
        // A12 reports the entity and nothing about the duplicate, so reporting both would name two fixes.
        expected["an UNRESOLVABLE operand beside an exact duplicate"] = listOf("MVK_INVALID_ENTITY")
        expected["the same group once in EACH of two calls"] = emptyList()
        assertEquals(expected, actual, "the shapes the group-list operand gates deliberately do not report")
    }

    /** The gates hold on every carrier that takes a group list, including the count's `> 0` tail. */
    @Test
    fun everyCarrierThatTakesAGroupList() {
        val actual = mutableMapOf<String, List<String>>()
        actual["AllGroupsFilled"] = codes("AllGroupsFilled(Garage, Garage)")
        actual["NotAllGroupsFilled"] = codes("NotAllGroupsFilled(Garage, Garage)")
        actual["GroupsNotCollectivelyFilled"] = codes("GroupsNotCollectivelyFilled(Garage, Garage)")
        actual["AtLeastOneGroupFilled"] = codes("AtLeastOneGroupFilled(Coverage, Coverage/Vehicle)")
        actual["NoGroupFilled"] = codes("NoGroupFilled(Coverage, Coverage/Vehicle)")
        actual["NumberOfFilledGroups"] = codes("NumberOfFilledGroups(Garage, Garage) > 0")

        val expected = mutableMapOf<String, List<String>>()
        expected["AllGroupsFilled"] = listOf(exact)
        expected["NotAllGroupsFilled"] = listOf(exact)
        expected["GroupsNotCollectivelyFilled"] = listOf(exact)
        expected["AtLeastOneGroupFilled"] = listOf(overlap)
        expected["NoGroupFilled"] = listOf(overlap)
        expected["NumberOfFilledGroups"] = listOf(exact)
        assertEquals(expected, actual, "every carrier that takes a group list")
    }

    /**
     * Within one call the exact duplicate wins, and each violating call is reported.
     *
     * A12 stops at the first violation it meets in the whole condition, so the second call's duplicate is
     * absent from its code list. It is a real violation all the same — fixing the first makes A12 report it —
     * so reporting both is telling the truth about the model rather than diverging from A12.
     */
    @Test
    fun exactWinsInsideACallAndEveryViolatingCallIsReported() {
        assertEquals(
            listOf(exact),
            codes("AllGroupsFilled(Coverage, Coverage/Vehicle, Garage, Garage)"),
            "the exact duplicate wins inside one call, whatever the authored order",
        )
        assertEquals(
            listOf(overlap, exact),
            codes("AllGroupsFilled(Coverage, Coverage/Vehicle) And AllGroupsFilled(Garage, Garage)"),
            "each violating call is reported, where A12 stops at the first",
        )
    }

    /** The finding names both operands and the carrier, since a condition can hold several calls. */
    @Test
    fun theFindingNamesTheOperandsAndTheCarrier() {
        val finding = check(
            ModelSource(
                """
                {"header":{"id":"policy","locales":[{"code":"en_US"}]},
                 "content":{"modelRoot":{"rootGroups":[
                  {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
                    {"type":"Field","id":"f0","name":"Number","Field":{"fieldType":{"type":"StringType"}}},
                    {"type":"Group","id":"g1","name":"Coverage","Group":{"elements":[
                      {"type":"Field","id":"f1","name":"Tier","Field":{"fieldType":{"type":"StringType"}}},
                      {"type":"Group","id":"g2","name":"Vehicle","Group":{"elements":[
                        {"type":"Field","id":"f2","name":"Plate","Field":{"fieldType":{"type":"StringType"}}}
                      ]}}
                    ]}},
                    {"type":"Rule","id":"r0","name":"Probe","Rule":{
                      "errorCondition":"FieldFilled(Number) And AllGroupsFilled(Coverage, Coverage/Vehicle)",
                      "errorEntityRelPath":"../Number",
                      "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
                  ]}}
                 ]}}}
                """.trimIndent(),
            ),
        ).findings.single()
        assertEquals(overlap, finding.code)
        assertEquals("/Policy/Probe", finding.at)
        assertEquals(true, finding.explain.contains("/Policy/Coverage`"), finding.explain)
        assertEquals(true, finding.explain.contains("/Policy/Coverage/Vehicle"), finding.explain)
        assertEquals(true, finding.explain.contains("AllGroupsFilled"), finding.explain)
    }
}
