package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.RoundingMode
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Where the whole-rule gates sit against everything else (a12-kernel-lean SPEC-2026-10-09-03). Kernel 30.8.1's
 * `ParserService.checkNormalRule` runs them only once the whole condition has parsed, in this order: the
 * uniqueness check beside a parallel iteration, a filter on a parallel-iterated operand, a filter body that
 * parallel-iterates, a semantic index in a filter body, a second `RepetitionNotUnique`, a raw-type `Length`.
 * Every gate raised WHILE parsing therefore answers first, wherever it is written.
 *
 * One boundary needs both of its sides measured: an UNMARKED index read in a filter body is refused by the
 * filter walk at the filtered operand ([KF377]), a parse-time site, while a `$`-marked one is skipped there and
 * reaches only the whole-rule gate ([KF487]). So a duplicate written after the filter beats the marked read and
 * loses to the unmarked one.
 *
 * Own domain (an e-commerce order whose lines and picks share an index field).
 */
class CheckerPostParseOrderAgreementTest {

    private val order = DocumentModelDraft.named("order-post-parse").locale("en_US")
        .rootGroup("Order") { o -> o
            .stringField("Reference") { }
            .stringField("Raw") { s -> s.lineBreaks().noValueValidation() }
            .numberField("Total") { n -> n.fractionDigits(0) }
            .group("Lines") { g -> g.repeatable(20).indexField("LineRef")
                .stringField("LineRef") { }
                .stringField("Sku") { }
                .numberField("Qty") { n -> n.fractionDigits(0) } }
            .group("Picks") { g -> g.repeatable(20).indexField("LineRef")
                .stringField("LineRef") { }
                .numberField("PickedQty") { n -> n.fractionDigits(0) } }
            .group("Shipping") { g -> g
                .stringField("Carrier") { }
                .group("Address") { a -> a.stringField("Street") { } } } }
        .build().toJson()

    private val validator = RuleValidator()

    @Test
    fun aParseTimeGateAnswersAheadOfEveryWholeRuleGate() {
        assertAgreement(linkedMapOf(
            "a marked filter index — control" to (atRoot(MARKED) to INDEX),
            "an unmarked filter index — control" to (atRoot(UNMARKED) to INDEX),
            "a marked filter index, then a duplicate" to (atRoot("$MARKED And $DUPLICATE") to DUPLICATE_CLASS),
            "an unmarked filter index, then a duplicate" to (atRoot("$UNMARKED And $DUPLICATE") to INDEX),
            "a duplicate, then an unmarked filter index" to (atRoot("$DUPLICATE And $UNMARKED") to DUPLICATE_CLASS),
            "a marked filter index repeated in one list" to (atRoot(
                "Sum(Lines*/Qty Having [Lines/Qty] > 0 And NoGroupFilled(\$/Order/Picks For \"A\", " +
                    "\$/Order/Picks For \"A\")) > 0") to DUPLICATE_CLASS),
            // The capture keeps the two apart, so no duplicate is raised and the plain one's filter walk answers.
            "one key selected plainly and through the capture" to (atRoot(
                "Sum(Lines*/Qty Having [Lines/Qty] > 0 And NoGroupFilled(Picks For \"A\", " +
                    "\$/Order/Picks For \"A\")) > 0") to INDEX),
            "a raw Length beside another conjunct — control" to (atRoot("Length(Raw) > 5") to RAW_LENGTH),
            "a raw Length, then a duplicate" to (atRoot("Length(Raw) > 5 And $DUPLICATE") to DUPLICATE_CLASS),
        ))
    }

    @Test
    fun theWholeRuleGatesAnswerInTheirOwnOrder() {
        assertAgreement(linkedMapOf(
            "a raw Length and a marked filter index" to (atRoot("Length(Raw) > 5 And $MARKED") to INDEX),
            "two uniqueness checks — control" to (inLines("$UNIQUE And $SECOND_UNIQUE") to COMBINATION),
            "two uniqueness checks and a raw Length" to
                (inLines("$UNIQUE And $SECOND_UNIQUE And Length(/Order/Raw) > 5") to COMBINATION),
            "a parallel read beside uniqueness — control" to (inLines("$UNIQUE And $PARALLEL_READ") to PARALLEL),
            "a parallel read beside two uniqueness checks" to
                (inLines("$UNIQUE And $PARALLEL_READ And $SECOND_UNIQUE") to PARALLEL),
            "a parallel read beside uniqueness and a raw Length" to
                (inLines("$UNIQUE And $PARALLEL_READ And Length(/Order/Raw) > 5") to PARALLEL),
        ))
    }

    /**
     * The filter-body parallel gate, reached through the capture: a marked read is skipped by the filter walk
     * that refuses an unmarked one first ([KF353]), so the body's parallel read reaches the whole-rule gate.
     */
    @Test
    fun aCapturedParallelReadReachesTheFilterBodyGate() {
        assertAgreement(linkedMapOf(
            "a parallel read alone — control" to (inLine("FieldFilled(Qty) And $PARALLEL_READ") to ADMITTED),
            "a captured parallel read in a filter" to (inLine(
                "FieldFilled(Qty) And Sum(/Order/Picks*/PickedQty Having [/Order/Picks/PickedQty] > 0 And [\$/Order/Picks/PickedQty] > 0) > 0") to PARALLEL),
            "the same filter reading the rule's own row — control" to (inLine(
                "FieldFilled(Qty) And Sum(/Order/Picks*/PickedQty Having [/Order/Picks/PickedQty] > 0 And [\$/Order/Lines/Qty] > 0) > 0") to ADMITTED),
            // The rule's own row, captured, once a read OUTSIDE the filter makes the rule parallel-iterate: A12 marks
            // the rule's keyed level on every reference then, so the body holds a marked level after all.
            "the rule's own row captured beside an outer parallel read" to (inLine(
                "FieldFilled(Qty) And $PARALLEL_READ And Sum(/Order/Picks*/PickedQty Having [/Order/Picks/PickedQty] > 0 And [\$/Order/Lines/Qty] > 0) > 0") to PARALLEL),
            // A candidate read is bound to the starred list, so it is no parallel read, beside uniqueness either.
            "uniqueness beside a filter whose candidate read names the other group" to (inLines(
                "$UNIQUE And Sum(/Order/Picks*/PickedQty Having [/Order/Picks/PickedQty] > 0) > 0") to ADMITTED),
        ))
    }

