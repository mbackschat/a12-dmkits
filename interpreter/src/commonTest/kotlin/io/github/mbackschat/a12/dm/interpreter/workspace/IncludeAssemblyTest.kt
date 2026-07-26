package io.github.mbackschat.a12.dm.interpreter.workspace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Common JVM/Node lock for structural include mounting. Element path/type observations come from the typed fixture
 * matrix whose Java-reader expansion is differentially checked against the real Kernel.
 */
class IncludeAssemblyTest {

    @Test
    fun mountedElementMatrixMatchesTheKernelReaderObservation() {
        for (caseId in WorkspacePreparationFixtureJson.caseIds()) {
            val result = assemble(caseId)
            assertEquals(
                expectedStructure(caseId),
                result.root.preparedStructure(),
                caseId,
            )
            for (directive in listOf("modelAlias", "includeLevel", "excludeRules", "excludeComputations")) {
                assertFalse(result.json.contains("\"$directive\""), "$caseId retains $directive")
            }
            assertNull(result.root.obj("header")["modelReferences"], caseId)
        }
    }

    @Test
    fun structuralComparatorDetectsUsageTypeLossThatPathAndTypeCannotSee() {
        val caseId = "single-attachment-rules-survive-outer-exclusion"
        val expected = expectedStructure(caseId)
        val actual = assemble(caseId).root.preparedStructure().toMutableMap()
        val mountPath = "/Permit/Documents"
        val mount = actual.getValue(mountPath)
        val facts = mount.obj("facts")
        actual[mountPath] = mount.replacing(
            "facts",
            facts.replacing("usageType", JsonNull),
        )

        assertEquals(
            expected.mapValues { (_, structure) -> structure.string("elementType") },
            actual.mapValues { (_, structure) -> structure.string("elementType") },
        )
        assertNotEquals(expected, actual)
    }

    @Test
    fun singleAndModelRootMountAtTheirObservedNestingLevels() {
        val single = assemble("imported-typedef-nested-single").root.preparedElementTypes()
        val modelRoot = assemble("imported-typedef-nested-model-root").root.preparedElementTypes()

        assertEquals("Group", single["/Permit/Review/Inspection"])
        assertFalse("/Permit/Review/Section" in single)
        assertEquals("Group", modelRoot["/Permit/Review/Section"])
        assertEquals("Group", modelRoot["/Permit/Review/Section/Inspection"])

        val multipleRoots = assemble("model-root-multiple-groups").root.preparedElementTypes()
        assertEquals("Group", multipleRoots["/Permit/Sections/Applicant"])
        assertEquals("Group", multipleRoots["/Permit/Sections/Decision"])
    }

    @Test
    fun expandsAnIncludeMountedAtTheDiscardedProviderRoot() {
        val result = assemble("single-provider-root-include")
        val elements = result.root.preparedElementTypes()

        assertEquals("Field", elements["/Permit/Review/Score"])
        assertEquals("Rule", elements["/Permit/Review/NegativeScore"])
        assertEquals("Computation", elements["/Permit/Review/CopyScore"])
    }

    @Test
    fun exclusionFlagsAreIndependentAndKeepMountedFields() {
        val bothExcluded = assemble("alias-with-exclusions").root.preparedElementTypes()
        val rulesExcluded = assemble("rules-excluded-computation-retained").root.preparedElementTypes()
        val computationsExcluded =
            assemble("computations-excluded-rule-retained").root.preparedElementTypes()

        assertEquals("Field", bothExcluded["/Permit/Inspection/Score"])
        assertFalse(bothExcluded.values.any { it == "Rule" || it == "Computation" })
        assertFalse(rulesExcluded.values.any { it == "Rule" })
        assertEquals("Computation", rulesExcluded["/Permit/Inspection/CopyScore"])
        assertFalse(computationsExcluded.values.any { it == "Computation" })
        assertEquals("Rule", computationsExcluded["/Permit/Inspection/NegativeScore"])
    }

    @Test
    fun outerExclusionsReachNestedIncludesButNotEntryElements() {
        val elements = assemble("nested-rules-excluded").root.preparedElementTypes()

        assertEquals("Rule", elements["/Permit/MountedInspectionFlag"])
        assertFalse("/Permit/Review/Inspection/NegativeScore" in elements)
        assertEquals("Computation", elements["/Permit/Review/Inspection/CopyScore"])
    }

    @Test
    fun attachmentTemplateRulesSurviveAnOuterExclusion() {
        val modelRootElements =
            assemble("attachment-rules-survive-outer-exclusion").root.preparedElementTypes()
        val single = assemble("single-attachment-rules-survive-outer-exclusion").root
        val singleElements = single.preparedElementTypes()

        assertEquals("Group", modelRootElements["/Permit/Documents/Evidence"])
        assertEquals("Rule", modelRootElements["/Permit/Documents/Evidence/AttachmentExactlyOneSource"])
        assertEquals("Rule", modelRootElements["/Permit/Documents/Evidence/AttachmentMimeTypeRequired"])
        assertEquals("Rule", singleElements["/Permit/Documents/AttachmentExactlyOneSource"])
        assertEquals("Rule", singleElements["/Permit/Documents/AttachmentMimeTypeRequired"])

        val singleMountBody = single
            .obj("content")
            .obj("modelRoot")
            .array("rootGroups")
            .single()
            .obj()
            .obj("Group")
            .array("elements")
            .single()
            .obj()
            .obj("Group")
        assertEquals("attachment", singleMountBody.string("usageType"))
    }

    @Test
    fun outerExclusionUsesTheFinalUsageTypeOfAnOrdinaryNestedMount() {
        val result = assembleWithFinalUsageExclusion(
            caseId = "imported-typedef-nested-single",
            entryModelId = "permit-single",
            providerModelId = "permit-section",
            transientMountName = "Inspection",
        )
        val structure = result.root.preparedStructure()

        assertFalse("/Permit/Review/Inspection/NegativeScore" in structure)
        assertEquals(
            "complexType",
            structure.getValue("/Permit/Review/Inspection").obj("facts").string("usageType"),
        )
    }

