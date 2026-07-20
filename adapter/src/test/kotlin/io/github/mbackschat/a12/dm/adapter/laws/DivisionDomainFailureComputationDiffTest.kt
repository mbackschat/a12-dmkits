package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Division by zero is consumer-sensitive: comparison projects it to not-fired, while a legal rounded
 * computation keeps the domain failure as target invalidity. Its public delta remains no-value/CLEARED,
 * but a dependent read poisons and the kernel exposes berechnungsWertFehler in formalErrorsInOperands.
 */
class DivisionDomainFailureComputationDiffTest {

    private val base = DocumentModelDraft.named("domain-failure").locale("en_US")
        .rootGroup("Calc") { group ->
            group.numberField("Numerator") { }
                .numberField("Divisor") { }
                .numberField("Source") { }
                .numberField("Later") { }
                .numberField("Result") { it.fractionDigits(2, 2) }
                .numberField("Dependent") { it.fractionDigits(2, 2) }
                .dateField("StartDate") { it.format("yyyy-MM-dd") }
                .dateField("DateResult") { it.format("yyyy-MM-dd") }
        }.build().toJson()

    @Test
    fun roundedZeroDivisionKeepsPublicDeltaButPoisonsADependentRead() {
        val model = withDependent(domainOperation())
        val fresh = docs(model) {
            field(NUMERATOR, "10", 1, 1)
            field(DIVISOR, "0", 1, 1)
        }
        val stale = docs(model) {
            field(NUMERATOR, "10", 1, 1)
            field(DIVISOR, "0", 1, 1)
            field(RESULT, "9.00", 1, 1)
            field(DEPENDENT, "8.00", 1, 1)
        }

        assertKernelDetail(model, fresh.kernel, expectedOutcomes = emptyList(),
            expectedFormal = listOf("berechnungsWertFehler|/Calc[1]/Result"))
        assertKernelDetail(model, stale.kernel,
            expectedOutcomes = listOf("$DEPENDENT|CLEARED", "$RESULT|CLEARED"),
            expectedFormal = listOf("berechnungsWertFehler|/Calc[1]/Result"))

        val interpreted = DmInterpreter(ModelLoader.load(model)).compute(stale.interpreter)
            .associate { it.path to it.outcome }
        assertEquals(ComputeOutcome.CLEARED, interpreted[RESULT], "domain target keeps no-value report-all outcome")
        assertEquals(ComputeOutcome.CLEARED, interpreted[DEPENDENT], "dependent read observes target poison")
    }

    @Test
    fun aDomainFailedLeftSubtreeStillEvaluatesAndPoisonsOnTheRight() {
        val operation = "RoundAccounting({1 / 0} + [Later], 2)"
        val model = oneComputation(operation)
        val documents = docs(model) {
            field(LATER, "1.5", 1, 1)
            field(RESULT, "9.00", 1, 1)
        }

        assertKernelDetail(model, documents.kernel,
            expectedOutcomes = listOf("$RESULT|CLEARED"),
            expectedFormal = listOf("zahlHatDezimalTrenner|/Calc[1]/Later"))
        assertEquals(ComputeOutcome.CLEARED,
            DmInterpreter(ModelLoader.load(model)).compute(documents.interpreter).single { it.path == RESULT }.outcome)
    }

    @Test
    fun onePassDivisionLoweringSelectsTheLaterOperandPoisonFirst() {
        val operation = "RoundAccounting({[Source] / 2} * [Later], 2)"
        val model = oneComputation(operation)
        val documents = docs(model) {
            field(SOURCE, "bad", 1, 1)
            field(LATER, "1.5", 1, 1)
            field(RESULT, "9.00", 1, 1)
        }

        assertKernelDetail(model, documents.kernel,
            expectedOutcomes = listOf("$RESULT|CLEARED"),
            expectedFormal = listOf(
                "zahlHatDezimalTrenner|/Calc[1]/Later",
                "zahlHatUngueltigeZeichen|/Calc[1]/Source",
            ))
        assertEquals(ComputeOutcome.CLEARED,
            DmInterpreter(ModelLoader.load(model)).compute(documents.interpreter).single { it.path == RESULT }.outcome)
    }

