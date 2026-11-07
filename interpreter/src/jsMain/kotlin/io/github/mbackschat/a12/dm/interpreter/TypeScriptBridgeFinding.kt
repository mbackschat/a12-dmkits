@file:OptIn(ExperimentalJsExport::class)

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

internal fun validationFinding(finding: CoreValidationFinding): InterpreterBridgeValidationFinding =
    InterpreterBridgeValidationFinding(
        code = finding.code,
        rulePath = finding.rulePath,
        errorPath = bridgePartiallyKnown(finding.errorPath),
        severity = finding.severity.wireName(),
        type = finding.type.wireName(),
        text = finding.text,
        referenced = finding.referenced.map(::bridgePartiallyKnown).toTypedArray(),
        fillToFix = finding.fillToFix.map(::bridgePartiallyKnown).toTypedArray(),
        possiblyCausing = finding.possiblyCausing.map { group -> group.map(::bridgePartiallyKnown).toTypedArray() }
            .toTypedArray(),
    )

private fun CoreValidationSeverity.wireName(): String = when (this) {
    CoreValidationSeverity.ERROR -> "error"
    CoreValidationSeverity.WARNING -> "warning"
    CoreValidationSeverity.INFO -> "info"
}

private fun CoreValidationFindingType.wireName(): String = when (this) {
    CoreValidationFindingType.VALUE -> "value"
    CoreValidationFindingType.OMISSION -> "omission"
}
