package io.github.mbackschat.a12.dm.rulekit.dsl.operators;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.ModelDrafts;
import io.github.mbackschat.a12.dm.core.KernelCodeRefusal;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldListRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldOperand;

/**
 * The kernel-measured operand-pair boundary of {@code SumOfProducts}, as the authoring surface reports it.
 * Every row here has a kernel measurement of the same shape in {@code SumOfProductsLawsTest}; the point of
 * this test is that the code we QUOTE is the one the kernel would emit for that pair, which is why the whole
 * matrix is asserted at once rather than one refusal at a time. A pair whose code depends on the rule it will
 * sit in is refused uncoded: an operand left unstarred on a repeatable level draws {@code MVK_NO_WILDCARD} only
 * where the rule does not bind that level ({@code adapter.laws.CheckerSumOfProductsBoundOperandAgreementTest}).
 */
class SumOfProductsConditionTest {

    private static final BoundModel PROJECT = BoundModel.ofJson(Fixtures.projectJson());
    private static final BoundModel BASKET = BoundModel.ofJson(ModelDrafts.basket().toJson());
    private static final BoundModel ORDER = Fixtures.order();

    private static final FieldListRef QTY = BASKET.flatten("/Basket/Lines*/Qty");
    private static final FieldListRef PACKED_WEIGHT = BASKET.flatten("/Basket/Lines*/Packaging/Weight");
    private static final FieldListRef VOUCHER = BASKET.flatten("/Basket/Vouchers*/Amount");

    @Test
    void acceptsTwoParallelListsOverOneOwningGroup() {
        assertDoesNotThrow(() -> Dsl.sumOfProducts(QTY, QTY));
        assertDoesNotThrow(() -> Dsl.sumOfProducts(PACKED_WEIGHT, PACKED_WEIGHT));
    }

    @Test
    void quotesTheKernelCodeTheOperandPairWouldActuallyProduce() {
        List<String> readings = List.of(
                reading("two lists, different owning group", QTY, PACKED_WEIGHT),
                reading("two lists, parallel groups", QTY, VOUCHER),
                reading("plain field inside the starred group", QTY, BASKET.field("/Basket/Lines/Packaging/Weight")),
                reading("plain field outside the starred group", QTY, BASKET.field("/Basket/Fees/Handling")),
                reading("descendant list under an unwildcarded level",
                        PROJECT.flatten("/Project/Milestones*/Budget"),
                        PROJECT.flatten("/Project/Milestones/Tasks*/Effort")),
                reading("two plain fields inside a repeatable group",
                        BASKET.field("/Basket/Lines/Qty"), BASKET.field("/Basket/Lines/Packaging/Weight")),
                reading("no repeatable level at all",
                        ORDER.field("/Order/Quantity"), ORDER.field("/Order/BackorderQuantity")),
                // Both operands plain AND in different owning groups. The row above cannot reach this: its
                // two fields share `/Order`, so "no repeatable level" and "no repeatable level and the same
                // owning group" are one thing on it — and the kernel draws the owning-group class here
                // (`validate.laws.SumOfProductsLawsTest`, measured 2026-09-14).
                reading("no repeatable level, different owning groups",
                        BASKET.field("/Basket/Total"), BASKET.field("/Basket/Fees/Handling")));

        assertEquals(
                List.of(
                        "two lists, different owning group -> MVK_DIFFERENT_GROUPS",
                        "two lists, parallel groups -> MVK_DIFFERENT_GROUPS",
                        "plain field inside the starred group -> uncoded",
                        "plain field outside the starred group -> MVK_DIFFERENT_GROUPS",
                        "descendant list under an unwildcarded level -> uncoded",
                        "two plain fields inside a repeatable group -> uncoded",
                        "no repeatable level at all -> MVK_REPEATABLE_LEVEL_REQUIRED",
                        "no repeatable level, different owning groups -> MVK_DIFFERENT_GROUPS"),
                readings,
                "each refusal must quote the code the kernel emits for that exact pair");
    }

    @Test
    void quotesTheSameCodeWhicheverSideCarriesTheDefect() {
        assertEquals(
                reading("x", QTY, BASKET.field("/Basket/Lines/Packaging/Weight")).replace("x -> ", ""),
                reading("x", BASKET.field("/Basket/Lines/Packaging/Weight"), QTY).replace("x -> ", ""),
                "the pair is unordered, so its code must be too");
    }

    @Test
    void rejectsAMultiStarOperandWithTheKernelCode() {
        FieldListRef multiStar = PROJECT.flatten("/Project/Milestones*/Tasks*/Effort");

        assertTrue(message(multiStar, multiStar).contains("MVK_WILDCARD_ONLY_AT_LOWEST_LEVEL_ALLOWED"),
                () -> message(multiStar, multiStar));
    }

    @Test
    void rejectsAFilteredOperandOnEitherSide() {
        FieldListRef filtered = QTY.having(BASKET.num("/Basket/Lines/Qty").gt(0));

        assertTrue(message(filtered, QTY).contains("MVK_WILDCARD_AT_LOWEST_LEVEL_REQUIRED"),
                () -> message(filtered, QTY));
        assertTrue(message(QTY, filtered).contains("MVK_WILDCARD_AT_LOWEST_LEVEL_REQUIRED"),
                () -> message(QTY, filtered));
    }

    private static String reading(String label, FieldOperand left, FieldOperand right) {
        IllegalArgumentException refusal = refusal(left, right);
        return label + " -> " + (refusal instanceof KernelCodeRefusal coded ? coded.code().name() : "uncoded");
    }

    private static String message(FieldOperand left, FieldOperand right) {
        return refusal(left, right).getMessage();
    }

    private static IllegalArgumentException refusal(FieldOperand left, FieldOperand right) {
        return assertThrows(IllegalArgumentException.class, () -> Dsl.sumOfProducts(left, right));
    }
}
