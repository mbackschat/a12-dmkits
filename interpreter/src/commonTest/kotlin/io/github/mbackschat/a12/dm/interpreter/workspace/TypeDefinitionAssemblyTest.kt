package io.github.mbackschat.a12.dm.interpreter.workspace

import io.github.mbackschat.a12.dm.interpreter.ModelPreparationDiagnostic
import io.github.mbackschat.a12.dm.interpreter.ModelPreparationDiagnosticCode
import io.github.mbackschat.a12.dm.interpreter.ModelPreparationError
import io.github.mbackschat.a12.dm.interpreter.ModelPreparationLimits
import io.github.mbackschat.a12.dm.interpreter.ModelPreparationResource
import io.github.mbackschat.a12.dm.interpreter.ModelReferencePurpose
import io.github.mbackschat.a12.dm.interpreter.ModelReferenceStep
import io.github.mbackschat.a12.dm.interpreter.WorkspaceModelSource
import io.github.mbackschat.a12.dm.interpreter.dec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Common JVM/Node lock for portable typedef assembly: local, imported, transitive, and include-contributed
 * declarations use one exact-id namespace, preserve the concrete field-type payload, retain include mounts, and
 * reject missing, cyclic, ambiguous, or resource-exceeding preparation state with structured diagnostics.
 *
 * Positive sources come from the typed workspace fixture author and are independently observed through ModelReader
 * plus the real Kernel. Negative siblings change only the illegal relationship each refusal test exercises.
 */
class TypeDefinitionAssemblyTest {

    @Test
    fun effectiveTypesMatchTheKernelReaderObservation() {
        val cases = mapOf(
            "local-typedef" to listOf(
                "/Permit/ApplicationCode",
                "/Permit/RequestedArea",
                "/Permit/Decision",
            ),
            "imported-typedef-nested-single" to listOf(
                "/Permit/ApplicationCode",
                "/Permit/InspectionDisposition",
            ),
            "include-contributed-typedef" to listOf("/Permit/ApplicationCode"),
        )

        for ((caseId, paths) in cases) {
            val result = assemble(caseId)
            val expected = Json.parseToJsonElement(
                WorkspacePreparationFixtureJson.effectiveFieldTypes(caseId),
            ).obj()
            for (path in paths) {
                assertEquals(
                    expected.obj(path).normalizedObservedType(),
                    result.root.fieldTypeAt(path).normalizedObservedType(),
                    "$caseId:$path",
                )
            }
        }
    }

    @Test
    fun assemblesLocalTypeDefinitionsIntoEffectivePortableTypes() {
        val result = assemble("local-typedef")
        val fieldType = result.root.fieldTypeAt("/Permit/ApplicationCode")

        assertEquals("StringType", fieldType.string("type"))
        assertEquals(8, fieldType.obj("StringType").primitive("maxLength").content.toInt())
        val numberType = result.root.fieldTypeAt("/Permit/RequestedArea")
        assertEquals("NumberType", numberType.string("type"))
        assertEquals("0.00", numberType.obj("NumberType").primitive("minValue").content)
        assertEquals("999.50", numberType.obj("NumberType").primitive("maxValue").content)
        val enumType = result.root.fieldTypeAt("/Permit/Decision")
        assertEquals("EnumerationType", enumType.string("type"))
        assertEquals(
            listOf("APPROVE", "REJECT"),
            enumType.obj("EnumerationType").array("values").map { it.obj().string("value") },
        )
        assertTrue(result.dependencies.isEmpty())
        assertFalse(result.json.contains("\"TypeDefType\""))
        assertFails { (result.dependencies as MutableList<ResolvedModelReference>).clear() }
    }

    @Test
    fun assemblesImportedTypeDefinitionsAndPreservesIncludesForTheNextStage() {
        val result = assemble("imported-typedef-nested-single")
        val fieldType = result.root.fieldTypeAt("/Permit/ApplicationCode")

        assertEquals("StringType", fieldType.string("type"))
        assertEquals(12, fieldType.obj("StringType").primitive("maxLength").content.toInt())
        assertEquals(
            listOf(
                ModelReferenceStep.typeDefinitions(
                    fromModelId = "permit-single",
                    toModelId = "permit-types",
                    alias = "permit-types",
                ),
                ModelReferenceStep.include(
                    fromModelId = "permit-single",
                    toModelId = "permit-section",
                    alias = "sectionAlias",
                ),
                ModelReferenceStep.include(
                    fromModelId = "permit-section",
                    toModelId = "inspection-checks",
                    alias = "checksAlias",
                ),
            ),
            result.dependencies.map(ResolvedModelReference::step),
        )
        assertEquals(
            listOf("include"),
            result.root.obj("header").array("modelReferences").map { it.obj().string("purpose") },
        )
    }

