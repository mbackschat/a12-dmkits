package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.model.Kind
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
        assertEquals(MessagePointers.houseForm(pointer), document.formattedPointer(path, listOf(1, 7, 1)))
        assertEquals(entriesAfterPointer, document.cacheEntries(), "the projection adds no cache entry")
    }

    private fun Document.cacheEntries(): Int {
        var observed = -1
        observeCacheEntries { _, kind, entries ->
            if (kind == CacheOwnerKind.DOCUMENT) observed = entries
        }
        return observed
    }
}
