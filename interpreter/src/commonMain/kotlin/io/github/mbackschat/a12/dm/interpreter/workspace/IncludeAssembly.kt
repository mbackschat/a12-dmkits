package io.github.mbackschat.a12.dm.interpreter.workspace

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A structurally mounted workspace entry. Element ids and reference-bearing fragments deliberately remain in their
 * source form for the subsequent move/remapping stage.
 */
internal class IncludeAssembly internal constructor(
    val entryModelId: String,
    dependencies: List<ResolvedModelReference>,
    mountedExecutableOrigins: List<MountedExecutableOrigin>,
    val root: JsonObject,
) {
    val dependencies: List<ResolvedModelReference> = immutableListSnapshot(dependencies)
    val mountedExecutableOrigins: List<MountedExecutableOrigin> =
        immutableListSnapshot(mountedExecutableOrigins)
    val json: String by lazy { root.toString() }
}

/**
 * Provenance for one mounted rule or computation. [outputPath] and [outputOrdinal] identify the final prepared
 * occurrence; [sourceParentPath] remains in the provider's expanded coordinate system for later reference binding.
 */
internal class MountedExecutableOrigin internal constructor(
    val outputPath: String,
    val outputOrdinal: Int,
    val sourceParentPath: String,
    val namespace: MountedNamespace,
) {
    val sourceModelId: String get() = namespace.sourceModelId
    val prefixes: List<MountedPrefix> get() = namespace.prefixes
}

/**
 * One provider's namespace at one physical include occurrence. Prefixes map that provider's fully expanded source
 * namespace to final output paths; sharing this object keeps wide MODEL_ROOT provenance linear in output size.
 */
internal class MountedNamespace internal constructor(
    val sourceModelId: String,
    val diagnosticLabel: String?,
    referenceChain: List<ModelReferenceStep>,
    val fieldRefByShortNameAllowed: Boolean,
    val conditionLanguageCode: String?,
    prefixes: List<MountedPrefix>,
) {
    val referenceChain: List<ModelReferenceStep> = immutableListSnapshot(referenceChain)
    val mapping = MountedPrefixMapping(prefixes)
    val prefixes: List<MountedPrefix> get() = mapping.prefixes

    /** The one segment-safe output mapping, or null when the path is unmapped or maps ambiguously. */
    fun outputPathFor(sourcePath: String): String? =
        mapping.outputPathFor(sourcePath)

    /** The one segment-safe provider mapping, or null when the path is unmapped or maps ambiguously. */
    fun sourcePathFor(outputPath: String): String? =
        mapping.sourcePathFor(outputPath)
}

internal data class MountedPrefix(
    val sourcePrefix: String,
    val outputPrefix: String,
)

/**
 * Immutable segment indexes for one physical mount's provider↔output prefixes. Lookup work is bounded by path
 * depth, not by a wide MODEL_ROOT provider's number of root groups.
 */
internal class MountedPrefixMapping(
    prefixes: List<MountedPrefix>,
    lookupObserver: (() -> Unit)? = null,
) {
    val prefixes: List<MountedPrefix> = immutableListSnapshot(prefixes)
    private val outputBySource = SegmentPrefixIndex(
        prefixes = this.prefixes,
        from = MountedPrefix::sourcePrefix,
        to = MountedPrefix::outputPrefix,
        lookupObserver = lookupObserver,
    )
    private val sourceByOutput = SegmentPrefixIndex(
        prefixes = this.prefixes,
        from = MountedPrefix::outputPrefix,
        to = MountedPrefix::sourcePrefix,
        lookupObserver = lookupObserver,
    )

    fun outputPathFor(sourcePath: String): String? = outputBySource.translate(sourcePath)

    fun sourcePathFor(outputPath: String): String? = sourceByOutput.translate(outputPath)
}

