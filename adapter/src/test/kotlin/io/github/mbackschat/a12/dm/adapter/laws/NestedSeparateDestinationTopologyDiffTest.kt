package io.github.mbackschat.a12.dm.adapter.laws

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * a12-kernel-lean **SPEC-2026-08-26-01** — the one-level answer (`SeparateDestinationTopologyDiffTest`) leaves
 * one thing undecided: when applying a retained result creates a row at a DEEPER repeatable level, is the
 * predecessor prefix it pads **parent-scoped** or **parent-global**?
 *
 * The two accounts differ on exactly one observation and nothing else. An action at outer 3 / inner 4 pads inner
 * rows 1–3 — the question is whose. Parent-scoped pads them under outer 3 only, leaving the synthetic outer rows
 * 1 and 2 with whatever their own actions gave them; parent-global would give every outer row an inner prefix
 * long enough for the deepest action anywhere. Placing the second action at outer 1 / inner 2 separates them:
 * under the parent-scoped account outer 1 owns exactly two inner rows and synthetic outer 2 owns none, while the
 * parent-global account gives outer 1 and outer 2 four inner rows each.
 *
 * The remaining cases are the one-level file's controls carried down a level: the action classes are swapped
 * without moving their coordinates, an existing nested destination cell must survive at its exact address, an
 * ERRORED action must create nothing, and the later validation reads the applied document as an INPUT so its
 * pointers state which rows the application really instantiated.
 *
 * The ERRORED case is guarded against vacuity by reading the source classification first: an empty applied
 * extent proves non-creation only if the action that failed to create a row demonstrably existed.
 *
 * PER_CLASS so the kernel's Java codegen and the standalone `PreparedModel` are built ONCE for the whole class
 * rather than once per case: `:adapter:test` carries timing-sensitive floors, and a class that recompiles a
 * kernel validator per method is the documented way to inflate a perf lane it never touches.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NestedSeparateDestinationTopologyDiffTest {

    private val model = SeparateDestinationDepthFixtures.nestedNumberModel()
    private val engines = SeparateDestinationEngines(model, CHAIN, CELLS)

    /** CLEARED at outer 1 / inner 2, changed VALUE at outer 3 / inner 4. */
    private fun reverseOrderSource() = nested(3, 4)
        .field(AMOUNT, "9", 1, 1, 2, 1)
        .field(CANDIDATE, "7", 1, 3, 4, 1)

    /** The same two nested addresses with the action classes swapped. */
    private fun forwardOrderSource() = nested(3, 4)
        .field(CANDIDATE, "7", 1, 1, 2, 1)
        .field(AMOUNT, "9", 1, 3, 4, 1)

    /** Two VALUE actions at the same two addresses — the later-validation input. */
    private fun laterValidationSource() = nested(3, 4)
        .field(CANDIDATE, "5", 1, 1, 2, 1)
        .field(CANDIDATE, "7", 1, 3, 4, 1)

    /** An out-of-range candidate at outer 3 / inner 4 — a source-classified ERRORED action. */
    private fun erroredSource() = nested(3, 4).field(CANDIDATE, "10", 1, 3, 4, 1)

    @Test fun theInnerPredecessorPrefixIsScopedToItsOwnOuterRow() {
        assertEquals(
            REVERSE_ORDER_EXTENT,
            engines.appliedExtent(reverseOrderSource(), nested(0, 0)),
            "outer 3's action pads inner rows 1-3 under OUTER 3 ONLY; synthetic outer 2 owns no inner row and " +
                "outer 1 owns exactly the two its own action needs",
        )
    }

    @Test fun theNestedTopologyIsAddressedRatherThanAppendedInActionOrder() {
        assertEquals(
            FORWARD_ORDER_EXTENT,
            engines.appliedExtent(forwardOrderSource(), nested(0, 0)),
            "swapping which nested address carries CLEARED and which carries VALUE keeps both coordinates",
        )
    }

    @Test fun anExistingNestedDestinationCellKeepsItsExactAddress() {
        assertEquals(
            "Order=1 | Outer=3 | Outer[1] Inner=2 | " +
                "Inner[1,1] Candidate=- Amount=- | Inner[1,2] Candidate=- Amount=() | " +
                "Outer[2] Inner=1 | Inner[2,1] Candidate=4 Amount=- | " +
                "Outer[3] Inner=4 | Inner[3,1] Candidate=- Amount=- | Inner[3,2] Candidate=- Amount=- | " +
                "Inner[3,3] Candidate=- Amount=- | Inner[3,4] Candidate=- Amount=7",
            engines.appliedExtent(
                reverseOrderSource(),
                nested(2, 1).field(CANDIDATE, "4", 1, 2, 1, 1),
            ),
            "the sentinel sits under the outer row the padding would otherwise synthesize empty, so a " +
                "parent-global prefix or a re-addressed row would move or lose it",
        )
    }

    @Test fun anErroredActionValidatesItsAddressAndCreatesNoRow() {
        assertEquals(
            listOf("ERRORED|/Order[1]/Outer[3]/Inner[4]/Amount"),
            engines.sourceClassification(erroredSource(), SeparateDestinationDepthFixtures.AMOUNT),
            "the out-of-range result really is source-classified ERRORED at the nested address",
        )
        assertEquals(
            listOf("ERRORED|Order/Outer[3]/Inner[4]/Amount"),
            engines.interpreterSourceClassification(erroredSource(), SeparateDestinationDepthFixtures.AMOUNT),
            "and so does the standalone route, in its own repetition-one-eliding dialect — without this " +
                "read its empty applied extent below would be consistent with minting no action at all",
        )
        assertEquals(
            "Order=1 | Outer=0",
            engines.appliedExtent(erroredSource(), nested(0, 0)),
            "an ERRORED action creates neither the outer nor the inner row",
        )
    }

    @Test fun aLaterValidationOverTheAppliedDocumentEnumeratesTheInstantiatedNestedRows() {
        assertEquals(
            LATER_VALIDATION_EXTENT,
            engines.appliedExtent(laterValidationSource(), nested(0, 0)),
            "the applied topology the validation below runs over",
        )
        assertEquals(
            listOf(
                "$CODE|VALUE_ERROR|/Order[1]/Outer[1]/Inner[2]/Amount",
                "$CODE|VALUE_ERROR|/Order[1]/Outer[3]/Inner[4]/Amount",
            ),
            engines.kernelLaterValidation(laterValidationSource(), nested(0, 0)),
            "the rule reports at exactly the two instantiated nested rows; the padded empty rows do not fire",
        )
        assertEquals(
            listOf(
                "$CODE|VALUE|Order/Outer/Inner[2]/Amount",
                "$CODE|VALUE|Order/Outer[3]/Inner[4]/Amount",
            ),
            engines.interpreterLaterValidation(laterValidationSource(), nested(0, 0)),
            "the standalone route reports the same two semantic addresses in A12's canonical dialect, which " +
                "omits a repetition of 1 — so the outer 1 finding carries no outer bracket and the outer 3 one " +
                "does, which is itself what shows the two sit at different addresses",
        )
    }

    private fun nested(outerRows: Int, innerRows: Int): RuntimeLaws.Doc =
        RuntimeLaws.doc().group(ROOT, 1).also { doc ->
            for (outer in 1..outerRows) {
                doc.group(OUTER, 1, outer)
                for (inner in 1..innerRows) doc.group(INNER, 1, outer, inner)
            }
        }

    private companion object {
        const val ROOT = SeparateDestinationDepthFixtures.ROOT
        const val OUTER = SeparateDestinationDepthFixtures.OUTER
        const val INNER = SeparateDestinationDepthFixtures.INNER
        const val CANDIDATE = SeparateDestinationDepthFixtures.NESTED_CANDIDATE
        const val AMOUNT = SeparateDestinationDepthFixtures.NESTED_AMOUNT
        const val CODE = SeparateDestinationDepthFixtures.AMOUNT_CODE
        val CHAIN = listOf("Order", "Outer", "Inner")
        val CELLS = listOf(
            SeparateDestinationDepthFixtures.CANDIDATE, SeparateDestinationDepthFixtures.AMOUNT,
        )

        const val REVERSE_ORDER_EXTENT =
            "Order=1 | Outer=3 | Outer[1] Inner=2 | " +
                "Inner[1,1] Candidate=- Amount=- | Inner[1,2] Candidate=- Amount=() | " +
                "Outer[2] Inner=0 | " +
                "Outer[3] Inner=4 | Inner[3,1] Candidate=- Amount=- | Inner[3,2] Candidate=- Amount=- | " +
                "Inner[3,3] Candidate=- Amount=- | Inner[3,4] Candidate=- Amount=7"
        const val FORWARD_ORDER_EXTENT =
            "Order=1 | Outer=3 | Outer[1] Inner=2 | " +
                "Inner[1,1] Candidate=- Amount=- | Inner[1,2] Candidate=- Amount=7 | " +
                "Outer[2] Inner=0 | " +
                "Outer[3] Inner=4 | Inner[3,1] Candidate=- Amount=- | Inner[3,2] Candidate=- Amount=- | " +
                "Inner[3,3] Candidate=- Amount=- | Inner[3,4] Candidate=- Amount=()"
        const val LATER_VALIDATION_EXTENT =
            "Order=1 | Outer=3 | Outer[1] Inner=2 | " +
                "Inner[1,1] Candidate=- Amount=- | Inner[1,2] Candidate=- Amount=5 | " +
                "Outer[2] Inner=0 | " +
                "Outer[3] Inner=4 | Inner[3,1] Candidate=- Amount=- | Inner[3,2] Candidate=- Amount=- | " +
                "Inner[3,3] Candidate=- Amount=- | Inner[3,4] Candidate=- Amount=7"
    }
}
