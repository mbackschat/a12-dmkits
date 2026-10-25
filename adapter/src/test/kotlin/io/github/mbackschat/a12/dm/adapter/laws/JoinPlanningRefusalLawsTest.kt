package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.edit.GroupEditor
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.ErrorEntity
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The A12 refusals behind the interpreter's two join-planning declines (INTERPRETER-FINDINGS IF432), on the shape of
 * its kernel-free fixture `JoinPlanningShapeTest`. The interpreter declines rather than evaluates these because A12
 * refuses each at the model, so no runtime answer exists to match; this pins that the refusal holds for every
 * written order and for the inner keyed group being the deepest, not only for the order the fixture first used.
 *
 * Own domain: an order whose keyed lines hold parts and keyed sub-lines, beside keyed picks holding bins, an
 * unkeyed notes list holding pages, and a desk with keyed calls.
 */
class JoinPlanningRefusalLawsTest {

    private val base = DocumentModelDraft.named("order-join-planning").locale("en_US")
        .rootGroup("Order") { o -> o
            .group("Lines") { l -> l.repeatable(5).indexField("LineNo")
                .stringField("LineNo") { }
                .group("Parts") { p -> p.repeatable(5).numberField("Count") { it.fractionDigits(0) } }
                .group("Subs") { s -> s.repeatable(3).indexField("LineNo")
                    .stringField("LineNo") { }
                    .stringField("Note") { } } }
            .group("Picks") { p -> p.repeatable(5).indexField("LineNo")
                .stringField("LineNo") { }
                .group("Bins") { b -> b.repeatable(3).numberField("Qty") { it.fractionDigits(0) } } }
            .group("Notes") { n -> n.repeatable(5)
                .group("Pages") { p -> p.repeatable(3).numberField("Words") { it.fractionDigits(0) } } }
            .group("Desk") { d -> d
                .group("Calls") { c -> c.repeatable(4).indexField("LineNo")
                    .stringField("LineNo") { }
                    .stringField("Topic") { } } } }
        .build().toJson()

    private val session = EditSession.read(base)
    private val order = session.group("/Order")
    private val desk = session.group("/Order/Desk")
    private val lines = order.group("Lines")
    private val topic = order.str("Desk/Calls/Topic")
    private val note = order.str("Lines/Subs/Note")
    private val pickLine = order.str("Picks/LineNo")
    private val parts = Dsl.sum(order.flatten("Lines/Parts*/Count")).gt(0)

    private fun rule(at: GroupEditor, name: String, entity: ErrorEntity, condition: Condition): Rule =
        at.rule { it.name(name).errorEntity(entity).`when`(condition).code(name).message(Dsl.en(name)).build() }

    private fun verdict(rule: Rule): List<String> =
        RuntimeLaws.consistencyMessages(ModelWriter.addRule(base, rule)).mapNotNull { CODE.find(it)?.value }.distinct()

    @Test
    fun a12RefusesEveryShapeTheInterpreterDeclines() {
        val keyedTie = Dsl.sum(order.flatten("Picks/Bins*/Qty")).gt(0)
        val unkeyedTie = Dsl.sum(order.flatten("Notes/Pages*/Words")).gt(0)
        val verdicts = linkedMapOf(
            "tie with a keyed sibling" to rule(order, "TieKeyed", lines.ref(),
                Dsl.groupFilled(lines).and(parts).and(keyedTie)),
            "tie with a keyed sibling, reversed" to rule(order, "TieKeyedReversed", lines.ref(),
                Dsl.groupFilled(lines).and(keyedTie).and(parts)),
            "tie with an unkeyed sibling" to rule(order, "TieUnkeyed", lines.ref(),
                Dsl.groupFilled(lines).and(parts).and(unkeyedTie)),
            "keyed group inside a joined group" to rule(desk, "Nested", topic.field(), topic.filled().and(note.filled())),
            "keyed group inside a joined group, reversed" to rule(desk, "NestedReversed", topic.field(),
                note.filled().and(topic.filled())),
            "inner keyed group deepest" to rule(desk, "Deepest", topic.field(), pickLine.filled().and(note.filled())),
        ).mapValues { (_, r) -> verdict(r) }
        assertEquals(linkedMapOf(
            "tie with a keyed sibling" to listOf("MVK_ERROR_FIELD_NO_INDEX"),
            "tie with a keyed sibling, reversed" to listOf("MVK_ERROR_FIELD_NO_INDEX"),
            "tie with an unkeyed sibling" to listOf("MVK_NO_WILDCARD"),
            "keyed group inside a joined group" to listOf("MVK_MULTIPLE_INDEX_FIELDS"),
            "keyed group inside a joined group, reversed" to listOf("MVK_MULTIPLE_INDEX_FIELDS"),
            "inner keyed group deepest" to listOf("MVK_MULTIPLE_INDEX_FIELDS"),
        ), verdicts)
    }

    private companion object {
        private val CODE = Regex("MVK_[A-Z0-9_]+")
    }
}