    @Test
    fun outerExclusionUsesTheFinalUsageTypeOfADiscardedProviderRootMount() {
        val result = assembleWithFinalUsageExclusion(
            caseId = "single-provider-root-include",
            entryModelId = "permit-root-mount-host",
            providerModelId = "inspection-root-mount",
            transientMountName = "InspectionProxy",
        )
        val structure = result.root.preparedStructure()

        assertFalse("/Permit/Review/NegativeScore" in structure)
        assertEquals(
            "complexType",
            structure.getValue("/Permit/Review").obj("facts").string("usageType"),
        )
    }

    @Test
    fun providerAbsenceReplacesAnOrdinaryNestedMountsRawUsageTypeBeforeOuterExclusion() {
        val result = assembleWithFinalUsageExclusion(
            caseId = "imported-typedef-nested-single",
            entryModelId = "permit-single",
            providerModelId = "permit-section",
            transientMountName = "Inspection",
            providerRootUsageType = null,
        )
        val structure = result.root.preparedStructure()

        assertFalse("/Permit/Review/Inspection/NegativeScore" in structure)
        assertNull(structure.getValue("/Permit/Review/Inspection").obj("facts").stringOrNull("usageType"))
    }

    @Test
    fun providerAbsenceReplacesADiscardedProviderRootMountsRawUsageTypeBeforeOuterExclusion() {
        val result = assembleWithFinalUsageExclusion(
            caseId = "single-provider-root-include",
            entryModelId = "permit-root-mount-host",
            providerModelId = "inspection-root-mount",
            transientMountName = "InspectionProxy",
            providerRootUsageType = null,
        )
        val structure = result.root.preparedStructure()

        assertFalse("/Permit/Review/NegativeScore" in structure)
        assertNull(structure.getValue("/Permit/Review").obj("facts").stringOrNull("usageType"))
    }

    @Test
    fun ordinaryNestedMountsOwnExclusionUsesTheFinalProviderAbsence() {
        val result = assembleWithFinalUsageExclusion(
            caseId = "imported-typedef-nested-single",
            entryModelId = "permit-single",
            providerModelId = "permit-section",
            transientMountName = "Inspection",
            providerRootUsageType = null,
            outerExcludeRules = false,
            mountExcludeRules = true,
        )
        val structure = result.root.preparedStructure()

        assertFalse("/Permit/Review/Inspection/NegativeScore" in structure)
        assertNull(structure.getValue("/Permit/Review/Inspection").obj("facts").stringOrNull("usageType"))
    }

    @Test
    fun discardedProviderRootMountsOwnExclusionUsesTheFinalProviderAbsence() {
        val result = assembleWithFinalUsageExclusion(
            caseId = "single-provider-root-include",
            entryModelId = "permit-root-mount-host",
            providerModelId = "inspection-root-mount",
            transientMountName = "InspectionProxy",
            providerRootUsageType = null,
            outerExcludeRules = false,
            mountExcludeRules = true,
        )
        val structure = result.root.preparedStructure()

        assertFalse("/Permit/Review/NegativeScore" in structure)
        assertNull(structure.getValue("/Permit/Review").obj("facts").stringOrNull("usageType"))
    }

    @Test
    fun repeatedMountsProduceAndChargeIndependentOutputOccurrences() {
        val elements = assemble("same-alias-mounted-twice").root.preparedElementTypes()

        assertEquals("Field", elements["/Permit/Primary/Score"])
        assertEquals("Field", elements["/Permit/Secondary/Score"])

        val error = assertFailsWith<ModelPreparationError> {
            assemble(
                "same-alias-mounted-twice",
                ModelPreparationLimits.builder().maxPreparedElements(9).build(),
            )
        }
        val diagnostic = error.diagnostics.single()
        assertEquals(ModelPreparationDiagnosticCode.RESOURCE_LIMIT_EXCEEDED, diagnostic.code)
        assertEquals(ModelPreparationResource.PREPARED_ELEMENTS, diagnostic.resource)
        assertEquals(9, diagnostic.maximum)
        assertEquals(10, diagnostic.actual)
    }

    @Test
    fun exclusionsCannotHideExpansionWorkFromThePreparedElementLimit() {
        val caseId = "rules-excluded-computation-retained"
        val retainedElements = assemble(caseId).root.preparedStructure().size
        val maximum = retainedElements + 1 // the provider's one registered type definition

        val error = assertFailsWith<ModelPreparationError> {
            assemble(
                caseId,
                ModelPreparationLimits.builder().maxPreparedElements(maximum).build(),
            )
        }
        val diagnostic = error.diagnostics.single()

        assertEquals(ModelPreparationDiagnosticCode.RESOURCE_LIMIT_EXCEEDED, diagnostic.code)
        assertEquals(ModelPreparationResource.PREPARED_ELEMENTS, diagnostic.resource)
        assertEquals(maximum.toLong(), diagnostic.maximum)
        assertEquals((maximum + 1).toLong(), diagnostic.actual)
    }

