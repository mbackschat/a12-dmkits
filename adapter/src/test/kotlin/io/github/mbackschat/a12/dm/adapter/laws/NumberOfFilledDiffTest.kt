package io.github.mbackschat.a12.dm.adapter.laws

import com.mgmtp.a12.kernel.md.rt.api.IMessage
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
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
 * Differential — the count-of-filled FUNCTIONS `NumberOfFilledFields` / `NumberOfFilledGroups` (number-valued,
 * used inside a comparison). The interpreter's count and the comparison verdict must match the kernel across
 * filled-counts, and the fired comparison's polarity too. A sibling (`UnitPrice`) is filled so the row is
 * evaluated. Driven from parsed text.
 */
class NumberOfFilledDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val fields = listOf(
        FieldDef(CN, Kind.STRING), FieldDef(PC, Kind.STRING), FieldDef(Q, Kind.NUMBER, scale = 0),
        FieldDef(SIB, Kind.NUMBER, scale = 2), FieldDef(SA_STREET, Kind.STRING), FieldDef(BA_PC, Kind.STRING),
    )

    // Conditions are authored with the rulekit typed DSL ([m]) and rendered to the canonical string fed to
    // BOTH engines — no free-text condition literals (renderer emits absolute paths; both engines accept them).
    private val m = BoundModel.ofJson(order)
    private fun render(c: Condition): String = DslRenderer().render(c)

    private fun rule(cond: String, errField: String) =
        DmInterpreter(ModelDef(fields, listOf(RuleDef("/Order/R", "/Order", cond, "NF", errField))))

    private fun agree(cond: String, errField: String, i: Document, k: RuntimeLaws.Doc, msg: String) {
        val kf = RuntimeLaws.fires(RuntimeLaws.withRule(order, "/Order", "R", errField, "NF", cond), "NF", k)
        assertEquals(kf, rule(cond, errField).validate(i).messages.any { it.code == "NF" }, msg)
    }

    @Test
    fun numberOfFilledFieldsInComparison() {
        // NumberOfFilledFields(CustomerName, ProductCode, Quantity) < 2 → fires at count 0 and 1.
        val cond = render(Dsl.numberOfFilledFields(m.field(CN), m.field(PC), m.field(Q)).lt(2))
        fun iDoc(vararg f: String) = Document(kinds).field(SIB, "1.00", 1, 1).also { d -> f.forEach { d.field(it, "x", 1, 1) } }
        fun kDoc(vararg f: String) = RuntimeLaws.doc().group("/Order", 1).field(SIB, "1.00", 1, 1).also { d -> f.forEach { d.field(it, "x", 1, 1) } }
        agree(cond, "../CustomerName", iDoc(), kDoc(), "count 0 < 2 fires")
        agree(cond, "../CustomerName", iDoc(CN), kDoc(CN), "count 1 < 2 fires")
        agree(cond, "../CustomerName", iDoc(CN, PC), kDoc(CN, PC), "count 2 not < 2")
        agree(cond, "../CustomerName", iDoc(CN, PC, Q), kDoc(CN, PC, Q), "count 3 not < 2")
    }

    @Test
    fun numberOfFilledGroupsInComparison() {
        // NumberOfFilledGroups(ShippingAddress, BillingAddress) == 2 → fires only when both groups filled.
        val cond = render(Dsl.numberOfFilledGroups(m.group(SA), m.group(BA)).eq(2))
        fun iDoc(vararg g: String) = Document(kinds).field(SIB, "1.00", 1, 1)
            .also { d -> listOf(SA to SA_STREET, BA to BA_PC).forEach { (grp, mem) -> d.group(grp, 1, 1); if (grp in g) d.field(mem, "x", 1, 1, 1) } }
        fun kDoc(vararg g: String) = RuntimeLaws.doc().group("/Order", 1).field(SIB, "1.00", 1, 1)
            .also { d -> listOf(SA to SA_STREET, BA to BA_PC).forEach { (grp, mem) -> d.group(grp, 1, 1); if (grp in g) d.field(mem, "x", 1, 1, 1) } }
        agree(cond, "../ShippingAddress/Street", iDoc(), kDoc(), "0 groups != 2")
        agree(cond, "../ShippingAddress/Street", iDoc(SA), kDoc(SA), "1 group != 2")
        agree(cond, "../ShippingAddress/Street", iDoc(SA, BA), kDoc(SA, BA), "2 groups == 2 fires")
    }

    @Test
    fun firedComparisonPolarityMatchesKernel() {
        // The catalog form `< 2` firing at count 1 — its message polarity must match the kernel.
        val cond = render(Dsl.numberOfFilledFields(m.field(CN), m.field(PC), m.field(Q)).lt(2))
        val i = Document(kinds).field(SIB, "1.00", 1, 1).field(CN, "x", 1, 1)
        val k = RuntimeLaws.doc().group("/Order", 1).field(SIB, "1.00", 1, 1).field(CN, "x", 1, 1)
        val kType = RuntimeLaws.typeOf(RuntimeLaws.withRule(order, "/Order", "R", "../CustomerName", "NF", cond), "NF", k).orElseThrow()
        val iType = rule(cond, "../CustomerName").validate(i).messages.single { it.code == "NF" }.type
        assertEquals(kType == IMessage.MessageType.VALUE_ERROR, iType == MsgType.VALUE, "polarity")
    }

    private val kinds = mapOf(CN to Kind.STRING, PC to Kind.STRING, Q to Kind.NUMBER, SIB to Kind.NUMBER, SA_STREET to Kind.STRING, BA_PC to Kind.STRING)

    private companion object {
        const val CN = "/Order/CustomerName"
        const val PC = "/Order/ProductCode"
        const val Q = "/Order/Quantity"
        const val SIB = "/Order/UnitPrice"
        const val SA = "/Order/ShippingAddress"
        const val BA = "/Order/BillingAddress"
        const val SA_STREET = "/Order/ShippingAddress/Street"
        const val BA_PC = "/Order/BillingAddress/PostalCode"
    }
}
