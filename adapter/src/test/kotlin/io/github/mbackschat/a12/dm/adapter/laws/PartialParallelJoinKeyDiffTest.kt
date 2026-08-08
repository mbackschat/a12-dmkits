package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws.Relevant
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.gen.PlacementSink
import io.github.mbackschat.a12.dm.interpreter.gen.asSink
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Under partial validation a PARALLEL-iteration row exists only where its INDEX cell is relevant ([IF269]).
 * The join is keyed by the shared index field and the kernel builds that cache from the relevant cells only
 * ([IF39]), so a relevant set naming the payload field but not the index produces **no iterations at all** —
 * and a group whose own index cell is left out becomes the outer join's "not specified" side for that value,
 * reporting at `Demand[-5]` exactly like a group with no row.
 *
 * Two independent gates therefore have to hold before a message appears, and the table separates them: the
 * index cell decides whether the ITERATION exists, the error field decides whether the rule RUNS in it
 * ([IF268]). `Demand[1]/Department alone` is the row that proves the second is still there.
 *
 * Both kernel strategies agree on every row. Found by probing the one arm of [IF268]'s fix that had been
 * reasoned rather than measured — the reasoning turned out to be right and this turned up beside it.
 */
class PartialParallelJoinKeyDiffTest {

    private val staffing = RuntimeLaws.model("models/staffing.dm.json")

    private val models = HashMap<String, String>()
    private val defs = HashMap<String, ModelDef>()
    private val engines = HashMap<String, RuntimeLaws.JavaKernelEngine>()

    private fun modelFor(code: String) = models.getOrPut(code) {
        val condition = when (code) {
            POSITIVE -> "FieldFilled(/Staffing/Demand/Headcount) Or FieldFilled(/Staffing/Supply/Headcount)"
            else -> "FieldsNotCollectivelyFilled(/Staffing/Demand/Headcount, /Staffing/Supply/Headcount)"
        }
        RuntimeLaws.withRule(staffing, "/Staffing", code, "../Demand/Headcount", code, condition)
    }

    @Test
    fun aParallelRowExistsOnlyWhereItsIndexCellIsRelevant() {
        val selections = listOf(
            "payload only, one row" to listOf(Sel(DHC, listOf(1, 1, 1))),
            "payload only, the other row" to listOf(Sel(DHC, listOf(1, 2, 1))),
            "payload only, wildcarded" to listOf(Sel(DHC, listOf(0, 0, 0))),
            "payload only, both rows" to listOf(Sel(DHC, listOf(1, 1, 1)), Sel(DHC, listOf(1, 2, 1))),
            "the whole GROUP of one row" to listOf(Sel(DEMAND, listOf(1, 1))),
            "index only, no payload" to listOf(Sel(DDEP, listOf(1, 1, 1))),
            "index + payload, one row" to listOf(Sel(DHC, listOf(1, 1, 1)), Sel(DDEP, listOf(1, 1, 1))),
            "index + payload, the other row" to listOf(Sel(DHC, listOf(1, 2, 1)), Sel(DDEP, listOf(1, 2, 1))),
            "index + payload, both rows" to listOf(
                Sel(DHC, listOf(1, 1, 1)), Sel(DDEP, listOf(1, 1, 1)),
                Sel(DHC, listOf(1, 2, 1)), Sel(DDEP, listOf(1, 2, 1))),
            "index + payload, wildcarded" to listOf(Sel(DHC, listOf(0, 0, 0)), Sel(DDEP, listOf(0, 0, 0))),
            "the far side only" to listOf(Sel(SHC, listOf(1, 1, 1))),
            "both sides, one key" to listOf(
                Sel(DHC, listOf(1, 1, 1)), Sel(DDEP, listOf(1, 1, 1)),
                Sel(SHC, listOf(1, 1, 1)), Sel(SDEP, listOf(1, 1, 1))),
            "the key comes from the FAR side, this side's index is not relevant" to listOf(
                Sel(SDEP, listOf(1, 1, 1)), Sel(SHC, listOf(1, 1, 1)), Sel(DHC, listOf(1, 1, 1))),
            "the key exists ONLY on the far side" to listOf(
                Sel(SDEP, listOf(1, 2, 1)), Sel(SHC, listOf(1, 2, 1)), Sel(DHC, listOf(1, 1, 1))),
        )

        val readings = selections.map { (label, sel) -> "$label | ${verdicts(POSITIVE, sel)}" }

        assertEquals(
            listOf(
                "payload only, one row | $SILENT",
                "payload only, the other row | $SILENT",
                "payload only, wildcarded | $SILENT",
                "payload only, both rows | $SILENT",
                "the whole GROUP of one row | $ROW1",
                "index only, no payload | $SILENT",
                "index + payload, one row | $ROW1",
                "index + payload, the other row | $ROW2",
                "index + payload, both rows | $BOTH",
                "index + payload, wildcarded | $BOTH",
                "the far side only | $SILENT",
                "both sides, one key | $ROW1",
                "the key comes from the FAR side, this side's index is not relevant | $UNMATCHED",
                "the key exists ONLY on the far side | $UNMATCHED",
            ),
            readings,
            "no relevant index cell means no join key and therefore no iteration at all; a relevant GROUP " +
                "supplies its own index cell, and an index cell left out of an otherwise relevant row makes " +
                "that group the unmatched side rather than removing the iteration",
        )
    }

