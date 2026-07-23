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
 * Multiplatform (JVM + JS) lock for SPEC-2026-07-23-12 — the `NumberOfValueInFields` count + polarity
 * contract, the value-count twin of [FilteredCountPolarityTest]. The count rides the kernel's filter-sensitive
 * count rule (`anzahlWertInFeldlisteIntern` / `containsFilteredValue`): a filtered operand makes the count
 * growable unconditionally, but shrinkable ONLY when a currently counted cell that MATCHED the constant came
 * through the filter — so a filtered current match is both-directional (OMISSION stored either side) while a
 * filtered selected non-match is a grow-only zero (VALUE stored below). An unfiltered star is grow-only via
 * its omitted tail; a kept malformed cell suppresses the whole count; textual families match by exact token
 * identity. Kernel-verified (both strategies) by `adapter.laws.NumberOfValueInFieldsPolarityDiffTest`.
 */
class NumberOfValueInFieldsPolarityTest {

    private val COUNT = "/Order/Count"
    private val QTY = "/Order/Items/Qty"
    private val FLAG = "/Order/Items/Flag"

    private fun def(cond: String) = ModelDef(
        fields = listOf(FieldDef(COUNT, Kind.NUMBER, scale = 0),
            FieldDef(QTY, Kind.NUMBER, scale = 0), FieldDef(FLAG, Kind.STRING)),
        rules = listOf(RuleDef("/Order/NvRule", "/Order", "[Count] != $cond", "NV", "../Count")),
        computations = emptyList(),
        repeatableGroups = setOf("/Order/Items"),
        declaredReps = mapOf("/Order/Items" to 10),
    )

    private fun sigs(cond: String, count: String, rows: List<Pair<String?, String?>>): List<String> {
        val d = def(cond)
        val doc = d.newDocument().group("/Order", 1).field(COUNT, count, 1, 1)
        rows.forEachIndexed { i, (qty, flag) ->
            doc.group("/Order/Items", 1, i + 1)
            qty?.let { doc.field(QTY, it, 1, i + 1, 1) }
            flag?.let { doc.field(FLAG, it, 1, i + 1, 1) }
        }
        return DmInterpreter(d).validateFull(doc)
            .map { "${it.code}|${if (it.type == MsgType.OMISSION) "O" else "V"}" }.sorted()
    }

    private val nv = "NumberOfValueInFields(1 In Items*/Qty Having [Items/Flag] == \"Y\")"
    private val nvBare = "NumberOfValueInFields(1 In Items*/Qty)"
    private val nvStr = "NumberOfValueInFields(\"A\" In Items*/Flag)"

    @Test fun filteredMatchStoredBelowIsOmission() =
        assertEquals(listOf("NV|O"), sigs(nv, "0", listOf("1" to "Y", "1" to "Y")))

    @Test fun filteredNonMatchStoredBelowIsValue() =
        assertEquals(listOf("NV|V"), sigs(nv, "-5", listOf("2" to "Y", "3" to "Y")))

    @Test fun filteredNonMatchStoredAboveIsOmission() =
        assertEquals(listOf("NV|O"), sigs(nv, "5", listOf("2" to "Y", "3" to "Y")))

    @Test fun filterDropsRowsThenMatchesValue() =
        assertEquals(listOf("NV|O"), sigs(nv, "0", listOf("1" to "N", "1" to "Y")))

    // the kept malformed `07` makes the count non-evaluable → NV absent (suppressed); only the cell's own
    // leading-zero formal error remains (`zahlHatFuehrendeNullen`, matching the kernel).
    @Test fun firstFormalCauseSuppressesTheCount() =
        assertEquals(listOf("zahlHatFuehrendeNullen|V"), sigs(nv, "5", listOf("07" to "Y", "1" to "Y")))

    @Test fun bareStarOmittedTailStoredAboveIsOmission() =
        assertEquals(listOf("NV|O"), sigs(nvBare, "5", listOf("1" to "Y")))

    @Test fun bareStarStoredBelowIsValue() =
        assertEquals(listOf("NV|V"), sigs(nvBare, "-5", listOf("1" to "Y")))

    @Test fun exactTokenIdentityCountsOnlyTheExactToken() =
        assertEquals(emptyList(), sigs(nvStr, "1", listOf(null to "A", null to "a")))

    @Test fun matchingCountIsSilent() =
        assertEquals(emptyList(), sigs(nv, "2", listOf("1" to "Y", "1" to "Y")))
}
