package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — `RepetitionNotUnique(keys…)`: a rule at the PARENT (`/Order`) that flags each `Items` row
 * whose (composite) key duplicates another's within the parent-row scope. Compares the FULL per-row message
 * set (pointer + polarity) to the kernel, so it locks the multiplicity AND the empty-component nuance
 * (kernel-probed): empties match empties among participants, an all-empty key row is skipped, and a duplicate
 * whose key has an empty (fillable) component is OMISSION (else VALUE). `Items` rows carry a distinct `Sku`
 * (the index field) so the `uniqueIndex` check is silent.
 */
class RepetitionNotUniqueDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val fields = listOf(FieldDef(SKU, Kind.STRING), FieldDef(COUNT, Kind.NUMBER), FieldDef(WEIGHT, Kind.NUMBER))

    // Conditions are authored with the rulekit typed DSL ([m]) and rendered to the canonical string fed to BOTH
    // engines — no free-text condition literals. ABSOLUTE paths; both engines accept them.
    private val m = BoundModel.ofJson(order)
    private fun render(c: Condition): String = DslRenderer().render(c)

    /** [cond] over `Items` rows = [rows] (count, weight); compare the kernel's and interpreter's RU message set. */
    private fun agree(cond: String, rows: List<Pair<String?, String?>>, msg: String) {
        val kModel = RuntimeLaws.withRule(order, "/Order", "RU", "../Items/Count", "RU", cond)
        val def = ModelDef(fields, listOf(RuleDef("/Order/RU", "/Order", cond, "RU", "../Items/Count")),
            repeatableGroups = setOf("/Order/Items"), declaredReps = mapOf("/Order/Items" to 50), indexFields = setOf(SKU))
        val kDoc = RuntimeLaws.doc().group("/Order", 1)
        val iDoc = def.newDocument()
        rows.forEachIndexed { i, (c, w) ->
            val r = i + 1
            kDoc.group("/Order/Items", 1, r).field(SKU, "K$r", 1, r, 1)
            iDoc.group("/Order/Items", 1, r).field(SKU, "K$r", 1, r, 1)
            if (c != null) { kDoc.field(COUNT, c, 1, r, 1); iDoc.field(COUNT, c, 1, r, 1) }
            if (w != null) { kDoc.field(WEIGHT, w, 1, r, 1); iDoc.field(WEIGHT, w, 1, r, 1) }
        }
        val kDesc = RuntimeLaws.messages(kModel, kDoc).filter { it.code() == "RU" }
            .map { "${it.field()}|${it.type().name}" }.sorted()
        val iDesc = DmInterpreter(def).validateFull(iDoc).filter { it.code == "RU" }
            .map { "${it.errorPath}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}" }.sorted()
        assertEquals(kDesc, iDesc, "$msg\n  kernel=$kDesc\n  interp=$iDesc")
    }

    private val single = render(Dsl.repetitionNotUnique(m.field(COUNT)))
    private val composite = render(Dsl.repetitionNotUnique(m.field(COUNT), m.field(WEIGHT)))

    @Test fun duplicateCountFlagsBothRows() = agree(single, listOf("5" to null, "5" to null), "5,5 → both rows flagged (VALUE)")
    @Test fun distinctCountsNoFire() = agree(single, listOf("5" to null, "7" to null), "5,7 → none")
    @Test fun onlyTheDuplicatesFlagged() = agree(single, listOf("5" to null, "7" to null, "5" to null), "5,7,5 → rows 1 & 3, not 2")
    @Test fun singleRowNoFire() = agree(single, listOf("5" to null), "one row → none")
    @Test fun allEmptyKeysSkipped() = agree(single, listOf(null to null, null to null), "empty,empty → skipped, none")
    @Test fun oneEmptyKeyNotADuplicate() = agree(single, listOf("5" to null, null to null), "5,empty → the empty row is skipped, no duplicate")

    @Test fun compositeDistinctTuplesNoFire() = agree(composite, listOf("5" to "1", "5" to "2"), "(5,1)(5,2) → distinct tuples, none")
    @Test fun compositeDuplicateTupleFlagged() = agree(composite, listOf("5" to "1", "5" to "1"), "(5,1)(5,1) → both flagged (VALUE)")
    @Test fun compositeEmptyComponentIsOmission() = agree(composite, listOf("5" to null, "5" to null), "(5,empty)(5,empty) → both flagged OMISSION (empty component fillable)")

    private companion object {
        const val SKU = "/Order/Items/Sku"
        const val COUNT = "/Order/Items/Count"
        const val WEIGHT = "/Order/Items/UnitWeight"
    }
}
