package io.github.mbackschat.a12.dm.interpreter.workspace

import io.github.mbackschat.a12.dm.interpreter.ModelPreparationDiagnostic
import io.github.mbackschat.a12.dm.interpreter.ModelPreparationDiagnosticCode
import io.github.mbackschat.a12.dm.interpreter.ModelPreparationError
import io.github.mbackschat.a12.dm.interpreter.ModelPreparationLimits
import io.github.mbackschat.a12.dm.interpreter.ModelPreparationResource
import io.github.mbackschat.a12.dm.interpreter.WorkspaceModelSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspaceSourceAdmissionTest {

    @Test
    fun indexesExactHeaderIdsAndSnapshotsTheSuppliedSourceList() {
        val upper = source("Policy", label = "showcase:policy")
        val lower = source("policy", label = "policy-secondary")
        val mutableInput = mutableListOf(upper, lower)

        val workspace = WorkspaceSourceAdmission.admit(mutableInput)
        mutableInput.clear()

        assertEquals(listOf("Policy", "policy"), workspace.modelIds)
        assertEquals("showcase:policy", workspace.source("Policy")?.diagnosticLabel)
        assertEquals(Sha256.hex(upper.json), workspace.source("Policy")?.sha256)
        assertEquals(upper.json, workspace.source("Policy")!!.json)
    }

    @Test
    fun rejectsDuplicateExactIdsWithoutSelectingByInputOrder() {
        val error = assertFailsWith<ModelPreparationError> {
            WorkspaceSourceAdmission.admit(
                listOf(
                    source("policy", label = "first"),
                    source("policy", label = "second"),
                ),
            )
        }

        val diagnostic = error.diagnostics.single()
        assertEquals(ModelPreparationDiagnosticCode.DUPLICATE_MODEL_ID, diagnostic.code)
        assertEquals("policy", diagnostic.modelId)
        assertEquals(listOf("first", "second"), diagnostic.conflictingDiagnosticLabels)
    }

    @Test
    fun verifiesExpectedSha256AndRejectsMalformedDigestText() {
        val json = raw("policy")
        val valid = workspaceSource(
            json = json,
            expectedSha256 = "4a3c92341b976141a8fcbc137e58e3f5e53488d79228809ae86db9f2c3428f4f",
        )

        assertEquals(valid.expectedSha256, WorkspaceSourceAdmission.admit(listOf(valid)).source("policy")?.sha256)

        val mismatch = assertFailsWith<ModelPreparationError> {
            WorkspaceSourceAdmission.admit(
                listOf(workspaceSource(valid.json, expectedSha256 = "0".repeat(64))),
            )
        }.diagnostics.single()
        assertEquals(ModelPreparationDiagnosticCode.MODEL_SOURCE_INTEGRITY_MISMATCH, mismatch.code)
        assertEquals("0".repeat(64), mismatch.expectedSha256)
        assertEquals(valid.expectedSha256, mismatch.actualSha256)

        val malformed = assertFailsWith<ModelPreparationError> {
            WorkspaceSourceAdmission.admit(
                listOf(workspaceSource(valid.json, expectedSha256 = "NOT-A-SHA")),
            )
        }.diagnostics.single()
        assertEquals(ModelPreparationDiagnosticCode.INVALID_MODEL_SOURCE, malformed.code)
        assertTrue(malformed.message.contains("lowercase hexadecimal SHA-256"))
    }

    @Test
    fun reportsInvalidJsonAndMissingHeaderIdentityStructurally() {
        val invalidJson = assertFailsWith<ModelPreparationError> {
            WorkspaceSourceAdmission.admit(listOf(workspaceSource("{")))
        }.diagnostics.single()
        assertEquals(ModelPreparationDiagnosticCode.INVALID_MODEL_SOURCE, invalidJson.code)

        val missingId = assertFailsWith<ModelPreparationError> {
            WorkspaceSourceAdmission.admit(listOf(workspaceSource("""{"header":{}}""")))
        }.diagnostics.single()
        assertEquals(ModelPreparationDiagnosticCode.INVALID_MODEL_SOURCE, missingId.code)
        assertTrue(missingId.message.contains("header.id"))
    }

    @Test
    fun rejectsMalformedUtf16InsteadOfHashingReplacementCharacters() {
        for (codeUnit in listOf(0xd800, 0xdc00)) {
            val diagnostic = assertFailsWith<ModelPreparationError> {
                WorkspaceSourceAdmission.admit(listOf(workspaceSource(malformedJson(codeUnit))))
            }.diagnostics.single()

            assertEquals(ModelPreparationDiagnosticCode.INVALID_MODEL_SOURCE, diagnostic.code)
            assertTrue(diagnostic.message.contains("strict UTF-8"))
        }
    }

    @Test
    fun rejectsEscapedUnpairedSurrogatesBeforeHostSerializersCanDiverge() {
        val escapedSurrogate = "\\" + "uD800"
        val source = raw("escaped", extra = ",\"text\":\"$escapedSurrogate\"")

        val diagnostic = assertFailsWith<ModelPreparationError> {
            WorkspaceSourceAdmission.admit(listOf(workspaceSource(source)))
        }.diagnostics.single()

        assertEquals(ModelPreparationDiagnosticCode.INVALID_MODEL_SOURCE, diagnostic.code)
        assertTrue(diagnostic.message.contains("Unicode scalar"))
    }

    @Test
    fun enforcesModelCountPerSourceAndAggregateUtf8ByteLimits() {
        val small = source("small")
        val euro = source("euro", extra = ",\"text\":\"€\"")
        val emoji = source("emoji", extra = ",\"text\":\"😀\"")
        val euroBytes = euro.json.encodeToByteArray().size
        val emojiBytes = emoji.json.encodeToByteArray().size

        val count = failure(
            listOf(small, euro),
            ModelPreparationLimits.builder().maxModelCount(1).build(),
        )
        assertResource(count, ModelPreparationResource.MODEL_COUNT, 1, 2)

        val perSource = failure(
            listOf(euro),
            ModelPreparationLimits.builder().maxModelBytes(euroBytes - 1).build(),
        )
        assertResource(perSource, ModelPreparationResource.MODEL_BYTES, euroBytes - 1, euroBytes)

        val surrogatePair = failure(
            listOf(emoji),
            ModelPreparationLimits.builder().maxModelBytes(emojiBytes - 1).build(),
        )
        assertResource(surrogatePair, ModelPreparationResource.MODEL_BYTES, emojiBytes - 1, emojiBytes)

        val totalBytes = small.json.encodeToByteArray().size + euroBytes
        val total = failure(
            listOf(small, euro),
            ModelPreparationLimits.builder().maxTotalBytes(totalBytes - 1).build(),
        )
        assertResource(total, ModelPreparationResource.TOTAL_BYTES, totalBytes - 1, totalBytes)
    }

    @Test
    fun aggregateLimitRefusesBeforeParsingOrHashingTheOversizedWorkspace() {
        val invalid = workspaceSource("{not-json")
        val byteCount = invalid.json.encodeToByteArray().size
        val diagnostic = failure(
            listOf(invalid),
            ModelPreparationLimits.builder()
                .maxModelBytes(byteCount)
                .maxTotalBytes(byteCount - 1)
                .build(),
        )

        assertResource(
            diagnostic,
            ModelPreparationResource.TOTAL_BYTES,
            byteCount - 1,
            byteCount,
        )
    }

    @Test
    fun aggregateLimitStopsFreezingAtTheFirstSourceThatCrossesTheBoundary() {
        val first = source("first")
        val second = source("second")
        val neverAdmitted = source("tail", extra = ",\"text\":\"${"x".repeat(1_000)}\"")
        val observedBytes = first.json.encodeToByteArray().size + second.json.encodeToByteArray().size
        val diagnostic = failure(
            listOf(first, second, neverAdmitted),
            ModelPreparationLimits.builder().maxTotalBytes(observedBytes - 1).build(),
        )

        assertResource(
            diagnostic,
            ModelPreparationResource.TOTAL_BYTES,
            observedBytes - 1,
            observedBytes,
        )
    }

    @Test
    fun callerWideningIsCappedByDocumentedHardMaxima() {
        val limits = ModelPreparationLimits.builder()
            .maxModelCount(Int.MAX_VALUE)
            .maxModelBytes(Int.MAX_VALUE)
            .maxTotalBytes(Int.MAX_VALUE)
            .maxPreparedBytes(Int.MAX_VALUE)
            .maxReferenceDepth(Int.MAX_VALUE)
            .maxReferenceEdges(Int.MAX_VALUE)
            .maxStructureDepth(Int.MAX_VALUE)
            .maxPreparedElements(Int.MAX_VALUE)
            .build()

        assertEquals(ModelPreparationLimits.HARD_MAX_MODEL_COUNT, limits.maxModelCount)
        assertEquals(ModelPreparationLimits.HARD_MAX_MODEL_BYTES, limits.maxModelBytes)
        assertEquals(ModelPreparationLimits.HARD_MAX_TOTAL_BYTES, limits.maxTotalBytes)
        assertEquals(ModelPreparationLimits.HARD_MAX_PREPARED_BYTES, limits.maxPreparedBytes)
        assertEquals(ModelPreparationLimits.HARD_MAX_REFERENCE_DEPTH, limits.maxReferenceDepth)
        assertEquals(ModelPreparationLimits.HARD_MAX_REFERENCE_EDGES, limits.maxReferenceEdges)
        assertEquals(ModelPreparationLimits.HARD_MAX_STRUCTURE_DEPTH, limits.maxStructureDepth)
        assertEquals(ModelPreparationLimits.HARD_MAX_PREPARED_ELEMENTS, limits.maxPreparedElements)
    }

    @Test
    fun defaultsAreTheDocumentedBrowserBoundedProfile() {
        val limits = ModelPreparationLimits.defaults()

        assertEquals(256, limits.maxModelCount)
        assertEquals(4 * 1024 * 1024, limits.maxModelBytes)
        assertEquals(32 * 1024 * 1024, limits.maxTotalBytes)
        assertEquals(64 * 1024 * 1024, limits.maxPreparedBytes)
        assertEquals(32, limits.maxReferenceDepth)
        assertEquals(4_096, limits.maxReferenceEdges)
        assertEquals(64, limits.maxStructureDepth)
        assertEquals(250_000, limits.maxPreparedElements)
    }

    @Test
    fun unsafeDiagnosticLabelsNeverEnterWorkspaceDiagnosticsOrMessages() {
        val secret = "https://user:password@example.invalid/model?token=top-secret"
        val admitted = WorkspaceSourceAdmission.admit(listOf(source("clean", label = secret)))
        assertNull(admitted.source("clean")?.diagnosticLabel)

        val error = assertFailsWith<ModelPreparationError> {
            WorkspaceSourceAdmission.admit(
                listOf(
                    source("policy", label = secret),
                    source("policy", label = "safe-label"),
                ),
            )
        }

        val diagnostic = error.diagnostics.single()
        assertNull(diagnostic.diagnosticLabel)
        assertEquals(listOf("safe-label"), diagnostic.conflictingDiagnosticLabels)
        assertFalse(error.message.orEmpty().contains("password"))
        assertFalse(error.message.orEmpty().contains("top-secret"))
        assertTrue(error.message.orEmpty().contains("policy"))
    }

    @Test
    fun workspaceAndDiagnosticListsAreRuntimeImmutable() {
        val workspace = WorkspaceSourceAdmission.admit(listOf(source("Policy"), source("policy")))
        assertFails { (workspace.modelIds as MutableList<String>).clear() }
        assertEquals(listOf("Policy", "policy"), workspace.modelIds)
        assertTrue(workspace.source("Policy") != null)

        val error = assertFailsWith<ModelPreparationError> {
            WorkspaceSourceAdmission.admit(
                listOf(source("duplicate", label = "first"), source("duplicate", label = "second")),
            )
        }
        val diagnostic = error.diagnostics.single()
        assertFails { (error.diagnostics as MutableList<ModelPreparationDiagnostic>).clear() }
        assertFails { (diagnostic.conflictingDiagnosticLabels as MutableList<String>).clear() }
        assertEquals(listOf("first", "second"), diagnostic.conflictingDiagnosticLabels)
    }

    private fun failure(
        sources: List<WorkspaceModelSource>,
        limits: ModelPreparationLimits,
    ): ModelPreparationDiagnostic =
        assertFailsWith<ModelPreparationError> {
            WorkspaceSourceAdmission.admit(sources, limits)
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

    private fun source(
        id: String,
        label: String? = null,
        extra: String = "",
    ): WorkspaceModelSource = workspaceSource(raw(id, extra), label)

    private fun workspaceSource(
        json: String,
        diagnosticLabel: String? = null,
        expectedSha256: String? = null,
    ): WorkspaceModelSource = WorkspaceModelSource.builder()
        .json(json)
        .diagnosticLabel(diagnosticLabel)
        .expectedSha256(expectedSha256)
        .build()

    /**
     * Build the invalid code unit at runtime: Kotlin/JS rewrites a source-level lone-surrogate literal to `?`,
     * which would test an ordinary ASCII source instead of the workspace admission boundary.
     */
    private fun malformedJson(codeUnit: Int): String = buildString {
        append("""{"header":{"id":"policy"},"text":"""")
        append(codeUnit.toChar())
        append("\"}")
    }

    private fun raw(id: String, extra: String = ""): String =
        """{"header":{"id":"$id"}$extra}"""
}
