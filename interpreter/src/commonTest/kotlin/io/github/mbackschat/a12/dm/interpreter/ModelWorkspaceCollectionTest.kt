package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.workspace.Sha256
import io.github.mbackschat.a12.dm.interpreter.workspace.WorkspacePreparationFixtureJson
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class ModelWorkspaceCollectionTest {

    @Test
    fun collectedAndFiniteWorkspacesPrepareTheSameTypedFixtureMatrix() {
        for (caseId in WorkspacePreparationFixtureJson.caseIds()) {
            val available = fixtureSources(caseId)
            val collected = runSuspend {
                ModelWorkspace.collect(
                    WorkspacePreparationFixtureJson.entryModelId(caseId),
                    RecordingProvider(available),
                )
            }
            val finite = ModelWorkspace.fromSources(available.values.toList())
            val entryModelId = WorkspacePreparationFixtureJson.entryModelId(caseId)
            val collectedModel = collected.prepare(entryModelId)
            val finiteModel = finite.prepare(entryModelId)

            assertEquals(finiteModel.definition(), collectedModel.definition(), caseId)
            assertEquals(finiteModel.fingerprint, collectedModel.fingerprint, caseId)
            assertEquals(finiteModel.dependencies, collectedModel.dependencies, caseId)
        }
    }

    @Test
    fun diamondLoadsEachExactIdOnceAndSkipsDeclaredButUnmountedIncludes() {
        val caseId = "collection-diamond-unmounted"
        val provider = RecordingProvider(fixtureSources(caseId))

        val workspace = runSuspend {
            ModelWorkspace.collect(
                WorkspacePreparationFixtureJson.entryModelId(caseId),
                provider,
            )
        }

        assertEquals(
            listOf(
                "permit-collection",
                "permit-collection-left-types",
                "permit-collection-right-types",
                "permit-collection-section",
                "permit-collection-shared-types",
                "permit-collection-checklist",
            ),
            provider.requests,
        )
        assertEquals(provider.requests.toSet(), workspace.modelIds.toSet())
        assertFalse("permit-collection-unused" in provider.requests)
    }

    @Test
    fun localTypeDefinitionsSuppressDeclaredImportsDuringCollection() {
        val caseId = "local-typedef"
        val entryModelId = WorkspacePreparationFixtureJson.entryModelId(caseId)
        val localSource = addTypeDefinitionImport(
            WorkspacePreparationFixtureJson.sources(caseId).getValue(entryModelId),
        )
        val provider = RecordingProvider(mapOf(entryModelId to source(localSource)))

        val workspace = runSuspend {
            ModelWorkspace.collect(entryModelId, provider)
        }

        assertEquals(listOf(entryModelId), provider.requests)
        workspace.prepare(entryModelId)
    }

    @Test
    fun integrityAndIdentityAreVerifiedBeforeReturnedSourceCanSteerDiscovery() {
        val caseId = "collection-diamond-unmounted"
        val entryModelId = WorkspacePreparationFixtureJson.entryModelId(caseId)
        val original = WorkspacePreparationFixtureJson.sources(caseId).getValue(entryModelId)
        val corrupted = WorkspaceModelSource.builder()
            .json("$original\n")
            .expectedSha256(Sha256.hex(original))
            .build()
        val integrityProvider = RecordingProvider(mapOf(entryModelId to corrupted))

        val integrity = assertFailsWith<ModelPreparationError> {
            runSuspend { ModelWorkspace.collect(entryModelId, integrityProvider) }
        }

        assertEquals(
            ModelPreparationDiagnosticCode.MODEL_SOURCE_INTEGRITY_MISMATCH,
            integrity.diagnostics.single().code,
        )
        assertEquals(listOf(entryModelId), integrityProvider.requests)

        val mismatched = fixtureSources("self-contained").values.single()
        val identityProvider = RecordingProvider(mapOf(entryModelId to mismatched))
        val identity = assertFailsWith<ModelPreparationError> {
            runSuspend { ModelWorkspace.collect(entryModelId, identityProvider) }
        }

        assertEquals(ModelPreparationDiagnosticCode.MODEL_SOURCE_ID_MISMATCH, identity.diagnostics.single().code)
        assertEquals(entryModelId, identity.diagnostics.single().requestedModelId)
        assertEquals("permit-basic", identity.diagnostics.single().actualModelId)
        assertEquals(listOf(entryModelId), identityProvider.requests)
    }

    @Test
    fun collectorSuppliesRemainingByteCeilingAndIndependentlyRefusesProviderExcess() {
        val caseId = "collection-diamond-unmounted"
        val entryModelId = WorkspacePreparationFixtureJson.entryModelId(caseId)
        val available = fixtureSources(caseId)
        val entryBytes = checkNotNull(
            io.github.mbackschat.a12.dm.interpreter.workspace.strictUtf8ByteCount(
                available.getValue(entryModelId).json,
            ),
        )
        val limits = ModelPreparationLimits.builder()
            .maxModelBytes(100_000)
            .maxTotalBytes((entryBytes + 1).toInt())
            .build()
        val provider = RecordingProvider(available)

        val error = assertFailsWith<ModelPreparationError> {
            runSuspend { ModelWorkspace.collect(entryModelId, provider, limits) }
        }

        assertEquals(listOf(1), provider.contexts.drop(1).map { it.maximumBytes })
        val diagnostic = error.diagnostics.single()
        assertEquals(ModelPreparationDiagnosticCode.RESOURCE_LIMIT_EXCEEDED, diagnostic.code)
        assertEquals(ModelPreparationResource.TOTAL_BYTES, diagnostic.resource)
    }

    @Test
    fun cancellationStopsBeforeAnotherRequestAndRetainsTheTypedReason() {
        val caseId = "collection-diamond-unmounted"
        val entryModelId = WorkspacePreparationFixtureJson.entryModelId(caseId)
        val signal = MutableCollectionSignal()
        val provider = RecordingProvider(fixtureSources(caseId))
        provider.afterLoad = { signal.cancelled = true }

        val error = assertFailsWith<ModelPreparationError> {
            runSuspend { ModelWorkspace.collect(entryModelId, provider, signal = signal) }
        }

        assertEquals(listOf(entryModelId), provider.requests)
        assertEquals(
            ModelPreparationDiagnosticCode.MODEL_SOURCE_PROVIDER_FAILED,
            error.diagnostics.single().code,
        )
        assertEquals(
            ModelSourceProviderFailureReason.CANCELLED,
            error.diagnostics.single().providerFailureReason,
        )
    }

    @Test
    fun typedAndArbitraryProviderFailuresAreNormalizedWithoutRetriesOrDiagnosticSecretLeakage() {
        val entryModelId = "permit-secret"
        val secret = "https://user:password@example.invalid/models?token=top-secret"
        for (reason in ModelSourceProviderFailureReason.entries) {
            val cause = IllegalStateException(secret)
            val provider = RecordingProvider(emptyMap())
            provider.onLoad = {
                throw ModelSourceProviderError.builder()
                    .reason(reason)
                    .modelId(entryModelId)
                    .message(secret)
                    .cause(cause)
                    .build()
            }

            val error = assertFailsWith<ModelPreparationError> {
                runSuspend { ModelWorkspace.collect(entryModelId, provider) }
            }

            assertEquals(listOf(entryModelId), provider.requests, reason.name)
            assertEquals(ModelPreparationDiagnosticCode.MODEL_SOURCE_PROVIDER_FAILED, error.diagnostics.single().code)
            assertEquals(reason, error.diagnostics.single().providerFailureReason)
            assertSame(cause, error.cause)
            assertFalse(error.message.orEmpty().contains("password"))
            assertFalse(error.diagnostics.joinToString().contains("top-secret"))
        }

        val arbitraryCause = IllegalStateException(secret)
        val arbitraryProvider = RecordingProvider(emptyMap())
        arbitraryProvider.onLoad = { throw arbitraryCause }
        val arbitrary = assertFailsWith<ModelPreparationError> {
            runSuspend { ModelWorkspace.collect(entryModelId, arbitraryProvider) }
        }

        assertEquals(
            ModelSourceProviderFailureReason.PROVIDER_FAILED,
            arbitrary.diagnostics.single().providerFailureReason,
        )
        assertSame(arbitraryCause, arbitrary.cause)
        assertFalse(arbitrary.message.orEmpty().contains("top-secret"))
    }

    @Test
    fun missingSourceAndReferenceCycleFailWithStructuralDiagnostics() {
        val missingProvider = RecordingProvider(emptyMap())
        val missing = assertFailsWith<ModelPreparationError> {
            runSuspend { ModelWorkspace.collect("permit-missing", missingProvider) }
        }

        assertEquals(ModelPreparationDiagnosticCode.UNRESOLVED_MODEL_REFERENCE, missing.diagnostics.single().code)
        assertEquals("permit-missing", missing.diagnostics.single().reference)

        val caseId = "imported-typedef-nested-single"
        val entryModelId = WorkspacePreparationFixtureJson.entryModelId(caseId)
        val available = fixtureSources(caseId).toMutableMap()
        available["permit-types"] = source(
            addSelfTypeDefinitionImport(available.getValue("permit-types").json),
        )
        val cycleProvider = RecordingProvider(available)
        val cycle = assertFailsWith<ModelPreparationError> {
            runSuspend { ModelWorkspace.collect(entryModelId, cycleProvider) }
        }

        assertEquals(ModelPreparationDiagnosticCode.MODEL_REFERENCE_CYCLE, cycle.diagnostics.single().code)
        assertEquals(2, cycle.diagnostics.single().referenceChain.size)
        assertEquals(1, cycleProvider.requests.count { it == "permit-types" })
    }

    @Test
    fun snapshotRevisionIsSanitizedProvenanceAndNeverChangesPreparedIdentity() {
        val caseId = "reference-family-single"
        val entryModelId = WorkspacePreparationFixtureJson.entryModelId(caseId)
        val firstProvider = RecordingProvider(fixtureSources(caseId)).apply {
            snapshotRevision = "manifest:revision-1"
        }
        val secondProvider = RecordingProvider(fixtureSources(caseId)).apply {
            snapshotRevision = "manifest:revision-2"
        }
        val first = runSuspend { ModelWorkspace.collect(entryModelId, firstProvider) }
        val second = runSuspend { ModelWorkspace.collect(entryModelId, secondProvider) }

        assertEquals("manifest:revision-1", first.snapshotRevision)
        assertEquals("manifest:revision-2", second.snapshotRevision)
        assertEquals(first.prepare(entryModelId).fingerprint, second.prepare(entryModelId).fingerprint)

        val unsafeProvider = RecordingProvider(fixtureSources(caseId)).apply {
            snapshotRevision = "https://example.invalid/revision?token=secret"
        }
        val unsafe = runSuspend { ModelWorkspace.collect(entryModelId, unsafeProvider) }
        assertNull(unsafe.snapshotRevision)
    }

    private class RecordingProvider(
        private val available: Map<String, WorkspaceModelSource>,
    ) : ModelSourceProvider {
        override var snapshotRevision: String? = null
        val requests = mutableListOf<String>()
        val contexts = mutableListOf<ModelSourceLoadContext>()
        var onLoad: (String) -> WorkspaceModelSource? = available::get
        var afterLoad: () -> Unit = {}

        override suspend fun load(
            modelId: String,
            context: ModelSourceLoadContext,
        ): WorkspaceModelSource? {
            requests += modelId
            contexts += context
            val source = onLoad(modelId)
            afterLoad()
            return source
        }
    }

    private class MutableCollectionSignal : ModelCollectionSignal {
        var cancelled = false
        override val isCancelled: Boolean get() = cancelled
    }

    private fun fixtureSources(caseId: String): Map<String, WorkspaceModelSource> =
        WorkspacePreparationFixtureJson.sources(caseId).mapValues { (modelId, json) ->
            WorkspaceModelSource.builder()
                .json(json)
                .diagnosticLabel("fixture:$modelId")
                .build()
        }

    private fun source(json: String): WorkspaceModelSource =
        WorkspaceModelSource.builder().json(json).build()

    private fun addTypeDefinitionImport(source: String): String =
        addTypeDefinitionImport(source, TypeDefinitionImport.SUPPRESSED)

    private fun addSelfTypeDefinitionImport(source: String): String =
        addTypeDefinitionImport(source, TypeDefinitionImport.SELF)

    private fun addTypeDefinitionImport(source: String, import: TypeDefinitionImport): String {
        val root = Json.parseToJsonElement(source) as JsonObject
        val header = root.getValue("header") as JsonObject
        val references = header["modelReferences"] as? JsonArray ?: JsonArray(emptyList())
        val imported = JsonObject(
            mapOf(
                "purpose" to JsonPrimitive("typeDefinitions"),
                "alias" to JsonPrimitive(import.reference),
                "reference" to JsonPrimitive(import.reference),
            ),
        )
        return JsonObject(
            root.mapValues { (member, value) ->
                if (member == "header") {
                    JsonObject(header.toMutableMap().apply {
                        this["modelReferences"] = JsonArray(references + imported)
                    })
                } else {
                    value
                }
            },
        ).toString()
    }

    private enum class TypeDefinitionImport(val reference: String) {
        SUPPRESSED("permit-import-that-local-mode-suppresses"),
        SELF("permit-types"),
    }

    private fun <T> runSuspend(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) {
                outcome = result
            }
        })
        return checkNotNull(outcome) { "test provider must complete synchronously" }.getOrThrow()
    }
}