private class SegmentPrefixIndex(
    prefixes: List<MountedPrefix>,
    from: (MountedPrefix) -> String,
    to: (MountedPrefix) -> String,
    private val lookupObserver: (() -> Unit)?,
) {
    private val root = PrefixNode()

    init {
        prefixes.forEach { prefix ->
            add(from(prefix), to(prefix))
        }
    }

    fun translate(path: String): String? {
        if (!path.startsWith('/')) {
            return null
        }
        var node = root
        var segmentStart = 1
        var candidate: String? = null
        while (segmentStart <= path.length) {
            val segmentEnd = path.indexOf('/', startIndex = segmentStart)
                .let { if (it < 0) path.length else it }
            if (segmentEnd == segmentStart) {
                return null
            }
            lookupObserver?.invoke()
            node = node.children[path.substring(segmentStart, segmentEnd)] ?: return candidate
            if (node.targets.isNotEmpty()) {
                val suffix = path.substring(segmentEnd)
                for (target in node.targets) {
                    val translated = target + suffix
                    if (candidate != null && candidate != translated) {
                        return null
                    }
                    candidate = translated
                }
            }
            if (segmentEnd == path.length) {
                return candidate
            }
            segmentStart = segmentEnd + 1
        }
        return candidate
    }

    private fun add(prefix: String, target: String) {
        require(prefix.startsWith('/') && prefix.length > 1) {
            "mounted prefixes must be non-root absolute paths"
        }
        var node = root
        var segmentStart = 1
        while (segmentStart <= prefix.length) {
            val segmentEnd = prefix.indexOf('/', startIndex = segmentStart)
                .let { if (it < 0) prefix.length else it }
            require(segmentEnd > segmentStart) {
                "mounted prefixes must not contain empty path segments"
            }
            node = node.children.getOrPut(prefix.substring(segmentStart, segmentEnd)) { PrefixNode() }
            if (segmentEnd == prefix.length) {
                node.targets += target
                return
            }
            segmentStart = segmentEnd + 1
        }
    }
}

private class PrefixNode {
    val children = linkedMapOf<String, PrefixNode>()
    val targets = linkedSetOf<String>()
}

/** Portable structural include assembly over the already admitted and graph-validated workspace closure. */
internal object WorkspaceIncludeAssembler {

    fun assemble(
        workspace: AdmittedWorkspace,
        entryModelId: String,
        limits: ModelPreparationLimits = ModelPreparationLimits.defaults(),
    ): IncludeAssembly {
        val preparation = WorkspaceTypeDefinitionAssembler.prepare(workspace, entryModelId, limits)
        val mounter = IncludeMounter(preparation.context)
        val mountedRoot = mounter.mount(preparation.entry)
        mounter.requireNoIncludeResidue(mountedRoot)
        val withEffectiveTypes = preparation.complete(
            root = mountedRoot,
            elementsAlreadyCounted = true,
            finishRoot = JsonObject::withoutModelReferences,
        )
        return IncludeAssembly(
            entryModelId = entryModelId,
            dependencies = withEffectiveTypes.dependencies,
            mountedExecutableOrigins = mounter.mountedExecutableOrigins(),
            root = withEffectiveTypes.root,
        )
    }
}

/**
 * Immutable recursive mounting. Each expanded occurrence—including one later removed by an exclusion—is
 * depth-checked and charged before its descendants are built, so filters cannot hide expansion work and repeated
 * mounts cannot allocate an unbounded intermediate tree ahead of the preparation limits.
 */
