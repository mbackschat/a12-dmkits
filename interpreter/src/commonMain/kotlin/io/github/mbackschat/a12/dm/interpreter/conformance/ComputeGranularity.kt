package io.github.mbackschat.a12.dm.interpreter.conformance

import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.formatPointer
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.gen.asSink

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
 * explicit V1 comparison surfaces project through this function first, diffing the interpreter's outcomes
 * against raw supplied cells. Canonical cases use [kernelCanonicalComputeGranularity] instead.
 *
 * This projection deliberately serves the conformance harness's **V1/String placement profile**. On such an
 * input the cell values are raw strings, so the kernel's V2-object change equality can hold only for a
 * **STRING-kind** target (a number/date/boolean computes to `BigDecimal`/`Instant`/`Boolean`, never equal to
 * the raw seed string; kernel-probe-verified, KERNEL-FINDINGS "compute reporting granularity"). A typed
 * `DocumentV2` Number source follows the separate full identity rule: coefficient + scale must both agree,
 * while an equal-looking source String or negative-scale decimal is changed (`NumberResultSourceIdentityLawsTest`).
 * This raw-placement projection must not reconstruct that typed identity from text. Hence:
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
        fun prepare(document: Document, model: ModelDef): KernelComputeProjection {
            val seeded = HashMap<String, SeededCell>()
            for (cell in document.placementsSnapshot()) {
                if (!cell.isGroup && !cell.raw.isNullOrEmpty()) {
                    seeded[formatPointer(cell.path, cell.reps)] = SeededCell(cell.raw, model.kinds[cell.path])
                }
            }
            return KernelComputeProjection(seeded)
        }
    }

    private data class SeededCell(val value: String, val kind: Kind?)
}

/** One-shot compatibility form; repeated callers should prepare [KernelComputeProjection] once. */
fun kernelComputeGranularity(signatures: List<String>, document: Document, model: ModelDef): List<String> =
    KernelComputeProjection.prepare(document, model).project(signatures)

/** Capture-side overload: project against a placement scenario, before a committed case (and its document) exists. */
fun kernelComputeGranularity(signatures: List<String>, placements: List<Placement>, model: ModelDef): List<String> =
    kernelComputeGranularity(signatures, model.newDocument().also { placements.replayInto(it.asSink()) }, model)

/** Canonical cases compare source identity retained by the public reader, as ComputationReport actions do. */
fun kernelCanonicalComputeGranularity(signatures: List<String>, document: Document): List<String> {
    val inputs = document.placementsSnapshot().filterNot { it.isGroup }
        .associateBy { formatPointer(it.path, it.reps) }
    return signatures.filterNot { signature ->
        val input = inputs[signature.substringBefore('|')]
        when {
            "|VALUE|" in signature -> input != null &&
                document.computedValueEquals(input.path, input.reps, signature.substringAfter("|VALUE|"))
            signature.endsWith("|CLEARED") -> input == null || !document.filledAt(input.path, input.reps)
            else -> false
        }
    }
}