    @Test
    fun executableOriginsRetainNestedAndRepeatedMountCoordinates() {
        val outerProvider = assemble("imported-typedef-nested-single")
            .mountedExecutableOrigins
            .single { it.outputPath == "/Permit/Review/NestedInspectionFlag" }
        assertEquals("permit-section", outerProvider.sourceModelId)
        assertEquals(
            "/Section/Inspection/Score",
            outerProvider.namespace.sourcePathFor("/Permit/Review/Inspection/Score"),
            "the outer provider namespace includes fields contributed by its nested include",
        )
        assertEquals(
            "/Permit/Review/Inspection/Score",
            outerProvider.namespace.outputPathFor("/Section/Inspection/Score"),
        )
        assertEquals(1, outerProvider.namespace.referenceChain.size)

        val nested = assemble("nested-rules-excluded")
        val nestedOrigin = nested.mountedExecutableOrigins.single {
            it.outputPath == "/Permit/Review/Inspection/CopyScore"
        }

        assertEquals("inspection-checks", nestedOrigin.sourceModelId)
        assertEquals("/Inspection", nestedOrigin.sourceParentPath)
        assertEquals(
            listOf(MountedPrefix("/Inspection", "/Permit/Review/Inspection")),
            nestedOrigin.prefixes,
        )
        assertEquals(
            "/Permit/Review/Inspection/Score",
            nestedOrigin.namespace.outputPathFor("/Inspection/Score"),
        )
        assertEquals(
            "/Inspection/Score",
            nestedOrigin.namespace.sourcePathFor("/Permit/Review/Inspection/Score"),
        )
        assertNull(
            nestedOrigin.namespace.sourcePathFor("/Permit/Review/Inspectional/Score"),
            "prefix translation must stop on a segment boundary",
        )
        assertEquals(2, nestedOrigin.namespace.referenceChain.size)
        assertEquals("fixture:inspection-checks", nestedOrigin.namespace.diagnosticLabel)
        assertTrue(nestedOrigin.namespace.fieldRefByShortNameAllowed)
        assertEquals("en_US", nestedOrigin.namespace.conditionLanguageCode)
        assertFalse(
            nested.mountedExecutableOrigins.any {
                it.outputPath == "/Permit/MountedInspectionFlag"
            },
        )

        val repeated = assemble("same-alias-mounted-twice")
            .mountedExecutableOrigins
            .filter { it.outputPath.endsWith("/CopyScore") }
            .sortedBy(MountedExecutableOrigin::outputPath)
        assertEquals(
            listOf(
                "/Permit/Primary/CopyScore",
                "/Permit/Secondary/CopyScore",
            ),
            repeated.map(MountedExecutableOrigin::outputPath),
        )
        assertEquals(
            listOf(
                listOf(MountedPrefix("/Inspection", "/Permit/Primary")),
                listOf(MountedPrefix("/Inspection", "/Permit/Secondary")),
            ),
            repeated.map(MountedExecutableOrigin::prefixes),
        )
        assertTrue(repeated.map(MountedExecutableOrigin::outputOrdinal).zipWithNext().all { (left, right) -> left < right })
    }

    @Test
    fun wideNamespacesFreezePrefixProvenanceOnceForEveryExecutable() {
        val prefixes = (1..2_048).map {
            MountedPrefix(sourcePrefix = "/Source$it", outputPrefix = "/Output$it")
        }
        val namespace = MountedNamespace(
            sourceModelId = "wide-provider",
            diagnosticLabel = "fixture:wide-provider",
            referenceChain = emptyList(),
            fieldRefByShortNameAllowed = true,
            conditionLanguageCode = "en_US",
            prefixes = prefixes,
        )
        val origins = (1..2_048).map {
            MountedExecutableOrigin(
                outputPath = "/Output$it/Rule",
                outputOrdinal = it,
                sourceParentPath = "/Source$it",
                namespace = namespace,
            )
        }

        origins.forEach { origin ->
            assertSame(namespace, origin.namespace)
            assertSame(namespace.prefixes, origin.prefixes)
        }
    }

    @Test
    fun preparedOutputBytesAreBoundedBeforeJsonMaterialization() {
        val result = assemble("same-alias-mounted-twice")
        val preparedBytes = result.root.toString().encodeToByteArray().size
        val maximum = preparedBytes - 100

        val error = assertFailsWith<ModelPreparationError> {
            assemble(
                "same-alias-mounted-twice",
                ModelPreparationLimits.builder()
                    .maxPreparedBytes(maximum)
                    .build(),
            )
        }
        val diagnostic = error.diagnostics.single()

        assertEquals(ModelPreparationDiagnosticCode.RESOURCE_LIMIT_EXCEEDED, diagnostic.code)
        assertEquals(ModelPreparationResource.PREPARED_BYTES, diagnostic.resource)
        assertEquals(maximum.toLong(), diagnostic.maximum)
        assertEquals(maximum.toLong() + 1, diagnostic.actual)
        assertTrue(diagnostic.message.contains("at least"))
    }

    @Test
    fun includedFieldsReceiveTheirEffectiveTypeAfterMounting() {
        val result = assemble("imported-typedef-nested-single")
        val fieldType = result.root.fieldTypeAt("/Permit/Review/Inspection/Disposition")

        assertEquals("StringType", fieldType.string("type"))
        assertEquals(6, fieldType.obj("StringType").primitive("maxLength").content.toInt())
        assertFalse(result.json.contains("\"TypeDefType\""))
    }

    @Test
    fun combinedMountedDepthIsBoundedBeforeMoreOutputIsBuilt() {
        val error = assertFailsWith<ModelPreparationError> {
            assemble(
                "imported-typedef-nested-single",
                ModelPreparationLimits.builder().maxStructureDepth(2).build(),
            )
        }
        val diagnostic = error.diagnostics.single()

        assertEquals(ModelPreparationDiagnosticCode.RESOURCE_LIMIT_EXCEEDED, diagnostic.code)
        assertEquals(ModelPreparationResource.STRUCTURE_DEPTH, diagnostic.resource)
        assertEquals(2, diagnostic.maximum)
        assertEquals(3, diagnostic.actual)
    }

    @Test
    fun includeCyclesFailWithTheFullStructuralReferenceChain() {
        val caseId = "include-contributed-typedef"
        val entryModelId = WorkspacePreparationFixtureJson.entryModelId(caseId)
        val sources = fixtureSources(caseId).toMutableMap()
        sources[entryModelId] = replaceIncludeReference(
            sources.getValue(entryModelId),
            entryModelId,
        )
        val error = assertFailsWith<ModelPreparationError> {
            WorkspaceIncludeAssembler.assemble(admit(sources), entryModelId)
        }
        val diagnostic = error.diagnostics.single()

        assertEquals(ModelPreparationDiagnosticCode.MODEL_REFERENCE_CYCLE, diagnostic.code)
        assertEquals(entryModelId, diagnostic.reference)
        assertEquals(1, diagnostic.referenceChain.size)
    }

