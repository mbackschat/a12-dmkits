package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.model.Message
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ComputationDiagnosticAssemblyTest {
    @Test
    fun twoCodesAtOneFullPointerRefuseTheWholeEnvelope() {
        val first = message("testIDNummer", listOf(1, 1, 1))
        val second = message("uniqueIndex", listOf(1, 1, 1))
        assertFailsWith<IllegalStateException> {
            ComputationEngineRun(emptyList(), listOf(first, second), emptyMap(), emptyList())
        }
    }

    @Test
    fun operandAndTargetChannelsShareOnePointerUniquenessBoundary() {
        val first = message("PROJECT_REJECT", listOf(1, 1, 1))
        val second = message("testIDNummer", listOf(1, 1, 1))
        assertFailsWith<IllegalStateException> {
            ComputationEngineRun(emptyList(), listOf(first), mapOf((FIELD to listOf(1, 1, 1)) to second), emptyList())
        }
    }

    @Test
    fun equalCodesAndDistinctFullRowPointersRemainSeparate() {
        val first = message("uniqueIndex", listOf(1, 1, 1))
        val second = message("uniqueIndex", listOf(1, 2, 1))
        val result = ComputationEngineRun(emptyList(), listOf(first, second), emptyMap(), emptyList())
        assertEquals(listOf(first, second), result.formalErrorsInOperands)
    }

    private fun message(code: String, reps: List<Int>) = Message(
        code = code,
        rulePath = "/Parcel/Tags/CheckIdentifier",
        errorPointer = A12PartiallyKnownMultiPointer.of(FIELD, reps),
        severity = Severity.ERROR,
        type = MsgType.VALUE,
    )

    private companion object { const val FIELD = "/Parcel/Tags/Identifier" }
}
