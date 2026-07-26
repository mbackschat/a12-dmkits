package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.model.Kind
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Lock for the internal ordered placement read-back (R2.8 substrate): the transport's encoder needs every stored
 * placement — auto-instantiated ancestor groups, present-empty cells, and raw lexical text — in insertion order,
 * without exposing the mutable placement list.
 */
class DocumentPlacementsSnapshotTest {

    @Test
    fun snapshotPreservesOrderStatesAndRawText() {
        val document = Document(
            mapOf(
                "/Order/Total" to Kind.NUMBER,
                "/Order/Note" to Kind.STRING,
                "/Order/Items/Qty" to Kind.NUMBER,
            ),
        )
        document.group("/Order", 1)
        document.field("/Order/Total", "12.50", 1, 1)
        document.emptyField("/Order/Note", 1, 1)
        document.field("/Order/Items/Qty", "2", 1, 2, 1)

        val records = document.placementsSnapshot()

        assertEquals(
            listOf(
                DocumentPlacementRecord("/Order", listOf(1), isGroup = true, raw = null),
                DocumentPlacementRecord("/Order/Total", listOf(1, 1), isGroup = false, raw = "12.50"),
                DocumentPlacementRecord("/Order/Note", listOf(1, 1), isGroup = false, raw = null),
                DocumentPlacementRecord("/Order/Items", listOf(1, 2), isGroup = true, raw = null),
                DocumentPlacementRecord("/Order/Items/Qty", listOf(1, 2, 1), isGroup = false, raw = "2"),
            ),
            records,
        )
    }

    @Test
    fun snapshotDistinguishesPresentEmptyRawTextFromAbsentRawText() {
        val document = Document(mapOf("/Order/Memo" to Kind.STRING))
        document.field("/Order/Memo", "", 1, 1)

        val record = document.placementsSnapshot().single { !it.isGroup }

        assertEquals("", record.raw, "an explicit empty string stays distinguishable from an unfilled cell")
    }
}
