package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.KernelConsistencyOracle
import io.github.mbackschat.a12.dm.core.ModelReader
import io.github.mbackschat.a12.dm.core.Severity
import io.github.mbackschat.a12.dm.adapter.preparation.WorkspacePreparationFixtures
import io.github.mbackschat.a12.dm.interpreter.conformance.Op
import io.github.mbackschat.a12.dm.interpreter.conformance.Placement
import io.github.mbackschat.a12.dm.interpreter.conformance.PlacementKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * IF146 assembly lock: production include preparation is compared with kernel expansion, then the prepared
 * models execute through Groovy-dynamic, generated Java, and the interpreter. The fixture combines mount-depth
 * 0/1/n rules, a nested computation whose common/alternative/operation fragments use a pre-mount short name,
 * a bare root-group reference, and a legal absolute `$` correlation inside `Having`; its German peer proves the
 * configured stored vocabulary and lower/canonical/upper Boolean variants at the real Kernel boundary.
 */
class IncludeAssemblyStrategyDiffTest {

    @Test
    fun singleRootMountAgreesAcrossBothKernelStrategies() =
        agree("reference-family-single", modelRoot = false)

    @Test
    fun modelRootMountAgreesAcrossBothKernelStrategies() =
        agree("reference-family-model-root", modelRoot = true)

    @Test
    fun germanSingleRootMountAgreesAcrossBothKernelStrategies() =
        agree("reference-family-german-single", modelRoot = false)

    private fun agree(caseId: String, modelRoot: Boolean) {
        val fixture = WorkspacePreparationFixtures.caseById(caseId)
        val raw = fixture.entryJson()
        val resolver = fixture.resolver()
        val consistencyErrors = KernelConsistencyOracle()
            .checkConsistency(raw, resolver)
            .filter { it.severity() == Severity.ERROR }
        assertTrue(consistencyErrors.isEmpty(), "the real Kernel consistency gate rejected $caseId: $consistencyErrors")
        val kernel = CorpusModelPreparer.prepareKernelJson(raw, resolver)
        val interpreter = ModelReader.expand(raw, resolver).toJson()
        val placements = placements(
            modelRoot = modelRoot,
            repeatedMount = caseId == "reference-family-single",
        )

        val dynamicValidation = CorpusEngines.groovyDynamic(kernel, placements, Op.ValidateFull)
        val staticValidation = CorpusEngines.javaStatic(kernel, placements, Op.ValidateFull)
        val interpretedValidation = CorpusEngines.interpret(interpreter, placements, Op.ValidateFull)
        assertTrue(dynamicValidation.any { it.startsWith("OUTER|") }, "the mounted Having/$ rule must fire")
        assertTrue(dynamicValidation.any { it.startsWith("INDEXED|") }, "the mounted field-keyed index rule must fire")
        assertTrue(dynamicValidation.any { it.startsWith("ROOT|") }, "the mounted bare-root rule must fire")
        assertTrue(dynamicValidation.any { it.startsWith("LEVEL|") }, "the depth-1 rule must fire")
        assertTrue(dynamicValidation.any { it.startsWith("DEEP|") }, "the depth-n rule must fire")
        assertEquals(dynamicValidation, staticValidation)
        assertEquals(dynamicValidation, interpretedValidation)

        val dynamicCompute = CorpusEngines.groovyDynamic(kernel, placements, Op.Compute)
        val staticCompute = CorpusEngines.javaStatic(kernel, placements, Op.Compute)
        val interpretedCompute = CorpusEngines.interpret(interpreter, placements, Op.Compute)
        assertTrue(dynamicCompute.any { it.endsWith("/Total|VALUE|5") },
            "the short-name-bound nested computation must produce a value: $dynamicCompute")
        assertEquals(dynamicCompute, staticCompute)
        assertEquals(dynamicCompute, interpretedCompute)
    }

    private fun placements(
        modelRoot: Boolean,
        repeatedMount: Boolean,
    ): List<Placement> {
        fun group(path: String, reps: List<Int>) = Placement(PlacementKind.GROUP, path, reps)
        fun field(path: String, value: String, reps: List<Int>) =
            Placement(PlacementKind.FIELD, path, reps + 1, value)
        fun provider(root: String, rootReps: List<Int>): List<Placement> = listOf(
            field("$root/Threshold", "5", rootReps),
            field("$root/Note", "present", rootReps),
            group("$root/Lines", rootReps + 1),
            field("$root/Lines/Key", "present", rootReps + 1),
            field("$root/Lines/Amount", "5", rootReps + 1),
            group("$root/Level", rootReps + 1),
            field("$root/Level/Value", "-1", rootReps + 1),
            group("$root/Level/Deep", rootReps + listOf(1, 1)),
            field("$root/Level/Deep/Marker", "-1", rootReps + listOf(1, 1)),
        )

        val host = listOf(
            group("/Policy", listOf(1)),
            field("/Policy/Threshold", "99", listOf(1)),
        )
        return if (modelRoot) {
            val root = "/Policy/Cover/Benefits"
            val rootReps = listOf(1, 1, 1)
            host +
                group("/Policy/Cover", listOf(1, 1)) +
                group(root, rootReps) +
                provider(root, rootReps)
        } else {
            val rootReps = listOf(1, 1)
            val roots = if (repeatedMount) {
                listOf("/Policy/Cover", "/Policy/Renewal")
            } else {
                listOf("/Policy/Cover")
            }
            host + roots.flatMap { root ->
                listOf(group(root, rootReps)) + provider(root, rootReps)
            }
        }
    }
}
