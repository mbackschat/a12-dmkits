package io.github.mbackschat.a12.dm.rulekit.dsl.operators;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldListRef;

/** The kernel-measured starred-level boundary of {@code SumOfProducts}. */
class SumOfProductsConditionTest {

    private static final BoundModel MODEL = BoundModel.ofJson(Fixtures.projectJson());
    private static final FieldListRef LOWEST_STAR =
            MODEL.flatten("/Project/Milestones/Tasks*/Effort");
    private static final FieldListRef MULTI_STAR =
            MODEL.flatten("/Project/Milestones*/Tasks*/Effort");

    @Test
    void acceptsAStarOnlyAtTheLowestRepeatableLevel() {
        assertDoesNotThrow(() -> Dsl.sumOfProducts(LOWEST_STAR, LOWEST_STAR));
    }

    @Test
    void rejectsAMultiStarOperandWithTheKernelCode() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> Dsl.sumOfProducts(MULTI_STAR, MULTI_STAR));

        assertTrue(failure.getMessage().contains("MVK_WILDCARD_ONLY_AT_LOWEST_LEVEL_ALLOWED"),
                failure::getMessage);
    }

    @Test
    void rejectsPlainFieldsBecauseTheOperationNeedsARepeatableLevel() {
        BoundModel order = Fixtures.order();
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> Dsl.sumOfProducts(
                        order.field("/Order/Quantity"),
                        order.field("/Order/BackorderQuantity")));

        assertTrue(failure.getMessage().contains("MVK_REPEATABLE_LEVEL_REQUIRED"), failure::getMessage);
    }

    @Test
    void rejectsOperandsFromDifferentRepeatableGroups() {
        FieldListRef milestones = MODEL.flatten("/Project/Milestones*/Budget");
        FieldListRef tasks = MODEL.flatten("/Project/Milestones/Tasks*/Effort");
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> Dsl.sumOfProducts(milestones, tasks));

        assertTrue(failure.getMessage().contains("MVK_DIFFERENT_GROUPS"), failure::getMessage);
    }

    @Test
    void rejectsAFilteredOperand() {
        FieldListRef filtered = LOWEST_STAR.having(MODEL.num("/Project/Milestones/Tasks/Effort").gt(0));
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> Dsl.sumOfProducts(filtered, LOWEST_STAR));

        assertTrue(failure.getMessage().contains("MVK_WILDCARD_AT_LOWEST_LEVEL_REQUIRED"), failure::getMessage);
    }
}
