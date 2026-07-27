package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.workspace.immutableListSnapshot
import kotlin.jvm.JvmStatic
import kotlin.jvm.JvmSynthetic

/**
 * One immutable raw DM-JSON source supplied to [ModelWorkspace].
 *
 * `diagnosticLabel` is presentation metadata only. Workspace admission retains it only when it is a short,
 * credential-free logical label. `expectedSha256`, when present, must be the lowercase SHA-256 of the exact strict
 * UTF-8 bytes in `json`.
 */
class WorkspaceModelSource private constructor(
    val json: String,
    val diagnosticLabel: String?,
    val expectedSha256: String?,
) {
    companion object {
        @JvmStatic
        fun builder(): Builder = Builder()
    }

    class Builder internal constructor() {
        private var json = ""
        private var diagnosticLabel: String? = null
        private var expectedSha256: String? = null

        fun json(value: String?) = apply {
            if (!value.isNullOrEmpty()) {
                json = value
            }
        }

        fun diagnosticLabel(value: String?) = apply {
            if (!value.isNullOrEmpty()) {
                diagnosticLabel = value
            }
        }

        fun expectedSha256(value: String?) = apply {
            if (!value.isNullOrEmpty()) {
                expectedSha256 = value
            }
        }

        fun build(): WorkspaceModelSource =
            WorkspaceModelSource(json, diagnosticLabel, expectedSha256)
    }
}

/**
 * Resource bounds for source admission and deterministic preparation. Caller values are capped by the hard
 * implementation maxima so an API consumer cannot accidentally disable the browser-safe denial-of-service guards.
 */
class ModelPreparationLimits private constructor(
    val maxModelCount: Int,
    val maxModelBytes: Int,
    val maxTotalBytes: Int,
    val maxPreparedBytes: Int,
    val maxReferenceDepth: Int,
    val maxReferenceEdges: Int,
    val maxStructureDepth: Int,
    val maxPreparedElements: Int,
) {
    companion object {
        const val DEFAULT_MAX_MODEL_COUNT = 256
        const val DEFAULT_MAX_MODEL_BYTES = 4 * 1024 * 1024
        const val DEFAULT_MAX_TOTAL_BYTES = 32 * 1024 * 1024
        const val DEFAULT_MAX_PREPARED_BYTES = 64 * 1024 * 1024
        const val DEFAULT_MAX_REFERENCE_DEPTH = 32
        const val DEFAULT_MAX_REFERENCE_EDGES = 4_096
        const val DEFAULT_MAX_STRUCTURE_DEPTH = 64
        const val DEFAULT_MAX_PREPARED_ELEMENTS = 250_000

        const val HARD_MAX_MODEL_COUNT = 1_024
        const val HARD_MAX_MODEL_BYTES = 16 * 1024 * 1024
        const val HARD_MAX_TOTAL_BYTES = 128 * 1024 * 1024
        const val HARD_MAX_PREPARED_BYTES = 256 * 1024 * 1024
        const val HARD_MAX_REFERENCE_DEPTH = 128
        const val HARD_MAX_REFERENCE_EDGES = 65_536
        const val HARD_MAX_STRUCTURE_DEPTH = 128
        const val HARD_MAX_PREPARED_ELEMENTS = 1_000_000

        @JvmStatic
        fun defaults(): ModelPreparationLimits = Builder().build()

        @JvmStatic
        fun builder(): Builder = Builder()
    }

    class Builder internal constructor() {
        private var maxModelCount = DEFAULT_MAX_MODEL_COUNT
        private var maxModelBytes = DEFAULT_MAX_MODEL_BYTES
        private var maxTotalBytes = DEFAULT_MAX_TOTAL_BYTES
        private var maxPreparedBytes = DEFAULT_MAX_PREPARED_BYTES
        private var maxReferenceDepth = DEFAULT_MAX_REFERENCE_DEPTH
        private var maxReferenceEdges = DEFAULT_MAX_REFERENCE_EDGES
        private var maxStructureDepth = DEFAULT_MAX_STRUCTURE_DEPTH
        private var maxPreparedElements = DEFAULT_MAX_PREPARED_ELEMENTS

        fun maxModelCount(value: Int) = apply { maxModelCount = positive("maxModelCount", value) }
        fun maxModelBytes(value: Int) = apply { maxModelBytes = positive("maxModelBytes", value) }
        fun maxTotalBytes(value: Int) = apply { maxTotalBytes = positive("maxTotalBytes", value) }
        fun maxPreparedBytes(value: Int) = apply { maxPreparedBytes = positive("maxPreparedBytes", value) }
        fun maxReferenceDepth(value: Int) = apply { maxReferenceDepth = positive("maxReferenceDepth", value) }
        fun maxReferenceEdges(value: Int) = apply { maxReferenceEdges = positive("maxReferenceEdges", value) }
        fun maxStructureDepth(value: Int) = apply { maxStructureDepth = positive("maxStructureDepth", value) }
        fun maxPreparedElements(value: Int) = apply { maxPreparedElements = positive("maxPreparedElements", value) }

        fun build(): ModelPreparationLimits = ModelPreparationLimits(
            maxModelCount = minOf(maxModelCount, HARD_MAX_MODEL_COUNT),
            maxModelBytes = minOf(maxModelBytes, HARD_MAX_MODEL_BYTES),
            maxTotalBytes = minOf(maxTotalBytes, HARD_MAX_TOTAL_BYTES),
            maxPreparedBytes = minOf(maxPreparedBytes, HARD_MAX_PREPARED_BYTES),
            maxReferenceDepth = minOf(maxReferenceDepth, HARD_MAX_REFERENCE_DEPTH),
            maxReferenceEdges = minOf(maxReferenceEdges, HARD_MAX_REFERENCE_EDGES),
            maxStructureDepth = minOf(maxStructureDepth, HARD_MAX_STRUCTURE_DEPTH),
            maxPreparedElements = minOf(maxPreparedElements, HARD_MAX_PREPARED_ELEMENTS),
        )

        private fun positive(name: String, value: Int): Int {
            require(value > 0) { "$name must be positive" }
            return value
        }
    }
}