private class IncludeMounter(
    private val context: WorkspaceAssemblyContext,
) {
    private val executableOrigins = mutableListOf<MountedExecutableOriginDraft>()
    private val excludedExecutableOccurrences = mutableSetOf<ExecutableOccurrence>()
    private var outputOrdinal = 0

    fun mountedExecutableOrigins(): List<MountedExecutableOrigin> =
        immutableListSnapshot(
            executableOrigins
                .filterNot { it.occurrence in excludedExecutableOccurrences }
                .map(MountedExecutableOriginDraft::materialize),
        )

    fun requireNoIncludeResidue(root: JsonObject) {
        val pending = mutableListOf<JsonElement>(root)
        while (pending.isNotEmpty()) {
            when (val element = pending.removeAt(pending.lastIndex)) {
                is JsonObject -> {
                    val body = element.objectMember(PreparedElementType.GROUP.wireName)
                    check(body == null || INCLUDE_DIRECTIVE_KEYS.none(body::containsKey)) {
                        "successful workspace mounting retained an include directive"
                    }
                    pending.addAll(element.values)
                }
                is JsonArray -> pending.addAll(element)
                is JsonPrimitive -> Unit
            }
        }
    }

    fun mount(entry: ParsedWorkspaceSource): JsonObject {
        val content = entry.root.objectMember("content") ?: return entry.root
        val modelRoot = content.objectMember("modelRoot") ?: return entry.root
        val rootGroups = modelRoot.arrayMember("rootGroups") ?: return entry.root
        val mountedGroups = rootGroups.map {
            mountElement(
                element = it,
                owningSource = entry,
                depth = 1,
                parentPath = "",
                sourceParentPath = "",
                moveContext = null,
                referenceChain = emptyList(),
            )
        }
        return entry.root.replacing(
            "content",
            content.replacing(
                "modelRoot",
                modelRoot.replacing("rootGroups", JsonArray(mountedGroups)),
            ),
        )
    }

    private fun mountElement(
        element: JsonElement,
        owningSource: ParsedWorkspaceSource,
        depth: Int,
        parentPath: String,
        sourceParentPath: String,
        moveContext: IncludeMoveContext?,
        referenceChain: List<ModelReferenceStep>,
    ): JsonObject {
        val node = element as? JsonObject
            ?: throw invalidModelElement(
                owningSource,
                sourceParentPath.ifEmpty { "/" },
                "element must be a JSON object",
            )
        val name = node.strictString("name").orEmpty()
        val sourcePath = "$sourceParentPath/$name"
        val elementType = PreparedElementType.fromWireName(node.strictString("type"))
            ?: throw invalidModelElement(
                owningSource,
                sourcePath,
                "type must identify a supported document-model element",
            )
        if (sourceParentPath.isEmpty() && elementType != PreparedElementType.GROUP) {
            throw invalidModelElement(
                owningSource,
                sourcePath,
                "a model root element must be a Group",
            )
        }
        context.requireStructureDepth(depth)
        context.consumePreparedElement()
        val currentOutputOrdinal = outputOrdinal++
        val path = "$parentPath/$name"
        if (
            moveContext != null &&
            (elementType == PreparedElementType.RULE || elementType == PreparedElementType.COMPUTATION)
        ) {
            executableOrigins += MountedExecutableOriginDraft(
                outputPath = path,
                outputOrdinal = currentOutputOrdinal,
                sourceParentPath = sourceParentPath,
                moveContext = moveContext,
                elementType = elementType,
            )
        }
        if (elementType != PreparedElementType.GROUP) {
            return node
        }

        val body = node.objectMember(PreparedElementType.GROUP.wireName)
            ?: throw invalidModelElement(
                owningSource,
                sourcePath,
                "a Group element requires an object Group body",
            )
        return when (val assembly = classifyGroup(body, owningSource, path)) {
            is GroupAssembly.Include -> mountIncludeGroup(
                node = node,
                body = body,
                owningSource = owningSource,
                directive = assembly.directive,
                depth = depth,
                mountPath = path,
                referenceChain = referenceChain,
            )
            is GroupAssembly.Ordinary -> mountOrdinaryGroup(
                node = node,
                body = body,
                owningSource = owningSource,
                elements = assembly.elements,
                depth = depth,
                groupPath = path,
                sourceGroupPath = "$sourceParentPath/$name",
                moveContext = moveContext,
                referenceChain = referenceChain,
            )
        }
    }

    private fun mountIncludeGroup(
        node: JsonObject,
        body: JsonObject,
        owningSource: ParsedWorkspaceSource,
        directive: IncludeDirective,
        depth: Int,
        mountPath: String,
        referenceChain: List<ModelReferenceStep>,
    ): JsonObject {
        val step = ModelReferenceStep.include(
            fromModelId = owningSource.source.modelId,
            toModelId = owningSource.root.includeReference(directive.alias),
            alias = directive.alias,
        )
        val includedSource = context.resolvedSource(step)
        val includedRootGroups = includedSource.root
            .objectMember("content")
            ?.objectMember("modelRoot")
            ?.arrayMember("rootGroups")
            ?: JsonArray(emptyList())
        val includeReferenceChain = immutableListSnapshot(referenceChain + step)
        val moveContext = IncludeMoveContext()
        val mounted = when (directive.level) {
            IncludeLevel.SINGLE_RG -> mountSingleRoot(
                includedRootGroups,
                includedSource,
                depth,
                mountPath,
                owningSource.source.modelId,
                moveContext,
                includeReferenceChain,
                directive.exclusions,
            )
            IncludeLevel.MODEL_ROOT -> mountModelRoots(
                includedRootGroups,
                includedSource,
                depth,
                mountPath,
                moveContext,
                includeReferenceChain,
                directive.exclusions,
            )
        }
        moveContext.bind(
            MountedNamespace(
                sourceModelId = includedSource.source.modelId,
                diagnosticLabel = includedSource.source.diagnosticLabel,
                referenceChain = includeReferenceChain,
                fieldRefByShortNameAllowed = includedSource.root.fieldRefByShortNameAllowed(),
                conditionLanguageCode = includedSource.root.conditionLanguageCode(),
                prefixes = mounted.prefixes,
            ),
        )
        val mountedBody = body.withoutIncludeDirectives()
            .withTransferredUsageType(mounted.usageTypeTransfer)
            .setting("elements", JsonArray(mounted.children))
        return node.replacing(PreparedElementType.GROUP.wireName, mountedBody)
    }

    private fun mountModelRoots(
        includedRootGroups: JsonArray,
        includedSource: ParsedWorkspaceSource,
        mountDepth: Int,
        mountPath: String,
        moveContext: IncludeMoveContext,
        referenceChain: List<ModelReferenceStep>,
        exclusions: IncludeExclusions,
    ): MountedIncludeContent {
        val children = mutableListOf<JsonObject>()
        val prefixes = mutableListOf<MountedPrefix>()
        for (root in includedRootGroups) {
            val expanded = mountElement(
                element = root,
                owningSource = includedSource,
                depth = mountDepth + 1,
                parentPath = mountPath,
                sourceParentPath = "",
                moveContext = moveContext,
                referenceChain = referenceChain,
            )
            val mounted = checkNotNull(filterMountedElement(expanded, exclusions, mountPath)) {
                "an include exclusion cannot remove a provider root group"
            }
            children += mounted
            mounted.strictString("name")
                ?.takeIf(String::isNotBlank)
                ?.let { name ->
                    prefixes += MountedPrefix(
                        sourcePrefix = "/$name",
                        outputPrefix = "$mountPath/$name",
                    )
                }
        }
        return MountedIncludeContent(
            children = immutableListSnapshot(children),
            prefixes = immutableListSnapshot(prefixes),
            usageTypeTransfer = UsageTypeTransfer.PreserveMount,
        )
    }

    private fun mountSingleRoot(
        includedRootGroups: JsonArray,
        includedSource: ParsedWorkspaceSource,
        mountDepth: Int,
        mountPath: String,
        mountModelId: String,
        moveContext: IncludeMoveContext,
        referenceChain: List<ModelReferenceStep>,
        exclusions: IncludeExclusions,
    ): MountedIncludeContent {
        if (includedRootGroups.size != 1) {
            throw invalidIncludeMount(
                mountModelId,
                mountPath,
                "SINGLE_RG requires exactly one non-repeatable root group",
            )
        }
        val includedRoot = includedRootGroups.single() as? JsonObject
            ?: throw invalidModelElement(
                includedSource,
                "/",
                "element must be a JSON object",
            )
        val rootName = includedRoot.strictString("name").orEmpty()
        if (includedRoot.strictString("type") != PreparedElementType.GROUP.wireName) {
            throw invalidModelElement(
                includedSource,
                "/$rootName",
                "type must identify a supported document-model element",
            )
        }
        val rootBody = includedRoot.objectMember(PreparedElementType.GROUP.wireName)
            ?: throw invalidModelElement(
                includedSource,
                "/$rootName",
                "a Group element requires an object Group body",
            )
        val repeatability = rootBody.strictOptionalInt("repeatability")
            ?: throw invalidIncludeMount(
                mountModelId,
                mountPath,
                "SINGLE_RG root repeatability must be an integer",
            )
        if (repeatability != 1) {
            throw invalidIncludeMount(
                mountModelId,
                mountPath,
                "SINGLE_RG requires exactly one non-repeatable root group",
            )
        }
        val mountedBody = when (val assembly = classifyGroup(rootBody, includedSource, "/$rootName")) {
            is GroupAssembly.Include -> mountIncludeGroup(
                node = includedRoot,
                body = rootBody,
                owningSource = includedSource,
                directive = assembly.directive,
                depth = mountDepth,
                mountPath = mountPath,
                referenceChain = referenceChain,
            ).objectMember(PreparedElementType.GROUP.wireName)
                ?: error("mounted include root lost its Group body")
            is GroupAssembly.Ordinary -> {
                val children = assembly.elements.orEmpty().map {
                    mountElement(
                        element = it,
                        owningSource = includedSource,
                        depth = mountDepth + 1,
                        parentPath = mountPath,
                        sourceParentPath = "/$rootName",
                        moveContext = moveContext,
                        referenceChain = referenceChain,
                    )
                }
            rootBody.setting("elements", JsonArray(children))
            }
        }
        val filteredBody = filterMountedGroupBody(mountedBody, exclusions, mountPath)
        return MountedIncludeContent(
            children = immutableListSnapshot(
                filteredBody.arrayMember("elements")
                    ?.map { it as JsonObject }
                    .orEmpty(),
            ),
            prefixes = listOf(MountedPrefix(sourcePrefix = "/$rootName", outputPrefix = mountPath)),
            usageTypeTransfer = UsageTypeTransfer.ReplaceMount(filteredBody["usageType"]),
        )
    }

    private fun mountOrdinaryGroup(
        node: JsonObject,
        body: JsonObject,
        owningSource: ParsedWorkspaceSource,
        elements: JsonArray?,
        depth: Int,
        groupPath: String,
        sourceGroupPath: String,
        moveContext: IncludeMoveContext?,
        referenceChain: List<ModelReferenceStep>,
    ): JsonObject {
        if (elements == null) {
            return node
        }
        val mountedChildren = elements.map {
            mountElement(
                element = it,
                owningSource = owningSource,
                depth = depth + 1,
                parentPath = groupPath,
                sourceParentPath = sourceGroupPath,
                moveContext = moveContext,
                referenceChain = referenceChain,
            )
        }
        return node.replacing(
            PreparedElementType.GROUP.wireName,
            body.replacing("elements", JsonArray(mountedChildren)),
        )
    }

    /**
     * Apply one mount's directives only after its provider is fully expanded. A final attachment group shields its
     * subtree from this outer filter; the nested mount's own directives have already run during its expansion.
     */
    private fun filterMountedElement(
        node: JsonObject,
        exclusions: IncludeExclusions,
        parentPath: String,
    ): JsonObject? {
        if (exclusions.isEmpty()) {
            return node
        }
        val elementType = checkNotNull(PreparedElementType.fromWireName(node.strictString("type"))) {
            "mounted output contains an unsupported element type"
        }
        val path = "$parentPath/${node.strictString("name").orEmpty()}"
        if (exclusions.excludes(elementType)) {
            excludedExecutableOccurrences += ExecutableOccurrence(path, elementType)
            return null
        }
        if (elementType != PreparedElementType.GROUP) {
            return node
        }
        val body = checkNotNull(node.objectMember(PreparedElementType.GROUP.wireName)) {
            "mounted Group output lost its body"
        }
        return node.replacing(
            PreparedElementType.GROUP.wireName,
            filterMountedGroupBody(body, exclusions, path),
        )
    }

    private fun filterMountedGroupBody(
        body: JsonObject,
        exclusions: IncludeExclusions,
        groupPath: String,
    ): JsonObject {
        if (exclusions.isEmpty() || body.strictString("usageType") == ATTACHMENT_USAGE_TYPE) {
            return body
        }
        val elements = body.arrayMember("elements") ?: return body
        val filtered = elements.mapNotNull { element ->
            filterMountedElement(element as JsonObject, exclusions, groupPath)
        }
        return body.replacing("elements", JsonArray(filtered))
    }

    private companion object {
        const val ATTACHMENT_USAGE_TYPE = "attachment"
    }
}

