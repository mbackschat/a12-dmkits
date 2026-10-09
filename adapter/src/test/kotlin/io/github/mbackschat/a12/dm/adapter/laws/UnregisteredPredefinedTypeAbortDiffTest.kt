package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Whether a REACHED predefined-type lookup that no validator answers is a firing LEAF or a failure of the whole
 * rule (a12-kernel-lean `SPEC-2026-10-09-04`; `UnregisteredPredefinedTypeObservableDiffTest` holds the
 * single-leaf matrix both accounts explain).
 *
 * The two accounts agree on a lone `Valid`/`Invalid` and part where the leaf is composed. A leaf that is true
 * fires nothing under a false trailing conjunct, selects rows inside a `Having` filter (so a count over two rows
 * stays below six), and fires once per row of an iterating rule. A failure caught around the rule fires the rule
 * in all three, once, with no references. The unreached lookup, behind a false leading conjunct, and a filter over
 * no rows are the controls on which both predict silence. No validator is registered on either strategy.
 *
 * Own domain (an e-commerce order).
 */
class UnregisteredPredefinedTypeAbortDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val m = BoundModel.ofJson(order)
    private val customer = m.field("/Order/CustomerName")
    private val sku = m.field("/Order/Items/Sku")

    /** One rule observed on both strategies, as `code/type/errorField refs=[…]` per message. */
    private fun observe(group: String, errorField: String, condition: Condition, rows: Int): String {
        val model = RuntimeLaws.withRule(order, group, "R", errorField, CODE, condition)
        val doc = RuntimeLaws.doc().group("/Order", 1).field("/Order/CustomerName", "Ann", 1, 1)
        for (row in 1..rows) {
            doc.group("/Order/Items", 1, row).field("/Order/Items/Sku", "S-$row", 1, row, 1)
        }
        val render = { messages: List<RuntimeLaws.Msg> ->
            messages.filter { it.code() == CODE }
                .joinToString(" | ") { "${it.type()}/${it.errorField()} refs=${it.referenced()}" }.ifEmpty { "<none>" }
        }
        val dynamic = render(RuntimeLaws.messages(model, doc))
        val engine = RuntimeLaws.kernelJavaEngine(model)
        val generated = render(engine.messages(engine.instance(doc)))
        assertEquals(dynamic, generated, "the two strategies must agree on $condition")
        return dynamic
    }

    @Test
    fun aReachedUnansweredLookupFailsTheWholeRule() {
        val missing = Dsl.validType(customer, TYPE)
        val inFilter = Dsl.numberOfFilledFields(m.flatten("/Order/Items*/Sku").having(Dsl.validType(sku, TYPE))).gt(5)
        val observed = linkedMapOf(
            "an ordinary firing, the references control" to observe("/Order", "../CustomerName", customer.filled(), 0),
            "a lone lookup" to observe("/Order", "../CustomerName", missing, rows = 0),
            "a false trailing conjunct" to observe("/Order", "../CustomerName", missing.and(customer.notFilled()), 0),
            "an unreached lookup" to observe("/Order", "../CustomerName", customer.notFilled().and(missing), 0),
            "inside a filter over two rows" to observe("/Order", "../CustomerName", customer.filled().and(inFilter), 2),
            "inside a filter over no rows" to observe("/Order", "../CustomerName", customer.filled().and(inFilter), 0),
            "an iterating rule over two rows" to observe("/Order/Items", "../Sku", Dsl.invalidType(sku, TYPE), 2),
        )
        val atCustomer = "VALUE_ERROR/PartiallyKnownDocumentMultiPointerImpl[/Order/CustomerName, [1, 1]]"
        assertEquals(linkedMapOf(
            "an ordinary firing, the references control" to "$atCustomer refs=[/Order[1]/CustomerName]",
            "a lone lookup" to "$atCustomer refs=[]",
            "a false trailing conjunct" to "$atCustomer refs=[]",
            "an unreached lookup" to "<none>",
            "inside a filter over two rows" to "$atCustomer refs=[]",
            "inside a filter over no rows" to "<none>",
            // One message at the first row, where a firing leaf would give one per row.
            "an iterating rule over two rows" to
                "VALUE_ERROR/PartiallyKnownDocumentMultiPointerImpl[/Order/Items/Sku, [1, 1, 1]] refs=[]",
        ), observed)
    }

    private companion object {
        const val CODE = "VT"
        const val TYPE = "Missing"
    }
}
