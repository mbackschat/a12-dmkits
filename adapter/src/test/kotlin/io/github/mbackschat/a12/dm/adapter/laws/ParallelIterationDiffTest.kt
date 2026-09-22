package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.gen.PlacementSink
import io.github.mbackschat.a12.dm.interpreter.gen.asSink
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Differential — **parallel iteration** (the index-keyed outer join, KERNEL-SEMANTICS §9). Two sibling
 * repeatable groups (`Demand` ⊥ `Supply` under `/Staffing`, sharing the `Department` index field) are iterated
 * jointly over the UNION of their index values; a group with no row for a value counts as "not specified". The
 * interpreter must agree with the real kernel on the full message signature `code|type|pointer` — including the
 * two subtleties the probe revealed:
 *  - **polarity**: an iteration where an operand is unspecified types OMISSION; all-specified types VALUE;
 *  - **pointer**: a fired iteration where the *error field's own group* has no matching row points at the
 *    kernel's `IIdentifier.UNKNOWN` repetition (`/Staffing[1]/Demand[-5]/Headcount`).
 *
 * This replaces the former `ParallelIterationGapTest` (which locked the fail-loud stop-gap); the construct is
 * now implemented, so the lock is the exact-match differential.
 */
class ParallelIterationDiffTest {

    private val staffing = RuntimeLaws.model("models/staffing.dm.json")

    // Conditions are authored with the rulekit typed DSL ([m]) and rendered to the canonical string fed to BOTH
    // engines — no free-text condition literals. Refs use ABSOLUTE paths; the renderer emits absolute and both
    // engines accept it (IF24). The cross-group parallel join is implicit in the two sibling field paths.
    private val m = BoundModel.ofJson(staffing)
    private fun render(c: Condition): String = RuntimeLaws.render(c)
    private val DEMAND_HC = "/Staffing/Demand/Headcount"
    private val SUPPLY_HC = "/Staffing/Supply/Headcount"

    private val def = ModelDef(
        listOf(
            FieldDef("/Staffing/PlanName", Kind.STRING),
            FieldDef("/Staffing/Demand/Department", Kind.STRING),
            FieldDef("/Staffing/Demand/Headcount", Kind.NUMBER, scale = 0),
            FieldDef("/Staffing/Supply/Department", Kind.STRING),
            FieldDef("/Staffing/Supply/Headcount", Kind.NUMBER, scale = 0),
        ),
        emptyList(),
        repeatableGroups = setOf("/Staffing/Demand", "/Staffing/Supply"),
        declaredReps = mapOf("/Staffing/Demand" to 20, "/Staffing/Supply" to 20),
        indexFields = setOf("/Staffing/Demand/Department", "/Staffing/Supply/Department"),
    )

    /** Demand: Sales=v1, Eng=v2 ; Supply: Sales=v3, Ops=v4 — the outer join spans {Sales, Eng, Ops}. */
    private fun place(sink: PlacementSink, v1: String?, v2: String?, v3: String?, v4: String?) {
        sink.group("/Staffing", 1); sink.field("/Staffing/PlanName", "Q3", 1, 1)
        sink.group("/Staffing/Demand", 1, 1); sink.field("/Staffing/Demand/Department", "Sales", 1, 1, 1)
        v1?.let { sink.field("/Staffing/Demand/Headcount", it, 1, 1, 1) }
        sink.group("/Staffing/Demand", 1, 2); sink.field("/Staffing/Demand/Department", "Eng", 1, 2, 1)
        v2?.let { sink.field("/Staffing/Demand/Headcount", it, 1, 2, 1) }
        sink.group("/Staffing/Supply", 1, 1); sink.field("/Staffing/Supply/Department", "Sales", 1, 1, 1)
        v3?.let { sink.field("/Staffing/Supply/Headcount", it, 1, 1, 1) }
        sink.group("/Staffing/Supply", 1, 2); sink.field("/Staffing/Supply/Department", "Ops", 1, 2, 1)
        v4?.let { sink.field("/Staffing/Supply/Headcount", it, 1, 2, 1) }
    }

    /** Kernel `code|type|pointer` signatures for [code] over the document built by [build]. */
    private fun kernelSigs(code: String, cond: String, build: (PlacementSink) -> Unit): List<String> {
        val model = RuntimeLaws.withRule(staffing, "/Staffing", code, "../Demand/Headcount", code, cond)
        val doc = RuntimeLaws.doc()
        build(object : PlacementSink {
            override fun group(path: String, vararg reps: Int) { doc.group(path, *reps) }
            override fun field(path: String, value: String, vararg reps: Int) { doc.field(path, value, *reps) }
            override fun emptyField(path: String, vararg reps: Int) { doc.emptyField(path, *reps) }
        })
        return RuntimeLaws.messages(model, doc).filter { it.code() == code }
            .map { "${it.code()}|${it.type().name}|${it.field()}" }.sorted()
    }

