package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.workspace.ParsedWorkspaceSource
import io.github.mbackschat.a12.dm.interpreter.workspace.UnresolvedModelReferenceDiagnostic
import io.github.mbackschat.a12.dm.interpreter.workspace.WorkspaceReferenceDiscovery
import io.github.mbackschat.a12.dm.interpreter.workspace.WorkspaceReferenceDiscoveryMode
import io.github.mbackschat.a12.dm.interpreter.workspace.WorkspaceReferenceTraversal
import io.github.mbackschat.a12.dm.interpreter.workspace.WorkspaceSourceAdmission
import io.github.mbackschat.a12.dm.interpreter.workspace.resourceLimit
import io.github.mbackschat.a12.dm.interpreter.workspace.sanitizeDiagnosticMetadata
import io.github.mbackschat.a12.dm.interpreter.workspace.strictUtf8ByteCount
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.jvm.JvmStatic
import kotlin.jvm.JvmSynthetic

/**
 * Snapshot-scoped host port that resolves one exact logical model id without exposing storage machinery.
 * [snapshotRevision] is optional provenance and is retained only when it is a safe logical label.
 */
interface ModelSourceProvider {
    val snapshotRevision: String? get() = null

    suspend fun load(
        modelId: String,
        context: ModelSourceLoadContext,
    ): WorkspaceModelSource?
}

/** Portable cancellation state shared with the provider and checked between exact-id loads. */
interface ModelCollectionSignal {
    val isCancelled: Boolean

    companion object {
        /** Signal that never cancels collection. */
        val NONE: ModelCollectionSignal = object : ModelCollectionSignal {
            override val isCancelled = false
        }
    }
}

/** Effective acquisition ceiling and cancellation state for one provider call. */
class ModelSourceLoadContext private constructor(
    val maximumBytes: Int,
    val signal: ModelCollectionSignal,
) {
    companion object {
        @JvmSynthetic
        internal fun create(
            maximumBytes: Int,
            signal: ModelCollectionSignal,
        ): ModelSourceLoadContext =
            ModelSourceLoadContext(maximumBytes, signal)
    }
}

/** Stable reason supplied by a provider that cannot return one requested source. */
enum class ModelSourceProviderFailureReason {
    /** The caller lacks authorization for the requested model. */
    ACCESS_DENIED,
    /** The backing source is temporarily or permanently unavailable. */
    UNAVAILABLE,
    /** Acquisition was cancelled before a complete source could be returned. */
    CANCELLED,
    /** The provider stopped acquisition at the supplied byte ceiling. */
    SOURCE_TOO_LARGE,
    /** The provider detected that its pinned source failed integrity verification. */
    INTEGRITY_MISMATCH,
    /** Acquisition failed for a reason outside the stable provider taxonomy. */
    PROVIDER_FAILED,
}

/** Typed provider-owned acquisition failure. Collection retains only its stable reason and cause. */
class ModelSourceProviderError private constructor(
    val reason: ModelSourceProviderFailureReason,
    val modelId: String,
    message: String,
    cause: Throwable?,
) : RuntimeException(message, cause) {
    val code: String = "A12_MODEL_SOURCE_PROVIDER_FAILED"

    companion object {
        @JvmStatic
        fun builder(): Builder = Builder()
    }

    class Builder internal constructor() {
        private var reason: ModelSourceProviderFailureReason? = null
        private var modelId = ""
        private var message: String? = null
        private var cause: Throwable? = null

        fun reason(value: ModelSourceProviderFailureReason?) = apply {
            if (value != null) {
                reason = value
            }
        }

        fun modelId(value: String?) = apply {
            if (!value.isNullOrEmpty()) {
                modelId = value
            }
        }

        fun message(value: String?) = apply {
            if (!value.isNullOrEmpty()) {
                message = value
            }
        }

        fun cause(value: Throwable?) = apply {
            if (value != null) {
                cause = value
            }
        }

        fun build(): ModelSourceProviderError {
            val requiredReason = checkNotNull(reason) { "reason is required" }
            require(modelId.isNotEmpty()) { "modelId is required" }
            return ModelSourceProviderError(
                reason = requiredReason,
                modelId = modelId,
                message = message ?: "provider could not load model '$modelId'",
                cause = cause,
            )
        }
    }
}

