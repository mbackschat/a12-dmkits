package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class DocumentPointerFormatCacheTest {

    @Test
    fun reusesOneRenderedPointerUntilTheDocumentMutates() {
        val path = "/Order/Lines/Amount"
        val document = Document(mapOf(path to Kind.NUMBER))
            .field(path, "7", 1, 7, 1)

        val first = document.formattedPointer(path, listOf(1, 7, 1))
        val entriesAfterFirstRender = document.cacheEntries()
        val repeated = document.formattedPointer(path, listOf(1, 7, 1))
        val entriesAfterRepeatedRender = document.cacheEntries()
        document.field(path, "8", 1, 8, 1)
        val entriesAfterMutation = document.cacheEntries()
        val afterMutation = document.formattedPointer(path, listOf(1, 7, 1))

        assertEquals("/Order[1]/Lines[7]/Amount", first)
        assertSame(first, repeated)
        assertEquals(entriesAfterFirstRender, entriesAfterRepeatedRender, "the repeated pointer adds no cache entry")
        assertEquals(0, entriesAfterMutation, "document mutation discards the complete derived index")
        assertEquals(first, afterMutation)
    }

    private fun Document.cacheEntries(): Int {
        var observed = -1
        observeCacheEntries { _, kind, entries ->
            if (kind == CacheOwnerKind.DOCUMENT) observed = entries
        }
        return observed
    }
}
