package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.StoredCellState
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** JVM + JS lock for the kernel's empty-string ingestion boundary (IF198). */
class EmptyStringIngestionTest {

    @Test
    fun anEmptyStringKeepsItsPlacementButCarriesNoValue() {
        val document = ModelDef(listOf(FieldDef(CODE, Kind.STRING)), emptyList())
            .newDocument()
            .group(ROOT, 1)
            .field(CODE, "", 1, 1)

        assertFalse(document.filledAt(CODE, CELL))
        assertEquals(StoredCellState.PresentEmpty, document.cellStateAt(CODE, CELL))
    }

    private companion object {
        const val ROOT = "/Application"
        const val CODE = "$ROOT/Code"
        val CELL = listOf(1, 1)
    }
}
