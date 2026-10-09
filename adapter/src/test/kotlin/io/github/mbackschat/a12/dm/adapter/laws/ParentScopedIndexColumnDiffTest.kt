package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Which index column a keyed read inside a repeatable PARENT belongs to (a12-kernel-lean SPEC-2026-10-07-01). Each
 * depot keys its own bins, so the column is the enclosing depot's: equal keys in two depots do not collide, and a
 * duplicate in one depot poisons that depot's keyed computation while the other depot's computes. A validation
 * rule over the same read separates the per-lookup arm, which stays silent on the duplicate it cannot resolve.
 *
 * Own domain (a store's depots and their keyed bins).
 */
class ParentScopedIndexColumnDiffTest {

    private val model: String = run {
        val draft = DocumentModelDraft.named("store-parent-index").locale("en_US")
            .rootGroup("Store") { s -> s
                .stringField("Ref") { }
                .group("Depots") { d -> d.repeatable(3)
                    .stringField("Wanted") { }
                    .numberField("Picked") { it.fractionDigits(0) }
                    .group("Bins") { b -> b.repeatable(4).indexField("BinCode")
                        .stringField("BinCode") { }
                        .numberField("Stock") { it.fractionDigits(0) } } } }
            .build().toJson()
        val session = EditSession.read(draft)
        val depot = session.group("/Store/Depots")
        val wanted = depot.field("Wanted")
        val stock = session.group("/Store/Depots/Bins").num("Stock")
        val picked = depot.num("Picked")
        var json = session.toJson()
        json = TypedRules.inject(json, depot.computation(picked) {
            it.name("PickedComp").calc(stock.forIndex(wanted)).message(Dsl.en("Check the depot.")).build()
        })
        json = TypedRules.inject(json, depot.rule {
            it.name("StockLow").code("StockLow").errorField(depot.field("Wanted"))
                .`when`(stock.forIndex(wanted).lt(5)).message(Dsl.en("Check the depot.")).build()
        })
        json
    }

    private fun bin(code: String, stock: String) = """{"BinCode":"$code","Stock":"$stock"}"""

    private fun depot(wanted: String, vararg bins: String) =
        """{"Wanted":"$wanted","Picked":"9","Bins":[${bins.joinToString(",")}]}"""

    private fun store(vararg depots: String) = """{"Store":{"Ref":"S-1","Depots":[${depots.joinToString(",")}]}}"""

    private val documents = linkedMapOf(
        "one key in two depots" to store(depot("a", bin("a", "3")), depot("a", bin("a", "7"))),
        "a duplicate in the first depot" to
            store(depot("a", bin("a", "3"), bin("a", "4")), depot("a", bin("a", "7"))),
        "a duplicate in the second depot" to
            store(depot("a", bin("a", "3")), depot("a", bin("a", "7"), bin("a", "8"))),
        "a duplicate of another key in the first depot" to
            store(depot("a", bin("a", "3"), bin("b", "1"), bin("b", "2")), depot("a", bin("a", "7"))),
        "an empty key cell in the first depot" to
            store(depot("a", bin("a", "3"), """{"Stock":"1"}"""), depot("a", bin("a", "7"))),
    )

    @Test
    fun theModelIsKernelConsistent() {
        assertEquals(emptyList<String>(), MessageParameterProbes.kernel(model))
    }

    @Test
    fun allThreeEnginesAgreeOnEveryDocument() {
        DocumentDifferential.assertAgree(model, documents)
    }

    /** The separating rows pinned against the normative kernel, so the agreement above cannot pass vacuously. */
    @Test
    fun aDuplicatePoisonsOnlyItsOwnDepot() {
        val observed = documents.filterKeys { it.startsWith("a duplicate in") }.mapValues { (_, json) ->
            DocumentDifferential.kernel(model, json, dynamic = true).outcomes
                .filterKeys { it.endsWith("Picked") }
        }
        assertEquals(mapOf(
            "a duplicate in the first depot" to mapOf("Depots/Picked" to "CLEARED", "Depots[2]/Picked" to "VALUE|7"),
            "a duplicate in the second depot" to mapOf("Depots/Picked" to "VALUE|3", "Depots[2]/Picked" to "CLEARED"),
        ), observed)
    }
}