private val INCLUDE_DIRECTIVE_KEYS =
    setOf("modelAlias", "includeLevel", "excludeRules", "excludeComputations")

private val INCLUDE_ONLY_DIRECTIVE_KEYS =
    setOf("includeLevel", "excludeRules", "excludeComputations")

internal sealed interface GroupAssembly {
    data class Ordinary(val elements: JsonArray?) : GroupAssembly
    data class Include(val directive: IncludeDirective) : GroupAssembly
}

internal data class IncludeDirective(
    val alias: String,
    val level: IncludeLevel,
    val exclusions: IncludeExclusions,
)

internal fun classifyGroup(
    body: JsonObject,
    source: ParsedWorkspaceSource,
    path: String,
): GroupAssembly {
    val aliasNode = body["modelAlias"]
    if (aliasNode == null) {
        val misplaced = INCLUDE_ONLY_DIRECTIVE_KEYS.firstOrNull(body::containsKey)
        if (misplaced != null) {
            throw invalidIncludeMount(
                source.source.modelId,
                path,
                "$misplaced is valid only on an include mount",
            )
        }
        return GroupAssembly.Ordinary(optionalElements(body, source, path))
    }

    val alias = (aliasNode as? JsonPrimitive)
        ?.takeIf(JsonPrimitive::isString)
        ?.content
        ?.takeIf(String::isNotBlank)
        ?: throw invalidIncludeMount(
            source.source.modelId,
            path,
            "modelAlias must be a non-empty string",
        )
    val elements = body["elements"]?.let {
        it as? JsonArray
            ?: throw invalidIncludeMount(
                source.source.modelId,
                path,
                "include-mount elements must be an array",
            )
    }
    // The Kernel accepts this ambiguous shape by silently keeping the local elements and ignoring the include.
    // Portable preparation refuses instead of discarding either authored source; the oracle behavior is locked by
    // WorkspaceIncludeStructureKernelTest.
    if (!elements.isNullOrEmpty()) {
        throw invalidIncludeMount(
            source.source.modelId,
            path,
            "a group cannot combine modelAlias with local elements",
        )
    }
    val level = includeLevel(body, source, path)
    val exclusions = IncludeExclusions.from(
        excludeRules = includeExclusionFlag(body, "excludeRules", source, path),
        excludeComputations = includeExclusionFlag(body, "excludeComputations", source, path),
    )
    return GroupAssembly.Include(IncludeDirective(alias, level, exclusions))
}

