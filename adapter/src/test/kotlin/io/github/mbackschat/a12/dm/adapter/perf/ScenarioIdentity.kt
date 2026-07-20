package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.interpreter.model.ModelTimeZone

/**
 * Whether a scenario's rules mostly FIRE (an invalid-document regime), mostly pass ([LOW_FIRING] — the realistic
 * valid document, where the kernel's short-circuit edge is largest), a deliberate [MIXED], or the axis is
 * inapplicable ([NOT_APPLICABLE] — a pure computation scenario has no firing regime). "Cover both firing regimes"
 * is a separate IG77 residual; this field records what a case exercises so that coverage can be checked later.
 */
enum class FiringRegime { FIRING, LOW_FIRING, MIXED, NOT_APPLICABLE }

/** The declared asymptotic complexity of a scenario's scaled axis — what its growth guard must honor (a
 *  [PAIRWISE] case may legitimately exceed the linear growth bar when no worse than the kernel's same operation). */
enum class Complexity { LINEAR, N_LOG_N, PAIRWISE }

/**
 * A **versioned identity** for a benchmark scenario (IG77 — INTERPRETER-SPEC "Non-functional contract": the
 * versioned model, operation, value/firing regime, result cardinality, locale, and zone are bound per-number).
 * Binding every default case to a stable [id]+[version] is what lets a later run be *certifying* — it records
 * which scenario, at which version, produced a number — and lets a per-number promotion cite an immutable
 * scenario. [version] is bumped whenever the scenario's model/instance changes, so an old number can never be
 * silently re-attributed to a changed scenario.
 */
data class ScenarioId(
    val id: String,
    val version: Int,
    val op: PerfOp,
    val firing: FiringRegime,
    val complexity: Complexity,
    val zone: ModelTimeZone = ModelTimeZone.UTC,
    val locale: String = "en_US",
    /**
     * Whether the kernel's generated-**Java** validator supports this scenario (the fair NFR-P2 comparator). A
     * fair-Java-capable scenario belongs in the default inventory; because [PerfBenchTest] derives its sweep from
     * [PerfInventory], such a scenario is benchmarked iff it is registered here (an omission is not silently
     * dropped — [ScenarioInventoryTest] locks the inventory's shape).
     */
    val fairJavaCapable: Boolean = true,
)
