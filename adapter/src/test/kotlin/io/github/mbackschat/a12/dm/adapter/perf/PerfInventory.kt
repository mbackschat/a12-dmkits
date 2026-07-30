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
                .version(3)
                .operation(VALIDATE).resultRegime(VALIDATION_MIXED).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.b1", parameters = mapOf("rows" to "flat", "values" to "perf-v1")))
                .scaleAxis("line-rows").expectedResultCardinality(listOf(1_470))
                .reviewedFingerprint("43606d1d51dde4e230613ece52ae1113860222a99aa29b383835e0f81e713238").build(),
            3_000,
            PerfCatalog::b1,
        )

        BenchmarkScenario.B2 -> include(
            ScenarioId.builder(scenario.id)
                .version(3)
                .operation(VALIDATE).resultRegime(VALIDATION_FIRING).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.b2", parameters = mapOf("rows" to "flat", "values" to "perf-v1")))
                .scaleAxis("line-rows").expectedResultCardinality(listOf(1))
                .reviewedFingerprint("28dbef17497f599f9c7284361b8cfa0be8fa7f55cfc3f0b69ad36026cc630f97").build(),
            3_000,
            PerfCatalog::b2,
        )

        BenchmarkScenario.B3 -> include(
            ScenarioId.builder(scenario.id)
                .version(3)
                .operation(VALIDATE).resultRegime(VALIDATION_MIXED).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.b3", parameters = mapOf("subs-per-line" to "5", "values" to "perf-v1")))
                .scaleAxis("line-rows").expectedResultCardinality(listOf(1_434))
                .reviewedFingerprint("fc5ab780b443cfd18fb4d6ab80e8d22311f529751d291bb16dd8942d19956cf0").build(),
            600,
            PerfCatalog::b3,
        )

        BenchmarkScenario.B4 -> include(
            ScenarioId.builder(scenario.id)
                .version(3)
                .operation(VALIDATE).resultRegime(VALIDATION_MIXED).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.b4", parameters = mapOf("subs-per-line" to "5", "values" to "perf-v1")))
                .scaleAxis("line-rows").expectedResultCardinality(listOf(1_152))
                .reviewedFingerprint("e0a2f524997174c0675415aa3a62ce8d5df7fc592d16e68bd7ef402b5f11988f").build(),
            600,
            PerfCatalog::b4,
        )

        BenchmarkScenario.B5 -> include(
            ScenarioId.builder(scenario.id)
                .version(3)
                .operation(VALIDATE).resultRegime(VALIDATION_FIRING).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.b5", parameters = mapOf("subs-per-line" to "5", "values" to "perf-v1")))
                .scaleAxis("line-rows").expectedResultCardinality(listOf(1))
                .reviewedFingerprint("cdd477f5a86e7bcd976b9e1593973d72c408d983841902380d740b7325d5d356").build(),
            600,
            PerfCatalog::b5,
        )

        BenchmarkScenario.B6 -> include(
            ScenarioId.builder(scenario.id)
                .version(3)
                .operation(VALIDATE).resultRegime(VALIDATION_MIXED).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.b6", parameters = mapOf("subs-per-line" to "5", "rules" to "12", "values" to "perf-v1")))
                .scaleAxis("line-rows").expectedResultCardinality(listOf(3_548))
                .reviewedFingerprint("dfb1dfc64f402544cf71d0d0878015358bdcfdda46892bb6eebaabd026e993a0").build(),
            600,
            PerfCatalog::b6,
        )

        BenchmarkScenario.B6_LOW_FIRING -> include(
            ScenarioId.builder(scenario.id)
                .version(3)
                .operation(VALIDATE).resultRegime(VALIDATION_LOW_FIRING).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.manyRules", parameters = mapOf(
                    "firing" to "false",
                    "rules" to "12",
                    "values" to "low-firing-v1",
                )))
                .scaleAxis("line-rows").expectedResultCardinality(listOf(0))
                .reviewedFingerprint("35c377289af245f644d487f864d10e987deb257e4d15569d08dca5f30373addf").build(),
            600,
            { PerfCatalog.manyRules(12, firing = false) },
        )

        BenchmarkScenario.B7 -> include(
            ScenarioId.builder(scenario.id)
                .version(3)
                .operation(COMPUTE).resultRegime(COMPUTE_VALUE).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.b7", parameters = mapOf("subs-per-line" to "5", "values" to "compute-v1")))
                .scaleAxis("line-rows").expectedResultCardinality(listOf(600))
                .reviewedFingerprint("3acca0bbfe66714bd987a4c7bca9c80dd6fe04163f043c68ab4e15e95a2abc06").build(),
            600,
            PerfCatalog::b7,
        )

        BenchmarkScenario.COMPUTE_CLEARED -> include(
            ScenarioId.builder(scenario.id)
                .version(3)
                .operation(COMPUTE).resultRegime(COMPUTE_CLEARED).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.computeCleared", parameters = mapOf("values" to "cleared-v1")))
                .scaleAxis("line-rows").expectedResultCardinality(listOf(1_000))
                .reviewedFingerprint("bfc7ade120c92f84da69ab9d4470e9e434e1c7be87621b786ded3ec190da8d2e").build(),
            1_000,
            PerfCatalog::computeCleared,
        )

        BenchmarkScenario.COMPUTE_ERRORED -> include(
            ScenarioId.builder(scenario.id)
                .version(3)
                .operation(COMPUTE).resultRegime(COMPUTE_ERRORED).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.computeErrored", parameters = mapOf("values" to "errored-v1")))
                .scaleAxis("line-rows").expectedResultCardinality(listOf(1_000))
                .reviewedFingerprint("6689e07fc2280ed3da0a046eff8df9c11fc81a169400e8feb7491fe86217c27f").build(),
            1_000,
            PerfCatalog::computeErrored,
        )

        BenchmarkScenario.B8 -> include(
            ScenarioId.builder(scenario.id)
                .version(3)
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
                .reviewedFingerprint("76711f31971be52b702e3a0af0745cd268be65e09f1711a60960c488a9dc5a4a").build(),
            500,
            { PerfCatalog.complexRealWorldFull(firing = false) },
        )

        BenchmarkScenario.B9_BERLIN -> include(
            ScenarioId.builder(scenario.id)
                .version(3)
                .operation(VALIDATE).resultRegime(VALIDATION_FIRING).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.berlinDateTime", parameters = mapOf("values" to "datetime-v1")))
                .scaleAxis("run-rows").expectedResultCardinality(listOf(3_000))
                .reviewedFingerprint("37d614222600c1f0a21383ab56037d4fe671d1b5ca07b3f8c99e727bcd3d851f")
                .zone(ModelTimeZone.EUROPE_BERLIN).build(),
            3_000,
            PerfCatalog::berlinDateTime,
        )

        BenchmarkScenario.B9_UTC -> include(
            ScenarioId.builder(scenario.id)
                .version(3)
                .operation(VALIDATE).resultRegime(VALIDATION_FIRING).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.utcDateTimeControl", parameters = mapOf("values" to "datetime-v1")))
                .scaleAxis("run-rows").expectedResultCardinality(listOf(3_000))
                .reviewedFingerprint("47ac263702541dc8889841ff7d6a1b0208696c67fd6b6f74d8b41208d2ef0e66").build(),
            3_000,
            PerfCatalog::utcDateTimeControl,
        )

        BenchmarkScenario.TIERED_COMPUTE -> include(
            ScenarioId.builder(scenario.id)
                .version(3)
                .operation(COMPUTE).resultRegime(COMPUTE_VALUE).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.tieredComputation", parameters = mapOf("tiers" to "2", "values" to "tiered-v1")))
                .scaleAxis("line-rows").expectedResultCardinality(listOf(3_000))
                .reviewedFingerprint("a5fae87edb589803025f6d972dddec05b753207f648db95aa14f0018e288d125").build(),
            3_000,
            PerfCatalog::tieredComputation,
        )

        BenchmarkScenario.PARALLEL_ITERATION -> include(
            ScenarioId.builder(scenario.id)
                .version(3)
                .operation(VALIDATE).resultRegime(VALIDATION_MIXED).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.parallel", parameters = mapOf("groups" to "A+B", "values" to "parallel-v1")))
                .scaleAxis("rows-per-group").expectedResultCardinality(listOf(1_000))
                .reviewedFingerprint("5d9388ecbc73f2a0b585fd877f081d6ea594f891ec2c598f7df69238d1db4245").build(),
            2_000,
            PerfCatalog::parallel,
        )
    }

    private fun include(id: ScenarioId, n: Int, scenarioFactory: () -> PerfScenario): InventoryDisposition =
        InventoryDisposition.Included(InventoryCase(id, n, scenarioFactory))
}
