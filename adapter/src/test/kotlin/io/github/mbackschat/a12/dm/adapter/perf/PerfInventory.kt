package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.adapter.perf.Complexity.LINEAR
import io.github.mbackschat.a12.dm.adapter.perf.PerfOp.COMPUTE
import io.github.mbackschat.a12.dm.adapter.perf.PerfOp.VALIDATE
import io.github.mbackschat.a12.dm.adapter.perf.ResultRegime.COMPUTE_VALUE
import io.github.mbackschat.a12.dm.adapter.perf.ResultRegime.VALIDATION_FIRING
import io.github.mbackschat.a12.dm.adapter.perf.ResultRegime.VALIDATION_LOW_FIRING
import io.github.mbackschat.a12.dm.adapter.perf.ResultRegime.VALIDATION_MIXED
import io.github.mbackschat.a12.dm.interpreter.model.ModelTimeZone

/** The closed set of reviewed default benchmark scenarios. */
enum class BenchmarkScenario(val id: String) {
    B1("B1-per-row"),
    B2("B2-star-aggregate"),
    B3("B3-nested-per-sub"),
    B4("B4-cross-level-ref"),
    B5("B5-deep-nested-star"),
    B6("B6-many-rules"),
    B7("B7-compute"),
    B8("B8-combined"),
    B9_BERLIN("B9-berlin-datetime"),
    B9_UTC("B9-utc-control"),
    TIERED_COMPUTE("tiered-compute"),
    PARALLEL_ITERATION("parallel-iteration"),
}

/**
 * Exhaustive disposition of a closed-catalog case. An exclusion is visible catalog state and must explain why
 * the case cannot participate; silently omitting a catalog entry is not representable.
 */
sealed interface InventoryDisposition {
    data class Included(val case: InventoryCase) : InventoryDisposition
    data class Excluded(val reason: String) : InventoryDisposition {
        init {
            require(reason.isNotBlank()) { "an inventory exclusion must explain why the case is not benchmarked" }
        }
    }
}

/** One reviewed benchmark contract plus its scenario factory. */
class InventoryCase(
    val id: ScenarioId,
    val n: Int,
    private val scenarioFactory: () -> PerfScenario,
) {
    internal fun unboundScenario(): PerfScenario = scenarioFactory()
    fun scenario(): PerfScenario = id.bind(unboundScenario(), n)
}

/**
 * The reviewed default scenario inventory. [BenchmarkScenario] is closed and [disposition] is exhaustive, so
 * adding a catalog entry cannot compile until it is included or explicitly excluded. Each included row stores
 * its content fingerprint beside its version; the scenario returned to the harness owns that bound identity.
 */
object PerfInventory {

    val dispositions: Map<BenchmarkScenario, InventoryDisposition> =
        BenchmarkScenario.entries.associateWith(::disposition)

    val cases: List<InventoryCase> = dispositions.values.mapNotNull {
        when (it) {
            is InventoryDisposition.Included -> it.case
            is InventoryDisposition.Excluded -> null
        }
    }

    /** Select the complete inventory or exactly one scenario id; an unmatched requested id is an error. */
    fun select(requestedId: String?): List<InventoryCase> {
        if (requestedId == null) return cases
        val selected = cases.filter { it.id.id == requestedId }
        require(selected.size == 1) {
            "unknown benchmark scenario '$requestedId'; expected one of ${cases.joinToString { it.id.id }}"
        }
        return selected
    }

