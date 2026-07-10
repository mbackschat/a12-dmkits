package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM + JS) lock for IF114 — the polarity of a fired comparison against a `Having`-FILTERED
 * entity COUNT: the kernel's `anzahlAngegebenerEntities` escalates both directions only when the filter
 * actually COUNTED a value; a kept-nothing count is GROW-ONLY (it can grow while any cell remains uncounted —
 * incl. a filtered-out filled cell), so the fired mismatch is DIRECTIONAL: stored below the count → VALUE,
 * stored above → OMISSION. Kernel-verified by the differential `adapter.laws.FilteredCountPolarityDiffTest`.
 */
class FilteredCountPolarityTest {

    private val COUNT = "/Order/Count"
    private val QTY = "/Order/Items/Qty"
    private val FLAG = "/Order/Items/Flag"

    private val def = ModelDef(
        fields = listOf(FieldDef(COUNT, Kind.NUMBER, scale = 0),
            FieldDef(QTY, Kind.NUMBER, scale = 0), FieldDef(FLAG, Kind.STRING)),
        rules = listOf(RuleDef("/Order/CountRule", "/Order",
            "[Count] != NumberOfFilledFields(Items*/Qty Having [Items/Flag] == \"Y\")", "CNT", "../Count")),
        computations = emptyList(),
        repeatableGroups = setOf("/Order/Items"),
        declaredReps = mapOf("/Order/Items" to 10),
    )

    private fun sigs(count: String, rows: List<Pair<String?, String?>>): List<String> {
        val doc = def.newDocument().group("/Order", 1).field(COUNT, count, 1, 1)
        rows.forEachIndexed { i, (qty, flag) ->
            doc.group("/Order/Items", 1, i + 1)
            qty?.let { doc.field(QTY, it, 1, i + 1, 1) }
            flag?.let { doc.field(FLAG, it, 1, i + 1, 1) }
        }
        return DmInterpreter(def).validateFull(doc)
            .map { "${it.code}|${if (it.type == MsgType.OMISSION) "O" else "V"}" }.sorted()
    }

    @Test fun keptNothingStoredBelowIsValue() =
        assertEquals(listOf("CNT|V"), sigs("-5", listOf("3" to "N", "4" to "N")))

    @Test fun keptNothingStoredAboveIsOmission() =
        assertEquals(listOf("CNT|O"), sigs("5", listOf("3" to "N", "4" to "N")))

    @Test fun keptSomeMismatchIsOmission() =
        assertEquals(listOf("CNT|O"), sigs("1", listOf("3" to "Y", "4" to "Y")))

    @Test fun noRowsStoredAboveIsOmission() =
        assertEquals(listOf("CNT|O"), sigs("5", emptyList()))

    @Test fun exhaustedRowsKeptNothingStoredAboveIsOmission() =
        assertEquals(listOf("CNT|O"), sigs("5", (1..10).map { "$it" to "N" }))

    @Test fun matchingCountIsSilent() =
        assertEquals(emptyList(), sigs("1", listOf("3" to "Y", "4" to "N")))
}
