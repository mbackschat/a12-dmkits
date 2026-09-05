package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Kernel-admission laws — **an extremum's operand-family refusal names the class its FIRST operand fixed, not
 * the offender's.** The same two operands draw `MVK_DATE_AND_NONDATE` behind a temporal leader and
 * `MVK_NOT_SORTABLE` behind a numeric one. Reproduces a12-kernel-lean's `SPEC-2026-09-05-11` on an
 * independently authored carrier, measured here rather than carried on their report.
 *
 * **Why it needed measuring even though we already held the mechanism.** Our catalog documents exactly this
 * first-operand rule on `NumberOfDifferentValues`, with its own worked pair. But a mechanism proved on one
 * operator is not proved on another — reusing it across that boundary needs no new branch, so nothing prompts
 * the question — and `MaxValue`'s constraint named `MVK_NOT_SORTABLE` alone with no mention of operand order,
 * so an author reaching for `MaxValue(<date>, <number>)` met a code our own surface never listed.
 *
 * **The family is ENUMERATED rather than sampled**, because the two spellings are genuinely different
 * operators: `MinValue`/`MaxValue` take a bare field list (they aggregate a repetition list) and `Min`/`Max`
 * take bracketed value expressions. A row measured on one form says nothing about the other, which is the same
 * scope-transfer trap one level down.
 *
 * Own domain (freight consignments).
 */
// One instance per CLASS: every row builds a model and runs the kernel's consistency oracle, and this Gradle
// task also carries clock-reading assertions (CLAUDE.md: a new test must not perturb a timing floor).
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExtremaOperandFamilyRefusalLawsTest {

    private companion object {
        const val DATE_FORMAT = "yyyy-MM-dd"

        const val GROSS = "/Consignment/GrossWeight"
        const val NET = "/Consignment/NetWeight"
        const val TARE = "/Consignment/TareWeight"
        const val SHIPPED = "/Consignment/ShippedOn"
        const val ARRIVED = "/Consignment/ArrivedOn"
        const val LABELLED = "/Consignment/LabelledOn"

        const val DATE_LEADER = "MVK_DATE_AND_NONDATE"
        const val NON_DATE_LEADER = "MVK_NOT_SORTABLE"
        const val ADMITTED = "ADMITTED"

        val MODEL: String = DocumentModelDraft.named("extrema-operand-family").locale("en_US")
            .rootGroup("Consignment") { c ->
                c.numberField("GrossWeight") { it.fractionDigits(0, 0) }
                    .numberField("NetWeight") { it.fractionDigits(0, 0) }
                    .numberField("TareWeight") { it.fractionDigits(0, 0) }
                    .dateField("ShippedOn") { it.format(DATE_FORMAT) }
                    .dateField("ArrivedOn") { it.format(DATE_FORMAT) }
                    .dateField("LabelledOn") { it.format(DATE_FORMAT) }
            }.build().toJson()
    }

    /**
     * **The whole claim as one table.** Each row's comparison right-hand side is kept in the LEADER's own
     * family, so the only family disagreement in the condition is inside the extremum's operand list and the
     * code cannot be the comparison's.
     */
    @Test
    fun theFieldListExtremaNameTheClassTheirFirstOperandFixed() {
        assertEquals(
            mapOf(
                "MaxValue · temporal leader" to DATE_LEADER,
                "MaxValue · numeric leader" to NON_DATE_LEADER,
                "MinValue · temporal leader" to DATE_LEADER,
                "MinValue · numeric leader" to NON_DATE_LEADER,
            ),
            mapOf(
                "MaxValue · temporal leader" to
                    verdictOf("MaxValue($SHIPPED, $GROSS) > [$ARRIVED]", "../ShippedOn"),
                "MaxValue · numeric leader" to
                    verdictOf("MaxValue($GROSS, $SHIPPED) > [$NET]", "../GrossWeight"),
                "MinValue · temporal leader" to
                    verdictOf("MinValue($SHIPPED, $GROSS) > [$ARRIVED]", "../ShippedOn"),
                "MinValue · numeric leader" to
                    verdictOf("MinValue($GROSS, $SHIPPED) > [$NET]", "../GrossWeight"),
            ),
            "the same two operands, and the reported class follows the operand ORDER",
        )
    }

    /** The operand-list spelling is a different operator, so it is measured rather than inherited. */
    @Test
    fun theOperandListExtremaFollowTheSameRule() {
        assertEquals(
            mapOf(
                "Max · temporal leader" to DATE_LEADER,
                "Max · numeric leader" to NON_DATE_LEADER,
                "Min · temporal leader" to DATE_LEADER,
                "Min · numeric leader" to NON_DATE_LEADER,
            ),
            mapOf(
                "Max · temporal leader" to
                    verdictOf("Max([$SHIPPED], [$GROSS]) > [$ARRIVED]", "../ShippedOn"),
                "Max · numeric leader" to
                    verdictOf("Max([$GROSS], [$SHIPPED]) > [$NET]", "../GrossWeight"),
                "Min · temporal leader" to
                    verdictOf("Min([$SHIPPED], [$GROSS]) > [$ARRIVED]", "../ShippedOn"),
                "Min · numeric leader" to
                    verdictOf("Min([$GROSS], [$SHIPPED]) > [$NET]", "../GrossWeight"),
            ),
            "the bracketed value-expression form obeys the same first-operand rule",
        )
    }

    /**
     * The homogeneous controls. Without them every refusal above is consistent with "this extremum refuses two
     * distinct fields", and the order rule would be indistinguishable from a blanket gate.
     */
    @Test
    fun aHomogeneousOperandListIsAdmittedInBothFamilies() {
        assertEquals(
            mapOf(
                "MaxValue · two dates" to ADMITTED,
                "MaxValue · two numbers" to ADMITTED,
                "Max · two dates" to ADMITTED,
                "Max · two numbers" to ADMITTED,
            ),
            mapOf(
                "MaxValue · two dates" to
                    verdictOf("MaxValue($SHIPPED, $ARRIVED) > [$LABELLED]", "../ShippedOn"),
                "MaxValue · two numbers" to
                    verdictOf("MaxValue($GROSS, $NET) > [$TARE]", "../GrossWeight"),
                "Max · two dates" to
                    verdictOf("Max([$SHIPPED], [$ARRIVED]) > [$LABELLED]", "../ShippedOn"),
                "Max · two numbers" to
                    verdictOf("Max([$GROSS], [$NET]) > [$TARE]", "../GrossWeight"),
            ),
            "a family-homogeneous operand list is admitted, so the refusals above are about the mix",
        )
    }

    // ---- the instrument ----

    /** [ADMITTED], or the distinct MVK codes the kernel's consistency oracle reported for [condition]. */
    private fun verdictOf(condition: String, errorRel: String): String {
        val messages = RuntimeLaws.consistencyMessages(
            RuntimeLaws.withRule(MODEL, "/Consignment", "RR", errorRel, "RR", condition))
        if (messages.isEmpty()) return ADMITTED
        val codes = messages.mapNotNull { Regex("(MVK_[A-Z_0-9]+)").find(it)?.groupValues?.get(1) }
            .distinct().sorted()
        return codes.joinToString(", ").ifEmpty { messages.joinToString(", ") }
    }
}