    @Test
    fun legalNumericWrappersPreserveTheDomainFailureInEitherListPosition() {
        val operations = listOf(
            "RoundAccounting(Abs([Numerator] / [Divisor]), 2)",
            "RoundAccounting(Min({[Numerator] / [Divisor]}, 3), 2)",
            "RoundAccounting(Max(3, {[Numerator] / [Divisor]}), 2)",
        )

        for (operation in operations) {
            val model = oneComputation(operation)
            val documents = docs(model) {
                field(NUMERATOR, "10", 1, 1)
                field(DIVISOR, "0", 1, 1)
                field(RESULT, "9.00", 1, 1)
            }

            assertKernelDetail(model, documents.kernel,
                expectedOutcomes = listOf("$RESULT|CLEARED"),
                expectedFormal = listOf("berechnungsWertFehler|/Calc[1]/Result"))
            assertEquals(ComputeOutcome.CLEARED,
                DmInterpreter(ModelLoader.load(model)).compute(documents.interpreter)
                    .single { it.path == RESULT }.outcome,
                operation)
        }
    }

    @Test
    fun aDateShiftIntentionallyProjectsAnInvalidNumericAmountToAValuelessDate() {
        val model = RuntimeLaws.withTieredComputation(base, ROOT, "DateComp", "../DateResult", null,
            listOf(RuntimeLaws.CompAlt(null,
                "AddDays(StartDate, RoundAccounting([Numerator] / [Divisor]))")))
        val documents = docs(model) {
            field(NUMERATOR, "10", 1, 1)
            field(DIVISOR, "0", 1, 1)
            field(START_DATE, "2024-01-01", 1, 1)
            field(DATE_RESULT, "2024-01-09", 1, 1)
        }

        assertKernelDetail(model, documents.kernel,
            expectedOutcomes = listOf("$DATE_RESULT|CLEARED"),
            expectedFormal = emptyList())
        assertEquals(ComputeOutcome.CLEARED,
            DmInterpreter(ModelLoader.load(model)).compute(documents.interpreter)
                .single { it.path == DATE_RESULT }.outcome)
    }

    private fun domainOperation() = "RoundAccounting([Numerator] / [Divisor], 2)"

    private fun oneComputation(operation: String): String =
        RuntimeLaws.withTieredComputation(base, ROOT, "ResultComp", "../Result", null,
            listOf(RuntimeLaws.CompAlt(null, operation)))

    private fun withDependent(operation: String): String {
        val result = oneComputation(operation)
        return RuntimeLaws.withTieredComputation(result, ROOT, "DependentComp", "../Dependent", null,
            listOf(RuntimeLaws.CompAlt(null, "RoundAccounting([Result] + 1, 2)")))
    }

    private data class Documents(val kernel: RuntimeLaws.Doc, val interpreter: Document)

    private fun docs(model: String, build: Documents.() -> Unit): Documents {
        val docs = Documents(RuntimeLaws.doc().group(ROOT, 1), ModelLoader.load(model).newDocument().group(ROOT, 1))
        docs.build()
        return docs
    }

    private fun Documents.field(path: String, value: String, vararg reps: Int) {
        kernel.field(path, value, *reps)
        interpreter.field(path, value, *reps)
    }

    private fun assertKernelDetail(
        model: String,
        doc: RuntimeLaws.Doc,
        expectedOutcomes: List<String>,
        expectedFormal: List<String>,
    ) {
        fun detail(detail: RuntimeLaws.ComputationDetail): Pair<List<String>, List<String>> {
            val outcomes = detail.outcomes().map {
                "${it.path()}|${if (it.cleared()) "CLEARED" else if (it.errored()) "ERRORED" else "VALUE:${it.value().orElse("")}"}"
            }.sorted()
            val formal = detail.formalErrorsInOperands().map { "${it.code()}|${it.field()}" }
            return outcomes to formal
        }

        val dynamic = detail(RuntimeLaws.computeWithFormalErrors(model, doc))
        val staticEngine = RuntimeLaws.kernelJavaEngine(model)
        val static = detail(staticEngine.computeWithFormalErrors(staticEngine.instance(doc)))
        assertEquals(expectedOutcomes.sorted(), dynamic.first, "dynamic-Groovy public delta")
        assertEquals(expectedFormal, dynamic.second, "dynamic-Groovy formal operand errors")
        assertEquals(dynamic, static, "static-Java agrees with dynamic-Groovy")
    }
}

private const val ROOT = "/Calc"
private const val NUMERATOR = "/Calc/Numerator"
private const val DIVISOR = "/Calc/Divisor"
private const val SOURCE = "/Calc/Source"
private const val LATER = "/Calc/Later"
private const val RESULT = "/Calc/Result"
private const val DEPENDENT = "/Calc/Dependent"
private const val START_DATE = "/Calc/StartDate"
private const val DATE_RESULT = "/Calc/DateResult"
