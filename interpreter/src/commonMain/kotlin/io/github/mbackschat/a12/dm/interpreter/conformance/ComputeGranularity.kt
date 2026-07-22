package io.github.mbackschat.a12.dm.interpreter.conformance

import io.github.mbackschat.a12.dm.interpreter.eval.formatPointer
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef

/**
 * Project the interpreter's report-ALL compute signatures onto the **kernel's three-channel change projection**,
 * for comparison against a kernel-captured expectation (CONFORMANCE-CORPUS-SPEC §7).
 *
 * The kernel's `IDocumentComputationResult` is a **five-channel result** (successful-incl-unchanged, its changed
 * subset, errored, cleared, and operand formal errors; `noErrorOccurred` == errored ∅ && formal-operand ∅ —
 * KERNEL-FINDINGS §11 five-channel supplement). The corpus/differential capture selects its **three-channel
 * change projection**, which is what this function targets:
 * - a **VALUE** only when it *changed* vs the input document — a TYPED-value equality
 *   (`getComputedFieldInstancesWithChanges`, `fieldValuesEqual(original, computed)`);
 * - a **CLEARED** instance only when the input document had the cell *filled* (`getClearedFieldInstances`);
 * - an **ERRORED** instance unconditionally (`getComputedFieldInstancesWithErrors`).
 *
 * `DmInterpreter.compute` deliberately reports **every** computed instance — report-all is the more useful
 * library contract, and result-envelope granularity is not behavior-parity territory (the compute-granularity
 * ruling — KERNEL-FINDINGS "compute reporting granularity", CONFORMANCE-CORPUS-SPEC §7) — so
 * every comparison surface that diffs the interpreter against a kernel-granularity multiset projects through
 * this function first, diffing the interpreter's outcomes against the input placements.
 *
 * On a placement-built input document the cell values are raw **strings**, so the kernel's typed change
 * equality can hold only for a **STRING-kind** target (a number/date/boolean computes to a typed value —
 * `BigDecimal`/`Instant`/`Boolean` — never equal to the raw seed string; kernel-probe-verified,
 * KERNEL-FINDINGS "compute reporting granularity"). Hence:
 * - a `VALUE` equal to the cell's placement value is dropped iff the target is [Kind.STRING];
 * - a `CLEARED` on a cell no placement filled (unseeded, [PlacementKind.EMPTY], or an empty/null field value)
 *   is dropped;
 * - an `ERRORED` is always kept.
 */
class KernelComputeProjection private constructor(private val seeded: Map<String, SeededCell>) {

    /** Project one freshly materialized report-all result; the prepared input lookup is reused unchanged. */
    fun project(signatures: List<String>): List<String> = signatures.filterNot { sig ->
        val pointer = sig.substringBefore('|')
        val input = seeded[pointer]
        when {
            "|VALUE|" in sig ->
                input?.value == sig.substringAfter("|VALUE|") && input.kind == Kind.STRING
            sig.endsWith("|CLEARED") -> input == null
            else -> false
        }
    }

    companion object {
        /** Build the immutable input side once when a reusable document instance is materialized. */
        fun prepare(placements: List<Placement>, model: ModelDef): KernelComputeProjection {
            val seeded = HashMap<String, SeededCell>()
            for (placement in placements) {
                if (placement.kind == PlacementKind.FIELD && !placement.value.isNullOrEmpty()) {
                    seeded[formatPointer(placement.path, placement.reps)] = SeededCell(
                        placement.value,
                        model.kinds[placement.path],
                    )
                }
            }
            return KernelComputeProjection(seeded)
        }
    }

    private data class SeededCell(val value: String, val kind: Kind?)
}

/** One-shot compatibility form; repeated callers should prepare [KernelComputeProjection] once. */
fun kernelComputeGranularity(signatures: List<String>, placements: List<Placement>, model: ModelDef): List<String> =
    KernelComputeProjection.prepare(placements, model).project(signatures)
