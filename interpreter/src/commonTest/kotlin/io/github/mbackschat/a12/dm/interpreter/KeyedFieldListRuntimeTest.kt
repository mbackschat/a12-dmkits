package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

/** JVM/Node replay of the kernel observations in adapter.laws.KeyedGroupFieldListDiffTest. */
class KeyedFieldListRuntimeTest {
    private fun fires(condition: String, tail: String = "5", second: List<String> = listOf("3")): Boolean {
        val model = ModelDef(
            fields = listOf(FieldDef("/Order/Reference", Kind.STRING),
                FieldDef("/Order/Packages/Code", Kind.STRING),
                FieldDef("/Order/Packages/Items/Sku", Kind.STRING),
                FieldDef("/Order/Packages/Items/Weight", Kind.NUMBER)),
            rules = listOf(RuleDef("/Order/R", "/Order", "FieldFilled(Reference) And ($condition)",
                "KEYED", "../Reference")),
            repeatableGroups = setOf("/Order/Packages", "/Order/Packages/Items"),
            declaredReps = mapOf("/Order/Packages" to 5, "/Order/Packages/Items" to 3),
            indexFields = setOf("/Order/Packages/Code"),
        )
        val doc = model.newDocument().field("/Order/Reference", "R", 1, 1)
        listOf(listOf("2", tail), second).forEachIndexed { parent, weights ->
            doc.group("/Order/Packages", 1, parent + 1)
                .field("/Order/Packages/Code", "P${parent + 1}", 1, parent + 1, 1)
            weights.forEachIndexed { row, weight ->
                doc.group("/Order/Packages/Items", 1, parent + 1, row + 1)
                    .field("/Order/Packages/Items/Sku", "S${row + 1}", 1, parent + 1, row + 1, 1)
                    .field("/Order/Packages/Items/Weight", weight, 1, parent + 1, row + 1, 1)
            }
        }
        val interpreter = DmInterpreter(model)
        assertEquals(emptyList(), interpreter.supportAudit(), condition)
        return interpreter.validateFull(doc).any { it.code == "KEYED" }
    }

    @Test fun membershipAndUniquenessReadTheSelectedList() {
        val operand = "/Order/Packages/Items*/Sku For \"P1\""
        for ((condition, expected) in mapOf(
            "AtLeastOneFieldValueIncludedInValueList($operand In \"S1\")" to true,
            "NoFieldValueIncludedInValueList($operand In \"S1\")" to false,
            "NotAllFieldValuesIncludedInValueList($operand In \"S1\")" to true,
            "FieldValuesNotUnique($operand)" to false,
        )) assertEquals(expected, fires(condition), condition)
    }

    @Test fun productsCheckBothListsBeforePairingTheirCommonPositions() {
        for ((left, right) in listOf("P1" to "P2", "P2" to "P1")) {
            val sum = "SumOfProducts(/Order/Packages/Items*/Weight For \"$left\", /Order/Packages/Items*/Weight For \"$right\")"
            assertEquals(true, fires("$sum == 6"))
            assertEquals(true, fires("$sum == 0", second = emptyList()))
            assertEquals(false, fires("$sum == 6", tail = "bad"))
        }
    }
}
