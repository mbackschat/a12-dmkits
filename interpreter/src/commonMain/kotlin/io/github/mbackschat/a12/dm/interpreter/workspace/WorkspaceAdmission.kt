package io.github.mbackschat.a12.dm.interpreter.workspace

import io.github.mbackschat.a12.dm.interpreter.ModelPreparationDiagnostic
import io.github.mbackschat.a12.dm.interpreter.ModelPreparationDiagnosticCode
import io.github.mbackschat.a12.dm.interpreter.ModelPreparationError
import io.github.mbackschat.a12.dm.interpreter.ModelPreparationLimits
import io.github.mbackschat.a12.dm.interpreter.ModelPreparationResource
import io.github.mbackschat.a12.dm.interpreter.WorkspaceModelSource
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
            throw ModelPreparationError.from(
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
                throw ModelPreparationError.from(
                    listOf(
                        InvalidModelSourceDiagnostic(
                            diagnosticLabel = diagnosticLabel,
                            message = "model source cannot be encoded as strict UTF-8",
                        ),
                    ),
                )
            }
            if (byteCount > limits.maxModelBytes) {
                throw ModelPreparationError.from(
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
                throw ModelPreparationError.from(
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
            throw ModelPreparationError.from(diagnostics)
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
