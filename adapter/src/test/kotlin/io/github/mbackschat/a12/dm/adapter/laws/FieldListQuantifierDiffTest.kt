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
 * Differential — the field-list quantifiers over a two-field list (`CustomerName`, `ProductCode`), by the
 * count k of filled operands: `NoFieldFilled` (k=0), `AtLeastOneFieldFilled` (k≥1), `MoreThanOneFieldFilled`
 * (k≥2), `NotAllFieldsFilled` (k<N), `NotExactlyOneFieldFilled` (k≠1), `FieldsNotCollectivelyFilled` (0<k<N).
 * The three negatives disagree at the all-empty edge (KERNEL-FINDINGS §1) — that trap is exercised here. A
 * sibling field (`Quantity`) is always filled so the ROW is evaluated and it is the *list* fields' emptiness
 * that's measured. The message TYPE (value/omission) is also checked against the kernel. Driven from parsed text.
 */
class FieldListQuantifierDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val fields = listOf(FieldDef(CN, Kind.STRING), FieldDef(PC, Kind.STRING), FieldDef(SIB, Kind.NUMBER, scale = 0))

    // Conditions are authored with the rulekit typed DSL ([m]) and rendered to the canonical string fed to BOTH
    // engines — no free-text condition literals. The list fields are `m.field(CN)` / `m.field(PC)` (absolute paths).
    private val m = BoundModel.ofJson(order)
    private fun render(c: Condition): String = DslRenderer().render(c)

    /** Build the interpreter doc: the sibling filled (row evaluated) + the chosen list fields filled. */
    private fun iDoc(filled: Set<String>): Document {
        val d = Document(kinds).field(SIB, "1", 1, 1)
        filled.forEach { d.field(it, "x", 1, 1) }
        return d
    }

    private fun kDoc(filled: Set<String>): RuntimeLaws.Doc {
        val d = RuntimeLaws.doc().group("/Order", 1).field(SIB, "1", 1, 1)
        filled.forEach { d.field(it, "x", 1, 1) }
        return d
    }

    /** Assert the interpreter fires iff the kernel does, for the given filled set; return the kernel verdict. */
    private fun agree(cond: String, filled: Set<String>, msg: String): Boolean {
        val k = RuntimeLaws.fires(RuntimeLaws.withRule(order, "/Order", "R", "../CustomerName", "FL", cond), "FL", kDoc(filled))
        val i = DmInterpreter(ModelDef(fields, listOf(RuleDef("/Order/R", "/Order", cond, "FL", "../CustomerName"))))
            .validate(iDoc(filled)).messages.any { it.code == "FL" }
        assertEquals(k, i, "$msg (filled=$filled)")
        return k
    }

    @Test fun noFieldFilled() {
        val c = render(Dsl.noFieldFilled(m.field(CN), m.field(PC)))
        agree(c, setOf(), "k=0 fires"); agree(c, setOf(CN), "k=1"); agree(c, setOf(CN, PC), "k=2")
    }

    @Test fun atLeastOneFieldFilled() {
        val c = render(Dsl.atLeastOneFieldFilled(m.field(CN), m.field(PC)))
        agree(c, setOf(), "k=0"); agree(c, setOf(CN), "k=1 fires"); agree(c, setOf(CN, PC), "k=2 fires")
    }

    @Test fun moreThanOneFieldFilled() {
        val c = render(Dsl.moreThanOneFieldFilled(m.field(CN), m.field(PC)))
        agree(c, setOf(), "k=0"); agree(c, setOf(CN), "k=1"); agree(c, setOf(CN, PC), "k=2 fires")
    }

    @Test fun notAllFieldsFilled() {
        val c = render(Dsl.notAllFieldsFilled(m.field(CN), m.field(PC)))
        agree(c, setOf(), "k=0 fires"); agree(c, setOf(CN), "k=1 fires"); agree(c, setOf(CN, PC), "k=2")
    }

    @Test fun notExactlyOneFieldFilled() {
        val c = render(Dsl.notExactlyOneFieldFilled(m.field(CN), m.field(PC)))
        agree(c, setOf(), "k=0 fires"); agree(c, setOf(CN), "k=1"); agree(c, setOf(CN, PC), "k=2 fires")
    }

    @Test fun fieldsNotCollectivelyFilled() {
        // The trap: excludes the all-empty case (needs ≥1 filled), unlike the two siblings above.
        val c = render(Dsl.fieldsNotCollectivelyFilled(m.field(CN), m.field(PC)))
        agree(c, setOf(), "k=0 does NOT fire"); agree(c, setOf(CN), "k=1 fires"); agree(c, setOf(CN, PC), "k=2")
    }

    @Test fun messageTypeMatchesKernel() {
        // For each quantifier, at a firing instance, the interpreter's polarity must equal the kernel's.
        check(render(Dsl.noFieldFilled(m.field(CN), m.field(PC))), setOf())
        check(render(Dsl.atLeastOneFieldFilled(m.field(CN), m.field(PC))), setOf(CN))
        check(render(Dsl.moreThanOneFieldFilled(m.field(CN), m.field(PC))), setOf(CN, PC))
        check(render(Dsl.notAllFieldsFilled(m.field(CN), m.field(PC))), setOf(CN))
        check(render(Dsl.notExactlyOneFieldFilled(m.field(CN), m.field(PC))), setOf())        // k=0
        check(render(Dsl.notExactlyOneFieldFilled(m.field(CN), m.field(PC))), setOf(CN, PC))  // k=2 — data-dependent?
        check(render(Dsl.fieldsNotCollectivelyFilled(m.field(CN), m.field(PC))), setOf(CN))
    }

    private fun check(cond: String, filled: Set<String>) {
        val kType = RuntimeLaws.typeOf(RuntimeLaws.withRule(order, "/Order", "R", "../CustomerName", "FL", cond), "FL", kDoc(filled)).orElseThrow()
        val iType = DmInterpreter(ModelDef(fields, listOf(RuleDef("/Order/R", "/Order", cond, "FL", "../CustomerName"))))
            .validate(iDoc(filled)).messages.single { it.code == "FL" }.type
        assertEquals(kType == IMessage.MessageType.VALUE_ERROR, iType == MsgType.VALUE, "$cond polarity")
    }

    private val kinds = mapOf(CN to Kind.STRING, PC to Kind.STRING, SIB to Kind.NUMBER)

    private companion object {
        const val CN = "/Order/CustomerName"
        const val PC = "/Order/ProductCode"
        const val SIB = "/Order/Quantity"
    }
}
