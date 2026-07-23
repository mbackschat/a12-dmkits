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
 * Differential + tri-check (groovy-dynamic == java-static == interpreter) — the filtered computation VALUE
 * AGGREGATES (`Sum`/`MinValue`/`MaxValue`/`NumberOfDifferentValues`) ride the SAME one-kept-successor
 * lookahead as every filtered field-list consumer (SPEC-2026-07-23-11; kernel `RuntimeController
 * .combineFeldListe` → `EbenenIterator.InnerIterator`), while still accumulating EVERY clean kept target
 * (no first-value stop). A filter poison found while locating the next kept candidate precedes the pending
 * current target (S5); after one successor is kept, a poisoned current target aborts before any filter beyond
 * that successor (S6); false candidates remain part of the successor search (S7); an invalid direct earlier
 * operand hides a later filtered slot (S8); a clean filtered Sum folds every kept target (S9).
 *
 * NOTE on observability: a compute poison is CAUSE-BLIND in every observation channel — the kernel's
 * generated catch discards `CalculationFieldException.getInternalId()` and marks the target "fehlerhaft
 * berechnet" silently, and the interpreter's `PoisonedReadException` path is likewise dropped at the
 * `ComputationEngine` catch — so S5–S7 pin the shared poison OUTCOME across both read orders; the read-order
 * mechanism itself is outcome-observable only through the `FirstFilledValue` prefix consumer
 * ([FirstFilledValueKeptSuccessorDiffTest]).
 *
 * Own domain (e-commerce shipment): `Total` over `Parcels* / Weight` kept by `[Parcels/Units] > 0`; the
 * invalid trigger a leading-zero number (`07`) on a scale-0 field.
 */
class AggregateKeptSuccessorDiffTest {

    private companion object {
        const val TOTAL = "/Shipment/Total"
        const val BASE = "/Shipment/Base"
        const val PARCELS = "/Shipment/Parcels"
        const val WEIGHT = "/Shipment/Parcels/Weight"
        const val UNITS = "/Shipment/Parcels/Units"
    }

    private val base = DocumentModelDraft.named("shipmentagg").locale("en_US")
        .rootGroup("Shipment") { s ->
            s.numberField("Total") { it.fractionDigits(0, 0) }
            s.numberField("Base") { it.fractionDigits(0, 0) }
            s.group("Parcels") { p ->
                p.repeatable(9)
                    .numberField("Weight") { it.fractionDigits(0, 0) }
                    .numberField("Units") { it.fractionDigits(0, 0) }
            }
        }.build().toJson()

    /** [rows]: per row `(Weight, Units)`, null = empty cell. Tri-checks and returns the `Total` outcome. */
    private fun triOutcome(expr: String, rows: List<Pair<String?, String?>>, baseField: String? = null): String {
        val kModel = RuntimeLaws.withComputation(base, "/Shipment", "TotalComp", "../Total", expr)
        val def = ModelLoader.load(kModel)
        fun build(k: RuntimeLaws.Doc, i: Document) {
            k.group("/Shipment", 1).field(TOTAL, "999", 1, 1)
            i.group("/Shipment", 1).field(TOTAL, "999", 1, 1)
            baseField?.let { k.field(BASE, it, 1, 1); i.field(BASE, it, 1, 1) }
            rows.forEachIndexed { idx, (weight, units) ->
                val r = idx + 1
                k.group(PARCELS, 1, r); i.group(PARCELS, 1, r)
                weight?.let { k.field(WEIGHT, it, 1, r, 1); i.field(WEIGHT, it, 1, r, 1) }
                units?.let { k.field(UNITS, it, 1, r, 1); i.field(UNITS, it, 1, r, 1) }
            }
        }
        val kDoc = RuntimeLaws.doc(); val iDoc = def.newDocument()
        build(kDoc, iDoc)
        val groovy = RuntimeLaws.compute(kModel, kDoc).firstOrNull { it.path() == TOTAL }
            .let { c -> if (c == null) "NONE" else if (c.cleared()) "CLEARED" else if (c.errored()) "ERRORED" else "VALUE|${c.value().orElse("")}" }
        val engine = RuntimeLaws.kernelJavaEngine(kModel)
        val kDoc2 = RuntimeLaws.doc(); val iDoc2 = def.newDocument()
        build(kDoc2, iDoc2)
        val javaStatic = engine.computeOutcomesSorted(engine.instance(kDoc2)).firstOrNull { it.startsWith("$TOTAL|") }
            ?.substringAfter("$TOTAL|")?.let { if (it == "ERR") "ERRORED" else if (it == "CLEARED") "CLEARED" else "VALUE|$it" } ?: "NONE"
        val interp = DmInterpreter(def).compute(iDoc).firstOrNull { it.path == TOTAL }
            .let { c -> when (c?.outcome) {
                null -> "NONE"
                ComputeOutcome.CLEARED -> "CLEARED"
                ComputeOutcome.ERRORED -> "ERRORED"
                ComputeOutcome.VALUE -> "VALUE|${c.value}"
            } }
        assertEquals(groovy, javaStatic, "rows=$rows — groovy vs java-static (tri-check)")
        assertEquals(groovy, interp, "rows=$rows — kernel vs interpreter")
        return groovy
    }