    @Test
    fun anIncludedModelContributesDefinitionsBeforeEffectiveTypesAreFinalized() {
        val result = assemble("include-contributed-typedef")
        val fieldType = result.root.fieldTypeAt("/Permit/ApplicationCode")

        assertEquals("StringType", fieldType.string("type"))
        assertEquals(10, fieldType.obj("StringType").primitive("maxLength").content.toInt())
        assertEquals(
            listOf(ModelReferencePurpose.INCLUDE),
            result.dependencies.map { it.step.purpose },
        )
        assertEquals(
            listOf("include"),
            result.root.obj("header").array("modelReferences").map { it.obj().string("purpose") },
        )
    }

    @Test
    fun aTransitivelyIncludedModelContributesDefinitionsToTheEntryNamespace() {
        val result = assemble("imported-typedef-nested-single")
        val fieldType = result.root.fieldTypeAt("/Permit/InspectionDisposition")

        assertEquals("StringType", fieldType.string("type"))
        assertEquals(6, fieldType.obj("StringType").primitive("maxLength").content.toInt())
        assertEquals(
            listOf("permit-section", "inspection-checks"),
            result.dependencies
                .filter { it.step.purpose == ModelReferencePurpose.INCLUDE }
                .map { it.step.toModelId },
        )
    }

    @Test
    fun resolvesTransitiveTypeDefinitionImportsInOneAssemblyNamespace() {
        val imported = fixtureSources("imported-typedef-nested-single")
        val leaf = renameModel(imported.getValue("permit-types"), "permit-types-leaf")
        val intermediate = addTypeDefinitionImport(
            removeTypeDefinitions(imported.getValue("permit-types")),
            alias = "leaf",
            reference = "permit-types-leaf",
        )
        val result = WorkspaceTypeDefinitionAssembler.assemble(
            admit(
                imported + mapOf(
                    "permit-single" to imported.getValue("permit-single"),
                    "permit-types" to intermediate,
                    "permit-types-leaf" to leaf,
                ),
            ),
            "permit-single",
        )

        assertEquals(
            12,
            result.root.fieldTypeAt("/Permit/ApplicationCode")
                .obj("StringType")
                .primitive("maxLength")
                .content
                .toInt(),
        )
        assertEquals(
            listOf("permit-types", "permit-types-leaf"),
            result.dependencies
                .filter { it.step.purpose == ModelReferencePurpose.TYPE_DEFINITIONS }
                .map { it.step.toModelId },
        )
    }

    @Test
    fun theSameDefinitionReachedThroughADiamondIsNotAmbiguous() {
        val imported = fixtureSources("imported-typedef-nested-single")
        val branch = addTypeDefinitionImport(
            removeTypeDefinitions(renameModel(imported.getValue("permit-types"), "branch-types")),
            alias = "shared-types",
            reference = "permit-types",
        )
        val entry = addTypeDefinitionImport(
            imported.getValue("permit-single"),
            alias = "branch-types",
            reference = "branch-types",
        )
        val result = WorkspaceTypeDefinitionAssembler.assemble(
            admit(
                imported + mapOf(
                    "permit-single" to entry,
                    "permit-types" to imported.getValue("permit-types"),
                    "branch-types" to branch,
                ),
            ),
            "permit-single",
        )

        assertEquals(
            listOf("permit-types", "branch-types", "permit-types"),
            result.dependencies
                .filter { it.step.purpose == ModelReferencePurpose.TYPE_DEFINITIONS }
                .map { it.step.toModelId },
        )
        assertEquals(
            "StringType",
            result.root.fieldTypeAt("/Permit/ApplicationCode").string("type"),
        )
    }

