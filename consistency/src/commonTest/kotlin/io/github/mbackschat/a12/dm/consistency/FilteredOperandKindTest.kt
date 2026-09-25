package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of A12's `checkFilterCondition` operand chain.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.FilteredOperandGateOrderLawsTest`. Two rows carry the risk: the unstarred field
 * operand, which the code's NAME says is `MVK_WILDCARDS_REQUIRED` and A12 answers `MVK_NO_WILDCARD`; and a
 * `Having` after an index key, which filters the KEY rather than the operand it is written on.
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
                {"type":"Group","id":"g1","name":"Insured","Group":{"elements":[
                  {"type":"Field","id":"f1","name":"InsuredName","Field":{"fieldType":{"type":"StringType"}}}
                ]}},
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
    ).findings.filter { it.category == FindingCategory.A12_CONSTRAINT }.map { it.code }

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
     * A group operand carrying an index draws the WILDCARD class, not the kind class, because the filter is
     * the key's: the key `Reference` is a field and unstarred, so the chain passes its kind gate and fails the
     * next one in its written order.
     */
    @Test
    fun aKeyFilteredOnAGroupOperandIsJudgedAsTheKey() {
        assertEquals(
            listOf(starRequired),
            report(filtered(
                "NumberOfFilledGroups(Claims* For Reference Having [Claims/ClaimRef] == \\\"X\\\") > 0")),
        )
    }

    /**
     * A `Having` after an index key filters the KEY when the key is an entity, and the operand when it is a
     * string literal, which has no spec to take one. Asserted over every A12 class, since the index family
     * answers the same text.
     */
    @Test
    fun aHavingAfterAnIndexKeyFiltersTheKeyUnlessTheKeyIsALiteral() {
        val actual = mutableMapOf<String, List<String>>()
        actual["an entity key, starred operand"] =
            filtered("Sum(Claims*/Amount For Reference Having [Claims/ClaimRef] == \\\"X\\\") > 0")
        actual["an entity key, unstarred operand"] =
            filtered("Sum(Claims/Amount For Reference Having [Claims/ClaimRef] == \\\"X\\\") > 0")
        actual["an entity key on a group operand"] =
            filtered("NumberOfFilledGroups(Claims* For Reference Having [Claims/ClaimRef] == \\\"X\\\") > 0")
        actual["a string key, starred operand"] =
            filtered("Sum(Claims*/Amount For \\\"X\\\" Having [Claims/ClaimRef] == \\\"X\\\") > 0")
        actual["a string key, unstarred operand"] =
            filtered("Sum(Claims/Amount For \\\"X\\\" Having [Claims/ClaimRef] == \\\"X\\\") > 0")
        actual["a string key, starred operand, a bad name in the filter"] =
            filtered("Sum(Claims*/Amount For \\\"X\\\" Having [NoSuchField] == \\\"X\\\") > 0")
        actual["a string key on a group operand"] =
            filtered("NumberOfFilledGroups(Claims For \\\"X\\\" Having [Claims/ClaimRef] == \\\"X\\\") > 0")
        actual["a group key"] =
            filtered("Sum(Claims*/Amount For Insured Having [Insured/InsuredName] == \\\"X\\\") > 0")
        actual["a starred key"] =
            filtered("Sum(Claims*/Amount For Claims*/ClaimRef Having [Claims/ClaimRef] == \\\"X\\\") > 0")

        val expected = mutableMapOf<String, List<String>>()
        expected["an entity key, starred operand"] = listOf(starRequired)
        expected["an entity key, unstarred operand"] = listOf(starRequired)
        expected["an entity key on a group operand"] = listOf(starRequired)
        // The key's own gate is raised at the key's end, ahead of the filter the operand then carries.
        expected["a string key, starred operand"] = listOf("MVK_SEMANTIC_INDEX_AND_WILDCARD")
        expected["a string key, unstarred operand"] = listOf(starRequired)
        // The string key's production is its literal, so its gate stands ahead of the filter body.
        expected["a string key, starred operand, a bad name in the filter"] =
            listOf("MVK_SEMANTIC_INDEX_AND_WILDCARD")
        expected["a string key on a group operand"] = listOf(noGroups)
        expected["a group key"] = listOf(noGroups)
        // The key's filter passes the chain, so the index gate on the starred key is what answers.
        expected["a starred key"] = listOf("MVK_SEMANTIC_INDEX_WITH_WILDCARD")
        assertEquals(expected, actual, "whose filter a Having after an index key is")
    }

    /** Only this family's two classes, so a co-firing family cannot make a row read green. */
    private fun report(codes: List<String>): List<String> = codes.filter { it == noGroups || it == starRequired }
}
