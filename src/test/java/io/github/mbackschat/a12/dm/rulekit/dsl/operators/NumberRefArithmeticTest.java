package io.github.mbackschat.a12.dm.rulekit.dsl.operators;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Calculation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberRef;

/**
 * Arithmetic reads off a kind-typed {@link NumberRef} directly, the way comparison already does (DEV-API-GAPS
 * DG60).
 *
 * <p><b>The friction this removes, measured from the consumer's seat.</b> {@code NumberRef} carried every
 * comparison and no arithmetic, so the two arms of the same ref diverged: a condition was
 * {@code QUANTITY.gt(10)} while a calculation over the same field was
 * {@code QUANTITY.asNumber().times(UNIT_PRICE.asNumber()).expression()} — out to {@code NumberExpr} for each
 * operand and back through {@code expression()} because {@code Computation.Builder.calc} takes a
 * {@link Calculation}. A kind-typed ref that must be converted before it can be used is exactly the friction
 * the condition arm does not impose, and CLAUDE.md's dogfood rule makes that gap the bug.
 *
 * <p><b>The oracle is render-equality with the long form.</b> Each row asserts the sugar produces the same text
 * as the {@code asNumber()} spelling it replaces, which is what makes this sugar rather than a second
 * lowering — a new node or a different fold would show up immediately as different output. The long form is
 * already kernel-locked by the arithmetic laws, so equality to it transfers that evidence.
 */
class NumberRefArithmeticTest {

    private static final String ORDER = DocumentModelDraft.named("order").locale("en_US")
            .rootGroup("Order", o -> o
                    .numberField("Quantity", n -> n.fractionDigits(0))
                    .numberField("UnitPrice", n -> n.fractionDigits(2))
                    .numberField("Total", n -> n.fractionDigits(2)))
            .build().toJson();

    private static final BoundModel M = BoundModel.ofJson(ORDER);
    private static final NumberRef QUANTITY = M.num("/Order/Quantity");
    private static final NumberRef UNIT_PRICE = M.num("/Order/UnitPrice");

    private static String render(Condition condition) {
        return new DslRenderer().render(condition);
    }

    private static String render(Calculation calculation) {
        return new DslRenderer().render(calculation);
    }

    @Test
    void everyOperatorOverAnotherRefMatchesTheAsNumberSpelling() {
        assertEquals(render(QUANTITY.asNumber().plus(UNIT_PRICE.asNumber()).gt(0)),
                render(QUANTITY.plus(UNIT_PRICE).gt(0)));
        assertEquals(render(QUANTITY.asNumber().minus(UNIT_PRICE.asNumber()).gt(0)),
                render(QUANTITY.minus(UNIT_PRICE).gt(0)));
        assertEquals(render(QUANTITY.asNumber().times(UNIT_PRICE.asNumber()).gt(0)),
                render(QUANTITY.times(UNIT_PRICE).gt(0)));
        assertEquals(render(QUANTITY.asNumber().dividedBy(UNIT_PRICE.asNumber()).gt(0)),
                render(QUANTITY.dividedBy(UNIT_PRICE).gt(0)));
        assertEquals(render(QUANTITY.asNumber().toThePowerOf(UNIT_PRICE.asNumber()).gt(0)),
                render(QUANTITY.toThePowerOf(UNIT_PRICE).gt(0)));
    }

    /** The rendered text is asserted once outright, so the row above cannot pass by both sides being wrong. */
    @Test
    void theRenderedTextIsTheKernelsOwnArithmetic() {
        assertEquals("[/Order/Quantity] * [/Order/UnitPrice] > 0", render(QUANTITY.times(UNIT_PRICE).gt(0)));
    }

    /**
     * The two types compose in BOTH directions, which the arithmetic arm is what exposed.
     *
     * <p>{@code ref.gt(expr)} existed and {@code expr.gt(ref)} did not, so an authored comparison compiled or
     * not depending on which side its operands happened to fall — invisible until arithmetic made an
     * expression the natural left operand. Found writing this feature's own API-GUIDE snippet, which is the
     * dogfood rule working as intended.
     */
    @Test
    void anExpressionComparesAgainstARefFromEitherSide() {
        assertEquals(render(QUANTITY.times(2).gt(UNIT_PRICE.asNumber())), render(QUANTITY.times(2).gt(UNIT_PRICE)));
        assertEquals("[/Order/Quantity] * 2 > [/Order/UnitPrice]", render(QUANTITY.times(2).gt(UNIT_PRICE)));
    }

    /**
     * Arithmetic composes from either side too: an expression takes a ref as its right operand, as its comparisons
     * already did. Found 2026-10-30 writing a joined-reference differential, where {@code Sum(…) + [field]} would
     * not compile with the aggregate on the left.
     */
    @Test
    void anExpressionTakesARefAsItsRightOperand() {
        var left = QUANTITY.times(2);
        assertEquals(render(left.plus(UNIT_PRICE.asNumber()).gt(0)), render(left.plus(UNIT_PRICE).gt(0)));
        assertEquals(render(left.minus(UNIT_PRICE.asNumber()).gt(0)), render(left.minus(UNIT_PRICE).gt(0)));
        assertEquals(render(left.times(UNIT_PRICE.asNumber()).gt(0)), render(left.times(UNIT_PRICE).gt(0)));
        assertEquals(render(left.dividedBy(UNIT_PRICE.asNumber()).gt(0)), render(left.dividedBy(UNIT_PRICE).gt(0)));
        assertEquals(render(left.toThePowerOf(UNIT_PRICE.asNumber()).gt(0)),
                render(left.toThePowerOf(UNIT_PRICE).gt(0)));
        assertEquals("[/Order/Quantity] * 2 + [/Order/UnitPrice] > 0", render(left.plus(UNIT_PRICE).gt(0)));
    }

    @Test
    void anExpressionOperandAndALiteralReachTheSameNodes() {
        assertEquals(render(QUANTITY.asNumber().times(UNIT_PRICE.asNumber().plus(1)).gt(0)),
                render(QUANTITY.times(UNIT_PRICE.asNumber().plus(1)).gt(0)));
        assertEquals(render(QUANTITY.asNumber().times(2).gt(0)), render(QUANTITY.times(2).gt(0)));
        assertEquals(render(QUANTITY.asNumber().dividedBy(2).gt(0)), render(QUANTITY.dividedBy(2).gt(0)));
        assertEquals(render(QUANTITY.asNumber().toThePowerOf(2).gt(0)), render(QUANTITY.toThePowerOf(2).gt(0)));
    }

    /**
     * The calculation arm accepts the expression directly, so the trailing {@code expression()} disappears.
     *
     * <p>That call was the other half of the gap: {@code calc} took only a {@link Calculation}, so every typed
     * computation ended in a conversion whose sole purpose was to satisfy the signature.
     */
    @Test
    void theCalculationArmTakesTheExpressionWithoutAConversion() {
        assertEquals(render(QUANTITY.times(UNIT_PRICE).expression()),
                render(QUANTITY.asNumber().times(UNIT_PRICE.asNumber()).expression()));
        assertEquals("[/Order/Quantity] * [/Order/UnitPrice]", render(QUANTITY.times(UNIT_PRICE).expression()));
    }
}
