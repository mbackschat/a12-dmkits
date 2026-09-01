package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * a12-kernel-lean **EXP-2026-08-25-01** — when a source-classified computation result is applied to a SEPARATE
 * compatible destination, what repetition TOPOLOGY does the applied document carry, and what does a later
 * validation over that document see?
 *
 * **Why a cell probe could not answer it.** `SeparateDestinationClearDiffTest` established that a retained
 * CLEARED or VALUE action creates its missing addressed row, but it reads exactly one target cell and exposes no
 * repetition count. Three questions ride on the count rather than on that cell: whether creating row 3 also
 * materializes row 2, whether a later action at row 1 keeps row 3 at its own address, and which rows a
 * later validation enumerates. So this file normalizes the WHOLE `Lines` extent — its repetition count and both
 * cells of every physical row — and compares that projection across dynamic Groovy, generated Java, and the
 * standalone interpreter's retained-result route.
 *
 * **The two order cases are each other's control.** Case A places the changed VALUE at row 1 and the CLEARED at
 * row 3; case B swaps the action classes without moving their addressed coordinates. The kernel's application
 * owner processes CLEARED before changed VALUE, so if the applied rows were appended in action encounter order
 * rather than written at their addresses, exactly one of the two cases would come out reversed. Neither case
 * alone distinguishes those accounts.
 *
 * **The preservation case attacks the same question from the destination side**: an existing row 2 carrying a
 * noncomputed sentinel must keep it, at row 2, while the row-1 and row-3 actions land on their own addresses. A
 * shifted or lost sentinel is the negative result.
 *
 * **The later-validation case is the only one that observes the applied state as an INPUT.** The applied document
 * is validated with no second computation, so the pointers the rule reports are a statement about which rows the
 * application actually instantiated.
 *
 * The extent projection is [AppliedTopology] and the three engines are [SeparateDestinationEngines], both shared
 * with the two-level and String siblings of this family.
 *
 * PER_CLASS so the kernel's Java codegen and the standalone `PreparedModel` are built ONCE for the whole class
 * rather than once per case: `:adapter:test` carries timing-sensitive floors, and a class that recompiles a
 * kernel validator per method is the documented way to inflate a perf lane it never touches.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SeparateDestinationTopologyDiffTest {

    /** The retained-result fixture, plus the ONE ordinary row-iterated validation the request names. */
    private val model = RetainedComputationFixture.modelJson().let { structure ->
        val bound = BoundModel.ofJson(structure)
        val amount = bound.num(AMOUNT)
        TypedRules.inject(
            structure,
            bound.rule(bound.group(LINES)).name("AmountPositive").code(CODE).errorField(amount)
                .`when`(amount.asNumber().gt(0))
                .severity(Severity.ERROR)
                .message(Dsl.en("This line carries a projected amount.")).build(),
        )
    }

    private val engines = SeparateDestinationEngines(model, CHAIN, CELLS)

    // ---- the four cases -------------------------------------------------------------------------------------

    /** Row 1 changes to a VALUE, row 3 CLEARS: the class the application owner processes FIRST sits LAST. */
    private fun reverseOrderSource() = lines(3)
        .field(CANDIDATE, "7", 1, 1, 1)
        .field(AMOUNT, "9", 1, 3, 1)

    /** The same two addressed coordinates with the action classes swapped. */
    private fun forwardOrderSource() = lines(3)
        .field(AMOUNT, "9", 1, 1, 1)
        .field(CANDIDATE, "7", 1, 3, 1)

    /** Two VALUE actions at rows 1 and 3 with no stale target anywhere — the later-validation input. */
    private fun laterValidationSource() = lines(3)
        .field(CANDIDATE, "5", 1, 1, 1)
        .field(CANDIDATE, "7", 1, 3, 1)

    @Test fun theAppliedTopologyIsAddressedRatherThanAppendedInActionOrder() {
        assertEquals(
            listOf(
                "reverse order  => $REVERSE_ORDER_EXTENT",
                "forward order  => $FORWARD_ORDER_EXTENT",
            ),
            listOf(
                "reverse order  => ${engines.appliedExtent(reverseOrderSource(), lines(0))}",
                "forward order  => ${engines.appliedExtent(forwardOrderSource(), lines(0))}",
            ),
            "the complete normalized Lines extent as ALL THREE engines report it",
        )
    }

    @Test fun anExistingPredecessorRowKeepsItsNoncomputedSentinelAtItsOwnAddress() {
        assertEquals(
            "Order=1 | Lines=3 | Lines[1] Candidate=- Amount=7 | Lines[2] Candidate=4 Amount=- | " +
                "Lines[3] Candidate=- Amount=()",
            engines.appliedExtent(reverseOrderSource(), lines(2).field(CANDIDATE, "4", 1, 2, 1)),
            "the sentinel must stay at row 2 while the row-1 and row-3 actions take their exact addresses",
        )
    }

    @Test fun aLaterValidationOverTheAppliedDocumentEnumeratesTheInstantiatedRows() {
        assertEquals(
            "Order=1 | Lines=3 | Lines[1] Candidate=- Amount=5 | Lines[2] Candidate=- Amount=- | " +
                "Lines[3] Candidate=- Amount=7",
            engines.appliedExtent(laterValidationSource(), lines(0)),
            "the applied topology the validation below runs over",
        )
        assertEquals(
            listOf(
                "$CODE|VALUE_ERROR|/Order[1]/Lines[1]/Amount",
                "$CODE|VALUE_ERROR|/Order[1]/Lines[3]/Amount",
            ),
            engines.kernelLaterValidation(laterValidationSource(), lines(0)),
            "the rule reports at the rows the application instantiated, on BOTH kernel strategies",
        )
        assertEquals(
            listOf(
                "$CODE|VALUE|Order/Lines/Amount",
                "$CODE|VALUE|Order/Lines[3]/Amount",
            ),
            engines.interpreterLaterValidation(laterValidationSource(), lines(0)),
            "the standalone route reports the same two rows, and each is pinned in its OWN dialect rather than " +
                "bridged by a string tweak: the kernel signature above is the house form with its leading " +
                "slash and explicit [1], while A12's canonical exact pointer OMITS a repetition of 1 " +
                "(kernel-measured in A12PointerKernelDomainDiffTest). So row 1 carries no bracket and row 3 " +
                "does — which is itself what shows the two findings sit at different addresses.",
        )
    }

    private fun lines(count: Int): RuntimeLaws.Doc = RuntimeLaws.doc().group(ROOT, 1).also { doc ->
        for (row in 1..count) doc.group(LINES, 1, row)
    }

    private companion object {
        const val ROOT = RetainedComputationFixture.ROOT
        const val LINES = RetainedComputationFixture.LINES
        const val CANDIDATE = RetainedComputationFixture.CANDIDATE
        const val AMOUNT = RetainedComputationFixture.AMOUNT
        const val CODE = "AMOUNT_POSITIVE"
        val CHAIN = listOf("Order", "Lines")
        val CELLS = listOf("Candidate", "Amount")
        const val REVERSE_ORDER_EXTENT =
            "Order=1 | Lines=3 | Lines[1] Candidate=- Amount=7 | Lines[2] Candidate=- Amount=- | " +
                "Lines[3] Candidate=- Amount=()"
        const val FORWARD_ORDER_EXTENT =
            "Order=1 | Lines=3 | Lines[1] Candidate=- Amount=() | Lines[2] Candidate=- Amount=- | " +
                "Lines[3] Candidate=- Amount=7"
    }
}
