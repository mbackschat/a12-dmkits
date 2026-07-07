package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM + JS) lock for the `RuleGroup` self-reference keyword (the kernel's `RegelKontext`, IF63):
 * `RuleGroup` resolves to the rule's OWN group (the parse base), not a child group `<base>/RuleGroup`. On a rule
 * iterating a repeatable group, `GroupFilled(RuleGroup)` is TRUE for every instantiated row (the row is present);
 * on a non-repeatable group it is the ordinary "has a filled descendant". The kernel-differential is
 * `adapter.laws.RuleGroupSelfReferenceDiffTest` (JVM only — it needs the kernel); this runs the SAME evaluator on
 * both targets so a JS parse/eval split surfaces.
 */
class RuleGroupSelfReferenceTest {

    private val cn = "/Order/CustomerName"
    private val sku = "/Order/Items/Sku"
    private val count = "/Order/Items/Count"
    private val street = "/Order/ShippingAddress/Street"

    private fun itemsModel(cond: String) = ModelDef(
        fields = listOf(FieldDef(sku, Kind.STRING), FieldDef(count, Kind.NUMBER)),
        rules = listOf(RuleDef("/Order/Items/R", "/Order/Items", cond, "R", "../Count")),
        repeatableGroups = setOf("/Order/Items"),
        declaredReps = mapOf("/Order/Items" to 50),
        indexFields = setOf(sku),
    )

    private fun shipModel(cond: String) = ModelDef(
        fields = listOf(FieldDef(cn, Kind.STRING), FieldDef(street, Kind.STRING)),
        rules = listOf(RuleDef("/Order/ShippingAddress/R", "/Order/ShippingAddress", cond, "R", "../Street")),
    )

    private fun fired(m: ModelDef, doc: Document) =
        DmInterpreter(m).validateFull(doc).filter { it.code == "R" }.map { it.errorPath }.sorted()

    @Test
    fun groupFilledRuleGroupIsTrueForEveryInstantiatedRow() {
        // Rows 1 (Sku filled), 2 (all-empty instantiated), 3 (Count filled) — GroupFilled(RuleGroup) is the row's
        // presence, so it fires on ALL three.
        val m = itemsModel("GroupFilled(RuleGroup)")
        val doc = m.newDocument().group("/Order", 1)
            .group("/Order/Items", 1, 1).field(sku, "K1", 1, 1, 1)
            .group("/Order/Items", 1, 2)
            .group("/Order/Items", 1, 3).field(count, "7", 1, 3, 1)
        assertEquals(listOf("/Order[1]/Items[1]/Count", "/Order[1]/Items[2]/Count", "/Order[1]/Items[3]/Count"),
            fired(m, doc), "GroupFilled(RuleGroup) fires on every instantiated row")
    }

    @Test
    fun groupFilledRuleGroupOnNonRepeatableTracksContent() {
        val m = shipModel("GroupFilled(RuleGroup)")
        val filled = m.newDocument().group("/Order/ShippingAddress", 1, 1).field(street, "Main St", 1, 1, 1)
        val empty = m.newDocument().field(cn, "Acme", 1, 1).group("/Order/ShippingAddress", 1, 1)
        assertEquals(listOf("/Order[1]/ShippingAddress[1]/Street"), fired(m, filled), "filled group → fires")
        assertEquals(emptyList(), fired(m, empty), "empty group → no fire")
    }

    @Test
    fun groupNotFilledRuleGroupOnNonRepeatableIsTheInverse() {
        val m = shipModel("GroupNotFilled(RuleGroup)")
        val filled = m.newDocument().group("/Order/ShippingAddress", 1, 1).field(street, "Main St", 1, 1, 1)
        val empty = m.newDocument().field(cn, "Acme", 1, 1).group("/Order/ShippingAddress", 1, 1)
        assertEquals(emptyList(), fired(m, filled), "filled group → GroupNotFilled does not fire")
        assertEquals(listOf("/Order[1]/ShippingAddress[1]/Street"), fired(m, empty), "empty group → fires")
    }
}
