package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of A12's `checkFilterCondition` operand chain.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.FilteredOperandGateOrderLawsTest`. Two rows carry the risk: the unstarred field
 * operand, which the code's NAME says is `MVK_WILDCARDS_REQUIRED` and A12 answers `MVK_NO_WILDCARD`; and the
 * doubly-defective operand, where A12's answer is the opposite of what the chain's source order predicts.
 */
class FilteredOperandKindTest {

    private val noGroups = "MVK_NO_GROUPS_ALLOWED"
    private val starRequired = "MVK_WILDCARDS_REQUIRED"

    /** A policy model whose one rule carries [condition]; `Claims` repeats and is keyed on `ClaimRef`. */
    private fun codes(condition: String): List<String> = check(
        ModelSource(
            """
            {"header":{"id":"policy-filter","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Group","id":"g2","name":"Claims","Group":{"repeatability":20,
                  "indexFieldName":"ClaimRef","elements":[
                  {"type":"Field","id":"f2","name":"ClaimRef","Field":{"fieldType":{"type":"StringType"}}},
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

    private fun filtered(probe: String): List<String> = codes("FieldFilled(Reference) And $probe")

    @Test
    fun whichFilteredOperandsDrawWhichClass() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a starred field operand — the control"] =
            report(filtered("Sum(Claims*/Amount Having [Claims/ClaimRef] == \\\"X\\\") > 0"))
        actual["a starred GROUP operand"] =
            report(filtered("NumberOfFilledGroups(Claims* Having [Claims/ClaimRef] == \\\"X\\\") > 0"))
        actual["a GROUP operand with no star either"] =
            report(filtered("NumberOfFilledGroups(Claims Having [Claims/ClaimRef] == \\\"X\\\") > 0"))
        actual["an operand carrying a SEMANTIC INDEX"] =
            report(filtered("Sum(Claims*/Amount For Reference Having [Claims/ClaimRef] == \\\"X\\\") > 0"))

        val expected = mutableMapOf<String, List<String>>()
        expected["a starred field operand — the control"] = emptyList()
        // The star is not a variable on this defect, which is why both rows are here rather than one.
        expected["a starred GROUP operand"] = listOf(noGroups)
        expected["a GROUP operand with no star either"] = listOf(noGroups)
        expected["an operand carrying a SEMANTIC INDEX"] = listOf(starRequired)

        assertEquals(expected, actual)
    }

    /**
     * The operand the code's own NAME would have this family report, and A12 does not.
     *
     * A plainly unstarred field operand is `MVK_NO_WILDCARD` — the iteration gate runs ahead of this chain —
     * so naming the wildcard-required class here would be a wrong class on a correctly rejected model.
     */
    @Test
    fun anUnstarredFieldOperandIsNotThisFamilys() {
        assertEquals(
            emptyList(),
            report(filtered("Sum(Claims/Amount Having [Claims/ClaimRef] == \\\"X\\\") > 0")),
        )
    }

    /**
     * With BOTH defects present A12 answers with the WILDCARD class, not the kind class.
     *
     * That is the opposite of what `checkFilterCondition`'s source order predicts, and the reason this
     * family tests the semantic index before the operand's kind rather than in the chain's written order.
     */
    @Test
    fun theIndexClassOutrunsTheKindClass() {
        assertEquals(
            listOf(starRequired),
            report(filtered(
                "NumberOfFilledGroups(Claims* For Reference Having [Claims/ClaimRef] == \\\"X\\\") > 0")),
        )
    }

    /** Only this family's two classes, so a co-firing family cannot make a row read green. */
    private fun report(codes: List<String>): List<String> = codes.filter { it == noGroups || it == starRequired }
}
