package io.github.mbackschat.a12.dm.interpreter.retained

import io.github.mbackschat.a12.dm.interpreter.ComputationOutcome
import io.github.mbackschat.a12.dm.interpreter.ModelOwnerMismatchError
import io.github.mbackschat.a12.dm.interpreter.PreparedModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * JVM + Node lock for IF239's public retained-result boundary. The model is authored through modelkit/rulekit
 * in :adapter and embedded into commonTest, so both runtimes consume one maintained A12 model serialization.
 */
class RetainedComputationResultTest {

    @Test
    fun retainedClearCreatesTheMissingTargetAndRepeatableRow() {
        assertApplied(
            source = """{"Order":{"Lines":[{},{"Amount":9}]}}""",
            destination = """{"Order":{"Lines":[{},{}]}}""",
            expected = """{"Order":{"Lines":[{},{"Amount":null}]}}""",
            expectedAction = ComputationOutcome.CLEARED,
        )
        assertApplied(
            source = """{"Order":{"Lines":[{},{"Amount":9}]}}""",
            destination = "{}",
            expected = """{"Order":{"Lines":[{},{"Amount":null}]}}""",
            expectedAction = ComputationOutcome.CLEARED,
        )
    }

    @Test
    fun silenceErrorValueAndFilledClearKeepTheirSeparateApplicationRules() {
        assertApplied(
            source = """{"Order":{"Lines":[{},{}]}}""",
            destination = "{}",
            expected = "{}",
            expectedAction = null,
        )
        assertApplied(
            source = """{"Order":{"Lines":[{},{"Candidate":10}]}}""",
            destination = "{}",
            expected = "{}",
            expectedAction = ComputationOutcome.ERRORED,
        )
        assertApplied(
            source = """{"Order":{"Lines":[{},{"Candidate":7}]}}""",
            destination = "{}",
            expected = """{"Order":{"Lines":[{},{"Amount":7}]}}""",
            expectedAction = ComputationOutcome.VALUE,
        )
        assertApplied(
            source = """{"Order":{"Lines":[{},{"Amount":9}]}}""",
            destination = """{"Order":{"Lines":[{},{"Amount":8}]}}""",
            expected = """{"Order":{"Lines":[{},{"Amount":null}]}}""",
            expectedAction = ComputationOutcome.CLEARED,
        )
    }

    @Test
    fun classificationStaysSourceRelativeAndNeverRecomputesOnTheDestination() {
        val prepared = prepared()
        val source = prepared.documents.readDocument("""{"Order":{"Lines":[{},{"Amount":9}]}}""")
        val destination = prepared.documents.readDocument("""{"Order":{"Lines":[{},{"Candidate":7}]}}""")

        val report = prepared.createInterpreter().compute(source)
        val applied = report.applyTo(destination)

        assertEquals(
            """{"Order":{"Lines":[{},{"Amount":null,"Candidate":7}]}}""",
            prepared.documents.encodeDocument(applied),
            "the source-classified clear wins; applying must not recompute Candidate=7 on the destination",
        )
    }

    @Test
    fun aTypedSourceEqualNumberIsReportedButNotRetainedAsAnAction() {
        val prepared = prepared()
        val source = prepared.documents.readDocument(
            """{"Order":{"Lines":[{},{"Amount":7,"Candidate":7}]}}""",
        )
        val destination = prepared.documents.readDocument(
            """{"Order":{"Lines":[{},{"Amount":8}]}}""",
        )

        val report = prepared.createInterpreter().compute(source)
        val amountResult = report.results.single { it.pointer.path == AMOUNT && it.pointer.coordinates == CELL }

        assertEquals(ComputationOutcome.VALUE, amountResult.outcome)
        assertEquals(emptyList(), report.actions.filter { it.pointer.path == AMOUNT })
        assertSame(destination, report.applyTo(destination))
        assertEquals(
            """{"Order":{"Lines":[{},{"Amount":8}]}}""",
            prepared.documents.encodeDocument(destination),
        )
    }

