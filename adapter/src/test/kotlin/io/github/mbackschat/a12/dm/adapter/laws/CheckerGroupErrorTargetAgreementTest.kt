package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A GROUP error target beside a parallel iteration: A12 bounds the target's scope by the group itself but reads
 * the index chain it matches a parallel level against from the group's PARENT (`CheckEntityUtils
 * .checkParallelIteration` starts at `getParentGroup(errorEntity)`, while `EntityUtils
 * .isGroupAboveOrEqualErrorEntityPath` takes the group's own path; KERNEL-FINDINGS KF609).
 *
 * [ROWS] pins A12's answer on every row, and the checker must name exactly the same classes. Each row's comment
 * names the rival reading it separates: "the group's own chain" (the group read as if it were a field's group)
 * and "the parent's scope" (the group read as if it were a field of its parent). Own domain: an e-commerce order
 * whose lines and picks share the index name `LineNo`.
 */
class CheckerGroupErrorTargetAgreementTest {

    private val order = DocumentModelDraft.named("order-group-target").locale("en_US")
        .rootGroup("Order") { o -> o
            .stringField("Email") { }
            .stringField("Phone") { }
            .stringField("Tier") { }
            .numberField("Budget") { n -> n.fractionDigits(0) }
            .dateField("OrderedOn") { d -> d.format("yyyy-MM-dd") }
            .dateRangeField("Season") { r -> r.format("yyyy-MM-dd").separator("/") }
            .group("Billing") { b -> b.stringField("Street") { }.stringField("City") { } }
            .group("Lines") { l -> l.repeatable(50).indexField("LineNo")
                .stringField("LineNo") { }
                .stringField("Sku") { }
                .stringField("Code") { }
                .numberField("Qty") { n -> n.fractionDigits(0) }
                .numberField("Price") { n -> n.fractionDigits(2) }
                .numberField("Day") { n -> n.fractionDigits(0).min("1").max("31") }
                .numberField("Month") { n -> n.fractionDigits(0).min("1").max("12") }
                .numberField("Year") { n -> n.fractionDigits(0).min("1800").max("2200") }
                .dateField("ShipOn") { d -> d.format("yyyy-MM-dd") }
                .dateRangeField("Window") { r -> r.format("yyyy-MM-dd").separator("/") }
                .group("Parts") { p -> p.repeatable(5)
                    .stringField("PartNo") { }
                    .numberField("Count") { n -> n.fractionDigits(0) }
                    .group("Tags") { t -> t.repeatable(3).indexField("TagNo")
                        .stringField("TagNo") { }
                        .stringField("Label") { } } }
                .group("Slots") { t -> t.repeatable(4).indexField("SlotNo")
                    .stringField("SlotNo") { }
                    .stringField("Mark") { } } }
            .group("Picks") { p -> p.repeatable(10).indexField("LineNo")
                .stringField("LineNo") { }
                .stringField("Ref") { }
                .numberField("Weight") { n -> n.fractionDigits(0) } }
            .group("Gifts") { g -> g.repeatable(2).indexField("GiftNo")
                .stringField("GiftNo") { }
                .stringField("Note") { }
                .group("Card") { c -> c.stringField("Text") { } } }
            .group("Wraps") { w -> w.repeatable(2)
                .stringField("Style") { }
                .group("Ribbon") { r -> r.stringField("Color") { } }
                .group("Tag") { t -> t.stringField("Label") { } }
                .group("Bows") { b -> b.repeatable(3).indexField("BowNo")
                    .stringField("BowNo") { }
                    .stringField("Shade") { } } } }
        .build().toJson()

    private val validator = RuleValidator()

    @Test
    fun a12AnswersEveryRowAsPinned() {
        assertEquals(ROWS.mapValues { it.value.a12 }, ROWS.mapValues { judge(it.value).first })
    }

    @Test
    fun theCheckerNamesWhatA12Names() {
        assertEquals(ROWS.mapValues { judge(it.value).first }, ROWS.mapValues { judge(it.value).second })
    }

    private fun judge(row: Row): Pair<List<String>, List<String>> {
        val draft = DraftRule("/Order", "TargetProbe", "../${row.target}", row.condition, "PROBE", "ERROR")
        val result = validator.check(order, draft)
        val a12 = if (result.ok()) emptyList() else result.diagnostics().map { it.code().orElse("(no code)") }
        val ours = check(ModelSource(validator.inject(order, draft))).findings.filter { it.justifiesRejection }.map { it.code }
        return a12.sorted() to ours.sorted()
    }

    private data class Row(val target: String, val condition: String, val a12: List<String>)

    private companion object {
        const val NO_INDEX = "MVK_ERROR_FIELD_NO_INDEX"
        const val NO_WILDCARD = "MVK_NO_WILDCARD"

        val ROWS = linkedMapOf(
            // The parent `Order` declares no index: against "the group's own chain", which finds `LineNo`.
            "g1" to Row("Lines", "NoFieldFilled(Lines/Sku, Picks/Ref)", listOf(NO_INDEX)),
            "g12" to Row("Lines", "GroupFilled(Lines) And FieldFilled(Picks/Ref)", listOf(NO_INDEX)),
            "g10" to Row("Gifts", "FieldFilled(Picks/Ref) And GroupFilled(Gifts)", listOf(NO_INDEX)),
            // The parent `Wraps` repeats unkeyed, so its chain is empty although the group keys `BowNo`.
            "g9" to Row("Wraps/Bows", "FieldFilled(Picks/Ref) And GroupFilled(Wraps/Bows)", listOf(NO_INDEX)),
            // The parent `Gifts` keys `GiftNo`, which differs from `LineNo`: against "the parent's scope", silent.
            "g4" to Row("Gifts/Card", "FieldFilled(Picks/Ref) And GroupFilled(Gifts/Card)", listOf("MVK_ERROR_FIELD_INVALID_INDEX")),
            // The parent chain is `Lines` alone, which matches `Picks`; the group's own `SlotNo` is not on it.
            "g2" to Row("Lines/Slots", "GroupNotFilled(Lines/Slots) And FieldFilled(Picks/Ref)", emptyList()),
            "g2b" to Row("Lines/Slots", "GroupNotFilled(Lines/Slots) Or FieldFilled(Picks/Ref)",
                listOf("MVK_PARALLEL_ITERATION_INVALID")),
            "g3" to Row("Lines/Parts", "FieldFilled(Picks/Ref) And GroupFilled(Lines/Parts)", emptyList()),
            // The parent chain of `Tags` is `Lines` (keyed) through unkeyed `Parts`: one index, matching `Picks`.
            "g8" to Row("Lines/Parts/Tags", "FieldFilled(Picks/Ref) And GroupFilled(Lines/Parts/Tags)", emptyList()),
            // A parallel level with no index of its own answers before either chain is read.
            "g5" to Row("Lines/Parts", "FieldFilled(Wraps/Style) And GroupFilled(Lines/Parts)", listOf(NO_WILDCARD)),
            "g13" to Row("Lines/Parts", "GroupFilled(Lines/Parts) And FieldFilled(Wraps/Bows/Shade)", listOf(NO_WILDCARD)),
            // The scope is the group itself: a level below it is unaddressed, against "the parent's scope".
            "g6" to Row("Lines", "FieldFilled(Lines/Parts/PartNo) And GroupFilled(Lines)", listOf(NO_WILDCARD)),
            "g7" to Row("Lines/Parts", "GroupFilled(Lines/Parts) And FieldFilled(Lines/Parts/Tags/Label)", listOf(NO_WILDCARD)),
            // The group's own fields are inside its scope.
            "g11" to Row("Lines/Slots", "GroupFilled(Lines/Slots) And FieldFilled(Lines/Slots/Mark)", emptyList()),
            // A key on the group's own level is in scope, against "the parent's scope", which calls it iterated below.
            "k5" to Row("Lines", "GroupFilled(Lines) And FieldFilled(Picks/Ref For Lines/LineNo)", emptyList()),
            "k4" to Row("Lines/Slots", "GroupFilled(Lines/Slots) And FieldFilled(Picks/Ref For Lines/LineNo)", emptyList()),
            "k1" to Row("Lines/Parts", "GroupFilled(Lines/Parts) And FieldFilled(Lines/Slots/Mark For Lines/Parts/PartNo)",
                listOf("MVK_MULTIPLE_INDEX_FIELDS")),
            // A key below the group's scope stays the index's own iteration class.
            "k3" to Row("Lines", "GroupFilled(Lines) And FieldFilled(Lines/Slots/Mark For Lines/Parts/PartNo)",
                listOf("MVK_SEMANTIC_INDEX_INVALID_ITER")),
            // A group key on the target's own level reads one row, so the key's kind answers, against "the parent's
            // scope", which calls the key iterated below the target.
            "k6" to Row("Lines/Slots", "GroupFilled(Lines/Slots) And FieldFilled(Lines/Slots/Mark For Lines/Slots)",
                listOf("MVK_SEMANTIC_INDEX_NO_FIELD")),
        )
    }
}
