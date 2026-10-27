package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.modelkit.DatePrecision
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.ToleranceBand
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The one condition A12 joins a computation into, with A12's class written out on every row and the portable
 * checker required to name the same one.
 *
 * `ComputationAlternativesJoiner` writes `FieldFilled(<target>) And (<common>) And (<precondition>) And
 * [<target>] != <operation>`, each alternative wrapped and joined by `Or` when there are several, the target
 * named relative to the computation's group when it lies below it and by its absolute path otherwise, and a
 * tolerance band replacing `!=` (source-read). Two consequences are locked here. The members are ONE text, so
 * the first class in the joined order is the only one A12 names. And every alternative ends in a comparison of
 * its target with its operation, so a copy of one declared kind into another draws the class
 * `CheckVergleichsBedingungImpl` raises for that pair in a rule, after the target's own `[<target>]` read has
 * passed the field-value read's gates. Where the comparison admits the pair, a later gate may answer: a String
 * into an unlabelled Enumeration draws the calculation's own class ([KERNEL-FINDINGS KF411]). The controls
 * separate the lowering from a computation-specific gate: a copy between two fields of one kind is admitted on
 * every kind.
 *
 * Own domain (an order with a billing block and repeatable lines).
 */
class CheckerComputationJoinedConditionAgreementTest {

    private val order = DocumentModelDraft.named("order-copies").locale("en_US")
        .rootGroup("Order") { o -> o
            .stringField("Label") { }
            .stringField("Note") { }
            .stringField("Remark") { s -> s.lineBreaks().noValueValidation() }
            .enumField("Tier") { e -> e.value("GOLD").value("SILVER") }
            .enumField("TierCopy") { e -> e.value("GOLD").value("SILVER") }
            .enumField("Plan") { e -> e
                .value("GOLD") { l -> l.label("en_US", "Gold") }
                .value("SILVER") { l -> l.label("en_US", "Silver") } }
            .numberField("Total") { n -> n.fractionDigits(0) }
            .numberField("Amount") { n -> n.fractionDigits(2) }
            .dateField("Shipped") { d -> d.format("yyyy-MM-dd") }
            .dateField("ShippedCopy") { d -> d.format("yyyy-MM-dd") }
            .dateField("Promised") { d -> d.format("yyyy-MM-dd").datePrecision(DatePrecision.DAY_OPTIONAL) }
            .timeField("Cutoff") { t -> t.format("HH:mm:ss") }
            .timeField("CutoffCopy") { t -> t.format("HH:mm:ss") }
            .dateRangeField("Window") { r -> r.format("dd.MM.yyyy").separator("-") }
            .dateRangeField("Window2") { r -> r.format("dd.MM.yyyy").separator("-") }
            .booleanField("Paid") { }
            .booleanField("PaidCopy") { }
            .confirmField("Agreed") { }
            .confirmField("AgreedCopy") { }
            .group("Billing") { b -> b
                .numberField("Net") { n -> n.fractionDigits(0) }
                .stringField("Owner") { } }
            .group("Lines") { l -> l.repeatable(5)
                .numberField("Qty") { n -> n.fractionDigits(0) }
                .stringField("Sku") { } } }
        .build().toJson()

    private val validator = RuleValidator()

