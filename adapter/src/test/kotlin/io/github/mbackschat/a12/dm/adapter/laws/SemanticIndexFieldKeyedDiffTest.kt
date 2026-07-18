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
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential for the **field-keyed semantic index** `[Items/Count For CustomerName]` (the kernel's
 * `[<field> For <keyField>]` form, BA doc "the value of the semantic index can be specified as either a
 * constant or a Field") — the value of `Items/Count` in the repetition whose INDEX field `Sku` equals the
 * CURRENT value of the key field `CustomerName`. The literal form is `SemanticIndexDiffTest`; this locks the
 * field-keyed form (which the interpreter's parser previously rejected) against the kernel: matched value,
 * other key, no-match → empty (0), and an empty key field → no match. Firing AND polarity.
 */
class SemanticIndexFieldKeyedDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val fields = listOf(FieldDef(CN, Kind.STRING), FieldDef(SKU, Kind.STRING), FieldDef(COUNT, Kind.NUMBER))

    // Conditions authored via the rulekit DSL — the field-keyed semantic index `[Count For CustomerName]`
    // is `m.num(COUNT).forIndex(m.field(CN))` (DG8). Rendered to the canonical string fed to both engines.
    private val m = BoundModel.ofJson(order)
    private fun render(c: Condition): String = DslRenderer().render(c)
    private fun countForCustomer() = m.num(COUNT).forIndex(m.field(CN))

    /** [cond] over Items=[rows], with CustomerName=[key] (the field-keyed index value); kernel vs interp. */
    private fun agree(cond: String, key: String?, rows: List<Pair<String, String?>>, msg: String) {
        val kModel = RuntimeLaws.withRule(order, "/Order", "R", "../CustomerName", "SI", cond)
        val def = ModelDef(fields, listOf(RuleDef("/Order/R", "/Order", cond, "SI", "../CustomerName")),
            repeatableGroups = setOf("/Order/Items"), declaredReps = mapOf("/Order/Items" to 50), indexFields = setOf(SKU))
        val kDoc = RuntimeLaws.doc().group("/Order", 1)
        val iDoc = def.newDocument().group("/Order", 1)
        if (key != null) { kDoc.field(CN, key, 1, 1); iDoc.field(CN, key, 1, 1) }
        rows.forEachIndexed { i, (sku, count) ->
            val r = i + 1
            kDoc.group("/Order/Items", 1, r).field(SKU, sku, 1, r, 1)
            iDoc.group("/Order/Items", 1, r).field(SKU, sku, 1, r, 1)
            if (count != null) { kDoc.field(COUNT, count, 1, r, 1); iDoc.field(COUNT, count, 1, r, 1) }
        }
        val k = RuntimeLaws.typeOf(kModel, "SI", kDoc).map { it.name }.orElse(null)
        val i = DmInterpreter(def).validate(iDoc).messages.firstOrNull { it.code == "SI" }
            ?.type?.let { if (it == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR" }
        assertEquals(k, i, "$msg  (kernel=$k interp=$i)")
    }

    private val present = listOf("SKU-1" to "5", "SKU-2" to "3")

    @Test fun matchedValueGreater() =
        agree(render(m.field(CN).filled().and(countForCustomer().gt(0))), "SKU-1", present, "key SKU-1 → count 5 > 0 → VALUE")
    @Test fun matchedValueEquals() =
        agree(render(m.field(CN).filled().and(countForCustomer().eq(5))), "SKU-1", present, "key SKU-1 → count == 5")
    @Test fun matchedValueNotEquals() =
        agree(render(m.field(CN).filled().and(countForCustomer().eq(9))), "SKU-1", present, "key SKU-1 → count != 9")
    @Test fun otherKey() =
        agree(render(m.field(CN).filled().and(countForCustomer().eq(3))), "SKU-2", present, "key SKU-2 → count == 3")

    // No matching row OR an empty key field → the empty value (0), fillable → OMISSION on == 0, no fire on > 0.
    @Test fun noMatchEqualsZeroIsOmission() =
        agree(render(m.field(CN).filled().and(countForCustomer().eq(0))), "SKU-9", present, "key SKU-9 (no row) → 0 → OMISSION")
    @Test fun noMatchGreaterZeroNoFire() =
        agree(render(m.field(CN).filled().and(countForCustomer().gt(0))), "SKU-9", present, "key SKU-9 (no row) → 0 not > 0")

    private companion object {
        const val CN = "/Order/CustomerName"
        const val SKU = "/Order/Items/Sku"
        const val COUNT = "/Order/Items/Count"
    }
}