    /**
     * The half the rule above **structurally cannot see** ([IF271]). A non-relevant index cell does not merely
     * remove a join key; it INVALIDATES the column exactly as an empty cell does, so every unmatched reference
     * into that group reads 3VL UNKNOWN instead of a definite empty. `FieldFilled(D) Or FieldFilled(S)` is TRUE
     * whenever the far side is filled — identical under both readings — so the positive shapes above pinned the
     * POINTER and left the VALIDITY unmeasured, and the interpreter shipped the wrong half of it. Only a
     * negative operator, which fires solely on a definite "not filled", separates them.
     *
     * The control matters as much as the splits: with EVERY index cell relevant the column is valid and the
     * unmatched sides fire normally, so this is not "a partial relevant set silences negative rules".
     */
    @Test
    fun aNonRelevantIndexCellInvalidatesTheColumnNotJustItsKey() {
        val selections = listOf(
            "every index cell relevant (control)" to listOf(
                Sel(DDEP, ALL3), Sel(DHC, ALL3), Sel(SDEP, ALL3), Sel(SHC, ALL3)),
            "this side's index cells are ALL non-relevant" to listOf(Sel(DHC, ALL3), Sel(SDEP, ALL3), Sel(SHC, ALL3)),
            "this side: only ONE row's index cell is relevant" to listOf(
                Sel(DDEP, listOf(1, 1, 1)), Sel(DHC, ALL3), Sel(SDEP, ALL3), Sel(SHC, ALL3)),
            "the FAR side: only one row's index cell is relevant" to listOf(
                Sel(DDEP, ALL3), Sel(DHC, ALL3), Sel(SDEP, listOf(1, 1, 1)), Sel(SHC, ALL3)),
        )

        val readings = selections.map { (label, sel) -> "$label | ${verdicts(NEGATIVE, sel)}" }

        assertEquals(
            listOf(
                "every index cell relevant (control) | $UNMATCHED_AND_ROW2",
                "this side's index cells are ALL non-relevant | $SILENT",
                "this side: only ONE row's index cell is relevant | $SILENT",
                "the FAR side: only one row's index cell is relevant | $SILENT",
            ),
            readings,
            "ONE index cell outside the relevant set compromises the whole column, on either side of the join, " +
                "so the negative operator is suppressed — while a fully relevant column fires exactly as it " +
                "does under full validation",
        )
    }

