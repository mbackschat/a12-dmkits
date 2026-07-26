package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.workspace.WorkspacePreparationFixtureJson
import io.github.mbackschat.a12.dm.interpreter.workspace.WorkspaceReferenceAssembler
import io.github.mbackschat.a12.dm.interpreter.workspace.WorkspaceSourceAdmission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Public preparation-boundary contract. These tests deliberately compare through the internal definition only as
 * an oracle: consumers receive [PreparedModel], never the mutable loader accumulators or interpreter AST.
 */
class PreparationApiTest {

    @Test
    fun rawWorkspaceAndExpandedFastPathConvergeOnOnePreparedDefinition() {
        val caseId = "reference-family-single"
        val raw = ModelWorkspace.fromSources(sources(caseId))
        val fromWorkspace = raw.prepare(entryModelId(caseId))
        val fromExpanded = PreparedModel.loadExpandedJson(expanded(caseId))

        assertEquals(fromWorkspace.definition(), fromExpanded.definition())
        assertEquals(entryModelId(caseId), fromWorkspace.entryModelId)
        assertEquals(entryModelId(caseId), fromExpanded.entryModelId)
        assertTrue(fromExpanded.dependencies.isEmpty())
    }

    @Test
    fun workspaceSnapshotAndPreparedMetadataAreRuntimeImmutable() {
        val mutableSources = sources("reference-family-single").toMutableList()
        val workspace = ModelWorkspace.fromSources(mutableSources)
        mutableSources.clear()
        val prepared = workspace.prepare(entryModelId("reference-family-single"))

        assertTrue(workspace.modelIds.isNotEmpty())
        assertFails { (workspace.modelIds as MutableList<String>).clear() }
        assertTrue(prepared.dependencies.isNotEmpty())
        assertFails { (prepared.dependencies as MutableList<ModelDependency>).clear() }
    }

    @Test
    fun onePreparedOwnerKeepsStableOpaqueIdentitiesWhileIndependentLoadsStayForeign() {
        val json = preparedModel("identity", "", """{"type":"StringType"}""")
        val first = PreparedModel.loadExpandedJson(json)
        val second = PreparedModel.loadExpandedJson(json)

        assertSame(first.owner, first.owner)
        assertSame(first.modelVersion, first.modelVersion)
        assertNotSame(first.owner, second.owner)
        assertNotSame(first.modelVersion, second.modelVersion)
        assertEquals(first.fingerprint, second.fingerprint)
    }

    @Test
    fun fingerprintUsesOnlyTheResolvedClosureAndIsIndependentOfSourceListOrder() {
        val caseId = "imported-typedef-nested-single"
        val sources = sources(caseId)
        val unusedA = source(unusedModel("unused", "Alpha"), "unused-a")
        val unusedB = source(unusedModel("unused", "Beta"), "unused-b")
        val invalidUnused = source(
            preparedModel("invalid-unused", "", """{"type":"StringType"}""").replace(
                """"Field":{"fieldType":{"type":"StringType"}}""",
                """"Field":null""",
            ),
            "invalid-unused",
        )

        val base = ModelWorkspace.fromSources(sources)
            .prepare(entryModelId(caseId))
        val reordered = ModelWorkspace.fromSources((sources + unusedA).reversed())
            .prepare(entryModelId(caseId))
        val changedUnused = ModelWorkspace.fromSources(sources + unusedB)
            .prepare(entryModelId(caseId))
        val ignoredInvalid = ModelWorkspace.fromSources(sources + invalidUnused)
            .prepare(entryModelId(caseId))

        assertEquals(base.fingerprint, reordered.fingerprint)
        assertEquals(base.fingerprint, changedUnused.fingerprint)
        assertEquals(base.fingerprint, ignoredInvalid.fingerprint)
        assertEquals(base.dependencies, reordered.dependencies)
        assertFalse(base.dependencies.any { it.toModelId == "unused" })
        assertTrue(Regex("[0-9a-f]{64}").matches(base.fingerprint))
        assertIs<ModelPreparationResult.Failure>(
            ModelWorkspace.fromSources(listOf(invalidUnused)).tryPrepare("invalid-unused"),
        )
    }

