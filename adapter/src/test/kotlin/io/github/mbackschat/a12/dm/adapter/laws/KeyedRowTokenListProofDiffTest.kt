package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Whether a list quantifier proves a keyed row token only through every operand, as it does for the unkeyed row token
 * (KERNEL-FINDINGS KF675, KF546 corrected 2026-11-05). `SelectedMessageReferenceDiffTest`'s grid pairs the keyed
 * operand with `Note`, outside the keyed row, in every list-quantifier row, so it cannot tell "a list quantifier never
 * proves a keyed row" from "only when an operand lies outside it". These rows hold every operand under key `b`, two
 * fields or two sibling groups, with the mixed row re-measured beside them. A12 and the portable checker are both
 * pinned.
 *
 * Own domain (a store's depots and their keyed bins).
 */
class KeyedRowTokenListProofDiffTest {

    @Test
    fun aKeyedTokensListProofNeedsEveryOperandUnderTheKey() {
        val edit = EditSession.read(DocumentModelDraft.named("store-keyed-proof").locale("en_US")
            .rootGroup("Store") { s -> s
                .group("Depots") { d -> d.repeatable(2)
                    .stringField("Note") { }
                    .group("Signals") { g -> g.stringField("Ready") { } }
                    .group("Bins") { b -> b.repeatable(4).indexField("BinCode")
                        .stringField("BinCode") { }
                        .numberField("Stock") { it.fractionDigits(0) }
                        .group("Shelf") { sh -> sh.numberField("Aisle") { it.fractionDigits(0) } }
                        .group("Tag") { t -> t.stringField("Colour") { } } } } }
            .build().toJson())
        val depot = edit.group("/Store/Depots")
        val base = edit.toJson()
        val note = depot.str("Note")
        val stock = depot.num("Bins/Stock").field().semanticIndex("b")
        val aisle = depot.field("Bins/Shelf/Aisle").semanticIndex("b")
        val bins = depot.flattenGroup("Bins").forIndex("b")
        val shelf = depot.flattenGroup("Bins/Shelf").forIndex("b")
        val tag = depot.flattenGroup("Bins/Tag").forIndex("b")
        val signals = depot.flattenGroup("Signals")
        val rows = linkedMapOf(
            "any-fields, one operand outside the key" to Dsl.atLeastOneFieldFilled(stock.index(), note.field()),
            "any-fields, every operand under the key" to Dsl.atLeastOneFieldFilled(stock.index(), aisle.index()),
            "many-fields, every operand under the key" to Dsl.moreThanOneFieldFilled(stock.index(), aisle.index()),
            "any-groups, one operand outside the key" to Dsl.atLeastOneGroupFilled(bins, signals),
            "any-groups, every operand under the key" to Dsl.atLeastOneGroupFilled(shelf, tag),
        )
        val authored = rows.mapValues { (_, condition) ->
            ModelWriter.addRule(base, depot.rule {
                it.name("MessageProbe").code("MESSAGE_PROBE").errorField(note.field())
                    .`when`(note.filled().and(condition)).message(Dsl.en("Bin " + Dsl.repetitionIndex(bins))).build()
            })
        }
        val kernel = authored.mapValues { (_, json) -> MessageParameterProbes.kernel(json) }
        assertEquals(linkedMapOf<String, List<String>>(
            "any-fields, one operand outside the key" to listOf("INVALID_USE_OF_SEMANTIC_INDEX"),
            "any-fields, every operand under the key" to emptyList(),
            "many-fields, every operand under the key" to emptyList(),
            "any-groups, one operand outside the key" to listOf("INVALID_USE_OF_SEMANTIC_INDEX"),
            "any-groups, every operand under the key" to emptyList(),
        ), kernel, "A12")
        assertEquals(kernel, authored.mapValues { (_, json) -> MessageParameterProbes.portable(json) }, "portable")
    }
}
