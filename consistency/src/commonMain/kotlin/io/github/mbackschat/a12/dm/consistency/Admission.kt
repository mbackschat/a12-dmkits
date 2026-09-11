package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * A caller-supplied model source.
 *
 * The field names follow the interpreter's `WorkspaceModelSource` (`json`, `diagnosticLabel`) on purpose:
 * the checker owns this type so its published API carries no interpreter dependency, while a consumer of
 * either product meets the same canonical names.
 */
data class ModelSource(
    val modelId: String,
    val json: String,
    val diagnosticLabel: String? = null,
)

/** Why a source could not be admitted. Distinct from any consistency code: admission is a separate phase. */
enum class AdmissionDiagnosticCode {
    SOURCE_NOT_WELL_FORMED,
}

data class AdmissionDiagnostic(
    val code: AdmissionDiagnosticCode,
    val modelId: String,
    val explain: String,
)

/**
 * The outcome of admitting one source — a sum type, because admission and consistency are different
 * questions and a caller must not have to tell them apart by inspecting a diagnostic list.
 */
sealed interface Admission {
    /**
     * [parsed] is retained rather than re-derived: B3's parse-once contract. The baseline interpreter path
     * serializes and reparses between phases, and a checker that inherited that would pay for the round trip
     * and risk losing what the read preserved.
     */
    data class Admitted(val source: ModelSource, val parsed: JsonElement) : Admission
    data class Rejected(val diagnostic: AdmissionDiagnostic) : Admission
}

/**
 * Decide whether [source] is well-formed enough to analyse, without judging its declarations.
 *
 * Parsing goes through `kotlinx-serialization-json`, whose `JsonPrimitive` retains a number's literal text.
 * That is a requirement rather than a convenience: a field configuration's bound or scale must reach a check
 * exactly as authored, and a parse that normalized `250.00` to a binary double would lose it irrecoverably
 * (PORTABLE-CONSISTENCY-PROPOSAL B3, "the applicable JSON fidelity contract on the read path").
 */
fun admit(source: ModelSource): Admission =
    try {
        Admission.Admitted(source, Json.parseToJsonElement(source.json))
    } catch (notWellFormed: IllegalArgumentException) {
        Admission.Rejected(
            AdmissionDiagnostic(
                code = AdmissionDiagnosticCode.SOURCE_NOT_WELL_FORMED,
                modelId = source.modelId,
                explain = notWellFormed.message ?: "the source is not well-formed JSON",
            ),
        )
    }
