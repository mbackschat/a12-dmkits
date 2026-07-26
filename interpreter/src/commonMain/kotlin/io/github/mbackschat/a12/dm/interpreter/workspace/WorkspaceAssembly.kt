package io.github.mbackschat.a12.dm.interpreter.workspace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Logical reference purposes in the host-neutral preparation graph. */
internal enum class ModelReferencePurpose(val wireName: String) {
    INCLUDE("include"),
    TYPE_DEFINITIONS("typeDefinitions");

    companion object {
        fun fromWireName(value: String): ModelReferencePurpose? = when (value) {
            INCLUDE.wireName -> INCLUDE
            TYPE_DEFINITIONS.wireName -> TYPE_DEFINITIONS
            else -> null
        }
    }
}

/** One exact directed edge in a workspace preparation closure. */
@ConsistentCopyVisibility
internal data class ModelReferenceStep private constructor(
    val fromModelId: String,
    val purpose: ModelReferencePurpose,
    val toModelId: String,
    val alias: String?,
) {
    companion object {
        fun typeDefinitions(
            fromModelId: String,
            toModelId: String,
            alias: String?,
        ): ModelReferenceStep = ModelReferenceStep(
            fromModelId = fromModelId,
            purpose = ModelReferencePurpose.TYPE_DEFINITIONS,
            toModelId = toModelId,
            alias = alias,
        )

        fun include(
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

/** One resolved graph edge plus the immutable admitted source that satisfied it. */
internal class ResolvedModelReference internal constructor(
    val step: ModelReferenceStep,
    private val source: AdmittedWorkspaceModelSource,
) {
    val diagnosticLabel: String? get() = source.diagnosticLabel
    val sha256: String get() = source.sha256
}

internal data class ParsedWorkspaceSource(
    val source: AdmittedWorkspaceModelSource,
    val root: JsonObject,
)

internal data class ResolvedWorkspaceReference(
    val parsedSource: ParsedWorkspaceSource,
    val referenceChain: List<ModelReferenceStep>,
)

/** Shared graph, source, dependency, and resource state for every stage of one synchronous preparation. */
internal class WorkspaceAssemblyContext(
    private val workspace: AdmittedWorkspace,
    private val limits: ModelPreparationLimits,
) {
    /** Exact-id parse memo: each admitted immutable source is decoded into a tree at most once per preparation. */
    private val parsedSources = linkedMapOf<String, ParsedWorkspaceSource>()
    /** Resolved edges stay in discovery order for deterministic dependency metadata and fingerprints. */
    private val dependencies = mutableListOf<ResolvedModelReference>()
    /** A validated graph edge resolves to one immutable parsed source; later stages reuse it without re-admission. */
    private val resolvedSources = linkedMapOf<ModelReferenceStep, ParsedWorkspaceSource>()
    private var referenceEdges = 0
    private var preparedElements = 0

    fun entry(modelId: String): ParsedWorkspaceSource {
        val source = workspace.source(modelId)
            ?: throw ModelPreparationError(listOf(EntryModelNotFoundDiagnostic(modelId)))
        return parsed(source)
    }

    fun resolve(
        step: ModelReferenceStep,
        referenceChain: List<ModelReferenceStep>,
    ): ResolvedWorkspaceReference {
        // Count the attempted edge before cycle/lookup work. A hostile graph can expose at most limit+1 edges,
        // including the edge that proves the refusal, without forcing another source parse.
        referenceEdges++
        if (referenceEdges > limits.maxReferenceEdges) {
            throw resourceLimit(
                resource = ModelPreparationResource.REFERENCE_EDGES,
                maximum = limits.maxReferenceEdges,
                actual = referenceEdges,
            )
        }

        val nextChain = immutableListSnapshot(referenceChain + step)
        if (nextChain.size > limits.maxReferenceDepth) {
            throw resourceLimit(
                resource = ModelPreparationResource.REFERENCE_DEPTH,
                maximum = limits.maxReferenceDepth,
                actual = nextChain.size,
            )
        }

        // Cycles span purposes: include → typedef → include is still a cycle in one preparation closure. Only
        // ancestors participate, so the same source reached through a separate diamond remains legal.
        val modelsOnPath = buildList {
            if (nextChain.isNotEmpty()) {
                add(nextChain.first().fromModelId)
                nextChain.dropLast(1).forEach { add(it.toModelId) }
            }
        }
        if (step.toModelId in modelsOnPath) {
            throw ModelPreparationError(
                listOf(
                    ModelReferenceCycleDiagnostic(
                        modelId = step.fromModelId,
                        reference = step.toModelId,
                        referenceChain = nextChain,
                    ),
                ),
            )
        }

        val source = workspace.source(step.toModelId)
            ?: throw ModelPreparationError(
                listOf(
                    UnresolvedModelReferenceDiagnostic(
                        modelId = step.fromModelId,
                        diagnosticLabel = workspace.source(step.fromModelId)?.diagnosticLabel,
                        reference = step.toModelId,
                        referenceChain = nextChain,
                    ),
                ),
            )
        val parsedSource = parsed(source)
        dependencies += ResolvedModelReference(step, source)
        if (step !in resolvedSources) {
            resolvedSources[step] = parsedSource
        }
        return ResolvedWorkspaceReference(parsedSource, nextChain)
    }

    fun consumePreparedElement() {
        preparedElements++
        if (preparedElements > limits.maxPreparedElements) {
            throw resourceLimit(
                resource = ModelPreparationResource.PREPARED_ELEMENTS,
                maximum = limits.maxPreparedElements,
                actual = preparedElements,
            )
        }
    }

    fun requireStructureDepth(depth: Int) {
        if (depth > limits.maxStructureDepth) {
            throw resourceLimit(
                resource = ModelPreparationResource.STRUCTURE_DEPTH,
                maximum = limits.maxStructureDepth,
                actual = depth,
            )
        }
    }

    fun requirePreparedBytes(root: JsonObject) {
        requirePreparedBytesWithin(root, limits)
    }

    fun dependenciesSnapshot(): List<ResolvedModelReference> = immutableListSnapshot(dependencies)

    fun resolvedSource(step: ModelReferenceStep): ParsedWorkspaceSource =
        checkNotNull(resolvedSources[step]) {
            "workspace edge was not resolved during closure discovery: $step"
        }

    private fun parsed(source: AdmittedWorkspaceModelSource): ParsedWorkspaceSource =
        parsedSources.getOrPut(source.modelId) {
            val root = Json.parseToJsonElement(source.json) as? JsonObject
                ?: error("admission accepted a non-object model source")
            ParsedWorkspaceSource(source, root)
        }

    private fun resourceLimit(
        resource: ModelPreparationResource,
        maximum: Int,
        actual: Int,
    ): ModelPreparationError =
        resourceLimit(resource, maximum.toLong(), actual.toLong())

    private fun resourceLimit(
        resource: ModelPreparationResource,
        maximum: Long,
        actual: Long,
    ): ModelPreparationError =
        ModelPreparationError(
            listOf(
                ResourceLimitDiagnostic(
                    resource = resource,
                    maximum = maximum,
                    actual = actual,
                ),
            ),
        )
}

internal fun requirePreparedBytesWithin(
    root: JsonObject,
    limits: ModelPreparationLimits,
) {
    val maximum = limits.maxPreparedBytes.toLong()
    val actual = compactJsonUtf8Size(root, stopAfter = maximum)
    if (actual > maximum) {
        throw ModelPreparationError(
            listOf(
                ResourceLimitDiagnostic(
                    resource = ModelPreparationResource.PREPARED_BYTES,
                    maximum = maximum,
                    actual = actual,
                ),
            ),
        )
    }
}

private data class EntryModelNotFoundDiagnostic(
    override val modelId: String,
) : ModelPreparationDiagnostic {
    override val code = ModelPreparationDiagnosticCode.ENTRY_MODEL_NOT_FOUND
    override val message = "entry model '$modelId' is not present in the workspace"
}

private data class UnresolvedModelReferenceDiagnostic(
    override val modelId: String,
    override val diagnosticLabel: String?,
    override val reference: String,
    override val referenceChain: List<ModelReferenceStep>,
) : ModelPreparationDiagnostic {
    override val code = ModelPreparationDiagnosticCode.UNRESOLVED_MODEL_REFERENCE
    override val message = "model '$modelId' references unavailable model '$reference'"
}

private data class ModelReferenceCycleDiagnostic(
    override val modelId: String,
    override val reference: String,
    override val referenceChain: List<ModelReferenceStep>,
) : ModelPreparationDiagnostic {
    override val code = ModelPreparationDiagnosticCode.MODEL_REFERENCE_CYCLE
    override val message = "model reference cycle closes at '$reference'"
}

internal data class AmbiguousTypeDefinitionDiagnostic(
    override val modelId: String,
    override val reference: String,
    val firstSourceModelId: String,
    val conflictingSourceModelId: String,
) : ModelPreparationDiagnostic {
    override val code = ModelPreparationDiagnosticCode.AMBIGUOUS_TYPE_DEFINITION
    override val message =
        "ambiguous type definition '$reference' is declared by '$firstSourceModelId' and '$conflictingSourceModelId'"
}

internal data class UnpreparedTypeDefinitionDiagnostic(
    override val modelId: String,
    override val path: String,
    override val reference: String,
    val cyclic: Boolean,
) : ModelPreparationDiagnostic {
    override val code = ModelPreparationDiagnosticCode.UNPREPARED_MODEL_RESIDUE
    override val message = if (cyclic) {
        "type definition cycle leaves unresolved TypeDefType '$reference' at '$path'"
    } else {
        "unresolved type definition '$reference' leaves TypeDefType residue at '$path'"
    }
}

internal data class UnmappedIncludedReferenceDiagnostic(
    override val modelId: String,
    override val diagnosticLabel: String?,
    override val path: String,
    override val reference: String,
    override val referenceChain: List<ModelReferenceStep>,
) : ModelPreparationDiagnostic {
    override val code = ModelPreparationDiagnosticCode.UNMAPPED_INCLUDED_REFERENCE
    override val message = "mounted executable '$path' retains unmapped provider reference '$reference'"
}

internal data class IncompatibleConditionLanguageDiagnostic(
    override val modelId: String,
    override val diagnosticLabel: String?,
    override val path: String,
    override val referenceChain: List<ModelReferenceStep>,
    val sourceLanguage: String,
    val entryLanguage: String,
) : ModelPreparationDiagnostic {
    override val code = ModelPreparationDiagnosticCode.INCOMPATIBLE_CONDITION_LANGUAGE
    override val message =
        "mounted executable '$path' uses $sourceLanguage syntax that is incompatible with entry language $entryLanguage"
}

internal data class InvalidTypeDefinitionDiagnostic(
    override val modelId: String,
    override val path: String,
    val reason: String,
) : ModelPreparationDiagnostic {
    override val code = ModelPreparationDiagnosticCode.INVALID_MODEL_SOURCE
    override val message = "invalid type definition at '$path': $reason"
}

internal data class InvalidModelElementDiagnostic(
    override val modelId: String,
    override val diagnosticLabel: String?,
    override val path: String,
    val reason: String,
) : ModelPreparationDiagnostic {
    override val code = ModelPreparationDiagnosticCode.INVALID_MODEL_SOURCE
    override val message = "invalid model element at '$path': $reason"
}

internal data class InvalidIncludeMountDiagnostic(
    override val modelId: String,
    override val path: String,
    val reason: String,
) : ModelPreparationDiagnostic {
    override val code = ModelPreparationDiagnosticCode.INVALID_INCLUDE_MOUNT
    override val message = "invalid include mount at '$path': $reason"
}

internal fun JsonObject.strictString(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
