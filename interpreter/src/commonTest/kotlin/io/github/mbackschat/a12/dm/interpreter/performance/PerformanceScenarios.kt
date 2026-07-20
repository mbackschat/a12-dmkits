package io.github.mbackschat.a12.dm.interpreter.performance

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef

internal enum class PerformanceHost { JVM, NODE }

internal enum class PerformanceMode { FIRST_ANSWER, WARM_THROUGHPUT }

internal enum class PerformanceModel { B1, B2, MANY_RULES_48, MANY_RULES_192 }

internal enum class PerformanceScenario(
    val id: String,
    val version: Int,
    val host: PerformanceHost,
    val mode: PerformanceMode,
    val model: PerformanceModel,
    val scales: List<Int>,
    val workloadIdentity: String,
) {
    JVM_48_RULES_FIRST_ANSWER(
        "jvm-48-rules-500-rows-first-answer", 1, PerformanceHost.JVM, PerformanceMode.FIRST_ANSWER,
        PerformanceModel.MANY_RULES_48, listOf(100), "low-firing:nested-lines=100:subs-per-line=5:v1",
    ),
    JVM_192_RULES_FIRST_ANSWER(
        "jvm-192-rules-500-rows-first-answer", 1, PerformanceHost.JVM, PerformanceMode.FIRST_ANSWER,
        PerformanceModel.MANY_RULES_192, listOf(100), "low-firing:nested-lines=100:subs-per-line=5:v1",
    ),
    JVM_100K_ROWS_FIRST_ANSWER(
        "jvm-b1-100000-rows-first-answer", 1, PerformanceHost.JVM, PerformanceMode.FIRST_ANSWER,
        PerformanceModel.B1, listOf(100_000), "b1-firing:flat-lines=100000:v1",
    ),
    NODE_10K_ROWS_FIRST_ANSWER(
        "node-b1-10000-rows-first-answer", 1, PerformanceHost.NODE, PerformanceMode.FIRST_ANSWER,
        PerformanceModel.B1, listOf(10_000), "b1-firing:flat-lines=10000:v1",
    ),
    JVM_B1_ABSOLUTE_THROUGHPUT(
        "jvm-b1-100000-rows-warm-throughput", 1, PerformanceHost.JVM, PerformanceMode.WARM_THROUGHPUT,
        PerformanceModel.B1, listOf(100_000), "b1-firing:flat-lines=100000:warm:v1",
    ),
    NODE_B1_THROUGHPUT(
        "node-b1-2500-10000-rows-warm-throughput", 1, PerformanceHost.NODE, PerformanceMode.WARM_THROUGHPUT,
        PerformanceModel.B1, listOf(2_500, 10_000), "b1-firing:paired-2500-10000:warm:v1",
    ),
    NODE_B2_THROUGHPUT(
        "node-b2-2500-10000-rows-warm-throughput", 1, PerformanceHost.NODE, PerformanceMode.WARM_THROUGHPUT,
        PerformanceModel.B2, listOf(2_500, 10_000), "b2-star:paired-2500-10000:warm:v1",
    );

    companion object {
        fun fromId(id: String): PerformanceScenario =
            entries.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("unknown performance scenario '$id'")
    }
}

internal data class PerformanceSemanticResult(
    val supported: Boolean,
    val cardinality: Int,
    val outcome: String,
)

internal class PerformanceFixture(
    val model: ModelDef,
    val document: Document,
    val contentDefinition: String,
    private val expected: (PerformanceSemanticResult) -> Boolean,
) {
    fun evaluate(interpreter: DmInterpreter): PerformanceSemanticResult {
        val result = interpreter.validate(document)
        val codes = result.messages.map { it.code }.distinct()
        val semantic = PerformanceSemanticResult(
            supported = result.unsupported.isEmpty(),
            cardinality = result.messages.size,
            outcome = "codes=${codes.joinToString(",")}",
        )
        require(expected(semantic)) { "performance scenario semantic mismatch: $semantic" }
        return semantic
    }
}

internal object PerformanceScenarios {

    fun fixture(scenario: PerformanceScenario, scale: Int): PerformanceFixture {
        require(scale in scenario.scales) { "${scenario.id}: scale $scale is not declared in ${scenario.scales}" }
        val modelJson = PerformanceModelFixtures.jsonFor(scenario.model)
        val model = ModelLoader.load(modelJson)
        val document = model.newDocument()
        when (scenario.model) {
            PerformanceModel.B1, PerformanceModel.B2 -> populateFlat(document, scale)
            PerformanceModel.MANY_RULES_48, PerformanceModel.MANY_RULES_192 ->
                populateNestedLowFiring(document, scale)
        }
        val expectedCardinality = expectedCardinality(scenario.model, scale)
        val expectedOutcome = expectedOutcome(scenario.model)
        return PerformanceFixture(
            model,
            document,
            "$modelJson\nscenario=${scenario.id}@${scenario.version}\nworkload=${scenario.workloadIdentity}",
        ) { result ->
            result.supported && result.cardinality == expectedCardinality && result.outcome == expectedOutcome
        }
    }

    private fun populateFlat(document: Document, rows: Int) {
        repeat(rows) { zeroBased ->
            val row = zeroBased + 1
            document.field(LINE_KEY, "K1_${row}_1", 1, row, 1)
                .field(QTY, (row % 100).toString(), 1, row, 1)
                .field(AMOUNT, "1", 1, row, 1)
        }
    }

    private fun populateNestedLowFiring(document: Document, lines: Int) {
        repeat(lines) { zeroBasedLine ->
            val line = zeroBasedLine + 1
            document.field(LINE_KEY, "K1_${line}_1", 1, line, 1)
                .field(QTY, "1", 1, line, 1)
                .field(AMOUNT, "1", 1, line, 1)
            repeat(SUBS_PER_LINE) { zeroBasedSub ->
                val sub = zeroBasedSub + 1
                document.field(SUB_KEY, "K1_${line}_${sub}_1", 1, line, sub, 1)
                    .field(COST, "1", 1, line, sub, 1)
            }
        }
    }

    private fun expectedCardinality(model: PerformanceModel, scale: Int): Int = when (model) {
        PerformanceModel.B1 -> scale / 100 * 49
        PerformanceModel.B2 -> 1
        PerformanceModel.MANY_RULES_48, PerformanceModel.MANY_RULES_192 -> 0
    }

    private fun expectedOutcome(model: PerformanceModel): String = when (model) {
        PerformanceModel.B1 -> "codes=HQ"
        PerformanceModel.B2 -> "codes=TP"
        PerformanceModel.MANY_RULES_48, PerformanceModel.MANY_RULES_192 -> "codes="
    }

    private const val LINE_KEY = "/Perf/Lines/LineKey"
    private const val QTY = "/Perf/Lines/Qty"
    private const val AMOUNT = "/Perf/Lines/Amount"
    private const val SUB_KEY = "/Perf/Lines/Subs/SubKey"
    private const val COST = "/Perf/Lines/Subs/Cost"
    private const val SUBS_PER_LINE = 5
}