/** The role of one directed edge in the raw-model preparation closure. */
enum class ModelReferencePurpose(
    @get:JvmSynthetic internal val wireName: String,
) {
    INCLUDE("include"),
    TYPE_DEFINITIONS("typeDefinitions");

    companion object {
        @JvmSynthetic
        internal fun fromWireName(value: String): ModelReferencePurpose? = when (value) {
            INCLUDE.wireName -> INCLUDE
            TYPE_DEFINITIONS.wireName -> TYPE_DEFINITIONS
            else -> null
        }
    }
}

/** One exact directed edge traversed while preparing a workspace closure. */
@ConsistentCopyVisibility
data class ModelReferenceStep private constructor(
    val fromModelId: String,
    val purpose: ModelReferencePurpose,
    val toModelId: String,
    val alias: String?,
) {
    companion object {
        @JvmSynthetic
        internal fun typeDefinitions(
            fromModelId: String,
            toModelId: String,
            alias: String?,
        ): ModelReferenceStep = ModelReferenceStep(
            fromModelId = fromModelId,
            purpose = ModelReferencePurpose.TYPE_DEFINITIONS,
            toModelId = toModelId,
            alias = alias,
        )

        @JvmSynthetic
        internal fun include(
            fromModelId: String,
            toModelId: String,
            alias: String?,
        ): ModelReferenceStep = ModelReferenceStep(
            fromModelId = fromModelId,
            purpose = ModelReferencePurpose.INCLUDE,
            toModelId = toModelId,
            alias = alias,
        )
    }
}

/** One distinct resolved preparation edge and the digest of the exact source bytes that satisfied it. */
@ConsistentCopyVisibility
data class ModelDependency private constructor(
    val fromModelId: String,
    val toModelId: String,
    val purpose: ModelReferencePurpose,
    val alias: String?,
    val diagnosticLabel: String?,
    val sha256: String,
) {
    companion object {
        @JvmSynthetic
        internal fun resolved(
            step: ModelReferenceStep,
            diagnosticLabel: String?,
            sha256: String,
        ): ModelDependency = ModelDependency(
            fromModelId = step.fromModelId,
            toModelId = step.toModelId,
            purpose = step.purpose,
            alias = step.alias,
            diagnosticLabel = diagnosticLabel,
            sha256 = sha256,
        )
    }
}

enum class ModelPreparationDiagnosticCode {
    /** A source or prepared element does not satisfy the portable DM-JSON boundary. */
    INVALID_MODEL_SOURCE,
    /** More than one admitted source declares the same exact `header.id`. */
    DUPLICATE_MODEL_ID,
    /** The requested entry id is absent from the finite workspace. */
    ENTRY_MODEL_NOT_FOUND,
    /** A required include or type-definition model id is absent from the workspace. */
    UNRESOLVED_MODEL_REFERENCE,
    /** A provider refused or failed one exact-id acquisition request. */
    MODEL_SOURCE_PROVIDER_FAILED,
    /** A supplied expected SHA-256 does not match the exact admitted source bytes. */
    MODEL_SOURCE_INTEGRITY_MISMATCH,
    /** A provider returned a source whose `header.id` differs from the requested exact id. */
    MODEL_SOURCE_ID_MISMATCH,
    /** The transitive include/type-definition graph returns to a model already on the active path. */
    MODEL_REFERENCE_CYCLE,
    /** An include directive cannot be mounted without discarding or inventing authored structure. */
    INVALID_INCLUDE_MOUNT,
    /** Distinct source models declare the same type-definition id. */
    AMBIGUOUS_TYPE_DEFINITION,
    /** A mounted executable reference cannot be mapped to one final model path. */
    UNMAPPED_INCLUDED_REFERENCE,
    /** Mounted executable DSL uses a condition language incompatible with the entry model. */
    INCOMPATIBLE_CONDITION_LANGUAGE,
    /** Expanded input still contains an include, type-definition reference, model reference, or opaque id. */
    UNPREPARED_MODEL_RESIDUE,
    /** Admission or preparation exceeded one configured portable resource ceiling. */
    RESOURCE_LIMIT_EXCEEDED,
    /** A date precision is Kernel-recognized but outside the interpreter's supported set. */
    UNSUPPORTED_DATE_PRECISION,
    /** A Kernel-legal model time-zone id is outside the interpreter's supported set. */
    UNSUPPORTED_TIME_ZONE,
}