private fun optionalElements(
    body: JsonObject,
    source: ParsedWorkspaceSource,
    path: String,
): JsonArray? {
    val value = body["elements"] ?: return null
    return value as? JsonArray
        ?: throw invalidModelElement(source, path, "Group.elements must be an array")
}

private fun includeLevel(
    body: JsonObject,
    source: ParsedWorkspaceSource,
    path: String,
): IncludeLevel {
    val value = body["includeLevel"] ?: return IncludeLevel.SINGLE_RG
    val wireName = (value as? JsonPrimitive)
        ?.takeIf(JsonPrimitive::isString)
        ?.content
    return IncludeLevel.fromWireName(wireName)
        ?: throw invalidIncludeMount(
            source.source.modelId,
            path,
            "includeLevel must be SINGLE_RG or MODEL_ROOT",
        )
}

private fun includeExclusionFlag(
    body: JsonObject,
    key: String,
    source: ParsedWorkspaceSource,
    path: String,
): Boolean {
    val value = body[key] ?: return false
    val boolean = (value as? JsonPrimitive)
        ?.takeIf { !it.isString }
        ?.content
        ?.toBooleanStrictOrNull()
    if (boolean != null) {
        return boolean
    }

    // The Kernel treats a malformed exclusion value as false. At the portable boundary that would turn an
    // authoring error into executable content, so refuse it explicitly; WorkspaceIncludeStructureKernelTest
    // locks the deliberately stricter policy's external basis.
    throw invalidIncludeMount(
        source.source.modelId,
        path,
        "$key must be a Boolean",
    )
}

