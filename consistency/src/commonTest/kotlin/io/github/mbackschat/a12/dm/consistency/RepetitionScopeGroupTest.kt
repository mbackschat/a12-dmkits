package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of "what a `RepetitionNotUnique` `@From` scope has to be".
 *
 * Mirrors `rulekit.validate.laws.RepetitionNotUniqueFromScopeLawsTest` on the same depot shape, row for row
 * — including every cell where A12 reports a DIFFERENT class, written as an explicit empty expectation. Those
 * are the family's whole risk, and two of them are classes a plausible reading would have merged into this
 * one: a FIELD scope is gated on its kind before its repeatability, and a key outside a REPEATABLE scope is a
 * containment question that turns on joint iteration rather than on the scope at all.
 */
class RepetitionScopeGroupTest {

    private val notRepeatable = "MVK_GROUP_NOT_REPEATABLE"

    @Test
    fun onlyANonRepeatableGroupScopeIsReported() {
        val actual = mutableMapOf<String, List<String>>()
        actual["the scope is the repeatable group holding the key"] =
            codes("../Crates/CrateRef", "RepetitionNotUnique(Crates/CrateRef @From Crates)")
        actual["the scope is a PARALLEL repeatable group"] =
            codes("../Crates/CrateRef", "RepetitionNotUnique(Crates/CrateRef @From Pallets)")
        actual["the scope is the non-repeatable ROOT"] =
            codes("../Crates/CrateRef", "RepetitionNotUnique(Crates/CrateRef @From /Depot)")
        actual["the scope is a non-repeatable group that is not the root"] =
            codes("../Crates/CrateRef", "RepetitionNotUnique(Crates/CrateRef @From Office)")
        actual["the scope names a FIELD"] =
            codes("../Crates/CrateRef", "RepetitionNotUnique(Crates/CrateRef @From Crates/CrateRef)")
        actual["the scope names nothing declared"] =
            codes("../Crates/CrateRef", "RepetitionNotUnique(Crates/CrateRef @From Nowhere)")
        actual["no scope at all"] =
            codes("../Crates/CrateRef", "RepetitionNotUnique(Crates/CrateRef)")

        val expected = mutableMapOf<String, List<String>>()
        expected["the scope is the repeatable group holding the key"] = emptyList()
        // A12 draws MVK_NO_WILDCARD: the two groups do not iterate jointly, so it refuses the pairing before
        // asking anything about the scope. The scope itself is legal and this family has nothing to say.
        expected["the scope is a PARALLEL repeatable group"] = emptyList()
        expected["the scope is the non-repeatable ROOT"] = listOf(notRepeatable)
        expected["the scope is a non-repeatable group that is not the root"] = listOf(notRepeatable)
        // A12 draws MVK_NO_FIELDS_ALLOWED — the KIND gate runs first, so "not a repeatable group" being true
        // of every field would have attached this fix to a different defect.
        expected["the scope names a FIELD"] = emptyList()
        // MVK_INVALID_ENTITY, and the reference family already reports it; a second opinion here would say
        // the scope does not repeat about something that does not exist.
        expected["the scope names nothing declared"] = emptyList()
        expected["no scope at all"] = emptyList()
        assertEquals(expected, actual, "RepetitionNotUnique @From scope")
    }

    /** Every code this family draws, which is the only thing these models are built to defect in. */
    private fun codes(errorField: String, condition: String): List<String> =
        check(ModelSource(model(errorField, condition))).findings
            .filter { it.code == notRepeatable }
            .map { it.code }

    /**
     * The depot the laws test measures on: two parallel repeatable groups, a non-repeatable one beside them,
     * and a field directly in the scope so the kind gate has a carrier.
     */
    private fun model(errorField: String, condition: String): String = """
        {"header":{"id":"depot","locales":[{"code":"en_US"}]},
         "content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Depot","Group":{"elements":[
            {"type":"Field","id":"f0","name":"DepotRef","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Group","id":"g1","name":"Crates","Group":{"repeatability":20,"elements":[
              {"type":"Field","id":"f1","name":"CrateRef","Field":{"fieldType":{"type":"StringType"}}}
            ]}},
            {"type":"Group","id":"g2","name":"Pallets","Group":{"repeatability":10,"elements":[
              {"type":"Field","id":"f2","name":"PalletRef","Field":{"fieldType":{"type":"StringType"}}}
            ]}},
            {"type":"Group","id":"g3","name":"Office","Group":{"elements":[
              {"type":"Field","id":"f3","name":"OfficeRef","Field":{"fieldType":{"type":"StringType"}}}
            ]}},
            {"type":"Rule","id":"r0","name":"Probe","Rule":{
              "errorCondition":"$condition","errorEntityRelPath":"$errorField",
              "errorCode":"C0","errorMessage":[{"locale":"en_US","text":"x"}]}}
          ]}}
         ]}}}
    """.trimIndent()
}
