package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The checker against A12: a comparison inside a `Having` body whose side is a `$`-marked read of the rule's own
 * row is typed as that row's field, so every comparison gate judges it (KERNEL-FINDINGS KF487, a12-kernel-lean
 * SPEC-2026-10-28-01's `$`-correlated carrier).
 *
 * The marked read is the enclosing rule's reference ([KERNEL-FINDINGS KF487]), and A12 types it like any other
 * read of a declared field: a scale-2 field against a scale-0 one is refused from either side, and an unknown
 * scale is refused against a known one. The checker once handed the bracketed text to a reader that recognizes
 * the marker only at the head of a bare spec, so it typed no marked side and every gate stood down.
 *
 * The `S5`/`S6` pair fixes the order against the filter's scope gate. A body that reads only marked fields fails
 * that gate (`MVK_NO_ITERATION_FOR_WILDCARD`, [KERNEL-FINDINGS KF487]), yet with an unknown scale the comparison's
 * class answers first; the known-scale twin, which the scale gate admits, reaches the scope gate. `S7` shows
 * that ordering is not scale-gated there either.
 *
 * Own domain (an e-commerce order's lines, the rule's error field on the line so the rule iterates it).
 */
class CheckerOuterMarkedComparisonAgreementTest {

    private val order = DocumentModelDraft.named("order-marked-comparison").locale("en_US")
        .rootGroup("Order") { o -> o
            .group("Lines") { l -> l.repeatable(5)
                .stringField("Label") { }
                .numberField("Qty") { it.fractionDigits(0) }
                .numberField("Price") { it.fractionDigits(2, 2) }
                .numberField("Cost") { it.fractionDigits(2, 2) }
                .numberField("Rate") { it.fractionDigits(2, -1) }
                .enumField("Kind") { e -> e.value("A").value("B") } } }
        .build().toJson()

    private val validator = RuleValidator()

    @Test
    fun aMarkedSideInAFilterBodyIsJudgedAsA12JudgesIt() {
        val actual = ROWS.mapValues { (_, condition) -> judged(condition) }
        assertEquals(ROWS.mapValues { (row, _) -> A12.getValue(row).let { it to it } }, actual, "as (A12, ours)")
    }

    private fun rejections(json: String) =
        check(ModelSource(json)).findings.filter { it.justifiesRejection }.mapTo(HashSet()) { it.code }

    private fun judged(body: String): Pair<Set<String>, Set<String>> {
        val condition = "FieldFilled(Lines/Label) And MaxValue(Lines*/Qty Having $body) > 0"
        val draft = DraftRule("/Order", "MarkedProbe", "../Lines/Label", condition, "PROBE", "ERROR")
        val a12 = validator.check(order, draft)
            .let { r -> if (r.ok()) emptySet() else r.diagnostics().mapTo(HashSet()) { it.ledgerClass() } }
        return a12 to rejections(validator.inject(order, draft))
    }

    private companion object {
        /** Each row's filter body; the rule around it is fixed. */
        val ROWS = linkedMapOf(
            "S1 two against a marked zero" to "[Lines/Price] == [\$Lines/Qty]",
            "S2 a marked zero against two" to "[\$Lines/Qty] == [Lines/Price]",
            "S3 a marked two against two" to "[\$Lines/Price] == [Lines/Cost]",
            "S4 two against a marked unknown" to "[Lines/Price] == [\$Lines/Rate]",
            "S5 a marked unknown alone" to "[\$Lines/Rate] == 1.00",
            "S6 a marked two alone" to "[\$Lines/Price] == 1.00",
            "S7 a marked unknown ordered alone" to "[\$Lines/Rate] > 1.00",
            "E1 a marked enumeration against a foreign value" to "[Lines/Qty] > 0 And [\$Lines/Kind] == \"Z\"",
            "T1 a marked enumeration ordered" to "[Lines/Qty] > 0 And [\$Lines/Kind] > 1",
        )

        private const val SCALE = "MVK_INVALID_COMPARE_DEC_PLACES"
        private const val SCOPE = "MVK_NO_ITERATION_FOR_WILDCARD"

        /** Measured through `RuleValidator`, kernel 30.8.1, 2026-10-28. */
        val A12 = mapOf(
            "S1 two against a marked zero" to setOf(SCALE),
            "S2 a marked zero against two" to setOf(SCALE),
            "S3 a marked two against two" to emptySet(),
            "S4 two against a marked unknown" to setOf(SCALE),
            "S5 a marked unknown alone" to setOf(SCALE),
            "S6 a marked two alone" to setOf(SCOPE),
            "S7 a marked unknown ordered alone" to setOf(SCOPE),
            "E1 a marked enumeration against a foreign value" to setOf("MVK_INVALID_STRING_CONSTANT_FOR_ENUM_COMPARISON"),
            "T1 a marked enumeration ordered" to setOf("MVK_INVALID_TYPE_FOR_COMPARISON"),
        )
    }
}
