package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A caller-supplied model source.
 *
 * The field names and their *shape* follow the interpreter's `WorkspaceModelSource` on purpose: the checker
 * owns this type so its published API carries no interpreter dependency, while a consumer of either product
 * meets the same canonical names. That includes the absence a reader might not notice — **there is no
 * caller-supplied model id**, because the peer extracts the exact `header.id` from the source rather than
 * trusting a parameter, and an identity the caller asserts is an echo rather than a retention.
 */
data class ModelSource(
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
     * The source is well-formed JSON but declares no `header.id`, so it cannot be identified. A12 puts the
     * model's identity there and every reference between sources resolves through it, so a source without
     * one is not a DocumentModel this checker can attribute anything to.
     */
    SOURCE_HAS_NO_IDENTITY,

    /**
     * The source carries no `content` object, which is the one shape above the model root that A12's own
     * reader cannot take: it raises there, while an empty `rootGroups`, an absent `rootGroups` and even an
     * absent `modelRoot` are all read without complaint (measured 2026-09-12, `ModelRootReadabilityLawsTest`).
     * Refusing exactly this far and no further is what keeps a type-def-only library — an empty root, and
     * Kernel-valid standalone — admissible.
     */
    SOURCE_HAS_NO_MODEL_CONTENT,

    /**
     * A workspace was asked about a model none of its sources declares. Separate from an unreadable source:
     * every source here was read fine, and the id simply names none of them.
     */
    SOURCE_NOT_IN_WORKSPACE,

    /**
     * Two supplied sources declare the same `header.id`. An include resolves by that id, so a duplicate makes
     * which model gets mounted a function of argument order — reported rather than silently resolved.
     */
    SOURCE_IDENTITY_NOT_UNIQUE,

    /**
     * A single declaration lacks the `type`, `id` or `name` the kernel's own reader requires. Reported and
     * skipped rather than raised, because one broken element must not hide its siblings' real errors.
     */
    DECLARATION_NOT_WELL_FORMED,
}

/**
 * @param modelId the source's own `header.id`, absent when admission could not read one.
 * @param at the declared path this concerns, when there is one — absent for a whole-source diagnostic, and
 *     for a declaration too broken to name itself it is the nearest path that could be derived.
 * @param diagnosticLabel the source's caller-supplied label, carried through so a consumer can name the file
 *     a finding came from rather than only the model it was attributed to.
 */
data class Diagnostic(
    val code: DiagnosticCode,
    val modelId: String?,
    val explain: String,
    val at: String? = null,
    val diagnosticLabel: String? = null,
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
     *
     * @param modelId the source's own `header.id`, extracted rather than asserted.
     */
    data class Admitted(val source: ModelSource, val modelId: String, val parsed: JsonElement) : Admission
    data class Rejected(val diagnostic: Diagnostic) : Admission
}

/**
 * Decide whether [source] is well-formed enough to analyse, without judging its declarations.
 *
 * Parsing goes through `kotlinx-serialization-json`, whose `JsonPrimitive` retains a number's literal text.
 * That is a requirement rather than a convenience: a field configuration's bound or scale must reach a check
 * exactly as authored, and a parse that normalized `250.00` to a binary double would lose it irrecoverably
 * (PORTABLE-CONSISTENCY-PROPOSAL B3, "the applicable JSON fidelity contract on the read path").
 *
 * **Comments are admitted because A12 admits them.** Both Kernel mappers are built with
 * `JsonParser.Feature.ALLOW_COMMENTS` (`ObjectMapperProvider.createPreconfiguredObjectMapper`, and again for
 * the document serializer), so a commented model is a legal A12 artifact its own tooling round-trips —
 * refusing one would make a readable file unreadable here. Nothing else is relaxed: neither mapper enables
 * `ALLOW_TRAILING_COMMA`, so a trailing comma stays a parse error. This is a parity decision, never a
 * be-forgiving one, and it mirrors `:interpreter`'s `A12_JSON` rather than inventing a second policy.
 */
@OptIn(ExperimentalSerializationApi::class)
private val A12_JSON: Json = Json { allowComments = true }

fun admit(source: ModelSource): Admission {
    val parsed = try {
        A12_JSON.parseToJsonElement(source.json)
    } catch (notWellFormed: IllegalArgumentException) {
        return source.rejected(
            DiagnosticCode.SOURCE_NOT_WELL_FORMED,
            notWellFormed.message ?: "the source is not well-formed JSON",
        )
    }
    val root = parsed as? JsonObject
    val modelId = (root?.get("header") as? JsonObject)
        ?.get("id")?.let { (it as? JsonPrimitive)?.contentOrNull }
    if (modelId == null || modelId.isUnicodeBlank()) {
        return source.rejected(DiagnosticCode.SOURCE_HAS_NO_IDENTITY, "the source declares no `header.id`")
    }
    if (root["content"] !is JsonObject) {
        return source.rejected(
            DiagnosticCode.SOURCE_HAS_NO_MODEL_CONTENT,
            "the source declares no `content` object, so it is not a DocumentModel A12 could read",
        )
    }
    return Admission.Admitted(source, modelId, parsed)
}

private fun ModelSource.rejected(code: DiagnosticCode, explain: String) =
    Admission.Rejected(Diagnostic(code, modelId = null, explain = explain, diagnosticLabel = diagnosticLabel))
