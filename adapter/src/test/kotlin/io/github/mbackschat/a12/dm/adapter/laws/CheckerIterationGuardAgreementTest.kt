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
 * parallel groups (ITERATION-GUARD-PORT-PROPOSAL; CONSISTENCY-GAPS DG146).
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
                    .numberField("Count") { n -> n.fractionDigits(0) } } }
            .group("Picks") { p -> p.repeatable(10).indexField("LineNo")
                .stringField("LineNo") { }
                .stringField("Ref") { } }
            .group("Gifts") { g -> g.repeatable(2).indexField("GiftNo")
                .stringField("GiftNo") { }
                .stringField("Note") { }
                .group("Card") { c -> c.stringField("Text") { } } }
            .group("Wraps") { w -> w.repeatable(2)
                .stringField("Style") { }
                .group("Ribbon") { r -> r.stringField("Color") { } }
                .group("Tag") { t -> t.stringField("Label") { } } } }
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
     * The shapes this checker does not judge for the law. A12's answer is pinned like any row's; the checker must
     * stay silent on the law there and name the rule in a gap, so an unreported refusal is never read as examined.
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
        val verdicts = COMPUTATIONS.mapValues { (_, precondition) ->
            val draft = DraftComputation("/Order", "PriceProbe", "../Lines/Price", precondition,
                listOf(DraftComputation.Alternative(null, "1")))
            val a12 = validator.check(order, draft).let { r ->
                if (r.ok()) emptyList() else r.diagnostics().map { it.code().orElse("(no code)") }
            }
            val report = check(ModelSource(validator.inject(order, draft)))
            a12.sorted() to (report.findings.filter { it.justifiesRejection }.map { it.code } +
                listOfNotNull(DECLINED_MARK.takeIf { report.gaps.any { it.at == "/Order/PriceProbe" } })).sorted()
        }
        assertEquals(COMPUTATIONS.mapValues { emptyList<String>() }, verdicts.mapValues { it.value.first })
        assertEquals(verdicts.mapValues { it.value.first }, verdicts.mapValues { it.value.second })
    }

    private companion object {
        const val NEG = "MVK_NEG_CONDITION_IN_ITERATION"
        const val PAR = "MVK_PARALLEL_ITERATION_INVALID"
        const val DECLINED_MARK = "(declined, named in a gap)"
        const val A12_LEVEL_MESSAGE = "Rule with path '/Order/Lines/Parts/GuardProbe': Iteration for '/Order/Lines' is " +
            "specified, but the rule can fire for not filled repetitions of this repetition level. " +
            "[MVK_NEG_CONDITION_IN_ITERATION]"

        /** Preconditions of a computation on each line's price; A12 joins each behind `FieldFilled(Lines/Price)`. */
        val COMPUTATIONS = linkedMapOf(
            "a per-row negative" to "FieldNotFilled(Lines/Sku)",
            "a parallel negative" to "NoFieldFilled(Lines/Sku, Picks/Ref)",
            "a per-row negative under Or" to "FieldNotFilled(Lines/Sku) Or FieldNotFilled(Email)",
        )

        private fun order(errorField: String, condition: String, vararg a12: String) =
            Row("/Order", errorField, condition, a12.toList())

        /** Shapes the guard declines, each with A12's own answer. */
        val DECLINED: Map<String, Row> = linkedMapOf(
            // A pattern leaf is outside the measured leaf set; A12 admits it as a leaf that iterates.
            "pattern beside a per-row positive" to
                order("Lines/Sku", "[Lines/Code] PatternViolated \"[0-9]+\" And FieldFilled(Lines/Sku)"),
            "pattern alone" to order("Lines/Code", "[Lines/Code] PatternViolated \"[0-9]+\""),
            // A root group read beside a negative: A12 judges it apart from the entity list.
            "root group beside a negative" to
                order("Lines/Sku", "GroupFilled(/Order) And FieldNotFilled(Lines/Sku)", NEG),
            // Only a starred reference names the error field while another iterates: the error-field binding is
            // A12's to answer first, so the guard has no verdict here.
            "error field named only starred" to order("Lines/Sku",
                "FieldsNotCollectivelyFilled(Lines*/Sku, Picks/Ref)", "MVK_ERROR_FIELD_AND_ITERATION_INVALID"),
            // Constants beyond the exact range a double holds: A12 still compares them, the checker declines.
            "sum equals 2^32 times 10^9" to order("Lines/Qty", "Sum(Lines/Qty, Lines/Price) == 4294967296000000000", NEG),
            // A group as the error target.
            "group target" to order("Lines/Parts", "GroupNotFilled(Lines/Parts)", NEG),
        )

        val ROWS: Map<String, Row> = linkedMapOf(
            // The baseline: a negative over the rule's own iteration, and its positive guard.
            "unguarded negative" to order("Lines/Sku", "FieldNotFilled(Lines/Sku)", NEG),
            "guarded by a per-row positive" to order("Lines/Sku", "FieldFilled(Lines/Qty) And FieldNotFilled(Lines/Sku)"),
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
            // An earlier class answers first: a constant with more decimals than the operation carries, which is
            // also why a constant's leading fractional zeros never reach this check.
            "sum equals a 19-digit fraction" to
                order("Lines/Qty", "Sum(Lines/Qty, Lines/Price) == 0.4000000000000000001", "MVK_INVALID_COMPARE_DEC_PLACES"),
            "error field not referenced" to order("Lines/Sku", "FieldNotFilled(Lines/Qty)", "MVK_ERROR_FIELD_NOT_REFERENCED"),
        )
    }
}
