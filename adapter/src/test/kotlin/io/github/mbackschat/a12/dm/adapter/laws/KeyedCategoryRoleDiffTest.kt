package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * A keyed category reads the row its key selects in every role, not only in a comparison: a value-list member, a
 * concatenation and `FieldValueAsNumber`, keyed category or keyed field (KERNEL-FINDINGS KF568). The keyed row is put
 * first and then second, so a reading of the first row or of any row is told apart from the keyed one; a key no row
 * carries, an empty and an undeclared keyed cell, and a duplicated key are the further cases. All three engines agree
 * on every document, message class and fill reads included, and the fired rules are pinned.
 *
 * Own domain (an order's lines, keyed by SKU).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KeyedCategoryRoleDiffTest {

    private val model: String = run {
        val draft = DocumentModelDraft.named("order-keyed-category-roles").locale("en_US")
            .rootGroup("Order") { o ->
                o.numberField("Total") { it.fractionDigits(0) }
                o.stringField("Pick") { }
                o.group("Lines") { l -> l.repeatable(3).indexField("Sku")
                    .stringField("Sku") { }
                    .enumField("Grade") { e -> e.value("OPEN").value("SHIPPED").category("Tier", "High", "Low") }
                    .enumField("Band") { e -> e.value("B1").value("B2").category("Rank", "1", "2") }
                    .enumField("Level") { e -> e.value("1").value("2") } }
            }.build().toJson()
        val session = EditSession.read(draft)
        val order = session.group("/Order")
        val total = order.num("Total")
        val tier = order.enumOf("Lines/Grade").category("Tier").forIndex("a")
        val rank = order.enumOf("Lines/Band").category("Rank").forIndex("a")
        val rules: Map<String, Condition> = linkedMapOf(
            "ValueListRule" to Dsl.atLeastOneFieldValueIncludedInValueList(listOf("High"), tier),
            "ConcatenationRule" to order.str("Pick").asString().eq(tier.plus("-x")),
            "AsNumberRule" to Dsl.fieldValueAsNumber(rank).gt(1),
            "AsNumberZeroRule" to Dsl.fieldValueAsNumber(rank).lt(1),
        )
        var json = session.toJson()
        for ((name, condition) in rules) {
            json = TypedRules.inject(json, order.rule {
                it.name(name).code(name).errorField(total.field()).`when`(total.filled().and(condition))
                    .message(Dsl.en("Check line a.")).build()
            })
        }
        // A keyed direct read has no typed form yet (DEV-API-GAPS DG158), so it is written as text.
        RuntimeLaws.withRule(json, "/Order", "DirectAsNumberRule", "../Total", "DirectAsNumberRule",
            "FieldFilled(Total) And FieldValueAsNumber(Lines/Level For \"a\") > 1")
    }

    private fun order(pick: String, vararg lines: String) =
        """{"Order":{"Total":1,"Pick":"$pick","Lines":[${lines.joinToString(",")}]}}"""

    private val keyed = """{"Sku":"a","Grade":"OPEN","Band":"B2","Level":"2"}"""
    private val other = """{"Sku":"b","Grade":"SHIPPED","Band":"B1"}"""
    private val keyedLow = """{"Sku":"a","Grade":"SHIPPED","Band":"B1"}"""
    private val otherHigh = """{"Sku":"b","Grade":"OPEN","Band":"B2"}"""

    private val documents = linkedMapOf(
        "keyed row first" to order("High-x", keyed, other),
        "keyed row second" to order("High-x", other, keyed),
        "keyed row low, other high" to order("Low-x", otherHigh, keyedLow),
        "no keyed row" to order("High-x", otherHigh),
        "keyed row without a band" to order("High-x", """{"Sku":"a","Grade":"OPEN"}""", otherHigh),
        "keyed row with an undeclared band" to order("High-x", """{"Sku":"a","Grade":"OPEN","Band":"B9"}""", otherHigh),
        "two rows keyed a" to order("High-x", keyed, keyed),
    )

    @Test
    fun allThreeEnginesAgreeOnEveryDocument() {
        DocumentDifferential.assertAgree(model, documents)
    }

    /**
     * The fired codes on the kernel's dynamic strategy. Both row orders fire the same rules, so the read is the keyed
     * row's and not the first row's; a key no row carries and an empty keyed cell read `0`, and an undeclared stored
     * value leaves the read unavailable beside its own formal error. A duplicated key fires only the uniqueness
     * check: every keyed read is undecidable there.
     */
    @Test
    fun everyRoleReadsTheKeyedRow() {
        val fired = documents.mapValues { (_, doc) ->
            DocumentDifferential.kernel(model, doc, dynamic = true).messages.map { it.code }.sorted()
        }
        val all = listOf("AsNumberRule", "ConcatenationRule", "DirectAsNumberRule", "ValueListRule")
        assertEquals(linkedMapOf(
            "keyed row first" to all,
            "keyed row second" to all,
            "keyed row low, other high" to listOf("ConcatenationRule"),
            "no keyed row" to listOf("AsNumberZeroRule"),
            "keyed row without a band" to listOf("AsNumberZeroRule", "ConcatenationRule", "ValueListRule"),
            "keyed row with an undeclared band" to listOf("ConcatenationRule", "ValueListRule", "stringFalschesMuster"),
            "two rows keyed a" to listOf("uniqueIndex", "uniqueIndex"),
        ), fired)
    }

    @Test
    fun theModelIsKernelConsistent() {
        assertEquals(emptyList<String>(), MessageParameterProbes.kernel(model))
    }
}
