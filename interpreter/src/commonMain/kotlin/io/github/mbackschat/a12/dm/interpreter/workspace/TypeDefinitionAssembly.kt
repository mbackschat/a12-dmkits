package io.github.mbackschat.a12.dm.interpreter.workspace

import io.github.mbackschat.a12.dm.interpreter.isJavaBlank
import io.github.mbackschat.a12.dm.interpreter.ModelPreparationError
import io.github.mbackschat.a12.dm.interpreter.ModelPreparationLimits
import io.github.mbackschat.a12.dm.interpreter.ModelReferencePurpose
import io.github.mbackschat.a12.dm.interpreter.ModelReferenceStep
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * A typedef-resolved intermediate. Include references deliberately remain for the mounting stage; the model tree
 * already contains effective concrete field types, and every dependency is an exact immutable workspace edge.
 */
internal class TypeDefinitionAssembly internal constructor(
    val entryModelId: String,
    dependencies: List<ResolvedModelReference>,
    val root: JsonObject,
) {
    val dependencies: List<ResolvedModelReference> = immutableListSnapshot(dependencies)
    val json: String by lazy { root.toString() }
}

/**
 * Portable typedef assembly over admitted sources. The registry is assembly-scoped because imported and included
 * models contribute to one effective-type namespace before the final field pass.
 */
internal object WorkspaceTypeDefinitionAssembler {

    fun assemble(
        workspace: AdmittedWorkspace,
        entryModelId: String,
        limits: ModelPreparationLimits = ModelPreparationLimits.defaults(),
    ): TypeDefinitionAssembly {
        val preparation = prepare(workspace, entryModelId, limits)
        return preparation.completeUnmounted(preparation.entry.root)
    }

    fun prepare(
        workspace: AdmittedWorkspace,
        entryModelId: String,
        limits: ModelPreparationLimits = ModelPreparationLimits.defaults(),
    ): WorkspaceTypeDefinitionPreparation {
        val context = WorkspaceAssemblyContext(workspace, limits)
        val entry = context.entry(entryModelId)
        val registry = TypeDefinitionRegistry(context, entryModelId)
        registry.registerWorkspaceClosure(entry)
        return WorkspaceTypeDefinitionPreparation(
            entryModelId = entryModelId,
            context = context,
            entry = entry,
            registry = registry,
        )
    }
}

internal class WorkspaceTypeDefinitionPreparation internal constructor(
    val entryModelId: String,
    val context: WorkspaceAssemblyContext,
    val entry: ParsedWorkspaceSource,
    private val registry: TypeDefinitionRegistry,
) {

    fun completeUnmounted(root: JsonObject): TypeDefinitionAssembly =
        finish(inlineEffectiveTypes(root, countPreparedElements = true).withoutTypeDefinitionReferences())

    fun completeMounted(root: JsonObject): TypeDefinitionAssembly =
        finish(
            inlineEffectiveTypes(root, countPreparedElements = false)
                .withoutTypeDefinitionReferences()
                .withoutModelReferences(),
        )

    private fun inlineEffectiveTypes(
        root: JsonObject,
        countPreparedElements: Boolean,
    ): JsonObject =
        EffectiveTypeInliner(
            context = context,
            definitions = registry,
            entryModelId = entryModelId,
            countPreparedElements = countPreparedElements,
        ).inline(root)

    private fun finish(root: JsonObject): TypeDefinitionAssembly {
        context.requirePreparedBytes(root)
        return TypeDefinitionAssembly(
            entryModelId = entryModelId,
            dependencies = context.dependenciesSnapshot(),
            root = root,
        )
    }
}

/**
 * One exact-id namespace for the full assembled model. A declaration reached twice through a diamond is the
 * same origin; two actual declarations are refused so preparation never selects a winner by source order.
 */