    @Test
    fun compatibilityIsTheExistingPreparedOwnerBoundary() {
        val first = prepared()
        val second = prepared()
        val report = first.createInterpreter().compute(
            first.documents.readDocument("""{"Order":{"Lines":[{},{"Amount":9}]}}"""),
        )
        val foreign = second.documents.readDocument("{}")

        assertFailsWith<ModelOwnerMismatchError> {
            report.applyTo(foreign)
        }
    }

    @Test
    fun sameDocumentConvenienceIsExactlyRetainThenApplyAndLeavesTheSourceImmutable() {
        val prepared = prepared()
        val sourceJson = """{"Order":{"Lines":[{},{"Amount":9}]}}"""
        val source = prepared.documents.readDocument(sourceJson)
        val interpreter = prepared.createInterpreter()

        val explicit = interpreter.compute(source).applyTo(source)
        val convenience = interpreter.applyComputations(source)

        assertEquals(
            prepared.documents.encodeDocument(explicit),
            prepared.documents.encodeDocument(convenience),
        )
        assertEquals(sourceJson, prepared.documents.encodeDocument(source))
    }

    @Test
    fun invalidOperandCarriesItsFormalDiagnosticAndFailsTheComputationStatus() {
        val prepared = prepared()
        val report = prepared.createInterpreter().compute(
            prepared.documents.readDocument(
                """{"Order":{"Lines":[{},{"Candidate":5.1,"Amount":9}]}}""",
            ),
        )

        assertEquals(
            listOf("zahlHatDezimalTrenner@/Order[1]/Lines[2]/Candidate"),
            report.formalErrorsInOperands.map { "${it.code}@${it.errorPath}" },
        )
        assertEquals(
            ComputationOutcome.CLEARED,
            report.results.single { it.pointer.coordinates == CELL }.outcome,
        )
        assertFalse(report.noErrorOccurred)
        assertTrue(report.fullySupported, "diagnostics and unsupported-program reporting are independent")
    }

    @Test
    fun invalidComputedTargetCarriesItsDiagnosticAndFailsTheComputationStatus() {
        val prepared = prepared()
        val report = prepared.createInterpreter().compute(
            prepared.documents.readDocument(
                """{"Order":{"Lines":[{},{"Candidate":10}]}}""",
            ),
        )
        val result = report.results.single { it.pointer.coordinates == CELL }

        assertEquals(ComputationOutcome.ERRORED, result.outcome)
        assertEquals("10", result.value)
        assertEquals("zahlZuGross", result.diagnostic?.code)
        assertEquals("/Order[1]/Lines[2]/Amount", result.diagnostic?.errorPath)
        assertTrue(result.diagnostic?.text.orEmpty().isNotBlank())
        assertEquals(emptyList(), report.formalErrorsInOperands)
        assertFalse(report.noErrorOccurred)
        assertTrue(report.fullySupported)
    }

    @Test
    fun computedTargetAndValidationUseTheSameFormalDiagnosticProjection() {
        val prepared = prepared()
        val document = prepared.documents.readDocument(
            """{"Order":{"Lines":[{},{"Amount":10,"Candidate":10}]}}""",
        )
        val interpreter = prepared.createInterpreter()

        val computed = interpreter.compute(document).results
            .single { it.pointer.path == AMOUNT && it.pointer.coordinates == CELL }
            .diagnostic
        val validated = interpreter.validateFull(document).findings
            .single { it.code == "zahlZuGross" && it.errorPath == "/Order[1]/Lines[2]/Amount" }

        assertEquals(validated, computed)
    }

    @Test
    fun cleanComputationHasNoDiagnosticsAndReportsSuccess() {
        val prepared = prepared()
        val report = prepared.createInterpreter().compute(
            prepared.documents.readDocument(
                """{"Order":{"Lines":[{},{"Candidate":7}]}}""",
            ),
        )

        assertTrue(report.results.all { it.diagnostic == null })
        assertEquals(emptyList(), report.formalErrorsInOperands)
        assertTrue(report.noErrorOccurred)
    }

