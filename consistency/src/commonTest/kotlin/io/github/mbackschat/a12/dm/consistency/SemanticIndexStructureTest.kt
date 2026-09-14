package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of the semantic index's structural chain.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.SemanticIndexStructureLawsTest`, including its doubly-defective cells: the order
 * among these gates is the part a reproduction gets wrong, and the three wildcard classes differ only in
 * where the star sits.
 */
class SemanticIndexStructureTest {

    @Test
    fun eachStructuralGateAndTheOrderAmongThem() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a literal key — the control"] = codes("""[Coverages/Limit For \"C-1\"] > 0""")
        actual["a field key — the second control"] = codes("[Coverages/Limit For LookupCode] > 0")
        actual["the key carries a WILDCARD"] = codes("[Coverages/Limit For Sections*/SectionName] > 0")
        actual["the key carries a CATEGORY"] = codes("[Endorsements/Amount For Tier -> Band] > 0")
        actual["the key carries its own index"] =
            codes("""[Coverages/Limit For Endorsements/Label For \"G-1\"] > 0""")
        actual["the indexed group declares no index field"] =
            codes("""[Sections/SectionLimit For \"S-1\"] > 0""")
        actual["an empty literal key"] = codes("""[Coverages/Limit For \"\"] > 0""")

        val expected = mutableMapOf<String, List<String>>()
        expected["a literal key — the control"] = emptyList()
        expected["a field key — the second control"] = emptyList()
        expected["the key carries a WILDCARD"] = listOf("MVK_SEMANTIC_INDEX_WITH_WILDCARD")
        expected["the key carries a CATEGORY"] = listOf("MVK_SEMANTIC_INDEX_WITH_CATEGORY")
        expected["the key carries its own index"] = listOf("MVK_SEMANTIC_INDEX_WITH_SEMANTIC_INDEX")
        expected["the indexed group declares no index field"] = listOf("MVK_NO_INDEX_FIELD")
        expected["an empty literal key"] = listOf("MVK_INDEX_VALUE_INVALID")
        assertEquals(expected, actual, "each structural gate")
    }

    @Test
    fun whereTheStarSitsChoosesTheClass() {
        val actual = mutableMapOf<String, List<String>>()
        actual["no star — the control"] =
            codes("""FirstFilledValue(Coverages/Note For \"C-1\", Reference) == \"x\" """)
        actual["the star at the indexed group's own level"] =
            codes("""FirstFilledValue(Coverages*/Note For \"C-1\", Reference) == \"x\" """)
        actual["the star ABOVE the indexed group"] =
            codes("""FirstFilledValue(Sections*/Items/ItemLimit For \"I-1\", Reference) == \"x\" """)
        actual["a starred key beside a star at the indexed group's level"] =
            codes("""FirstFilledValue(Coverages*/Note For Sections*/SectionName, Reference) == \"x\" """)
        actual["an empty key beside a star at the indexed group's level"] =
            codes("""FirstFilledValue(Coverages*/Note For \"\", Reference) == \"x\" """)
        actual["a star at the level of a group that declares no index field"] =
            codes("""FirstFilledValue(Sections*/SectionName For \"S-1\", Reference) == \"x\" """)

        val expected = mutableMapOf<String, List<String>>()
        expected["no star — the control"] = emptyList()
        expected["the star at the indexed group's own level"] = listOf("MVK_SEMANTIC_INDEX_AND_WILDCARD")
        expected["the star ABOVE the indexed group"] = listOf("MVK_SEMANTIC_INDEX_BELOW_WILDCARD")
        // The key's own star outranks the star on the indexed path, measured.
        expected["a starred key beside a star at the indexed group's level"] =
            listOf("MVK_SEMANTIC_INDEX_WITH_WILDCARD")
        expected["an empty key beside a star at the indexed group's level"] =
            listOf("MVK_INDEX_VALUE_INVALID")
        expected["a star at the level of a group that declares no index field"] = listOf("MVK_NO_INDEX_FIELD")
        assertEquals(expected, actual, "where the star sits chooses the class")
    }

    @Test
    fun theShapesAnotherGateAnswersFirst() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a GROUP key"] = codes("[Coverages/Limit For Insured] > 0")
        actual["an ITERATING key"] = codes("[Coverages/Limit For Coverages/CoverageCode] > 0")
        actual["a key naming nothing declared"] = codes("[Coverages/Limit For Nowhere] > 0")
        actual["an indexed reference naming nothing declared"] = codes("""[Nowhere/Limit For \"C-1\"] > 0""")

        val expected = mutableMapOf<String, List<String>>()
        // MVK_SEMANTIC_INDEX_NO_FIELD, the key-kind family's, ahead of every gate here.
        expected["a GROUP key"] = emptyList()
        // MVK_SEMANTIC_INDEX_INVALID_ITER, which A12 substitutes for every class of this chain.
        expected["an ITERATING key"] = emptyList()
        expected["a key naming nothing declared"] = emptyList()
        expected["an indexed reference naming nothing declared"] = emptyList()
        assertEquals(expected, actual, "the shapes another gate answers first")
    }

    /** Only this family's six codes, so another family's finding cannot make a row look right. */
    private fun codes(probe: String): List<String> =
        check(ModelSource(model(probe))).findings
            .filter { it.code in OWNED }
            .map { it.code }

    /** An insurance policy with three indexed shapes, mirroring the laws test's carrier. */
    private fun model(probe: String): String = """
        {"header":{"id":"policy","locales":[{"code":"en_US"}]},
         "content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f1","name":"LookupCode","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f2","name":"Tier","Field":{"fieldType":{"type":"EnumerationType",
              "EnumerationType":{"values":[{"value":"BRONZE"},{"value":"GOLD"}],
              "categories":[{"name":"Band","values":[{"value":"LOW"},{"value":"HIGH"}]}]}}}},
            {"type":"Group","id":"g1","name":"Insured","Group":{"elements":[
              {"type":"Field","id":"f3","name":"InsuredName","Field":{"fieldType":{"type":"StringType"}}}
            ]}},
            {"type":"Group","id":"g2","name":"Coverages","Group":{"repeatability":20,
              "indexFieldName":"CoverageCode","elements":[
              {"type":"Field","id":"f4","name":"CoverageCode","Field":{"fieldType":{"type":"StringType"}}},
              {"type":"Field","id":"f5","name":"Note","Field":{"fieldType":{"type":"StringType"}}},
              {"type":"Field","id":"f6","name":"Limit","Field":{"fieldType":{"type":"NumberType"}}}
            ]}},
            {"type":"Group","id":"g3","name":"Endorsements","Group":{"repeatability":10,
              "indexFieldName":"Grade","elements":[
              {"type":"Field","id":"f7","name":"Grade","Field":{"fieldType":{"type":"EnumerationType",
                "EnumerationType":{"values":[{"value":"BRONZE"},{"value":"GOLD"}]}}}},
              {"type":"Field","id":"f8","name":"Label","Field":{"fieldType":{"type":"StringType"}}},
              {"type":"Field","id":"f9","name":"Amount","Field":{"fieldType":{"type":"NumberType"}}}
            ]}},
            {"type":"Group","id":"g4","name":"Sections","Group":{"repeatability":10,"elements":[
              {"type":"Field","id":"f10","name":"SectionName","Field":{"fieldType":{"type":"StringType"}}},
              {"type":"Field","id":"f11","name":"SectionLimit","Field":{"fieldType":{"type":"NumberType"}}},
              {"type":"Group","id":"g5","name":"Items","Group":{"repeatability":10,
                "indexFieldName":"ItemCode","elements":[
                {"type":"Field","id":"f12","name":"ItemCode","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f13","name":"ItemLimit","Field":{"fieldType":{"type":"NumberType"}}}
              ]}}
            ]}},
            {"type":"Rule","id":"r0","name":"Probe","Rule":{
              "errorCondition":"FieldFilled(Reference) And $probe",
              "errorEntityRelPath":"../Reference",
              "errorCode":"C0","errorMessage":[{"locale":"en_US","text":"x"}]}}
          ]}}
         ]}}}
    """.trimIndent()

    private companion object {
        val OWNED = setOf(
            "MVK_SEMANTIC_INDEX_WITH_WILDCARD",
            "MVK_SEMANTIC_INDEX_WITH_SEMANTIC_INDEX",
            "MVK_NO_INDEX_FIELD",
            "MVK_SEMANTIC_INDEX_BELOW_WILDCARD",
            "MVK_INDEX_VALUE_INVALID",
            "MVK_SEMANTIC_INDEX_WITH_CATEGORY",
            "MVK_SEMANTIC_INDEX_AND_WILDCARD",
        )
    }
}
