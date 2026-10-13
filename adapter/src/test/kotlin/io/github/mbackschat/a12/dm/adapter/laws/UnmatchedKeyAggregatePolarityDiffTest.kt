package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A key that selects no row leaves a starred list below it fillable, so a fired comparison over its `Sum` is an
 * omission (KERNEL-FINDINGS KF554, a12-kernel-lean SPEC-2026-10-13-07). The computation's own generated check
 * meets the stale stored total as `[Total] != Sum(...)`, and an authored rule compares the same sum with a stored
 * expectation.
 *
 * Own domain (a warehouse's bins, each keyed by its code, holding shelves of packed items).
 */
class UnmatchedKeyAggregatePolarityDiffTest {

    private val model: String = run {
        val session = EditSession.read(DocumentModelDraft.named("warehouse-keyed-sum").locale("en_US")
            .rootGroup("Warehouse") { w -> w
                .stringField("Pick") { it.lineBreaks() }.numberField("Total") { it.fractionDigits(0) }
                .numberField("Expected") { it.fractionDigits(0) }
                .group("Bins") { b -> b.repeatable(4).indexField("Code").stringField("Code") { it.lineBreaks() }
                    .group("Shelf") { s -> s.group("Items") { i -> i.repeatable(3)
                        .numberField("Qty") { it.fractionDigits(0) } } } } }
            .build().toJson())
        val warehouse = session.group("/Warehouse")
        val picked = warehouse.flatten("Bins/Shelf/Items*/Qty").forIndex(warehouse.field("Pick"))
        warehouse.addComputation(warehouse.num("Total")) {
            it.name("PickTotal").calc(Dsl.sum(picked)).message(Dsl.en("Check the pick total.")).build()
        }
        warehouse.addRule {
            it.name("PickMismatch").code("PICK_MISMATCH").errorField(warehouse.field("Expected"))
                .`when`(warehouse.num("Expected").filled().and(Dsl.sum(picked).ne(warehouse.num("Expected"))))
                .message(Dsl.en("The pick differs from the expectation.")).build()
        }
        session.toJson()
    }

    private fun warehouse(pick: String?) = """{"Warehouse":{""" + listOfNotNull(pick?.let { "\"Pick\":$it" },
        "\"Total\":99", "\"Expected\":7",
        """"Bins":[{"Code":"B1","Shelf":{"Items":[{"Qty":2},{"Qty":5}]}},{"Code":"B2"},""" +
            """{"Code":"B3","Shelf":{"Items":[{"Qty":1},{"Qty":2},{"Qty":4}]}}]""").joinToString(",") + "}}"

    private val documents = linkedMapOf(
        "a key a bin carries" to warehouse("\"B1\""),
        "a key a full bin carries" to warehouse("\"B3\""),
        "a key no bin carries" to warehouse("\"B9\""),
        "a key differing by a lone carriage return" to warehouse("\"B1\\r\""),
        "an empty key" to warehouse("\"\""),
        "no key" to warehouse(null),
        "a bin holding no items" to warehouse("\"B2\""),
    )

    @Test
    fun theModelIsKernelConsistent() {
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(model))
    }

    @Test
    fun allThreeEnginesAgreeOnEveryDocument() {
        DocumentDifferential.assertAgree(model, documents)
    }

    /**
     * The typing, per document. Only a selected bin whose item level is full is closed, so its stale total is a
     * value error; an unmatched, empty, absent or carriage-return-differing key selects nothing and stays fillable,
     * as does a selected bin with room for more items.
     */
    @Test
    fun anUnmatchedSelectionKeepsTheStarredSumFillable() {
        val omission = listOf("PICK_MISMATCH|OMISSION_ERROR", "PickTotal|OMISSION_ERROR")
        val expected = mapOf(
            "a key a bin carries" to listOf("PickTotal|OMISSION_ERROR"),
            "a key a full bin carries" to listOf("PickTotal|VALUE_ERROR"),
            "a key no bin carries" to omission,
            "a key differing by a lone carriage return" to omission,
            "an empty key" to omission,
            "no key" to omission,
            "a bin holding no items" to omission,
        )
        for ((document, json) in documents) assertEquals(expected.getValue(document),
            DocumentDifferential.kernelMessages(model, json, true).map { "${it.code}|${it.type}" }.sorted(), document)
    }
}