    @Test
    fun rejectsAnUnresolvedTypeDefinitionWithItsModelAndFieldPath() {
        val sources = fixtureSources("local-typedef").mapValues { (_, source) ->
            replaceTypeReference(source, "LocalPermitCode", "MissingPermitCode")
        }
        val diagnostic = failure(sources, "permit-local-typedef")

        assertEquals(ModelPreparationDiagnosticCode.UNPREPARED_MODEL_RESIDUE, diagnostic.code)
        assertEquals("permit-local-typedef", diagnostic.modelId)
        assertEquals("/Permit/ApplicationCode", diagnostic.path)
        assertEquals("MissingPermitCode", diagnostic.reference)
        assertTrue(diagnostic.message.contains("unresolved type definition"))
    }

    @Test
    fun rejectsACyclicEffectiveTypeInsteadOfLeavingTypeDefResidue() {
        val sources = fixtureSources("local-typedef").mapValues { (_, source) ->
            makeDefinitionSelfReferential(source, "LocalPermitCode")
        }
        val diagnostic = failure(sources, "permit-local-typedef")

        assertEquals(ModelPreparationDiagnosticCode.UNPREPARED_MODEL_RESIDUE, diagnostic.code)
        assertEquals("/Permit/ApplicationCode", diagnostic.path)
        assertEquals("LocalPermitCode", diagnostic.reference)
        assertTrue(diagnostic.message.contains("cycle"))
    }

    @Test
    fun rejectsUnusedCyclicTypeDefinitionResidue() {
        val source = appendUnusedSelfReferentialDefinition(
            fixtureSources("local-typedef").getValue("permit-local-typedef"),
            "UnusedCycle",
        )
        val diagnostic = failure(
            mapOf("permit-local-typedef" to source),
            "permit-local-typedef",
        )

        assertEquals(ModelPreparationDiagnosticCode.UNPREPARED_MODEL_RESIDUE, diagnostic.code)
        assertEquals("UnusedCycle", diagnostic.reference)
        assertTrue(diagnostic.message.contains("cycle"))
    }

    @Test
    fun rejectsResidueInATypeDefinitionOnlyEntryWithoutAModelRoot() {
        val provider = withoutModelRoot(
            fixtureSources("imported-typedef-nested-single").getValue("permit-types"),
        )
        val unresolved = failure(
            mapOf("permit-types" to replaceDefinitionReference(provider, "PermitCode", "MissingCode")),
            "permit-types",
        )
        assertEquals(ModelPreparationDiagnosticCode.UNPREPARED_MODEL_RESIDUE, unresolved.code)
        assertEquals("MissingCode", unresolved.reference)
        assertTrue(unresolved.message.contains("unresolved type definition"))

        val cyclic = failure(
            mapOf("permit-types" to replaceDefinitionReference(provider, "PermitCode", "PermitCode")),
            "permit-types",
        )
        assertEquals(ModelPreparationDiagnosticCode.UNPREPARED_MODEL_RESIDUE, cyclic.code)
        assertEquals("PermitCode", cyclic.reference)
        assertTrue(cyclic.message.contains("cycle"))
    }

    @Test
    fun resolvesALongFlatTypeDefinitionChainWithoutRecursiveOrQuadraticWork() {
        val source = withTypeDefinitionChain(
            fixtureSources("imported-typedef-nested-single").getValue("permit-types"),
            length = 4_096,
        )
        val result = WorkspaceTypeDefinitionAssembler.assemble(
            admit(mapOf("permit-types" to source)),
            "permit-types",
        )

        assertFalse(result.json.contains("\"TypeDefType\""))
        assertEquals(4_096, result.root.obj("content").array("typeDefinitions").size)
    }

    @Test
    fun rejectsAmbiguousImportedTypeDefinitionsInsteadOfDependingOnTraversalOrder() {
        val imported = fixtureSources("imported-typedef-nested-single")
        val alternateTypes = renameModel(
            replaceIntPrimitive(imported.getValue("permit-types"), "maxLength", 12, 16),
            "alternate-permit-types",
        )
        val entry = addTypeDefinitionImport(
            imported.getValue("permit-single"),
            alias = "alternate-types",
            reference = "alternate-permit-types",
        )
        val diagnostic = failure(
            mapOf(
                "permit-single" to entry,
                "permit-types" to imported.getValue("permit-types"),
                "alternate-permit-types" to alternateTypes,
            ),
            "permit-single",
        )

        assertEquals(ModelPreparationDiagnosticCode.AMBIGUOUS_TYPE_DEFINITION, diagnostic.code)
        assertEquals("permit-single", diagnostic.modelId)
        assertEquals("PermitCode", diagnostic.reference)
        assertTrue(diagnostic.message.contains("ambiguous type definition"))
    }

