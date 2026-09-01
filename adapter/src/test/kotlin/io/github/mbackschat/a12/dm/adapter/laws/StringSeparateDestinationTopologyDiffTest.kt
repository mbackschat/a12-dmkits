package io.github.mbackschat.a12.dm.adapter.laws

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * a12-kernel-lean **SPEC-2026-08-26-02** — the one-level predecessor rule was measured over a NUMBER target
 * (`SeparateDestinationTopologyDiffTest`), and a Number target is exactly the family whose result domain could
 * be doing the work: a numeric computation carries the kernel's own decimal model, so "row 3 exists, rows 1–2
 * are padded" could in principle be a statement about numeric results rather than about application.
 *
 * A String target is the control that separates those accounts, because it shares the application path and
 * nothing else. Every case below is its Number twin with the target family swapped and nothing else moved: the
 * changed-VALUE / CLEARED pair at rows 1 and 3, the same pair with the action classes reversed, an existing
 * row-2 sentinel that must survive, an ERRORED action that must create nothing, and a later validation over the
 * applied document with no second computation.
 *
 * String state is read as three distinct cells — `-` absent, `()` present-but-valueless, and the stored text —
 * so a CLEARED String target cannot be confused with an absent one or with a stored empty string.
 *
 * PER_CLASS so the kernel's Java codegen and the standalone `PreparedModel` are built ONCE for the whole class
 * rather than once per case: `:adapter:test` carries timing-sensitive floors, and a class that recompiles a
 * kernel validator per method is the documented way to inflate a perf lane it never touches.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StringSeparateDestinationTopologyDiffTest {

    private val model = SeparateDestinationDepthFixtures.flatStringModel()
    private val engines = SeparateDestinationEngines(model, CHAIN, CELLS)

    /** A changed VALUE at row 1 and a source-classified CLEARED at row 3. */
    private fun reverseOrderSource() = lines(3)
        .field(SOURCE, "A", 1, 1, 1)
        .field(LABEL, "X", 1, 3, 1)

    /** The same two row addresses with the action classes swapped. */
    private fun forwardOrderSource() = lines(3)
        .field(LABEL, "X", 1, 1, 1)
        .field(SOURCE, "A", 1, 3, 1)

    /** Two VALUE actions at rows 1 and 3 — the later-validation input. */
    private fun laterValidationSource() = lines(3)
        .field(SOURCE, "A", 1, 1, 1)
        .field(SOURCE, "CCC", 1, 3, 1)

    /** A source longer than the target's declared maximum — a source-classified ERRORED action at row 3. */
    private fun erroredSource() = lines(3).field(SOURCE, "TOOLONG", 1, 3, 1)

    @Test fun theAppliedStringTopologyUsesTheCompletePredecessorPrefix() {
        assertEquals(
            listOf(
                "value at 1, cleared at 3 => $REVERSE_ORDER_EXTENT",
                "cleared at 1, value at 3 => $FORWARD_ORDER_EXTENT",
            ),
            listOf(
                "value at 1, cleared at 3 => ${engines.appliedExtent(reverseOrderSource(), lines(0))}",
                "cleared at 1, value at 3 => ${engines.appliedExtent(forwardOrderSource(), lines(0))}",
            ),
            "row 3's action pads rows 1-2, and swapping the action classes keeps both exact row addresses " +
                "rather than appending them in action-class order",
        )
    }

    @Test fun anExistingPredecessorRowKeepsItsNoncomputedSentinelAtItsOwnAddress() {
        assertEquals(
            "Order=1 | Lines=3 | Lines[1] Source=- Label=A | Lines[2] Source=KEEP Label=- | " +
                "Lines[3] Source=- Label=()",
            engines.appliedExtent(reverseOrderSource(), lines(2).field(SOURCE, "KEEP", 1, 2, 1)),
            "the row-2 sentinel must survive the padding that would otherwise synthesize row 2 empty",
        )
    }

    @Test fun anErroredStringActionValidatesItsAddressAndCreatesNoRow() {
        assertEquals(
            listOf("ERRORED|/Order[1]/Lines[3]/Label"),
            engines.sourceClassification(erroredSource(), SeparateDestinationDepthFixtures.LABEL),
            "a copy that overruns the target's declared maximum length really is source-classified ERRORED",
        )
        assertEquals(
            listOf("ERRORED|Order/Lines[3]/Label"),
            engines.interpreterSourceClassification(erroredSource(), SeparateDestinationDepthFixtures.LABEL),
            "and so does the standalone route, in its own repetition-one-eliding dialect — without this " +
                "read its empty applied extent below would be consistent with minting no action at all",
        )
        assertEquals(
            "Order=1 | Lines=0",
            engines.appliedExtent(erroredSource(), lines(0)),
            "an ERRORED String action leaves the row extent empty",
        )
    }

    @Test fun aLaterValidationOverTheAppliedStringDocumentEnumeratesTheInstantiatedRows() {
        assertEquals(
            "Order=1 | Lines=3 | Lines[1] Source=- Label=A | Lines[2] Source=- Label=- | " +
                "Lines[3] Source=- Label=CCC",
            engines.appliedExtent(laterValidationSource(), lines(0)),
            "the applied topology the validation below runs over",
        )
        assertEquals(
            listOf(
                "$CODE|VALUE_ERROR|/Order[1]/Lines[1]/Label",
                "$CODE|VALUE_ERROR|/Order[1]/Lines[3]/Label",
            ),
            engines.kernelLaterValidation(laterValidationSource(), lines(0)),
            "the rule reports at exactly the two instantiated rows; the padded empty row 2 does not fire",
        )
        assertEquals(
            listOf(
                "$CODE|VALUE|Order/Lines/Label",
                "$CODE|VALUE|Order/Lines[3]/Label",
            ),
            engines.interpreterLaterValidation(laterValidationSource(), lines(0)),
            "the standalone route reports the same two rows in A12's canonical dialect, which omits a " +
                "repetition of 1",
        )
    }

    private fun lines(count: Int): RuntimeLaws.Doc = RuntimeLaws.doc().group(ROOT, 1).also { doc ->
        for (row in 1..count) doc.group(LINES, 1, row)
    }

    private companion object {
        const val ROOT = SeparateDestinationDepthFixtures.ROOT
        const val LINES = SeparateDestinationDepthFixtures.LINES
        const val SOURCE = SeparateDestinationDepthFixtures.LINE_SOURCE
        const val LABEL = SeparateDestinationDepthFixtures.LINE_LABEL
        const val CODE = SeparateDestinationDepthFixtures.LABEL_CODE
        val CHAIN = listOf("Order", "Lines")
        val CELLS = listOf(
            SeparateDestinationDepthFixtures.SOURCE, SeparateDestinationDepthFixtures.LABEL,
        )

        const val REVERSE_ORDER_EXTENT =
            "Order=1 | Lines=3 | Lines[1] Source=- Label=A | Lines[2] Source=- Label=- | " +
                "Lines[3] Source=- Label=()"
        const val FORWARD_ORDER_EXTENT =
            "Order=1 | Lines=3 | Lines[1] Source=- Label=() | Lines[2] Source=- Label=- | " +
                "Lines[3] Source=- Label=A"
    }
}
