package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Whether a value-list leaf supplies the presence guard a keyed group message parameter needs, the first
 * acceptance cell of a12-kernel-lean SPEC-2026-10-26-18 ([KERNEL-FINDINGS KF637]). It does not: a value-list
 * quantifier over the keyed cell is refused `INVALID_USE_OF_SEMANTIC_INDEX` on its own, and admitted only beside
 * a group-presence guard. The expected column is A12's consistency check; the checker must name the same class.
 *
 * Own domain (a store's depot bins).
 */
class KeyedMessageValueListGuardAgreementTest {

    private val base = DocumentModelDraft.named("store-keyed-value-list").locale("en_US")
        .rootGroup("Store") { s -> s.stringField("Ref") { }
            .group("Depots") { d -> d.repeatable(2).stringField("Note") { }
                .group("Bins") { b -> b.repeatable(4).indexField("BinCode")
                    .stringField("BinCode") { }.stringField("Label") { } } } }
        .build().toJson()

    @Test
    fun aValueListLeafSuppliesNoKeyedGuard() {
        val edit = EditSession.read(base)
        val depot = edit.group("/Store/Depots")
        val note = depot.str("Note")
        val bins = depot.flattenGroup("Bins").forIndex("b")
        val label = depot.field("Bins/Label").semanticIndex("b")
        val rows: List<Pair<String, Condition>> = listOf(
            "group presence" to bins.filled(),
            "value list over the keyed cell" to Dsl.atLeastOneFieldValueIncludedInValueList(listOf("x"),
                label.index(), note.field()),
            "value list beside group presence" to bins.filled().and(Dsl.atLeastOneFieldValueIncludedInValueList(
                listOf("x"), label.index(), note.field())),
        )
        val observed = rows.associate { (id, condition) ->
            val json = ModelWriter.addRule(base, depot.rule {
                it.name("MessageProbe").code("MESSAGE_PROBE").errorField(note.field())
                    .`when`(note.filled().and(condition)).message(Dsl.en("Bin " + Dsl.repetitionIndex(bins))).build()
            })
            id to (MessageParameterProbes.kernel(json) to MessageParameterProbes.portable(json))
        }
        val admitted = emptyList<String>() to emptyList<String>()
        assertEquals(mapOf(
            "group presence" to admitted,
            "value list over the keyed cell" to (listOf("INVALID_USE_OF_SEMANTIC_INDEX") to listOf("INVALID_USE_OF_SEMANTIC_INDEX")),
            "value list beside group presence" to admitted,
        ), observed, "as (A12's classes, the checker's)")
    }
}