    @Test
    fun rejectsKernelInvalidIncludeShapesWithStructuredDiagnostics() {
        val invalidCases = listOf(
            invalidUnknownIncludeLevel(),
            invalidNonStringIncludeLevel(),
            invalidNullIncludeLevel(),
            invalidSingleMountWithNoRoots(),
            invalidSingleMountWithMultipleRoots(),
        )

        for ((entryModelId, sources) in invalidCases) {
            val diagnostic = assertFailsWith<ModelPreparationError>(
                "$entryModelId must reject malformed model elements",
            ) {
                WorkspaceIncludeAssembler.assemble(admit(sources), entryModelId)
            }.diagnostics.single()

            assertEquals(ModelPreparationDiagnosticCode.INVALID_INCLUDE_MOUNT, diagnostic.code)
            assertEquals(entryModelId, diagnostic.modelId)
            assertTrue(diagnostic.path?.startsWith("/") == true)
            assertTrue(diagnostic.message.contains("include mount"))
        }
    }

    @Test
    fun rejectsEveryPresentSingleRootRepeatabilityOtherThanIntegerOne() {
        listOf(
            JsonNull,
            JsonPrimitive(false),
            JsonPrimitive(true),
            JsonObject(emptyMap()),
            JsonArray(emptyList()),
            JsonPrimitive("1"),
            JsonPrimitive("one"),
            JsonPrimitive(1.5),
            JsonPrimitive(0),
            JsonPrimitive(-1),
            JsonPrimitive(2),
        ).forEach { value ->
            assertInvalidIncludeMount(invalidSingleMountWithRepeatability(value))
        }
    }

    @Test
    fun rejectsMalformedElementMembersWithoutDroppingOrUnderchargingThem() {
        val invalidCases = listOf(
            invalidNonObjectIncludedElement(),
            invalidUnknownIncludedElementType(),
            invalidUnknownIncludedRootType(),
            invalidKnownNonGroupModelRoot(),
            invalidMissingModelRootGroupBody(),
            invalidScalarSingleRoot(),
            invalidMissingSingleRootGroupBody(),
            invalidMalformedSingleRootElements(),
        )

        for ((entryModelId, sources) in invalidCases) {
            val diagnostic = assertFailsWith<ModelPreparationError>(
                "$entryModelId must reject malformed model elements",
            ) {
                WorkspaceIncludeAssembler.assemble(admit(sources), entryModelId)
            }.diagnostics.single()

            assertEquals(ModelPreparationDiagnosticCode.INVALID_MODEL_SOURCE, diagnostic.code)
            assertTrue(diagnostic.modelId in sources.keys)
            assertTrue(diagnostic.path?.startsWith("/") == true)
        }
    }

    @Test
    fun rejectsMalformedExclusionsExplicitly() {
        listOf(
            invalidNonBooleanExclusion("excludeRules"),
            invalidNonBooleanExclusion("excludeComputations"),
        ).forEach(::assertInvalidIncludeMount)
    }

    @Test
    fun rejectsMalformedOrMisplacedIncludeDirectivesExplicitly() {
        listOf(
            invalidNumericAlias(),
            invalidBlankAlias(),
            invalidNullAlias(),
            invalidScalarElements(),
            invalidNullElements(),
            invalidDirectiveOnOrdinaryGroup("includeLevel", JsonPrimitive("MODEL_ROOT")),
            invalidDirectiveOnOrdinaryGroup("excludeRules", JsonPrimitive(true)),
            invalidDirectiveOnOrdinaryGroup("excludeComputations", JsonPrimitive(true)),
        ).forEach(::assertInvalidIncludeMount)
    }

    @Test
    fun rejectsKernelAcceptedShadowMountsExplicitly() {
        assertInvalidIncludeMount(invalidShadowMount())
    }

    @Test
    fun defaultsAnAbsentSingleRootRepeatabilityLikeTheKernel() {
        val caseId = "include-contributed-typedef"
        val entryModelId = WorkspacePreparationFixtureJson.entryModelId(caseId)
        val sources = fixtureSources(caseId).toMutableMap()
        val providerModelId = "permit-section-types"
        sources[providerModelId] = sources.getValue(providerModelId).rewriteObjects { node ->
            val group = node["Group"] as? JsonObject
            if (node.strictString("name") == "Section" && group != null) {
                node.replacing("Group", JsonObject(group.filterKeys { it != "repeatability" }))
            } else {
                node
            }
        }

        val result = WorkspaceIncludeAssembler.assemble(admit(sources), entryModelId)

        assertEquals("Field", result.root.preparedElementTypes()["/Permit/Details/Description"])
    }

    private fun assertInvalidIncludeMount(invalidCase: Pair<String, Map<String, String>>) {
        val (entryModelId, sources) = invalidCase
        val diagnostic = assertFailsWith<ModelPreparationError>(
            "$entryModelId must reject ambiguous or malformed include directives",
        ) {
            WorkspaceIncludeAssembler.assemble(admit(sources), entryModelId)
        }.diagnostics.single()

        assertEquals(ModelPreparationDiagnosticCode.INVALID_INCLUDE_MOUNT, diagnostic.code)
        assertEquals(entryModelId, diagnostic.modelId)
        assertTrue(diagnostic.path?.startsWith("/") == true)
    }

    private fun assemble(
        caseId: String,
        limits: ModelPreparationLimits = ModelPreparationLimits.defaults(),
    ): IncludeAssembly =
        WorkspaceIncludeAssembler.assemble(
            admit(fixtureSources(caseId)),
            WorkspacePreparationFixtureJson.entryModelId(caseId),
            limits,
        )

    private fun fixtureSources(caseId: String): Map<String, String> =
        WorkspacePreparationFixtureJson.sources(caseId)

