package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What an entity list's NESTED-duplicate gate compares, beside what its exact-duplicate gate compares
 * (a12-kernel-lean SPEC-2026-10-07-05 and SPEC-2026-10-08-03). The two arms of `CheckEntityListenUtils` key an
 * operand differently in Kernel 30.8.1 source:
 * - the exact arm uses `EntityRef.equals`, which includes the `$` capture flag and skips a starred operand;
 * - the nested arm compares the path by step, the semantic index and the category, and nothing else, so it
 *   ignores `$` and does not skip a star.
 *
 * So a captured group beside its own uncaptured self repeats nothing, a captured group beside an uncaptured
 * descendant is nested, a starred group beside its starred descendant is nested, and a group beside its own
 * field under a category is neither — the carrier's later gates answer. Every row is measured; the portable
 * checker must name the same class.
 *
 * Own domain (an e-commerce order's lines and shipping).
 */
class CheckerOverlapIdentityAgreementTest {

    private val order = DocumentModelDraft.named("order-overlap-identity").locale("en_US")
        .rootGroup("Order") { o -> o
            .stringField("Reference") { }
            .group("Lines") { g -> g.repeatable(20).indexField("Sku")
                .stringField("Sku") { }
                .numberField("Qty") { n -> n.fractionDigits(0) }
                .enumField("Tag") { e -> e.value("GIFT").value("PLAIN").category("Kind", "SPECIAL", "NORMAL") }
                .group("Wrap") { w -> w.stringField("Message") { } } }
            .group("Shipping") { g -> g
                .stringField("Carrier") { }
                .enumField("Mode") { e -> e.value("AIR").value("ROAD").category("Speed", "FAST", "SLOW") }
                .group("Address") { a -> a.stringField("Street") { } } } }
        .build().toJson()

    private val validator = RuleValidator()

    @Test
    fun aCaptureSeparatesTheExactDuplicateAndNotTheNestedOne() {
        assertAgreement(linkedMapOf(
            "a group beside its own capture" to (filtered("AllGroupsFilled(Shipping, \$/Order/Shipping)") to ADMITTED),
            "a group beside its captured subgroup" to
                (filtered("AllGroupsFilled(Shipping, \$/Order/Shipping/Address)") to CONTAINED),
            "a captured group beside its subgroup" to
                (filtered("AllGroupsFilled(\$/Order/Shipping, Shipping/Address)") to CONTAINED),
            "a group beside its captured field" to
                (filtered("AtLeastOneGroupFilled(Shipping, \$/Order/Shipping/Carrier)") to CONTAINED),
            "a group twice" to (filtered("AllGroupsFilled(Shipping, Shipping)") to DUPLICATE),
            "a captured group twice" to
                (filtered("AllGroupsFilled(\$/Order/Shipping, \$/Order/Shipping)") to DUPLICATE),
            "the in-scope conjunct alone — control" to (filtered("FieldFilled(Lines/Sku)") to ADMITTED),
            // A filter body takes no semantic index at all, captured or not, so a typed record has to carry the
            // marker into the text and let A12 refuse it rather than drop it and author a different operand.
            "a captured keyed group" to (filtered("GroupFilled(\$/Order/Lines For \"A\")") to INDEX_IN_FILTER),
            "a keyed group — control" to (filtered("GroupFilled(Lines For \"A\")") to INDEX_IN_FILTER),
        ))
    }

    @Test
    fun aStarExemptsTheExactDuplicateAndNotTheNestedOne() {
        assertAgreement(linkedMapOf(
            "a starred group twice" to ("NoGroupFilled(Lines*, Lines*)" to ADMITTED),
            "a starred group beside its starred subgroup" to ("NoGroupFilled(Lines*, Lines*/Wrap)" to CONTAINED),
            "a starred subgroup beside its starred group" to
                ("AtLeastOneGroupFilled(Lines*/Wrap, Lines*)" to CONTAINED),
            "a group beside its subgroup — control" to ("AllGroupsFilled(Shipping, Shipping/Address)" to CONTAINED),
        ))
    }

    @Test
    fun aCategoryTakesAFieldOutOfItsGroupsReference() {
        assertAgreement(linkedMapOf(
            "a repeatable group beside its categorized field" to
                (unique("Lines, Lines/Tag -> Kind") to CATEGORY),
            "the categorized field first" to (unique("Lines/Tag -> Kind, Lines") to CATEGORY),
            "a repeatable group beside its stored field" to (unique("Lines, Lines/Tag") to CONTAINED),
            "a categorized field twice" to
                (unique("Lines/Tag -> Kind, Lines/Tag -> Kind") to DUPLICATE),
            "a group beside its categorized field, first filled" to
                ("FirstFilledValue(Shipping, Shipping/Mode -> Speed) == \"x\"" to ADMITTED),
            "a group beside its stored field, first filled" to
                ("FirstFilledValue(Shipping, Shipping/Mode) == \"x\"" to CONTAINED),
            // The nested arm would answer ahead of the base type; reaching it shows the category kept them apart.
            "a group beside its categorized field, value list" to
                ("AtLeastOneFieldValueIncludedInValueList(Shipping, Shipping/Mode -> Speed In Reference)" to VARYING),
            "a group beside its stored field, value list" to
                ("AtLeastOneFieldValueIncludedInValueList(Shipping, Shipping/Mode In Reference)" to CONTAINED),
            "a group beside its categorized field, all filled" to
                ("AllFieldsFilled(Shipping, Shipping/Mode -> Speed)" to CATEGORY),
        ))
    }

    private fun assertAgreement(predicted: Map<String, Pair<String, Set<String>>>) {
        val wrong = predicted.mapNotNull { (row, probe) ->
            val (condition, a12) = probe
            val observed = compare(condition)
            val expected = a12 to (OURS[row] ?: a12)
            if (observed == expected) null else "$row: expected (A12, ours) $expected, observed $observed"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n", "rows that disagree:\n"))
    }

    private fun filtered(leaf: String) = "Sum(Lines*/Qty Having [Lines/Qty] > 0 And $leaf) > 0"

    /** A uniqueness key list, on a rule whose error field sits in the repetition the keys are unique across. */
    private fun unique(keys: String) = "$UNIQUE_KEYS$keys)"

    private fun compare(probe: String): Pair<Set<String>, Set<String>> {
        val draft = if (probe.startsWith(UNIQUE_KEYS)) {
            DraftRule("/Order", "ProbeRule", "../Lines/Sku", probe, "PROBE", "ERROR")
        } else {
            DraftRule("/Order", "ProbeRule", "../Reference", "FieldFilled(Reference) And $probe", "PROBE", "ERROR")
        }
        val a12 = validator.check(order, draft).let { result ->
            if (result.ok()) emptySet() else result.diagnostics().map { it.code().orElse("(no code: ${it.summary()})") }.toSet()
        }
        val ours = check(ModelSource(validator.inject(order, draft))).findings
            .filter { it.justifiesRejection }.map { it.code }.toSet()
        return a12 to ours
    }

    private companion object {
        val ADMITTED = emptySet<String>()
        val DUPLICATE = setOf("MVK_DUPLICATE_PARAM1")
        val CONTAINED = setOf("MVK_DUPLICATE_PARAM2")
        val CATEGORY = setOf("MVK_CATEGORY_NOT_ALLOWED")
        val VARYING = setOf("MVK_VARYING_TYPES_NOT_ALLOWED")
        val INDEX_IN_FILTER = setOf("MVK_SEMANTIC_INDEX_NOT_ALLOWED")
        const val UNIQUE_KEYS = "RepetitionNotUnique("

        /** The rows where the checker answers otherwise, with what it answers. */
        val OURS: Map<String, Set<String>> = mapOf()
    }
}
