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
 * Differential + tri-check (groovy-dynamic == java-static == interpreter) — a SEMANTIC-INDEX lookup
 * (`[Items/Price For LookupKey]`) as a COMPUTATION operation (the IG56 E4 hostile CVV minimal: a real
 * workspace `[OrderInformation/Price For SemIndex]` whose index COLUMN carries a hostile 16-digit cell — the
 * kernel CLEARS every reading computation, match or no match: the IndexFieldCache's invalid-index marking
 * (the semantic-index analog of IF96's parallel-iteration rule; any malformed, DUPLICATED, or EMPTY key cell
 * triggers it). A CLEAN column behaves like the validation read — a no-match and a matched-but-empty cell
 * both read empty-as-0 (the interpreter's original behavior, kernel-confirmed).
 */
class SemanticIndexComputeNoMatchDiffTest {

    private companion object {
        const val KEY = "/Shop/LookupKey"
        const val RESULT = "/Shop/Result"
        const val ITEM_NO = "/Shop/Items/ItemNo"
        const val PRICE = "/Shop/Items/Price"
        const val ITEMS = "/Shop/Items"
    }

    private val base = DocumentModelDraft.named("pricelookup").locale("en_US")
        .rootGroup("Shop") { s ->
            s.numberField("LookupKey") { it.fractionDigits(0, 0) }
            s.numberField("Result") { it.fractionDigits(2, 2) }
            s.group("Items") { i ->
                i.repeatable(10).numberField("ItemNo") { it.fractionDigits(0, 0) }
                    .numberField("Price") { it.fractionDigits(2, 2) }.indexField("ItemNo")
            }
        }.build().toJson()

    private val kModel = RuntimeLaws.withComputation(base, "/Shop", "LookupComp", "../Result",
        "[Items/Price For LookupKey]")

    /** [rows]: (itemNo?, price?) per row. Returns the tri-checked Result outcome. */
    private fun triOutcome(key: String, rows: List<Pair<String?, String?>>): String {
        val def = ModelLoader.load(kModel)
        fun build(k: RuntimeLaws.Doc, i: Document) {
            k.group("/Shop", 1).field(KEY, key, 1, 1).field(RESULT, "999.99", 1, 1)
            i.group("/Shop", 1).field(KEY, key, 1, 1).field(RESULT, "999.99", 1, 1)
            rows.forEachIndexed { idx, (no, price) ->
                val r = idx + 1
                k.group(ITEMS, 1, r); i.group(ITEMS, 1, r)
                no?.let { k.field(ITEM_NO, it, 1, r, 1); i.field(ITEM_NO, it, 1, r, 1) }
                price?.let { k.field(PRICE, it, 1, r, 1); i.field(PRICE, it, 1, r, 1) }
            }
        }
        val kDoc = RuntimeLaws.doc(); val iDoc = def.newDocument()
        build(kDoc, iDoc)
        val groovy = RuntimeLaws.compute(kModel, kDoc).firstOrNull { it.pointer() == "/Shop[1]/Result" }
            .let { c -> if (c == null) "NONE" else if (c.cleared()) "CLEARED" else if (c.errored()) "ERRORED" else "VALUE|${c.value().orElse("")}" }
        val engine = RuntimeLaws.kernelJavaEngine(kModel)
        val kDoc2 = RuntimeLaws.doc(); val iDoc2 = def.newDocument()
        build(kDoc2, iDoc2)
        val javaStatic = engine.computeOutcomesSorted(engine.instance(kDoc2)).firstOrNull { it.startsWith("$RESULT|") }
            ?.substringAfter("$RESULT|")?.let { if (it == "ERR") "ERRORED" else if (it == "CLEARED") "CLEARED" else "VALUE|$it" } ?: "NONE"
        val interp = DmInterpreter(def).compute(iDoc).firstOrNull { it.path == RESULT }
            .let { c -> when (c?.outcome) {
                null -> "NONE"
                ComputeOutcome.CLEARED -> "CLEARED"
                ComputeOutcome.ERRORED -> "ERRORED"
                ComputeOutcome.VALUE -> "VALUE|${c.value}"
            } }
        assertEquals(groovy, javaStatic, "key=$key rows=$rows — groovy vs java-static (tri-check)")
        assertEquals(groovy, interp, "key=$key rows=$rows — kernel vs interpreter")
        return groovy
    }

    @Test fun noMatchOverACleanColumnIsEmptyAsZero() =
        assertEquals("VALUE|0.00", triOutcome("274", listOf("534" to "0.01", "12" to "0.02")),
            "a clean index column with no matching row reads empty-as-0 — same as the validation read")

    @Test fun matchingRowComputesItsPrice() =
        assertEquals("VALUE|5.25", triOutcome("12", listOf("534" to "0.01", "12" to "5.25")))

    @Test fun noMatchWithAnInvalidIndexCellClears() =
        assertEquals("CLEARED", triOutcome("274", listOf("534" to "0.01", "9999999999999999" to "0.01")),
            "the corpus instance: no match; a 16-digit index cell present")

    @Test fun matchWithAnotherRowsIndexMalformedClears() =
        assertEquals("CLEARED", triOutcome("12", listOf<Pair<String?, String?>>("12" to "5.25", "9999999999999999" to "0.01")),
            "the column marking poisons the lookup even with a clean match")

    @Test fun matchWithADuplicateKeyElsewhereClears() =
        assertEquals("CLEARED", triOutcome("12", listOf<Pair<String?, String?>>("12" to "5.25", "7" to "0.01", "7" to "0.02")))

    @Test fun matchWithAnEmptyKeyCellElsewhereClears() =
        assertEquals("CLEARED", triOutcome("12", listOf<Pair<String?, String?>>("12" to "5.25", null to "0.01")))

    @Test fun matchedRowWithAnEmptyTargetCellIsEmptyAsZero() =
        assertEquals("VALUE|0.00", triOutcome("12", listOf<Pair<String?, String?>>("12" to null, "7" to "0.01")))
}
