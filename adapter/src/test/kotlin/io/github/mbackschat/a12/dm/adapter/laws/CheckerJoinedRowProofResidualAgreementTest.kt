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
 * Which further conditions prove a joined group's row for its `$#…$` token, beside
 * `CheckerParallelJoinMessageAgreementTest`'s two-operand rows: three operands, a nonrepeatable group below a joined
 * one, a group list, an OR whose every arm proves, a literal comparison, `GroupNotFilled`, a starred operand, and the
 * relative and rule-group spellings. `NoFieldFilled` beside the join is refused before any message is read.
 *
 * Each row is one model with one parameter, pinned for A12 and compared against the portable checker. Requested by
 * a12-kernel-lean as SPEC-2026-11-05-04; its rows `c01`–`c13`, `g11`/`g12` and `n2x`/`n3x` are re-expressed on a
 * shop whose `Ordered` and `Received` lines are both keyed by `Sku`. Own domain.
 */
class CheckerJoinedRowProofResidualAgreementTest {

    private val base = DocumentModelDraft.named("shop-receipts").locale("en_US")
        .rootGroup("Shop") { shop -> shop
            .group("Ordered") { o -> o.repeatable(3).indexField("Sku")
                .stringField("Sku") { }
                .numberField("Qty") { it.fractionDigits(0) }
                .numberField("Gap") { it.fractionDigits(0) }
                .stringField("Note") { }
                .group("Packing") { p -> p.stringField("Label") { } }
                .group("Wrapping") { w -> w.stringField("Paper") { } } }
            .group("Received") { r -> r.repeatable(3).indexField("Sku")
                .stringField("Sku") { }
                .numberField("Qty") { it.fractionDigits(0) } } }
        .build().toJson()

    private val refused = listOf("INVALID_USE_OF_ITERATION_PARAM")
    private val admitted = emptyList<String>()

    @Test
    fun aJoinedRowIsProvedOnlyByConditionsThatNeedBothRowsPresent() {
        val rows = linkedMapOf<String, Pair<String, List<String>>>()
        fun both(carrier: String, ordered: List<String>, received: List<String>, reads: (EditSession) -> Condition) {
            rows["$carrier, ordered"] = rule("\$#/Shop/Ordered\$", reads = reads) to ordered
            rows["$carrier, received"] = rule("\$#/Shop/Received\$", reads = reads) to received
        }
        both("MoreThanOneFieldFilled over both sides", refused, refused) { s -> Dsl.moreThanOneFieldFilled(oq(s).field(), rq(s).field()) }
        both("AllFieldsFilled over three", admitted, admitted) { s -> Dsl.allFieldsFilled(oq(s).field(), on(s).field(), rq(s).field()) }
        both("AtLeastOneFieldFilled over three ordered AND FieldFilled", admitted, admitted) { s ->
            Dsl.atLeastOneFieldFilled(oq(s).field(), on(s).field(), og(s).field()).and(rq(s).filled())
        }
        both("MoreThanOneFieldFilled over three", refused, refused) { s ->
            Dsl.moreThanOneFieldFilled(oq(s).field(), on(s).field(), rq(s).field())
        }
        both("two literal comparisons", refused, refused) { s -> oq(s).gt(0).and(rq(s).gt(0)) }
        both("GroupNotFilled AND FieldFilled", refused, admitted) { s ->
            s.group("/Shop/Ordered").ref().notFilled().and(rq(s).filled())
        }
        both("an OR whose every arm proves", admitted, admitted) { s ->
            oq(s).filled().and(rq(s).filled()).or(Dsl.allFieldsFilled(oq(s).field(), rq(s).field()))
        }
        both("a nonrepeatable sub-group's field proves", admitted, admitted) { s ->
            oq(s).notFilled().and(s.group("/Shop/Ordered/Packing").str("Label").filled()).and(rq(s).filled())
        }
        both("a starred operand proves nothing", refused, admitted) { s ->
            oq(s).notFilled().and(Dsl.atLeastOneFieldFilled(s.group("/Shop").flatten("Ordered*/Note"))).and(rq(s).filled())
        }
        both("AtLeastOneGroupFilled over both sides", refused, refused) { s ->
            Dsl.atLeastOneGroupFilled(s.group("/Shop/Ordered").ref(), s.group("/Shop/Received").ref())
        }
        both("AtLeastOneGroupFilled over two ordered sub-groups AND FieldFilled", admitted, admitted) { s ->
            oq(s).notFilled()
                .and(Dsl.atLeastOneGroupFilled(s.group("/Shop/Ordered/Packing").ref(), s.group("/Shop/Ordered/Wrapping").ref()))
                .and(rq(s).filled())
        }
        assertAgreement(rows)
    }

