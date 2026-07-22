package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM+Node) lock for the partial-validation star-extent gate (SPEC-2026-07-22-09): a starred
 * value-list entry establishes its complete extent only from WILDCARD coverage of the starred level, not from
 * concretely enumerating every current row. `No` (either side) and `NotAll` (values side) are suppressed
 * (UNKNOWN) when the extent is unknown. The JVM tri-differential `PartialValidationValueListDiffTest` anchors
 * these to both kernel strategies; this pins the interpreter's own JVM+Node behavior.
 */
class PartialValidationValueListExtentTest {

    private fun model(rawCond: String) = ModelDef(
        fields = listOf(FieldDef(ACK, Kind.STRING), FieldDef(TAG, Kind.STRING)),
        rules = listOf(RuleDef("/Order/VLQ", "/Order", "FieldFilled(/Order/Ack) And ($rawCond)", "VLQ", "../Ack")),
        repeatableGroups = setOf("/Order/Items"),
        declaredReps = mapOf("/Order/Items" to 50),
    )

    private fun fired(rawCond: String, rel: Set<RelevantEntity>): Int {
        val m = model(rawCond)
        val doc = m.newDocument().group("/Order", 1).field(ACK, "X", 1, 1)
            .group("/Order/Items", 1, 1).field(TAG, "C", 1, 1, 1)
            .group("/Order/Items", 1, 2).field(TAG, "D", 1, 2, 1)
        return DmInterpreter(m).validatePart(doc, rel).messages.count { it.code == "VLQ" }
    }

    private val wild = setOf(RelevantEntity.anyRep(ACK), RelevantEntity.anyRep(ITEMS))
    private val concrete = setOf(RelevantEntity.anyRep(ACK), RelevantEntity(ITEMS, listOf(0, 1)), RelevantEntity(ITEMS, listOf(0, 2)))

    @Test
    fun noFieldsSideStarFiresOnlyWithWildcardExtent() {
        val cond = "NoFieldValueIncludedInValueList(/Order/Items*/Tag In \"A\")"   // Tags C,D ∉ {A} → No holds
        assertEquals(1, fired(cond, wild), "wildcard coverage establishes extent → No fires")
        assertEquals(0, fired(cond, concrete), "concrete rows do NOT establish extent → No is UNKNOWN")
    }

    @Test
    fun noValuesSideStarFiresOnlyWithWildcardExtent() {
        val cond = "NoFieldValueIncludedInValueList(/Order/Ack In /Order/Items*/Tag)"   // Ack X ∉ {C,D} → No holds
        assertEquals(1, fired(cond, wild), "wildcard coverage establishes extent → No fires")
        assertEquals(0, fired(cond, concrete), "concrete rows do NOT establish extent → No is UNKNOWN")
    }

    @Test
    fun notAllValuesSideStarFiresOnlyWithWildcardExtent() {
        val cond = "NotAllFieldValuesIncludedInValueList(/Order/Ack In /Order/Items*/Tag)"   // Ack X ∉ {C,D} → NotAll holds
        assertEquals(1, fired(cond, wild), "wildcard coverage establishes extent → NotAll(values) fires")
        assertEquals(0, fired(cond, concrete), "concrete rows do NOT establish extent → NotAll(values) is UNKNOWN")
    }

    @Test
    fun notAllFieldsSideAndAtLeastOneIgnoreExtent() {
        val notAll = "NotAllFieldValuesIncludedInValueList(/Order/Items*/Tag In \"C\")"   // C in, D out → holds, fields-side extent ignored
        assertEquals(fired(notAll, wild), fired(notAll, concrete), "NotAll ignores extent on the fields side")
        val atLeastOne = "AtLeastOneFieldValueIncludedInValueList(/Order/Items*/Tag In \"C\")"   // C ∈ {C} → holds
        assertEquals(fired(atLeastOne, wild), fired(atLeastOne, concrete), "AtLeastOne ignores extent on either side")
    }

    private companion object {
        const val ACK = "/Order/Ack"
        const val ITEMS = "/Order/Items"
        const val TAG = "/Order/Items/Tag"
    }
}
