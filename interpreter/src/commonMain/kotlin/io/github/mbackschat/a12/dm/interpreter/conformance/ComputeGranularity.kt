package io.github.mbackschat.a12.dm.interpreter.conformance

import io.github.mbackschat.a12.dm.interpreter.eval.formatPointer
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef

// Escaped closing bracket — Kotlin/JS compiles regexes with the `u` flag, where an unescaped `]` is a SyntaxError.
private val ROW_INDEX = Regex("""\[\d+\]""")

/**
 * Project the interpreter's report-ALL compute signatures onto the **kernel's compute reporting granularity**,
 * for comparison against a kernel-captured expectation (CONFORMANCE-CORPUS-SPEC §7).
 *
 * The kernel's `IDocumentComputationResult` reports:
 * - a **VALUE** only when it *changed* vs the input document — a TYPED-value equality
 *   (`getComputedFieldInstancesWithChanges`, `fieldValuesEqual(original, computed)`);
 * - a **CLEARED** instance only when the input document had the cell *filled* (`getClearedFieldInstances`);
 * - an **ERRORED** instance unconditionally (`getComputedFieldInstancesWithErrors`).
 *
 * `DmInterpreter.compute` deliberately reports **every** computed instance — report-all is the more useful
 * library contract, and result-envelope granularity is not behavior-parity territory (the IG47 ruling) — so
 * every comparison surface that diffs the interpreter against a kernel-granularity multiset projects through
 * this function first, diffing the interpreter's outcomes against the input placements.
 *
 * On a placement-built input document the cell values are raw **strings**, so the kernel's typed change
 * equality can hold only for a **STRING-kind** target (a number/date/boolean computes to a typed value —
 * `BigDecimal`/`Instant`/`Boolean` — never equal to the raw seed string; kernel-probe-verified,
 * KERNEL-FINDINGS "compute reporting granularity"). Hence:
 * - a `VALUE` equal to the cell's placement value is dropped iff the target is [Kind.STRING];
 * - a `CLEARED` on a cell no placement filled (unseeded, or an [PlacementKind.EMPTY] placement) is dropped;
 * - an `ERRORED` is always kept.
 */
fun kernelComputeGranularity(signatures: List<String>, placements: List<Placement>, model: ModelDef): List<String> {
    val seeded = HashMap<String, String>()
    for (p in placements) {
        if (p.kind == PlacementKind.FIELD) seeded[formatPointer(p.path, p.reps)] = p.value ?: ""
    }
    return signatures.filterNot { sig ->
        val pointer = sig.substringBefore('|')
        when {
            "|VALUE|" in sig ->
                seeded[pointer] == sig.substringAfter("|VALUE|") &&
                    model.kinds[pointer.replace(ROW_INDEX, "")] == Kind.STRING
            sig.endsWith("|CLEARED") -> pointer !in seeded
            else -> false
        }
    }
}