    @Test
    fun theRelativeAndRuleGroupSpellingsFollowTheAbsoluteOne() {
        val notCollectively = { s: EditSession -> Dsl.fieldsNotCollectivelyFilled(oq(s).field(), rq(s).field()) }
        val both = { s: EditSession -> oq(s).filled().and(rq(s).filled()) }
        val rows = linkedMapOf(
            "relative ordered, FieldsNotCollectivelyFilled" to (rule("\$#Ordered\$", reads = notCollectively) to refused),
            "relative received, FieldsNotCollectivelyFilled" to (rule("\$#Received\$", reads = notCollectively) to refused),
            "relative ordered, AND" to (rule("\$#Ordered\$", reads = both) to admitted),
            "relative received, AND" to (rule("\$#Received\$", reads = both) to admitted),
            "rule group in Ordered, FieldsNotCollectivelyFilled" to
                (rule("\$#RuleGroup\$", declaring = "/Shop/Ordered", reads = notCollectively) to refused),
            "rule group in Ordered, AND" to (rule("\$#RuleGroup\$", declaring = "/Shop/Ordered", reads = both) to admitted),
            "absolute ordered in Ordered, AND" to
                (rule("\$#/Shop/Ordered\$", declaring = "/Shop/Ordered", reads = both) to admitted),
            "absolute received in Ordered, AND" to
                (rule("\$#/Shop/Received\$", declaring = "/Shop/Ordered", reads = both) to admitted),
        )
        assertAgreement(rows)
    }

    @Test
    fun noFieldFilledBesideTheJoinIsRefusedWhateverTheMessage() {
        val invalid = listOf("MVK_PARALLEL_ITERATION_INVALID")
        val rows = linkedMapOf(
            "over three, a row token" to (rule("\$#/Shop/Ordered\$") { s -> Dsl.noFieldFilled(oq(s).field(), on(s).field(), rq(s).field()) } to invalid),
            "over three, plain text" to (rule("") { s -> Dsl.noFieldFilled(oq(s).field(), on(s).field(), rq(s).field()) } to invalid),
            "over two, plain text" to (rule("") { s -> Dsl.noFieldFilled(oq(s).field(), rq(s).field()) } to invalid),
        )
        assertAgreement(rows)
    }

    private fun oq(s: EditSession) = s.group("/Shop/Ordered").num("Qty")
    private fun og(s: EditSession) = s.group("/Shop/Ordered").num("Gap")
    private fun on(s: EditSession) = s.group("/Shop/Ordered").str("Note")
    private fun rq(s: EditSession) = s.group("/Shop/Received").num("Qty")

    private fun rule(parameter: String, declaring: String = "/Shop", reads: (EditSession) -> Condition): String {
        val edit = EditSession.read(base)
        return ModelWriter.addRule(base, edit.group(declaring).rule {
            it.name("JoinMessage").code("JOIN").errorField(oq(edit)).`when`(reads(edit))
                .message(Dsl.en("Check $parameter.")).build()
        })
    }

    private fun assertAgreement(rows: Map<String, Pair<String, List<String>>>) {
        assertEquals(rows.mapValues { (_, row) -> row.second to row.second },
            rows.mapValues { (_, row) -> kernel(row.first) to portable(row.first) }, "(A12, ours)")
    }
}
