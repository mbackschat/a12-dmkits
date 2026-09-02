package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldListRef
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tri-engine differential — does the declared-capacity projection survive a `Having` FILTER on the star?
 * a12-kernel-lean's `SPEC-2026-09-02-02` (filtered String, Boolean and Confirm value counts) and
 * `SPEC-2026-09-02-04` (the filtered Number aggregates), reproduced on an independently authored carrier.
 *
 * **The filter is reflexive on purpose, so `filtered` is the only variable.** Every operand here is narrowed by
 * `CurrentRepetition(G) == CurrentRepetition(G)`, which selects every instantiated row and reads no value field.
 * A filter that selected a subset would confound two questions — whether the projection survives filtering at
 * all, and whether this particular predicate happens to drop the over-limit row — and only the first is the
 * claim. What the filter does change is the operand's evaluation ROUTE: our unfiltered stars go through
 * `starRows` and a `Having` goes through `rowContextsScoped`, two separate exclusion sites, which is why the
 * filtered form is not a corollary of the unfiltered result we already hold in
 * [GroupOperandOverLimitExtentDiffTest].
 *
 * **Each carrier reads an exact value off a ladder of equalities, never a fired/silent bit.** An operand the
 * projection has made non-evaluable leaves both `> 0` and `< 1` silent, so a boolean read cannot separate
 * `unknown` from the identity value; every ladder therefore carries its identity member (`0` throughout) and the
 * fired member names the answer. None firing is reported as [UNAVAILABLE] rather than silently as zero.
 *
 * **The aggregates need a MIXED document and the value counts need a REVERSED one.** On a domain the projection
 * has emptied, *excluded* and *unavailable* are both candidate-free, so `Sum` and the extrema are read off a
 * document holding one value in capacity and another above it. Symmetrically, counting `5` over that same
 * forward document answers `1` under either account — so the value count is read off the reversed document,
 * where the counted value is the one above capacity. A row that cannot distinguish the accounts measures
 * nothing while reading as a measurement.
 *
 * Own domain (marketplace payout settlement).
 */
class FilteredStarCapacityExtentDiffTest {

    private companion object {
        const val PAYOUT = "/Payout"
        const val MERCHANT = "/Payout/Merchant"
        const val LINES = "/Payout/Lines"
        const val AMOUNT = "/Payout/Lines/Amount"
        const val BONUS = "/Payout/Lines/Bonus"
        const val TAGS = "/Payout/Tags"
        const val CODE = "/Payout/Tags/Code"
        const val ALT = "/Payout/Tags/Alt"
        const val CHECKS = "/Payout/Checks"
        const val PASSED = "/Payout/Checks/Passed"
        const val WAIVED = "/Payout/Checks/Waived"
        const val TERMS = "/Payout/Terms"
        const val SIGNED = "/Payout/Terms/Signed"
        const val READ = "/Payout/Terms/Read"

        const val MERCHANT_NAME = "Nordwind Handel"
        const val MATCH = "PRIORITY"
        const val NON_MATCH = "STANDARD"

        /** Capacity is two on every operand group, so row 3 is the over-limit row everywhere. */
        const val CAPACITY = 2

        const val UNAVAILABLE = "UNAVAILABLE — no ladder member fired, the operand could not evaluate"
        const val OMISSION = "OMISSION"
        const val VALUE = "VALUE"

        val OVER_REPETITION_CODES = setOf("zuGrosseZeile", "zuGrosseKontextnummer")

        fun typeName(kernelType: String) = if (kernelType == "OMISSION_ERROR") OMISSION else VALUE
    }

    private val base = DocumentModelDraft.named("payout-filtered-star-capacity").locale("en_US")
        .rootGroup("Payout") { payout ->
            payout.stringField("Merchant") { }
            payout.group("Lines") { g ->
                g.repeatable(CAPACITY)
                g.numberField("Amount") { it.fractionDigits(0) }
                g.numberField("Bonus") { it.fractionDigits(0) }
            }
            payout.group("Tags") { g ->
                g.repeatable(CAPACITY)
                g.stringField("Code") { }
                g.stringField("Alt") { }
            }
            payout.group("Checks") { g ->
                g.repeatable(CAPACITY)
                g.field("Passed", FieldKind.BOOLEAN)
                g.field("Waived", FieldKind.BOOLEAN)
            }
            payout.group("Terms") { g ->
                g.repeatable(CAPACITY)
                g.field("Signed", FieldKind.CONFIRM)
                g.field("Read", FieldKind.CONFIRM)
            }
        }.build().toJson()

    private val bound = BoundModel.ofJson(base)