internal class TypeDefinitionRegistry(
    private val context: WorkspaceAssemblyContext,
    private val entryModelId: String,
) {
    private val definitions = linkedMapOf<String, RegisteredTypeDefinition>()
    private val resolutions = mutableMapOf<String, TypeDefinitionResolution>()
    private var resolutionStarted = false

    /**
     * Register the entry plus every actually mounted include. Included models share the same namespace, whereas
     * a typedef-import provider contributes only its declarations and transitive typedef imports.
     */
    fun registerWorkspaceClosure(entry: ParsedWorkspaceSource) {
        registerStructuralClosure(entry, emptyList())
    }

    fun resolve(definitionId: String): TypeDefinitionResolution =
        resolutions[definitionId] ?: resolveIteratively(definitionId)

    /**
     * Validate definitions that no field happens to use. Otherwise a dangling or cyclic imported declaration
     * could survive preparation invisibly and become order-dependent when a later include contributes a consumer.
     */
    fun requireNoResidue() {
        for ((definitionId, definition) in definitions) {
            val residue = resolve(definitionId) as? TypeDefinitionResolution.Residue ?: continue
            throw ModelPreparationError.from(
                listOf(
                    UnpreparedTypeDefinitionDiagnostic(
                        modelId = definition.origin.modelId,
                        path = "typeDefinitions/$definitionId",
                        reference = residue.reference,
                        cyclic = residue.cyclic,
                    ),
                ),
            )
        }
    }

    /** Imported providers recurse through their own imports even when they contribute local definitions. */
    private fun registerImportedModel(
        model: ParsedWorkspaceSource,
        referenceChain: List<ModelReferenceStep>,
    ) {
        registerLocalDefinitions(model)
        registerDiscoveredReferences(
            model = model,
            referenceChain = referenceChain,
            mode = WorkspaceReferenceDiscoveryMode.TYPE_DEFINITION_PROVIDER,
        )
    }

    private fun registerLocalDefinitions(model: ParsedWorkspaceSource) {
        val typeDefinitions = model.root.objectMember("content")
            ?.arrayMember("typeDefinitions")
            ?: return
        // A non-empty declaration array selects local mode. This preserves the preparation observation; a malformed
        // member is refused rather than ignored to activate imports.
        typeDefinitions.forEachIndexed { index, candidate ->
            context.consumePreparedElement()
            val path = "typeDefinitions/$index"
            val definition = candidate as? JsonObject
                ?: throw invalidTypeDefinition(model.source.modelId, path, "declaration must be an object")
            // A12 names only the empty id ("The id is empty"); an id of spaces declares and resolves like any other.
            val definitionId = definition.strictString("id")?.takeIf(String::isNotEmpty)
                ?: throw invalidTypeDefinition(model.source.modelId, path, "id must be a non-empty string")
            val fieldType = definition.objectMember("fieldType")
                ?: throw invalidTypeDefinition(model.source.modelId, path, "fieldType must be an object")
            requireCompleteTypeDefinitionReference(model.source.modelId, path, fieldType)
            register(
                definitionId,
                RegisteredTypeDefinition(
                    origin = TypeDefinitionOrigin(model.source.modelId, index),
                    fieldType = fieldType,
                ),
            )
        }
    }

    private fun registerDiscoveredReferences(
        model: ParsedWorkspaceSource,
        referenceChain: List<ModelReferenceStep>,
        mode: WorkspaceReferenceDiscoveryMode,
    ) {
        val references = WorkspaceReferenceDiscovery.discover(
            source = model,
            mode = mode,
            requireStructureDepth = context::requireStructureDepth,
        )
        for (step in references) {
            val resolved = context.resolve(step, referenceChain)
            when (step.purpose) {
                ModelReferencePurpose.INCLUDE ->
                    registerStructuralClosure(resolved.parsedSource, resolved.referenceChain)
                ModelReferencePurpose.TYPE_DEFINITIONS ->
                    registerImportedModel(resolved.parsedSource, resolved.referenceChain)
            }
        }
    }

    private fun register(definitionId: String, candidate: RegisteredTypeDefinition) {
        check(!resolutionStarted) {
            "type definitions cannot be registered after effective-type resolution starts"
        }
        val existing = definitions[definitionId]
        when {
            existing == null -> definitions[definitionId] = candidate
            existing.origin == candidate.origin -> Unit
            else -> throw ModelPreparationError.from(
                listOf(
                    AmbiguousTypeDefinitionDiagnostic(
                        modelId = entryModelId,
                        reference = definitionId,
                        firstSourceModelId = existing.origin.modelId,
                        conflictingSourceModelId = candidate.origin.modelId,
                    ),
                ),
            )
        }
    }

    private fun registerStructuralClosure(
        model: ParsedWorkspaceSource,
        referenceChain: List<ModelReferenceStep>,
    ) {
        registerLocalDefinitions(model)
        registerDiscoveredReferences(
            model = model,
            referenceChain = referenceChain,
            mode = WorkspaceReferenceDiscoveryMode.STRUCTURAL,
        )
    }

    /**
     * Resolve a chain without consuming the host stack. Every visited id receives the terminal memoized outcome,
     * so validating all declarations is linear in the namespace size rather than quadratic in chain length.
     */
    private fun resolveIteratively(definitionId: String): TypeDefinitionResolution {
        resolutionStarted = true
        val path = mutableListOf<String>()
        val positions = mutableSetOf<String>()
        var current = definitionId
        val outcome: TypeDefinitionResolution
        while (true) {
            val cached = resolutions[current]
            if (cached != null) {
                outcome = cached
                break
            }
            if (!positions.add(current)) {
                outcome = TypeDefinitionResolution.Residue(current, cyclic = true)
                break
            }
            path += current
            val target = definitions[current]
            if (target == null) {
                outcome = TypeDefinitionResolution.Residue(current, cyclic = false)
                break
            }
            val nestedReference = target.fieldType
                .takeIf { it.strictString("type") == TYPE_DEF_TYPE }
                ?.objectMember(TYPE_DEF_TYPE)
                ?.strictString("typeDefinitionId")
            if (nestedReference == null) {
                outcome = TypeDefinitionResolution.Resolved(target.fieldType)
                break
            }
            current = nestedReference
        }
        path.forEach { resolutions[it] = outcome }
        return outcome
    }

    private fun requireCompleteTypeDefinitionReference(
        modelId: String,
        path: String,
        fieldType: JsonObject,
    ) {
        if (fieldType.strictString("type") != TYPE_DEF_TYPE) {
            return
        }
        val reference = fieldType.objectMember(TYPE_DEF_TYPE)
            ?.strictString("typeDefinitionId")
            ?.takeIf(String::isNotEmpty)
        if (reference == null) {
            throw invalidTypeDefinition(
                modelId,
                "$path/fieldType/$TYPE_DEF_TYPE",
                "typeDefinitionId must be a non-empty string",
            )
        }
    }

    private fun invalidTypeDefinition(
        modelId: String,
        path: String,
        reason: String,
    ): ModelPreparationError =
        ModelPreparationError.from(
            listOf(
                InvalidTypeDefinitionDiagnostic(
                    modelId = modelId,
                    path = path,
                    reason = reason,
                ),
            ),
        )

    private companion object {
        const val TYPE_DEF_TYPE = "TypeDefType"
    }
}

