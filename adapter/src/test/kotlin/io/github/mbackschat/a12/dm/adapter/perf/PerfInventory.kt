package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.adapter.perf.Complexity.LINEAR
import io.github.mbackschat.a12.dm.adapter.perf.FiringRegime.MIXED
import io.github.mbackschat.a12.dm.adapter.perf.FiringRegime.NOT_APPLICABLE
import io.github.mbackschat.a12.dm.adapter.perf.PerfOp.COMPUTE
import io.github.mbackschat.a12.dm.adapter.perf.PerfOp.VALIDATE
import io.github.mbackschat.a12.dm.interpreter.model.ModelTimeZone

/** One default benchmark case: its versioned [id], the scale [n] it is swept at, and the [scenario] factory. */
class InventoryCase(val id: ScenarioId, val n: Int, val scenario: () -> PerfScenario)

/**
 * The **default B-scenario inventory** (IG77): the single source of the scenarios the kernel-ratio benchmark tier
 * ([PerfBenchTest]) sweeps, each bound to a versioned [ScenarioId]. PerfBenchTest **derives** its sweep from here,
 * so a fair-Java-capable scenario is benchmarked *iff* it is registered — an omission is not silently dropped, and
 * [ScenarioInventoryTest] locks the inventory's shape (unique versioned ids, op/zone identity consistency against
 * the built scenario, B9 Europe/Berlin present, and that every case is executable + fully supported). Adding a
 * default benchmark scenario = add one row here; changing a scenario's model/instance = bump its [ScenarioId.version].
 */
object PerfInventory {

    val cases: List<InventoryCase> = listOf(
        // Validation B-series (nested scenarios use a smaller Line count × PerfCatalog.SUBS_PER_LINE sub rows).
        InventoryCase(ScenarioId("B1-per-row", 1, VALIDATE, MIXED, LINEAR), 3_000, PerfCatalog::b1),
        InventoryCase(ScenarioId("B2-star-aggregate", 1, VALIDATE, MIXED, LINEAR), 3_000, PerfCatalog::b2),
        InventoryCase(ScenarioId("B3-nested-per-sub", 1, VALIDATE, MIXED, LINEAR), 600, PerfCatalog::b3),
        InventoryCase(ScenarioId("B4-cross-level-ref", 1, VALIDATE, MIXED, LINEAR), 600, PerfCatalog::b4),
        InventoryCase(ScenarioId("B5-deep-nested-star", 1, VALIDATE, MIXED, LINEAR), 600, PerfCatalog::b5),
        InventoryCase(ScenarioId("B6-many-rules", 1, VALIDATE, MIXED, LINEAR), 600, PerfCatalog::b6),
        // Computation cases (no firing regime — the compute path, VALUE/CLEARED/ERRORED outcomes).
        InventoryCase(ScenarioId("B7-compute", 1, COMPUTE, NOT_APPLICABLE, LINEAR), 600, PerfCatalog::b7),
        InventoryCase(ScenarioId("tiered-compute", 1, COMPUTE, NOT_APPLICABLE, LINEAR), 3_000, PerfCatalog::tieredComputation),
        // Parallel-iteration outer join (the index-keyed O(rows) path).
        InventoryCase(ScenarioId("parallel-iteration", 1, VALIDATE, MIXED, LINEAR), 2_000, PerfCatalog::parallel),
        // B9 Europe/Berlin DATE_TIME + its UTC control (the zone axis — IF132).
        InventoryCase(
            ScenarioId("B9-berlin-datetime", 1, VALIDATE, MIXED, LINEAR, zone = ModelTimeZone.EUROPE_BERLIN),
            3_000, PerfCatalog::berlinDateTime,
        ),
        InventoryCase(ScenarioId("B9-utc-control", 1, VALIDATE, MIXED, LINEAR), 3_000, PerfCatalog::utcDateTimeControl),
    )
}