internal object ModelWorkspaceCollector {

    suspend fun collect(
        entryModelId: String,
        provider: ModelSourceProvider,
        limits: ModelPreparationLimits,
        signal: ModelCollectionSignal,
    ): CollectedWorkspaceSources {
        val snapshotRevision = try {
            sanitizeDiagnosticMetadata(provider.snapshotRevision)
        } catch (error: Throwable) {
            throw providerFailure(
                modelId = entryModelId,
                reason = ModelSourceProviderFailureReason.PROVIDER_FAILED,
                cause = error,
            )
        }
        val collected = linkedMapOf<String, CollectedSource>()
        val pending = ArrayDeque<CollectionVisit>()
        val traversal = WorkspaceReferenceTraversal(limits)
        var totalBytes = 0L
        pending += CollectionVisit(
            modelId = entryModelId,
            mode = WorkspaceReferenceDiscoveryMode.STRUCTURAL,
            referenceChain = emptyList(),
        )

        while (pending.isNotEmpty()) {
            val visit = pending.removeFirst()
            requireNotCancelled(visit.modelId, signal)
            val source = collected[visit.modelId] ?: run {
                if (collected.size >= limits.maxModelCount) {
                    throw resourceLimit(
                        resource = ModelPreparationResource.MODEL_COUNT,
                        maximum = limits.maxModelCount,
                        actual = collected.size + 1,
                    )
                }
                val remainingBytes = limits.maxTotalBytes.toLong() - totalBytes
                if (remainingBytes <= 0) {
                    throw resourceLimit(
                        resource = ModelPreparationResource.TOTAL_BYTES,
                        maximum = limits.maxTotalBytes.toLong(),
                        actual = limits.maxTotalBytes.toLong() + 1,
                    )
                }
                val loaded = load(
                    modelId = visit.modelId,
                    provider = provider,
                    context = ModelSourceLoadContext.create(
                        maximumBytes = minOf(limits.maxModelBytes.toLong(), remainingBytes).toInt(),
                        signal = signal,
                    ),
                ) ?: throw unresolvedReference(visit, collected)
                requireNotCancelled(visit.modelId, signal)

                val admitted = WorkspaceSourceAdmission.admit(listOf(loaded), limits)
                val actualModelId = admitted.modelIds.single()
                if (actualModelId != visit.modelId) {
                    throw ModelPreparationError.from(
                        listOf(
                            ModelSourceIdMismatchDiagnostic(
                                requestedModelId = visit.modelId,
                                actualModelId = actualModelId,
                            ),
                        ),
                    )
                }
                val byteCount = checkNotNull(strictUtf8ByteCount(loaded.json)) {
                    "admission accepted source that is not strict UTF-8"
                }
                val newTotalBytes = totalBytes + byteCount
                if (newTotalBytes > limits.maxTotalBytes) {
                    throw resourceLimit(
                        resource = ModelPreparationResource.TOTAL_BYTES,
                        maximum = limits.maxTotalBytes.toLong(),
                        actual = newTotalBytes,
                    )
                }
                totalBytes = newTotalBytes
                val admittedSource = checkNotNull(admitted.source(actualModelId))
                CollectedSource(
                    supplied = loaded,
                    parsed = ParsedWorkspaceSource(
                        source = admittedSource,
                        root = Json.parseToJsonElement(admittedSource.json) as JsonObject,
                    ),
                ).also { collected[visit.modelId] = it }
            }

            val references = WorkspaceReferenceDiscovery.discover(
                source = source.parsed,
                mode = visit.mode,
                requireStructureDepth = { depth ->
                    if (depth > limits.maxStructureDepth) {
                        throw resourceLimit(
                            resource = ModelPreparationResource.STRUCTURE_DEPTH,
                            maximum = limits.maxStructureDepth,
                            actual = depth,
                        )
                    }
                },
            )
            for (step in references) {
                val nextChain = traversal.follow(step, visit.referenceChain)
                pending += CollectionVisit(
                    modelId = step.toModelId,
                    mode = when (step.purpose) {
                        ModelReferencePurpose.INCLUDE -> WorkspaceReferenceDiscoveryMode.STRUCTURAL
                        ModelReferencePurpose.TYPE_DEFINITIONS ->
                            WorkspaceReferenceDiscoveryMode.TYPE_DEFINITION_PROVIDER
                    },
                    referenceChain = nextChain,
                )
            }
        }
        return CollectedWorkspaceSources(
            sources = collected.values.map(CollectedSource::supplied),
            snapshotRevision = snapshotRevision,
        )
    }

