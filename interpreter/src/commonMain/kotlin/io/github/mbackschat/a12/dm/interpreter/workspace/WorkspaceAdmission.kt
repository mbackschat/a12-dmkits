package io.github.mbackschat.a12.dm.interpreter.workspace

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private class ImmutableListSnapshot<T>(values: Collection<T>) : AbstractList<T>() {
    private val values = values.toList()
    override val size: Int get() = values.size
    override fun get(index: Int): T = values[index]
}

internal fun <T> immutableListSnapshot(values: Collection<T>): List<T> = ImmutableListSnapshot(values)

/**
 * Count strict UTF-8 bytes without allocating them. Null marks one unpaired UTF-16 surrogate. This pass lets
 * admission enforce the source and remaining-workspace ceilings before it creates the frozen byte array.
 */
internal fun strictUtf8ByteCount(text: String): Long? {
    var count = 0L
    var index = 0
    while (index < text.length) {
        val codeUnit = text[index].code
        when {
            codeUnit <= 0x7f -> count += 1
            codeUnit <= 0x7ff -> count += 2
            codeUnit in 0xd800..0xdbff -> {
                if (index + 1 >= text.length || text[index + 1].code !in 0xdc00..0xdfff) {
                    return null
                }
                count += 4
                index++
            }
            codeUnit in 0xdc00..0xdfff -> return null
            else -> count += 3
        }
        index++
    }
    return count
}

/** One immutable caller-supplied raw DM-JSON source before admission freezes its exact UTF-8 bytes. */
internal class WorkspaceModelSource private constructor(
    val json: String,
    val diagnosticLabel: String?,
    val expectedSha256: String?,
) {
    companion object {
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

        fun build(): WorkspaceModelSource = WorkspaceModelSource(json, diagnosticLabel, expectedSha256)
    }
}

/**
 * Resource bounds for source admission and preparation. Defaults suit browser use; callers may tighten or widen
 * them only up to the hard maxima. Graph and model assembly consume the reference, structure, element, and
 * prepared-output bounds.
 */
