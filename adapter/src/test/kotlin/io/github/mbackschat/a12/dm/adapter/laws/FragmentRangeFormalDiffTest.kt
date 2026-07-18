package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — the formal check for a DATE_RANGE whose declared format is a **date FRAGMENT** (`yyyy`, a
 * year-range like `2024/2025` — a real-workspace idiom the IG56 E3 hunt surfaced: the interpreter fired
 * `datumBereichFormatFalsch` on every such value because the range side-parse knew only full `y-M-d` formats,
 * while the kernel parses each side BY THE DECLARED FORMAT, whatever its granularity). Own domain (travel
 * shop): `Trip/Season`, format `yyyy`, separator `/`.
 */
class FragmentRangeFormalDiffTest {

    private val SEASON = "/Trip/Season"

    private val kModel = DocumentModelDraft.named("tripseasons").locale("en_US")
        .rootGroup("Trip") { t ->
            t.dateRangeField("Season") { it.format("yyyy").separator("/") }
        }.build().toJson()

    private val def = ModelDef(
        listOf(FieldDef(SEASON, Kind.DATE_RANGE, format = "yyyy", rangeSeparator = "/")),
        emptyList(),
    )

    private fun assertFormalAgrees(value: String) {
        val kDoc = RuntimeLaws.doc().group("/Trip", 1).field(SEASON, value, 1, 1)
        val iDoc = def.newDocument()
            .group("/Trip", 1).field(SEASON, value, 1, 1)
        val kSigs = RuntimeLaws.messages(kModel, kDoc).map { "${it.code()}|${it.type().name}|${it.field()}" }.sorted()
        val iSigs = DmInterpreter(def).validateFull(iDoc)
            .map { "${it.code}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}|${it.errorPath}" }.sorted()
        assertEquals(kSigs, iSigs, "formal-error signatures must match the kernel ($SEASON = \"$value\")")
    }

    @Test
    fun yearRangeFormalCodesMatchKernel() {
        assertFormalAgrees("2024/2024")   // the corpus instance: start == end, year granularity → valid
        assertFormalAgrees("2024/2025")   // ordinary year range → valid
        assertFormalAgrees("2025/2024")   // start after end
        assertFormalAgrees("20x4/2024")   // a side that is not a year for the format
        assertFormalAgrees("2024")        // no separator
        assertFormalAgrees("1500/1600")   // before the Gregorian floor (IF88) — the kernel decides
    }
}
