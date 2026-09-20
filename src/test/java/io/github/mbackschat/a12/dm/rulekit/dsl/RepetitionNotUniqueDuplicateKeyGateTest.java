package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;

/**
 * The typed twin of {@code validate.laws.RepetitionNotUniqueKeyPathLawsTest}'s repeated-key row, and of
 * {@code DuplicateOverlapCarrierSweepLawsTest}'s {@code RNU | repeated key} cell: the kernel answers
 * {@code MVK_DUPLICATE_PARAM1} for {@code RepetitionNotUnique(Sku, Sku)}, so the typed surface refuses it
 * rather than rendering a condition the kernel will reject.
 *
 * <p>This carrier needs only the EXACT-duplicate arm, and that is a property of its signature rather than a
 * scoping choice: {@code RepetitionNotUniqueCondition} takes {@code List<FieldRef>}, so a star is erased by the
 * type (no {@link FieldListRef} arm) and there is no category projection or semantic index to fold into the
 * key. Containment cannot arise either — a key is a field, and no entity has a field's path as a strict prefix.
 * The identity is therefore the path plus the {@code $} outer-iteration flag, which distinguishes two genuinely
 * different operands ({@code $X} reads the outer row and {@code X} the current one).
 */
class RepetitionNotUniqueDuplicateKeyGateTest {

    private static final BoundModel ORDER = Fixtures.order();

    @Test
    void theSameKeyTwiceIsRefusedWithTheKernelsOwnCode() {
        FieldRef sku = ORDER.field("/Order/Items/Sku");
        var refusal = assertThrows(IllegalArgumentException.class,
                () -> Dsl.repetitionNotUnique(sku, sku));
        assertTrue(refusal.getMessage().contains("MVK_DUPLICATE_PARAM1"),
                () -> "expected the kernel's own code in: " + refusal.getMessage());
        assertTrue(refusal.getMessage().contains("Sku"),
                () -> "the refusal must name the repeated key: " + refusal.getMessage());
    }

    /** The control, one variable apart: two DISTINCT keys in the same group compose a legal composite key. */
    @Test
    void twoDistinctKeysAreAdmitted() {
        assertDoesNotThrow(() -> Dsl.repetitionNotUnique(
                ORDER.field("/Order/Items/Sku"), ORDER.field("/Order/Items/Count")));
    }

    /** A single key is the ordinary shape and stays admitted — the gate must not fire on list length. */
    @Test
    void oneKeyIsAdmitted() {
        assertDoesNotThrow(() -> Dsl.repetitionNotUnique(ORDER.field("/Order/Items/Sku")));
    }

    /**
     * A correlated key beside its uncorrelated twin is two operands, not one, so the gate stays silent.
     *
     * <p>This is a claim about OUR gate's leniency, deliberately not about the kernel's verdict: at every locus
     * reachable here the outer-iteration gate refuses the shape before the duplicate arm speaks
     * ({@code MVK_INVALID_OUTER_ITERATION}, measured in {@code DuplicateOverlapCarrierSweepLawsTest}). Dropping
     * the flag from the identity would make the gate STRICTER than its measured basis, which is the one
     * direction a client-side refusal must never take, so this row exists to fail if it is dropped.
     */
    @Test
    void aCorrelatedKeyIsNotItsUncorrelatedTwin() {
        FieldRef sku = ORDER.field("/Order/Items/Sku");
        assertDoesNotThrow(() -> Dsl.repetitionNotUnique(sku, sku.outer()));
    }
}