    private fun assembleWithFinalUsageExclusion(
        caseId: String,
        entryModelId: String,
        providerModelId: String,
        transientMountName: String,
        providerRootUsageType: String? = "complexType",
        outerExcludeRules: Boolean = true,
        mountExcludeRules: Boolean = false,
    ): IncludeAssembly {
        val sources = fixtureSources(caseId).toMutableMap()
        sources["inspection-checks"] = sources.getValue("inspection-checks").rewriteObjects { node ->
            val group = node["Group"] as? JsonObject
            if (node.strictString("name") == "Inspection" && group != null) {
                if (providerRootUsageType == null) {
                    node.replacing("Group", JsonObject(group.filterKeys { it != "usageType" }))
                } else {
                    node.replacing(
                        "Group",
                        group.replacingOrAdding("usageType", JsonPrimitive(providerRootUsageType)),
                    )
                }
            } else {
                node
            }
        }
        sources[providerModelId] = sources.getValue(providerModelId).rewriteObjects { node ->
            val group = node["Group"] as? JsonObject
            if (
                node.strictString("name") == transientMountName &&
                group?.strictString("modelAlias") != null
            ) {
                val mountBody = group.replacingOrAdding("usageType", JsonPrimitive("attachment"))
                    .let { body ->
                        if (mountExcludeRules) {
                            body.replacingOrAdding("excludeRules", JsonPrimitive(true))
                        } else {
                            body
                        }
                    }
                node.replacing("Group", mountBody)
            } else {
                node
            }
        }
        if (outerExcludeRules) {
            sources[entryModelId] = sources.getValue(entryModelId).rewriteObjects { node ->
                val group = node["Group"] as? JsonObject
                if (group?.strictString("modelAlias") != null) {
                    node.replacing("Group", group.replacingOrAdding("excludeRules", JsonPrimitive(true)))
                } else {
                    node
                }
            }
        }
        return WorkspaceIncludeAssembler.assemble(admit(sources), entryModelId)
    }

    private fun admit(sources: Map<String, String>): AdmittedWorkspace =
        WorkspaceSourceAdmission.admit(
            sources.map { (modelId, json) ->
                WorkspaceModelSource.builder()
                    .json(json)
                    .diagnosticLabel("fixture:$modelId")
                    .build()
            },
        )

    private fun expectedElementTypes(caseId: String): Map<String, String> =
        expectedStructure(caseId).mapValues { (_, value) -> value.string("elementType") }

    private fun expectedStructure(caseId: String): Map<String, JsonObject> =
        Json.parseToJsonElement(WorkspacePreparationFixtureJson.preparedStructure(caseId))
            .obj()
            .mapValues { (_, value) -> value.obj() }

    private fun replaceIncludeReference(source: String, reference: String): String {
        val root = Json.parseToJsonElement(source).obj()
        val header = root.obj("header")
        val references = header.array("modelReferences").map { candidate ->
            val modelReference = candidate.obj()
            if (modelReference.string("purpose") == "include") {
                modelReference.replacing("reference", JsonPrimitive(reference))
            } else {
                modelReference
            }
        }
        return root.replacing(
            "header",
            header.replacing("modelReferences", JsonArray(references)),
        ).toString()
    }

    private fun invalidUnknownIncludeLevel(): Pair<String, Map<String, String>> {
        val caseId = "imported-typedef-nested-model-root"
        val entryModelId = WorkspacePreparationFixtureJson.entryModelId(caseId)
        val sources = fixtureSources(caseId).toMutableMap()
        sources[entryModelId] = sources.getValue(entryModelId).rewriteObjects { node ->
            val group = node["Group"] as? JsonObject
            if (group?.strictString("modelAlias") == null) {
                node
            } else {
                node.replacing(
                    "Group",
                    group.replacing("includeLevel", JsonPrimitive("UNKNOWN")),
                )
            }
        }
        return entryModelId to sources
    }

    private fun invalidNonStringIncludeLevel(): Pair<String, Map<String, String>> =
        invalidEntryMount { group ->
            group.replacingOrAdding("includeLevel", JsonObject(emptyMap()))
        }

    private fun invalidNullIncludeLevel(): Pair<String, Map<String, String>> =
        invalidEntryMount { group ->
            group.replacingOrAdding("includeLevel", JsonNull)
        }

    private fun invalidSingleMountWithMultipleRoots(): Pair<String, Map<String, String>> {
        val caseId = "model-root-multiple-groups"
        val entryModelId = WorkspacePreparationFixtureJson.entryModelId(caseId)
        val sources = fixtureSources(caseId).toMutableMap()
        sources[entryModelId] = sources.getValue(entryModelId).rewriteObjects { node ->
            val group = node["Group"] as? JsonObject
            if (group?.strictString("modelAlias") == null) {
                node
            } else {
                node.replacing(
                    "Group",
                    JsonObject(group.filterKeys { it != "includeLevel" }),
                )
            }
        }
        return entryModelId to sources
    }

    private fun invalidSingleMountWithNoRoots(): Pair<String, Map<String, String>> {
        val caseId = "include-contributed-typedef"
        val entryModelId = WorkspacePreparationFixtureJson.entryModelId(caseId)
        val sources = fixtureSources(caseId).toMutableMap()
        val providerModelId = "permit-section-types"
        val provider = Json.parseToJsonElement(sources.getValue(providerModelId)).obj()
        val content = provider.obj("content")
        val modelRoot = content.obj("modelRoot")
        sources[providerModelId] = provider.replacing(
            "content",
            content.replacing(
                "modelRoot",
                modelRoot.replacing("rootGroups", JsonArray(emptyList())),
            ),
        ).toString()
        return entryModelId to sources
    }

