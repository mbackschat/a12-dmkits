package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.Message
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential for the RICH message model: the interpreter's [Message] must carry the same channels as
 * the kernel's `IMessage` — the offending-field pointer ([Message.errorPath]), the operand set
 * ([Message.referenced] vs `getReferencedFieldsPointers`), and — for an OMISSION — the fill-to-fix set
 * ([Message.fillToFix] vs `getRefOmissionErrorResponsiblePointers`). Pointers are compared in the shared
 * clean form (`RuntimeLaws.Msg.field`/`referenced`/`omissionResponsible` render the kernel pointers the
 * same way the interpreter does), so this asserts content parity, not the kernel's internal `toString`.
 */
class RichMessageDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val fields = listOf(FieldDef(Q, Kind.NUMBER), FieldDef(BQ, Kind.NUMBER), FieldDef(CN, Kind.STRING))

    // Conditions are authored with the rulekit typed DSL ([m]) and rendered to the canonical string fed to BOTH
    // engines — no free-text condition literals. The message channels under test are unaffected by re-authoring.
    private val m = BoundModel.ofJson(order)
    private fun render(c: Condition): String = DslRenderer().render(c)

    private fun interp(cond: String): DmInterpreter =
        DmInterpreter(ModelDef(fields, listOf(RuleDef("/Order/R", "/Order", cond, "POL", "../Quantity"))))

    private fun kernelMsg(cond: String, doc: RuntimeLaws.Doc): RuntimeLaws.Msg =
        RuntimeLaws.messages(RuntimeLaws.withRule(order, "/Order", "R", "../Quantity", "POL", cond), doc)
            .single { it.code() == "POL" }

    @Test
    fun omissionMessageChannelsMatchKernel() {
        // Quantity 10 (filled, VALUE branch) And BackorderQuantity empty (→0, OMISSION branch) → OMISSION fires.
        val cond = render(m.num(Q).gt(5).and(m.num(BQ).ge(0)))
        val kernel = kernelMsg(cond, RuntimeLaws.doc().group("/Order", 1).field(Q, "10", 1, 1).emptyField(BQ, 1, 1))
        val msg = interp(cond).validate(Document(modelKinds()).field(Q, "10", 1, 1).emptyField(BQ, 1, 1)).messages
            .single { it.code == "POL" }

        assertEquals(kernel.field(), msg.errorPath, "offending-field pointer")
        assertEquals(kernel.referenced().toSet(), msg.referenced.toSet(), "referenced operands")
        assertEquals(kernel.omissionResponsible().toSet(), msg.fillToFix.toSet(), "fill-to-fix (refOmissionErrorResponsible)")
    }

    @Test
    fun orOmissionResponsibleSetMatchesKernel() {
        // CustomerName filled (row evaluated); Quantity + BackorderQuantity both empty (→0): the value
        // branch (0>5) is false, the omission branch (0>=0) is true → Or types OMISSION. Probes whether the
        // responsible set is "all operands" beyond the And case.
        val cond = render(m.num(Q).gt(5).or(m.num(BQ).ge(0)))
        val kernel = kernelMsg(cond, RuntimeLaws.doc().group("/Order", 1).field(CN, "Acme", 1, 1).emptyField(Q, 1, 1).emptyField(BQ, 1, 1))
        val msg = interp(cond).validate(
            Document(modelKinds()).field(CN, "Acme", 1, 1).emptyField(Q, 1, 1).emptyField(BQ, 1, 1),
        ).messages.single { it.code == "POL" }

        assertEquals(kernel.referenced().toSet(), msg.referenced.toSet(), "referenced operands")
        assertEquals(kernel.omissionResponsible().toSet(), msg.fillToFix.toSet(), "Or fill-to-fix set matches the kernel")
    }

    @Test
    fun valueMessageChannelsMatchKernel() {
        // Quantity 50 (filled) < 100 → fires VALUE; fill-to-fix is empty (no fill fixes a value error).
        val cond = render(m.num(Q).lt(100))
        val kernel = kernelMsg(cond, RuntimeLaws.doc().group("/Order", 1).field(Q, "50", 1, 1))
        val msg = interp(cond).validate(Document(modelKinds()).field(Q, "50", 1, 1)).messages.single { it.code == "POL" }

        assertEquals(kernel.field(), msg.errorPath, "offending-field pointer")
        assertEquals(kernel.referenced().toSet(), msg.referenced.toSet(), "referenced operands")
        assertEquals(emptyList<String>(), msg.fillToFix, "a VALUE error has no fill-to-fix")
        assertEquals(kernel.omissionResponsible().toSet(), msg.fillToFix.toSet(), "matches the kernel's (empty) responsible set")
    }

    private fun modelKinds() = mapOf(Q to Kind.NUMBER, BQ to Kind.NUMBER, CN to Kind.STRING)

    private companion object {
        const val Q = "/Order/Quantity"
        const val BQ = "/Order/BackorderQuantity"
        const val CN = "/Order/CustomerName"
    }
}
