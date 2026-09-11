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

/**
 * Why the checker could not take an input at face value — one vocabulary across the phases that read the
 * source, so a consumer never has to reconcile two parallel diagnostic estates. These are separate from any
 * consistency code: they say the checker could not form a question, not that A12 answered no.
 */
enum class DiagnosticCode {
    /** The source is not well-formed JSON, so nothing in it can be read. */
    SOURCE_NOT_WELL_FORMED,

    /**
     * A single declaration lacks the `type`, `id` or `name` the kernel's own reader requires. Reported and
     * skipped rather than raised, because one broken element must not hide its siblings' real errors.
     */
    DECLARATION_NOT_WELL_FORMED,
}

/**
 * @param at the declared path this concerns, when there is one — absent for a whole-source diagnostic, and
 *     for a declaration too broken to name itself it is the nearest declared ancestor.
 */
data class Diagnostic(
    val code: DiagnosticCode,
    val modelId: String,
    val explain: String,
    val at: String? = null,
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
    data class Rejected(val diagnostic: Diagnostic) : Admission
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
            Diagnostic(
                code = DiagnosticCode.SOURCE_NOT_WELL_FORMED,
                modelId = source.modelId,
                explain = notWellFormed.message ?: "the source is not well-formed JSON",
            ),
        )
    }