internal class ModelPreparationLimits private constructor(
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

        fun defaults(): ModelPreparationLimits = Builder().build()

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

internal enum class ModelPreparationDiagnosticCode {
    INVALID_MODEL_SOURCE,
    DUPLICATE_MODEL_ID,
    ENTRY_MODEL_NOT_FOUND,
    UNRESOLVED_MODEL_REFERENCE,
    MODEL_SOURCE_INTEGRITY_MISMATCH,
    MODEL_REFERENCE_CYCLE,
    INVALID_INCLUDE_MOUNT,
    AMBIGUOUS_TYPE_DEFINITION,
    UNMAPPED_INCLUDED_REFERENCE,
    INCOMPATIBLE_CONDITION_LANGUAGE,
    UNPREPARED_MODEL_RESIDUE,
    RESOURCE_LIMIT_EXCEEDED,
}

internal enum class ModelPreparationResource {
    MODEL_COUNT,
    MODEL_BYTES,
    TOTAL_BYTES,
    PREPARED_BYTES,
    REFERENCE_DEPTH,
    REFERENCE_EDGES,
    STRUCTURE_DEPTH,
    PREPARED_ELEMENTS,
}

/** Closed workspace-preparation diagnostic family; each subtype carries only fields valid for that failure kind. */
internal sealed interface ModelPreparationDiagnostic {
    val code: ModelPreparationDiagnosticCode
    val diagnosticLabel: String? get() = null
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
    val message: String
}

private data class InvalidModelSourceDiagnostic(
    override val diagnosticLabel: String?,
    override val message: String,
) : ModelPreparationDiagnostic {
    override val code = ModelPreparationDiagnosticCode.INVALID_MODEL_SOURCE
}

private data class DuplicateModelIdDiagnostic(
    override val modelId: String,
    override val conflictingDiagnosticLabels: List<String>,
) : ModelPreparationDiagnostic {
    override val code = ModelPreparationDiagnosticCode.DUPLICATE_MODEL_ID
    override val message = "duplicate model header.id '$modelId'"
}

private data class IntegrityMismatchDiagnostic(
    override val diagnosticLabel: String?,
    override val expectedSha256: String,
    override val actualSha256: String,
) : ModelPreparationDiagnostic {
    override val code = ModelPreparationDiagnosticCode.MODEL_SOURCE_INTEGRITY_MISMATCH
    override val message = "model source SHA-256 does not match its expected digest"
}

internal data class ResourceLimitDiagnostic(
    override val resource: ModelPreparationResource,
    override val maximum: Long,
    override val actual: Long,
    override val diagnosticLabel: String? = null,
) : ModelPreparationDiagnostic {
    override val code = ModelPreparationDiagnosticCode.RESOURCE_LIMIT_EXCEEDED
    override val message = if (
        resource == ModelPreparationResource.PREPARED_BYTES &&
        actual == maximum + 1
    ) {
        "workspace preparation exceeded ${resource.name.lowercase()} limit $maximum (observed at least $actual)"
    } else {
        "workspace preparation exceeded ${resource.name.lowercase()} limit $maximum (actual $actual)"
    }
}

/** Typed aggregate failure; messages contain only model identities and already-sanitized labels. */
internal class ModelPreparationError(diagnostics: List<ModelPreparationDiagnostic>) :
    IllegalArgumentException(diagnostics.joinToString("; ") { it.message }) {
    val diagnostics: List<ModelPreparationDiagnostic> = immutableListSnapshot(diagnostics)
}

/** One admitted source whose bytes, digest, identity, and presentation label cannot be mutated by the caller. */
internal class AdmittedWorkspaceModelSource internal constructor(
    val modelId: String,
    val diagnosticLabel: String?,
    val sha256: String,
    bytes: ByteArray,
) {
    private val bytes = bytes.copyOf()
    val json: String = this.bytes.decodeToString(throwOnInvalidSequence = true)

    fun bytesCopy(): ByteArray = bytes.copyOf()
}

/** Immutable exact-id index produced before include/type-definition assembly begins. */
internal class AdmittedWorkspace internal constructor(sources: List<AdmittedWorkspaceModelSource>) {
    private val byModelId = sources.associateByTo(LinkedHashMap(), AdmittedWorkspaceModelSource::modelId)
    val modelIds: List<String> = immutableListSnapshot(byModelId.keys)

    fun source(modelId: String): AdmittedWorkspaceModelSource? = byModelId[modelId]
}

/**
 * The synchronous, platform-neutral source-admission boundary. It freezes UTF-8 bytes first, applies bounds before
 * parsing, verifies integrity, extracts the exact header identity, sanitizes presentation labels, and refuses
 * duplicate identities without input-order selection.
 */
internal object WorkspaceSourceAdmission {
    private val digestPattern = Regex("[0-9a-f]{64}")
    private val safeLabelPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}(:[A-Za-z0-9][A-Za-z0-9._-]{0,63})?")
    private data class FrozenSource(
        val supplied: WorkspaceModelSource,
        val diagnosticLabel: String?,
        val bytes: ByteArray,
    )

    fun admit(
        sources: List<WorkspaceModelSource>,
        limits: ModelPreparationLimits = ModelPreparationLimits.defaults(),
    ): AdmittedWorkspace {
        val snapshot = sources.toList()
        if (snapshot.size > limits.maxModelCount) {
            throw ModelPreparationError(
                listOf(
                    ResourceLimitDiagnostic(
                        resource = ModelPreparationResource.MODEL_COUNT,
                        maximum = limits.maxModelCount.toLong(),
                        actual = snapshot.size.toLong(),
                    ),
                ),
            )
        }

        val frozen = mutableListOf<FrozenSource>()
        var totalBytes = 0L
        for (source in snapshot) {
            val diagnosticLabel = sanitizeLabel(source.diagnosticLabel)
            val byteCount = strictUtf8ByteCount(source.json)
            if (byteCount == null) {
                throw ModelPreparationError(
                    listOf(
                        InvalidModelSourceDiagnostic(
                            diagnosticLabel = diagnosticLabel,
                            message = "model source cannot be encoded as strict UTF-8",
                        ),
                    ),
                )
            }
            if (byteCount > limits.maxModelBytes) {
                throw ModelPreparationError(
                    listOf(
                        ResourceLimitDiagnostic(
                            resource = ModelPreparationResource.MODEL_BYTES,
                            maximum = limits.maxModelBytes.toLong(),
                            actual = byteCount,
                            diagnosticLabel = diagnosticLabel,
                        ),
                    ),
                )
            }
            totalBytes += byteCount
            if (totalBytes > limits.maxTotalBytes) {
                throw ModelPreparationError(
                    listOf(
                        ResourceLimitDiagnostic(
                            resource = ModelPreparationResource.TOTAL_BYTES,
                            maximum = limits.maxTotalBytes.toLong(),
                            actual = totalBytes,
                        ),
                    ),
                )
            }
            val bytes = source.json.encodeToByteArray(throwOnInvalidSequence = true)
            check(bytes.size.toLong() == byteCount) {
                "strict UTF-8 byte count and encoder disagree"
            }
            frozen += FrozenSource(source, diagnosticLabel, bytes)
        }

        val diagnostics = mutableListOf<ModelPreparationDiagnostic>()
        val candidates = mutableListOf<AdmittedWorkspaceModelSource>()
        for (source in frozen) {
            val label = source.diagnosticLabel
            val expectedDigest = source.supplied.expectedSha256
            if (expectedDigest != null && !digestPattern.matches(expectedDigest)) {
                diagnostics += InvalidModelSourceDiagnostic(
                    label,
                    "expectedSha256 must be a 64-character lowercase hexadecimal SHA-256",
                )
                continue
            }
            val actualDigest = Sha256.hex(source.bytes)
            if (expectedDigest != null && expectedDigest != actualDigest) {
                diagnostics += IntegrityMismatchDiagnostic(
                    diagnosticLabel = label,
                    expectedSha256 = expectedDigest,
                    actualSha256 = actualDigest,
                )
                continue
            }

            val modelId = headerId(source.bytes.decodeToString(), label, diagnostics) ?: continue
            candidates += AdmittedWorkspaceModelSource(
                modelId = modelId,
                diagnosticLabel = label,
                sha256 = actualDigest,
                bytes = source.bytes,
            )
        }
        candidates.groupBy(AdmittedWorkspaceModelSource::modelId)
            .filterValues { it.size > 1 }
            .forEach { (modelId, duplicates) ->
                diagnostics += DuplicateModelIdDiagnostic(
                    modelId = modelId,
                    conflictingDiagnosticLabels = immutableListSnapshot(
                        duplicates.mapNotNull(AdmittedWorkspaceModelSource::diagnosticLabel).distinct(),
                    ),
                )
            }

        if (diagnostics.isNotEmpty()) {
            throw ModelPreparationError(diagnostics)
        }
        return AdmittedWorkspace(candidates)
    }

    private fun headerId(
        source: String,
        label: String?,
        diagnostics: MutableList<ModelPreparationDiagnostic>,
    ): String? {
        val root = try {
            Json.parseToJsonElement(source) as? JsonObject
        } catch (_: SerializationException) {
            null
        }
        val header = root?.get("header") as? JsonObject
        val idNode = header?.get("id") as? JsonPrimitive
        val id = idNode?.takeIf { it.isString }?.content
        if (root != null && containsUnpairedSurrogate(root)) {
            diagnostics += InvalidModelSourceDiagnostic(
                label,
                "model source JSON strings must contain only Unicode scalar values",
            )
            return null
        }
        if (id.isNullOrBlank()) {
            diagnostics += InvalidModelSourceDiagnostic(
                label,
                if (root == null) "model source is not valid JSON object" else "model source requires a string header.id",
            )
            return null
        }
        return id
    }

    private fun containsUnpairedSurrogate(root: JsonObject): Boolean {
        val pending = mutableListOf<JsonElement>(root)
        while (pending.isNotEmpty()) {
            when (val element = pending.removeAt(pending.lastIndex)) {
                is JsonObject -> {
                    if (element.keys.any { strictUtf8ByteCount(it) == null }) {
                        return true
                    }
                    pending.addAll(element.values)
                }
                is JsonArray -> pending.addAll(element)
                is JsonPrimitive -> {
                    if (element.isString && strictUtf8ByteCount(element.content) == null) {
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun sanitizeLabel(label: String?): String? =
        label?.takeIf { it.length <= 129 && safeLabelPattern.matches(it) }
}
