package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — `GroupFilled` / `GroupNotFilled` on a NON-repeatable group. A group is "filled" when ≥1 of
 * its fields carries a value; `GroupNotFilled` is the inverse. The interpreter must fire (or not) exactly
 * when the kernel does. The error field is a member of the referenced group (the kernel requires the error
 * field to be referenced — `MVK_ERROR_FIELD_NOT_REFERENCED` — and a `GroupFilled` reference covers its
 * members indirectly, BA "indirect referencing"). The REPEATABLE-group form requires the `*` aggregate
 * (`GroupFilled(Items*)` — `MVK_NO_WILDCARD`), which is the `*`/aggregate family, deferred to that increment.
 * Drives the interpreter from PARSED condition text; the kernel side injects the same rule.
 */
class GroupFilledDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val fields = listOf(FieldDef(STREET, Kind.STRING), FieldDef(CITY, Kind.STRING), FieldDef(CN, Kind.STRING))

    private fun interpFires(cond: String, doc: Document): Boolean =
        DmInterpreter(ModelDef(fields, listOf(RuleDef("/Order/R", "/Order", cond, "GF", "../ShippingAddress/Street"))))
            .validate(doc).messages.any { it.code == "GF" }

    private fun kernelFires(cond: String, doc: RuntimeLaws.Doc): Boolean =
        RuntimeLaws.fires(RuntimeLaws.withRule(order, "/Order", "R", "../ShippingAddress/Street", "GF", cond), "GF", doc)

    @Test
    fun groupFilledFiresWhenAMemberIsFilled() {
        val cond = "GroupFilled(ShippingAddress)"
        assertEquals(
            kernelFires(cond, RuntimeLaws.doc().group("/Order", 1).group("/Order/ShippingAddress", 1, 1)
                .field(STREET, "Main St", 1, 1, 1)),
            interpFires(cond, Document(kinds).group("/Order/ShippingAddress", 1, 1).field(STREET, "Main St", 1, 1, 1)),
            "GroupFilled fires when a member field is filled",
        )
    }

    @Test
    fun groupFilledDoesNotFireWhenEmpty() {
        val cond = "GroupFilled(ShippingAddress)"
        // CustomerName filled (row evaluated) but ShippingAddress empty → GroupFilled false → no fire.
        assertEquals(
            kernelFires(cond, RuntimeLaws.doc().group("/Order", 1).field(CN, "Acme", 1, 1)
                .group("/Order/ShippingAddress", 1, 1)),
            interpFires(cond, Document(kinds).field(CN, "Acme", 1, 1).group("/Order/ShippingAddress", 1, 1)),
            "GroupFilled does not fire on an empty group",
        )
    }

    @Test
    fun groupNotFilledFiresWhenEmpty() {
        val cond = "GroupNotFilled(ShippingAddress)"
        assertEquals(
            kernelFires(cond, RuntimeLaws.doc().group("/Order", 1).field(CN, "Acme", 1, 1)
                .group("/Order/ShippingAddress", 1, 1)),
            interpFires(cond, Document(kinds).field(CN, "Acme", 1, 1).group("/Order/ShippingAddress", 1, 1)),
            "GroupNotFilled fires when no member field is filled",
        )
    }

    @Test
    fun groupNotFilledDoesNotFireWhenAMemberIsFilled() {
        val cond = "GroupNotFilled(ShippingAddress)"
        assertEquals(
            kernelFires(cond, RuntimeLaws.doc().group("/Order", 1).group("/Order/ShippingAddress", 1, 1)
                .field(STREET, "Main St", 1, 1, 1)),
            interpFires(cond, Document(kinds).group("/Order/ShippingAddress", 1, 1).field(STREET, "Main St", 1, 1, 1)),
            "GroupNotFilled does not fire when a member is filled",
        )
    }

    private val kinds = mapOf(STREET to Kind.STRING, CITY to Kind.STRING, CN to Kind.STRING)

    private companion object {
        const val STREET = "/Order/ShippingAddress/Street"
        const val CITY = "/Order/ShippingAddress/City"
        const val CN = "/Order/CustomerName"
    }
}
