package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.MessageParameterProbes.kernel
import io.github.mbackschat.a12.dm.adapter.laws.MessageParameterProbes.portable
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A field key's read joins a message's parallel marking as an iterated read does (KERNEL-FINDINGS KF675). In
 * `FieldFilled(Gifts/Note For Picks/LineNo)` the key `Picks/LineNo` iterates the picks beside the rule's lines, so A12
 * marks both levels: the lines' index field becomes a member of the message, and a picks row token needs the
 * condition to prove a pick, which the key does not. The plain-read row is the control, where an unkeyed
 * `Picks/Ref` makes the same join. Against "a key read marks nothing", which refuses the lines' index field and
 * admits the picks token. Own domain: an order whose lines and picks are both keyed by `LineNo`.
 */
class CheckerKeyReadMarkingAgreementTest {

    private val order = DocumentModelDraft.named("orders").locale("en_US")
        .rootGroup("Order") { o -> o
            .group("Lines") { l -> l.repeatable(5).indexField("LineNo").stringField("LineNo") { }.stringField("Sku") { } }
            .group("Picks") { p -> p.repeatable(5).indexField("LineNo").stringField("LineNo") { }.stringField("Ref") { } }
            .group("Gifts") { g -> g.repeatable(2).indexField("GiftNo").stringField("GiftNo") { }.stringField("Note") { } } }
        .build().toJson()

    private val keyed = "FieldFilled(Lines/Sku) And FieldFilled(Gifts/Note For Picks/LineNo)"
    private val plain = "FieldFilled(Lines/Sku) And FieldFilled(Picks/Ref)"
    private val linesKey = "\$/Order/Lines/LineNo.value\$"
    private val picksKey = "\$/Order/Picks/LineNo.value\$"
    private val picksRow = "\$#/Order/Picks\$"

    @Test
    fun aKeyReadMarksItsLevelForTheMessage() {
        val rows = linkedMapOf(
            "keyed, the lines' index field" to (rule(keyed, linesKey) to emptyList()),
            "keyed, the picks' index field" to (rule(keyed, picksKey) to emptyList()),
            "keyed, the picks row" to (rule(keyed, picksRow) to listOf("INVALID_USE_OF_ITERATION_PARAM")),
            "plain, the lines' index field" to (rule(plain, linesKey) to emptyList()),
            "plain, the picks' index field" to (rule(plain, picksKey) to emptyList()),
            "plain, the picks row" to (rule(plain, picksRow) to emptyList()),
        )
        assertEquals(rows.mapValues { (_, row) -> row.second to row.second },
            rows.mapValues { (_, row) -> kernel(row.first) to portable(row.first) }, "(A12, ours)")
    }

    private fun rule(condition: String, parameter: String): String = RuleValidator().inject(order,
        DraftRule("/Order", "KeyMarking", "../Lines/Sku", condition, "PICK", "ERROR",
            listOf(LocalizedText("en_US", "Check $parameter."))))
}
