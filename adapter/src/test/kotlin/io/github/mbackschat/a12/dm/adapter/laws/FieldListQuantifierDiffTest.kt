package io.github.mbackschat.a12.dm.adapter.laws

import com.mgmtp.a12.kernel.md.rt.api.IMessage
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
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
        val c = "NoFieldFilled(CustomerName, ProductCode)"
        agree(c, setOf(), "k=0 fires"); agree(c, setOf(CN), "k=1"); agree(c, setOf(CN, PC), "k=2")
    }

    @Test fun atLeastOneFieldFilled() {
        val c = "AtLeastOneFieldFilled(CustomerName, ProductCode)"
        agree(c, setOf(), "k=0"); agree(c, setOf(CN), "k=1 fires"); agree(c, setOf(CN, PC), "k=2 fires")
    }

    @Test fun moreThanOneFieldFilled() {
        val c = "MoreThanOneFieldFilled(CustomerName, ProductCode)"
        agree(c, setOf(), "k=0"); agree(c, setOf(CN), "k=1"); agree(c, setOf(CN, PC), "k=2 fires")
    }

    @Test fun notAllFieldsFilled() {
        val c = "NotAllFieldsFilled(CustomerName, ProductCode)"
        agree(c, setOf(), "k=0 fires"); agree(c, setOf(CN), "k=1 fires"); agree(c, setOf(CN, PC), "k=2")
    }

    @Test fun notExactlyOneFieldFilled() {
        val c = "NotExactlyOneFieldFilled(CustomerName, ProductCode)"
        agree(c, setOf(), "k=0 fires"); agree(c, setOf(CN), "k=1"); agree(c, setOf(CN, PC), "k=2 fires")
    }

    @Test fun fieldsNotCollectivelyFilled() {
        // The trap: excludes the all-empty case (needs ≥1 filled), unlike the two siblings above.
        val c = "FieldsNotCollectivelyFilled(CustomerName, ProductCode)"
        agree(c, setOf(), "k=0 does NOT fire"); agree(c, setOf(CN), "k=1 fires"); agree(c, setOf(CN, PC), "k=2")
    }

    @Test fun messageTypeMatchesKernel() {
        // For each quantifier, at a firing instance, the interpreter's polarity must equal the kernel's.
        check("NoFieldFilled(CustomerName, ProductCode)", setOf())
        check("AtLeastOneFieldFilled(CustomerName, ProductCode)", setOf(CN))
        check("MoreThanOneFieldFilled(CustomerName, ProductCode)", setOf(CN, PC))
        check("NotAllFieldsFilled(CustomerName, ProductCode)", setOf(CN))
        check("NotExactlyOneFieldFilled(CustomerName, ProductCode)", setOf())        // k=0
        check("NotExactlyOneFieldFilled(CustomerName, ProductCode)", setOf(CN, PC))  // k=2 — data-dependent?
        check("FieldsNotCollectivelyFilled(CustomerName, ProductCode)", setOf(CN))
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