    /** A computation's generated condition meets the same gates, all of them before the rounding mode. */
    @Test
    fun aComputationsConditionGatesAnswerBeforeItsRoundingMode() {
        assertAgreement(linkedMapOf(
            "rounding beside a plain comparison — control" to (computed(null, RoundingMode.ROUND_UP) to ROUNDING),
            "a marked filter index — control" to (computed(MARKED, RoundingMode.EXACT) to INDEX),
            "a marked filter index and rounding" to (computed(MARKED, RoundingMode.ROUND_UP) to INDEX),
            "a duplicate and rounding" to (computed(DUPLICATE, RoundingMode.ROUND_UP) to DUPLICATE_CLASS),
        ))
    }

    private fun assertAgreement(predicted: Map<String, Pair<Probe, Set<String>>>) {
        val wrong = predicted.mapNotNull { (row, probe) ->
            val (input, a12) = probe
            val observed = input.compare()
            val expected = a12 to (OURS[row] ?: a12)
            if (observed == expected) null else "$row: expected (A12, ours) $expected, observed $observed"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n", "rows that disagree:\n"))
    }

    private fun interface Probe {
        fun compare(): Pair<Set<String>, Set<String>>
    }

    /** At the root, reporting on a root field the guard references. */
    private fun atRoot(condition: String) = rule(DraftRule("/Order", "ProbeRule", "../Reference",
        "FieldFilled(Reference) And $condition", "PROBE", "ERROR"))

    /** At the root, reporting into `Lines`, which is where a uniqueness check over its rows reports. */
    private fun inLines(condition: String) = rule(DraftRule("/Order", "ProbeRule", "../Lines/LineRef", condition,
        "PROBE", "ERROR"))

    /** Inside a `Lines` row, so a plain read of `Picks` iterates it jointly by `LineRef`. */
    private fun inLine(condition: String) = rule(DraftRule("/Order/Lines", "ProbeRule", "../Qty", condition,
        "PROBE", "ERROR"))

    private fun rule(draft: DraftRule) = Probe {
        verdicts(validator.check(order, draft).let { if (it.ok()) emptySet() else it.codes() },
            validator.inject(order, draft))
    }

    /** A constant into `Total`; [leaf] joins its precondition and [mode] meets an unbanded alternative. */
    private fun computed(leaf: String?, mode: RoundingMode) = Probe {
        val draft = DraftComputation.builder().groupPath("/Order").name("Derived").computedFieldRelPath("../Total")
            .commonPrecondition("FieldFilled(Reference)" + (leaf?.let { " And $it" } ?: ""))
            .alternatives(listOf(DraftComputation.Alternative(null, "1")))
            .roundingMode(mode).build()
        verdicts(validator.check(order, draft).let { if (it.ok()) emptySet() else it.codes() },
            validator.inject(order, draft))
    }

    private fun io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult.codes() =
        diagnostics().map { it.codeOrSummary() }.toSet()

    private fun verdicts(a12: Set<String>, injected: String) =
        a12 to rejectingCodes(injected).toSet()

    private companion object {
        /** A filter body selecting a `Picks` row by key, read through the capture and then plainly. */
        const val MARKED = "Sum(Lines*/Qty Having [Lines/Qty] > 0 And GroupFilled(\$/Order/Picks For \"A\")) > 0"
        const val UNMARKED = "Sum(Lines*/Qty Having [Lines/Qty] > 0 And GroupFilled(Picks For \"A\")) > 0"
        const val DUPLICATE = "AllGroupsFilled(Shipping, Shipping)"
        const val UNIQUE = "RepetitionNotUnique(Lines/LineRef)"
        const val SECOND_UNIQUE = "RepetitionNotUnique(Lines/Sku)"

        /** A read of `Picks` from a rule iterating `Lines`, both keyed by `LineRef`: a parallel iteration. */
        const val PARALLEL_READ = "FieldFilled(/Order/Picks/PickedQty)"

        val ADMITTED = emptySet<String>()
        val INDEX = setOf("MVK_SEMANTIC_INDEX_NOT_ALLOWED")
        val DUPLICATE_CLASS = setOf("MVK_DUPLICATE_PARAM1")
        val RAW_LENGTH = setOf("MVK_INVALID_LENGTH_OF_RAW_TYPE")
        val COMBINATION = setOf("MVK_INVALID_COMBINATION_OF_REPETITON_NOT_UNIQUE")
        val PARALLEL = setOf("MVK_PARALLEL_ITERATION_NOT_ALLOWED")
        val ROUNDING = setOf("MVK_ERROR_WRONG_ROUNDING_MODE")

        /** The rows where the checker answers otherwise, with what it answers. */
        val OURS: Map<String, Set<String>> = mapOf()
    }
}