private fun invalidIncludeMount(
    modelId: String,
    path: String,
    reason: String,
): ModelPreparationError =
    ModelPreparationError(
        listOf(
            InvalidIncludeMountDiagnostic(
                modelId = modelId,
                path = path,
                reason = reason,
            ),
        ),
    )

private fun invalidModelElement(
    source: ParsedWorkspaceSource,
    path: String,
    reason: String,
): ModelPreparationError =
    ModelPreparationError(
        listOf(
            InvalidModelElementDiagnostic(
                modelId = source.source.modelId,
                diagnosticLabel = source.source.diagnosticLabel,
                path = path,
                reason = reason,
            ),
        ),
    )

private data class MountedIncludeContent(
    val children: List<JsonObject>,
    val prefixes: List<MountedPrefix>,
    val usageTypeTransfer: UsageTypeTransfer,
)

private sealed interface UsageTypeTransfer {
    data object PreserveMount : UsageTypeTransfer
    data class ReplaceMount(val value: JsonElement?) : UsageTypeTransfer
}

private data class MountedExecutableOriginDraft(
    val outputPath: String,
    val outputOrdinal: Int,
    val sourceParentPath: String,
    val moveContext: IncludeMoveContext,
    val elementType: PreparedElementType,
) {
    val occurrence: ExecutableOccurrence get() = ExecutableOccurrence(outputPath, elementType)

    fun materialize(): MountedExecutableOrigin =
        MountedExecutableOrigin(
            outputPath = outputPath,
            outputOrdinal = outputOrdinal,
            sourceParentPath = sourceParentPath,
            namespace = moveContext.namespace,
        )
}

