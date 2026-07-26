package io.github.mbackschat.a12.dm.interpreter.workspace

import io.github.mbackschat.a12.dm.interpreter.model.ConditionLanguage
import io.github.mbackschat.a12.dm.interpreter.parse.parseCondition
import io.github.mbackschat.a12.dm.interpreter.parse.parseExpression
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A mounted workspace entry whose document-model element ids use their final name paths. */
internal class ReferenceAssembly internal constructor(
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

/** Test-visible lifecycle seam for preventive-allocation guards. */
internal fun interface ReferenceAssemblyObserver {
    fun sourceSymbolIndexStarted()
}

/** Portable name-path normalization over the structurally mounted workspace closure. */
internal object WorkspaceReferenceAssembler {

    fun assemble(
        workspace: AdmittedWorkspace,
        entryModelId: String,
        limits: ModelPreparationLimits = ModelPreparationLimits.defaults(),
        observer: ReferenceAssemblyObserver? = null,
    ): ReferenceAssembly {
        val mounted = WorkspaceIncludeAssembler.assemble(workspace, entryModelId, limits)
        val byteBudget = PreparedJsonByteBudget(mounted.root, limits)
        val root = ReferenceNormalizer(mounted, byteBudget, observer).normalize()
        byteBudget.verify(root)
        return ReferenceAssembly(
            entryModelId = mounted.entryModelId,
            dependencies = mounted.dependencies,
            mountedExecutableOrigins = mounted.mountedExecutableOrigins,
            root = root,
        )
    }
}

/**
 * Finalizes one structurally mounted tree without losing the namespace in which each executable was authored.
 * A fragment first binds against provider-local symbols and its provider declaring group, then the occurrence's
 * prefix map projects that bound target into final output coordinates. The output-tree walk and the provenance
 * cursors must therefore consume executable occurrences in the same order.
 *
 * Replacement bytes are reserved before a rewritten string is materialized. Diagnostics accumulate across the
 * pass, but a tree containing any incompatible language or unmapped included reference is never returned.
 */
private class ReferenceNormalizer(
    private val mounted: IncludeAssembly,
    private val byteBudget: PreparedJsonByteBudget,
    private val observer: ReferenceAssemblyObserver?,
) {
    private val originsByPath: Map<String, OriginCursor> = mounted.mountedExecutableOrigins
        .groupBy(MountedExecutableOrigin::outputPath)
        .mapValues { (_, origins) -> OriginCursor(origins.sortedBy(MountedExecutableOrigin::outputOrdinal)) }
    private lateinit var sourceSymbols: Map<MountedNamespace, SourceModelSymbols>
    private val diagnostics = mutableListOf<ModelPreparationDiagnostic>()
    private val diagnosedResiduals = mutableSetOf<ResidualLocation>()
    private val diagnosedLanguageNamespaces = mutableSetOf<MountedNamespace>()
    private val entryLanguage = ConditionLanguage.fromCode(mounted.root.conditionLanguageCode())

    fun normalize(): JsonObject {
        // Final ids are the potentially much larger retained representation. Charge and materialize them before
        // constructing any derived provider-path index, so a prepared-byte refusal cannot trail that allocation.
        val withFinalIds = normalizeIds(mounted.root)
        sourceSymbols = MountedSourceSymbolIndex.create(
            root = withFinalIds,
            namespaces = mounted.mountedExecutableOrigins.map(MountedExecutableOrigin::namespace).distinct(),
            observer = observer,
        )
        val normalized = normalizeExecutables(withFinalIds)
        check(originsByPath.values.all(OriginCursor::fullyConsumed)) {
            "mounted executable provenance does not match the mounted output tree"
        }
        if (diagnostics.isNotEmpty()) {
            throw ModelPreparationError(diagnostics)
        }
        return normalized
    }

    private fun normalizeIds(root: JsonObject): JsonObject =
        replaceRootGroups(root) { normalizeElementId(it, "") }

    private fun normalizeElementId(element: JsonElement, parentPath: String): JsonObject {
        val node = element as JsonObject
        val name = node.strictString("name").orEmpty()
        val path = byteBudget.materializeStringMember(node, "id", parentPath, "/", name)
        val elementType = checkNotNull(PreparedElementType.fromWireName(node.strictString("type"))) {
            "include assembly emitted an unsupported document-model element"
        }
        val normalized = if (elementType == PreparedElementType.GROUP) {
            normalizeGroupIds(node, path)
        } else {
            node
        }
        return normalized.setting("id", JsonPrimitive(path))
    }

    private fun normalizeGroupIds(node: JsonObject, path: String): JsonObject {
        val body = node.objectMember(PreparedElementType.GROUP.wireName) ?: return node
        val elements = body.arrayMember("elements") ?: return node
        return node.replacing(
            PreparedElementType.GROUP.wireName,
            body.replacing(
                "elements",
                JsonArray(elements.map { normalizeElementId(it, path) }),
            ),
        )
    }

    private fun normalizeExecutables(root: JsonObject): JsonObject =
        replaceRootGroups(root, ::normalizeExecutableElement)

    private fun replaceRootGroups(
        root: JsonObject,
        transform: (JsonElement) -> JsonObject,
    ): JsonObject {
        val content = root.objectMember("content") ?: return root
        val modelRoot = content.objectMember("modelRoot") ?: return root
        val rootGroups = modelRoot.arrayMember("rootGroups") ?: return root
        return root.replacing(
            "content",
            content.replacing(
                "modelRoot",
                modelRoot.replacing(
                    "rootGroups",
                    JsonArray(rootGroups.map(transform)),
                ),
            ),
        )
    }

    private fun normalizeExecutableElement(element: JsonElement): JsonObject {
        val node = element as JsonObject
        val path = checkNotNull(node.strictString("id")) {
            "id normalization omitted a document-model element"
        }
        val elementType = checkNotNull(PreparedElementType.fromWireName(node.strictString("type"))) {
            "include assembly emitted an unsupported document-model element"
        }
        return when (elementType) {
            PreparedElementType.GROUP -> normalizeExecutableGroup(node)
            PreparedElementType.FIELD,
            -> node
            PreparedElementType.RULE -> normalizeRule(node, path)
            PreparedElementType.COMPUTATION -> normalizeComputation(node, path)
        }
    }

    private fun normalizeExecutableGroup(node: JsonObject): JsonObject {
        val body = node.objectMember(PreparedElementType.GROUP.wireName) ?: return node
        val elements = body.arrayMember("elements") ?: return node
        return node.replacing(
            PreparedElementType.GROUP.wireName,
            body.replacing(
                "elements",
                JsonArray(elements.map(::normalizeExecutableElement)),
            ),
        )
    }

    private fun normalizeRule(node: JsonObject, path: String): JsonObject {
        val origin = nextOrigin(path) ?: return node
        val body = node.objectMember(PreparedElementType.RULE.wireName) ?: return node
        return node.replacing(
            PreparedElementType.RULE.wireName,
            remapMember(
                body,
                "errorCondition",
                ReferenceFragmentKind.CONDITION,
                binding(origin),
                origin,
            ),
        )
    }

    private fun normalizeComputation(node: JsonObject, path: String): JsonObject {
        val origin = nextOrigin(path) ?: return node
        val body = node.objectMember(PreparedElementType.COMPUTATION.wireName) ?: return node
        val binding = binding(origin)
        val withCommonPrecondition = remapMember(
            body,
            "commonPrecondition",
            ReferenceFragmentKind.CONDITION,
            binding,
            origin,
        )
        val alternatives = withCommonPrecondition.arrayMember("computationAlternatives")
            ?: return node.replacing(PreparedElementType.COMPUTATION.wireName, withCommonPrecondition)
        val remappedAlternatives = alternatives.map { candidate ->
            val alternative = candidate as? JsonObject ?: return@map candidate
            remapMember(
                remapMember(
                    alternative,
                    "precondition",
                    ReferenceFragmentKind.CONDITION,
                    binding,
                    origin,
                ),
                "operation",
                ReferenceFragmentKind.EXPRESSION,
                binding,
                origin,
            )
        }
        return node.replacing(
            PreparedElementType.COMPUTATION.wireName,
            withCommonPrecondition.replacing(
                "computationAlternatives",
                JsonArray(remappedAlternatives),
            ),
        )
    }

    private fun remapMember(
        owner: JsonObject,
        key: String,
        kind: ReferenceFragmentKind,
        binding: ReferenceBinding,
        origin: MountedExecutableOrigin,
    ): JsonObject {
        val value = owner[key] as? JsonPrimitive
        if (value == null || !value.isString) {
            return owner
        }
        val source = value.content
        val measuredBytes = ReferenceFragmentRemapper.measureQuotedBytes(
            source = source,
            binding = binding,
            stopAfter = byteBudget.maximumReplacementBytes(source),
        )
        byteBudget.commitStringReplacement(source, measuredBytes)
        val remapped = ReferenceFragmentRemapper.remap(source, binding)
        if (
            origin.namespace !in diagnosedLanguageNamespaces &&
            !isLanguageCompatible(remapped.text, kind, origin)
        ) {
            diagnosedLanguageNamespaces += origin.namespace
            diagnostics += IncompatibleConditionLanguageDiagnostic(
                modelId = origin.sourceModelId,
                diagnosticLabel = origin.namespace.diagnosticLabel,
                path = origin.outputPath,
                referenceChain = origin.namespace.referenceChain,
                sourceLanguage = binding.conditionLanguage.code,
                entryLanguage = entryLanguage.code,
            )
        }
        remapped.residualReferences.forEach { reference ->
            val location = ResidualLocation(origin.outputPath, reference)
            if (diagnosedResiduals.add(location)) {
                diagnostics += UnmappedIncludedReferenceDiagnostic(
                    modelId = origin.sourceModelId,
                    diagnosticLabel = origin.namespace.diagnosticLabel,
                    path = origin.outputPath,
                    reference = reference,
                    referenceChain = origin.namespace.referenceChain,
                )
            }
        }
        return if (remapped.text == source) {
            owner
        } else {
            owner.setting(key, JsonPrimitive(remapped.text))
        }
    }

    /**
     * A language-neutral fragment remains legal through a differently configured entry. Compare canonical ASTs
     * rather than refusing on configuration alone: the real Kernel rejects localized provider syntax only when
     * expansion makes the entry grammar reinterpret it (the mixed-language fixture locks that boundary).
     */
    private fun isLanguageCompatible(
        text: String,
        kind: ReferenceFragmentKind,
        origin: MountedExecutableOrigin,
    ): Boolean {
        val sourceLanguage = ConditionLanguage.fromCode(origin.namespace.conditionLanguageCode)
        if (sourceLanguage == entryLanguage) {
            return true
        }
        val base = origin.outputPath.substringBeforeLast('/')
        val sourceAst = runCatching {
            kind.parse(text, base, sourceLanguage)
        }.getOrNull() ?: return false
        val entryAst = runCatching {
            kind.parse(text, base, entryLanguage)
        }.getOrNull() ?: return false
        return sourceAst == entryAst
    }

    private fun binding(origin: MountedExecutableOrigin): ReferenceBinding =
        ReferenceBinding(
            prefixMapping = origin.namespace.mapping,
            declaringGroup = origin.sourceParentPath,
            sourceSymbols = sourceSymbols.getValue(origin.namespace),
            shortNamesAllowed = origin.namespace.fieldRefByShortNameAllowed,
            conditionLanguage = ConditionLanguage.fromCode(origin.namespace.conditionLanguageCode),
        )

    private fun nextOrigin(path: String): MountedExecutableOrigin? =
        originsByPath[path]?.next()
}

private class OriginCursor(
    private val origins: List<MountedExecutableOrigin>,
) {
    private var nextIndex = 0

    fun next(): MountedExecutableOrigin? =
        origins.getOrNull(nextIndex)?.also { nextIndex++ }

    fun fullyConsumed(): Boolean = nextIndex == origins.size
}

/**
 * Builds every provider occurrence's pre-mount symbol view in one output-tree walk. Nested fields contribute to
 * both their own namespace and active outer provider namespaces; repeated and wide mounts remain proportional to
 * their emitted occurrences rather than to the number of executable fragments that share a namespace.
 */
private object MountedSourceSymbolIndex {

    fun create(
        root: JsonObject,
        namespaces: List<MountedNamespace>,
        observer: ReferenceAssemblyObserver?,
    ): Map<MountedNamespace, SourceModelSymbols> {
        observer?.sourceSymbolIndexStarted()
        val mutableSymbols = namespaces.associateWithTo(linkedMapOf()) { MutableSourceSymbols() }
        val namespacesStartingAt = linkedMapOf<String, MutableList<MountedNamespace>>()
        namespaces.forEach { namespace ->
            namespace.prefixes.forEach { prefix ->
                namespacesStartingAt
                    .getOrPut(prefix.outputPrefix) { mutableListOf() }
                    .add(namespace)
            }
        }
        val active = linkedMapOf<MountedNamespace, Int>()
        val rootGroups = root.objectMember("content")
            ?.objectMember("modelRoot")
            ?.arrayMember("rootGroups")
            .orEmpty()
        rootGroups.forEach { candidate ->
            collect(
                element = candidate as JsonObject,
                namespacesStartingAt = namespacesStartingAt,
                active = active,
                mutableSymbols = mutableSymbols,
            )
        }
        return mutableSymbols.mapValues { (_, symbols) -> symbols.freeze() }
    }

    private fun collect(
        element: JsonObject,
        namespacesStartingAt: Map<String, List<MountedNamespace>>,
        active: MutableMap<MountedNamespace, Int>,
        mutableSymbols: Map<MountedNamespace, MutableSourceSymbols>,
    ) {
        val path = checkNotNull(element.strictString("id")) {
            "source-symbol indexing requires normalized element ids"
        }
        val starting = namespacesStartingAt[path].orEmpty()
        starting.forEach { namespace ->
            active[namespace] = active.getOrElse(namespace) { 0 } + 1
        }

        val elementType = PreparedElementType.fromWireName(element.strictString("type"))
        if (elementType == PreparedElementType.GROUP || elementType == PreparedElementType.FIELD) {
            active.keys.forEach { namespace ->
                namespace.sourcePathFor(path)?.let { sourcePath ->
                    mutableSymbols.getValue(namespace).add(elementType, sourcePath)
                }
            }
        }
        if (elementType == PreparedElementType.GROUP) {
            element.objectMember(PreparedElementType.GROUP.wireName)
                ?.arrayMember("elements")
                ?.forEach { child ->
                    collect(
                        element = child as JsonObject,
                        namespacesStartingAt = namespacesStartingAt,
                        active = active,
                        mutableSymbols = mutableSymbols,
                    )
                }
        }

        starting.asReversed().forEach { namespace ->
            val remaining = active.getValue(namespace) - 1
            if (remaining == 0) {
                active.remove(namespace)
            } else {
                active[namespace] = remaining
            }
        }
    }
}

private class MutableSourceSymbols {
    private val groupPaths = linkedSetOf<String>()
    private val fieldPaths = linkedSetOf<String>()

    fun add(elementType: PreparedElementType, sourcePath: String) {
        when (elementType) {
            PreparedElementType.GROUP -> groupPaths += sourcePath
            PreparedElementType.FIELD -> fieldPaths += sourcePath
            PreparedElementType.RULE,
            PreparedElementType.COMPUTATION,
            -> error("only groups and fields belong to a source symbol index")
        }
    }

    fun freeze(): SourceModelSymbols = SourceModelSymbols.fromPaths(groupPaths, fieldPaths)
}

private data class ResidualLocation(
    val outputPath: String,
    val reference: String,
)

private enum class ReferenceFragmentKind {
    CONDITION,
    EXPRESSION,
    ;

    fun parse(text: String, base: String, language: ConditionLanguage): Any =
        when (this) {
            CONDITION -> parseCondition(text, base, language = language)
            EXPRESSION -> parseExpression(text, base, language = language)
        }
}