    @Test
    fun numericDomainFailureIsAFormalOperandDiagnosticOnTheComputedTarget() {
        val prepared = prepared()
        val report = prepared.createInterpreter().compute(
            prepared.documents.readDocument(
                """{"Order":{"Numerator":10,"Divisor":0}}""",
            ),
        )

        assertEquals(
            listOf("berechnungsWertFehler@/Order[1]/Quotient"),
            report.formalErrorsInOperands.map { "${it.code}@${it.errorPath}" },
        )
        assertEquals(ComputationOutcome.CLEARED, report.results.single().outcome)
        assertFalse(report.noErrorOccurred)
    }

    @Test
    fun selectedEmptyAndDuplicateIndexesCarryTheirGeneratedDiagnostics() {
        val prepared = prepared()
        val interpreter = prepared.createInterpreter()
        val empty = interpreter.compute(
            prepared.documents.readDocument(
                """{"Order":{"IndexedLines":[{"Source":3}]}}""",
            ),
        )
        assertEquals(
            listOf("mandatoryField@/Order[1]/IndexedLines[1]/Code"),
            empty.formalErrorsInOperands.map { "${it.code}@${it.errorPath}" },
        )

        val duplicate = interpreter.compute(
            prepared.documents.readDocument(
                """{"Order":{"IndexedLines":[{"Code":"A","Source":3},{"Code":"A","Source":4}]}}""",
            ),
        )
        assertEquals(
            listOf(
                "uniqueIndex@/Order[1]/IndexedLines[1]/Code",
                "uniqueIndex@/Order[1]/IndexedLines[2]/Code",
            ),
            duplicate.formalErrorsInOperands.map { "${it.code}@${it.errorPath}" },
        )
    }

    @Test
    fun formalOperandAndErroredTargetChannelsCanBothBePopulated() {
        val prepared = prepared()
        val report = prepared.createInterpreter().compute(
            prepared.documents.readDocument(
                """{"Order":{"Lines":[{"Candidate":10}],"Numerator":10,"Divisor":0}}""",
            ),
        )

        assertEquals(
            setOf("berechnungsWertFehler"),
            report.formalErrorsInOperands.mapTo(linkedSetOf()) { it.code },
        )
        assertEquals(
            setOf("zahlZuGross"),
            report.results.mapNotNullTo(linkedSetOf()) { it.diagnostic?.code },
        )
        assertFalse(report.noErrorOccurred)
    }

    private fun assertApplied(
        source: String,
        destination: String,
        expected: String,
        expectedAction: ComputationOutcome?,
    ) {
        val prepared = prepared()
        val sourceSnapshot = prepared.documents.readDocument(source)
        val destinationSnapshot = prepared.documents.readDocument(destination)
        val beforeSource = prepared.documents.encodeDocument(sourceSnapshot)
        val beforeDestination = prepared.documents.encodeDocument(destinationSnapshot)
        val report = prepared.createInterpreter().compute(sourceSnapshot)

        val targetActions = report.actions.filter { it.pointer.path == AMOUNT && it.pointer.coordinates == CELL }
        assertEquals(expectedAction?.let(::listOf) ?: emptyList(), targetActions.map { it.outcome })
        assertEquals(expected, prepared.documents.encodeDocument(report.applyTo(destinationSnapshot)))
        assertEquals(beforeSource, prepared.documents.encodeDocument(sourceSnapshot))
        assertEquals(beforeDestination, prepared.documents.encodeDocument(destinationSnapshot))
    }

    private fun prepared(): PreparedModel = PreparedModel.loadExpandedJson(RetainedComputationModelFixture.JSON)

    private companion object {
        const val AMOUNT = "/Order/Lines/Amount"
        val CELL = listOf(1, 2, 1)
    }
}
