package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * The typed peer of {@code validate.laws.DateRangeOverlapParameterLawsTest}:
 * {@code AtLeastOneDateRangeOverlaps}' first slot takes exactly one UNSTARRED date-range field.
 *
 * <p>Intrinsic — starredness is visible from the operand — so it sits in
 * {@link FieldListEntityListCondition}'s constructor, not on {@link BoundModel} (API-FINDINGS AF148).
 */
class DateRangeOverlapSlotGateTest {

    private static final BoundModel M = BoundModel.ofJson(
            DocumentModelDraft.named("range-overlap-slot").locale("en_US")
                    .rootGroup("Cover", c -> c
                            .field("Ref", FieldKind.STRING)
                            .dateRangeField("WindowA", r -> r.format("yyyy-MM-dd").separator("/"))
                            .dateRangeField("WindowB", r -> r.format("yyyy-MM-dd").separator("/"))
                            .group("Rows", rows -> rows.repeatable(20).indexField("Key")
                                    .field("Key", FieldKind.STRING)
                                    .dateRangeField("RowWindow", r -> r.format("yyyy-MM-dd").separator("/"))))
                    .build().toJson());

    @Test
    void anUnstarredSingleSlotIsAdmitted() {
        assertDoesNotThrow(() -> Dsl.atLeastOneDateRangeOverlaps(
                M.field("/Cover/WindowA"), M.flatten("/Cover/Rows*/RowWindow")));
    }

    @Test
    void aStarredSingleSlotIsRefused() {
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> Dsl.atLeastOneDateRangeOverlaps(
                        M.flatten("/Cover/Rows*/RowWindow"), M.field("/Cover/WindowA")));
        assertTrue(thrown.getMessage().contains("MVK_INVALID_PARAMETER_FOR_DATE_RANGE_COMPARISON"),
                thrown::getMessage);
        assertTrue(thrown.getMessage().contains("/Cover/Rows/RowWindow"), thrown::getMessage);
    }

    /**
     * The measured limit of the rule: a starred range in the operand LIST is admitted, so the gate must not
     * generalize to every range position. This row is what stops it being widened.
     */
    @Test
    void aStarredRangeInTheOperandListIsAdmitted() {
        assertDoesNotThrow(() -> Dsl.atLeastOneDateRangeOverlaps(
                M.field("/Cover/WindowA"), M.field("/Cover/WindowB"),
                M.flatten("/Cover/Rows*/RowWindow")));
        assertDoesNotThrow(() -> Dsl.dateRangesOverlap(
                M.field("/Cover/WindowA"), M.flatten("/Cover/Rows*/RowWindow")));
    }
}
