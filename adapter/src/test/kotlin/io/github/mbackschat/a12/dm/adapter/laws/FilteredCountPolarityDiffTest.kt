package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — the POLARITY of a fired comparison against a `Having`-FILTERED COUNT
 * (`NumberOfFilledFields(Items* / Qty Having …)` — the IG56 E3 nice-mode V→O flip class: kernel VALUE,
 * interp over-escalated to OMISSION by the IF104 blanket).
 *
 * The kernel's entity count (`RuntimeController.anzahlAngegebenerEntities`) does NOT ride the value
 * combiners' unconditional filter escalation: its `(kannGroesser=true, kannKleiner=true)` filter arm fires
 * only when a filtered operand actually COUNTED a value (`containsFilteredValue`); a filter that keeps
 * NOTHING returns the ordinary count flags — grow-only while more cells exist than were counted
 * (`anzAllerFelder > anzAngFelder`), never shrink. So a fired mismatch over a kept-nothing count is typed
 * DIRECTIONALLY: stored BELOW the count → VALUE (the count cannot shrink), stored ABOVE → OMISSION (the
 * count could still grow to it) — where the IF104 blanket said OMISSION for every direction.
 */
class FilteredCountPolarityDiffTest {

    private companion object {
        const val COUNT = "/Order/Count"
        const val QTY = "/Order/Items/Qty"
        const val FLAG = "/Order/Items/Flag"
        const val ITEMS = "/Order/Items"
    }

    private val kModel = RuntimeLaws.withRule(
        DocumentModelDraft.named("flagcount").locale("en_US")
            .rootGroup("Order") { o ->
                o.numberField("Count") { it.fractionDigits(0, 0) }
                o.group("Items") { i ->
                    i.repeatable(10).numberField("Qty") { it.fractionDigits(0, 0) }.stringField("Flag") {}
                }
            }.build().toJson(),
        "/Order", "CountRule", "../Count", "CNT",
        "[Count] != NumberOfFilledFields(Items*/Qty Having [Items/Flag] == \"Y\")")

    /** [rows]: (qty?, flag?) per row. Returns the agreed kernel signature list. */
    private fun sigs(count: String, rows: List<Pair<String?, String?>>): List<String> {
        val def = ModelLoader.load(kModel)
        val kDoc = RuntimeLaws.doc().group("/Order", 1).field(COUNT, count, 1, 1)
        val iDoc = def.newDocument().group("/Order", 1).field(COUNT, count, 1, 1)
        rows.forEachIndexed { idx, (qty, flag) ->
            val r = idx + 1
            kDoc.group(ITEMS, 1, r); iDoc.group(ITEMS, 1, r)
            qty?.let { kDoc.field(QTY, it, 1, r, 1); iDoc.field(QTY, it, 1, r, 1) }
            flag?.let { kDoc.field(FLAG, it, 1, r, 1); iDoc.field(FLAG, it, 1, r, 1) }
        }
        val k = RuntimeLaws.messages(kModel, kDoc).map { "${it.code()}|${it.type().name}" }.sorted()
        val i = DmInterpreter(def).validateFull(iDoc)
            .map { "${it.code}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}" }.sorted()
        assertEquals(k, i, "count=$count rows=$rows — kernel vs interpreter")
        return k
    }

    // ── the corpus instance: the filter keeps NOTHING, the stored value is BELOW the count → VALUE ─────────
    @Test fun keptNothingStoredBelowIsValue() =
        assertEquals(listOf("CNT|VALUE_ERROR"), sigs("-5", listOf("3" to "N", "4" to "N")),
            "count 0, stored -5: the count cannot shrink to -5 — only changing the value helps")

    // ── kept nothing, stored ABOVE the count: the count could still grow to it → OMISSION ─────────────────
    @Test fun keptNothingStoredAboveIsOmission() =
        assertEquals(listOf("CNT|OMISSION_ERROR"), sigs("5", listOf("3" to "N", "4" to "N")))

    // ── the filter COUNTED values: the kernel's containsFilteredValue arm → both directions OMISSION ──────
    @Test fun keptSomeMismatchIsOmission() =
        assertEquals(listOf("CNT|OMISSION_ERROR"), sigs("1", listOf("3" to "Y", "4" to "Y")))

    @Test fun keptSomeStoredAboveIsOmission() =
        assertEquals(listOf("CNT|OMISSION_ERROR"), sigs("5", listOf("3" to "Y", "4" to "Y")))

    // ── no rows at all: the un-instantiated declared tail keeps the count growable → OMISSION ─────────────
    @Test fun noRowsStoredAbove() =
        assertEquals(listOf("CNT|OMISSION_ERROR"), sigs("5", emptyList()))

    // ── EXHAUSTED repetition (all 10 declared rows filled), filter keeps nothing, stored above: the kernel's
    //    grow test is `anzAllerFelder > anzAngFelder` — the FILTERED-OUT filled cells still count as "more
    //    fields than counted", so the count stays growable → OMISSION (no declared tail, no empty cell) ─────
    @Test fun exhaustedRowsKeptNothingStoredAboveIsOmission() =
        assertEquals(listOf("CNT|OMISSION_ERROR"),
            sigs("5", (1..10).map { "$it" to "N" }))

    // ── a matching stored count is silent ──────────────────────────────────────────────────────────────────
    @Test fun matchingCountIsSilent() =
        assertEquals(emptyList<String>(), sigs("1", listOf("3" to "Y", "4" to "N")))
}
