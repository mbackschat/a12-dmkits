package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The checker's half of the two gates every entity list meets before its operator asks anything.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.EntityListPreGateLawsTest`. The risk the grid carries is the CALLER table: the law
 * is one shared utility, so a family that read the list alone would name the root-group class on the three
 * carriers that never ask for it.
 */
class EntityListPreGatesTest {

    private fun codes(condition: String): List<String> = check(
        ModelSource(
            """
            {"header":{"id":"order","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f1","name":"Quantity","Field":{"fieldType":{"type":"NumberType"}}},
                {"type":"Group","id":"g1","name":"Shipping","Group":{"elements":[
                  {"type":"Field","id":"f2","name":"Carrier","Field":{"fieldType":{"type":"StringType"}}}
                ]}},
                {"type":"Group","id":"g2","name":"Lines","Group":{"repeatability":50,"elements":[
                  {"type":"Field","id":"f3","name":"Amount","Field":{"fieldType":{"type":"NumberType"}}}
                ]}},
                {"type":"Rule","id":"r0","name":"Probe","Rule":{
                  "errorCondition":"CONDITION_HERE","errorEntityRelPath":"../Reference",
                  "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
              ]}}
             ]}}}
            """.trimIndent().replace("CONDITION_HERE", condition),
        ),
    ).findings.map { it.code }

    private fun subject(call: String): List<String> = codes("FieldFilled(Reference) And $call")

    @Test
    fun theEntityNamedTwiceInOneList() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a repeated field"] = subject("AllFieldsFilled(Quantity, Quantity)")
        actual["a repeated group"] = subject("AllFieldsFilled(Shipping, Shipping)")
        actual["two different fields"] = subject("AllFieldsFilled(Quantity, Reference)")
        actual["the same starred read twice"] =
            subject("AllFieldsFilled(Lines*/Amount, Lines*/Amount)")

