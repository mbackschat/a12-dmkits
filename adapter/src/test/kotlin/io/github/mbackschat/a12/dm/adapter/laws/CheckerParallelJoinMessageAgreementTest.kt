package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.MessageParameterProbes.kernel
import io.github.mbackschat.a12.dm.adapter.laws.MessageParameterProbes.portable
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * What a rule's message may name when its condition joins two keyed groups row by row (a parallel iteration).
 *
 * The carrier is a shop whose `Ordered` and `Received` lines are both keyed by their `Sku`; every rule sits at the
 * root and reports on an ordered line's `Qty`, so reading a received line's field unstarred joins the two groups.
 *
 * - **Membership.** The join's index fields are members of the rule's message although the condition names neither,
 *   so `$Ordered/Sku$` and `$Received/Sku$` are admitted beside it. A rule that reads only its own group joins
 *   nothing, and the same parameter is `INVALID_FIELD`.
 * - **The row token.** `$#Ordered$` and `$#Received$` name a level the condition iterates in parallel, so A12 admits
 *   either only when the condition proves that level's row exists: one AND arm or every OR arm must carry the proof.
 *   Without one the token draws `INVALID_USE_OF_ITERATION_PARAM`. A rule that joins nothing leaves the token to the
 *   ordinary availability gates.
 *
 * Each row is one model with one parameter, pinned for A12 and compared against the portable checker. Requested by
 * a12-kernel-lean as SPEC-2026-11-05-03, whose capture measured the `FieldsNotCollectivelyFilled` rows; the other
 * carriers separate which conditions carry the proof. Own domain.
 */
class CheckerParallelJoinMessageAgreementTest {

    private val base = DocumentModelDraft.named("shop-receipts").locale("en_US")
        .rootGroup("Shop") { shop -> shop
            .group("Ordered") { o -> o.repeatable(3).indexField("Sku")
                .stringField("Sku") { }
                .numberField("Qty") { it.fractionDigits(0) }
                .numberField("Gap") { it.fractionDigits(0) }
                .stringField("Note") { } }
            .group("Received") { r -> r.repeatable(3).indexField("Sku")
                .stringField("Sku") { }
                .numberField("Qty") { it.fractionDigits(0) }
                .stringField("Note") { } } }
        .build().toJson()

    private val orderedSku = "\$/Shop/Ordered/Sku.value\$"
    private val receivedSku = "\$/Shop/Received/Sku.value\$"
    private val orderedRow = "\$#/Shop/Ordered\$"
    private val receivedRow = "\$#/Shop/Received\$"

    @Test
    fun theJoinsIndexFieldsAreMembersOfTheMessage() {
        val rows = linkedMapOf(
            "joined, the ordered key" to (rule(orderedSku) { s -> notCollectively(s) } to admitted),
            "joined, the received key" to (rule(receivedSku) { s -> notCollectively(s) } to admitted),
            "joined, the key by name" to (rule("\$/Shop/Ordered/Sku\$") { s -> notCollectively(s) } to admitted),
            "joined through an AND, the ordered key" to
                (rule(orderedSku) { s -> oq(s).filled().and(rq(s).filled()) } to admitted),
            "own group only (control), the ordered key" to (rule(orderedSku) { s -> oq(s).filled() } to invalidField),
            "a starred received read, the received key" to
                (rule(receivedSku) { s -> oq(s).filled().and(Dsl.atLeastOneFieldFilled(s.group("/Shop").flatten("Received*/Qty"))) }
                    to invalidField),
            "joined, a payload field the condition does not read (control)" to
                (rule("\$/Shop/Received/Note.value\$") { s -> notCollectively(s) } to invalidField),
            "declared in the ordered group, joined, the received key" to
                (rule(receivedSku, declaring = "/Shop/Ordered") { s -> notCollectively(s) } to admitted),
        )
        assertAgreement(rows)
    }

