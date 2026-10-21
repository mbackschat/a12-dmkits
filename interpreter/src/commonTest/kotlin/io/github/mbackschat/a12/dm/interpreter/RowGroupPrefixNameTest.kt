package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A field whose path merely BEGINS with a row's group name is not in that group. A row resolves the fields directly
 * under its own group without a lookup, and `/Policy/ItemCount` starts with `/Policy/Item`, so the shortcut must also
 * see the separator; read as a child it would take the row's repetition and find no value. The oracle is the same
 * model with the field named so it shares no prefix: renaming a field changes no reading.
 */
class RowGroupPrefixNameTest {

    @Test
    fun aParentFieldNamedLikeTheRowGroupReadsAsAnyOtherParentField() {
        val colliding = firings("$ROOT/ItemCount")
        assertTrue(colliding.isNotEmpty(), "the control must fire, or the comparison is between two silences")
        assertEquals(firings("$ROOT/Count"), colliding)
    }

    private fun firings(countPath: String): List<String> {
        val model = ModelDef(
            fields = listOf(FieldDef(countPath, Kind.NUMBER, scale = 0), FieldDef(PREMIUM, Kind.NUMBER, scale = 2)),
            rules = listOf(RuleDef("$ITEM/CountRead", ITEM, "FieldFilled($PREMIUM) And FieldFilled($countPath)",
                "COUNT_READ", "../Premium")),
            repeatableGroups = setOf(ITEM),
            declaredReps = mapOf(ITEM to 3),
        )
        val document = model.newDocument().group(ROOT, 1).field(countPath, "2", 1, 1)
        (1..2).forEach { row -> document.group(ITEM, 1, row).field(PREMIUM, "10.00", 1, row, 1) }
        return DmInterpreter(model).validateFull(document).filter { it.code == "COUNT_READ" }.map { "${it.errorPointer}" }
    }

    private companion object {
        const val ROOT = "/Policy"
        const val ITEM = "/Policy/Item"
        const val PREMIUM = "/Policy/Item/Premium"
    }
}