    private fun verdicts(code: String, selection: List<Sel>): String {
        val model = modelFor(code)
        val def = defs.getOrPut(code) { ModelLoader.load(model) }
        val engine = engines.getOrPut(code) { RuntimeLaws.kernelJavaEngine(model) }
        val kDoc = RuntimeLaws.doc()
        place(object : PlacementSink {
            override fun group(path: String, vararg reps: Int) { kDoc.group(path, *reps) }
            override fun field(path: String, value: String, vararg reps: Int) { kDoc.field(path, value, *reps) }
            override fun emptyField(path: String, vararg reps: Int) { kDoc.emptyField(path, *reps) }
        })
        val iDoc = def.newDocument()
        place(iDoc.asSink())
        val kRel = selection.map { Relevant.of(it.path, *it.reps.toIntArray()) }.toSet()
        val iRel = selection.mapTo(HashSet()) { RelevantEntity(it.path, it.reps) }
        fun sig(v: List<String>) = v.sorted().joinToString(";").ifEmpty { "-" }
        val dynamic = sig(RuntimeLaws.messagesPart(model, kDoc, kRel).filter { it.code() == code }.map { it.field() })
        val static = sig(engine.messagesPart(kDoc, kRel).filter { it.code() == code }.map { it.field() })
        val interp = sig(DmInterpreter(def).validatePart(iDoc, iRel).messages
            .filter { it.code == code }.map { it.errorPath })
        return "groovy=$dynamic java=$static interp=$interp"
    }

    /** Demand: Sales=10, Eng=20 · Supply: Sales=30, Ops=40 — the outer join spans {Sales, Eng, Ops}. */
    private fun place(sink: PlacementSink) {
        sink.group("/Staffing", 1); sink.field("/Staffing/PlanName", "Q3", 1, 1)
        sink.group(DEMAND, 1, 1); sink.field(DDEP, "Sales", 1, 1, 1); sink.field(DHC, "10", 1, 1, 1)
        sink.group(DEMAND, 1, 2); sink.field(DDEP, "Eng", 1, 2, 1); sink.field(DHC, "20", 1, 2, 1)
        sink.group(SUPPLY, 1, 1); sink.field(SDEP, "Sales", 1, 1, 1); sink.field(SHC, "30", 1, 1, 1)
        sink.group(SUPPLY, 1, 2); sink.field(SDEP, "Ops", 1, 2, 1); sink.field(SHC, "40", 1, 2, 1)
    }

    private data class Sel(val path: String, val reps: List<Int>)

    private companion object {
        const val DEMAND = "/Staffing/Demand"
        const val SUPPLY = "/Staffing/Supply"
        const val DHC = "/Staffing/Demand/Headcount"
        const val DDEP = "/Staffing/Demand/Department"
        const val SHC = "/Staffing/Supply/Headcount"
        const val SDEP = "/Staffing/Supply/Department"

        private const val R1 = "/Staffing[1]/Demand[1]/Headcount"
        private const val R2 = "/Staffing[1]/Demand[2]/Headcount"
        /** The kernel's `IIdentifier.UNKNOWN` repetition — this group has no row for the joined value. */
        private const val NONE = "/Staffing[1]/Demand[-5]/Headcount"
        const val POSITIVE = "PG"
        const val NEGATIVE = "NG"
        val ALL3 = listOf(0, 0, 0)

        const val ROW1 = "groovy=$R1 java=$R1 interp=$R1"
        const val ROW2 = "groovy=$R2 java=$R2 interp=$R2"
        const val BOTH = "groovy=$R1;$R2 java=$R1;$R2 interp=$R1;$R2"
        const val UNMATCHED = "groovy=$NONE java=$NONE interp=$NONE"
        /** The negative rule over a fully relevant column: both unmatched keys fire, as under full validation. */
        const val UNMATCHED_AND_ROW2 = "groovy=$NONE;$R2 java=$NONE;$R2 interp=$NONE;$R2"
        const val SILENT = "groovy=- java=- interp=-"
    }
}