    @Test
    fun rejectsDuplicateDefinitionIdsWithinOneSource() {
        val source = duplicateFirstTypeDefinition(
            fixtureSources("local-typedef").getValue("permit-local-typedef"),
        )
        val diagnostic = failure(
            mapOf("permit-local-typedef" to source),
            "permit-local-typedef",
        )

        assertEquals(ModelPreparationDiagnosticCode.AMBIGUOUS_TYPE_DEFINITION, diagnostic.code)
        assertEquals("LocalPermitCode", diagnostic.reference)
    }

    @Test
    fun rejectsMalformedTypeDefinitionDeclarationsWithStructuredDiagnostics() {
        val source = fixtureSources("local-typedef").getValue("permit-local-typedef")
        val malformedSources = listOf(
            removeDefinitionMember(source, "LocalPermitCode", "id"),
            removeDefinitionMember(source, "LocalPermitCode", "fieldType"),
            replaceDefinitionFieldType(
                source,
                "LocalPermitCode",
                JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("TypeDefType"),
                        "TypeDefType" to JsonObject(emptyMap()),
                    ),
                ),
            ),
        )

        for (malformed in malformedSources) {
            val diagnostic = failure(
                mapOf("permit-local-typedef" to malformed),
                "permit-local-typedef",
            )
            assertEquals(ModelPreparationDiagnosticCode.INVALID_MODEL_SOURCE, diagnostic.code)
            assertEquals("permit-local-typedef", diagnostic.modelId)
            assertTrue(diagnostic.path?.startsWith("typeDefinitions/") == true)
            assertTrue(diagnostic.message.contains("type definition"))
        }
    }

    @Test
    fun rejectsMissingAndCyclicTypeDefinitionModelReferencesStructurally() {
        val imported = fixtureSources("imported-typedef-nested-single")
        val missing = failure(
            mapOf("permit-single" to imported.getValue("permit-single")),
            "permit-single",
        )
        assertEquals(ModelPreparationDiagnosticCode.UNRESOLVED_MODEL_REFERENCE, missing.code)
        assertEquals("permit-types", missing.reference)
        assertEquals(1, missing.referenceChain.size)

        val selfImportingTypes = addTypeDefinitionImport(
            imported.getValue("permit-types"),
            alias = "self",
            reference = "permit-types",
        )
        val cycle = failure(
            mapOf(
                "permit-single" to imported.getValue("permit-single"),
                "permit-types" to selfImportingTypes,
            ),
            "permit-single",
        )
        assertEquals(ModelPreparationDiagnosticCode.MODEL_REFERENCE_CYCLE, cycle.code)
        assertEquals("permit-types", cycle.reference)
        assertEquals(2, cycle.referenceChain.size)
    }

    @Test
    fun enforcesReferenceAndPreparedElementLimitsBeforeFurtherAssembly() {
        val imported = fixtureSources("imported-typedef-nested-single")
        val nestedTypes = addTypeDefinitionImport(
            imported.getValue("permit-types"),
            alias = "missing",
            reference = "deeper-types",
        )
        val depth = failure(
            mapOf(
                "permit-single" to imported.getValue("permit-single"),
                "permit-types" to nestedTypes,
            ),
            "permit-single",
            ModelPreparationLimits.builder().maxReferenceDepth(1).build(),
        )
        assertResource(depth, ModelPreparationResource.REFERENCE_DEPTH, 1, 2)

        val duplicateImport = addTypeDefinitionImport(
            imported.getValue("permit-single"),
            alias = "second-types",
            reference = "permit-types",
        )
        val edges = failure(
            mapOf(
                "permit-single" to duplicateImport,
                "permit-types" to imported.getValue("permit-types"),
            ),
            "permit-single",
            ModelPreparationLimits.builder().maxReferenceEdges(1).build(),
        )
        assertResource(edges, ModelPreparationResource.REFERENCE_EDGES, 1, 2)

        val elements = failure(
            fixtureSources("local-typedef"),
            "permit-local-typedef",
            ModelPreparationLimits.builder().maxPreparedElements(1).build(),
        )
        assertResource(elements, ModelPreparationResource.PREPARED_ELEMENTS, 1, 2)

        val nested = failure(
            mapOf(
                "permit-local-typedef" to withNestedGroups(
                    fixtureSources("local-typedef").getValue("permit-local-typedef"),
                    depth = 33,
                ),
            ),
            "permit-local-typedef",
            ModelPreparationLimits.builder().maxStructureDepth(32).build(),
        )
        assertResource(nested, ModelPreparationResource.STRUCTURE_DEPTH, 32, 33)
    }

    @Test
    fun rejectsAnEntryIdentityThatIsNotInTheAdmittedWorkspace() {
        val diagnostic = failure(
            fixtureSources("local-typedef"),
            "absent-entry",
        )

        assertEquals(ModelPreparationDiagnosticCode.ENTRY_MODEL_NOT_FOUND, diagnostic.code)
        assertEquals("absent-entry", diagnostic.modelId)
    }

    private fun assemble(caseId: String): TypeDefinitionAssembly {
        val entryModelId = WorkspacePreparationFixtureJson.entryModelId(caseId)
        val workspace = admit(fixtureSources(caseId))
        return WorkspaceTypeDefinitionAssembler.assemble(workspace, entryModelId)
    }

    private fun fixtureSources(caseId: String): Map<String, String> =
        WorkspacePreparationFixtureJson.sources(caseId)

    private fun admit(sources: Map<String, String>): AdmittedWorkspace =
        WorkspaceSourceAdmission.admit(
            sources.map { (modelId, json) ->
                WorkspaceModelSource.builder()
                    .json(json)
                    .diagnosticLabel("fixture:$modelId")
                    .build()
            },
        )

    private fun failure(
        sources: Map<String, String>,
        entryModelId: String,
        limits: ModelPreparationLimits = ModelPreparationLimits.defaults(),
    ): ModelPreparationDiagnostic =
        assertFailsWith<ModelPreparationError> {
            WorkspaceTypeDefinitionAssembler.assemble(admit(sources), entryModelId, limits)
        }.diagnostics.single()

    private fun assertResource(
        diagnostic: ModelPreparationDiagnostic,
        resource: ModelPreparationResource,
        maximum: Int,
        actual: Int,
    ) {
        assertEquals(ModelPreparationDiagnosticCode.RESOURCE_LIMIT_EXCEEDED, diagnostic.code)
        assertEquals(resource, diagnostic.resource)
        assertEquals(maximum.toLong(), diagnostic.maximum)
        assertEquals(actual.toLong(), diagnostic.actual)
    }

    /**
     * Produce one invalid-input sibling from the typed-authored fixture by changing only the reference under
     * test. This is not an alternate model author; every other byte-level model decision stays owned by modelkit.
     */
    private fun replaceTypeReference(source: String, from: String, to: String): String =
        Json.parseToJsonElement(source).rewrite { objectNode ->
            if (objectNode.stringOrNull("type") != "TypeDefType") {
                objectNode
            } else {
                objectNode.replacing(
                    "TypeDefType",
                    objectNode.obj("TypeDefType").replacing(
                        "typeDefinitionId",
                        JsonPrimitive(
                            objectNode.obj("TypeDefType").string("typeDefinitionId").replace(from, to),
                        ),
                    ),
                )
            }
        }.toString()

    /** Change only the typed fixture's concrete definition target to the impossible reference under test. */
    private fun replaceDefinitionReference(
        source: String,
        definitionId: String,
        reference: String,
    ): String =
        replaceDefinitionFieldType(
            source,
            definitionId,
            JsonObject(
                mapOf(
                    "type" to JsonPrimitive("TypeDefType"),
                    "TypeDefType" to JsonObject(
                        mapOf("typeDefinitionId" to JsonPrimitive(reference)),
                    ),
                ),
            ),
        )

    private fun replaceDefinitionFieldType(
        source: String,
        definitionId: String,
        fieldType: JsonObject,
    ): String {
        val root = Json.parseToJsonElement(source).obj()
        val content = root.obj("content")
        val definitions = content.array("typeDefinitions").map { definition ->
            val objectNode = definition.obj()
            if (objectNode.string("id") != definitionId) {
                objectNode
            } else {
                objectNode.replacing("fieldType", fieldType)
            }
        }
        return root.replacing(
            "content",
            content.replacing("typeDefinitions", JsonArray(definitions)),
        ).toString()
    }

    private fun removeDefinitionMember(
        source: String,
        definitionId: String,
        member: String,
    ): String {
        val root = Json.parseToJsonElement(source).obj()
        val content = root.obj("content")
        val definitions = content.array("typeDefinitions").map { definition ->
            val objectNode = definition.obj()
            if (objectNode.string("id") == definitionId) {
                JsonObject(objectNode.filterKeys { it != member })
            } else {
                objectNode
            }
        }
        return root.replacing(
            "content",
            content.replacing("typeDefinitions", JsonArray(definitions)),
        ).toString()
    }

    private fun makeDefinitionSelfReferential(source: String, definitionId: String): String =
        replaceDefinitionReference(source, definitionId, definitionId)

    /** Add only the invalid import edge under test to an otherwise typed-authored source. */
    private fun addTypeDefinitionImport(source: String, alias: String, reference: String): String {
        val root = Json.parseToJsonElement(source).obj()
        val header = root.obj("header")
        val references = header.array("modelReferences") + JsonObject(
            mapOf(
                "alias" to JsonPrimitive(alias),
                "modelType" to JsonPrimitive("document"),
                "purpose" to JsonPrimitive("typeDefinitions"),
                "reference" to JsonPrimitive(reference),
            ),
        )
        return root.replacing(
            "header",
            header.replacing("modelReferences", JsonArray(references)),
        ).toString()
    }

    private fun removeTypeDefinitions(source: String): String {
        val root = Json.parseToJsonElement(source).obj()
        return root.replacing(
            "content",
            JsonObject(root.obj("content").filterKeys { it != "typeDefinitions" }),
        ).toString()
    }

    private fun withoutModelRoot(source: String): String {
        val root = Json.parseToJsonElement(source).obj()
        return root.replacing(
            "content",
            JsonObject(root.obj("content").filterKeys { it != "modelRoot" }),
        ).toString()
    }

    private fun renameModel(source: String, modelId: String): String {
        val root = Json.parseToJsonElement(source).obj()
        val header = root.obj("header").replacing("id", JsonPrimitive(modelId))
        val content = root.obj("content")
        val modelInfo = content.obj("modelInfo").replacing("name", JsonPrimitive(modelId))
        return root.replacing(
            "header",
            header,
        ).replacing(
            "content",
            content.replacing("modelInfo", modelInfo),
        ).toString()
    }

    private fun duplicateFirstTypeDefinition(source: String): String {
        val root = Json.parseToJsonElement(source).obj()
        val content = root.obj("content")
        val definitions = content.array("typeDefinitions")
        return root.replacing(
            "content",
            content.replacing(
                "typeDefinitions",
                JsonArray(definitions + definitions.first()),
            ),
        ).toString()
    }

    private fun appendUnusedSelfReferentialDefinition(source: String, definitionId: String): String {
        val root = Json.parseToJsonElement(source).obj()
        val content = root.obj("content")
        val definitions = content.array("typeDefinitions")
        val cyclic = JsonObject(
            mapOf(
                "id" to JsonPrimitive(definitionId),
                "name" to JsonPrimitive(definitionId),
                "fieldType" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("TypeDefType"),
                        "TypeDefType" to JsonObject(
                            mapOf("typeDefinitionId" to JsonPrimitive(definitionId)),
                        ),
                    ),
                ),
            ),
        )
        return root.replacing(
            "content",
            content.replacing(
                "typeDefinitions",
                JsonArray(definitions + cyclic),
            ),
        ).toString()
    }

    private fun withTypeDefinitionChain(source: String, length: Int): String {
        val root = Json.parseToJsonElement(source).obj()
        val content = root.obj("content")
        val concreteType = content.array("typeDefinitions").first().obj().obj("fieldType")
        val definitions = (0 until length).map { index ->
            val definitionId = "Chain$index"
            val fieldType = if (index == length - 1) {
                concreteType
            } else {
                JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("TypeDefType"),
                        "TypeDefType" to JsonObject(
                            mapOf("typeDefinitionId" to JsonPrimitive("Chain${index + 1}")),
                        ),
                    ),
                )
            }
            JsonObject(
                mapOf(
                    "id" to JsonPrimitive(definitionId),
                    "name" to JsonPrimitive(definitionId),
                    "fieldType" to fieldType,
                ),
            )
        }
        return root.replacing(
            "content",
            content.replacing("typeDefinitions", JsonArray(definitions)),
        ).toString()
    }

    private fun withNestedGroups(source: String, depth: Int): String {
        val root = Json.parseToJsonElement(source).obj()
        val content = root.obj("content")
        val modelRoot = content.obj("modelRoot")
        var nested = modelRoot.array("rootGroups").single()
        repeat(depth) { index ->
            val name = "Layer$index"
            nested = JsonObject(
                mapOf(
                    "type" to JsonPrimitive("Group"),
                    "id" to JsonPrimitive("/$name"),
                    "name" to JsonPrimitive(name),
                    "Group" to JsonObject(
                        mapOf("elements" to JsonArray(listOf(nested))),
                    ),
                ),
            )
        }
        return root.replacing(
            "content",
            content.replacing(
                "modelRoot",
                modelRoot.replacing("rootGroups", JsonArray(listOf(nested))),
            ),
        ).toString()
    }

    private fun replaceIntPrimitive(source: String, key: String, from: Int, to: Int): String =
        Json.parseToJsonElement(source).rewrite { objectNode ->
            val primitive = objectNode[key] as? JsonPrimitive
            if (primitive?.content == from.toString()) objectNode.replacing(key, JsonPrimitive(to)) else objectNode
        }.toString()

    private fun JsonElement.rewrite(transform: (JsonObject) -> JsonObject): JsonElement = when (this) {
        is JsonObject -> transform(JsonObject(mapValues { (_, child) -> child.rewrite(transform) }))
        is JsonArray -> JsonArray(map { it.rewrite(transform) })
        else -> this
    }

    private fun JsonObject.fieldTypeAt(path: String): JsonObject {
        fun search(node: JsonElement): JsonObject? = when (node) {
            is JsonObject -> {
                if (node.stringOrNull("id") == path && node.stringOrNull("type") == "Field") {
                    node.obj("Field").obj("fieldType")
                } else {
                    node.values.firstNotNullOfOrNull(::search)
                }
            }
            is JsonArray -> node.firstNotNullOfOrNull(::search)
            else -> null
        }
        return requireNotNull(search(this)) { "no field at $path" }
    }

    /**
     * The Java reader's Jackson tree canonicalizes number-token scale, while common assembly deliberately retains
     * the source literal. Normalize only numeric bounds for the representation-neutral Kernel/reader comparison;
     * the local-typedef test independently locks raw-literal fidelity.
     */
    private fun JsonObject.normalizedObservedType(): JsonObject {
        if (stringOrNull("type") != "NumberType") {
            return this
        }
        val number = obj("NumberType")
        val normalized = JsonObject(
            number.mapValues { (key, value) ->
                if (key == "minValue" || key == "maxValue") {
                    JsonPrimitive(dec((value as JsonPrimitive).content).display())
                } else {
                    value
                }
            },
        )
        return replacing("NumberType", normalized)
    }

    private fun JsonObject.replacing(key: String, value: JsonElement): JsonObject =
        JsonObject(mapValues { (candidate, original) -> if (candidate == key) value else original })

    private fun JsonElement.obj(): JsonObject = this as JsonObject
    private fun JsonObject.obj(key: String): JsonObject = getValue(key).obj()
    private fun JsonObject.array(key: String): JsonArray = getValue(key) as JsonArray
    private fun JsonObject.primitive(key: String): JsonPrimitive = getValue(key) as JsonPrimitive
    private fun JsonObject.string(key: String): String = primitive(key).content
    private fun JsonObject.stringOrNull(key: String): String? = (get(key) as? JsonPrimitive)?.content
}
