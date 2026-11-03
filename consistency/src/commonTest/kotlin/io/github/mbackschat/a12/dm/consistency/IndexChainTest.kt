package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * What the parallel-level walk answers, on the fixture that separates its two outcomes.
 *
 * **Why this chain earns its own lock rather than riding the families'.** `levelClassOf` is an ORDERED walk
 * whose five gates return three different classes, and it sits on `parallelVerdictOf`, which resolves an
 * index field at each end and compares their base families. Four families read it and none of their own
 * tests reaches the index comparison, so a defect in it would show up as silence rather than as a failure.
 */
class IndexChainTest {

    /**
     * Two repeatable branches under one claim, each keyed by its own declared index field.
     *
     * The keys are the instrument: A12 iterates two branches jointly only when both resolve to one index
     * field name AND the two fields share a base family, so naming the claim-items key [itemsKey] moves the
     * verdict between `Joint` (silence) and a refusal without touching anything else.
     */
    private fun claim(itemsKey: String) = ModelSource(
        """
        {"header":{"modelVersion":"29.4.0","id":"claims","locales":[{"code":"en_US"}]},
         "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Claim","Group":{"elements":[
            {"type":"Rule","id":"r0","name":"ItemCovered","Rule":{
              "errorEntityRelPath":"../Items/Covered","errorCondition":"FieldFilled(Items*/Covered)",
              "errorCode":"item.covered"}},
            {"type":"Group","id":"g1","name":"Items","Group":{
              "repeatability":99,"indexFieldName":"$itemsKey","elements":[
                {"type":"Field","id":"f0","name":"$itemsKey","Field":{"fieldType":{"type":"NumberType"}}},
                {"type":"Field","id":"f1","name":"Covered","Field":{"fieldType":{"type":"BooleanType"}}}
            ]}},
            {"type":"Group","id":"g2","name":"Charges","Group":{
              "repeatability":99,"indexFieldName":"LineNo","elements":[
                {"type":"Field","id":"f2","name":"LineNo","Field":{"fieldType":{"type":"NumberType"}}},
                {"type":"Field","id":"f3","name":"Amount","Field":{"fieldType":{"type":"NumberType"}}}
            ]}}
          ]}}
         ]}}}
        """.trimIndent(),
    )

    private fun prepared(itemsKey: String): PreparedModel {
        val admitted = admit(claim(itemsKey))
        assertIs<Admission.Admitted>(admitted, "the fixture must be admissible")
        return prepare(admitted)
    }

    private fun PreparedModel.levelClass(): String? {
        val tracked = tracked()
        return tracked.levelClassOf(
            "Charges/Amount", "/Claim", TargetAnchor("/Claim/Items", "/Claim/Items"),
            tracked.declarations().single { it.path == "/Claim/ItemCovered" })
    }

    @Test
    fun aParallelLevelWithADifferentIndexFieldDrawsTheIndexClass() {
        // `RowNo` against the charges' `LineNo`: two keyed branches that do not correspond, which A12 refuses
        // by name — the one outcome of this walk that is neither silence nor a wildcard class.
        assertEquals(
            "MVK_ERROR_FIELD_INVALID_INDEX",
            prepared("RowNo").levelClass(),
            "the mismatched keys must draw the index class",
        )
    }

    @Test
    fun theWalkStaysSilentWhereTheBranchesIterateJointly() {
        // The same model with one key renamed: both branches now resolve to `LineNo` over a number field, so
        // A12 iterates them together and this walk has nothing to say. The control for the row above — the
        // two differ in one character of the fixture.
        assertNull(prepared("LineNo").levelClass(), "jointly iterated branches must draw nothing")
    }
}
