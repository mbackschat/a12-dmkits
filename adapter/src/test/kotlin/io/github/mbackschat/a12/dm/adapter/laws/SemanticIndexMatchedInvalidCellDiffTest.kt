package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential + tri-check (groovy-dynamic == java-static == interpreter) — a semantic-index lookup whose
 * MATCHED cell is FORMALLY INVALID, read by a COMPUTATION (the IG56 E4 hostile minimal: a real workspace
 * concat `"…" + [G/Product For Key] + FieldValueAsString(G/Quantity For Key) + "…"` where the matched row's
 * Quantity violates its fraction-digit config — the kernel CLEARS the computation; the interpreter concatenated
 * the invalid cell as `""`). The sibling of `SemanticIndexComputeNoMatchDiffTest` (IF107 — the invalid index
 * COLUMN); here the column is CLEAN and the invalidity sits in the matched TARGET cell.
 *
 * The class this pins: a compute-mode indexed read of a matched formally-invalid cell is a poisoning read
 * (IF85's direct-field rule extends to `[F For key]`), through BOTH read sites — the direct operand
 * (`evalSemanticIndex`) and the `FieldValueAsString(ref For key)` coercion (which also inherits IF107's
 * column marking). An invalid target cell in an UNMATCHED row is NOT read and does not poison.
 */
class SemanticIndexMatchedInvalidCellDiffTest {

    private companion object {
        const val KEY = "/Shop/LookupKey"
        const val INFO = "/Shop/Info"
        const val RESULT = "/Shop/Result"
        const val PRICE_TEXT = "/Shop/PriceText"
        const val ITEM_NO = "/Shop/Items/ItemNo"
        const val PRICE = "/Shop/Items/Price"
        const val LABEL = "/Shop/Items/Label"
        const val ITEMS = "/Shop/Items"
    }

    private val base = DocumentModelDraft.named("pricelabels").locale("en_US")
        .rootGroup("Shop") { s ->
            s.numberField("LookupKey") { it.fractionDigits(0, 0) }
            s.stringField("Info") {}
            s.numberField("Result") { it.fractionDigits(2, 2) }
            s.stringField("PriceText") {}
            s.group("Items") { i ->
                i.repeatable(10).numberField("ItemNo") { it.fractionDigits(0, 0) }
                    .numberField("Price") { it.fractionDigits(2, 2) }
                    .stringField("Label") { it.maxLength(5) }
                    .indexField("ItemNo")
            }
        }.build().toJson()

    // FieldValueAsString over an indexed ref has no typed DSL form yet (DEV-API-GAPS) — the kernel grammar
    // shape under test is authored as the raw operation, like the IF107 sibling. PriceText isolates the
    // FieldValueAsString read (no direct indexed operand alongside), so its column/matched-invalid arms pin
    // THAT read site alone.
    private val kModel = RuntimeLaws.withComputation(
        RuntimeLaws.withComputation(
            RuntimeLaws.withComputation(base, "/Shop", "InfoComp", "../Info",
                "\"P \" + [Items/Label For LookupKey] + \" Q \" + FieldValueAsString(Items/Price For LookupKey) + \" R\""),
            "/Shop", "ResultComp", "../Result", "[Items/Price For LookupKey]"),
        "/Shop", "PriceTextComp", "../PriceText", "FieldValueAsString(Items/Price For LookupKey)")

    /** [rows]: (itemNo?, price?, label?) per row. Returns the tri-checked outcomes for (Info, Result, PriceText). */
    private fun triOutcomes(key: String, rows: List<Triple<String?, String?, String?>>): Triple<String, String, String> {
        val def = ModelLoader.load(kModel)
        fun build(k: RuntimeLaws.Doc, i: Document) {
            k.group("/Shop", 1).field(KEY, key, 1, 1).field(INFO, "SEED", 1, 1).field(RESULT, "999.99", 1, 1).field(PRICE_TEXT, "SEEDTEXT", 1, 1)
            i.group("/Shop", 1).field(KEY, key, 1, 1).field(INFO, "SEED", 1, 1).field(RESULT, "999.99", 1, 1).field(PRICE_TEXT, "SEEDTEXT", 1, 1)
            rows.forEachIndexed { idx, (no, price, label) ->
                val r = idx + 1
                k.group(ITEMS, 1, r); i.group(ITEMS, 1, r)
                no?.let { k.field(ITEM_NO, it, 1, r, 1); i.field(ITEM_NO, it, 1, r, 1) }
                price?.let { k.field(PRICE, it, 1, r, 1); i.field(PRICE, it, 1, r, 1) }
                label?.let { k.field(LABEL, it, 1, r, 1); i.field(LABEL, it, 1, r, 1) }
            }
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

        for (target in listOf(INFO, RESULT, PRICE_TEXT)) {
            assertEquals(groovyAt(target), javaAt(target), "key=$key rows=$rows $target — groovy vs java-static (tri-check)")
            assertEquals(groovyAt(target), interpAt(target), "key=$key rows=$rows $target — kernel vs interpreter")
        }
        return Triple(groovyAt(INFO), groovyAt(RESULT), groovyAt(PRICE_TEXT))
    }

    // ── control: matched row, every cell valid → all three computations compute ───────────────────────────
    @Test fun matchedValidComputes() =
        assertEquals(Triple("VALUE|P AB Q 5.25 R", "VALUE|5.25", "VALUE|5.25"),
            triOutcomes("12", listOf(Triple("12", "5.25", "AB"))))

    // ── the corpus instance: FieldValueAsString over a matched INVALID number cell → the kernel CLEARS ────
    @Test fun fieldValueAsStringOverAMatchedInvalidCellClears() =
        assertEquals(Triple("CLEARED", "CLEARED", "CLEARED"),
            triOutcomes("12", listOf(Triple("12", "1.234", "AB"))),
            "Price=1.234 (3 frac > max 2) is formally invalid; the matched read poisons every reading computation")

    // ── second instance: the DIRECT indexed operand `[Items/Label For LookupKey]` matched-invalid ─────────
    @Test fun aDirectIndexedStringOperandMatchedInvalidClears() =
        assertEquals(Triple("CLEARED", "VALUE|5.25", "VALUE|5.25"),
            triOutcomes("12", listOf(Triple("12", "5.25", "TOOLONG"))),
            "Label len 7 > max 5: the concat clears; the reads of the CLEAN Price cell are untouched (per-cell, not per-row)")

    // ── an invalid target cell in an UNMATCHED row is never read → no poison ──────────────────────────────
    @Test fun anInvalidTargetCellInAnUnmatchedRowDoesNotPoison() =
        assertEquals(Triple("VALUE|P AB Q 5.25 R", "VALUE|5.25", "VALUE|5.25"),
            triOutcomes("12", listOf(Triple("12", "5.25", "AB"), Triple("7", "1.234", "XYZ"))))

    // ── second instance: IF107's column marking reaches the ISOLATED FieldValueAsString read site too ─────
    @Test fun anInvalidIndexColumnPoisonsTheFieldValueAsStringReadToo() =
        assertEquals(Triple("CLEARED", "CLEARED", "CLEARED"),
            triOutcomes("12", listOf(Triple("12", "5.25", "AB"), Triple("9999999999999999", "0.01", "CD"))),
            "a 16-digit index cell elsewhere: the column marking poisons every indexed read, incl. the standalone FieldValueAsString")

    // ── matched row with EMPTY cells: the reads concat as ""; the STANDALONE FieldValueAsString copy of the
    //    not-given read CLEARS (the string-copy store law) while the numeric direct read fills 0 ─────────────
    @Test fun matchedEmptyCellsConcatAsBlank() =
        assertEquals(Triple("VALUE|P  Q  R", "VALUE|0.00", "CLEARED"),
            triOutcomes("12", listOf(Triple("12", null, null))))

    // ── no matching row over a clean column: same shape as matched-empty (IF107's clean-column rule) ──────
    @Test fun noMatchOverACleanColumnConcatsAsBlank() =
        assertEquals(Triple("VALUE|P  Q  R", "VALUE|0.00", "CLEARED"),
            triOutcomes("274", listOf(Triple("12", "5.25", "AB"))))
}
