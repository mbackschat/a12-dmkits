@file:OptIn(ExperimentalJsExport::class)

import io.github.mbackschat.a12.dm.interpreter.A12PartiallyKnownMultiPointer as CorePartiallyKnown
import io.github.mbackschat.a12.dm.interpreter.ComputationResult as CoreComputationResult
import io.github.mbackschat.a12.dm.interpreter.ValidationFinding as CoreValidationFinding
import io.github.mbackschat.a12.dm.interpreter.ValidationFindingType as CoreValidationFindingType
import io.github.mbackschat.a12.dm.interpreter.ValidationSeverity as CoreValidationSeverity

// Package-less like TypeScriptBridge.kt, whose bridge this file continues: the finding crossing.

@JsExport
class InterpreterBridgeValidationFinding internal constructor(
    val code: String,
    val rulePath: String,
    val errorPath: InterpreterBridgeParsedPartiallyKnownPointer,
    val severity: String,
    val type: String,
    val text: String,
    val referenced: Array<InterpreterBridgeParsedPartiallyKnownPointer>,
    val fillToFix: Array<InterpreterBridgeParsedPartiallyKnownPointer>,
    val possiblyCausing: Array<Array<InterpreterBridgeParsedPartiallyKnownPointer>>,
)

/**
 * One report's crossing of its findings. A duplicate cluster's findings share their `referenced` list and their cause
 * groups in the engine (INTERPRETER-FINDINGS IF191, IF261), so each shared list, group and pointer is bridged once
 * and the same array is handed to every finding; mapping them per finding allocates N² pointers for an N-row
 * cluster (IF256). Keyed by identity, because equal lists that the engine did not share need no sharing here.
 */
internal class FindingCrossing {
    private val pointers = IdentityMap<CorePartiallyKnown, InterpreterBridgeParsedPartiallyKnownPointer>()
    private val lists = IdentityMap<Collection<CorePartiallyKnown>, Array<InterpreterBridgeParsedPartiallyKnownPointer>>()
    private val groups = IdentityMap<List<Set<CorePartiallyKnown>>, Array<Array<InterpreterBridgeParsedPartiallyKnownPointer>>>()

    fun finding(finding: CoreValidationFinding): InterpreterBridgeValidationFinding =
        InterpreterBridgeValidationFinding(
            code = finding.code,
            rulePath = finding.rulePath,
            errorPath = pointer(finding.errorPath),
            severity = finding.severity.wireName(),
            type = finding.type.wireName(),
            text = finding.text,
            referenced = pointers(finding.referenced),
            fillToFix = pointers(finding.fillToFix),
            possiblyCausing = groups.getOrPut(finding.possiblyCausing) {
                finding.possiblyCausing.map(::pointers).toTypedArray()
            },
        )

    fun computedField(result: CoreComputationResult): InterpreterBridgeComputedField =
        InterpreterBridgeComputedField(
            pointer = InterpreterBridgePointer(result.pointer),
            outcome = result.outcome.wireName(),
            value = result.value,
            diagnostic = result.diagnostic?.let(::finding),
        )

    private fun pointer(pointer: CorePartiallyKnown) = pointers.getOrPut(pointer) { bridgePartiallyKnown(pointer) }

    private fun pointers(list: Collection<CorePartiallyKnown>) = lists.getOrPut(list) { list.map(::pointer).toTypedArray() }
}

/** A JavaScript `Map`, whose keys compare by identity — the sharing the crossing preserves, not value equality. */
@JsName("Map")
private external class IdentityMap<K : Any, V : Any> {
    fun get(key: K): V?
    fun set(key: K, value: V): IdentityMap<K, V>
}

private inline fun <K : Any, V : Any> IdentityMap<K, V>.getOrPut(key: K, make: () -> V): V =
    get(key) ?: make().also { set(key, it) }

private fun CoreValidationSeverity.wireName(): String = when (this) {
    CoreValidationSeverity.ERROR -> "error"
    CoreValidationSeverity.WARNING -> "warning"
    CoreValidationSeverity.INFO -> "info"
}

private fun CoreValidationFindingType.wireName(): String = when (this) {
    CoreValidationFindingType.VALUE -> "value"
    CoreValidationFindingType.OMISSION -> "omission"
}
