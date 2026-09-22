package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — `RepetitionNotUnique` over a NESTED repeatable model (`/Project → Milestones[] → Tasks[]`,
 * the project fixture). The basic version's scope is the **first repeatable group below the rule group**, so a
 * key two repeatable levels deep (`Milestones/Tasks/Effort`, rule at `/Project`) is checked across ALL tasks
 * of all milestones — NOT per milestone (kernel-probed; the single-level `RepetitionNotUniqueDiffTest` can't
 * distinguish this). Compares the full per-row RU message set. `@From` (a narrower reference) is a follow-up.
 */
class RepetitionNotUniqueNestedDiffTest {

    private val project = RuntimeLaws.model("models/project.dm.json")

    // Conditions are authored with the rulekit typed DSL ([m]) and rendered to the canonical string fed to BOTH
    // engines — no free-text condition literals. Refs use ABSOLUTE paths; the renderer emits absolute and both
    // engines accept it (IF24). `repetitionNotUnique(field).from(group)` is the `@From` reference-scope form.
    private val m = BoundModel.ofJson(project)
    private fun render(c: Condition): String = RuntimeLaws.render(c)

    private val fields = listOf(FieldDef(PHASE, Kind.STRING), FieldDef(TASK_ID, Kind.STRING), FieldDef(EFFORT, Kind.NUMBER))
    private val cond = render(Dsl.repetitionNotUnique(m.field(EFFORT)))

    /** Milestones each holding a list of Task Efforts; Phase/TaskId distinct (indexes). Compare kernel vs interp. */
    private fun agree(efforts: List<List<String>>, msg: String) = agree(cond, efforts, msg)

    private fun agree(cond: String, efforts: List<List<String>>, msg: String) {
        val kModel = RuntimeLaws.withRule(project, "/Project", "RU", "../Milestones/Tasks/Effort", "RU", cond)
        val def = ModelDef(fields, listOf(RuleDef("/Project/RU", "/Project", cond, "RU", "../Milestones/Tasks/Effort")),
            repeatableGroups = setOf("/Project/Milestones", "/Project/Milestones/Tasks"),
            declaredReps = mapOf("/Project/Milestones" to 20, "/Project/Milestones/Tasks" to 20),
            indexFields = setOf(PHASE, TASK_ID))
        val kDoc = RuntimeLaws.doc().group("/Project", 1)
        val iDoc = def.newDocument()
        efforts.forEachIndexed { mi, tasks ->
            val m = mi + 1
            kDoc.group("/Project/Milestones", 1, m).field(PHASE, "P$m", 1, m, 1)
            iDoc.group("/Project/Milestones", 1, m).field(PHASE, "P$m", 1, m, 1)
            tasks.forEachIndexed { ti, eff ->
                val t = ti + 1
                kDoc.group("/Project/Milestones/Tasks", 1, m, t).field(TASK_ID, "T${m}_$t", 1, m, t, 1).field(EFFORT, eff, 1, m, t, 1)
                iDoc.group("/Project/Milestones/Tasks", 1, m, t).field(TASK_ID, "T${m}_$t", 1, m, t, 1).field(EFFORT, eff, 1, m, t, 1)
            }
        }
        val kDesc = RuntimeLaws.messages(kModel, kDoc).filter { it.code() == "RU" }
            .map { "${it.field()}|${it.type().name}" }.sorted()
        val iDesc = DmInterpreter(def).validateFull(iDoc).filter { it.code == "RU" }
            .map { "${it.errorPath}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}" }.sorted()
        assertEquals(kDesc, iDesc, "$msg\n  kernel=$kDesc\n  interp=$iDesc")
    }

    @Test fun duplicateAcrossMilestonesFires() =
        agree(listOf(listOf("5", "7"), listOf("5", "9")), "5 in M1 & M2 duplicates ACROSS milestones (basic scope is the rule group)")

    @Test fun duplicateWithinAMilestoneFires() =
        agree(listOf(listOf("5", "5"), listOf("7", "9")), "5,5 within M1 → both M1 tasks flagged")

    @Test fun allDistinctNoFire() =
        agree(listOf(listOf("1", "2"), listOf("3", "4")), "all distinct across the project → none")

    @Test fun threeWayAcross() =
        agree(listOf(listOf("5", "7"), listOf("5", "5")), "5 appears 3× across M1+M2 → all three flagged")

    // @From overrides the reference group. M1[5,7] M2[5,9]: the 5 dups across milestones, unique within each.
    @Test fun fromMilestonesIsGlobal() =
        agree(render(Dsl.repetitionNotUnique(m.field(EFFORT)).from(m.group("/Project/Milestones"))),
            listOf(listOf("5", "7"), listOf("5", "9")), "@From Milestones → reference Milestones → checked across all milestones (fires)")

    @Test fun fromTasksIsPerMilestone() =
        agree(render(Dsl.repetitionNotUnique(m.field(EFFORT)).from(m.group("/Project/Milestones/Tasks"))),
            listOf(listOf("5", "7"), listOf("5", "9")), "@From Milestones/Tasks → reference Tasks → per-milestone scope (no cross-milestone dup → none)")

    @Test fun fromTasksStillFiresWithinAMilestone() =
        agree(render(Dsl.repetitionNotUnique(m.field(EFFORT)).from(m.group("/Project/Milestones/Tasks"))),
            listOf(listOf("5", "5"), listOf("7", "9")), "@From Milestones/Tasks → 5,5 within M1 still flagged (per-milestone)")

    private companion object {
        const val PHASE = "/Project/Milestones/Phase"
        const val TASK_ID = "/Project/Milestones/Tasks/TaskId"
        const val EFFORT = "/Project/Milestones/Tasks/Effort"
    }
}