        val expected = mutableMapOf<String, List<String>>()
        expected["a repeated field"] = listOf("MVK_DUPLICATE_PARAM1")
        expected["a repeated group"] = listOf("MVK_DUPLICATE_PARAM1")
        expected["two different fields"] = emptyList()
        // A wildcard takes the reference out of A12's own direct-duplicate test: two starred reads of one
        // list are two different rows rather than one entity twice.
        expected["the same starred read twice"] = emptyList()
        assertEquals(expected, actual, "the entity named twice in one list")
    }

    @Test
    fun theEntityLyingUnderAnotherOfTheSameList() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a group and a field inside it"] = subject("AllFieldsFilled(Shipping, Shipping/Carrier)")
        actual["the contained entity written first"] =
            subject("AllFieldsFilled(Shipping/Carrier, Shipping)")
        actual["two entities under one group"] = subject("AllFieldsFilled(Shipping/Carrier, Quantity)")

        val expected = mutableMapOf<String, List<String>>()
        expected["a group and a field inside it"] = listOf("MVK_DUPLICATE_PARAM2")
        expected["the contained entity written first"] = listOf("MVK_DUPLICATE_PARAM2")
        expected["two entities under one group"] = emptyList()
        assertEquals(expected, actual, "the entity lying under another of the same list")
    }

    /**
     * The caller table: the same list, and only some operators ask the root-group gate about it.
     *
     * The two `GroupFilled` rows are the ones that fail if the gate is read off the list rather than off the
     * caller — A12 admits both, so reporting there would refuse a model it accepts.
     */
    @Test
    fun whichCarriersAskTheRootGroupGate() {
        val actual = mutableMapOf<String, List<String>>()
        actual["AllFieldsFilled"] = subject("AllFieldsFilled(/Order)")
        actual["FieldFilled"] = subject("FieldFilled(/Order)")
        actual["NumberOfValueInFields"] = subject("NumberOfValueInFields(True In /Order) > 0")
        actual["GroupFilled"] = subject("GroupFilled(/Order)")
        actual["GroupNotFilled"] = subject("GroupNotFilled(/Order)")
        actual["RepetitionNotUnique"] = codes("RepetitionNotUnique(/Order)")

        val expected = mutableMapOf<String, List<String>>()
        expected["AllFieldsFilled"] = listOf("MVK_ROOT_GROUP_REFERENCED")
        expected["FieldFilled"] = listOf("MVK_ROOT_GROUP_REFERENCED")
        expected["NumberOfValueInFields"] = listOf("MVK_ROOT_GROUP_REFERENCED")
        expected["GroupFilled"] = emptyList()
        expected["GroupNotFilled"] = emptyList()
        // The uniqueness carrier's own placement class, from another family — what matters is that no
        // root-group class joins it, `CheckWiederholungBedingungImpl` asking no such gate.
        expected["RepetitionNotUnique"] = listOf("MVK_NOT_IN_RULEGROUP")
        assertEquals(expected, actual, "which carriers ask the root-group gate")
    }

    /** A duplicate is found while the list is BUILT, so it answers ahead of both later gates. */
    @Test
    fun theDuplicateAnswersBeforeTheRootGroupAndTheArityGate() {
        val actual = mutableMapOf<String, List<String>>()
        actual["the root group, repeated"] = subject("AllFieldsFilled(/Order, /Order)")
        actual["three entities, all the same"] = subject("FieldFilled(Quantity, Quantity, Quantity)")

        val expected = mutableMapOf<String, List<String>>()
        expected["the root group, repeated"] = listOf("MVK_DUPLICATE_PARAM1")
        expected["three entities, all the same"] = listOf("MVK_DUPLICATE_PARAM1")
        assertEquals(expected, actual, "the duplicate answers before the root-group and the arity gate")
    }

    /**
     * The shapes it leaves alone, each because another reader owns them.
     *
     * The clause row is the one that matters: A12's duplicate test compares the semantic index and the
     * category alongside the path, so two same-path operands under different keys are not duplicates at all
     * and reporting one would refuse a model A12 admits.
     */
    @Test
    fun theShapesItDeliberatelyDoesNotReport() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a repeated entity under different semantic-index keys"] =
            subject("NumberOfFilledFields(Lines*/Amount For \\\"A\\\", Lines*/Amount For \\\"B\\\") > 0")
        actual["a group list, which its own family owns"] = subject("AllGroupsFilled(Shipping, Shipping)")
        actual["an entity no reader can resolve"] = subject("AllFieldsFilled(Missing, Missing)")

        assertTrue(
            "MVK_DUPLICATE_PARAM1" !in actual.getValue("a repeated entity under different semantic-index keys"),
            "a keyed pair is not a duplicate: ${actual["a repeated entity under different semantic-index keys"]}",
        )
        assertEquals(
            listOf("MVK_DUPLICATE_PARAM1"),
            actual["a group list, which its own family owns"],
            "the group-list family reports it, and exactly once",
        )
        assertEquals(
            listOf("MVK_INVALID_ENTITY"),
            actual["an entity no reader can resolve"],
            "the reference family answers, and no duplicate class is added to it",
        )
    }

    /** The finding names the operator and the entity, so a reader can find the operand to drop. */
    @Test
    fun theFindingNamesTheOperatorAndTheEntity() {
        val finding = check(
            ModelSource(
                """
                {"header":{"id":"order","locales":[{"code":"en_US"}]},
                 "content":{"modelRoot":{"rootGroups":[
                  {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
                    {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
                    {"type":"Field","id":"f1","name":"Quantity","Field":{"fieldType":{"type":"NumberType"}}},
                    {"type":"Rule","id":"r0","name":"Probe","Rule":{
                      "errorCondition":"FieldFilled(Reference) And AllFieldsFilled(Quantity, Quantity)",
                      "errorEntityRelPath":"../Reference",
                      "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
                  ]}}
                 ]}}}
                """.trimIndent(),
            ),
        ).findings.single { it.code == "MVK_DUPLICATE_PARAM1" }

        assertEquals("/Order/Probe", finding.at)
        assertTrue("AllFieldsFilled" in finding.explain, finding.explain)
        assertTrue("Quantity" in finding.explain, finding.explain)
    }
}
