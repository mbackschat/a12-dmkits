package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.eval.readDocumentJson
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

    @Test fun selectedGroupsExpandSecondFieldsAndPreserveAbsentFixedPolarity() {
        val root = "/Order"
        val packages = "$root/Packages"
        val model = ModelDef(
            fields = listOf(FieldDef("$root/Needle", Kind.NUMBER), FieldDef("$packages/Code", Kind.STRING)) +
                listOf("Pair", "Rows").flatMap { group -> listOf("A", "B").map { FieldDef("$packages/$group/$it", Kind.NUMBER) } },
            rules = listOf(
                "ROW" to "AtLeastOneFieldValueIncludedInValueList(Packages/Rows* For \"P1\" In Needle)",
                "PAIR" to "NoFieldValueIncludedInValueList(Packages/Pair For \"P1\" In Needle)",
            ).map { (code, condition) -> RuleDef("$root/$code", root, condition, code, "../Needle") },
            repeatableGroups = setOf(packages, "$packages/Rows"),
            declaredReps = mapOf(packages to 3, "$packages/Rows" to 3),
            indexFields = setOf("$packages/Code"), groupPaths = setOf(root, packages, "$packages/Rows", "$packages/Pair"),
        )
        val interpreter = DmInterpreter(model)
        assertEquals(emptyList(), interpreter.supportAudit())
        for (matches in listOf(false, true)) {
            val document = model.readDocumentJson("""{"Order":{"Needle":10,"Packages":[{"Code":"P1","Rows":[{"A":7,"B":${if (matches) 10 else 7}}]},{"Code":"P2","Rows":[{"A":10,"B":10}]}]}}""")
            assertEquals(if (matches) setOf("ROW|VALUE", "PAIR|OMISSION") else setOf("PAIR|OMISSION"),
                interpreter.validateFull(document).map { "${it.code}|${it.type}" }.toSet())
        }
    }

    @Test fun selectedListsKeepAncestorAndIndependentKeyRows() {
        val root = "/Store"
        val depots = "$root/Depots"
        val bins = "$depots/Bins"
        val catalog = "$root/Catalog"
        val model = ModelDef(
            fields = listOf(FieldDef("$depots/Wanted", Kind.STRING)) + listOf(bins, catalog).flatMap {
                listOf(FieldDef("$it/Code", Kind.STRING), FieldDef("$it/Slots/Qty", Kind.NUMBER))
            },
            rules = listOf(
                RuleDef("$root/Payload", root, "Sum(Depots/Bins/Slots*/Qty For \"b\") > 0", "Payload", "../Depots/Bins/Slots/Qty"),
                RuleDef("$root/Key", root, "Sum(Catalog/Slots*/Qty For Depots/Wanted) > 0", "Key", "../Depots/Wanted"),
            ),
            repeatableGroups = setOf(depots, bins, "$bins/Slots", catalog, "$catalog/Slots"),
            declaredReps = mapOf(depots to 2, bins to 3, "$bins/Slots" to 2, catalog to 3, "$catalog/Slots" to 2),
            indexFields = setOf("$bins/Code", "$catalog/Code"),
            groupPaths = setOf(root, depots, bins, "$bins/Slots", catalog, "$catalog/Slots"),
        )
        val interpreter = DmInterpreter(model)
        assertEquals(emptyList(), interpreter.supportAudit())
        for (active in 1..2) {
            val rows = (1..2).joinToString(",") { row ->
                val value = if (row == active) 1 else 0
                """{"Wanted":"${if (row == active) "b" else "a"}","Bins":[{"Code":"a"},{"Code":"b","Slots":[{"Qty":$value},{"Qty":0}]}]}"""
            }
            val document = model.readDocumentJson("""{"Store":{"Depots":[$rows],"Catalog":[{"Code":"a","Slots":[{"Qty":0},{"Qty":0}]},{"Code":"b","Slots":[{"Qty":1},{"Qty":0}]}]}}""")
            assertEquals(listOf("Key|/Store[1]/Depots[$active]/Wanted", "Payload|/Store[1]/Depots[$active]/Bins[2]/Slots[1]/Qty"),
                interpreter.validateFull(document).map { "${it.code}|${it.errorPath}" }.sorted())
        }
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
