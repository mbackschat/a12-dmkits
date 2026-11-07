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
                .version(5)
                .operation(VALIDATE).resultRegime(VALIDATION_MIXED).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.b1", parameters = mapOf("rows" to "flat", "values" to "perf-v1")))
                .scaleAxis("line-rows").expectedResultCardinality(listOf(1_470))
                .reviewedFingerprint("1dde31d2e0ce63c928a79ab31a66ea694e9484582d0b6bccf342a76823c69b83").build(),
            3_000,
            PerfCatalog::b1,
        )

        BenchmarkScenario.B2 -> include(
            ScenarioId.builder(scenario.id)
                .version(5)
                .operation(VALIDATE).resultRegime(VALIDATION_FIRING).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.b2", parameters = mapOf("rows" to "flat", "values" to "perf-v1")))
                .scaleAxis("line-rows").expectedResultCardinality(listOf(1))
                .reviewedFingerprint("79809863eee5c127f22b20aadcd74e9182571a48dbbd0224dac54f0fc310ae03").build(),
            3_000,
            PerfCatalog::b2,
        )

        BenchmarkScenario.B3 -> include(
            ScenarioId.builder(scenario.id)
                .version(5)
                .operation(VALIDATE).resultRegime(VALIDATION_MIXED).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.b3", parameters = mapOf("subs-per-line" to "5", "values" to "perf-v1")))
                .scaleAxis("line-rows").expectedResultCardinality(listOf(1_434))
                .reviewedFingerprint("5974a022c1e73b72d10f2d4f6d5c653e8a86546e3d1697e23a2422a5ed7417e6").build(),
            600,
            PerfCatalog::b3,
        )

        BenchmarkScenario.B4 -> include(
            ScenarioId.builder(scenario.id)
                .version(5)
                .operation(VALIDATE).resultRegime(VALIDATION_MIXED).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.b4", parameters = mapOf("subs-per-line" to "5", "values" to "perf-v1")))
                .scaleAxis("line-rows").expectedResultCardinality(listOf(1_152))
                .reviewedFingerprint("f9a411cc87fbf4c969a206aa24fb3d96cd5516c3feab8ae892b8d938a0f94706").build(),
            600,
            PerfCatalog::b4,
        )

        BenchmarkScenario.B5 -> include(
            ScenarioId.builder(scenario.id)
                .version(5)
                .operation(VALIDATE).resultRegime(VALIDATION_FIRING).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.b5", parameters = mapOf("subs-per-line" to "5", "values" to "perf-v1")))
                .scaleAxis("line-rows").expectedResultCardinality(listOf(1))
                .reviewedFingerprint("f373cff5056d57cdd1a577b216516edbec66a89fecee39b132b7ada26ecc2e7f").build(),
            600,
            PerfCatalog::b5,
        )

        BenchmarkScenario.B6 -> include(
            ScenarioId.builder(scenario.id)
                .version(5)
                .operation(VALIDATE).resultRegime(VALIDATION_MIXED).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.b6", parameters = mapOf("subs-per-line" to "5", "rules" to "12", "values" to "perf-v1")))
                .scaleAxis("line-rows").expectedResultCardinality(listOf(3_548))
                .reviewedFingerprint("817a72c09fa1783b6f66056a2850bf53ba4972401729e336c09bdaf6342a2ccd").build(),
            600,
            PerfCatalog::b6,
        )

        BenchmarkScenario.B6_LOW_FIRING -> include(
            ScenarioId.builder(scenario.id)
                .version(5)
                .operation(VALIDATE).resultRegime(VALIDATION_LOW_FIRING).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.manyRules", parameters = mapOf(
                    "firing" to "false",
                    "rules" to "12",
                    "values" to "low-firing-v1",
                )))
                .scaleAxis("line-rows").expectedResultCardinality(listOf(0))
                .reviewedFingerprint("f6256ce4bdb5f10c0512891c77fc880dba0da206ded1f8ce7d402f09d6ecb678").build(),
            600,
            { PerfCatalog.manyRules(12, firing = false) },
        )

        BenchmarkScenario.B7 -> include(
            ScenarioId.builder(scenario.id)
                .version(5)
                .operation(COMPUTE).resultRegime(COMPUTE_VALUE).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.b7", parameters = mapOf("subs-per-line" to "5", "values" to "compute-v1")))
                .scaleAxis("line-rows").expectedResultCardinality(listOf(600))
                .reviewedFingerprint("886ba126c34ac1b28ff92973625b409324cf0233bee9f964c52c324473cd987f").build(),
            600,
            PerfCatalog::b7,
        )

        BenchmarkScenario.COMPUTE_CLEARED -> include(
            ScenarioId.builder(scenario.id)
                .version(5)
                .operation(COMPUTE).resultRegime(COMPUTE_CLEARED).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.computeCleared", parameters = mapOf("values" to "cleared-v1")))
                .scaleAxis("line-rows").expectedResultCardinality(listOf(1_000))
                .reviewedFingerprint("9a6a251a13e07486a33f0794567ac0a38bbde268570621d7b88cc169f6c64e4f").build(),
            1_000,
            PerfCatalog::computeCleared,
        )

        BenchmarkScenario.COMPUTE_ERRORED -> include(
            ScenarioId.builder(scenario.id)
                .version(5)
                .operation(COMPUTE).resultRegime(COMPUTE_ERRORED).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.computeErrored", parameters = mapOf("values" to "errored-v1")))
                .scaleAxis("line-rows").expectedResultCardinality(listOf(1_000))
                .reviewedFingerprint("b0ae86733dba592a917a2c3067818226fe8570315e4e5cb152449844b5a01559").build(),
            1_000,
            PerfCatalog::computeErrored,
        )

        BenchmarkScenario.B8 -> include(
            ScenarioId.builder(scenario.id)
                .version(5)
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
                .reviewedFingerprint("730a06ee51b902dd3a16c2bea6b30d51107ed2f05f23a8227649a099fd05ad11").build(),
            500,
            { PerfCatalog.complexRealWorldFull(firing = false) },
        )

        BenchmarkScenario.B9_BERLIN -> include(
            ScenarioId.builder(scenario.id)
                .version(5)
                .operation(VALIDATE).resultRegime(VALIDATION_FIRING).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.berlinDateTime", parameters = mapOf("values" to "datetime-v1")))
                .scaleAxis("run-rows").expectedResultCardinality(listOf(3_000))
                .reviewedFingerprint("5870c7438430b81d95656ee7b2a4458348d165d26f9d9ee7f242606ff33f28db")
                .zone(ModelTimeZone.EUROPE_BERLIN).build(),
            3_000,
            PerfCatalog::berlinDateTime,
        )

        BenchmarkScenario.B9_UTC -> include(
            ScenarioId.builder(scenario.id)
                .version(5)
                .operation(VALIDATE).resultRegime(VALIDATION_FIRING).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.utcDateTimeControl", parameters = mapOf("values" to "datetime-v1")))
                .scaleAxis("run-rows").expectedResultCardinality(listOf(3_000))
                .reviewedFingerprint("799ec01292a1d13c31aecca42f27a2ac78e1ba9e8d0e7c4f64a39921a6190c0e").build(),
            3_000,
            PerfCatalog::utcDateTimeControl,
        )

        BenchmarkScenario.TIERED_COMPUTE -> include(
            ScenarioId.builder(scenario.id)
                .version(5)
                .operation(COMPUTE).resultRegime(COMPUTE_VALUE).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.tieredComputation", parameters = mapOf("tiers" to "2", "values" to "tiered-v1")))
                .scaleAxis("line-rows").expectedResultCardinality(listOf(3_000))
                .reviewedFingerprint("412aabe4bb9526edcc5a7826ce643bd0626793d71a54b1fcc0237091a71b7f63").build(),
            3_000,
            PerfCatalog::tieredComputation,
        )

        BenchmarkScenario.PARALLEL_ITERATION -> include(
            ScenarioId.builder(scenario.id)
                .version(5)
                .operation(VALIDATE).resultRegime(VALIDATION_MIXED).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.parallel", parameters = mapOf("groups" to "A+B", "values" to "parallel-v1")))
                .scaleAxis("rows-per-group").expectedResultCardinality(listOf(1_000))
                .reviewedFingerprint("49bb5a22c41f18dc6a6097c244e071c239d775ca047fef0bf07bc7ea2a24fb9b").build(),
            2_000,
            PerfCatalog::parallel,
        )
    }

    private fun include(id: ScenarioId, n: Int, scenarioFactory: () -> PerfScenario): InventoryDisposition =
        InventoryDisposition.Included(InventoryCase(id, n, scenarioFactory))
}
