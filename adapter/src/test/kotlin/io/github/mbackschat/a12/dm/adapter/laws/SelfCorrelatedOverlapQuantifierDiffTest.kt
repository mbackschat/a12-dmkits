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
 * Differential + tri-check (groovy-dynamic == java-static == interpreter) — the per-row SELF-CORRELATED
 * period-overlap idiom (the IG56 E3 `row≥2` class minimal: a real workspace "another price period overlaps
 * mine" rule — `FieldFilled(Start) And AtLeastOneFieldFilled(star Having (CurrentRepetition(RuleGroup) !=
 * CurrentRepetition($RuleGroup) And <overlap disjunction over [Start]/[End] vs [$Start]>))` — where the kernel
 * fires on row 2 and the interpreter fires nothing).
 *
 * The corpus instance's discriminating detail: the FIRING row's own scanned cell (its `Price`) is formally
 * INVALID (1 fraction digit into a 2-frac field) — the arms below split that from the all-valid case to
 * isolate whether the invalid scanned cell suppresses the interpreter's filtered tally where the kernel
 * (whose filter DROPS that row via the self-exclusion before its cell is read) still fires.
 */
class SelfCorrelatedOverlapQuantifierDiffTest {

    private companion object {
        const val PRICE = "/Catalog/Offers/Price"
        const val FROM = "/Catalog/Offers/From"
        const val UNTIL = "/Catalog/Offers/Until"
        const val OFFERS = "/Catalog/Offers"
    }

    private val base = DocumentModelDraft.named("catalog").locale("en_US")
        .rootGroup("Catalog") { c ->
            c.group("Offers") { o ->
                o.repeatable(10)
                    .numberField("Price") { it.fractionDigits(2, 2) }
                    .dateField("From") { }.dateField("Until") { }
            }
        }.build().toJson()

    private val cond = "FieldFilled(From) And AtLeastOneFieldFilled(../Offers*/Price Having " +
        "(CurrentRepetition(RuleGroup) != CurrentRepetition(\$RuleGroup) And " +
        "(([From] <= [\$From] And [\$From] <= [Until]) Or ([From] <= [\$From] And FieldNotFilled(Until)) Or " +
        "(FieldNotFilled(From) And [Until] >= [\$From]) Or (FieldNotFilled(From) And FieldNotFilled(Until)))))"

    private val kModel = RuntimeLaws.withRule(base, OFFERS, "R", "../From", "OVERLAP", cond)

    private fun agree(expect: List<String>, msg: String, build: (RuntimeLaws.Doc, Document) -> Unit) {
        val def = ModelLoader.load(kModel)
        val kDoc = RuntimeLaws.doc(); val iDoc = def.newDocument()
        build(kDoc, iDoc)
        val groovy = RuntimeLaws.messages(kModel, kDoc).filter { it.code() == "OVERLAP" }
            .map { "${it.field()}|${it.type().name}" }.sorted()
        val engine = RuntimeLaws.kernelJavaEngine(kModel)
        val kDoc2 = RuntimeLaws.doc(); val iDoc2 = def.newDocument()
        build(kDoc2, iDoc2)
        val javaStatic = engine.messagesModern(kDoc2, null, java.util.Locale.US).filter { it.code() == "OVERLAP" }
            .map { "${it.field()}|${it.type().name}" }.sorted()
        val interp = DmInterpreter(def).validateFull(iDoc).filter { it.code == "OVERLAP" }
            .map { "${it.errorPath}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}" }.sorted()
        assertEquals(expect.sorted(), groovy, "$msg — the kernel groovy-dynamic fire set")
        assertEquals(groovy, javaStatic, "$msg — groovy vs java-static (tri-check)")
        assertEquals(groovy, interp, "$msg — kernel vs interpreter")
    }

    private fun Document.offer(r: Int, price: String, from: String, until: String?) = apply {
        group(OFFERS, 1, r).field(PRICE, price, 1, r, 1).field(FROM, from, 1, r, 1)
        until?.let { field(UNTIL, it, 1, r, 1) }
    }

    private fun RuntimeLaws.Doc.offer(r: Int, price: String, from: String, until: String?) = apply {
        group(OFFERS, 1, r).field(PRICE, price, 1, r, 1).field(FROM, from, 1, r, 1)
        until?.let { field(UNTIL, it, 1, r, 1) }
    }

    private fun at(r: Int, pol: String = "OMISSION_ERROR") = "/Catalog[1]/Offers[$r]/From|$pol"

    // ── the corpus instance: the firing row's own scanned cell is formally INVALID ─────────────────────────

    @Test fun firesOnTheRowWhoseOwnPriceIsInvalid() =
        agree(listOf(at(2)), "row 2's period starts inside row 1's; row 2's own Price is 1-frac INVALID — " +
            "the kernel's filter drops row 2 (self-exclusion) before its cell is read, keeps valid row 1 → fires") { k, i ->
            k.offer(1, "376.25", "2024-01-21", "2024-11-06").offer(2, "404.2", "2024-08-28", "2024-08-14")
            i.offer(1, "376.25", "2024-01-21", "2024-11-06").offer(2, "404.2", "2024-08-28", "2024-08-14")
        }

    // ── the all-valid control: same dates, valid prices ────────────────────────────────────────────────────

    @Test fun firesOnTheOverlappedRowAllValid() =
        agree(listOf(at(2)), "same periods, all prices valid — the overlap fires on row 2 only") { k, i ->
            k.offer(1, "376.25", "2024-01-21", "2024-11-06").offer(2, "404.20", "2024-08-28", "2024-08-14")
            i.offer(1, "376.25", "2024-01-21", "2024-11-06").offer(2, "404.20", "2024-08-28", "2024-08-14")
        }

    @Test fun silentWhenPeriodsAreDisjoint() =
        agree(emptyList(), "row 2 starts after row 1 ends — no overlap arm holds") { k, i ->
            k.offer(1, "376.25", "2024-01-21", "2024-02-06").offer(2, "404.20", "2024-08-28", null)
            i.offer(1, "376.25", "2024-01-21", "2024-02-06").offer(2, "404.20", "2024-08-28", null)
        }
}
