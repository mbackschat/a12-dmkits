package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential + tri-check (groovy-dynamic == java-static == interpreter) — `FieldValueAsString(plainField)`
 * as a COMPUTATION operand, the PLAIN-field sibling of `SemanticIndexMatchedInvalidCellDiffTest`'s indexed
 * reads (IF108's class sweep):
 *
 *  - a formally-INVALID source read through the coercion poisons the computation (the concat cannot swallow
 *    it as `""` — the same rule as a direct invalid field operand, IF85/IF81), standalone and in-concat alike;
 *  - an EMPTY source is the NOT-GIVEN string: a concat maps it to `""` (IF81's empty rule), a STANDALONE
 *    copy computes nothing → CLEARED (the string-copy store law, KERNEL-FINDINGS §11 — `FieldValueAsString`
 *    does not coerce empty to a storable `""` the way `Length`/`FieldValueAsNumber` coerce to 0).
 *
 * The operand must be a NUMBER/AMOUNT field — the kernel rejects `FieldValueAsString(stringField)` at model
 * validation (`MVK_NO_NUMBER`, "Only numbers and amounts are allowed"); a string copies bare (`[S]`).
 *
 * Own domain (e-commerce invoice): `FeeText = FieldValueAsString(Fee) + " G"` (the concat context),
 * `QtyText = FieldValueAsString(Qty)` (the standalone copy).
 */
class FieldValueAsStringComputeDiffTest {

    private companion object {
        const val FEE = "/Invoice/Fee"
        const val QTY = "/Invoice/Qty"
        const val FEE_TEXT = "/Invoice/FeeText"
        const val QTY_TEXT = "/Invoice/QtyText"
    }

    private val base = DocumentModelDraft.named("invoicetexts").locale("en_US")
        .rootGroup("Invoice") { g ->
            g.numberField("Fee") { it.fractionDigits(2, 2) }
            g.numberField("Qty") { it.fractionDigits(0, 0) }
            g.stringField("FeeText") {}
            g.stringField("QtyText") {}
        }.build().toJson()

    private val kModel: String = run {
        val m = BoundModel.ofJson(base)
        val feeText = RuntimeLaws.render("/Invoice", Dsl.fieldValueAsString(m.field(FEE)).plus(" G").expression())
        val qtyText = RuntimeLaws.render("/Invoice", Dsl.fieldValueAsString(m.field(QTY)).expression())
        RuntimeLaws.withComputation(
            RuntimeLaws.withComputation(base, "/Invoice", "FeeTextComp", "../FeeText", feeText),
            "/Invoice", "QtyTextComp", "../QtyText", qtyText)
    }

    /** Returns the tri-checked outcomes for (FeeText, QtyText). */
    private fun triOutcomes(fee: String?, qty: String?): Pair<String, String> {
        val def = ModelLoader.load(kModel)
        fun build(k: RuntimeLaws.Doc, i: Document) {
            k.group("/Invoice", 1).field(FEE_TEXT, "SEED", 1, 1).field(QTY_TEXT, "SEED", 1, 1)
            i.group("/Invoice", 1).field(FEE_TEXT, "SEED", 1, 1).field(QTY_TEXT, "SEED", 1, 1)
            fee?.let { k.field(FEE, it, 1, 1); i.field(FEE, it, 1, 1) }
            qty?.let { k.field(QTY, it, 1, 1); i.field(QTY, it, 1, 1) }
        }

        fun kernelSig(c: RuntimeLaws.Computed?): String = when {
            c == null -> "NONE"
            c.cleared() -> "CLEARED"
            c.errored() -> "ERRORED"
            else -> "VALUE|${c.value().orElse("")}"
        }

        val kDoc = RuntimeLaws.doc(); val iDoc = def.newDocument()
        build(kDoc, iDoc)
        val kComputed = RuntimeLaws.compute(kModel, kDoc)
        fun groovyAt(path: String) = kernelSig(kComputed.firstOrNull { it.path() == path })

        val engine = RuntimeLaws.kernelJavaEngine(kModel)
        val kDoc2 = RuntimeLaws.doc(); val iDoc2 = def.newDocument()
        build(kDoc2, iDoc2)
        val javaOutcomes = engine.computeOutcomesSorted(engine.instance(kDoc2))
        fun javaAt(path: String) = javaOutcomes.firstOrNull { it.startsWith("$path|") }
            ?.substringAfter("$path|")
            ?.let { if (it == "ERR") "ERRORED" else if (it == "CLEARED") "CLEARED" else "VALUE|$it" } ?: "NONE"

        val iComputed = DmInterpreter(def).compute(iDoc)
        fun interpAt(path: String) = iComputed.firstOrNull { it.path == path }.let { c ->
            when (c?.outcome) {
                null -> "NONE"
                ComputeOutcome.CLEARED -> "CLEARED"
                ComputeOutcome.ERRORED -> "ERRORED"
                ComputeOutcome.VALUE -> "VALUE|${c.value}"
            }
        }

        for (target in listOf(FEE_TEXT, QTY_TEXT)) {
            assertEquals(groovyAt(target), javaAt(target), "fee=$fee qty=$qty $target — groovy vs java-static (tri-check)")
            assertEquals(groovyAt(target), interpAt(target), "fee=$fee qty=$qty $target — kernel vs interpreter")
        }
        return groovyAt(FEE_TEXT) to groovyAt(QTY_TEXT)
    }

    // ── controls: valid sources compute through both contexts ─────────────────────────────────────────────
    @Test fun filledSourcesCompute() =
        assertEquals("VALUE|3.50 G" to "VALUE|7", triOutcomes("3.50", "7"))

    // ── the class instance: a formally-invalid source read through the coercion poisons the concat ────────
    @Test fun anInvalidSourceInTheConcatClears() =
        assertEquals("CLEARED" to "VALUE|7", triOutcomes("1.234", "7"),
            "Fee=1.234 (3 frac > max 2) is formally invalid; the coercion read poisons — the concat must not swallow it as \"\"")

    // ── the invalid source in the STANDALONE copy clears too ──────────────────────────────────────────────
    @Test fun anInvalidSourceStandaloneClears() =
        assertEquals("VALUE|3.50 G" to "CLEARED", triOutcomes("3.50", "7.5"),
            "Qty=7.5 (1 frac > max 0) is formally invalid")

    // ── an EMPTY source in a concat is the IF81 empty rule: contributes "" ─────────────────────────────────
    @Test fun anEmptySourceInTheConcatContributesBlank() =
        assertEquals("VALUE| G" to "VALUE|7", triOutcomes(null, "7"))

    // ── an EMPTY source in a STANDALONE copy computes nothing (the string-copy store law) ──────────────────
    @Test fun anEmptySourceStandaloneClears() =
        assertEquals("VALUE|3.50 G" to "CLEARED", triOutcomes("3.50", null))
}
