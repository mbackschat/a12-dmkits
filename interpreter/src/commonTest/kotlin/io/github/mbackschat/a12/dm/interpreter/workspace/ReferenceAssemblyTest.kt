package io.github.mbackschat.a12.dm.interpreter.workspace

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.ModelPreparationDiagnostic
import io.github.mbackschat.a12.dm.interpreter.ModelPreparationDiagnosticCode
import io.github.mbackschat.a12.dm.interpreter.ModelPreparationError
import io.github.mbackschat.a12.dm.interpreter.ModelPreparationLimits
import io.github.mbackschat.a12.dm.interpreter.ModelPreparationResource
import io.github.mbackschat.a12.dm.interpreter.WorkspaceModelSource
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReferenceAssemblyTest {

    @Test
    fun fixtureMatrixMatchesTheKernelCheckedJavaReaderReferenceSurface() {
        for (caseId in WorkspacePreparationFixtureJson.caseIds()) {
            val expected = Json.parseToJsonElement(
                WorkspacePreparationFixtureJson.preparedElements(caseId),
            )
            val actual = assemble(caseId).root.preparedElements()

            assertEquals(expected, actual, caseId)
        }
    }

    @Test
    fun fixtureMatrixRefusesEveryKernelRejectedWorkspace() {
        for (caseId in WorkspacePreparationFixtureJson.rejectedCaseIds()) {
            val error = assertFailsWith<ModelPreparationError> {
                assemble(caseId)
            }

            assertEquals(
                WorkspacePreparationFixtureJson.expectedPreparationDiagnostic(caseId),
                error.diagnostics.single().code.name,
                caseId,
            )
        }
    }

    @Test
    fun normalizesEveryElementIdToItsFinalNamePath() {
        val result = assemble("same-model-mounted-twice")
        val elementIds = result.root.elementIdsByPath()

        assertEquals(elementIds.keys, elementIds.values.toSet())
        assertEquals("/Permit/Primary/Score", elementIds.getValue("/Permit/Primary/Score"))
        assertEquals("/Permit/Secondary/CopyScore", elementIds.getValue("/Permit/Secondary/CopyScore"))
    }

    @Test
    fun remapsEveryExecutableSlotInEachPhysicalProviderNamespace() {
        val single = assemble("reference-family-single").root.preparedElements()

        assertReferenceFamily(single, "/Policy/Cover")
        assertReferenceFamily(single, "/Policy/Renewal")

        val modelRoot = assemble("reference-family-model-root").root.preparedElements()
        assertReferenceFamily(modelRoot, "/Policy/Cover/Benefits")
    }

    @Test
    fun preservesGermanSyntaxWhileRemappingItsProviderNamespace() {
        val elements = assemble("reference-family-german-single").root.preparedElements()

        assertReferenceFamily(elements, "/Policy/Cover")
        val indexed = elements.getValue("/Policy/Cover/IndexedAmount") as JsonObject
        val condition = (indexed.getValue("errorCondition") as JsonPrimitive).content
        assertTrue("Fuer Note" in condition)
        assertFalse("/Benefits" in condition)
    }

    @Test
    fun refusesAProviderWhoseLocalizedSyntaxCannotSurviveTheEntryLanguage() {
        val caseId = "reference-family-mixed-language-nested"

        val error = assertFailsWith<ModelPreparationError> {
            assemble(caseId)
        }

        val diagnostic = error.diagnostics.single()
        assertEquals(
            WorkspacePreparationFixtureJson.expectedPreparationDiagnostic(caseId),
            diagnostic.code.name,
        )
        assertEquals("benefits-reference-family-de", diagnostic.modelId)
        assertEquals("fixture:benefits-reference-family-de", diagnostic.diagnosticLabel)
        assertEquals("/Envelope/NestedPolicy/Cover/Level/Deep/DeepNegative", diagnostic.path)
        assertEquals(2, diagnostic.referenceChain.size)
        assertTrue("de_DE" in diagnostic.message)
        assertTrue("en_US" in diagnostic.message)
    }

    @Test
    fun acceptsLanguageNeutralSyntaxInBothProviderEntryDirections() {
        for (
            caseId in listOf(
                "reference-family-mixed-language-neutral",
                "reference-family-mixed-language-neutral-reverse",
            )
        ) {
            val result = assemble(caseId)
            val elements = result.root.preparedElements()
            val rule = elements.getValue("/Policy/Cover/NoteEqualsX") as JsonObject

            assertEquals(
                "[Note] == \"x\"",
                (rule.getValue("errorCondition") as JsonPrimitive).content,
                caseId,
            )
        }
    }

    @Test
    fun assembledReferenceOutputLoadsAndExecutesEveryStoredProgram() {
        val definition = ModelLoader.load(assemble("reference-family-single").json)
        val interpreter = DmInterpreter(definition)

        assertEquals(emptyList(), interpreter.supportAudit())
        assertEquals(emptyList(), interpreter.validate(definition.newDocument()).unsupported)
        assertEquals(
            setOf(
                "/Policy/Cover/Level/Deep/Total",
                "/Policy/Renewal/Level/Deep/Total",
            ),
            interpreter.compute(definition.newDocument()).map { it.path }.toSet(),
        )
    }

    @Test
    fun refusesCompatibilityWhenTheSourceLanguageCannotParseTheFragment() {
        val caseId = "reference-family-mixed-language-neutral"
        val providerModelId = "benefits-reference-neutral-de"
        val sources = WorkspacePreparationFixtureJson.sources(caseId).toMutableMap()
        sources[providerModelId] = withStoredRuleCondition(
            sources.getValue(providerModelId),
            ruleName = "NoteEqualsX",
            condition = "[Note] PatternMatched \"x\"",
        )

        val error = assertFailsWith<ModelPreparationError> {
            WorkspaceReferenceAssembler.assemble(
                admit(sources),
                WorkspacePreparationFixtureJson.entryModelId(caseId),
            )
        }

        assertEquals(
            ModelPreparationDiagnosticCode.INCOMPATIBLE_CONDITION_LANGUAGE,
            error.diagnostics.single().code,
        )
    }

    @Test
    fun refusesEveryForeignKeywordFamilyInBothLanguageDirections() {
        val cases = listOf(
            ForeignKeywordCase(
                caseId = "reference-family-mixed-language-neutral",
                providerModelId = "benefits-reference-neutral-de",
                fragments = listOf(
                    "FieldFilled(Note)",
                    "FieldValueAsString(Note) == \"x\"",
                    "[Note] == \"x\" And [Note] == \"y\"",
                    "Today == Today",
                    "WiederholungNichtEindeutig(/Benefits/Note @From /Benefits)",
                ),
            ),
            ForeignKeywordCase(
                caseId = "reference-family-mixed-language-neutral-reverse",
                providerModelId = "benefits-reference-neutral-en",
                fragments = listOf(
                    "FeldAngegeben(Note)",
                    "FeldWertAlsString(Note) == \"x\"",
                    "[Note] == \"x\" Und [Note] == \"y\"",
                    "Heute == Heute",
                    "RepetitionNotUnique(/Benefits/Note @Von /Benefits)",
                ),
            ),
        )

        for (case in cases) {
            for (fragment in case.fragments) {
                val sources = WorkspacePreparationFixtureJson.sources(case.caseId).toMutableMap()
                sources[case.providerModelId] = withStoredRuleCondition(
                    sources.getValue(case.providerModelId),
                    ruleName = "NoteEqualsX",
                    condition = fragment,
                )

                val error = assertFailsWith<ModelPreparationError>("${case.caseId}: $fragment") {
                    WorkspaceReferenceAssembler.assemble(
                        admit(sources),
                        WorkspacePreparationFixtureJson.entryModelId(case.caseId),
                    )
                }

                assertEquals(
                    ModelPreparationDiagnosticCode.INCOMPATIBLE_CONDITION_LANGUAGE,
                    error.diagnostics.single().code,
                    "${case.caseId}: $fragment",
                )
            }
        }
    }

    @Test
    fun refusesCompatibilityWhenBothLanguagesParseDifferentMeanings() {
        val caseId = "reference-family-mixed-language-neutral"
        val providerModelId = "benefits-reference-neutral-de"
        val sources = WorkspacePreparationFixtureJson.sources(caseId).toMutableMap()
        sources[providerModelId] = withStoredRuleCondition(
            sources.getValue(providerModelId),
            ruleName = "NoteEqualsX",
            condition = "True == True",
        )

        val error = assertFailsWith<ModelPreparationError> {
            WorkspaceReferenceAssembler.assemble(
                admit(sources),
                WorkspacePreparationFixtureJson.entryModelId(caseId),
            )
        }

        assertEquals(
            ModelPreparationDiagnosticCode.INCOMPATIBLE_CONDITION_LANGUAGE,
            error.diagnostics.single().code,
        )
    }

    @Test
    fun boundsTheFinalNormalizedAndRemappedRepresentation() {
        val caseId = "reference-family-single"
        val workspace = admittedWorkspace(caseId)
        val entryModelId = WorkspacePreparationFixtureJson.entryModelId(caseId)
        val mounted = WorkspaceIncludeAssembler.assemble(workspace, entryModelId)
        val mountedBytes = mounted.root.toString().encodeToByteArray().size
        var successfulSourceSymbolIndexStarted = false
        val prepared = WorkspaceReferenceAssembler.assemble(
            workspace,
            entryModelId,
            observer = ReferenceAssemblyObserver {
                successfulSourceSymbolIndexStarted = true
            },
        )
        assertTrue(successfulSourceSymbolIndexStarted)
        val preparedBytes = prepared.root.toString().encodeToByteArray().size
        assertTrue(preparedBytes > mountedBytes)
        var sourceSymbolIndexStarted = false

        val error = assertFailsWith<ModelPreparationError> {
            WorkspaceReferenceAssembler.assemble(
                workspace,
                entryModelId,
                ModelPreparationLimits.builder()
                    .maxPreparedBytes(mountedBytes)
                    .build(),
                ReferenceAssemblyObserver {
                    sourceSymbolIndexStarted = true
                },
            )
        }
        assertFalse(sourceSymbolIndexStarted)
        val diagnostic = error.diagnostics.single()
        assertEquals(ModelPreparationDiagnosticCode.RESOURCE_LIMIT_EXCEEDED, diagnostic.code)
        assertEquals(ModelPreparationResource.PREPARED_BYTES, diagnostic.resource)
        assertEquals(mountedBytes.toLong(), diagnostic.maximum)
        assertEquals(mountedBytes.toLong() + 1, diagnostic.actual)
    }

    @Test
    fun refusesHostileReferenceExpansionAtTheFirstByteBeyondTheLimit() {
        val caseId = "reference-family-single"
        val providerModelId = "benefits-reference-family"
        val sources = WorkspacePreparationFixtureJson.sources(caseId).toMutableMap()
        val expandedCondition = List(50_000) {
            "FieldFilled(/Benefits/Note)"
        }.joinToString(" And ")
        sources[providerModelId] = withStoredRuleCondition(
            sources.getValue(providerModelId),
            ruleName = "DeepNegative",
            condition = expandedCondition,
        )
        val workspace = admit(sources)
        val entryModelId = WorkspacePreparationFixtureJson.entryModelId(caseId)
        val mounted = WorkspaceIncludeAssembler.assemble(workspace, entryModelId)
        val mountedBytes = mounted.root.toString().encodeToByteArray().size
        val maximum = mountedBytes + 32

        val error = assertFailsWith<ModelPreparationError> {
            WorkspaceReferenceAssembler.assemble(
                workspace,
                entryModelId,
                ModelPreparationLimits.builder()
                    .maxPreparedBytes(maximum)
                    .build(),
            )
        }

        val diagnostic = error.diagnostics.single()
        assertEquals(ModelPreparationResource.PREPARED_BYTES, diagnostic.resource)
        assertEquals(maximum.toLong(), diagnostic.maximum)
        assertEquals(maximum.toLong() + 1, diagnostic.actual)
    }

    @Test
    fun reportsEveryRetainedResidualAtItsPhysicalExecutableOccurrence() {
        val caseId = "reference-family-single"
        val providerModelId = "benefits-reference-family"
        val sources = WorkspacePreparationFixtureJson.sources(caseId).toMutableMap()
        sources[providerModelId] = withResidualExecutables(
            sources.getValue(providerModelId),
            ruleName = "DeepNegative",
            computationName = "CopyThreshold",
        )

        val error = assertFailsWith<ModelPreparationError> {
            WorkspaceReferenceAssembler.assemble(
                admit(sources),
                WorkspacePreparationFixtureJson.entryModelId(caseId),
            )
        }
        assertEquals(
            setOf(
                "/Policy/Cover/Level/Deep/DeepNegative",
                "/Policy/Cover/Level/Deep/CopyThreshold",
                "/Policy/Renewal/Level/Deep/DeepNegative",
                "/Policy/Renewal/Level/Deep/CopyThreshold",
            ),
            error.diagnostics.mapNotNull(ModelPreparationDiagnostic::path).toSet(),
        )
        error.diagnostics.forEach { diagnostic ->
            assertEquals(ModelPreparationDiagnosticCode.UNMAPPED_INCLUDED_REFERENCE, diagnostic.code)
            assertEquals(providerModelId, diagnostic.modelId)
            assertEquals("fixture:$providerModelId", diagnostic.diagnosticLabel)
            assertEquals("/Missing/Field", diagnostic.reference)
            assertEquals(1, diagnostic.referenceChain.size)
        }
    }

    @Test
    fun excludedExecutablesLeaveNoReferenceResidue() {
        val caseId = "mount-with-exclusions"
        val providerModelId = "inspection-checks"
        val sources = WorkspacePreparationFixtureJson.sources(caseId).toMutableMap()
        sources[providerModelId] = withResidualExecutables(
            sources.getValue(providerModelId),
            ruleName = "NegativeScore",
            computationName = "CopyScore",
        )

        val result = WorkspaceReferenceAssembler.assemble(
            admit(sources),
            WorkspacePreparationFixtureJson.entryModelId(caseId),
        )

        assertFalse(result.json.contains("/Missing/Field"))
    }

    private fun assertReferenceFamily(elements: JsonObject, mountedRoot: String) {
        val rulePaths = listOf(
            "$mountedRoot/OuterMatch",
            "$mountedRoot/IndexedAmount",
            "$mountedRoot/RootPresent",
            "$mountedRoot/Level/LevelNegative",
            "$mountedRoot/Level/Deep/DeepNegative",
        )
        val ruleFragments = rulePaths.map { path ->
            val facts = elements.getValue(path) as JsonObject
            (facts.getValue("errorCondition") as JsonPrimitive).content
        }
        // Same-group relative references deliberately remain byte-identical. RootPresent carries the
        // move-sensitive absolute provider root and therefore proves rule-fragment projection.
        assertTrue(mountedRoot in ruleFragments[2], mountedRoot)
        assertTrue(ruleFragments.none { "/Benefits" in it.replace(mountedRoot, "") }, mountedRoot)

        val computation = elements.getValue(
            "$mountedRoot/Level/Deep/CopyThreshold",
        ) as JsonObject
        val common = (computation.getValue("commonPrecondition") as JsonPrimitive).content
        val alternatives = computation.getValue("alternatives") as JsonArray
        assertEquals(2, alternatives.size, mountedRoot)
        val fragments = buildList {
            add(common)
            alternatives.forEach { candidate ->
                val alternative = candidate as JsonObject
                add((alternative.getValue("precondition") as JsonPrimitive).content)
                add((alternative.getValue("operation") as JsonPrimitive).content)
            }
        }
        assertTrue(fragments.all { "$mountedRoot/Threshold" in it }, mountedRoot)
        assertTrue(fragments.none { "/Benefits" in it.replace(mountedRoot, "") }, mountedRoot)
        assertFalse(fragments.any { "[Threshold]" in it }, mountedRoot)
    }

    private fun assemble(caseId: String): ReferenceAssembly {
        val workspace = admittedWorkspace(caseId)
        return WorkspaceReferenceAssembler.assemble(
            workspace,
            WorkspacePreparationFixtureJson.entryModelId(caseId),
        )
    }

    private fun admittedWorkspace(caseId: String): AdmittedWorkspace {
        return admit(WorkspacePreparationFixtureJson.sources(caseId))
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

    private fun withResidualExecutables(
        json: String,
        ruleName: String,
        computationName: String,
    ): String {
        fun rewrite(element: JsonElement): JsonElement = when (element) {
            is JsonArray -> JsonArray(element.map(::rewrite))
            is JsonObject -> {
                val rewritten = JsonObject(element.mapValues { (_, value) -> rewrite(value) })
                when {
                    rewritten["type"] == JsonPrimitive("Rule") &&
                        rewritten["name"] == JsonPrimitive(ruleName) -> {
                        val body = rewritten.getValue("Rule") as JsonObject
                        rewritten.replacing(
                            "Rule",
                            body.setting(
                                "errorCondition",
                                JsonPrimitive("FieldFilled(/Missing/Field)"),
                            ),
                        )
                    }
                    rewritten["type"] == JsonPrimitive("Computation") &&
                        rewritten["name"] == JsonPrimitive(computationName) -> {
                        val body = rewritten.getValue("Computation") as JsonObject
                        val alternatives = body.getValue("computationAlternatives") as JsonArray
                        val first = alternatives.first() as JsonObject
                        rewritten.replacing(
                            "Computation",
                            body.replacing(
                                "computationAlternatives",
                                JsonArray(
                                    listOf(
                                        first.setting(
                                            "operation",
                                            JsonPrimitive("[/Missing/Field]"),
                                        ),
                                    ) + alternatives.drop(1),
                                ),
                            ),
                        )
                    }
                    else -> rewritten
                }
            }
            else -> element
        }

        return rewrite(Json.parseToJsonElement(json)).toString()
    }

    /**
     * Foreign workspace input can carry condition text the typed authoring surface deliberately cannot produce.
     * This boundary-test helper changes only that stored slot; normal fixtures remain typed-authored.
     */
    private fun withStoredRuleCondition(
        json: String,
        ruleName: String,
        condition: String,
    ): String {
        fun rewrite(element: JsonElement): JsonElement = when (element) {
            is JsonArray -> JsonArray(element.map(::rewrite))
            is JsonObject -> {
                val rewritten = JsonObject(element.mapValues { (_, value) -> rewrite(value) })
                if (
                    rewritten["type"] == JsonPrimitive("Rule") &&
                    rewritten["name"] == JsonPrimitive(ruleName)
                ) {
                    val body = rewritten.getValue("Rule") as JsonObject
                    rewritten.replacing(
                        "Rule",
                        body.setting("errorCondition", JsonPrimitive(condition)),
                    )
                } else {
                    rewritten
                }
            }
            else -> element
        }

        return rewrite(Json.parseToJsonElement(json)).toString()
    }

    private data class ForeignKeywordCase(
        val caseId: String,
        val providerModelId: String,
        val fragments: List<String>,
    )

    private fun JsonObject.elementIdsByPath(): Map<String, String> {
        val ids = linkedMapOf<String, String>()
        fun collect(element: JsonElement, parentPath: String) {
            val node = element as? JsonObject ?: return
            val name = (node["name"] as? JsonPrimitive)?.content ?: return
            val path = "$parentPath/$name"
            ids[path] = (node["id"] as? JsonPrimitive)?.content.orEmpty()
            if ((node["type"] as? JsonPrimitive)?.content == "Group") {
                val group = node["Group"] as? JsonObject
                val children = group?.get("elements") as? JsonArray
                children?.forEach { collect(it, path) }
            }
        }

        val content = getValue("content") as JsonObject
        val modelRoot = content.getValue("modelRoot") as JsonObject
        val rootGroups = modelRoot.getValue("rootGroups") as JsonArray
        rootGroups.forEach { collect(it, "") }
        return ids
    }

    private fun JsonObject.preparedElements(): JsonObject {
        val elements = linkedMapOf<String, JsonElement>()
        fun collect(element: JsonElement, parentPath: String) {
            val node = element as? JsonObject ?: return
            val name = (node["name"] as? JsonPrimitive)?.content ?: return
            val elementType = (node["type"] as? JsonPrimitive)?.content ?: return
            val path = "$parentPath/$name"
            val facts = linkedMapOf<String, JsonElement>(
                "id" to (node["id"] ?: JsonNull),
                "elementType" to JsonPrimitive(elementType),
            )
            when (elementType) {
                "Rule" -> {
                    val body = node["Rule"] as? JsonObject
                    facts["errorCondition"] = body?.get("errorCondition") ?: JsonNull
                }
                "Computation" -> {
                    val body = node["Computation"] as? JsonObject
                    facts["commonPrecondition"] = body?.get("commonPrecondition") ?: JsonNull
                    val alternatives = body?.get("computationAlternatives") as? JsonArray
                    facts["alternatives"] = JsonArray(
                        alternatives.orEmpty().map { candidate ->
                            val alternative = candidate as? JsonObject
                            JsonObject(
                                linkedMapOf(
                                    "precondition" to
                                        (alternative?.get("precondition") ?: JsonNull),
                                    "operation" to
                                        (alternative?.get("operation") ?: JsonNull),
                                ),
                            )
                        },
                    )
                }
                "Group", "Field" -> Unit
            }
            elements[path] = JsonObject(facts)
            if (elementType == "Group") {
                val group = node["Group"] as? JsonObject
                val children = group?.get("elements") as? JsonArray
                children?.forEach { collect(it, path) }
            }
        }

        val content = getValue("content") as JsonObject
        val modelRoot = content.getValue("modelRoot") as JsonObject
        val rootGroups = modelRoot.getValue("rootGroups") as JsonArray
        rootGroups.forEach { collect(it, "") }
        return JsonObject(elements)
    }
}
