package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.ThreeEngineRuns.admitted
import io.github.mbackschat.a12.dm.adapter.laws.ThreeEngineRuns.fired
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Which row a keyed read's message reference names at a repeatable level below the keyed group that the rule's
 * own iteration binds (a12-kernel-lean SPEC-2026-10-28-02, [KERNEL-FINDINGS KF522]).
 *
 * Every rule here reports on a field of the repeatable `Bays`/`Slots` row and reads a field of that same row
 * through a key. A12 names the selected row at the keyed level and the FIRING row at the level below it, on a key
 * over the root's index, over a fixed group's index and over a repeatable group's: in the second bin's
 * selection, row 2 of the first bin references `Bins[2]/Slots[2]`. A level the rule does not iterate stays A12's
 * wildcard (`SelectedMessageReferenceDiffTest`). The documents also carry -02's selection: a key equal to the stored
 * index reads the unkeyed row, a different one reads empty, and an empty index fires neither the read nor its
 * absence and draws `mandatoryField`. Every document is three-engine agreed,
 * and the repeatable-index row is pinned on both kernel strategies because it is the one that separates "the
 * level the rule binds" from "a level below a non-repeatable keyed group".
 *
 * Own domain (a warehouse's sites and bays, a store's bins and slots).
 */
class RowBoundSelectedReferenceDiffTest {

    private val model: String = run {
        val base = DocumentModelDraft.named("warehouse-row-bound-keys").locale("en_US")
            .rootGroup("Site") { s -> s.indexField("SiteCode").stringField("SiteCode") { }.stringField("Note") { }
                .group("Floor") { f -> f.group("Bays") { b -> b.repeatable(3)
                    .numberField("Load") { it.fractionDigits(0) }.stringField("Label") { } } } }
            .rootGroup("Depot") { d -> d.stringField("Note") { }
                .group("Floor") { f -> f.indexField("FloorCode").stringField("FloorCode") { }
                    .group("Bays") { b -> b.repeatable(3)
                        .numberField("Load") { it.fractionDigits(0) }.stringField("Label") { } } } }
            .rootGroup("Store") { s -> s.stringField("Ref") { }
                .group("Bins") { b -> b.repeatable(4).indexField("BinCode").stringField("BinCode") { }
                    .group("Slots") { sl -> sl.repeatable(3)
                        .numberField("Qty") { it.fractionDigits(0) }.stringField("SlotNote") { } } } }
            .build().toJson()
        val session = EditSession.read(base)
        /** A keyed read of [value] in the row, and its absence, each reported on the row's [label]. */
        fun addKeyedRules(prefix: String, group: String, value: String, label: String, key: String) {
            val row = session.group(group)
            val note = row.str(label)
            val rules: List<Pair<String, Condition>> = listOf(
                "${prefix}Read" to note.filled().and(row.num(value).forIndex(key).gt(0)),
                "${prefix}Missing" to note.filled().and(row.field(value).semanticIndex(key).notFilled()),
            )
            for ((code, condition) in rules) row.addRule {
                it.name(code).code(code).errorField(note.field()).`when`(condition).message(Dsl.en("Check the row.")).build()
            }
        }
        addKeyedRules("Site", "/Site/Floor/Bays", "Load", "Label", "W1")
        addKeyedRules("Depot", "/Depot/Floor/Bays", "Load", "Label", "F1")
        addKeyedRules("Store", "/Store/Bins/Slots", "Qty", "SlotNote", "b")
        admitted(session.toJson())
    }

    private fun bays(vararg loads: Int) = loads.joinToString(",", "[", "]") { """{"Load":$it,"Label":"L$it"}""" }

    private val documents = linkedMapOf(
        "the site's key matches" to """{"Site":{"SiteCode":"W1","Note":"n","Floor":{"Bays":${bays(1, 2)}}}}""",
        "the site's key differs" to """{"Site":{"SiteCode":"W2","Note":"n","Floor":{"Bays":${bays(1, 2)}}}}""",
        "the floor's key matches" to """{"Depot":{"Note":"n","Floor":{"FloorCode":"F1","Bays":${bays(1, 2)}}}}""",
        "the floor's key differs" to """{"Depot":{"Note":"n","Floor":{"FloorCode":"F2","Bays":${bays(1, 2)}}}}""",
        "the site's key is empty" to """{"Site":{"Note":"n","Floor":{"Bays":${bays(1, 2)}}}}""",
        "the floor's key is empty" to """{"Depot":{"Note":"n","Floor":{"Bays":${bays(1, 2)}}}}""",
        "another bin selected" to SELECTING_ANOTHER_BIN,
        "the firing bin selected" to """{"Store":{"Ref":"r","Bins":[{"BinCode":"a","Slots":[{"Qty":1}]},""" +
            """{"BinCode":"b","Slots":[{"Qty":3,"SlotNote":"x"},{"Qty":4,"SlotNote":"y"}]}]}}""",
    )

    @Test
    fun allThreeEnginesAgreeOnEveryDocument() {
        DocumentDifferential.assertAgree(model, documents)
    }

    @Test
    fun aKeyOverARootOrFixedIndexSelectsThatGroupsOneInstance() {
        val observed = documents.filterKeys { it.startsWith("the site") || it.startsWith("the floor") }
            .mapValues { (_, document) -> fired(model, document) }
        fun selection(carrier: String) = listOf(listOf("${carrier}Read", "${carrier}Read"),
            listOf("${carrier}Missing", "${carrier}Missing"), listOf("mandatoryField"))
        val (siteMatch, siteOther, siteEmpty) = selection("Site")
        val (floorMatch, floorOther, floorEmpty) = selection("Depot")
        assertEquals(linkedMapOf(
            "the site's key matches" to siteMatch, "the site's key differs" to siteOther,
            "the floor's key matches" to floorMatch, "the floor's key differs" to floorOther,
            "the site's key is empty" to siteEmpty, "the floor's key is empty" to floorEmpty,
        ), observed)
    }

    @Test
    fun theFiringRowNamesTheLevelBelowARepeatableKeyedGroup() {
        val observed = listOf(true, false).map { dynamic ->
            DocumentDifferential.kernel(model, SELECTING_ANOTHER_BIN, dynamic).messages
                .filter { it.toString().startsWith("StoreRead|") }
                .map { message -> message.referenced.filter { it.endsWith("/Qty") } }
        }
        val expected = listOf(listOf("/Store[1]/Bins[2]/Slots[1]/Qty"), listOf("/Store[1]/Bins[2]/Slots[2]/Qty"))
        assertEquals(List(2) { expected }, observed, "the selected bin's slot at the firing row, on both strategies")
    }

    private companion object {
        val SELECTING_ANOTHER_BIN = """{"Store":{"Ref":"r","Bins":[""" +
            """{"BinCode":"a","Slots":[{"Qty":1,"SlotNote":"x"},{"Qty":2,"SlotNote":"y"}]},""" +
            """{"BinCode":"b","Slots":[{"Qty":3},{"Qty":4},{"Qty":5}]}]}}"""
    }
}