    private fun invalidSingleMountWithRepeatability(
        repeatability: JsonElement,
    ): Pair<String, Map<String, String>> {
        val caseId = "include-contributed-typedef"
        val entryModelId = WorkspacePreparationFixtureJson.entryModelId(caseId)
        val sources = fixtureSources(caseId).toMutableMap()
        val providerModelId = "permit-section-types"
        sources[providerModelId] = sources.getValue(providerModelId).rewriteObjects { node ->
            val group = node["Group"] as? JsonObject
            if (node.strictString("name") != "Section" || group == null) {
                node
            } else {
                node.replacing(
                    "Group",
                    group.replacing("repeatability", repeatability),
                )
            }
        }
        return entryModelId to sources
    }

    private fun invalidNonObjectIncludedElement(): Pair<String, Map<String, String>> =
        invalidInspectionProvider { elements ->
            JsonArray(elements + JsonPrimitive("not-an-element"))
        }

    private fun invalidUnknownIncludedElementType(): Pair<String, Map<String, String>> =
        invalidInspectionProvider { elements ->
            JsonArray(
                elements.map { candidate ->
                    val node = candidate as? JsonObject
                    if (node?.strictString("name") == "Score") {
                        node.replacing("type", JsonPrimitive("UnknownElement"))
                    } else {
                        candidate
                    }
                },
            )
        }

    private fun invalidUnknownIncludedRootType(): Pair<String, Map<String, String>> {
        val caseId = "imported-typedef-nested-single"
        val entryModelId = WorkspacePreparationFixtureJson.entryModelId(caseId)
        val sources = fixtureSources(caseId).toMutableMap()
        sources["inspection-checks"] = sources.getValue("inspection-checks").rewriteObjects { node ->
            if (node.strictString("name") == "Inspection") {
                node.replacing("type", JsonPrimitive("UnknownElement"))
            } else {
                node
            }
        }
        return entryModelId to sources
    }

    private fun invalidKnownNonGroupModelRoot(): Pair<String, Map<String, String>> {
        val (entryModelId, sources) = multiRootSources()
        val providerModelId = "permit-multi-root-sections"
        sources[providerModelId] = sources.getValue(providerModelId).rewriteObjects { node ->
            val group = node["Group"] as? JsonObject
            val elements = group?.arrayMember("elements")
            if (node.strictString("name") != "Applicant" || elements == null) {
                node
            } else {
                val fieldBody = elements.first().obj().obj("Field")
                JsonObject(
                    node.toMutableMap().apply {
                        this["type"] = JsonPrimitive("Field")
                        this["Field"] = fieldBody
                        remove("Group")
                    },
                )
            }
        }
        return entryModelId to sources
    }

    private fun invalidMissingModelRootGroupBody(): Pair<String, Map<String, String>> {
        val (entryModelId, sources) = multiRootSources()
        val providerModelId = "permit-multi-root-sections"
        sources[providerModelId] = sources.getValue(providerModelId).rewriteObjects { node ->
            if (node.strictString("name") == "Applicant") {
                JsonObject(node.filterKeys { it != "Group" })
            } else {
                node
            }
        }
        return entryModelId to sources
    }

    private fun invalidScalarSingleRoot(): Pair<String, Map<String, String>> =
        invalidSingleRootProvider { JsonPrimitive("not-a-root-group") }

    private fun invalidMissingSingleRootGroupBody(): Pair<String, Map<String, String>> =
        invalidSingleRootProvider { root -> JsonObject(root.filterKeys { it != "Group" }) }

    private fun invalidMalformedSingleRootElements(): Pair<String, Map<String, String>> =
        invalidSingleRootProvider { root ->
            root.replacing(
                "Group",
                root.obj("Group").replacing("elements", JsonPrimitive("not-an-array")),
            )
        }

    private fun invalidSingleRootProvider(
        transform: (JsonObject) -> JsonElement,
    ): Pair<String, Map<String, String>> {
        val caseId = "imported-typedef-nested-single"
        val entryModelId = WorkspacePreparationFixtureJson.entryModelId(caseId)
        val sources = fixtureSources(caseId).toMutableMap()
        val providerModelId = "permit-section"
        val provider = Json.parseToJsonElement(sources.getValue(providerModelId)).obj()
        val content = provider.obj("content")
        val modelRoot = content.obj("modelRoot")
        val rootGroups = modelRoot.array("rootGroups")
        sources[providerModelId] = provider.replacing(
            "content",
            content.replacing(
                "modelRoot",
                modelRoot.replacing(
                    "rootGroups",
                    JsonArray(listOf(transform(rootGroups.single().obj()))),
                ),
            ),
        ).toString()
        return entryModelId to sources
    }

    private fun multiRootSources(): Pair<String, MutableMap<String, String>> {
        val caseId = "model-root-multiple-groups"
        return WorkspacePreparationFixtureJson.entryModelId(caseId) to
            fixtureSources(caseId).toMutableMap()
    }

    private fun invalidInspectionProvider(
        transform: (JsonArray) -> JsonArray,
    ): Pair<String, Map<String, String>> {
        val caseId = "imported-typedef-nested-single"
        val entryModelId = WorkspacePreparationFixtureJson.entryModelId(caseId)
        val sources = fixtureSources(caseId).toMutableMap()
        sources["inspection-checks"] = sources.getValue("inspection-checks").rewriteObjects { node ->
            val group = node["Group"] as? JsonObject
            val elements = group?.arrayMember("elements")
            if (node.strictString("name") == "Inspection" && elements != null) {
                node.replacing("Group", group.replacing("elements", transform(elements)))
            } else {
                node
            }
        }
        return entryModelId to sources
    }

    private fun invalidNonBooleanExclusion(key: String): Pair<String, Map<String, String>> {
        val caseId = "rules-excluded-computation-retained"
        val entryModelId = WorkspacePreparationFixtureJson.entryModelId(caseId)
        val sources = fixtureSources(caseId).toMutableMap()
        sources[entryModelId] = sources.getValue(entryModelId).rewriteObjects { node ->
            val group = node["Group"] as? JsonObject
            if (group?.strictString("modelAlias") == null) {
                node
            } else {
                node.replacing(
                    "Group",
                    group.replacingOrAdding(key, JsonObject(emptyMap())),
                )
            }
        }
        return entryModelId to sources
    }

