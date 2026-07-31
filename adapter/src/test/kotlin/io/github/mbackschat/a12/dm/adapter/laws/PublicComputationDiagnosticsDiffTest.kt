package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.PreparedModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * IAG9 — the native public computation report retains the Kernel's explanatory channels while keeping its
 * deliberate report-all result granularity. The typed-authored IF239 fixture supplies ordinary operands, an
 * indexed computation, a domain failure, and a bounded target. Dynamic Groovy is normative; generated Java
 * must agree independently before the public Kotlin/JVM projection is compared.
 */
class PublicComputationDiagnosticsDiffTest {

    private data class Case(
        val label: String,
        val documentJson: String,
        val kernelDocument: RuntimeLaws.Doc,
    )

    private val model = RetainedComputationFixture.modelJson()
    private val prepared = PreparedModel.loadExpandedJson(model)
    private val generated = RuntimeLaws.kernelJavaEngine(model)

    @Test
    fun publicDiagnosticsMatchBothKernelStrategiesAcrossEveryResultChannel() {
        cases().forEach { case ->
            val dynamic = RuntimeLaws.resultChannels(model, case.kernelDocument)
            val static = generated.resultChannels(generated.instance(case.kernelDocument))
            assertEquals(
                signatures(dynamic.formalErrorsInOperands()),
                signatures(static.formalErrorsInOperands()),
                "${case.label}: formal operand diagnostics differ between Kernel strategies",
            )
            assertEquals(
                signatures(dynamic.computedErrors()),
                signatures(static.computedErrors()),
                "${case.label}: computed-target diagnostics differ between Kernel strategies",
            )
            assertEquals(
                dynamic.noErrorOccurred(),
                static.noErrorOccurred(),
                "${case.label}: computation status differs between Kernel strategies",
            )

            val report = prepared.createInterpreter().compute(
                prepared.documents.readDocument(case.documentJson),
            )
            assertEquals(
                signatures(dynamic.formalErrorsInOperands()),
                report.formalErrorsInOperands.map { "${it.code}@${it.errorPath}" }.sorted(),
                "${case.label}: public formal operand diagnostics",
            )
            assertEquals(
                signatures(dynamic.computedErrors()),
                report.results.mapNotNull { it.diagnostic }.map { "${it.code}@${it.errorPath}" }.sorted(),
                "${case.label}: public computed-target diagnostics",
            )
            assertEquals(
                dynamic.noErrorOccurred(),
                report.noErrorOccurred,
                "${case.label}: public computation status",
            )
        }
    }

    private fun cases(): List<Case> = listOf(
        Case(
            "clean",
            """{"Order":{"Lines":[{"Candidate":7}]}}""",
            RuntimeLaws.doc().group(ROOT, 1)
                .group(LINES, 1, 1)
                .field(CANDIDATE, "7", 1, 1, 1),
        ),
        Case(
            "invalid ordinary operand",
            """{"Order":{"Lines":[{"Candidate":5.1,"Amount":9}]}}""",
            RuntimeLaws.doc().group(ROOT, 1)
                .group(LINES, 1, 1)
                .field(CANDIDATE, "5.1", 1, 1, 1)
                .field(AMOUNT, "9", 1, 1, 1),
        ),
        Case(
            "domain failure",
            """{"Order":{"Numerator":10,"Divisor":0,"Quotient":9.00}}""",
            RuntimeLaws.doc().group(ROOT, 1)
                .field(NUMERATOR, "10", 1, 1)
                .field(DIVISOR, "0", 1, 1)
                .field(QUOTIENT, "9.00", 1, 1),
        ),
        Case(
            "invalid computed target",
            """{"Order":{"Lines":[{"Candidate":10}]}}""",
            RuntimeLaws.doc().group(ROOT, 1)
                .group(LINES, 1, 1)
                .field(CANDIDATE, "10", 1, 1, 1),
        ),
        Case(
            "selected empty index",
            """{"Order":{"IndexedLines":[{"Source":3}]}}""",
            RuntimeLaws.doc().group(ROOT, 1)
                .group(INDEXED_LINES, 1, 1)
                .field(SOURCE, "3", 1, 1, 1),
        ),
        Case(
            "selected duplicate index",
            """{"Order":{"IndexedLines":[{"Code":"A","Source":3},{"Code":"A","Source":4}]}}""",
            RuntimeLaws.doc().group(ROOT, 1)
                .group(INDEXED_LINES, 1, 1)
                .field(CODE, "A", 1, 1, 1)
                .field(SOURCE, "3", 1, 1, 1)
                .group(INDEXED_LINES, 1, 2)
                .field(CODE, "A", 1, 2, 1)
                .field(SOURCE, "4", 1, 2, 1),
        ),
        Case(
            "formal operand and errored target",
            """{"Order":{"Lines":[{"Candidate":10}],"Numerator":10,"Divisor":0}}""",
            RuntimeLaws.doc().group(ROOT, 1)
                .group(LINES, 1, 1)
                .field(CANDIDATE, "10", 1, 1, 1)
                .field(NUMERATOR, "10", 1, 1)
                .field(DIVISOR, "0", 1, 1),
        ),
    )

    private fun signatures(messages: List<RuntimeLaws.Msg>): List<String> =
        messages.map { "${it.code()}@${it.field()}" }.sorted()

    private companion object {
        const val ROOT = "/Order"
        const val LINES = "$ROOT/Lines"
        const val CANDIDATE = "$LINES/Candidate"
        const val AMOUNT = "$LINES/Amount"
        const val NUMERATOR = "$ROOT/Numerator"
        const val DIVISOR = "$ROOT/Divisor"
        const val QUOTIENT = "$ROOT/Quotient"
        const val INDEXED_LINES = "$ROOT/IndexedLines"
        const val CODE = "$INDEXED_LINES/Code"
        const val SOURCE = "$INDEXED_LINES/Source"
    }
}
