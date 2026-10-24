package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The iteration guard: A12 refuses an iterating rule whose condition can be true for a repetition nobody filled,
 * `MVK_NEG_CONDITION_IN_ITERATION` on the rule's own iteration and `MVK_PARALLEL_ITERATION_INVALID` across
 * parallel groups (KERNEL-FINDINGS KF537, KF595, KF596, KF601, KF608).
 *
 * [ROWS] pins A12's own answer on every row, so a kernel bump that moves one fails here before it is mistaken
 * for a checker defect; the checker must then agree with A12 on every row. Each row's comment names the rival
 * reading it separates. Own domain: an e-commerce order whose lines and picks are keyed by one index name.
 */
class CheckerIterationGuardAgreementTest {

    private val order = DocumentModelDraft.named("order-iteration-guard").locale("en_US")
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

    private data class Row(val group: String, val errorField: String, val condition: String, val a12: List<String>)

    /**
     * A12's rejection classes and the checker's, for one rule. A judged row must carry no gap, so a row the
     * checker silently declined cannot pass as agreement; the marker [DECLINED] makes a decline visible.
     */
    private fun judge(row: Row): Pair<List<String>, List<String>> {
        val draft = DraftRule(row.group, "GuardProbe", "../${row.errorField}", row.condition, "PROBE", "ERROR")
        val a12 = validator.check(order, draft).let { r ->
            if (r.ok()) emptyList() else r.diagnostics().map { it.code().orElse("(no code)") }
        }
        val report = check(ModelSource(validator.inject(order, draft)))
        val declined = report.gaps.any { it.at == "${row.group}/GuardProbe" && NEG in it.reason }
        val ours = report.findings.filter { it.justifiesRejection }.map { it.code } +
            listOfNotNull(DECLINED_MARK.takeIf { declined })
        return a12.sorted() to ours.sorted()
    }

    @Test
    fun a12AnswersEveryRowAsPinned() {
        assertEquals(ROWS.mapValues { it.value.a12 }, ROWS.mapValues { judge(it.value).first })
    }

    @Test
    fun theCheckerAgreesWithA12OnEveryRow() {
        // A12 never reaches this check where an earlier class answers, so a gap there is redundant but true.
        val disagreements = ROWS.mapValues { judge(it.value) }.filter { (_, v) ->
            val earlier = v.first.isNotEmpty() && v.first.none { it == NEG || it == PAR }
            (if (earlier) v.second - DECLINED_MARK else v.second) != v.first
        }
        assertEquals(emptyMap<String, Pair<List<String>, List<String>>>(), disagreements)
    }

    /**
     * The shapes this checker does not judge for the law, each refused by A12 with a class it decides first. A12's
     * answer is pinned like any row's; the checker must stay silent on the law there and name the rule in a gap,
     * so an unreported refusal is never read as examined.
     */
    @Test
    fun aDeclinedShapeIsNamedAtItsRuleAndNeverJudged() {
        val verdicts = DECLINED.mapValues { judge(it.value) }
        assertEquals(DECLINED.mapValues { it.value.a12 }, verdicts.mapValues { it.value.first })
        assertEquals(DECLINED.mapValues { true to false },
            verdicts.mapValues { (_, v) -> (DECLINED_MARK in v.second) to v.second.any { it == NEG || it == PAR } })
    }

    /**
     * The level named is the shallowest one refused: both the lines and the parts levels refuse here, and A12's
     * message names the lines. Against "deepest first", which names the parts.
     */
    @Test
    fun theRefusedLevelNamedIsTheShallowest() {
        val draft = DraftRule("/Order/Lines/Parts", "GuardProbe", "../PartNo", "FieldNotFilled(PartNo)", "PROBE", "ERROR")
        val a12 = validator.check(order, draft).diagnostics().single().summary()
        val ours = check(ModelSource(validator.inject(order, draft))).findings.single { it.code == NEG }.explain
        assertEquals(A12_LEVEL_MESSAGE, a12)
        assertTrue("`/Order/Lines`" in ours, ours)
    }

    /**
     * Whether a computation's precondition meets the same check: `checkNormalRule` is reached from the rule
     * parser, and the calculation module parses a computation's condition through the same entry, which only a
     * run can settle.
     */
    @Test
    fun aComputationPreconditionAsA12AnswersIt() {
        val verdicts = COMPUTATIONS.mapValues { (_, row) ->
            val draft = DraftComputation("/Order", "PriceProbe", "../${row.computed}", row.precondition, row.alternatives)
            val a12 = validator.check(order, draft).let { r ->
                if (r.ok()) emptyList() else r.diagnostics().map { it.code().orElse("(no code)") }
            }
            val report = check(ModelSource(validator.inject(order, draft)))
            a12.sorted() to (report.findings.filter { it.justifiesRejection }.map { it.code } +
                listOfNotNull(DECLINED_MARK.takeIf { report.gaps.any { it.at == "/Order/PriceProbe" } })).sorted()
        }
        assertEquals(COMPUTATIONS.mapValues { it.value.a12 }, verdicts.mapValues { it.value.first })
        // As for a rule: where an earlier class answers, a gap beside it is redundant but true.
        assertEquals(verdicts.mapValues { it.value.first }, verdicts.mapValues { (_, v) ->
            if (v.first.isNotEmpty() && v.first.none { it == NEG || it == PAR }) v.second - DECLINED_MARK else v.second
        })
    }