enum class ModelPreparationResource {
    /** Number of admitted source models. */
    MODEL_COUNT,
    /** Strict UTF-8 bytes in one admitted source. */
    MODEL_BYTES,
    /** Strict UTF-8 bytes across all admitted sources. */
    TOTAL_BYTES,
    /** Compact strict UTF-8 bytes in the assembled expanded model. */
    PREPARED_BYTES,
    /** Active model-reference chain depth. */
    REFERENCE_DEPTH,
    /** Traversed include/type-definition edge occurrences. */
    REFERENCE_EDGES,
    /** Nested document-element depth. */
    STRUCTURE_DEPTH,
    /** Expanded document-element occurrences materialized during preparation. */
    PREPARED_ELEMENTS,
}

/**
 * One structured preparation failure. Only properties meaningful for [code] are populated; [message] is
 * presentation text and is never the sole carrier of corrective data exposed by this contract.
 */
interface ModelPreparationDiagnostic {
    val code: ModelPreparationDiagnosticCode
    val diagnosticLabel: String? get() = null
    val requestedModelId: String? get() = null
    val actualModelId: String? get() = null
    val providerFailureReason: ModelSourceProviderFailureReason? get() = null
    val expectedSha256: String? get() = null
    val actualSha256: String? get() = null
    val modelId: String? get() = null
    val path: String? get() = null
    val reference: String? get() = null
    val referenceChain: List<ModelReferenceStep> get() = emptyList()
    val conflictingDiagnosticLabels: List<String> get() = emptyList()
    val resource: ModelPreparationResource? get() = null
    val maximum: Long? get() = null
    val actual: Long? get() = null
    val firstSourceModelId: String? get() = null
    val conflictingSourceModelId: String? get() = null
    val sourceLanguage: String? get() = null
    val entryLanguage: String? get() = null
    val reason: String? get() = null
    val cyclic: Boolean? get() = null
    val message: String
}

/** Aggregate failure for source admission or model preparation. */
class ModelPreparationError private constructor(
    diagnostics: List<ModelPreparationDiagnostic>,
    cause: Throwable?,
) : RuntimeException(diagnostics.joinToString("; ") { it.message }, cause) {
    val code: String = "A12_MODEL_PREPARATION_FAILED"
    val diagnostics: List<ModelPreparationDiagnostic> = immutableListSnapshot(diagnostics)

    companion object {
        @JvmSynthetic
        internal fun from(
            diagnostics: List<ModelPreparationDiagnostic>,
            cause: Throwable? = null,
        ): ModelPreparationError =
            ModelPreparationError(diagnostics, cause)
    }
}

/** Non-throwing result of preparing one model for evaluation. */
sealed interface ModelPreparationResult {
    data class Success(val model: PreparedModel) : ModelPreparationResult
    data class Failure(val error: ModelPreparationError) : ModelPreparationResult
}

/** Non-throwing result of assembler-only expanded DM-JSON generation. */
sealed interface ModelExpansionResult {
    data class Success(val json: String) : ModelExpansionResult
    data class Failure(val error: ModelPreparationError) : ModelExpansionResult
}

/** Opaque process- or Worker-local identity for one independently prepared model version. */
class ModelVersionIdentity private constructor() {
    companion object {
        @JvmSynthetic
        internal fun create(): ModelVersionIdentity = ModelVersionIdentity()
    }
}

/** Opaque process- or Worker-local authenticity domain for objects created by one [PreparedModel]. */
class ModelOwnerIdentity private constructor() {
    companion object {
        @JvmSynthetic
        internal fun create(): ModelOwnerIdentity = ModelOwnerIdentity()
    }
}

/** Common ownership contract for model-bound runtime values. */
interface ModelOwned {
    val owner: ModelOwnerIdentity
    val modelVersion: ModelVersionIdentity
}
