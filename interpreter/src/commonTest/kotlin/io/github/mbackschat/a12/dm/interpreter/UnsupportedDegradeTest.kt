package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The per-rule degrade net (IF61): a rule whose condition uses a construct the evaluator does not
 * model in that position is reported [io.github.mbackschat.a12.dm.interpreter.model.UnsupportedRule] — it
 * must NEVER throw out of `validate` and sink the other rules' evaluation (an uncaught ClassCastException
 * did exactly that on real corpora; the sweep lost whole models' coverage). Loud, not wrong: the construct
 * is named, the remaining rules still evaluate.
 */
class UnsupportedDegradeTest {

    private val cn = "/Order/CustomerName"
    private val sku = "/Order/Items/Sku"
    private val count = "/Order/Items/Count"

    private val model = ModelDef(
        fields = listOf(FieldDef(cn, Kind.STRING), FieldDef(sku, Kind.STRING), FieldDef(count, Kind.NUMBER)),
        rules = listOf(
            // a semantic index on a group that declares no index field: A12 refuses it (`MVK_NO_INDEX_FIELD`,
            // KERNEL-FINDINGS KF476), so it reaches the evaluator only from an unchecked model, and the evaluator
            // cannot model it. The keyed uniqueness operand this row used until 2026-10-28 is modeled now (KF475).
            RuleDef("/Order/R1", "/Order", "FieldFilled(CustomerName) And [CustomerName For \"K1\"] == \"a\"", "R1", "../CustomerName"),
            // a healthy rule that must still evaluate (and fire) despite R1's degrade
            RuleDef("/Order/R2", "/Order", "FieldFilled(CustomerName)", "R2", "../CustomerName"),
        ),
        repeatableGroups = setOf("/Order/Items"),
        declaredReps = mapOf("/Order/Items" to 50),
        indexFields = setOf(sku),
    )

    @Test
    fun unmodeledConstructDegradesWithoutSinkingTheRun() {
        val doc = model.newDocument().group("/Order", 1).field(cn, "Acme", 1, 1)
        val outcome = DmInterpreter(model).validate(doc)   // must not throw
        assertEquals(listOf("R2"), outcome.messages.map { it.code }, "the healthy rule still evaluates")
        assertTrue(outcome.unsupported.any { it.rulePath == "/Order/R1" && "SemanticIndex" in it.reason },
            "R1 is reported unsupported naming the construct: ${outcome.unsupported}")
    }
}
