package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **Parallel iteration is a documented interpreter GAP — and it FAILS LOUD, never silently wrong.** When a
 * rule references two NON-nested repeatable groups, the kernel iterates them jointly as an outer join keyed by
 * a shared index field (KERNEL-SEMANTICS §9; its `IndexFieldCache` makes that O(rows) — the kernel's steepest
 * O(N²) avoidance). The interpreter does not model that join. The danger was a SILENT wrong answer (it iterated
 * one side and read the other at row 1 → under-fired vs the kernel — verified). This test locks the fix: the
 * kernel **fires**, and the interpreter reports the rule **unsupported** (a loud, honest "not modeled"), rather
 * than emitting a wrong/missing message. (Implementing the index-keyed outer join — mirroring the kernel's
 * IndexFieldCache clean-room — is the future fix; until then, loud > wrong.)
 */
class ParallelIterationGapTest {

    private val staffing = RuntimeLaws.model("models/staffing.dm.json")
    private val cond = "FieldsNotCollectivelyFilled(Demand/Headcount, Supply/Headcount)"
    private val kModel = RuntimeLaws.withRule(staffing, "/Staffing", "Mismatch", "../Demand/Headcount", "MISMATCH", cond)
    private val def = ModelDef(
        listOf(
            FieldDef("/Staffing/PlanName", Kind.STRING),
            FieldDef("/Staffing/Demand/Department", Kind.STRING),
            FieldDef("/Staffing/Demand/Headcount", Kind.NUMBER),
            FieldDef("/Staffing/Supply/Department", Kind.STRING),
            FieldDef("/Staffing/Supply/Headcount", Kind.NUMBER),
        ),
        listOf(RuleDef("/Staffing/Mismatch", "/Staffing", cond, "MISMATCH", "../Demand/Headcount")),
        repeatableGroups = setOf("/Staffing/Demand", "/Staffing/Supply"),
        declaredReps = mapOf("/Staffing/Demand" to 50, "/Staffing/Supply" to 50),
        indexFields = setOf("/Staffing/Demand/Department", "/Staffing/Supply/Department"),
    )

    @Test
    fun kernelFiresButInterpreterReportsUnsupported_neverSilentlyWrong() {
        // Demand: Sales=5, Eng=3 ; Supply: Sales=5 (no Eng row) — the kernel's outer join fires on Eng.
        val kDoc = RuntimeLaws.doc().group("/Staffing", 1).field("/Staffing/PlanName", "Q3", 1, 1)
            .group("/Staffing/Demand", 1, 1).field("/Staffing/Demand/Department", "Sales", 1, 1, 1).field("/Staffing/Demand/Headcount", "5", 1, 1, 1)
            .group("/Staffing/Demand", 1, 2).field("/Staffing/Demand/Department", "Eng", 1, 2, 1).field("/Staffing/Demand/Headcount", "3", 1, 2, 1)
            .group("/Staffing/Supply", 1, 1).field("/Staffing/Supply/Department", "Sales", 1, 1, 1).field("/Staffing/Supply/Headcount", "5", 1, 1, 1)
        val iDoc = Document(def.kinds, def.declaredReps, def.unsignedFields, def.rangeConfig, def.dateFormats, def.scales, def.dateTimeFormats, def.datePrecisions)
            .group("/Staffing", 1).field("/Staffing/PlanName", "Q3", 1, 1)
            .group("/Staffing/Demand", 1, 1).field("/Staffing/Demand/Department", "Sales", 1, 1, 1).field("/Staffing/Demand/Headcount", "5", 1, 1, 1)
            .group("/Staffing/Demand", 1, 2).field("/Staffing/Demand/Department", "Eng", 1, 2, 1).field("/Staffing/Demand/Headcount", "3", 1, 2, 1)
            .group("/Staffing/Supply", 1, 1).field("/Staffing/Supply/Department", "Sales", 1, 1, 1).field("/Staffing/Supply/Headcount", "5", 1, 1, 1)

        assertTrue(RuntimeLaws.fires(kModel, "MISMATCH", kDoc), "kernel: the outer join fires on the unmatched Eng department")

        val outcome = DmInterpreter(def).validate(iDoc)
        assertEquals(0, outcome.messages.count { it.code == "MISMATCH" },
            "interpreter must NOT emit a (wrong) MISMATCH — it does not model the join")
        assertTrue(outcome.unsupported.any { it.rulePath == "/Staffing/Mismatch" && it.reason.contains("parallel iteration") },
            "interpreter must report the rule UNSUPPORTED (loud), not silently produce a wrong answer: ${outcome.unsupported}")
    }
}
