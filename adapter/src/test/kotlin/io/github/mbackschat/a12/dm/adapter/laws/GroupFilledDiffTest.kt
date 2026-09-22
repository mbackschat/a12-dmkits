package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — `GroupFilled` / `GroupNotFilled` on a NON-repeatable group with formally clean fields. A group
 * is "filled" when ≥1 field carries admitted content; `GroupNotFilled` is its inverse only in this clean matrix
 * (formal errors make negative presence UNKNOWN; `GroupPresenceFormalInvalidDiffTest`). The interpreter fires
 * when the kernel does. The error field is a member of the referenced group (the kernel requires the error
 * field to be referenced — `MVK_ERROR_FIELD_NOT_REFERENCED` — and a `GroupFilled` reference covers its
 * members indirectly, BA "indirect referencing"). The REPEATABLE-group form requires the `*` aggregate
 * (`GroupFilled(Items*)` — `MVK_NO_WILDCARD`), which is the `*`/aggregate family, deferred to that increment.
 * Drives the interpreter from PARSED condition text; the kernel side injects the same rule.
 */
class GroupFilledDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val fields = listOf(FieldDef(STREET, Kind.STRING), FieldDef(CITY, Kind.STRING), FieldDef(CN, Kind.STRING))

    // Conditions are authored with the rulekit typed DSL ([m]) and rendered to the canonical string fed to BOTH
    // engines — no free-text condition literals. `m.group(path).filled()` is GroupFilled (Kontext=Group).
    private val m = BoundModel.ofJson(order)
    private fun render(c: Condition): String = RuntimeLaws.render(c)
    private val SHIP = "/Order/ShippingAddress"

    private fun interpFires(cond: String, doc: Document): Boolean =
        DmInterpreter(ModelDef(fields, listOf(RuleDef("/Order/R", "/Order", cond, "GF", "../ShippingAddress/Street"))))
            .validate(doc).messages.any { it.code == "GF" }

    private fun kernelFires(cond: String, doc: RuntimeLaws.Doc): Boolean =
        RuntimeLaws.fires(RuntimeLaws.withRule(order, "/Order", "R", "../ShippingAddress/Street", "GF", cond), "GF", doc)

    @Test
    fun groupFilledFiresWhenAMemberIsFilled() {
        val cond = render(m.group(SHIP).filled())
        assertEquals(
            kernelFires(cond, RuntimeLaws.doc().group("/Order", 1).group("/Order/ShippingAddress", 1, 1)
                .field(STREET, "Main St", 1, 1, 1)),
            interpFires(cond, Document(kinds).group("/Order/ShippingAddress", 1, 1).field(STREET, "Main St", 1, 1, 1)),
            "GroupFilled fires when a member field is filled",
        )
    }

    @Test
    fun groupFilledDoesNotFireWhenEmpty() {
        val cond = render(m.group(SHIP).filled())
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
        val cond = render(m.group(SHIP).notFilled())
        assertEquals(
            kernelFires(cond, RuntimeLaws.doc().group("/Order", 1).field(CN, "Acme", 1, 1)
                .group("/Order/ShippingAddress", 1, 1)),
            interpFires(cond, Document(kinds).field(CN, "Acme", 1, 1).group("/Order/ShippingAddress", 1, 1)),
            "GroupNotFilled fires when no member field is filled",
        )
    }

    @Test
    fun groupNotFilledDoesNotFireWhenAMemberIsFilled() {
        val cond = render(m.group(SHIP).notFilled())
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
