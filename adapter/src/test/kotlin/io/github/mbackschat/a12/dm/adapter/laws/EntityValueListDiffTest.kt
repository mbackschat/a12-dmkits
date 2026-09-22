package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.DocumentConfig
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — the ENTITY-valued value list `f In /Group* then x` (IG27): a field's membership in the flattened
 * FILLED cell values of a STARRED repetition list, rather than a literal value list. The value ref is an
 * `Expr.Star`, which `evalValueListQuant` used to `evalExpr` → the "starred path must sit inside an aggregate"
 * crash (root mechanism R1's value-list sibling). The value SET is the starred field's filled cells across all
 * rows (empties dropped). `ValueListQuantifierDiffTest` covers only the LITERAL form. Authored via the typed
 * DSL, rendered, run through BOTH engines.
 */
class EntityValueListDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val m = BoundModel.ofJson(order)
    private fun render(c: Condition) = RuntimeLaws.render(c)
    private fun gate(c: Condition) = m.field(CN).filled().and(c)

    private val skus = listOf(m.flatten("/Order/Items*/Sku"))
    private val atLeastOne = render(gate(Dsl.atLeastOneFieldValueIncludedInValueList(skus, m.field(PC))))
    private val noIn = render(gate(Dsl.noFieldValueIncludedInValueList(skus, m.field(PC))))
    private val notAll = render(gate(Dsl.notAllFieldValuesIncludedInValueList(skus, m.field(PC))))

    private val fields = listOf(FieldDef(CN, Kind.STRING), FieldDef(PC, Kind.STRING), FieldDef(SKU, Kind.STRING))
    private val kinds = fields.associate { it.path to it.kind }

    private fun kFires(cond: String, pc: String?, skuVals: List<String>): Boolean {
        val doc = RuntimeLaws.doc().group("/Order", 1).field(CN, "Acme", 1, 1)
        if (pc != null) doc.field(PC, pc, 1, 1)
        skuVals.forEachIndexed { i, s -> val r = i + 1; doc.group("/Order/Items", 1, r); doc.field(SKU, s, 1, r, 1) }
        return RuntimeLaws.messages(RuntimeLaws.withRule(order, "/Order", "R", "../CustomerName", "NF", cond), doc).any { it.code() == "NF" }
    }

    private fun iFires(cond: String, pc: String?, skuVals: List<String>): Boolean {
        val def = ModelDef(fields, listOf(RuleDef("/Order/R", "/Order", cond, "NF", "../CustomerName")),
            repeatableGroups = setOf("/Order/Items"), declaredReps = mapOf("/Order/Items" to 50))
        val doc = Document(DocumentConfig(kinds, def.declaredReps))
        doc.field(CN, "Acme", 1, 1)
        if (pc != null) doc.field(PC, pc, 1, 1)
        skuVals.forEachIndexed { i, s -> val r = i + 1; doc.group("/Order/Items", 1, r); doc.field(SKU, s, 1, r, 1) }
        return DmInterpreter(def).validateFull(doc).any { it.code == "NF" }
    }

    private fun agree(cond: String, pc: String?, skuVals: List<String>) =
        assertEquals(kFires(cond, pc, skuVals), iFires(cond, pc, skuVals), "cond=$cond pc=$pc skus=$skuVals")

    @Test fun atLeastOneMembership() {
        agree(atLeastOne, "A", listOf("A", "B"))   // A ∈ {A,B} → fires
        agree(atLeastOne, "C", listOf("A", "B"))   // C ∉ {A,B}
        agree(atLeastOne, "A", emptyList())         // empty value set → not in
        agree(atLeastOne, null, listOf("A"))        // empty field → skipped
    }

    @Test fun noMembership() {
        agree(noIn, "C", listOf("A", "B"))
        agree(noIn, "A", listOf("A", "B"))
        agree(noIn, null, listOf("A"))
    }

    @Test fun notAllMembership() {
        agree(notAll, "C", listOf("A", "B"))    // the one field is OUT → not all in → fires
        agree(notAll, "A", listOf("A", "B"))    // in → not(all) false
        agree(notAll, "A", emptyList())         // empty value set → out → fires
    }

    private companion object {
        const val CN = "/Order/CustomerName"
        const val PC = "/Order/ProductCode"
        const val SKU = "/Order/Items/Sku"
    }
}
