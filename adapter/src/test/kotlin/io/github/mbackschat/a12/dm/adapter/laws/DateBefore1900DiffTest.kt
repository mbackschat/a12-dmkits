package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tri-engine runtime lock for modelkit's DateType `youngerThan1900Check` support and the interpreter's shared
 * stored/computed formal owner. A computed exact attempt before 1900 is retained but classified ERRORED; the
 * boundary itself is a VALUE. This is distinct from the always-on 1583-10-16 Gregorian floor.
 */
class DateBefore1900DiffTest {

    private val base = DocumentModelDraft.named("historic-date-check").locale("en_US")
        .rootGroup("Calendar") { group ->
            group.dateField("Source") { it.format(FORMAT) }
                .dateField("Target") { it.format(FORMAT).rejectBefore1900() }
        }
        .build().toJson()
    private val bound = BoundModel.ofJson(base)
    private val computation = bound.computation(bound.date(TARGET).field()).name("TargetComp")
        .calc(Dsl.addYears(bound.date(SOURCE), 0).expression())
        .message(Dsl.en("Copy the source date."))
        .build()
    private val modelJson = TypedRules.inject(base, computation)
    private val definition = ModelLoader.load(modelJson)

    @Test
    fun dateBefore1900IsAnErroredExactAttempt() =
        assertTri("31.12.1899", "ERRORED|31.12.1899")

    @Test
    fun dateAt1900BoundaryIsAValue() =
        assertTri("01.01.1900", "VALUE|01.01.1900")

    private fun assertTri(source: String, expected: String) {
        val dynamicDocument = RuntimeLaws.doc().group(ROOT, 1).field(SOURCE, source, 1, 1)
        val dynamic = kernelSignature(RuntimeLaws.compute(modelJson, dynamicDocument).single { it.path() == TARGET })

        val java = RuntimeLaws.kernelJavaEngine(modelJson)
        val generatedDocument = RuntimeLaws.doc().group(ROOT, 1).field(SOURCE, source, 1, 1)
        val generatedRaw = java.computeOutcomesSorted(java.instance(generatedDocument))
            .single { it.startsWith("$TARGET|") }
            .substringAfter('|')
        val generated = when (generatedRaw) {
            "ERR" -> "ERRORED"
            "CLEARED" -> "CLEARED"
            else -> "VALUE|$generatedRaw"
        }

        val interpreterDocument = definition.newDocument().group(ROOT, 1).field(SOURCE, source, 1, 1)
        val interpretedResult = DmInterpreter(definition).compute(interpreterDocument).single { it.path == TARGET }
        val interpreted = when (interpretedResult.outcome) {
            ComputeOutcome.VALUE -> "VALUE|${interpretedResult.value}"
            ComputeOutcome.ERRORED -> "ERRORED|${interpretedResult.value}"
            ComputeOutcome.CLEARED -> "CLEARED"
        }

        assertEquals(expected, dynamic, "dynamic-Groovy is normative")
        assertEquals(if (expected.startsWith("ERRORED|")) "ERRORED" else expected, generated, "generated Java")
        assertEquals(dynamic, interpreted, "kernel vs interpreter")
    }

    private fun kernelSignature(computed: RuntimeLaws.Computed): String = when {
        computed.cleared() -> "CLEARED"
        computed.errored() -> "ERRORED|${computed.value().orElse("")}"
        else -> "VALUE|${computed.value().orElse("")}"
    }

    private companion object {
        const val ROOT = "/Calendar"
        const val SOURCE = "/Calendar/Source"
        const val TARGET = "/Calendar/Target"
        const val FORMAT = "dd.MM.yyyy"
    }
}
