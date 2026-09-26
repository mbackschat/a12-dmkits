package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of the semantic index's key-kind gate.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.SemanticIndexStructureLawsTest`. The SILENT rows carry the risk and outnumber the
 * reporting one, because A12 answers most malformed keys with a class this family deliberately does not name.
 */
class SemanticIndexKeyTest {

    private val noField = "MVK_SEMANTIC_INDEX_NO_FIELD"

    /** A policy model whose one rule carries [condition]; `Insured` is the only group that does not repeat. */
    private fun codes(condition: String): List<String> = checkRead(
        ModelSource(
            """
            {"header":{"id":"policy","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f1","name":"LookupCode","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Group","id":"g1","name":"Insured","Group":{"elements":[
                  {"type":"Field","id":"f2","name":"InsuredName","Field":{"fieldType":{"type":"StringType"}}}
                ]}},
                {"type":"Group","id":"g2","name":"Coverages","Group":{"repeatability":20,
                  "indexFieldName":"CoverageCode","elements":[
                  {"type":"Field","id":"f3","name":"CoverageCode","Field":{"fieldType":{"type":"StringType"}}},
                  {"type":"Field","id":"f4","name":"Limit","Field":{"fieldType":{"type":"NumberType"}}},
                  {"type":"Group","id":"g3","name":"Excess","Group":{"elements":[
                    {"type":"Field","id":"f5","name":"Amount","Field":{"fieldType":{"type":"NumberType"}}}
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

    private fun indexed(probe: String): List<String> = codes("FieldFilled(Reference) And $probe")

    @Test
    fun whichKeysDrawTheKindClass() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a LITERAL key — the control"] = indexed("[Coverages/Limit For \\\"C-1\\\"] > 0")
        actual["a FIELD key — the second control"] = indexed("[Coverages/Limit For LookupCode] > 0")
        actual["a NON-REPEATABLE group as the key"] = indexed("[Coverages/Limit For Insured] > 0")
        actual["a group key beside a second defect the chain reports later"] =
            indexed("[Coverages/Limit For Insured] > 0 And [Insured/InsuredName For \\\"X\\\"] == \\\"y\\\"")

        val expected = mutableMapOf<String, List<String>>()
        expected["a LITERAL key — the control"] = emptyList()
        expected["a FIELD key — the second control"] = emptyList()
        expected["a NON-REPEATABLE group as the key"] = listOf(noField)
        // The key-kind gate reports ahead of the missing-index-field gate, so one condition violating both
        // draws this class once per index rather than a second family's.
        expected["a group key beside a second defect the chain reports later"] = listOf(noField)
        assertEquals(expected, actual, "which semantic-index keys draw the kind class")
    }

    /**
     * The shapes it stays SILENT on, each because A12 answers them with a class this family does not own.
     *
     * An ITERATING key is the load-bearing one: A12's iteration gate pre-empts the whole chain and reports
     * `MVK_SEMANTIC_INDEX_INVALID_ITER`, so reporting the kind class there would hand the author a fix for a
     * gate they did not trip — the wrong-class defect this estate has already shipped once.
     */
    @Test
    fun theShapesItDeliberatelyDoesNotReport() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a key that does not resolve"] = indexed("[Coverages/Limit For NoSuchThing] > 0")
        actual["a STARRED key, which the structure family names"] =
            indexed("[Coverages/Limit For Coverages*/CoverageCode] > 0")

        val expected = mutableMapOf<String, List<String>>()
        expected["a key that does not resolve"] = listOf("MVK_INVALID_ENTITY")
        expected["a STARRED key, which the structure family names"] =
            listOf("MVK_SEMANTIC_INDEX_WITH_WILDCARD")
        assertEquals(expected, actual, "the semantic-index key shapes deliberately not reported")
    }

    /**
     * The iteration gate, which A12 asks of the key before anything else and answers by RENAMING.
     *
     * Every row mirrors one measured by `rulekit.validate.laws.SemanticIndexStructureLawsTest`; the last is
     * that grid's discriminator, showing the same read outside an index keeps the ordinary class.
     */
    @Test
    fun anIteratingKeyIsRenamedRatherThanAdded() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a REPEATABLE group as the key"] = indexed("[Coverages/Limit For Coverages] > 0")
        actual["a field INSIDE a repetition as the key"] =
            indexed("[Coverages/Limit For Coverages/CoverageCode] > 0")
        actual["a NON-repeatable group inside a repetition as the key"] =
            indexed("[Coverages/Limit For Coverages/Excess] > 0")
        actual["a key outside every repetition — the control"] =
            indexed("[Coverages/Limit For LookupCode] > 0")
        actual["the same iterating read OUTSIDE any semantic index"] =
            indexed("FieldFilled(Coverages/CoverageCode)")

        val expected = mutableMapOf<String, List<String>>()
        expected["a REPEATABLE group as the key"] = listOf(invalidIter)
        expected["a field INSIDE a repetition as the key"] = listOf(invalidIter)
        // The key's KIND is not the variable: a group here could have drawn the key-kind class instead.
        expected["a NON-repeatable group inside a repetition as the key"] = listOf(invalidIter)
        expected["a key outside every repetition — the control"] = emptyList()
        expected["the same iterating read OUTSIDE any semantic index"] = listOf("MVK_NO_WILDCARD")
        assertEquals(expected, actual, "an iterating key is renamed rather than added")
    }

    private val invalidIter = "MVK_SEMANTIC_INDEX_INVALID_ITER"

    /** The finding names the group, since the fix is to key on one of its fields instead. */
    @Test
    fun theFindingNamesTheGroupItWasKeyedOn() {
        val finding = checkRead(
            ModelSource(
                """
                {"header":{"id":"policy","locales":[{"code":"en_US"}]},
                 "content":{"modelRoot":{"rootGroups":[
                  {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
                    {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
                    {"type":"Group","id":"g1","name":"Insured","Group":{"elements":[
                      {"type":"Field","id":"f2","name":"InsuredName","Field":{"fieldType":{"type":"StringType"}}}
                    ]}},
                    {"type":"Group","id":"g2","name":"Coverages","Group":{"repeatability":20,
                      "indexFieldName":"CoverageCode","elements":[
                      {"type":"Field","id":"f3","name":"CoverageCode","Field":{"fieldType":{"type":"StringType"}}},
                      {"type":"Field","id":"f4","name":"Limit","Field":{"fieldType":{"type":"NumberType"}}}
                    ]}},
                    {"type":"Rule","id":"r0","name":"Probe","Rule":{
                      "errorCondition":"FieldFilled(Reference) And [Coverages/Limit For Insured] > 0",
                      "errorEntityRelPath":"../Reference",
                      "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
                  ]}}
                 ]}}}
                """.trimIndent(),
            ),
        ).findings.single()
        assertEquals(noField, finding.code)
        assertEquals("/Policy/Probe", finding.at)
        assertEquals(true, finding.explain.contains("/Policy/Insured"), finding.explain)
    }
}
