package io.github.mbackschat.a12.dm.interpreter.workspace

import io.github.mbackschat.a12.dm.interpreter.ModelReferencePurpose
import io.github.mbackschat.a12.dm.interpreter.ModelReferenceStep
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Selects the measured reference-discovery rule for one source occurrence. Structural sources follow mounted
 * includes and use local-definition suppression; imported providers always follow their typedef imports.
 */
internal enum class WorkspaceReferenceDiscoveryMode {
    STRUCTURAL,
    TYPE_DEFINITION_PROVIDER,
}

/**
 * The single reference enumerator used by acquisition and preparation. Keeping discovery here prevents a provider
 * collector from fetching declared-but-unused references that the synchronous assembler would never resolve.
 */
internal object WorkspaceReferenceDiscovery {

    fun discover(
        source: ParsedWorkspaceSource,
        mode: WorkspaceReferenceDiscoveryMode,
        requireStructureDepth: (Int) -> Unit,
    ): List<ModelReferenceStep> = when (mode) {
        WorkspaceReferenceDiscoveryMode.STRUCTURAL -> buildList {
            if (!source.hasLocalTypeDefinitions()) {
                addAll(source.typeDefinitionReferences())
            }
            addAll(source.mountedIncludeReferences(requireStructureDepth))
        }
        WorkspaceReferenceDiscoveryMode.TYPE_DEFINITION_PROVIDER ->
            source.typeDefinitionReferences()
    }

    private fun ParsedWorkspaceSource.hasLocalTypeDefinitions(): Boolean =
        root.objectMember("content")
            ?.arrayMember("typeDefinitions")
            ?.isNotEmpty()
            ?: false

    private fun ParsedWorkspaceSource.typeDefinitionReferences(): List<ModelReferenceStep> {
        val references = root.objectMember("header")
            ?.arrayMember("modelReferences")
            ?: return emptyList()
        return buildList {
            for (candidate in references) {
                val reference = candidate as? JsonObject ?: continue
                if (reference.strictString("purpose") != ModelReferencePurpose.TYPE_DEFINITIONS.wireName) {
                    continue
                }
                add(
                    ModelReferenceStep.typeDefinitions(
                        fromModelId = source.modelId,
                        toModelId = reference.strictString("reference").orEmpty(),
                        alias = reference.strictString("alias"),
                    ),
                )
            }
        }
    }

    private fun ParsedWorkspaceSource.mountedIncludeReferences(
        requireStructureDepth: (Int) -> Unit,
    ): List<ModelReferenceStep> {
        val rootGroups = root.objectMember("content")
            ?.objectMember("modelRoot")
            ?.arrayMember("rootGroups")
            ?: return emptyList()
        val references = mutableListOf<ModelReferenceStep>()
        val pending = mutableListOf<PendingElement>()
        rootGroups.asReversed().forEach {
            pending += PendingElement(it, parentPath = "", depth = 1)
        }
        while (pending.isNotEmpty()) {
            val candidate = pending.removeAt(pending.lastIndex)
            val structure = requirePreparedElementStructure(
                element = candidate.element,
                parentPath = candidate.parentPath,
                position = if (candidate.parentPath.isEmpty()) {
                    PreparedElementPosition.MODEL_ROOT
                } else {
                    PreparedElementPosition.NESTED
                },
            ) { path, reason ->
                throw invalidModelElement(this, path, reason)
            }
            requireStructureDepth(candidate.depth)
            if (structure.type != PreparedElementType.GROUP) {
                continue
            }
            val path = "${candidate.parentPath}/${structure.name}"
            val children = requirePreparedGroupChildren(structure.body, path) { invalidPath, reason ->
                if ("modelAlias" in structure.body) {
                    throw invalidIncludeMount(source.modelId, invalidPath, reason)
                }
                throw invalidModelElement(this, invalidPath, reason)
            }
            when (val assembly = classifyGroup(structure.body, children, this, path)) {
                is GroupAssembly.Include -> references += ModelReferenceStep.include(
                    fromModelId = source.modelId,
                    toModelId = root.includeReference(source.modelId, path, assembly.directive.alias),
                    alias = assembly.directive.alias,
                )
                is GroupAssembly.Ordinary -> assembly.elements
                    ?.asReversed()
                    ?.forEach {
                        pending += PendingElement(
                            element = it,
                            parentPath = path,
                            depth = candidate.depth + 1,
                        )
                    }
            }
        }
        return references
    }

    private data class PendingElement(
        val element: JsonElement,
        val parentPath: String,
        val depth: Int,
    )
}
