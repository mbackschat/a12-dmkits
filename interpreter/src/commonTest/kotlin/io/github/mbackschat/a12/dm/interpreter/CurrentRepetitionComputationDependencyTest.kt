package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.ComputationAlternative
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import kotlin.test.Test
import kotlin.test.assertEquals

/** A structural row-index read is not a computed-field dependency. */
class CurrentRepetitionComputationDependencyTest {

    private val lines = "/Order/Lines"
    private val base = "$lines/Base"
    private val first = "$lines/First"
    private val second = "$lines/Second"

    private val model = ModelDef(
        fields = listOf(
            FieldDef(base, Kind.NUMBER, scale = 0),
            FieldDef(first, Kind.NUMBER, scale = 0),
            FieldDef(second, Kind.NUMBER, scale = 0),
        ),
        rules = emptyList(),
        computations = listOf(
            guardedCopy(first),
            ComputationDef(second, lines, "[First]"),
        ),
        repeatableGroups = setOf(lines),
    )

    @Test
    fun structuralGuardDoesNotCycleWithARealDependentComputation() {
        val document = model.newDocument()
            .group("/Order", 1)
            .group(lines, 1, 1)
            .field(base, "7", 1, 1, 1)

        val computed = DmInterpreter(model).compute(document)

        assertEquals(listOf(first, second), computed.map { it.path })
        assertEquals(listOf("7", "7"), computed.map { it.value })
    }

    private fun guardedCopy(target: String) = ComputationDef(
        targetPath = target,
        group = lines,
        alternatives = listOf(ComputationAlternative(null, "[Base]")),
        commonPrecondition = "CurrentRepetition($lines) > 0",
    )
}
