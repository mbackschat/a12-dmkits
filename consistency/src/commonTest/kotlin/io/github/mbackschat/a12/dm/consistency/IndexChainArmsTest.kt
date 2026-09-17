package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The parallel-level walk answers the same on both receivers while the estate is part-migrated.
 *
 * **Why this chain earns its own lock rather than riding the families'.** `levelClassOf` is an ORDERED walk
 * whose five gates return three different classes, and it sits on `parallelVerdictOf`, which resolves an
 * index field at each end and compares their base families. Both are shared by migrated and unmigrated
 * families, so the two arms answering differently would make one half of the estate report
 * `MVK_ERROR_FIELD_INVALID_INDEX` where the other half stays silent — on one model, with every family's own
 * test green, because a family only ever calls one arm.
 *
 * The walk itself is one implementation shared by both arms, so what this test can still catch is the arms
 * feeding it different DECLARATIONS, which is exactly what the per-carrier index lookups do.
 */
class IndexChainArmsTest {

    /**
     * Two repeatable branches under one claim, each keyed by its own declared index field.
     *
     * The keys are the instrument: A12 iterates two branches jointly only when both resolve to one index
     * field name AND the two fields share a base family, so naming the claim-items key [itemsKey] moves the
     * verdict between `Joint` (silence) and a refusal without touching anything else.
     */
    private fun claim(itemsKey: String) = ModelSource(
        """
        {"header":{"id":"claims","locales":[{"code":"en_US"}]},
         "content":{"modelRoot":{"rootGroups":[
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

    private fun PreparedModel.classOnBothArms(): Pair<String?, String?> {
        val rule = "/Claim/ItemCovered"
        val untracked = levelClassOf(
            "Charges/Amount", "/Claim", "/Claim/Items", elements.single { it.path == rule })
        val tracked = tracked().levelClassOf(
            "Charges/Amount", "/Claim", "/Claim/Items",
            tracked().declarations().single { it.path == rule })
        return untracked to tracked
    }

    @Test
    fun bothArmsRefuseAParallelLevelWithADifferentIndexFieldAlike() {
        // `RowNo` against the charges' `LineNo`: two keyed branches that do not correspond, which A12 refuses
        // by name — the one outcome of this walk that is neither silence nor a wildcard class.
        val (untracked, tracked) = prepared("RowNo").classOnBothArms()

        assertEquals(
            "MVK_ERROR_FIELD_INVALID_INDEX",
            untracked,
            "the mismatched keys must draw the index class, or this test compares two silences",
        )
        assertEquals(untracked, tracked, "the two arms must draw the same class")
    }

    @Test
    fun bothArmsStaySilentWhereTheBranchesIterateJointly() {
        // The same model with one key renamed: both branches now resolve to `LineNo` over a number field, so
        // A12 iterates them together and this walk has nothing to say.
        val (untracked, tracked) = prepared("LineNo").classOnBothArms()

        assertNull(untracked, "jointly iterated branches must draw nothing")
        assertEquals(untracked, tracked, "the two arms must stay silent alike")
    }
}
