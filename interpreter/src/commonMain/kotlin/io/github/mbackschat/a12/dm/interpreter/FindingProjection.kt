package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.model.Message as EngineMessage
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.Severity
import io.github.mbackschat.a12.dm.interpreter.workspace.immutableListSnapshot
import io.github.mbackschat.a12.dm.interpreter.workspace.isImmutableProjection

/**
 * Publish a pointer list across the public boundary — **without copying one that is already immutable**.
 *
 * The engine hands every message of a duplicate cluster the *same* pointer-list instance rather than a private
 * copy per message (IF191). Snapshotting defensively per message therefore copies one N-element list N times:
 * quadratic in both time and memory, and it cancels that optimization at exactly the boundary a library consumer
 * calls. Measured, the façade exhausted the default test heap on 8,000 all-duplicate rows — a document the
 * engine itself handles.
 *
 * The first fix cached the last snapshot per channel, which held for ONE cluster and missed on every message of
 * two interleaved ones — the copy, and the O(N²), came straight back (IF261). Since nothing bounds how many
 * duplicate clusters a document may interleave, no fixed number of slots is defensible. So the engine marks every list it
 * builds immutably (`ImmutableProjection`) and this boundary recognizes the marker: no copy, no cache, no scale
 * at which it degrades. A list from anywhere else is still copied, so the published list is immutable either way.
 */
private fun publishedPointers(values: List<A12PartiallyKnownMultiPointer>): List<A12PartiallyKnownMultiPointer> =
    when {
        values.isEmpty() -> emptyList()
        isImmutableProjection(values) -> values
        else -> immutableListSnapshot(values)
    }

/** The single engine-to-public message projection; internal so common tests can lock the channel boundary. */
internal fun validationFinding(message: EngineMessage): ValidationFinding =
    ValidationFinding(
        code = message.code,
        rulePath = message.rulePath,
        errorPath = message.errorPointer,
        severity = when (message.severity) {
            Severity.ERROR -> ValidationSeverity.ERROR
            Severity.WARNING -> ValidationSeverity.WARNING
            Severity.INFO -> ValidationSeverity.INFO
        },
        type = when (message.type) {
            MsgType.VALUE -> ValidationFindingType.VALUE
            MsgType.OMISSION -> ValidationFindingType.OMISSION
        },
        text = message.text,
        referenced = publishedPointers(message.referencedPointers),
        fillToFix = publishedPointers(message.fillToFixPointers),
        possiblyCausing = immutableListSnapshot(message.groupsOfPossiblyCausingEntities.map(::publishedSet)),
    )

/**
 * Publish one group of possibly-causing entities on the same terms as [publishedPointers]: a duplicate cluster's
 * messages share their cluster's cells (`ClusterCauses`), so a per-finding copy is the O(N²) of IF261 again.
 */
private fun publishedSet(values: Set<A12PartiallyKnownMultiPointer>): Set<A12PartiallyKnownMultiPointer> =
    if (isImmutableProjection(values)) values else PointerSet(values)
