package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Differential + tri-check — a fill quantifier over a **FILTERED group star**, `…(G* Having …)`, on the
 * VALIDATION path.
 *
 * **This file records a REFUSAL, not a defect — read the measurement below before the reasoning above it.** It
 * began as a predicted defect: the group-expansion audit ([IF287]) fixed the compute path to expand a starred
 * group, carrying the `Having` onto each expanded field, while validation's group-aware scope arm is gated on
 * `having == null`, so a *filtered* group star never reaches it and falls through to the naive star tally, which
 * reads at the group path where no field is declared. That code reading is accurate and the two paths really do
 * disagree — but the shape they disagree about **cannot be authored**, so nothing observable follows from it.
 *
 * **Admission is measured first and separately**, because it governs whether there is a runtime question at all:
 * nothing in this repo established that the kernel admits a `Having` on a group-scope star, and no test anywhere
 * puts one there. If the kernel refuses the shape, the divergence is unreachable by authoring and the finding is
 * the refusal — which is why it is its own test with its own message rather than an inference from some other
 * assertion passing.
 *
 * **MEASURED 2026-08-14: the kernel REFUSES the shape** — `MVK_NO_GROUPS_ALLOWED`, "At 'Having': It is not
 * allowed to use a group" — and it refuses at CODE GENERATION, throwing rather than reporting a message, which
 * is why this file catches instead of reading a diagnostic list. So the divergence is **unreachable by
 * authoring** and there is no runtime defect here; the finding is the refusal itself.
 *
 * Two things follow, and they are the reason this file is kept rather than deleted. The `having` our shared
 * expansion carries onto each expanded field is **dead for the group case**, so no test can pin what duplicating
 * a filter across N expanded fields would mean — it is unreachable, not merely untested. And the audit that
 * predicted a live defect here was reading the code correctly: both paths really do disagree. It was the
 * ADMISSION question, which no amount of source reading answers, that made the difference between a defect and
 * a non-issue. This lock exists so the next reader does not re-derive the same wrong conclusion from the same
 * correct reading.
 */
class FilteredGroupStarFillDiffTest {

    private companion object {
        const val REF = "/Invoice/Ref"
        const val CHARGES = "/Invoice/Charges"
        const val KEEP = "/Invoice/Charges/Keep"
        const val PAYLOAD = "/Invoice/Charges/Payload"
        const val CODE = "FGS"
        const val FILTER = "Having [Charges/Keep] == \"Y\""
    }

    private val base = DocumentModelDraft.named("invoice").locale("en_US")
        .rootGroup("Invoice") { g ->
            g.stringField("Ref") { }
            g.group("Charges") { c ->
                c.repeatable(10).stringField("Keep") { }
                c.stringField("Payload") { }
            }
        }.build().toJson()

    private class Both(val kDoc: RuntimeLaws.Doc, val iDoc: Document) {
        fun group(path: String, vararg reps: Int): Both {
            kDoc.group(path, *reps); iDoc.group(path, *reps); return this
        }

        fun field(path: String, v: String, vararg reps: Int): Both {
            kDoc.field(path, v, *reps); iDoc.field(path, v, *reps); return this
        }
    }

    /**
     * Row 1 is DROPPED by the filter and is fully filled — so a reading that ignores the filter also gets caught.
     * Row 2 is kept and holds exactly one filled cell (`Keep`) beside one empty one (`Payload`).
     */
    private fun twoRows(b: Both) {
        b.group("/Invoice", 1).field(REF, "R-1", 1, 1)
            .group(CHARGES, 1, 1).field(KEEP, "N", 1, 1, 1).field(PAYLOAD, "p1", 1, 1, 1)
            .group(CHARGES, 1, 2).field(KEEP, "Y", 1, 2, 1)
    }

    private fun modelWith(condition: String) =
        RuntimeLaws.withRule(base, "/Invoice", "R", "../Ref", CODE, condition)

    /**
     * The refusal, locked on both quantifier polarities so it cannot be read as specific to one operator. The
     * kernel throws at code generation rather than returning a diagnostic, so the assertion is on the thrown
     * message — a shape that never reaches the runtime cannot be measured any other way.
     */
    @Test
    fun theKernelRefusesAHavingOverAGroupScopeStar() {
        for (op in listOf("AtLeastOneFieldFilled", "NoFieldFilled")) {
            val kModel = modelWith("FieldFilled($REF) And $op($CHARGES* $FILTER)")
            val def = ModelLoader.load(kModel)
            val doc = Both(RuntimeLaws.doc(), def.newDocument()).also(::twoRows)

            val refusal = assertThrows(Exception::class.java) { RuntimeLaws.messages(kModel, doc.kDoc) }
            assertTrue(refusal.message!!.contains("MVK_NO_GROUPS_ALLOWED")) {
                "$op — expected the group-in-Having refusal, got: ${refusal.message}"
            }
        }
    }

    /**
     * The control that keeps the refusal meaningful: the SAME filter over a starred FIELD list of that group is
     * admitted and evaluates. Without it, the refusal above could be read as the kernel disliking this fixture's
     * filter or its `Charges` group rather than the group-scope operand specifically.
     */
    @Test
    fun theSameFilterOverAStarredFieldListIsAdmittedAndEvaluates() {
        val kModel = modelWith("FieldFilled($REF) And AtLeastOneFieldFilled($CHARGES*/Payload $FILTER)")
        val def = ModelLoader.load(kModel)
        val dynamic = Both(RuntimeLaws.doc(), def.newDocument()).also(::twoRows)
        val messages = RuntimeLaws.messages(kModel, dynamic.kDoc)
        assertTrue(messages.none { it.code().startsWith("MVK_") }) {
            "the starred FIELD list must be admitted, else the refusal above is about the fixture: " +
                messages.map { it.code() }
        }
        val groovy = messages.any { it.code() == CODE }

        val staticDoc = Both(RuntimeLaws.doc(), def.newDocument()).also(::twoRows).kDoc
        val javaStatic = RuntimeLaws.kernelJavaEngine(kModel)
            .messagesModern(staticDoc, null, java.util.Locale.US).any { it.code() == CODE }
        val interpreter = DmInterpreter(def).validateFull(dynamic.iDoc).any { it.code == CODE }

        assertEquals(groovy, javaStatic, "starred field list — groovy vs java-static (tri-check)")
        assertEquals(groovy, interpreter, "starred field list — kernel vs INTERPRETER")
    }
}
