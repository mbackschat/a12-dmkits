package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * A rule whose error field lies in a repeatable group BELOW the one its condition iterates keeps the parent's
 * iteration and reports at the child's first coordinate, whether that child row exists or not; the child's capacity
 * does not widen what the rule iterates (a12-kernel-lean SPEC-2026-10-09-08). Both parent rules name the error field
 * through a child star, since A12 refuses a condition that does not name it (`MVK_ERROR_FIELD_NOT_REFERENCED`): one
 * fires on a depot without bins, one on a `Sum` over its bins. A rule iterating the child itself is the exact-scope
 * control.
 *
 * Own domain (a warehouse's depots and their bins).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeeperErrorFieldIterationDiffTest {

    private val model: String = run {
        val draft = DocumentModelDraft.named("warehouse-bins").locale("en_US")
            .rootGroup("Store") { s ->
                s.group("Depots") { d -> d.repeatable(3)
                    .numberField("Wanted") { it.fractionDigits(0) }
                    .group("Bins") { b -> b.repeatable(4).numberField("Stock") { it.fractionDigits(0) } } }
            }.build().toJson()
        val session = EditSession.read(draft)
        val depots = session.group("/Store/Depots")
        val bins = session.group("/Store/Depots/Bins")
        val wanted = depots.num("Wanted")
        val stock = bins.num("Stock")
        var json = session.toJson()
        json = TypedRules.inject(json, depots.rule {
            it.name("EmptyDepot").code("EmptyDepot").errorField(stock.field())
                .`when`(wanted.filled().and(wanted.gt(5)).and(Dsl.noFieldFilled(depots.flatten("Bins*/Stock"))))
                .message(Dsl.en("Check the depot.")).build()
        })
        json = TypedRules.inject(json, depots.rule {
            it.name("BinsShort").code("BinsShort").errorField(stock.field())
                .`when`(wanted.filled().and(Dsl.sum(depots.flatten("Bins*/Stock")).lt(wanted.asNumber())))
                .message(Dsl.en("Check the depot.")).build()
        })
        json = TypedRules.inject(json, bins.rule {
            it.name("StockLow").code("StockLow").errorField(stock.field())
                .`when`(stock.filled().and(stock.lt(2))).message(Dsl.en("Check the bin.")).build()
        })
        json
    }

    private val documents = linkedMapOf(
        "three depots, the second without bins" to """{"Store":{"Depots":[""" +
            """{"Wanted":9,"Bins":[{"Stock":1},{"Stock":3}]},{"Wanted":9},{"Wanted":2,"Bins":[{"Stock":5}]}]}}""",
        "one depot whose second bin is low" to """{"Store":{"Depots":[{"Wanted":9,"Bins":[{"Stock":4},{"Stock":1}]}]}}""",
    )

    @Test
    fun allThreeEnginesAgreeOnEveryDocument() {
        DocumentDifferential.assertAgree(model, documents)
    }

    /**
     * Each message's code and the depot and bin coordinates of its pointer, on the kernel's dynamic strategy. The
     * `Sum` over a depot without bins folds to the fillable 0 (KERNEL-SEMANTICS §2), so that depot fires both
     * parent rules, each at bin 1 although no bin exists.
     */
    @Test
    fun aDeeperErrorFieldKeepsTheParentsIterationAtTheFirstChild() {
        val observed = documents.mapValues { (_, doc) ->
            DocumentDifferential.kernel(model, doc, dynamic = true).messages
                .map { it.code + "@" + it.pointer.substringAfter("Depots[").substringBefore("]") + "/" +
                    it.pointer.substringAfter("Bins[").substringBefore("]") }
                .sorted().joinToString()
        }
        assertEquals(linkedMapOf(
            "three depots, the second without bins" to "BinsShort@1/1, BinsShort@2/1, EmptyDepot@2/1, StockLow@1/1",
            "one depot whose second bin is low" to "BinsShort@1/1, StockLow@1/2",
        ), observed)
    }

    @Test
    fun theCheckerIsSilentOnTheModelA12Admits() {
        assertEquals(emptyList<String>(), check(ModelSource(model)).findings.filter { it.justifiesRejection }.map { it.code })
    }
}