    private companion object {
        const val NEG = "MVK_NEG_CONDITION_IN_ITERATION"
        const val PAR = "MVK_PARALLEL_ITERATION_INVALID"
        const val PARALLEL_NOT_ALLOWED = "MVK_PARALLEL_ITERATION_NOT_ALLOWED"
        const val DECLINED_MARK = "(declined, named in a gap)"
        const val A12_LEVEL_MESSAGE = "Rule with path '/Order/Lines/Parts/GuardProbe': Iteration for '/Order/Lines' is " +
            "specified, but the rule can fire for not filled repetitions of this repetition level. " +
            "[MVK_NEG_CONDITION_IN_ITERATION]"

        /**
         * Computations, each with A12's answer; A12 joins each precondition behind `FieldFilled(<computed>)`. The
         * last row is the one that separates "the joined condition is judged" from "a computation is not judged":
         * a positive presence admits an `And` against a negative, but not against a parallel iteration.
         */
        val COMPUTATIONS: Map<String, ComputationRow> = linkedMapOf(
            "a per-row negative" to ComputationRow("FieldNotFilled(Lines/Sku)"),
            "a parallel negative" to ComputationRow("NoFieldFilled(Lines/Sku, Picks/Ref)"),
            "a per-row negative under Or" to ComputationRow("FieldNotFilled(Lines/Sku) Or FieldNotFilled(Email)"),
            "a common and two own preconditions" to ComputationRow("FieldNotFilled(Lines/Sku)", listOf(
                DraftComputation.Alternative("FieldNotFilled(Email)", "1"),
                DraftComputation.Alternative("FieldFilled(Email)", "2"))),
            "per-row negative preconditions under Or" to ComputationRow(null, listOf(
                DraftComputation.Alternative("FieldNotFilled(Lines/Sku)", "1"),
                DraftComputation.Alternative("FieldNotFilled(Lines/Code)", "2"))),
            "the line's own repetition in the precondition" to ComputationRow("CurrentRepetition(Lines) > 1"),
            "a negative over the picks alone" to ComputationRow("FieldNotFilled(Picks/Ref)"),
            "a part beside a parallel pick, computing the part" to ComputationRow(
                "NoFieldFilled(Lines/Parts/PartNo, Picks/Ref)", computed = "Lines/Parts/Count"),
            // A level the computed field does not iterate is refused earlier, so the gate is never short of one.
            "a part beside a parallel pick, computing the line" to ComputationRow(
                "NoFieldFilled(Lines/Parts/PartNo, Picks/Ref)", a12 = listOf("MVK_NO_WILDCARD")),
            "a part's negative, computing the line" to ComputationRow(
                "FieldNotFilled(Lines/Parts/PartNo)", a12 = listOf("MVK_NO_WILDCARD")),
        )

        data class ComputationRow(
            val precondition: String?,
            val alternatives: List<DraftComputation.Alternative> = listOf(DraftComputation.Alternative(null, "1")),
            val computed: String = "Lines/Price",
            val a12: List<String> = emptyList(),
        )

        private fun order(errorField: String, condition: String, vararg a12: String) =
            Row("/Order", errorField, condition, a12.toList())

        /** Shapes the guard declines, each with A12's own answer. */
        val DECLINED: Map<String, Row> = linkedMapOf(
            // Only a starred reference names the error field while another iterates: the error-field binding is
            // A12's to answer first, so the guard has no verdict here.
            "error field named only starred" to order("Lines/Sku",
                "FieldsNotCollectivelyFilled(Lines*/Sku, Picks/Ref)", "MVK_ERROR_FIELD_AND_ITERATION_INVALID"),
            // A `$` marker beside a parallel iteration, over the picks' own star and over another group's.
            "a marked filter beside a parallel pick" to order("Lines/Sku", "FieldFilled(Picks/Ref) And FieldFilled(Lines/Sku) " +
                "And Sum(Picks*/Weight Having [Picks/LineNo] == [\$Lines/LineNo]) > [Budget]", PARALLEL_NOT_ALLOWED),
            "a marked filter on the wraps beside a parallel pick" to order("Lines/Sku", "FieldFilled(Picks/Ref) And " +
                "FieldFilled(Lines/Sku) And NumberOfFilledFields(Wraps*/Style Having [Wraps/Style] == [\$Lines/Sku]) > [Budget]",
                PARALLEL_NOT_ALLOWED),
            "a marked filter first, beside a parallel pick" to order("Lines/Sku", "NumberOfFilledFields(Wraps*/Style " +
                "Having [Wraps/Style] == [\$Lines/Sku]) > [Budget] And FieldFilled(Picks/Ref)", PARALLEL_NOT_ALLOWED),
        )

        private val PAT = "PatternViolated \"[0-9]+\""

        /** KERNEL-FINDINGS KF608: the leaves, operators, constants and references the guard once declined. */
        val KF608_ROWS: Map<String, Row> = linkedMapOf(
            // A pattern is a comparison no arm reads: admitted, and iterating where its field does. Against
            // "a pattern refuses like a negative" (the first two) and "a pattern iterates always" (the fixed ones).
            "a per-row pattern beside a negative" to order("Lines/Sku", "[Lines/Code] $PAT And FieldNotFilled(Lines/Sku)"),
            "a fixed pattern beside a negative" to order("Lines/Sku", "[Email] $PAT And FieldNotFilled(Lines/Sku)", NEG),
            "a per-row pattern or a positive" to order("Lines/Sku", "[Lines/Code] $PAT Or FieldFilled(Lines/Sku)"),
            "a matched pattern beside a negative" to
                order("Lines/Sku", "[Lines/Code] PatternMatched \"[0-9]+\" And FieldNotFilled(Lines/Sku)"),
            "a fixed pattern or a negative" to order("Lines/Sku", "[Email] $PAT Or FieldNotFilled(Lines/Sku)", NEG),
            "a fixed pattern or a positive" to order("Lines/Sku", "[Email] $PAT Or FieldFilled(Lines/Sku)", NEG),
            "a matched pattern alone" to order("Lines/Code", "[Lines/Code] PatternMatched \"[0-9]+\""),
            "a pattern between a positive and a negative" to order("Lines/Sku",
                "FieldFilled(Lines/Qty) And [Lines/Code] $PAT And FieldNotFilled(Lines/Sku)"),
            "a negative or a pattern" to order("Lines/Sku", "FieldNotFilled(Lines/Sku) Or [Lines/Code] $PAT", NEG),
            "pattern beside a per-row positive" to
                order("Lines/Sku", "[Lines/Code] $PAT And FieldFilled(Lines/Sku)"),
            "pattern alone" to order("Lines/Code", "[Lines/Code] $PAT"),
            // A tolerance comparison takes part where `!=` does: the count's below arm and the mixed constant, not
            // the zero arm. Against "a tolerance is a leaf without arms" (t5, t7) and "it meets zero as == does" (t6).
            "a tolerance between per-row reads" to order("Lines/Qty", "[Lines/Qty] DiffersWithToleranceRange1 [Lines/Price]"),
            "a tolerance against the budget" to order("Lines/Qty", "[Lines/Qty] DiffersWithToleranceRange1 [Budget]"),
            "a per-row tolerance beside a negative" to order("Lines/Sku",
                "[Lines/Qty] DiffersWithToleranceRange1 [Lines/Price] And FieldNotFilled(Lines/Sku)"),
            "a fixed tolerance beside a negative" to order("Lines/Sku",
                "[Budget] DiffersWithToleranceRange1 [Budget] And FieldNotFilled(Lines/Sku)", NEG),
            "a count differing from three" to
                order("Lines/Qty", "NumberOfFilledFields(Lines/Qty, Lines/Price) DiffersWithToleranceRange1 3", NEG),
            "a sum differing from zero" to order("Lines/Qty", "Sum(Lines/Qty, Lines/Price) DiffersWithToleranceRange1 0"),
            "a mixed sum differing from five" to order("Lines/Qty", "Sum(Lines/Qty, Budget) DiffersWithToleranceRange1 5", NEG),
            "a count differing from zero" to
                order("Lines/Qty", "NumberOfFilledFields(Lines/Qty, Lines/Price) DiffersWithToleranceRange10 0"),
            // RepetitionNotUnique has no arm: admitted, iterating where its field does.
            "a repeated line number" to order("Lines/LineNo", "RepetitionNotUnique(Lines/LineNo)"),
            "a repeated line number beside a negative" to
                order("Lines/Sku", "RepetitionNotUnique(Lines/LineNo) And FieldNotFilled(Lines/Sku)"),
            "a repeated line number or a negative" to
                order("Lines/Sku", "RepetitionNotUnique(Lines/LineNo) Or FieldNotFilled(Lines/Sku)", NEG),
            // The field-and-type Valid and Invalid have no arm, unlike the date form's Invalid. Against "the field
            // form's Invalid refuses as the date form's does", which refuses the first and the last two.
            "an invalid address per row" to order("Lines/Code", "Invalid(Lines/Code, \"EMail\")"),
            "a valid address per row" to order("Lines/Code", "Valid(Lines/Code, \"EMail\")"),
            "a valid address beside a negative" to
                order("Lines/Sku", "Valid(Lines/Code, \"EMail\") And FieldNotFilled(Lines/Sku)"),
            "a fixed invalid address beside a positive" to
                order("Lines/Sku", "Invalid(Email, \"EMail\") And FieldFilled(Lines/Sku)"),
            "a fixed valid address beside a negative" to
                order("Lines/Sku", "Valid(Email, \"EMail\") And FieldNotFilled(Lines/Sku)", NEG),
            "an invalid address beside a negative" to
                order("Lines/Sku", "Invalid(Lines/Code, \"EMail\") And FieldNotFilled(Lines/Sku)"),
            "an invalid address or a positive" to
                order("Lines/Sku", "Invalid(Lines/Code, \"EMail\") Or FieldFilled(Lines/Sku)"),
            // The root group is a reference at no repeatable level, as any fixed one is.
            "root group beside a negative" to
                order("Lines/Sku", "GroupFilled(/Order) And FieldNotFilled(Lines/Sku)", NEG),
            "root group between a positive and a negative" to
                order("Lines/Sku", "FieldFilled(Lines/Qty) And GroupFilled(/Order) And FieldNotFilled(Lines/Sku)"),
            "root group or a negative" to order("Lines/Sku", "GroupFilled(/Order) Or FieldNotFilled(Lines/Sku)", NEG),
            "no root group beside a positive" to order("Lines/Sku", "GroupNotFilled(/Order) And FieldFilled(Lines/Sku)"),
            "a positive beside the root group" to order("Lines/Sku", "FieldFilled(Lines/Sku) And GroupFilled(/Order)"),
            "root group or a positive" to order("Lines/Sku", "GroupFilled(/Order) Or FieldFilled(Lines/Sku)", NEG),
            // A constant is parsed to the nearest double, rounded by Math.round, saturating, then cast to an int.
            // Against "exact rounding, then narrowing" (the last two rows), "a saturating int cast" (the first and
            // the saturating rows) and "a plain half-up on the double" (the 0.49999999999999994 row).
            "sum equals 2^32 times 10^9" to order("Lines/Qty", "Sum(Lines/Qty, Lines/Price) == 4294967296000000000", NEG),
            "sum equals 2^32 times 10^9 plus one" to
                order("Lines/Qty", "Sum(Lines/Qty, Lines/Price) == 4294967296000000001", NEG),
            "count below 2^32 times 10^9" to
                order("Lines/Qty", "NumberOfFilledFields(Lines/Qty, Lines/Price) < 4294967296000000000"),
            "count below 2^32 times 10^9 plus one" to
                order("Lines/Qty", "NumberOfFilledFields(Lines/Qty, Lines/Price) < 4294967296000000001"),
            "sum equals 2^32 times 10^9 and a fraction" to
                order("Lines/Qty", "Sum(Lines/Qty, Lines/Price) == 4294967296000000000.4", NEG),
            "sum equals minus 2^32 times 10^9" to
                order("Lines/Qty", "Sum(Lines/Qty, Lines/Price) == -4294967296000000000", NEG),
            "count below twenty nines" to
                order("Lines/Qty", "NumberOfFilledFields(Lines/Qty, Lines/Price) < 99999999999999999999"),
            "sum equals 2^64" to order("Lines/Qty", "Sum(Lines/Qty, Lines/Price) == 18446744073709551616"),
            "count below 2^32 plus one" to order("Lines/Qty", "NumberOfFilledFields(Lines/Qty, Lines/Price) < 4294967297", NEG),
            "count below a nineteen-digit near-half" to
                order("Lines/Qty", "NumberOfFilledFields(Lines/Qty, Lines/Price) < 0.4999999999999999999", NEG),
            "count below the double before a half" to
                order("Lines/Qty", "NumberOfFilledFields(Lines/Qty, Lines/Price) < 0.49999999999999994"),
            "count below a midpoint above 2^53" to
                order("Lines/Qty", "NumberOfFilledFields(Lines/Qty, Lines/Price) < 9007199254740993.00001", NEG),
            "sum equals an exponent" to order("Lines/Qty", "Sum(Lines/Qty, Lines/Price) == 1E3", "MVK_UNEXPECTED_TOKEN"),
            // A product sum meets only the zero arm. Against "it is an amount" (the != row) and "a count" (< 5).
            "product sum equals zero beside a positive" to order("Lines/Qty",
                "SumOfProducts(Lines/Parts*/Count, Lines/Parts*/Count) == 0 And FieldFilled(Lines/Qty)"),
            "product sum above zero beside a negative" to order("Lines/Qty",
                "SumOfProducts(Lines/Parts*/Count, Lines/Parts*/Count) > 0 And FieldNotFilled(Lines/Qty)"),
            "product sum equals zero beside a negative" to order("Lines/Qty",
                "SumOfProducts(Lines/Parts*/Count, Lines/Parts*/Count) == 0 And FieldNotFilled(Lines/Qty)", NEG),
            "product sum below five beside a negative" to order("Lines/Qty",
                "SumOfProducts(Lines/Parts*/Count, Lines/Parts*/Count) < 5 And FieldNotFilled(Lines/Qty)"),
            "product sum not five beside a negative" to order("Lines/Qty",
                "SumOfProducts(Lines/Parts*/Count, Lines/Parts*/Count) != 5 And FieldNotFilled(Lines/Qty)"),
            // A group in a field list counts as its fields: billing is two. Against "a group counts once", which
            // admits the first row.
            "MoreThanOne, billing" to
                order("Lines/Sku", "MoreThanOneFieldFilled(Lines/Qty, Billing) And FieldNotFilled(Lines/Sku)", NEG),
            "AtLeastOne, billing" to
                order("Lines/Sku", "AtLeastOneFieldFilled(Lines/Qty, Billing) And FieldNotFilled(Lines/Sku)", NEG),
            "not together, billing" to
                order("Lines/Sku", "FieldsNotCollectivelyFilled(Lines/Qty, Billing) And FieldNotFilled(Lines/Sku)", NEG),
            "none of the line and billing" to order("Lines/Sku", "NoFieldFilled(Lines/Sku, Billing)", NEG),
            "no billing beside a positive" to order("Lines/Sku", "NoFieldFilled(Billing) And FieldFilled(Lines/Sku)"),
            "all billing beside a negative" to
                order("Lines/Sku", "AllFieldsFilled(Billing) And FieldNotFilled(Lines/Sku)", NEG),
            "AtLeastOne, starred slots" to
                order("Lines/Sku", "AtLeastOneFieldFilled(Lines/Sku, Lines/Slots*) And FieldNotFilled(Lines/Sku)"),
            "not all billing beside a positive" to
                order("Lines/Sku", "NotAllFieldsFilled(Billing) And FieldFilled(Lines/Sku)"),
            "AtLeastOne of the line and billing, guarded" to
                order("Lines/Sku", "AtLeastOneFieldFilled(Lines/Sku, Billing) And FieldFilled(Lines/Qty)"),
            "MoreThanOne, starred bows" to order("Lines/Sku",
                "MoreThanOneFieldFilled(Lines/Qty, Wraps*/Bows) And FieldNotFilled(Lines/Sku)", "MVK_NO_WILDCARD"),
            // A `$`-marked reference adds the levels it iterates and iterates nothing itself. Against "it iterates
            // as a reference outside the filter does" (the first and third rows) and "the filter is ignored" (the
            // third and the parts row).
            "a negative beside a marked sum" to order("Lines/Sku", "FieldNotFilled(Lines/Sku) And " +
                "Sum(Picks*/Weight Having [Picks/LineNo] == [\$Lines/LineNo]) > [Budget]", NEG),
            "a positive beside a marked sum" to order("Lines/Sku",
                "FieldFilled(Lines/Sku) And Sum(Picks*/Weight Having [Picks/LineNo] == [\$Lines/LineNo]) > 0"),
            "a marked sum beside a fixed positive" to order("Lines/Sku",
                "Sum(Picks*/Weight Having [Picks/Ref] == [\$Lines/Sku]) > [Budget] And FieldFilled(Email)", NEG),
            "a marked sum alone" to order("Lines/Sku", "Sum(Picks*/Weight Having [Picks/Ref] == [\$Lines/Sku]) > [Budget]"),
            "a marked sum between a fixed and a per-row positive" to order("Lines/Sku", "FieldFilled(Email) And " +
                "Sum(Picks*/Weight Having [Picks/Ref] == [\$Lines/Sku]) > [Budget] And FieldFilled(Lines/Code)"),
            "a marked part beside its own positive" to order("Lines/Parts/PartNo", "FieldFilled(Lines/Parts/PartNo) " +
                "And Sum(Picks*/Weight Having [Picks/Ref] == [\$Lines/Parts/PartNo]) > [Budget]"),
            "a marked part beside a line's positive" to order("Lines/Parts/PartNo", "FieldFilled(Lines/Sku) " +
                "And Sum(Picks*/Weight Having [Picks/Ref] == [\$Lines/Parts/PartNo]) > [Budget]", NEG),
            "a negative beside a marked count" to order("Lines/Sku", "FieldNotFilled(Lines/Sku) And " +
                "NumberOfFilledFields(Wraps*/Style Having [Wraps/Style] == [\$Lines/Sku]) > [Budget]", NEG),
            "a marked count below three, guarded" to order("Lines/Sku",
                "NumberOfFilledFields(Wraps*/Style Having [Wraps/Style] == [\$Lines/Sku]) < 3 And FieldFilled(Lines/Sku)"),
            "a guarded marked count below three" to order("Lines/Sku",
                "FieldFilled(Lines/Sku) And NumberOfFilledFields(Wraps*/Style Having [Wraps/Style] == [\$Lines/Sku]) < 3"),
            "a marked line number outside a filter" to order("Lines/Qty",
                "FieldFilled(Lines/Qty) And Sum(Lines/Parts*/Count Having [\$Lines/Qty] > 1) > 0", "MVK_NO_ITERATION_FOR_WILDCARD"),
            // A group target iterated in parallel is judged as a field target is. Against "the parallel pass alone
            // decides a group target", which admits the first row and the parts-or-pick row.
            "a fixed and a parallel positive around the parts" to order("Lines/Parts",
                "FieldFilled(Email) And GroupNotFilled(Lines/Parts) And FieldFilled(Picks/Ref)", NEG),
            "the parts beside a parallel negative" to
                order("Lines/Parts", "GroupFilled(Lines/Parts) And FieldNotFilled(Picks/Ref)"),
            "no parts or a parallel positive" to
                order("Lines/Parts", "GroupNotFilled(Lines/Parts) Or FieldFilled(Picks/Ref)", PAR),
            "a parallel positive beside no parts" to
                order("Lines/Parts", "FieldFilled(Picks/Ref) And GroupNotFilled(Lines/Parts)", NEG),
            "the parts or a parallel positive" to
                order("Lines/Parts", "GroupFilled(Lines/Parts) Or FieldFilled(Picks/Ref)", NEG),
            "a parallel and a part positive beside no parts" to order("Lines/Parts",
                "FieldFilled(Picks/Ref) And FieldFilled(Lines/Parts/PartNo) And GroupNotFilled(Lines/Parts)"),
            // A field key's level is marked parallel with the rest. Against "a key keeps its own iteration", which
            // refuses the first two rows on the parallel pass.
            "a keyed pick beside a parallel negative" to order("Lines/Sku",
                "FieldFilled(Picks/Ref For Lines/LineNo) And NoFieldFilled(Lines/Sku, Picks/Ref)"),
            "a keyed pick after a parallel negative" to order("Lines/Sku",
                "FieldFilled(Email) And NoFieldFilled(Lines/Sku, Picks/Ref) And FieldFilled(Picks/Ref For Lines/LineNo)"),
            "no keyed pick beside a parallel positive" to order("Lines/Sku",
                "FieldNotFilled(Picks/Ref For Lines/LineNo) And AtLeastOneFieldFilled(Lines/Sku, Picks/Ref)"),
            "no keyed pick between positives" to order("Lines/Sku",
                "FieldFilled(Lines/Sku) And FieldNotFilled(Picks/Ref For Lines/LineNo) And FieldFilled(Picks/Ref)"),
            "a keyed pick or a parallel negative" to order("Lines/Sku",
                "FieldFilled(Picks/Ref For Lines/LineNo) Or NoFieldFilled(Lines/Sku, Picks/Ref)", PAR),
            "a keyed pick beside no pick and the line" to order("Lines/Sku",
                "FieldFilled(Picks/Ref For Lines/LineNo) And FieldNotFilled(Picks/Ref) And FieldFilled(Lines/Sku)"),
            // A constant selection still iterating a level above it never reaches the guard beside a parallel
            // iteration: its chain then declares two index fields.
            "a selected tag beside a parallel pick" to order("Lines/Parts/PartNo", "FieldFilled(Picks/Ref) And " +
                "FieldFilled(Lines/Parts/PartNo) And FieldNotFilled(Lines/Parts/Tags/Label For \"t\")", "MVK_MULTIPLE_INDEX_FIELDS"),
        )

        val ROWS: Map<String, Row> = linkedMapOf(
            // The baseline: a negative over the rule's own iteration, and its positive guard.
            "unguarded negative" to order("Lines/Sku", "FieldNotFilled(Lines/Sku)", NEG),
            "guarded by a per-row positive" to order("Lines/Sku", "FieldFilled(Lines/Qty) And FieldNotFilled(Lines/Sku)"),
            // A selection keyed by a field iterates wherever its key does (KF601), and a group target is guarded at
            // its own level as a field target is.
            "a field-keyed selection beside a per-row positive" to
                order("Lines/Qty", "FieldFilled(Lines/Qty) And FieldNotFilled(Picks/Ref For Lines/LineNo)"),
            "group target" to order("Lines/Parts", "GroupNotFilled(Lines/Parts)", NEG),
            // A positive at no repeatable level guards nothing (KF537), against "any positive guards".
            "guarded by a fixed positive" to order("Lines/Sku", "FieldFilled(Email) And FieldNotFilled(Lines/Sku)", NEG),
            // Or needs every part, against "one part suffices as under And".
            "Or with a per-row positive" to order("Lines/Sku", "FieldFilled(Lines/Qty) Or FieldNotFilled(Lines/Sku)", NEG),
            // A non-iterating member makes AtLeastOne refuse at a threshold of one.
            "AtLeastOne with a fixed member" to
                order("Lines/Sku", "AtLeastOneFieldFilled(Lines/Qty, Email) And FieldNotFilled(Lines/Sku)", NEG),
            "AtLeastOne all per-row" to
                order("Lines/Sku", "AtLeastOneFieldFilled(Lines/Qty, Lines/Price) And FieldNotFilled(Lines/Sku)"),
            // MoreThanOne refuses only at two non-iterating members, against "threshold one like AtLeastOne".
            "MoreThanOne, one fixed member" to
                order("Lines/Sku", "MoreThanOneFieldFilled(Lines/Qty, Email) And FieldNotFilled(Lines/Sku)"),
            "MoreThanOne, two fixed members" to
                order("Lines/Sku", "MoreThanOneFieldFilled(Lines/Qty, Email, Phone) And FieldNotFilled(Lines/Sku)", NEG),
            // A starred member counts as many as its level repeats, against "a member counts once".
            "MoreThanOne, one starred member" to
                order("Lines/Sku", "MoreThanOneFieldFilled(Lines/Qty, Picks*/Ref) And FieldNotFilled(Lines/Sku)", NEG),
            // AllFieldsFilled is judged only with a single member, against "it refuses like AtLeastOne".
            "AllFieldsFilled with a fixed member" to
                order("Lines/Sku", "AllFieldsFilled(Lines/Qty, Email) And FieldNotFilled(Lines/Sku)"),
            // Counts compared below a positive constant need a filled row. An aggregate takes two operands or more.
            "count below one" to order("Lines/Qty", "NumberOfFilledFields(Lines/Qty, Lines/Price) < 1", NEG),
            "count above zero as a guard" to
                order("Lines/Sku", "NumberOfFilledFields(Lines/Qty, Lines/Price) > 0 And FieldNotFilled(Lines/Sku)"),
            // A sum compared equal to zero, with A12's constant rounded to a whole number first.
            "sum equals zero" to order("Lines/Qty", "Sum(Lines/Qty, Lines/Price) == 0", NEG),
            "sum equals 0.4, rounding to zero" to order("Lines/Qty", "Sum(Lines/Qty, Lines/Price) == 0.4", NEG),
            "sum equals 0.5, rounding to one" to order("Lines/Qty", "Sum(Lines/Qty, Lines/Price) == 0.5"),
            "sum equals 2^32, narrowing to zero" to
                order("Lines/Qty", "Sum(Lines/Qty, Lines/Price) == 4294967296", NEG),
            "field read equals zero" to order("Lines/Qty", "[Lines/Qty] == 0", NEG),
            "field read above zero" to order("Lines/Qty", "[Lines/Qty] > 0"),
            // Per-row and fixed members against a constant, whatever the comparison.
            "count over mixed members" to order("Lines/Qty", "NumberOfFilledFields(Lines/Qty, Email) > 1", NEG),
            // CurrentRepetition over the judged level refuses wherever it sits.
            "CurrentRepetition under Or" to
                order("Lines/Sku", "FieldFilled(Lines/Sku) Or CurrentRepetition(Lines) > 2", NEG),
            "CurrentRepetition under And" to
                order("Lines/Sku", "FieldFilled(Lines/Sku) And CurrentRepetition(Lines) > 2"),
            "Invalid date per row" to order("Lines/Day", "Invalid(Date(Lines/Day, Lines/Month, Lines/Year))", NEG),
            "Invalid date, guarded" to order("Lines/Day",
                "AllFieldsFilled(Lines/Day, Lines/Month, Lines/Year) And Invalid(Date(Lines/Day, Lines/Month, Lines/Year))"),
            "none in a value list" to order("Lines/Sku", "NoFieldValueIncludedInValueList(Lines/Sku In \"X\", \"Y\")", NEG),
            "none in a value list, guarded" to
                order("Lines/Sku", "FieldFilled(Lines/Qty) And NoFieldValueIncludedInValueList(Lines/Sku In \"X\")"),
            "fixed field not in a per-row list" to
                order("Lines/Code", "FieldValueNotIncludedInValueList(Tier, [Lines/Code])", NEG),
            "fixed field in a mixed list" to
                order("Lines/Code", "FieldValueIncludedInValueList(Tier, [Lines/Code], [Email])", NEG),
            "fixed field in a per-row list" to order("Lines/Code", "FieldValueIncludedInValueList(Tier, [Lines/Code])"),
            // A level repeating exactly twice with an index field is exempt: against "every repeatable level is
            // judged" (the keyed rows), "a two-row level is always exempt" (the unkeyed one) and "any keyed level is
            // exempt" (the fifty-row lines above).
            "two-row keyed level, deeper field" to order("Gifts/Card/Text", "FieldNotFilled(Gifts/Card/Text)"),
            "two-row keyed level, its own field" to order("Gifts/Note", "FieldNotFilled(Gifts/Note)"),
            "two-row unkeyed level, deeper field" to order("Wraps/Ribbon/Color", "FieldNotFilled(Wraps/Ribbon/Color)", NEG),
            // Parallel lines and picks keyed by one index name (KF428).
            "parallel negative" to order("Lines/Sku", "NoFieldFilled(Lines/Sku, Picks/Ref)", PAR),
            "parallel positive" to order("Lines/Sku", "AtLeastOneFieldFilled(Lines/Sku, Picks/Ref)"),
            "parallel negative, per-row guard" to
                order("Lines/Sku", "FieldFilled(Lines/Qty) And NoFieldFilled(Lines/Sku, Picks/Ref)"),
            "parallel negative, fixed guard" to
                order("Lines/Sku", "FieldFilled(Email) And NoFieldFilled(Lines/Sku, Picks/Ref)", PAR),
            // Levels are tried shallowest first: the lines level passes, the parts level fails.
            "nested levels, outer guard only" to Row("/Order/Lines/Parts", "PartNo",
                "FieldFilled(../Sku) And FieldNotFilled(PartNo)", listOf(NEG)),
            "nested levels, inner guard" to Row("/Order/Lines/Parts", "PartNo",
                "FieldFilled(Count) And FieldNotFilled(PartNo)", listOf()),
            "rule inside the repetition" to Row("/Order/Lines", "Sku", "FieldNotFilled(Sku)", listOf(NEG)),
            // A negative literal is a constant too, rounded half up on its sign: -0.4 and -0.5 to zero, -0.6 not.
            "sum equals -0.4" to order("Lines/Qty", "Sum(Lines/Qty, Lines/Price) == -0.4", NEG),
            "sum equals -0.5" to order("Lines/Qty", "Sum(Lines/Qty, Lines/Price) == -0.5", NEG),
            "sum equals -0.6" to order("Lines/Qty", "Sum(Lines/Qty, Lines/Price) == -0.6"),
            "sum equals -0.50, a trailing zero" to order("Lines/Qty", "Sum(Lines/Qty, Lines/Price) == -0.50", NEG),
            "sum equals - 0.4, spaced sign" to order("Lines/Qty", "Sum(Lines/Qty, Lines/Price) == - 0.4", NEG),
            "count over mixed members, 16-digit bound" to
                order("Lines/Qty", "NumberOfFilledFields(Lines/Qty, Email) > 1234567890123456", NEG),
            // A bracket only groups: the operation or constant inside it is what is compared.
            "bracketed zero" to order("Lines/Qty", "Sum(Lines/Qty, Lines/Price) == {0}", NEG),
            "bracketed sum" to order("Lines/Qty", "{Sum(Lines/Qty, Lines/Price)} == 0", NEG),
            "bracketed count" to order("Lines/Qty", "{NumberOfFilledFields(Lines/Qty, Lines/Price)} < 1", NEG),
            // Every operator of the two arms, and the constant on the left.
            "count at most one" to order("Lines/Qty", "NumberOfFilledFields(Lines/Qty, Lines/Price) <= 1", NEG),
            "count not one" to order("Lines/Qty", "NumberOfFilledFields(Lines/Qty, Lines/Price) != 1", NEG),
            "count above one" to order("Lines/Qty", "NumberOfFilledFields(Lines/Qty, Lines/Price) > 1"),
            "one above the count" to order("Lines/Qty", "1 > NumberOfFilledFields(Lines/Qty, Lines/Price)", NEG),
            "one at least the count" to order("Lines/Qty", "1 >= NumberOfFilledFields(Lines/Qty, Lines/Price)", NEG),
            "one not the count" to order("Lines/Qty", "1 != NumberOfFilledFields(Lines/Qty, Lines/Price)", NEG),
            "one below the count" to order("Lines/Qty", "1 < NumberOfFilledFields(Lines/Qty, Lines/Price)"),
            "sum at least zero" to order("Lines/Qty", "Sum(Lines/Qty, Lines/Price) >= 0", NEG),
            "sum at most zero" to order("Lines/Qty", "Sum(Lines/Qty, Lines/Price) <= 0", NEG),
            "sum below zero" to order("Lines/Qty", "Sum(Lines/Qty, Lines/Price) < 0"),
            "zero equals the sum" to order("Lines/Qty", "0 == Sum(Lines/Qty, Lines/Price)", NEG),
            // Each comparison kind on the arm it is, and is not, subject to.
            "value count equals zero" to order("Lines/Qty", "NumberOfValueInFields(0 In Lines/Qty, Lines/Price) == 0", NEG),
            "distinct values below one" to order("Lines/Sku", "NumberOfDifferentValues(Lines/Sku, Lines/Code) < 1", NEG),
            "minimum equals zero" to order("Lines/Qty", "MinValue(Lines/Qty, Lines/Price) == 0", NEG),
            "maximum above zero" to order("Lines/Qty", "MaxValue(Lines/Qty, Lines/Price) > 0"),
            "first filled at most zero" to order("Lines/Qty", "FirstFilledValue(Lines/Qty, Lines/Price) <= 0", NEG),
            "absolute difference equals zero" to order("Lines/Qty", "Abs([Lines/Qty] - [Lines/Price]) == 0", NEG),
            "maximum of mixed reads" to order("Lines/Qty", "Max([Lines/Qty], [Budget]) > 5", NEG),
            "length equals zero" to order("Lines/Sku", "Length(Lines/Sku) == 0", NEG),
            "length above zero" to order("Lines/Sku", "Length(Lines/Sku) > 0"),
            "day of shipping equals zero" to order("Lines/ShipOn", "DayFromDate(Lines/ShipOn) == 0", NEG),
            "days since ordering, mixed" to order("Lines/ShipOn", "DifferenceInDays(Lines/ShipOn, OrderedOn) > 5"),
            // The remaining presence and list leaves.
            "not all filled" to order("Lines/Sku", "NotAllFieldsFilled(Lines/Sku, Lines/Qty)", NEG),
            "not exactly one filled" to order("Lines/Sku", "NotExactlyOneFieldFilled(Lines/Sku, Lines/Qty)", NEG),
            "no ribbon, colour filled" to
                order("Wraps/Ribbon/Color", "NoGroupFilled(Wraps/Ribbon) And FieldFilled(Wraps/Ribbon/Color)"),
            "not every ribbon, or no colour" to
                order("Wraps/Ribbon/Color",
                    "NotAllGroupsFilled(Wraps/Ribbon, Wraps/Tag) Or FieldNotFilled(Wraps/Ribbon/Color)", NEG),
            "ribbon and tag not together, colour missing" to order("Wraps/Ribbon/Color",
                "GroupsNotCollectivelyFilled(Wraps/Ribbon, Wraps/Tag) And FieldNotFilled(Wraps/Ribbon/Color)"),
            "a ribbon, colour missing" to
                order("Wraps/Ribbon/Color", "AtLeastOneGroupFilled(Wraps/Ribbon) And FieldNotFilled(Wraps/Ribbon/Color)"),
            "ribbon filled, colour missing" to
                order("Wraps/Ribbon/Color", "GroupFilled(Wraps/Ribbon) And FieldNotFilled(Wraps/Ribbon/Color)"),
            "not all codes listed" to order("Lines/Code", "NotAllFieldValuesIncludedInValueList(Lines/Code In \"X\")"),
            "not all codes listed, fixed tier" to
                order("Lines/Code", "NotAllFieldValuesIncludedInValueList(Tier, Lines/Code In \"X\")", NEG),
            "a code listed" to order("Lines/Code", "AtLeastOneFieldValueIncludedInValueList(Lines/Code In \"X\")"),
            "a code listed, fixed tier" to
                order("Lines/Code", "AtLeastOneFieldValueIncludedInValueList(Tier, Lines/Code In \"X\")", NEG),
            "valid date per row" to order("Lines/Day", "Valid(Date(Lines/Day, Lines/Month, Lines/Year))"),
            "window overlaps the season" to order("Lines/Window", "DateRangesOverlap(Lines/Window, Season)"),
            // Parallel is decided before the ordinary iteration: both refuse here, and A12 names the parallel one.
            "parallel and nested negative" to order("Lines/Parts/PartNo", "NoFieldFilled(Lines/Parts/PartNo, Picks/Ref)", PAR),
            // A constant selection fixes the level it selects: it neither iterates it, nor is it parallel, nor does
            // it multiply. Against "a selected reference iterates its row", "a selection is parallel" and "a
            // selection counts as its level's repeats", in that order.
            "a selected positive beside a per-row negative" to
                order("Lines/Qty", "FieldNotFilled(Lines/Qty) And FieldFilled(Lines/Sku For \"1\")", NEG),
            "a per-row positive beside a selected negative" to
                order("Lines/Qty", "FieldFilled(Lines/Qty) And FieldNotFilled(Lines/Sku For \"1\")"),
            "a selected pick beside a per-row negative" to
                order("Lines/Sku", "NoFieldFilled(Lines/Sku, Picks/Ref For \"1\")", NEG),
            "MoreThanOne, one selected member" to order("Lines/Sku",
                "MoreThanOneFieldFilled(Lines/Qty, Picks/Ref For \"1\") And FieldNotFilled(Lines/Sku)"),
            "MoreThanOne, two selected members" to order("Lines/Sku",
                "MoreThanOneFieldFilled(Lines/Qty, Picks/Ref For \"1\", Picks/Ref For \"2\") And FieldNotFilled(Lines/Sku)", NEG),
            // A selection fixes only its own level: the wrap it sits in still iterates. Against "a selected
            // reference iterates nothing", which refuses the first row.
            "a selected bow guarding the wrap" to
                order("Wraps/Style", "FieldFilled(Wraps/Bows/Shade For \"a\") And FieldNotFilled(Wraps/Style)"),
            "the wrap guarding a selected bow" to
                order("Wraps/Style", "FieldFilled(Wraps/Style) And FieldNotFilled(Wraps/Bows/Shade For \"a\")"),
            // The text A12 joins the parts computation below into, written as a rule: admitted, as the computation is.
            "a parts computation's joined text" to order("Lines/Parts/Count",
                "FieldFilled(Lines/Parts/Count) And (NoFieldFilled(Lines/Parts/PartNo, Picks/Ref)\n) And [Lines/Parts/Count] != 1"),
            // An earlier class answers first: a constant with more decimals than the operation carries, which is
            // also why a constant's leading fractional zeros never reach this check.
            "sum equals a 19-digit fraction" to
                order("Lines/Qty", "Sum(Lines/Qty, Lines/Price) == 0.4000000000000000001", "MVK_INVALID_COMPARE_DEC_PLACES"),
            "error field not referenced" to order("Lines/Sku", "FieldNotFilled(Lines/Qty)", "MVK_ERROR_FIELD_NOT_REFERENCED"),
        ) + KF608_ROWS

    }
}