/** Closed effective-type lookup: either the complete concrete payload or the exact dangling/cyclic residue. */
internal sealed interface TypeDefinitionResolution {
    data class Resolved(val fieldType: JsonObject) : TypeDefinitionResolution
    data class Residue(val reference: String, val cyclic: Boolean) : TypeDefinitionResolution
}

private data class TypeDefinitionOrigin(
    val modelId: String,
    val declarationIndex: Int,
)

private data class RegisteredTypeDefinition(
    val origin: TypeDefinitionOrigin,
    val fieldType: JsonObject,
)

/**
 * Final pure tree pass over the assembly-wide registry. Fields are transformed before unused declarations are
 * validated so a used bad definition reports the consumer's model path; only truly unused residue reports its
 * declaration location.
 */
internal class EffectiveTypeInliner(
    private val context: WorkspaceAssemblyContext,
    private val definitions: TypeDefinitionRegistry,
    private val entryModelId: String,
    private val countPreparedElements: Boolean,
) {

    fun inline(root: JsonObject): JsonObject {
        val content = root.objectMember("content") ?: return root
        val withEffectiveFields = content.objectMember("modelRoot")
            ?.let { modelRoot ->
                modelRoot.arrayMember("rootGroups")
                    ?.let { rootGroups ->
                        root.replacing(
                            "content",
                            content.replacing(
                                "modelRoot",
                                modelRoot.replacing(
                                    "rootGroups",
                                    JsonArray(rootGroups.map { inlineElement(it, "", depth = 1) }),
                                ),
                            ),
                        )
                    }
            }
            ?: root
        definitions.requireNoResidue()
        return inlineDeclaredTypes(withEffectiveFields)
    }

    private fun inlineElement(
        element: JsonElement,
        parentPath: String,
        depth: Int,
    ): JsonElement {
        val node = element as? JsonObject ?: return element
        val elementType = PreparedElementType.fromWireName(node.strictString("type")) ?: return node
        context.requireStructureDepth(depth)
        if (countPreparedElements) {
            context.consumePreparedElement()
        }
        val name = node.strictString("name").orEmpty()
        val path = "$parentPath/$name"
        return when (elementType) {
            PreparedElementType.FIELD -> inlineField(node, path)
            PreparedElementType.GROUP -> inlineGroup(node, path, depth)
            PreparedElementType.RULE, PreparedElementType.COMPUTATION -> node
        }
    }

    private fun inlineField(node: JsonObject, path: String): JsonObject {
        val field = node.objectMember("Field") ?: return node
        val fieldType = field.objectMember("fieldType") ?: return node
        if (fieldType.strictString("type") != TYPE_DEF_TYPE) {
            return node
        }
        val reference = fieldType.objectMember(TYPE_DEF_TYPE)
            ?.strictString("typeDefinitionId")
            .orEmpty()
        val effectiveType = when (val resolution = definitions.resolve(reference)) {
            is TypeDefinitionResolution.Resolved -> resolution.fieldType
            is TypeDefinitionResolution.Residue ->
                throw typeDefinitionResidue(path, resolution.reference, resolution.cyclic)
        }
        return node.replacing("Field", field.replacing("fieldType", effectiveType))
    }

    private fun inlineGroup(node: JsonObject, path: String, depth: Int): JsonObject {
        val group = node.objectMember("Group") ?: return node
        val elements = group.arrayMember("elements") ?: return node
        return node.replacing(
            "Group",
            group.replacing(
                "elements",
                JsonArray(elements.map { inlineElement(it, path, depth + 1) }),
            ),
        )
    }

    /** Inline retained local declarations too, leaving no hidden `TypeDefType` residue in the intermediate JSON. */
    private fun inlineDeclaredTypes(root: JsonObject): JsonObject {
        val content = root.objectMember("content") ?: return root
        val declarations = content.arrayMember("typeDefinitions") ?: return root
        val transformed = declarations.map { candidate ->
            val declaration = candidate as? JsonObject ?: return@map candidate
            val definitionId = declaration.strictString("id") ?: return@map candidate
            when (val resolution = definitions.resolve(definitionId)) {
                is TypeDefinitionResolution.Resolved ->
                    declaration.replacing("fieldType", resolution.fieldType)
                is TypeDefinitionResolution.Residue ->
                    error("validated type-definition residue escaped into the transform")
            }
        }
        return root.replacing(
            "content",
            content.replacing("typeDefinitions", JsonArray(transformed)),
        )
    }

    private fun typeDefinitionResidue(
        fieldPath: String,
        reference: String,
        cyclic: Boolean,
    ): ModelPreparationError =
        ModelPreparationError.from(
            listOf(
                UnpreparedTypeDefinitionDiagnostic(
                    modelId = entryModelId,
                    path = fieldPath,
                    reference = reference,
                    cyclic = cyclic,
                ),
            ),
        )

    private companion object {
        const val TYPE_DEF_TYPE = "TypeDefType"
    }
}

