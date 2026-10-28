package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.ThreeEngineRuns.admitted
import io.github.mbackschat.a12.dm.adapter.laws.ThreeEngineRuns.signatures
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * What a key over a repeatable index reads at a lower level the rule iterates, when the selected row lacks it
 * (a12-kernel-lean SPEC-2026-10-28-07, [KERNEL-FINDINGS KF475]).
 *
 * Every rule reports on the shelf, so it iterates `Shelves`, and keys store `s2`. The key selects store `s2` at the
 * firing shelf's row: a shelf the selected store lacks is a clean no-match, so a starred `Sum` below it counts
 * nothing and fires its `== 0` twin, and a uniqueness test against the missing partner stays silent. A shelf of
 * the selected store compares with itself, so its own row is a duplicate. Every document is three-engine agreed on
 * every channel; the short store's firings are pinned.
 *
 * Own domain (an e-commerce shop's stores, their shelves, and the items on each shelf).
 */
class KeyedLowerRowDiffTest {

    private val model: String = run {
        val base = DocumentModelDraft.named("shop-keyed-lower-row").locale("en_US")
            .rootGroup("Shop") { s -> s.stringField("Ref") { }
                .group("Stores") { st -> st.repeatable(4).indexField("StoreCode").stringField("StoreCode") { }
                    .group("Shelves") { sh -> sh.repeatable(4)
                        .stringField("ShelfNote") { }.stringField("Label") { }.dateField("Shipped") { }
                        .group("Items") { i -> i.repeatable(4).numberField("Qty") { it.fractionDigits(0) } } } } }
            .build().toJson()
        admitted(RULES.entries.fold(base) { json, (code, clause) ->
            RuntimeLaws.withRule(json, "/Shop/Stores/Shelves", code, "../ShelfNote", code,
                "FieldFilled(ShelfNote) And $clause")
        })
    }

    @Test
    fun allThreeEnginesAgreeOnEveryDocument() {
        DocumentDifferential.assertAgree(model, DOCUMENTS)
    }

    @Test
    fun aShelfTheSelectedStoreLacksIsANoMatch() {
        fun shelf(store: Int, shelf: Int) = "/Shop[1]/Stores[$store]/Shelves[$shelf]/ShelfNote"
        assertEquals(
            listOf(
                "DateDup|VALUE_ERROR|${shelf(1, 1)}", "DateDup|VALUE_ERROR|${shelf(2, 1)}",
                "LabelDup|VALUE_ERROR|${shelf(1, 1)}", "LabelDup|VALUE_ERROR|${shelf(2, 1)}",
                "SumPositive|OMISSION_ERROR|${shelf(1, 1)}", "SumPositive|OMISSION_ERROR|${shelf(2, 1)}",
                "SumZero|OMISSION_ERROR|${shelf(1, 2)}", "SumZero|OMISSION_ERROR|${shelf(1, 3)}",
            ),
            signatures(model, DOCUMENTS.getValue("the selected store has one shelf")),
        )
    }

    private companion object {
        val RULES = linkedMapOf(
            "SumPositive" to "Sum(Items*/Qty For \"s2\") > 0",
            "SumZero" to "Sum(Items*/Qty For \"s2\") == 0",
            "LabelDup" to "FieldValuesNotUnique(Label, Label For \"s2\")",
            "DateDup" to "FieldValuesNotUnique(Shipped, Shipped For \"s2\")",
        )

        fun shelf(label: String, day: Int, vararg qtys: Int) = """{"ShelfNote":"n","Label":"$label",""" +
            """"Shipped":"2026-03-0$day","Items":[${qtys.joinToString(",") { """{"Qty":$it}""" }}]}"""

        fun store(code: String, vararg shelves: String) = """{"StoreCode":"$code","Shelves":[${shelves.joinToString(",")}]}"""

        fun shop(vararg stores: String) = """{"Shop":{"Ref":"r","Stores":[${stores.joinToString(",")}]}}"""

        val FIRST = store("s1", shelf("L1", 1, 1, 2), shelf("L2", 2, 3), shelf("L3", 3, 4))

        val DOCUMENTS = linkedMapOf(
            "the selected store has every shelf" to
                shop(FIRST, store("s2", shelf("L1", 1, 5), shelf("L2", 2, 6), shelf("L3", 3, 7))),
            "the selected store has one shelf" to shop(FIRST, store("s2", shelf("L1", 1, 5))),
            "the selected store's shelves differ" to shop(FIRST, store("s2", shelf("L9", 9, 0), shelf("L8", 8))),
            "no store carries the key" to shop(FIRST, store("s9", shelf("L1", 1, 5))),
            "two stores carry the key" to shop(FIRST, store("s2", shelf("L1", 1, 5)), store("s2", shelf("L2", 2, 6))),
        )
    }
}
