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
                .operation(VALIDATE).resultRegime(VALIDATION_MIXED).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.b1", parameters = mapOf("rows" to "flat", "values" to "perf-v1")))
                .scaleAxis("line-rows").expectedResultCardinality(listOf(1_470))
                .reviewedFingerprint("86816909342c88f2657089b9433b0bcd51f927db7c544e2cfa6bed63b0a1adeb").build(),
            3_000,
            PerfCatalog::b1,
        )

        BenchmarkScenario.B2 -> include(
            ScenarioId.builder(scenario.id)
                .operation(VALIDATE).resultRegime(VALIDATION_FIRING).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.b2", parameters = mapOf("rows" to "flat", "values" to "perf-v1")))
                .scaleAxis("line-rows").expectedResultCardinality(listOf(1))
                .reviewedFingerprint("9b4529b1c1f01fd60784f99480020aba60c11b5eec3349264f641972c658f416").build(),
            3_000,
            PerfCatalog::b2,
        )

        BenchmarkScenario.B3 -> include(
            ScenarioId.builder(scenario.id)
                .operation(VALIDATE).resultRegime(VALIDATION_MIXED).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.b3", parameters = mapOf("subs-per-line" to "5", "values" to "perf-v1")))
                .scaleAxis("line-rows").expectedResultCardinality(listOf(1_434))
                .reviewedFingerprint("3a23a1ed7425e587a824920c26c01ad5689d48b9c26872e5ea71ea2fd563b936").build(),
            600,
            PerfCatalog::b3,
        )

        BenchmarkScenario.B4 -> include(
            ScenarioId.builder(scenario.id)
                .operation(VALIDATE).resultRegime(VALIDATION_MIXED).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.b4", parameters = mapOf("subs-per-line" to "5", "values" to "perf-v1")))
                .scaleAxis("line-rows").expectedResultCardinality(listOf(1_152))
                .reviewedFingerprint("5aedf10295163fc6934e229647bfc60b33e05c939eb8e77ed0f7787126a10417").build(),
            600,
            PerfCatalog::b4,
        )

        BenchmarkScenario.B5 -> include(
            ScenarioId.builder(scenario.id)
                .operation(VALIDATE).resultRegime(VALIDATION_FIRING).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.b5", parameters = mapOf("subs-per-line" to "5", "values" to "perf-v1")))
                .scaleAxis("line-rows").expectedResultCardinality(listOf(1))
                .reviewedFingerprint("b99e38355ccf76ac79fcbd9702b297eef505f44eb5d98872c0b9bce0be2f961a").build(),
            600,
            PerfCatalog::b5,
        )

        BenchmarkScenario.B6 -> include(
            ScenarioId.builder(scenario.id)
                .operation(VALIDATE).resultRegime(VALIDATION_MIXED).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.b6", parameters = mapOf("subs-per-line" to "5", "rules" to "12", "values" to "perf-v1")))
                .scaleAxis("line-rows").expectedResultCardinality(listOf(3_548))
                .reviewedFingerprint("57c5fec191d4e7c978e757cbfad68322b17ce4b4ac8c606d63bd792962e79a79").build(),
            600,
            PerfCatalog::b6,
        )

        BenchmarkScenario.B6_LOW_FIRING -> include(
            ScenarioId.builder(scenario.id)
                .operation(VALIDATE).resultRegime(VALIDATION_LOW_FIRING).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.manyRules", parameters = mapOf(
                    "firing" to "false",
                    "rules" to "12",
                    "values" to "low-firing-v1",
                )))
                .scaleAxis("line-rows").expectedResultCardinality(listOf(0))
                .reviewedFingerprint("a613040b12d2a710f7fd69bc555b6b2436d93f7aa68196fc4e37676f0fb0ff43").build(),
            600,
            { PerfCatalog.manyRules(12, firing = false) },
        )

        BenchmarkScenario.B7 -> include(
            ScenarioId.builder(scenario.id)
                .operation(COMPUTE).resultRegime(COMPUTE_VALUE).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.b7", parameters = mapOf("subs-per-line" to "5", "values" to "compute-v1")))
                .scaleAxis("line-rows").expectedResultCardinality(listOf(600))
                .reviewedFingerprint("fe6b9c71d4a8ee0bfc4fedfb30bb47d7e34bafd9dd392c7c61ae9637334f2084").build(),
            600,
            PerfCatalog::b7,
        )

        BenchmarkScenario.COMPUTE_CLEARED -> include(
            ScenarioId.builder(scenario.id)
                .operation(COMPUTE).resultRegime(COMPUTE_CLEARED).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.computeCleared", parameters = mapOf("values" to "cleared-v1")))
                .scaleAxis("line-rows").expectedResultCardinality(listOf(1_000))
                .reviewedFingerprint("658efd3d3df582acd5ff546b62b6e994dbd2c2f4dee28c261580818ed1b041c3").build(),
            1_000,
            PerfCatalog::computeCleared,
        )

        BenchmarkScenario.COMPUTE_ERRORED -> include(
            ScenarioId.builder(scenario.id)
                .operation(COMPUTE).resultRegime(COMPUTE_ERRORED).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.computeErrored", parameters = mapOf("values" to "errored-v1")))
                .scaleAxis("line-rows").expectedResultCardinality(listOf(1_000))
                .reviewedFingerprint("e5bf2515ded579b483e25cd4d6035d7cd0973cceb10ddbc471932561ca1d2dbb").build(),
            1_000,
            PerfCatalog::computeErrored,
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
                .reviewedFingerprint("fc44da61086a5c374b797c18f30a5b889fefe9320a728849eb1ad02a2dc5fc3e").build(),
            500,
            { PerfCatalog.complexRealWorldFull(firing = false) },
        )

        BenchmarkScenario.B9_BERLIN -> include(
            ScenarioId.builder(scenario.id)
                .operation(VALIDATE).resultRegime(VALIDATION_FIRING).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.berlinDateTime", parameters = mapOf("values" to "datetime-v1")))
                .scaleAxis("run-rows").expectedResultCardinality(listOf(3_000))
                .reviewedFingerprint("dc4ae869febefde7d23a959ebbae9a0a3449aaaa3ed286c8dbf78923591e2719")
                .zone(ModelTimeZone.EUROPE_BERLIN).build(),
            3_000,
            PerfCatalog::berlinDateTime,
        )

        BenchmarkScenario.B9_UTC -> include(
            ScenarioId.builder(scenario.id)
                .operation(VALIDATE).resultRegime(VALIDATION_FIRING).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.utcDateTimeControl", parameters = mapOf("values" to "datetime-v1")))
                .scaleAxis("run-rows").expectedResultCardinality(listOf(3_000))
                .reviewedFingerprint("f713db16e529f9f57f927627f19c773342dfdc63e5c337aaa71773461ec46f4a").build(),
            3_000,
            PerfCatalog::utcDateTimeControl,
        )

        BenchmarkScenario.TIERED_COMPUTE -> include(
            ScenarioId.builder(scenario.id)
                .operation(COMPUTE).resultRegime(COMPUTE_VALUE).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.tieredComputation", parameters = mapOf("tiers" to "2", "values" to "tiered-v1")))
                .scaleAxis("line-rows").expectedResultCardinality(listOf(3_000))
                .reviewedFingerprint("7294c0f990a5a4579836c7a9fb7e1da1794730fc59cb24d39592ddfbfc9aaea8").build(),
            3_000,
            PerfCatalog::tieredComputation,
        )

        BenchmarkScenario.PARALLEL_ITERATION -> include(
            ScenarioId.builder(scenario.id)
                .operation(VALIDATE).resultRegime(VALIDATION_MIXED).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.parallel", parameters = mapOf("groups" to "A+B", "values" to "parallel-v1")))
                .scaleAxis("rows-per-group").expectedResultCardinality(listOf(1_000))
                .reviewedFingerprint("b440223d4daf1e252a2f8edee44301bdf030ac59763b306b18d7eefc930b6c3d").build(),
            2_000,
            PerfCatalog::parallel,
        )
    }

    private fun include(id: ScenarioId, n: Int, scenarioFactory: () -> PerfScenario): InventoryDisposition =
        InventoryDisposition.Included(InventoryCase(id, n, scenarioFactory))
}
