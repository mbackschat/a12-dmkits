package io.github.mbackschat.a12.dm.cli

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The CLI face of a computation that fails result assembly (KERNEL-FINDINGS KF666): a leap day computed into a
 * day-month target under a common Base Year draws two diagnostics on one pointer, so `model compute` and the
 * compute-before-eval of `model eval` return no partial payload, while validation alone still runs. The same model
 * under a leap Base Year, differing in that member alone, computes. The kernel rows are
 * `adapter.laws.CalendarTargetCompletionDiffTest`.
 */
class ComputationDiagnosticAssemblyCliTest {
    @Test
    fun computeAndEvalFailAssemblyWhileValidationAloneStillRuns(@TempDir dir: Path) {
        val document = dir.resolve("plan.json")
        val input = """{"Plan":{"Cycle":"02-28"}}"""
        Files.writeString(document, input)
        val failing = model(dir, 2023)
        for (verb in listOf("compute", "eval")) {
            val result = CliTestSupport.run("-m", failing.toString(), "model", verb, "--instance", document.toString())
            assertEquals(2, result.code(), "$verb: ${result.out()}${result.err()}")
            assertEquals("", result.out(), "$verb must return no partial computation or evaluation payload")
            assertTrue(result.err().contains("Duplicate computation diagnostic"), "$verb: ${result.err()}")
            assertEquals(input, Files.readString(document), "$verb caller file is unchanged")
        }
        val validation = CliTestSupport.run("-m", failing.toString(), "model", "eval", "--instance", document.toString(),
            "--no-computations")
        assertEquals(0, validation.code(), validation.err())

        val leap = CliTestSupport.run("-m", model(dir, 2024).toString(), "model", "compute", "--instance", document.toString())
        assertEquals(0, leap.code(), leap.err())
        assertTrue(leap.out().contains("02-29"), leap.out())
    }

    private fun model(dir: Path, baseYear: Int): Path {
        val base = DocumentModelDraft.named("plan-cli-diagnostic-assembly-$baseYear").locale("en_US").baseYear(baseYear)
            .rootGroup("Plan") { p -> p.dateField("Cycle") { it.format("MM-dd") } }.build().toJson()
        val session = EditSession.read(base)
        val plan = session.group("/Plan")
        plan.addComputation(plan.date("Cycle")) {
            it.name("CycleComp").calc(Dsl.dateValue("29.02.2024").expression()).message(Dsl.en("Check the plan.")).build()
        }
        return dir.resolve("plan-$baseYear.dm.json").also { Files.writeString(it, session.toJson()) }
    }
}