    @Test
    fun aComputationIsJudgedAsTheComparisonItIsLoweredTo() {
        val predicted = linkedMapOf(
            // The text and enumeration arm, asked once per side.
            "a String from a Number" to (Copy("../Label", "[Total]") to TO_ENUM),
            "an Enumeration from a Number" to (Copy("../TierCopy", "[Total]") to TO_ENUM),
            "a Number from a String" to (Copy("../Total", "[Label]") to TO_ENUM),
            "a String from a written-out number" to (Copy("../Label", "1") to TO_ENUM),
            "a Number from a written-out text" to (Copy("../Total", "\"abc\"") to TO_ENUM),
            "an Enumeration from a value it does not declare" to (Copy("../TierCopy", "\"PLATINUM\"") to OUTSIDE_DOMAIN),
            "an Enumeration from a labelled Enumeration" to (Copy("../TierCopy", "[Plan]") to setOf("MVK_INVALID_COMPARE_ENUM_TO_STRING")),
            // A String into an Enumeration: the comparison admits a text against a textless target, so the
            // calculation's own class answers ([KF411]); a labelled target refuses the text first ([KF407]).
            "an Enumeration from a String" to (Copy("../TierCopy", "[Label]") to setOf("MVK_ERROR_INVALID_ENUM_CALCULATION")),
            "a labelled Enumeration from a String" to (Copy("../Plan", "[Label]") to setOf("MVK_INVALID_COMPARE_ENUM_TO_STRING")),
            "an Enumeration from a value it declares, the control" to (Copy("../TierCopy", "\"GOLD\"") to ADMITTED),
            "an Enumeration from an Enumeration, the control" to (Copy("../TierCopy", "[Tier]") to ADMITTED),
            "a String from a String, the control" to (Copy("../Label", "[Note]") to ADMITTED),
            // The number arm.
            "a Number from a Number of a larger scale" to (Copy("../Total", "[Amount]") to SCALE),
            "a Number from a Number of a smaller scale" to (Copy("../Amount", "[Total]") to SCALE),
            "a Number from a written-out number of a smaller scale" to (Copy("../Amount", "1") to ADMITTED),
            "a Number from a written-out number of a larger scale" to (Copy("../Total", "1.50") to SCALE),
            "a Number from True" to (Copy("../Total", "True") to setOf("MVK_INCONSISTENT_TYPES_COMPARED")),
            "a Number from a Number of its scale, the control" to (Copy("../Total", "[Billing/Net]") to ADMITTED),
            // The date and date-range arms, asked ahead of the others.
            "a Date from a Number" to (Copy("../Shipped", "[Total]") to DATE),
            "a Number from a Date" to (Copy("../Total", "[Shipped]") to DATE),
            "a Date from a String" to (Copy("../Shipped", "[Label]") to DATE),
            "a Date from a Time" to (Copy("../Shipped", "[Cutoff]") to DATE),
            "a DateRange from a Date" to (Copy("../Window", "[Shipped]") to DATE),
            "a DateRange from a String" to (Copy("../Window", "[Label]") to setOf("MVK_INVALID_COMPARE_TO_DATE_RANGE")),
            "a Date from a Date, the control" to (Copy("../Shipped", "[ShippedCopy]") to ADMITTED),
            "a Time from a Time, the control" to (Copy("../CutoffCopy", "[Cutoff]") to ADMITTED),
            "a DateRange from a DateRange, the control" to (Copy("../Window", "[Window2]") to ADMITTED),
            // The boolean arms, the confirm arm first.
            "a Boolean from a Number" to (Copy("../Paid", "[Total]") to YES_NO),
            "a String from a Boolean" to (Copy("../Label", "[Paid]") to YES_NO),
            "a confirm from a Boolean" to (Copy("../Agreed", "[Paid]") to setOf("MVK_INVALID_COMPARE_TO_YES")),
            "a Boolean from True, the control" to (Copy("../Paid", "True") to ADMITTED),
            "a Boolean from a Boolean, the control" to (Copy("../PaidCopy", "[Paid]") to ADMITTED),
            "a confirm from True, the control" to (Copy("../Agreed", "True") to ADMITTED),
            "a confirm from a confirm, the control" to (Copy("../AgreedCopy", "[Agreed]") to ADMITTED),
            // The target's spelling: relative below the computation's group, absolute outside it.
            "declared in Billing, into a root String" to (Copy("../../Label", "[Net]", group = "/Order/Billing") to TO_ENUM),
            "declared in Billing, into its own String" to (Copy("../Owner", "[Net]", group = "/Order/Billing") to TO_ENUM),
            "declared at the root, into a repeatable Number" to (Copy("../Lines/Qty", "[Label]") to TO_ENUM),
            "declared in Lines, into its own Number" to (Copy("../Qty", "[/Order/Label]", group = "/Order/Lines") to TO_ENUM),
            // A tolerance band replaces `!=`, and the tolerance gate asks for numbers on both sides.
            "a String from a String under a tolerance band" to
                (Copy("../Label", "[Note]", band = ToleranceBand.TR1) to setOf("MVK_INVALID_TYPE_FOR_TOLERANCE_OP")),
            "a Number from a Number under a tolerance band, the control" to
                (Copy("../Total", "[Billing/Net]", band = ToleranceBand.TR1) to ADMITTED),
            "a Number from a Number of a larger scale under a tolerance band" to
                (Copy("../Total", "[Amount]", band = ToleranceBand.TR1) to ADMITTED),
            "a Number from a Number of a smaller scale under a tolerance band" to
                (Copy("../Amount", "[Total]", band = ToleranceBand.TR1) to ADMITTED),
            // The target is read as `[<target>]` first, so its field-value read gates answer ahead of every
            // other class, and a source's read gates ahead of the comparison.
            "a partially known Date from a Date" to (Copy("../Promised", "[ShippedCopy]") to PARTIAL),
            "a raw String from a String" to (Copy("../Remark", "[Note]") to RAW),
            "a Number from a partially known Date" to (Copy("../Total", "[Promised]") to PARTIAL),
            "a Number from a raw String" to (Copy("../Total", "[Remark]") to RAW),
            // The comparison is the joined text's last clause, so a precondition's class answers ahead of it.
            "a String from a Number behind a refused precondition" to
                (Copy("../Label", "[Total]", precondition = "[Shipped] > [Total]") to DATE),
            // A side the comparison families do not type: A12 still raises the kind class.
            "a String from a sum" to (Copy("../Label", "[Total] + 1") to TO_ENUM),
            // The comparison closes before the parser meets a token it cannot use after it.
            "a String from a Number, a stray read after it" to (Copy("../Label", "[Total] [Note]") to TO_ENUM),
        )
        val wrong = predicted.mapNotNull { (row, probe) ->
            val (copy, a12) = probe
            val observed = compare(copy)
            val expected = a12 to a12
            if (observed == expected) null else "$row: expected (A12, ours) $expected, observed $observed"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n", "rows that disagree:\n"))
    }

    @Test
    fun aComputationsMembersAnswerInTheOrderA12JoinsThem() {
        val predicted = linkedMapOf(
            "a precondition, then its operation" to
                (Joined(null, "[Shipped] > [Billing/Net]" to "[Billing] + 1") to DATE),
            "the common precondition, then an alternative's precondition" to
                (Joined("[Shipped] > [Billing/Net]", "[Billing] > 0" to "[Billing/Net] + 1") to DATE),
            "an alternative's operation, then the next alternative's precondition" to
                (Joined(null, "[Billing/Net] > 0" to "[Billing] + 1", "[Shipped] > [Billing/Net]" to "[Billing/Net] + 1") to GROUP),
            "a precondition, then a name its operation does not resolve" to
                (Joined(null, "[Shipped] > [Billing/Net]" to "[NoSuch] + 1") to DATE),
            "a name a precondition does not resolve, then its operation" to
                (Joined(null, "[NoSuch] > 0" to "[Billing] + 1") to setOf("MVK_INVALID_ENTITY")),
            "each member alone, the controls" to
                (Joined("[Billing/Net] > 0", "[Billing/Net] > 1" to "[Billing/Net] + 1", "[Billing/Net] > 2" to "[Billing/Net] + 2") to ADMITTED),
        )
        val wrong = predicted.mapNotNull { (row, probe) ->
            val (joined, a12) = probe
            val observed = compare(joined.draft())
            val expected = a12 to a12
            if (observed == expected) null else "$row: expected (A12, ours) $expected, observed $observed"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n", "rows that disagree:\n"))
    }

    /** A root computation into `Total` with [common] ahead of each (precondition, operation) alternative. */
    private class Joined(val common: String?, vararg val alternatives: Pair<String, String>) {
        fun draft() = DraftComputation("/Order", "Derived", "../Total", common,
            alternatives.map { (precondition, operation) ->
                DraftComputation.Alternative.builder(operation).precondition(precondition).build()
            })
    }

    /** One computation: [target] relative to the computation declared in [group], computed from [operation]. */
    private data class Copy(
        val target: String,
        val operation: String,
        val group: String = "/Order",
        val precondition: String? = null,
        val band: ToleranceBand? = null,
    )

    private fun compare(copy: Copy): Pair<Set<String>, Set<String>> {
        val alternative = DraftComputation.Alternative.builder(copy.operation)
            .precondition(copy.precondition).toleranceRangeOp(copy.band).build()
        return compare(DraftComputation(copy.group, "Derived", copy.target, null, listOf(alternative)))
    }

    private fun compare(draft: DraftComputation): Pair<Set<String>, Set<String>> {
        val a12 = validator.check(order, draft).let { result ->
            if (result.ok()) emptySet() else result.diagnostics().map { it.code().orElse("(no code: ${it.summary()})") }.toSet()
        }
        val ours = check(ModelSource(validator.inject(order, draft))).findings
            .filter { it.justifiesRejection }.map { it.code }.toSet()
        return a12 to ours
    }

    private companion object {
        val ADMITTED = emptySet<String>()
        val TO_ENUM = setOf("MVK_INVALID_COMPARE_TO_ENUM_OR_STRING")
        val OUTSIDE_DOMAIN = setOf("MVK_INVALID_STRING_CONSTANT_FOR_ENUM_COMPARISON")
        val SCALE = setOf("MVK_INVALID_COMPARE_DEC_PLACES")
        val DATE = setOf("MVK_INVALID_COMPARE_TO_DATE")
        val YES_NO = setOf("MVK_INVALID_COMPARE_TO_YESNO")
        val GROUP = setOf("MVK_NO_GROUPS_ALLOWED")
        val RAW = setOf("MVK_INVALID_RAW_TYPE")
        val PARTIAL = setOf("MVK_INVALID_DATE_TYPE")

    }
}
