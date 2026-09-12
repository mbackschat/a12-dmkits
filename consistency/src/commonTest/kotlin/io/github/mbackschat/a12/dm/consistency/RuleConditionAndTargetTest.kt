package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The two members a stored RULE cannot work without — `errorCondition` and `errorEntityRelPath`.
 *
 * Every verdict below is the Kernel's, measured on the raw stored shapes by
 * `rulekit.validate.laws.RuleConditionAndTargetSlotLawsTest` (kernel 30.8.1, 2026-09-12). `Rule.Builder`
 * requires both, so none of these shapes can be authored through the typed route.
 */
class RuleConditionAndTargetTest {

    /**
     * A one-rule model.
     *
     * @param condition the whole `errorCondition` member, raw, or null to omit it.
     * @param target the whole `errorEntityRelPath` member, raw, or null to omit it.
     */
    private fun codesFor(condition: String?, target: String?) = prepare(
        assertIs<Admission.Admitted>(
            admit(
                ModelSource(
                    """
                    {"header":{"id":"catalog","locales":[{"code":"en_US"}]},
                     "content":{"modelRoot":{"rootGroups":[
                      {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
                        {"type":"Field","id":"f0","name":"Price","Field":{}},
                        {"type":"Rule","id":"r0","name":"PriceCheck","Rule":{${
                        listOfNotNull(
                            condition?.let { """"errorCondition":$it""" },
                            target?.let { """"errorEntityRelPath":$it""" },
                        ).joinToString(",")
                    }}}
                      ]}}
                     ]}}}
                    """.trimIndent(),
                ),
            ),
        ),
    ).ruleConditionAndTargetFindings().map { it.code }

    private val condition = """"FieldNotFilled(Price)""""
    private val target = """"../Price""""

    /**
     * A BLANK member is judged; an ABSENT one is not judged at all.
     *
     * This is the inversion the family exists to record. Three sibling slots read a blank string as absent,
     * so a checker carrying that law across would fold the two shapes onto one code — and A12 puts them in
     * different TIERS: the blank one is its verdict about the rule, the absent one is it saying there was no
     * model to judge. A finding with an `MVK_*` code on the absent row would be this checker's own opinion
     * wearing A12's authority.
     */
    @Test
    fun aBlankRequiredMemberIsAVerdictAndAnAbsentOneIsNot() {
        val actual = mutableMapOf<String, List<String>>()
        actual["both present — the control"] = codesFor(condition, target)
        actual["condition blank"] = codesFor("\"\"", target)
        actual["condition whitespace"] = codesFor("\"   \"", target)
        actual["condition absent"] = codesFor(null, target)
        actual["condition null"] = codesFor("null", target)
        actual["target blank"] = codesFor(condition, "\"\"")
        actual["target whitespace"] = codesFor(condition, "\"   \"")
        actual["target absent"] = codesFor(condition, null)
        actual["target null"] = codesFor(condition, "null")

        // Both members draw a PAIR of codes, not one. Naming a single code would under-report what A12 said,
        // and `MVK_ERROR_FIELD_MISSING` / `MVK_MISSING_ERROR_FIELD` are two distinct codes a reader will
        // mistake for each other.
        val blankCondition = listOf("MVK_CONDITION_MISSING", "MVK_INCOMPLETE_INPUT")
        val blankTarget = listOf("MVK_ERROR_FIELD_MISSING", "MVK_MISSING_ERROR_FIELD")
        val notJudged = listOf("CK_MODEL_NOT_BUILDABLE")
        val expected = mapOf(
            "both present — the control" to listOf(),
            "condition blank" to blankCondition,
            "condition whitespace" to blankCondition,
            "condition absent" to notJudged,
            "condition null" to notJudged,
            "target blank" to blankTarget,
            "target whitespace" to blankTarget,
            "target absent" to notJudged,
            "target null" to notJudged,
        )
        assertEquals(expected, actual, "a rule's required members")
    }

    /** A computation carries neither member, and nothing here may report one missing from it. */
    @Test
    fun aComputationIsNotAskedForARulesMembers() {
        val prepared = prepare(
            assertIs<Admission.Admitted>(
                admit(
                    ModelSource(
                        """
                        {"header":{"id":"catalog","locales":[{"code":"en_US"}]},
                         "content":{"modelRoot":{"rootGroups":[
                          {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
                            {"type":"Field","id":"f0","name":"Price","Field":{}},
                            {"type":"Computation","id":"c0","name":"PriceCalc","Computation":{
                              "computationAlternatives":[{"operation":"42.00"}]}}
                          ]}}
                         ]}}}
                        """.trimIndent(),
                    ),
                ),
            ),
        )
        assertEquals(emptyList(), prepared.ruleConditionAndTargetFindings())
    }
}