    /** `CurrentRepetition(G) == CurrentRepetition(G)` — true for every instantiated row, reading no value. */
    private fun everyRowOf(groupPath: String): Condition =
        Dsl.currentRepetition(bound.group(groupPath)).eq(Dsl.currentRepetition(bound.group(groupPath)))

    private fun filtered(starPath: String, groupPath: String): FieldListRef =
        bound.flatten(starPath).having(everyRowOf(groupPath))

    private val amounts = filtered("$LINES*/Amount", LINES)

    /**
     * The eight carriers, each with the ladder its own answer has to land on. The three value-count kinds are
     * `SPEC-2026-09-02-02`'s subject and the five Number carriers are `SPEC-2026-09-02-04`'s; they share one
     * model because they share the one property under test.
     */
    private val carriers: Map<String, Carrier> = linkedMapOf(
        "SUM" to Carrier(listOf(0, 5, 7, 12)) { n -> Dsl.sum(amounts).eq(n.toLong()) },
        "MIN" to Carrier(listOf(0, 5, 7, 12)) { n -> Dsl.minValue(amounts).eq(n.toLong()) },
        "MAX" to Carrier(listOf(0, 5, 7, 12)) { n -> Dsl.maxValue(amounts).eq(n.toLong()) },
        "NDV" to Carrier(listOf(0, 1, 2, 3)) { n -> Dsl.numberOfDifferentValues(amounts).eq(n.toLong()) },
        "NVF" to Carrier(listOf(0, 1, 2)) { n -> Dsl.numberOfValueInFields(5L, amounts).eq(n.toLong()) },
        "STR" to Carrier(listOf(0, 1, 2)) { n ->
            Dsl.numberOfValueInFields(MATCH, filtered("$TAGS*/Code", TAGS)).eq(n.toLong())
        },
        "BOOL" to Carrier(listOf(0, 1, 2)) { n ->
            Dsl.numberOfValueInFields(true, filtered("$CHECKS*/Passed", CHECKS)).eq(n.toLong())
        },
        "CONF" to Carrier(listOf(0, 1, 2)) { n ->
            Dsl.numberOfValueInFields(true, filtered("$TERMS*/Signed", TERMS)).eq(n.toLong())
        },
    )

    private class Carrier(val ladder: List<Int>, val at: (Int) -> Condition)

    private val rules: List<Rule> = carriers.flatMap { (prefix, carrier) ->
        carrier.ladder.map { n -> rule("$prefix$n", carrier.at(n)) }
    }

    /**
     * The error field is conjoined into every condition because the kernel refuses a rule whose error field its
     * condition never reads (`MVK_ERROR_FIELD_NOT_REFERENCED`). `Merchant` is filled in every document below, so
     * the conjunct is constantly true and the carrier decides each rule.
     */
    private fun rule(code: String, condition: Condition): Rule =
        bound.rule(bound.group(PAYOUT)).name("${code}Rule").errorField(bound.str(MERCHANT))
            .`when`(bound.field(MERCHANT).filled().and(condition))
            .code(code).severity(Severity.ERROR).message(Dsl.en(code)).build()

    private val modelJson = rules.fold(base) { json, rule -> TypedRules.inject(json, rule) }
    private val definition = ModelLoader.load(modelJson)
    private val staticEngine by lazy { RuntimeLaws.kernelJavaEngine(modelJson) }
    private val ladderCodes: Set<String> =
        carriers.flatMap { (prefix, c) -> c.ladder.map { "$prefix$it" } }.toSet()

