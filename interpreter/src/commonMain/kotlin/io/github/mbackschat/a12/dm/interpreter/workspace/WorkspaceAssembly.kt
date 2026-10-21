package io.github.mbackschat.a12.dm.interpreter.workspace

import io.github.mbackschat.a12.dm.interpreter.declaredLocaleKeys
import io.github.mbackschat.a12.dm.interpreter.modelMemberText
import io.github.mbackschat.a12.dm.interpreter.parseA12Json
import io.github.mbackschat.a12.dm.interpreter.unbindableModelMember
import io.github.mbackschat.a12.dm.interpreter.ModelPreparationDiagnostic
import io.github.mbackschat.a12.dm.interpreter.ModelPreparationDiagnosticCode
import io.github.mbackschat.a12.dm.interpreter.ModelPreparationError
import io.github.mbackschat.a12.dm.interpreter.ModelPreparationLimits
import io.github.mbackschat.a12.dm.interpreter.ModelPreparationResource
import io.github.mbackschat.a12.dm.interpreter.ModelReferencePurpose
import io.github.mbackschat.a12.dm.interpreter.ModelReferenceStep
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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

/** Shared occurrence-based edge/depth/cycle guard for collection and synchronous preparation. */
internal class WorkspaceReferenceTraversal(
    private val limits: ModelPreparationLimits,
) {
    private var referenceEdges = 0

    fun follow(
        step: ModelReferenceStep,
        referenceChain: List<ModelReferenceStep>,
    ): List<ModelReferenceStep> {
        // Count the attempted edge before cycle/lookup work. A hostile graph can expose at most limit+1 edges,
        // including the edge that proves the refusal, without forcing another source parse or provider call.
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

        // Cycles span purposes: include → typedef → include is still a cycle in one closure. Only ancestors
        // participate, so the same source reached through a separate diamond remains legal.
        val modelsOnPath = buildList {
            if (nextChain.isNotEmpty()) {
                add(nextChain.first().fromModelId)
                nextChain.dropLast(1).forEach { add(it.toModelId) }
            }
        }
        if (step.toModelId in modelsOnPath) {
            throw ModelPreparationError.from(
                listOf(
                    ModelReferenceCycleDiagnostic(
                        modelId = step.fromModelId,
                        reference = step.toModelId,
                        referenceChain = nextChain,
                    ),
                ),
            )
        }
        return nextChain
    }

}

