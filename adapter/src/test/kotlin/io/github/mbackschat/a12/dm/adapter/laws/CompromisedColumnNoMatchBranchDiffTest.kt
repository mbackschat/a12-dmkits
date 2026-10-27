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
 * A no-match read over an index column holding one empty cell, alone and beside a branch that decides on its own
 * (a12-kernel-lean SPEC-2026-10-26-28, [KERNEL-FINDINGS KF645]). Over a clean column the key no row carries reads
 * empty; over the compromised one the read is unknown, and the `Or` beside a true branch is the row that tells a
 * branch-scoped unknown from one that silences the whole rule. Every rule reads `Ref`, its error field, which every
 * document fills, so `NoMatchAndFiring` stands for the bare no-match. Every row is three-engine agreed.
 *
 * Own domain (an e-commerce order's lines, keyed by product code).
 */
class CompromisedColumnNoMatchBranchDiffTest {

    private val model: String = run {
        val base = DocumentModelDraft.named("order-line-keys").locale("en_US")
            .rootGroup("Order") { o -> o.stringField("Ref") { }
                .group("Lines") { l -> l.repeatable(5).indexField("Code")
                    .stringField("Code") { }.numberField("Amount") { it.fractionDigits(0) } } }
            .build().toJson()
        val session = EditSession.read(base)
        val order = session.group("/Order")
        val ref = order.field("Ref")
        val noMatch = order.field("Lines/Amount").semanticIndex("Z").notFilled()
        val rules: List<Pair<String, Condition>> = listOf(
            "NoMatchOrFiring" to noMatch.or(ref.filled()),
            "NoMatchOrSilent" to noMatch.or(ref.notFilled()),
            "NoMatchAndFiring" to noMatch.and(ref.filled()),
            "Firing" to ref.filled(),
        )
        for ((code, condition) in rules) order.addRule {
            it.name(code).code(code).errorField(ref).`when`(condition).message(Dsl.en("Check the order lines.")).build()
        }
        admitted(session.toJson())
    }

    @Test
    fun anEmptyIndexCellMakesTheNoMatchUnknownInItsOwnBranchOnly() {
        val observed = linkedMapOf(
            "clean column" to """{"Order":{"Ref":"R","Lines":[{"Code":"A","Amount":1},{"Code":"B","Amount":2}]}}""",
            "one empty index cell" to """{"Order":{"Ref":"R","Lines":[{"Code":"A","Amount":1},{"Amount":2}]}}""",
        ).mapValues { (_, document) -> fired(model, document) }
        assertEquals(linkedMapOf(
            "clean column" to listOf("Firing", "NoMatchAndFiring", "NoMatchOrFiring", "NoMatchOrSilent"),
            // The generated mandatory check states the empty index cell itself.
            "one empty index cell" to listOf("Firing", "NoMatchOrFiring", "mandatoryField"),
        ), observed)
    }
}