private fun JsonObject.withoutTypeDefinitionReferences(): JsonObject {
    val header = objectMember("header") ?: return this
    val references = header.arrayMember("modelReferences") ?: return this
    val remaining = references.filterNot { candidate ->
        (candidate as? JsonObject)?.strictString("purpose") == ModelReferencePurpose.TYPE_DEFINITIONS.wireName
    }
    return replacing(
        "header",
        header.replacing("modelReferences", JsonArray(remaining)),
    )
}

internal fun JsonObject.replacing(key: String, value: JsonElement): JsonObject =
    JsonObject(mapValues { (candidate, original) -> if (candidate == key) value else original })

internal fun JsonObject.setting(key: String, value: JsonElement): JsonObject =
    JsonObject(toMutableMap().apply { this[key] = value })

internal fun JsonObject.objectMember(key: String): JsonObject? = get(key) as? JsonObject

internal fun JsonObject.arrayMember(key: String): JsonArray? = get(key) as? JsonArray

/**
 * The model the include mount at [path] names through [alias]: the `reference` of this model's include entry for
 * that alias. A12 finds no included model when the entry is missing or its reference is absent, null or blank as
 * Java's `Character.isWhitespace` reads it, and refuses the expansion ("cannot be found"), so this refuses too
 * rather than reading the alias as the reference; a no-break-space reference is text and resolves exactly.
 */
internal fun JsonObject.includeReference(modelId: String, path: String, alias: String): String {
    val entry = objectMember("header")?.arrayMember("modelReferences")
        ?.filterIsInstance<JsonObject>()
        ?.firstOrNull {
            it.strictString("purpose") == ModelReferencePurpose.INCLUDE.wireName && it.strictString("alias") == alias
        }
    return entry?.strictString("reference")?.takeUnless { it.isJavaBlank() }
        ?: throw invalidIncludeMount(
            modelId,
            path,
            "no header.modelReferences include entry for alias '$alias' names a model, so A12 cannot find the " +
                "included model; declare its non-blank reference",
        )
}
