package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ModelPathIndexTest {
    private val index = ModelPathIndex(
        ModelDef(
            fields = listOf(FieldDef("/Claim/Lines/Amount", Kind.NUMBER)),
            rules = emptyList(),
            declaredReps = mapOf("/Claim" to 2, "/Claim/Lines" to 3),
        ),
    )

    @Test
    fun preparesAncestorsOnceForFieldsAndGroups() {
        assertEquals(listOf("/Claim", "/Claim/Lines"), index.ancestorsOf("/Claim/Lines/Amount"))
        assertEquals(listOf("/Claim", "/Claim/Lines"), index.groupsThrough("/Claim/Lines"))
        assertEquals(1, index.levelOf("/Claim/Lines"))
    }

    @Test
    fun findsTheOutermostExceededBoundWithoutRebuildingPrefixes() {
        assertEquals(0, index.overLimitLevel("/Claim/Lines/Amount", listOf(3, 4, 1)))
        assertEquals(1, index.overLimitLevel("/Claim/Lines/Amount", listOf(1, 4, 1)))
        assertNull(index.overLimitLevel("/Claim/Lines/Amount", listOf(2, 3, 1)))
    }
}
