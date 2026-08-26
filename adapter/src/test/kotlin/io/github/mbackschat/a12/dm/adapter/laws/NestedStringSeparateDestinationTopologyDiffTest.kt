package io.github.mbackschat.a12.dm.adapter.laws

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * a12-kernel-lean **SPEC-2026-08-26-03** — the closing corner of the separate-destination topology family: the
 * parent-scoped inner prefix measured over a Number target
 * ([NestedSeparateDestinationTopologyDiffTest]) applied to a String target.
 *
 * Depth and target family are the two axes this family varies, and the other three cells are locked
 * ([SeparateDestinationTopologyDiffTest] one-level Number, [StringSeparateDestinationTopologyDiffTest]
 * one-level String, the nested Number sibling). Only this cell can still hide an interaction — a rule that is
 * parent-scoped for numbers and parent-global for strings would survive every other law in the family.
 *
 * The cases are the nested Number ones with the target family swapped and nothing else moved: the CLEARED /
 * changed-VALUE pair at outer 1 / inner 2 and outer 3 / inner 4, the same pair reversed, a nested destination
 * sentinel under the outer row the padding would otherwise synthesize empty, an ERRORED action that must create
 * neither level, and a later validation over the applied document with no second computation.
 *
 * PER_CLASS so the kernel's Java codegen and the standalone `PreparedModel` are built ONCE for the whole class
 * rather than once per case: `:adapter:test` carries timing-sensitive floors, and a class that recompiles a
 * kernel validator per method is the documented way to inflate a perf lane it never touches.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NestedStringSeparateDestinationTopologyDiffTest {

    private val model = SeparateDestinationDepthFixtures.nestedStringModel()
    private val engines = SeparateDestinationEngines(model, CHAIN, CELLS)

    /** CLEARED at outer 1 / inner 2, changed VALUE at outer 3 / inner 4. */
    private fun reverseOrderSource() = nested(3, 4)
        .field(LABEL, "X", 1, 1, 2, 1)
        .field(SOURCE, "A", 1, 3, 4, 1)

    /** The same two nested addresses with the action classes swapped. */
    private fun forwardOrderSource() = nested(3, 4)
        .field(SOURCE, "A", 1, 1, 2, 1)
        .field(LABEL, "X", 1, 3, 4, 1)

    /** Two VALUE actions at the same two addresses — the later-validation input. */
    private fun laterValidationSource() = nested(3, 4)
        .field(SOURCE, "A", 1, 1, 2, 1)
        .field(SOURCE, "CCC", 1, 3, 4, 1)

    /** A source longer than the target's declared maximum at outer 3 / inner 4. */
    private fun erroredSource() = nested(3, 4).field(SOURCE, "TOOLONG", 1, 3, 4, 1)

    @Test fun theInnerStringPrefixIsScopedToItsOwnOuterRow() {
        assertEquals(
            REVERSE_ORDER_EXTENT,
            engines.appliedExtent(reverseOrderSource(), nested(0, 0)),
            "outer 3's action pads inner rows 1-3 under OUTER 3 ONLY; synthetic outer 2 owns no inner row, " +
                "exactly as the nested Number sibling measures",
        )
    }

    @Test fun theNestedStringTopologyIsAddressedRatherThanAppendedInActionOrder() {
        assertEquals(
            FORWARD_ORDER_EXTENT,
            engines.appliedExtent(forwardOrderSource(), nested(0, 0)),
            "swapping which nested address carries CLEARED and which carries VALUE keeps both coordinates",
        )
    }

    @Test fun anExistingNestedDestinationCellKeepsItsExactAddress() {
        assertEquals(
            "Order=1 | Outer=3 | Outer[1] Inner=2 | " +
                "Inner[1,1] Source=- Label=- | Inner[1,2] Source=- Label=() | " +
                "Outer[2] Inner=1 | Inner[2,1] Source=KEEP Label=- | " +
                "Outer[3] Inner=4 | Inner[3,1] Source=- Label=- | Inner[3,2] Source=- Label=- | " +
                "Inner[3,3] Source=- Label=- | Inner[3,4] Source=- Label=A",
            engines.appliedExtent(
                reverseOrderSource(),
                nested(2, 1).field(SOURCE, "KEEP", 1, 2, 1, 1),
            ),
            "the sentinel sits under the outer row the padding would otherwise synthesize empty",
        )
    }

    @Test fun anErroredNestedStringActionValidatesItsAddressAndCreatesNoRow() {
        assertEquals(
            listOf("ERRORED|/Order[1]/Outer[3]/Inner[4]/Label"),
            engines.sourceClassification(erroredSource(), SeparateDestinationDepthFixtures.LABEL),
            "the over-length copy really is source-classified ERRORED at the nested address",
        )
        assertEquals(
            listOf("ERRORED|Order/Outer[3]/Inner[4]/Label"),
            engines.interpreterSourceClassification(erroredSource(), SeparateDestinationDepthFixtures.LABEL),
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
                "$CODE|VALUE_ERROR|/Order[1]/Outer[1]/Inner[2]/Label",
                "$CODE|VALUE_ERROR|/Order[1]/Outer[3]/Inner[4]/Label",
            ),
            engines.kernelLaterValidation(laterValidationSource(), nested(0, 0)),
            "the rule reports at exactly the two instantiated nested rows; the padded empty rows do not fire",
        )
        assertEquals(
            listOf(
                "$CODE|VALUE|Order/Outer/Inner[2]/Label",
                "$CODE|VALUE|Order/Outer[3]/Inner[4]/Label",
            ),
            engines.interpreterLaterValidation(laterValidationSource(), nested(0, 0)),
            "the standalone route reports the same two semantic addresses in A12's canonical dialect",
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
        const val SOURCE = SeparateDestinationDepthFixtures.NESTED_SOURCE
        const val LABEL = SeparateDestinationDepthFixtures.NESTED_LABEL
        const val CODE = SeparateDestinationDepthFixtures.LABEL_CODE
        val CHAIN = listOf("Order", "Outer", "Inner")
        val CELLS = listOf(
            SeparateDestinationDepthFixtures.SOURCE, SeparateDestinationDepthFixtures.LABEL,
        )

        const val REVERSE_ORDER_EXTENT =
            "Order=1 | Outer=3 | Outer[1] Inner=2 | " +
                "Inner[1,1] Source=- Label=- | Inner[1,2] Source=- Label=() | " +
                "Outer[2] Inner=0 | " +
                "Outer[3] Inner=4 | Inner[3,1] Source=- Label=- | Inner[3,2] Source=- Label=- | " +
                "Inner[3,3] Source=- Label=- | Inner[3,4] Source=- Label=A"
        const val FORWARD_ORDER_EXTENT =
            "Order=1 | Outer=3 | Outer[1] Inner=2 | " +
                "Inner[1,1] Source=- Label=- | Inner[1,2] Source=- Label=A | " +
                "Outer[2] Inner=0 | " +
                "Outer[3] Inner=4 | Inner[3,1] Source=- Label=- | Inner[3,2] Source=- Label=- | " +
                "Inner[3,3] Source=- Label=- | Inner[3,4] Source=- Label=()"
        const val LATER_VALIDATION_EXTENT =
            "Order=1 | Outer=3 | Outer[1] Inner=2 | " +
                "Inner[1,1] Source=- Label=- | Inner[1,2] Source=- Label=A | " +
                "Outer[2] Inner=0 | " +
                "Outer[3] Inner=4 | Inner[3,1] Source=- Label=- | Inner[3,2] Source=- Label=- | " +
                "Inner[3,3] Source=- Label=- | Inner[3,4] Source=- Label=CCC"
    }
}