    private suspend fun load(
        modelId: String,
        provider: ModelSourceProvider,
        context: ModelSourceLoadContext,
    ): WorkspaceModelSource? =
        try {
            provider.load(modelId, context)
        } catch (error: ModelSourceProviderError) {
            throw providerFailure(
                modelId = modelId,
                reason = error.reason,
                cause = error.cause ?: error,
            )
        } catch (error: Throwable) {
            throw providerFailure(
                modelId = modelId,
                reason = ModelSourceProviderFailureReason.PROVIDER_FAILED,
                cause = error,
            )
        }

    private fun requireNotCancelled(
        modelId: String,
        signal: ModelCollectionSignal,
    ) {
        if (signal.isCancelled) {
            throw providerFailure(
                modelId = modelId,
                reason = ModelSourceProviderFailureReason.CANCELLED,
            )
        }
    }

    private fun unresolvedReference(
        visit: CollectionVisit,
        collected: Map<String, CollectedSource>,
    ): ModelPreparationError {
        val referringModelId = visit.referenceChain.lastOrNull()?.fromModelId ?: visit.modelId
        return ModelPreparationError.from(
            listOf(
                UnresolvedModelReferenceDiagnostic(
                    modelId = referringModelId,
                    diagnosticLabel = collected[referringModelId]?.parsed?.source?.diagnosticLabel,
                    reference = visit.modelId,
                    referenceChain = visit.referenceChain,
                ),
            ),
        )
    }

    private fun providerFailure(
        modelId: String,
        reason: ModelSourceProviderFailureReason,
        cause: Throwable? = null,
    ): ModelPreparationError =
        ModelPreparationError.from(
            diagnostics = listOf(
                ModelSourceProviderFailureDiagnostic(
                    requestedModelId = modelId,
                    providerFailureReason = reason,
                ),
            ),
            cause = cause,
        )

    private data class CollectedSource(
        val supplied: WorkspaceModelSource,
        val parsed: ParsedWorkspaceSource,
    )

    private data class CollectionVisit(
        val modelId: String,
        val mode: WorkspaceReferenceDiscoveryMode,
        val referenceChain: List<ModelReferenceStep>,
    )
}

internal data class CollectedWorkspaceSources(
    val sources: List<WorkspaceModelSource>,
    val snapshotRevision: String?,
)

private data class ModelSourceProviderFailureDiagnostic(
    override val requestedModelId: String,
    override val providerFailureReason: ModelSourceProviderFailureReason,
) : ModelPreparationDiagnostic {
    override val code = ModelPreparationDiagnosticCode.MODEL_SOURCE_PROVIDER_FAILED
    override val message = "model source provider could not load '$requestedModelId' ($providerFailureReason)"
}

private data class ModelSourceIdMismatchDiagnostic(
    override val requestedModelId: String,
    override val actualModelId: String,
) : ModelPreparationDiagnostic {
    override val code = ModelPreparationDiagnosticCode.MODEL_SOURCE_ID_MISMATCH
    override val message =
        "provider returned model '$actualModelId' for requested model '$requestedModelId'"
}
