package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tri-engine lock for the text a COMPUTATION's declared message renders. Every computation must declare one
 * (`MVK_ERROR_MESSAGE_MISSING`), and the kernel auto-generates one validation rule per computation that
 * carries it — so this text is on the ordinary `validateFull` output of any model with a computation, which
 * is most of them.
 *
 * <p><b>Why it is a separate lock from the rule-message estate.</b> The parameter terminal bundle belongs to
 * the OWNING DECLARATION rather than to a validation rule — a12-kernel-lean's `SPEC-2026-09-05-06`, whose
 * evidence is that the kernel's own refusal on this carrier calls the computation a rule. An implementation
 * that scopes message handling to stored rules passes every rule row in the estate and fails only here,
 * silently, because a computation's message never appears in a rule-shaped fixture.
 *
 * <p>The message carries a group parameter and a field parameter together: the group position is the one the
 * ownership claim is about, and the field position is the control that separates "this carrier renders no
 * text at all" from "this carrier renders text but not the group token".
 */
class ComputationMessageDiffTest {

    private companion object {
        const val INVOICE = "/Invoice"
        const val LINES = "/Invoice/Lines"
        const val QTY = "/Invoice/Lines/Qty"
        const val TOTAL = "/Invoice/Lines/Total"
        const val CODE = "TotalMatchesQuantity"
    }

    private val model: String = run {
        val base = DocumentModelDraft.named("invoice-lines").locale("en_US")
            .rootGroup("Invoice") { invoice ->
                invoice.group("Lines") { lines ->
                    lines.repeatable(10)
                        .numberField("Qty") { it.fractionDigits(0, 0) }
                        .numberField("Total") { it.fractionDigits(0, 0) }
                }
            }
            .build().toJson()
        EditSession.read(base).also { session ->
            val lines = session.group(LINES)
            lines.addComputation(lines.num("Total")) { computation ->
                computation.name(CODE)
                    .calc(lines.num("Qty").value())
                    .message(Dsl.en("row=[\$#RuleGroup\$] qty=[\$Qty.value\$]"))
                    .build()
            }
            session.validate()
        }.toJson()
    }

    private val definition = ModelLoader.load(model)

    /** Row 1 agrees with the computation and row 2 does not, so the implicit validation fires once, on row 2. */
    private inner class Build {
        val kDoc = RuntimeLaws.doc().group(INVOICE, 1)
        val iDoc = definition.newDocument().group(INVOICE, 1)

        fun row(row: Int, qty: String, total: String) {
            kDoc.group(LINES, 1, row)
            iDoc.group(LINES, 1, row)
            kDoc.field(QTY, qty, 1, row, 1)
            iDoc.field(QTY, qty, 1, row, 1)
            kDoc.field(TOTAL, total, 1, row, 1)
            iDoc.field(TOTAL, total, 1, row, 1)
        }
    }

    @Test
    fun aComputationsDeclaredMessageRendersWithItsGroupAndFieldParametersResolved() {
        val build = Build().apply {
            row(1, "2", "2")
            row(2, "3", "99")
        }
        val dynamic = RuntimeLaws.errorTextsOf(model, CODE, build.kDoc, Locale.US)
        val java = RuntimeLaws.kernelJavaEngine(model)
        val generated = java.errorTextsOf(java.instance(build.kDoc), CODE, Locale.US)
        val interpreted = DmInterpreter(definition).validateFull(build.iDoc, "en_US")
            .filter { it.code == CODE }.map { it.text }

        assertEquals(listOf("row=[2] qty=[3]"), dynamic, "normative Groovy-dynamic")
        assertEquals(dynamic, generated, "generated Java  kernel=$dynamic java=$generated")
        assertEquals(dynamic, interpreted, "interpreter  kernel=$dynamic interp=$interpreted")
    }
}