    /**
     * Two things a reader of the rows below has to be able to take for granted, asserted rather than assumed:
     * the kernel ADMITS this model — so a silent ladder member is a carrier's answer and not a rule that never
     * ran — and the operands really are filtered, which is checked against the rendered condition text because a
     * `having(…)` that quietly dropped its filter would make every row a re-measurement of the unfiltered form.
     */
    @Test
    fun theProbeModelIsAdmittedAndEveryOperandCarriesItsFilter() {
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(modelJson),
            "a refused model makes every UNAVAILABLE row in this file unfalsifiable")
        val renderer = DslRenderer(ConditionLanguage.EN, PAYOUT)
        val unfiltered = rules.map { renderer.render(it.condition()) }.filterNot { it.contains("Having") }
        assertEquals(emptyList<String>(), unfiltered,
            "every carrier must be read through a Having filter, or this file duplicates the unfiltered locks")
    }

    // ---- the documents ----

    /**
     * The deciding document. Every operand group carries content in in-capacity row 1 and again in over-limit
     * row 3: `Amount` holds `5` then `7`, and each token carrier's counted cell holds a non-match then its
     * match. The projection answers `Sum 5 · Min 5 · Max 5 · distinct 1` and `0` on all three token counts; a
     * complete-instantiated-row account answers `Sum 12 · Min 5 · Max 7 · distinct 2` and `1` on each count.
     */
    private val forwardWithOverLimit = Rows(
        amount = listOf("5", null, "7"),
        code = listOf(NON_MATCH, NON_MATCH, MATCH),
        passed = listOf("false", "false", "true"),
        signed = listOf(null, null, "true"),
    )

    /**
     * The Number values reversed — `7` in capacity, `5` above it — which is what makes the Number value count
     * discriminating: `NumberOfValueInFields(5 …)` answers `0` under the projection and `1` without it, where
     * over the forward document it answers `1` either way.
     */
    private val reversedWithOverLimit = forwardWithOverLimit.copy(amount = listOf("7", null, "5"))

    /**
     * Everything inside capacity, one match per token carrier — the control that keeps every `0` above from
     * reading as "the operand sees nothing at all".
     */
    private val allInCapacity = Rows(
        amount = listOf("5", "7"),
        code = listOf(MATCH, NON_MATCH),
        passed = listOf("true", "false"),
        signed = listOf("true", null),
    )

    /** Both in-capacity rows matching, so each token count's `2` member has a positive firing of its own. */
    private val bothInCapacityMatch = Rows(
        amount = listOf("5", "7"),
        code = listOf(MATCH, MATCH),
        passed = listOf("true", "true"),
        signed = listOf("true", "true"),
    )

    // ---- the measurements ----

    @Test
    fun everyFilteredCarrierReadsOnlyItsInCapacityExtent() =
        assertReadings(
            forwardWithOverLimit,
            mapOf(
                "SUM" to "5", "MIN" to "5", "MAX" to "5", "NDV" to "1", "NVF" to "1",
                "STR" to "0", "BOOL" to "0", "CONF" to "0",
            ),
            "Number in-capacity 5 / over-limit 7; each token match above capacity",
        )

    @Test
    fun theReversedDocumentMakesTheFilteredNumberValueCountDiscriminating() =
        assertReadings(
            reversedWithOverLimit,
            mapOf(
                "SUM" to "7", "MIN" to "7", "MAX" to "7", "NDV" to "1", "NVF" to "0",
                "STR" to "0", "BOOL" to "0", "CONF" to "0",
            ),
            "Number in-capacity 7 / over-limit 5 — the counted value is the one above capacity",
        )

    @Test
    fun theInCapacityControlShowsEveryFilteredCarrierSeesWhatItMay() =
        assertReadings(
            allInCapacity,
            mapOf(
                "SUM" to "12", "MIN" to "5", "MAX" to "7", "NDV" to "2", "NVF" to "1",
                "STR" to "1", "BOOL" to "1", "CONF" to "1",
            ),
            "everything inside capacity",
        )

    @Test
    fun bothInCapacityRowsMatchingGivesEachTokenCountItsTwoMember() =
        assertReadings(
            bothInCapacityMatch,
            mapOf(
                "SUM" to "12", "MIN" to "5", "MAX" to "7", "NDV" to "2", "NVF" to "1",
                "STR" to "2", "BOOL" to "2", "CONF" to "2",
            ),
            "both in-capacity rows match on every token carrier",
        )

    /**
     * The over-repetition finding is reported for the very documents whose readings it does not change. A fixture
     * whose row 3 was quietly not above capacity would satisfy every row above by accident, and the in-capacity
     * controls are the other half of the same check: they must NOT carry it.
     */
    @Test
    fun theOverLimitFindingIsPresentExactlyOnTheDocumentsThatCarryARowAboveCapacity() {
        val actual = listOf(
            "forward" to forwardWithOverLimit,
            "reversed" to reversedWithOverLimit,
            "in-capacity control" to allInCapacity,
            "both matching, in capacity" to bothInCapacityMatch,
        ).associate { (label, rows) ->
            label to RuntimeLaws.messages(modelJson, kernelDocument(rows))
                .any { it.code() in OVER_REPETITION_CODES }
        }
        assertEquals(
            mapOf(
                "forward" to true,
                "reversed" to true,
                "in-capacity control" to false,
                "both matching, in capacity" to false,
            ),
            actual,
            "the structural channel must be lit exactly where a row sits above capacity",
        )
    }

    // ---- the instrument ----

    /**
     * Reads every carrier named in [expected] off one document, after asserting that the three engines agree on
     * the complete fired `code:type` set. The agreement is checked on the whole set rather than on the carriers
     * under inspection, so a divergence in the accompanying structural findings cannot pass as agreement.
     *
     * The type is carried beside the value because a12-kernel-lean's entries report the omission-vs-value
     * polarity of these rules; it is pinned as measured rather than predicted.
     */
    private fun assertReadings(rows: Rows, expected: Map<String, String>, label: String) {
        val kernelDocument = kernelDocument(rows)
        val dynamic = RuntimeLaws.messages(modelJson, kernelDocument)
            .filter { it.code() in ladderCodes }
            .map { "${it.code()}:${typeName(it.type().name)}" }.sorted()
        val static = staticEngine.messages(staticEngine.instance(kernelDocument(rows)))
            .filter { it.code() in ladderCodes }
            .map { "${it.code()}:${typeName(it.type().name)}" }.sorted()
        val interpreter = DmInterpreter(definition).validateFull(interpreterDocument(rows))
            .filter { it.code in ladderCodes }
            .map { "${it.code}:${if (it.type == MsgType.OMISSION) OMISSION else VALUE}" }.sorted()

        assertEquals(dynamic, static, "$label — generated Java vs normative dynamic-Groovy")
        assertEquals(dynamic, interpreter, "$label — interpreter vs normative dynamic-Groovy")

        val fired = dynamic.map { it.substringBefore(':') }.toSet()
        val actual = expected.keys.associateWith { prefix -> reading(prefix, fired) }
        assertEquals(expected, actual, "$label — the value each filtered carrier answered")
    }

    /** Which ladder member of [prefix] fired: exactly one is the answer, none is [UNAVAILABLE], several a bug. */
    private fun reading(prefix: String, fired: Set<String>): String {
        val hits = carriers.getValue(prefix).ladder.filter { "$prefix$it" in fired }
        return when (hits.size) {
            0 -> UNAVAILABLE
            1 -> hits.single().toString()
            else -> "AMBIGUOUS $hits — the ladder itself is broken"
        }
    }

    private fun kernelDocument(rows: Rows): RuntimeLaws.Doc {
        val d = RuntimeLaws.doc().group(PAYOUT, 1).field(MERCHANT, MERCHANT_NAME, 1, 1)
        forEachRow(rows) { group, row, field, sibling, value, siblingValue ->
            d.group(group, 1, row)
            value?.let { d.field(field, it, 1, row, 1) }
            siblingValue?.let { d.field(sibling, it, 1, row, 1) }
        }
        return d
    }

    private fun interpreterDocument(rows: Rows): Document {
        val d = definition.newDocument().group(PAYOUT, 1).field(MERCHANT, MERCHANT_NAME, 1, 1)
        forEachRow(rows) { group, row, field, sibling, value, siblingValue ->
            d.group(group, 1, row)
            value?.let { d.field(field, it, 1, row, 1) }
            siblingValue?.let { d.field(sibling, it, 1, row, 1) }
        }
        return d
    }

    /**
     * Walks the rows one document declares.
     *
     * **Every operand group declares two fields and the star names one of them, and the unnamed sibling is
     * filled — that is the fixture control, not decoration.** Each token carrier's sibling holds the very value
     * its operand hunts for, so an evaluator reading the star as the group's whole subtree lands on a different
     * ladder member: the account a one-field-per-group fixture cannot separate from the correct one, which is
     * how a12-kernel-lean's `LF114` shipped. The Number sibling is filled once, in in-capacity row 1, for the
     * same reason; on two of the four documents that misread pushes the total off the ladder, so it fails as an
     * unevaluable carrier rather than as a wrong number — a failure either way, but the reader should know which
     * shape to expect.
     */
    private fun forEachRow(
        rows: Rows,
        visit: (group: String, row: Int, field: String, sibling: String,
            value: String?, siblingValue: String?) -> Unit,
    ) {
        rows.amount.forEachIndexed { i, v ->
            visit(LINES, i + 1, AMOUNT, BONUS, v, if (i == 0) "7" else null)
        }
        rows.code.forEachIndexed { i, v -> visit(TAGS, i + 1, CODE, ALT, v, MATCH) }
        rows.passed.forEachIndexed { i, v -> visit(CHECKS, i + 1, PASSED, WAIVED, v, "false") }
        rows.signed.forEachIndexed { i, v -> visit(TERMS, i + 1, SIGNED, READ, v, "true") }
    }

    /**
     * One document's content per operand group, index 0 = row 1, `null` = the row exists and the counted cell is
     * absent. Every document below gives every group rows, so no carrier ever reads an empty domain and this
     * file pins nothing about the emptied-projection case that [GroupOperandOverLimitExtentDiffTest] owns.
     */
    private data class Rows(
        val amount: List<String?>,
        val code: List<String?>,
        val passed: List<String?>,
        val signed: List<String?>,
    )
}