/** Shared graph, source, dependency, and resource state for every stage of one synchronous preparation. */
internal class WorkspaceAssemblyContext(
    private val workspace: AdmittedWorkspace,
    private val limits: ModelPreparationLimits,
) {
    /** Exact-id parse memo: each admitted immutable source is decoded into a tree at most once per preparation. */
    private val parsedSources = linkedMapOf<String, ParsedWorkspaceSource>()
    /** Distinct resolved edges stay in first-discovery order for deterministic public dependency metadata. */
    private val dependencies = linkedMapOf<ModelReferenceStep, ResolvedModelReference>()
    /** A validated graph edge resolves to one immutable parsed source; later stages reuse it without re-admission. */
    private val resolvedSources = linkedMapOf<ModelReferenceStep, ParsedWorkspaceSource>()
    private val referenceTraversal = WorkspaceReferenceTraversal(limits)
    private var preparedElements = 0
    /** The entry's locale keys, which every model it reaches must declare; set by [entry] before any [resolve]. */
    private var entryLocaleKeys: Set<String>? = null

    fun entry(modelId: String): ParsedWorkspaceSource {
        val source = workspace.source(modelId)
            ?: throw ModelPreparationError.from(listOf(EntryModelNotFoundDiagnostic(modelId)))
        return parsed(source).also { entryLocaleKeys = it.root.declaredLocaleKeys() }
    }

    fun resolve(
        step: ModelReferenceStep,
        referenceChain: List<ModelReferenceStep>,
    ): ResolvedWorkspaceReference {
        val nextChain = referenceTraversal.follow(step, referenceChain)

        val source = workspace.source(step.toModelId)
            ?: throw ModelPreparationError.from(
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
        requireExpandable(step, parsedSource)
        if (step !in dependencies) {
            dependencies[step] = ResolvedModelReference(step, source)
        }
        if (step !in resolvedSources) {
            resolvedSources[step] = parsedSource
        }
        return ResolvedWorkspaceReference(parsedSource, nextChain)
    }

    /**
     * What A12's expansion demands of a model the entry reaches, before it reads a declaration of it (KF594): every
     * locale the entry declares, and, for a type-definition library, no root group at all, since an import whose root
     * holds one, even empty, is not expanded. Measured one reference deep; a nested reference is held to the entry's
     * locales as the portable checker holds it, which no measurement has yet separated from its referrer's.
     */
    private fun requireExpandable(step: ModelReferenceStep, reached: ParsedWorkspaceSource) {
        if (step.purpose == ModelReferencePurpose.TYPE_DEFINITIONS &&
            (((reached.root["content"] as? JsonObject)?.get("modelRoot") as? JsonObject)?.get("rootGroups") as? JsonArray)
                .orEmpty().isNotEmpty()
        ) {
            throw invalidModelElement(reached, "content.modelRoot.rootGroups",
                "a type-definition library declares a root group, so A12 cannot expand the import")
        }
        val uncovered = checkNotNull(entryLocaleKeys) { "the entry is read before any reference is resolved" } -
            reached.root.declaredLocaleKeys()
        if (uncovered.isNotEmpty()) {
            throw invalidModelElement(reached, "header.locales",
                "the model does not declare the entry model's ${uncovered.joinToString(", ")}, so A12 does not expand it")
        }
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

    fun dependenciesSnapshot(): List<ResolvedModelReference> = immutableListSnapshot(dependencies.values)

    fun resolvedSource(step: ModelReferenceStep): ParsedWorkspaceSource =
        checkNotNull(resolvedSources[step]) {
            "workspace edge was not resolved during closure discovery: $step"
        }

    /**
     * Every source the closure reaches passes through here once, the entry included, which is where a model-level
     * member A12 cannot bind refuses: A12 reads each reached model whole, so such a member in any of them leaves the
     * entry unbuilt ([unbindableModelMember]).
     */
    private fun parsed(source: AdmittedWorkspaceModelSource): ParsedWorkspaceSource =
        parsedSources.getOrPut(source.modelId) {
            val root = parseA12Json(source.json) as? JsonObject
                ?: error("admission accepted a non-object model source")
            val parsedSource = ParsedWorkspaceSource(source, root)
            root.unbindableModelMember()?.let { member ->
                throw invalidModelElement(parsedSource, member,
                    "$member holds ${root.modelMemberText(member)}, which A12 cannot bind, so A12 does not load the model")
            }
            parsedSource
        }

}

internal fun requirePreparedBytesWithin(
    root: JsonObject,
    limits: ModelPreparationLimits,
) {
    val maximum = limits.maxPreparedBytes.toLong()
    val actual = compactJsonUtf8Size(root, stopAfter = maximum)
    if (actual > maximum) {
        throw resourceLimit(
            resource = ModelPreparationResource.PREPARED_BYTES,
            maximum = maximum,
            actual = actual,
        )
    }
}

private data class EntryModelNotFoundDiagnostic(
    override val modelId: String,
) : ModelPreparationDiagnostic {
    override val code = ModelPreparationDiagnosticCode.ENTRY_MODEL_NOT_FOUND
    override val message = "entry model '$modelId' is not present in the workspace"
}

internal data class UnresolvedModelReferenceDiagnostic(
    override val modelId: String,
    override val diagnosticLabel: String?,
    override val reference: String,
    override val referenceChain: List<ModelReferenceStep>,
) : ModelPreparationDiagnostic {
    override val code = ModelPreparationDiagnosticCode.UNRESOLVED_MODEL_REFERENCE
    override val message = "model '$modelId' references unavailable model '$reference'"
}

internal data class ModelReferenceCycleDiagnostic(
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
    override val firstSourceModelId: String,
    override val conflictingSourceModelId: String,
) : ModelPreparationDiagnostic {
    override val code = ModelPreparationDiagnosticCode.AMBIGUOUS_TYPE_DEFINITION
    override val message =
        "ambiguous type definition '$reference' is declared by '$firstSourceModelId' and '$conflictingSourceModelId'"
}

internal data class UnpreparedTypeDefinitionDiagnostic(
    override val modelId: String,
    override val path: String,
    override val reference: String,
    override val cyclic: Boolean,
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
    override val sourceLanguage: String,
    override val entryLanguage: String,
) : ModelPreparationDiagnostic {
    override val code = ModelPreparationDiagnosticCode.INCOMPATIBLE_CONDITION_LANGUAGE
    override val message =
        "mounted executable '$path' uses $sourceLanguage syntax that is incompatible with entry language $entryLanguage"
}

internal data class InvalidTypeDefinitionDiagnostic(
    override val modelId: String,
    override val path: String,
    override val reason: String,
) : ModelPreparationDiagnostic {
    override val code = ModelPreparationDiagnosticCode.INVALID_MODEL_SOURCE
    override val message = "invalid type definition at '$path': $reason"
}

internal data class InvalidModelElementDiagnostic(
    override val modelId: String,
    override val diagnosticLabel: String?,
    override val path: String,
    override val reason: String,
) : ModelPreparationDiagnostic {
    override val code = ModelPreparationDiagnosticCode.INVALID_MODEL_SOURCE
    override val message = "invalid model element at '$path': $reason"
}

internal data class InvalidIncludeMountDiagnostic(
    override val modelId: String,
    override val path: String,
    override val reason: String,
) : ModelPreparationDiagnostic {
    override val code = ModelPreparationDiagnosticCode.INVALID_INCLUDE_MOUNT
    override val message = "invalid include mount at '$path': $reason"
}

internal fun JsonObject.strictString(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