    @Test
    fun fingerprintExcludesLabelsAndExpectedDigestsEvenForResolvedSources() {
        val caseId = "reference-family-single"
        val original = sources(caseId)
        val relabeled = original.mapIndexed { index, source ->
            WorkspaceModelSource.builder()
                .json(source.json)
                .diagnosticLabel("different:$index")
                .expectedSha256(
                    io.github.mbackschat.a12.dm.interpreter.workspace.Sha256.hex(source.json),
                )
                .build()
        }

        val before = ModelWorkspace.fromSources(original).prepare(entryModelId(caseId))
        val after = ModelWorkspace.fromSources(relabeled).prepare(entryModelId(caseId))

        assertEquals(before.fingerprint, after.fingerprint)
    }

    @Test
    fun changingExactBytesInsideTheResolvedClosureChangesTheFingerprintOnly() {
        val caseId = "imported-typedef-nested-single"
        val original = sources(caseId)
        val dependencyIndex = original.indexOfFirst { it.modelIdHint() != entryModelId(caseId) }
        val changed = original.toMutableList()
        changed[dependencyIndex] = source(changed[dependencyIndex].json + "\n", "changed-dependency")

        val before = ModelWorkspace.fromSources(original).prepare(entryModelId(caseId))
        val after = ModelWorkspace.fromSources(changed).prepare(entryModelId(caseId))

        assertNotEquals(before.fingerprint, after.fingerprint)
        assertEquals(before.definition(), after.definition())
    }

    @Test
    fun dependenciesRetainResolvedEdgesAndComputedDigestsWithoutSourceText() {
        val prepared = ModelWorkspace.fromSources(sources("reference-family-single"))
            .prepare(entryModelId("reference-family-single"))

        assertTrue(prepared.dependencies.isNotEmpty())
        prepared.dependencies.forEach { dependency ->
            assertTrue(dependency.fromModelId.isNotBlank())
            assertTrue(dependency.toModelId.isNotBlank())
            assertTrue(Regex("[0-9a-f]{64}").matches(dependency.sha256))
            assertFalse(dependency.toString().contains("\"header\""))
        }
    }

    @Test
    fun throwingAndNonThrowingPreparationExposeTheSameStructuredFailure() {
        val sources = sources("reference-family-single")
        val entry = entryModelId("reference-family-single")
        val incomplete = sources.filter { it.modelIdHint() == entry }
        val workspace = ModelWorkspace.fromSources(incomplete)

        val thrown = assertFailsWith<ModelPreparationError> {
            workspace.prepare(entry)
        }
        val attempted = assertIs<ModelPreparationResult.Failure>(
            workspace.tryPrepare(entry),
        )

        assertEquals(thrown.diagnostics, attempted.error.diagnostics)
        assertEquals(
            ModelPreparationDiagnosticCode.UNRESOLVED_MODEL_REFERENCE,
            attempted.error.diagnostics.single().code,
        )
    }

    @Test
    fun throwingAndNonThrowingSuccessReturnEquivalentPreparedModels() {
        val caseId = "reference-family-single"
        val workspace = ModelWorkspace.fromSources(sources(caseId))
        val thrownPath = workspace.prepare(entryModelId(caseId))
        val attempted = assertIs<ModelPreparationResult.Success>(
            workspace.tryPrepare(entryModelId(caseId)),
        )

        assertEquals(thrownPath.definition(), attempted.model.definition())
        assertEquals(thrownPath.fingerprint, attempted.model.fingerprint)
        assertEquals(thrownPath.dependencies, attempted.model.dependencies)
    }