    private fun disposition(scenario: BenchmarkScenario): InventoryDisposition = when (scenario) {
        BenchmarkScenario.B1 -> include(
            ScenarioId.builder(scenario.id)
                .operation(VALIDATE).resultRegime(VALIDATION_MIXED).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.b1", parameters = mapOf("rows" to "flat", "values" to "perf-v1")))
                .scaleAxis("line-rows").expectedResultCardinality(listOf(1_470))
                .reviewedFingerprint("988d9acaed29cd5a057fe1f1a7ff2172363e40726e1efdce0fa9e12f1e1ad8e8").build(),
            3_000,
            PerfCatalog::b1,
        )

        BenchmarkScenario.B2 -> include(
            ScenarioId.builder(scenario.id)
                .operation(VALIDATE).resultRegime(VALIDATION_FIRING).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.b2", parameters = mapOf("rows" to "flat", "values" to "perf-v1")))
                .scaleAxis("line-rows").expectedResultCardinality(listOf(1))
                .reviewedFingerprint("a1a16b388757b4facd1034c564e6512ab161c95f38860686ba81f50d72a82ef1").build(),
            3_000,
            PerfCatalog::b2,
        )

        BenchmarkScenario.B3 -> include(
            ScenarioId.builder(scenario.id)
                .operation(VALIDATE).resultRegime(VALIDATION_MIXED).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.b3", parameters = mapOf("subs-per-line" to "5", "values" to "perf-v1")))
                .scaleAxis("line-rows").expectedResultCardinality(listOf(1_434))
                .reviewedFingerprint("dd60c53f7878feb274e204800dafda1afaaa08e9d2075d19d918dcf4e6250f81").build(),
            600,
            PerfCatalog::b3,
        )

        BenchmarkScenario.B4 -> include(
            ScenarioId.builder(scenario.id)
                .operation(VALIDATE).resultRegime(VALIDATION_MIXED).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.b4", parameters = mapOf("subs-per-line" to "5", "values" to "perf-v1")))
                .scaleAxis("line-rows").expectedResultCardinality(listOf(1_152))
                .reviewedFingerprint("f5051372ffbc710946378d5b65fd29b4e780237e6314fffd3e0f96f3e2fb8c47").build(),
            600,
            PerfCatalog::b4,
        )

        BenchmarkScenario.B5 -> include(
            ScenarioId.builder(scenario.id)
                .operation(VALIDATE).resultRegime(VALIDATION_FIRING).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.b5", parameters = mapOf("subs-per-line" to "5", "values" to "perf-v1")))
                .scaleAxis("line-rows").expectedResultCardinality(listOf(1))
                .reviewedFingerprint("0fcea35fdfc68994b3436f892db75ae82209d4ca52894171d5e8ba862dfe7553").build(),
            600,
            PerfCatalog::b5,
        )

        BenchmarkScenario.B6 -> include(
            ScenarioId.builder(scenario.id)
                .operation(VALIDATE).resultRegime(VALIDATION_MIXED).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.b6", parameters = mapOf("subs-per-line" to "5", "rules" to "12", "values" to "perf-v1")))
                .scaleAxis("line-rows").expectedResultCardinality(listOf(3_548))
                .reviewedFingerprint("65bd844d487a1c9b9fb0c5c459545d71885da1a01d40036323f58adf73924531").build(),
            600,
            PerfCatalog::b6,
        )

        BenchmarkScenario.B7 -> include(
            ScenarioId.builder(scenario.id)
                .operation(COMPUTE).resultRegime(COMPUTE_VALUE).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.b7", parameters = mapOf("subs-per-line" to "5", "values" to "compute-v1")))
                .scaleAxis("line-rows").expectedResultCardinality(listOf(600))
                .reviewedFingerprint("e859c7ebc01f150a7ebf2638ebd060c7c37c69e38fcab88d290d43868142b20c").build(),
            600,
            PerfCatalog::b7,
        )

        BenchmarkScenario.B8 -> include(
            ScenarioId.builder(scenario.id)
                .operation(VALIDATE).resultRegime(VALIDATION_LOW_FIRING).complexity(LINEAR)
                .generator(GeneratorIdentity(
                    "PerfCatalog.complexRealWorldFull",
                    parameters = mapOf(
                        "charges-per-visit" to ComplexRealWorld.CHARGES_PER_VISIT.toString(),
                        "firing" to "false",
                        "shape" to "full",
                        "values" to "complex-v1",
                        "visits-per-claim" to ComplexRealWorld.VISITS_PER_CLAIM.toString(),
                    ),
                ))
                .scaleAxis("claim-rows").expectedResultCardinality(listOf(0))
                .reviewedFingerprint("62dadf1b8234126d4aac67d80f7e62ba18be72de0e2836fc5e05da9c5e731f60").build(),
            500,
            { PerfCatalog.complexRealWorldFull(firing = false) },
        )

        BenchmarkScenario.B9_BERLIN -> include(
            ScenarioId.builder(scenario.id)
                .operation(VALIDATE).resultRegime(VALIDATION_FIRING).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.berlinDateTime", parameters = mapOf("values" to "datetime-v1")))
                .scaleAxis("run-rows").expectedResultCardinality(listOf(3_000))
                .reviewedFingerprint("887b25d8400f3d8bf7f463e5960bc4b618890f4cadbefe3c475fb352db9cb905")
                .zone(ModelTimeZone.EUROPE_BERLIN).build(),
            3_000,
            PerfCatalog::berlinDateTime,
        )

        BenchmarkScenario.B9_UTC -> include(
            ScenarioId.builder(scenario.id)
                .operation(VALIDATE).resultRegime(VALIDATION_FIRING).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.utcDateTimeControl", parameters = mapOf("values" to "datetime-v1")))
                .scaleAxis("run-rows").expectedResultCardinality(listOf(3_000))
                .reviewedFingerprint("2afd9ac52577c133a8e6efb4f774c3105530525d96b8946d1964b9bde98dddf7").build(),
            3_000,
            PerfCatalog::utcDateTimeControl,
        )

        BenchmarkScenario.TIERED_COMPUTE -> include(
            ScenarioId.builder(scenario.id)
                .operation(COMPUTE).resultRegime(COMPUTE_VALUE).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.tieredComputation", parameters = mapOf("tiers" to "2", "values" to "tiered-v1")))
                .scaleAxis("line-rows").expectedResultCardinality(listOf(3_000))
                .reviewedFingerprint("26e0297ed9b783b96a6e8d1e242d491a00b3ff40b2f2bf5382fe77c90561147b").build(),
            3_000,
            PerfCatalog::tieredComputation,
        )

        BenchmarkScenario.PARALLEL_ITERATION -> include(
            ScenarioId.builder(scenario.id)
                .operation(VALIDATE).resultRegime(VALIDATION_MIXED).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.parallel", parameters = mapOf("groups" to "A+B", "values" to "parallel-v1")))
                .scaleAxis("rows-per-group").expectedResultCardinality(listOf(1_000))
                .reviewedFingerprint("04ad5b24df312dc0bf70a93d924502cd5485e2cf91914ae5463e06eb600087a7").build(),
            2_000,
            PerfCatalog::parallel,
        )
    }

    private fun include(id: ScenarioId, n: Int, scenarioFactory: () -> PerfScenario): InventoryDisposition =
        InventoryDisposition.Included(InventoryCase(id, n, scenarioFactory))
}
