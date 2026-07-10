package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential + tri-check (groovy-dynamic == java-static == interpreter) — `FieldsNotCollectivelyFilled` over
 * a GROUP scope with a REPEATABLE descendant (the IG56 E3 interp-extra minimal: a real workspace
 * `FieldsNotCollectivelyFilled(OrderInformation)` over a repeatable group whose two instantiated rows are both
 * FILLED — the kernel is silent, the interpreter fired OMISSION off the un-instantiated DECLARED tail).
 *
 * The corpus rule's ERROR FIELD sits INSIDE the referenced group (`../OrderInformation/OrderNumber`) — that
 * is what makes the bare (no-star) repeatable-group reference legal: the rule iterates the group's rows and
 * the reference resolves to the CURRENT row (with an outside error field the codegen rejects
 * `MVK_NO_WILDCARD`). So the collectively-filled judgment is PER ROW — the interpreter's all-rows +
 * declared-range fillScope fired where every row is individually complete.
 */
class CollectivelyFilledGroupScopeDiffTest {

    private companion object {
        const val GATE = "/Order/Note"
        const val NUM = "/Order/OrderInformation/OrderNumber"
        const val QTY = "/Order/OrderInformation/Qty"
        const val INFO = "/Order/OrderInformation"
    }

    private val base = DocumentModelDraft.named("orders").locale("en_US")
        .rootGroup("Order") { o ->
            o.stringField("Note") { }
            o.group("OrderInformation") { g ->
                g.repeatable(10).numberField("OrderNumber") { it.fractionDigits(0, 0) }
                    .numberField("Qty") { it.fractionDigits(0, 0) }.indexField("OrderNumber")
            }
        }.build().toJson()

    private val kModel = RuntimeLaws.withRule(base, "/Order", "R", "../OrderInformation/OrderNumber", "COLL",
        "FieldsNotCollectivelyFilled(OrderInformation)")

    /** [rows]: one entry per instantiated row — (OrderNumber?, Qty?); null = that cell left empty. */
    private fun agree(rows: List<Pair<String?, String?>>, expect: List<String>, msg: String) {
        val def = ModelLoader.load(kModel)
        fun build(k: RuntimeLaws.Doc, i: Document) {
            k.group("/Order", 1).field(GATE, "n", 1, 1); i.group("/Order", 1).field(GATE, "n", 1, 1)
            rows.forEachIndexed { idx, (num, qty) ->
                val r = idx + 1
                k.group(INFO, 1, r); i.group(INFO, 1, r)
                num?.let { k.field(NUM, it, 1, r, 1); i.field(NUM, it, 1, r, 1) }
                qty?.let { k.field(QTY, it, 1, r, 1); i.field(QTY, it, 1, r, 1) }
            }
        }
        val kDoc = RuntimeLaws.doc(); val iDoc = def.newDocument()
        build(kDoc, iDoc)
        val groovy = RuntimeLaws.messages(kModel, kDoc).filter { it.code() == "COLL" }
            .map { "${it.field()}|${it.type().name}" }.sorted()
        val engine = RuntimeLaws.kernelJavaEngine(kModel)
        val kDoc2 = RuntimeLaws.doc(); val iDoc2 = def.newDocument()
        build(kDoc2, iDoc2)
        val javaStatic = engine.messagesModern(kDoc2, null, java.util.Locale.US).filter { it.code() == "COLL" }
            .map { "${it.field()}|${it.type().name}" }.sorted()
        val interp = DmInterpreter(def).validateFull(iDoc).filter { it.code == "COLL" }
            .map { "${it.errorPath}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}" }.sorted()
        assertEquals(expect.sorted(), groovy, "$msg — the kernel groovy-dynamic fire set")
        assertEquals(groovy, javaStatic, "$msg — groovy vs java-static (tri-check)")
        assertEquals(groovy, interp, "$msg — kernel vs interpreter")
    }

    private fun at(r: Int) = "/Order[1]/OrderInformation[$r]/OrderNumber|OMISSION_ERROR"

    @Test fun everyRowCompleteIsSilent() =
        agree(listOf("557" to "5", "82" to "7"), emptyList(),
            "the corpus instance's shape: every row individually complete — silent on every row")

    @Test fun aPartialRowFiresAtThatRowOnly() =
        agree(listOf("557" to "5", "82" to null), listOf(at(2)),
            "row 2 has its Qty empty — partially filled PER ROW; row 1 stays silent")

    @Test fun anAllEmptyRowIsNotPartial() =
        agree(listOf("557" to "5", null to null), emptyList(),
            "row 2 is instantiated but ALL-empty — none filled in that row, not partial")

    @Test fun noRowsIsSilent() =
        agree(emptyList(), emptyList(), "nothing instantiated — nothing partially filled")
}
