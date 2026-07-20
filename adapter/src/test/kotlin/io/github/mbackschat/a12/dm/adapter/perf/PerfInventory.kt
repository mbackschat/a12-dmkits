package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.adapter.perf.Complexity.LINEAR
import io.github.mbackschat.a12.dm.adapter.perf.PerfOp.COMPUTE
import io.github.mbackschat.a12.dm.adapter.perf.PerfOp.VALIDATE
import io.github.mbackschat.a12.dm.adapter.perf.ResultRegime.COMPUTE_VALUE
import io.github.mbackschat.a12.dm.adapter.perf.ResultRegime.VALIDATION_FIRING
import io.github.mbackschat.a12.dm.adapter.perf.ResultRegime.VALIDATION_MIXED
import io.github.mbackschat.a12.dm.interpreter.model.ModelTimeZone

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
 * The reviewed default scenario inventory. Each row stores the content fingerprint beside its version; the
 * scenario returned to the harness owns that bound identity.
 */
object PerfInventory {

    val cases: List<InventoryCase> = listOf(
        InventoryCase(
            ScenarioId.builder("B1-per-row")
                .operation(VALIDATE).resultRegime(VALIDATION_MIXED).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.b1", parameters = mapOf("rows" to "flat", "values" to "perf-v1")))
                .scaleAxis("line-rows").expectedResultCardinality(listOf(1_470))
                .reviewedFingerprint("988d9acaed29cd5a057fe1f1a7ff2172363e40726e1efdce0fa9e12f1e1ad8e8").build(),
            3_000,
            PerfCatalog::b1,
        ),
        InventoryCase(
            ScenarioId.builder("B2-star-aggregate")
                .operation(VALIDATE).resultRegime(VALIDATION_FIRING).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.b2", parameters = mapOf("rows" to "flat", "values" to "perf-v1")))
                .scaleAxis("line-rows").expectedResultCardinality(listOf(1))
                .reviewedFingerprint("a1a16b388757b4facd1034c564e6512ab161c95f38860686ba81f50d72a82ef1").build(),
            3_000,
            PerfCatalog::b2,
        ),
        InventoryCase(
            ScenarioId.builder("B3-nested-per-sub")
                .operation(VALIDATE).resultRegime(VALIDATION_MIXED).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.b3", parameters = mapOf("subs-per-line" to "5", "values" to "perf-v1")))
                .scaleAxis("line-rows").expectedResultCardinality(listOf(1_434))
                .reviewedFingerprint("dd60c53f7878feb274e204800dafda1afaaa08e9d2075d19d918dcf4e6250f81").build(),
            600,
            PerfCatalog::b3,
        ),
        InventoryCase(
            ScenarioId.builder("B4-cross-level-ref")
                .operation(VALIDATE).resultRegime(VALIDATION_MIXED).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.b4", parameters = mapOf("subs-per-line" to "5", "values" to "perf-v1")))
                .scaleAxis("line-rows").expectedResultCardinality(listOf(1_152))
                .reviewedFingerprint("f5051372ffbc710946378d5b65fd29b4e780237e6314fffd3e0f96f3e2fb8c47").build(),
            600,
            PerfCatalog::b4,
        ),
        InventoryCase(
            ScenarioId.builder("B5-deep-nested-star")
                .operation(VALIDATE).resultRegime(VALIDATION_FIRING).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.b5", parameters = mapOf("subs-per-line" to "5", "values" to "perf-v1")))
                .scaleAxis("line-rows").expectedResultCardinality(listOf(1))
                .reviewedFingerprint("0fcea35fdfc68994b3436f892db75ae82209d4ca52894171d5e8ba862dfe7553").build(),
            600,
            PerfCatalog::b5,
        ),
        InventoryCase(
            ScenarioId.builder("B6-many-rules")
                .operation(VALIDATE).resultRegime(VALIDATION_MIXED).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.b6", parameters = mapOf("subs-per-line" to "5", "rules" to "12", "values" to "perf-v1")))
                .scaleAxis("line-rows").expectedResultCardinality(listOf(3_548))
                .reviewedFingerprint("65bd844d487a1c9b9fb0c5c459545d71885da1a01d40036323f58adf73924531").build(),
            600,
            PerfCatalog::b6,
        ),
        InventoryCase(
            ScenarioId.builder("B7-compute")
                .operation(COMPUTE).resultRegime(COMPUTE_VALUE).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.b7", parameters = mapOf("subs-per-line" to "5", "values" to "compute-v1")))
                .scaleAxis("line-rows").expectedResultCardinality(listOf(600))
                .reviewedFingerprint("e859c7ebc01f150a7ebf2638ebd060c7c37c69e38fcab88d290d43868142b20c").build(),
            600,
            PerfCatalog::b7,
        ),
        InventoryCase(
            ScenarioId.builder("tiered-compute")
                .operation(COMPUTE).resultRegime(COMPUTE_VALUE).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.tieredComputation", parameters = mapOf("tiers" to "2", "values" to "tiered-v1")))
                .scaleAxis("line-rows").expectedResultCardinality(listOf(3_000))
                .reviewedFingerprint("26e0297ed9b783b96a6e8d1e242d491a00b3ff40b2f2bf5382fe77c90561147b").build(),
            3_000,
            PerfCatalog::tieredComputation,
        ),
        InventoryCase(
            ScenarioId.builder("parallel-iteration")
                .operation(VALIDATE).resultRegime(VALIDATION_MIXED).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.parallel", parameters = mapOf("groups" to "A+B", "values" to "parallel-v1")))
                .scaleAxis("rows-per-group").expectedResultCardinality(listOf(1_000))
                .reviewedFingerprint("04ad5b24df312dc0bf70a93d924502cd5485e2cf91914ae5463e06eb600087a7").build(),
            2_000,
            PerfCatalog::parallel,
        ),
        InventoryCase(
            ScenarioId.builder("B9-berlin-datetime")
                .operation(VALIDATE).resultRegime(VALIDATION_FIRING).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.berlinDateTime", parameters = mapOf("values" to "datetime-v1")))
                .scaleAxis("run-rows").expectedResultCardinality(listOf(3_000))
                .reviewedFingerprint("887b25d8400f3d8bf7f463e5960bc4b618890f4cadbefe3c475fb352db9cb905")
                .zone(ModelTimeZone.EUROPE_BERLIN).build(),
            3_000,
            PerfCatalog::berlinDateTime,
        ),
        InventoryCase(
            ScenarioId.builder("B9-utc-control")
                .operation(VALIDATE).resultRegime(VALIDATION_FIRING).complexity(LINEAR)
                .generator(GeneratorIdentity("PerfCatalog.utcDateTimeControl", parameters = mapOf("values" to "datetime-v1")))
                .scaleAxis("run-rows").expectedResultCardinality(listOf(3_000))
                .reviewedFingerprint("2afd9ac52577c133a8e6efb4f774c3105530525d96b8946d1964b9bde98dddf7").build(),
            3_000,
            PerfCatalog::utcDateTimeControl,
        ),
    )
}