    private fun invalidNumericAlias(): Pair<String, Map<String, String>> =
        invalidEntryMount { group ->
            group.replacing("modelAlias", JsonPrimitive(7))
        }

    private fun invalidBlankAlias(): Pair<String, Map<String, String>> =
        invalidEntryMount { group ->
            group.replacing("modelAlias", JsonPrimitive(""))
        }

    private fun invalidNullAlias(): Pair<String, Map<String, String>> =
        invalidEntryMount { group ->
            group.replacing("modelAlias", JsonNull)
        }

    private fun invalidScalarElements(): Pair<String, Map<String, String>> =
        invalidEntryMount { group ->
            group.replacingOrAdding("elements", JsonPrimitive("not-an-array"))
        }

    private fun invalidNullElements(): Pair<String, Map<String, String>> =
        invalidEntryMount { group ->
            group.replacingOrAdding("elements", JsonNull)
        }

    private fun invalidDirectiveOnOrdinaryGroup(
        key: String,
        value: JsonElement,
    ): Pair<String, Map<String, String>> {
        val caseId = "self-contained"
        val entryModelId = WorkspacePreparationFixtureJson.entryModelId(caseId)
        val sources = fixtureSources(caseId).toMutableMap()
        sources[entryModelId] = sources.getValue(entryModelId).rewriteObjects { node ->
            val group = node["Group"] as? JsonObject
            if (node.strictString("name") == "Permit" && group != null) {
                node.replacing(
                    "Group",
                    group.replacingOrAdding(key, value),
                )
            } else {
                node
            }
        }
        return entryModelId to sources
    }

    private fun invalidEntryMount(
        transform: (JsonObject) -> JsonObject,
    ): Pair<String, Map<String, String>> {
        val caseId = "imported-typedef-nested-single"
        val entryModelId = WorkspacePreparationFixtureJson.entryModelId(caseId)
        val sources = fixtureSources(caseId).toMutableMap()
        sources[entryModelId] = sources.getValue(entryModelId).rewriteObjects { node ->
            val group = node["Group"] as? JsonObject
            if (node.strictString("name") == "Review" && group != null) {
                node.replacing("Group", transform(group))
            } else {
                node
            }
        }
        return entryModelId to sources
    }

    private fun invalidShadowMount(): Pair<String, Map<String, String>> {
        val caseId = "imported-typedef-nested-single"
        val entryModelId = WorkspacePreparationFixtureJson.entryModelId(caseId)
        val sources = fixtureSources(caseId).toMutableMap()
        val localElement = Json.parseToJsonElement(sources.getValue("inspection-checks"))
            .obj()
            .obj("content")
            .obj("modelRoot")
            .array("rootGroups")
            .first()
        val entry = Json.parseToJsonElement(sources.getValue(entryModelId)).obj()
        val content = entry.obj("content")
        val modelRoot = content.obj("modelRoot")
        val rootGroups = modelRoot.array("rootGroups").map { candidate ->
            val rootGroup = candidate.obj()
            val rootBody = rootGroup.obj("Group")
            val elements = rootBody.array("elements").map { element ->
                val node = element.obj()
                val group = node["Group"] as? JsonObject
                if (node.string("name") != "Review" || group == null) {
                    node
                } else {
                    node.replacing(
                        "Group",
                        group.replacingOrAdding("elements", JsonArray(listOf(localElement))),
                    )
                }
            }
            rootGroup.replacing("Group", rootBody.replacing("elements", JsonArray(elements)))
        }
        sources[entryModelId] = entry.replacing(
            "content",
            content.replacing(
                "modelRoot",
                modelRoot.replacing("rootGroups", JsonArray(rootGroups)),
            ),
        ).toString()
        return entryModelId to sources
    }

    private fun String.rewriteObjects(transform: (JsonObject) -> JsonObject): String =
        Json.parseToJsonElement(this).rewriteObjects(transform).toString()

    private fun JsonElement.rewriteObjects(transform: (JsonObject) -> JsonObject): JsonElement = when (this) {
        is JsonObject -> transform(JsonObject(mapValues { (_, child) -> child.rewriteObjects(transform) }))
        is JsonArray -> JsonArray(map { it.rewriteObjects(transform) })
        else -> this
    }

    private fun JsonObject.preparedElementTypes(): Map<String, String> =
        preparedStructure().mapValues { (_, structure) -> structure.string("elementType") }

    private fun JsonObject.preparedStructure(): Map<String, JsonObject> {
        val structures = linkedMapOf<String, JsonObject>()
        fun collect(element: JsonElement, parentPath: String) {
            val node = element as? JsonObject ?: return
            val type = node.strictString("type") ?: return
            val name = node.strictString("name") ?: return
            val path = "$parentPath/$name"
            structures[path] = elementStructure(node, type)
            if (type == "Group") {
                node.obj("Group").arrayOrNull("elements")
                    ?.forEach { collect(it, path) }
            }
        }
        obj("content").obj("modelRoot").array("rootGroups").forEach { collect(it, "") }
        return structures
    }

    private fun elementStructure(node: JsonObject, elementType: String): JsonObject =
        JsonObject(
            buildMap {
                put("elementType", JsonPrimitive(elementType))
                when (elementType) {
                    "Group" -> put("facts", groupFacts(node))
                    "Field" -> put("facts", fieldFacts(node))
                    "Rule", "Computation" -> Unit
                }
            },
        )

    private fun groupFacts(node: JsonObject): JsonObject {
        val body = node.obj("Group")
        return elementFacts(
            group = true,
            repeatability = body.intOrDefault("repeatability", 1),
            usageType = body.stringOrNull("usageType"),
            annotations = node.annotationFacts(),
        )
    }

