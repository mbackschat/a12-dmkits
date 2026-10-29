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

    /**
     * The two gates that read the key AGAINST the indexed group, on a rule anchored inside that group.
     *
     * Every row mirrors `rulekit.validate.laws.SemanticIndexKeyGateLawsTest`. The anchoring is the point: a
     * key inside the indexed group is unreachable without it, because A12's iteration gate answers first.
     */
    @Test
    fun theTypeGateAnswersAheadOfThePlacementGate() {
        val actual = mutableMapOf<String, List<String>>()
        actual["outside the indexed group, type matching — the control"] = runKeyedOn("/Depot/LookupKey")
        actual["INSIDE the indexed group, type matching"] = runKeyedOn("/Depot/Runs/RunNote")
        actual["outside it, type differing"] = runKeyedOn("/Depot/Excess")
        actual["INSIDE it AND type differing"] = runKeyedOn("/Depot/Runs/RunCount")
        actual["inside ANOTHER repetition, which the anchor does not bind"] =
            runKeyedOn("/Depot/Shifts/ShiftLabel")
        actual["inside the anchor, indexing a THIRD group, type matching"] =
            depotCodes("[/Depot/Legs/LegWeight For /Depot/Runs/RunNote] > 0", RUNS)
        actual["inside the anchor, indexing a THIRD group, type differing"] =
            depotCodes("[/Depot/Legs/LegWeight For /Depot/Runs/RunCount] > 0", RUNS)
        actual["a key without value validation that is also inside the indexed group"] =
            runKeyedOn("/Depot/Runs/RunRaw")

        val expected = mutableMapOf<String, List<String>>()
        expected["outside the indexed group, type matching — the control"] = emptyList()
        expected["INSIDE the indexed group, type matching"] = listOf("MVK_SEMANTIC_INDEX_CONTAINED_IN_INDEX")
        expected["outside it, type differing"] = listOf("MVK_INDEX_TYPE_INCONSISTENT")
        expected["INSIDE it AND type differing"] = listOf("MVK_INDEX_TYPE_INCONSISTENT")
        // A12 answers MVK_ERROR_FIELD_INVALID_INDEX here — the rule-level gate ahead of this chain, and no
        // family's here — so silence is the only reading that does not name a class on the wrong gate.
        expected["inside ANOTHER repetition, which the anchor does not bind"] = emptyList()
        expected["inside the anchor, indexing a THIRD group, type matching"] = emptyList()
        expected["inside the anchor, indexing a THIRD group, type differing"] =
            listOf("MVK_INDEX_TYPE_INCONSISTENT")
        expected["a key without value validation that is also inside the indexed group"] =
            listOf("MVK_SEMANTIC_INDEX_CONTAINED_IN_INDEX")
        assertEquals(expected, actual, "the type gate answers ahead of the placement gate")
    }

    /** Which declared types the key gate calls inconsistent, read from both sides of the comparison. */
    @Test
    fun whichDeclaredTypesTheKeyGateCallsInconsistent() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a STRING index field, a string key"] = depotCodes("[Runs/RunLimit For LookupKey] > 0", ROOT)
        actual["a STRING index field, a custom key"] = depotCodes("[Runs/RunLimit For Courier] > 0", ROOT)
        actual["a STRING index field, an enum key"] = depotCodes("[Runs/RunLimit For Grade] > 0", ROOT)
        actual["a STRING index field, a number key"] = depotCodes("[Runs/RunLimit For Excess] > 0", ROOT)
        actual["a STRING index field, a boolean key"] = depotCodes("[Runs/RunLimit For Insured] > 0", ROOT)
        actual["a STRING index field, a date key"] = depotCodes("[Runs/RunLimit For ShippedOn] > 0", ROOT)
        actual["a STRING index field, a time key"] = depotCodes("[Runs/RunLimit For PickupTime] > 0", ROOT)
        actual["a STRING index field, a date-range key"] = depotCodes("[Runs/RunLimit For Window] > 0", ROOT)
        actual["a STRING index field, a confirm key"] = depotCodes("[Runs/RunLimit For Acknowledged] > 0", ROOT)
        actual["an ENUM index field, an enum key"] = depotCodes("[Shifts/ShiftAmount For Grade] > 0", ROOT)
        actual["an ENUM index field, a string key"] = depotCodes("[Shifts/ShiftAmount For LookupKey] > 0", ROOT)
        actual["an ENUM index field, a custom key"] = depotCodes("[Shifts/ShiftAmount For Courier] > 0", ROOT)

        val expected = mutableMapOf<String, List<String>>()
        expected["a STRING index field, a string key"] = emptyList()
        // Custom shares the text class rather than matching by name, so this row is the one a checker
        // comparing declared types for equality would refuse.
        expected["a STRING index field, a custom key"] = emptyList()
        expected["a STRING index field, an enum key"] = listOf("MVK_INDEX_TYPE_INCONSISTENT")
        expected["a STRING index field, a number key"] = listOf("MVK_INDEX_TYPE_INCONSISTENT")
        expected["a STRING index field, a boolean key"] = listOf("MVK_INDEX_TYPE_INCONSISTENT")
        expected["a STRING index field, a date key"] = listOf("MVK_INDEX_TYPE_INCONSISTENT")
        expected["a STRING index field, a time key"] = listOf("MVK_INDEX_TYPE_INCONSISTENT")
        expected["a STRING index field, a date-range key"] = listOf("MVK_INDEX_TYPE_INCONSISTENT")
        expected["a STRING index field, a confirm key"] = listOf("MVK_INDEX_TYPE_INCONSISTENT")
        expected["an ENUM index field, an enum key"] = emptyList()
        // …and the relation is not symmetric: the enumeration side admits nothing but its own.
        expected["an ENUM index field, a string key"] = listOf("MVK_INDEX_TYPE_INCONSISTENT")
        expected["an ENUM index field, a custom key"] = listOf("MVK_INDEX_TYPE_INCONSISTENT")
        assertEquals(expected, actual, "which declared types the key gate calls inconsistent")
    }

    /** Where the key gates sit relative to the star gates and the category gate. */
    @Test
    fun theOrderAroundTheKeyGates() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a star ABOVE the indexed group beside a type-differing key"] =
            firstFilled("Regions*/Docks/DockLimit For LookupKey")
        actual["a star at the indexed group's own level beside a type-differing key"] =
            firstFilled("Runs*/RunNote For Excess")
        actual["a CATEGORY key that also differs in type"] =
            depotCodes("[Runs/RunLimit For Grade -> Band] > 0", ROOT)
        actual["a key inside the indexed group that also carries a CATEGORY"] =
            depotCodes("[/Depot/Shifts/ShiftAmount For /Depot/Shifts/ShiftTier -> Band] > 0", SHIFTS)
        actual["a star ABOVE the indexed group, nothing else wrong"] =
            firstFilled("Regions*/Docks/DockLimit For Grade")
        actual["a star ABOVE the indexed group beside an EMPTY key"] =
            firstFilled("""Regions*/Docks/DockLimit For \"\" """)
        actual["a star ABOVE the indexed group beside a CATEGORY key"] =
            firstFilled("Regions*/Docks/DockLimit For Grade -> Band")
        actual["a star ABOVE the indexed group beside a star at its own level"] =
            firstFilled("Regions*/Docks*/DockLimit For Grade")
        actual["a key without value validation beside a star at the indexed group's own level"] =
            firstFilled("Runs*/RunNote For RawNote")

        val expected = mutableMapOf<String, List<String>>()
        expected["a star ABOVE the indexed group beside a type-differing key"] =
            listOf("MVK_INDEX_TYPE_INCONSISTENT")
        expected["a star at the indexed group's own level beside a type-differing key"] =
            listOf("MVK_INDEX_TYPE_INCONSISTENT")
        expected["a CATEGORY key that also differs in type"] = listOf("MVK_INDEX_TYPE_INCONSISTENT")
        expected["a key inside the indexed group that also carries a CATEGORY"] =
            listOf("MVK_SEMANTIC_INDEX_CONTAINED_IN_INDEX")
        expected["a star ABOVE the indexed group, nothing else wrong"] =
            listOf("MVK_SEMANTIC_INDEX_BELOW_WILDCARD")
        expected["a star ABOVE the indexed group beside an EMPTY key"] = listOf("MVK_INDEX_VALUE_INVALID")
        expected["a star ABOVE the indexed group beside a CATEGORY key"] =
            listOf("MVK_SEMANTIC_INDEX_WITH_CATEGORY")
        expected["a star ABOVE the indexed group beside a star at its own level"] =
            listOf("MVK_SEMANTIC_INDEX_AND_WILDCARD")
        expected["a key without value validation beside a star at the indexed group's own level"] =
            listOf("MVK_INVALID_RAW_TYPE")
        assertEquals(expected, actual, "the order around the key gates")
    }

    private fun runKeyedOn(key: String): List<String> =
        depotCodes("[/Depot/Runs/RunLimit For $key] > 0", RUNS)

    private fun firstFilled(operand: String): List<String> =
        depotCodes("""FirstFilledValue($operand, Reference) == \"x\" """, ROOT)

    /** Only this family's codes, so another family's finding cannot make a row look right. */
    private fun codes(probe: String): List<String> =
        checkRead(ModelSource(model(probe))).findings
            .filter { it.code in OWNED }
            .map { it.code }

    private fun depotCodes(probe: String, anchor: String): List<String> =
        checkRead(ModelSource(depot(probe, anchor))).findings
            .filter { it.code in OWNED }
            .map { it.code }

    /**
     * A parcel depot mirroring the key-gate laws carrier, with the rule declared in [anchor].
     *
     * Where the rule sits decides which reads iterate, so the anchor is a parameter rather than a second
     * fixture: the same index draws the placement class from inside the indexed group and nothing at all
     * from the root.
     */
    private fun depot(probe: String, anchor: String): String = """
        {"header":{"modelVersion":"28.4.0","id":"depot","locales":[{"code":"en_US"}]},
         "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Depot","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f1","name":"LookupKey","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f2","name":"RawNote","Field":{"fieldType":{"type":"StringType",
              "StringType":{"noValueValidation":true}}}},
            {"type":"Field","id":"f3","name":"Excess","Field":{"fieldType":{"type":"NumberType"}}},
            {"type":"Field","id":"f4","name":"Insured","Field":{"fieldType":{"type":"BooleanType"}}},
            {"type":"Field","id":"f5","name":"ShippedOn","Field":{"fieldType":{"type":"DateType",
              "DateType":{"format":"dd.MM.yyyy"}}}},
            {"type":"Field","id":"f6","name":"PickupTime","Field":{"fieldType":{"type":"TimeType",
              "TimeType":{"format":"HH:mm:ss"}}}},
            {"type":"Field","id":"f7","name":"Window","Field":{"fieldType":{"type":"DateRangeType",
              "DateRangeType":{"format":"dd.MM.yyyy","rangeSeparator":"-"}}}},
            {"type":"Field","id":"f8","name":"Acknowledged","Field":{"fieldType":{"type":"ConfirmType"}}},
            {"type":"Field","id":"f9","name":"Courier","Field":{"fieldType":{"type":"CustomFieldType",
              "CustomFieldType":{"name":"CourierCode"}}}},
            {"type":"Field","id":"f10","name":"Grade","Field":{"fieldType":{"type":"EnumerationType",
              "EnumerationType":{"values":[{"value":"BRONZE"},{"value":"GOLD"}],
              "categories":[{"name":"Band","values":[{"value":"LOW"},{"value":"HIGH"}]}]}}}},
            {"type":"Group","id":"g1","name":"Runs","Group":{"repeatability":20,
              "indexFieldName":"RunKey","elements":[
              {"type":"Field","id":"f11","name":"RunKey","Field":{"fieldType":{"type":"StringType"}}},
              {"type":"Field","id":"f12","name":"RunNote","Field":{"fieldType":{"type":"StringType"}}},
              {"type":"Field","id":"f13","name":"RunRaw","Field":{"fieldType":{"type":"StringType",
                "StringType":{"noValueValidation":true}}}},
              {"type":"Field","id":"f14","name":"RunCount","Field":{"fieldType":{"type":"NumberType"}}},
              {"type":"Field","id":"f15","name":"RunLimit","Field":{"fieldType":{"type":"NumberType"}}}
              ${ruleIn(anchor, RUNS, probe, "RunNote")}
            ]}},
            {"type":"Group","id":"g2","name":"Shifts","Group":{"repeatability":10,
              "indexFieldName":"ShiftGrade","elements":[
              {"type":"Field","id":"f16","name":"ShiftGrade","Field":{"fieldType":{"type":"EnumerationType",
                "EnumerationType":{"values":[{"value":"BRONZE"},{"value":"GOLD"}]}}}},
              {"type":"Field","id":"f17","name":"ShiftTier","Field":{"fieldType":{"type":"EnumerationType",
                "EnumerationType":{"values":[{"value":"BRONZE"},{"value":"GOLD"}],
                "categories":[{"name":"Band","values":[{"value":"LOW"},{"value":"HIGH"}]}]}}}},
              {"type":"Field","id":"f18","name":"ShiftLabel","Field":{"fieldType":{"type":"StringType"}}},
              {"type":"Field","id":"f19","name":"ShiftAmount","Field":{"fieldType":{"type":"NumberType"}}}
              ${ruleIn(anchor, SHIFTS, probe, "ShiftLabel")}
            ]}},
            {"type":"Group","id":"g3","name":"Legs","Group":{"repeatability":10,
              "indexFieldName":"LegCode","elements":[
              {"type":"Field","id":"f20","name":"LegCode","Field":{"fieldType":{"type":"StringType"}}},
              {"type":"Field","id":"f21","name":"LegWeight","Field":{"fieldType":{"type":"NumberType"}}}
            ]}},
            {"type":"Group","id":"g4","name":"Regions","Group":{"repeatability":5,"elements":[
              {"type":"Field","id":"f22","name":"RegionName","Field":{"fieldType":{"type":"StringType"}}},
              {"type":"Group","id":"g5","name":"Docks","Group":{"repeatability":5,
                "indexFieldName":"DockGrade","elements":[
                {"type":"Field","id":"f23","name":"DockGrade","Field":{"fieldType":{"type":"EnumerationType",
                  "EnumerationType":{"values":[{"value":"BRONZE"},{"value":"GOLD"}]}}}},
                {"type":"Field","id":"f24","name":"DockName","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f25","name":"DockLimit","Field":{"fieldType":{"type":"NumberType"}}}
              ]}}
            ]}}
            ${ruleIn(anchor, ROOT, probe, "Reference")}
          ]}}
         ]}}}
    """.trimIndent()

    /** The rule element, emitted only in the group the row anchors it in. */
    private fun ruleIn(anchor: String, group: String, probe: String, errorField: String): String =
        if (anchor != group) {
            ""
        } else {
            """,{"type":"Rule","id":"r0","name":"Probe","Rule":{""" +
                """"errorCondition":"FieldFilled($errorField) And $probe",""" +
                """"errorEntityRelPath":"../$errorField",""" +
                """"errorCode":"C0","errorMessage":[{"locale":"en_US","text":"x"}]}}"""
        }

    /** An insurance policy with three indexed shapes, mirroring the laws test's carrier. */
    private fun model(probe: String): String = """
        {"header":{"modelVersion":"28.4.0","id":"policy","locales":[{"code":"en_US"}]},
         "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
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
        const val ROOT = "Depot"
        const val RUNS = "Runs"
        const val SHIFTS = "Shifts"

        val OWNED = setOf(
            "MVK_SEMANTIC_INDEX_WITH_WILDCARD",
            "MVK_SEMANTIC_INDEX_WITH_SEMANTIC_INDEX",
            "MVK_NO_INDEX_FIELD",
            "MVK_SEMANTIC_INDEX_BELOW_WILDCARD",
            "MVK_INDEX_VALUE_INVALID",
            "MVK_INDEX_TYPE_INCONSISTENT",
            "MVK_SEMANTIC_INDEX_CONTAINED_IN_INDEX",
            "MVK_INVALID_RAW_TYPE",
            "MVK_SEMANTIC_INDEX_WITH_CATEGORY",
            "MVK_SEMANTIC_INDEX_AND_WILDCARD",
        )
    }
}