    /** Interpreter `code|type|pointer` signatures for [code] over the document built by [build]. */
    private fun interpSigs(code: String, cond: String, build: (PlacementSink) -> Unit): List<String> {
        val iDoc = def.newDocument()
        build(iDoc.asSink())
        val ruleDef = RuleDef("/Staffing/$code", "/Staffing", cond, code, "../Demand/Headcount")
        return DmInterpreter(def.copy(rules = listOf(ruleDef))).validateFull(iDoc).filter { it.code == code }
            .map { "${it.code}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}|${it.errorPath}" }.sorted()
    }

    private fun assertAgrees(code: String, cond: String, build: (PlacementSink) -> Unit) =
        assertEquals(kernelSigs(code, cond, build), interpSigs(code, cond, build),
            "parallel-iteration signatures must match the kernel for $code [$cond]")

    private fun assertAgrees(code: String, cond: String, v1: String?, v2: String?, v3: String?, v4: String?) =
        assertAgrees(code, cond) { place(it, v1, v2, v3, v4) }

    @Test
    fun fieldsNotCollectivelyFilledMatchesKernel() {
        // Eng (Demand=3, Supply missing) AND Ops (Demand missing → Demand[-5], Supply=2) both fire OMISSION.
        assertAgrees("PI", render(Dsl.fieldsNotCollectivelyFilled(m.field(DEMAND_HC), m.field(SUPPLY_HC))), "5", "3", "5", "2")
    }

    @Test
    fun guardedPerRowCompareMatchesKernel() {
        // Both-filled guard → only the matched index value (Sales: 9 > 5) fires, VALUE; no synthetic-index row.
        assertAgrees("PX", render(m.field(DEMAND_HC).filled().and(m.field(SUPPLY_HC).filled())
            .and(m.num(DEMAND_HC).gt(m.num(SUPPLY_HC)))),
            "9", "3", "5", "2")
    }

    @Test
    fun unguardedPerRowCompareMatchesKernel() {
        // Empty-as-0 across the join: Sales 9>5 VALUE ; Eng 3>0(missing) OMISSION ; Ops 0>2 no fire.
        assertAgrees("RW", render(m.num(DEMAND_HC).gt(m.num(SUPPLY_HC))), "9", "3", "5", "2")
    }

    @Test
    fun invalidIndexInParallelGroupMatchesKernel() {
        // Demand (error group) has a VALID index (Sales); Supply has an INVALID index — a row with an empty
        // Department. The kernel reads Supply's UNMATCHED references as 3VL UNKNOWN (not "empty"), which fires PI
        // on Supply's valid Ops value (→ the synthetic Demand[-5]) but suppresses it where the Supply side is
        // unknown. The interpreter models exactly that (the empty index also emits `mandatoryField`); assert the
        // full message signatures agree (KERNEL-FINDINGS §9).
        val cond = render(Dsl.fieldsNotCollectivelyFilled(m.field(DEMAND_HC), m.field(SUPPLY_HC)))
        fun build(sink: PlacementSink) {
            sink.group("/Staffing", 1); sink.field("/Staffing/PlanName", "Q3", 1, 1)
            sink.group("/Staffing/Demand", 1, 1); sink.field("/Staffing/Demand/Department", "Sales", 1, 1, 1); sink.field("/Staffing/Demand/Headcount", "5", 1, 1, 1)
            sink.group("/Staffing/Supply", 1, 1); sink.field("/Staffing/Supply/Department", "Ops", 1, 1, 1); sink.field("/Staffing/Supply/Headcount", "2", 1, 1, 1)
            sink.group("/Staffing/Supply", 1, 2); sink.field("/Staffing/Supply/Headcount", "4", 1, 2, 1)   // empty Department → invalid index
        }
        assertTrue(kernelSigs("PI", cond, ::build).isNotEmpty(),
            "precondition: the kernel fires PI here (so the agreement isn't vacuous)")
        assertAgrees("PI", cond, ::build)
    }

    @Test
    fun allUnmatchedDisjointKeysMatchesKernel() {
        // Demand IS the error field's group and HAS rows (Sales,Eng) while Supply is empty — every iteration fires
        // OMISSION (Supply unspecified). Sanity: the kernel really fires (the assertion isn't vacuous).
        val cond = render(Dsl.fieldsNotCollectivelyFilled(m.field(DEMAND_HC), m.field(SUPPLY_HC)))
        assertTrue(kernelSigs("PI", cond) { place(it, "5", "3", null, null) }.isNotEmpty(),
            "kernel must fire PI for the test to be meaningful")
        assertAgrees("PI", cond, "5", "3", null, null)
    }
}