    private fun fieldFacts(node: JsonObject): JsonObject {
        val fieldType = node.obj("Field").obj("fieldType")
        val type = fieldType.string("type")
        val body = fieldType[type] as? JsonObject
        val enumeration = body.takeIf { type == "EnumerationType" }
        val string = body.takeIf { type == "StringType" }
        val number = body.takeIf { type == "NumberType" }
        return elementFacts(
            group = false,
            kind = valueKind(type),
            scale = number?.intOrDefault("maxFractionalDigits", 0),
            min = number?.primitiveContentOrNull("minValue"),
            max = number?.primitiveContentOrNull("maxValue"),
            minLength = string?.intOrNull("minLength"),
            maxLength = string?.intOrNull("maxLength"),
            pattern = string?.stringOrNull("pattern")?.takeIf(String::isNotEmpty),
            enumValues = enumeration?.arrayOrNull("values")
                ?.map { it.obj().string("value") },
            enumCategories = enumeration?.enumCategoryFacts(),
            annotations = node.annotationFacts(),
        )
    }

    private fun elementFacts(
        group: Boolean,
        kind: String? = null,
        scale: Int? = null,
        min: String? = null,
        max: String? = null,
        minLength: Int? = null,
        maxLength: Int? = null,
        pattern: String? = null,
        enumValues: List<String>? = null,
        enumCategories: Map<String, List<String>>? = null,
        repeatability: Int? = null,
        usageType: String? = null,
        annotations: List<String>,
    ): JsonObject =
        JsonObject(
            linkedMapOf(
                "group" to JsonPrimitive(group),
                "kind" to kind.jsonStringOrNull(),
                "scale" to scale.jsonNumberOrNull(),
                "min" to min.jsonStringOrNull(),
                "max" to max.jsonStringOrNull(),
                "minLength" to minLength.jsonNumberOrNull(),
                "maxLength" to maxLength.jsonNumberOrNull(),
                "pattern" to pattern.jsonStringOrNull(),
                "enumValues" to enumValues?.let { JsonArray(it.map(::JsonPrimitive)) }.orJsonNull(),
                "enumCategories" to enumCategories
                    ?.let { values ->
                        JsonObject(
                            values.mapValues { (_, members) ->
                                JsonArray(members.map(::JsonPrimitive))
                            },
                        )
                    }
                    .orJsonNull(),
                "repeatability" to repeatability.jsonNumberOrNull(),
                "usageType" to usageType.jsonStringOrNull(),
                "annotations" to JsonArray(annotations.map(::JsonPrimitive)),
            ),
        )

    private fun JsonObject.annotationFacts(): List<String> =
        arrayOrNull("annotations")
            ?.map { annotation ->
                val value = annotation.obj()
                "${value.string("name")}=${value.string("value")}"
            }
            .orEmpty()

    private fun JsonObject.enumCategoryFacts(): Map<String, List<String>> =
        arrayOrNull("categories")
            ?.associate { category ->
                val value = category.obj()
                value.string("name") to value.array("values")
                    .map { (it as JsonPrimitive).content }
                    .distinct()
            }
            .orEmpty()

    private fun valueKind(type: String): String = when (type) {
        "NumberType" -> "NUMBER"
        "StringType" -> "STRING"
        "EnumerationType" -> "ENUMERATION"
        "BooleanType" -> "BOOLEAN"
        "ConfirmType" -> "CONFIRM"
        "DateType" -> "DATE"
        "DateTimeType" -> "DATE_TIME"
        "TimeType" -> "TIME"
        "DateFragmentType" -> "DATE_FRAGMENT"
        "DateRangeType" -> "DATE_RANGE"
        "CustomFieldType" -> "CUSTOM"
        else -> "UNKNOWN"
    }

    private fun JsonObject.fieldTypeAt(path: String): JsonObject {
        fun search(element: JsonElement, parentPath: String): JsonObject? {
            val node = element as? JsonObject ?: return null
            val type = node.strictString("type") ?: return null
            val name = node.strictString("name") ?: return null
            val candidatePath = "$parentPath/$name"
            if (type == "Field" && candidatePath == path) {
                return node.obj("Field").obj("fieldType")
            }
            if (type != "Group") {
                return null
            }
            return node.obj("Group").arrayOrNull("elements")
                ?.firstNotNullOfOrNull { search(it, candidatePath) }
        }
        val rootGroups = obj("content").obj("modelRoot").array("rootGroups")
        return requireNotNull(rootGroups.firstNotNullOfOrNull { search(it, "") }) { "no field at $path" }
    }

    private fun JsonObject.replacing(key: String, value: JsonElement): JsonObject =
        JsonObject(mapValues { (candidate, original) -> if (candidate == key) value else original })

    private fun JsonObject.replacingOrAdding(key: String, value: JsonElement): JsonObject =
        JsonObject(toMutableMap().apply { this[key] = value })

    private fun JsonElement.obj(): JsonObject = this as JsonObject
    private fun JsonObject.obj(key: String): JsonObject = getValue(key).obj()
    private fun JsonObject.array(key: String): JsonArray = getValue(key) as JsonArray
    private fun JsonObject.arrayOrNull(key: String): JsonArray? = get(key) as? JsonArray
    private fun JsonObject.primitive(key: String): JsonPrimitive = getValue(key) as JsonPrimitive
    private fun JsonObject.string(key: String): String = primitive(key).content
    private fun JsonObject.stringOrNull(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
    private fun JsonObject.primitiveContentOrNull(key: String): String? =
        (get(key) as? JsonPrimitive)?.content
    private fun JsonObject.intOrNull(key: String): Int? =
        (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()
    private fun JsonObject.intOrDefault(key: String, defaultValue: Int): Int =
        intOrNull(key) ?: defaultValue
    private fun String?.jsonStringOrNull(): JsonElement = this?.let(::JsonPrimitive) ?: JsonNull
    private fun Int?.jsonNumberOrNull(): JsonElement = this?.let(::JsonPrimitive) ?: JsonNull
    private fun JsonElement?.orJsonNull(): JsonElement = this ?: JsonNull
}
