package io.github.mbackschat.a12.dm.interpreter.workspace

import io.github.mbackschat.a12.dm.interpreter.conditionLanguageCode
import io.github.mbackschat.a12.dm.interpreter.bindJavaPrimitiveInt
import io.github.mbackschat.a12.dm.interpreter.ConfigFlag
import io.github.mbackschat.a12.dm.interpreter.ModelPreparationError
import io.github.mbackschat.a12.dm.interpreter.ModelPreparationLimits
import io.github.mbackschat.a12.dm.interpreter.ModelReferenceStep
import io.github.mbackschat.a12.dm.interpreter.configFlag
import io.github.mbackschat.a12.dm.interpreter.isJavaBlank
import io.github.mbackschat.a12.dm.interpreter.orRefuse
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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
        val withEffectiveTypes = preparation.completeMounted(mountedRoot)
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
                    check(body?.includeDirectiveMember() == null) {
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
        val structure = requirePreparedElementStructure(
            element = element,
            parentPath = sourceParentPath,
            position = if (sourceParentPath.isEmpty()) {
                PreparedElementPosition.MODEL_ROOT
            } else {
                PreparedElementPosition.NESTED
            },
        ) { path, reason ->
            throw invalidModelElement(owningSource, path, reason)
        }
        val node = structure.node
        val name = structure.name
        val sourcePath = "$sourceParentPath/$name"
        val elementType = structure.type
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

        val body = structure.body
        val children = requirePreparedGroupChildren(body, sourcePath) { invalidPath, reason ->
            if (body.includeDirectiveMember() != null) {
                throw invalidIncludeMount(owningSource.source.modelId, invalidPath, reason)
            }
            throw invalidModelElement(owningSource, invalidPath, reason)
        }
        return when (val assembly = classifyGroup(body, children, owningSource, path)) {
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
            toModelId = directive.reference,
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
        // The mount keeps its own `usageType`: kernel 31.1.1 no longer copies the provider root's onto it
        // (KERNEL-FINDINGS KF3, adapter.MountUsageTypeKernelTest).
        val mountedBody = body.withoutIncludeDirectives()
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
                ?.takeIf(String::isNotEmpty)
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
        val rootStructure = requirePreparedElementStructure(
            element = includedRootGroups.single(),
            parentPath = "",
            position = PreparedElementPosition.MODEL_ROOT,
        ) { path, reason ->
            throw invalidModelElement(includedSource, path, reason)
        }
        val includedRoot = rootStructure.node
        val rootName = rootStructure.name
        val rootBody = rootStructure.body
        val rootChildren = requirePreparedGroupChildren(rootBody, "/$rootName") { invalidPath, reason ->
            if (rootBody.includeDirectiveMember() != null) {
                throw invalidIncludeMount(includedSource.source.modelId, invalidPath, reason)
            }
            throw invalidModelElement(includedSource, invalidPath, reason)
        }
        val repeatability = rootBody.singleRootRepeatabilityOrNull()
            ?: throw invalidIncludeMount(
                mountModelId,
                mountPath,
                "SINGLE_RG root repeatability must be a whole number A12 can bind",
            )
        // A12 refuses only a bound count above one: `0`, `-1`, `1.9`, `"01"`, `""` and null all mount
        // (KERNEL-FINDINGS KF3, `adapter.WorkspaceIncludeStructureKernelTest`).
        if (repeatability > 1) {
            throw invalidIncludeMount(
                mountModelId,
                mountPath,
                "SINGLE_RG requires exactly one non-repeatable root group",
            )
        }
        val mountedBody = when (
            val assembly = classifyGroup(rootBody, rootChildren, includedSource, "/$rootName")
        ) {
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

internal sealed interface GroupAssembly {
    data class Ordinary(val elements: JsonArray?) : GroupAssembly
    data class Include(val directive: IncludeDirective) : GroupAssembly
}

internal data class IncludeDirective(
    val reference: String,
    val level: IncludeLevel,
    val exclusions: IncludeExclusions,
)

/**
 * Whether the group at [path] mounts a model, read as A12 31.1.1 binds DM 29.4's `includeConfig` (KF651): an object
 * is a mount, an absent member or `null` is a plain group, and any other value builds no model.
 */
internal fun classifyGroup(
    body: JsonObject,
    elements: JsonArray?,
    source: ParsedWorkspaceSource,
    path: String,
): GroupAssembly {
    // A12 reads a DM 28 member as nothing and keeps the group plain, so the mount or the option the author meant
    // is lost without a notification (KF651, `adapter.laws.IncludeConfigShapeLawsTest`). Refused on purpose,
    // matching the kits' reader.
    DM28_MOUNT_MEMBERS.firstOrNull(body::containsKey)?.let { stale ->
        throw invalidIncludeMount(
            source.source.modelId,
            path,
            "$stale is a DM 28 mount member that A12 has ignored since DM 29.0, so it would be lost silently; " +
                "write the mount as includeConfig {reference, includeLevel?, excludeRules?, excludeComputations?}",
        )
    }
    val config = when (val value = body[INCLUDE_CONFIG_MEMBER]) {
        null, is JsonNull -> return GroupAssembly.Ordinary(elements)
        is JsonObject -> value
        else -> throw invalidIncludeMount(
            source.source.modelId,
            path,
            "includeConfig must be an object; A12 builds no model from any other value",
        )
    }
    val reference = includedReference(config, source, path)
    // A12 mounts the included content and discards the group's own elements without a notification (KF651).
    // Portable preparation refuses instead of discarding authored content.
    if (!elements.isNullOrEmpty()) {
        throw invalidIncludeMount(
            source.source.modelId,
            path,
            "a group cannot combine includeConfig with local elements",
        )
    }
    val exclusions = IncludeExclusions.from(
        excludeRules = includeExclusionFlag(config, "excludeRules", source, path),
        excludeComputations = includeExclusionFlag(config, "excludeComputations", source, path),
    )
    return GroupAssembly.Include(IncludeDirective(reference, includeLevel(config, source, path), exclusions))
}

/**
 * The included model's id: a scalar's JSON text (a number keeps its zeros, a string is not trimmed). A12 refuses an
 * absent or Java-blank id as "not specified" and builds no model from `null`, an object or an array (KF651).
 */
private fun includedReference(config: JsonObject, source: ParsedWorkspaceSource, path: String): String {
    val value = config["reference"]
    val text = (value as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
    if (value != null && text == null) {
        throw invalidIncludeMount(source.source.modelId, path, "includeConfig.reference must be a text")
    }
    return text?.takeUnless(String::isJavaBlank)
        ?: throw invalidIncludeMount(
            source.source.modelId,
            path,
            "includeConfig.reference names no model, so A12 refuses to expand the mount",
        )
}

/**
 * The level as A12 binds its enum: a constant's name or its ordinal ([IncludeLevel.SINGLE_RG] is 0), as an integer
 * token or as the ordinal's digits, all after a Java `trim()`. Every other value refuses the model (KF651).
 */
private fun includeLevel(
    config: JsonObject,
    source: ParsedWorkspaceSource,
    path: String,
): IncludeLevel {
    val value = config["includeLevel"] ?: return IncludeLevel.SINGLE_RG
    val primitive = value as? JsonPrimitive
    val level = when {
        primitive == null || primitive is JsonNull -> null
        primitive.isString -> IncludeLevel.fromBoundText(primitive.content.trim { it <= ' ' })
        else -> IncludeLevel.fromOrdinalLiteral(primitive.content)
    }
    return level
        ?: throw invalidIncludeMount(
            source.source.modelId,
            path,
            "includeLevel must be SINGLE_RG or MODEL_ROOT",
        )
}

/** An exclusion flag, bound through A12's shared boolean law, which DM 29.4 moved these members onto (KF651). */
private fun includeExclusionFlag(
    config: JsonObject,
    key: String,
    source: ParsedWorkspaceSource,
    path: String,
): Boolean = configFlag(config[key]).orRefuse {
    throw invalidIncludeMount(
        source.source.modelId,
        path,
        "$key must be a Boolean A12 can bind",
    )
}

internal fun invalidIncludeMount(
    modelId: String,
    path: String,
    reason: String,
): ModelPreparationError =
    ModelPreparationError.from(
        listOf(
            InvalidIncludeMountDiagnostic(
                modelId = modelId,
                path = path,
                reason = reason,
            ),
        ),
    )

internal fun invalidModelElement(
    source: ParsedWorkspaceSource,
    path: String,
    reason: String,
): ModelPreparationError =
    ModelPreparationError.from(
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
)

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
        /** A constant's name, or its ordinal's digits without sign or leading zero. */
        fun fromBoundText(value: String): IncludeLevel? = entries.firstOrNull { it.name == value }
            ?: entries.firstOrNull { it.ordinal.toString() == value }

        /** An integer token's literal, judged on its digits; `-0` is the ordinal 0. */
        fun fromOrdinalLiteral(literal: String): IncludeLevel? {
            val negative = literal.startsWith("-")
            val digits = if (negative) literal.drop(1) else literal
            if (digits.isEmpty() || !digits.all { it in '0'..'9' }) {
                return null
            }
            val magnitude = digits.trimStart('0').ifEmpty { "0" }
            if (negative && magnitude != "0") {
                return null
            }
            return entries.firstOrNull { it.ordinal.toString() == magnitude }
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

/**
 * The provider root's count, bound as A12's reader binds a group's primitive count (`" 1 "` and `"01"` are 1); null
 * where the reader cannot bind it, which on kernel 31 includes the empty text and a JSON or textual null.
 */
private fun JsonObject.singleRootRepeatabilityOrNull(): Int? {
    val value = get("repeatability") ?: return 1
    return value.bindJavaPrimitiveInt()
}

private fun JsonObject.withoutIncludeDirectives(): JsonObject = JsonObject(filterKeys { it != INCLUDE_CONFIG_MEMBER })

internal fun JsonObject.withoutModelReferences(): JsonObject {
    val header = objectMember("header") ?: return this
    return replacing(
        "header",
        JsonObject(header.filterKeys { it != "modelReferences" }),
    )
}

/**
 * The model's `fieldRefByShortNameAllowed`, through the kernel's own POJO binding ([configFlag]).
 *
 * The earlier reading here refused every STRING spelling outright, so a model declaring `"true"` — which the
 * kernel takes as on — had its short-name tier silently disabled at assembly, retargeting bare references
 * across the whole included tree. A value A12 cannot bind never arrives: the source was refused when it was parsed
 * (`unbindableModelMember`).
 */
private fun JsonObject.fieldRefByShortNameAllowed(): Boolean =
    configFlag(objectMember("content")?.objectMember("modelConfig")?.get("fieldRefByShortNameAllowed")) == ConfigFlag.ON

