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
 * Runtime-invalid integral power is a numeric DOMAIN FAILURE, classified exactly like division-by-zero at the
 * numeric expression/target boundary (SPEC-2026-07-21-05; KERNEL-SEMANTICS "Arithmetic domain failures are
 * consumer-sensitive", IF16). `0` to a negative integral exponent and an integral exponent outside inclusive
 * `-1000..1000` produce the shared invalid-number sentinel: a rule comparison stays quiet (owned by
 * [DivPowDiffTest]), while a legal numeric computation keeps the no-value/CLEARED public delta yet marks the
 * Number target invalid (`berechnungsWertFehler`), so a dependent read poisons. Legal numeric wrappers preserve
 * the failure; valid boundary powers (`0 ^ 0`, exponent `±1000`) remain values. Fractional/unknown-scale
 * exponents stay static authoring errors (`MVK_INVALID_NUMBER_FOR_EXP`) and never reach this runtime path.
 */
class PowerDomainFailureComputationDiffTest {

    private val base = DocumentModelDraft.named("power-domain-failure").locale("en_US")
        .rootGroup("Calc") { group ->
            group.numberField("Result") { it.fractionDigits(2, 2) }
                .numberField("Dependent") { it.fractionDigits(2, 2) }
        }.build().toJson()

    @Test
    fun invalidIntegralPowerKeepsPublicDeltaButPoisonsADependentRead() {
        for (operation in listOf("RoundAccounting(0 ^ -1, 2)", "RoundAccounting(2 ^ 1001, 2)")) {
            val model = withDependent(operation)
            val fresh = docs(model) { }
            val stale = docs(model) {
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
            assertEquals(ComputeOutcome.CLEARED, interpreted[RESULT], "$operation: domain target keeps no-value outcome")
            assertEquals(ComputeOutcome.CLEARED, interpreted[DEPENDENT], "$operation: dependent read observes target poison")
        }
    }

    @Test
    fun legalNumericWrappersPreserveThePowerDomainFailure() {
        val operations = listOf(
            "RoundAccounting(Abs(0 ^ -1), 2)",
            "RoundAccounting(Min({2 ^ 1001}, 3), 2)",
            "RoundAccounting(Max(3, {0 ^ -1}), 2)",
        )

        for (operation in operations) {
            val model = oneComputation(operation)
            val documents = docs(model) { field(RESULT, "9.00", 1, 1) }

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
    fun validBoundaryPowersRemainValues() {
        val cases = mapOf(
            "RoundAccounting(0 ^ 0, 2)" to "1.00",
            "RoundAccounting(1 ^ 1000, 2)" to "1.00",
            "RoundAccounting(1 ^ -1000, 2)" to "1.00",
        )

        for ((operation, expected) in cases) {
            val model = oneComputation(operation)
            val documents = docs(model) { }

            assertKernelDetail(model, documents.kernel,
                expectedOutcomes = listOf("$RESULT|VALUE:$expected"),
                expectedFormal = emptyList())
            assertEquals(ComputeOutcome.VALUE,
                DmInterpreter(ModelLoader.load(model)).compute(documents.interpreter)
                    .single { it.path == RESULT }.outcome,
                operation)
        }
    }

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
    ): Pair<List<String>, List<String>> {
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
        return dynamic
    }
}

private const val ROOT = "/Calc"
private const val RESULT = "/Calc/Result"
private const val DEPENDENT = "/Calc/Dependent"