private data class ExecutableOccurrence(
    val outputPath: String,
    val elementType: PreparedElementType,
)

private class IncludeMoveContext {
    private var boundNamespace: MountedNamespace? = null

    val namespace: MountedNamespace
        get() = checkNotNull(boundNamespace) { "mounted namespace has not been bound" }

    fun bind(namespace: MountedNamespace) {
        check(boundNamespace == null) { "mounted namespace is already bound" }
        boundNamespace = namespace
    }
}

internal enum class IncludeLevel {
    SINGLE_RG,
    MODEL_ROOT;

    companion object {
        fun fromWireName(value: String?): IncludeLevel? = when (value) {
            SINGLE_RG.name -> SINGLE_RG
            MODEL_ROOT.name -> MODEL_ROOT
            else -> null
        }
    }
}

internal class IncludeExclusions private constructor(
    private val values: Set<IncludeExclusion>,
) {
    fun excludes(elementType: PreparedElementType): Boolean = when (elementType) {
        PreparedElementType.RULE -> IncludeExclusion.RULES in values
        PreparedElementType.COMPUTATION -> IncludeExclusion.COMPUTATIONS in values
        PreparedElementType.FIELD, PreparedElementType.GROUP -> false
    }

    fun isEmpty(): Boolean = values.isEmpty()

    companion object {
        fun from(
            excludeRules: Boolean,
            excludeComputations: Boolean,
        ): IncludeExclusions = IncludeExclusions(
            buildSet {
                if (excludeRules) {
                    add(IncludeExclusion.RULES)
                }
                if (excludeComputations) {
                    add(IncludeExclusion.COMPUTATIONS)
                }
            },
        )
    }
}

private enum class IncludeExclusion {
    RULES,
    COMPUTATIONS,
}

private fun JsonObject.strictOptionalInt(key: String): Int? {
    val value = get(key) ?: return 1
    return (value as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()
}

private fun JsonObject.withoutIncludeDirectives(): JsonObject =
    JsonObject(
        filterKeys {
            it != "modelAlias" &&
                it != "includeLevel" &&
                it != "excludeRules" &&
                it != "excludeComputations"
        },
    )

private fun JsonObject.withTransferredUsageType(transfer: UsageTypeTransfer): JsonObject = when (transfer) {
    UsageTypeTransfer.PreserveMount -> this
    is UsageTypeTransfer.ReplaceMount -> {
        if (transfer.value == null) {
            JsonObject(filterKeys { it != "usageType" })
        } else {
            setting("usageType", transfer.value)
        }
    }
}

private fun JsonObject.withoutModelReferences(): JsonObject {
    val header = objectMember("header") ?: return this
    return replacing(
        "header",
        JsonObject(header.filterKeys { it != "modelReferences" }),
    )
}

private fun JsonObject.fieldRefByShortNameAllowed(): Boolean =
    objectMember("content")
        ?.objectMember("modelConfig")
        ?.get("fieldRefByShortNameAllowed")
        ?.let { it as? JsonPrimitive }
        ?.takeIf { !it.isString }
        ?.content
        ?.toBooleanStrictOrNull()
        ?: false

internal fun JsonObject.conditionLanguageCode(): String? =
    objectMember("content")
        ?.objectMember("modelConfig")
        ?.objectMember("conditionLanguage")
        ?.strictString("code")
