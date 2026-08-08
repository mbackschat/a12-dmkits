package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.gen.PlacementSink
import io.github.mbackschat.a12.dm.interpreter.gen.asSink
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * In the index-keyed parallel join a **DUPLICATED index value keys NOTHING** ([IF270]) — it is not a selectable
 * entry and is never resolved by row order, so every colliding row drops out of the value→row map. Because the
 * group is *also* marked invalid there, a reference to that value is then unmatched-into-an-invalid-group, which
 * reads 3VL UNKNOWN and suppresses a negative operator like `FieldsNotCollectivelyFilled`.
 *
 * The interpreter previously kept the colliding value with **last write wins**, so the last duplicate row stayed
 * selectable and fired a message the kernel never emits. Three rows separate that reading from every rival:
 *
 *  - `a duplicated key alongside a CLEAN one` kills "an invalid index suppresses the whole group": the clean
 *    value still fires, so invalidity is not a blanket gate — only the colliding value is gone.
 *  - `the FAR side carries the duplicated value` kills "the value simply leaves the union": the value is in the
 *    iteration set from the other group, and *still* nothing fires on this side, so the colliding rows are
 *    unmatched rather than merely unvisited.
 *  - `the same key THREE times` is the positive control for the collision set itself; a fix that only removes on
 *    the second occurrence re-inserts on the third and fires at the last row.
 *
 * `an EMPTY index cell` is the pre-existing sibling behaviour and was already exact — it never entered the map —
 * which is what made the duplicate branch the lone offender. Both kernel strategies agree on every row.
 */
class ParallelJoinDuplicateKeyDiffTest {

    private val staffing = RuntimeLaws.model("models/staffing.dm.json")

    private val model by lazy {
        RuntimeLaws.withRule(
            staffing, "/Staffing", "PJ", "../Demand/Headcount", "PJ",
            "FieldsNotCollectivelyFilled(/Staffing/Demand/Headcount, /Staffing/Supply/Headcount)",
        )
    }
    private val def by lazy { ModelLoader.load(model) }
    private val engine by lazy { RuntimeLaws.kernelJavaEngine(model) }

    @Test
    fun aDuplicatedIndexValueIsNeverASelectableJoinKey() {
        val rows = listOf(
            "unique keys (control)" to Doc(listOf("Sales" to "10", "Eng" to "20"), listOf("Sales" to "30", "Ops" to "40")),
            "a duplicated key, nothing else" to Doc(listOf("Dup" to "10", "Dup" to "20"), listOf("Ops" to "40")),
            "a duplicated key alongside a CLEAN one" to
                Doc(listOf("Dup" to "10", "Dup" to "20", "Eng" to "30"), listOf("Ops" to "40")),
            "the same key THREE times" to
                Doc(listOf("Dup" to "10", "Dup" to "20", "Dup" to "30"), listOf("Ops" to "40")),
            "the FAR side carries the duplicated value" to
                Doc(listOf("Dup" to "10", "Dup" to "20", "Eng" to "30"), listOf("Dup" to "40")),
            "both sides duplicate" to Doc(listOf("Dup" to "10", "Dup" to "20"), listOf("Dup" to "30", "Dup" to "40")),
            "an EMPTY index cell alongside a clean one" to
                Doc(listOf("" to "10", "Eng" to "20"), listOf("Ops" to "40")),
        )

        val readings = rows.map { (label, doc) -> "$label | ${verdicts(doc)}" }

        assertEquals(
            listOf(
                "unique keys (control) | $ROW2_AND_NONE",
                "a duplicated key, nothing else | $SILENT",
                "a duplicated key alongside a CLEAN one | $ROW3",
                "the same key THREE times | $SILENT",
                "the FAR side carries the duplicated value | $ROW3",
                "both sides duplicate | $SILENT",
                // Only Demand[2], NOT the control's extra `[-5]`: an empty cell invalidates the group just as a
                // duplicate does, so the far side's own key reads UNKNOWN here instead of definite-empty.
                "an EMPTY index cell alongside a clean one | $ROW2",
            ),
            readings,
            "a colliding index value is not a join key on either side, so the rows carrying it are the " +
                "outer join's unmatched side; a clean value in the same compromised group still joins normally",
        )
    }

    private fun verdicts(doc: Doc): String {
        val kDoc = RuntimeLaws.doc()
        doc.place(object : PlacementSink {
            override fun group(path: String, vararg reps: Int) { kDoc.group(path, *reps) }
            override fun field(path: String, value: String, vararg reps: Int) { kDoc.field(path, value, *reps) }
            override fun emptyField(path: String, vararg reps: Int) { kDoc.emptyField(path, *reps) }
        })
        val iDoc = def.newDocument()
        doc.place(iDoc.asSink())
        fun sig(v: List<String>) = v.sorted().joinToString(";").ifEmpty { "-" }
        val dynamic = sig(RuntimeLaws.messages(model, kDoc).filter { it.code() == "PJ" }.map { it.field() })
        val static = sig(engine.messages(engine.instance(kDoc)).filter { it.code() == "PJ" }.map { it.field() })
        val interp = sig(DmInterpreter(def).validateFull(iDoc).filter { it.code == "PJ" }.map { it.errorPath })
        return "groovy=$dynamic java=$static interp=$interp"
    }

    /** One document shape: the two sibling groups' `(Department, Headcount)` rows. An empty Department is an
     *  empty index CELL (the other invalidity), never an absent field. */
    private data class Doc(val demand: List<Pair<String, String>>, val supply: List<Pair<String, String>>) {
        fun place(sink: PlacementSink) {
            sink.group("/Staffing", 1)
            sink.field("/Staffing/PlanName", "Q3", 1, 1)
            rows(sink, DEMAND, demand)
            rows(sink, SUPPLY, supply)
        }

        private fun rows(sink: PlacementSink, group: String, values: List<Pair<String, String>>) =
            values.forEachIndexed { idx, (dept, headcount) ->
                val r = idx + 1
                sink.group(group, 1, r)
                if (dept.isEmpty()) sink.emptyField("$group/Department", 1, r, 1)
                else sink.field("$group/Department", dept, 1, r, 1)
                sink.field("$group/Headcount", headcount, 1, r, 1)
            }
    }

    private companion object {
        const val DEMAND = "/Staffing/Demand"
        const val SUPPLY = "/Staffing/Supply"

        private const val R2 = "/Staffing[1]/Demand[2]/Headcount"
        private const val R3 = "/Staffing[1]/Demand[3]/Headcount"
        /** The kernel's `IIdentifier.UNKNOWN` repetition — Demand has no row for the joined value. */
        private const val NONE = "/Staffing[1]/Demand[-5]/Headcount"

        /** A VALID index on both sides: this side's unmatched key fires at row 2, the far side's at `[-5]`. */
        const val ROW2_AND_NONE = "groovy=$NONE;$R2 java=$NONE;$R2 interp=$NONE;$R2"
        const val ROW2 = "groovy=$R2 java=$R2 interp=$R2"
        const val ROW3 = "groovy=$R3 java=$R3 interp=$R3"
        const val SILENT = "groovy=- java=- interp=-"
    }
}