    @Test
    fun aJoinedLevelsRowTokenNeedsTheConditionToProveTheRow() {
        val refused = listOf("INVALID_USE_OF_ITERATION_PARAM")
        val rows = linkedMapOf<String, Pair<String, List<String>>>()
        fun both(carrier: String, ordered: List<String>, received: List<String>, reads: (EditSession) -> Condition) {
            rows["$carrier, ordered"] = rule(orderedRow, reads = reads) to ordered
            rows["$carrier, received"] = rule(receivedRow, reads = reads) to received
        }
        both("FieldsNotCollectivelyFilled", refused, refused) { s -> notCollectively(s) }
        both("FieldFilled AND FieldFilled", admitted, admitted) { s -> oq(s).filled().and(rq(s).filled()) }
        both("FieldFilled OR FieldFilled", refused, refused) { s -> oq(s).filled().or(rq(s).filled()) }
        both("AllFieldsFilled over both sides", admitted, admitted) { s -> Dsl.allFieldsFilled(oq(s).field(), rq(s).field()) }
        both("AtLeastOneFieldFilled over both sides", refused, refused) { s -> Dsl.atLeastOneFieldFilled(oq(s).field(), rq(s).field()) }
        both("AtLeastOneFieldFilled over the ordered side AND FieldFilled", admitted, admitted) { s ->
            Dsl.atLeastOneFieldFilled(oq(s).field(), s.group("/Shop/Ordered").str("Note").field()).and(rq(s).filled())
        }
        both("MoreThanOneFieldFilled over the ordered side AND FieldFilled", admitted, admitted) { s ->
            Dsl.moreThanOneFieldFilled(oq(s).field(), s.group("/Shop/Ordered").str("Note").field()).and(rq(s).filled())
        }
        both("a comparison", refused, refused) { s -> oq(s).gt(rq(s)) }
        both("FieldNotFilled AND FieldFilled", refused, admitted) { s -> oq(s).notFilled().and(rq(s).filled()) }
        both("GroupFilled AND FieldFilled", admitted, admitted) { s -> s.group("/Shop/Ordered").ref().filled().and(rq(s).filled()) }
        both("AllGroupsFilled over both sides", admitted, admitted) { s ->
            Dsl.allGroupsFilled(s.group("/Shop/Ordered").ref(), s.group("/Shop/Received").ref())
        }
        rows["own group only (control), ordered"] = rule(orderedRow) { s -> oq(s).filled() } to admitted
        rows["declared in the ordered group, joined, ordered"] =
            rule(orderedRow, declaring = "/Shop/Ordered") { s -> notCollectively(s) } to refused
        rows["declared in the ordered group, joined, received"] =
            rule(receivedRow, declaring = "/Shop/Ordered") { s -> notCollectively(s) } to refused
        assertAgreement(rows)
    }

    @Test
    fun aComputationsJoinMakesTheIndexFieldsMembersToo() {
        val rows = linkedMapOf(
            "joined, the received key" to (computation(receivedSku) { s -> oq(s).asNumber().minus(rq(s).asNumber()) } to admitted),
            "own group only (control), the ordered key" to
                (computation(orderedSku) { s -> oq(s).asNumber().plus(1) } to invalidField),
        )
        assertAgreement(rows)
    }

    private val admitted = emptyList<String>()
    private val invalidField = listOf("INVALID_FIELD")

    private fun oq(s: EditSession) = s.group("/Shop/Ordered").num("Qty")
    private fun rq(s: EditSession) = s.group("/Shop/Received").num("Qty")
    private fun notCollectively(s: EditSession) = Dsl.fieldsNotCollectivelyFilled(oq(s).field(), rq(s).field())

    private fun rule(parameter: String, declaring: String = "/Shop", reads: (EditSession) -> Condition): String {
        val edit = EditSession.read(base)
        val shop = edit.group(declaring)
        return ModelWriter.addRule(base, shop.rule {
            it.name("JoinMessage").code("JOIN").errorField(oq(edit)).`when`(reads(edit))
                .message(Dsl.en("Check $parameter.")).build()
        })
    }

    private fun computation(parameter: String, value: (EditSession) -> io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr): String {
        val edit = EditSession.read(base)
        val shop = edit.group("/Shop")
        return ModelWriter.addComputation(base, shop.computation(edit.group("/Shop/Ordered").num("Gap")) {
            it.group(shop).name("GapComputation").calc(value(edit).expression())
                .message(Dsl.en("Check $parameter.")).build()
        })
    }

    private fun assertAgreement(rows: Map<String, Pair<String, List<String>>>) {
        assertEquals(rows.mapValues { (_, row) -> row.second to row.second },
            rows.mapValues { (_, row) -> kernel(row.first) to portable(row.first) }, "(A12, ours)")
    }
}