    private val filteredSum = "Sum(Parcels*/Weight Having ([Parcels/Units] > 0))"

    // ── S5 (lead): a filter poison met while locating the next kept candidate precedes the pending target ──
    @Test fun aSuccessorFilterPoisonPrecedesTheKeptRowsInvalidTarget() =
        assertEquals("CLEARED", triOutcome(filteredSum, listOf("07" to "1", "3" to "07")),
            "row 1 is kept with an invalid target, row 2's filter is invalid — the filter cause is reached first; both read orders poison")

    // ── S6: after one successor is kept, a poisoned current target aborts before the third filter ──────────
    @Test fun aPoisonedCurrentTargetAbortsBeforeTheThirdFilter() =
        assertEquals("CLEARED", triOutcome(filteredSum, listOf("07" to "1", "3" to "1", "4" to "07")),
            "rows 1+2 are kept — the invalid row-1 target aborts; row 3's filter stays unread; both read orders poison")

    // ── S7: false candidates remain part of the successor search ──────────────────────────────────────────
    @Test fun theSuccessorSearchReadsTheThirdFilterThroughAFalseCandidate() =
        assertEquals("CLEARED", triOutcome(filteredSum, listOf("5" to "1", "3" to "0", "4" to "07")),
            "row 2 is false, so the successor search reaches row 3's invalid filter before target 1 is consumed")

    // ── S8: an invalid direct earlier operand hides a later filtered slot ─────────────────────────────────
    @Test fun anInvalidDirectEarlierOperandHidesTheFilteredSlot() =
        assertEquals("CLEARED", triOutcome(
            "Sum(Base, Parcels*/Weight Having ([Parcels/Units] > 0))",
            listOf("5" to "1"), baseField = "07",
        ), "the invalid direct Base poisons before the filtered slot is resolved")

    // ── S9: a clean filtered Sum accumulates EVERY kept target — no first-value stop ──────────────────────
    @Test fun aCleanFilteredSumAccumulatesEveryKeptTarget() =
        assertEquals("VALUE|6", triOutcome(filteredSum, listOf("2" to "1", "3" to "0", "4" to "1")))

    // ── the family rides the same traversal: MinValue poison + NumberOfDifferentValues clean fold ─────────
    @Test fun aMinValueSuccessorFilterPoisonRidesTheSameTraversal() =
        assertEquals("CLEARED", triOutcome(
            "MinValue(Parcels*/Weight Having ([Parcels/Units] > 0))",
            listOf("5" to "1", "3" to "07"),
        ))

    @Test fun aNumberOfDifferentValuesStillConsumesEveryKeptRow() =
        assertEquals("VALUE|2", triOutcome(
            "NumberOfDifferentValues(Parcels*/Weight Having ([Parcels/Units] > 0))",
            listOf("2" to "1", "2" to "1", "4" to "1"),
        ))
}
