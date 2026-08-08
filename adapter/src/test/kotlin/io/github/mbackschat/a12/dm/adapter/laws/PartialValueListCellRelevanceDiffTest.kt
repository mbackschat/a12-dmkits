package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws.Relevant
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A starred value-list entry reads only the RELEVANT cells ([IF267]). The extent gate ([IF264]) answers
 * *"do I know every row?"*; this answers the separate question *"may I read THIS row?"*, and the two are
 * independent — `AtLeastOne` never consults the extent, yet it still must not see a cell the caller left out
 * of the relevant set. The discriminating row is the second one: the ONLY matching cell is non-relevant, so
 * the kernel stays silent while a relevance-blind scan would fire.
 *
 * The `No` block is the co-measurement that keeps the reading honest: on that side the extent gate fires
 * first on every subset shape, so `No` cannot discriminate a skipped cell from an unknown one. That is why
 * this law is stated over `AtLeastOne` alone.
 */
class PartialValueListCellRelevanceDiffTest {

    private val json = DocumentModelDraft.named("catalog").locale("en_US")
        .rootGroup("Order") { g ->
            g.stringField("Ack") { }
                .group("Items") { it.repeatable(50).stringField("Tag") { } }
        }.build().toJson()

    // One model, one loaded ModelDef, one static-Java engine per condition — a per-case rebuild would put
    // codegen inside a task that also carries timing floors (CLAUDE.md).
    private val engines = mutableMapOf<String, RuntimeLaws.JavaKernelEngine>()
    private val models = mutableMapOf<String, String>()
    private val defs = mutableMapOf<String, ModelDef>()

    @Test
    fun aStarredValueListEntrySeesOnlyTheRelevantCells() {
        val readings = listOf(
            "the only matching cell is NOT relevant" to
                listOf(Sel(ACK, listOf(1, 1)), Sel(TAG, listOf(1, 2, 1))),
            "the matching cell IS relevant" to
                listOf(Sel(ACK, listOf(1, 1)), Sel(TAG, listOf(1, 1, 1))),
            "every cell relevant by wildcard" to
                listOf(Sel(ACK, listOf(1, 1)), Sel(TAG, listOf(0, 0, 0))),
            "a non-matching row's GROUP only" to
                listOf(Sel(ACK, listOf(1, 1)), Sel(ITEMS, listOf(1, 2))),
        ).map { (label, sel) -> "$label | ${verdicts(AT_LEAST_ONE, sel)}" }

        assertEquals(
            listOf(
                "the only matching cell is NOT relevant | $SILENT",
                "the matching cell IS relevant | $FIRES",
                "every cell relevant by wildcard | $FIRES",
                "a non-matching row's GROUP only | $SILENT",
            ),
            readings,
            "a cell outside the relevant set contributes nothing to the scan — row 1 is the discriminator, " +
                "where the sole \"A\" sits in a row the caller did not select",
        )
    }

    @Test
    fun theNoSideIsDecidedByTheExtentGateBeforeCellRelevanceCanShow() {
        val readings = listOf(
            "no cell matches, every cell relevant by wildcard" to
                listOf(Sel(ACK, listOf(1, 1)), Sel(TAG, listOf(0, 0, 0))),
            "no cell matches, but one concrete row only" to
                listOf(Sel(ACK, listOf(1, 1)), Sel(TAG, listOf(1, 2, 1))),
        ).map { (label, sel) -> "$label | ${verdicts(NO_UNMATCHED, sel)}" }

        assertEquals(
            listOf(
                "no cell matches, every cell relevant by wildcard | $FIRES",
                "no cell matches, but one concrete row only | $SILENT",
            ),
            readings,
            "`No` needs the COMPLETE extent, so a concrete subset silences it whatever the cells say — which " +
                "is exactly why the cell-relevance law above is stated over `AtLeastOne`",
        )
    }

    private fun verdicts(condition: String, selection: List<Sel>): String {
        val model = models.getOrPut(condition) {
            RuntimeLaws.withRule(json, "/Order", "VL", "../Ack", "VL", condition)
        }
        val def = defs.getOrPut(model) { ModelLoader.load(model) }
        val kDoc = RuntimeLaws.doc().group("/Order", 1).field(ACK, "X", 1, 1)
        val iDoc = def.newDocument().group("/Order", 1).field(ACK, "X", 1, 1)
        listOf("A", "B", "C").forEachIndexed { idx, tag ->
            val r = idx + 1
            kDoc.group(ITEMS, 1, r).field(TAG, tag, 1, r, 1)
            iDoc.group(ITEMS, 1, r).field(TAG, tag, 1, r, 1)
        }
        val kRel = selection.map { Relevant.of(it.path, *it.reps.toIntArray()) }.toSet()
        val iRel = selection.mapTo(HashSet()) { RelevantEntity(it.path, it.reps) }
        val dynamic = RuntimeLaws.messagesPart(model, kDoc, kRel).any { it.code() == "VL" }
        val static = engines.getOrPut(model) { RuntimeLaws.kernelJavaEngine(model) }
            .messagesPart(kDoc, kRel).any { it.code() == "VL" }
        val interp = DmInterpreter(def).validatePart(iDoc, iRel).messages.any { it.code == "VL" }
        return "groovy=$dynamic java=$static interp=$interp"
    }

    private data class Sel(val path: String, val reps: List<Int>)

    private companion object {
        const val ACK = "/Order/Ack"
        const val ITEMS = "/Order/Items"
        const val TAG = "/Order/Items/Tag"
        const val AT_LEAST_ONE =
            "FieldFilled(/Order/Ack) And AtLeastOneFieldValueIncludedInValueList(/Order/Items*/Tag In \"A\")"
        const val NO_UNMATCHED =
            "FieldFilled(/Order/Ack) And NoFieldValueIncludedInValueList(/Order/Items*/Tag In \"Z\")"
        const val FIRES = "groovy=true java=true interp=true"
        const val SILENT = "groovy=false java=false interp=false"
    }
}
