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
 * Differential + tri-check (groovy-dynamic == java-static == interpreter) — the fired POLARITY of a
 * `Having`-filtered starred fill quantifier (the IG56 E3 dominant `polarity-flip OMISSION→VALUE` class: the
 * corpus minimal's implicit computation-validation fired `OMISSION` in the kernel and `VALUE` in the
 * interpreter, the flip riding its `NoFieldFilled(star Having <active>)` precondition conjunct).
 *
 * The kernel ESCALATES a fired fill quantifier over a sole `Having`-filtered star to `TRUE_AF` (OMISSION)
 * UNCONDITIONALLY — differentially pinned in both directions: `NoFieldFilled(star Having …)` fired with
 * nothing kept is OMISSION, AND `AtLeastOneFieldFilled(star Having …)` fired over a fully-kept FILLED row is
 * OMISSION too (the fill-quantifier twin of the §8 value-list rule "a Having-filtered entry escalates a fired
 * result to TRUE_AF"). The interpreter typed fillability over the KEPT rows only (`(t=0, f=0)` → nothing to
 * fill → VALUE) — the flip.
 */
class HavingQuantifierPolarityDiffTest {

    private companion object {
        const val NOTE = "/Shop/Note"
        const val PRICE = "/Shop/Items/Price"
        const val CATEGORY = "/Shop/Items/Category"
    }

    private val base = DocumentModelDraft.named("hotitems").locale("en_US")
        .rootGroup("Shop") { g ->
            g.stringField("Note") { }
            g.group("Items") { i ->
                i.repeatable(10)
                    .numberField("Price") { it.fractionDigits(0, 2) }
                    .stringField("Category") { }
            }
        }.build().toJson()

    /** [rows]: (price?, category) per Items row. Returns the fired `pointer|polarity` set of [cond]. */
    private fun firedSet(cond: String, rows: List<Pair<String?, String>>): Triple<List<String>, List<String>, List<String>> {
        val kModel = RuntimeLaws.withRule(base, "/Shop", "R", "../Note", "HOTQ", cond)
        val def = ModelLoader.load(kModel)
        fun build(kDoc: RuntimeLaws.Doc, iDoc: Document) {
            kDoc.group("/Shop", 1).field(NOTE, "n", 1, 1); iDoc.group("/Shop", 1).field(NOTE, "n", 1, 1)
            rows.forEachIndexed { idx, (price, cat) ->
                val r = idx + 1
                kDoc.group("/Shop/Items", 1, r).field(CATEGORY, cat, 1, r, 1)
                iDoc.group("/Shop/Items", 1, r).field(CATEGORY, cat, 1, r, 1)
                price?.let { kDoc.field(PRICE, it, 1, r, 1); iDoc.field(PRICE, it, 1, r, 1) }
            }
        }
        val kDoc = RuntimeLaws.doc(); val iDoc = def.newDocument()
        build(kDoc, iDoc)
        val groovy = RuntimeLaws.messages(kModel, kDoc).filter { it.code() == "HOTQ" }
            .map { "${it.field()}|${it.type().name}" }.sorted()
        val engine = RuntimeLaws.kernelJavaEngine(kModel)
        val kDoc2 = RuntimeLaws.doc(); val iDoc2 = def.newDocument()
        build(kDoc2, iDoc2)
        val javaStatic = engine.messagesModern(kDoc2, null, java.util.Locale.US).filter { it.code() == "HOTQ" }
            .map { "${it.field()}|${it.type().name}" }.sorted()
        val interp = DmInterpreter(def).validateFull(iDoc).filter { it.code == "HOTQ" }
            .map { "${it.errorPath}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}" }.sorted()
        return Triple(groovy, javaStatic, interp)
    }

    private fun agree(cond: String, rows: List<Pair<String?, String>>, expect: List<String>, msg: String) {
        val (groovy, javaStatic, interp) = firedSet(cond, rows)
        assertEquals(expect.sorted(), groovy, "$msg — the kernel groovy-dynamic fire set")
        assertEquals(groovy, javaStatic, "$msg — groovy vs java-static (tri-check)")
        assertEquals(groovy, interp, "$msg — kernel vs interpreter")
    }

    private val noHotPriced = "FieldFilled(Note) And NoFieldFilled(/Shop/Items*/Price Having [/Shop/Items/Category] == \"hot\")"
    private val someHotPriced = "FieldFilled(Note) And AtLeastOneFieldFilled(/Shop/Items*/Price Having [/Shop/Items/Category] == \"hot\")"

    @Test fun firedNoFieldFilledWithHavingIsOmissionWhenTheFilterKeepsNothing() =
        agree(noHotPriced, rows = listOf("10.00" to "basic", "5.00" to "basic"),
            expect = listOf("/Shop[1]/Note|OMISSION_ERROR"),
            "no row passes the filter → fires; the open range means a hot row could be ADDED → OMISSION")

    @Test fun firedNoFieldFilledWithHavingIsOmissionAtZeroRows() =
        agree(noHotPriced, rows = emptyList(),
            expect = listOf("/Shop[1]/Note|OMISSION_ERROR"),
            "no rows at all → fires as OMISSION (add a passing row)")

    @Test fun firedNoFieldFilledWithHavingKeptEmptyCellIsStillFillable() =
        agree(noHotPriced, rows = listOf(null to "hot"),
            expect = listOf("/Shop[1]/Note|OMISSION_ERROR"),
            "a kept row with an EMPTY price → fires; filling that cell clears it → OMISSION")

    @Test fun silentWhenAKeptRowIsFilled() =
        agree(noHotPriced, rows = listOf("10.00" to "hot"), expect = emptyList(),
            "a filled kept cell → the quantifier is false → silent")

    @Test fun firedAtLeastOneWithHavingIsOmissionToo() =
        agree(someHotPriced, rows = listOf("10.00" to "hot"),
            expect = listOf("/Shop[1]/Note|OMISSION_ERROR"),
            "even a fired AtLeastOne over a fully-kept, FILLED row is TRUE_AF — the Having escalation is unconditional")
}
