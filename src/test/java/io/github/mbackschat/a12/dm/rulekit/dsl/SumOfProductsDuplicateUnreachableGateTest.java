package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;

/**
 * Why {@code SumOfProducts} carries NO duplicate gate, expressed as a test rather than as a comment.
 *
 * <p>The kernel routes {@code SumOfProducts} through the same {@code getRefList} fan-in as {@code Sum}
 * ({@code CheckFeldListenOpImpl}), so the duplicate arm does reach it — {@code SumOfProducts(Quantity, Quantity)}
 * is {@code MVK_DUPLICATE_PARAM1}, and it wins over the level requirement because the duplicate check is the
 * first substantive check in that routine. That makes an absent client gate look like an oversight. It is not,
 * for a reason that only holds while the two rows below do:
 *
 * <ul>
 *   <li><b>unstarred and identical</b> — our own parallelism gate already refuses it, so the shape is not
 *       renderable and the kernel is never asked. We report {@code MVK_REPEATABLE_LEVEL_REQUIRED} where the
 *       kernel would report {@code MVK_DUPLICATE_PARAM1}: a disagreement on the CODE, on input both refuse;</li>
 *   <li><b>starred and identical</b> — admitted by the kernel, because the exact-duplicate arm skips a
 *       wildcarded operand ({@code ref.hasWildCards() && !parser.isHModel()}). So the one shape that clears
 *       our gate is legal, and a duplicate refusal here would be an OVER-rejection.</li>
 * </ul>
 *
 * <p>Between them the duplicate arm has no reachable input on this carrier. If the first row ever stops
 * refusing, that reasoning collapses and this test fails — which is the point of it existing.
 * Kernel rows: {@code validate.laws.DuplicateOverlapCarrierSweepLawsTest}.
 */
class SumOfProductsDuplicateUnreachableGateTest {

    private static final BoundModel ORDER = Fixtures.order();

    @Test
    void theUnstarredIdenticalPairIsAlreadyRefusedByTheParallelismGate() {
        FieldRef qty = ORDER.field("/Order/Quantity");
        var refusal = assertThrows(IllegalArgumentException.class, () -> Dsl.sumOfProducts(qty, qty),
                "if this stops throwing, SumOfProducts needs its own duplicate gate");
        assertTrue(refusal.getMessage().contains("MVK_REPEATABLE_LEVEL_REQUIRED"),
                () -> "expected the parallelism refusal to be what covers this shape: " + refusal.getMessage());
    }

    @Test
    void theStarredIdenticalPairStaysAdmittedBecauseTheKernelAdmitsIt() {
        FieldListRef count = ORDER.flatten("/Order/Items*/Count");
        assertDoesNotThrow(() -> Dsl.sumOfProducts(count, count),
                "the exact-duplicate arm skips a wildcarded operand, so refusing this would over-reject");
    }
}
