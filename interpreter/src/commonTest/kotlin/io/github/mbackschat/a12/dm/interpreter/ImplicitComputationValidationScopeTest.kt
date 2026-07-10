package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.ComputationAlternative
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM + JS) lock for IF100 — the IMPLICIT computation-validation rule (IF32/IF65) anchors at
 * the computation's TARGET, not its declaring group: a computation declared in an EMPTY container group (a
 * real-workspace `*_Rules` root) targeting a field in another root must still validate the stored value
 * against its term — the declaring group's lack of instance content must not row-gate the check away (the
 * validation twin of IF89's write-scope rule). Kernel-verified by the tri-check differential
 * `adapter.laws.ImplicitComputationValidationScopeDiffTest`.
 */
class ImplicitComputationValidationScopeTest {

    private val HOURS = "/Employee/ContractedHours"
    private val START = "/Assignments/Assignment/StartDate"

    private val def = ModelDef(
        fields = listOf(
            FieldDef(HOURS, Kind.NUMBER, scale = 2),
            FieldDef(START, Kind.DATE),
            FieldDef("/HrRules/Note", Kind.STRING),
        ),
        rules = emptyList(),
        computations = listOf(ComputationDef(HOURS, "/HrRules",
            listOf(ComputationAlternative("NoFieldFilled(/Assignments/Assignment*/StartDate)", "-1")),
            name = "HoursComp")),
        repeatableGroups = setOf("/Assignments/Assignment"),
    )

    private fun fired(build: (io.github.mbackschat.a12.dm.interpreter.eval.Document) -> Unit): List<String> {
        val doc = def.newDocument()
        build(doc)
        return DmInterpreter(def).validateFull(doc).filter { it.code == "HoursComp" }.map { it.errorPath }.sorted()
    }

    @Test fun firesAtTheTargetDespiteTheEmptyDeclaringContainer() = assertEquals(
        listOf("/Employee[1]/ContractedHours"),
        fired { it.group("/Employee", 1).field(HOURS, "40.00", 1, 1) },
        "term −1 ≠ stored 40.00 — the implicit validation fires at the TARGET's scope")

    @Test fun silentWhenTheStoredValueMatchesTheTerm() = assertEquals(
        emptyList(),
        fired { it.group("/Employee", 1).field(HOURS, "-1", 1, 1) })

    @Test fun silentWhenTheTierDoesNotApply() = assertEquals(
        emptyList(),
        fired {
            it.group("/Employee", 1).field(HOURS, "40.00", 1, 1)
            it.group("/Assignments/Assignment", 1, 1).field(START, "2024-02-27", 1, 1, 1)
        })
}
