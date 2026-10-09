package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * What a fired message references when its condition selects a row by semantic index (a12-kernel-lean
 * SPEC-2026-10-07-02). Each rule sits in a depot and reports on `Note`; the selection is a bin read by the depot's
 * `Wanted` key, a literal key, a group under the bin, or a starred list under it, and three rules put the selection
 * on an `Or` branch the evaluation never reaches. The differential compares the referenced and fill-to-fix pointers
 * of every message on both kernel strategies and the interpreter, across a match, a miss, an empty key, a
 * duplicated key and an invalid selected payload.
 *
 * Own domain (a store's depots and their keyed bins).
 */
class SelectedMessageReferenceDiffTest {

    private val model: String = run {
        val draft = DocumentModelDraft.named("store-selected-references").locale("en_US")
            .rootGroup("Store") { s -> s
                .stringField("Ref") { }
                .group("Depots") { d -> d.repeatable(2)
                    .stringField("Wanted") { }
                    .stringField("Note") { }
                    .group("Bins") { b -> b.repeatable(4).indexField("BinCode")
                        .stringField("BinCode") { }
                        .numberField("Stock") { it.fractionDigits(0) }
                        .group("Shelf") { sh -> sh
                            .numberField("Aisle") { it.fractionDigits(0) }
                            .numberField("Level") { it.fractionDigits(0) } }
                        .group("Slots") { sl -> sl.repeatable(3).numberField("Qty") { it.fractionDigits(0) } } } } }
            .build().toJson()
        val session = EditSession.read(draft)
        val depot = session.group("/Store/Depots")
        val note = depot.str("Note")
        val wanted = depot.field("Wanted")
        val stock = session.group("/Store/Depots/Bins").num("Stock")
        val shelf = depot.flattenGroup("Bins/Shelf").forIndex(wanted)
        val slots = depot.flatten("Bins/Slots*/Qty").forIndex(wanted)
        val rules: List<Pair<String, Condition>> = listOf(
            "RefDynamic" to note.filled().and(stock.forIndex(wanted).ge(0)),
            "RefLiteral" to note.filled().and(stock.forIndex("b").ge(0)),
            "RefGroup" to note.filled().and(Dsl.sum(shelf).ge(0)),
            "RefUnreached" to note.filled().or(stock.forIndex(wanted).gt(0)),
            "RefUnreachedGroup" to note.filled().or(Dsl.sum(shelf).gt(0)),
            "RefUnreachedStar" to note.filled().or(Dsl.sum(slots).gt(0)),
        )
        var json = session.toJson()
        for ((name, condition) in rules) {
            json = TypedRules.inject(json, depot.rule {
                it.name(name).code(name).errorField(note.field()).`when`(condition)
                    .message(Dsl.en("Check the depot.")).build()
            })
        }
        json
    }

    private fun bin(code: String, stock: String) =
        """{"BinCode":"$code","Stock":"$stock","Shelf":{"Aisle":"1","Level":"2"},"Slots":[{"Qty":"3"}]}"""

    private fun depot(wanted: String?, note: String?, vararg bins: String) = "{" + listOfNotNull(
        wanted?.let { "\"Wanted\":\"$it\"" }, note?.let { "\"Note\":\"$it\"" },
        "\"Bins\":[" + bins.joinToString(",") + "]",
    ).joinToString(",") + "}"

    private fun store(vararg depots: String) = """{"Store":{"Ref":"S-1","Depots":[${depots.joinToString(",")}]}}"""

    private val documents = linkedMapOf(
        "the first bin selected" to store(depot("a", "n", bin("a", "1"), bin("b", "2"))),
        "the second bin selected" to store(depot("b", "n", bin("a", "1"), bin("b", "2"))),
        "no bin carries the key" to store(depot("zz", "n", bin("a", "1"), bin("b", "2"))),
        "the key is empty" to store(depot(null, "n", bin("a", "1"), bin("b", "2"))),
        "the selected payload is invalid" to store(depot("a", "n", bin("a", "x"), bin("b", "2"))),
        "the note is empty" to store(depot("a", null, bin("a", "1"), bin("b", "2"))),
        "two depots select different bins" to
            store(depot("a", "n", bin("a", "1"), bin("b", "2")), depot("b", "n", bin("a", "4"), bin("b", "5"))),
    )

    @Test
    fun theModelIsKernelConsistent() {
        assertEquals(emptyList<String>(), MessageParameterProbes.kernel(model))
    }

    @Test
    fun allThreeEnginesAgreeOnEveryDocument() {
        DocumentDifferential.assertAgree(model, documents)
    }

    /**
     * Which row a DUPLICATED key's pointer names, pinned on both kernel strategies alone. The value reads refuse the
     * duplicate and their rules stay silent, while the unreached selections still name one row, and it is not the
     * first or the last matching one in general: A12 names the row its index cache was written with last, in the
     * iteration order of a hash map over the Document's cells (INTERPRETER-GAPS IG164). These two
     * rows happen to name the first match, which is why they are kept out of the agreement above.
     */
    @Test
    fun aDuplicatedKeysPointerIsTheKernelsCacheRow() {
        val duplicates = linkedMapOf(
            "rows 2 and 3" to (store(depot("b", "n", bin("a", "1"), bin("b", "2"), bin("b", "3"))) to 2),
            "rows 1 and 3" to (store(depot("b", "n", bin("b", "1"), bin("a", "2"), bin("b", "3"))) to 1),
        )
        val observed = duplicates.mapValues { (_, probe) ->
            listOf(true, false).map { dynamic ->
                DocumentDifferential.kernel(model, probe.first, dynamic).messages
                    .single { it.toString().startsWith("RefUnreached|") }.referenced
                    .filter { it.endsWith("/Stock") }
            }
        }
        val expected = duplicates.mapValues { (_, probe) ->
            List(2) { listOf("/Store[1]/Depots[1]/Bins[${probe.second}]/Stock") }
        }
        assertEquals(expected, observed, "the duplicated key's pointer row, on both strategies")
    }
}
