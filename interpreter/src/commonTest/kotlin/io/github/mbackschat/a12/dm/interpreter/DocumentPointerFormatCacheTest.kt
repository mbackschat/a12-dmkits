package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.footprint.FootprintScenarios
import io.github.mbackschat.a12.dm.interpreter.footprint.FootprintScenario
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldType
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldTypeDeclaration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class DocumentPointerFormatCacheTest {

    @Test
    fun reusesOneMessagePointerUntilTheDocumentMutates() {
        val path = "/Order/Lines/Amount"
        val document = Document(mapOf(path to Kind.NUMBER))
            .field(path, "7", 1, 7, 1)

        val first = document.messagePointer(path, listOf(1, 7, 1))
        val entriesAfterFirstBuild = document.cacheEntries()
        val repeated = document.messagePointer(path, listOf(1, 7, 1))
        val entriesAfterRepeatedBuild = document.cacheEntries()
        document.field(path, "8", 1, 8, 1)
        val entriesAfterMutation = document.cacheEntries()
        val afterMutation = document.messagePointer(path, listOf(1, 7, 1))

        assertEquals(path, first.fullName)
        assertEquals(listOf(1, 7, 1), first.repetitionIndexes)
        assertSame(first, repeated)
        assertEquals(entriesAfterFirstBuild, entriesAfterRepeatedBuild, "the repeated pointer adds no cache entry")
        assertEquals(0, entriesAfterMutation, "document mutation discards the complete derived index")
        assertEquals(first, afterMutation)
    }

    /** The house string is a projection of that retained pointer, so it inherits the same cache entry. */
    @Test
    fun theHouseFormProjectsTheRetainedPointer() {
        val path = "/Order/Lines/Amount"
        val document = Document(mapOf(path to Kind.NUMBER)).field(path, "7", 1, 7, 1)

        val pointer = document.messagePointer(path, listOf(1, 7, 1))
        val entriesAfterPointer = document.cacheEntries()

        assertEquals("/Order[1]/Lines[7]/Amount", document.formattedPointer(path, listOf(1, 7, 1)))
        assertEquals(messagePointerHouseForm(pointer), document.formattedPointer(path, listOf(1, 7, 1)))
        assertEquals(entriesAfterPointer, document.cacheEntries(), "the projection adds no cache entry")
    }

    @Test
    fun successfulInputSupportChecksDoNotMaterializeDiagnosticPointers() {
        for (custom in listOf(false, true)) {
            val fixture = FootprintScenarios.fixture(FootprintScenario.SIMPLE_VALIDATION, 2)
            val model = fixture.model.copy(rules = emptyList(), fields = fixture.model.fields.map {
                if (custom) it.copy(customType = CustomFieldTypeDeclaration("AcceptedText")) else it
            })
            val result = DmInterpreter(model, customFieldTypes = mapOf("AcceptedText" to CustomFieldType { _, _ -> null }))
                .validate(fixture.document)
            assertEquals(emptyList(), result.messages)
            assertEquals(emptyList(), result.unsupported)
            val before = fixture.document.cacheEntries()
            for (field in model.fields) for (row in 1..2) {
                fixture.document.messagePointer(field.path, listOf(1, row, 1))
            }
            assertEquals(2 * model.fields.size, fixture.document.cacheEntries() - before,
                "diagnostic pointers must remain absent for supported input cells (custom=$custom)")
        }
    }

    @Test
    fun successfulComputationSupportChecksDoNotMaterializeDiagnosticPointers() {
        val fixture = FootprintScenarios.fixture(FootprintScenario.COMPUTE_OVERLAY, 2)
        val result = fixture.evaluate(DmInterpreter(fixture.model))
        assertEquals(true, result.supported)
        assertEquals(6, result.cardinality)
        val before = fixture.document.cacheEntries()
        for (field in fixture.model.fields) for (row in 1..2) {
            fixture.document.messagePointer(field.path, listOf(1, row, 1))
        }
        assertEquals(2 * fixture.model.fields.size, fixture.document.cacheEntries() - before,
            "ordinary inputs and computed targets must not retain unused diagnostic pointers")
    }

    private fun Document.cacheEntries(): Int {
        var observed = -1
        observeCacheEntries { _, kind, entries ->
            if (kind == CacheOwnerKind.DOCUMENT) observed = entries
        }
        return observed
    }
}
