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
 * Differential — the group-list quantifiers over two non-repeatable groups (`ShippingAddress`,
 * `BillingAddress`), by the count k of filled GROUPS (a group is filled when ≥1 member field is): each of
 * `AllGroupsFilled` (k=N), `NoGroupFilled` (k=0), `AtLeastOneGroupFilled` (k≥1), `NotAllGroupsFilled` (k<N),
 * `GroupsNotCollectivelyFilled` (0<k<N) must fire iff the kernel does, and carry the kernel's polarity. A
 * sibling field (`CustomerName`) is filled so the row is evaluated. Driven from parsed condition text.
 */
class GroupListQuantifierDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val fields = listOf(FieldDef(SA_STREET, Kind.STRING), FieldDef(BA_PC, Kind.STRING), FieldDef(CN, Kind.STRING))

    // Conditions are authored with the rulekit typed DSL ([m]) and rendered to the canonical string fed to BOTH
    // engines — no free-text condition literals. The groups are `m.group(SA)` / `m.group(BA)` (absolute paths).
    private val m = BoundModel.ofJson(order)
    private fun render(c: Condition): String = DslRenderer().render(c)

    private fun member(group: String) = if (group == SA) SA_STREET else BA_PC

    private fun iDoc(filled: Set<String>): Document {
        val d = Document(kinds).field(CN, "Acme", 1, 1)
        listOf(SA, BA).forEach { g -> d.group(g, 1, 1); if (g in filled) d.field(member(g), "x", 1, 1, 1) }
        return d
    }

    private fun kDoc(filled: Set<String>): RuntimeLaws.Doc {
        val d = RuntimeLaws.doc().group("/Order", 1).field(CN, "Acme", 1, 1)
        listOf(SA, BA).forEach { g -> d.group(g, 1, 1); if (g in filled) d.field(member(g), "x", 1, 1, 1) }
        return d
    }

    private fun agree(cond: String, filled: Set<String>, msg: String) {
        val k = RuntimeLaws.fires(RuntimeLaws.withRule(order, "/Order", "R", "../ShippingAddress/Street", "GL", cond), "GL", kDoc(filled))
        val i = DmInterpreter(ModelDef(fields, listOf(RuleDef("/Order/R", "/Order", cond, "GL", "../ShippingAddress/Street"))))
            .validate(iDoc(filled)).messages.any { it.code == "GL" }
        assertEquals(k, i, "$msg (filled=$filled)")
    }

    @Test fun allGroupsFilled() {
        val c = render(Dsl.allGroupsFilled(m.group(SA), m.group(BA)))
        agree(c, setOf(), "k=0"); agree(c, setOf(SA), "k=1"); agree(c, setOf(SA, BA), "k=2 fires")
    }

    @Test fun noGroupFilled() {
        val c = render(Dsl.noGroupFilled(m.group(SA), m.group(BA)))
        agree(c, setOf(), "k=0 fires"); agree(c, setOf(SA), "k=1"); agree(c, setOf(SA, BA), "k=2")
    }

    @Test fun atLeastOneGroupFilled() {
        val c = render(Dsl.atLeastOneGroupFilled(m.group(SA), m.group(BA)))
        agree(c, setOf(), "k=0"); agree(c, setOf(SA), "k=1 fires"); agree(c, setOf(SA, BA), "k=2 fires")
    }

    @Test fun notAllGroupsFilled() {
        val c = render(Dsl.notAllGroupsFilled(m.group(SA), m.group(BA)))
        agree(c, setOf(), "k=0 fires"); agree(c, setOf(SA), "k=1 fires"); agree(c, setOf(SA, BA), "k=2")
    }

    @Test fun groupsNotCollectivelyFilled() {
        val c = render(Dsl.groupsNotCollectivelyFilled(m.group(SA), m.group(BA)))
        agree(c, setOf(), "k=0 does NOT fire"); agree(c, setOf(SA), "k=1 fires"); agree(c, setOf(SA, BA), "k=2")
    }

    @Test fun messageTypeMatchesKernel() {
        check(render(Dsl.allGroupsFilled(m.group(SA), m.group(BA))), setOf(SA, BA))
        check(render(Dsl.noGroupFilled(m.group(SA), m.group(BA))), setOf())
        check(render(Dsl.atLeastOneGroupFilled(m.group(SA), m.group(BA))), setOf(SA))
        check(render(Dsl.notAllGroupsFilled(m.group(SA), m.group(BA))), setOf(SA))
        check(render(Dsl.groupsNotCollectivelyFilled(m.group(SA), m.group(BA))), setOf(SA))
    }

    private fun check(cond: String, filled: Set<String>) {
        val kType = RuntimeLaws.typeOf(RuntimeLaws.withRule(order, "/Order", "R", "../ShippingAddress/Street", "GL", cond), "GL", kDoc(filled)).orElseThrow()
        val iType = DmInterpreter(ModelDef(fields, listOf(RuleDef("/Order/R", "/Order", cond, "GL", "../ShippingAddress/Street"))))
            .validate(iDoc(filled)).messages.single { it.code == "GL" }.type
        assertEquals(kType == IMessage.MessageType.VALUE_ERROR, iType == MsgType.VALUE, "$cond polarity")
    }

    private val kinds = mapOf(SA_STREET to Kind.STRING, BA_PC to Kind.STRING, CN to Kind.STRING)

    private companion object {
        const val SA = "/Order/ShippingAddress"
        const val BA = "/Order/BillingAddress"
        const val SA_STREET = "/Order/ShippingAddress/Street"
        const val BA_PC = "/Order/BillingAddress/PostalCode"
        const val CN = "/Order/CustomerName"
    }
}
