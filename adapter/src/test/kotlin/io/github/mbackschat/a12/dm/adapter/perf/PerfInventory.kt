package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.adapter.perf.Complexity.LINEAR
import io.github.mbackschat.a12.dm.adapter.perf.PerfOp.COMPUTE
import io.github.mbackschat.a12.dm.adapter.perf.PerfOp.VALIDATE
import io.github.mbackschat.a12.dm.adapter.perf.ResultRegime.COMPUTE_CLEARED
import io.github.mbackschat.a12.dm.adapter.perf.ResultRegime.COMPUTE_ERRORED
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
    B6_LOW_FIRING("B6-many-rules-low-firing"),
    B7("B7-compute"),
    COMPUTE_CLEARED("compute-cleared"),
    COMPUTE_ERRORED("compute-errored"),
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
                .version(4)
                .operation(VALIDATE).resultRegime(VALIDATION_MIXED).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.b1", parameters = mapOf("rows" to "flat", "values" to "perf-v1")))
                .scaleAxis("line-rows").expectedResultCardinality(listOf(1_470))
                .reviewedFingerprint("d57dab001087f51230af51ba24fa82516a9f45023d61d832e48ccf65de5d079a").build(),
            3_000,
            PerfCatalog::b1,
        )

        BenchmarkScenario.B2 -> include(
            ScenarioId.builder(scenario.id)
                .version(4)
                .operation(VALIDATE).resultRegime(VALIDATION_FIRING).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.b2", parameters = mapOf("rows" to "flat", "values" to "perf-v1")))
                .scaleAxis("line-rows").expectedResultCardinality(listOf(1))
                .reviewedFingerprint("15b34a4c7940b5c347640f669bbdcf0932c481b2e7c78f0dfb72b32eb67b25d0").build(),
            3_000,
            PerfCatalog::b2,
        )

        BenchmarkScenario.B3 -> include(
            ScenarioId.builder(scenario.id)
                .version(4)
                .operation(VALIDATE).resultRegime(VALIDATION_MIXED).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.b3", parameters = mapOf("subs-per-line" to "5", "values" to "perf-v1")))
                .scaleAxis("line-rows").expectedResultCardinality(listOf(1_434))
                .reviewedFingerprint("e149817d6906824bfe94bc20c9d2869ef0f8b9335fe7d7b11af24d6cc709ba56").build(),
            600,
            PerfCatalog::b3,
        )

        BenchmarkScenario.B4 -> include(
            ScenarioId.builder(scenario.id)
                .version(4)
                .operation(VALIDATE).resultRegime(VALIDATION_MIXED).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.b4", parameters = mapOf("subs-per-line" to "5", "values" to "perf-v1")))
                .scaleAxis("line-rows").expectedResultCardinality(listOf(1_152))
                .reviewedFingerprint("d66789dd5f72126ade3f7ad35e29408858fea38ffb1b9316d65c7bfffaa5e2dc").build(),
            600,
            PerfCatalog::b4,
        )

        BenchmarkScenario.B5 -> include(
            ScenarioId.builder(scenario.id)
                .version(4)
                .operation(VALIDATE).resultRegime(VALIDATION_FIRING).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.b5", parameters = mapOf("subs-per-line" to "5", "values" to "perf-v1")))
                .scaleAxis("line-rows").expectedResultCardinality(listOf(1))
                .reviewedFingerprint("0dc9719322dd872fa2aa58e2776c0ef7c8b5d77feae1b3e04200673db850f828").build(),
            600,
            PerfCatalog::b5,
        )

        BenchmarkScenario.B6 -> include(
            ScenarioId.builder(scenario.id)
                .version(4)
                .operation(VALIDATE).resultRegime(VALIDATION_MIXED).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.b6", parameters = mapOf("subs-per-line" to "5", "rules" to "12", "values" to "perf-v1")))
                .scaleAxis("line-rows").expectedResultCardinality(listOf(3_548))
                .reviewedFingerprint("40e8cec564db4c47541f803643fd725b9b001d1ed3012f689866577143de104b").build(),
            600,
            PerfCatalog::b6,
        )

        BenchmarkScenario.B6_LOW_FIRING -> include(
            ScenarioId.builder(scenario.id)
                .version(4)
                .operation(VALIDATE).resultRegime(VALIDATION_LOW_FIRING).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.manyRules", parameters = mapOf(
                    "firing" to "false",
                    "rules" to "12",
                    "values" to "low-firing-v1",
                )))
                .scaleAxis("line-rows").expectedResultCardinality(listOf(0))
                .reviewedFingerprint("fa70f193c40bbf4929fa71463f16d32b218c148678c3b1eea18a7d0b6d17c74d").build(),
            600,
            { PerfCatalog.manyRules(12, firing = false) },
        )

        BenchmarkScenario.B7 -> include(
            ScenarioId.builder(scenario.id)
                .version(4)
                .operation(COMPUTE).resultRegime(COMPUTE_VALUE).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.b7", parameters = mapOf("subs-per-line" to "5", "values" to "compute-v1")))
                .scaleAxis("line-rows").expectedResultCardinality(listOf(600))
                .reviewedFingerprint("86fe24ec6939d06589784b2f344a33b580311864d96395315e22c85a23f8f5d1").build(),
            600,
            PerfCatalog::b7,
        )

        BenchmarkScenario.COMPUTE_CLEARED -> include(
            ScenarioId.builder(scenario.id)
                .version(4)
                .operation(COMPUTE).resultRegime(COMPUTE_CLEARED).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.computeCleared", parameters = mapOf("values" to "cleared-v1")))
                .scaleAxis("line-rows").expectedResultCardinality(listOf(1_000))
                .reviewedFingerprint("c399e36cf1c3e18a863b72f9a7310662b0c800bf39d6cffae04034a012ffa282").build(),
            1_000,
            PerfCatalog::computeCleared,
        )

        BenchmarkScenario.COMPUTE_ERRORED -> include(
            ScenarioId.builder(scenario.id)
                .version(4)
                .operation(COMPUTE).resultRegime(COMPUTE_ERRORED).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.computeErrored", parameters = mapOf("values" to "errored-v1")))
                .scaleAxis("line-rows").expectedResultCardinality(listOf(1_000))
                .reviewedFingerprint("792bc412ccf328566e806368ac35607e0656d678c03bc72bb5f19128931ab989").build(),
            1_000,
            PerfCatalog::computeErrored,
        )

        BenchmarkScenario.B8 -> include(
            ScenarioId.builder(scenario.id)
                .version(4)
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
                .reviewedFingerprint("68b55bddb74d4dd3fa4d1bf7797da6560fe867681de2e2658537998b4fce552d").build(),
            500,
            { PerfCatalog.complexRealWorldFull(firing = false) },
        )

        BenchmarkScenario.B9_BERLIN -> include(
            ScenarioId.builder(scenario.id)
                .version(4)
                .operation(VALIDATE).resultRegime(VALIDATION_FIRING).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.berlinDateTime", parameters = mapOf("values" to "datetime-v1")))
                .scaleAxis("run-rows").expectedResultCardinality(listOf(3_000))
                .reviewedFingerprint("6825d6cb3b6465d9fc28d129c01b231adad262cec5345cd4b9f556b8e7e31046")
                .zone(ModelTimeZone.EUROPE_BERLIN).build(),
            3_000,
            PerfCatalog::berlinDateTime,
        )

        BenchmarkScenario.B9_UTC -> include(
            ScenarioId.builder(scenario.id)
                .version(4)
                .operation(VALIDATE).resultRegime(VALIDATION_FIRING).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.utcDateTimeControl", parameters = mapOf("values" to "datetime-v1")))
                .scaleAxis("run-rows").expectedResultCardinality(listOf(3_000))
                .reviewedFingerprint("7876062817d6af7b5c8f5f03893822952774e7d86d77d583a7ef59fb5265f5e2").build(),
            3_000,
            PerfCatalog::utcDateTimeControl,
        )

        BenchmarkScenario.TIERED_COMPUTE -> include(
            ScenarioId.builder(scenario.id)
                .version(4)
                .operation(COMPUTE).resultRegime(COMPUTE_VALUE).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.tieredComputation", parameters = mapOf("tiers" to "2", "values" to "tiered-v1")))
                .scaleAxis("line-rows").expectedResultCardinality(listOf(3_000))
                .reviewedFingerprint("90f0cde3fef4e4e6a01e1c77d334ac8b8a9628e4ccf2cf517889dc19e6f5edb4").build(),
            3_000,
            PerfCatalog::tieredComputation,
        )

        BenchmarkScenario.PARALLEL_ITERATION -> include(
            ScenarioId.builder(scenario.id)
                .version(4)
                .operation(VALIDATE).resultRegime(VALIDATION_MIXED).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.parallel", parameters = mapOf("groups" to "A+B", "values" to "parallel-v1")))
                .scaleAxis("rows-per-group").expectedResultCardinality(listOf(1_000))
                .reviewedFingerprint("5e35bf550eb8aeefc42bc16ca33fba3d1138656a0cb57618ef394f5e5a298a63").build(),
            2_000,
            PerfCatalog::parallel,
        )
    }

    private fun include(id: ScenarioId, n: Int, scenarioFactory: () -> PerfScenario): InventoryDisposition =
        InventoryDisposition.Included(InventoryCase(id, n, scenarioFactory))
}