    @Test
    fun acquisitionSecretsNeverEnterPreparationDiagnosticsOrDependencyMetadata() {
        val caseId = "reference-family-single"
        val entry = entryModelId(caseId)
        val secret = "https://user:password@example.invalid/models?token=top-secret"
        val incomplete = WorkspacePreparationFixtureJson.sources(caseId)
            .filterKeys { it == entry }
            .values
            .map { json -> source(json, secret) }
        val workspace = ModelWorkspace.fromSources(incomplete)

        val error = assertFailsWith<ModelPreparationError> {
            workspace.prepare(entry)
        }
        val rendered = error.toString() + error.diagnostics.joinToString()

        assertFalse(rendered.contains("password"))
        assertFalse(rendered.contains("top-secret"))

        val complete = WorkspacePreparationFixtureJson.sources(caseId)
            .values
            .map { json -> source(json, secret) }
        val prepared = ModelWorkspace.fromSources(complete).prepare(entry)
        val metadata = prepared.dependencies.joinToString() + prepared.fingerprint

        assertFalse(metadata.contains("password"))
        assertFalse(metadata.contains("top-secret"))
    }

    @Test
    fun expandedFastPathRejectsEveryPreparationResidueThroughThePublicErrorFamily() {
        val residues = listOf(
            WorkspacePreparationFixtureJson.sources("local-typedef")
                .getValue(entryModelId("local-typedef")),
            preparedModel(
                id = "include",
                modelConfig = "",
                fieldType = """{"type":"StringType"}""",
            ).replace(
                """"Group":{"elements":""",
                """"Group":{"modelAlias":"provider","elements":""",
            ),
            preparedModel(
                id = "opaque",
                modelConfig = "",
                fieldType = """{"type":"StringType"}""",
            ).replace(""""id":"/Root/Value"""", """"id":"opaque-field-id""""),
            preparedModel(
                id = "typedef-residue",
                modelConfig = "",
                fieldType = """
                    {"type":"TypeDefType","TypeDefType":{"typeDefinitionId":"Missing"}}
                """.trimIndent(),
            ),
        )

        for (source in residues) {
            val thrown = assertFailsWith<ModelPreparationError> {
                PreparedModel.loadExpandedJson(source)
            }
            val attempted = assertIs<ModelPreparationResult.Failure>(
                PreparedModel.tryLoadExpandedJson(source),
            )

            assertEquals(
                ModelPreparationDiagnosticCode.UNPREPARED_MODEL_RESIDUE,
                thrown.diagnostics.single().code,
            )
            assertEquals(thrown.diagnostics, attempted.error.diagnostics)
        }
    }

    @Test
    fun expandedFastPathClassifiesInvalidSourceDatePrecisionAndTimeZone() {
        val invalidJson = assertIs<ModelPreparationResult.Failure>(
            PreparedModel.tryLoadExpandedJson("{"),
        )
        assertEquals(
            ModelPreparationDiagnosticCode.INVALID_MODEL_SOURCE,
            invalidJson.error.diagnostics.single().code,
        )

        for (precision in listOf("MONTH_OPTIONAL", "YEAR_OPTIONAL")) {
            val unsupportedPrecision = assertIs<ModelPreparationResult.Failure>(
                PreparedModel.tryLoadExpandedJson(
                    preparedModel(
                        id = precision.lowercase(),
                        modelConfig = "",
                        fieldType = """
                            {"type":"DateType","DateType":{"format":"dd.MM.yyyy","datePrecision":"$precision"}}
                        """.trimIndent(),
                    ),
                ),
            )
            assertEquals(
                ModelPreparationDiagnosticCode.UNSUPPORTED_DATE_PRECISION,
                unsupportedPrecision.error.diagnostics.single().code,
            )
            assertEquals(precision, unsupportedPrecision.error.diagnostics.single().reference)
        }

        val unsupportedTimeZone = assertIs<ModelPreparationResult.Failure>(
            PreparedModel.tryLoadExpandedJson(
                preparedModel(
                    id = "unsupported-zone",
                    modelConfig = """"timeZone":"Europe/Paris"""",
                    fieldType = """{"type":"StringType"}""",
                ),
            ),
        )
        assertEquals(
            ModelPreparationDiagnosticCode.UNSUPPORTED_TIME_ZONE,
            unsupportedTimeZone.error.diagnostics.single().code,
        )
    }

    @Test
    fun expandedFastPathRejectsAMalformedModelReferenceContainer() {
        val source = preparedModel(
            id = "malformed-references",
            modelConfig = "",
            fieldType = """{"type":"StringType"}""",
        ).replace(
            """"header":{"id":"malformed-references"}""",
            """"header":{"id":"malformed-references","modelReferences":{}}""",
        )

        val failure = assertIs<ModelPreparationResult.Failure>(
            PreparedModel.tryLoadExpandedJson(source),
        )

        assertEquals(
            ModelPreparationDiagnosticCode.INVALID_MODEL_SOURCE,
            failure.error.diagnostics.single().code,
        )
        assertEquals(
            "header.modelReferences",
            failure.error.diagnostics.single().path,
        )
    }

    @Test
    fun expandedFastPathMapsMalformedPreparedStructuresToTypedFailures() {
        val malformedFieldBody = preparedModel(
            id = "malformed-field",
            modelConfig = "",
            fieldType = """{"type":"StringType"}""",
        ).replace(
            """"Field":{"fieldType":{"type":"StringType"}}""",
            """"Field":null""",
        )
        val malformedDatePrecision = preparedModel(
            id = "malformed-date",
            modelConfig = "",
            fieldType = """
                {"type":"DateType","DateType":{"format":"dd.MM.yyyy","datePrecision":"UNKNOWN"}}
            """.trimIndent(),
        )
        val overClimbingComputation = preparedElementModel(
            id = "malformed-computation",
            element = """
                {
                  "type":"Computation",
                  "id":"/Root/Calc",
                  "name":"Calc",
                  "Computation":{
                    "computedFieldRelPath":"../../../Value",
                    "computationAlternatives":[]
                  }
                }
            """.trimIndent(),
        )

        for ((source, expectedPath) in listOf(
            malformedFieldBody to "/Root/Value",
            malformedDatePrecision to "/Root/Value",
            overClimbingComputation to "/Root/Calc",
        )) {
            val failure = assertIs<ModelPreparationResult.Failure>(
                PreparedModel.tryLoadExpandedJson(source),
            )

            assertEquals(
                ModelPreparationDiagnosticCode.INVALID_MODEL_SOURCE,
                failure.error.diagnostics.single().code,
            )
            assertEquals(expectedPath, failure.error.diagnostics.single().path)
        }
    }

    private fun sources(caseId: String): List<WorkspaceModelSource> =
        WorkspacePreparationFixtureJson.sources(caseId).map { (modelId, json) ->
            source(json, "fixture:$modelId")
        }

    private fun source(json: String, label: String): WorkspaceModelSource =
        WorkspaceModelSource.builder()
            .json(json)
            .diagnosticLabel(label)
            .build()

    private fun entryModelId(caseId: String): String =
        WorkspacePreparationFixtureJson.entryModelId(caseId)

    private fun expanded(caseId: String): String {
        val admitted = WorkspaceSourceAdmission.admit(sources(caseId))
        return WorkspaceReferenceAssembler.assemble(admitted, entryModelId(caseId)).json
    }

    private fun WorkspaceModelSource.modelIdHint(): String =
        Regex(""""id"\s*:\s*"([^"]+)"""")
            .find(json)
            ?.groupValues
            ?.get(1)
            .orEmpty()

    private fun unusedModel(id: String, fieldName: String): String =
        preparedModel(id, "", """{"type":"StringType"}""", fieldName)

    private fun preparedModel(
        id: String,
        modelConfig: String,
        fieldType: String,
        fieldName: String = "Value",
    ): String {
        val config = modelConfig.takeIf(String::isNotEmpty)?.let { """"modelConfig":{$it},""" }.orEmpty()
        return """
            {
              "header":{"id":"$id"},
              "content":{
                $config
                "modelRoot":{"rootGroups":[{
                  "type":"Group","id":"/Root","name":"Root",
                  "Group":{"elements":[{
                    "type":"Field","id":"/Root/$fieldName","name":"$fieldName",
                    "Field":{"fieldType":$fieldType}
                  }]}
                }]}
              }
            }
        """.trimIndent()
    }

    private fun preparedElementModel(
        id: String,
        element: String,
    ): String = """
        {
          "header":{"id":"$id"},
          "content":{
            "modelRoot":{"rootGroups":[{
              "type":"Group","id":"/Root","name":"Root",
              "Group":{"elements":[$element]}
            }]}
          }
        }
    """.trimIndent()
}
